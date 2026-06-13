package com.AutoBookshelf.addon.mixin;

import com.AutoBookshelf.addon.modules.GetPreview;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DrawContext.class)
public class DrawContextMixin {
    @Inject(method = "drawItem(Lnet/minecraft/item/ItemStack;II)V", at = @At("TAIL"))
    private void onDrawItem(ItemStack stack, int x, int y, CallbackInfo ci) {
        GetPreview module = Modules.get().get(GetPreview.class);
        if (module != null && module.isActive()) {
            module.renderBundleOverlay((DrawContext) (Object) this, x, y, stack);
        }
    }
}
