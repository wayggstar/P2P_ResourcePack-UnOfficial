package dev.p2p.resourcepack;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class ActivePackSharingTest {
    @TempDir Path temp;

    @Test void deterministicFolderZipTracksFileChangesAndKeepsRootMetadata() throws Exception {
        Path folder = Files.createDirectory(temp.resolve("example"));
        Files.writeString(folder.resolve("pack.mcmeta"), "{\"pack\":{}}");
        Files.createDirectories(folder.resolve("assets/example"));
        Path texture = folder.resolve("assets/example/file.txt");
        Files.writeString(texture, "base");
        var source = new ActivePacks.Source("file/example", folder);
        var initial = ActivePacks.capture(source);

        Path first = temp.resolve("first.zip");
        Path second = temp.resolve("second.zip");
        ActivePacks.writeZip(initial, first);
        ActivePacks.writeZip(initial, second);
        var firstVerified = PackVerifier.verifyFile(first);
        assertEquals(firstVerified, PackVerifier.verifyFile(second));
        try (var archive = new ZipFile(first.toFile())) {
            assertNotNull(archive.getEntry("pack.mcmeta"));
            assertNotNull(archive.getEntry("assets/example/file.txt"));
        }
        Files.writeString(texture, "edited payload");
        var edited = ActivePacks.capture(source);
        assertNotEquals(initial, edited);
        ActivePacks.writeZip(edited, second);
        assertNotEquals(firstVerified.sha1(), PackVerifier.verifyFile(second).sha1());
    }

    @Test void filteringDoesNotLetCheckboxOrderOverrideRepositoryOrder() {
        var low = new ActivePacks.Source("file/low", temp.resolve("low"));
        var middle = new ActivePacks.Source("file/middle", temp.resolve("middle"));
        var high = new ActivePacks.Source("file/high.zip", temp.resolve("high.zip"));
        assertEquals(List.of(low, high), ActivePacks.included(List.of(low, middle, high), List.of(high.id(), low.id())));
    }

    @Test void legacySettingsAndOrderedStackSurviveRestart() throws Exception {
        Path path = temp.resolve("settings.json");
        String hash = "0".repeat(40);
        String url = "https://example.com/old.zip";
        Files.writeString(path, """
                {"enabled":true,"url":"%s","sha1":"%s","required":true}
                """.formatted(url, hash));
        var settings = new SettingsStore(path);
        settings.load();
        assertEquals(PackSettings.Mode.URL, settings.get().mode());
        assertEquals(List.of(new PackSettings.Offer(url, hash, "old.zip")), settings.get().offers());
        var offers = List.of(new PackSettings.Offer(url, hash, "Base folder"),
                new PackSettings.Offer("https://example.com/new.zip", "1".repeat(40), "Top pack.zip"));
        var updated = new PackSettings(true, "", "", true, PackSettings.Mode.ACTIVE,
                List.of("file/old", "file/new"), offers);
        settings.save(updated);
        var oldConfig = com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        oldConfig.addProperty("autoResend", true);
        Files.writeString(path, oldConfig.toString());
        var reopened = new SettingsStore(path);
        reopened.load();
        assertEquals(updated, reopened.get());
        assertEquals(offers, reopened.get().offers());
        reopened.save(reopened.get());
        assertFalse(com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject().has("autoResend"));
    }

    @Test void singlePackListLabelsHideUrlParametersAndKeepUploadedNames() throws Exception {
        String sha1 = "0".repeat(40);
        String url = "https://example.com/packs/Custom%20Items.zip?token=private";
        var linked = new PackSettings(true, url, sha1, false);
        assertEquals("Custom Items.zip", linked.offers().getFirst().name());
        assertEquals("", new PackSettings(true, "https://example.com/download?token=private", sha1, false)
                .offers().getFirst().name());

        var uploaded = new PackSettings(true, url, sha1, false, PackSettings.Mode.ZIP,
                List.of(), List.of(), "Uploaded pack.zip");
        Path path = temp.resolve("zip-settings.json");
        new SettingsStore(path).save(uploaded);
        var reopened = new SettingsStore(path);
        reopened.load();
        assertEquals("Uploaded pack.zip", reopened.get().offers().getFirst().name());

        var label = new PackSettings.Offer(url, sha1, "Bad\nname§text\u202E.zip").name();
        assertEquals("Bad name text .zip", label);
    }

    @Test void cachedUploadPreservesOriginalBytesAndReusesVerifiedReceipt() throws Exception {
        Path folder = Files.createDirectory(temp.resolve("cache-pack"));
        Files.writeString(folder.resolve("pack.mcmeta"), "{\"pack\":{}}");
        Path zip = temp.resolve("source.zip");
        ActivePacks.writeZip(ActivePacks.capture(new ActivePacks.Source("file/cache-pack", folder)), zip);
        byte[] original = Files.readAllBytes(zip);
        var checked = PackVerifier.verifyFile(zip);
        var uploads = new AtomicInteger();
        var downloads = new AtomicInteger();
        HttpServer host = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        URI endpoint = URI.create("http://127.0.0.1:" + host.getAddress().getPort() + "/upload");
        String url = endpoint.resolve("/pack.zip").toString();
        host.createContext("/upload", exchange -> {
            uploads.incrementAndGet();
            byte[] posted = exchange.getRequestBody().readAllBytes();
            int start = new String(posted, StandardCharsets.ISO_8859_1).indexOf("Content-Type: application/zip\r\n\r\n")
                    + "Content-Type: application/zip\r\n\r\n".length();
            if (!Arrays.equals(original, Arrays.copyOfRange(posted, start, start + original.length))) {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }
            byte[] result = """
                    {"success":true,"data":{"download_url":"%s","sha1":"%s"}}
                    """.formatted(url, checked.sha1()).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, result.length);
            try (var body = exchange.getResponseBody()) { body.write(result); }
        });
        host.createContext("/pack.zip", exchange -> {
            downloads.incrementAndGet();
            exchange.sendResponseHeaders(200, original.length);
            try (var body = exchange.getResponseBody()) { body.write(original); }
        });
        host.start();
        try {
            Path receipts = temp.resolve("receipts");
            var first = new McPacksUploader(endpoint).uploadCached(zip, checked, receipts);
            var reused = new McPacksUploader(endpoint).uploadCached(zip, checked, receipts);
            assertEquals(first, reused);
            assertEquals(1, uploads.get());
            assertEquals(2, downloads.get());
            assertArrayEquals(original, Files.readAllBytes(zip));
        } finally {
            host.stop(0);
        }
    }
    @Test void activeUploadsOverlapAndReuseVerifiedPacksInNewPriorityOrder() throws Exception {
        Path first = temp.resolve("first.zip");
        Path second = temp.resolve("second.zip");
        writePack(first, "first");
        writePack(second, "second");
        var lower = ActivePacks.capture(new ActivePacks.Source("file/first.zip", first));
        var higher = ActivePacks.capture(new ActivePacks.Source("file/second.zip", second));
        byte[] firstBytes = Files.readAllBytes(first);
        byte[] secondBytes = Files.readAllBytes(second);
        String firstHash = PackVerifier.verifyFile(first).sha1();
        String secondHash = PackVerifier.verifyFile(second).sha1();

        var requests = new AtomicInteger();
        var firstAndSecond = new CountDownLatch(2);
        var higherDownloaded = new CountDownLatch(1);
        HttpServer host = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        URI endpoint = URI.create("http://127.0.0.1:" + host.getAddress().getPort() + "/upload");
        host.createContext("/", exchange -> {
            try (exchange) {
                requests.incrementAndGet();
                String route = exchange.getRequestURI().getPath();
                if (route.equals("/upload")) {
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    boolean low = new String(body, StandardCharsets.ISO_8859_1)
                            .contains(new String(firstBytes, StandardCharsets.ISO_8859_1));
                    firstAndSecond.countDown();
                    if (!firstAndSecond.await(5, TimeUnit.SECONDS)
                            || (low && !higherDownloaded.await(5, TimeUnit.SECONDS))) {
                        exchange.sendResponseHeaders(504, -1);
                        return;
                    }
                    URI download = endpoint.resolve(low ? "/first.zip" : "/second.zip");
                    byte[] response = """
                            {"success":true,"data":{"download_url":"%s","sha1":"%s"}}
                            """.formatted(download, low ? firstHash : secondHash).getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(201, response.length);
                    exchange.getResponseBody().write(response);
                } else {
                    boolean low = route.equals("/first.zip");
                    byte[] bytes = low ? firstBytes : secondBytes;
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    if (!low) higherDownloaded.countDown();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        try (var serverThreads = Executors.newCachedThreadPool()) {
            host.setExecutor(serverThreads);
            host.start();
            try {
                var progress = new ArrayList<Integer>();
                var uploader = new McPacksUploader(endpoint);
                var receipts = temp.resolve("parallel-receipts");
                var offers = ActivePacks.publish(List.of(lower, higher), Map.of(), progress::add, uploader, receipts);
                assertEquals(List.of(firstHash, secondHash), offers.stream().map(PackSettings.Offer::sha1).toList());
                assertEquals(List.of("first.zip", "second.zip"), offers.stream().map(PackSettings.Offer::name).toList());
                assertEquals(List.of(0, 1, 2), progress);
                assertEquals(4, requests.get());

                progress.clear();
                var reusable = Map.of(lower, offers.getFirst(), higher, offers.getLast());
                var reordered = ActivePacks.publish(List.of(higher, lower), reusable, progress::add, uploader, receipts);
                assertEquals(offers.reversed(), reordered);
                assertEquals(List.of(2), progress);
                assertEquals(4, requests.get());
            } finally {
                host.stop(0);
            }
        }
    }

    private void writePack(Path zip, String description) throws Exception {
        Path folder = Files.createDirectory(temp.resolve(description));
        Files.writeString(folder.resolve("pack.mcmeta"), ("{\"pack\":{\"description\":\"%s\"}}").formatted(description));
        ActivePacks.writeZip(ActivePacks.capture(new ActivePacks.Source("file/" + description, folder)), zip);
    }

}
