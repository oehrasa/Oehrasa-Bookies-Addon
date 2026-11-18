/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.AttackAircraft;

public final class BoatShotDrawLock {
    private boolean wasDrawing, wasHeld, armed, pending;

    public void reset() {
        wasDrawing = wasHeld = armed = pending = false;
    }

    public boolean update(boolean drawing, boolean held, boolean automatic, boolean busy, boolean persistent, boolean valid) {
        boolean acquire = update(drawing, held, automatic, busy);
        return acquire && (!persistent || !valid);
    }

    public boolean update(boolean drawing, boolean held, boolean automatic, boolean busy) {
        if (held && !wasHeld) armed = true;
        if (drawing && !wasDrawing) {
            if (!automatic || armed) pending = true;
            armed = false;
        }
        wasDrawing = drawing;
        wasHeld = held;
        if (!drawing) pending = false;
        if (!pending || busy) return false;
        pending = false;
        return true;
    }
}
