/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.LOSAT;

final class LanceMovementMath {
    private LanceMovementMath() {
    }

    record Step(double x, double y, double z) {
    }

    static Step approachStep(double dx, double dy, double dz, double maxH, double maxV) {
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)
            || !Double.isFinite(maxH) || !Double.isFinite(maxV) || maxH <= 0 || maxV < 0 || maxH > 64) return new Step(0, 0, 0);
        double y = Math.clamp(dy, -Math.min(maxH, maxV), Math.min(maxH, maxV));
        double horizontalBudget = Math.sqrt(Math.max(0, maxH * maxH - y * y));
        double h = Math.hypot(dx, dz);
        double scale = h > horizontalBudget ? horizontalBudget / h : 1;
        return new Step(dx * scale, y, dz * scale);
    }

    static double capScale(double dx, double dy, double dz, double maxH, double maxV) {
        double scale = 1;
        double h = Math.hypot(dx, dz);
        if (h > maxH) scale = maxH / h;
        if (Math.abs(dy) * scale > maxV) scale = Math.min(scale, maxV / Math.abs(dy));
        return scale;
    }

    static double followFraction(double rx, double ry, double rz, double dx, double dy, double dz, double leash) {
        double radiusSq = rx * rx + ry * ry + rz * rz;
        double limitSq = Math.max(radiusSq, leash * leash);
        double a = dx * dx + dy * dy + dz * dz;
        if (a < 1e-12) return 1;
        double b = 2 * (rx * dx + ry * dy + rz * dz);
        double c = radiusSq - limitSq;
        double root = (-b + Math.sqrt(Math.max(0, b * b - 4 * a * c))) / (2 * a);
        return Math.max(0, Math.min(1, root));
    }

    static double nextRecoveryVelocity(double previous, double gravity) {
        return (previous - gravity) * 0.98;
    }

    static boolean plausibleStrikeAge(int age, int pingTicks) {
        int minimum = Math.max(1, pingTicks);
        return age >= minimum && age <= minimum + 6;
    }
}
