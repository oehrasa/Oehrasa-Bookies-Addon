/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.chesttracker;

import com.AutoBookshelf.addon.modules.chesttracker.search.HeadAliases;
import com.AutoBookshelf.addon.modules.chesttracker.search.ItemIndex;
import com.AutoBookshelf.addon.modules.chesttracker.track.BankManager;
import com.AutoBookshelf.addon.modules.chesttracker.track.InteractionTracker;
import meteordevelopment.meteorclient.MeteorClient;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

public final class TrackerServices {
    private static @Nullable BankManager banks;
    private static @Nullable InteractionTracker interactions;
    private static @Nullable ItemIndex itemIndex;
    private static @Nullable HeadAliases headAliases;

    private TrackerServices() {
    }

    public static Path root() {
        return MeteorClient.FOLDER.toPath().resolve("ChestTracker");
    }

    public static synchronized BankManager banks() {
        return banks;
    }

    public static synchronized InteractionTracker interactions() {
        return interactions;
    }

    public static synchronized ItemIndex index() {
        if (itemIndex == null) itemIndex = new ItemIndex();
        return itemIndex;
    }

    public static synchronized HeadAliases headAliases() {
        if (headAliases == null) headAliases = new HeadAliases(root());
        return headAliases;
    }

    /**
     * Wires the per-session instances. Call from the module's onActivate.
     */
    public static synchronized void init(Path root, BankManager bankManager, InteractionTracker interactionTracker) {
        banks = bankManager;
        interactions = interactionTracker;
        // Keep the search index across toggles: its cache is keyed by record key + lastOpened
        // and bounded, so a re-enabled module doesn't start from a cold index again.
        if (itemIndex == null) itemIndex = new ItemIndex();
        headAliases = new HeadAliases(root);
    }

    /**
     * Drops caches; call from onDeactivate. The search index is kept (see {@link #init}).
     */
    public static synchronized void clear() {
        // Keep the BankManager/interactions references: their bank() is already null after close(),
        // so GUI code that calls TrackerServices.banks() keeps a non-null handle and can null-check
        // .bank() instead of crashing on a null manager.
    }
}
