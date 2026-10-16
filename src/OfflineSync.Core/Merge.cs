using System.Collections.Immutable;
using System.Text.Json.Serialization;

namespace OfflineSync.Core;

public sealed record HybridTimestamp(long PhysicalMs, int Logical, string ReplicaId) : IComparable<HybridTimestamp>
{
    public int CompareTo(HybridTimestamp? other)
    {
        if (other is null) return 1;
        var physical = PhysicalMs.CompareTo(other.PhysicalMs);
        if (physical != 0) return physical;
        var logical = Logical.CompareTo(other.Logical);
        return logical != 0 ? logical : StringComparer.Ordinal.Compare(ReplicaId, other.ReplicaId);
    }

    public HybridTimestamp Tick(long nowMs, HybridTimestamp? received = null)
    {
        var physical = Math.Max(nowMs, Math.Max(PhysicalMs, received?.PhysicalMs ?? 0));
        var logical = physical == PhysicalMs && physical == received?.PhysicalMs
            ? checked(Math.Max(Logical, received.Logical) + 1)
            : physical == PhysicalMs ? checked(Logical + 1)
            : physical == received?.PhysicalMs ? checked(received.Logical + 1) : 0;
        return new(physical, logical, ReplicaId);
    }
}

public sealed record LwwRegister<T>(T Value, HybridTimestamp Stamp)
{
    public LwwRegister<T> Merge(LwwRegister<T> other)
    {
        var order = Stamp.CompareTo(other.Stamp);
        if (order == 0 && !EqualityComparer<T>.Default.Equals(Value, other.Value))
            throw new ArgumentException("One field timestamp cannot identify different values.");
        return order >= 0 ? this : other;
    }
}

public sealed record PnCounter(
    ImmutableSortedDictionary<string, long> Positive,
    ImmutableSortedDictionary<string, long> Negative)
{
    public static PnCounter Empty { get; } = new(
        ImmutableSortedDictionary.Create<string, long>(StringComparer.Ordinal),
        ImmutableSortedDictionary.Create<string, long>(StringComparer.Ordinal));

    [JsonIgnore]
    public long Value => checked(Positive.Values.Sum() - Negative.Values.Sum());

    public PnCounter Increment(string replicaId, long amount)
    {
        if (amount == long.MinValue) throw new ArgumentOutOfRangeException(nameof(amount));
        var components = amount >= 0 ? Positive : Negative;
        var next = checked(components.GetValueOrDefault(replicaId) + Math.Abs(amount));
        return amount >= 0
            ? this with { Positive = components.SetItem(replicaId, next) }
            : this with { Negative = components.SetItem(replicaId, next) };
    }

    public PnCounter Merge(PnCounter other) => new(
        MergeComponents(Positive, other.Positive), MergeComponents(Negative, other.Negative));

    private static ImmutableSortedDictionary<string, long> MergeComponents(
        ImmutableSortedDictionary<string, long> left, ImmutableSortedDictionary<string, long> right)
    {
        if (left.Values.Concat(right.Values).Any(value => value < 0))
            throw new ArgumentException("PN components must be nonnegative.");
        return left.SetItems(right.Select(entry => new KeyValuePair<string, long>(
            entry.Key, Math.Max(left.GetValueOrDefault(entry.Key), entry.Value))));
    }
}

public sealed record ObservedRemoveSet(
    ImmutableSortedDictionary<string, string> Adds,
    ImmutableSortedSet<string> Removes)
{
    public static ObservedRemoveSet Empty { get; } = new(
        ImmutableSortedDictionary.Create<string, string>(StringComparer.Ordinal),
        ImmutableSortedSet.Create<string>(StringComparer.Ordinal));

    [JsonIgnore]
    public ImmutableSortedSet<string> Values => Adds
        .Where(entry => !Removes.Contains(entry.Key)).Select(entry => entry.Value)
        .ToImmutableSortedSet(StringComparer.Ordinal);

    public ObservedRemoveSet Add(string value, string dot)
    {
        if (Adds.TryGetValue(dot, out var existing) && existing != value)
            throw new ArgumentException("An add dot cannot identify two values.");
        return this with { Adds = Adds.SetItem(dot, value) };
    }

    public ObservedRemoveSet Remove(string value) => this with
    {
        Removes = Removes.Union(Adds.Where(entry => entry.Value == value).Select(entry => entry.Key))
    };

    public ObservedRemoveSet Merge(ObservedRemoveSet other)
    {
        var result = this;
        foreach (var entry in other.Adds) result = result.Add(entry.Value, entry.Key);
        return result with { Removes = result.Removes.Union(other.Removes) };
    }
}