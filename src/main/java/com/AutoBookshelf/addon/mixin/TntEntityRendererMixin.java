package com.AutoBookshelf.addon.mixin;

import com.AutoBookshelf.addon.modules.TntFuseEsp;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.renderer.entity.TntRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(TntRenderer.class)
public class TntEntityRendererMixin {
    @ModifyArg(method = "submit(Lnet/minecraft/client/renderer/entity/state/TntRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/TntMinecartRenderer;submitWhiteSolidBlock(Lnet/minecraft/client/renderer/block/BlockModelRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IZI)V"), index = 4)
    private boolean numbyhack$disableTntFlash(boolean flashing) {
        TntFuseEsp tntFuseEsp = Modules.get().get(TntFuseEsp.class);
        if (tntFuseEsp != null && tntFuseEsp.shouldHideFlashing()) {
            return false;
        }
        return flashing;
    }
}
