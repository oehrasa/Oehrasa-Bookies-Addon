/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.utils.DistanceUtil;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public class MURADAESA extends Module {
    private static final double PLAYER_ESP_LIMIT_BLOCKS = 128.0;
    private static final double PLAYER_ESP_LIMIT_SQ =
        PLAYER_ESP_LIMIT_BLOCKS * PLAYER_ESP_LIMIT_BLOCKS;
    private static final double CONFIRM_PLAYER_RADIUS_BLOCKS = 12.0;
    private static final double CONFIRM_PLAYER_RADIUS_SQ =
        CONFIRM_PLAYER_RADIUS_BLOCKS * CONFIRM_PLAYER_RADIUS_BLOCKS;
    private static final long STATE_CACHE_TTL_MS = 120000L;
    // Rebuild throttles: expire/resync state at most once per CLEANUP_INTERVAL_TICKS
    // and re-filter/re-sort pings for render at most once per RENDER_SORT_INTERVAL
    // frames, so idle ticks don't walk the full ping/state maps every frame.
    private static final int CLEANUP_INTERVAL_TICKS = 10;
    private static final int RENDER_SORT_INTERVAL = 3;

    // Burst-suppression: if a resync/anti-xray correction slams a region with
    // many block updates in a short window, that's not a player.
    private static final long BURST_WINDOW_MS = 250L;
    private static final int BURST_THRESHOLD = 12;
    private static final double BURST_RADIUS_BLOCKS = 24.0;
    private static final double BURST_RADIUS_SQ = BURST_RADIUS_BLOCKS * BURST_RADIUS_BLOCKS;

    // Cumulative redstone score at one position required before it earns a marker.
    // One flip scores REDSTONE_SCORE (1.7), so this needs two.
    private static final double REDSTONE_SCORE = 1.7;
    private static final double PLACE_BREAK_SCORE = 4.0;
    private static final double REDSTONE_MARKER_THRESHOLD = REDSTONE_SCORE * 2;

    /**
     * Redstone positions that have scored, but not enough for a marker yet. Cleared by the
     * same cleanup pass as everything else, otherwise a block that toggles once and then
     * stops would accumulate forever and produce a marker minutes later.
     */
    private final Map<Long, PendingRedstone> pendingRedstone = new ConcurrentHashMap<>();

    private record PendingRedstone(double score, long timeMs) {
    }

    /**
     * Hard cap on {@link #knownStates}. TTL alone is not a bound: it holds 120 seconds of
     * every block update in range, and on a busy server that is a large map swept on a
     * 10-tick cadence. When the cap trips we drop the oldest entries first.
     */
    private static final int MAX_KNOWN_STATES = 20000;

    /**
     * Hysteresis for the 128-block cutoff. A marker is only revoked once the player is
     * inside (PLAYER_ESP_LIMIT_BLOCKS - REVOKE_HYSTERESIS_BLOCKS), so standing on the
     * boundary no longer makes markers strobe on and off, and you can actually walk up
     * to a marker you were investigating instead of having it vanish on approach.
     */
    private static final double REVOKE_HYSTERESIS_BLOCKS = 12.0;
    private static final double REVOKE_HYSTERESIS_BLOCKS_MAX =
        Math.max(PLAYER_ESP_LIMIT_BLOCKS - REVOKE_HYSTERESIS_BLOCKS, 0);
    private static final double REVOKE_RANGE_SQ =
        REVOKE_HYSTERESIS_BLOCKS_MAX * REVOKE_HYSTERESIS_BLOCKS_MAX;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Print debug messages in chat.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> showBoxes = sgRender.add(new BoolSetting.Builder()
        .name("show-boxes")
        .description("Draw boxes around suspicious far activity spots.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyBeyondPlayerEspRange = sgGeneral.add(new BoolSetting.Builder()
        .name("only-beyond-128")
        .description("Only detect activity outside the typical PlayerESP range.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showTracers = sgRender.add(new BoolSetting.Builder()
        .name("show-tracers")
        .description("Draw tracers to suspicious far activity spots.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> tracerFlash = sgRender.add(new BoolSetting.Builder()
        .name("tracer-flash")
        .description("Make tracers pulse with a smooth fade.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> detectRedstoneInteraction = sgGeneral.add(new BoolSetting.Builder()
        .name("detect-redstone")
        .description("Also score powered/open/triggered/lit toggles as weaker hints.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chatAlerts = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-alerts")
        .description("Show chat alerts when a block-change signal is detected.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> suppressBursts = sgGeneral.add(new BoolSetting.Builder()
        .name("suppress-bursts")
        .description("Ignore dense clusters of block updates (chunk resyncs, anti-xray corrections) instead of scoring them as player activity.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> markerLifetimeSec = sgGeneral.add(new IntSetting.Builder()
        .name("marker-lifetime")
        .description("How long a AESA marker stays on screen, in seconds.")
        .defaultValue(35)
        .min(5)
        .sliderRange(5, 180)
        .build()
    );

    private final Setting<Integer> maxMarkers = sgGeneral.add(new IntSetting.Builder()
        .name("max-markers")
        .description("Maximum number of simultaneous AESA markers.")
        .defaultValue(80)
        .min(10)
        .sliderRange(10, 300)
        .build()
    );

    private final Setting<SettingColor> placeColor = sgRender.add(new ColorSetting.Builder()
        .name("place-color")
        .description("Color for block placements.")
        .defaultValue(new SettingColor(255, 80, 80))
        .build()
    );

    private final Setting<SettingColor> breakColor = sgRender.add(new ColorSetting.Builder()
        .name("break-color")
        .description("Color for block breaks.")
        .defaultValue(new SettingColor(80, 200, 255))
        .build()
    );

    private final Setting<SettingColor> redstoneColor = sgRender.add(new ColorSetting.Builder()
        .name("redstone-color")
        .description("Color for redstone/interaction toggles.")
        .defaultValue(new SettingColor(255, 200, 80))
        .build()
    );


    private final Map<Long, AESAPing> pings = new ConcurrentHashMap<>();
    private final Map<Long, CachedState> knownStates = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<RecentEvent> recentEvents = new ConcurrentLinkedDeque<>();

    /**
     * Every map above is keyed by {@code BlockPos#asLong()}, which packs only x/y/z and
     * carries no dimension. Without this guard a nether portal transition leaves Overworld
     * markers rendering at the same coordinates in the Nether, and - worse - an Overworld
     * block state gets fed to {@link #resolveOldState} as the "old" state for the Nether's
     * block at the same coords, manufacturing PLACE/BREAK detections out of nothing.
     *
     * <p>Compared by identity on purpose: {@code Level#dimension()} returns the same
     * {@link ResourceKey} instance for the life of a {@link Level}, so {@code !=} is both
     * correct and allocation-free on the packet hot path. The key alone is not enough though:
     * it is a canonical constant shared by every level in that dimension, so switching between
     * two worlds that are both the Overworld would compare equal. The {@link Level} instance is
     * tracked too, and the level is treated as unchanged only when <em>both</em> identities match.
     */
    private ResourceKey<Level> lastDimension = null;
    private Level lastLevel = null;

    private int cleanupTickCounter = 0;
    private int renderSortCounter = 0;
    private List<AESAPing> sortedRenderCache = null;

    public MURADAESA() {
        super(Addon.CATEGORY2, "MURAD-AESA",
            "Detects likely player activity outside 128 blocks by scoring world-change packet patterns.");
    }

    @Override
    public void onDeactivate() {
        resetAll();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        // Without this, stale markers and - worse - stale block states survive a
        // disconnect and seed the next world with phantom detections.
        resetAll();
    }

    private void resetAll() {
        pings.clear();
        knownStates.clear();
        recentEvents.clear();
        pendingRedstone.clear();
        sortedRenderCache = null;
        lastDimension = null;
        lastLevel = null;
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (mc.player == null || mc.level == null)
            return;

        if (mc.hasSingleplayerServer())
            return;

        Packet<?> packet = event.packet;
        if (packet instanceof ClientboundBlockUpdatePacket blockUpdate) {
            handleBlockChange(blockUpdate.getPos(), blockUpdate.getBlockState());
            return;
        }

        if (packet instanceof ClientboundSectionBlocksUpdatePacket deltaUpdate) {
            deltaUpdate.runUpdates(this::handleBlockChange);
            return;
        }
    }

    private void handleBlockChange(BlockPos pos, BlockState newState) {
        if (pos == null || newState == null)
            return;

        // Checked before isInAllowedRange so a stale-dimension state can never be
        // compared against an incoming update, which is the false-positive path.
        if (!sameDimension())
            return;

        if (!isInAllowedRange(pos))
            return;

        long now = System.currentTimeMillis();
        long key = pos.asLong();

        BlockState oldState = resolveOldState(pos, key, newState, now);
        if (oldState == null) {
            knownStates.put(key, new CachedState(newState, now));
            return;
        }

        DetectionResult detection = classifyRelevantTransition(oldState, newState);
        knownStates.put(key, new CachedState(newState, now));

        if (detection.score <= 0)
            return;

        if (suppressBursts.get() && isWithinBurst(pos, now))
            return;

        // Score is a real weight, not a label. A redstone flip is worth half a block
        // break, so one stray toggle no longer earns a marker on its own, two flips at
        // the same spot do. Previously every score>0 detection produced an identical
        // marker, which made the "weaker hints" claim in the setting description untrue.
        if (detection.kind.equals("REDSTONE")) {
            PendingRedstone prior = pendingRedstone.get(key);
            double accumulated = detection.score + (prior != null ? prior.score : 0);
            if (accumulated < REDSTONE_MARKER_THRESHOLD) {
                pendingRedstone.put(key, new PendingRedstone(accumulated, now));
                return;
            }
            pendingRedstone.remove(key);
        }

        updatePing(pos, detection.kind, detection.oldId, detection.newId, detection.score);
        recordRecentEvent(pos, now);
    }

    private DetectionResult classifyRelevantTransition(BlockState oldState, BlockState newState) {
        if (oldState == null || newState == null || oldState == newState)
            return DetectionResult.NONE;

        boolean oldAir = oldState.isAir();
        boolean newAir = newState.isAir();
        boolean oldFluid = !oldState.getFluidState().isEmpty();
        boolean newFluid = !newState.getFluidState().isEmpty();
        if (isLikelyNaturalTransition(oldState.getBlock(), newState.getBlock()))
            return DetectionResult.NONE;

        if (isFluidOrFireState(oldState) || isFluidOrFireState(newState))
            return DetectionResult.NONE;

        if (oldAir && !newAir && !newFluid)
            return new DetectionResult(PLACE_BREAK_SCORE, "PLACE", getBlockId(oldState), getBlockId(newState));

        if (!oldAir && !oldFluid && newAir)
            return new DetectionResult(PLACE_BREAK_SCORE, "BREAK", getBlockId(oldState), getBlockId(newState));

        if (!detectRedstoneInteraction.get())
            return DetectionResult.NONE;

        if (oldState.getBlock() == newState.getBlock()) {
            if (hasInteractiveFlip(oldState, newState))
                return new DetectionResult(REDSTONE_SCORE, "REDSTONE", getBlockId(oldState), getBlockId(newState));
            return DetectionResult.NONE;
        }

        return DetectionResult.NONE;
    }

    private boolean isLikelyNaturalTransition(Block oldBlock, Block newBlock) {
        return isLikelyNaturalBlock(oldBlock) || isLikelyNaturalBlock(newBlock);
    }

    /**
     * Blocks that change on their own from weather, growth, decay or physics, and so are NOT
     * evidence of a player. This is an identity allowlist, never a substring test.
     *
     * <p><b>Why identity:</b> the previous {@code blockId.contains(...)} version silently hid real
     * player builds. The exact-id string set had to run a registry lookup per block update to get
     * the id for the membership test; identity comparison against compile-time Block fields is
     * that same exact-id allowlist, allocation-free, and self-verifying at build time if a field
     * is ever renamed by a mapping change. The set stays exact: every member below is a block
     * that vanilla creates from air without a player.
     *
     * <p>Every block below exists as a {@code Blocks} field on this branch (verified by javap and
     * by the project's build). Note {@code snow} here is the *weather snow layer*
     * ({@code SnowLayerBlock}, built up by snowfall) and is deliberately natural;
     * {@code snow_block} is the player-placed full block and is deliberately NOT listed.
     */
    private static final Set<Block> NATURAL_BLOCKS = Set.of(
        // Snowfall accumulation + ice. snow = the layer snowfall builds, NOT snow_block.
        Blocks.SNOW, Blocks.ICE, Blocks.FROSTED_ICE, Blocks.POWDER_SNOW,
        // Moss / lichen spread
        Blocks.MOSS_BLOCK, Blocks.MOSS_CARPET,
        Blocks.PALE_MOSS_BLOCK, Blocks.PALE_MOSS_CARPET,
        Blocks.PALE_HANGING_MOSS, Blocks.GLOW_LICHEN,
        // Vines and cave growth
        Blocks.VINE, Blocks.CAVE_VINES,
        // Grass / ferns / dead bushes (spreading + decay)
        Blocks.SHORT_GRASS, Blocks.TALL_GRASS,
        Blocks.FERN, Blocks.LARGE_FERN, Blocks.DEAD_BUSH,
        // Leaf decay and growth
        Blocks.OAK_LEAVES, Blocks.SPRUCE_LEAVES, Blocks.BIRCH_LEAVES,
        Blocks.JUNGLE_LEAVES, Blocks.ACACIA_LEAVES, Blocks.DARK_OAK_LEAVES,
        Blocks.MANGROVE_LEAVES, Blocks.CHERRY_LEAVES, Blocks.PALE_OAK_LEAVES,
        Blocks.AZALEA_LEAVES, Blocks.FLOWERING_AZALEA_LEAVES,
        Blocks.AZALEA, Blocks.FLOWERING_AZALEA, Blocks.MANGROVE_ROOTS,
        // Physics: falling / drifting blocks
        Blocks.SAND, Blocks.RED_SAND, Blocks.GRAVEL,
        Blocks.SUSPICIOUS_SAND,
        // Concrete powder hardens on its own schedule. There is NO bare
        // Blocks.CONCRETE_POWDER constant -> it is a ColorCollection<Block> record of the 16
        // dyed variants, so all 16 accessor calls are listed individually.
        Blocks.CONCRETE_POWDER.white(), Blocks.CONCRETE_POWDER.orange(),
        Blocks.CONCRETE_POWDER.magenta(), Blocks.CONCRETE_POWDER.lightBlue(),
        Blocks.CONCRETE_POWDER.yellow(), Blocks.CONCRETE_POWDER.lime(),
        Blocks.CONCRETE_POWDER.pink(), Blocks.CONCRETE_POWDER.gray(),
        Blocks.CONCRETE_POWDER.lightGray(), Blocks.CONCRETE_POWDER.cyan(),
        Blocks.CONCRETE_POWDER.purple(), Blocks.CONCRETE_POWDER.blue(),
        Blocks.CONCRETE_POWDER.brown(), Blocks.CONCRETE_POWDER.green(),
        Blocks.CONCRETE_POWDER.red(), Blocks.CONCRETE_POWDER.black(),
        // Crops (the four the substring version used to miss) + nether growth
        Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES,
        Blocks.BEETROOTS, Blocks.TORCHFLOWER_CROP, Blocks.PITCHER_CROP,
        Blocks.NETHER_WART, Blocks.NETHER_SPROUTS, Blocks.COCOA,
        Blocks.SWEET_BERRY_BUSH,
        // Sugar cane / cactus / bamboo spread
        Blocks.SUGAR_CANE, Blocks.CACTUS, Blocks.BAMBOO,
        Blocks.BAMBOO_SAPLING,
        // Stem fruit dropped into an adjacent air cell by a melon/pumpkin stem. An unattended
        // melon or pumpkin farm past 128 blocks otherwise fires a marker + chat alert per fruit.
        Blocks.MELON, Blocks.PUMPKIN,
        // Cave and nether vine growth (the plant blocks spawn as well as the hanging forms)
        Blocks.CAVE_VINES_PLANT, Blocks.WEEPING_VINES,
        Blocks.WEEPING_VINES_PLANT, Blocks.TWISTING_VINES,
        Blocks.TWISTING_VINES_PLANT,
        // Underwater growth + pointed dripstone. Only the *pointed* dripstone grows; a
        // dripstone_block is never created at runtime (javap: PointedDripstoneBlock references
        // only Blocks.MUD/Blocks.CLAY, and DRIPSTONE_BLOCK is placed solely by
        // LargeDripstoneFeature worldgen), so it is not listed - a player-placed one must
        // register as activity.
        Blocks.SEAGRASS, Blocks.TALL_SEAGRASS,
        Blocks.KELP, Blocks.HANGING_ROOTS,
        Blocks.POINTED_DRIPSTONE,
        Blocks.BIG_DRIPLEAF, Blocks.SMALL_DRIPLEAF,
        Blocks.BIG_DRIPLEAF_STEM, Blocks.LEAF_LITTER,
        // Mushroom / crystal / sculk / chorus growth
        Blocks.BROWN_MUSHROOM, Blocks.RED_MUSHROOM,
        Blocks.BROWN_MUSHROOM_BLOCK, Blocks.RED_MUSHROOM_BLOCK,
        Blocks.AMETHYST_CLUSTER, Blocks.SCULK, Blocks.SCULK_VEIN,
        Blocks.CHORUS_PLANT, Blocks.CHORUS_FLOWER
    );

    private boolean isLikelyNaturalBlock(Block block) {
        return NATURAL_BLOCKS.contains(block);
    }

    private boolean hasInteractiveFlip(BlockState oldState, BlockState newState) {
        try {
            for (Property<?> property : oldState.getProperties()) {
                String name = property.getName();
                if (!name.equals("open") && !name.equals("powered")
                    && !name.equals("triggered") && !name.equals("lit"))
                    continue;

                Comparable<?> oldVal = oldState.getValue(property);
                Comparable<?> newVal = newState.getValue(property);
                if (oldVal != null && newVal != null && !oldVal.equals(newVal))
                    return true;
            }
        } catch (Exception ignored) {
        }

        return false;
    }

    private String getBlockId(BlockState state) {
        if (state == null)
            return "minecraft:air";

        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private boolean isFluidOrFireState(BlockState state) {
        if (state == null)
            return true;

        if (!state.getFluidState().isEmpty())
            return true;

        Block block = state.getBlock();
        return block == Blocks.FIRE || block == Blocks.SOUL_FIRE;
    }

    private boolean isChunkLoaded(BlockPos pos) {
        if (mc.level == null)
            return false;

        return mc.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private void recordRecentEvent(BlockPos pos, long now) {
        recentEvents.addLast(new RecentEvent(pos.immutable(), now));
        pruneRecentEvents(now);
    }

    private void pruneRecentEvents(long now) {
        // Pruned at the burst window, not a longer tail: the only consumer is
        // isWithinBurst(), which ignores anything older than BURST_WINDOW_MS. Keeping a
        // 2500ms tail meant ~90% of every burst scan walked entries it would skip.
        while (!recentEvents.isEmpty()) {
            RecentEvent first = recentEvents.peekFirst();
            if (first == null || now - first.timeMs <= BURST_WINDOW_MS)
                break;
            recentEvents.pollFirst();
        }
    }

    private boolean isWithinBurst(BlockPos pos, long now) {
        int nearbyRecent = 0;

        for (RecentEvent event : recentEvents) {
            if (now - event.timeMs > BURST_WINDOW_MS)
                continue;

            if (pos.distSqr(event.pos) <= BURST_RADIUS_SQ)
                nearbyRecent++;

            if (nearbyRecent >= BURST_THRESHOLD)
                return true;
        }

        return false;
    }

    private void updatePing(BlockPos pos, String kind, String oldId, String newId, double score) {
        long key = pos.asLong();
        long now = System.currentTimeMillis();
        AESAPing ping = pings.get(key);
        if (ping == null) {
            ping = new AESAPing(pos.immutable());
            pings.put(key, ping);
        }

        ping.lastUpdateMs = now;
        ping.hits++;
        ping.score += score;
        ping.lastKind = kind;
        ping.lastOldId = oldId;
        ping.lastNewId = newId;

        if (chatAlerts.get() && now - ping.lastAlertMs >= 300L) {
            Vec3 center = Vec3.atCenterOf(ping.pos);
            int distanceBlocks = (int) Math.round(
                DistanceUtil.distance(center, mc.player.position())
            );
            String rangeSuffix = onlyBeyondPlayerEspRange.get()
                ? String.format(" (%db away, outside %.0fb).", distanceBlocks, PLAYER_ESP_LIMIT_BLOCKS)
                : String.format(" (%db away).", distanceBlocks);
            if (debug.get()) info(String.format(
                "%s %s -> %s at %d, %d, %d%s (%d hit%s, %.1f score)", ping.lastKind,
                ping.lastOldId, ping.lastNewId, ping.pos.getX(),
                ping.pos.getY(), ping.pos.getZ(), rangeSuffix,
                ping.hits, ping.hits == 1 ? "" : "s", ping.score));
            ping.lastAlertMs = now;
        }

        trimToMarkerLimit();
    }

    private void trimToMarkerLimit() {
        int max = maxMarkers.get();
        if (pings.size() <= max)
            return;

        ArrayList<AESAPing> sorted = new ArrayList<>(pings.values());
        sorted.sort(Comparator.comparingLong(p -> p.lastUpdateMs));
        int toRemove = pings.size() - max;
        for (int i = 0; i < toRemove && i < sorted.size(); i++)
            pings.remove(sorted.get(i).pos.asLong());
    }

    private BlockState resolveOldState(BlockPos pos, long key, BlockState newState, long now) {
        CachedState cached = knownStates.get(key);
        if (cached != null && now - cached.timeMs <= STATE_CACHE_TTL_MS) {
            if (cached.state != newState)
                return cached.state;
        }

        if (!isChunkLoaded(pos))
            return null;

        return mc.level.getBlockState(pos);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.level == null) return;

        // Drain a dimension change even if no block packets arrived during the window,
        // so a marker never renders against the wrong dimension.
        if (!sameDimension()) return;

        if (cleanupTickCounter++ % CLEANUP_INTERVAL_TICKS != 0) return;

        long now = System.currentTimeMillis();

        long lifetimeMs = markerLifetimeSec.get() * 1000L;
        boolean onlyBeyond = onlyBeyondPlayerEspRange.get();
        pings.entrySet().removeIf(entry -> {
            AESAPing ping = entry.getValue();
            return now - ping.lastUpdateMs > lifetimeMs || shouldRevokePing(ping, onlyBeyond);
        });

        pruneRecentEvents(now);
        pendingRedstone.entrySet().removeIf(e -> now - e.getValue().timeMs > lifetimeMs);

        knownStates.entrySet().removeIf(e -> now - e.getValue().timeMs > STATE_CACHE_TTL_MS);

        // TTL is a lifetime, not a bound. If the map is still over cap, drop the oldest
        // entries until it isn't. Sorting is only reached on the overflow path.
        if (knownStates.size() > MAX_KNOWN_STATES) {
            ArrayList<Map.Entry<Long, CachedState>> byAge =
                new ArrayList<>(knownStates.entrySet());
            byAge.sort(Comparator.comparingLong(e -> e.getValue().timeMs));
            int excess = knownStates.size() - MAX_KNOWN_STATES;
            for (int i = 0; i < excess && i < byAge.size(); i++) {
                knownStates.remove(byAge.get(i).getKey());
            }
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || pings.isEmpty())
            return;

        if (sortedRenderCache == null || renderSortCounter++ % RENDER_SORT_INTERVAL == 0) {
            // pings is already capped at maxMarkers by trimToMarkerLimit on insert, so
            // the .limit() here was a second, redundant gate on the same bound.
            sortedRenderCache = new ArrayList<>(pings.values()).stream()
                .filter(p -> isRetainedForRender(p.pos))
                .sorted(Comparator.<AESAPing>comparingDouble(p -> p.score).reversed()
                    .thenComparing(Comparator.comparingLong((AESAPing p) -> p.lastUpdateMs).reversed()))
                .toList();
        }

        for (AESAPing ping : sortedRenderCache) {
            SettingColor color = getColorForKind(ping.lastKind);
            if (tracerFlash.get())
                color = flashColor(color);
            AABB box = new AABB(ping.pos);

            if (showBoxes.get()) {
                event.renderer.box(box, color, color, ShapeMode.Lines, 0);
            }

            if (showTracers.get()) {
                Vec3 center = box.getCenter();
                event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
                    center.x, center.y, center.z, color);
            }
        }
    }

    private SettingColor flashColor(SettingColor color) {
        double t = (System.currentTimeMillis() % 1000L) / 1000.0;
        double pulse = 0.5 + 0.5 * Math.sin(t * Math.PI * 2);
        int alpha = (int) (color.a * (0.4 + 0.6 * pulse));
        return new SettingColor(color.r, color.g, color.b, alpha);
    }

    private SettingColor getColorForKind(String kind) {
        return switch (kind) {
            case "PLACE" -> placeColor.get();
            case "BREAK" -> breakColor.get();
            case "REDSTONE" -> redstoneColor.get();
            default -> placeColor.get();
        };
    }

    private boolean isRetainedForRender(BlockPos pos) {
        if (pos == null || mc.player == null)
            return false;

        if (!onlyBeyondPlayerEspRange.get())
            return true;

        // Render at the SAME threshold shouldRevokePing holds markers to, not the strict
        // 128-block one. Between REVOKE_HYSTERESIS_BLOCKS_MAX (116) and the 128 cutoff a ping is
        // deliberately RETAINED by the revoke sweep, so filtering it out here left markers alive
        // but invisible - which is precisely the strobe the hysteresis was added to prevent, and it
        // contradicted the constant's own comment about walking up to a marker you were
        // investigating. It also let those invisible markers hold slots in `pings` against
        // maxMarkers.
        //
        // isInAllowedRange stays strict at 128 on purpose: it gates NEW detections in
        // handleBlockChange, where the exact cutoff is the intended rule.
        return DistanceUtil.distanceSq(mc.player.position(), Vec3.atCenterOf(pos)) > REVOKE_RANGE_SQ;
    }

    private boolean isBeyondPlayerEspRange(BlockPos pos) {
        if (mc.player == null || pos == null)
            return false;

        return DistanceUtil.distanceSq(mc.player.position(), Vec3.atCenterOf(pos)) > PLAYER_ESP_LIMIT_SQ;
    }

    private boolean isInAllowedRange(BlockPos pos) {
        if (pos == null || mc.player == null)
            return false;

        if (!onlyBeyondPlayerEspRange.get())
            return true;

        return isBeyondPlayerEspRange(pos);
    }

    private boolean shouldRevokePing(AESAPing ping, boolean onlyBeyond) {
        if (ping == null || ping.pos == null || mc.player == null || mc.level == null)
            return false;

        if (!onlyBeyond)
            return false;

        // Revokes inside a band rather than exactly at the 128 cutoff, so markers on the
        // boundary don't strobe as the player walks.
        if (DistanceUtil.distanceSq(mc.player.position(), Vec3.atCenterOf(ping.pos)) > REVOKE_RANGE_SQ)
            return false;

        return !hasTrackedPlayerNear(ping.pos);
    }

    /**
     * True when {@code mc.level} is not the same level the cached state/marker maps were
     * populated under, and clears them when it is not.
     *
     * <p>Called from the packet handler, so it must stay cheap: both compares are reference
     * identity, so the common (unchanged) path allocates nothing.
     */
    private boolean sameDimension() {
        if (mc.level == null) return false;

        Level currentLevel = mc.level;
        ResourceKey<Level> current = currentLevel.dimension();
        if (lastLevel == currentLevel && lastDimension == current) return true;

        lastLevel = currentLevel;
        lastDimension = current;
        pings.clear();
        knownStates.clear();
        recentEvents.clear();
        pendingRedstone.clear();
        sortedRenderCache = null;
        return false;
    }

    private boolean hasTrackedPlayerNear(BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        for (Player player : mc.level.players()) {
            if (player == null || player == mc.player || player.isRemoved())
                continue;

            if (DistanceUtil.distanceSq(player.position(), center) <= CONFIRM_PLAYER_RADIUS_SQ)
                return true;
        }

        return false;
    }

    private static final class AESAPing {
        private final BlockPos pos;
        private long lastUpdateMs = System.currentTimeMillis();
        private long lastAlertMs;
        private int hits;
        /**
         * Accumulated detection weight; drives render ordering so strong signals surface first.
         */
        private double score;
        private String lastKind = "UNKNOWN";
        private String lastOldId = "unknown";
        private String lastNewId = "unknown";

        private AESAPing(BlockPos pos) {
            this.pos = pos;
        }
    }

    private record DetectionResult(double score, String kind, String oldId, String newId) {
        private static final DetectionResult NONE = new DetectionResult(0, "", "", "");
    }

    private record CachedState(BlockState state, long timeMs) {
    }

    private record RecentEvent(BlockPos pos, long timeMs) {
    }
}
