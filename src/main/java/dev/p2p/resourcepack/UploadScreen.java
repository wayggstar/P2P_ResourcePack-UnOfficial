package dev.p2p.resourcepack;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class UploadScreen extends Screen {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "P2P-ResourcePack-Upload");
        thread.setDaemon(true);
        return thread;
    });
    private final ResourcePackScreen parent;
    private Path file;
    private PackVerifier.Result verified;
    private Component status = text("upload_hint");
    private boolean busy;
    private boolean uploading;
    private int generation;
    private Button selectButton;
    private Button uploadButton;
    private Button backButton;

    public UploadScreen(ResourcePackScreen parent) {
        super(text("upload_title"));
        this.parent = parent;
    }
    private static Component text(String key) { return Component.translatable("p2p_resourcepack." + key); }

    @Override protected void init() {
        int x = width / 2 - 150;
        selectButton = addRenderableWidget(Button.builder(text("select_zip"), b -> {
            int selection = generation;
            ZipFilePicker.open(minecraft, path -> {
                if (selection == generation) select(path);
            }, () -> { status = text("picker_failed"); });
        }).bounds(x, 46, 300, 20).build());
        uploadButton = addRenderableWidget(Button.builder(text("upload_public"), b -> upload()).bounds(x, height - 52, 300, 20).build());
        backButton = addRenderableWidget(Button.builder(text("back"), b -> onClose()).bounds(x, height - 28, 300, 20).build());
        refresh();
    }

    private void refresh() {
        if (selectButton != null) selectButton.active = !busy;
        if (uploadButton != null) uploadButton.active = !busy && verified != null;
        if (backButton != null) backButton.active = !uploading;
    }

    @Override public void onFilesDrop(List<Path> files) {
        if (!busy && files.size() == 1) select(files.getFirst());
    }

    private void select(Path path) {
        if (busy) return;
        file = path;
        verified = null;
        if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            status = text("zip_only");
            refresh();
            return;
        }
        busy = true;
        int request = ++generation;
        status = text("checking_local");
        refresh();
        WORKER.submit(() -> {
            try {
                var result = PackVerifier.verifyFile(path);
                minecraft.execute(() -> {
                    if (request != generation) return;
                    busy = false;
                    verified = result;
                    status = Component.translatable("p2p_resourcepack.local_ready", String.format(Locale.ROOT, "%.1f", result.bytes() / 1048576.0));
                    refresh();
                });
            } catch (Exception e) {
                minecraft.execute(() -> {
                    if (request != generation) return;
                    busy = false;
                    status = text("check_failed");
                    refresh();
                });
            }
        });
    }

    private void upload() {
        if (busy || verified == null) return;
        busy = uploading = true;
        int request = ++generation;
        String hash = verified.sha1();
        Path selected = file;
        status = text("uploading");
        refresh();
        P2PResourcePack.LOG.info("Public resource pack upload to mcpacks.dev started: sha1={}", hash);
        WORKER.submit(() -> {
            try {
                var result = new McPacksUploader().upload(selected, hash,
                        FabricLoader.getInstance().getConfigDir().resolve("p2p_resourcepack_uploads"));
                P2PResourcePack.LOG.info("mcpacks.dev upload verified: sha1={}, bytes={}; management receipt saved locally", result.sha1(), result.bytes());
                minecraft.execute(() -> {
                    if (request != generation) return;
                    busy = uploading = false;
                    parent.acceptUpload(result);
                    minecraft.setScreenAndShow(parent);
                });
            } catch (Exception e) {
                P2PResourcePack.LOG.warn("mcpacks.dev upload failed: {}. No automatic retry; inspect local receipt before retrying.", e.getClass().getSimpleName());
                minecraft.execute(() -> {
                    if (request != generation) return;
                    busy = uploading = false;
                    status = text("upload_failed");
                    refresh();
                });
            }
        });
    }

    @Override public boolean shouldCloseOnEsc() { return !uploading; }
    @Override public void onClose() { if (!uploading) minecraft.setScreenAndShow(parent); }
    @Override public void removed() { generation++; super.removed(); }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int x = width / 2 - 150;
        graphics.centeredText(font, title, width / 2, 15, 0xFFFFFFFF);
        if (file != null) graphics.text(font, font.plainSubstrByWidth(file.getFileName().toString(), 300), x, 73, 0xFFFFFFFF);
        graphics.textWithWordWrap(font, status, x, 91, 300, 0xFFDDDDDD);
        graphics.textWithWordWrap(font, text("upload_notice"), x, 128, 300, 0xFFFFCC77);
    }
}
