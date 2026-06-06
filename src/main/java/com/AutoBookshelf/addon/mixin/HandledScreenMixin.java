package com.AutoBookshelf.addon.mixin;

import com.AutoBookshelf.addon.modules.BundlePreview;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin extends Screen {
    protected HandledScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "drawSlot", at = @At("TAIL"))
    private void onDrawSlotTail(DrawContext context, Slot slot, CallbackInfo ci) {
        BundlePreview bundleModule = Modules.get().get(BundlePreview.class);
        if (bundleModule != null && bundleModule.isActive()) {
            bundleModule.renderBundleOverlay(context, slot.x, slot.y, slot.getStack());
        }
    }
}
