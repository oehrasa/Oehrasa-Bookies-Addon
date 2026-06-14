package com.AutoBookshelf.addon.mixin;

import com.AutoBookshelf.addon.modules.GetPreview;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiGraphicsExtractor.class)
public class DrawContextMixin {
    @Inject(method = "item(Lnet/minecraft/world/item/ItemStack;II)V", at = @At("TAIL"))
    private void onDrawItem(ItemStack stack, int x, int y, CallbackInfo ci) {
        GetPreview module = Modules.get().get(GetPreview.class);
        if (module != null && module.isActive()) {
            module.renderBundleOverlay((GuiGraphicsExtractor) (Object) this, x, y, stack);
        }
    }
}
