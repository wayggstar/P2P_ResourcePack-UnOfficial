package dev.p2p.resourcepack;

import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;

/** Official mcpacks.dev API: multipart POST /api/v1/packs, field "file". */
public final class McPacksUploader {
    public static final URI ENDPOINT = URI.create("https://mcpacks.dev/api/v1/packs");
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final URI endpoint;
    public record Result(String url, String sha1, long bytes, Path receipt) {}

    public McPacksUploader() { this(ENDPOINT); }
    McPacksUploader(URI endpoint) { this.endpoint = endpoint; }

    public Result upload(Path source, String expectedSha1, Path receipts) throws IOException, InterruptedException {
        if (!Files.isRegularFile(source) || !source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new IOException("Select a ZIP file.");
        }
        Path snapshot = Files.createTempFile("p2p-pack-upload-", ".zip");
        try {
            // Upload exactly the bytes that were validated, even if the original is edited later.
            try (InputStream in = Files.newInputStream(source); OutputStream out = Files.newOutputStream(snapshot)) {
                byte[] buffer = new byte[32768];
                long total = 0;
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    if ((total += count) > PackVerifier.MAX_BYTES) throw new IOException("Pack exceeds 100 MiB.");
                    out.write(buffer, 0, count);
                }
            }
            var verified = PackVerifier.verifyFile(snapshot);
            if (!verified.sha1().equals(expectedSha1)) throw new IOException("File changed. Select it again.");
            byte[] random = new byte[24];
            new SecureRandom().nextBytes(random);
            String password = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            String boundary = "P2PResourcePack" + UUID.randomUUID().toString().replace("-", "");
            String prefix = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"management_password\"\r\n\r\n"
                    + password + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"resource-pack.zip\"\r\n"
                    + "Content-Type: application/zip\r\n\r\n";
            var body = HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofString(prefix),
                    HttpRequest.BodyPublishers.ofFile(snapshot), HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n"));

            // Save management credentials before sending; never put them in logs or the game config.
            Files.createDirectories(receipts);
            Path receipt = Files.createTempFile(receipts, "upload-", ".json");
            if (Files.getFileStore(receipt).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(receipt, PosixFilePermissions.fromString("rw-------"));
            }
            JsonObject record = new JsonObject();
            record.addProperty("service", endpoint.getHost());
            record.addProperty("sha1", verified.sha1());
            record.addProperty("management_password", password);
            record.addProperty("status", "pending");
            Files.writeString(receipt, JSON.toJson(record));

            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofMinutes(3))
                    .header("Accept", "application/json")
                    .header("User-Agent", "P2P_ResourcePack/0.2.0")
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(body).build();
            try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NEVER).build()) {
                var response = client.send(request, HttpResponse.BodyHandlers.limiting(
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), 65536));
                record.addProperty("http_status", response.statusCode());
                record.addProperty("status", "response_received");
                if (response.statusCode() != 200 && response.statusCode() != 201) {
                    record.addProperty("status", "failed");
                    Files.writeString(receipt, JSON.toJson(record));
                    throw new IOException("Upload returned HTTP " + response.statusCode() + ".");
                }
                JsonObject data;
                try {
                    JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                    if (!root.has("success") || !root.get("success").getAsBoolean()) throw new IllegalArgumentException();
                    data = root.getAsJsonObject("data");
                    // Preserve the receipt even if later integrity/download checks fail.
                    record.add("data", data);
                    Files.writeString(receipt, JSON.toJson(record));
                    String url = data.get("download_url").getAsString();
                    URI download = PackSettings.checkUrl(url);
                    if (!endpoint.getScheme().equalsIgnoreCase(download.getScheme())
                            || !endpoint.getAuthority().equalsIgnoreCase(download.getAuthority())) {
                        throw new IllegalArgumentException("Unexpected download destination.");
                    }
                    String hash = data.get("sha1").getAsString();
                    if (!verified.sha1().equalsIgnoreCase(hash)) throw new IllegalArgumentException("Uploaded hash mismatch.");
                    var downloaded = PackVerifier.verify(url);
                    if (!downloaded.sha1().equals(verified.sha1())) throw new IOException("Downloaded hash mismatch.");
                    record.addProperty("status", "verified");
                    Files.writeString(receipt, JSON.toJson(record));
                    return new Result(url, verified.sha1(), verified.bytes(), receipt);
                } catch (RuntimeException e) {
                    throw new IOException("Invalid upload response. See local upload receipt.", e);
                }
            }
        } finally {
            Files.deleteIfExists(snapshot);
        }
    }
}
