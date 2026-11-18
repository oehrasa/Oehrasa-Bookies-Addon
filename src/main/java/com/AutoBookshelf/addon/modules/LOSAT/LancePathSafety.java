/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.LOSAT;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

final class LancePathSafety {
    private static final int SEARCH_STEPS = 20;
    private static final double WITHDRAW_FRACTION = 1e-6;

    private LancePathSafety() {
    }

    record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    }

    record Detour(double sideX, double sideZ, double rise) {
    }

    static Bounds swept(Bounds start, double dx, double dy, double dz) {
        return new Bounds(start.minX + Math.min(0, dx), start.minY + Math.min(0, dy), start.minZ + Math.min(0, dz),
            start.maxX + Math.max(0, dx), start.maxY + Math.max(0, dy), start.maxZ + Math.max(0, dz));
    }

    static double safeFraction(Bounds start, double dx, double dy, double dz, Predicate<Bounds> clear) {
        if (!valid(start) || !Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz) || clear == null) return 0;
        Bounds full = swept(start, dx, dy, dz);
        if (!valid(full) || !clear.test(start)) return 0;
        if (clear.test(full)) return 1;

        double low = 0, high = 1;
        for (int i = 0; i < SEARCH_STEPS; i++) {
            double middle = (low + high) * 0.5;
            if (clear.test(swept(start, dx * middle, dy * middle, dz * middle))) low = middle;
            else high = middle;
        }

        return Math.max(0, low - WITHDRAW_FRACTION);
    }

    static double safeFraction(Bounds start, double dx, double dy, double dz, Iterable<Bounds> obstacles) {
        if (!valid(start) || !Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)
            || obstacles == null || !valid(swept(start, dx, dy, dz))) return 0;
        double first = 1;
        for (Bounds obstacle : obstacles) {
            if (!valid(obstacle)) return 0;
            first = Math.min(first, entryFraction(start, dx, dy, dz, obstacle));
            if (first == 0) return 0;
        }
        return first == 1 ? 1 : Math.max(0, first - WITHDRAW_FRACTION);
    }

    private static double entryFraction(Bounds start, double dx, double dy, double dz, Bounds obstacle) {
        if (intersects(start, obstacle)) return 0;
        double enter = Math.max(axisEntry(start.minX, start.maxX, obstacle.minX, obstacle.maxX, dx),
            Math.max(axisEntry(start.minY, start.maxY, obstacle.minY, obstacle.maxY, dy),
                axisEntry(start.minZ, start.maxZ, obstacle.minZ, obstacle.maxZ, dz)));
        double leave = Math.min(axisExit(start.minX, start.maxX, obstacle.minX, obstacle.maxX, dx),
            Math.min(axisExit(start.minY, start.maxY, obstacle.minY, obstacle.maxY, dy),
                axisExit(start.minZ, start.maxZ, obstacle.minZ, obstacle.maxZ, dz)));

        if (!(enter < leave && leave > 0 && enter < 1)) return 1;
        return Math.max(0, enter);
    }

    private static double axisEntry(double min, double max, double obstacleMin, double obstacleMax, double delta) {
        if (delta > 0) return (obstacleMin - max) / delta;
        if (delta < 0) return (obstacleMax - min) / delta;
        return min < obstacleMax && max > obstacleMin ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
    }

    private static double axisExit(double min, double max, double obstacleMin, double obstacleMax, double delta) {
        if (delta > 0) return (obstacleMax - min) / delta;
        if (delta < 0) return (obstacleMin - max) / delta;
        return min < obstacleMax && max > obstacleMin ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
    }

    static boolean intersects(Bounds a, Bounds b) {
        return valid(a) && valid(b)
            && a.minX < b.maxX && a.maxX > b.minX
            && a.minY < b.maxY && a.maxY > b.minY
            && a.minZ < b.maxZ && a.maxZ > b.minZ;
    }

    static Detour detour(Bounds start, double wantDx, double wantDy, double wantDz, Predicate<Bounds> clear) {
        if (clear == null) return null;
        return chooseDetour(start, wantDx, wantDy, wantDz, (body, dx, dy, dz) -> safeFraction(body, dx, dy, dz, clear) == 1);
    }

    static Detour detour(Bounds start, double wantDx, double wantDy, double wantDz, Iterable<Bounds> obstacles) {
        if (obstacles == null) return null;
        List<Bounds> snapshot = new ArrayList<>();
        for (Bounds obstacle : obstacles) {
            if (!valid(obstacle)) return null;
            snapshot.add(obstacle);
        }
        return chooseDetour(start, wantDx, wantDy, wantDz, (body, dx, dy, dz) -> safeFraction(body, dx, dy, dz, snapshot) == 1);
    }

    static boolean allowDetour(boolean strikeOrContinuation, double desiredDy) {
        return !strikeOrContinuation && Double.isFinite(desiredDy) && desiredDy > 0.2;
    }

    @FunctionalInterface
    private interface SweepClear {
        boolean clear(Bounds start, double dx, double dy, double dz);
    }

    private static Detour chooseDetour(Bounds start, double wantDx, double wantDy, double wantDz, SweepClear clear) {
        if (!valid(start) || !Double.isFinite(wantDx) || !Double.isFinite(wantDy) || !Double.isFinite(wantDz)) return null;
        Detour best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (double radius : new double[]{0, 2, 4}) {
            for (int i = 0; i < (radius == 0 ? 1 : 8); i++) {
                double angle = i * Math.PI / 4;
                double sideX = Math.cos(angle) * radius, sideZ = Math.sin(angle) * radius;
                Bounds side = new Bounds(start.minX + sideX, start.minY, start.minZ + sideZ,
                    start.maxX + sideX, start.maxY, start.maxZ + sideZ);
                if (!clear.clear(start, sideX, 0, sideZ) || !clear.clear(side, 0, 3, 0)) continue;
                double x = sideX - wantDx, y = 3 - wantDy, z = sideZ - wantDz;
                double score = x * x + y * y + z * z + radius * radius * 0.2;
                if (score < bestScore) {
                    bestScore = score;
                    best = new Detour(sideX, sideZ, 3);
                }
            }
        }
        return best;
    }

    private static boolean valid(Bounds bounds) {
        return bounds != null
            && Double.isFinite(bounds.minX) && Double.isFinite(bounds.minY) && Double.isFinite(bounds.minZ)
            && Double.isFinite(bounds.maxX) && Double.isFinite(bounds.maxY) && Double.isFinite(bounds.maxZ)
            && bounds.minX < bounds.maxX && bounds.minY < bounds.maxY && bounds.minZ < bounds.maxZ;
    }
}
