// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker.track;

import com.AutoBookshelf.addon.modules.chesttracker.TrackerConfig;
import com.AutoBookshelf.addon.modules.chesttracker.TrackerLog;
import com.AutoBookshelf.addon.modules.chesttracker.data.*;
import com.AutoBookshelf.addon.modules.chesttracker.search.Tally;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Opens the bank for the current server once the player exists, saves it after changes, and
 * closes it on disconnect. Respawns and dimension changes never touch it.
 */
public final class BankManager {
    private final BankStorage storage;
    private final TrackerConfig config;
    private @Nullable MemoryBank bank;
    /**
     * Loads and writes run here, one at a time and in order, so reading or writing a big bank
     * never freezes the game, and a reconnect can't read a file that is still being written.
     */
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "chesttracker-io");
        t.setDaemon(true);
        return t;
    });
    private @Nullable Future<BankStorage.LoadResult> loading;
    private @Nullable String loadingAddress;
    /**
     * The connection the bank was opened for.
     */
    private @Nullable ClientPacketListener connection;
    private @Nullable Future<?> lastWrite;
    private @Nullable String pendingWarning;
    private long ticks;
    /**
     * The pre-bank {@code tracked_containers.json} import currently running, if any.
     */
    private @Nullable Future<List<BlockRecord>> legacyMigration;
    /**
     * The bank key the legacy import belongs to (null until one has been migrated or found absent).
     */
    private @Nullable String legacyMigrationBankKey;
    /**
     * A split-bank merge ({@code .ct bank merge}) currently loading its source bank, if any.
     */
    private @Nullable Future<BankStorage.LoadResult> merging;
    private @Nullable String mergeSourceKey;
    /**
     * The bank the merge target was when the merge started; a reconnect abandons the merge.
     */
    private @Nullable String mergeTargetKey;

    public BankManager(BankStorage storage, TrackerConfig config) {
        this.storage = storage;
        this.config = config;
    }

    public @Nullable MemoryBank bank() {
        return bank;
    }

    public BankStorage storage() {
        return storage;
    }

    public void tick(Minecraft client) {
        // The bank belongs to one connection. If the disconnect event was missed (seen when leaving
        // one server and joining another), a new or missing connection still closes it, so the
        // next server never gets the previous server's bank.
        if ((bank != null || loading != null) && client.getConnection() != connection) close();
        if (bank == null && loading == null && client.player != null && client.level != null) startLoading(client);
        if (loading != null && loading.isDone() && client.player != null) open(client);
        if (bank != null) {
            maybeStartLegacyMigration();
            applyLegacyMigration();
            applyMerge();
        }
        if (bank != null && ++ticks % 1200 == 0) bank.markPlayedToday(); // also catches playing past midnight
        if (bank != null && bank.tickDebounce(config.saveDebounceTicks)) saveInBackground();
        if (pendingWarning != null && client.player != null) {
            ChatUtils.warning(pendingWarning);
            pendingWarning = null;
        }
    }

    /**
     * True while the bank is being read from disk (the screens show "Loading memory...").
     */
    public boolean isLoading() {
        return loading != null;
    }

    private void startLoading(Minecraft client) {
        connection = client.getConnection();
        loadingAddress = serverName(client);
        String server = storage.resolveServer(loadingAddress);
        loading = io.submit(() -> storage.load(server));
    }

    private void open(Minecraft client) {
        String address = loadingAddress;
        BankStorage.LoadResult result;
        try {
            result = loading.get();
        } catch (Exception e) {
            TrackerLog.LOGGER.error("Loading the bank failed", e);
            loading = null;
            return;
        }
        loading = null;
        bank = result.bank();
        UUID me = client.player.getUUID();
        String myName = client.player.getName().getString();
        bank.currentPlayer = me;
        bank.markPlayedToday();
        if (!myName.equals(bank.accounts.put(me, myName))) bank.markDirty();
        // Ender chests carried over from old per-account banks only know the account's UUID.
        for (BlockRecord r : bank.allBlocks()) {
            if (r.global && r.owner != null && bank.accounts.containsKey(r.owner)
                && !bank.accounts.get(r.owner).equals(r.ownerName)) {
                r.ownerName = bank.accounts.get(r.owner);
                bank.markDirty();
            }
        }
        pendingWarning = result.warning();
        // Deliberately no auto-purge: the trash is a manual, restorable list, and a player who
        // trashes a container must keep it until they choose to delete it permanently.
        // (Trash.trashGoneBlock is also gated off by the autoTrashGoneBlocks setting, default false.)
        TrackerLog.LOGGER.info("Using bank {} for address {} ({}) at {} with {} entities",
            bank.key, address, result.reason(), storage.bankFile(bank.key), bank.size());
    }

    /**
     * One-shot migration of the pre-bank {@code tracked_containers.json} into the open bank: kick
     * off the read on the IO executor the first time a bank is open, once per bank.
     */
    private void maybeStartLegacyMigration() {
        if (bank == null) return;
        if (legacyMigration != null && !legacyMigration.isDone()) return;
        if (legacyMigrationBankKey != null && legacyMigrationBankKey.equals(bank.key)) return;
        Path legacy = storage.root().resolve("tracked_containers.json");
        legacyMigrationBankKey = bank.key;
        if (!Files.exists(legacy)) return;
        legacyMigration = io.submit(() -> LegacyImport.read(legacy));
    }

    /**
     * Applies a finished legacy import to the open bank, then retires the old file by renaming it
     * to {@code tracked_containers.json.migrated}. Runs on the game thread; the read itself is on the
     * background executor, so a big legacy file never freezes the client.
     */
    private void applyLegacyMigration() {
        if (bank == null || legacyMigration == null) return;
        if (!legacyMigration.isDone()) return;
        String jobKey = legacyMigrationBankKey;
        Future<List<BlockRecord>> future = legacyMigration;
        legacyMigration = null;
        if (jobKey == null || !jobKey.equals(bank.key)) return;
        List<BlockRecord> records;
        try {
            records = future.get();
        } catch (Exception e) {
            TrackerLog.LOGGER.error("The legacy chest tracker import failed", e);
            legacyMigrationBankKey = bank.key;
            return;
        }
        UUID owner = bank.currentPlayer;
        int applied = 0;
        for (BlockRecord record : records) {
            if (record.global) {
                // An ender chest is the same container for every account; bind it to the player who
                // just opened it. No owner means the player never joined, so there is nothing to key.
                if (owner != null) {
                    record.owner = owner;
                    bank.putBlock(record);
                    applied++;
                }
                continue;
            }
            bank.putBlock(record);
            applied++;
        }
        if (applied > 0) bank.markDirty();
        Path legacy = storage.root().resolve("tracked_containers.json");
        try {
            Files.move(legacy, legacy.resolveSibling("tracked_containers.json.migrated"));
        } catch (IOException e) {
            TrackerLog.LOGGER.error("Could not retire the legacy chest tracker file", e);
        }
        TrackerLog.LOGGER.info("Migrated {} legacy chest tracker records into bank {}", applied, bank.key);
        legacyMigrationBankKey = bank.key;
    }

    private static String serverName(Minecraft client) {
        if (client.hasSingleplayerServer() && client.getSingleplayerServer() != null) {
            return "singleplayer_" + client.getSingleplayerServer().getWorldData().getLevelName();
        }
        ServerData info = client.getCurrentServer();
        if (info != null) return info.ip;
        return "unknown_server";
    }

    /**
     * Snapshot and write on the I/O thread; only cheap record-list copies are taken on game thread.
     */
    private void saveInBackground() {
        saveInBackground(false);
    }

    private void saveInBackground(boolean force) {
        MemoryBank b = bank;
        if (b == null) return;
        if (!force && lastWrite != null && !lastWrite.isDone()) return;
        List<EntityRecord> entities = new ArrayList<>(b.all());
        List<BlockRecord> blocks = new ArrayList<>(b.allBlocks());
        var accounts = new LinkedHashMap<>(b.accounts);
        var playDays = new TreeSet<>(b.playDays);
        List<String> lastAreaAction = new ArrayList<>(b.lastAreaAction());
        int journalRevision = b.revision();
        // The I/O thread must never read a live record: the game thread mutates them while it
        // tracks, so a read there is a torn snapshot at best. The payload is built here, where the
        // records are consistent, and the I/O thread only writes it.
        BankStorage.JournalBatch batch = storage.prepareJournal(b.key, b, entities, blocks, accounts,
            playDays, lastAreaAction, !force);
        b.clearDirty();
        lastWrite = io.submit(() -> {
            boolean ok = storage.writeJournal(b.key, batch);
            String note = storage.saveNote;
            if (note != null) {
                storage.saveNote = null;
                Minecraft.getInstance().execute(() -> pendingWarning = note);
            }
            if (ok) {
                // The baseline has to be marked on the game thread: it walks the bank's maps, and
                // the revision check has to be atomic with the state the journal covers. A change
                // that landed after the payload was built leaves the revision higher, so the
                // baseline stays where it was and the next save journals that change again.
                Minecraft.getInstance().execute(() -> {
                    if (b.revision() == journalRevision) b.markJournalWritten();
                    // Refresh the script-facing tally beside the bank on every successful save.
                    // Tally.of is revision-cached but thread-confined to the game thread, so the
                    // payload is built here; only the file write goes to the I/O executor.
                    var tallyJson = Tally.of(b).toJson(b.server);
                    io.submit(() -> storage.writeTally(b.key, tallyJson));
                });
            } else {
                // A failed write marks the bank dirty again so the next save retries.
                Minecraft.getInstance().execute(b::markDirty);
            }
        });
    }

    /**
     * On disconnect: save in the background (the next load waits for it in the same queue).
     */
    private volatile boolean saveRequested = false;
    private volatile boolean waitCompleted = false;

    public void close() {
        if (saveRequested) return;
        saveRequested = true;
        if (bank != null) {
            if (bank.isDirty()) saveInBackground(true);
            TrackerLog.LOGGER.info("Closed bank {}", bank.key);
        }
        if (loading != null) loading.cancel(false);
        loading = null;
        if (merging != null) merging.cancel(false);
        merging = null;
        mergeSourceKey = null;
        mergeTargetKey = null;
        bank = null;
        connection = null;
        pendingWarning = null;
    }

    /**
     * On game exit: request the final save and wait only briefly. A huge bank can take longer
     * than this to serialize; timing out keeps the client responsive. The already-written atomic
     * file stays intact, and the background writer can finish if the process remains alive.
     */
    public void closeAndWait() {
        close();
        if (waitCompleted) {
            // Already waited (e.g. onDeactivate); don't block again.
            return;
        }
        waitCompleted = true;
        Future<?> last = io.submit(() -> {
        });
        try {
            last.get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            TrackerLog.LOGGER.warn("Waiting for the bank to be written timed out; the last complete bank remains on disk", e);
        }
    }

    public void saveNow() {
        if (bank != null) saveInBackground(true);
    }

    /**
     * Starts merging the bank stored for {@code address} (another IP of the same server) into
     * the open bank. The source is loaded on the I/O thread and merged on the game thread by
     * {@link #applyMerge()}; the address is aliased to the open bank so future joins via it
     * land here. Returns an error message, or null when the merge started.
     */
    public @Nullable String requestMerge(String address) {
        if (bank == null) return "No bank open.";
        if (merging != null) return "A merge is already running.";
        String sourceServer = storage.resolveServer(address);
        String sourceKey = BankStorage.keyFor(sourceServer);
        if (sourceKey.equals(bank.key)) return "That address already opens this bank.";
        if (!storage.bankOrJournalExists(sourceKey)) {
            return "No saved bank found for " + address + ".";
        }
        mergeSourceKey = sourceKey;
        mergeTargetKey = bank.key;
        String canonical = bank.server;
        merging = io.submit(() -> {
            storage.addAlias(canonical, address);
            return storage.load(sourceServer);
        });
        return null;
    }

    /**
     * Applies a finished split-bank merge to the open bank, archives the source bank and its
     * journal, and queues a save. Runs on the game thread; the load itself was on the I/O
     * thread. Per record the newer copy wins wholesale (see {@link MemoryBank#mergeFrom}).
     */
    private void applyMerge() {
        if (merging == null || !merging.isDone()) return;
        Future<BankStorage.LoadResult> future = merging;
        String sourceKey = mergeSourceKey;
        String targetKey = mergeTargetKey;
        merging = null;
        mergeSourceKey = null;
        mergeTargetKey = null;
        if (bank == null || sourceKey == null || targetKey == null || !targetKey.equals(bank.key)) return;
        BankStorage.LoadResult result;
        try {
            result = future.get();
        } catch (Exception e) {
            TrackerLog.LOGGER.error("Merging bank {} failed", sourceKey, e);
            pendingWarning = "Chest Tracker: merging bank " + sourceKey + " failed; nothing was changed.";
            return;
        }
        MemoryBank.MergeResult merged = bank.mergeFrom(result.bank());
        pendingWarning = "Chest Tracker: merged bank " + sourceKey + ": "
            + merged.added() + " new, " + merged.replaced() + " updated, " + merged.kept() + " kept. "
            + "The old bank was archived under backups/" + sourceKey + "/.";
        TrackerLog.LOGGER.info("Merged bank {} into {}: {} new, {} updated, {} kept",
            sourceKey, bank.key, merged.added(), merged.replaced(), merged.kept());
        io.submit(() -> storage.archiveBank(sourceKey));
        saveInBackground(true);
    }
}
