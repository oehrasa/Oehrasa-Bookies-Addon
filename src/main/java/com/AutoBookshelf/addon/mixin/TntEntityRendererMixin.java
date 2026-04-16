package com.AutoBookshelf.addon.mixin;

import net.minecraft.client.render.entity.TntEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import com.AutoBookshelf.addon.modules.TntFuseEsp;
import meteordevelopment.meteorclient.systems.modules.Modules;

@Mixin(TntEntityRenderer.class)
public class TntEntityRendererMixin {
    @ModifyArg(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/entity/TntEntityRenderer;renderFlashingBlock(Lnet/minecraft/block/BlockState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;IZI)V"), index = 4)
    private boolean numbyhack$disableTntFlash(boolean flashing) {
        TntFuseEsp tntFuseEsp = Modules.get().get(TntFuseEsp.class);
        if (tntFuseEsp != null && tntFuseEsp.shouldHideFlashing()) {
            return false;
        }
        return flashing;
    }
}