package dev.p2p.resourcepack.smoke;

import dev.p2p.resourcepack.ResourcePackScreen;
import dev.p2p.resourcepack.UploadScreen;
import kfc.udp.client.gui.CustomRoomScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.slf4j.LoggerFactory;

/** Starts at the title screen; never opens, creates, or modifies a world. */
public final class ScreenSmokeTest implements ClientModInitializer {
    private int ticks;
    private int stage;
    private CustomRoomScreen room;

    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ticks++;
            if (stage == 0 && ticks > 60 && client.gui.screen() instanceof TitleScreen) {
                try {
                    // Force transformation of the packet-listener mixin, even without a server.
                    Class.forName("net.minecraft.server.network.ServerCommonPacketListenerImpl");
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
                    client.setScreenAndShow(new ResourcePackScreen(room));
                    String uploadLabel = Component.translatable("p2p_resourcepack.upload_open").getString();
                    Button upload = (Button) client.gui.screen().children().stream().filter(w -> w instanceof Button b && b.getMessage().getString().equals(uploadLabel)).findFirst().orElseThrow();
                    upload.onPress(null);
                    if (!(client.gui.screen() instanceof UploadScreen)) throw new AssertionError("Upload screen did not open");
                    String publicLabel = Component.translatable("p2p_resourcepack.upload_public").getString();
                    Button publicUpload = (Button) client.gui.screen().children().stream().filter(w -> w instanceof Button b && b.getMessage().getString().equals(publicLabel)).findFirst().orElseThrow();
                    if (publicUpload.active) throw new AssertionError("Upload must be disabled without a verified file");
                    LoggerFactory.getLogger("P2P_ResourcePack-Smoke").info("SMOKE PASS: mixins, room title, settings and upload screen; upload disabled without a verified file");
                    stage = 1;
                    ticks = 0;
                } catch (Throwable e) {
                    LoggerFactory.getLogger("P2P_ResourcePack-Smoke").error("SMOKE FAILED", e);
                    stage = 2;
                    client.stop();
                }
            }
            if (stage == 1 && ticks > 80) client.stop();
        });
    }
}
