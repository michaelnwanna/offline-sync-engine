using System.Text.Json;
using OfflineSync.Core;
using Xunit;

namespace OfflineSync.Core.Tests;

public sealed class ProtocolTests
{
    public const string Replica = "11111111-1111-4111-8111-111111111111";
    public const string Entity = "22222222-2222-4222-8222-222222222222";

    public static Mutation Create(string title = "Pack supplies") => new(1, Replica,
        Guid.NewGuid().ToString("D"), 1, Entity, new DocumentDelta
        {
            Registers = new DocumentDelta().Registers.Add("title",
                new(JsonSerializer.SerializeToElement(title), new(100, 0, Replica)))
        });

    [Fact]
    public void WireRoundTripKeepsCanonicalHashStable()
    {
        var mutation = Create();
        var roundTrip = WireJson.Deserialize<Mutation>(WireJson.Serialize(mutation));
        Assert.Equal(WireJson.Hash(mutation), WireJson.Hash(roundTrip));
        Assert.Null(MutationValidation.Error(roundTrip, DateTimeOffset.UtcNow));
        Assert.NotEqual(WireJson.Hash(mutation), WireJson.Hash(mutation with { LocalSequence = 2 }));
    }

    [Fact]
    public void UnknownAndDuplicateMembersAreRejected()
    {
        Assert.Throws<JsonException>(() => WireJson.Deserialize<DocumentDelta>("{\"unexpected\":1}"));
        Assert.Throws<JsonException>(() => WireJson.Deserialize<DocumentDelta>("{\"deleted\":true,\"deleted\":false}"));
    }

    [Fact]
    public void DeletesRemainDeletedAfterLateUpdates()
    {
        var deleted = new DocumentDelta { Deleted = true };
        var update = Create().Delta;
        Assert.True(deleted.Merge(update).Deleted);
        Assert.Equal(WireJson.Canonical(deleted.Merge(update)), WireJson.Canonical(update.Merge(deleted)));
    }

    [Fact]
    public void RejectsFutureClockAndForeignReplicaCounterComponents()
    {
        var mutation = Create();
        Assert.Equal("CLOCK_SKEW", MutationValidation.Error(mutation, DateTimeOffset.FromUnixTimeMilliseconds(-400_000)));
        var foreign = mutation with
        {
            Delta = new DocumentDelta
            {
                Counters = new DocumentDelta().Counters.Add("quantity", PnCounter.Empty.Increment(Entity, 1))
            }
        };
        Assert.Equal("INVALID_COUNTER_COMPONENT", MutationValidation.Error(foreign, DateTimeOffset.UtcNow));
    }
}