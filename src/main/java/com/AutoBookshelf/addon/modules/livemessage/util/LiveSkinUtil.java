package com.AutoBookshelf.addon.modules.livemessage.util;

import com.google.common.collect.ArrayListMultimap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.world.entity.player.PlayerSkin;

import java.io.File;
import java.io.FileReader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.AutoBookshelf.addon.modules.livemessage.LiveMessage.logError;

public class LiveSkinUtil {
    private static final int MAX_CACHE_SIZE = 512;
    private static final Map<UUID, LiveSkinUtil> SKIN_CACHE =
        Collections.synchronizedMap(new LinkedHashMap<>(128, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, LiveSkinUtil> eldest) {
                return size() > MAX_CACHE_SIZE;
            }
        });

    // Authlib "textures" properties persisted from the tab list (LastSeenTracker pattern).
    // They let a head render again even after the player leaves the world or the client restarts.
    private static final Map<UUID, PersistedSkin> persistedSkins = new ConcurrentHashMap<>();
    private static final long SAVE_INTERVAL_MS = 30_000;
    private static final int MAX_PERSISTED = 1024;
    private static long lastSaveTime = 0L;
    private static boolean dirty = false;
    private static volatile boolean loaded = false;
    // Guards dirty/lastSaveTime and the actual write, since the tick thread
    // (via storeProperty -> maybeSave) and the shutdown-hook thread (forceSave)
    // can call into save() concurrently.
    private static final Object saveLock = new Object();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, PersistedSkin>>() {
    }.getType();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(LiveSkinUtil::forceSave));
    }

    private final UUID uuid;
    private volatile PlayerSkin cachedTextures;
    private volatile boolean restoreAttempted = false;

    private LiveSkinUtil(UUID uuid) {
        this.uuid = uuid;
    }

    public static LiveSkinUtil get(UUID uuid) {
        synchronized (SKIN_CACHE) {
            return SKIN_CACHE.computeIfAbsent(uuid, LiveSkinUtil::new);
        }
    }

    public static void clearCache() {
        synchronized (SKIN_CACHE) {
            SKIN_CACHE.clear();
        }
    }

    /**
     * Live capture from the tab list: freshens the in-memory textures and mirrors the
     * profile's "textures" property to disk so the skin survives leaving the world.
     */
    public void captureFromTabList(PlayerInfo entry) {
        if (entry != null) {
            captureTextures(entry.getSkin());
            storeProperty(uuid, entry.getProfile());
        }
    }

    public void captureTextures(PlayerSkin textures) {
        this.cachedTextures = textures;
        this.restoreAttempted = false;
    }

    /**
     * Whether textures are available. The first query after textures went missing also
     * re-submits the persisted property to the skin provider, so the head pops back in
     * as soon as the download completes.
     */
    public boolean hasCachedSkin() {
        if (this.cachedTextures == null) {
            requestRestore();
        }
        return this.cachedTextures != null;
    }

    public PlayerSkin getCachedTextures() {
        return this.cachedTextures;
    }

    private void requestRestore() {
        if (this.restoreAttempted) return;
        this.restoreAttempted = true;

        ensureLoaded();
        PersistedSkin persisted = persistedSkins.get(this.uuid);
        if (persisted == null || persisted.value == null || persisted.value.isEmpty()) return;

        // PropertyMap wraps whatever it is given in an ImmutableMultimap, so the
        // textures property must be populated before the profile is built.
        ArrayListMultimap<String, Property> source = ArrayListMultimap.create();
        source.put("textures", new Property("textures", persisted.value, persisted.signature));
        GameProfile profile = new GameProfile(this.uuid, persisted.name, new PropertyMap(source));

        SkinManager provider = Minecraft.getInstance().getSkinManager();
        if (provider == null) return;

        provider.get(profile).whenCompleteAsync((optional, error) -> {
            if (error != null) {
                logError("Failed to restore skin for {}", this.uuid, error);
                return;
            }
            optional.ifPresent(this::captureTextures);
        });
    }

    // Called on every tick for each player in the tab list; only writes when the skin changed.
    private static void storeProperty(UUID uuid, GameProfile profile) {
        ensureLoaded();

        Collection<Property> textures = profile.properties().get("textures");
        Property property = textures == null || textures.isEmpty() ? null : textures.iterator().next();
        if (property == null) return;

        PersistedSkin persisted = new PersistedSkin();
        persisted.value = property.value();
        persisted.signature = property.signature();
        persisted.name = profile.name() != null ? profile.name() : uuid.toString();

        PersistedSkin previous = persistedSkins.get(uuid);
        if (previous != null
            && previous.value.equals(persisted.value)
            && Objects.equals(previous.signature, persisted.signature)) {
            // Same skin: keep it alive so live entries are never evicted by trim().
            previous.persistedAtMs = System.currentTimeMillis();
            return;
        }

        persisted.persistedAtMs = System.currentTimeMillis();
        persistedSkins.put(uuid, persisted);
        trim();
        // Set under saveLock so it can't interleave with save() clearing the flag.
        synchronized (saveLock) {
            dirty = true;
        }
        maybeSave();
    }

    private static File getFile() {
        return LivemessageUtil.LIVEMESSAGE_FOLDER.resolve("skins.json").toFile();
    }

    private static void ensureLoaded() {
        if (loaded) return;
        synchronized (saveLock) {
            if (loaded) return;
            try {
                File file = getFile();
                if (!file.exists()) return;

                try (FileReader reader = new FileReader(file)) {
                    Map<String, PersistedSkin> raw = GSON.fromJson(reader, MAP_TYPE);
                    if (raw != null) {
                        for (Map.Entry<String, PersistedSkin> entry : raw.entrySet()) {
                            try {
                                if (entry.getValue() == null || entry.getValue().value == null) continue;
                                UUID uuid = UUID.fromString(entry.getKey());
                                if (entry.getValue().name == null) {
                                    entry.getValue().name = uuid.toString();
                                }
                                persistedSkins.put(uuid, entry.getValue());
                            } catch (IllegalArgumentException ignored) {
                            }
                        }
                    }
                    trim();
                } catch (Exception e) {
                    logError("Failed to load persisted skins", e);
                }
            } finally {
                // Only flip after the load has finished (or failed), so a concurrent
                // caller never sees loaded == true with a half-populated map.
                loaded = true;
            }
        }
    }

    private static void trim() {
        if (persistedSkins.size() <= MAX_PERSISTED) return;
        List<Map.Entry<UUID, PersistedSkin>> oldest = new ArrayList<>(persistedSkins.entrySet());
        oldest.sort(Map.Entry.comparingByValue(Comparator.comparingLong(p -> p.persistedAtMs)));
        for (int i = 0; i < oldest.size() - MAX_PERSISTED; i++) {
            persistedSkins.remove(oldest.get(i).getKey());
        }
    }

    private static void maybeSave() {
        synchronized (saveLock) {
            if (!dirty) return;
            if (System.currentTimeMillis() - lastSaveTime < SAVE_INTERVAL_MS) return;
            save();
        }
    }

    private static void save() {
        // Must always be called with saveLock held.
        Map<String, PersistedSkin> raw = new HashMap<>();
        for (Map.Entry<UUID, PersistedSkin> entry : persistedSkins.entrySet()) {
            raw.put(entry.getKey().toString(), entry.getValue());
        }

        File target = getFile();
        Path targetPath = target.toPath();
        Path tmp = targetPath.resolveSibling(target.getName() + ".tmp");

        try {
            Files.createDirectories(targetPath.getParent());
            try (Writer writer = Files.newBufferedWriter(tmp)) {
                GSON.toJson(raw, MAP_TYPE, writer);
            }
            try {
                Files.move(tmp, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
            lastSaveTime = System.currentTimeMillis();
        } catch (Exception e) {
            logError("Failed to save persisted skins", e);
        }
    }

    public static void forceSave() {
        synchronized (saveLock) {
            if (dirty) {
                save();
            }
        }
    }

    private static class PersistedSkin {
        String value;
        String signature;
        String name;
        long persistedAtMs;
    }
}
