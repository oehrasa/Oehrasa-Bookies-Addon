/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.AttackAircraft;

import com.AutoBookshelf.addon.modules.LOSAT.LanceWebMath;

public final class BoatShotWebShape {
    public enum Pattern {FlightNet, Cube}

    public static final int HEIGHT = 3;
    public static final int CELLS = 9 * HEIGHT;
    public static final double HOVER = HEIGHT + 1.1;

    public record Offset(int x, int y, int z) {
    }

    public static java.util.List<Offset> flightCells(double x, double y, double z, double vx, double vy, double vz, double width, double height) {
        if (!Double.isFinite(x + y + z + vx + vy + vz + width + height) || width <= 0 || height <= 0
            || Math.abs(x) > 64 || Math.abs(y) > 64 || Math.abs(z) > 64) return java.util.List.of();
        int cx = (int) Math.floor(x), cz = (int) Math.floor(z);
        var cells = new java.util.ArrayList<Offset>(9);
        double speed = vx * vx + vz * vz;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                int bx = cx + dx, bz = cz + dz;
                double time = speed < 1e-6 ? 0 : Math.clamp(((bx + .5 - x) * vx + (bz + .5 - z) * vz) / speed, 0, 4);
                cells.add(new Offset(bx, (int) Math.floor(y + height * .5 + Math.clamp(vy * time, -1, 1)), bz));
            }
        cells.sort(java.util.Comparator.comparingDouble((Offset c) -> contactTime(c, x, y, z, vx, vy, vz, width, height))
            .thenComparingDouble(c -> Math.pow(c.x() + .5 - x, 2) + Math.pow(c.y() + .5 - y - height / 2, 2) + Math.pow(c.z() + .5 - z, 2)));
        return java.util.List.copyOf(cells);
    }

    public static LanceWebMath.Click click(
        LanceWebMath.Cell cell, double ex, double ey, double ez, double reach,
        boolean airPlace, java.util.function.Predicate<LanceWebMath.Cell> support) {
        var supported = LanceWebMath.placementClick(cell, ex, ey, ez, reach, false, support);
        if (supported != null) return supported;
        if (!airPlace || cell == null || support == null || !Double.isFinite(ex) || !Double.isFinite(ey)
            || !Double.isFinite(ez) || !Double.isFinite(reach) || reach <= 0
            || Math.abs(ex) > 32_000_000 || Math.abs(ey) > 32_000_000 || Math.abs(ez) > 32_000_000) return null;
        double x = Math.clamp(ex, cell.x() + .01, cell.x() + .99), y = Math.clamp(ey, cell.y() + .01, cell.y() + .99),
            z = Math.clamp(ez, cell.z() + .01, cell.z() + .99);
        if ((ex - x) * (ex - x) + (ey - y) * (ey - y) + (ez - z) * (ez - z) > Math.min(4.5, reach) * Math.min(4.5, reach)) return null;
        return new LanceWebMath.Click(cell, 0, -1, 0, x, y, z);
    }

    public static java.util.List<Offset> cells() {
        var cells = new java.util.ArrayList<Offset>(CELLS);
        for (int y = 0; y < HEIGHT; y++) cells.add(new Offset(0, y, 0));
        for (int x = -1; x <= 1; x++)
            for (int z = -1; z <= 1; z++)
                if (x != 0 || z != 0)
                    for (int y = 0; y < HEIGHT; y++) cells.add(new Offset(x, y, z));
        return java.util.List.copyOf(cells);
    }

    public static java.util.List<Offset> cells(double vx, double vz) {
        if (Math.hypot(vx, vz) < .05) return cells();
        int x = Math.abs(vx) >= Math.abs(vz) ? -(int) Math.signum(vx) : 0;
        int z = x == 0 ? -(int) Math.signum(vz) : 0;
        var ordered = new java.util.LinkedHashSet<Offset>();

        ordered.add(new Offset(x, 0, z));
        ordered.add(new Offset(x, 1, z));
        ordered.add(new Offset(0, 0, 0));
        ordered.add(new Offset(0, 1, 0));
        ordered.addAll(cells());
        return java.util.List.copyOf(ordered);
    }

    public static java.util.List<Offset> fastCells(double vx, double vz) {
        var ordered = new java.util.ArrayList<Offset>(CELLS);

        for (int y = 0; y < HEIGHT; y++) for (var cell : cells(vx, vz)) if (cell.y() == y) ordered.add(cell);
        return java.util.List.copyOf(ordered);
    }

    public static java.util.List<Offset> contactCells(double x, double y, double z, double vx, double vy, double vz, double width, double height) {
        var ordered = new java.util.ArrayList<>(cells());
        if (!Double.isFinite(x + y + z + vx + vy + vz + width + height) || width <= 0 || height <= 0) return fastCells(vx, vz);
        ordered.sort(java.util.Comparator.comparingDouble((Offset cell) -> contactTime(cell, x, y, z, vx, vy, vz, width, height))
            .thenComparingDouble(cell -> Math.pow(cell.x() + .5 - x, 2) + Math.pow(cell.y() + .5 - y - height / 2, 2) + Math.pow(cell.z() + .5 - z, 2)));
        return java.util.List.copyOf(ordered);
    }

    private static double contactTime(Offset c, double x, double y, double z, double vx, double vy, double vz, double width, double height) {
        double enter = 0, exit = 12;
        double[] p = {x, y, z}, v = {vx, vy, vz}, min = {c.x() - width / 2, c.y() - height, c.z() - width / 2}, max = {c.x() + 1 + width / 2, c.y() + 1, c.z() + 1 + width / 2};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(v[i]) < 1e-9) {
                if (p[i] < min[i] || p[i] > max[i]) return Double.POSITIVE_INFINITY;
            } else {
                double a = (min[i] - p[i]) / v[i], b = (max[i] - p[i]) / v[i];
                enter = Math.max(enter, Math.min(a, b));
                exit = Math.min(exit, Math.max(a, b));
                if (enter > exit) return Double.POSITIVE_INFINITY;
            }
        }
        return enter;
    }
}
