/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.AircraftPhase;

public final class BoatPhaseVertical {
    public static final double STEP = 9;
    public static final int PACKETS = 5;
    public static final double PROVEN_SPEED = STEP * PACKETS;
    public static final double FAST_STEP = 19.8;
    public static final double MAX_SPEED = FAST_STEP * PACKETS;
    public static final double MAX_HORIZONTAL = BoatPhaseAirSteps.STEP * PACKETS;

    public static int count(double vertical) {
        if (!Double.isFinite(vertical) || Math.abs(vertical) > MAX_SPEED + 1e-6) return 0;
        double step = Math.abs(vertical) <= PROVEN_SPEED + 1e-6 ? STEP : FAST_STEP;
        return Math.min(PACKETS, Math.max(1, (int) Math.ceil(Math.abs(vertical) / step)));
    }
}
