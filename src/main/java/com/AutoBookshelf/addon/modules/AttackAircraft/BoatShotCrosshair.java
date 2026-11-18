/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.AttackAircraft;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class BoatShotCrosshair {
    public record Score(double cosine, double distance, Vec3 point) {
        public boolean betterThan(Score other) {
            return other == null || cosine > other.cosine + 1e-10
                || Math.abs(cosine - other.cosine) <= 1e-10 && distance < other.distance;
        }
    }

    public static Score score(AABB body, Vec3 eye, Vec3 direction, double range) {
        if (!Double.isFinite(range) || range <= 0 || !Double.isFinite(direction.lengthSqr())
            || direction.lengthSqr() < 1e-12) return null;
        Vec3 view = direction.normalize();
        if (body.contains(eye)) return new Score(1, 0, eye);
        var hit = body.clip(eye, eye.add(view.scale(range)));
        if (hit.isPresent()) return new Score(1, eye.distanceTo(hit.get()), hit.get());
        Vec3[] corners = new Vec3[8];
        for (int i = 0; i < 8; i++)
            corners[i] = new Vec3((i & 1) == 0 ? body.minX : body.maxX,
                (i & 2) == 0 ? body.minY : body.maxY, (i & 4) == 0 ? body.minZ : body.maxZ).subtract(eye);
        Score best = null;
        for (int i = 0; i < 8; i++) {
            best = consider(best, corners[i], eye, view, range);
            for (int axis : new int[]{1, 2, 4}) {
                if ((i & axis) != 0) continue;
                Vec3 start = corners[i], edge = corners[i | axis].subtract(start);
                double a = view.dot(start), b = view.dot(edge);
                double c = start.lengthSqr(), d = start.dot(edge), e = edge.lengthSqr();
                double denominator = b * d - a * e;
                if (Math.abs(denominator) < 1e-12) continue;
                double t = (a * d - b * c) / denominator;
                if (t > 0 && t < 1) best = consider(best, start.add(edge.scale(t)), eye, view, range);
            }
        }
        return best;
    }

    private static Score consider(Score best, Vec3 offset, Vec3 eye, Vec3 view, double range) {
        double distance = offset.length();
        if (!Double.isFinite(distance) || distance < 1e-10 || distance > range) return best;
        double cosine = view.dot(offset) / distance;
        if (cosine <= 0) return best;
        Score candidate = new Score(Math.min(1, cosine), distance, eye.add(offset));
        return candidate.betterThan(best) ? candidate : best;
    }
}
