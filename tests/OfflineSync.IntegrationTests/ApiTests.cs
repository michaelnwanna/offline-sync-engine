using System.Net;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.Configuration;
using Xunit;

namespace OfflineSync.IntegrationTests;

public sealed class ApiTests
{
    [Fact]
    public async Task LivenessAndOpenApiWorkWithoutDatabaseButSyncRequiresAuthentication()
    {
        await using var factory = new TestApplication();
        using var client = factory.CreateClient();
        Assert.Equal(HttpStatusCode.OK, (await client.GetAsync("/health/live")).StatusCode);
        var schema = await client.GetStringAsync("/openapi/v1.json");
        Assert.Contains("/v1/sync/push", schema);
        Assert.Contains("/v1/sync/pull", schema);
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.GetAsync("/v1/sync/pull")).StatusCode);
        using var content = new StringContent("{}", System.Text.Encoding.UTF8, "application/json");
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.PostAsync("/v1/sync/push", content)).StatusCode);
    }

    private sealed class TestApplication : WebApplicationFactory<Program>
    {
        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            builder.UseEnvironment("Development");
            builder.ConfigureAppConfiguration((_, configuration) => configuration.AddInMemoryCollection(
                new Dictionary<string, string?>
                {
                    ["ConnectionStrings:Postgres"] = "Host=127.0.0.1;Port=1;Database=unused;Username=unused;Password=unused;Timeout=1"
                }));
        }
    }
}