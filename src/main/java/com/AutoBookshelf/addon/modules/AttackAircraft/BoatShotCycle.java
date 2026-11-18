/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.AttackAircraft;

public final class BoatShotCycle {
    public enum Stage {IDLE, QUEUED, MOVED, READY, WAIT_CLEAR, RETURNING}

    public static final int TIMEOUT_TICKS = 4;
    public static final double MAX_BURST = 29;
    private Stage stage = Stage.IDLE;
    private int queuedTick, moveTick;
    private double originY, deltaY, destinationY;
    private boolean returnHome;

    public Stage stage() {
        return stage;
    }

    public boolean pending() {
        return stage == Stage.QUEUED || stage == Stage.MOVED || stage == Stage.READY;
    }

    public double destinationY() {
        return destinationY;
    }

    public boolean queue(int tick, double y, double delta) {
        if (stage != Stage.IDLE || !Double.isFinite(y) || !Double.isFinite(delta)
            || Math.abs(delta) < 0.1 || Math.abs(delta) > MAX_BURST) return false;
        queuedTick = tick;
        originY = y;
        deltaY = delta;
        destinationY = y + delta;
        stage = Stage.QUEUED;
        return true;
    }

    public boolean expired(int tick) {
        return pending() && (tick < queuedTick || tick - queuedTick > TIMEOUT_TICKS);
    }

    public double plan(int tick, double currentY, double limit) {
        if (!Double.isFinite(currentY) || !Double.isFinite(limit) || limit <= 0 || expired(tick)) return 0;
        if (stage == Stage.QUEUED) {
            if (Math.abs(currentY - originY) > 1e-4) return 0;
            moveTick = tick;
            stage = Stage.MOVED;
            return deltaY;
        }
        if (stage == Stage.RETURNING) {

            if (tick <= moveTick) return 0;
            double remaining = originY - currentY;
            if (Math.abs(remaining) < 1e-4) {
                reset();
                return 0;
            }
            return Math.copySign(Math.min(Math.abs(remaining), Math.min(9, limit)), remaining);
        }
        return 0;
    }

    public boolean sent(int tick, double fromY, double toY, double horizontalDelta) {
        if (stage != Stage.MOVED || tick != moveTick || expired(tick)
            || !Double.isFinite(fromY) || !Double.isFinite(toY) || !Double.isFinite(horizontalDelta)
            || Math.abs(fromY - originY) > 1e-4 || Math.abs(toY - destinationY) > 1e-4
            || Math.abs(horizontalDelta) > 1e-4) return false;
        stage = Stage.READY;
        return true;
    }

    public void released(boolean returnHome) {
        if (stage != Stage.READY) {
            reset();
            return;
        }
        this.returnHome = returnHome;
        stage = Stage.WAIT_CLEAR;
    }

    public void arrowClear() {
        if (stage == Stage.WAIT_CLEAR) stage = returnHome ? Stage.RETURNING : Stage.IDLE;
    }

    public void reset() {
        stage = Stage.IDLE;
    }

    public static double burst(double pitch, double speed, double minimumPitch) {
        if (!Double.isFinite(pitch) || !Double.isFinite(speed)
            || !Double.isFinite(minimumPitch) || Math.abs(pitch) > 90
            || Math.abs(pitch) < Math.max(1, minimumPitch)) return 0;
        double amount = Math.max(0, Math.min(MAX_BURST, speed));
        return pitch > 0 ? -amount : amount;
    }

    public static double launchSpeed(double pitch, double deltaY, int useTicks, boolean grounded) {
        if (!Double.isFinite(pitch) || !Double.isFinite(deltaY)) return 0;
        double draw = Math.max(0, useTicks) / 20.0;
        double speed = 3 * Math.min(1, (draw * draw + 2 * draw) / 3);
        double radians = Math.toRadians(pitch);
        return Math.hypot(speed * Math.cos(radians), -speed * Math.sin(radians) + (grounded ? 0 : deltaY));
    }
}
