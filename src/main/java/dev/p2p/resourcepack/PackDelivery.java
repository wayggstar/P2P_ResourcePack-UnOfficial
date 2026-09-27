package dev.p2p.resourcepack;

import java.util.*;
import kfc.udp.client.quic.QuicBridge;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/** All mutations run on the integrated server thread, including status callbacks. */
public final class PackDelivery {
    private static final Map<ServerGamePacketListenerImpl, Delivery> SENT = new IdentityHashMap<>();
    private static Object hostToken;
    private static PackSettings applied = PackSettings.DEFAULT;
    private static final long LOAD_TIMEOUT_NANOS = 180_000_000_000L;

    private static final class Delivery {
        final UUID id = UUID.randomUUID();
        final boolean required;
        final long deadline = System.nanoTime() + LOAD_TIMEOUT_NANOS;
        boolean terminal;
        Delivery(boolean required) { this.required = required; }
    }

    public static void tick(MinecraftServer server) {
        if (!(server instanceof IntegratedServer)) return;
        Object token = QuicBridge.currentHostToken();
        PackSettings settings = P2PResourcePack.SETTINGS.get();
        if (hostToken != token || !applied.equals(settings)) {
            SENT.forEach((handler, delivery) -> handler.send(new ClientboundResourcePackPopPacket(Optional.of(delivery.id))));
            SENT.clear();
            hostToken = token;
            applied = settings;
        }
        if (token == null || !settings.enabled() || !settings.verified()) return;
        for (var player : server.getPlayerList().getPlayers()) {
            var handler = player.connection;
            if (!SENT.containsKey(handler)) {
                // The host may preview the pack, but a failed download must never close their world.
                boolean owner = server.isSingleplayerOwner(player.nameAndId());
                Delivery delivery = new Delivery(settings.required() && !owner);
                SENT.put(handler, delivery);
                handler.send(new ClientboundResourcePackPushPacket(delivery.id, settings.url(), settings.sha1(),
                        delivery.required, Optional.of(Component.literal("이 P2P 방의 리소스팩을 적용합니다. / Resource pack for this P2P room."))));
                P2PResourcePack.LOG.info("Pack requested: player={}, pack={}, required={}", player.getUUID(), delivery.id, delivery.required);
            }
        }
        long now = System.nanoTime();
        // Disconnect callbacks may remove entries synchronously, so iterate a snapshot.
        for (var entry : List.copyOf(SENT.entrySet())) {
            Delivery delivery = entry.getValue();
            if (!delivery.terminal && now > delivery.deadline) {
                delivery.terminal = true;
                P2PResourcePack.LOG.warn("Pack response timed out: pack={}, required={}", delivery.id, delivery.required);
                if (delivery.required) entry.getKey().disconnect(Component.literal("Required resource pack timed out. Please reconnect."));
            }
        }
    }

    public static void response(ServerCommonPacketListenerImpl listener, ServerboundResourcePackPacket packet) {
        if (!(listener instanceof ServerGamePacketListenerImpl handler)) return;
        Delivery delivery = SENT.get(handler);
        if (delivery == null || !delivery.id.equals(packet.id()) || delivery.terminal) return;
        P2PResourcePack.LOG.info("Pack response: player={}, pack={}, status={}", handler.player.getUUID(), packet.id(), packet.action());
        if (packet.action().isTerminal()) {
            delivery.terminal = true;
            if (delivery.required && packet.action() != ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED) {
                handler.disconnect(Component.translatable("multiplayer.requiredTexturePrompt.disconnect"));
            }
        }
    }

    public static void remove(ServerGamePacketListenerImpl handler) { SENT.remove(handler); }
    public static void clear() { SENT.clear(); hostToken = null; applied = PackSettings.DEFAULT; }
}
