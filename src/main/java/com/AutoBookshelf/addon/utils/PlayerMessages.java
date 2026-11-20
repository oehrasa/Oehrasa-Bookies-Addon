/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.utils;

import net.minecraft.network.chat.Component;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Client-only messages to the local player. The 26.x send path is
 * Player#sendSystemMessage/Player#sendOverlayMessage; the 1.21.x path is
 * DisplayClientMessage with the overlay flag, so the call sites stay shared.
 */
public final class PlayerMessages {
    private PlayerMessages() {
    }

    /**
     * Prints a message in the player's chat; silently dropped when not in a world.
     */
    public static void chat(Component message) {
        if (mc.player == null) return;
        //? if >=26.1 {
        mc.player.sendSystemMessage(message);
        //?} else {
        /*mc.player.displayClientMessage(message, false);
         *///?}
    }

    /**
     * Shows a message above the hotbar, briefly; silently dropped when not in a world.
     */
    public static void actionBar(Component message) {
        if (mc.player == null) return;
        //? if >=26.1 {
        mc.player.sendOverlayMessage(message);
        //?} else {
        /*mc.player.displayClientMessage(message, true);
         *///?}
    }
}
