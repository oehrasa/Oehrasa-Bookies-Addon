// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker.data;

/**
 * Warning flags and hints a record can carry. Stored as strings so a newer version's notes
 * survive in an older one's save file.
 */
public final class Notes {
    // Flags: something about the container changed; cleared when it is opened again.
    public static final String CHEST_REMOVED = "chest_removed";
    public static final String CONTAINER_CHANGED = "container_changed";
    public static final String WENT_THROUGH_PORTAL = "went_through_portal";
    /**
     * A villager changed profession (lost its job and took another): its offers are new.
     */
    public static final String PROFESSION_CHANGED = "profession_changed";
    /**
     * A villager levelled up since its offers were seen: it has new ones.
     */
    public static final String LEVELED_UP = "leveled_up";
    /**
     * Brought over from another tracker: slot order and age are as that mod saved them.
     */
    public static final String IMPORTED = "imported";

    // Hints: the contents may change on their own; recomputed while the container is loaded.
    public static final String HOPPER_BELOW = "hopper_below";
    public static final String FED_BY_HOPPER = "fed_by_hopper";
    public static final String PROCESSES_ITEMS = "processes_items";
    public static final String MOVES_ITEMS = "moves_items";
    public static final String OVER_HOPPER = "over_hopper";
    /**
     * A jukebox or chiseled bookshelf holds something nobody saw go in (the server doesn't say what).
     */
    public static final String UNKNOWN_CONTENTS = "unknown_contents";
    /**
     * A lectern holds a book that wasn't seen yet: open it to remember the book.
     */
    public static final String UNREAD_BOOK = "unread_book";
    /**
     * Wandering traders leave by themselves after a while.
     */
    public static final String LEAVES_BY_ITSELF = "leaves_by_itself";

    private Notes() {
    }
}
