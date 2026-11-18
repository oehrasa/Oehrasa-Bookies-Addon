// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker.search;

import com.AutoBookshelf.addon.modules.chesttracker.data.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * How many of each item the memory holds, by item id, with what's inside shulker boxes and
 * bundles counted too (a shulker of 27 totems is 27 totems and 1 shulker box).
 * <p>
 * Counted straight from the saved item JSON, so it needs no world, and each container is only
 * counted again when its contents change. The rules are the same ones a script reading the tally
 * file would use (see TALLY.md):
 * <ul>
 *   <li>containers in the trash don't count;</li>
 *   <li>containers that may be gone (missing, likely destroyed, chest removed) are counted
 *       separately, not in the total;</li>
 *   <li>ender chests count, and are also shown on their own;</li>
 *   <li>villagers' trades are not storage and don't count.</li>
 * </ul>
 */
public final class Tally {
    private static final int MAX_DEPTH = 8;

    /**
     * One item's numbers. {@code total} includes {@code enderChests}; {@code maybeGone} is extra.
     */
    public static final class Count {
        public long total;
        public long enderChests;
        public long maybeGone;
        /**
         * Containers holding it, nested shulkers not counted separately.
         */
        public int containers;
    }

    public final Map<String, Count> items;
    /**
     * Containers counted (not in the trash, not maybe gone).
     */
    public final int containers;

    private Tally(Map<String, Count> items, int containers) {
        this.items = items;
        this.containers = containers;
    }

    /**
     * The tally as the tally file wants it: a JSON object keyed by item id, counts plus
     * ender-chest and maybe-gone breakdowns, alphabetical so the file is stable to read.
     */
    public JsonObject toJson(String server) {
        JsonObject out = new JsonObject();
        out.addProperty("server", server);
        out.addProperty("containers", containers);
        JsonObject items = new JsonObject();
        List<String> ids = new ArrayList<>(this.items.keySet());
        java.util.Collections.sort(ids);
        for (String id : ids) {
            Count c = this.items.get(id);
            JsonObject entry = new JsonObject();
            entry.addProperty("total", c.total);
            entry.addProperty("enders", c.enderChests);
            entry.addProperty("maybeGone", c.maybeGone);
            entry.addProperty("containers", c.containers);
            items.add(id, entry);
        }
        out.add("items", items);
        return out;
    }

    public Count get(String itemId) {
        Count c = items.get(itemId);
        return c != null ? c : new Count();
    }

    private static @Nullable MemoryBank cachedBank;
    private static int cachedRevision;
    private static @Nullable Tally cached;

    /**
     * Each container's own count, kept while its contents stay the same: snapshots always put in
     * a new item list (or empty the old one), so the same list at the same size is the same
     * contents. A change anywhere (an entity moving) then only adds these up again, instead of
     * walking every item in a big base (about 20 ms per 100,000 stacks) at each save.
     */
    private record RecordCount(List<ContainerRecord.SlotItem> list, int size, Map<String, Long> counts) {
    }

    private static Map<ContainerRecord, RecordCount> perRecord = new IdentityHashMap<>();

    /**
     * The tally for this memory, counted again only when it has changed. Game thread.
     */
    public static Tally of(MemoryBank bank) {
        if (cached != null && cachedBank == bank && cachedRevision == bank.revision()) return cached;
        if (cachedBank != bank) perRecord = new IdentityHashMap<>();
        Map<ContainerRecord, RecordCount> stillThere = new IdentityHashMap<>();
        Map<String, Count> items = new HashMap<>();
        int containers = 0;
        for (ContainerRecord r : (Iterable<ContainerRecord>) bank.containers()::iterator) {
            if (r.isTrashed()) continue;
            boolean maybeGone = maybeGone(r);
            boolean ender = r instanceof BlockRecord b && b.global;
            if (!maybeGone) containers++;
            RecordCount known = perRecord.get(r);
            if (known == null || known.list() != r.items || known.size() != r.items.size()) {
                Map<String, Long> counts = new HashMap<>();
                for (ContainerRecord.SlotItem slot : r.items) {
                    countStack(slot.stack()).forEach((id, n) -> counts.merge(id, n, Long::sum));
                }
                known = new RecordCount(r.items, r.items.size(), counts);
            }
            stillThere.put(r, known);
            known.counts().forEach((id, n) -> {
                Count c = items.computeIfAbsent(id, k -> new Count());
                if (maybeGone) {
                    c.maybeGone += n;
                    return;
                }
                c.total += n;
                if (ender) c.enderChests += n;
                c.containers++;
            });
        }
        perRecord = stillThere;
        cached = new Tally(items, containers);
        cachedBank = bank;
        cachedRevision = bank.revision();
        return cached;
    }

    /**
     * Missing, likely destroyed, or its chest taken off: its items may not be there any more.
     */
    public static boolean maybeGone(ContainerRecord r) {
        return r.status == RecordStatus.MISSING || r.status == RecordStatus.LIKELY_DESTROYED
            || r.flags.contains(Notes.CHEST_REMOVED);
    }

    /**
     * Item id -> count for one stack and everything inside it.
     */
    public static Map<String, Long> countStack(JsonElement stack) {
        Map<String, Long> out = new HashMap<>();
        add(stack, out, 0);
        return out;
    }

    private static void add(JsonElement json, Map<String, Long> out, int depth) {
        if (!(json instanceof JsonObject o) || !o.has("id")) return;
        long count = o.has("count") ? o.get("count").getAsLong() : 1;
        if (count <= 0) return;
        out.merge(o.get("id").getAsString(), count, Long::sum);
        if (depth >= MAX_DEPTH || !(o.get("components") instanceof JsonObject components)) return;
        // A shulker box (or any block item that keeps its contents): [{slot, item}, ...]
        if (components.get("minecraft:container") instanceof JsonArray container) {
            for (JsonElement e : container) {
                if (e instanceof JsonObject slot) add(slot.get("item"), out, depth + 1);
            }
        }
        // A bundle: [item, ...]
        if (components.get("minecraft:bundle_contents") instanceof JsonArray bundle) {
            for (JsonElement e : bundle) add(e, out, depth + 1);
        }
    }
}
