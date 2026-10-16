using System.Security.Cryptography;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using OfflineSync.Core;
using OfflineSync.Server;
using Xunit;

namespace OfflineSync.IntegrationTests;

public sealed class PostgresFactAttribute : FactAttribute
{
    public PostgresFactAttribute()
    {
        if (string.IsNullOrEmpty(Environment.GetEnvironmentVariable("SYNC_TEST_POSTGRES")))
            Skip = "Set SYNC_TEST_POSTGRES to a dedicated PostgreSQL test database to run durable integration tests.";
    }
}

public sealed class ServerTests
{
    [Fact]
    public void CursorsRejectTamperingCrossWorkspaceAndMalformedInput()
    {
        var codec = new CursorCodec(RandomNumberGenerator.GetBytes(32));
        var workspace = Guid.NewGuid().ToString("D");
        Assert.Equal(42, codec.Decode(workspace, codec.Encode(workspace, 42)));
        Assert.Equal(0, codec.Decode(workspace, null));
        Assert.Throws<ArgumentException>(() => codec.Decode(Guid.NewGuid().ToString("D"), codec.Encode(workspace, 42)));
        Assert.Throws<ArgumentException>(() => codec.Decode(workspace, "not-a-cursor"));
        Assert.Throws<ArgumentException>(() => codec.Decode(workspace, codec.Encode(workspace, 42) + "x"));
    }

    [PostgresFact]
    public async Task ConcurrentReplayAppliesOnceAndSurvivesNewServiceInstance()
    {
        var factory = await FactoryAsync();
        var workspace = Guid.NewGuid().ToString("D");
        var mutation = Create();
        var codec = new CursorCodec(RandomNumberGenerator.GetBytes(32));
        SyncService NewService() => new(factory, new NoReceiptCache(), codec, TimeProvider.System);
        var results = await Task.WhenAll(Enumerable.Range(0, 8).Select(_ => NewService().ApplyAsync(workspace, mutation)));
        Assert.All(results, result => Assert.Equal(1, result.Revision));
        Assert.Single(results.Select(result => WireJson.Canonical(result)).Distinct());
        var replay = await NewService().ApplyAsync(workspace, mutation);
        Assert.Equal(WireJson.Canonical(results[0]), WireJson.Canonical(replay));
        var mismatch = await NewService().ApplyAsync(workspace, mutation with { LocalSequence = 2 });
        Assert.Equal("IDEMPOTENCY_MISMATCH", mismatch.ErrorCode);
        var page = await NewService().PullAsync(workspace, null, 100);
        Assert.Single(page.Changes);
        Assert.False(page.HasMore);
        Assert.Empty((await NewService().PullAsync(workspace, page.NextCursor, 100)).Changes);
    }

    [PostgresFact]
    public async Task HeldWorkspaceTransactionBlocksLaterWriterAndDoesNotCreateSkippedChanges()
    {
        var factory = await FactoryAsync();
        var workspace = Guid.NewGuid().ToString("D");
        var service = new SyncService(factory, new NoReceiptCache(), new(RandomNumberGenerator.GetBytes(32)), TimeProvider.System);
        await service.ApplyAsync(workspace, Create());
        await using var held = await factory.CreateDbContextAsync();
        await using var transaction = await held.Database.BeginTransactionAsync();
        await held.Workspaces.FromSqlInterpolated($"SELECT * FROM workspaces WHERE \"Id\" = {workspace} FOR UPDATE").SingleAsync();
        var laterWrite = service.ApplyAsync(workspace, Create());
        var during = await service.PullAsync(workspace, null, 100);
        Assert.Single(during.Changes);
        await transaction.RollbackAsync();
        await laterWrite;
        var after = await service.PullAsync(workspace, during.NextCursor, 100);
        Assert.Single(after.Changes);
        Assert.Equal(2, after.Changes[0].Revision);
    }

    private static Mutation Create()
    {
        var replica = Guid.NewGuid().ToString("D");
        return new(1, replica, Guid.NewGuid().ToString("D"), 1, Guid.NewGuid().ToString("D"), new()
        {
            Registers = new DocumentDelta().Registers.Add("title",
                new(JsonSerializer.SerializeToElement("Offline task"), new(100, 0, replica)))
        });
    }

    private static async Task<TestFactory> FactoryAsync()
    {
        var factory = new TestFactory(new DbContextOptionsBuilder<SyncDbContext>()
            .UseNpgsql(Environment.GetEnvironmentVariable("SYNC_TEST_POSTGRES")).Options);
        await using var database = await factory.CreateDbContextAsync();
        await database.Database.MigrateAsync();
        return factory;
    }

    private sealed class TestFactory(DbContextOptions<SyncDbContext> options) : IDbContextFactory<SyncDbContext>
    {
        public SyncDbContext CreateDbContext() => new(options);
        public Task<SyncDbContext> CreateDbContextAsync(CancellationToken cancellationToken = default) =>
            Task.FromResult(CreateDbContext());
    }
}