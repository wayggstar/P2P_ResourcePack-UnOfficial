package dev.p2p.resourcepack.mixin;

import dev.p2p.resourcepack.ResourcePackScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "kfc.udp.client.gui.CustomRoomScreen", remap = false)
public abstract class CustomRoomScreenMixin extends Screen {
    @Shadow private EditBox titleField;
    @Unique private String p2pResourcePack$savedRoomTitle;

    protected CustomRoomScreenMixin(Component title) { super(title); }

    @Inject(method = "init", at = @At("TAIL"))
    private void p2pResourcePack$addSettings(CallbackInfo ci) {
        if (p2pResourcePack$savedRoomTitle != null && titleField != null) {
            titleField.setValue(p2pResourcePack$savedRoomTitle);
            p2pResourcePack$savedRoomTitle = null;
        }
        addRenderableWidget(Button.builder(Component.translatable("p2p_resourcepack.open"), button -> {
            p2pResourcePack$savedRoomTitle = titleField == null ? null : titleField.getValue();
            minecraft.setScreenAndShow(new ResourcePackScreen(this));
        }).bounds(width / 2 + 35, 87, 120, 20).build());
    }
}
