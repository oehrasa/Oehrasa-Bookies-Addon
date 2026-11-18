/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.LOSAT;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

public final class LanceWebMath {
    private static final double EPSILON = 1.0E-7;
    private static final double FUTURE_TICKS = 6;
    private static final double COORDINATE_LIMIT = 32_000_000;
    private static final int MAX_CANDIDATES = 64;

    private LanceWebMath() {
    }

    public record Cell(int x, int y, int z) {
    }

    public record Click(Cell clicked, int sideX, int sideY, int sideZ, double hitX, double hitY, double hitZ) {
    }

    public static Click placementClick(Cell cell, double eyeX, double eyeY, double eyeZ, double reach,
                                       boolean airPlace, Predicate<Cell> stableSupport) {
        if (cell == null || stableSupport == null || !finite(eyeX, eyeY, eyeZ, reach) || reach <= 0
            || !bounded(eyeX, eyeY, eyeZ, cell.x, cell.y, cell.z)) return null;
        reach = Math.min(reach, 6);
        double cx = cell.x + 0.5, cy = cell.y + 0.5, cz = cell.z + 0.5;
        Click best = null;
        double relevance = -Double.MAX_VALUE;
        for (int axis = 0; axis < 3; axis++) {
            for (int sign : new int[]{-1, 1}) {
                int dx = axis == 0 ? sign : 0, dy = axis == 1 ? sign : 0, dz = axis == 2 ? sign : 0;
                Cell support = new Cell(cell.x + dx, cell.y + dy, cell.z + dz);
                if (!stableSupport.test(support)) continue;
                double hx = cx + dx * 0.5, hy = cy + dy * 0.5, hz = cz + dz * 0.5;
                if (distanceSq(hx, hy, hz, eyeX, eyeY, eyeZ) > reach * reach) continue;
                double score = (cx - eyeX) * dx + (cy - eyeY) * dy + (cz - eyeZ) * dz;
                if (score > relevance) {
                    relevance = score;
                    best = new Click(support, -dx, -dy, -dz, hx, hy, hz);
                }
            }
        }
        if (best != null) return best;
        return airPlace && distanceSq(cx, cy, cz, eyeX, eyeY, eyeZ) <= reach * reach
            ? new Click(cell, 0, -1, 0, cx, cy, cz) : null;
    }

    private static double distanceSq(double ax, double ay, double az, double bx, double by, double bz) {
        double x = ax - bx, y = ay - by, z = az - bz;
        return x * x + y * y + z * z;
    }

    public record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public Bounds offset(double x, double y, double z) {
            return new Bounds(minX + x, minY + y, minZ + z, maxX + x, maxY + y, maxZ + z);
        }

        private boolean valid() {
            return finite(minX, minY, minZ, maxX, maxY, maxZ)
                && bounded(minX, minY, minZ, maxX, maxY, maxZ)
                && maxX > minX && maxY > minY && maxZ > minZ
                && maxX - minX <= 16 && maxY - minY <= 16 && maxZ - minZ <= 16;
        }
    }

    private record Interval(double enter, double exit) {
    }

    private record Candidate(Cell cell, boolean centerPath, double enter, double duration, double reachSq) {
    }

    private record CaptureCandidate(Cell cell, int priority, double arrival, double distanceSq) {
    }

    public static List<Cell> candidates(Bounds serverBox, double vx, double vy, double vz, double leadTicks,
                                        double eyeX, double eyeY, double eyeZ, double reach) {
        if (serverBox == null || !serverBox.valid() || !finite(vx, vy, vz, leadTicks, eyeX, eyeY, eyeZ, reach)
            || !bounded(eyeX, eyeY, eyeZ) || reach <= 0) {
            return List.of();
        }

        reach = Math.min(reach, 6);
        leadTicks = Math.max(0, Math.min(leadTicks, 20));
        Bounds start = serverBox.offset(vx * leadTicks, vy * leadTicks, vz * leadTicks);
        Bounds end = start.offset(vx * FUTURE_TICKS, vy * FUTURE_TICKS, vz * FUTURE_TICKS);
        if (!start.valid() || !end.valid()) return List.of();
        double centerX = (start.minX + start.maxX) * 0.5;
        double centerY = (start.minY + start.maxY) * 0.5;
        double centerZ = (start.minZ + start.maxZ) * 0.5;

        int x0 = floor(Math.max(Math.min(start.minX, end.minX), eyeX - reach - EPSILON));
        int y0 = floor(Math.max(Math.min(start.minY, end.minY), eyeY - reach - EPSILON));
        int z0 = floor(Math.max(Math.min(start.minZ, end.minZ), eyeZ - reach - EPSILON));
        int x1 = floor(Math.min(Math.max(start.maxX, end.maxX) - EPSILON, eyeX + reach));
        int y1 = floor(Math.min(Math.max(start.maxY, end.maxY) - EPSILON, eyeY + reach));
        int z1 = floor(Math.min(Math.max(start.maxZ, end.maxZ) - EPSILON, eyeZ + reach));
        List<Candidate> ranked = new ArrayList<>();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    double distance = reachSq(x, y, z, eyeX, eyeY, eyeZ);
                    if (distance > reach * reach + EPSILON) continue;
                    Interval body = interval(start.minX, start.minY, start.minZ, start.maxX, start.maxY, start.maxZ, vx, vy, vz, x, y, z);
                    if (body == null) continue;
                    Interval center = interval(centerX, centerY, centerZ, centerX, centerY, centerZ, vx, vy, vz, x, y, z);
                    ranked.add(new Candidate(new Cell(x, y, z), center != null, body.enter, body.exit - body.enter, distance));
                }
            }
        }
        ranked.sort(Comparator.comparing((Candidate c) -> !c.centerPath)
            .thenComparingDouble(Candidate::enter)
            .thenComparing(Comparator.comparingDouble(Candidate::duration).reversed())
            .thenComparingDouble(Candidate::reachSq)
            .thenComparingInt(c -> c.cell.x).thenComparingInt(c -> c.cell.y).thenComparingInt(c -> c.cell.z));
        return ranked.stream().limit(MAX_CANDIDATES).map(Candidate::cell).toList();
    }

    public static List<Cell> captureCandidates(Bounds serverBox, double vx, double vy, double vz, double leadTicks,
                                               double eyeX, double eyeY, double eyeZ, double reach, boolean contained) {
        if (serverBox == null || !serverBox.valid() || !finite(vx, vy, vz, leadTicks, eyeX, eyeY, eyeZ, reach)
            || !bounded(eyeX, eyeY, eyeZ) || reach <= 0) return List.of();
        reach = Math.min(reach, 6);
        leadTicks = Math.max(0, Math.min(leadTicks, 20));
        double future = contained ? Math.max(8, leadTicks + 4) : leadTicks + FUTURE_TICKS;
        if (!serverBox.offset(vx * future, vy * future, vz * future).valid()) return List.of();

        double centerX = (serverBox.minX + serverBox.maxX) * 0.5;
        double centerY = (serverBox.minY + serverBox.maxY) * 0.5;
        double centerZ = (serverBox.minZ + serverBox.maxZ) * 0.5;
        int shellX = floor(centerX), shellY = floor(serverBox.minY), shellZ = floor(centerZ);
        double aimTick = contained ? 0 : leadTicks;
        int x0 = floor(eyeX - reach - EPSILON), x1 = floor(eyeX + reach);
        int y0 = floor(eyeY - reach - EPSILON), y1 = floor(eyeY + reach);
        int z0 = floor(eyeZ - reach - EPSILON), z1 = floor(eyeZ + reach);
        List<CaptureCandidate> ranked = new ArrayList<>();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    double distance = reachSq(x, y, z, eyeX, eyeY, eyeZ);
                    if (distance > reach * reach + EPSILON) continue;
                    Interval body = interval(serverBox.minX, serverBox.minY, serverBox.minZ,
                        serverBox.maxX, serverBox.maxY, serverBox.maxZ, vx, vy, vz, x, y, z, future);
                    Interval center = interval(centerX, centerY, centerZ, centerX, centerY, centerZ,
                        vx, vy, vz, x, y, z, future);
                    Interval shell = interval(shellX - 1, shellY, shellZ - 1, shellX + 2, shellY + 3, shellZ + 2,
                        vx, vy, vz, x, y, z, future);
                    if (body == null && shell == null) continue;
                    int priority;
                    if (contained) {
                        priority = body != null && body.enter <= EPSILON ? 0 : center != null ? 1 : body != null ? 2 : 3;
                    } else {
                        priority = center != null ? 0 : body != null ? 1 : 2;
                    }
                    Interval contact = center != null ? center : body != null ? body : shell;
                    double arrival = Math.max(contact.enter - aimTick, Math.max(aimTick - contact.exit, 0));
                    ranked.add(new CaptureCandidate(new Cell(x, y, z), priority, arrival, distance));
                }
            }
        }
        ranked.sort(Comparator.comparingInt(CaptureCandidate::priority)
            .thenComparingDouble(CaptureCandidate::arrival)
            .thenComparingDouble(CaptureCandidate::distanceSq)
            .thenComparingInt(c -> c.cell.x).thenComparingInt(c -> c.cell.y).thenComparingInt(c -> c.cell.z));
        return ranked.stream().limit(MAX_CANDIDATES).map(CaptureCandidate::cell).toList();
    }

    public static boolean isEscaping(Bounds serverBox, double vx, double vy, double vz, Predicate<Cell> isWeb) {
        if (serverBox == null || !serverBox.valid() || isWeb == null || !finite(vx, vy, vz)
            || vx * vx + vy * vy + vz * vz <= 0.01 * 0.01
            || !substantialCoverage(serverBox, isWeb)) return false;
        for (int tick = 1; tick <= 8; tick++) {
            Bounds future = serverBox.offset(vx * tick, vy * tick, vz * tick);
            if (!future.valid()) return false;
            if (!substantialCoverage(future, isWeb)) return true;
        }
        return false;
    }

    public static boolean hasCurrentWebCoverage(Bounds serverBox, Predicate<Cell> isWeb) {
        return serverBox != null && serverBox.valid() && isWeb != null && substantialCoverage(serverBox, isWeb);
    }

    public static boolean hasSustainedWebCoverage(Bounds serverBox, double vx, double vy, double vz, Predicate<Cell> isWeb) {
        if (serverBox == null || !serverBox.valid() || isWeb == null || !finite(vx, vy, vz)
            || vx * vx + vz * vz > 0.25 * 0.25 || Math.abs(vy) > 0.25) return false;
        for (int tick = 0; tick <= 3; tick++) {
            if (!substantialCoverage(serverBox.offset(vx * tick, vy * tick, vz * tick), isWeb)) return false;
        }
        return true;
    }

    private static boolean substantialCoverage(Bounds box, Predicate<Cell> isWeb) {
        double horizontalArea = (box.maxX - box.minX) * (box.maxZ - box.minZ);
        double minimumVerticalOverlap = Math.min(0.25, (box.maxY - box.minY) * 0.5);
        double coveredArea = 0;
        for (int x = floor(box.minX + EPSILON); x <= floor(box.maxX - EPSILON); x++) {
            for (int z = floor(box.minZ + EPSILON); z <= floor(box.maxZ - EPSILON); z++) {
                for (int y = floor(box.minY + EPSILON); y <= floor(box.maxY - EPSILON); y++) {
                    if (Math.min(box.maxY, y + 1.0) - Math.max(box.minY, y) + EPSILON < minimumVerticalOverlap
                        || !isWeb.test(new Cell(x, y, z))) continue;

                    coveredArea += (Math.min(box.maxX, x + 1.0) - Math.max(box.minX, x))
                        * (Math.min(box.maxZ, z + 1.0) - Math.max(box.minZ, z));
                    break;
                }
            }
        }
        return coveredArea + EPSILON >= horizontalArea * 0.5;
    }

    private static Interval interval(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                                     double vx, double vy, double vz, int x, int y, int z) {
        return interval(minX, minY, minZ, maxX, maxY, maxZ, vx, vy, vz, x, y, z, FUTURE_TICKS);
    }

    private static Interval interval(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                                     double vx, double vy, double vz, int x, int y, int z, double futureTicks) {
        double[] times = {0, futureTicks};
        if (!clip(times, minX, maxX, vx, x) || !clip(times, minY, maxY, vy, y) || !clip(times, minZ, maxZ, vz, z)) return null;
        return times[1] - times[0] > EPSILON ? new Interval(times[0], times[1]) : null;
    }

    private static boolean clip(double[] times, double min, double max, double velocity, int cell) {
        if (Math.abs(velocity) < EPSILON) {
            if (min == max) return min >= cell && min < cell + 1.0;
            return max > cell + EPSILON && min < cell + 1.0 - EPSILON;
        }
        double a = (cell - max) / velocity, b = (cell + 1.0 - min) / velocity;
        times[0] = Math.max(times[0], Math.min(a, b));
        times[1] = Math.min(times[1], Math.max(a, b));
        return times[1] > times[0] + EPSILON;
    }

    private static double reachSq(int x, int y, int z, double eyeX, double eyeY, double eyeZ) {
        double dx = Math.max(Math.max(x - eyeX, eyeX - (x + 1.0)), 0);
        double dy = Math.max(Math.max(y - eyeY, eyeY - (y + 1.0)), 0);
        double dz = Math.max(Math.max(z - eyeZ, eyeZ - (z + 1.0)), 0);
        return dx * dx + dy * dy + dz * dz;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }

    private static boolean bounded(double... values) {
        for (double value : values) if (Math.abs(value) > COORDINATE_LIMIT) return false;
        return true;
    }
}
