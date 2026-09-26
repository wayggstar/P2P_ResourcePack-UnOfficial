package dev.p2p.resourcepack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.*;

public final class SettingsStore {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path path;
    private volatile PackSettings current = PackSettings.DEFAULT;

    public SettingsStore(Path path) { this.path = path; }
    public PackSettings get() { return current; }

    public void load() throws IOException {
        if (!Files.exists(path)) return;
        try {
            PackSettings loaded = JSON.fromJson(Files.readString(path), PackSettings.class);
            if (loaded == null || (loaded.enabled() && !loaded.verified())) {
                throw new IOException("Invalid resource pack configuration: " + path);
            }
            current = loaded;
        } catch (RuntimeException e) {
            throw new IOException("Cannot read resource pack configuration: " + path, e);
        }
    }

    public synchronized void save(PackSettings settings) throws IOException {
        if (settings.enabled() && !settings.verified()) throw new IOException("Verify the pack before enabling it.");
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), "p2p-resourcepack-", ".tmp");
        try {
            Files.writeString(temp, JSON.toJson(settings));
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            current = settings;
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
