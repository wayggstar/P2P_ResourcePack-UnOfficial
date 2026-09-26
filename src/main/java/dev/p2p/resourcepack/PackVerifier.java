package dev.p2p.resourcepack;

import com.google.gson.JsonParser;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;
import java.util.zip.ZipFile;

/** A bounded, temporary download; no archive extraction or execution. */
public final class PackVerifier {
    public static final long MAX_BYTES = 100L * 1024 * 1024;
    public record Result(String sha1, long bytes) {}

    public static Result verify(String url) throws IOException {
        return verify(url, MAX_BYTES);
    }

    static Result verify(String url, long limit) throws IOException {
        URI uri = PackSettings.checkUrl(url);
        long deadline = System.nanoTime() + 60_000_000_000L;
        Path file = Files.createTempFile("p2p-resourcepack-check-", ".zip");
        try {
            MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-1"); }
            catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
            long size = download(uri, file, digest, limit, deadline);
            validateZip(file);
            return new Result(HexFormat.of().formatHex(digest.digest()), size);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    public static Result verifyFile(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) {
            throw new IOException("Choose a regular ZIP file no larger than 100 MiB.");
        }
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-1"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        long size = 0;
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[32768];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled.");
                size += count;
                if (size > MAX_BYTES) throw new IOException("Resource pack exceeds download limit.");
                digest.update(buffer, 0, count);
            }
        }
        validateZip(file);
        return new Result(HexFormat.of().formatHex(digest.digest()), size);
    }

    private static void validateZip(Path file) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
                var entry = zip.getEntry("pack.mcmeta");
                if (entry == null || entry.isDirectory()) throw new IOException("ZIP root must contain pack.mcmeta.");
                try (InputStream input = zip.getInputStream(entry)) {
                    byte[] meta = input.readNBytes(1_048_577);
                    if (meta.length > 1_048_576) throw new IOException("pack.mcmeta is too large.");
                    var json = JsonParser.parseString(new String(meta, StandardCharsets.UTF_8));
                    if (!json.isJsonObject() || !json.getAsJsonObject().has("pack")
                            || !json.getAsJsonObject().get("pack").isJsonObject()) {
                        throw new IOException("pack.mcmeta must contain a pack object.");
                    }
                } catch (RuntimeException e) {
                    throw new IOException("pack.mcmeta contains invalid JSON.", e);
                }
        }
    }

    private static long download(URI uri, Path file, MessageDigest digest, long limit, long deadline) throws IOException {
        for (int redirects = 0; redirects <= 5; redirects++) {
            checkDeadline(deadline);
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "P2P_ResourcePack/0.2.0");
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Redirect has no destination.");
                    uri = PackSettings.checkUrl(uri.resolve(location).toString());
                    continue;
                }
                if (status != 200) throw new IOException("Download returned HTTP " + status + ".");
                if (connection.getContentLengthLong() > limit) throw new IOException("Resource pack exceeds download limit.");
                long total = 0;
                try (InputStream input = connection.getInputStream(); OutputStream output = Files.newOutputStream(file)) {
                    byte[] buffer = new byte[32768];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        checkDeadline(deadline);
                        total += count;
                        if (total > limit) throw new IOException("Resource pack exceeds download limit.");
                        digest.update(buffer, 0, count);
                        output.write(buffer, 0, count);
                    }
                }
                return total;
            } finally { connection.disconnect(); }
        }
        throw new IOException("Too many redirects.");
    }

    private static void checkDeadline(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) {
            throw new IOException("Resource pack check timed out or was cancelled.");
        }
    }
}
