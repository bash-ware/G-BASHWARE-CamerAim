using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.Extensions.Options;

namespace CamerAim.Server;

public sealed class TokenService
{
    private readonly byte[] ingestTokenHash;
    private readonly byte[] readTokenHash;

    public TokenService(IWebHostEnvironment environment, IOptions<CamerAimOptions> options)
    {
        string? configuredIngest = Environment.GetEnvironmentVariable("CAMERAIM_INGEST_TOKEN");
        string? configuredRead = Environment.GetEnvironmentVariable("CAMERAIM_READ_TOKEN");
        if (!string.IsNullOrWhiteSpace(configuredIngest) && !string.IsNullOrWhiteSpace(configuredRead))
        {
            ingestTokenHash = Hash(configuredIngest);
            readTokenHash = Hash(configuredRead);
            return;
        }

        string dataDirectory = Path.GetFullPath(options.Value.DataDirectory, environment.ContentRootPath);
        Directory.CreateDirectory(dataDirectory);
        string credentialsPath = Path.Combine(dataDirectory, "credentials.json");
        Credentials credentials;
        if (File.Exists(credentialsPath))
        {
            credentials = JsonSerializer.Deserialize<Credentials>(File.ReadAllText(credentialsPath))
                ?? throw new InvalidOperationException($"Invalid credentials file: {credentialsPath}");
        }
        else
        {
            credentials = new Credentials(CreateToken(), CreateToken());
            string json = JsonSerializer.Serialize(credentials, new JsonSerializerOptions { WriteIndented = true });
            File.WriteAllText(credentialsPath, json);
        }

        if (string.IsNullOrWhiteSpace(credentials.IngestToken) || string.IsNullOrWhiteSpace(credentials.ReadToken))
            throw new InvalidOperationException($"Both tokens are required in {credentialsPath}.");

        ingestTokenHash = Hash(credentials.IngestToken);
        readTokenHash = Hash(credentials.ReadToken);
    }

    public bool Authorize(HttpRequest request, TokenRole requiredRole)
    {
        string authorization = request.Headers.Authorization.ToString();
        if (!authorization.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase)) return false;
        string token = authorization[7..].Trim();
        if (token.Length == 0) return false;
        byte[] supplied = Hash(token);
        return requiredRole switch
        {
            TokenRole.Ingest => CryptographicOperations.FixedTimeEquals(supplied, ingestTokenHash),
            TokenRole.Read => CryptographicOperations.FixedTimeEquals(supplied, readTokenHash),
            _ => false
        };
    }

    private static string CreateToken() =>
        Convert.ToBase64String(RandomNumberGenerator.GetBytes(32))
            .TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static byte[] Hash(string token) => SHA256.HashData(Encoding.UTF8.GetBytes(token));

    private sealed record Credentials(string IngestToken, string ReadToken);
}

public enum TokenRole
{
    Ingest,
    Read
}
