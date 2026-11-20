/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.utils;

import net.minecraft.network.protocol.Packet;
//? if <26.1 {
/*import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
*///?}

/**
 * Version-independent packet helpers.
 */
public final class PacketUtils {
    private PacketUtils() {
    }

    /**
     * True when the packet is an attack on an entity. The 26.x split of the attack into
     * its own packet does not exist on the 1.21.x protocol, where an attack is a
     * ServerboundInteractPacket with the ATTACK action.
     */
    //? if >=26.1 {
    public static boolean isAttack(Packet<?> packet) {
        return packet instanceof net.minecraft.network.protocol.game.ServerboundAttackPacket;
    }
    //?} else {
    /*public static boolean isAttack(Packet<?> packet) {
        if (!(packet instanceof ServerboundInteractPacket interact)) return false;
        AttackDetector detector = new AttackDetector();
        interact.dispatch(detector);
        return detector.attack;
    }

    private static final class AttackDetector implements ServerboundInteractPacket.Handler {
        private boolean attack;

        @Override
        public void onInteraction(InteractionHand hand) {
        }

        @Override
        public void onInteraction(InteractionHand hand, Vec3 location) {
        }

        @Override
        public void onAttack() {
            attack = true;
        }
    }
    *///?}
}
