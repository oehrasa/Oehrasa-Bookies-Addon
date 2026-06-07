package com.AutoBookshelf.addon.mixin;

import com.AutoBookshelf.addon.modules.BundlePreview;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public abstract class HandledScreenMixin extends Screen {
    protected HandledScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "extractSlot", at = @At("TAIL"))
    private void onExtractSlotTail(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        BundlePreview bundleModule = Modules.get().get(BundlePreview.class);
        if (bundleModule != null && bundleModule.isActive()) {
            bundleModule.renderBundleOverlay(graphics, slot.x, slot.y, slot.getItem());
        }
    }
}
