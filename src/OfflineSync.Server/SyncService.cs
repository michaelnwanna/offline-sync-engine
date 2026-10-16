using Microsoft.EntityFrameworkCore;
using OfflineSync.Core;

namespace OfflineSync.Server;

public sealed class SyncService(IDbContextFactory<SyncDbContext> factory, IReceiptCache cache,
    CursorCodec cursors, TimeProvider clock)
{
    public async Task<MutationAck> ApplyAsync(string workspaceId, Mutation mutation, CancellationToken cancellationToken = default)
    {
        if (!MutationValidation.IsId(workspaceId) || !MutationValidation.IsId(mutation.ReplicaId)
            || !MutationValidation.IsId(mutation.MutationId)) throw new ArgumentException("INVALID_IDENTITY");
        var hash = WireJson.Hash(mutation);
        var key = $"sync:v1:{workspaceId}:{mutation.ReplicaId}:{mutation.MutationId}";
        var cached = await cache.GetAsync(key, cancellationToken);
        if (cached is not null) return Replay(mutation.MutationId, hash, cached);

        await using var database = await factory.CreateDbContextAsync(cancellationToken);
        await using var transaction = await database.Database.BeginTransactionAsync(cancellationToken);
        await database.Database.ExecuteSqlInterpolatedAsync(
            $"INSERT INTO workspaces (\"Id\", \"Revision\") VALUES ({workspaceId}, 0) ON CONFLICT DO NOTHING", cancellationToken);
        var workspace = await database.Workspaces.FromSqlInterpolated(
            $"SELECT * FROM workspaces WHERE \"Id\" = {workspaceId} FOR UPDATE").SingleAsync(cancellationToken);
        var receipt = await database.Receipts.FindAsync([workspaceId, mutation.ReplicaId, mutation.MutationId], cancellationToken);
        if (receipt is not null)
        {
            var original = new CachedReceipt(receipt.Hash, WireJson.Deserialize<MutationAck>(receipt.Ack));
            await transaction.CommitAsync(cancellationToken);
            await cache.PutAsync(key, original, cancellationToken);
            return Replay(mutation.MutationId, hash, original);
        }

        var error = MutationValidation.Error(mutation, clock.GetUtcNow());
        MutationAck ack;
        if (error is not null) ack = new(mutation.MutationId, "REJECTED", null, null, error);
        else
        {
            var document = await database.Documents.FindAsync([workspaceId, mutation.EntityId], cancellationToken);
            var state = document is null ? new DocumentDelta() : WireJson.Deserialize<DocumentDelta>(document.State);
            try
            {
                var merged = state.Merge(mutation.Delta);
                workspace.Revision = checked(workspace.Revision + 1);
                if (document is null)
                    database.Documents.Add(new() { WorkspaceId = workspaceId, EntityId = mutation.EntityId, State = WireJson.Serialize(merged) });
                else document.State = WireJson.Serialize(merged);
                database.Changes.Add(new()
                {
                    WorkspaceId = workspaceId, Revision = workspace.Revision,
                    EntityId = mutation.EntityId, Delta = WireJson.Serialize(mutation.Delta)
                });
                ack = new(mutation.MutationId, "SYNCED", workspace.Revision, merged);
            }
            catch (Exception exception) when (exception is ArgumentException or OverflowException)
            {
                ack = new(mutation.MutationId, "CONFLICT", null, null, "INCONSISTENT_MERGE_METADATA");
            }
        }
        database.Receipts.Add(new()
        {
            WorkspaceId = workspaceId, ReplicaId = mutation.ReplicaId, MutationId = mutation.MutationId,
            Hash = hash, Ack = WireJson.Serialize(ack)
        });
        await database.SaveChangesAsync(cancellationToken);
        await transaction.CommitAsync(cancellationToken);
        await cache.PutAsync(key, new(hash, ack), cancellationToken);
        return ack;
    }

    public async Task<PullResponse> PullAsync(string workspaceId, string? cursor, int limit,
        CancellationToken cancellationToken = default)
    {
        if (!MutationValidation.IsId(workspaceId) || limit is < 1 or > 100) throw new ArgumentException("INVALID_PULL");
        var after = cursors.Decode(workspaceId, cursor);
        await using var database = await factory.CreateDbContextAsync(cancellationToken);
        var latest = await database.Workspaces.Where(row => row.Id == workspaceId)
            .Select(row => (long?)row.Revision).SingleOrDefaultAsync(cancellationToken) ?? 0;
        if (after > latest) throw new ArgumentException("CURSOR_AHEAD_OF_HISTORY");
        var rows = await database.Changes.AsNoTracking().Where(row => row.WorkspaceId == workspaceId && row.Revision > after)
            .OrderBy(row => row.Revision).Take(limit + 1).ToArrayAsync(cancellationToken);
        var changes = rows.Take(limit).Select(row => new SyncChange(row.Revision, row.EntityId,
            WireJson.Deserialize<DocumentDelta>(row.Delta))).ToArray();
        return new(changes, cursors.Encode(workspaceId, changes.LastOrDefault()?.Revision ?? after), rows.Length > limit);
    }

    private static MutationAck Replay(string mutationId, string hash, CachedReceipt receipt) => hash == receipt.Hash
        ? receipt.Ack : new(mutationId, "REJECTED", null, null, "IDEMPOTENCY_MISMATCH");
}