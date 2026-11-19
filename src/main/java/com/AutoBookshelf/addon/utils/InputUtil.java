/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.utils;

import com.mojang.blaze3d.platform.InputConstants;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Cross-version wrapper around {@link InputConstants#isKeyDown}. The 26.3 engine took GLFW off the
 * classpath and the method no longer takes a {@code Window}; older nodes do. Same query, one
 * helper, so call sites stay version-free.
 */
public final class InputUtil {
    private InputUtil() {
    }

    public static boolean isDown(int key) {
//? if >=26.3 {
        /*return InputConstants.isKeyDown(key);
*///?} else {
        return InputConstants.isKeyDown(mc.getWindow(), key);
//?}
    }
}
