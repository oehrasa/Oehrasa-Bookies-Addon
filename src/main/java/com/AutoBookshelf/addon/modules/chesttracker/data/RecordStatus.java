// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker.data;

public enum RecordStatus {
    /**
     * Present in the client world right now.
     */
    LOADED,
    /**
     * Left the client world in the normal way (walked out of range, relog).
     */
    UNLOADED,
    /**
     * The whole home area is loaded on the client but the entity is not there.
     */
    MISSING,
    /**
     * Disappeared close to the player with item drops, or died. Kept, never deleted automatically.
     */
    LIKELY_DESTROYED
}
