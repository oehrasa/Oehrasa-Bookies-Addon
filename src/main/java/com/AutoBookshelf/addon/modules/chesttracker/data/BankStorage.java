// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker.data;

import com.AutoBookshelf.addon.modules.chesttracker.TrackerConfig;
import com.AutoBookshelf.addon.modules.chesttracker.TrackerLog;
import com.google.gson.*;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads and writes memory banks under the addon's {@code ChestTracker/} folder: atomic writes,
 * daily rolling backups, and a corrupt file is set aside rather than replaced with an empty bank.
 * Nothing in here ever deletes a bank.
 */
public final class BankStorage {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    /**
     * Bank files are written compact: pretty printing tripled their size.
     */
    private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();
    /**
     * 1: every stack written out in full. 2: each distinct stack once, in a table ({@link StackStore}).
     * A file from a newer format than this is never written over (see {@link #load}).
     */
    private static final int FORMAT_VERSION = 2;
    /**
     * Regular saves are journal deltas; compact to a full snapshot once the journal grows past this.
     */
    private static final long JOURNAL_COMPACT_BYTES = 10L * 1024L * 1024L;

    private final Path root;
    private final TrackerConfig config;

    /**
     * Banks whose file is from a newer version of the addon: opened empty, never saved.
     */
    private final Set<String> readOnly = ConcurrentHashMap.newKeySet();

    /**
     * One-line status for the chat (compaction done / save failed), set on the I/O thread and
     * consumed by {@code BankManager} when {@code show-save-status} is on. Null means silent.
     */
    public volatile String saveNote;

    public BankStorage(Path root, TrackerConfig config) {
        this.root = root;
        this.config = config;
    }

    public Path bankFile(String key) {
        return root.resolve("banks").resolve(key + ".json");
    }

    private Path backupDir(String key) {
        return root.resolve("backups").resolve(key);
    }

    private Path journalFile(String key) {
        return root.resolve("journal").resolve(key + ".jsonl");
    }

    /**
     * True when a bank file or a journal exists for {@code key}.
     */
    public boolean bankOrJournalExists(String key) {
        return Files.exists(bankFile(key)) || Files.exists(journalFile(key));
    }

    /**
     * The mod's folder, {@code <game dir>/ChestTracker}.
     */
    public Path root() {
        return root;
    }

    /**
     * How many of each item, for scripts and Zymbot.
     */
    public Path tallyFile(String key) {
        return root.resolve("tally").resolve(key + ".json");
    }

    /**
     * Writes the tally beside the bank, readable by people too. Safe off the game thread.
     */
    public void writeTally(String key, JsonObject tally) {
        if (readOnly.contains(key)) return;
        Path file = tallyFile(key);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(tally), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // Only the tally is stale; the bank itself was saved.
            TrackerLog.LOGGER.error("Failed to write the tally {}", file, e);
        }
    }

    /**
     * Server name after aliases; never the resolved IP or world name. Not the account: all of the
     * player's accounts share one bank per server.
     */
    public static String keyFor(String server) {
        return server.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }

    /**
     * Maps a server-list address to the name its bank is stored under. Addresses listed together
     * in server-aliases.json share one bank.
     */
    public String resolveServer(String address) {
        String normalized = normalizeAddress(address);
        for (Map.Entry<String, JsonElement> group : loadAliases().entrySet()) {
            if (!group.getValue().isJsonArray()) continue;
            for (JsonElement alias : group.getValue().getAsJsonArray()) {
                if (normalizeAddress(alias.getAsString()).equals(normalized)) return group.getKey();
            }
        }
        return normalized;
    }

    /**
     * Every address that shares the bank called {@code server}, including the name itself.
     */
    public List<String> addressesFor(String server) {
        List<String> out = new ArrayList<>();
        out.add(server);
        JsonElement group = loadAliases().get(server);
        if (group != null && group.isJsonArray()) {
            for (JsonElement alias : group.getAsJsonArray()) out.add(normalizeAddress(alias.getAsString()));
        }
        return out;
    }

    /**
     * Adds {@code address} to the alias group of {@code canonicalServer} so future joins via
     * that address open the canonical bank. Safe off the game thread; a broken alias file is
     * left as it is.
     */
    public void addAlias(String canonicalServer, String address) {
        String normalized = normalizeAddress(address);
        if (normalized.equals(normalizeAddress(canonicalServer))) return;
        Path file = root.resolve("server-aliases.json");
        try {
            JsonObject aliases = loadAliases();
            JsonArray group;
            if (aliases.has(canonicalServer) && aliases.get(canonicalServer).isJsonArray()) {
                group = aliases.getAsJsonArray(canonicalServer);
            } else {
                group = new JsonArray();
                aliases.add(canonicalServer, group);
            }
            for (JsonElement alias : group) {
                if (normalizeAddress(alias.getAsString()).equals(normalized)) return;
            }
            group.add(normalized);
            Files.createDirectories(root);
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(aliases, writer);
            }
            TrackerLog.LOGGER.info("Aliased address {} to bank {}", normalized, canonicalServer);
        } catch (Exception e) {
            TrackerLog.LOGGER.error("Could not write {}", file, e);
        }
    }

    /**
     * Archives a merged-away bank and its journal under {@code backups/<key>/} (never deletes
     * them), so a bad merge can be undone by hand. Safe off the game thread.
     */
    public void archiveBank(String key) {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path bank = bankFile(key);
        Path journal = journalFile(key);
        try {
            Files.createDirectories(backupDir(key));
            if (Files.exists(bank)) Files.move(bank, backupDir(key).resolve("merged-" + stamp + ".json"));
            if (Files.exists(journal)) Files.move(journal, backupDir(key).resolve("merged-" + stamp + ".jsonl"));
            TrackerLog.LOGGER.info("Archived merged bank {} under {}", key, backupDir(key));
        } catch (IOException e) {
            TrackerLog.LOGGER.error("Could not archive merged bank {}", key, e);
        }
    }

    static String normalizeAddress(String address) {
        String a = address.trim().toLowerCase(Locale.ROOT);
        if (a.endsWith(":25565")) a = a.substring(0, a.length() - ":25565".length());
        if (a.endsWith(".")) a = a.substring(0, a.length() - 1);
        return a;
    }

    private JsonObject loadAliases() {
        Path file = root.resolve("server-aliases.json");
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(root);
                JsonObject defaults = new JsonObject();
                Files.writeString(file, GSON.toJson(defaults), StandardCharsets.UTF_8);
                return defaults;
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        } catch (Exception e) {
            // A broken alias file must not stop the bank from opening; it just opens unaliased.
            TrackerLog.LOGGER.error("Could not read {}, ignoring aliases", file, e);
            return new JsonObject();
        }
    }

    public record LoadResult(MemoryBank bank, String reason, @Nullable String warning) {
    }

    public LoadResult load(String server) {
        String key = keyFor(server);
        Path file = bankFile(key);
        readOnly.remove(key);
        if (!Files.exists(file)) {
            LoadResult merged = mergeLegacy(key, server);
            applyJournal(merged.bank(), key);
            return merged;
        }
        try {
            JsonObject json = parse(file);
            int format = json.has("formatVersion") ? json.get("formatVersion").getAsInt() : 1;
            if (format > FORMAT_VERSION) {
                // Written by a newer version of this mod: this one can't read it right, and saving
                // would throw away what it doesn't understand. Leave the file alone.
                readOnly.add(key);
                TrackerLog.LOGGER.warn("Bank {} is format {}, this version reads up to {}; not reading or saving it",
                    file, format, FORMAT_VERSION);
                return new LoadResult(new MemoryBank(key, server), "file from a newer version, left untouched",
                    "Chest Tracker: this server's memory was saved by a newer version of the mod. It was left"
                        + " untouched, and nothing will be remembered until you update.");
            }
            MemoryBank bank = read(json, key, server, null);
            if (format < FORMAT_VERSION) keepOldFormat(key, file, format, bank);
            // The version walk can be skipped when the snapshot says every item is current and
            // no journal delta (which carries no version summary) has been replayed on top.
            boolean journalEmpty = !Files.exists(journalFile(key)) || Files.size(journalFile(key)) == 0;
            boolean allCurrent = json.has("allItemsCurrent") && json.get("allItemsCurrent").getAsBoolean();
            applyJournal(bank, key);
            String warning = allCurrent && journalEmpty ? null : checkVersions(bank, key, file);
            return new LoadResult(bank, "loaded existing file", warning);
        } catch (Exception e) {
            TrackerLog.LOGGER.error("Bank file {} is unreadable", file, e);
            Path corrupt = file.resolveSibling(key + ".json.corrupt-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
            try {
                Files.move(file, corrupt);
            } catch (IOException moveError) {
                TrackerLog.LOGGER.error("Could not set aside corrupt bank {}", file, moveError);
            }
            for (Path backup : backupsNewestFirst(key)) {
                try {
                    MemoryBank bank = read(parse(backup), key, server, null);
                    applyJournal(bank, key);
                    checkVersions(bank, key, backup);
                    bank.markDirty(); // write the restored data back as the live file
                    return new LoadResult(bank, "restored from backup " + backup.getFileName(),
                        "Chest Tracker: bank file was unreadable, restored backup " + backup.getFileName()
                            + ". The broken file was kept as " + corrupt.getFileName());
                } catch (Exception backupError) {
                    TrackerLog.LOGGER.error("Backup {} is unreadable too", backup, backupError);
                }
            }
            return new LoadResult(new MemoryBank(key, server), "file unreadable, no usable backup",
                "Chest Tracker: bank file was unreadable and no backup worked. It was kept as "
                    + corrupt.getFileName() + "; starting empty.");
        }
    }

    /**
     * Applies the journal deltas to a bank loaded from {@link #bankFile}. A partial trailing line
     * (process killed mid-append) is set aside whole so later appends start from a clean file.
     */
    private void applyJournal(MemoryBank bank, String key) {
        Path journal = journalFile(key);
        if (!Files.exists(journal)) {
            bank.markJournaledBaseline();
            journalSizes.put(key, 0L);
            return;
        }
        boolean corrupt = false;
        java.util.List<String> preserved = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(journal, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonObject op = JsonParser.parseString(line).getAsJsonObject();
                    String operation = op.get("op").getAsString();
                    String kind = op.has("kind") ? op.get("kind").getAsString() : null;
                    if ("upsert".equals(operation) && "entity".equals(kind)) {
                        String uuid = op.get("key").getAsString();
                        bank.put(EntityRecord.fromJson(UUID.fromString(uuid), op.getAsJsonObject("record")));
                    } else if ("upsert".equals(operation) && "block".equals(kind)) {
                        bank.putBlock(BlockRecord.fromJson(op.getAsJsonObject("record")));
                    } else if ("delete".equals(operation) && "entity".equals(kind)) {
                        bank.applyDeletedEntity(op.get("key").getAsString());
                    } else if ("delete".equals(operation) && "block".equals(kind)) {
                        bank.applyDeletedBlock(op.get("key").getAsString());
                    } else if ("meta".equals(operation)) {
                        if (op.has("accounts")) {
                            bank.accounts.clear();
                            for (Map.Entry<String, JsonElement> e : op.getAsJsonObject("accounts").entrySet()) {
                                bank.accounts.put(UUID.fromString(e.getKey()), e.getValue().getAsString());
                            }
                        }
                        if (op.has("playDays")) {
                            bank.playDays.clear();
                            for (JsonElement e : op.getAsJsonArray("playDays")) bank.playDays.add(e.getAsString());
                        }
                        if (op.has("lastAreaAction")) {
                            List<String> keys = new ArrayList<>();
                            for (JsonElement e : op.getAsJsonArray("lastAreaAction")) keys.add(e.getAsString());
                            bank.setLastAreaAction(keys);
                        }
                    }
                    preserved.add(line);
                } catch (Exception e) {
                    corrupt = true;
                    TrackerLog.LOGGER.error("Could not apply journal line for bank {}; skipping it", key, e);
                }
            }
        } catch (IOException e) {
            TrackerLog.LOGGER.error("Could not read journal for bank {}", key, e);
        }
        if (corrupt) {
            Path moved = journal.resolveSibling(key + ".journal.jsonl.corrupt-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
            try {
                Files.copy(journal, moved);
            } catch (IOException copyError) {
                TrackerLog.LOGGER.error("Could not preserve corrupt journal {}", journal, copyError);
            }
            Path fixed = journal.resolveSibling(key + ".journal.jsonl.fixed");
            try {
                Files.writeString(fixed,
                    preserved.isEmpty() ? "" : String.join("\n", preserved) + "\n", StandardCharsets.UTF_8);
                try {
                    Files.move(fixed, journal, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(fixed, journal, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException writeError) {
                TrackerLog.LOGGER.error("Could not rewrite journal without the corrupt line {}", journal, writeError);
            }
        }
        bank.markJournaledBaseline();
        bank.clearDirty();
        try {
            journalSizes.put(key, Files.size(journal));
        } catch (IOException e) {
            journalSizes.put(key, 0L);
        }
    }

    /**
     * Banks used to be per server + account (key_uuid.json). If only those exist, merge them into
     * the shared bank; the old files are left untouched.
     */
    private LoadResult mergeLegacy(String key, String server) {
        Pattern legacyName = Pattern.compile(Pattern.quote(key) + "_([0-9a-f-]{36})\\.json");
        MemoryBank merged = new MemoryBank(key, server);
        List<String> used = new ArrayList<>();
        Path dir = root.resolve("banks");
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path p : files.toList()) {
                    Matcher m = legacyName.matcher(p.getFileName().toString());
                    if (!m.matches()) continue;
                    try {
                        MemoryBank old = read(parse(p), key, server, UUID.fromString(m.group(1)));
                        for (EntityRecord r : old.all()) merged.mergeEntity(r);
                        for (BlockRecord r : old.allBlocks()) merged.mergeBlock(r);
                        old.accounts.forEach(merged.accounts::putIfAbsent);
                        merged.playDays.addAll(old.playDays);
                        used.add(p.getFileName().toString());
                    } catch (Exception e) {
                        TrackerLog.LOGGER.error("Could not read old bank {}; left as it is", p, e);
                    }
                }
            } catch (IOException e) {
                TrackerLog.LOGGER.error("Could not list {}", dir, e);
            }
        }
        if (used.isEmpty()) return new LoadResult(merged, "new bank (no file yet)", null);
        checkVersions(merged, key, null);
        merged.markDirty();
        return new LoadResult(merged, "merged old per-account banks " + used + " (old files kept)", null);
    }

    /**
     * Upgrades items saved by an older Minecraft version (see {@link ItemData}) and, when the file
     * holds items from a newer version (the bank was used by a newer game, and this is an older
     * one), keeps a copy of the file and returns a warning. The newer items are left exactly as
     * they are; only reopening a container replaces them with this version's.
     */
    private @Nullable String checkVersions(MemoryBank bank, String key, @Nullable Path file) {
        int upgraded = 0;
        long newer = 0;
        for (ContainerRecord r : bank.allRecords().toList()) {
            upgraded += r.upgradeItems();
            newer += r.newerItems();
            if (r instanceof EntityRecord e && e.trades != null) {
                upgraded += e.trades.upgradeItems();
                newer += e.trades.newerSnapshots();
            }
        }
        if (upgraded > 0) {
            TrackerLog.LOGGER.info("Upgraded {} items saved by an older Minecraft version in bank {}", upgraded, key);
            bank.markDirty();
        }
        if (newer == 0 || file == null) return null;
        Path copy = backupDir(key).resolve("from-newer-versions")
            .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".json");
        try {
            Files.createDirectories(copy.getParent());
            Files.copy(file, copy);
        } catch (IOException e) {
            TrackerLog.LOGGER.error("Could not copy {} to {}", file, copy, e);
        }
        TrackerLog.LOGGER.warn("Bank {} has {} items from a newer Minecraft version (this is data version {}); kept as they are, copy at {}",
            key, newer, ItemData.CURRENT, copy);
        return "Chest Tracker: this memory was last used by a newer Minecraft version. " + newer
            + " remembered items from it are kept unchanged and shown by type only until you open those"
            + " containers again. A copy of the file was saved in backups/" + key + "/from-newer-versions.";
    }

    /**
     * A file in an older format is kept as it was, once (older versions of the mod can read only
     * that copy), and the bank is saved again in the new format.
     */
    private void keepOldFormat(String key, Path file, int format, MemoryBank bank) {
        Path copy = backupDir(key).resolve("before-format-" + FORMAT_VERSION + ".json");
        try {
            if (!Files.exists(copy)) {
                Files.createDirectories(copy.getParent());
                Files.copy(file, copy);
            }
            bank.markDirty();
            TrackerLog.LOGGER.info("Bank {} is format {}; kept a copy at {} and saving it as format {}",
                key, format, copy, FORMAT_VERSION);
        } catch (IOException e) {
            // Without the copy, leave the file in its old format rather than risk the only one.
            TrackerLog.LOGGER.error("Could not copy {} to {}; keeping the old format for now", file, copy, e);
        }
    }

    private static JsonObject parse(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    /**
     * {@code legacyOwner}: the account of an old per-account file, for its ownerless ender chest.
     */
    private static MemoryBank read(JsonObject root, String key, String server, @Nullable UUID legacyOwner) {
        // Books first: their table is referred to from inside the stacks table, each entry once.
        BookStore.unpack(root);
        StackStore.unpack(root);
        MemoryBank bank = new MemoryBank(key, server);
        if (root.has("accounts")) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("accounts").entrySet()) {
                bank.accounts.put(UUID.fromString(e.getKey()), e.getValue().getAsString());
            }
        }
        if (legacyOwner == null && root.has("player")) legacyOwner = UUID.fromString(root.get("player").getAsString());
        if (legacyOwner != null) bank.accounts.putIfAbsent(legacyOwner, legacyOwner.toString().substring(0, 8));
        JsonObject entities = root.getAsJsonObject("entities");
        for (Map.Entry<String, JsonElement> e : entities.entrySet()) {
            UUID uuid = UUID.fromString(e.getKey());
            bank.put(EntityRecord.fromJson(uuid, e.getValue().getAsJsonObject()));
        }
        if (root.has("blocks")) {
            for (JsonElement e : root.getAsJsonArray("blocks")) {
                BlockRecord r = BlockRecord.fromJson(e.getAsJsonObject());
                if (r.global && r.owner == null) r.owner = legacyOwner;
                bank.putBlock(r);
            }
        }
        if (root.has("playDays")) {
            for (JsonElement e : root.getAsJsonArray("playDays")) bank.playDays.add(e.getAsString());
        }
        if (root.has("lastAreaAction")) {
            List<String> keys = new ArrayList<>();
            for (JsonElement e : root.getAsJsonArray("lastAreaAction")) keys.add(e.getAsString());
            bank.setLastAreaAction(keys);
            bank.clearDirty();
        }
        return bank;
    }

    /**
     * Saves right away on this thread (e.g. when the game is closing).
     */
    public void save(MemoryBank bank) {
        JsonObject root = snapshot(bank);
        bank.clearDirty();
        if (!write(bank.key, root, bank.size())) bank.markDirty();
    }

    /**
     * The bank as JSON, taken from the supplied point-in-time lists. Item data is shared, not
     * copied, so the slow part (deduplication and writing the file) can run on the I/O thread.
     */
    public JsonObject snapshot(MemoryBank bank) {
        return snapshot(bank.key, bank.server,
            new ArrayList<>(bank.all()), new ArrayList<>(bank.allBlocks()),
            new java.util.LinkedHashMap<>(bank.accounts), new TreeSet<>(bank.playDays),
            new ArrayList<>(bank.lastAreaAction()));
    }

    public JsonObject snapshot(String key, String server, List<EntityRecord> entities,
                               List<BlockRecord> blocks, Map<UUID, String> accounts, Set<String> playDays,
                               List<String> lastAreaAction) {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", FORMAT_VERSION);
        // The game version that last saved the file; each record and item also says which wrote it.
        root.addProperty("dataVersion", ItemData.CURRENT);
        // Additive load hint: every stored item is already at the current data version, so the
        // loader can skip the per-item version walk. Old files lack the key and are walked.
        boolean allCurrent = true;
        for (EntityRecord r : entities)
            for (ContainerRecord.SlotItem item : r.items) {
                if (item.dataVersion() != ItemData.CURRENT) {
                    allCurrent = false;
                    break;
                }
            }
        if (allCurrent) for (BlockRecord r : blocks)
            for (ContainerRecord.SlotItem item : r.items) {
                if (item.dataVersion() != ItemData.CURRENT) {
                    allCurrent = false;
                    break;
                }
            }
        root.addProperty("allItemsCurrent", allCurrent);
        root.addProperty("server", server);
        JsonObject accountsJson = new JsonObject();
        accounts.forEach((uuid, name) -> accountsJson.addProperty(uuid.toString(), name));
        root.add("accounts", accountsJson);
        JsonObject entitiesJson = new JsonObject();
        for (EntityRecord r : entities) entitiesJson.add(r.uuid.toString(), r.toJson());
        root.add("entities", entitiesJson);
        JsonArray blocksJson = new JsonArray();
        for (BlockRecord r : blocks) blocksJson.add(r.toJson());
        root.add("blocks", blocksJson);
        JsonArray lastArea = new JsonArray();
        for (String k : lastAreaAction) lastArea.add(k);
        root.add("lastAreaAction", lastArea);
        JsonArray playDaysJson = new JsonArray();
        playDays.forEach(playDaysJson::add);
        root.add("playDays", playDaysJson);
        return root;
    }

    /**
     * Writes a snapshot (backup, temp file, atomic rename). Safe off the game thread.
     */
    public boolean write(String key, JsonObject root, int size) {
        Path file = bankFile(key);
        if (readOnly.contains(key)) {
            TrackerLog.LOGGER.warn("Not saving bank {}: its file is from a newer version", key);
            return true; // not a failure to retry
        }
        try {
            Files.createDirectories(file.getParent());
            backupIfNeeded(key, file);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                // Here, off the game thread: walking every item for books and stacks is the slow part.
                COMPACT.toJson(StackStore.pack(BookStore.pack(root)), writer);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            TrackerLog.LOGGER.info("Saved bank {} ({} entities) to {}", key, size, file);
            return true;
        } catch (IOException e) {
            TrackerLog.LOGGER.error("Failed to save bank {} to {}", key, file, e);
            return false;
        }
    }

    /**
     * The journal payload, built on the game thread. The IO thread must never read a live
     * {@link EntityRecord} or {@link BlockRecord}: the game thread mutates them while it tracks,
     * so a read there is a torn snapshot at best and a {@code ConcurrentModificationException} at
     * worst.
     */
    public record JournalBatch(JsonObject compaction, List<JsonObject> lines, int recordCount) {
        public static final JournalBatch EMPTY = new JournalBatch(null, List.of(), 0);
    }

    /**
     * Journal sizes as of the last write or load, so the game thread can decide about compaction.
     */
    private final Map<String, Long> journalSizes = new ConcurrentHashMap<>();

    /**
     * Builds the journal payload. Runs on the game thread, where the records are consistent.
     */
    public JournalBatch prepareJournal(String key, MemoryBank bank, List<EntityRecord> entities,
                                       List<BlockRecord> blocks, Map<UUID, String> accounts, Set<String> playDays,
                                       List<String> lastAreaAction, boolean allowCompaction) {
        if (readOnly.contains(key)) return JournalBatch.EMPTY;

        List<JsonObject> lines = new ArrayList<>();
        for (EntityRecord e : bank.changedEntities(entities)) {
            JsonObject op = new JsonObject();
            op.addProperty("op", "upsert");
            op.addProperty("kind", "entity");
            op.addProperty("key", e.uuid.toString());
            op.add("record", e.toJson());
            lines.add(op);
        }
        for (BlockRecord b : bank.changedBlocks(blocks)) {
            JsonObject op = new JsonObject();
            op.addProperty("op", "upsert");
            op.addProperty("kind", "block");
            op.addProperty("key", b.key());
            op.add("record", b.toJson());
            lines.add(op);
        }
        for (String deleted : bank.journalDeletedEntityKeys()) {
            JsonObject op = new JsonObject();
            op.addProperty("op", "delete");
            op.addProperty("kind", "entity");
            op.addProperty("key", deleted);
            lines.add(op);
        }
        for (String deleted : bank.journalDeletedBlockKeys()) {
            JsonObject op = new JsonObject();
            op.addProperty("op", "delete");
            op.addProperty("kind", "block");
            op.addProperty("key", deleted);
            lines.add(op);
        }
        if (bank.hasJournalMetadataChanges(accounts, playDays, lastAreaAction)) {
            JsonObject op = new JsonObject();
            op.addProperty("op", "meta");
            JsonObject accountsJson = new JsonObject();
            accounts.forEach((uuid, name) -> accountsJson.addProperty(uuid.toString(), name));
            op.add("accounts", accountsJson);
            JsonArray playDaysJson = new JsonArray();
            playDays.forEach(playDaysJson::add);
            op.add("playDays", playDaysJson);
            JsonArray lastAreaJson = new JsonArray();
            lastAreaAction.forEach(lastAreaJson::add);
            op.add("lastAreaAction", lastAreaJson);
            lines.add(op);
        }

        // The journal is compacted to a full snapshot once it has grown past the threshold. The
        // snapshot serialises every record, so it has to be built here as well. A big bank can
        // stall the client here, so log how long it took; a freeze on world exit is diagnosable.
        JsonObject compaction = null;
        if (allowCompaction && journalSizes.getOrDefault(key, 0L) > JOURNAL_COMPACT_BYTES) {
            long started = System.nanoTime();
            compaction = snapshot(key, bank.server, entities, blocks, accounts, playDays, lastAreaAction);
            long ms = (System.nanoTime() - started) / 1_000_000L;
            TrackerLog.LOGGER.info("Built the compaction snapshot for bank {} ({} entities, {} blocks) in {} ms",
                key, entities.size(), blocks.size(), ms);
        }
        return new JournalBatch(compaction, lines, entities.size() + blocks.size());
    }

    /**
     * Writes a prepared journal payload. Safe off the game thread: it only touches the payload
     * and the file.
     */
    public boolean writeJournal(String key, JournalBatch batch) {
        if (readOnly.contains(key)) return true;
        Path journal = journalFile(key);
        try {
            if (batch.compaction() != null) {
                long started = System.nanoTime();
                if (!write(key, batch.compaction(), batch.recordCount())) {
                    if (config.showSaveStatus) saveNote = "Chest Tracker: compaction of bank " + key + " failed; will retry.";
                    return false;
                }
                Files.deleteIfExists(journal);
                journalSizes.put(key, 0L);
                if (config.showSaveStatus) {
                    double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
                    saveNote = String.format(java.util.Locale.ROOT,
                        "Chest Tracker: compacted bank %s in %.1fs.", key, seconds);
                }
                return true;
            }
            if (batch.lines().isEmpty()) return true;
            Files.createDirectories(journal.getParent());
            try (Writer writer = Files.newBufferedWriter(journal, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                for (JsonObject line : batch.lines()) {
                    COMPACT.toJson(line, writer);
                    writer.write('\n');
                }
            }
            journalSizes.put(key, Files.size(journal));
            return true;
        } catch (IOException e) {
            TrackerLog.LOGGER.error("Failed to append journal for bank {}", key, e);
            if (config.showSaveStatus) saveNote = "Chest Tracker: saving bank " + key + " failed; will retry.";
            return false;
        }
    }

    /**
     * First save of each day copies the current file into backups/, keeping the newest few.
     */
    private void backupIfNeeded(String key, Path file) throws IOException {
        if (!Files.exists(file)) return;
        Path dir = backupDir(key);
        Path today = dir.resolve(LocalDate.now() + ".json");
        if (Files.exists(today)) return;
        Files.createDirectories(dir);
        Files.copy(file, today);
        List<Path> backups = backupsNewestFirst(key);
        for (int i = config.backupsToKeep; i < backups.size(); i++) {
            Files.deleteIfExists(backups.get(i));
        }
    }

    private List<Path> backupsNewestFirst(String key) {
        Path dir = backupDir(key);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}\\.json"))
                .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                .toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
