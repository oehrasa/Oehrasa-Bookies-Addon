// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker.data;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Stream;

/**
 * One server's memory, shared by all of the player's accounts (it is the same world). Only ender
 * chests are per account. Dimensions live inside the records, never in the bank key.
 */
public final class MemoryBank {
    public final String key;
    public final String server;
    /**
     * Accounts seen on this server: UUID -> name, for labelling their ender chests.
     */
    public final Map<UUID, String> accounts = new LinkedHashMap<>();
    /**
     * Dates (yyyy-mm-dd) the player was on this server. Staleness counts these, not calendar
     * days, so time away from the server doesn't age anything.
     */
    public final TreeSet<String> playDays = new TreeSet<>();
    /**
     * The account currently playing, or null before one has joined.
     */
    public @Nullable UUID currentPlayer;
    private final Map<UUID, EntityRecord> entities = new LinkedHashMap<>();
    private final Map<String, BlockRecord> blocks = new LinkedHashMap<>();
    /**
     * Every block position (both halves of a double chest) -> record key.
     */
    private final Map<String, String> blockIndex = new HashMap<>();
    /**
     * Keys trashed by the last area action, for undo.
     */
    private List<String> lastAreaAction = new ArrayList<>();
    private boolean dirty;
    private int quietTicks;
    private int revision;
    private final IdentityHashMap<ContainerRecord, SavedState> savedStates = new IdentityHashMap<>();
    private final Set<String> journalDeletedEntityKeys = new LinkedHashSet<>();
    private final Set<String> journalDeletedBlockKeys = new LinkedHashSet<>();
    private Map<UUID, String> savedAccounts = Map.of();
    private Set<String> savedPlayDays = Set.of();
    private List<String> savedLastAreaAction = List.of();

    public MemoryBank(String key, String server) {
        this.key = key;
        this.server = server;
    }

    public void markPlayedToday() {
        if (playDays.add(LocalDate.now().toString())) markDirty();
    }

    /**
     * Days played on this server after the day this time falls on.
     */
    public int playDaysSince(long millis) {
        String day = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString();
        return playDays.tailSet(day, false).size();
    }

    /**
     * Not opened during the last {@code staleDays} days played here. Only means "not checked for
     * a while": it may be safe in storage or gone; nothing urgent.
     */
    public boolean isStale(ContainerRecord r, int staleDays) {
        return r.hasSnapshot() && !(r instanceof InventoryRecord) && playDaysSince(r.lastOpened) >= staleDays;
    }

    public @Nullable EntityRecord get(UUID uuid) {
        return entities.get(uuid);
    }

    public EntityRecord getOrCreate(UUID uuid) {
        EntityRecord record = entities.get(uuid);
        if (record != null) return record;
        record = new EntityRecord(uuid);
        entities.put(uuid, record);
        journalDeletedEntityKeys.remove(uuid.toString());
        return record;
    }

    /**
     * The block record covering this position, including the second half of a double chest.
     */
    public @Nullable BlockRecord getBlockAt(String dimension, BlockPos pos) {
        String recordKey = blockIndex.get(BlockRecord.keyFor(dimension, pos));
        return recordKey == null ? null : blocks.get(recordKey);
    }

    public @Nullable BlockRecord getEnderChest(UUID owner) {
        return blocks.get(BlockRecord.enderKey(owner));
    }

    /**
     * For merging old per-account banks: keeps whichever copy was opened (or seen) most recently.
     */
    void mergeEntity(EntityRecord record) {
        EntityRecord old = entities.get(record.uuid);
        if (old == null || newer(record, old)) entities.put(record.uuid, record);
    }

    void mergeBlock(BlockRecord record) {
        BlockRecord old = blocks.get(record.key());
        if (old == null || newer(record, old)) putBlock(record);
    }

    /**
     * Counts from {@link #mergeFrom(MemoryBank)}: new records, records replaced by a newer copy, and records kept.
     */
    public record MergeResult(int added, int replaced, int kept) {
    }

    /**
     * Merges another bank (e.g. a split bank for another address of the same server) into this
     * one. Per record the newer copy wins wholesale - items are never unioned. Accounts and
     * play days are unioned. Ender chests keep their original owner.
     */
    public MergeResult mergeFrom(MemoryBank other) {
        int added = 0, replaced = 0, kept = 0;
        for (EntityRecord record : other.all()) {
            EntityRecord old = entities.get(record.uuid);
            if (old == null) {
                entities.put(record.uuid, record);
                added++;
            } else if (newer(record, old)) {
                entities.put(record.uuid, record);
                replaced++;
            } else {
                kept++;
            }
        }
        for (BlockRecord record : other.allBlocks()) {
            BlockRecord old = blocks.get(record.key());
            if (old == null) {
                putBlock(record);
                added++;
            } else if (newer(record, old)) {
                putBlock(record);
                replaced++;
            } else {
                kept++;
            }
        }
        other.accounts.forEach(accounts::putIfAbsent);
        playDays.addAll(other.playDays);
        if (added + replaced > 0) markDirty();
        return new MergeResult(added, replaced, kept);
    }

    private static boolean newer(ContainerRecord a, ContainerRecord b) {
        return a.lastOpened != b.lastOpened ? a.lastOpened > b.lastOpened : a.lastSeen > b.lastSeen;
    }

    /**
     * Adds or replaces a block record and re-indexes its positions.
     */
    public void putBlock(BlockRecord record) {
        BlockRecord old = blocks.put(record.key(), record);
        if (old != null) unindex(old);
        journalDeletedBlockKeys.remove(record.key());
        if (!record.global) {
            blockIndex.put(BlockRecord.keyFor(record.dimension, record.pos), record.key());
            for (BlockPos p : record.otherPositions) blockIndex.put(BlockRecord.keyFor(record.dimension, p), record.key());
        }
    }

    /**
     * Drops a block record that has been replaced by another one covering the same blocks (a
     * single chest that became a double chest). Only for that merge; nothing else deletes records.
     */
    public void replaceBlock(BlockRecord old, BlockRecord replacement) {
        if (blocks.remove(old.key()) != null) unindex(old);
        if (!replacement.key().equals(old.key())) journalDeletedBlockKeys.add(old.key());
        putBlock(replacement);
    }

    private void unindex(BlockRecord record) {
        if (record.global) return;
        blockIndex.remove(BlockRecord.keyFor(record.dimension, record.pos), record.key());
        for (BlockPos p : record.otherPositions) blockIndex.remove(BlockRecord.keyFor(record.dimension, p), record.key());
    }

    public @Nullable ContainerRecord getByKey(String recordKey) {
        BlockRecord block = blocks.get(recordKey);
        if (block != null) return block;
        try {
            return entities.get(UUID.fromString(recordKey));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Permanently forgets a record. Only for "Delete permanently" and for trash older than the
     * trash period, both of which follow an explicit "mark destroyed" by the player.
     */
    public void remove(ContainerRecord record) {
        if (record instanceof EntityRecord e) {
            if (entities.remove(e.uuid) != null) journalDeletedEntityKeys.add(e.uuid.toString());
        } else if (record instanceof BlockRecord b) {
            if (blocks.remove(b.key()) != null) {
                unindex(b);
                journalDeletedBlockKeys.add(b.key());
            }
        }
        savedStates.remove(record);
        markDirty();
    }

    /**
     * Applies a journal delete (already persisted), without moving the in-memory record to pending.
     */
    void applyDeletedEntity(String key) {
        UUID uuid;
        try {
            uuid = UUID.fromString(key);
        } catch (IllegalArgumentException ignored) {
            return;
        }
        entities.remove(uuid);
    }

    void applyDeletedBlock(String key) {
        BlockRecord record = blocks.remove(key);
        if (record != null) unindex(record);
    }

    public List<String> lastAreaAction() {
        return lastAreaAction;
    }

    public void setLastAreaAction(List<String> keys) {
        lastAreaAction = new ArrayList<>(keys);
        markDirty();
    }

    /**
     * Only for loading from disk.
     */
    void put(EntityRecord record) {
        entities.put(record.uuid, record);
    }

    public Collection<EntityRecord> all() {
        return Collections.unmodifiableCollection(entities.values());
    }

    public Collection<BlockRecord> allBlocks() {
        return Collections.unmodifiableCollection(blocks.values());
    }

    public Stream<ContainerRecord> allRecords() {
        return Stream.concat(entities.values().stream(), blocks.values().stream());
    }

    /**
     * Storage only: every record except villagers and wandering traders.
     */
    public Stream<ContainerRecord> containers() {
        return allRecords().filter(r -> !(r instanceof EntityRecord e && e.isTrader()));
    }

    /**
     * Villagers and wandering traders.
     */
    public Stream<EntityRecord> traders() {
        return entities.values().stream().filter(EntityRecord::isTrader);
    }

    public int size() {
        return entities.size() + blocks.size();
    }

    public void markDirty() {
        dirty = true;
        quietTicks = 0;
        revision++;
    }

    /**
     * Goes up with every change, for things worked out from the whole memory (the tally).
     */
    public int revision() {
        return revision;
    }

    public boolean isDirty() {
        return dirty;
    }

    /**
     * Counts quiet ticks since the last change; true once the debounce has passed.
     */
    public boolean tickDebounce(int debounceTicks) {
        if (!dirty) return false;
        return ++quietTicks >= debounceTicks;
    }

    public void clearDirty() {
        dirty = false;
        quietTicks = 0;
    }

    /**
     * Marks the current in-memory state as the persisted baseline used for journal deltas.
     */
    public void markJournaledBaseline() {
        savedStates.clear();
        for (EntityRecord r : entities.values()) savedStates.put(r, SavedState.capture(r));
        for (BlockRecord r : blocks.values()) savedStates.put(r, SavedState.capture(r));
        savedAccounts = Map.copyOf(accounts);
        savedPlayDays = Set.copyOf(playDays);
        savedLastAreaAction = List.copyOf(lastAreaAction);
        journalDeletedEntityKeys.clear();
        journalDeletedBlockKeys.clear();
    }

    public List<EntityRecord> changedEntities(List<EntityRecord> candidates) {
        List<EntityRecord> out = new ArrayList<>();
        for (EntityRecord r : candidates) if (isChanged(r)) out.add(r);
        return out;
    }

    public List<BlockRecord> changedBlocks(List<BlockRecord> candidates) {
        List<BlockRecord> out = new ArrayList<>();
        for (BlockRecord r : candidates) if (isChanged(r)) out.add(r);
        return out;
    }

    public boolean isChanged(ContainerRecord record) {
        SavedState saved = savedStates.get(record);
        return saved == null || !saved.matches(record);
    }

    public Set<String> journalDeletedEntityKeys() {
        return Collections.unmodifiableSet(journalDeletedEntityKeys);
    }

    public Set<String> journalDeletedBlockKeys() {
        return Collections.unmodifiableSet(journalDeletedBlockKeys);
    }

    public boolean hasJournalMetadataChanges() {
        return !savedAccounts.equals(accounts)
            || !savedPlayDays.equals(playDays)
            || !savedLastAreaAction.equals(lastAreaAction);
    }

    public boolean hasJournalMetadataChanges(Map<UUID, String> currentAccounts,
                                             Set<String> currentPlayDays, List<String> currentLastAreaAction) {
        return !savedAccounts.equals(currentAccounts)
            || !savedPlayDays.equals(currentPlayDays)
            || !savedLastAreaAction.equals(currentLastAreaAction);
    }

    /**
     * After a successful journal append/compact: the current state is the newest durable state.
     */
    public void markJournalWritten() {
        markJournaledBaseline();
    }

    /**
     * An incremental-save baseline. `lastSeen` is deliberately omitted: it would make every
     * loaded block look changed on every tracking pass and defeat delta-only saves.
     */
    private static final class SavedState {
        private final String type;
        private final String name;
        private final String dimension;
        private final double x, y, z;
        private final RecordStatus status;
        private final long firstSeen, lastOpened;
        private final int slotCount;
        private final List<ContainerRecord.SlotItem> items;
        private final long trashedAt;
        private final boolean autoTrashed;
        private final Set<String> flags;
        private final Set<String> hints;
        private final Integer homeChunkX, homeChunkZ;
        private final Boolean reappeared;
        private final Integer timesRehomed;
        private final String tradesJson;
        private final Boolean global;
        private final UUID owner;
        private final String ownerName;
        private final BlockPos pos;
        private final List<BlockPos> otherPositions;

        private SavedState(ContainerRecord r) {
            type = r.type;
            name = r.name;
            dimension = r.dimension;
            x = r.x;
            y = r.y;
            z = r.z;
            status = r.status;
            firstSeen = r.firstSeen;
            lastOpened = r.lastOpened;
            slotCount = r.slotCount;
            items = new ArrayList<>(r.items);
            trashedAt = r.trashedAt;
            autoTrashed = r.autoTrashed;
            flags = new LinkedHashSet<>(r.flags);
            hints = new LinkedHashSet<>(r.hints);
            if (r instanceof EntityRecord e) {
                homeChunkX = e.homeChunkX;
                homeChunkZ = e.homeChunkZ;
                reappeared = e.reappeared;
                timesRehomed = e.timesRehomed;
                tradesJson = e.trades == null ? null : e.trades.toJson().toString();
            } else {
                homeChunkX = null;
                homeChunkZ = null;
                reappeared = null;
                timesRehomed = null;
                tradesJson = null;
            }
            if (r instanceof BlockRecord b) {
                global = b.global;
                owner = b.owner;
                ownerName = b.ownerName;
                pos = b.pos;
                otherPositions = new ArrayList<>(b.otherPositions);
            } else {
                global = null;
                owner = null;
                ownerName = null;
                pos = null;
                otherPositions = null;
            }
        }

        static SavedState capture(ContainerRecord r) {
            return new SavedState(r);
        }

        boolean matches(ContainerRecord r) {
            if (!Objects.equals(type, r.type)
                || !Objects.equals(name, r.name)
                || !Objects.equals(dimension, r.dimension)
                || x != r.x || y != r.y || z != r.z
                || status != r.status
                || firstSeen != r.firstSeen
                || lastOpened != r.lastOpened
                || slotCount != r.slotCount
                || trashedAt != r.trashedAt
                || autoTrashed != r.autoTrashed
                || !flags.equals(r.flags)
                || !hints.equals(r.hints)
                || items.size() != r.items.size()) {
                return false;
            }
            for (int i = 0; i < items.size(); i++) {
                ContainerRecord.SlotItem oldItem = items.get(i);
                ContainerRecord.SlotItem newItem = r.items.get(i);
                if (oldItem != newItem && !oldItem.equals(newItem)) return false;
            }
            if (r instanceof EntityRecord e) {
                String trades = e.trades == null ? null : e.trades.toJson().toString();
                if (!Objects.equals(homeChunkX, e.homeChunkX)
                    || !Objects.equals(homeChunkZ, e.homeChunkZ)
                    || !Objects.equals(reappeared, e.reappeared)
                    || !Objects.equals(timesRehomed, e.timesRehomed)
                    || !Objects.equals(tradesJson, trades)) {
                    return false;
                }
            }
            if (r instanceof BlockRecord b) {
                if (!Objects.equals(global, b.global)
                    || !Objects.equals(owner, b.owner)
                    || !Objects.equals(ownerName, b.ownerName)
                    || !Objects.equals(pos, b.pos)
                    || !Objects.equals(otherPositions, b.otherPositions)) {
                    return false;
                }
            }
            return true;
        }
    }
}
