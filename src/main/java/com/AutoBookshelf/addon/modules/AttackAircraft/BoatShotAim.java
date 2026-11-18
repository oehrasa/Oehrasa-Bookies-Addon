/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.AttackAircraft;

public final class BoatShotAim {
    public record Solution(float yaw, float pitch, double ticks) {
    }

    public static double distanceFactor(double t) {
        int n = (int) t;
        double r = Math.pow(0.99, n);
        return (1 - r) / 0.01 + (t - n) * r;
    }

    public static double gravityDrop(double t) {
        int n = (int) t;
        double a = (1 - Math.pow(0.99, n)) / 0.01;
        return 5 * (n - a) + (t - n) * 0.05 * a;
    }

    public static double bowSpeed(int ticks) {
        double t = Math.max(0, ticks) / 20.0;
        return 3 * Math.min(1, (t * t + 2 * t) / 3);
    }

    public static Solution solveDiscrete(double x, double y, double z, double vx, double vy, double vz,
                                         double speed, double burst, double minimumPitch) {
        if (!Double.isFinite(x + y + z + vx + vy + vz + speed + burst + minimumPitch) || speed < .1) return null;
        for (int tick = 0; tick < 80; tick++) {
            double tx = x + vx * (tick + 1), ty = y + vy * (tick + 1), tz = z + vz * (tick + 1);
            double low = tick + 1e-5, high = tick + 1, lo = error(low, tx, ty, tz, 0, 0, 0, speed, burst);
            double hi = error(high, tx, ty, tz, 0, 0, 0, speed, burst);
            if (Math.signum(lo) == Math.signum(hi)) continue;
            for (int j = 0; j < 40; j++) {
                double mid = (low + high) / 2, e = error(mid, tx, ty, tz, 0, 0, 0, speed, burst);
                if (Math.signum(e) == Math.signum(lo)) {
                    low = mid;
                    lo = e;
                } else high = mid;
            }
            double time = (low + high) / 2, a = distanceFactor(time);
            double dx = tx / a, dz = tz / a, dy = (ty + gravityDrop(time)) / a - burst;
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
            if (Math.abs(pitch) >= minimumPitch && pitch * burst < 0)
                return new Solution((float) Math.toDegrees(Math.atan2(-dx, dz)), pitch, time);
        }
        return null;
    }

    public static Solution solve(double x, double y, double z, double vx, double vy, double vz,
                                 double speed, double burst, double minimumPitch) {
        if (!Double.isFinite(x + y + z + vx + vy + vz + speed + burst + minimumPitch) || speed < 0.1) return null;
        double previousT = 0.025, previous = error(previousT, x, y, z, vx, vy, vz, speed, burst);
        for (int i = 1; i <= 800; i++) {
            double t = i * 0.1;
            double value = error(t, x, y, z, vx, vy, vz, speed, burst);
            if (Math.signum(value) != Math.signum(previous)) {
                double low = previousT, high = t, lowValue = previous;
                for (int j = 0; j < 40; j++) {
                    double mid = (low + high) / 2;
                    double e = error(mid, x, y, z, vx, vy, vz, speed, burst);
                    if (Math.signum(e) == Math.signum(lowValue)) {
                        low = mid;
                        lowValue = e;
                    } else high = mid;
                }
                double time = (low + high) / 2, a = distanceFactor(time);
                double dx = (x + vx * time) / a, dz = (z + vz * time) / a;
                double dy = (y + vy * time + gravityDrop(time)) / a - burst;
                float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
                if (Math.abs(pitch) >= minimumPitch && pitch * burst < 0) {
                    return new Solution((float) Math.toDegrees(Math.atan2(-dx, dz)), pitch, time);
                }
            }
            previousT = t;
            previous = value;
        }
        return null;
    }

    private static double error(double t, double x, double y, double z, double vx, double vy, double vz, double speed, double burst) {
        double a = distanceFactor(t), dx = (x + vx * t) / a, dz = (z + vz * t) / a;
        double dy = (y + vy * t + gravityDrop(t)) / a - burst;
        return dx * dx + dy * dy + dz * dz - speed * speed;
    }
}
