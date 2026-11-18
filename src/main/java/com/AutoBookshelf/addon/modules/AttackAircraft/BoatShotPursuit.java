/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.AttackAircraft;

public final class BoatShotPursuit {

    public static final class Navigator {
        private int altitudeHold, side = 1;

        public void reset() {
            altitudeHold = 0;
            side = 1;
        }

        public Step next(double x, double y, double z, double vx, double vz,
                         double speed, double vertical, Clearance space) {
            if (!Double.isFinite(x + y + z + vx + vz + speed + vertical) || speed <= 0 || vertical <= 0) return Step.ZERO;
            if (altitudeHold > 0) {
                altitudeHold--;
                y = Math.max(0, y);
            }
            Step requested = desired(x, y, z, vx, vz, speed, vertical);
            if (space.clear(requested)) return requested;

            Step level = new Step(requested.x, 0, requested.z);
            if (level.length() > .01 && space.clear(level)) return level;

            for (double scale : new double[]{1, .5, .25}) {
                Step up = bounded(requested.x * scale, Math.min(3, vertical), requested.z * scale);
                if (space.clear(up)) {
                    altitudeHold = 12;
                    return up;
                }
            }
            Step up = new Step(0, Math.min(3, vertical), 0);
            if (space.clear(up)) {
                altitudeHold = 12;
                return up;
            }
            for (int direction : new int[]{side, -side}) {
                for (double angle : new double[]{45, 90, 135}) {
                    double a = Math.toRadians(angle * direction);
                    Step around = bounded(level.x * Math.cos(a) - level.z * Math.sin(a), 0,
                        level.x * Math.sin(a) + level.z * Math.cos(a));
                    if (around.length() > .01 && space.clear(around)) {
                        side = direction;
                        altitudeHold = 12;
                        return around;
                    }
                }
            }

            for (double scale : new double[]{.5, .25, .125}) {
                Step partial = new Step(requested.x * scale, requested.y * scale, requested.z * scale);
                if (space.clear(partial)) return partial;
            }
            return Step.ZERO;
        }
    }

    public record Step(double x, double y, double z) {
        public static final Step ZERO = new Step(0, 0, 0);

        public double length() {
            return Math.sqrt(x * x + y * y + z * z);
        }
    }

    public interface Clearance {
        boolean clear(Step step);
    }

    public static Step plan(double x, double y, double z, double vx, double vz,
                            double speed, double vertical, Clearance space) {
        return new Navigator().next(x, y, z, vx, vz, speed, vertical, space);
    }

    private static Step desired(double x, double y, double z, double vx, double vz,
                                double speed, double vertical) {
        if (!Double.isFinite(x + y + z + vx + vz + speed + vertical) || speed <= 0 || vertical <= 0) return Step.ZERO;

        double dx = vx + x * .35, dz = vz + z * .35;
        double h = Math.hypot(dx, dz), limit = Math.min(9, speed);
        if (h > limit) {
            dx *= limit / h;
            dz *= limit / h;
        }
        if (Math.hypot(x, z) < .15 && Math.hypot(vx, vz) < .05) dx = dz = 0;
        double dy = Math.max(-vertical, Math.min(vertical, y * .35));
        return bounded(dx, dy, dz);
    }

    private static Step bounded(double x, double y, double z) {
        double length = Math.sqrt(x * x + y * y + z * z), factor = length > 9 ? 9 / length : 1;
        return new Step(x * factor, y * factor, z * factor);
    }
}
