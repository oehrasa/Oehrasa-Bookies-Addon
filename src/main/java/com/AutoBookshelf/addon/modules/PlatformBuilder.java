package com.AutoBookshelf.addon.modules;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.utils.AreaSelector;
import com.AutoBookshelf.addon.utils.PlacementEngine;
import com.AutoBookshelf.addon.utils.ShulkerRestockEngine;
import meteordevelopment.meteorclient.events.entity.player.InteractBlockEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.*;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

public class PlatformBuilder extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSelection = settings.createGroup("Selection");
    private final SettingGroup sgSelectionRender = settings.createGroup("Selection Render");
    private final SettingGroup sgPlacement = settings.createGroup("Placement");
    private final SettingGroup sgBlocks = settings.createGroup("Blocks");
    private final SettingGroup sgBaritone = settings.createGroup("Baritone");
    private final SettingGroup sgRestock = settings.createGroup("Restock");

    private final AreaSelector areaSelector = new AreaSelector(sgSelection, sgSelectionRender, Items.NETHERITE_HOE);

    private final PlacementEngine placementEngine = new PlacementEngine();

    private final ShulkerRestockEngine restockEngine = new ShulkerRestockEngine(mc, placementEngine,
        new ShulkerRestockEngine.RestockCallback() {
            @Override
            public void onInfo(String message) {
                info(message);
            }

            @Override
            public void onFinished(boolean success) {
                if (success && !restockEngine.wasShulkerFullyEmptied()) {
                    info("§eShulker still had items when broken - some were left behind.");
                }

                if (success && restockBreakAfterFill.get()) {
                    collectingDroppedShulker = true;
                    pendingShulkerItem = null;
                    shulkerCollectTimeout = 100;

                } else {
                    restockCooldown = success ? restockResumeDelay.get() : 20;
                }
            }
        });

    /**
     * Module-owned cooldown before re-checking material stock after a failed restock attempt.
     */
    private int restockCooldown = 0;

    /**
     * Post-restock dropped-shulker collection state (see onFinished above).
     */
    private boolean collectingDroppedShulker = false;
    private ItemEntity pendingShulkerItem = null;
    private int shulkerCollectTimeout = 0;

    // yLevel is internal state driven by (a) the Y of your first selection click,
    // which seeds it, and (b) the move-up/move-down keybinds after that. The
    // selection is always rendered/evaluated at this Y, so what you see selected
    // is exactly where blocks will be placed - it starts at your click height
    // instead of a fixed default, and moves from there.
    private final Setting<Integer> yLevel = sgGeneral.add(new IntSetting.Builder()
        .name("y-level")
        .description("Current build Y-level. Seeded from your first selection click, then adjusted with the move-up/move-down keys below.")
        .defaultValue(319)
        .min(-64)
        .max(319)
        .sliderMin(-64)
        .sliderMax(319)
        .build()
    );

    private final Setting<Keybind> moveUpKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("move-up-key")
        .description("Moves the current selection/build level up by one step.")
        .defaultValue(Keybind.fromKey(-1))
        .build()
    );

    private final Setting<Keybind> moveDownKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("move-down-key")
        .description("Moves the current selection/build level down by one step.")
        .defaultValue(Keybind.fromKey(-1))
        .build()
    );

    private final Setting<Integer> moveStep = sgGeneral.add(new IntSetting.Builder()
        .name("move-step")
        .description("How many Y-levels to shift per key press.")
        .defaultValue(1)
        .min(1)
        .max(16)
        .sliderMin(1)
        .sliderMax(16)
        .build()
    );

    private final Setting<Keybind> startBuildKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("start-build-key")
        .description("Press once your selection is complete to confirm and actually start building. Nothing gets placed before this.")
        .defaultValue(Keybind.fromKey(-1))
        .build()
    );

    private final Setting<Keybind> resetSelectionKey = sgSelection.add(new KeybindSetting.Builder()
        .name("reset-selection-key")
        .description("Press to reset the selected area.")
        .defaultValue(Keybind.fromKey(-1))
        .build()
    );

    private final Setting<Integer> maxPlacementsPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("max-placements-per-tick")
        .description("Maximum number of blocks to place per tick.")
        .defaultValue(3)
        .min(1)
        .max(8)
        .build()
    );

    private final Setting<Integer> delayAfterPlacement = sgGeneral.add(new IntSetting.Builder()
        .name("delay-after-placement")
        .description("Delay in ticks after placing a block before placing another.")
        .defaultValue(2)
        .min(0)
        .max(20)
        .build()
    );

    private final Setting<Boolean> ignoreLiquids = sgPlacement.add(new BoolSetting.Builder()
        .name("ignore-liquids")
        .description("Allow placing blocks in water/lava.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> replaceBlocks = sgPlacement.add(new BoolSetting.Builder()
        .name("replace-grass")
        .description("Replace grass and other replaceable blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rotate = sgPlacement.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotate your view toward each block while placing it in build mode.")
        .defaultValue(false)
        .build()
    );

    private final Setting<List<Block>> allowedBlocks = sgBlocks.add(new BlockListSetting.Builder()
        .name("allowed-blocks")
        .description("Which blocks to use for building the platform.")
        .defaultValue(new ArrayList<>(List.of(Blocks.OBSIDIAN)))
        .build()
    );

    private final Setting<Boolean> refillFromInventory = sgBlocks.add(new BoolSetting.Builder()
        .name("refill-from-inventory")
        .description("Automatically move blocks from inventory to hotbar when the hotbar runs out.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> useBaritone = sgBaritone.add(new BoolSetting.Builder()
        .name("use-baritone")
        .description("Use Baritone to walk to sections of the platform that are out of reach.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> preventFallOffEdges = sgBaritone.add(new BoolSetting.Builder()
        .name("prevent-fall-off-edges")
        .description("Forces Baritone to sneak near edges/ledges while pathing here, instead of assuming an external 'safewalk' mechanic (a separate mod feature we don't have) prevents fall damage. Leave this on unless you specifically have a safewalk mod loaded.")
        .defaultValue(true)
        .visible(useBaritone::get)
        .build()
    );

    private final Setting<Integer> allowedFallHeight = sgBaritone.add(new IntSetting.Builder()
        .name("allowed-fall-height")
        .description("Maximum fall height (in blocks) Baritone is allowed to path through. Keep this low (0-3) so it won't take a route that drops it off the platform's edge.")
        .defaultValue(0)
        .min(0)
        .max(20)
        .sliderMin(0)
        .sliderMax(20)
        .visible(useBaritone::get)
        .build()
    );

    private final Setting<Boolean> jumpWhenStuck = sgBaritone.add(new BoolSetting.Builder()
        .name("jump-when-stuck")
        .description("Jump if no block has been placed for 5 seconds while building.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoRestock = sgRestock.add(new BoolSetting.Builder()
        .name("auto-restock")
        .description("Pause building and pull more of the allowed block from a shulker when supply runs low.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> restockThreshold = sgRestock.add(new IntSetting.Builder()
        .name("restock-threshold")
        .description("Restock when your total count of allowed blocks falls below this number.")
        .defaultValue(16)
        .min(1)
        .sliderRange(1, 64)
        .visible(autoRestock::get)
        .build()
    );

    private final Setting<Integer> restockPlaceRange = sgRestock.add(new IntSetting.Builder()
        .name("shulker-place-range")
        .description("How far the shulker placement search range is.")
        .defaultValue(2)
        .min(1)
        .sliderMax(5)
        .visible(autoRestock::get)
        .build()
    );

    private final Setting<Boolean> restockAirPlace = sgRestock.add(new BoolSetting.Builder()
        .name("shulker-air-place")
        .description("Place the shulker in mid-air.")
        .defaultValue(true)
        .visible(autoRestock::get)
        .build()
    );

    private final Setting<Boolean> restockPreferSolidBlock = sgRestock.add(new BoolSetting.Builder()
        .name("shulker-prefer-solid-block")
        .description("When shulker air place is on, try solid block positions first.")
        .defaultValue(true)
        .visible(() -> autoRestock.get() && restockAirPlace.get())
        .build()
    );

    private final Setting<Boolean> restockBreakAfterFill = sgRestock.add(new BoolSetting.Builder()
        .name("shulker-break-after-fill")
        .description("Break the shulker after restocking. Disable to leave it placed.")
        .defaultValue(true)
        .visible(autoRestock::get)
        .build()
    );

    private final Setting<Boolean> restockAutoTake = sgRestock.add(new BoolSetting.Builder()
        .name("shulker-auto-take")
        .description("Automatically take blocks from the shulker. If disabled, only open/close.")
        .defaultValue(true)
        .visible(autoRestock::get)
        .build()
    );

    private final Setting<Boolean> restockRotate = sgRestock.add(new BoolSetting.Builder()
        .name("shulker-rotate")
        .description("Rotate when placing/interacting with the shulker.")
        .defaultValue(true)
        .visible(autoRestock::get)
        .build()
    );

    private final Setting<Integer> restockShulkerHotbarSlot = sgRestock.add(new IntSetting.Builder()
        .name("shulker-hotbar-slot")
        .description("Hotbar slot (1-9) used when moving the shulker from inventory.")
        .defaultValue(1)
        .min(1)
        .max(9)
        .sliderRange(1, 9)
        .visible(autoRestock::get)
        .build()
    );

    private final Setting<List<Item>> restockProtectedItems = sgRestock.add(new ItemListSetting.Builder()
        .name("shulker-protected-items")
        .description("Items in the hotbar that must never be swapped out for the shulker.")
        .defaultValue(new ArrayList<>())
        .visible(autoRestock::get)
        .build()
    );

    private final Setting<Integer> restockResumeDelay = sgRestock.add(new IntSetting.Builder()
        .name("resume-delay")
        .description("Ticks to wait after a successful restock before resuming placement, so the hotbar swap back to your build material fully settles first.")
        .defaultValue(6)
        .min(0)
        .max(40)
        .visible(autoRestock::get)
        .build()
    );

    private final HashSet<BlockPos> pendingPlacements = new HashSet<>();

    private int delay = 0;
    private boolean buildStarted = false;

    // Baritone
    private IBaritone baritone = null;
    private boolean waitingForBaritone = false;
    private BlockPos currentTargetPos = null;
    private int stuckTicks = 0;
    private boolean prevAssumeSafeWalk = true;
    private boolean overrodeAssumeSafeWalk = false;
    private int prevMaxFallHeight = 3;
    private boolean overrodeMaxFallHeight = false;

    public PlatformBuilder() {
        super(Addon.CATEGORY2, "Platform", "Build a platform within a selected area at a given y-level.");
    }

    @Override
    public void onActivate() {
        pendingPlacements.clear();
        delay = 0;
        waitingForBaritone = false;
        currentTargetPos = null;
        stuckTicks = 0;
        overrodeAssumeSafeWalk = false;
        overrodeMaxFallHeight = false;
        restockCooldown = 0;
        collectingDroppedShulker = false;
        pendingShulkerItem = null;
        shulkerCollectTimeout = 0;
        restockEngine.reset();

        if (useBaritone.get()) {
            try {
                baritone = BaritoneAPI.getProvider().getPrimaryBaritone();

                if (preventFallOffEdges.get()) {
                    prevAssumeSafeWalk = BaritoneAPI.getSettings().assumeSafeWalk.value;
                    BaritoneAPI.getSettings().assumeSafeWalk.value = false;
                    overrodeAssumeSafeWalk = true;
                }

                // Cap how far Baritone is willing to drop while pathing here, so it
                // can't "shortcut" by falling off the platform into the void below.
                prevMaxFallHeight = BaritoneAPI.getSettings().maxFallHeightNoWater.value;
                BaritoneAPI.getSettings().maxFallHeightNoWater.value = allowedFallHeight.get();
                overrodeMaxFallHeight = true;
            } catch (Exception e) {
                error("Baritone not available!");
                useBaritone.set(false);
                baritone = null;
            }
        }
    }

    @Override
    public void onDeactivate() {
        pendingPlacements.clear();
        if (restockEngine.isActive()) {
            restockEngine.restoreOriginalSlotIfNeeded();
        }
        restockEngine.reset();
        restockCooldown = 0;
        collectingDroppedShulker = false;
        pendingShulkerItem = null;
        shulkerCollectTimeout = 0;
        mc.options.forwardKey.setPressed(false);
        if (baritone != null) baritone.getPathingBehavior().cancelEverything();
        if (overrodeAssumeSafeWalk) {
            BaritoneAPI.getSettings().assumeSafeWalk.value = prevAssumeSafeWalk;
            overrodeAssumeSafeWalk = false;
        }
        if (overrodeMaxFallHeight) {
            BaritoneAPI.getSettings().maxFallHeightNoWater.value = prevMaxFallHeight;
            overrodeMaxFallHeight = false;
        }
        waitingForBaritone = false;
        currentTargetPos = null;
    }

    @EventHandler
    private void onInteract(InteractBlockEvent event) {
        if (mc.player == null || mc.world == null) return;
        if (event.hand != Hand.MAIN_HAND) return;

        ItemStack hand = mc.player.getMainHandStack();
        if (!areaSelector.isToolStack(hand)) return;

        BlockHitResult hitResult = event.result;
        if (hitResult == null) return;
        BlockPos pos = hitResult.getBlockPos();

        // Cancel any in‑progress build if the user tries to move/complete the selection
        if (buildStarted) {
            cancelBuild("§cBuild cancelled. selection is being modified.");
        }

        AreaSelector.Result result = areaSelector.handleClick(pos);
        switch (result) {
            case POS1_SET -> {
                yLevel.set(pos.getY());
                info("§aPlatform pos1 set to §f" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                    + " §7(build level set to Y=" + yLevel.get() + ")");
            }
            case COMPLETE -> info("§aPlatform area selected! Press the start-build key to begin building at y=§f" + yLevel.get());
            case RESET -> {
                buildStarted = false;
                info("§ePlatform selection reset.");
            }
        }
        event.cancel();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        areaSelector.render(event, yLevel.get());
    }

    @EventHandler
    public void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        if (moveUpKey.get().isPressed()) {
            yLevel.set(Math.min(yLevel.get() + moveStep.get(), 319));
            if (buildStarted) cancelBuild("§cBuild cancelled as the Y-level changed.");
            info("Platform level moved up to Y=" + yLevel.get());
        }

        if (moveDownKey.get().isPressed()) {
            yLevel.set(Math.max(yLevel.get() - moveStep.get(), -64));
            if (buildStarted) cancelBuild("§cBuild cancelled as the Y-level changed.");
            info("Platform level moved down to Y=" + yLevel.get());
        }

        if (resetSelectionKey.get().isPressed()) {
            areaSelector.reset();
            if (buildStarted) {
                cancelBuild("§ePlatform selection reset.");
            } else {
                info("§ePlatform selection reset.");
            }
        }

        // Dropped-shulker collection (post-restock)
        if (collectingDroppedShulker) {
            tickShulkerCollection();
            return;
        }

        // If a restock is already underway, it owns the tick until it's done
        // don't touch placement/Baritone logic while it's mid-sequence.
        if (restockEngine.isActive()) {
            restockEngine.tick();
            return;
        }

        pendingPlacements.removeIf(pos -> needsBlock(mc.world.getBlockState(pos)));

        if (!areaSelector.hasCompleteSelection()) return;

        if (!buildStarted) {
            if (startBuildKey.get().isPressed()) {
                buildStarted = true;
                info("§aBuild confirmed. starting platform construction at y=§f" + yLevel.get());
            } else {
                return;
            }
        }

        if (restockCooldown > 0) {
            restockCooldown--;
            return;
        } else if (autoRestock.get() && !allowedBlocks.get().isEmpty() && needsMaterialRestock()) {
            // Pause any in-flight Baritone movement before handing control to the
            // restock sequence, so it doesn't fight the walk-to-placement logic below.
            if (baritone != null) baritone.getPathingBehavior().cancelEverything();
            waitingForBaritone = false;
            currentTargetPos = null;
            restockEngine.start(pickRestockItem(), buildRestockConfig());
            return;
        }

        // If we're mid-pathfind, wait for Baritone to either arrive or put us
        // within reach of something placeable, then hand control back.
        if (waitingForBaritone) {
            if (baritone == null || !useBaritone.get()) {
                waitingForBaritone = false;
                currentTargetPos = null;
            } else if (!baritone.getPathingBehavior().isPathing() && baritoneArrived()) {
                waitingForBaritone = false;
                baritone.getPathingBehavior().cancelEverything();
                currentTargetPos = null;
                delay = 0;
            } else {
                return;
            }
        }

        if (delay > 0) {
            delay--;
            return;
        }

        if (!ensureBuildMatInHand()) return;

        BlockPos[] targets = reachableTargets();

        if (targets.length == 0) {
            if (jumpWhenStuck.get()) {
                if (stuckTicks >= 100) {
                    mc.player.jump();
                    stuckTicks = 0;
                } else {
                    stuckTicks++;
                }
            }

            if (useBaritone.get() && baritone != null) {
                BlockPos safeGoal = findBaritoneEdgeSafeGoal();
                if (safeGoal != null) {
                    waitingForBaritone = true;
                    currentTargetPos = safeGoal;
                    // GoalBlock (not GoalXZ) so Y is actually respected. we only ever
                    // target a cell that's already solid, standing on top of it, rather
                    // than an XZ column that ignores whether there's a floor at all.
                    baritone.getCustomGoalProcess().setGoalAndPath(
                        new GoalBlock(safeGoal.getX(), safeGoal.getY() + 1, safeGoal.getZ()));
                    info("Moving to reach platform section near " + safeGoal.toShortString());
                }
            }
            return;
        }

        stuckTicks = 0;

        BlockPos origin = mc.player.getBlockPos();
        java.util.Arrays.sort(targets,
            Comparator.comparingDouble(a -> a.getSquaredDistance(origin)));

        int placed = 0;
        for (BlockPos pos : targets) {
            if (placed >= maxPlacementsPerTick.get()) break;
            if (tryPlace(pos)) placed++;
        }

        if (placed > 0) {
            delay = delayAfterPlacement.get();
        }

        int targetY = yLevel.get();
        boolean allPlaced = true;
        for (BlockPos pos : areaSelector.getFlatArea(targetY)) {
            // A position is still “needed” if it's air/liquid/replaceable
            // AND it hasn't just been placed this tick (pending).
            if (needsBlock(mc.world.getBlockState(pos)) && !pendingPlacements.contains(pos)) {
                allPlaced = false;
                break;
            }
        }

        if (allPlaced) {
            buildStarted = false;
            if (baritone != null) baritone.getPathingBehavior().cancelEverything();
            waitingForBaritone = false;
            currentTargetPos = null;
            pendingPlacements.clear();
            stuckTicks = 0;
            info("§aPlatform construction complete!");
        }
    }

    private void cancelBuild(String message) {
        buildStarted = false;
        if (baritone != null) baritone.getPathingBehavior().cancelEverything();
        waitingForBaritone = false;
        currentTargetPos = null;
        pendingPlacements.clear();
        stuckTicks = 0;
        if (restockEngine.isActive()) {
            restockEngine.restoreOriginalSlotIfNeeded();
            restockEngine.reset();
        }
        stopCollectingShulker();
        restockCooldown = 0;
        info(message);
    }

    /**
     * Baritone uses GoalBlock now (Y-aware), so "arrived" means either something
     * is now placeable within reach, or we've settled near the target column.
     */
    private boolean baritoneArrived() {
        if (reachableTargets().length > 0) return true;
        if (currentTargetPos == null) return true;
        int dx = Math.abs(mc.player.getBlockX() - currentTargetPos.getX());
        int dz = Math.abs(mc.player.getBlockZ() - currentTargetPos.getZ());
        return dx <= 2 && dz <= 2;
    }

    private BlockPos findBaritoneEdgeSafeGoal() {
        int targetY = yLevel.get();
        BlockPos playerPos = mc.player.getBlockPos();

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (BlockPos pos : areaSelector.getFlatArea(targetY)) {
            if (pendingPlacements.contains(pos)) continue;
            if (!needsBlock(mc.world.getBlockState(pos))) continue;

            for (BlockPos n : new BlockPos[]{pos.north(), pos.south(), pos.east(), pos.west()}) {
                if (needsBlock(mc.world.getBlockState(n))) continue; // still an open gap, skip it

                double dist = n.getSquaredDistance(playerPos);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = n;
                }
            }
        }
        return best;
    }

    private boolean tryPlace(BlockPos pos) {
        if (!PlayerUtils.isWithinReach(pos)) return false;
        if (pendingPlacements.contains(pos)) return false;

        BlockState state = mc.world.getBlockState(pos);
        if (!needsBlock(state)) return false;

        FindItemResult item = InvUtils.findInHotbar(this::isAllowedStack);
        if (!item.found()) return false;

        boolean ok = BlockUtils.place(pos, item, rotate.get(), 50, true, true);
        if (ok) pendingPlacements.add(pos);
        return ok;
    }

    private boolean needsBlock(BlockState state) {
        if (state.isAir()) return true;

        if (state.getFluidState().isStill() || state.getBlock() instanceof FluidBlock) {
            return ignoreLiquids.get();
        }

        if (state.isReplaceable()
            || state.getBlock() == Blocks.GRASS_BLOCK
            || state.getBlock() == Blocks.FERN) {
            return replaceBlocks.get();
        }

        return false;
    }

    /**
     * Bounded by the selected footprint (X/Z), flattened to the current yLevel.
     */
    private BlockPos[] reachableTargets() {
        int targetY = yLevel.get();

        if (Math.abs((int) mc.player.getY() - targetY) > 4) {
            return new BlockPos[0];
        }

        List<BlockPos> positions = new ArrayList<>();
        for (BlockPos pos : areaSelector.getFlatArea(targetY)) {
            if (!PlayerUtils.isWithinReach(pos)) continue;
            if (pendingPlacements.contains(pos)) continue;
            if (!needsBlock(mc.world.getBlockState(pos))) continue;
            positions.add(pos);
        }

        return positions.toArray(new BlockPos[0]);
    }

    private boolean isAllowedStack(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem bi)) return false;
        return isAllowedBlock(bi.getBlock());
    }

    private boolean isAllowedBlock(Block block) {
        return allowedBlocks.get().contains(block);
    }

    private boolean ensureBuildMatInHand() {
        ItemStack held = mc.player.getMainHandStack();
        if (isAllowedStack(held)) return true;

        for (int i = 0; i < 9; i++) {
            if (isAllowedStack(mc.player.getInventory().getStack(i))) {
                InvUtils.swap(i, false);
                return true;
            }
        }

        if (refillFromInventory.get()) {
            int emptySlot = -1;
            for (int j = 0; j < 9; j++) {
                if (mc.player.getInventory().getStack(j).isEmpty()) {
                    emptySlot = j;
                    break;
                }
            }
            if (emptySlot != -1) {
                for (int i = 9; i < 36; i++) {
                    if (isAllowedStack(mc.player.getInventory().getStack(i))) {
                        InvUtils.move().from(i).toHotbar(emptySlot);
                        InvUtils.swap(emptySlot, false);
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private boolean needsMaterialRestock() {
        int total = 0;
        for (Block b : allowedBlocks.get()) total += InvUtils.find(b.asItem()).count();
        return total < restockThreshold.get();
    }

    /**
     * Picks which configured block to restock. Assumes a single-material build.
     * If you run multi-block builds and the first entry's shulker isn't available,
     * this retries the same entry after the cooldown rather than falling through
     * to try the others.
     */
    private Item pickRestockItem() {
        return allowedBlocks.get().get(0).asItem();
    }

    private ShulkerRestockEngine.RestockConfig buildRestockConfig() {
        List<BlockPos> footprint = new ArrayList<>();
        for (BlockPos pos : areaSelector.getFlatArea(yLevel.get())) {
            footprint.add(pos);
        }

        return new ShulkerRestockEngine.RestockConfig(
            restockPlaceRange.get(),
            restockAirPlace.get(),
            restockPreferSolidBlock.get(),
            restockBreakAfterFill.get(),
            restockAutoTake.get(),
            restockRotate.get(),
            restockShulkerHotbarSlot.get(),
            restockProtectedItems.get(),
            footprint
        );
    }

    private void tickShulkerCollection() {
        if (shulkerCollectTimeout <= 0) {
            info("§eTimed out waiting to collect the dropped shulker – resuming build.");
            stopCollectingShulker();
            return;
        }
        shulkerCollectTimeout--;

        if (pendingShulkerItem == null || !pendingShulkerItem.isAlive()) {
            pendingShulkerItem = findDroppedShulker();
            if (pendingShulkerItem == null) {
                stopCollectingShulker();
                return;
            }
        }

        BlockPos itemPos = pendingShulkerItem.getBlockPos();

        if (useBaritone.get() && baritone != null) {
            if (!itemPos.equals(currentTargetPos) || !baritone.getPathingBehavior().isPathing()) {
                currentTargetPos = itemPos;
                baritone.getCustomGoalProcess().setGoalAndPath(
                    new GoalBlock(itemPos.getX(), itemPos.getY(), itemPos.getZ()));
            }
        } else {
            double dx = pendingShulkerItem.getX() - mc.player.getX();
            double dz = pendingShulkerItem.getZ() - mc.player.getZ();
            float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90;
            mc.player.setYaw(yaw);
            mc.options.forwardKey.setPressed(true);
        }
    }

    private void stopCollectingShulker() {
        collectingDroppedShulker = false;
        pendingShulkerItem = null;
        shulkerCollectTimeout = 0;
        currentTargetPos = null;
        if (baritone != null) baritone.getPathingBehavior().cancelEverything();
        mc.options.forwardKey.setPressed(false);
        restockCooldown = restockResumeDelay.get();
    }

    private ItemEntity findDroppedShulker() {
        ItemEntity closest = null;
        double minDist = Double.MAX_VALUE;

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity item) || !entity.isAlive()) continue;
            if (!(Block.getBlockFromItem(item.getStack().getItem()) instanceof ShulkerBoxBlock)) continue;
            if (isShulkerEmpty(item.getStack())) continue;

            double dist = mc.player.squaredDistanceTo(entity);
            if (dist > 400) continue; // ignore anything further than ~20 blocks away
            if (dist < minDist) {
                minDist = dist;
                closest = item;
            }
        }

        return closest;
    }

    /**
     * True if the shulker box item stack has no items in it (or no container
     * data at all, which amounts to the same thing). An empty shulker box just
     * merges into any existing empty-shulker stack when picked up, so it's never
     * worth chasing down the way a still-loaded one is.
     */
    private boolean isShulkerEmpty(ItemStack stack) {
        ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
        if (container == null) return true;
        for (ItemStack inner : container.iterateNonEmpty()) {
            return false; // found at least one non-empty slot
        }
        return true;
    }
}
