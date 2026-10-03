package com.AutoBookshelf.addon.modules.livemessage.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.AutoBookshelf.addon.modules.livemessage.LiveMessage.logError;

public class LastSeenTracker {
    private static final int MAX_ENTRIES = 2000;
    private static final long STALE_ENTRY_MS = 90L * 24L * 60L * 60L * 1000L; // 90 days

    private static final Map<UUID, Long> lastSeen = new ConcurrentHashMap<>();
    private static Set<UUID> previouslyOnline = new HashSet<>();

    private static final long SAVE_INTERVAL_MS = 30_000;
    private static long lastSaveTime = 0L;
    private static boolean dirty = false;
    private static boolean loaded = false;
    private static boolean listening = false;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Long>>() {
    }.getType();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(LastSeenTracker::forceSave));
    }

    // Call once per frame with the current set of online player UUIDs
    public static void update(Set<UUID> currentlyOnline) {
        ensureLoaded();

        for (UUID uuid : previouslyOnline) {
            if (!currentlyOnline.contains(uuid)) {
                lastSeen.put(uuid, System.currentTimeMillis());
                dirty = true;
            }
        }
        previouslyOnline = currentlyOnline;

        prune();
        maybeSave();
    }

    /**
     * Call when leaving the world/server: whoever was online just went offline, and we flush immediately.
     */
    public static void onDisconnect() {
        ensureLoaded();
        long now = System.currentTimeMillis();
        for (UUID uuid : previouslyOnline) {
            lastSeen.put(uuid, now);
            dirty = true;
        }
        prune();
        previouslyOnline = new HashSet<>();
        forceSave();
    }

    public static String formatLastSeen(UUID uuid) {
        ensureLoaded();

        Long ts = lastSeen.get(uuid);
        if (ts == null) return "Unknown";

        long seconds = (System.currentTimeMillis() - ts) / 1000;
        if (seconds < 60) return "Just now";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h ago";
        return (hours / 24) + "d ago";
    }

    private static File getFile() {
        return LivemessageUtil.LIVEMESSAGE_FOLDER.resolve("lastseen.json").toFile();
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;

        File file = getFile();
        if (!file.exists()) return;

        try (FileReader reader = new FileReader(file)) {
            Map<String, Long> raw = GSON.fromJson(reader, MAP_TYPE);
            if (raw != null) {
                for (Map.Entry<String, Long> entry : raw.entrySet()) {
                    try {
                        lastSeen.put(UUID.fromString(entry.getKey()), entry.getValue());
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        } catch (Exception e) {
            logError("Failed to load last-seen data", e);
        }
    }

    private static void maybeSave() {
        if (!dirty) return;
        long now = System.currentTimeMillis();
        if (now - lastSaveTime < SAVE_INTERVAL_MS) return;
        save();
    }

    /**
     * Bounds the in-memory last-seen map: drops entries that haven't been seen in STALE_ENTRY_MS
     * and trims the oldest entries once the hard cap is exceeded. Prevents a server churning
     * through UUIDs from growing the map (and the saved file) without bound.
     */
    private static void prune() {
        if (lastSeen.size() <= MAX_ENTRIES) {
            return;
        }
        long cutoff = System.currentTimeMillis() - STALE_ENTRY_MS;
        lastSeen.entrySet().removeIf(e -> e.getValue() < cutoff);
        if (lastSeen.size() <= MAX_ENTRIES) {
            return;
        }
        List<Map.Entry<UUID, Long>> oldest = new ArrayList<>(lastSeen.entrySet());
        oldest.sort(Map.Entry.comparingByValue());
        for (int i = 0; i < oldest.size() - MAX_ENTRIES; i++) {
            lastSeen.remove(oldest.get(i).getKey());
        }
    }

    private static void save() {
        Map<String, Long> raw = new HashMap<>();
        for (Map.Entry<UUID, Long> entry : lastSeen.entrySet()) {
            raw.put(entry.getKey().toString(), entry.getValue());
        }

        try (FileWriter writer = new FileWriter(getFile())) {
            GSON.toJson(raw, MAP_TYPE, writer);
            dirty = false;
            lastSaveTime = System.currentTimeMillis();
        } catch (Exception e) {
            logError("Failed to save last-seen data", e);
        }
    }

    public static void forceSave() {
        if (dirty) {
            save();
        }
    }
}
