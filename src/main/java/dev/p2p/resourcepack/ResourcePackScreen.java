package dev.p2p.resourcepack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class ResourcePackScreen extends Screen {
    private static final int GRAY = 0xFFAAAAAA;
    private static final int ERROR = 0xFFFF7777;
    private static final int SUCCESS = 0xFF88EE88;
    private static final ExecutorService CHECKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "P2P-ResourcePack-Check");
        thread.setDaemon(true);
        return thread;
    });
    private final Screen parent;
    private final boolean publicRoom;
    private final Set<String> selectedIds;
    private final EnumMap<PackSettings.Mode, Button> modeButtons = new EnumMap<>(PackSettings.Mode.class);
    private PackSettings.Mode mode;
    private boolean required;
    private String url;
    private String verifiedUrl;
    private String sha1;
    private PackSettings.Offer uploaded;
    private List<ActivePacks.Source> detected = List.of();
    private List<Long> sizes = List.of();
    private int page;
    private int rowCount;
    private EditBox urlField;
    private Button saveButton;
    private Button modeAction;
    private Button cancelButton;
    private Checkbox requiredBox;
    private Component status = Component.empty();
    private int statusColor = GRAY;
    private Future<?> pending;
    private int generation;
    private int scanGeneration;
    private boolean checking;
    private boolean applying;
    private boolean measuring;

    public ResourcePackScreen(Screen parent, boolean publicRoom) {
        super(text("title"));
        this.parent = parent;
        this.publicRoom = publicRoom;
        PackSettings settings = P2PResourcePack.SETTINGS.get();
        mode = settings.mode();
        required = settings.required();
        selectedIds = new HashSet<>(settings.selectedIds());
        url = mode == PackSettings.Mode.ZIP ? "" : settings.url();
        sha1 = mode == PackSettings.Mode.ZIP ? "" : settings.sha1();
        verifiedUrl = !url.isBlank() && settings.verified() ? url : "";
        if (mode == PackSettings.Mode.ZIP) uploaded = new PackSettings.Offer(settings.url(), settings.sha1(), settings.name());
        if (mode == PackSettings.Mode.URL && !verifiedUrl.isEmpty()) status = text("verified_saved");
    }

    private static Component text(String key) { return Component.translatable("p2p_resourcepack." + key); }

    @Override protected void init() {
        int panelWidth = panelWidth();
        int x = (width - panelWidth) / 2;
        int half = (panelWidth - 8) / 2;
        modeButtons.clear();
        urlField = null;
        modeAction = null;

        int y = 37;
        for (var option : PackSettings.Mode.values()) {
            int index = option.ordinal();
            var label = Component.literal(option == mode ? "● " : "○ ").append(text("mode_" + option.name().toLowerCase(Locale.ROOT)));
            var button = new Button(x, y + index * 18, panelWidth, 16, label, b -> selectMode(option), java.util.function.Supplier::get) {
                @Override protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
                    extractDefaultSprite(graphics);
                    int color = active || mode == option ? 0xFFFFFFFF : GRAY;
                    graphics.text(font, getMessage(), getX() + 7, getY() + 4, color);
                }
            };
            modeButtons.put(option, addRenderableWidget(button));
        }

        if (mode == PackSettings.Mode.URL) {
            urlField = new EditBox(font, x, 117, panelWidth - 102, 20, text("url"));
            urlField.setMaxLength(8192);
            urlField.setHint(Component.literal("https://example.com/pack.zip"));
            urlField.setValue(url);
            urlField.setResponder(value -> {
                url = value.trim();
                updateStatus(url.equals(verifiedUrl) ? text("verified_saved") : text("needs_check"), GRAY);
                refresh();
            });
            addRenderableWidget(urlField);
            modeAction = addRenderableWidget(Button.builder(text("check_short"), b -> verify())
                    .bounds(x + panelWidth - 96, 117, 96, 20).build());
        } else if (mode == PackSettings.Mode.ZIP) {
            modeAction = addRenderableWidget(Button.builder(text("upload_open"), b -> minecraft.setScreenAndShow(new UploadScreen(this)))
                    .bounds(x, 117, panelWidth, 20).build());
        } else if (mode == PackSettings.Mode.ACTIVE) {
            var active = ActivePacks.selected(minecraft);
            if (!active.equals(detected)) measure(active);
            initActiveList(x, panelWidth);
        }

        requiredBox = addRenderableWidget(Checkbox.builder(text(publicRoom ? "public_optional" : "required_short"), font).pos(x, height - 52)
                .selected(required && !publicRoom).onValueChange((box, value) -> required = value).build());
        cancelButton = addRenderableWidget(Button.builder(text("cancel"), b -> onClose()).bounds(x + half + 8, height - 28, half, 20).build());
        saveButton = addRenderableWidget(Button.builder(text("apply"), b -> apply()).bounds(x, height - 28, half, 20).build());
        refresh();
    }

    private int panelWidth() { return Math.min(330, width - 20); }

    private void selectMode(PackSettings.Mode option) {
        if (mode == option || checking || applying) return;
        mode = option;
        updateStatus(Component.empty(), GRAY);
        rebuildWidgets();
    }

    private void initActiveList(int x, int panelWidth) {
        rowCount = Math.max(1, (height - 205) / 20);
        int lastPage = Math.max(0, (detected.size() - 1) / rowCount);
        page = Math.min(page, lastPage);
        modeAction = addRenderableWidget(Button.builder(text("refresh_short"), b -> {
            updateStatus(Component.empty(), GRAY);
            minecraft.getResourcePackRepository().reload();
            measure(ActivePacks.selected(minecraft));
            rebuildWidgets();
        }).bounds(x + panelWidth - 126, 110, 78, 18).build());
        var previous = addRenderableWidget(Button.builder(Component.literal("<"), b -> {
            page--;
            rebuildWidgets();
        }).bounds(x + panelWidth - 44, 110, 20, 18).build());
        previous.active = page > 0 && !applying;
        var next = addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            page++;
            rebuildWidgets();
        }).bounds(x + panelWidth - 20, 110, 20, 18).build());
        next.active = page < lastPage && !applying;
        for (int row = 0; row < rowCount && page * rowCount + row < detected.size(); row++) {
            int index = detected.size() - 1 - (page * rowCount + row);
            var source = detected.get(index);
            String name = source.name();
            String display = font.plainSubstrByWidth(name, panelWidth - 106);
            if (!display.equals(name)) display = font.plainSubstrByWidth(name, panelWidth - 114) + "…";
            var label = Component.literal(display);
            var box = addRenderableWidget(Checkbox.builder(label, font).pos(x, 130 + row * 20)
                    .selected(selectedIds.contains(source.id())).onValueChange((widget, selected) -> {
                        if (selected) selectedIds.add(source.id());
                        else selectedIds.remove(source.id());
                        refresh();
                    }).build());
            if (!display.equals(name)) box.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(name)));
            box.active = !applying;
        }
    }

    private void measure(List<ActivePacks.Source> sources) {
        detected = sources;
        sizes = List.of();
        int request = ++scanGeneration;
        measuring = !sources.isEmpty();
        if (!measuring) return;
        CompletableFuture.runAsync(() -> {
            var measured = new ArrayList<Long>();
            for (var source : sources) {
                try { measured.add(ActivePacks.capture(source).size()); }
                catch (Exception e) { measured.add(-1L); }
            }
            minecraft.execute(() -> {
                if (scanGeneration != request) return;
                sizes = measured;
                measuring = false;
                refresh();
            });
        });
    }

    private void refresh() {
        modeButtons.forEach((option, button) -> button.active = option != mode && !checking && !applying);
        cancelButton.active = !applying;
        requiredBox.active = !publicRoom && mode != PackSettings.Mode.NONE && !applying;
        if (saveButton != null) saveButton.active = !checking && !applying && switch (mode) {
            case NONE -> true;
            case URL -> !url.isBlank() && url.equals(verifiedUrl);
            case ZIP -> uploaded != null;
            case ACTIVE -> !measuring;
        };
        if (modeAction != null) {
            boolean valid = true;
            if (mode == PackSettings.Mode.URL) {
                try { PackSettings.checkUrl(url); }
                catch (IllegalArgumentException e) { valid = false; }
            }
            modeAction.active = valid && !checking && !applying && !measuring;
        }
        if (urlField != null) urlField.setEditable(!checking);
    }

    public void acceptUpload(McPacksUploader.Result result, String name) {
        mode = PackSettings.Mode.ZIP;
        uploaded = new PackSettings.Offer(result.url(), result.sha1(), name);
        updateStatus(text("upload_done"), SUCCESS);
    }

    private void verify() {
        String requested = url;
        int request = ++generation;
        checking = true;
        verifiedUrl = "";
        sha1 = "";
        updateStatus(text("checking"), GRAY);
        refresh();
        pending = CHECKER.submit(() -> {
            try {
                var result = PackVerifier.verify(requested);
                minecraft.execute(() -> {
                    if (request != generation) return;
                    checking = false;
                    verifiedUrl = requested;
                    sha1 = result.sha1();
                    updateStatus(Component.translatable("p2p_resourcepack.verified", formatSize(result.bytes())), SUCCESS);
                    refresh();
                });
            } catch (Exception e) {
                minecraft.execute(() -> {
                    if (request != generation) return;
                    checking = false;
                    updateStatus(text("check_failed"), ERROR);
                    P2PResourcePack.LOG.warn("Resource pack verification failed: {}", e.getClass().getSimpleName());
                    refresh();
                });
            }
        });
    }

    private void apply() {
        if (mode != PackSettings.Mode.ACTIVE) {
            String currentUrl = mode == PackSettings.Mode.ZIP ? uploaded.url() : url;
            String currentHash = mode == PackSettings.Mode.ZIP ? uploaded.sha1() : url.equals(verifiedUrl) ? sha1 : "";
            String name = mode == PackSettings.Mode.ZIP ? uploaded.name() : "";
            commit(new PackSettings(mode != PackSettings.Mode.NONE, currentUrl, currentHash, required,
                    mode, selectedIds.stream().sorted().toList(), List.of(), name), null);
            return;
        }

        var sources = ActivePacks.included(ActivePacks.selected(minecraft), selectedIds);
        var draft = new PackSettings(true, "", "", required, mode,
                selectedIds.stream().sorted().toList(), List.of());
        if (sources.isEmpty()) {
            commit(draft, List.of());
            return;
        }
        int request = ++generation;
        var reusable = ActivePacks.reusableOffers();
        applying = true;
        updateStatus(text("active_uploading"), GRAY);
        rebuildWidgets();
        pending = ActivePacks.WORKER.submit(() -> {
            try {
                var snapshots = ActivePacks.captureAll(sources);
                var offers = ActivePacks.publish(snapshots, reusable, completed -> minecraft.execute(() -> {
                    if (request != generation || !applying) return;
                    updateStatus(Component.translatable("p2p_resourcepack.active_progress", completed, snapshots.size()), GRAY);
                }));
                minecraft.execute(() -> {
                    if (request != generation) return;
                    applying = false;
                    if (!sources.equals(ActivePacks.included(ActivePacks.selected(minecraft), selectedIds))) {
                        updateStatus(text("active_changed"), ERROR);
                        rebuildWidgets();
                        return;
                    }
                    commit(draft.withActivePacks(offers), snapshots);
                });
            } catch (Exception e) {
                minecraft.execute(() -> {
                    if (request != generation) return;
                    applying = false;
                    updateStatus(text("active_failed"), ERROR);
                    P2PResourcePack.LOG.warn("Active pack upload failed: {}", e.getClass().getSimpleName());
                    rebuildWidgets();
                });
            }
        });
    }

    private void commit(PackSettings settings, List<ActivePacks.Snapshot> snapshots) {
        try {
            P2PResourcePack.SETTINGS.save(settings);
            if (snapshots != null) ActivePacks.remember(settings, snapshots);
            minecraft.setScreenAndShow(parent);
        } catch (Exception e) {
            updateStatus(text("save_failed"), ERROR);
            P2PResourcePack.LOG.error("Failed to save resource pack settings", e);
            rebuildWidgets();
        }
    }

    private void updateStatus(Component message, int color) {
        status = message;
        statusColor = color;
    }

    private static String formatSize(long size) { return String.format(Locale.ROOT, "%.1f", size / 1048576.0); }

    @Override public boolean shouldCloseOnEsc() { return !applying; }
    @Override public void onClose() { if (!applying) minecraft.setScreenAndShow(parent); }

    @Override public void removed() {
        generation++;
        scanGeneration++;
        if (pending != null) pending.cancel(true);
        checking = false;
        applying = false;
        if (measuring) detected = List.of();
        measuring = false;
        super.removed();
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int panelWidth = panelWidth();
        int x = (width - panelWidth) / 2;
        graphics.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
        graphics.text(font, text("share_mode"), x, 27, 0xFFCCCCCC);
        if (mode == PackSettings.Mode.ACTIVE) {
            graphics.text(font, text("detected"), x, 115, 0xFFCCCCCC);
            if (detected.isEmpty()) graphics.text(font, text("active_empty"), x, 132, GRAY);
            for (int row = 0; row < rowCount && page * rowCount + row < detected.size(); row++) {
                int index = detected.size() - 1 - (page * rowCount + row);
                long size = index < sizes.size() ? sizes.get(index) : -1;
                String value = size < 0 ? "—" : formatSize(size) + " MiB";
                graphics.text(font, value, x + panelWidth - font.width(value) - 4, 135 + row * 20, GRAY);
            }
            Component message = status.getString().isEmpty() ? text("active_notice") : status;
            graphics.textWithWordWrap(font, message, x, height - 74, panelWidth, statusColor);
        } else {
            Component message = status;
            if (mode == PackSettings.Mode.ZIP && status.getString().isEmpty()) {
                message = text(uploaded == null ? "zip_setup" : "zip_saved");
            }
            graphics.textWithWordWrap(font, message, x, 144, panelWidth, statusColor);
        }
    }
}
