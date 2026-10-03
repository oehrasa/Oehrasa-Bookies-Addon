package com.AutoBookshelf.addon.modules.chesttracker;

import com.google.gson.*;
import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

public class ChestTrackerDataV2 {
    private static final Logger LOGGER = LoggerFactory.getLogger("ChestTracker");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int CURRENT_VERSION = 2;
    private final Map<String, Map<BlockPos, TrackedContainer>> containers;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Minecraft mc;
    private File dataFile;
    private File backupFile;
    private File tempFile;
    private long lastSaveTime = 0;
    private volatile int saveFailures = 0;

    // Guards the actual on-disk write (temp -> backup -> move). Separate from
    // `lock` above on purpose: `lock` protects the in-memory container map,
    // this protects the filesystem. saveData() can be triggered from three
    // places: the debounced background executor, the "Save Data" button, and
    // world-leave. and without this, two of them landing at once could
    // interleave writes to the same temp file.
    // A ReentrantLock (not a monitor) so the client-thread inline path can
    // time out on it instead of blocking indefinitely behind a stuck writer.
    private final ReentrantLock fileWriteLock = new ReentrantLock();

    private final AtomicInteger pendingContainerSaves = new AtomicInteger(0);

    // Bumped on every mutation so consumers (e.g. the browser screen) can
    // cheaply detect "did anything change" without diffing the map.
    private final AtomicLong dataVersion = new AtomicLong(0);

    private static final long SAVE_DEBOUNCE_MS = 2000;

    /**
     * How long a synchronous save waits for the background save thread before
     * writing inline. Kept equal to SAVE_INLINE_LOCK_TIMEOUT_SECONDS: every caller
     * of saveDataSync() is on the client thread (deactivate, world-leave, the manual
     * save button), so waiting far longer than the inline path's own lock budget
     * would freeze the game for many seconds before even reaching the fallback
     * that exists precisely to avoid freezing it.
     */
    private static final long SAVE_SYNC_TIMEOUT_SECONDS = 2;

    /**
     * How long the last-resort inline save waits for the data lock. Deliberately
     * short: this runs on the client thread (typically while disconnecting), so a
     * writer that is genuinely stuck must not be able to freeze the game.
     */
    private static final long SAVE_INLINE_LOCK_TIMEOUT_SECONDS = 2;
    private final ScheduledExecutorService saveExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ChestTracker-Save");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean dirty = false;
    private ScheduledFuture<?> pendingSave = null;

    public ChestTrackerDataV2() {
        this.containers = new ConcurrentHashMap<>();
        this.mc = MeteorClient.mc;
        initializeFiles();
    }

    private void initializeFiles() {
        try {
            File folder = new File(MeteorClient.FOLDER, "ChestTracker");
            if (!folder.exists() && !folder.mkdirs()) {
                LOGGER.error("Failed to create ChestTracker folder");
            }
            dataFile = new File(folder, "tracked_containers.json");
            backupFile = new File(folder, "tracked_containers.backup.json");
            tempFile = new File(folder, "tracked_containers.tmp");
        } catch (Exception e) {
            LOGGER.error("Failed to initialize files", e);
        }
    }

    private synchronized void markDirty() {
        dirty = true;
        if (pendingSave == null || pendingSave.isDone()) {
            pendingSave = saveExecutor.schedule(this::flushIfDirty, SAVE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void flushIfDirty() {
        if (!dirty) return;
        dirty = false;
        // Already running on saveExecutor, call directly instead of submitting
        // another task, so this can't queue behind a save submitted after it.
        snapshotAndWrite();
    }

    public void trackContainer(BlockPos pos, String dimension, String containerType, List<ItemStack> contents) {
        lock.writeLock().lock();
        try {
            Map<BlockPos, TrackedContainer> dimContainers = containers.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>());
            TrackedContainer container = dimContainers.get(pos);
            if (container == null) {
                container = new TrackedContainer(pos, dimension, containerType);
                dimContainers.put(pos, container);
            }
            container.updateContents(contents);
        } finally {
            lock.writeLock().unlock();
        }
        markDirty();
        pendingContainerSaves.incrementAndGet();
        dataVersion.incrementAndGet();
    }

    public TrackedContainer getContainer(BlockPos pos, String dimension) {
        lock.readLock().lock();
        try {
            Map<BlockPos, TrackedContainer> dimContainers = containers.get(dimension);
            return dimContainers != null ? dimContainers.get(pos) : null;
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<TrackedContainer> searchItem(Item item) {
        String currentDim = getCurrentDimension();
        return searchItem(item, currentDim);
    }

    public List<TrackedContainer> searchItem(Item item, String dimension) {
        lock.readLock().lock();
        try {
            Map<BlockPos, TrackedContainer> dimContainers = containers.get(dimension);
            if (dimContainers == null) return new ArrayList<>();
            return dimContainers.values().stream()
                .filter(c -> c.containsItem(item))
                .sorted((a, b) -> {
                    String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
                    return Integer.compare(b.getItemCount(itemId), a.getItemCount(itemId));
                })
                .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<TrackedContainer> getAllContainers(String dimension) {
        lock.readLock().lock();
        try {
            Map<BlockPos, TrackedContainer> dimContainers = containers.get(dimension);
            return dimContainers != null ? new ArrayList<>(dimContainers.values()) : new ArrayList<>();
        } finally {
            lock.readLock().unlock();
        }
    }

    public int getTotalContainerCount() {
        lock.readLock().lock();
        try {
            return containers.values().stream()
                .mapToInt(Map::size)
                .sum();
        } finally {
            lock.readLock().unlock();
        }
    }
    // Some leftover im not sure
    public int getCurrentDimensionContainerCount() {
        String dimension = getCurrentDimension();
        lock.readLock().lock();
        try {
            Map<BlockPos, TrackedContainer> dimContainers = containers.get(dimension);
            return dimContainers != null ? dimContainers.size() : 0;
        } finally {
            lock.readLock().unlock();
        }
    }

    public void clearAll() {
        lock.writeLock().lock();
        try {
            containers.clear();
        } finally {
            lock.writeLock().unlock();
        }
        markDirty();
        dataVersion.incrementAndGet();
    }

    public int getPendingSaveCount() {
        return pendingContainerSaves.get();
    }

    public long getDataVersion() {
        return dataVersion.get();
    }

    public int getSaveFailures() {
        return saveFailures;
    }

    /**
     * Builds the snapshot and writes it, both on the executor thread, so the
     * build order and the write order can never diverge between overlapping
     * saveData()/saveDataSync()/flushIfDirty() calls.
     */
    private void snapshotAndWrite() {
        JsonObject root;
        try {
            root = buildSnapshotJson();
        } catch (Exception e) {
            saveFailures++;
            LOGGER.error("Failed to build save snapshot (attempt {})", saveFailures, e);
            return;
        }
        writeJsonToFile(root);
    }

    public void saveData() {
        saveExecutor.submit(this::snapshotAndWrite);
    }

    /**
     * Same as saveData() but blocks until write is flushed, for callers that
     * must know the data is on disk (module deactivate, world-leave, manual save).
     */
    public void saveDataSync() {
        // A pending debounced save is redundant once we write the full snapshot below.
        ScheduledFuture<?> pending = pendingSave;
        if (pending != null) pending.cancel(false);

        try {
            saveExecutor.submit(this::snapshotAndWrite).get(SAVE_SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            saveFailures++;
            LOGGER.error("Save interrupted while waiting for write", e);
        } catch (ExecutionException e) {
            saveFailures++;
            LOGGER.error("Failed to save data (attempt {})", saveFailures, e);
        } catch (TimeoutException e) {
            // The save thread is blocked or backed up (typically a writer holding
            // the data lock while the world tears down). Don't drop the save on
            // the floor: write it here, with a bounded lock wait so a stuck
            // writer can't freeze the client thread either.
            saveFailures++;
            LOGGER.warn("Save thread busy after {}s, writing inline (attempt {})", SAVE_SYNC_TIMEOUT_SECONDS, saveFailures);
            writeSnapshotInline();
        }
    }

    /**
     * Last-resort save: builds and writes a snapshot on the calling thread,
     * giving up if the data lock cannot be acquired promptly.
     */
    private void writeSnapshotInline() {
        boolean locked = false;
        boolean writeLocked = false;
        try {
            locked = lock.readLock().tryLock(SAVE_INLINE_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!locked) {
                LOGGER.error("Gave up saving chest tracker data: data lock still held after {}s", SAVE_INLINE_LOCK_TIMEOUT_SECONDS);
                return;
            }
            // The data lock bounds the snapshot build, but a background writer
            // can still hold the file lock. Time out on that too, otherwise
            // this client-thread path blocks behind it and freezes the game.
            writeLocked = fileWriteLock.tryLock(SAVE_INLINE_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!writeLocked) {
                LOGGER.error("Gave up saving chest tracker data: file lock still held after {}s", SAVE_INLINE_LOCK_TIMEOUT_SECONDS);
                return;
            }
            writeJsonToFile(buildSnapshotJsonLocked());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.error("Interrupted while saving chest tracker data inline", e);
        } catch (Exception e) {
            saveFailures++;
            LOGGER.error("Failed to save data inline (attempt {})", saveFailures, e);
        } finally {
            if (writeLocked) fileWriteLock.unlock();
            if (locked) lock.readLock().unlock();
        }
    }

    private JsonObject buildSnapshotJson() {
        lock.readLock().lock();
        try {
            return buildSnapshotJsonLocked();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Caller must already hold the data lock (read is enough).
     */
    private JsonObject buildSnapshotJsonLocked() {
        {
            JsonObject root = new JsonObject();
            root.addProperty("version", CURRENT_VERSION);
            root.addProperty("saveTime", System.currentTimeMillis());
            JsonObject dimensions = new JsonObject();
            for (Map.Entry<String, Map<BlockPos, TrackedContainer>> dimEntry : containers.entrySet()) {
                JsonArray dimArray = new JsonArray();
                for (TrackedContainer container : dimEntry.getValue().values()) {
                    dimArray.add(container.toJson());
                }
                dimensions.add(dimEntry.getKey(), dimArray);
            }
            root.add("dimensions", dimensions);
            return root;
        }
    }

    /**
     * Writes a previously-built snapshot to disk. Runs on the single background
     * save-executor thread, or on the client thread from the inline last-resort
     * path (which already holds the file lock via tryLock). Held on
     * fileWriteLock (not the data lock) so the debounced flush, manual
     * "Save Data" button, and world-leave saves don't stomp on the same temp
     * file if they overlap.
     */
    private void writeJsonToFile(JsonObject root) {
        fileWriteLock.lock();
        try {
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(tempFile), StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            if (dataFile.exists() && dataFile.length() > 0) {
                Files.copy(dataFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tempFile.toPath(), dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            lastSaveTime = System.currentTimeMillis();
            saveFailures = 0;
            pendingContainerSaves.set(0);
        } catch (Exception e) {
            saveFailures++;
            LOGGER.error("Failed to save data (attempt {})", saveFailures, e);
            if (saveFailures > 3) {
                LOGGER.error("Multiple save failures, data may be lost!");
            }
        } finally {
            fileWriteLock.unlock();
        }
    }

    public void loadData() {
        lock.writeLock().lock();
        try {
            containers.clear();
            if (loadFromFile(dataFile)) {
                LOGGER.info("Loaded data from main file");
                dataVersion.incrementAndGet();
                return;
            }
            if (loadFromFile(backupFile)) {
                LOGGER.warn("Main file corrupted, loaded from backup");
                dataVersion.incrementAndGet();
                saveData();
                return;
            }
            File oldFile = new File(MeteorClient.FOLDER, "ChestTracker/tracked_containers.json");
            if (oldFile.exists() && loadFromFile(oldFile)) {
                LOGGER.info("Migrated data from old format");
                dataVersion.incrementAndGet();
                saveData();
                return;
            }
            LOGGER.info("No existing data found, starting fresh");
        } finally {
            lock.writeLock().unlock();
        }
    }

    private boolean loadFromFile(File file) {
        if (!file.exists() || file.length() == 0) return false;
        try {
            String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            int version = root.has("version") ? root.get("version").getAsInt() : 1;
            if (root.has("dimensions")) {
                JsonObject dimensions = root.getAsJsonObject("dimensions");
                for (Map.Entry<String, JsonElement> dimEntry : dimensions.entrySet()) {
                    String dimension = dimEntry.getKey();
                    JsonArray dimArray = dimEntry.getValue().getAsJsonArray();
                    Map<BlockPos, TrackedContainer> dimContainers = new ConcurrentHashMap<>();
                    for (JsonElement element : dimArray) {
                        try {
                            TrackedContainer container = TrackedContainer.fromJson(element.getAsJsonObject());
                            dimContainers.put(container.getPosition(), container);
                        } catch (Exception e) {
                            LOGGER.warn("Skipped corrupted container entry", e);
                        }
                    }
                    if (!dimContainers.isEmpty()) {
                        containers.put(dimension, dimContainers);
                    }
                }
            }
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to load from file: {}", file.getName(), e);
            return false;
        }
    }

    /** Used by ChestTrackerModule for instant block‑break removal. */
    public void removeContainer(BlockPos pos, String dimension) {
        lock.writeLock().lock();
        try {
            Map<BlockPos, TrackedContainer> dimContainers = containers.get(dimension);
            if (dimContainers != null) dimContainers.remove(pos);
        } finally {
            lock.writeLock().unlock();
        }
        markDirty();
        dataVersion.incrementAndGet();
    }

    Map<String, Map<BlockPos, TrackedContainer>> getContainers() {
        return containers;
    }

    private String getCurrentDimension() {
        if (mc.level == null) return "unknown";
        ResourceKey<Level> key = mc.level.dimension();
        return key.identifier().toString();
    }
}
