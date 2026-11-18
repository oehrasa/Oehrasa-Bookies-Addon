/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.utils.DistanceUtil;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public class DriedGhastPlacer extends Module {
    private enum Stage {
        IDLE,
        // Batch ice-mode stages
        PREPARE_BATCH, PLACE_ICE_BATCH, WAIT_ICE_BATCH,
        MINE_ICE_BATCH, WAIT_WATER_BATCH,
        // Batch ghast stages (shared with fast mode)
        PLACE_GHAST_BATCH, WAIT_GHAST_BATCH,
        FINISH_BATCH
    }

    /**
     * Where to walk once the strip in front of the player has been used up.
     */
    private enum MoveMode {Backwards, Sideways, Alternate}

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Print debug messages in chat.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .defaultValue(4.5)
        .min(1)
        .sliderMax(5)
        .build());

    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay")
        .defaultValue(5)
        .min(1)
        .sliderMax(40)
        .build());

    private final Setting<Integer> maxBlocks = sgGeneral.add(new IntSetting.Builder()
        .name("max-blocks")
        .defaultValue(0)
        .min(0)
        .sliderMax(256)
        .build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .defaultValue(true)
        .build());

    private final Setting<Integer> batchTimeout = sgGeneral.add(new IntSetting.Builder()
        .name("batch-timeout")
        .description("Ticks to wait for a whole batch phase before giving up on it.")
        .defaultValue(60)
        .min(20)
        .sliderMax(200)
        .build());

    private final Setting<Boolean> fastMode = sgGeneral.add(new BoolSetting.Builder()
        .name("fast-mode")
        .description("Skip ice placement – look for existing water sources on solid blocks instead.")
        .defaultValue(false)
        .build());

    private final Setting<Integer> stripWidth = sgGeneral.add(new IntSetting.Builder()
        .name("strip-width")
        .description("How many blocks wide the strip in front of you is.")
        .defaultValue(3)
        .min(1)
        .sliderMax(15)
        .build());

    private final Setting<Integer> minPlaceDistance = sgGeneral.add(new IntSetting.Builder()
        .name("min-place-distance")
        .description("Never place closer than this many blocks in front of you, so the walk lines stay open.")
        .defaultValue(2)
        .min(1)
        .sliderMax(4)
        .build());

    private final Setting<MoveMode> moveMode = sgGeneral.add(new EnumSetting.Builder<MoveMode>()
        .name("move-mode")
        .description("Backwards retreats away from the field, Sideways widens it, Alternate does both in turn.")
        .defaultValue(MoveMode.Alternate)
        .build());

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .defaultValue(true)
        .build());

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .defaultValue(new SettingColor(255, 165, 0, 75))
        .build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .defaultValue(new SettingColor(255, 165, 0, 255))
        .build());


    private Stage stage = Stage.IDLE;
    private List<BlockPos> targetCells = new ArrayList<>();
    private List<BlockPos> supportCells = new ArrayList<>();
    private boolean[] icePresent;
    /**
     * Whether the break packets for a cell already went out; a cell waits for the world.
     */
    private boolean[] iceSent;
    private boolean[] ghastPlaced;
    private int delayTicks = 0;
    private int placedCount = 0;
    /**
     * Client ticks the module has ticked, used for the batch phase deadlines.
     */
    private int clientTicks = 0;
    /**
     * The client tick the current batch phase gives up at.
     */
    private int batchDeadline = 0;
    private int batchBreakStep = 0;
    private Block driedGhastBlock = null;
    private int iceSlot = -1, pickSlot = -1, ghastSlot = -1;

    /**
     * Relocation state. The walk is a plain straight-line walk toward {@link #relocateGoal} -
     * Baritone was removed, so there is no pathing, no goal process and no Baritone dependency.
     */
    /**
     * Frozen on activate: placement turns the view towards its targets (see rotate), so
     * deriving the axis from yaw every cycle would flip the strip around mid-field.
     */
    private Direction stripAxis = Direction.SOUTH;
    private boolean relocating = false;
    private BlockPos relocateGoal = null;
    private int relocateStep = 0;
    private int relocateWaitTicks = 0;
    private int relocateMoves = 0;
    private int relocateFailures = 0;
    /**
     * Whether the last relocation walk actually arrived (a stall is counted by onTick instead).
     */
    private boolean lastRelocationArrived = false;
    private boolean initialRelocateDone = false;

    /**
     * Cells whose placement never showed up after a bounded wait. They still look free to
     * the scan, so without this the module would pick the exact same cell every cycle.
     */
    private final LongSet rejectedTargets = new LongOpenHashSet();
    private BlockPos lastScanFeet = null;
    private boolean stripSaturatedNotified = false;
    private int confirmWaitTicks = 0;

    /**
     * 15s for a two-to-four block walk; anything longer counts as a failed relocation.
     */
    private static final int RELOCATE_WAIT_TICKS = 300;
    private static final int MAX_RELOCATE_FAILURES = 3;
    /**
     * 2s for ice/water to appear after acting - a rejected placement would otherwise wait forever.
     */
    private static final int PLACE_CONFIRM_TIMEOUT = 40;

    public DriedGhastPlacer() {
        super(Addon.CATEGORY, "Dried-Ghast",
            "Give these cute and tiny little creatures a second chances.");
    }

    @Override
    public void onActivate() {
        resetState();
        stripAxis = mc.player != null ? mc.player.getDirection() : Direction.SOUTH;
    }

    @Override
    public void onDeactivate() {
        stage = Stage.IDLE;
        // A batch holds a swapped slot (the ice, then the pickaxe, then the ghast) through
        // several phases, and InvUtils' previous slot is global. Turning the module off in
        // the middle of a batch left the player holding that item and left the stale slot
        // behind, so the next run's swapBack restored a slot from the last run instead of
        // the one the player held when this run started. swapBack is a no-op when there is
        // nothing to restore, so the paths that already restored it are unaffected.
        InvUtils.swapBack();
        targetCells.clear();
        supportCells.clear();
        // Release the held movement keys. A walk that ends without this leaves the player
        // walking (and possibly bunny-hopping) for the rest of the session.
        stopWalking();
        relocateGoal = null;
        relocateWaitTicks = 0;
    }

    private void resetState() {
        stage = Stage.IDLE;
        targetCells.clear();
        supportCells.clear();
        icePresent = null;
        iceSent = null;
        ghastPlaced = null;
        aimedIceCell = -1;
        aimedGhastCell = -1;
        delayTicks = 0;
        iceCursor = 0;
        batchBreakStep = 0;
        placedCount = 0;
        driedGhastBlock = null;
        iceSlot = pickSlot = ghastSlot = -1;
        confirmWaitTicks = 0;
        relocating = false;
        stopWalking();
        relocateGoal = null;
        relocateStep = 0;
        relocateWaitTicks = 0;
        relocateMoves = 0;
        relocateFailures = 0;
        lastRelocationArrived = false;
        rejectedTargets.clear();
        lastScanFeet = null;
        stripSaturatedNotified = false;
        initialRelocateDone = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;
        // Counted before the delay check: a phase's timeout is elapsed ticks, and the delay
        // ticks skip the stage handler but are still ticks the player spent waiting.
        clientTicks++;
        if (maxBlocks.get() > 0 && placedCount >= maxBlocks.get()) {
            if (stage != Stage.IDLE) {
                // Let the in-flight batch finish cleanup before deactivating.
                if (stage == Stage.FINISH_BATCH) {
                    finishBatch();
                }
            }
            // An interrupted batch can still hold a swapped slot (ice, pickaxe, ghast). Give the
            // player's own slot back before the module goes away.
            InvUtils.swapBack();
            if (debug.get()) info("Placed " + placedCount + " dried ghast blocks (limit reached).");
            toggle();
            return;
        }

        // Ensure we start in fresh ground before placing first block (prevents water flowing)
        if (!initialRelocateDone && !relocating) {
            // Check the inventory BEFORE the walk. prepare() used to be the only item check,
            // so activating with a missing ghast/ice/pickaxe started the relocation walk (up
            // to RELOCATE_WAIT_TICKS) and only disabled the module once it arrived. Relocation
            // itself is unchanged: still requested here on activation, and otherwise only from
            // prepare()/relocateOrRetry() after findPosition() found no free cell.
            if (!ensureItems()) return;
            if (tryRelocate()) return;
            initialRelocateDone = true;
        }

        // Parked while walking to fresh ground: no cycle can be in flight here, since
        // relocation is only ever requested from prepare() while the stage machine is IDLE.
        if (relocating) {
            walkToRelocateGoal();
            if (relocateArrived()) {
                clearRelocation();
                lastRelocationArrived = true;
                delayTicks = 0;
            } else if (relocateWaitTicks++ >= RELOCATE_WAIT_TICKS) {
                // Stalled or unreachable: stop and let the next prepare() try again. Alternate
                // automatically picks the other direction because relocateMoves already advanced
                // when the goal was set.
                clearRelocation();
                // A walk that never arrived is counted here, once, and only here.
                lastRelocationArrived = false;
                if (++relocateFailures >= MAX_RELOCATE_FAILURES) {
                    error("§cCouldn't reach a fresh strip - move manually.");
                    toggle();
                    return;
                }
                if (debug.get()) info("§eCouldn't reach a fresh strip, retrying.");
                delayTicks = 20;
            }
            if (relocating) return;
        }

        if (delayTicks > 0) { delayTicks--;
            return;
        }

        switch (stage) {
            case IDLE -> prepareBatch();
            // Ice mode stages
            case PREPARE_BATCH -> { /* already advanced in prepareBatch */ }
            case PLACE_ICE_BATCH -> executePlaceIceBatch();
            case WAIT_ICE_BATCH -> checkIceBatch();
            case MINE_ICE_BATCH -> executeMineIceBatch();
            case WAIT_WATER_BATCH -> checkWaterBatch();
            // Ghast stages (shared with fast mode)
            case PLACE_GHAST_BATCH -> executePlaceGhastBatch();
            case WAIT_GHAST_BATCH -> checkGhastBatch();
            case FINISH_BATCH -> finishBatch();
        }
    }

    // Idle: find items and a valid position
    private void prepareBatch() {
        if (!ensureItems()) return;
        aimedIceCell = -1;
        aimedGhastCell = -1;

        if (fastMode.get()) {
            if (!findAllPositions(true)) {
                relocateOrRetry();
                return;
            }
            InvUtils.swap(ghastSlot, true);
            if (rotate.get() && !targetCells.isEmpty()) {
                Vec3 hitVec = Vec3.atCenterOf(supportCells.get(0)).add(0, 0.5, 0);
                Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec));
                delayTicks = 2;
            }
            // The flags have to exist before the placement phase: the out of stock path finishes
            // the batch, and it reads them.
            ghastPlaced = new boolean[targetCells.size()];
            stage = Stage.PLACE_GHAST_BATCH;
            return;
        }

        if (!findAllPositions(false)) {
            relocateOrRetry();
            return;
        }

        InvUtils.swap(iceSlot, true);
        if (rotate.get() && !targetCells.isEmpty()) {
            Vec3 hitVec = Vec3.atCenterOf(supportCells.get(0)).add(0, 0.5, 0);
            Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec));
            delayTicks = 2;
        }
        stage = Stage.PLACE_ICE_BATCH;
    }

    /**
     * Collects every free cell in the strip (farthest first), capped by the remaining
     * max-blocks budget. Returns false if none found.
     */
    private boolean findAllPositions(boolean requireWater) {
        if (driedGhastBlock == null) return false;
        targetCells.clear();
        supportCells.clear();

        BlockPos feet = mc.player.blockPosition();
        if (!feet.equals(lastScanFeet)) {
            rejectedTargets.clear();
            stripSaturatedNotified = false;
            lastScanFeet = feet;
        }

        Direction side = stripAxis.getClockWise();
        double rangeVal = range.get();
        double maxDistSq = rangeVal * rangeVal;
        int maxFwd = (int) rangeVal;
        // One query covers every candidate the sweep visits; candidates intersect against it
        // instead of issuing a query each. The exclusion matches the old predicate exactly.
        List<AABB> occupants = entityBoxesForScan(feet, rangeVal);
        int minFwd = Math.max(1, Math.min(minPlaceDistance.get(), maxFwd));
        int half = (stripWidth.get() - 1) / 2;
        int budget = maxBlocks.get() > 0 ? Math.max(1, maxBlocks.get() - placedCount) : Integer.MAX_VALUE;
        if (!requireWater) {
            // Ice mode: every target cell costs an ice placement, a pickaxe break and a dried
            // ghast, so cap the batch at the ghasts currently in the hotbar as well. Otherwise
            // cells are mined and then rejected for lack of a ghast. Fast mode keeps the plain
            // max-blocks budget.
            budget = Math.min(budget, countDriedGhasts());
        }

        for (int fwd = maxFwd; fwd >= minFwd && targetCells.size() < budget; fwd--)
            for (int dy = -maxFwd; dy <= maxFwd && targetCells.size() < budget; dy++)
                for (int lat = -half; lat <= stripWidth.get() - 1 - half && targetCells.size() < budget; lat++) {
                    BlockPos pos = feet.offset(
                        stripAxis.getStepX() * fwd + side.getStepX() * lat, dy,
                        stripAxis.getStepZ() * fwd + side.getStepZ() * lat);

                    if (pos.equals(feet) || pos.equals(feet.above())) continue;
                    if (pos.distSqr(feet) > maxDistSq) continue;
                    if (rejectedTargets.contains(pos.asLong())) continue;

                    BlockState ts = mc.level.getBlockState(pos);
                    if (requireWater) {
                        if (ts.getBlock() != Blocks.WATER) continue;
                    } else {
                        if (!ts.isAir() && ts.getBlock() != Blocks.WATER) continue;
                    }
                    if (ts.getBlock() == driedGhastBlock) continue;

                    BlockPos below = pos.below();
                    BlockState bs = mc.level.getBlockState(below);
                    if (!bs.isCollisionShapeFullBlock(mc.level, below)) continue;
                    if (bs.getBlock() == driedGhastBlock) continue;
                    if (bs.getBlock() == Blocks.ICE) continue;

                    // One query covers the whole sweep: checked against the hoisted list.
                    if (occupiedByEntity(occupants, pos)) continue;

                    targetCells.add(pos.immutable());
                    supportCells.add(below.immutable());
                }

        // Only a real target clears the relocation state. A scan that found nothing has to keep the
        // failure count, or walkable-but-full terrain repeats the walk forever without ever
        // tripping the cap that relocateOrRetry relies on.
        if (targetCells.isEmpty()) return false;
        relocateFailures = 0;
        lastRelocationArrived = false;
        stripSaturatedNotified = false;
        return true;
    }

    /**
     * One entity query covering every candidate cell the strip sweep visits. The candidates
     * sit within {@code range} blocks of the feet and {@code stripWidth} to the sides; the
     * exclusion mirrors the predicate the per-cell queries used, so only the local player is
     * excluded and every other entity (alive, spectator or not) blocks a cell.
     */
    private List<AABB> entityBoxesForScan(BlockPos feet, double range) {
        double pad = range + 1.0 + stripWidth.get();
        AABB bounds = new AABB(feet).inflate(pad, pad, pad);
        List<AABB> boxes = new ArrayList<>();
        for (Entity e : mc.level.getEntities((Entity) null, bounds,
            e -> !(e instanceof net.minecraft.world.entity.player.Player && e == mc.player))) {
            boxes.add(e.getBoundingBox());
        }
        return boxes;
    }

    private boolean occupiedByEntity(List<AABB> occupants, BlockPos pos) {
        if (occupants.isEmpty()) return false;
        AABB cell = new AABB(pos);
        for (AABB box : occupants) {
            if (box.intersects(cell)) return true;
        }
        return false;
    }

    /**
     * Resolve the items the selected mode needs and stash their slots, disabling the module with
     * a message when something is missing. Called from {@link #prepare()} and from the
     * activation gate in {@link #onTick} before the walk to fresh ground can start, so a missing
     * item can never move the player first and only then report itself.
     */
    private boolean ensureItems() {
        ghastSlot = findSlot(s -> s.getItem() == Items.DRIED_GHAST);
        if (ghastSlot == -1) {
            if (debug.get()) info("§cNo dried ghast block in hotbar.");
            toggle();
            return false;
        }

        Item item = mc.player.getInventory().getItem(ghastSlot).getItem();
        if (item instanceof BlockItem bi) driedGhastBlock = bi.getBlock();
        else {
            if (debug.get()) info("§cDried ghast item is not a block.");
            toggle();
            return false;
        }

        if (fastMode.get()) return true;

        // Ice mode: need ice and a valid pickaxe
        iceSlot = findSlot(s -> s.getItem() == Items.ICE);
        if (iceSlot == -1) {
            if (debug.get()) info("§cNo ice blocks in hotbar.");
            toggle();
            return false;
        }

        pickSlot = findSlot(this::isValidPickaxe);
        if (pickSlot == -1) {
            if (debug.get()) info("§cNo valid pickaxe in hotbar.");
            toggle();
            return false;
        }

        return true;
    }

    // Find position
    private boolean findTopPlacementPosition() {
        if (driedGhastBlock == null) return false;
        return findPosition(false);
    }

    /** Fast mode: target block must be water */
    private boolean findWaterPlacementPosition() {
        if (driedGhastBlock == null) return false;
        return findPosition(true);
    }

    /**
     * Scans the strip in front of the player, farthest cell first.
     */
    private boolean findPosition(boolean requireWater) {
        if (driedGhastBlock == null) return false;

        BlockPos feet = mc.player.blockPosition();
        if (!feet.equals(lastScanFeet)) {
            // The player moved: a cell rejected from the old spot is worth retrying, and a
            // saturated strip there says nothing about the new spot.
            rejectedTargets.clear();
            stripSaturatedNotified = false;
            lastScanFeet = feet;
        }

        Direction side = stripAxis.getClockWise();
        double rangeVal = range.get();
        double maxDistSq = rangeVal * rangeVal;
        int maxFwd = (int) rangeVal;
        // One query covers every candidate the sweep visits; candidates intersect against it
        // instead of issuing a query each. The exclusion matches the old predicate exactly.
        List<AABB> occupants = entityBoxesForScan(feet, rangeVal);
        int minFwd = Math.max(1, Math.min(minPlaceDistance.get(), maxFwd));
        int half = (stripWidth.get() - 1) / 2;

        for (int fwd = maxFwd; fwd >= minFwd; fwd--)               // farthest first
            for (int dy = -maxFwd; dy <= maxFwd; dy++)             // ground rows before stacked ones
                for (int lat = -half; lat <= stripWidth.get() - 1 - half; lat++) {
                    BlockPos pos = feet.offset(
                        stripAxis.getStepX() * fwd + side.getStepX() * lat, dy,
                        stripAxis.getStepZ() * fwd + side.getStepZ() * lat);

                    // fwd >= minFwd >= 1 makes a collision with the player's own cells
                    // impossible, but keep the guard so a future edit can't reintroduce it.
                    if (pos.equals(feet) || pos.equals(feet.above())) continue;
                    if (pos.distSqr(feet) > maxDistSq) continue;
                    if (rejectedTargets.contains(pos.asLong())) continue;

                    BlockState ts = mc.level.getBlockState(pos);
                    if (requireWater) {
                        if (ts.getBlock() != Blocks.WATER) continue;
                    } else {
                        if (!ts.isAir() && ts.getBlock() != Blocks.WATER) continue;
                    }
                    if (ts.getBlock() == driedGhastBlock) continue;

                    BlockPos below = pos.below();

                    BlockState bs = mc.level.getBlockState(below);
                    if (!bs.isCollisionShapeFullBlock(mc.level, below)) continue;
                    if (bs.getBlock() == driedGhastBlock) continue;
                    // Our own abandoned (or in-flight) ice is not a stable support: it is
                    // about to be broken, or already was, so building on it risks a floating
                    // block once the ice melts away.
                    if (bs.getBlock() == Blocks.ICE) continue;

                    // Skip if any entity (other than the local player) occupies this block.
                    // Deliberately last: nearly every position in the sweep already failed a
                    // check above. One query covers the whole sweep (entityBoxesForScan).
                    if (occupiedByEntity(occupants, pos)) continue;

                    targetCells.add(pos.immutable());
                    supportCells.add(below.immutable());
                }
        if (targetCells.isEmpty()) return false;
        relocateFailures = 0;
        lastRelocationArrived = false;
        stripSaturatedNotified = false;
        return true;
    }

    /**
     * No free cell left in the strip: walk to fresh ground, otherwise just retry later.
     */
    private void relocateOrRetry() {
        // The stall path (onTick) only counts walks that fail to ARRIVE. A walk that arrives
        // but lands on ground whose strip is likewise saturated starts another walk from the new
        // feet, and another, forever: the goal is chosen blind, so walkable-but-full terrain can
        // repeat indefinitely without ever tripping that counter. Count a saturated ARRIVAL here
        // too and, past the cap, use the notify-and-wait fallback instead of walking again.
        // A walk that stalled was already counted in onTick, so `lastRelocationArrived` gates this
        // increment: without it one failed walk cost two of the budget and tripped the cap early.
        // findPosition clears the count the instant a real target appears, so moving somewhere
        // useful recovers on its own.
        if (relocateFailures < MAX_RELOCATE_FAILURES && tryRelocate()) {
            if (lastRelocationArrived) relocateFailures++;
            lastRelocationArrived = false;
            return;
        }
        if (!stripSaturatedNotified) {
            if (debug.get()) info("§eNo free cell left in the strip - move somewhere new.");
            stripSaturatedNotified = true;
        }
        delayTicks = 20;
    }

    /**
     * Starts a straight-line walk to fresh ground. Called only with the placement cycle idle
     * (nothing swapped, no ice in flight), so walking mid-cycle is impossible.
     *
     * @return true if a walk is now wanted (started or already under way)
     */
    private boolean tryRelocate() {
        if (relocating) return true;
        initialRelocateDone = true;

        Direction side = stripAxis.getClockWise();
        BlockPos feet = mc.player.blockPosition();
        boolean backwards = moveMode.get() == MoveMode.Backwards
            || (moveMode.get() == MoveMode.Alternate && relocateMoves % 2 == 0);

        BlockPos goal;
        if (backwards) {
            // Retreat by roughly one strip's worth of depth, so the walk buys a full strip
            // of fresh cells instead of a single row. The ground behind decides how far the
            // walk can actually go.
            int retreat = Math.max(1, (int) Math.floor(range.get() - minPlaceDistance.get()));
            goal = walkGoal(feet, stripAxis.getOpposite(), retreat);
        } else {
            goal = walkGoal(feet, side, stripWidth.get());
        }

        // In Alternate mode a direction that comes back empty is blocked, so advance the
        // direction-selection state before failing - otherwise the next retry re-picks the
        // same blocked direction. Re-evaluate the other way first; if it is empty too, fall
        // through to the failure path below unchanged.
        if (goal == null && moveMode.get() == MoveMode.Alternate) {
            relocateMoves++;
            backwards = relocateMoves % 2 == 0;
            if (backwards) {
                int retreat = Math.max(1, (int) Math.floor(range.get() - minPlaceDistance.get()));
                goal = walkGoal(feet, stripAxis.getOpposite(), retreat);
            } else {
                goal = walkGoal(feet, side, stripWidth.get());
            }
        }

        if (goal == null) return false;   // relocateOrRetry falls back to its notify path

        relocateStep = Math.abs(goal.getX() - feet.getX()) + Math.abs(goal.getZ() - feet.getZ());
        relocateMoves++;
        relocateGoal = goal;
        relocateWaitTicks = 0;
        relocating = true;
        return true;
    }

    /**
     * The furthest cell along the walk direction that the player can actually walk to. The
     * ground behind the player decides how far the walk goes: a rise the player cannot climb
     * or a wall ends it, because the walk is a straight line and the columns beyond it are
     * unreachable anyway. A drop does not end it, the walk just steps down.
     */
    private BlockPos walkGoal(BlockPos feet, Direction direction, int distance) {
        BlockPos goal = null;
        BlockPos previous = feet;              // the ground the last accepted step stood on
        for (int d = 1; d <= distance; d++) {
            BlockPos ground = walkableGround(previous, feet.relative(direction, d));
            if (ground == null) break;
            goal = ground;
            previous = ground;
        }
        return goal;
    }

    /**
     * The cell in a column the player can walk to: air or water with solid support below, room
     * for the player's head above it, and at most one block above the ground the previous step
     * stood on (the walk jumps the last block). The height is measured against the previous step,
     * so a staircase of one block rises is walkable while a two block step is not. A drop of more
     * than two blocks is a fall, not a walk.
     */
    private BlockPos walkableGround(BlockPos previous, BlockPos column) {
        if (mc.level == null) return null;
        for (int y = previous.getY() + 1; y >= previous.getY() - 2; y--) {
            BlockPos test = new BlockPos(column.getX(), y, column.getZ());
            if (test.getY() < mc.level.getMinY() || test.getY() > mc.level.getMaxY()) continue;
            BlockPos belowTest = test.below();
            BlockPos head = test.above();
            BlockState testState = mc.level.getBlockState(test);
            // Accept air OR water. Water has no collision shape (LiquidBlock.getCollisionShape
            // resolves to Blocks.AIR's empty shape) so the player walks straight through it, and
            // it is the target medium for this module anyway; this matches the cell test
            // findPosition already uses. Lava is still rejected: no collision either, but
            // walking into it kills you. Support below must be solid.
            //
            // The cell above the feet cell has to be free as well: the player is 1.8 blocks
            // tall, so a one block overhang passes the ground test but the player cannot walk
            // into it. The goal would then be a cell the walk never reaches, and the
            // relocation stalls for RELOCATE_WAIT_TICKS and counts as a failure. The test is
            // the collision shape and not isCollisionShapeFullBlock: a slab or a stair above
            // the head blocks the player just as much as a full block does.
            if (mc.level.getBlockState(belowTest).isCollisionShapeFullBlock(mc.level, belowTest)
                && (testState.isAir() || testState.getBlock() == Blocks.WATER)
                && mc.level.getBlockState(head).getCollisionShape(mc.level, head).isEmpty()) {
                return test;
            }
        }
        return null;
    }

    /**
     * Straight-line walk toward the relocate goal: face it, hold forward, jump when it is above
     * us and close enough to clear.
     *
     * <p>Called every tick while relocating so the view keeps tracking the goal; a one-shot
     * version would walk off-axis as soon as the player's position changes.
     */
    private void walkToRelocateGoal() {
        if (mc.player == null || relocateGoal == null) return;

        Vec3 target = Vec3.atCenterOf(relocateGoal);
        double dx = target.x - mc.player.getX();
        double dy = target.y - mc.player.getY();
        double dz = target.z - mc.player.getZ();
        double horiz = DistanceUtil.distanceXZ(
            mc.player.getX(), mc.player.getZ(), target.x, target.z);

        mc.player.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90));
        if (horiz > 1.0E-4) {
            mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, horiz)));
        }

        mc.options.keyUp.setDown(true);
        // Jump when there is a step right in front of us - that is what a staircase of one block
        // rises looks like - or when the goal is above us and close enough that a jump can reach
        // it. The step case must not depend on the goal's height: a path that steps up and then
        // back down has a goal below us and still has to clear the step.
        mc.options.keyJump.setDown(stepAhead() || (dy > 0.8 && horiz < 2.0));
    }

    /**
     * True when the block right in front of the player is a step it has to jump.
     */
    private boolean stepAhead() {
        if (mc.player == null || mc.level == null) return false;
        BlockPos front = mc.player.blockPosition().relative(mc.player.getDirection());
        for (BlockPos pos : new BlockPos[]{front, front.above()}) {
            if (mc.level.getBlockState(pos).isCollisionShapeFullBlock(mc.level, pos)) return true;
        }
        return false;
    }

    /**
     * Releases the held movement keys. Every walk exit path must call this.
     */
    private void stopWalking() {
        if (mc.options == null) return;
        mc.options.keyUp.setDown(false);
        // The walk can hold jump; leaving it down would bunny-hop the player through the field
        // long after the walk ended.
        mc.options.keyJump.setDown(false);
    }

    private boolean relocateArrived() {
        if (relocateGoal == null) return true;
        // Accept stopping one block short of the goal, but never "arrived" without having
        // moved at all - a 1-block sideways goal would otherwise pass instantly.
        int tol = relocateStep >= 2 ? 1 : 0;
        int dx = Math.abs(mc.player.getBlockX() - relocateGoal.getX());
        int dz = Math.abs(mc.player.getBlockZ() - relocateGoal.getZ());
        return dx <= tol && dz <= tol;
    }

    private void clearRelocation() {
        relocating = false;
        stopWalking();
        relocateGoal = null;
        relocateWaitTicks = 0;
    }

    private int iceCursor = 0;
    /**
     * The cell the ice phase is aiming at, so the aim is requested once per cell.
     */
    private int aimedIceCell = -1;
    /**
     * The cell the ghast phase is aiming at, so the aim is requested once per cell.
     */
    private int aimedGhastCell = -1;

    private void executePlaceIceBatch() {
        if (targetCells.isEmpty()) {
            stage = Stage.IDLE;
            return;
        }
        int idx = iceCursor;
        if (idx >= targetCells.size()) {
            icePresent = new boolean[targetCells.size()];
            iceSent = new boolean[targetCells.size()];
            startBatchWait();
            stage = Stage.WAIT_ICE_BATCH;
            iceCursor = 0;
            return;
        }
        BlockPos pos = targetCells.get(idx);
        BlockPos below = supportCells.get(idx);
        Vec3 hitVec = Vec3.atCenterOf(below).add(0, 0.5, 0);
        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, below, false);
        // The ice can run out in the middle of a batch; other hotbar slots may still hold ice.
        // Re-resolve from the remaining hotbar stock before falling back to the exhaustion path,
        // which keeps the cells that already have ice and rejects the rest.
        if (mc.player.getInventory().getItem(iceSlot).isEmpty()) {
            int replen = findSlot(s -> s.getItem() == Items.ICE);
            if (replen == -1) {
                if (debug.get()) info("§cOut of ice, keeping the cells that already have ice.");
                retainIcedCells();
                return;
            }
            iceSlot = replen;
        }
        InvUtils.swap(iceSlot, true);
        // Aim at this cell before placing on it. A rotation lands on the next movement
        // packet, so the placement has to wait a tick for it - placing in the same tick
        // would go out with the previous aim.
        if (rotate.get() && aimedIceCell != idx) {
            Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec));
            aimedIceCell = idx;
            return;
        }
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        mc.player.swing(InteractionHand.MAIN_HAND);
        iceCursor++;
        aimedIceCell = -1;
        delayTicks = 1;
    }

    /**
     * Starts the timeout of a batch phase that waits for something. The deadline is in elapsed
     * client ticks, not in handler ticks: the delay ticks skip the stage handler, but the player
     * spent them waiting.
     */
    private void startBatchWait() {
        batchDeadline = clientTicks + batchTimeout.get();
    }

    private void checkIceBatch() {
        for (int i = 0; i < targetCells.size(); i++) {
            if (mc.level.getBlockState(targetCells.get(i)).getBlock() == Blocks.ICE) icePresent[i] = true;
        }
        if (clientTicks > batchDeadline) {
            retainIcedCells();
            return;
        }
        boolean all = true;
        for (boolean b : icePresent)
            if (!b) {
                all = false;
                break;
            }
        if (all) {
            iceCursor = 0;
            // The mining phase gets its own deadline: the ice wait's one may already be
            // spent, and the mining phase would end before it mined anything.
            startBatchWait();
            iceSent = new boolean[targetCells.size()];
            stage = Stage.MINE_ICE_BATCH;
        } else {
            delayTicks = 2;
        }
    }

    /**
     * Keeps the cells whose ice is there and rejects the rest, then continues with the mining
     * phase. The ice wait and the "out of ice" path share it, so a batch that runs out of ice
     * does not sit in the wait for blocks that will never appear.
     */
    private void retainIcedCells() {
        List<BlockPos> keep = new ArrayList<>();
        List<BlockPos> keepSup = new ArrayList<>();
        for (int i = 0; i < targetCells.size(); i++) {
            boolean iced = mc.level.getBlockState(targetCells.get(i)).getBlock() == Blocks.ICE;
            if (iced) {
                keep.add(targetCells.get(i));
                keepSup.add(supportCells.get(i));
            } else {
                rejectedTargets.add(targetCells.get(i).asLong());
            }
        }
        targetCells = keep;
        supportCells = keepSup;
        if (targetCells.isEmpty()) {
            abortBatch();
            return;
        }
        icePresent = new boolean[targetCells.size()];
        for (int i = 0; i < icePresent.length; i++) icePresent[i] = true;
        iceSent = new boolean[targetCells.size()];
        iceCursor = 0;
        startBatchWait();
        stage = Stage.MINE_ICE_BATCH;
    }

    private void executeMineIceBatch() {
        InvUtils.swap(pickSlot, true);

        // The server breaks one block at a time: it keeps a single destroy position and a
        // single delayed destroy, and a second pair in the same tick overwrites the first
        // one. So only one cell is in flight - its pair goes out, and the next cell only
        // starts once that one is gone from the world.
        int target = -1;
        boolean completed = false;
        for (int i = 0; i < targetCells.size(); i++) {
            if (!icePresent[i]) continue;
            if (mc.level.getBlockState(targetCells.get(i)).getBlock() != Blocks.ICE) {
                // The ice is gone: the cell either became water or was removed.
                icePresent[i] = false;
                completed = true;
                continue;
            }
            target = i;
            break;
        }

        // Every cell gets its own window: a batch is one cell per tick now, so a single
        // deadline for the whole batch would drop the cells past it.
        if (completed) startBatchWait();

        // A cell whose break packets went out but whose ice is still there when its window
        // ends is given up on, and the mining continues with the next iced cell. The packets
        // are not resent - they are not a promise, the server can refuse them - so without
        // this the loop kept picking the same stuck cell on every tick until the whole
        // batch timed out, and every cell behind it lost its ice: the batch abandoned ice
        // in the field.
        if (target >= 0 && iceSent[target] && clientTicks > batchDeadline) {
            icePresent[target] = false;
            startBatchWait();
            delayTicks = 1;
            return;
        }

        if (target < 0) {
            // Every cell lost its ice. The water phase decides which cells are usable, from
            // the world state.
            delayTicks = 2;
            startBatchWait();
            stage = Stage.WAIT_WATER_BATCH;
            return;
        }

        if (!iceSent[target]) {
            // The break packets are not a promise: the server can refuse them. A cell whose
            // packets already went out is only done once the world says the ice is gone.
            BlockPos p = targetCells.get(target);
            ((com.AutoBookshelf.addon.mixin.accessor.MultiPlayerGameModeAccessor) mc.gameMode)
                .invokeStartPrediction(mc.level, seq -> new net.minecraft.network.protocol.game.ServerboundPlayerActionPacket(
                    net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, p, Direction.UP, seq));
            ((com.AutoBookshelf.addon.mixin.accessor.MultiPlayerGameModeAccessor) mc.gameMode)
                .invokeStartPrediction(mc.level, seq -> new net.minecraft.network.protocol.game.ServerboundPlayerActionPacket(
                    net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, p, Direction.UP, seq));
            mc.player.swing(InteractionHand.MAIN_HAND);
            iceSent[target] = true; // packets sent, waiting for the world to catch up
        }

        delayTicks = 1;
        stage = Stage.MINE_ICE_BATCH;
    }

    private void checkWaterBatch() {
        boolean allWater = true;
        for (int i = 0; i < targetCells.size(); i++) {
            if (mc.level.getBlockState(targetCells.get(i)).getBlock() != Blocks.WATER) {
                allWater = false;
                break;
            }
        }
        if (allWater) {
            iceCursor = 0;
            InvUtils.swapBack();
            InvUtils.swap(ghastSlot, true);
            if (rotate.get() && !targetCells.isEmpty()) {
                Vec3 hitVec = Vec3.atCenterOf(supportCells.get(0)).add(0, 0.5, 0);
                Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec));
                delayTicks = 2;
            }
            // The flags have to exist before the placement phase: the out of stock path finishes
            // the batch, and it reads them.
            ghastPlaced = new boolean[targetCells.size()];
            stage = Stage.PLACE_GHAST_BATCH;
            return;
        }
        if (clientTicks > batchDeadline) {
            // One cell that never became water must not throw away the cells that did: the ice
            // phase already keeps the cells that worked, and the water phase does the same.
            List<BlockPos> keep = new ArrayList<>();
            List<BlockPos> keepSup = new ArrayList<>();
            for (int i = 0; i < targetCells.size(); i++) {
                if (mc.level.getBlockState(targetCells.get(i)).getBlock() == Blocks.WATER) {
                    keep.add(targetCells.get(i));
                    keepSup.add(supportCells.get(i));
                } else {
                    rejectedTargets.add(targetCells.get(i).asLong());
                }
            }
            targetCells = keep;
            supportCells = keepSup;
            if (targetCells.isEmpty()) {
                abortBatch();
                return;
            }
            ghastPlaced = new boolean[targetCells.size()];
            iceCursor = 0;
                stage = Stage.PLACE_GHAST_BATCH;
            return;
        }
        delayTicks = 2;
    }

    private void executePlaceGhastBatch() {
        if (iceCursor >= targetCells.size()) {
            ghastPlaced = new boolean[targetCells.size()];
            startBatchWait();
            stage = Stage.WAIT_GHAST_BATCH;
            iceCursor = 0;
            return;
        }
        BlockPos pos = targetCells.get(iceCursor);
        BlockPos below = supportCells.get(iceCursor);
        Vec3 hitVec = Vec3.atCenterOf(below).add(0, 0.5, 0);
        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, below, false);
        // The ghasts can run out in the middle of a batch; other hotbar slots may still hold
        // dried ghasts. Re-resolve from the remaining hotbar stock before falling back to the
        // exhaustion path, which keeps the already-placed cells and rejects the rest. The cell
        // placed a tick ago is already there: useItemOn applies the placement to the client's
        // own world inside the prediction.
        if (mc.player.getInventory().getItem(ghastSlot).isEmpty()) {
            int replen = findSlot(s -> s.getItem() == Items.DRIED_GHAST);
            if (replen == -1) {
                if (debug.get()) info("§cOut of dried ghasts, keeping the cells that already have one.");
                finishGhastBatch();
                return;
            }
            ghastSlot = replen;
        }
        InvUtils.swap(ghastSlot, true);
        // Aim at this cell before placing on it, the same way the ice phase does.
        if (rotate.get() && aimedGhastCell != iceCursor) {
            Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec));
            aimedGhastCell = iceCursor;
            return;
        }
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        mc.player.swing(InteractionHand.MAIN_HAND);
        iceCursor++;
        aimedGhastCell = -1;
        delayTicks = 1;
    }

    /**
     * Counts the cells that got a ghast, rejects the rest and moves on to the cleanup.
     */
    private void finishGhastBatch() {
        refreshGhastPlaced();
        int n = 0;
        for (int i = 0; i < targetCells.size(); i++) {
            if (ghastPlaced[i]) n++;
            else rejectedTargets.add(targetCells.get(i).asLong());
        }
        placedCount += n;
        stage = Stage.FINISH_BATCH;
    }

    private void checkGhastBatch() {
        // The flags have to be refreshed from the world before the completion test: the placement
        // phase leaves them all false, so a test against the stale array would never see a
        // finished batch and every batch would wait out the timeout.
        refreshGhastPlaced();
        boolean all = true;
        for (boolean b : ghastPlaced)
            if (!b) {
                all = false;
                break;
            }
        if (all || clientTicks > batchDeadline) {
            finishGhastBatch();
            return;
        }
        delayTicks = 2;
    }

    /**
     * Marks the cells that hold a dried ghast block, from the world's current state.
     */
    private void refreshGhastPlaced() {
        if (ghastPlaced == null) return;
        for (int i = 0; i < targetCells.size() && i < ghastPlaced.length; i++) {
            // Assign both outcomes: the client can roll back a predicted placement (server rejects
            // it), and a stale true would inflate placedCount and pass the completion test anyway.
            ghastPlaced[i] = mc.level.getBlockState(targetCells.get(i)).getBlock() == driedGhastBlock;
        }
    }

    private void finishBatch() {
        InvUtils.swapBack();
        targetCells.clear();
        supportCells.clear();
        icePresent = null;
        iceSent = null;
        ghastPlaced = null;
        aimedIceCell = -1;
        aimedGhastCell = -1;
        iceCursor = 0;
        delayTicks = placeDelay.get();
        stage = Stage.IDLE;
    }

    private void abortBatch() {
        for (BlockPos p : targetCells) rejectedTargets.add(p.asLong());
        InvUtils.swapBack();
        targetCells.clear();
        supportCells.clear();
        icePresent = null;
        iceSent = null;
        ghastPlaced = null;
        aimedIceCell = -1;
        aimedGhastCell = -1;
        iceCursor = 0;
        stage = Stage.IDLE;
        delayTicks = 20;
    }

    private int findSlot(java.util.function.Predicate<ItemStack> filter) {
        for (int i = 0; i < 9; i++) if (filter.test(mc.player.getInventory().getItem(i))) return i;
        return -1;
    }

    /**
     * Dried ghasts currently in the hotbar; this module only sources them from there.
     */
    private int countDriedGhasts() {
        int total = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == Items.DRIED_GHAST) total += stack.getCount();
        }
        return total;
    }
    private boolean isValidPickaxe(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (!stack.is(ItemTags.PICKAXES)) return false;
        Identifier silkTouchId = Enchantments.SILK_TOUCH.identifier();
        ItemEnchantments ench = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        for (var entry : ench.entrySet())
            if (entry.getKey().unwrapKey().map(key -> key.identifier().equals(silkTouchId)).orElse(false)) return false;
        return true;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || targetCells.isEmpty()) return;
        event.renderer.box(targetCells.get(0), sideColor.get(), lineColor.get(), ShapeMode.Both, 0);
    }
}
