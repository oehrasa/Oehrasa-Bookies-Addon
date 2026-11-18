/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.AttackAircraft;

public final class BoatShotPrediction {
    public record Velocity(double x, double y, double z) {
    }

    private Velocity velocity = new Velocity(0, 0, 0);
    private double lead = Double.NaN;
    private double px, py, pz;
    private int changedAt = -1;

    public void resetMotion() {
        velocity = new Velocity(0, 0, 0);
        changedAt = -1;
    }

    public void reset() {
        resetMotion();
        lead = Double.NaN;
    }

    public Velocity observePosition(int tick, double x, double y, double z) {
        return observePosition(tick, x, y, z, 5);
    }

    public Velocity observePosition(int tick, double x, double y, double z, int staleTicks) {
        if (!Double.isFinite(x + y + z)) {
            resetMotion();
            return velocity;
        }
        if (changedAt < 0 || tick <= changedAt) {
            px = x;
            py = y;
            pz = z;
            changedAt = tick;
            return velocity;
        }
        double dx = x - px, dy = y - py, dz = z - pz;
        int elapsed = tick - changedAt;
        if (dx * dx + dy * dy + dz * dz < 1e-10) {
            if (elapsed > staleTicks) velocity = new Velocity(0, 0, 0);
            return velocity;
        }
        px = x;
        py = y;
        pz = z;
        changedAt = tick;

        int dt = elapsed > staleTicks ? 1 : elapsed;
        double vx = dx / dt, vy = dy / dt, vz = dz / dt;

        Velocity result = vx * vx + vy * vy + vz * vz > 64 ? new Velocity(0, 0, 0) : new Velocity(vx, vy, vz);
        velocity = result;
        changedAt = tick;
        return result;
    }

    public int sampleAge(int tick) {
        return changedAt < 0 ? 0 : Math.clamp(tick - changedAt, 0, 5);
    }

    public static double serverTicksPerClientTick(double tps) {
        return Double.isFinite(tps) && tps >= 5 ? Math.clamp(tps, 5, 20) / 20 : 1;
    }

    public double updateLead(int pingMs, double tps, int age) {
        double ratio = serverTicksPerClientTick(tps);

        double wanted = Math.clamp((pingMs < 0 ? 1 : pingMs / 50.0) * ratio, 0, 10);
        lead = Double.isFinite(lead) ? lead * .5 + wanted * .5 : wanted;
        return Math.clamp(lead + Math.max(0, age) * ratio, 0, 10);
    }

    public static double collisionLead(double lead) {
        return Math.round(Math.clamp(lead, 0, 10));
    }

    public Velocity observe(double x, double y, double z) {
        if (!Double.isFinite(x + y + z) || x * x + y * y + z * z > 64) {
            resetMotion();
            return velocity;
        }
        double oldSpeed = Math.hypot(velocity.x, velocity.z), speed = Math.hypot(x, z);
        boolean braking = speed < oldSpeed * .5;
        boolean turning = oldSpeed > .1 && speed > .1 && x * velocity.x + z * velocity.z < oldSpeed * speed * .7;
        double weight = braking || turning ? .85 : .65;
        velocity = new Velocity(velocity.x * (1 - weight) + x * weight, velocity.y * (1 - weight) + y * weight, velocity.z * (1 - weight) + z * weight);
        return velocity;
    }

    public double updateLead(int pingMs) {

        double wanted = pingMs < 0 ? 2 : Math.clamp(pingMs / 50.0 + 1, 1, 8);
        lead = Double.isFinite(lead) ? lead * .9 + wanted * .1 : wanted;
        return lead;
    }
}
