using System.Text;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.IdentityModel.Tokens;
using OfflineSync.Server;

namespace OfflineSync.InteropHost;

internal static class InteropHost
{
    public static async Task Main()
    {
        var connection = Environment.GetEnvironmentVariable("SYNC_TEST_POSTGRES")
            ?? throw new InvalidOperationException("Set SYNC_TEST_POSTGRES to a dedicated test database.");
        await using var factory = new InteropApplication(connection);
        factory.UseKestrel(5087);
        using var client = factory.CreateClient();
        await using (var scope = factory.Services.CreateAsyncScope())
        {
            var databaseFactory = scope.ServiceProvider.GetRequiredService<IDbContextFactory<SyncDbContext>>();
            await using var database = await databaseFactory.CreateDbContextAsync();
            await database.Database.MigrateAsync();
        }
        Console.WriteLine("Interop API ready on http://localhost:5087; test credentials only. Stop with Ctrl+C.");
        await Task.Delay(Timeout.InfiniteTimeSpan);
    }
}

internal sealed class InteropApplication(string connection) : WebApplicationFactory<global::Program>
{
    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        builder.UseEnvironment("Development");
        var root = new DirectoryInfo(AppContext.BaseDirectory);
        while (root is not null && !File.Exists(Path.Combine(root.FullName, "OfflineSyncEngine.slnx")))
            root = root.Parent;
        if (root is null) throw new InvalidOperationException("Run this test host from its repository checkout.");
        builder.UseContentRoot(Path.Combine(root.FullName, "src", "OfflineSync.Api"));
        builder.ConfigureAppConfiguration((_, configuration) => configuration.AddInMemoryCollection(
            new Dictionary<string, string?>
            {
                ["ConnectionStrings:Postgres"] = connection,
                ["ConnectionStrings:Redis"] = "",
                ["Sync:CursorSigningKey"] = Convert.ToBase64String(Encoding.UTF8.GetBytes(
                    "interop-cursor-key-not-for-production-0001")),
            }));
        builder.ConfigureServices(services => services.PostConfigure<JwtBearerOptions>(
            JwtBearerDefaults.AuthenticationScheme, options =>
            {
                options.TokenValidationParameters = new TokenValidationParameters
                {
                    ValidateIssuerSigningKey = true,
                    IssuerSigningKey = new SymmetricSecurityKey(Encoding.UTF8.GetBytes(
                        "interop-jwt-key-not-for-production-00000001")),
                    ValidateIssuer = true,
                    ValidIssuer = "offline-sync-interop",
                    ValidateAudience = true,
                    ValidAudience = "offline-sync-interop",
                    ValidateLifetime = true,
                    ClockSkew = TimeSpan.FromSeconds(5),
                };
            }));
    }
}