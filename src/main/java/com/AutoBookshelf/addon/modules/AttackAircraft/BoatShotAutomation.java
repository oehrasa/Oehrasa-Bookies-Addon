/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.AttackAircraft;

public final class BoatShotAutomation {
    public record Step(double x, double y, double z) {
    }

    public static boolean ceilingDanger(double boatY, double targetBottom, double targetTop, double ceiling, double gap) {
        return Double.isFinite(boatY + targetBottom + targetTop + ceiling + gap)
            && targetBottom > boatY + 1 && targetTop + gap > ceiling
            && (boatY >= ceiling - 12 || targetBottom >= ceiling - 8);
    }

    public static boolean cubeFits(int base, int bottom, int top) {
        return base >= bottom && (long) base + 2 <= top;
    }

    public static double downwardBurst(double requested, double eye, double targetTop) {
        return Math.min(requested, Math.max(.1, eye - targetTop - 2));
    }

    public static Step approach(double x, double y, double z, double horizontal, double vertical) {
        if (!Double.isFinite(x + y + z + horizontal + vertical) || horizontal <= 0 || vertical <= 0) return new Step(0, 0, 0);
        double dy = Math.clamp(y, -Math.min(45, vertical), Math.min(45, vertical));
        double cap = Math.min(Math.abs(dy) > 9 ? 4.75 : 9, horizontal), length = Math.hypot(x, z);
        double scale = length > cap ? cap / length : 1;
        return new Step(x * scale, dy, z * scale);
    }

    public static boolean retainDraw(boolean owned, boolean drawingBow, boolean ready, boolean releasing) {
        return owned && drawingBow && ready && !releasing;
    }

    public static int stoppedTicks(int previous, boolean confirmedWeb, double horizontal, double vertical) {
        return confirmedWeb && horizontal >= 0 && horizontal <= .035 && Math.abs(vertical) <= .08 ? Math.min(100, previous + 1) : 0;
    }
}
