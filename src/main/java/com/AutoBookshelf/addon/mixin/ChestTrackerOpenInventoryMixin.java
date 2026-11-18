/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.mixin;

import com.AutoBookshelf.addon.modules.chesttracker.ChestTrackerModule;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class ChestTrackerOpenInventoryMixin {
    @Inject(method = "sendOpenInventory", at = @At("HEAD"))
    private void chesttracker$onOpenRidingInventory(CallbackInfo ci) {
        ChestTrackerModule tracker = Modules.get().get(ChestTrackerModule.class);
        if (tracker == null || tracker.getInteractions() == null) return;
        Entity vehicle = ((LocalPlayer) (Object) this).getVehicle();
        if (vehicle != null) tracker.getInteractions().set(vehicle);
    }
}
