/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalXZ;
import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.utils.DistanceUtil;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AutoBeacon extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgControls = settings.createGroup("Controls");
    private final SettingGroup sgPlacement = settings.createGroup("Placement");
    private final SettingGroup sgBaritone = settings.createGroup("Baritone");

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Print debug messages in chat.")
        .defaultValue(false)
        .build()
    );

    public enum OriginMode { PlayerPosition, SelectedBlock }

    private final Setting<OriginMode> originMode = sgControls.add(new EnumSetting.Builder<OriginMode>()
        .name("origin-mode")
        .description("How to set the build location.")
        .defaultValue(OriginMode.SelectedBlock)
        .build());

    private final Setting<Keybind> setOriginKey = sgControls.add(new KeybindSetting.Builder()
        .name("set-origin-key")
        .description("Key to set the build origin to the block you are looking at.")
        .defaultValue(Keybind.fromKey(-1))
        .visible(() -> originMode.get() == OriginMode.SelectedBlock)
        .build());

    private final Setting<Keybind> buildKey = sgControls.add(new KeybindSetting.Builder()
        .name("build-key")
        .description("Key to start building the pyramid after origin is set.")
        .defaultValue(Keybind.fromKey(-1))
        .visible(() -> originMode.get() == OriginMode.SelectedBlock)
        .build());

    private final Setting<Boolean> preview = sgRender.add(new BoolSetting.Builder()
        .name("preview")
        .description("Preview the planned pyramid before building starts.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> verticalOffset = sgGeneral.add(new IntSetting.Builder()
        .name("vertical-offset")
        .description("How many blocks above the origin the bottom layer will start.")
        .defaultValue(1).min(0).max(10)
        .build());

    private final Setting<Boolean> replaceGrass = sgPlacement.add(new BoolSetting.Builder()
        .name("replace-grass")
        .description("Replace grass, tall grass, seagrass, ferns, etc.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> useBaritone = sgBaritone.add(new BoolSetting.Builder()
        .name("use-baritone")
        .description("Use Baritone to move to out-of-reach block positions.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> jumpWhenStuck = sgBaritone.add(new BoolSetting.Builder()
        .name("jump-when-stuck")
        .description("Jump and re-path after 5 seconds without a verified placement.")
        .defaultValue(true)
        .build());

    private final Setting<List<Block>> allowedBlocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("allowed-blocks")
        .description("Which mineral blocks to use (iron, gold, emerald, diamond, netherite).")
        .defaultValue(Blocks.IRON_BLOCK, Blocks.GOLD_BLOCK, Blocks.EMERALD_BLOCK,
            Blocks.DIAMOND_BLOCK, Blocks.NETHERITE_BLOCK)
        .build());

    private final Setting<Integer> delayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Ticks between each block placement.")
        .defaultValue(2)
        .min(0)
        .max(10)
        .build());

    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("How many blocks to place per tick.")
        .defaultValue(1).min(1).max(5)
        .build());

    private final Setting<Boolean> placeBeacons = sgGeneral.add(new BoolSetting.Builder()
        .name("place-beacons")
        .description("Place four beacons on top after building the pyramid.")
        .defaultValue(true)
        .build());

    /**
     * Which part of the build forces the player to sneak, suppressing any interactive-block
     * GUI (beacon, chest, ...) that would otherwise open from the click face.
     */
    public enum SneakLockScope {BeaconPlacement, EntireBuild}

    private final Setting<Boolean> sneakLock = sgGeneral.add(new BoolSetting.Builder()
        .name("sneak-lock")
        .description("Force the player to sneak so placement can't open nearby interactive-block accidentally.")
        .defaultValue(true)
        .build());

    private final Setting<SneakLockScope> sneakLockScope = sgGeneral.add(new EnumSetting.Builder<SneakLockScope>()
        .name("sneak-lock-scope")
        .description("Which portion of the build to sneak: the 4 beacon placement only, or the whole pyramid + beacon placement.")
        .defaultValue(SneakLockScope.BeaconPlacement)
        .visible(() -> sneakLock.get())
        .build());

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Render the remaining blocks.")
        .defaultValue(true)
        .build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .defaultValue(ShapeMode.Both)
        .build());

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .defaultValue(new SettingColor(255, 255, 255, 20))
        .build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .defaultValue(new SettingColor(255, 255, 255, 200))
        .build());

    private final Setting<SettingColor> previewColor = sgRender.add(new ColorSetting.Builder()
        .name("preview-color")
        .description("Color of the preview boxes before building starts.")
        .defaultValue(new SettingColor(0, 255, 0, 70)).visible(preview::get)
        .build());


    private BlockPos origin = null;
    private List<BlockPos> blocksToPlace = new ArrayList<>();
    private List<BlockPos> beaconsToPlace = new ArrayList<>();
    private List<BlockPos> remainingBlocks = new ArrayList<>();
    /**
     * Neighbor block state only — whether a cell is non-replaceable (usable as a support).
     * Reach verdicts are re-evaluated per scan so a moving player re-checks against the
     * current eye position; this cache only removes the getBlockState call.
     */
    private final Map<Long, Boolean> neighborBlockCache = new HashMap<>();
    private boolean placingBeacons = false;
    private boolean building = false;
    /**
     * Whether this module forced the sneak on, and the pre-lock states to restore later.
     * Both the key mapping (which drives the sneak flag on the wire) and the local player
     * field (which drives the client-side pose/prediction) are forced, so the module
     * actually sends ServerboundPlayerInputPacket(shift=true) for placements.
     */
    private boolean sneakLockActive = false;
    private boolean sneakLockPreviousPlayer = false;
    private boolean sneakLockPreviousKey = false;
    /**
     * Target a Baritone goal was last issued for, and ticks before re-issuing the same goal.
     */
    private long lastPathTargetKey = -1;
    private int repathCooldown = 0;
    /**
     * Throttles the "everything is blocked" warning and the identical no-progress repath.
     */
    private int blockedWarnCooldown = 0;
    private boolean originSet = false;
    private int placeTimer = 0;
    private boolean waitingForBaritone = false;
    private BlockPos currentTargetPos = null; // the perimeter approach position, not the block to place
    private IBaritone baritone = null;

    private BlockPos pendingVerification = null;
    private Block preferredBlock = null;

    private int currentLayer = 0;
    private int baseY = 0;
    private int noProgressTicks = 0;
    private int cachedBlockSlot = -1;
    private int cachedBeaconSlot = -1;

    public AutoBeacon() {
        super(Addon.CATEGORY, "Auto-Beacon", "Builds a 4-beacon pyramid at a selected location.");
    }

    @Override
    public void onActivate() {
        resetState();
        try {
            baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        } catch (Exception e) {
            error("Baritone not available!");
            useBaritone.set(false);
        }
        if (originMode.get() == OriginMode.PlayerPosition) {
            origin = mc.player.blockPosition();
            initBuild();
        }
    }

    /**
     * Throttled notice when every remaining cell is blocked (by an entity or by being
     * otherwise unreachable). Called from both no-target branches; the counter keeps it
     * to one line every 200 ticks so the chat is not flooded while the mob stands still.
     */
    private void warnBlockedComplete() {
        if (blockedWarnCooldown > 0) return;
        if (debug.get()) info("All remaining blocks are blocked or unreachable; waiting for the obstruction to clear.");
        blockedWarnCooldown = 200;
    }

    /**
     * Repath-debounced Baritone request: does not re-issue a goal for the same fallback
     * target within repathCooldown ticks. Sets waitingForBaritone if the target is already
     * in-flight and returns false so callers know a goal was not posted this tick.
     */
    private boolean requestBaritonePath(BlockPos fallback) {
        long key = fallback.asLong();
        if (lastPathTargetKey == key && repathCooldown > 0) {
            waitingForBaritone = true;
            return false;
        }
        BlockPos approach = findApproachPositionForBlock(fallback);
        waitingForBaritone = true;
        currentTargetPos = approach;
        lastPathTargetKey = key;
        repathCooldown = 40;
        baritone.getCustomGoalProcess().setGoalAndPath(new GoalXZ(approach.getX(), approach.getZ()));
        return true;
    }

    /**
     * Applies sneak while the build phase that wants it is active, and releases it when the
     * phase ends or the module resets. The release restores the pre-lock sneak state, so a
     * user who was already sneaking is not forced out, and a user who was not is not left
     * sneaking afterwards.
     */
    private void refreshSneakLock() {
        boolean want = sneakLock.get() && building
            && (sneakLockScope.get() == SneakLockScope.EntireBuild || placingBeacons);
        if (want == this.sneakLockActive) return;
        if (mc.player == null) {
            setSneakLock(false);
            return;
        }
        if (want) {
            setSneakLock(true);
        } else {
            setSneakLock(false);
        }
    }

    /**
     * Engages/releases the sneak using the key mapping (wire) and the player field.
     */
    private void setSneakLock(boolean on) {
        if (on) {
            this.sneakLockPreviousKey = mc.options != null && mc.options.keyShift.isDown();
            this.sneakLockPreviousPlayer = mc.player != null && mc.player.isShiftKeyDown();
            if (mc.options != null) mc.options.keyShift.setDown(true);
            if (mc.player != null) mc.player.setShiftKeyDown(true);
            this.sneakLockActive = true;
        } else {
            if (mc.options != null) mc.options.keyShift.setDown(this.sneakLockPreviousKey);
            if (mc.player != null) mc.player.setShiftKeyDown(this.sneakLockPreviousPlayer);
            this.sneakLockActive = false;
        }
    }

    /**
     * Force-unlock sneak, restoring the pre-lock state; used on reset paths.
     */
    private void forceSneakLockOff() {
        if (!this.sneakLockActive) return;
        setSneakLock(false);
    }

    private void resetState() {
        blocksToPlace.clear();
        beaconsToPlace.clear();
        remainingBlocks.clear();
        placingBeacons = false;
        building = false;
        // Drop the sneak-lock too, or toggle-off mid-build leaves the player visually sneaking.
        forceSneakLockOff();
        originSet = false;
        placeTimer = 0;
        waitingForBaritone = false;
        currentTargetPos = null;
        origin = null;
        pendingVerification = null;
        preferredBlock = null;
        currentLayer = 0;
        noProgressTicks = 0;
        lastPathTargetKey = -1;
        repathCooldown = 0;
        blockedWarnCooldown = 0;
        cachedBlockSlot = -1;
        cachedBeaconSlot = -1;
        neighborBlockCache.clear();
    }

    @Override
    public void onDeactivate() {
        if (baritone != null) baritone.getPathingBehavior().cancelEverything();
        resetState();
    }

    private void initBuild() {
        baseY = origin.getY() + verticalOffset.get();
        generateBlockList();
        originSet = true;
        building = true;
        currentLayer = 0;
        remainingBlocks.clear();
        remainingBlocks.addAll(getBlocksForLayer(currentLayer));
        if (debug.get()) info("Starting layer 1 (Y=" + baseY + ").");
    }

    private List<BlockPos> getBlocksForLayer(int layer) {
        List<BlockPos> layerBlocks = new ArrayList<>();
        int y = baseY + layer;
        int offset = layer;
        int startX = origin.getX() - 3 + offset;
        int endX = origin.getX() + 6 - offset;
        int startZ = origin.getZ() - 3 + offset;
        int endZ = origin.getZ() + 6 - offset;
        for (int x = startX; x <= endX; x++)
            for (int z = startZ; z <= endZ; z++)
                layerBlocks.add(new BlockPos(x, y, z));
        return layerBlocks;
    }

    private BlockPos findApproachPositionForBlock(BlockPos target) {
        int off = currentLayer; // layer 0 = widest, layer 3 = narrowest
        int x0 = origin.getX() - 3 + off;
        int x1 = origin.getX() + 6 - off;
        int z0 = origin.getZ() - 3 + off;
        int z1 = origin.getZ() + 6 - off;
        int midX = (x0 + x1) / 2;
        int midZ = (z0 + z1) / 2;
        int y = target.getY();

        // 8 positions 2 blocks outside the layer perimeter
        BlockPos[] candidates = {
            new BlockPos(x0 - 2, y, z0 - 2),  // SW corner
            new BlockPos(x1 + 2, y, z0 - 2),  // SE corner
            new BlockPos(x0 - 2, y, z1 + 2),  // NW corner
            new BlockPos(x1 + 2, y, z1 + 2),  // NE corner
            new BlockPos(midX, y, z0 - 2),     // S edge mid
            new BlockPos(midX, y, z1 + 2),     // N edge mid
            new BlockPos(x0 - 2, y, midZ),     // W edge mid
            new BlockPos(x1 + 2, y, midZ),     // E edge mid
        };

        // Pick the candidate with the smallest XZ distance to the target block.
        // XZ-only comparison because GoalXZ ignores Y.
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos c : candidates) {
            double d = DistanceUtil.distanceSqXZ(c.getX(), c.getZ(), target.getX(), target.getZ());
            if (d < bestDist) {
                bestDist = d;
                best = c;
            }
        }
        return best != null ? best : target;
    }

    private boolean baritoneArrived() {
        // Primary: can we now reach something to place? If yes, we're done navigating.
        if (findNextPlaceable() != null) return true;

        // Secondary: did Baritone stop and are we within 2 blocks XZ of the approach?
        if (currentTargetPos == null) return true;
        int dx = Math.abs(mc.player.getBlockX() - currentTargetPos.getX());
        int dz = Math.abs(mc.player.getBlockZ() - currentTargetPos.getZ());
        return dx <= 2 && dz <= 2;
    }

    @EventHandler
    private void onTickPre(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;
        // Sneak must be on the wire BEFORE the player ticks, because KeyboardInput.tick()
        // builds the ServerboundPlayerInputPacket from keyShift.isDown() during LocalPlayer.tick(),
        // which runs before TickEvent.Post. Setting it here ensures the server sees shift=true
        // on the same tick the placement packet goes out (inside the Rotations.rotate callback).
        refreshSneakLock();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null) return;

        if (repathCooldown > 0) repathCooldown--;
        if (blockedWarnCooldown > 0) blockedWarnCooldown--;

        if (originMode.get() == OriginMode.SelectedBlock) {
            if (!originSet && setOriginKey.get().isPressed()) {
                if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK) {
                    BlockHitResult hit = (BlockHitResult) mc.hitResult;
                    origin = hit.getBlockPos();
                    baseY = origin.getY() + verticalOffset.get();
                    generateBlockList();
                    originSet = true;
                    if (debug.get()) info("Build origin set to " + origin.getX() + ", " + origin.getY() + ", " + origin.getZ() +
                        " (bottom layer starts " + verticalOffset.get() + " blocks above).");
                    if (debug.get()) info("Press your build key to start constructing.");
                } else {
                    error("You must look at a block to set origin.");
                }
                return;
            }
            if (originSet && !building && buildKey.get().isPressed()) {
                initBuild();
                return;
            }
        }

        if (!building) return;

        // Periodic cleanup of already-placed blocks
        if (mc.level.getGameTime() % 100 == 0) {
            remainingBlocks.removeIf(pos -> isBlockCorrectAtPosition(mc.level.getBlockState(pos)));
            // Neighbor block-state entries changed too; the next scan reloads them.
            neighborBlockCache.clear();
        }

        // Verify previous placement
        if (pendingVerification != null) {
            if (isBlockCorrectAtPosition(mc.level.getBlockState(pendingVerification)))
                remainingBlocks.remove(pendingVerification);
            pendingVerification = null;
            // Our placement changed the neighbor set; cached verdicts are stale.
            neighborBlockCache.clear();
            placeTimer = delayTicks.get();
            return;
        }

        if (waitingForBaritone && baritone != null && useBaritone.get()) {
            // Pausing to path is legitimate progress, not a wedge: reset so this
            // does not fire the unstuck branch mid-navigation.
            noProgressTicks = 0;
            if (!baritone.getPathingBehavior().isPathing() && baritoneArrived()) {
                waitingForBaritone = false;
                baritone.getPathingBehavior().cancelEverything();
                currentTargetPos = null;
                placeTimer = 0;
            }
            return;
        }

        // Advance on "no verified progress", not on a placeTimer clock: resetting this
        // in the verify / placement paths above leaves it growing whenever the module
        // is effectively wedged, including the player standing inside the built cells.
        noProgressTicks++;
        if (building && noProgressTicks >= 100) {
            if (jumpWhenStuck.get() && mc.player.onGround()) mc.player.jumpFromGround();
            BlockPos fallback = findNearestClearTarget();
            if (useBaritone.get() && baritone != null && fallback != null && !waitingForBaritone) {
                if (requestBaritonePath(fallback)) {
                    if (debug.get()) info("No progress; pathing toward the nearest clear cell.");
                }
            } else if (fallback == null) {
                warnBlockedComplete();
            }
            noProgressTicks = 0;
        }

        if (placeTimer > 0) {
            placeTimer--;
            return;
        }

        if (!placingBeacons) {
            boolean placedSomething = false;
            for (int i = 0; i < blocksPerTick.get() && !remainingBlocks.isEmpty(); i++) {
                BlockPos target = findNextPlaceable();
                if (target == null) {
                    BlockPos fallback = findNearestClearTarget();
                    if (useBaritone.get() && baritone != null && fallback != null
                        && !waitingForBaritone && requestBaritonePath(fallback)) {
                        if (debug.get()) info("Moving to approach position for block at " + fallback.toShortString());
                        return;
                    }
                    if (fallback == null) {
                        warnBlockedComplete();
                    }
                    break;
                }
                if (placeBlock(target)) {
                    pendingVerification = target;
                    placeTimer = delayTicks.get() + 5;
                    placedSomething = true;
                    noProgressTicks = 0;
                    return;
                } else {
                    return;
                }
            }
            if (placedSomething) return;

            // Layer verification
            if (remainingBlocks.isEmpty()) {
                List<BlockPos> stillMissing = new ArrayList<>();
                for (BlockPos pos : getBlocksForLayer(currentLayer))
                    if (!isBlockCorrectAtPosition(mc.level.getBlockState(pos)))
                        stillMissing.add(pos);

                if (stillMissing.isEmpty()) {
                    currentLayer++;
                    if (currentLayer > 3) {
                        placingBeacons = true;
                        remainingBlocks.clear();
                        remainingBlocks.addAll(beaconsToPlace);
                        if (debug.get()) info("Pyramid done — placing beacons...");
                    } else {
                        remainingBlocks.clear();
                        remainingBlocks.addAll(getBlocksForLayer(currentLayer));
                        if (debug.get()) info("Layer complete, starting layer " + (currentLayer + 1)
                            + " (Y=" + (baseY + currentLayer) + ").");
                    }
                } else {
                    remainingBlocks.clear();
                    remainingBlocks.addAll(stillMissing);
                    if (debug.get()) info("Rechecking layer, " + stillMissing.size() + " blocks still missing.");
                }
            }
        } else {
            // Beacon placement
            for (int i = 0; i < blocksPerTick.get() && !remainingBlocks.isEmpty(); i++) {
                BlockPos target = findNextPlaceable();
                if (target == null) {
                    BlockPos fallback = findNearestClearTarget();
                    if (useBaritone.get() && baritone != null && fallback != null
                        && !waitingForBaritone && requestBaritonePath(fallback)) {
                        if (debug.get()) info("Moving to approach position for beacon at " + fallback.toShortString());
                        return;
                    }
                    if (fallback == null) {
                        warnBlockedComplete();
                    }
                    break;
                }
                if (placeBeacon(target)) {
                    pendingVerification = target;
                    placeTimer = delayTicks.get() + 5;
                    noProgressTicks = 0;
                    return;
                } else {
                    return;
                }
            }
            if (remainingBlocks.isEmpty()) finish();
        }
    }

    private BlockPos findNextPlaceable() {
        for (BlockPos pos : remainingBlocks)
            if (isPlaceableNow(pos) && isPosPlaceableThroughWall(pos)) return pos;
        return null;
    }

    /**
     * Last-resort pick for the no-progress fallback: the closest target the player is
     * not standing in (or that no live entity occupies), even if it's not clickable yet.
     * Pathing to its approach is what actually breaks the wedge.
     */
    private BlockPos findNearestClearTarget() {
        BlockPos playerPos = mc.player.blockPosition();
        double closestDist = Double.MAX_VALUE;
        BlockPos closest = null;
        for (BlockPos pos : remainingBlocks) {
            if (!canPlaceAtPosition(pos) || isCellBlocked(pos)) continue;
            double dist = pos.distSqr(playerPos);
            if (dist < closestDist) {
                closestDist = dist;
                closest = pos;
            }
        }
        // No unobstructed candidate at all: fall back to the plain closest one.
        return closest != null ? closest : findClosestBlock();
    }

    private BlockPos findClosestBlock() {
        BlockPos playerPos = mc.player.blockPosition();
        double closestDist = Double.MAX_VALUE;
        BlockPos closest = null;
        for (BlockPos pos : remainingBlocks) {
            double dist = pos.distSqr(playerPos);
            if (dist < closestDist) {
                closestDist = dist;
                closest = pos;
            }
        }
        return closest;
    }

    private boolean isPlaceableNow(BlockPos pos) {
        if (!canPlaceAtPosition(pos)) return false;
        return !isCellBlocked(pos);
    }

    /**
     * True if the cell intersects an alive entity (players included), so a block placed
     * there would rest on / inside a living hitbox and be rejected or corrupt the build.
     */
    private boolean isCellBlocked(BlockPos pos) {
        AABB box = new AABB(pos);
        for (Entity e : mc.level.getEntities((Entity) null, box, EntitySelector.NO_SPECTATORS)) {
            if (e.isAlive() && box.intersects(e.getBoundingBox())) return true;
        }
        return false;
    }

    private boolean canPlaceAtPosition(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) return true;
        if (!replaceGrass.get()) return false;
        Block block = state.getBlock();
        return block == Blocks.SHORT_GRASS || block == Blocks.TALL_GRASS ||
            block == Blocks.FERN || block == Blocks.SEAGRASS || block == Blocks.TALL_SEAGRASS;
    }

    private boolean isPosPlaceableThroughWall(BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            // The neighbor block state is cacheable; the reach verdict is not -> it depends on
            // where the player is standing right now.
            if (!neighborNonReplaceable(neighbor)) continue;
            if (PlayerUtils.isWithinReach(neighbor)) return true;
        }
        return false;
    }

    private boolean neighborNonReplaceable(BlockPos pos) {
        long key = pos.asLong();
        Boolean cached = neighborBlockCache.get(key);
        if (cached != null) return cached;
        boolean nonReplaceable = !mc.level.getBlockState(pos).canBeReplaced();
        if (neighborBlockCache.size() > 4096) neighborBlockCache.clear();
        neighborBlockCache.put(key, nonReplaceable);
        return nonReplaceable;
    }

    private boolean isBlockCorrectAtPosition(BlockState state) {
        if (state.isAir()) return false;
        Block block = state.getBlock();
        if (block == Blocks.GRASS_BLOCK || block == Blocks.TALL_GRASS ||
            block == Blocks.FERN || block == Blocks.SEAGRASS || block == Blocks.TALL_SEAGRASS)
            return false;
        if (placingBeacons && block == Blocks.BEACON) return true;
        return allowedBlocks.get().contains(block);
    }

    private boolean placeBlock(BlockPos pos) {
        FindItemResult item = findBlockInHotbar();
        if (!item.found()) {
            error("No allowed mineral block in hotbar!");
            toggle();
            return false;
        }
        return placeBlockManually(pos, item);
    }

    private boolean placeBeacon(BlockPos pos) {
        FindItemResult item = findBeaconInInventory();
        if (!item.found()) {
            error("No beacon in inventory!");
            toggle();
            return false;
        }
        return placeBlockManually(pos, item);
    }

    private boolean placeBlockManually(BlockPos pos, FindItemResult item) {
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            BlockState neighborState = mc.level.getBlockState(neighbor);
            if (neighborState.canBeReplaced()) continue;
            if (!PlayerUtils.isWithinReach(neighbor)) continue;

            Vec3 hitPos = Vec3.atCenterOf(neighbor).add(
                dir.getStepX() * 0.5,
                dir.getStepY() * 0.5,
                dir.getStepZ() * 0.5
            );
            BlockHitResult hit = new BlockHitResult(hitPos, dir.getOpposite(), neighbor, false);

            Rotations.rotate(Rotations.getYaw(hitPos), Rotations.getPitch(hitPos), () -> {
                InvUtils.swap(item.slot(), false);
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
                mc.player.swing(InteractionHand.MAIN_HAND);
            });
            return true;
        }
        InvUtils.swap(item.slot(), false);
        boolean result = BlockUtils.place(pos, item, true, 50, true, true);
        return result;
    }

    private FindItemResult findBlockInHotbar() {
        List<Block> allowed = allowedBlocks.get();
        ItemStack mainHand = mc.player.getMainHandItem();
        if (!mainHand.isEmpty() && mainHand.getItem() instanceof BlockItem handBlock) {
            if (allowed.contains(handBlock.getBlock())) {
                preferredBlock = handBlock.getBlock();
                cachedBlockSlot = mc.player.getInventory().getSelectedSlot();
                return new FindItemResult(mc.player.getInventory().getSelectedSlot(), mainHand.getCount());
            }
        }
        if (cachedBlockSlot != -1) {
            ItemStack cached = mc.player.getInventory().getItem(cachedBlockSlot);
            if (!cached.isEmpty() && cached.getItem() instanceof BlockItem cachedBlock
                && allowed.contains(cachedBlock.getBlock())
                && (preferredBlock == null || preferredBlock == cachedBlock.getBlock())) {
                return new FindItemResult(cachedBlockSlot, cached.getCount());
            }
            cachedBlockSlot = -1;
        }
        if (preferredBlock != null) {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = mc.player.getInventory().getItem(i);
                if (!stack.isEmpty() && stack.getItem() instanceof BlockItem bi
                    && bi.getBlock() == preferredBlock)
                    return new FindItemResult(i, stack.getCount());
            }
            FindItemResult result = InvUtils.find(stack ->
                stack.getItem() instanceof BlockItem bi && bi.getBlock() == preferredBlock);
            if (result.found() && !result.isHotbar()) {
                FindItemResult empty = InvUtils.find(ItemStack::isEmpty, 0, 8);
                if (empty.found()) {
                    InvUtils.move().from(result.slot()).toHotbar(empty.slot());
                    return InvUtils.findInHotbar(stack ->
                        stack.getItem() instanceof BlockItem bi && bi.getBlock() == preferredBlock);
                } else {
                    error("Hotbar full, can't pull preferred block.");
                    return new FindItemResult(-1, 0);
                }
            }
            preferredBlock = null;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem bi
                && allowedBlocks.get().contains(bi.getBlock())) {
                preferredBlock = bi.getBlock();
                return new FindItemResult(i, stack.getCount());
            }
        }
        for (Block candidate : allowed) {
            FindItemResult result = InvUtils.find(stack ->
                stack.getItem() instanceof BlockItem bi && bi.getBlock() == candidate);
            if (result.found() && !result.isHotbar()) {
                FindItemResult empty = InvUtils.find(ItemStack::isEmpty, 0, 8);
                if (empty.found()) {
                    InvUtils.move().from(result.slot()).toHotbar(empty.slot());
                    preferredBlock = candidate;
                    return InvUtils.findInHotbar(stack ->
                        stack.getItem() instanceof BlockItem bi && bi.getBlock() == candidate);
                } else {
                    error("Block in inventory but hotbar full.");
                    return new FindItemResult(-1, 0);
                }
            }
        }
        return new FindItemResult(-1, 0);
    }

    private FindItemResult findBeaconInInventory() {
        if (cachedBeaconSlot != -1) {
            ItemStack cached = mc.player.getInventory().getItem(cachedBeaconSlot);
            if (!cached.isEmpty() && cached.getItem() == Items.BEACON)
                return new FindItemResult(cachedBeaconSlot, cached.getCount());
            cachedBeaconSlot = -1;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == Items.BEACON) {
                cachedBeaconSlot = i;
                return new FindItemResult(i, stack.getCount());
            }
        }
        FindItemResult result = InvUtils.find(stack -> stack.getItem() == Items.BEACON);
        if (result.found() && !result.isHotbar()) {
            FindItemResult empty = InvUtils.find(ItemStack::isEmpty, 0, 8);
            if (empty.found()) {
                InvUtils.move().from(result.slot()).toHotbar(empty.slot());
                for (int i = 0; i < 9; i++) {
                    ItemStack stack = mc.player.getInventory().getItem(i);
                    if (!stack.isEmpty() && stack.getItem() == Items.BEACON)
                        return new FindItemResult(i, stack.getCount());
                }
            } else {
                error("Beacon in inventory but hotbar full.");
            }
        }
        return new FindItemResult(-1, 0);
    }

    private void generateBlockList() {
        blocksToPlace.clear();
        beaconsToPlace.clear();
        if (origin == null) return;
        for (int layer = 0; layer <= 3; layer++)
            blocksToPlace.addAll(getBlocksForLayer(layer));
        if (placeBeacons.get()) {
            int beaconY = baseY + 4;
            beaconsToPlace.add(new BlockPos(origin.getX() + 1, beaconY, origin.getZ() + 1));
            beaconsToPlace.add(new BlockPos(origin.getX() + 2, beaconY, origin.getZ() + 1));
            beaconsToPlace.add(new BlockPos(origin.getX() + 1, beaconY, origin.getZ() + 2));
            beaconsToPlace.add(new BlockPos(origin.getX() + 2, beaconY, origin.getZ() + 2));
        }
    }

    private void finish() {
        if (debug.get()) info("Beacon pyramid built successfully!");
        building = false;
        toggle();
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        // isPosPlaceableThroughWall(pos) reads the six neighbours of pos; a neighbour's
        // block-state change can flip all of them. Unconditionally drop the affected verdicts.
        neighborBlockCache.remove(event.pos.asLong());
        for (Direction dir : Direction.values()) {
            neighborBlockCache.remove(event.pos.relative(dir).asLong());
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || !originSet) return;
        if (building) {
            for (BlockPos pos : remainingBlocks)
                event.renderer.box(pos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        } else if (preview.get()) {
            for (BlockPos pos : blocksToPlace)
                event.renderer.box(pos, previewColor.get(), previewColor.get(), shapeMode.get(), 0);
            for (BlockPos pos : beaconsToPlace)
                event.renderer.box(pos, previewColor.get(), previewColor.get(), shapeMode.get(), 0);
        }
    }
}
