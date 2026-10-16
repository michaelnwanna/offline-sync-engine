using System.Collections.Immutable;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace OfflineSync.Core;

public static class WireJson
{
    public static JsonSerializerOptions Options { get; } = new(JsonSerializerDefaults.Web)
    {
        PropertyNameCaseInsensitive = false,
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow,
        NumberHandling = JsonNumberHandling.Strict,
        AllowDuplicateProperties = false,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        MaxDepth = 32
    };

    public static string Serialize<T>(T value) => JsonSerializer.Serialize(value, Options);
    public static T Deserialize<T>(string json) => JsonSerializer.Deserialize<T>(json, Options)
        ?? throw new JsonException("A non-null JSON value is required.");

    public static string Canonical<T>(T value)
    {
        using var stream = new MemoryStream();
        using (var writer = new Utf8JsonWriter(stream)) Write(writer, JsonSerializer.SerializeToElement(value, Options));
        return Encoding.UTF8.GetString(stream.ToArray());
    }

    public static string Hash(Mutation mutation) => Convert.ToHexStringLower(
        SHA256.HashData(Encoding.UTF8.GetBytes(Canonical(mutation))));

    private static void Write(Utf8JsonWriter writer, JsonElement value)
    {
        switch (value.ValueKind)
        {
            case JsonValueKind.Object:
                writer.WriteStartObject();
                foreach (var property in value.EnumerateObject().OrderBy(property => property.Name, StringComparer.Ordinal))
                {
                    writer.WritePropertyName(property.Name);
                    Write(writer, property.Value);
                }
                writer.WriteEndObject();
                break;
            case JsonValueKind.Array:
                writer.WriteStartArray();
                foreach (var item in value.EnumerateArray()) Write(writer, item);
                writer.WriteEndArray();
                break;
            default:
                value.WriteTo(writer);
                break;
        }
    }
}

public sealed record RegisterValue(JsonElement Value, HybridTimestamp Stamp)
{
    public RegisterValue Merge(RegisterValue other)
    {
        var order = Stamp.CompareTo(other.Stamp);
        if (order == 0 && WireJson.Canonical(Value) != WireJson.Canonical(other.Value))
            throw new ArgumentException("One field timestamp cannot identify different values.");
        return order >= 0 ? this : other;
    }
}

public sealed record DocumentDelta
{
    public ImmutableSortedDictionary<string, RegisterValue> Registers { get; init; } =
        ImmutableSortedDictionary.Create<string, RegisterValue>(StringComparer.Ordinal);
    public ImmutableSortedDictionary<string, PnCounter> Counters { get; init; } =
        ImmutableSortedDictionary.Create<string, PnCounter>(StringComparer.Ordinal);
    public ImmutableSortedDictionary<string, ObservedRemoveSet> Sets { get; init; } =
        ImmutableSortedDictionary.Create<string, ObservedRemoveSet>(StringComparer.Ordinal);
    public bool Deleted { get; init; }

    public DocumentDelta Merge(DocumentDelta other)
    {
        var registers = Registers;
        foreach (var field in other.Registers)
            registers = registers.SetItem(field.Key, registers.TryGetValue(field.Key, out var current)
                ? current.Merge(field.Value) : field.Value);
        var counters = Counters;
        foreach (var field in other.Counters)
            counters = counters.SetItem(field.Key, counters.GetValueOrDefault(field.Key, PnCounter.Empty).Merge(field.Value));
        var sets = Sets;
        foreach (var field in other.Sets)
            sets = sets.SetItem(field.Key, sets.GetValueOrDefault(field.Key, ObservedRemoveSet.Empty).Merge(field.Value));
        return new() { Registers = registers, Counters = counters, Sets = sets, Deleted = Deleted || other.Deleted };
    }
}

/// <summary>An immutable mutation; its identity and payload must remain unchanged across retries.</summary>
public sealed record Mutation(int ProtocolVersion, string ReplicaId, string MutationId,
    long LocalSequence, string EntityId, DocumentDelta Delta);

/// <summary>A bounded ordered batch; each mutation is independently committed and acknowledged.</summary>
public sealed record PushRequest(int ProtocolVersion, string ReplicaId, Mutation[] Mutations);

/// <summary>A stable replay receipt. Rejected mutations have no revision or authoritative state.</summary>
public sealed record MutationAck(string MutationId, string Status, long? Revision,
    DocumentDelta? State, string? ErrorCode = null);

/// <summary>Acknowledgments in request order; an interrupted batch may have partially committed.</summary>
public sealed record PushResponse(MutationAck[] Acknowledgments);

/// <summary>A retained field delta at a workspace-scoped commit-ordered revision.</summary>
public sealed record SyncChange(long Revision, string EntityId, DocumentDelta Delta);

/// <summary>A page whose next cursor must be persisted atomically with all included changes.</summary>
public sealed record PullResponse(SyncChange[] Changes, string NextCursor, bool HasMore);

public static class MutationValidation
{
    public const long MaximumComponent = 1_000_000_000_000;

    public static string? Error(Mutation mutation, DateTimeOffset now)
    {
        if (mutation.ProtocolVersion != 1) return "UNSUPPORTED_VERSION";
        if (!IsId(mutation.ReplicaId) || !IsId(mutation.MutationId) || !IsId(mutation.EntityId)
            || mutation.LocalSequence <= 0 || mutation.LocalSequence > MaximumComponent || mutation.Delta is null)
            return "INVALID_MUTATION";
        var delta = mutation.Delta;
        if (delta.Registers is null || delta.Counters is null || delta.Sets is null) return "INVALID_DELTA";
        if (delta.Registers.Count + delta.Counters.Count + delta.Sets.Count == 0 && !delta.Deleted) return "EMPTY_DELTA";
        foreach (var field in delta.Registers)
        {
            if (field.Value?.Stamp is not { } stamp || stamp.ReplicaId != mutation.ReplicaId
                || stamp.PhysicalMs < 0 || stamp.Logical < 0) return "INVALID_STAMP";
            if (stamp.PhysicalMs > now.ToUnixTimeMilliseconds() + 300_000) return "CLOCK_SKEW";
            if (field.Key == "title")
            {
                if (field.Value.Value.ValueKind != JsonValueKind.String || field.Value.Value.GetString()!.Length > 500)
                    return "INVALID_TITLE";
            }
            else if (field.Key != "completed" || field.Value.Value.ValueKind is not (JsonValueKind.True or JsonValueKind.False))
                return "UNKNOWN_FIELD_OR_TYPE";
        }
        foreach (var field in delta.Counters)
        {
            if (field.Key != "quantity" || field.Value?.Positive is null || field.Value.Negative is null)
                return "INVALID_COUNTER";
            if (field.Value.Positive.Concat(field.Value.Negative).Any(entry => entry.Key != mutation.ReplicaId
                || entry.Value < 0 || entry.Value > MaximumComponent)) return "INVALID_COUNTER_COMPONENT";
        }
        foreach (var field in delta.Sets)
        {
            if (field.Key != "tags" || field.Value?.Adds is null || field.Value.Removes is null
                || field.Value.Adds.Count > 100 || field.Value.Removes.Count > 1000) return "INVALID_SET";
            if (field.Value.Adds.Any(entry => entry.Key != mutation.MutationId || entry.Value.Length > 100)
                || field.Value.Removes.Any(dot => !IsId(dot))) return "INVALID_DOT";
        }
        return null;
    }

    public static bool IsId(string? value) => Guid.TryParseExact(value, "D", out var id)
        && id != Guid.Empty && id.ToString("D") == value;
}