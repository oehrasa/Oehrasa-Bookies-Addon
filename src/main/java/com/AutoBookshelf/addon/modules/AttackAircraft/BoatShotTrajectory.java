/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.AttackAircraft;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.function.BiPredicate;

public final class BoatShotTrajectory {
    public static boolean clear(Vec3 muzzle, Vec3 velocity, double ticks, AABB target,
                                Vec3 motion, double extraLead, BiPredicate<Vec3, Vec3> clearSegment) {
        return trace(muzzle, velocity, ticks, target, motion, extraLead, clearSegment, false);
    }

    public static boolean clearDiscrete(Vec3 muzzle, Vec3 velocity, double ticks, AABB target,
                                        Vec3 motion, double extraLead, BiPredicate<Vec3, Vec3> clearSegment) {
        return trace(muzzle, velocity, ticks, target, motion, extraLead, clearSegment, true);
    }

    private static boolean trace(Vec3 muzzle, Vec3 velocity, double ticks, AABB target,
                                 Vec3 motion, double extraLead, BiPredicate<Vec3, Vec3> clearSegment, boolean discrete) {
        if (!Double.isFinite(ticks) || ticks <= 0 || ticks > 80) return false;
        Vec3 from = muzzle;
        AABB hitbox = target.inflate(.1);
        for (int i = 0; i < Math.ceil(ticks); i++) {
            double fraction = Math.min(1, ticks - i);
            Vec3 to = from.add(velocity.scale(fraction));
            Vec3 shift = motion.scale(extraLead + i + (discrete ? 1 : 0));
            Vec3 a = from.subtract(shift), b = to.subtract(shift).subtract(discrete ? Vec3.ZERO : motion.scale(fraction));
            Optional<Vec3> contact = hitbox.contains(a) ? Optional.of(a) : hitbox.clip(a, b);
            if (contact.isPresent()) {
                double length = a.distanceTo(b), portion = length < 1e-9 ? 0 : a.distanceTo(contact.get()) / length;
                to = from.lerp(to, Math.max(0, Math.min(1, portion)));
            }
            if (!clearSegment.test(from, to)) return false;
            if (contact.isPresent()) return true;
            from = to;
            velocity = velocity.scale(.99).add(0, -.05, 0);
        }
        return false;
    }
}
