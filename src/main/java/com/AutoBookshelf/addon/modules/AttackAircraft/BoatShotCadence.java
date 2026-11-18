/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.AttackAircraft;

public final class BoatShotCadence {
    public static boolean mayDraw(BoatShotCycle.Stage stage, boolean rapid) {
        return stage == BoatShotCycle.Stage.IDLE || rapid && (stage == BoatShotCycle.Stage.WAIT_CLEAR
            || stage == BoatShotCycle.Stage.RETURNING);
    }

    public static int minimumDraw(boolean rapid, int shortDraw, int fullDraw) {
        return rapid ? Math.clamp(shortDraw, 3, 20) : Math.max(20, fullDraw);
    }

    public static boolean mayRelease(BoatShotCycle.Stage stage, boolean rapid, int sinceRelease) {
        return stage == BoatShotCycle.Stage.IDLE && (!rapid || sinceRelease >= 10);
    }
}
