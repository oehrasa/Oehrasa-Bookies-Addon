/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.AttackAircraft;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;

public final class BoatShotPackets {
    public static boolean preserveQueuedRelease(Packet<?> packet, boolean automatic, boolean sameBow, int slot) {
        if (!sameBow) return false;
//? if >=26.3 {
        /*return automatic && packet instanceof ServerboundUseItemPacket use && use.hand() == InteractionHand.MAIN_HAND
            || packet instanceof ServerboundSetCarriedItemPacket sync && sync.getSlot() == slot;
*///?} else {
        return automatic && packet instanceof ServerboundUseItemPacket use && use.getHand() == InteractionHand.MAIN_HAND
            || packet instanceof ServerboundSetCarriedItemPacket sync && sync.getSlot() == slot;
//?}
    }
}
