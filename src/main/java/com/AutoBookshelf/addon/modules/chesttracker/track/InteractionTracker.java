// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker.track;

import com.AutoBookshelf.addon.modules.chesttracker.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Recent right-clicks on entities and blocks (CLAUDE.md 4.1 step 1, 4.1d A), fed by the
 * interaction mixins. Kept as a queue, not just the last click: under lag, cart 1's screen can
 * arrive after the player has already clicked cart 2, and the server answers clicks in order.
 */
public final class InteractionTracker {
    private static final int MAX_PENDING = 8;

    /**
     * Either an entity click (uuid != null) or a block click (pos != null).
     */
    public record Pending(@Nullable UUID uuid, int networkId, @Nullable BlockPos pos, long tick) {
        public boolean isEntity() {
            return uuid != null;
        }
    }

    private final Deque<Pending> queue = new ArrayDeque<>();
    /**
     * Ticks from click to screen for the last few bound screens: how laggy the server is right now.
     */
    private final Deque<Long> recentDelays = new ArrayDeque<>();
    private long tick;

    public void set(Entity entity) {
        add(new Pending(entity.getUUID(), entity.getId(), null, tick));
    }

    public void setBlock(BlockPos pos) {
        add(new Pending(null, -1, pos.immutable(), tick));
    }

    private void add(Pending p) {
        queue.addLast(p);
        while (queue.size() > MAX_PENDING) queue.removeFirst();
    }

    public void tick() {
        tick++;
    }

    public long currentTick() {
        return tick;
    }

    /**
     * Clicks still fresh enough to bind a screen to, oldest first.
     */
    public List<Pending> fresh() {
        long window = windowTicks();
        queue.removeIf(p -> tick - p.tick() > window);
        return new ArrayList<>(queue);
    }

    /**
     * How long a click waits for its screen. At least {@link Settings#bindTimeoutTicks}; on a laggy
     * server (9b9t answers in 2-9 s at times) twice the slowest recent click-to-screen time or twice
     * the ping, plus a second, up to {@link Settings#bindTimeoutMaxTicks}. A window that's too short
     * records nothing; one far too long lets a click that never got a screen take a later one.
     */
    public long windowTicks() {
        long slowest = recentDelays.stream().mapToLong(Long::longValue).max().orElse(0);
        long pingTicks = 0;
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && client.getConnection() != null) {
            PlayerInfo info = client.getConnection().getPlayerInfo(client.player.getUUID());
            if (info != null) pingTicks = info.getLatency() / 50L;
        }
        long wanted = 2 * Math.max(slowest, pingTicks) + 20;
        return Math.min(Settings.bindTimeoutMaxTicks, Math.max(Settings.bindTimeoutTicks, wanted));
    }

    /**
     * A screen was bound to this click: it and every older click are done.
     */
    public void consume(Pending used) {
        recentDelays.addLast(tick - used.tick());
        while (recentDelays.size() > 8) recentDelays.removeFirst();
        while (!queue.isEmpty()) {
            if (queue.removeFirst() == used) return;
        }
    }

    /**
     * A mount screen opened for this entity: drop its click and older ones, if it was clicked.
     */
    public void consumeEntity(UUID uuid) {
        for (Pending p : queue) {
            if (uuid.equals(p.uuid())) {
                consume(p);
                return;
            }
        }
    }

    public void clear() {
        queue.clear();
    }
}
