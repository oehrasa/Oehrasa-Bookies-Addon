/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.LOSAT;

final class LanceBeatTiming {
    private boolean anchored;
    private int lastStrikeTick;

    void reset() {
        anchored = false;
    }

    void strike(int tick) {
        anchored = true;
        lastStrikeTick = tick;
    }

    boolean due(int tick, int period) {
        return remaining(tick, period) == 0;
    }

    int remaining(int tick, int period) {
        if (!anchored) return 0;
        int interval = Math.max(1, period);
        long elapsed = (long) tick - lastStrikeTick;
        if (elapsed < 0) return interval;
        return elapsed >= interval ? 0 : (int) (interval - elapsed);
    }
}
