using System.Security.Cryptography;
using System.Text;
using OfflineSync.Core;

namespace OfflineSync.Server;

public sealed class CursorCodec
{
    private readonly byte[] key;

    public CursorCodec(byte[] key)
    {
        if (key.Length < 32) throw new ArgumentException("Cursor signing key must contain at least 32 bytes.");
        this.key = key.ToArray();
    }

    public string Encode(string workspaceId, long revision)
    {
        var payload = Encoding.UTF8.GetBytes($"1:{workspaceId}:{revision}");
        return Convert.ToBase64String(payload) + "." + Convert.ToBase64String(HMACSHA256.HashData(key, payload));
    }

    public long Decode(string workspaceId, string? cursor)
    {
        if (cursor is null) return 0;
        if (cursor.Length > 512) throw new ArgumentException("INVALID_CURSOR");
        try
        {
            var parts = cursor.Split('.');
            if (parts.Length != 2) throw new ArgumentException("INVALID_CURSOR");
            var payload = Convert.FromBase64String(parts[0]);
            if (!CryptographicOperations.FixedTimeEquals(HMACSHA256.HashData(key, payload), Convert.FromBase64String(parts[1])))
                throw new ArgumentException("INVALID_CURSOR");
            var values = Encoding.UTF8.GetString(payload).Split(':');
            if (values.Length != 3 || values[0] != "1" || values[1] != workspaceId
                || !long.TryParse(values[2], System.Globalization.NumberStyles.None,
                    System.Globalization.CultureInfo.InvariantCulture, out var revision) || revision < 0)
                throw new ArgumentException("INVALID_CURSOR");
            return revision;
        }
        catch (FormatException) { throw new ArgumentException("INVALID_CURSOR"); }
    }
}