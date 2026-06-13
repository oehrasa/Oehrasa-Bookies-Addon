package com.AutoBookshelf.addon.mixin;

import com.AutoBookshelf.addon.modules.GetPreview;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public class InGameHudMixin {
    @Inject(method = "renderHotbar", at = @At("TAIL"))
    private void onRenderHotbar(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        GetPreview module = Modules.get().get(GetPreview.class);
        if (module == null || !module.isActive()) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerEntity player = mc.player;
        if (player == null) return;

        int scaledWidth = mc.getWindow().getScaledWidth();
        int scaledHeight = mc.getWindow().getScaledHeight();
        int center = scaledWidth / 2;
        int hotbarY = scaledHeight - 19;

        for (int i = 0; i < 9; i++) {
            int posX = center - 90 + i * 20 + 2;
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            module.renderBundleOverlay(context, posX, hotbarY, stack);
        }
        // optional offhand
    }
}
