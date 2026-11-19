// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker.search;

import com.AutoBookshelf.addon.modules.chesttracker.TrackerServices;
import com.AutoBookshelf.addon.modules.chesttracker.data.ContainerRecord;
import com.AutoBookshelf.addon.modules.chesttracker.data.ItemData;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.*;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Stream;

/**
 * Decodes a record's stored items and flattens them, nested shulkers and bundles included, into
 * searchable entries. Cached per record until its snapshot changes.
 */
public final class ItemIndex {
    private static final int MAX_DEPTH = 8;

    /**
     * One item somewhere inside a container. {@code slot} is the container slot it (or the
     * shulker/bundle holding it) occupies; {@code path} lists the shulkers/bundles it sits in.
     * Identical stacks, e.g. duplicated shulkers, stay separate entries and are never merged.
     * {@code words} is searchText split into words once, for close-match search. {@code bookText}
     * is a book's page text (empty for anything else): searched for exact matches only, since a
     * 100-page book would make close matches both slow and noisy. Copies of one book share it.
     */
    public record IndexedItem(ItemStack stack, int slot, List<String> path, String searchText, String[] words,
                              String bookText) {
        /**
         * Exact match on the item itself or, for a book, its pages. Query lowercase.
         */
        public boolean contains(String query) {
            return searchText.contains(query) || (!bookText.isEmpty() && bookText.contains(query));
        }
    }

    private record Cached(long lastOpened, List<IndexedItem> items) {
    }

    private final Map<String, Cached> cache = new HashMap<>();
    /**
     * Expanded entries per distinct stack (its raw JSON), shared by every copy: a base full of
     * identical shulkers decodes and builds tooltips once, not once per copy. Each copy still
     * gets its own entries with its own slot, so duplicates stay separate results.
     */
    private final Map<JsonElement, List<IndexedItem>> expansions = new HashMap<>();
    private static final int MAX_EXPANSIONS = 50_000;

    /**
     * A record being indexed a few slots at a time: entries so far and the next slot to do.
     */
    private static final class Partial {
        final long lastOpened;
        final List<IndexedItem> items = new ArrayList<>();
        int next;

        Partial(long lastOpened) {
            this.lastOpened = lastOpened;
        }
    }

    private final Map<String, Partial> partial = new HashMap<>();

    /**
     * Inverted indexes over the cached records: item -> record keys, and search word -> record
     * keys. Maintained incrementally as records finish indexing, so a search only has to verify
     * candidate records instead of walking every container in the bank.
     */
    private final Map<Item, Set<String>> byItem = new HashMap<>();
    /**
     * Sorted so a prefix query is a range scan, not a walk over every distinct word.
     */
    private final Map<String, Set<String>> byToken = new TreeMap<>();
    /**
     * Trigram -> record keys, for mid-token substring queries ("mond" -> diamond). A query's
     * candidate set is the intersection of its trigrams' sets: a superset of true substring
     * matches (the search still verifies), but far smaller than the whole bank.
     */
    private final Map<String, Set<String>> byTrigram = new HashMap<>();
    private static final int MAX_CACHE_RECORDS = 200_000;

    /**
     * All of the record's items, indexing whatever is missing right now (commands, "On me").
     */
    public List<IndexedItem> items(ContainerRecord record) {
        return itemsWithin(record, Long.MAX_VALUE);
    }

    /**
     * The record's items, indexing slot by slot until {@code deadline} (System.nanoTime). Returns
     * null if the deadline came first; the next call carries on where this one stopped. One
     * slot (a full shulker) takes about a millisecond, so a big chest never stalls a frame.
     */
    public @Nullable List<IndexedItem> itemsWithin(ContainerRecord record, long deadline) {
        List<IndexedItem> cached = cachedItems(record);
        if (cached != null) return cached;
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return List.of();
        if (expansions.size() > MAX_EXPANSIONS) expansions.clear();
        Partial p = partial.get(record.key());
        if (p == null || p.lastOpened != record.lastOpened) {
            p = new Partial(record.lastOpened);
            partial.put(record.key(), p);
        }
        List<ContainerRecord.SlotItem> slots = record.items;
        while (p.next < slots.size()) {
            if (System.nanoTime() >= deadline) return null;
            ContainerRecord.SlotItem slot = slots.get(p.next++);
            for (IndexedItem e : expansions.computeIfAbsent(slot.stack(), json -> expand(json, client))) {
                p.items.add(new IndexedItem(e.stack(), slot.slot(), e.path(), e.searchText(), e.words(), e.bookText()));
            }
        }
        partial.remove(record.key());
        Cached previous = cache.put(record.key(), new Cached(record.lastOpened, p.items));
        if (previous != null) unindexRecord(record.key(), previous.items());
        indexRecord(record.key(), p.items);
        // Bound memory on huge banks; a cleared index only costs re-decoding later.
        if (cache.size() > MAX_CACHE_RECORDS) clear();
        return p.items;
    }

    private void indexRecord(String key, List<IndexedItem> items) {
        for (IndexedItem item : items) {
            byItem.computeIfAbsent(item.stack().getItem(), k -> new HashSet<>()).add(key);
            for (String word : item.words()) {
                if (!word.isEmpty()) byToken.computeIfAbsent(word, k -> new HashSet<>()).add(key);
            }
            // Book page text is searched exactly and isn't in words(): index its words too, so
            // a book query still gets a candidate pass instead of always falling back.
            if (!item.bookText().isEmpty()) {
                for (String word : Fuzzy.words(item.bookText())) {
                    if (!word.isEmpty()) byToken.computeIfAbsent(word, k -> new HashSet<>()).add(key);
                }
            }
            for (String trigram : trigramsOf(item.searchText())) {
                byTrigram.computeIfAbsent(trigram, k -> new HashSet<>()).add(key);
            }
            if (!item.bookText().isEmpty()) {
                for (String trigram : trigramsOf(item.bookText())) {
                    byTrigram.computeIfAbsent(trigram, k -> new HashSet<>()).add(key);
                }
            }
        }
    }

    private static List<String> trigramsOf(String text) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i + 3 <= text.length(); i++) out.add(text.substring(i, i + 3));
        return out;
    }

    /**
     * Record keys whose cached items contain every trigram of the query (superset of substring matches).
     */
    public Set<String> recordsForTrigrams(String query) {
        Set<String> out = null;
        for (String trigram : trigramsOf(query)) {
            Set<String> keys = byTrigram.get(trigram);
            if (keys == null) return Set.of();
            if (out == null) out = new HashSet<>(keys);
            else out.retainAll(keys);
            if (out.isEmpty()) return out;
        }
        return out == null ? Set.of() : out;
    }

    private void unindexRecord(String key, List<IndexedItem> items) {
        for (IndexedItem item : items) {
            Set<String> forItem = byItem.get(item.stack().getItem());
            if (forItem != null && forItem.remove(key) && forItem.isEmpty()) byItem.remove(item.stack().getItem());
            for (String word : item.words()) {
                Set<String> forToken = byToken.get(word);
                if (forToken != null && forToken.remove(key) && forToken.isEmpty()) byToken.remove(word);
            }
            if (!item.bookText().isEmpty()) {
                for (String word : Fuzzy.words(item.bookText())) {
                    Set<String> forToken = byToken.get(word);
                    if (forToken != null && forToken.remove(key) && forToken.isEmpty()) byToken.remove(word);
                }
            }
            for (String trigram : trigramsOf(item.searchText())) {
                Set<String> forTrigram = byTrigram.get(trigram);
                if (forTrigram != null && forTrigram.remove(key) && forTrigram.isEmpty()) byTrigram.remove(trigram);
            }
            if (!item.bookText().isEmpty()) {
                for (String trigram : trigramsOf(item.bookText())) {
                    Set<String> forTrigram = byTrigram.get(trigram);
                    if (forTrigram != null && forTrigram.remove(key) && forTrigram.isEmpty()) byTrigram.remove(trigram);
                }
            }
        }
    }

    /**
     * Record keys whose cached items include this item (empty until indexing has seen it).
     */
    public Set<String> recordsForItem(Item item) {
        return byItem.getOrDefault(item, Set.of());
    }

    /**
     * Record keys whose cached items contain this exact search word.
     */
    public Set<String> recordsForToken(String word) {
        return byToken.getOrDefault(word, Set.of());
    }

    /**
     * Record keys whose cached items contain a word starting with this prefix.
     */
    public Set<String> recordsForTokenPrefix(String prefix) {
        Set<String> out = new HashSet<>();
        for (Set<String> keys : ((TreeMap<String, Set<String>>) byToken)
            .subMap(prefix, prefix + "\uFFFF").values()) {
            out.addAll(keys);
        }
        return out;
    }

    /**
     * The record's items if already indexed for its current snapshot, else null (costs nothing).
     */
    public @Nullable List<IndexedItem> cachedItems(ContainerRecord record) {
        Cached cached = cache.get(record.key());
        return cached != null && cached.lastOpened == record.lastOpened ? cached.items : null;
    }

    /**
     * Indexes records that aren't indexed yet, stopping once {@code budgetNanos} is used up, so
     * the game never stalls on a big memory (a freeze can get you killed). Returns how many
     * records are still left.
     */
    public int warm(Stream<? extends ContainerRecord> records, long budgetNanos) {
        long end = System.nanoTime() + budgetNanos;
        int left = 0;
        var it = records.iterator();
        while (it.hasNext()) {
            ContainerRecord r = it.next();
            if (cachedItems(r) != null) continue;
            if (System.nanoTime() >= end || itemsWithin(r, end) == null) left++;
        }
        return left;
    }

    public void clear() {
        cache.clear();
        partial.clear();
        expansions.clear();
        byItem.clear();
        byToken.clear();
        byTrigram.clear();
    }

    /**
     * One stack and everything nested in it; slot -1 (filled in per copy).
     */
    private static List<IndexedItem> expand(JsonElement json, Minecraft client) {
        List<IndexedItem> out = new ArrayList<>();
        var ops = client.level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        Item.TooltipContext context = Item.TooltipContext.of(client.level);
        // Items that no longer decode (e.g. from a removed mod) are skipped here but stay on disk.
        ItemStack decoded = ItemData.decode(ops, json);
        if (!decoded.isEmpty()) add(out, decoded, -1, List.of(), context, client, 0);
        return out;
    }

    private static void add(List<IndexedItem> out, ItemStack stack, int slot, List<String> path,
                            Item.TooltipContext context, Minecraft client, int depth) {
        String text = searchText(stack, context, client);
        out.add(new IndexedItem(stack, slot, path, text, Fuzzy.words(text), bookText(stack)));
        if (depth >= MAX_DEPTH) return;

        List<String> inner = new ArrayList<>(path);
        inner.add(stack.getHoverName().getString());
        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        if (container != null) {
            container.nonEmptyItemCopyStream().forEach(child -> add(out, child, slot, inner, context, client, depth + 1));
        }
        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) {
//? if >=26.3 {
            /*bundle.itemCopies().forEach(child -> add(out, child, slot, inner, context, client, depth + 1));
*///?} else {
            bundle.itemCopyStream().forEach(child -> add(out, child, slot, inner, context, client, depth + 1));
//?}
        }
    }

    /**
     * Whether a stack, or anything inside it (shulker, bundle), matches a lowercase query.
     */
    public static boolean matchesDeep(ItemStack stack, String query, int depth) {
        if (query.isEmpty()) return false;
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return false;
        if (searchText(stack, Item.TooltipContext.of(client.level), client).contains(query)) return true;
        if (bookText(stack).contains(query)) return true;
        if (depth >= MAX_DEPTH) return false;
        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        if (container != null && container.nonEmptyItemCopyStream().anyMatch(c -> matchesDeep(c, query, depth + 1))) return true;
        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
//? if >=26.3 {
        /*if (bundle != null && bundle.itemCopies().anyMatch(c -> matchesDeep(c, query, depth + 1))) return true;
*///?} else {
        if (bundle != null && bundle.itemCopyStream().anyMatch(c -> matchesDeep(c, query, depth + 1))) return true;
//?}
        return false;
    }

    /**
     * Search text for a single stack (trade offers); empty for an empty stack.
     */
    public static String searchText(ItemStack stack) {
        Minecraft client = Minecraft.getInstance();
        if (stack.isEmpty() || client.level == null) return "";
        return searchText(stack, Item.TooltipContext.of(client.level), client);
    }

    /**
     * A written book's or book and quill's pages as one lowercase text, empty for other items.
     * Title and author are in the tooltip already (searchText).
     */
    public static String bookText(ItemStack stack) {
        WrittenBookContent written = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        WritableBookContent writable = stack.get(DataComponents.WRITABLE_BOOK_CONTENT);
        if (written == null && writable == null) return "";
        StringBuilder sb = new StringBuilder();
        if (written != null) {
            for (Component page : written.getPages(true)) sb.append(page.getString()).append('\n');
        } else {
            writable.getPages(true).forEach(page -> sb.append(page).append('\n'));
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * Tooltip lines cover custom name, lore, enchantments and effects; the id covers the rest.
     */
    private static String searchText(ItemStack stack, Item.TooltipContext context, Minecraft client) {
        StringBuilder sb = new StringBuilder(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        // The type's own name too, so "shulker box" also finds one renamed "Kit".
        sb.append('\n').append(stack.getItem().getName(stack).getString());
        // Heads: profile name and texture (plus its alias) are searchable even after renaming,
        // as plain words and as head:<name> / headtex:<hash or alias>.
        HeadInfo head = HeadInfo.of(stack);
        if (head != null) {
            if (head.profileName() != null) sb.append('\n').append(head.profileName()).append("\nhead:").append(head.profileName());
            if (head.textureHash() != null) {
                sb.append("\nheadtex:").append(head.textureHash());
                String alias = TrackerServices.headAliases().get(head.textureHash());
                if (alias != null) sb.append('\n').append(alias).append("\nheadtex:").append(alias);
            }
        }
        if (stack.has(DataComponents.CONTAINER) || stack.has(DataComponents.BUNDLE_CONTENTS)) {
            // A shulker/bundle tooltip lists its contents, which are indexed as their own entries;
            // match only the container's own name and lore so results aren't doubled.
            sb.append('\n').append(stack.getHoverName().getString());
            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore != null) for (Component line : lore.lines()) sb.append('\n').append(line.getString());
            return sb.toString().toLowerCase(Locale.ROOT);
        }
        try {
            for (Component line : stack.getTooltipLines(context, client.player, TooltipFlag.NORMAL)) {
                sb.append('\n').append(line.getString());
            }
        } catch (RuntimeException e) {
            // A mod's tooltip code failing must not break search; fall back to the name.
            sb.append('\n').append(stack.getHoverName().getString());
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
