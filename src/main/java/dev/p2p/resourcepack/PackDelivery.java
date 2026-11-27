package dev.p2p.resourcepack;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import kfc.udp.g8.client.moderation.AdmitGate;
import kfc.udp.g8.client.quic.QuicBridge;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

public final class PackDelivery {
    private static final Map<ServerGamePacketListenerImpl, PlayerDelivery> PLAYERS = new IdentityHashMap<>();
    private static final long LOAD_TIMEOUT_NANOS = 180_000_000_000L;
    private static final Component REQUIRED_DISCONNECT = Component.translatable("multiplayer.requiredTexturePrompt.disconnect");

    private static Object visibilityToken;
    private static boolean privateRoom;
    private static Object hostToken;
    private static List<PackSettings.Offer> applied = List.of();
    private static boolean required;
    private static boolean shareWithOwner;

    private static final class PlayerDelivery {
        final PackConsent choice;
        final boolean required;
        final long choiceDeadline = System.nanoTime() + LOAD_TIMEOUT_NANOS;
        final Map<UUID, Delivery> packs = new LinkedHashMap<>();

        PlayerDelivery(boolean guest, boolean required) {
            choice = guest ? new PackConsent() : null;
            this.required = required;
        }
    }

    private static final class Delivery {
        final long deadline = System.nanoTime() + LOAD_TIMEOUT_NANOS;
        boolean terminal;
    }

    // Instant P2P room settings are written on the client thread. Share a lock with packet delivery
    // so a room cannot finish becoming public while the addon is starting a required send.
    public static synchronized void updateVisibility(Object token, boolean publicRoom) {
        if (token == null || token != QuicBridge.currentHostToken()) return;
        visibilityToken = token;
        privateRoom = !publicRoom;
    }

    private static void reconcile() {
        Object token = QuicBridge.currentHostToken();
        PackSettings settings = P2PResourcePack.SETTINGS.get();
        var offers = token == null ? List.<PackSettings.Offer>of() : settings.offers();
        boolean mustApply = PackConsent.required(token != null && token == visibilityToken && privateRoom,
                settings.required(), false);
        boolean applyToOwner = settings.mode() != PackSettings.Mode.ACTIVE;
        if (hostToken == token && applied.equals(offers) && required == mustApply && shareWithOwner == applyToOwner) return;

        boolean withdrawn = hostToken == token && !applied.isEmpty() && offers.isEmpty();
        PLAYERS.forEach((handler, state) -> {
            for (UUID id : state.packs.keySet()) handler.send(new ClientboundResourcePackPopPacket(Optional.of(id)));
            if (withdrawn && state.choice != null) {
                handler.player.sendSystemMessage(guestText(handler.player, "withdrawn"));
            }
        });
        PLAYERS.clear();
        hostToken = token;
        applied = offers;
        required = mustApply;
        shareWithOwner = applyToOwner;
    }

    public static synchronized void tick(MinecraftServer server) {
        if (!(server instanceof IntegratedServer)) return;
        reconcile();
        if (applied.isEmpty()) return;

        for (var player : server.getPlayerList().getPlayers()) {
            var handler = player.connection;
            boolean owner = server.isSingleplayerOwner(player.nameAndId());
            if (PLAYERS.containsKey(handler) || (owner && !shareWithOwner)) continue;
            // Offer packs only to guests already admitted to the room.
            if (!owner && AdmitGate.isPending(player.getUUID())) continue;

            var state = new PlayerDelivery(!owner, required && !owner);
            PLAYERS.put(handler, state);
            if (owner) push(handler, state);
            else player.openDialog(Holder.direct(ResourcePackChoiceDialog.create(state.choice.id(), state.required,
                    applied, player.clientInformation().language())));
        }

        long now = System.nanoTime();
        // Disconnect callbacks may remove entries synchronously.
        for (var handler : List.copyOf(PLAYERS.keySet())) {
            var state = PLAYERS.get(handler);
            if (state == null || !state.required) continue;
            if (state.choice != null && state.choice.pending() && now - state.choiceDeadline >= 0) {
                handler.disconnect(guestText(handler.player, "timeout"));
                continue;
            }
            for (var entry : state.packs.entrySet()) {
                Delivery delivery = entry.getValue();
                if (!delivery.terminal && now - delivery.deadline >= 0) {
                    delivery.terminal = true;
                    P2PResourcePack.LOG.warn("Pack response timed out: pack={}, required=true", entry.getKey());
                    handler.disconnect(guestText(handler.player, "timeout"));
                    break;
                }
            }
        }
    }

    public static synchronized void answer(ServerCommonPacketListenerImpl listener, ServerboundCustomClickActionPacket packet) {
        if (!(listener instanceof ServerGamePacketListenerImpl handler) || !PLAYERS.containsKey(handler)) return;
        var response = ResourcePackChoiceDialog.readChoice(packet.id(), packet.payload()).orElse(null);
        if (response == null || AdmitGate.isPending(handler.player.getUUID())) return;
        reconcile();
        var state = PLAYERS.get(handler);
        if (state == null || state.choice == null || !state.choice.choose(response.offer())) {
            handler.player.sendSystemMessage(guestText(handler.player, "expired"));
            return;
        }
        if (response.accept()) push(handler, state);
        else if (state.required) handler.disconnect(REQUIRED_DISCONNECT);
    }

    private static void push(ServerGamePacketListenerImpl handler, PlayerDelivery state) {
        // Vanilla 26.3 reverses packet order, then inserts fixed TOP packs in reverse again.
        for (var offer : applied) {
            UUID id = UUID.randomUUID();
            state.packs.put(id, new Delivery());
            handler.send(new ClientboundResourcePackPushPacket(id, offer.url(), offer.sha1(), state.required,
                    Optional.of(guestText(handler.player, "load_prompt"))));
            P2PResourcePack.LOG.info("Pack requested: player={}, pack={}, required={}", handler.player.getUUID(), id, state.required);
        }
    }

    public static synchronized void response(ServerCommonPacketListenerImpl listener, ServerboundResourcePackPacket packet) {
        if (!(listener instanceof ServerGamePacketListenerImpl handler) || !PLAYERS.containsKey(handler)) return;
        reconcile();
        var state = PLAYERS.get(handler);
        Delivery delivery = state == null ? null : state.packs.get(packet.id());
        if (delivery == null || delivery.terminal) return;
        P2PResourcePack.LOG.info("Pack response: player={}, pack={}, status={}", handler.player.getUUID(), packet.id(), packet.action());
        if (packet.action().isTerminal()) {
            delivery.terminal = true;
            if (state.required && packet.action() != ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED) {
                handler.disconnect(REQUIRED_DISCONNECT);
            }
        }
    }

    private static Component guestText(ServerPlayer player, String key) {
        return PackGuestLanguage.forClient(player.clientInformation().language()).text(key);
    }

    public static synchronized void remove(ServerGamePacketListenerImpl handler) {
        PLAYERS.remove(handler);
    }

    public static synchronized void clear() {
        PLAYERS.clear();
        visibilityToken = null;
        privateRoom = false;
        hostToken = null;
        applied = List.of();
        required = false;
        shareWithOwner = false;
    }
}
