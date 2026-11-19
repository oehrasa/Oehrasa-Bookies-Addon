/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.mixin;

import com.AutoBookshelf.addon.modules.GetPreview;
import com.AutoBookshelf.addon.modules.MapartNamer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
//? if >=26.2 {
import net.minecraft.client.gui.Hud;
//?} else {
/*import net.minecraft.client.gui.Gui;*/
//?}
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//? if >=26.2 {
@Mixin(Hud.class)
//?} else {
/*@Mixin(Gui.class)*/
//?}
public class InGameHudMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void onExtractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        MapartNamer mapart = Modules.get().get(MapartNamer.class);
        GetPreview module = Modules.get().get(GetPreview.class);
        // Both overlays live in the same hotbar loop, so only bail out when neither
        // module has anything to draw.
        if ((module == null || !module.isActive()) && (mapart == null || !mapart.isActive())) return;

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
            if (module != null && module.isActive()) {
                module.renderBundleOverlay(graphics, posX, hotbarY, stack);
            }
            if (mapart != null && mapart.isActive()) {
                mapart.renderIndexOverlay(graphics, posX, hotbarY, stack);
            }
        }
    }
}
