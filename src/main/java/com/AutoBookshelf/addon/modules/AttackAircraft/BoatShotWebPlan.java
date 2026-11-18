/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.AttackAircraft;

public final class BoatShotWebPlan {
    public record Point(double x, double y, double z) {
    }

    public static Point intercept(double x, double y, double z, double vx, double vy, double vz,
                                  double bx, double by, double bz, double speed, double vertical, double latency, int settle, double tickRatio) {
        if (!Double.isFinite(x + y + z + vx + vy + vz + bx + by + bz + speed + vertical + latency + tickRatio) || speed <= 0 || vertical <= 0)
            return null;
        double descent = Math.max(0, by - y - BoatShotWebShape.HOVER) / Math.min(45, vertical);
        double travel = descent, lead = Math.max(0, latency) + settle * tickRatio + 1;
        for (int i = 0; i < 5; i++) {
            double time = lead + travel * tickRatio;
            double px = x + vx * time, pz = z + vz * time;
            travel = Math.max(descent, Math.hypot(px - bx, pz - bz) / speed);
            if (travel > 40 || time > 50) return null;
        }
        double time = lead + travel * tickRatio;

        return new Point(x + vx * time, y + Math.clamp(vy * Math.min(time, 3), -1, 1), z + vz * time);
    }

    public static boolean canEnter(double x, double z, double vx, double vz, double horizon) {
        double speed = vx * vx + vz * vz;
        double time = speed < 1e-6 ? 0 : Math.clamp(-(x * vx + z * vz) / speed, 0, horizon);
        return Math.abs(x + vx * time) <= 2 && Math.abs(z + vz * time) <= 2;
    }
}
