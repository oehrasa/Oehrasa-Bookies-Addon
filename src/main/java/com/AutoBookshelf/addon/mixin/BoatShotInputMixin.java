/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.mixin;

import com.AutoBookshelf.addon.modules.AttackAircraft.BoatShot;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps the automatic bow draw alive while BoatShot is driving. Vanilla releases the item at the
 * end of the draw tick; without this the shot would be cancelled before it fires.
 */
@Mixin(Minecraft.class)
public abstract class BoatShotInputMixin {

    @WrapOperation(method = "handleKeybinds",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;releaseUsingItem(Lnet/minecraft/world/entity/player/Player;)V"))
    private void oehrasa$keepAutomaticBow(MultiPlayerGameMode manager, Player player, Operation<Void> original) {
        BoatShot shot = BoatShot.active();
        if (shot == null || !shot.retainAutomaticDraw(player)) original.call(manager, player);
    }
}
