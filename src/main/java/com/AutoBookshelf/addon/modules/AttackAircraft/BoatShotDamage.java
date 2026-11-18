/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.AttackAircraft;

public final class BoatShotDamage {
    public record Defense(double health, double armor, double toughness, int protection, int resistance) {
    }

    public static double damage(int draw, double burst, int power, Defense defense, boolean expectedCritical) {
        double speed = Math.max(0, burst) + BoatShotAim.bowSpeed(draw);

        double raw = Math.ceil(speed * .94 * (2 + (power > 0 ? .5 * (power + 1) : 0)));
        if (draw >= 20 && expectedCritical) raw += Math.floor(raw / 2) / 2. + .5;
        double armor = Math.clamp(defense.armor() - raw / (2 + defense.toughness() / 4), defense.armor() * .2, 20);
        return Math.max(0, raw * (1 - armor / 25) * (1 - Math.clamp(defense.resistance(), 0, 5) * .2)
            * (1 - Math.clamp(defense.protection(), 0, 20) / 25.));
    }

    public static int drawTicks(double burst, int power, Defense defense) {
        int best = 20;
        double bestDps = -1;
        for (int draw = 3; draw <= 20; draw++) {
            double minimum = damage(draw, burst, power, defense, false);
            if (minimum >= defense.health() + 1) return draw;

            double dps = damage(draw, burst, power, defense, true) / Math.max(10, draw + 1);
            if (dps > bestDps) {
                bestDps = dps;
                best = draw;
            }
        }
        return best;
    }

    public static int clientTicks(int serverTicks, double tps) {
        return (int) Math.ceil(serverTicks / BoatShotPrediction.serverTicksPerClientTick(tps));
    }
}
