/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.mixin;

import com.AutoBookshelf.addon.modules.chesttracker.ChestTrackerModule;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ChestTrackerMerchantMixin {
    @Inject(method = "handleMerchantOffers", at = @At("TAIL"))
    private void chesttracker$afterMerchantOffers(ClientboundMerchantOffersPacket packet, CallbackInfo ci) {
        ChestTrackerModule tracker = Modules.get().get(ChestTrackerModule.class);
        if (tracker != null && tracker.getTrades() != null) tracker.getTrades().onOffers(packet.getContainerId(), packet.getOffers());
    }
}
