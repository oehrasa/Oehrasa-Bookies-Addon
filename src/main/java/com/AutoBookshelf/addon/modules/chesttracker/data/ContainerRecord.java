// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What every remembered container has, whether it is an entity or a block.
 */
public abstract class ContainerRecord {
    /**
     * Registry id of the entity type or block.
     */
    public String type;
    public @Nullable String name;
    public String dimension;
    public double x, y, z;
    public RecordStatus status = RecordStatus.LOADED;
    public long firstSeen;
    public long lastSeen;
    /**
     * 0 until the player has opened it.
     */
    public long lastOpened;
    /**
     * Number of container slots captured at the last snapshot.
     */
    public int slotCount;
    /**
     * Last snapshot: slot index + item, kept as the item's raw JSON so unknown data survives.
     */
    public List<SlotItem> items = new ArrayList<>();
    /**
     * When the player marked it destroyed (moved to the trash); 0 = not in the trash.
     */
    public long trashedAt;
    /**
     * Trashed by the mod because the block was gone, not by the player: restored if it's back.
     */
    public boolean autoTrashed;
    /**
     * Warning flags (see {@link Notes}); cleared by a fresh snapshot.
     */
    public Set<String> flags = new LinkedHashSet<>();
    /**
     * Hints that the contents may change on their own (see {@link Notes}).
     */
    public Set<String> hints = new LinkedHashSet<>();

    /**
     * {@code dataVersion}: the game version that wrote the item (see {@link ItemData}).
     */
    public record SlotItem(int slot, JsonElement stack, int dataVersion) {
        /**
         * An item read from the game just now.
         */
        public SlotItem(int slot, JsonElement stack) {
            this(slot, stack, ItemData.CURRENT);
        }
    }

    /**
     * Brings items saved by an older version up to this one. Returns how many were upgraded.
     */
    public int upgradeItems() {
        int n = 0;
        for (int i = 0; i < items.size(); i++) {
            SlotItem item = items.get(i);
            if (item.dataVersion() < ItemData.CURRENT) {
                items.set(i, new SlotItem(item.slot(), ItemData.upgrade(item.stack(), item.dataVersion())));
                n++;
            }
        }
        return n;
    }

    /**
     * Items saved by a newer version than this one; kept exactly as they are.
     */
    public long newerItems() {
        return items.stream().filter(i -> i.dataVersion() > ItemData.CURRENT).count();
    }

    /**
     * Unique within a bank; used for caches.
     */
    public abstract String key();

    public boolean hasSnapshot() {
        return lastOpened != 0;
    }

    public boolean isTrashed() {
        return trashedAt != 0;
    }

    protected void writeCommon(JsonObject o) {
        o.addProperty("type", type);
        if (name != null) o.addProperty("name", name);
        o.addProperty("dimension", dimension);
        JsonArray pos = new JsonArray();
        pos.add(x);
        pos.add(y);
        pos.add(z);
        o.add("pos", pos);
        o.addProperty("status", status.name());
        o.addProperty("firstSeen", firstSeen);
        o.addProperty("lastSeen", lastSeen);
        o.addProperty("lastOpened", lastOpened);
        o.addProperty("slotCount", slotCount);
        // One version for the record; an item from another version says so itself.
        int dataVersion = items.isEmpty() ? ItemData.CURRENT : items.get(0).dataVersion();
        o.addProperty("dataVersion", dataVersion);
        JsonArray arr = new JsonArray();
        for (SlotItem item : new ArrayList<>(items)) {
            JsonObject s = new JsonObject();
            s.addProperty("slot", item.slot());
            s.add("stack", item.stack());
            if (item.dataVersion() != dataVersion) s.addProperty("dataVersion", item.dataVersion());
            arr.add(s);
        }
        o.add("items", arr);
        if (trashedAt != 0) o.addProperty("trashedAt", trashedAt);
        if (autoTrashed) o.addProperty("autoTrashed", true);
        if (!flags.isEmpty()) o.add("flags", toArray(flags));
        if (!hints.isEmpty()) o.add("hints", toArray(hints));
    }

    private static JsonArray toArray(Set<String> values) {
        JsonArray a = new JsonArray();
        new ArrayList<>(values).forEach(a::add);
        return a;
    }

    private static void readSet(JsonObject o, String key, Set<String> into) {
        if (o.has(key)) for (JsonElement e : o.getAsJsonArray(key)) into.add(e.getAsString());
    }

    protected void readCommon(JsonObject o) {
        type = o.get("type").getAsString();
        name = o.has("name") ? o.get("name").getAsString() : null;
        dimension = o.get("dimension").getAsString();
        JsonArray pos = o.getAsJsonArray("pos");
        x = pos.get(0).getAsDouble();
        y = pos.get(1).getAsDouble();
        z = pos.get(2).getAsDouble();
        try {
            status = RecordStatus.valueOf(o.get("status").getAsString());
        } catch (IllegalArgumentException e) {
            status = RecordStatus.UNLOADED;
        }
        // Nothing is loaded before the world is; a LOADED status on disk is from the last session.
        if (status == RecordStatus.LOADED) status = RecordStatus.UNLOADED;
        firstSeen = o.get("firstSeen").getAsLong();
        lastSeen = o.get("lastSeen").getAsLong();
        lastOpened = o.get("lastOpened").getAsLong();
        slotCount = o.has("slotCount") ? o.get("slotCount").getAsInt() : 0;
        // Saved before data versions were stored: the 1.21.11 builds.
        int dataVersion = o.has("dataVersion") ? o.get("dataVersion").getAsInt() : ItemData.LEGACY;
        if (o.has("items")) {
            for (JsonElement e : o.getAsJsonArray("items")) {
                JsonObject s = e.getAsJsonObject();
                items.add(new SlotItem(s.get("slot").getAsInt(), s.get("stack"),
                    s.has("dataVersion") ? s.get("dataVersion").getAsInt() : dataVersion));
            }
        }
        trashedAt = o.has("trashedAt") ? o.get("trashedAt").getAsLong() : 0;
        autoTrashed = o.has("autoTrashed") && o.get("autoTrashed").getAsBoolean();
        readSet(o, "flags", flags);
        readSet(o, "hints", hints);
    }
}
