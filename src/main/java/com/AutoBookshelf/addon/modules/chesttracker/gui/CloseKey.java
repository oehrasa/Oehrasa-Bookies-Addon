// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

/**
 * The inventory key (E, or whatever it's bound to in Controls) works as Esc on the mod's screens,
 * as it does on the game's own container screens. Not while typing in a text box, where it's a letter.
 */
final class CloseKey {
    private CloseKey() {
    }

    static KeyEvent asEscape(Screen screen, Minecraft client, KeyEvent input) {
        if (input.key() != GLFW.GLFW_KEY_ESCAPE && !(screen.getFocused() instanceof EditBox)
            && client.options.keyInventory.matches(input)) {
            return new KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, input.modifiers());
        }
        return input;
    }
}
