/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.AttackAircraft;

import java.lang.ref.WeakReference;

public final class BoatShotEvents {

    private final WeakReference<?>[] player = {new WeakReference<>(null)};
    private int age = -1;
    private Object vehiclePacket;

    public boolean tick(Object current, int currentAge) {
        if (current == player[0].get() && age == currentAge) return false;
        if (current != player[0].get()) player[0] = new WeakReference<>(current);
        age = currentAge;
        return true;
    }

    public boolean vehicle(Object packet) {
        if (packet == vehiclePacket) return false;
        vehiclePacket = packet;
        return true;
    }

    public void reset() {
        player[0].clear();
        vehiclePacket = null;
        age = -1;
    }
}
