/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.LOSAT;

final class LanceLanePolicy {
    private LanceLanePolicy() {
    }

    static double hardLimit(double hp, double preferred, boolean strict) {
        if (!Double.isFinite(hp) || !Double.isFinite(preferred)) return 0;
        double reserve = Math.max(0, hp - 8);
        if (strict) return Math.min(Math.max(0, preferred), reserve);
        return Math.max(0, Math.min(6, Math.min(hp * 0.25, reserve)));
    }

    static double score(double actualDamage, double potentialDamage, double travelDistance, double preferred,
                        double hardLimit, boolean currentLane, boolean clearApproach) {
        if (!Double.isFinite(actualDamage) || actualDamage < 0 || !Double.isFinite(hardLimit) || hardLimit < 0
            || actualDamage > hardLimit || !Double.isFinite(preferred) || !Double.isFinite(travelDistance)
            || travelDistance < 0) return Double.POSITIVE_INFINITY;
        double preferredDamage = Math.max(0, preferred);
        double potential = !Double.isFinite(potentialDamage) || potentialDamage < 0 ? 25 : Math.min(25, potentialDamage);
        double score = Math.max(0, actualDamage - preferredDamage) * 12
            + Math.max(0, potential - preferredDamage) * 2 + travelDistance
            + (currentLane ? 0 : 2) - (clearApproach ? 2 : 0);
        return Double.isFinite(score) ? score : Double.POSITIVE_INFINITY;
    }
}
