package com.AutoBookshelf.addon.modules.livemessage.util;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class LiveSkinUtil {
    private static final Map<UUID, LiveSkinUtil> SKIN_CACHE = new ConcurrentHashMap<>();
    private final UUID uuid;
    private volatile PlayerSkin cachedTextures;

    private LiveSkinUtil(UUID uuid) {
        this.uuid = uuid;
    }

    public static LiveSkinUtil get(UUID uuid) {
        return SKIN_CACHE.computeIfAbsent(uuid, LiveSkinUtil::new);
    }

    public static void clearCache() {
        SKIN_CACHE.clear();
    }

    public void captureFromTabList(PlayerInfo entry) {
        if (entry != null) {
            this.cachedTextures = entry.getSkin();
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
    public PlayerSkin getCachedTextures() {
        return this.cachedTextures;
    }
}
