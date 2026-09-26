package dev.p2p.resourcepack.mixin;

import dev.p2p.resourcepack.PackDelivery;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ResourcePackResponseMixin {
    // TAIL executes after vanilla has handed the packet off to the server thread.
    @Inject(method = "handleResourcePackResponse", at = @At("TAIL"))
    private void p2pResourcePack$response(ServerboundResourcePackPacket packet, CallbackInfo ci) {
        PackDelivery.response((ServerCommonPacketListenerImpl) (Object) this, packet);
    }
}
