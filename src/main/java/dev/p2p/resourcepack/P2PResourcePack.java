package dev.p2p.resourcepack;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class P2PResourcePack implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("P2P_ResourcePack");
    public static final SettingsStore SETTINGS = new SettingsStore(
            FabricLoader.getInstance().getConfigDir().resolve("p2p_resourcepack.json"));

    @Override public void onInitializeClient() {
        try { SETTINGS.load(); }
        catch (Exception e) { LOG.error("Cannot load settings; resource packs remain disabled", e); }
        ServerTickEvents.END_SERVER_TICK.register(PackDelivery::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> PackDelivery.remove(handler));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> PackDelivery.clear());
        LOG.info("P2P_ResourcePack 0.2.1 loaded. Unofficial addon. Instant P2P by 카이트 (KITE2459): https://github.com/KITE2459/kfcudp-instant-p2p");
    }
}
