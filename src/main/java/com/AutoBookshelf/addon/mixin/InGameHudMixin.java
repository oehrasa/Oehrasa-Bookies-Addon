package com.AutoBookshelf.addon.mixin;

import com.AutoBookshelf.addon.modules.BundlePreview;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.client.gui.Gui.class)
public class InGameHudMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void onExtractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        BundlePreview module = Modules.get().get(BundlePreview.class);
        if (module == null || !module.isActive()) return;

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;

        int scaledWidth = mc.getWindow().getGuiScaledWidth();
        int scaledHeight = mc.getWindow().getGuiScaledHeight();
        int center = scaledWidth / 2;
        int hotbarY = scaledHeight - 19;

        for (int i = 0; i < 9; i++) {
            int posX = center - 90 + i * 20 + 2;
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            module.renderBundleOverlay(graphics, posX, hotbarY, stack);
        }
    }
}
