package dev.p2p.resourcepack.mixin;

import dev.p2p.resourcepack.PackDelivery;
import kfc.udp.g8.client.KfcudpClient;
import kfc.udp.g8.client.quic.QuicBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.objectweb.asm.Opcodes;

@Mixin(value = KfcudpClient.class, remap = false)
abstract class RoomVisibilityMixin {
    @Inject(method = "openRoomNow", at = @At(value = "FIELD",
            target = "Lkfc/udp/g8/client/KfcudpClient;activePublicRoom:Z",
            opcode = Opcodes.PUTSTATIC, shift = At.Shift.AFTER))
    private static void p2pResourcePack$roomOpened(CallbackInfo ci) {
        PackDelivery.updateVisibility(QuicBridge.currentHostToken(), KfcudpClient.isActivePublicRoom());
    }

    @Inject(method = "applyRoomSettings", at = @At("HEAD"))
    private static void p2pResourcePack$beforeRoomSettings(Minecraft client, GameType gameMode, int maxPlayers,
            boolean allowCheats, boolean publicRoom, String title, boolean allowBroadcast, CallbackInfoReturnable<Boolean> cir) {
        if (publicRoom) PackDelivery.updateVisibility(QuicBridge.currentHostToken(), true);
    }

    @Inject(method = "applyRoomSettings", at = @At("RETURN"))
    private static void p2pResourcePack$afterRoomSettings(CallbackInfoReturnable<Boolean> cir) {
        PackDelivery.updateVisibility(QuicBridge.currentHostToken(), KfcudpClient.isActivePublicRoom());
    }

    @Inject(method = "forcePublicOff", at = @At("RETURN"))
    private static void p2pResourcePack$roomMadePrivate(CallbackInfo ci) {
        PackDelivery.updateVisibility(QuicBridge.currentHostToken(), KfcudpClient.isActivePublicRoom());
    }
}
