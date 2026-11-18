/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.LOSAT;

final class LanceAttackMath {
    static final double EYE = 0.4;
    private static final double MIN_DAMAGE_CLOSING = 4.6f / 20d;

    private LanceAttackMath() {
    }

    record Point(double x, double y, double z) {
        Point add(double dx, double dy, double dz) {
            return new Point(x + dx, y + dy, z + dz);
        }

        Point add(Point other) {
            return add(other.x, other.y, other.z);
        }

        Point subtract(Point other) {
            return add(-other.x, -other.y, -other.z);
        }

        Point multiply(double scale) {
            return new Point(x * scale, y * scale, z * scale);
        }

        double dot(Point other) {
            return x * other.x + y * other.y + z * other.z;
        }

        double lengthSquared() {
            return dot(this);
        }
    }

    record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    }

    record Lane(Point impact, Point launch, Point rest, Point axis, Point contact, boolean horizontal) {
    }

    static Lane vertical(Bounds target, double strikeGap, double dip, double restGap) {
        if (!valid(target, strikeGap, dip, restGap)) return null;
        Point surface = new Point(midpoint(target.minX, target.maxX), target.maxY, midpoint(target.minZ, target.maxZ));
        return lane(surface, new Point(0, -1, 0), strikeGap, dip, restGap, false);
    }

    static Lane horizontal(Bounds target, double axisX, double axisZ, double strikeGap, double dip, double restGap) {
        if (!valid(target, strikeGap, dip, restGap) || !Double.isFinite(axisX) || !Double.isFinite(axisZ)) return null;
        double length = Math.hypot(axisX, axisZ);
        if (!Double.isFinite(length) || length < 1e-9) return null;
        Point axis = new Point(axisX / length, 0, axisZ / length);
        double halfX = (target.maxX - target.minX) * 0.5, halfZ = (target.maxZ - target.minZ) * 0.5;
        double surfaceDistance = Math.min(axis.x == 0 ? Double.POSITIVE_INFINITY : halfX / Math.abs(axis.x),
            axis.z == 0 ? Double.POSITIVE_INFINITY : halfZ / Math.abs(axis.z));

        double aimY = Math.min(Math.nextDown(target.maxY), Math.max(midpoint(target.minY, target.maxY), target.minY + EYE));
        Point center = new Point(midpoint(target.minX, target.maxX), aimY, midpoint(target.minZ, target.maxZ));
        Point surface = center.subtract(axis.multiply(surfaceDistance));
        return lane(surface, axis, strikeGap, dip, restGap, true);
    }

    static boolean readyToStrike(Lane lane, Point feet, Point targetVelocity, double dip, double maxH, double maxV) {
        if (lane == null || !finite(feet) || !finite(targetVelocity) || !finite(lane.impact) || !finite(lane.launch)
            || !finite(lane.axis) || !Double.isFinite(dip) || dip <= 0 || !Double.isFinite(maxH) || maxH <= 0
            || !Double.isFinite(maxV) || maxV <= 0 || Math.abs(lane.axis.lengthSquared() - 1) > 1e-6) return false;
        Point launchError = feet.subtract(lane.launch);
        if (lane.horizontal) {
            if (Math.abs(launchError.dot(lane.axis)) > 0.4 + 1e-7 || Math.abs(launchError.y) > 0.4 + 1e-7) return false;
        } else if (Math.abs(launchError.y) >= 0.15) return false;
        Point displacement = lane.impact.subtract(feet);
        if (!finite(displacement) || Math.hypot(displacement.x, displacement.z) > maxH + 1e-7
            || Math.abs(displacement.y) > maxV + 1e-7) return false;
        double forward = displacement.dot(lane.axis);
        double relativeClosing = displacement.subtract(targetVelocity).dot(lane.axis);
        return Double.isFinite(forward) && Double.isFinite(relativeClosing)
            && forward + 1e-7 >= 0.3 && relativeClosing + 1e-7 >= MIN_DAMAGE_CLOSING;
    }

    private static Lane lane(Point surface, Point axis, double strikeGap, double dip, double restGap, boolean horizontal) {
        Point impact = surface.subtract(axis.multiply(strikeGap)).add(0, -EYE, 0);
        Point launch = impact.subtract(axis.multiply(dip));
        Point rest = restGap <= strikeGap + dip ? launch : surface.subtract(axis.multiply(restGap)).add(0, -EYE, 0);
        if (!finite(impact) || !finite(launch) || !finite(rest)) return null;
        return new Lane(impact, launch, rest, axis, surface, horizontal);
    }

    private static double midpoint(double min, double max) {
        return min + (max - min) * 0.5;
    }

    private static boolean finite(Point point) {
        return point != null && Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }

    private static boolean valid(Bounds b, double strikeGap, double dip, double restGap) {
        return b != null && Double.isFinite(b.minX) && Double.isFinite(b.minY) && Double.isFinite(b.minZ)
            && Double.isFinite(b.maxX) && Double.isFinite(b.maxY) && Double.isFinite(b.maxZ)
            && b.minX < b.maxX && b.minY < b.maxY && b.minZ < b.maxZ
            && Double.isFinite(strikeGap) && strikeGap > 0 && Double.isFinite(dip) && dip > 0
            && Double.isFinite(restGap) && restGap >= 0;
    }
}
