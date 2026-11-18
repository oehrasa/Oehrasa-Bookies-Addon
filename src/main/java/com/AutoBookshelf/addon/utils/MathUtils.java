/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.utils;

public class MathUtils {
    public static double toMapQuad(double v) {
        int j = (int) Math.floor((v + 64.0) / 128.0);
        int l = j * 128 + 128 / 2 - 64;
        return (double) l / 128;
    }
}
