using System.Security.Cryptography;
using System.Text.Json.Serialization;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.EntityFrameworkCore;
using OfflineSync.Api;
using OfflineSync.Server;
using StackExchange.Redis;

var builder = WebApplication.CreateBuilder(args);
builder.Services.AddProblemDetails();
builder.Services.AddOpenApi();
builder.Services.AddRequestDecompression();
builder.Services.AddResponseCompression(options => options.EnableForHttps = true);
builder.WebHost.ConfigureKestrel(options => options.Limits.MaxRequestBodySize = 1_048_576);
builder.Services.ConfigureHttpJsonOptions(options =>
{
    options.SerializerOptions.PropertyNameCaseInsensitive = false;
    options.SerializerOptions.UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow;
    options.SerializerOptions.AllowDuplicateProperties = false;
    options.SerializerOptions.NumberHandling = JsonNumberHandling.Strict;
    options.SerializerOptions.DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull;
    options.SerializerOptions.MaxDepth = 32;
});
builder.Services.AddAuthentication(JwtBearerDefaults.AuthenticationScheme).AddJwtBearer(options =>
{
    options.MapInboundClaims = false;
    options.TokenValidationParameters.ClockSkew = TimeSpan.FromSeconds(30);
    if (!builder.Environment.IsDevelopment())
    {
        options.Authority = builder.Configuration["Authentication:Authority"]
            ?? throw new InvalidOperationException("Authentication:Authority is required outside development.");
        options.Audience = builder.Configuration["Authentication:Audience"]
            ?? throw new InvalidOperationException("Authentication:Audience is required outside development.");
        options.RequireHttpsMetadata = true;
    }
});
builder.Services.AddAuthorization(options => options.AddPolicy("sync", policy => policy
    .RequireAuthenticatedUser().RequireClaim("workspace").RequireClaim("replica")));
builder.Services.AddDbContextFactory<SyncDbContext>(options => options.UseNpgsql(
    builder.Configuration.GetConnectionString("Postgres")
    ?? throw new InvalidOperationException("ConnectionStrings:Postgres is required.")));
builder.Services.AddSingleton(TimeProvider.System);
var signingKey = builder.Configuration["Sync:CursorSigningKey"];
if (signingKey is null && !builder.Environment.IsDevelopment())
    throw new InvalidOperationException("Sync:CursorSigningKey is required outside development.");
builder.Services.AddSingleton(new CursorCodec(signingKey is null ? RandomNumberGenerator.GetBytes(32) : Convert.FromBase64String(signingKey)));
var redis = builder.Configuration.GetConnectionString("Redis");
if (string.IsNullOrWhiteSpace(redis)) builder.Services.AddSingleton<IReceiptCache, NoReceiptCache>();
else
{
    var configuration = ConfigurationOptions.Parse(redis);
    configuration.AbortOnConnectFail = false;
    configuration.ConnectTimeout = 1000;
    configuration.AsyncTimeout = 1000;
    builder.Services.AddSingleton<IConnectionMultiplexer>(_ => ConnectionMultiplexer.Connect(configuration));
    builder.Services.AddSingleton<IReceiptCache, RedisReceiptCache>();
}
builder.Services.AddScoped<SyncService>();

var app = builder.Build();
app.UseExceptionHandler();
app.UseStatusCodePages();
if (!app.Environment.IsDevelopment()) app.UseHttpsRedirection();
app.UseResponseCompression();
app.UseRequestDecompression();
app.UseAuthentication();
app.UseAuthorization();
app.MapGet("/health/live", () => TypedResults.Ok(new { status = "alive" }))
    .WithName("Liveness").WithSummary("Process liveness; does not assert database readiness.");
app.MapGet("/health/ready", async (IDbContextFactory<SyncDbContext> factory, CancellationToken cancellationToken) =>
{
    await using var database = await factory.CreateDbContextAsync(cancellationToken);
    return await database.Database.CanConnectAsync(cancellationToken)
        ? Results.Ok(new { status = "ready" }) : Results.StatusCode(503);
}).WithName("Readiness").WithSummary("Checks PostgreSQL connectivity; Redis is optional.");
app.MapSyncEndpoints();
if (app.Environment.IsDevelopment()) app.MapOpenApi();
if (args.Contains("--migrate", StringComparer.Ordinal))
{
    await using var scope = app.Services.CreateAsyncScope();
    var factory = scope.ServiceProvider.GetRequiredService<IDbContextFactory<SyncDbContext>>();
    await using var database = await factory.CreateDbContextAsync();
    await database.Database.MigrateAsync();
    return;
}
app.Run();

public partial class Program;