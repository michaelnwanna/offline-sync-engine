using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Migrations;

namespace OfflineSync.Server.Migrations;

[DbContext(typeof(SyncDbContext))]
[Migration("202610160001_InitialSchema")]
public sealed class InitialSchema : Migration
{
    protected override void Up(MigrationBuilder migrationBuilder) => migrationBuilder.Sql("""
        CREATE TABLE workspaces (
            "Id" text PRIMARY KEY,
            "Revision" bigint NOT NULL CHECK ("Revision" >= 0)
        );
        CREATE TABLE documents (
            "WorkspaceId" text NOT NULL REFERENCES workspaces("Id"),
            "EntityId" text NOT NULL,
            "State" jsonb NOT NULL,
            PRIMARY KEY ("WorkspaceId", "EntityId")
        );
        CREATE TABLE receipts (
            "WorkspaceId" text NOT NULL REFERENCES workspaces("Id"),
            "ReplicaId" text NOT NULL,
            "MutationId" text NOT NULL,
            "Hash" text NOT NULL,
            "Ack" jsonb NOT NULL,
            PRIMARY KEY ("WorkspaceId", "ReplicaId", "MutationId")
        );
        CREATE TABLE changes (
            "WorkspaceId" text NOT NULL REFERENCES workspaces("Id"),
            "Revision" bigint NOT NULL CHECK ("Revision" > 0),
            "EntityId" text NOT NULL,
            "Delta" jsonb NOT NULL,
            PRIMARY KEY ("WorkspaceId", "Revision")
        );
        """);

    protected override void Down(MigrationBuilder migrationBuilder) => migrationBuilder.Sql(
        "DROP TABLE changes; DROP TABLE receipts; DROP TABLE documents; DROP TABLE workspaces;");
}