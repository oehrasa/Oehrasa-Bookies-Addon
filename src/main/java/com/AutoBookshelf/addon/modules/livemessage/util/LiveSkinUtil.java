package com.AutoBookshelf.addon.modules.livemessage.util;

import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.SkinTextures;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class LiveSkinUtil {
    private static final Map<UUID, LiveSkinUtil> SKIN_CACHE = new ConcurrentHashMap<>();
    private final UUID uuid;
    private volatile SkinTextures cachedTextures;

    private LiveSkinUtil(UUID uuid) {
        this.uuid = uuid;
    }

    public static LiveSkinUtil get(UUID uuid) {
        return SKIN_CACHE.computeIfAbsent(uuid, LiveSkinUtil::new);
    }

    public static void clearCache() {
        SKIN_CACHE.clear();
    }

    public void captureFromTabList(PlayerListEntry entry) {
        if (entry != null) {
            this.cachedTextures = entry.getSkinTextures();
        }
    }

    public boolean hasCachedSkin() {
        return this.cachedTextures != null;
    }

    /**
     * The last-seen SkinTextures for this player, or null if they've never
     * been captured this session (never seen online, this cache is in-memory only,
     * same as LastSeenTracker before it got persistence).
     */
    public SkinTextures getCachedTextures() {
        return this.cachedTextures;
    }
}
