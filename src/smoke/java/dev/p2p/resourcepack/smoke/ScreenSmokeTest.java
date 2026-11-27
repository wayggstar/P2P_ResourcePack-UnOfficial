package dev.p2p.resourcepack.smoke;

import dev.p2p.resourcepack.PackSettings;
import dev.p2p.resourcepack.ResourcePackChoiceDialog;
import dev.p2p.resourcepack.ResourcePackScreen;
import io.netty.buffer.Unpooled;
import dev.p2p.resourcepack.UploadScreen;
import kfc.udp.g8.client.gui.room.CustomRoomScreen;
import kfc.udp.g8.client.quic.QuicBridge;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.dialog.DialogConnectionAccess;
import net.minecraft.client.gui.screens.dialog.DialogScreens;
import net.minecraft.core.Holder;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.ServerLinks;
import net.minecraft.server.dialog.ConfirmationDialog;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.SharedConstants;
import net.minecraft.server.packs.PackType;
import org.slf4j.LoggerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Starts at the title screen; never opens, creates, or modifies a world. */
public final class ScreenSmokeTest implements ClientModInitializer {
    private int ticks;
    private int stage;
    private CustomRoomScreen room;
    private final UUID low = UUID.randomUUID();
    private final UUID high = UUID.randomUUID();
    private Path lowerZip;
    private Path higherZip;

    private static Button findButton(net.minecraft.client.gui.screens.Screen screen, String key) {
        String label = Component.translatable("p2p_resourcepack." + key).getString();
        return screen.children().stream().filter(w -> w instanceof Button b && b.getMessage().getString().endsWith(label))
                .map(w -> (Button) w).findFirst().orElseThrow();
    }

    private static Checkbox findCheckbox(net.minecraft.client.gui.screens.Screen screen, String key) {
        String label = Component.translatable(key).getString();
        return screen.children().stream().filter(w -> w instanceof Checkbox box && box.getMessage().getString().equals(label))
                .map(w -> (Checkbox) w).findFirst().orElseThrow();
    }

    private static void verifyChoiceDialog(Minecraft client, String locale, String title,
                                           String apply, String skip, String unnamedPack) {
        Screen parent = client.gui.screen();
        UUID offer = UUID.randomUUID();
        String hash = "0".repeat(40);
        var packs = List.of(new PackSettings.Offer("https://example.com/download?token=private", hash),
                new PackSettings.Offer("https://example.com/base.zip", hash, "Base.zip"),
                new PackSettings.Offer("https://example.com/top.zip", hash, "Top.zip"));
        if (ResourcePackChoiceDialog.create(offer, true, packs, locale).common().canCloseWithEscape()) {
            throw new AssertionError("Escape must not count as Leave for a required pack");
        }
        var packet = new ClientboundShowDialogPacket(Holder.direct(ResourcePackChoiceDialog.create(offer, false, packs, locale)));
        var buffer = Unpooled.buffer();
        ConfirmationDialog dialog;
        try {
            var codec = ClientboundShowDialogPacket.CONTEXT_FREE_STREAM_CODEC;
            codec.encode(buffer, packet);
            dialog = (ConfirmationDialog) codec.decode(buffer).dialog().value();
        } finally {
            buffer.release();
        }

        var lines = dialog.common().body().stream().map(PlainMessage.class::cast)
                .map(line -> line.contents().getString()).toList();
        if (!dialog.common().title().getString().equals(title)
                || !lines.subList(2, lines.size()).equals(List.of("1. Top.zip", "2. Base.zip", "3. " + unnamedPack))) {
            throw new AssertionError("Incorrect dialog language or pack order: " + lines);
        }
        List<String> responses = new ArrayList<>();
        var connection = new DialogConnectionAccess() {
            @Override public void disconnect(Component message) {
                throw new AssertionError("A public-room button tried to disconnect the guest");
            }

            @Override public void runCommand(String command, Screen parent) {
                throw new AssertionError("A resource pack button tried to run a command");
            }

            @Override public void openDialog(Holder<Dialog> dialog, Screen parent) {
                throw new AssertionError("A resource pack button tried to open another dialog");
            }

            @Override public void sendCustomAction(Identifier id, Optional<Tag> payload) {
                if (!(payload.orElseThrow() instanceof StringTag code) || !offer.toString().equals(code.value())) {
                    throw new AssertionError("Invalid offer in dialog response");
                }
                responses.add(id.toString());
            }

            @Override public ServerLinks serverLinks() { return ServerLinks.EMPTY; }
        };

        for (String label : List.of(apply, skip)) {
            var screen = DialogScreens.createFromData(dialog, parent, connection);
            client.setScreenAndShow(screen);
            screen.children().stream().filter(widget -> widget instanceof Button button
                    && button.getMessage().getString().equals(label)).map(widget -> (Button) widget)
                    .findFirst().orElseThrow().onPress(null);
            if (client.gui.screen() != parent) throw new AssertionError("Dialog failed to close after the guest chose");
        }
        if (!responses.equals(List.of("p2p_resourcepack:accept", "p2p_resourcepack:decline"))) {
            throw new AssertionError("Incorrect public-room dialog responses: " + responses);
        }
    }

    private static void writeFixture(Path zip, String metadata) throws java.io.IOException {
        try (var archive = new ZipOutputStream(Files.newOutputStream(zip))) {
            archive.putNextEntry(new ZipEntry("pack.mcmeta"));
            archive.write(metadata.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            archive.closeEntry();
        }
    }

    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ticks++;
            if (stage == 0 && ticks > 60 && client.gui.screen() instanceof TitleScreen) {
                try {
                    // Force transformation of the packet-listener mixin, even without a server.
                    Class.forName("net.minecraft.server.network.ServerCommonPacketListenerImpl");
                    if (QuicBridge.currentHostToken() != null) throw new AssertionError("Unexpected active host in isolated smoke test");
                    // Resolve the addon call site too, catching accidental references to the removed WebRTC bridge.
                    Class.forName("dev.p2p.resourcepack.PackDelivery");
                    Class.forName("kfc.udp.g8.client.KfcudpClient");
                    verifyChoiceDialog(client, "ko_kr", "방 리소스팩", "적용", "건너뛰기", "리소스팩");
                    verifyChoiceDialog(client, "en_us", "Room resource packs", "Apply", "Skip", "Resource pack");
                    verifyChoiceDialog(client, "fr_fr", "Room resource packs", "Apply", "Skip", "Resource pack");
                    room = new CustomRoomScreen(client.gui.screen());
                    client.setScreenAndShow(room);
                    String label = Component.translatable("p2p_resourcepack.open").getString();
                    Button open = (Button) room.children().stream().filter(w -> w instanceof Button b && b.getMessage().getString().equals(label)).findFirst().orElseThrow();
                    var title = room.children().stream().filter(w -> w instanceof EditBox box && box.getWidth() > 150).map(w -> (EditBox)w).findFirst().orElseThrow();
                    title.setValue("Smoke room title");
                    open.onPress(null);
                    if (!(client.gui.screen() instanceof ResourcePackScreen)) throw new AssertionError("Settings screen did not open");
                    client.gui.screen().onClose();
                    var restoredTitle = room.children().stream().filter(w -> w instanceof EditBox box && box.getWidth() > 150).map(w -> (EditBox)w).findFirst().orElseThrow();
                    if (!restoredTitle.getValue().equals("Smoke room title")) throw new AssertionError("Room title was lost");
                    var publicToggle = findCheckbox(room, "instant-p2p.custom_room.public_allow");
                    if (!publicToggle.selected()) publicToggle.onPress(null);
                    findButton(room, "open").onPress(null);
                    findButton(client.gui.screen(), "mode_url").onPress(null);
                    var publicOnly = findCheckbox(client.gui.screen(), "p2p_resourcepack.public_optional");
                    if (publicOnly.active || publicOnly.selected()) throw new AssertionError("Public rooms must prohibit required packs");
                    client.gui.screen().onClose();
                    publicToggle = findCheckbox(room, "instant-p2p.custom_room.public_allow");
                    if (publicToggle.selected()) publicToggle.onPress(null);
                    findButton(room, "open").onPress(null);
                    findButton(client.gui.screen(), "mode_url").onPress(null);
                    if (!findCheckbox(client.gui.screen(), "p2p_resourcepack.required_short").active) {
                        throw new AssertionError("Private rooms must allow the required setting");
                    }
                    if (client.gui.screen().children().stream().filter(Checkbox.class::isInstance).count() != 1) {
                        throw new AssertionError("Only the room requirement checkbox should remain");
                    }
                    if (client.gui.screen().children().stream().noneMatch(EditBox.class::isInstance)) {
                        throw new AssertionError("URL mode did not show an input");
                    }
                    var repository = client.getResourcePackRepository();
                    var previous = List.copyOf(repository.getSelectedIds());
                    Path folder = Files.createTempDirectory(client.getResourcePackDirectory(), "Smoke low ");
                    Path zip = Files.createTempFile(client.getResourcePackDirectory(), "Smoke high ", ".zip");
                    var format = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES);
                    var metadata = """
                            {"pack":{"description":"Smoke fixture","min_format":[%d,%d],"max_format":[%d,%d]}}
                            """.formatted(format.major(), format.minor(), format.major(), format.minor());
                    try {
                        Files.writeString(folder.resolve("pack.mcmeta"), metadata);
                        writeFixture(zip, metadata);
                        repository.reload();
                        var selected = new ArrayList<>(previous);
                        selected.add("file/" + folder.getFileName());
                        selected.add("file/" + zip.getFileName());
                        repository.setSelected(selected);
                        findButton(client.gui.screen(), "mode_active").onPress(null);
                        var packs = client.gui.screen().children().stream().filter(w -> w instanceof Checkbox c
                                && c.getMessage().getString().startsWith("Smoke")).map(w -> (Checkbox) w).toList();
                        if (packs.isEmpty() || !packs.getFirst().getMessage().getString().startsWith("Smoke high")) {
                            throw new AssertionError("Active pack list did not prioritize the selected ZIP");
                        }
                        if (packs.size() > 1 && !packs.get(1).getMessage().getString().startsWith("Smoke low")) {
                            throw new AssertionError("Active pack list did not preserve the selected folder order");
                        }
                        findButton(client.gui.screen(), "refresh_short").onPress(null);
                        findButton(client.gui.screen(), "mode_none").onPress(null);
                        if (!findButton(client.gui.screen(), "apply").active) {
                            throw new AssertionError("Apply must be available when sharing is disabled");
                        }
                    } finally {
                        repository.setSelected(previous);
                        Files.deleteIfExists(folder.resolve("pack.mcmeta"));
                        Files.deleteIfExists(folder);
                        Files.deleteIfExists(zip);
                        repository.reload();
                    }
                    findButton(client.gui.screen(), "mode_zip").onPress(null);
                    findButton(client.gui.screen(), "upload_open").onPress(null);
                    if (!(client.gui.screen() instanceof UploadScreen)) throw new AssertionError("Upload screen did not open");
                    String publicLabel = Component.translatable("p2p_resourcepack.upload_public").getString();
                    Button publicUpload = (Button) client.gui.screen().children().stream().filter(w -> w instanceof Button b && b.getMessage().getString().equals(publicLabel)).findFirst().orElseThrow();
                    if (publicUpload.active) throw new AssertionError("Upload must be disabled without a verified file");
                    lowerZip = Files.createTempFile("p2p-smoke-low-", ".zip");
                    higherZip = Files.createTempFile("p2p-smoke-high-", ".zip");
                    writeFixture(lowerZip, metadata);
                    writeFixture(higherZip, metadata);
                    var downloaded = client.getDownloadedPackSource();
                    downloaded.configureForLocalWorld();
                    downloaded.pushLocalPack(low, lowerZip);
                    downloaded.pushLocalPack(high, higherZip);
                    stage = 1;
                    ticks = 0;
                } catch (Throwable e) {
                    LoggerFactory.getLogger("P2P_ResourcePack-Smoke").error("SMOKE FAILED", e);
                    stage = 2;
                    ticks = 0;
                }
            }
            if (stage == 1) {
                var ids = client.getResourcePackRepository().getSelectedIds().stream()
                        .filter(id -> id.endsWith(low.toString()) || id.endsWith(high.toString())).toList();
                if (ids.size() == 2 || ticks > 300) {
                    var logger = LoggerFactory.getLogger("P2P_ResourcePack-Smoke");
                    if (ids.size() == 2 && ids.getFirst().endsWith(low.toString()) && ids.getLast().endsWith(high.toString())) {
                        logger.info("SMOKE PASS: room entry and title, all sharing modes, public and private required settings, localized pack choice and names, active ZIP/folder order, upload gating, and vanilla pack stack order");
                    } else {
                        logger.error("SMOKE FAILED: vanilla pack stack was not applied in push order: {}", ids);
                    }
                    client.getDownloadedPackSource().popAll();
                    stage = 2;
                    ticks = 0;
                }
            }
            if (stage == 2 && ticks > 60) {
                try {
                    if (lowerZip != null) Files.deleteIfExists(lowerZip);
                    if (higherZip != null) Files.deleteIfExists(higherZip);
                } catch (Exception e) {
                    LoggerFactory.getLogger("P2P_ResourcePack-Smoke").warn("Could not remove temporary smoke packs", e);
                }
                client.stop();
            }
        });
    }
}
