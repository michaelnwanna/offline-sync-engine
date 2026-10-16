using Microsoft.Extensions.Logging;
using OfflineSync.Core;
using StackExchange.Redis;

namespace OfflineSync.Server;

public sealed record CachedReceipt(string Hash, MutationAck Ack);

public interface IReceiptCache
{
    Task<CachedReceipt?> GetAsync(string key, CancellationToken cancellationToken);
    Task PutAsync(string key, CachedReceipt receipt, CancellationToken cancellationToken);
}

public sealed class NoReceiptCache : IReceiptCache
{
    public Task<CachedReceipt?> GetAsync(string key, CancellationToken cancellationToken) => Task.FromResult<CachedReceipt?>(null);
    public Task PutAsync(string key, CachedReceipt receipt, CancellationToken cancellationToken) => Task.CompletedTask;
}

public sealed class RedisReceiptCache(IConnectionMultiplexer connection, ILogger<RedisReceiptCache> logger) : IReceiptCache
{
    public async Task<CachedReceipt?> GetAsync(string key, CancellationToken cancellationToken)
    {
        try
        {
            var value = await connection.GetDatabase().StringGetAsync(key).WaitAsync(cancellationToken);
            return value.IsNullOrEmpty ? null : WireJson.Deserialize<CachedReceipt>(value.ToString());
        }
        catch (Exception exception) when (exception is RedisException or System.Text.Json.JsonException)
        {
            logger.LogWarning("Redis receipt lookup unavailable; using PostgreSQL. Error type: {ErrorType}", exception.GetType().Name);
            return null;
        }
    }

    public async Task PutAsync(string key, CachedReceipt receipt, CancellationToken cancellationToken)
    {
        try
        {
            await connection.GetDatabase().StringSetAsync(key, WireJson.Serialize(receipt), TimeSpan.FromHours(24))
                .WaitAsync(cancellationToken);
        }
        catch (RedisException exception)
        {
            logger.LogWarning("Redis receipt write unavailable; durable receipt retained. Error type: {ErrorType}", exception.GetType().Name);
        }
    }
}