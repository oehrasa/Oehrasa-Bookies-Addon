/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.LOSAT;

final class LanceCouchTiming {
    private long generation;
    private int pressedAt = -1;
    private int offSince = -1;
    private boolean confirmed;
    private int observedResponse;
    private int failures;
    private boolean nextPostWebRetry, postWebRetry;
    private int reassertedAt = -1;

    void reset() {
        stop();
        observedResponse = failures = 0;
    }

    long start(int tick) {
        generation++;
        pressedAt = tick;
        confirmed = false;
        offSince = -1;
        postWebRetry = nextPostWebRetry;
        nextPostWebRetry = false;
        reassertedAt = -1;
        return generation;
    }

    long stop() {
        generation++;
        pressedAt = -1;
        confirmed = false;
        offSince = -1;
        cancelPostWebRetry();
        reassertedAt = -1;
        return generation;
    }

    boolean observe(long receivedGeneration, boolean using, int tick) {
        if (receivedGeneration != generation || pressedAt < 0) return false;
        if (using) {

            if (!confirmed && reassertedAt < 0) observedResponse = Math.max(Math.max(0, observedResponse - 1), Math.min(80, tick - pressedAt));
            confirmed = true;
            postWebRetry = false;
            offSince = -1;
            failures = 0;
        } else if (confirmed && offSince < 0) offSince = tick;
        return true;
    }

    boolean confirmed() {
        return confirmed && offSince < 0;
    }

    void allowNextPostWebRetry() {
        nextPostWebRetry = true;
    }

    void cancelPostWebRetry() {
        nextPostWebRetry = postWebRetry = false;
    }

    boolean reassertDue(int tick, int pingMs) {
        return pressedAt >= 0 && postWebRetry && !confirmed && reassertedAt < 0
            && tick - pressedAt >= Math.max(6, latencyTicks(pingMs) + 2) && !retryDue(tick, pingMs);
    }

    boolean reassert(int tick, int pingMs) {
        if (!reassertDue(tick, pingMs)) return false;
        postWebRetry = false;
        reassertedAt = tick;
        return true;
    }

    int earliestUseAge(int tick) {
        return pressedAt < 0 ? 0 : Math.max(0, tick - pressedAt);
    }

    int responseBudget(int pingMs) {

        int latency = latencyTicks(pingMs);
        return Math.min(100, Math.max(Math.max(12 + failures * 4, latency + 6), observedResponse + 4));
    }

    int latencyTicks(int pingMs) {
        return Math.max(observedResponse, (int) Math.min(100, Math.ceil(Math.max(0, pingMs) / 50.0)));
    }

    int predictionTicks(int pingMs) {
        return Math.min(20, latencyTicks(pingMs) + 1);
    }

    boolean retryDue(int tick, int pingMs) {
        if (pressedAt < 0) return false;
        if (!confirmed) return tick - pressedAt >= responseBudget(pingMs);

        int grace = Math.max(3, Math.min(8, (int) Math.ceil(Math.max(0, pingMs) / 50.0) + 1));
        return offSince >= 0 && tick - offSince >= grace;
    }

    void failed() {
        failures = Math.min(6, failures + 1);
    }
}
