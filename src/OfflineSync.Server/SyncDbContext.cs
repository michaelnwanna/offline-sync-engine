using Microsoft.EntityFrameworkCore;

namespace OfflineSync.Server;

public sealed class SyncDbContext(DbContextOptions<SyncDbContext> options) : DbContext(options)
{
    public DbSet<WorkspaceRow> Workspaces => Set<WorkspaceRow>();
    public DbSet<DocumentRow> Documents => Set<DocumentRow>();
    public DbSet<ReceiptRow> Receipts => Set<ReceiptRow>();
    public DbSet<ChangeRow> Changes => Set<ChangeRow>();

    protected override void OnModelCreating(ModelBuilder model)
    {
        model.Entity<WorkspaceRow>().ToTable("workspaces").HasKey(row => row.Id);
        model.Entity<DocumentRow>().ToTable("documents").HasKey(row => new { row.WorkspaceId, row.EntityId });
        model.Entity<ReceiptRow>().ToTable("receipts").HasKey(row => new { row.WorkspaceId, row.ReplicaId, row.MutationId });
        model.Entity<ChangeRow>().ToTable("changes").HasKey(row => new { row.WorkspaceId, row.Revision });
        model.Entity<DocumentRow>().Property(row => row.State).HasColumnType("jsonb");
        model.Entity<ReceiptRow>().Property(row => row.Ack).HasColumnType("jsonb");
        model.Entity<ChangeRow>().Property(row => row.Delta).HasColumnType("jsonb");
    }
}

public sealed class WorkspaceRow
{
    public required string Id { get; set; }
    public long Revision { get; set; }
}

public sealed class DocumentRow
{
    public required string WorkspaceId { get; set; }
    public required string EntityId { get; set; }
    public required string State { get; set; }
}

public sealed class ReceiptRow
{
    public required string WorkspaceId { get; set; }
    public required string ReplicaId { get; set; }
    public required string MutationId { get; set; }
    public required string Hash { get; set; }
    public required string Ack { get; set; }
}

public sealed class ChangeRow
{
    public required string WorkspaceId { get; set; }
    public long Revision { get; set; }
    public required string EntityId { get; set; }
    public required string Delta { get; set; }
}