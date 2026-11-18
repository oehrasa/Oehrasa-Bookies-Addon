/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.AttackAircraft;

public final class BoatShotUseBudget {
    public static final int LIMIT = 9;
    public static final long WINDOW = 310_000_000L;
    private long start;
    private int used;
    private Object lastPacket;

    public void reset() {
        start = 0;
        used = 0;
        lastPacket = null;
    }

    private void advance(long now) {
        if (used == 0 || now - start >= WINDOW || now < start) {
            start = now;
            used = 0;
        }
    }

    public int available(long now) {
        advance(now);
        return Math.max(0, LIMIT - used);
    }

    public void sent(Object packet, long now) {
        if (packet == lastPacket) return;
        lastPacket = packet;
        advance(now);
        used++;
    }
}
