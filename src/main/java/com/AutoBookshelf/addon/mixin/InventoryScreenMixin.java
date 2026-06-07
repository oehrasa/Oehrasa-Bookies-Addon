package com.AutoBookshelf.addon.mixin;

import com.AutoBookshelf.addon.events.ScreenRenderEvent;
import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InventoryScreen.class)
public class InventoryScreenMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void onRenderTail(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        MeteorClient.EVENT_BUS.post(ScreenRenderEvent.get(graphics, delta));
    }
}
