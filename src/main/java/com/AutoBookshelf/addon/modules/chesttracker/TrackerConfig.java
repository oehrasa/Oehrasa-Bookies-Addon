/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.chesttracker;

/**
 * Plain snapshot of the module's settings, read by the data and tracking classes so they do not
 * depend on Meteor's setting objects. The module refreshes it from the settings at the start of
 * each tick and on activation, so a setting change takes effect immediately.
 */
public final class TrackerConfig {
    /**
     * Ticks after interacting with an entity in which a container screen may still be bound to it.
     */
    public int bindTimeoutTicks = 60;
    /**
     * The longest a click waits for its screen when the server is very laggy.
     */
    public int bindTimeoutMaxTicks = 400;
    /**
     * Home area radius in chunks (1 = the 3x3 area around the home chunk).
     */
    public int homeRadiusChunks = 1;
    /**
     * An entity vanishing closer than this, followed by item drops, counts as likely destroyed.
     */
    public double destroyDistance = 16.0;
    /**
     * Ticks to wait for item drops after a nearby entity disappears.
     */
    public int dropWindowTicks = 20;
    /**
     * Ticks between position-tracking passes.
     */
    public int trackIntervalTicks = 20;
    /**
     * Ticks of quiet after a change before the bank is written.
     */
    public int saveDebounceTicks = 100;
    /**
     * Days without opening after which an entry counts as stale.
     */
    public int staleDays = 7;
    /**
     * "Missing" is only judged within this distance of the entity's last position.
     */
    public double missingCheckRange = 48.0;
    /**
     * The same for item frames and armor stands, which servers track over a shorter range.
     */
    public double displayMissingCheckRange = 24.0;
    /**
     * Radius around a vanished entity in which dropped items count as its drops.
     */
    public double dropRadius = 2.5;
    /**
     * Days a record stays in the trash (restorable) before it is forgotten.
     */
    public int trashDays = 7;
    /**
     * Move a destroyed block to the trash automatically when its chunk shows it gone.
     */
    public boolean autoTrashGoneBlocks = false;
    /**
     * Whether the browser list shows likely-destroyed containers.
     */
    public boolean showDestroyed = true;
    /**
     * Remember villager and wandering trader offers.
     */
    public boolean trackTrades = true;
    /**
     * Ticks each page of a villager's offer preview shows.
     */
    public int tradePreviewTicks = 35;
    /**
     * Time per client tick spent building the search index in the background.
     */
    public long indexBudgetNanos = 1_000_000L;
    /**
     * Time the search may take per frame.
     */
    public long searchBudgetNanos = 4_000_000L;
    /**
     * Daily backups to keep per bank.
     */
    public int backupsToKeep = 7;
    /**
     * Chat notice when the bank is compacted or a save fails.
     */
    public boolean showSaveStatus = false;
}
