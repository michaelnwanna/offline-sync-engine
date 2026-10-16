using System.Text.Json;
using OfflineSync.Core;
using Xunit;

namespace OfflineSync.Core.Tests;

public sealed class MergeTests
{
    [Fact]
    public void LwwUsesOrdinalReplicaTieBreakerAndRejectsAmbiguousStamp()
    {
        var left = new LwwRegister<string>("left", new(100, 0, "a"));
        var right = new LwwRegister<string>("right", new(100, 0, "b"));
        Assert.Equal(right, left.Merge(right));
        Assert.Equal(right, right.Merge(left));
        Assert.Throws<ArgumentException>(() => left.Merge(left with { Value = "different" }));
    }

    [Fact]
    public void ClockAdvancesDespiteWallClockRollbackAndReceivedFutureClock()
    {
        var original = new HybridTimestamp(100, 5, "a");
        Assert.Equal(new(100, 6, "a"), original.Tick(50));
        Assert.Equal(new(200, 8, "a"), original.Tick(50, new(200, 7, "b")));
        Assert.Equal(new(100, 10, "a"), original.Tick(50, new(100, 9, "b")));
    }

    [Fact]
    public void CounterPreservesConcurrentIncrementsAndDecrementsAndReplays()
    {
        var left = PnCounter.Empty.Increment("a", 5).Increment("a", -2);
        var right = PnCounter.Empty.Increment("b", 4);
        Assert.Equal(7, left.Merge(right).Merge(right).Value);
        Assert.Equal(left.Merge(right).Value, right.Merge(left).Value);
        Assert.Equal(3, left.Value);
    }

    [Fact]
    public void RemoveDoesNotEraseUnobservedConcurrentAddAndAllowsReadd()
    {
        var original = ObservedRemoveSet.Empty.Add("urgent", "a:1");
        var removed = original.Remove("urgent");
        var concurrent = original.Add("urgent", "b:1");
        Assert.Empty(removed.Values);
        Assert.Contains("urgent", removed.Merge(concurrent).Values);
        Assert.Contains("urgent", removed.Add("urgent", "a:2").Values);
        Assert.Throws<ArgumentException>(() => original.Add("different", "a:1"));
    }

    [Fact]
    public void TombstoneCanArriveBeforeItsAdd()
    {
        var add = ObservedRemoveSet.Empty.Add("tag", "a:1");
        var removeDelta = ObservedRemoveSet.Empty with { Removes = add.Remove("tag").Removes };
        Assert.Empty(removeDelta.Merge(add).Values);
    }

    [Fact]
    public void GeneratedStatesConvergeUnderPermutationDuplicationAndGrouping()
    {
        var random = new Random(421);
        for (var iteration = 0; iteration < 100; iteration++)
        {
            var first = PnCounter.Empty.Increment("a", random.Next(-100, 100));
            var second = PnCounter.Empty.Increment("b", random.Next(-100, 100));
            var third = PnCounter.Empty.Increment("c", random.Next(-100, 100));
            Same(first.Merge(second), second.Merge(first));
            Same(first.Merge(first), first);
            Same(first.Merge(second).Merge(third), first.Merge(second.Merge(third)));

            var firstSet = ObservedRemoveSet.Empty.Add("x", $"a:{iteration}");
            var secondSet = firstSet.Remove("x");
            var thirdSet = ObservedRemoveSet.Empty.Add("x", $"b:{iteration}");
            Same(firstSet.Merge(secondSet), secondSet.Merge(firstSet));
            Same(secondSet.Merge(secondSet), secondSet);
            Same(firstSet.Merge(secondSet).Merge(thirdSet), firstSet.Merge(secondSet.Merge(thirdSet)));
        }
    }

    private static void Same<T>(T expected, T actual) =>
        Assert.Equal(JsonSerializer.Serialize(expected), JsonSerializer.Serialize(actual));
}