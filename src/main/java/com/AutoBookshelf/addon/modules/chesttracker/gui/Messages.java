// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker.gui;

import net.minecraft.network.chat.Component;

/**
 * Messages to the player that only this client sees; dropped when not in a world.
 */
public final class Messages {
    private Messages() {
    }

    public static void chat(Component message) {
        com.AutoBookshelf.addon.utils.PlayerMessages.chat(message);
    }

    /**
     * Above the hotbar, briefly.
     */
    public static void actionBar(Component message) {
        com.AutoBookshelf.addon.utils.PlayerMessages.actionBar(message);
    }
}
