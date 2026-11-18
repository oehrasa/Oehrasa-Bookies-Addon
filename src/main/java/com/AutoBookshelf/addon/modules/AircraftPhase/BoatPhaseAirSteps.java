/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.AircraftPhase;

public final class BoatPhaseAirSteps {
    public static final double STEP = .95;
    public static final int MAX_PACKETS = 4;
    public static final double MAX_SPEED = STEP * MAX_PACKETS;
    public static final int EXTENDED_PACKETS = 5;
    public static final double EXTENDED_SPEED = STEP * EXTENDED_PACKETS;
    public static final int TRIAL_PACKETS = 10;
    public static final double TRIAL_SPEED = 9;

    public static int count(double horizontal) {
        return count(horizontal, MAX_PACKETS);
    }

    public static int count(double horizontal, int budget) {
        if (budget < 1 || budget > TRIAL_PACKETS || !Double.isFinite(horizontal)
            || horizontal < 0 || horizontal > STEP * budget + 1e-6) return 0;
        return Math.min(budget, Math.max(1, (int) Math.ceil(horizontal / STEP)));
    }
}
