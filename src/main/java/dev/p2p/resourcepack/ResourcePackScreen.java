package dev.p2p.resourcepack;

import java.util.concurrent.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class ResourcePackScreen extends Screen {
    private static final ExecutorService CHECKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "P2P-ResourcePack-Check");
        thread.setDaemon(true);
        return thread;
    });
    private final Screen parent;
    private boolean enabled;
    private boolean required;
    private String url;
    private String verifiedUrl;
    private String sha1;
    private EditBox urlField;
    private Button saveButton;
    private Button checkButton;
    private Button uploadButton;
    private Component status = Component.empty();
    private int statusColor = 0xFFAAAAAA;
    private Future<?> check;
    private int generation;
    private boolean checking;

    public ResourcePackScreen(Screen parent) {
        super(Component.literal("P2P_ResourcePack"));
        this.parent = parent;
        PackSettings settings = P2PResourcePack.SETTINGS.get();
        enabled = settings.enabled();
        required = settings.required();
        url = settings.url();
        sha1 = settings.sha1();
        verifiedUrl = settings.verified() ? url : "";
        if (settings.verified()) status = text("verified_saved");
    }

    private static Component text(String key) { return Component.translatable("p2p_resourcepack." + key); }

    @Override protected void init() {
        int x = width / 2 - 150;
        addRenderableWidget(Checkbox.builder(text("enabled"), font).pos(x, 39).selected(enabled)
                .onValueChange((box, value) -> { enabled = value; refresh(); }).build());
        urlField = new EditBox(font, x, 79, 300, 20, text("url"));
        urlField.setMaxLength(8192);
        urlField.setHint(Component.literal("https://example.com/pack.zip"));
        urlField.setValue(url);
        urlField.setResponder(value -> {
            url = value.trim();
            // A result is only reusable for exactly the URL that was checked.
            if (!url.equals(verifiedUrl)) {
                status = text("needs_check");
                statusColor = 0xFFFFCC55;
            }
            refresh();
        });
        addRenderableWidget(urlField);
        addRenderableWidget(Checkbox.builder(text("required"), font).pos(x, 107).selected(required)
                .onValueChange((box, value) -> { required = value; refresh(); }).build());
        checkButton = addRenderableWidget(Button.builder(text("check"), b -> verify()).bounds(x, 137, 145, 20).build());
        uploadButton = addRenderableWidget(Button.builder(text("upload_open"), b -> minecraft.setScreenAndShow(new UploadScreen(this)))
                .bounds(x + 155, 137, 145, 20).build());
        addRenderableWidget(Button.builder(text("cancel"), b -> onClose()).bounds(x + 155, height - 28, 145, 20).build());
        saveButton = addRenderableWidget(Button.builder(text("save"), b -> save()).bounds(x, height - 28, 145, 20).build());
        refresh();
    }

    private void refresh() {
        if (saveButton != null) saveButton.active = !checking && (!enabled || (url.equals(verifiedUrl) && new PackSettings(true, url, sha1, required).verified()));
        if (checkButton != null) {
            boolean valid;
            try { PackSettings.checkUrl(url); valid = true; }
            catch (IllegalArgumentException e) { valid = false; }
            checkButton.active = !checking && valid;
        }
        if (urlField != null) urlField.setEditable(!checking);
        if (uploadButton != null) uploadButton.active = !checking;
    }

    public void acceptUpload(McPacksUploader.Result result) {
        enabled = true;
        url = verifiedUrl = result.url();
        sha1 = result.sha1();
        status = text("upload_done");
        statusColor = 0xFF88EE88;
    }

    private void verify() {
        final String requested = url;
        final int request = ++generation;
        checking = true;
        // A failed recheck must invalidate the previous hash, even if the URL is unchanged.
        verifiedUrl = "";
        sha1 = "";
        status = text("checking");
        statusColor = 0xFFAAAAAA;
        refresh();
        P2PResourcePack.LOG.info("Resource pack verification started (URL omitted to protect signed links)");
        check = CHECKER.submit(() -> {
            try {
                var result = PackVerifier.verify(requested);
                minecraft.execute(() -> {
                    if (request != generation) return;
                    checking = false;
                    verifiedUrl = requested;
                    sha1 = result.sha1();
                    status = Component.translatable("p2p_resourcepack.verified", String.format(java.util.Locale.ROOT, "%.1f", result.bytes() / 1048576.0));
                    statusColor = 0xFF88EE88;
                    P2PResourcePack.LOG.info("Resource pack verified: bytes={}, sha1={}", result.bytes(), sha1);
                    refresh();
                });
            } catch (Exception e) {
                minecraft.execute(() -> {
                    if (request != generation) return;
                    checking = false;
                    status = text("check_failed");
                    statusColor = 0xFFFF7777;
                    // Exception messages may include signed URLs; only log their type.
                    P2PResourcePack.LOG.warn("Resource pack verification failed: {}", e.getClass().getSimpleName());
                    refresh();
                });
            }
        });
    }

    private void save() {
        try {
            P2PResourcePack.SETTINGS.save(new PackSettings(enabled, url, url.equals(verifiedUrl) ? sha1 : "", required));
            P2PResourcePack.LOG.info("Resource pack settings saved: enabled={}, required={}", enabled, required);
            onClose();
        } catch (Exception e) {
            status = text("save_failed");
            statusColor = 0xFFFF7777;
            P2PResourcePack.LOG.error("Failed to save resource pack settings", e);
        }
    }

    @Override public void onClose() { minecraft.setScreenAndShow(parent); }

    @Override public void removed() {
        generation++;
        if (check != null) check.cancel(true);
        checking = false;
        super.removed();
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int x = width / 2 - 150;
        graphics.centeredText(font, title, width / 2, 15, 0xFFFFFFFF);
        graphics.text(font, text("url"), x, 67, 0xFFCCCCCC);
        graphics.textWithWordWrap(font, status, x, 164, 300, statusColor);
        if (height >= 280) {
            graphics.textWithWordWrap(font, text("help"), x, 192, 300, 0xFFAAAAAA);
        }
        graphics.centeredText(font, text("credit"), width / 2, height - 56, 0xFFAAAAAA);
        graphics.centeredText(font, text("unofficial"), width / 2, height - 44, 0xFF888888);
    }
}
