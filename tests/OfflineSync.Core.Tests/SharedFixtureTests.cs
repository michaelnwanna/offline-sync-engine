using System.Text.Json;
using OfflineSync.Core;
using Xunit;

namespace OfflineSync.Core.Tests;

public sealed class SharedFixtureTests
{
    [Fact]
    public void CanonicalEncodingMatchesSharedCases()
    {
        using var fixtures = JsonDocument.Parse(File.ReadAllText(
            Path.Combine(AppContext.BaseDirectory, "fixtures", "canonical-v1.json")));
        foreach (var fixture in fixtures.RootElement.EnumerateArray())
            Assert.Equal(fixture.GetProperty("canonical").GetString(), WireJson.Canonical(fixture.GetProperty("value")));
    }

    [Fact]
    public void SharedWireFixturesRoundTripWithoutLosingInt64Precision()
    {
        using var fixtures = JsonDocument.Parse(File.ReadAllText(
            Path.Combine(AppContext.BaseDirectory, "fixtures", "wire-v1.json")));
        RoundTrip<PushRequest>(fixtures.RootElement.GetProperty("pushRequest"));
        RoundTrip<PushResponse>(fixtures.RootElement.GetProperty("pushResponse"));
        RoundTrip<PullResponse>(fixtures.RootElement.GetProperty("pullResponse"));
        var request = WireJson.Deserialize<PushRequest>(fixtures.RootElement.GetProperty("pushRequest").GetRawText());
        Assert.Equal(fixtures.RootElement.GetProperty("mutationHash").GetString(), WireJson.Hash(request.Mutations[0]));
        Assert.Null(MutationValidation.Error(request.Mutations[0], DateTimeOffset.FromUnixTimeMilliseconds(1_700_000_000_000)));
        var response = WireJson.Deserialize<PushResponse>(fixtures.RootElement.GetProperty("pushResponse").GetRawText());
        Assert.Equal(9_007_199_254_740_993L, response.Acknowledgments[0].Revision);
    }

    private static void RoundTrip<T>(JsonElement fixture) => Assert.Equal(
        WireJson.Canonical(fixture), WireJson.Canonical(WireJson.Deserialize<T>(fixture.GetRawText())));

    [Fact]
    public void SharedMergeAndClockFixturesMatchBackend()
    {
        using var fixtures = JsonDocument.Parse(File.ReadAllText(
            Path.Combine(AppContext.BaseDirectory, "fixtures", "merge-v1.json")));
        foreach (var fixture in fixtures.RootElement.GetProperty("clocks").EnumerateArray())
        {
            var current = WireJson.Deserialize<HybridTimestamp>(fixture.GetProperty("current").GetRawText());
            var received = fixture.TryGetProperty("received", out var value)
                ? WireJson.Deserialize<HybridTimestamp>(value.GetRawText()) : null;
            var expected = WireJson.Deserialize<HybridTimestamp>(fixture.GetProperty("expected").GetRawText());
            Assert.Equal(expected, current.Tick(fixture.GetProperty("nowMs").GetInt64(), received));
        }
        foreach (var fixture in fixtures.RootElement.GetProperty("merges").EnumerateArray())
        {
            var left = WireJson.Deserialize<DocumentDelta>(fixture.GetProperty("left").GetRawText());
            var right = WireJson.Deserialize<DocumentDelta>(fixture.GetProperty("right").GetRawText());
            var expected = WireJson.Deserialize<DocumentDelta>(fixture.GetProperty("expected").GetRawText());
            Assert.Equal(WireJson.Canonical(expected), WireJson.Canonical(left.Merge(right)));
            Assert.Equal(WireJson.Canonical(expected), WireJson.Canonical(right.Merge(left)));
            Assert.Equal(WireJson.Canonical(expected), WireJson.Canonical(left.Merge(right).Merge(right)));
        }
    }
}