// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker.search;

import com.AutoBookshelf.addon.modules.chesttracker.TrackerLog;
import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Which items a typed name means, for the tally: "totems", "enchanted golden apples", "gapples".
 * In order:
 * <ol>
 *   <li>an alias from {@code item-aliases.json} (players' words: gapple, egap, pearls, xp...);</li>
 *   <li>an item whose name or id is exactly the words typed;</li>
 *   <li>items whose name has every word typed ("shulker box" gives all the colours).</li>
 * </ol>
 * Plurals don't matter on either side ("totems" is "totem", "bricks" is "brick"). Exact means
 * exact: "golden apples" is golden apples only, not the enchanted ones.
 */
public final class ItemQuery {
    private ItemQuery() {
    }

    public enum Kind {ALIAS, EXACT, WORDS, NONE}

    /**
     * {@code ids} best first; empty for NONE.
     */
    public record Result(Kind kind, List<String> ids) {
        public boolean found() {
            return kind != Kind.NONE;
        }
    }

    /**
     * Alias (normalised, singular) -> id patterns as written ("minecraft:*shulker_box").
     */
    private static final Map<String, List<String>> aliases = new LinkedHashMap<>();
    private static @Nullable Path aliasFile;
    private static long aliasModified = -1;

    private static final Map<String, List<String>> DEFAULTS = new LinkedHashMap<>();

    static {
        alias("minecraft:totem_of_undying", "totem");
        alias("minecraft:enchanted_golden_apple", "egap", "god apple", "notch apple", "enchanted gapple");
        DEFAULTS.put("gapple", List.of("minecraft:enchanted_golden_apple", "minecraft:golden_apple"));
        DEFAULTS.put("gap", List.of("minecraft:enchanted_golden_apple", "minecraft:golden_apple"));
        alias("minecraft:golden_apple", "crapple");
        alias("minecraft:ender_pearl", "pearl", "epearl");
        alias("minecraft:experience_bottle", "xp", "exp", "xp bottle", "exp bottle", "bottle o enchanting");
        alias("minecraft:end_crystal", "crystal");
        alias("minecraft:obsidian", "obby", "obi");
        alias("minecraft:respawn_anchor", "anchor");
        alias("minecraft:firework_rocket", "rocket", "firework");
        alias("minecraft:*shulker_box", "shulker", "shulker box");
        alias("minecraft:*_bed", "bed");
    }

    private static void alias(String pattern, String... names) {
        for (String name : names) DEFAULTS.put(name, List.of(pattern));
    }

    /**
     * Reads {@code <dir>/item-aliases.json}, writing the defaults there first if it isn't there.
     */
    public static synchronized void loadAliases(Path dir) {
        Path file = dir.resolve("item-aliases.json");
        aliasFile = file;
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(dir);
                JsonObject o = new JsonObject();
                o.addProperty("_about", "Words for the tally (/etracker tally, the tracker's search line, the"
                    + " tally file). Each name maps to item ids; * matches any part of an id. Plurals are"
                    + " handled, so \"totem\" also covers \"totems\".");
                DEFAULTS.forEach((name, ids) -> {
                    JsonArray a = new JsonArray();
                    ids.forEach(a::add);
                    o.add(name, a);
                });
                Files.writeString(file, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(o),
                    StandardCharsets.UTF_8);
            }
            long modified = Files.getLastModifiedTime(file).toMillis();
            if (modified == aliasModified) return;
            JsonObject o;
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                o = JsonParser.parseReader(reader).getAsJsonObject();
            }
            aliases.clear();
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                if (e.getKey().startsWith("_")) continue;
                List<String> ids = new ArrayList<>();
                if (e.getValue().isJsonArray()) e.getValue().getAsJsonArray().forEach(v -> ids.add(v.getAsString()));
                else ids.add(e.getValue().getAsString());
                aliases.put(singular(normalize(e.getKey())), ids);
            }
            aliasModified = modified;
        } catch (Exception e) {
            // A broken alias file only loses the aliases; names and ids still work.
            TrackerLog.LOGGER.error("Could not read {}, using the built-in item aliases", file, e);
            aliases.clear();
            DEFAULTS.forEach((name, ids) -> aliases.put(singular(normalize(name)), ids));
        }
    }

    /**
     * Re-reads the alias file if it was edited since.
     */
    private static void refreshAliases() {
        if (aliasFile != null) loadAliases(aliasFile.getParent());
    }

    public static Result resolve(String query) {
        refreshAliases();
        String q = singular(normalize(query));
        if (q.isEmpty()) return new Result(Kind.NONE, List.of());
        List<String> alias = aliases.get(q);
        if (alias != null) return new Result(Kind.ALIAS, expand(alias));

        List<String> exact = new ArrayList<>();
        List<String> words = new ArrayList<>();
        List<String> wanted = Arrays.asList(q.split(" "));
        for (Map.Entry<String, String> e : names().entrySet()) {
            String name = singular(normalize(e.getValue()));
            String idWords = singular(normalize(e.getKey()));
            if (q.equals(name) || q.equals(idWords)) exact.add(e.getKey());
            else if (Arrays.asList(name.split(" ")).containsAll(wanted)) words.add(e.getKey());
        }
        if (!exact.isEmpty()) return new Result(Kind.EXACT, exact);
        if (!words.isEmpty()) return new Result(Kind.WORDS, words);
        return new Result(Kind.NONE, List.of());
    }

    /**
     * Every alias with its patterns expanded to the items that exist, for the tally file.
     */
    public static Map<String, List<String>> expandedAliases() {
        refreshAliases();
        Map<String, List<String>> out = new LinkedHashMap<>();
        aliases.forEach((name, ids) -> out.put(name, expand(ids)));
        return out;
    }

    private static List<String> expand(List<String> patterns) {
        Set<String> out = new LinkedHashSet<>();
        for (String p : patterns) {
            if (!p.contains("*")) {
                out.add(p);
                continue;
            }
            Pattern regex = Pattern.compile(Pattern.quote(p).replace("*", "\\E.*\\Q"));
            names().keySet().stream().filter(id -> regex.matcher(id).matches()).sorted().forEach(out::add);
        }
        return new ArrayList<>(out);
    }

    private static final Map<String, String> nameCache = new LinkedHashMap<>();
    private static @Nullable String nameLanguage;

    /**
     * Item id -> name in the game's language, for every item there is. Game thread.
     */
    public static Map<String, String> names() {
        String language = Minecraft.getInstance().getLanguageManager().getSelected();
        if (!language.equals(nameLanguage) || nameCache.isEmpty()) {
            nameCache.clear();
            for (Item item : BuiltInRegistries.ITEM) {
                nameCache.put(BuiltInRegistries.ITEM.getKey(item).toString(), Component.translatable(item.getDescriptionId()).getString());
            }
            nameLanguage = language;
        }
        return nameCache;
    }

    /**
     * The item's name, or its id in words for an item this game doesn't have (a removed mod).
     */
    public static String name(String id) {
        String name = names().get(id);
        if (name != null) return name;
        Identifier parsed = Identifier.tryParse(id);
        return parsed == null ? id : parsed.getPath().replace('_', ' ');
    }

    /**
     * Lowercase words: "minecraft:Totem_of_Undying" -> "totem of undying".
     */
    public static String normalize(String s) {
        String out = s.toLowerCase(Locale.ROOT).trim();
        if (out.startsWith("minecraft:")) out = out.substring("minecraft:".length());
        out = out.replace('_', ' ').replace(':', ' ').replaceAll("[^\\p{L}\\p{N}* ]", " ");
        return out.replaceAll("\\s+", " ").trim();
    }

    /**
     * Each word made singular: "enchanted golden apples" -> "enchanted golden apple".
     */
    public static String singular(String words) {
        StringBuilder out = new StringBuilder();
        for (String w : words.split(" ")) {
            if (out.length() > 0) out.append(' ');
            out.append(singularWord(w));
        }
        return out.toString();
    }

    private static String singularWord(String w) {
        if (w.length() <= 3) return w;
        if (w.endsWith("ies")) return w.substring(0, w.length() - 3) + "y";
        if (w.endsWith("xes") || w.endsWith("ches") || w.endsWith("shes") || w.endsWith("sses")) return w.substring(0, w.length() - 2);
        if (w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us")) return w.substring(0, w.length() - 1);
        return w;
    }

    /**
     * Ids sorted by how many there are, most first.
     */
    public static List<String> byCount(List<String> ids, Tally tally) {
        List<String> sorted = new ArrayList<>(ids);
        sorted.sort(Comparator.comparingLong((String id) -> -tally.get(id).total).thenComparing(ItemQuery::name));
        return sorted;
    }
}
