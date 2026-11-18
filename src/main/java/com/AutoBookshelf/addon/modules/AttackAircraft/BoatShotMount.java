/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.AttackAircraft;

public final class BoatShotMount {
    private int candidate = -1, mounted = -1, until;
    private boolean airborne;

    public void begin(int id, int tick) {
        candidate = id;
        until = tick + 20;
        airborne = false;
    }

    public boolean window(int tick) {
        return candidate != -1 && tick <= until;
    }

    public void sentGround(boolean onGround, int tick) {
        if (mounted != -1) return;
        if (onGround) airborne = false;
        else if (window(tick)) airborne = true;
    }

    public void seat(int id, int tick) {
        if (id == mounted) return;
        airborne = airborne && mounted == -1 && id == candidate && window(tick);
        mounted = id;
        if (id == -1) reset();
    }

    public boolean ready() {
        return mounted != -1 && airborne;
    }

    public void reset() {
        candidate = mounted = -1;
        airborne = false;
    }
}
