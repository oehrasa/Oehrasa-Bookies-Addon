/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.AircraftPhase;

public final class BoatPhaseHash {
    private static final int INVERSE_19 = (int) 678152731L;

    public static int hash(double x, double y, double z, float yaw, float pitch) {
        int h = 3;
        h = 19 * h + Double.hashCode(x);
        h = 19 * h + Double.hashCode(y);
        h = 19 * h + Double.hashCode(z);
        h = 19 * h + Float.floatToIntBits(pitch);
        return 19 * h + Float.floatToIntBits(yaw);
    }

    public static double altitude(double x, double y, double z, float yaw, float pitch, int reference) {
        if (!Double.isFinite(x + y + z) || !Float.isFinite(yaw + pitch) || y < -2048 || y > 2048) return Double.NaN;
        int h = (reference - Float.floatToIntBits(yaw)) * INVERSE_19;
        h = (h - Float.floatToIntBits(pitch)) * INVERSE_19;
        h = (h - Double.hashCode(z)) * INVERSE_19;
        int yHash = h - 19 * (19 * 3 + Double.hashCode(x));
        long bits = Double.doubleToLongBits(y), high = bits >>> 32;
        double adjusted = Double.longBitsToDouble((bits & 0xffffffff00000000L) | ((yHash ^ high) & 0xffffffffL));
        return Double.isFinite(adjusted) && Math.abs(adjusted - y) <= .001
            && hash(x, adjusted, z, yaw, pitch) == reference ? adjusted : Double.NaN;
    }
}
