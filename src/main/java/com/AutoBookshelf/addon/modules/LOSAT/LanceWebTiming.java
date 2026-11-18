/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.LOSAT;

final class LanceWebTiming {
    private LanceWebTiming() {
    }

    static boolean canStart(int now, int lastVolley, int period, boolean couchReady) {
        if (lastVolley < 0) return true;
        long elapsed = (long) now - lastVolley;
        return couchReady && elapsed >= Math.max(10, period);
    }
}
