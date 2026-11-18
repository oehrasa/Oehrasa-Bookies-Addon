// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker.data;

import com.AutoBookshelf.addon.modules.chesttracker.TrackerLog;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads the old {@code tracked_containers.json} memory file (the pre-bank format) and turns it
 * into {@link BlockRecord}s for the one-time migration into a {@link MemoryBank}.
 *
 * <p>The old format stored, per container: position, dimension, type, custom name, last-update
 * timestamp, an aggregate item map and a per-slot item-id array. Only the slot ids survive; item
 * stacks (counts, enchantments, shulker nesting) are reconstructed as single-count stacks, so the
 * migrated records carry degraded contents that are refreshed the next time the container is opened
 * with the module.
 */
public final class LegacyImport {
    private static final String AIR = "minecraft:air";

    private LegacyImport() {
    }

    /**
     * Migrated records, in file order; empty when the file is missing or unreadable.
     */
    public static List<BlockRecord> read(Path file) {
        List<BlockRecord> out = new ArrayList<>();
        JsonElement element;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            element = JsonParser.parseReader(reader);
        } catch (Exception e) {
            TrackerLog.LOGGER.error("Could not parse the legacy chest tracker file {}", file, e);
            return out;
        }
        JsonObject root = element.getAsJsonObject();
        JsonObject dimensions = root.has("dimensions") ? root.getAsJsonObject("dimensions") : null;
        if (dimensions == null) return out;
        for (String dimension : dimensions.keySet()) {
            JsonElement dimEntry = dimensions.get(dimension);
            if (!(dimEntry instanceof JsonArray array)) continue;
            for (int i = 0; i < array.size(); i++) {
                JsonObject c = array.get(i).getAsJsonObject();
                BlockRecord record = toRecord(c, dimension);
                if (record != null) out.add(record);
            }
        }
        return out;
    }

    private static BlockRecord toRecord(JsonObject c, String dimension) {
        if (c.get("x").getAsInt() == 0 && c.get("y").getAsInt() == 0 && c.get("z").getAsInt() == 0) {
            // Positions are always present, but a degenerate all-zero container is not a thing we
            // want to migrate; skip defensively.
            return null;
        }
        BlockPos pos = new BlockPos(c.get("x").getAsInt(), c.get("y").getAsInt(), c.get("z").getAsInt());
        String type = c.has("type") ? c.get("type").getAsString() : "chest";
        String customName = c.has("customName") && !c.get("customName").isJsonNull()
            ? c.get("customName").getAsString()
            : null;
        long lastUpdated = c.has("lastUpdated") ? c.get("lastUpdated").getAsLong() : 0L;
        JsonArray slotContents = c.has("slotContents") && c.get("slotContents").isJsonArray()
            ? c.getAsJsonArray("slotContents")
            : new JsonArray();

        boolean isEnderChest = "ender_chest".equals(type);
        BlockRecord record = new BlockRecord(isEnderChest);
        record.dimension = dimension;
        record.type = type;
        record.name = customName;
        if (isEnderChest) {
            // An ender chest is the same container for every account; BankManager applies the
            // current player's UUID to it before putting it in the bank.
            record.pos = new BlockPos(0, 0, 0);
            record.x = 0.5;
            record.y = 0.5;
            record.z = 0.5;
        } else {
            record.pos = pos;
            record.x = pos.getX() + 0.5;
            record.y = pos.getY() + 0.5;
            record.z = pos.getZ() + 0.5;
        }
        record.firstSeen = lastUpdated;
        record.lastSeen = lastUpdated;
        record.lastOpened = lastUpdated;
        record.slotCount = slotContents.size();
        record.status = RecordStatus.UNLOADED;
        Set<String> flags = new LinkedHashSet<>();
        flags.add(Notes.IMPORTED);
        record.flags = flags;
        record.items = new ArrayList<>();
        for (int i = 0; i < slotContents.size(); i++) {
            String id = slotContents.get(i).getAsString();
            if (AIR.equals(id)) continue;
            JsonObject json = new JsonObject();
            json.addProperty("id", id);
            json.addProperty("count", 1);
            record.items.add(new ContainerRecord.SlotItem(i, json, ItemData.CURRENT));
        }
        return record;
    }
}
