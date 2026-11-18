// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker;

/**
 * Static mirror of {@link TrackerConfig}, kept so the ported tracking classes can read tunables
 * the same way the reference did (static fields). The module refreshes these alongside the config
 * snapshot at the start of each tick and on activation.
 */
public final class Settings {
    public static int bindTimeoutTicks = 60;
    public static int bindTimeoutMaxTicks = 400;
    public static int homeRadiusChunks = 1;
    public static double destroyDistance = 16.0;
    public static int dropWindowTicks = 20;
    public static int trackIntervalTicks = 20;
    public static int saveDebounceTicks = 100;
    public static int staleDays = 7;
    public static double missingCheckRange = 48.0;
    public static double displayMissingCheckRange = 24.0;
    public static double dropRadius = 2.5;
    public static int trashDays = 7;
    public static boolean autoTrashGoneBlocks = false;
    public static boolean showDestroyed = true;
    public static boolean trackTrades = true;
    public static int tradePreviewTicks = 35;
    public static long indexBudgetNanos = 1_000_000L;
    public static long searchBudgetNanos = 4_000_000L;
    public static int backupsToKeep = 7;
    public static boolean showSaveStatus = false;

    private Settings() {
    }
}
