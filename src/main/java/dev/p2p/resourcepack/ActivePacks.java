package dev.p2p.resourcepack;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.PackSource;

final class ActivePacks {
    static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "P2P-ActiveResourcePacks");
        thread.setDaemon(true);
        return thread;
    });
    private static final ExecutorService UPLOADS = Executors.newFixedThreadPool(3, r -> {
        Thread thread = new Thread(r, "P2P-ActivePackUpload");
        thread.setDaemon(true);
        return thread;
    });

    record Source(String id, Path path) {
        String name() { return path.getFileName().toString(); }
    }

    record FileStamp(Path path, long size, FileTime modified, FileTime created, Object fileKey) {}
    record Snapshot(Source source, boolean folder, List<FileStamp> files) {
        long size() { return files.stream().mapToLong(FileStamp::size).sum(); }
    }

    private record FinishedPack(int index, PackSettings.Offer offer) {}

    // The last manual apply and its reusable upload results belong to the client thread.
    private static PackSettings verifiedSettings;
    private static Map<Snapshot, PackSettings.Offer> verifiedOffers = Map.of();

    static List<Source> selected(Minecraft client) {
        Path root = client.getResourcePackDirectory().toAbsolutePath().normalize();
        List<Source> sources = new ArrayList<>();
        // The repository exposes application order, from lowest to highest priority.
        for (var pack : client.getResourcePackRepository().getSelectedPacks()) {
            String id = pack.getId();
            if (pack.getPackSource() != PackSource.DEFAULT || !id.startsWith("file/")) continue;
            Path path = root.resolve(id.substring(5)).normalize();
            if (root.equals(path.getParent())) sources.add(new Source(id, path));
        }
        return List.copyOf(sources);
    }

    static Snapshot capture(Source source) throws IOException {
        var root = source.path();
        var attributes = Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isRegularFile() && source.name().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return new Snapshot(source, false, List.of(stamp(root, attributes)));
        }
        if (!attributes.isDirectory()) throw new IOException("Unsupported resource pack source.");

        List<FileStamp> files = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                var entry = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (entry.isRegularFile()) files.add(stamp(path, entry));
                else if (!entry.isDirectory()) throw new IOException("Resource pack contains a symlink or special file.");
            }
        }
        return new Snapshot(source, true, List.copyOf(files));
    }

    private static FileStamp stamp(Path path, BasicFileAttributes attributes) {
        return new FileStamp(path, attributes.size(), attributes.lastModifiedTime(),
                attributes.creationTime(), attributes.fileKey());
    }

    static List<Source> included(List<Source> sources, Collection<String> ids) {
        return sources.stream().filter(source -> ids.contains(source.id())).toList();
    }

    static List<Snapshot> captureAll(List<Source> sources) throws IOException {
        var snapshots = new ArrayList<Snapshot>();
        for (Source source : sources) snapshots.add(capture(source));
        return List.copyOf(snapshots);
    }

    static Map<Snapshot, PackSettings.Offer> reusableOffers() {
        return P2PResourcePack.SETTINGS.get() == verifiedSettings ? verifiedOffers : Map.of();
    }

    static List<PackSettings.Offer> publish(List<Snapshot> snapshots, Map<Snapshot, PackSettings.Offer> reusable,
                                            IntConsumer progress) throws IOException, InterruptedException {
        Path receipts = FabricLoader.getInstance().getConfigDir().resolve("p2p_resourcepack_uploads");
        return publish(snapshots, reusable, progress, new McPacksUploader(), receipts);
    }

    static List<PackSettings.Offer> publish(List<Snapshot> snapshots, Map<Snapshot, PackSettings.Offer> reusable,
                                            IntConsumer progress, McPacksUploader uploader, Path receipts)
            throws IOException, InterruptedException {
        var offers = new ArrayList<PackSettings.Offer>(Collections.nCopies(snapshots.size(), null));
        var completion = new ExecutorCompletionService<FinishedPack>(UPLOADS);
        var jobs = new ArrayList<Future<FinishedPack>>();
        try {
            for (int index = 0; index < snapshots.size(); index++) {
                var snapshot = snapshots.get(index);
                var existing = reusable.get(snapshot);
                if (existing != null) {
                    offers.set(index, existing);
                    continue;
                }
                int position = index;
                jobs.add(completion.submit(() -> new FinishedPack(position, publishOne(snapshot, uploader, receipts))));
            }
            int completed = snapshots.size() - jobs.size();
            progress.accept(completed);
            for (int remaining = jobs.size(); remaining > 0; remaining--) {
                var result = completion.take().get();
                offers.set(result.index(), result.offer());
                progress.accept(++completed);
            }
            // Check all sources again before replacing the shared stack.
            for (Snapshot snapshot : snapshots) {
                if (!snapshot.equals(capture(snapshot.source()))) throw new IOException("Resource pack changed during upload.");
            }
            return List.copyOf(offers);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof InterruptedException interrupted) throw interrupted;
            if (e.getCause() instanceof IOException io) throw io;
            throw new IOException("Could not prepare an active resource pack.", e.getCause());
        } finally {
            for (var job : jobs) {
                if (!job.isDone()) job.cancel(true);
            }
        }
    }

    private static PackSettings.Offer publishOne(Snapshot snapshot, McPacksUploader uploader, Path receipts)
            throws IOException, InterruptedException {
        Path zip = snapshot.folder() ? Files.createTempFile("p2p-folder-pack-", ".zip") : snapshot.source().path();
        try {
            if (snapshot.folder()) writeZip(snapshot, zip);
            var checked = PackVerifier.verifyFile(zip);
            if (!snapshot.equals(capture(snapshot.source()))) throw new IOException("Resource pack changed during preparation.");
            var result = uploader.uploadCached(zip, checked, receipts);
            return new PackSettings.Offer(result.url(), result.sha1(), snapshot.source().name());
        } finally {
            if (snapshot.folder()) Files.deleteIfExists(zip);
        }
    }

    static void writeZip(Snapshot snapshot, Path output) throws IOException {
        try (var file = Files.newOutputStream(output);
             var limited = new FilterOutputStream(file) {
                 private long size;

                 private void reserve(int count) throws IOException {
                     size += count;
                     if (size > PackVerifier.MAX_BYTES) throw new IOException("Pack ZIP exceeds 100 MiB.");
                 }

                 @Override public void write(int value) throws IOException {
                     reserve(1);
                     out.write(value);
                 }

                 @Override public void write(byte[] bytes, int offset, int count) throws IOException {
                     reserve(count);
                     out.write(bytes, offset, count);
                 }
             };
             var zip = new ZipOutputStream(limited)) {
            byte[] buffer = new byte[32_768];
            for (FileStamp fileStamp : snapshot.files()) {
                Path path = fileStamp.path();
                String name = snapshot.source().path().relativize(path).toString().replace(java.io.File.separatorChar, '/');
                var entry = new ZipEntry(name);
                // Stable archive bytes make the SHA-1 cache work across scans and game restarts.
                entry.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0));
                zip.putNextEntry(entry);
                try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled.");
                        zip.write(buffer, 0, count);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    static void remember(PackSettings settings, List<Snapshot> snapshots) {
        var offers = new HashMap<Snapshot, PackSettings.Offer>();
        for (int index = 0; index < snapshots.size(); index++) {
            offers.put(snapshots.get(index), settings.activePacks().get(index));
        }
        verifiedSettings = settings;
        verifiedOffers = Map.copyOf(offers);
    }
}
