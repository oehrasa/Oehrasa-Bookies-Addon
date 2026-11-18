// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker.track;

import com.AutoBookshelf.addon.modules.chesttracker.TrackerLog;
import com.AutoBookshelf.addon.modules.chesttracker.data.BlockRecord;
import com.AutoBookshelf.addon.modules.chesttracker.data.ContainerRecord;
import com.AutoBookshelf.addon.modules.chesttracker.data.EntityRecord;
import com.AutoBookshelf.addon.modules.chesttracker.data.MemoryBank;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Manual overrides: the client can't tell "unloaded" from "destroyed", so the player marks things
 * destroyed. Marked records go to a trash, hidden from search but restorable for a few days; only
 * "Delete permanently" and that expiry forget them.
 */
public final class Trash {
    private Trash() {
    }

    /**
     * An axis-aligned block area, inclusive.
     */
    public record Area(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public static Area of(BlockPos a, BlockPos b) {
            return new Area(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        }

        boolean contains(BlockPos p) {
            return p.getX() >= minX && p.getX() <= maxX && p.getY() >= minY && p.getY() <= maxY
                && p.getZ() >= minZ && p.getZ() <= maxZ;
        }

        /**
         * Whether an entity's home area (chunks, full height) overlaps this area.
         */
        boolean overlapsHome(EntityRecord e, int homeRadiusChunks) {
            int r = homeRadiusChunks;
            int homeMinX = (e.homeChunkX - r) << 4, homeMaxX = ((e.homeChunkX + r) << 4) + 15;
            int homeMinZ = (e.homeChunkZ - r) << 4, homeMaxZ = ((e.homeChunkZ + r) << 4) + 15;
            return homeMaxX >= minX && homeMinX <= maxX && homeMaxZ >= minZ && homeMinZ <= maxZ;
        }

        public long volume() {
            return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        }
    }

    /**
     * Non-trashed records in the area: blocks by position (either chest half), entities by home area.
     */
    public static List<ContainerRecord> inArea(MemoryBank bank, String dimension, Area area, int homeRadiusChunks) {
        List<ContainerRecord> out = new ArrayList<>();
        for (BlockRecord b : bank.allBlocks()) {
            if (b.global || b.isTrashed() || !dimension.equals(b.dimension)) continue;
            boolean inside = area.contains(b.pos);
            for (BlockPos p : b.otherPositions) inside |= area.contains(p);
            if (inside) out.add(b);
        }
        for (EntityRecord e : bank.all()) {
            if (!e.isTrader() && !e.isTrashed() && dimension.equals(e.dimension)
                && area.overlapsHome(e, homeRadiusChunks)) out.add(e);
        }
        return out;
    }

    public static void trash(MemoryBank bank, ContainerRecord record) {
        record.trashedAt = System.currentTimeMillis();
        bank.markDirty();
        TrackerLog.LOGGER.info("Marked {} destroyed (in trash)", record.key());
    }

    /**
     * A block that is certainly gone (its chunk is loaded and the container isn't there): trash it, restorable.
     */
    public static void trashGoneBlock(MemoryBank bank, BlockRecord record) {
        record.trashedAt = System.currentTimeMillis();
        record.autoTrashed = true;
        record.hints.clear(); // "fed by a hopper", "not seen yet": about a block that isn't there any more
        bank.markDirty();
        TrackerLog.LOGGER.info("{} at {} is gone; moved it to the trash", record.type, record.pos.toShortString());
    }

    public static void restore(MemoryBank bank, ContainerRecord record) {
        record.trashedAt = 0;
        record.autoTrashed = false;
        bank.markDirty();
        TrackerLog.LOGGER.info("Restored {} from trash", record.key());
    }

    public static void deletePermanently(MemoryBank bank, ContainerRecord record) {
        bank.remove(record);
        TrackerLog.LOGGER.info("Deleted {} permanently", record.key());
    }

    /**
     * Trashes the given records as one undoable action.
     */
    public static void trashArea(MemoryBank bank, List<ContainerRecord> records) {
        List<String> keys = new ArrayList<>();
        for (ContainerRecord r : records) {
            trash(bank, r);
            keys.add(r.key());
        }
        bank.setLastAreaAction(keys);
    }

    /**
     * Restores what the last area action trashed (if still in the trash). Returns the count.
     */
    public static int undoArea(MemoryBank bank) {
        int restored = 0;
        for (String key : bank.lastAreaAction()) {
            ContainerRecord r = bank.getByKey(key);
            if (r != null && r.isTrashed()) {
                restore(bank, r);
                restored++;
            }
        }
        bank.setLastAreaAction(List.of());
        return restored;
    }
}
