/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.utils;

import net.minecraft.client.gui.screens.Screen;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Cross-version accessor for the open screen. 26.2 moved opening and reading the screen into
 * {@code Gui#screen()} and {@code Gui#setScreen(Screen)}; 26.1.2 keeps a public {@code mc.screen}
 * field and {@code mc.setScreen(...)}. Callers should use {@link #current()} and {@link #set(Screen)}
 * so the version gate lives in one place.
 */
public final class ScreenUtil {
    private ScreenUtil() {
    }

//? if >=26.2 {
    public static Screen current() {
        return mc.gui.screen();
    }

    public static void set(Screen screen) {
        mc.gui.setScreen(screen);
    }
//?} else {
    /*public static Screen current() {
        return mc.screen;
    }

    public static void set(Screen screen) {
        mc.setScreen(screen);
    }*/
//?}
}
