// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker.search;

import com.AutoBookshelf.addon.modules.chesttracker.data.ContainerRecord;
import com.AutoBookshelf.addon.modules.chesttracker.data.InventoryRecord;
import com.AutoBookshelf.addon.modules.chesttracker.data.MemoryBank;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.*;

/**
 * The tally put into words: chat lines for {@code /etracker tally}, one line for the tracker's search.
 */
public final class TallyReport {
    /**
     * Kinds listed one by one before the rest are summed up.
     */
    private static final int MAX_LINES = 8;

    private TallyReport() {
    }

    /**
     * Chat lines answering "how many X do I have".
     */
    public static List<Component> lines(String query, MemoryBank bank) {
        ItemQuery.Result result = ItemQuery.resolve(query);
        if (!result.found()) return List.of(Component.translatable("chesttracker.tally.no_match", query));
        Tally tally = Tally.of(bank);
        Map<String, Long> onYou = onPlayer();
        List<String> shown = ItemQuery.byCount(result.ids(), tally).stream()
            .filter(id -> result.ids().size() == 1 || has(tally.get(id), onYou.getOrDefault(id, 0L)))
            .toList();
        if (shown.isEmpty()) return List.of(Component.translatable("chesttracker.tally.none_of", query));
        List<Component> out = new ArrayList<>();
        for (int i = 0; i < Math.min(MAX_LINES, shown.size()); i++) out.add(line(shown.get(i), tally, onYou));
        if (shown.size() > 1) out.add(together(shown, tally, onYou));
        return out;
    }

    /**
     * One line for the tracker's search field, or null if the search isn't an item.
     */
    public static @Nullable Component hint(String query, MemoryBank bank) {
        ItemQuery.Result result = ItemQuery.resolve(query);
        if (!result.found()) return null;
        Tally tally = Tally.of(bank);
        Map<String, Long> onYou = onPlayer();
        if (result.ids().size() == 1) return line(result.ids().get(0), tally, onYou);
        return together(result.ids(), tally, onYou);
    }

    /**
     * One item's line, by id (the tracker's grid view).
     */
    public static Component line(String id, MemoryBank bank) {
        return line(id, Tally.of(bank), onPlayer());
    }

    /**
     * The most plentiful items, for a bare {@code /etracker tally}.
     */
    public static List<Component> top(MemoryBank bank, int n) {
        Tally tally = Tally.of(bank);
        List<Component> out = new ArrayList<>();
        out.add(Component.translatable("chesttracker.tally.top", tally.containers));
        tally.items.entrySet().stream()
            .filter(e -> e.getValue().total > 0)
            .sorted((a, b) -> Long.compare(b.getValue().total, a.getValue().total))
            .limit(n)
            .forEach(e -> out.add(line(e.getKey(), tally, Map.of())));
        return out;
    }

    /**
     * "Totem of Undying: 214 in 12 containers · 64 in ender chests · 3 on you · 27 more may be gone"
     */
    static Component line(String id, Tally tally, Map<String, Long> onYou) {
        Tally.Count c = tally.get(id);
        long carried = onYou.getOrDefault(id, 0L);
        String name = ItemQuery.name(id);
        if (!has(c, carried)) return Component.translatable("chesttracker.tally.none", name);
        Component head = c.total == 0
            ? Component.translatable("chesttracker.tally.none_stored", name)
            : Component.translatable(c.containers == 1 ? "chesttracker.tally.count_one" : "chesttracker.tally.count",
            name, c.total, c.containers);
        return withExtras(head, c.enderChests, carried, c.maybeGone);
    }

    /**
     * "3 kinds: 120 in storage · ..." for several items at once.
     */
    private static Component together(List<String> ids, Tally tally, Map<String, Long> onYou) {
        long total = 0, ender = 0, carried = 0, maybeGone = 0;
        for (String id : ids) {
            Tally.Count c = tally.get(id);
            total += c.total;
            ender += c.enderChests;
            maybeGone += c.maybeGone;
            carried += onYou.getOrDefault(id, 0L);
        }
        return withExtras(Component.translatable("chesttracker.tally.together", ids.size(), total), ender, carried, maybeGone);
    }

    private static Component withExtras(Component head, long ender, long carried, long maybeGone) {
        var out = Component.empty().append(head);
        if (ender > 0) out.append(" · ").append(Component.translatable("chesttracker.tally.ender", ender));
        if (carried > 0) out.append(" · ").append(Component.translatable("chesttracker.tally.on_you", carried));
        if (maybeGone > 0) out.append(" · ").append(Component.translatable("chesttracker.tally.maybe_gone", maybeGone));
        return out;
    }

    private static boolean has(Tally.Count c, long carried) {
        return c.total > 0 || c.maybeGone > 0 || carried > 0;
    }

    /**
     * The version of {@link #export}'s format; scripts should check it (TALLY.md).
     */
    public static final int EXPORT_FORMAT = 1;

    /**
     * The whole tally as JSON, for {@code entity-tracker/tally/<server>.json}: what a script or
     * Zymbot reads to answer "how many" with the game closed. Game thread (names, inventory).
     */
    public static JsonObject export(MemoryBank bank) {
        Tally tally = Tally.of(bank);
        Map<String, Long> onYou = onPlayer();
        var root = new JsonObject();
        root.addProperty("format", EXPORT_FORMAT);
        root.addProperty("server", bank.server);
        long now = System.currentTimeMillis();
        root.addProperty("savedAt", Instant.ofEpochMilli(now).toString());
        root.addProperty("savedAtMillis", now);
        root.addProperty("minecraft", SharedConstants.getCurrentVersion().name());
        root.addProperty("language", Minecraft.getInstance().getLanguageManager().getSelected());
        var player = Minecraft.getInstance().player;
        if (player != null) root.addProperty("player", player.getName().getString());
        root.addProperty("containers", tally.containers);

        Set<String> ids = new HashSet<>(tally.items.keySet());
        ids.addAll(onYou.keySet());
        var items = new JsonObject();
        ids.stream()
            .sorted(Comparator.comparingLong((String id) -> -tally.get(id).total).thenComparing(id -> id))
            .forEach(id -> {
                Tally.Count c = tally.get(id);
                var o = new JsonObject();
                o.addProperty("name", ItemQuery.name(id));
                o.addProperty("total", c.total);
                o.addProperty("containers", c.containers);
                o.addProperty("enderChests", c.enderChests);
                o.addProperty("onPlayer", onYou.getOrDefault(id, 0L));
                o.addProperty("maybeGone", c.maybeGone);
                items.add(id, o);
            });
        root.add("items", items);

        var aliases = new JsonObject();
        ItemQuery.expandedAliases().forEach((name, list) -> {
            var a = new JsonArray();
            list.forEach(a::add);
            aliases.add(name, a);
        });
        root.add("aliases", aliases);
        return root;
    }

    /**
     * Item id -> how many the player is carrying right now, shulkers and bundles included.
     */
    public static Map<String, Long> onPlayer() {
        Map<String, Long> out = new HashMap<>();
        InventoryRecord inventory = InventoryRecord.current();
        if (inventory == null) return out;
        for (ContainerRecord.SlotItem slot : inventory.items) {
            Tally.countStack(slot.stack()).forEach((id, n) -> out.merge(id, n, Long::sum));
        }
        return out;
    }
}
