/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.LOSAT;

import com.AutoBookshelf.addon.modules.LOSAT.LanceAttackMath.Lane;
import com.AutoBookshelf.addon.modules.LOSAT.LanceAttackMath.Point;

import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

final class LanceApproachPriority {
    private long generation;
    private int targetId, lastTick;
    private boolean initialized, spent;
    private long until;

    void observe(int tick, long generation, int targetId, boolean ready, boolean continuation) {
        if (!initialized || this.generation != generation || this.targetId != targetId || tick < lastTick
            || !ready || continuation) {
            spent = false;
            until = Long.MIN_VALUE;
        }
        initialized = true;
        this.generation = generation;
        this.targetId = targetId;
        lastTick = tick;
    }

    boolean active(int tick) {
        return initialized && spent && tick >= lastTick && tick < until;
    }

    boolean preserve(int tick, boolean imminent) {
        if (!initialized || !imminent || tick < lastTick) return false;
        if (!spent) {
            spent = true;
            until = (long) tick + 2;
        }
        return active(tick);
    }

    static boolean imminent(Lane lane, Point next, Point velocity, double dip, double maxH, double strikeV,
                            UnaryOperator<Point> approachStep, BiPredicate<Point, Point> clearMove,
                            Predicate<Lane> clearRay) {
        if (lane == null || next == null || velocity == null) return false;
        Lane following = shifted(lane, velocity);
        if (strikeClear(following, next, velocity, dip, maxH, strikeV, clearMove, clearRay)) return true;
        Point step = approachStep.apply(following.launch().subtract(next));
        if (step == null || !finite(step)) return false;
        Point approached = next.add(step);
        if (!finite(approached) || !clearMove.test(next, approached)) return false;
        return strikeClear(shifted(following, velocity), approached, velocity, dip, maxH, strikeV, clearMove, clearRay);
    }

    private static boolean strikeClear(Lane lane, Point from, Point velocity, double dip, double maxH,
                                       double maxV, BiPredicate<Point, Point> clearMove, Predicate<Lane> clearRay) {
        return LanceAttackMath.readyToStrike(lane, from, velocity, dip, maxH, maxV)
            && clearMove.test(from, lane.impact()) && clearRay.test(lane);
    }

    private static Lane shifted(Lane lane, Point d) {
        return new Lane(lane.impact().add(d), lane.launch().add(d), lane.rest().add(d), lane.axis(),
            lane.contact().add(d), lane.horizontal());
    }

    private static boolean finite(Point p) {
        return Double.isFinite(p.x()) && Double.isFinite(p.y()) && Double.isFinite(p.z());
    }
}
