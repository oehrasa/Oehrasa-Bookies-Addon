/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.LOSAT;

import java.util.List;

public final class LanceCrystalSafety {
    private static final double RADIUS = 12;
    private static final double SAMPLE_SPACING = 0.25;
    private static final int MAX_INTERVALS = 256;
    private static final double COORDINATE_LIMIT = 32_000_000;

    private LanceCrystalSafety() {
    }

    public record Point(double x, double y, double z) {
        private boolean valid() {
            return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                && Math.abs(x) <= COORDINATE_LIMIT && Math.abs(y) <= COORDINATE_LIMIT && Math.abs(z) <= COORDINATE_LIMIT;
        }
    }

    public interface DamageModel {

        double reduce(double rawDamage);

        double exact(Point ghost, Point explosion);
    }

    public record Result(double maximumDamage, Point threatPoint) {
    }

    public static boolean canPlaceAt(Point eye, Point baseMin, double reach) {
        if (eye == null || baseMin == null || !eye.valid() || !baseMin.valid() || !Double.isFinite(reach)
            || reach < 0 || reach > 64) return false;
        double dx = Math.max(Math.max(baseMin.x - eye.x, eye.x - baseMin.x - 1), 0);
        double dy = Math.max(Math.max(baseMin.y - eye.y, eye.y - baseMin.y - 1), 0);
        double dz = Math.max(Math.max(baseMin.z - eye.z, eye.z - baseMin.z - 1), 0);
        double allowed = reach + 1;
        return dx * dx + dy * dy + dz * dz < allowed * allowed;
    }

    public static Result estimateSegment(Point from, Point to, List<Point> explosions, double threshold, DamageModel model) {
        if (from == null || to == null || !from.valid() || !to.valid() || explosions == null || model == null
            || !Double.isFinite(threshold) || threshold < 0) return invalid(null);

        for (Point source : explosions) if (source == null || !source.valid()) return invalid(source);
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        double lengthSq = dx * dx + dy * dy + dz * dz;
        double length = Math.sqrt(lengthSq);
        int intervals = (int) Math.min(Integer.MAX_VALUE, Math.ceil(length / SAMPLE_SPACING));
        double maximum = 0;
        Point worst = null;
        for (Point source : explosions) {
            double closestT = lengthSq == 0 ? 0 : Math.clamp(
                ((source.x - from.x) * dx + (source.y - from.y) * dy + (source.z - from.z) * dz) / lengthSq, 0, 1);
            Point closest = interpolate(from, dx, dy, dz, closestT);
            double distance = distance(closest, source);
            if (distance > RADIUS) continue;
            double bound;
            try {
                bound = model.reduce(rawDamage(distance, 1));
            } catch (RuntimeException failure) {
                return invalid(source);
            }
            if (!Double.isFinite(bound) || bound < 0) return invalid(source);
            if (bound <= threshold) {
                if (bound > maximum) {
                    maximum = bound;
                    worst = source;
                }
                continue;
            }
            if (intervals > MAX_INTERVALS) return invalid(source);

            double damage = exact(model, closest, source);
            if (damage > maximum) {
                maximum = damage;
                worst = source;
            }
            if (maximum > threshold) return new Result(maximum, worst);

            for (int sample = 0; sample <= intervals; sample++) {
                double t = intervals == 0 ? 0 : (double) sample / intervals;
                if (Math.abs(t - closestT) <= 1.0E-12) continue;
                damage = exact(model, interpolate(from, dx, dy, dz, t), source);
                if (damage > maximum) {
                    maximum = damage;
                    worst = source;
                }
                if (maximum > threshold) return new Result(maximum, worst);
            }
        }
        return new Result(maximum, worst);
    }

    public static double rawDamage(double distance, double exposure) {
        if (!Double.isFinite(distance) || !Double.isFinite(exposure) || distance < 0 || exposure < 0 || exposure > 1) {
            return Double.POSITIVE_INFINITY;
        }
        if (distance > RADIUS) return 0;
        double impact = (1 - distance / RADIUS) * exposure;
        return (impact * impact + impact) * 42 + 1;
    }

    private static double exact(DamageModel model, Point ghost, Point source) {
        try {
            double result = model.exact(ghost, source);
            return Double.isFinite(result) && result >= 0 ? result : Double.POSITIVE_INFINITY;
        } catch (RuntimeException failure) {
            return Double.POSITIVE_INFINITY;
        }
    }

    private static Result invalid(Point source) {
        return new Result(Double.POSITIVE_INFINITY, source);
    }

    private static Point interpolate(Point start, double dx, double dy, double dz, double t) {
        return new Point(start.x + dx * t, start.y + dy * t, start.z + dz * t);
    }

    private static double distance(Point a, Point b) {
        double dx = a.x - b.x, dy = a.y - b.y, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
