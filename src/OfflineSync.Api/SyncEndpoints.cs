using Microsoft.AspNetCore.Http.HttpResults;
using OfflineSync.Core;
using OfflineSync.Server;

namespace OfflineSync.Api;

public static class SyncEndpoints
{
    public static void MapSyncEndpoints(this WebApplication app)
    {
        var group = app.MapGroup("/v1/sync").RequireAuthorization("sync").WithTags("Synchronization");
        group.MapPost("/push", PushAsync).WithName("PushMutations")
            .WithSummary("Apply up to 100 immutable mutations, with durable replay receipts.")
            .ProducesProblem(400).ProducesProblem(401).ProducesProblem(403).ProducesProblem(413);
        group.MapGet("/pull", PullAsync).WithName("PullChanges")
            .WithSummary("Read a workspace-scoped page of committed field deltas.")
            .ProducesProblem(400).ProducesProblem(401).ProducesProblem(403);
    }

    private static async Task<Results<Ok<PushResponse>, ProblemHttpResult>> PushAsync(
        PushRequest request, HttpContext context, SyncService service, CancellationToken cancellationToken)
    {
        var workspace = context.User.FindFirst("workspace")?.Value;
        var replica = context.User.FindFirst("replica")?.Value;
        if (!MutationValidation.IsId(workspace) || !MutationValidation.IsId(replica) || request.ReplicaId != replica)
            return TypedResults.Problem(statusCode: 403, title: "Replica or workspace authorization mismatch.");
        if (request.ProtocolVersion != 1 || request.Mutations is null || request.Mutations.Length is < 1 or > 100
            || request.Mutations.Any(mutation => mutation is null || mutation.ReplicaId != replica
                || !MutationValidation.IsId(mutation.MutationId)))
            return TypedResults.Problem(statusCode: 400, title: "Invalid batch identity, size, or version.");
        var acknowledgments = new List<MutationAck>();
        foreach (var mutation in request.Mutations)
            acknowledgments.Add(await service.ApplyAsync(workspace!, mutation, cancellationToken));
        return TypedResults.Ok(new PushResponse(acknowledgments.ToArray()));
    }

    private static async Task<Results<Ok<PullResponse>, ProblemHttpResult>> PullAsync(
        HttpContext context, SyncService service, CancellationToken cancellationToken, string? cursor = null, int limit = 100)
    {
        var workspace = context.User.FindFirst("workspace")?.Value;
        if (!MutationValidation.IsId(workspace)) return TypedResults.Problem(statusCode: 403, title: "Invalid workspace claim.");
        try { return TypedResults.Ok(await service.PullAsync(workspace!, cursor, limit, cancellationToken)); }
        catch (ArgumentException exception)
        {
            return TypedResults.Problem(statusCode: 400, title: exception.Message);
        }
    }
}