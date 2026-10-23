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
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

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
                    info("§eShulker still had items when broken, some were left behind.");
                }
                // The dropped-shulker walk (when enabled) already ran inside the engine before
                // this fired, so there is nothing left to start here.
                restockCooldown = success ? restockResumeDelay.get() : 20;
            }
        });

    /**
     * Module-owned cooldown before re-checking material stock after a failed restock attempt.
     */
    private int restockCooldown = 0;

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

    private final Setting<BuildShape> buildShape = sgGeneral.add(new EnumSetting.Builder<BuildShape>()
        .name("build-shape")
        .description("What gets built: a flat platform (current Y), vertical walls (perimeter spanning corner Ys), or the full cuboid.")
        .defaultValue(BuildShape.Flat)
        .build()
    );

    public enum BuildShape {
        Flat,
        Walls,
        Solid
    }

    private final Setting<LayerOrder> solidLayerOrder = sgGeneral.add(new EnumSetting.Builder<LayerOrder>()
        .name("solid-layer-order")
        .description("Placement order for solid cuboid layers.")
        .defaultValue(LayerOrder.BottomToTop)
        .build()
    );

    private final Setting<LayerOrder> wallsLayerOrder = sgGeneral.add(new EnumSetting.Builder<LayerOrder>()
        .name("walls-layer-order")
        .description("Placement order for wall layers.")
        .defaultValue(LayerOrder.BottomToTop)
        .build()
    );

    public enum LayerOrder {
        BottomToTop,
        TopToBottom
    }

    private final Setting<Boolean> layerByLayer = sgGeneral.add(new BoolSetting.Builder()
        .name("layer-by-layer")
        .description("Walls and Solid only: finish and verify one Y layer, then climb onto it before starting the next.")
        .defaultValue(true)
        .visible(() -> buildShape.get() != BuildShape.Flat)
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
        .description("Forces Baritone to sneak near edges/ledges while pathing here, instead of assuming an external 'safewalk'.")
        .defaultValue(true)
        .visible(useBaritone::get)
        .build()
    );

    private final Setting<Integer> allowedFallHeight = sgBaritone.add(new IntSetting.Builder()
        .name("allowed-fall-height")
        .description("Maximum fall height (in blocks) Baritone is allowed to path through. Keep this low (0-3).")
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
        .description("When shulker air place is on, prefer positions sitting on a full block (glass and leaves included) over mid-air ones.")
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

    /**
     * After the restock break, walk to the dropped shulker box and pick it up. Runs through
     * {@link ShulkerRestockEngine.DropCollector}, so it shares its walk with MaterialsRefill and
     * AutoLoader. Off by default: it moves the player, which is not always wanted mid-build.
     */
    private final Setting<Boolean> restockCollectDropped = sgRestock.add(new BoolSetting.Builder()
        .name("collect-dropped-shulker")
        .description("After breaking the shulker, walk to the dropped box and pick it up.")
        .defaultValue(false)
        .visible(() -> autoRestock.get() && restockBreakAfterFill.get())
        .build()
    );

    private final Setting<Boolean> restockAutoTake = sgRestock.add(new BoolSetting.Builder()
        .name("shulker-auto-take")
        .description("Automatically take blocks from the shulker. If disabled, only open/close.")
        .defaultValue(true)
        .visible(autoRestock::get)
        .build()
    );

    private final Setting<Boolean> restockOnlySingleItem = sgRestock.add(new BoolSetting.Builder()
        .name("shulker-only-single-item")
        .description("Only auto-take if the shulker contains a single item type.")
        .defaultValue(true)
        .visible(autoRestock::get)
        .build()
    );

    private final Setting<Boolean> restockPacketBreak = sgRestock.add(new BoolSetting.Builder()
        .name("shulker-packet-break")
        .description("Use instant packet based breaking for the shulker.")
        .defaultValue(false)
        .visible(() -> autoRestock.get() && restockBreakAfterFill.get())
        .build()
    );

    private final Setting<Boolean> restockPacketBreakGrim = sgRestock.add(new BoolSetting.Builder()
        .name("shulker-grim-v3-packet")
        .description("Use the Grim-v3 packet breaking sequence.")
        .defaultValue(false)
        .visible(() -> autoRestock.get() && restockPacketBreak.get())
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
        .description("Preferred slot (1-9) for the shulker when it is moved out of the inventory. An empty slot is used first so nothing is displaced.")
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
        .description("Ticks to wait after a successful restock before resuming placement.")
        .defaultValue(6)
        .min(0)
        .max(40)
        .visible(autoRestock::get)
        .build()
    );

    private final Setting<Integer> restockMaxPlacementAttempts = sgRestock.add(new IntSetting.Builder()
        .name("shulker-max-place-attempts")
        .description("How many times to retry finding/placing the shulker before giving up on this restock.")
        .defaultValue(5)
        .min(1)
        .sliderMax(20)
        .visible(autoRestock::get)
        .build()
    );

    private final Setting<Integer> restockMaxOpenAttempts = sgRestock.add(new IntSetting.Builder()
        .name("shulker-max-open-attempts")
        .description("How many times to retry opening the placed shulker before giving up.")
        .defaultValue(3)
        .min(1)
        .sliderMax(20)
        .visible(autoRestock::get)
        .build()
    );

    private final HashSet<BlockPos> pendingPlacements = new HashSet<>();

    /**
     * Index into the cached build-cell list where the completion scan last stopped. Every cell
     * before it was satisfied on an earlier tick, so the scan resumes there rather than re-walking
     * the whole selection. Without it the post-placement scan re-read every already-built cell on
     * every tick, which for a Solid cuboid near completion is the entire selection.
     *
     * <p>Reset by {@link #getBuildCells()} whenever the list is actually rebuilt (selection, shape
     * or build level changed), and by the pending-placement prune in {@code onTick} whenever a cell
     * behind the cursor stops being solid. That covers every cell the module placed itself; a block
     * broken while still in reach is also re-detected by {@link #reachableTargets()} and re-placed.
     */
    private int completionScanIndex = 0;

    private static final int RESTOCK_CHECK_INTERVAL = 20;

    /**
     * Ticks between Baritone goal searches. The search walks the whole selection for a cell whose
     * neighbour is already solid, and it runs whenever nothing is reachable, so at a fixed throttle
     * an idle walk would pay for it every tick. A null result is not retried sooner than a hit: the
     * terrain or the frontier it looks at does not change within a few ticks.
     */
    private static final int BARITONE_GOAL_COOLDOWN = 20;

    /**
     * Horizontal neighbours the Baritone goal scan looks at; hoisted so it allocates nothing per cell.
     */
    private static final Direction[] HORIZONTAL_DIRECTIONS =
        {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    private int restockCheckTimer = 0;
    private int cachedBuildSlot = -1;

    /**
     * Round-robin cursor over {@link #allowedBlocks} for {@link #pickRestockItem()}, so a build
     * listing several materials keeps cycling instead of retrying the first entry's shulker.
     */
    private int restockBlockCursor = 0;

    private List<BlockPos> cachedBuildCells;
    private int cachedMinX = Integer.MIN_VALUE;
    private int cachedMaxX = Integer.MIN_VALUE;
    private int cachedMinY = Integer.MIN_VALUE;
    private int cachedMaxY = Integer.MIN_VALUE;
    private int cachedMinZ = Integer.MIN_VALUE;
    private int cachedMaxZ = Integer.MIN_VALUE;
    private BuildShape cachedShape;
    private int cachedYLevel = Integer.MIN_VALUE;
    private boolean cachedValid = false;

    private int delay = 0;
    private boolean buildStarted = false;

    /**
     * Sentinel for "no layer restriction" in the staged-layer helpers.
     */
    private static final int NO_LAYER = Integer.MIN_VALUE;

    /**
     * Layer currently being built when {@link #layerByLayer} is on, as an index into the
     * ascending-Y layers of {@link #getBuildCells()}. Reset when a build starts or is cancelled.
     */
    private int layerIndex = 0;

    /**
     * Ascending-Y boundaries of the layers in {@link #getBuildCells()}: layer {@code i} spans
     * {@code [layerBounds[i], layerBounds[i + 1])}, with a trailing sentinel equal to the list
     * size. Null means "recompute", and it is cleared wherever that list is rebuilt.
     */
    private int[] layerBounds = null;

    // Baritone
    private IBaritone baritone = null;
    private boolean waitingForBaritone = false;
    private BlockPos currentTargetPos = null;
    private int stuckTicks = 0;
    private int baritoneGoalCooldown = 0;
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
        layerIndex = 0;
        waitingForBaritone = false;
        currentTargetPos = null;
        stuckTicks = 0;
        baritoneGoalCooldown = 0;
        overrodeAssumeSafeWalk = false;
        overrodeMaxFallHeight = false;
        restockCooldown = 0;
        restockCheckTimer = 0;
        restockBlockCursor = 0;
        cachedBuildSlot = -1;
        invalidateFlatArea();
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
        layerIndex = 0;
        if (restockEngine.isActive()) {
            restockEngine.restoreOriginalSlotIfNeeded();
        }
        restockEngine.reset();
        restockCooldown = 0;
        restockBlockCursor = 0;
        cachedBuildSlot = -1;
        invalidateFlatArea();
        mc.options.forwardKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
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
        invalidateFlatArea();
        switch (result) {
            case POS1_SET -> {
                yLevel.set(pos.getY());
                if (buildShape.get() == BuildShape.Flat) {
                    info("§aPlatform pos1 set to §f" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                        + " §7(build level set to Y=" + yLevel.get() + ")");
                } else {
                    info("§aPlatform pos1 set to §f" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                        + " §7(click second corner at any height)");
                }
            }
            case COMPLETE -> {
                int count = getBuildCells().size();
                int minY = areaSelector.minY();
                int maxY = areaSelector.maxY();
                switch (buildShape.get()) {
                    case Flat ->
                        info("§aPlatform area selected! Press the start-build key to begin building at y=§f" + yLevel.get() + " §7(" + count + " blocks)");
                    case Walls ->
                        info("§aPlatform walls selected §7(" + count + " blocks, Y: " + minY + " → " + maxY + ")! Press the start-build key to begin building.");
                    case Solid ->
                        info("§aPlatform cuboid selected §7(" + count + " blocks, Y: " + minY + " → " + maxY + ")! Press the start-build key to begin building.");
                }
            }
            case RESET -> {
                buildStarted = false;
                info("§ePlatform selection reset.");
            }
        }
        event.cancel();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (buildShape.get() == BuildShape.Flat) areaSelector.render(event, yLevel.get());
        else areaSelector.render(event);
    }

    @EventHandler
    public void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        if (baritoneGoalCooldown > 0) baritoneGoalCooldown--;

        if (moveUpKey.get().isPressed()) {
            yLevel.set(Math.min(yLevel.get() + moveStep.get(), 319));
            if (buildStarted && yLevelAffectsBuild()) cancelBuild("§cBuild cancelled as the Y-level changed.");
            info("Platform level moved up to Y=" + yLevel.get());
        }

        if (moveDownKey.get().isPressed()) {
            yLevel.set(Math.max(yLevel.get() - moveStep.get(), -64));
            if (buildStarted && yLevelAffectsBuild()) cancelBuild("§cBuild cancelled as the Y-level changed.");
            info("Platform level moved down to Y=" + yLevel.get());
        }

        if (resetSelectionKey.get().isPressed()) {
            areaSelector.reset();
            invalidateFlatArea();
            if (buildStarted) {
                cancelBuild("§ePlatform selection reset.");
            } else {
                info("§ePlatform selection reset.");
            }
        }

        // If a restock is already underway, it owns the tick until it's done
        // don't touch placement/Baritone logic while it's mid-sequence. The
        // dropped-shulker walk (when enabled) runs inside the engine too.
        if (restockEngine.isActive()) {
            restockEngine.tick();
            return;
        }

        // A removal here means a placement we counted as done is actually air again (rejected by
        // the server, or broken). The completion cursor may already be past it, so start the scan
        // over; without this a hole behind the cursor would be reported as a finished build.
        if (pendingPlacements.removeIf(pos -> needsBlock(mc.world.getBlockState(pos)))) {
            completionScanIndex = 0;
        }

        if (!areaSelector.hasCompleteSelection()) return;

        if (!buildStarted) {
            if (startBuildKey.get().isPressed()) {
                buildStarted = true;
                completionScanIndex = 0;
                layerIndex = 0;
                int count = getBuildCells().size();
                switch (buildShape.get()) {
                    case Flat -> info("§aBuild confirmed. start platform construction at y=§f" + yLevel.get() + " §7(" + count + " blocks)");
                    case Walls ->
                        info("§aBuild confirmed. start walls construction §7(" + count + " blocks, Y: " + areaSelector.minY() + " → " + areaSelector.maxY() + ")");
                    case Solid ->
                        info("§aBuild confirmed. start solid construction §7(" + count + " blocks, Y: " + areaSelector.minY() + " → " + areaSelector.maxY() + ")");
                }
            } else {
                return;
            }
        }

        if (restockCooldown > 0) {
            restockCooldown--;
            return;
        }

        if (autoRestock.get() && !allowedBlocks.get().isEmpty()) {
            if (restockCheckTimer > 0) {
                restockCheckTimer--;
            } else {
                restockCheckTimer = RESTOCK_CHECK_INTERVAL;
                if (needsMaterialRestock()) {
                    // Pause any in-flight Baritone movement before handing control to the
                    // restock sequence, so it doesn't fight the walk-to-placement logic below.
                    if (baritone != null) baritone.getPathingBehavior().cancelEverything();
                    waitingForBaritone = false;
                    currentTargetPos = null;
                    restockEngine.start(pickRestockItem(), buildRestockConfig());
                    return;
                }
            }
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
            if (staging()) {
                // Layer-by-layer: with nothing placeable, first ask whether this layer is simply
                // finished. Verifying before pathing matters, because otherwise the Baritone goal
                // below would walk us onto a layer that is already done instead of starting the next.
                if (currentLayerComplete()) {
                    advanceLayer();
                    return;
                }
            } else if (scanSelectionComplete()) {
                // Every layer has been visited and nothing is reachable, so the whole-selection
                // scan is the only completion authority left: it is also the repair pass for a
                // placement the server rejected after we predicted it. Without this branch a
                // finished staged build idled forever instead of reporting complete.
                finishBuild();
                return;
            }

            if (jumpWhenStuck.get()) {
                if (stuckTicks >= 100) {
                    mc.player.jump();
                    stuckTicks = 0;
                } else {
                    stuckTicks++;
                }
            }

            if (useBaritone.get() && baritone != null && baritoneGoalCooldown <= 0) {
                baritoneGoalCooldown = BARITONE_GOAL_COOLDOWN;
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
        if (buildShape.get() == BuildShape.Solid) {
            LayerOrder o = solidLayerOrder.get();
            if (o == LayerOrder.BottomToTop) {
                java.util.Arrays.sort(targets, Comparator.<BlockPos>comparingInt(p -> p.getY()).thenComparingDouble(p -> p.getSquaredDistance(origin)));
            } else {
                java.util.Arrays.sort(targets, Comparator.<BlockPos>comparingInt(p -> p.getY()).reversed().thenComparingDouble(p -> p.getSquaredDistance(origin)));
            }
        } else if (buildShape.get() == BuildShape.Walls) {
            LayerOrder o = wallsLayerOrder.get();
            if (o == LayerOrder.BottomToTop) {
                java.util.Arrays.sort(targets, Comparator.<BlockPos>comparingInt(p -> p.getY()).thenComparingDouble(p -> p.getSquaredDistance(origin)));
            } else {
                java.util.Arrays.sort(targets, Comparator.<BlockPos>comparingInt(p -> p.getY()).reversed().thenComparingDouble(p -> p.getSquaredDistance(origin)));
            }
        } else {
            java.util.Arrays.sort(targets, Comparator.comparingDouble(a -> a.getSquaredDistance(origin)));
        }

        int placed = 0;
        for (BlockPos pos : targets) {
            if (placed >= maxPlacementsPerTick.get()) break;
            if (tryPlace(pos)) placed++;
        }

        if (placed > 0) {
            delay = delayAfterPlacement.get();
        }

        // A staged build advances layer by layer in the targets.length == 0 branch above, and only
        // after every layer has been visited does it hand over to the whole-selection scan below.
        // Running that scan while staging would count layers that have not started yet as missing
        // and so never let the build complete.
        if (staging()) return;

        // Only scan once every cell we could reach this tick has been handled. An unplaced target
        // is by construction still needed and not pending, so the scan would find it and report the
        // build unfinished anyway; max-placements-per-tick is 3 by default, so during an active
        // build this skips the scan outright.
        if (placed >= targets.length && scanSelectionComplete()) finishBuild();
    }

    /**
     * Whole-selection completion scan, resuming from {@link #completionScanIndex}. True when every
     * build cell is satisfied in the world (or pending this tick).
     *
     * <p>Shared by the normal completion path and the empty-targets path so a finished build is
     * judged by the same criteria either way. The cursor keeps the rescan cheap: a run that finds
     * everything built leaves it at the end, and one that finds a hole resumes from that hole.
     */
    private boolean scanSelectionComplete() {
        List<BlockPos> cells = getBuildCells();
        int i = Math.min(completionScanIndex, cells.size());
        boolean complete = true;
        for (; i < cells.size(); i++) {
            BlockPos pos = cells.get(i);
            // A position is still "needed" if it's air/liquid/replaceable
            // AND it hasn't just been placed this tick (pending).
            if (needsBlock(mc.world.getBlockState(pos)) && !pendingPlacements.contains(pos)) {
                complete = false;
                break;
            }
        }
        completionScanIndex = complete ? cells.size() : i;
        return complete;
    }

    /**
     * Ends a successful build: releases Baritone, drops the pending placeholders and reports it.
     */
    private void finishBuild() {
        buildStarted = false;
        if (baritone != null) baritone.getPathingBehavior().cancelEverything();
        waitingForBaritone = false;
        currentTargetPos = null;
        pendingPlacements.clear();
        stuckTicks = 0;
        info("§aPlatform construction complete!");
    }

    private void cancelBuild(String message) {
        buildStarted = false;
        layerIndex = 0;
        if (baritone != null) baritone.getPathingBehavior().cancelEverything();
        waitingForBaritone = false;
        currentTargetPos = null;
        pendingPlacements.clear();
        stuckTicks = 0;
        baritoneGoalCooldown = 0;
        cachedBuildSlot = -1;
        invalidateFlatArea();
        if (restockEngine.isActive()) {
            restockEngine.restoreOriginalSlotIfNeeded();
            restockEngine.reset();
        }
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

    /**
     * Nearest already-solid cell adjacent to a cell that still needs a block, used as the Baritone
     * walk target.
     *
     * <p>Only called when nothing is reachable, so the goal is by definition outside reach and a
     * reach-clipped candidate set would be empty; restricting to the selection's shell would
     * instead change which goal Solid picks. The whole-selection walk this needs is bounded by
     * {@link #BARITONE_GOAL_COOLDOWN} and allocates nothing per cell.
     *
     * <p>While staging, the scan is restricted to the layer being built. The goal is one block
     * above a solid cell of that layer, i.e. "stand on the part of this layer already placed", so
     * the walk climbs onto the current layer and continues along it instead of leaving it for a
     * layer further up. Once every layer has been visited the restriction lifts and this walks
     * back to repair a hole in reach.
     */
    private BlockPos findBaritoneEdgeSafeGoal() {
        BlockPos playerPos = mc.player.getBlockPos();
        int layerY = currentLayerY();

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        // Reused per candidate: the old form built a four-element BlockPos array (and a neighbour
        // object for each direction) per cell, i.e. millions of allocations for a Solid cuboid.
        BlockPos.Mutable neighbour = new BlockPos.Mutable();

        for (BlockPos pos : getBuildCells()) {
            if (layerY != NO_LAYER && pos.getY() != layerY) continue;
            if (pendingPlacements.contains(pos)) continue;
            if (!needsBlock(mc.world.getBlockState(pos))) continue;

            for (Direction dir : HORIZONTAL_DIRECTIONS) {
                neighbour.set(pos.getX() + dir.getOffsetX(), pos.getY(), pos.getZ() + dir.getOffsetZ());
                if (needsBlock(mc.world.getBlockState(neighbour))) continue; // still an open gap, skip it

                double dist = neighbour.getSquaredDistance(playerPos);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = neighbour.toImmutable();
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

        FindItemResult item = findBuildBlockInHotbar();
        if (!item.found()) return false;

        // The restock pulls its shulker into the hotbar and can displace the material that was
        // there, so the slot the lookup returned must still hold a build block when we place with
        // it. A shulker box placed as a build block is a lost box and a hole in the platform.
        if (!slotHoldsBuildBlock(item)) {
            cachedBuildSlot = -1;
            return false;
        }

        if (rotate.get()) {
            // BlockUtils.place defers its swap and click into the movement phase, which runs after
            // the other modules' TickEvent.Pre handlers, so a concurrent restock can pull its
            // shulker into the slot we just verified in between. Register the same rotation here
            // and re-resolve the item at click time, where the check and the click cannot split.
            BlockPos target = pos.toImmutable();
            Vec3d hitPos = Vec3d.ofCenter(target);
            Direction side = BlockUtils.getPlaceSide(target);
            if (side != null) {
                hitPos = hitPos.add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
            }
            Rotations.rotate(Rotations.getYaw(hitPos), Rotations.getPitch(hitPos), 50, () -> {
                FindItemResult live = findBuildBlockInHotbar();
                if (live.found() && slotHoldsBuildBlock(live)) {
                    BlockUtils.place(target, live, false, 50, true, true);
                }
            });
            pendingPlacements.add(target);
            return true;
        }

        boolean ok = BlockUtils.place(pos, item, false, 50, true, true);
        if (ok) pendingPlacements.add(pos);
        return ok;
    }

    /**
     * True while the slot the lookup returned still holds a configured build block, so the item
     * we verified is the item that gets placed.
     *
     * <p>A shulker box is refused outright while auto-restock is on. The restock pulls boxes
     * through the hotbar and, on the server's side, through the carried slot, and a box placed as
     * platform material is a lost box plus a hole in the platform, never worth the risk of
     * matching a lookup against one.
     */
    private boolean slotHoldsBuildBlock(FindItemResult item) {
        ItemStack slotStack = placeableStack(item);
        if (autoRestock.get() && ShulkerRestockEngine.isShulkerBox(slotStack)) return false;
        return isAllowedStack(slotStack);
    }

    /**
     * The stack {@link BlockUtils#place} would actually put in the main hand for this lookup: the
     * offhand stack, or the hotbar slot's stack. This is the stack that gets placed, so it is not
     * the lookup result alone that the guard has to check.
     */
    private ItemStack placeableStack(FindItemResult item) {
        if (item.isOffhand()) return mc.player.getOffHandStack();
        if (item.slot() < 0 || item.slot() > 8) return ItemStack.EMPTY;
        return mc.player.getInventory().getStack(item.slot());
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

    private boolean yLevelAffectsBuild() {
        return switch (buildShape.get()) {
            case Flat -> true;
            case Walls, Solid -> false;
        };
    }

    /**
     * True while the build is being completed one Y layer at a time. Only Walls and Solid have
     * more than one layer.
     *
     * <p>Once every layer has been visited this flips back to false, and the tail of
     * {@code onTick} takes over again: that whole-selection scan is what repairs a hole left in an
     * already-verified layer (a placement the server rejected after the client had already
     * predicted it) and what gates the final "complete" message, so it is never printed over a
     * hole.
     */
    private boolean staging() {
        if (!layerByLayer.get() || buildShape.get() == BuildShape.Flat) return false;
        return layerIndex < layerCount();
    }

    /**
     * True when the active shape's layer order walks the selection top-down.
     */
    private boolean layerOrderDescending() {
        LayerOrder order = buildShape.get() == BuildShape.Solid ? solidLayerOrder.get() : wallsLayerOrder.get();
        return order == LayerOrder.TopToBottom;
    }

    /**
     * Ascending-Y boundaries of the cached build cells. All three shapes emit their cells Y-major
     * ascending, so a layer is always a contiguous range of that list.
     */
    private int[] layerBounds() {
        if (layerBounds == null) {
            List<BlockPos> cells = getBuildCells();
            List<Integer> starts = new ArrayList<>();
            int lastY = Integer.MIN_VALUE;
            for (int i = 0; i < cells.size(); i++) {
                int y = cells.get(i).getY();
                if (y != lastY) {
                    starts.add(i);
                    lastY = y;
                }
            }
            starts.add(cells.size());
            int[] bounds = new int[starts.size()];
            for (int i = 0; i < bounds.length; i++) bounds[i] = starts.get(i);
            layerBounds = bounds;
        }
        return layerBounds;
    }

    /**
     * Number of Y layers in the current selection.
     */
    private int layerCount() {
        return Math.max(0, layerBounds().length - 1);
    }

    /**
     * Ascending-range index of the layer being built, honouring the shape's layer order.
     */
    private int currentRange() {
        int count = layerCount();
        if (count == 0) return -1;
        int i = Math.min(Math.max(layerIndex, 0), count - 1);
        return layerOrderDescending() ? count - 1 - i : i;
    }

    /**
     * Y of the layer being built, or {@link #NO_LAYER} when every layer has been visited (or
     * staging is off), which leaves the target and Baritone goal scans unrestricted.
     */
    private int currentLayerY() {
        if (!staging()) return NO_LAYER;
        int range = currentRange();
        return range < 0 ? NO_LAYER : getBuildCells().get(layerBounds()[range]).getY();
    }

    /**
     * True when every cell of the layer being built is already solid in the world.
     *
     * <p>Checked against the world rather than against {@code pendingPlacements}, matching
     * AutoBeacon's layer recheck. The pending set is pruned at the top of every tick, so
     * {@code onTick} only ever reaches this method with pending entries the client world already
     * shows as solid; ignoring them here additionally makes a placement the client never applied
     * show up as still missing, so it is retried instead of being advanced past.
     */
    private boolean currentLayerComplete() {
        int range = currentRange();
        if (range < 0) return true;
        int[] bounds = layerBounds();
        List<BlockPos> cells = getBuildCells();
        for (int i = bounds[range]; i < bounds[range + 1]; i++) {
            if (needsBlock(mc.world.getBlockState(cells.get(i)))) return false;
        }
        return true;
    }

    /**
     * Moves on to the next layer. After the last one this drops out of staging, which hands the
     * build back to the whole-selection verification and repair in {@code onTick}.
     */
    private void advanceLayer() {
        int finishedY = currentLayerY();
        layerIndex++;
        // Otherwise a stale cooldown stalls the walk onto the new layer for up to
        // BARITONE_GOAL_COOLDOWN ticks.
        baritoneGoalCooldown = 0;
        if (staging()) info("§7Layer at Y=" + finishedY + " complete, starting the next layer.");
    }

    /**
     * Cells to consider this tick.
     *
     * <p>Flat and Walls just walk their cached list, which is small: Flat is a single Y plane and
     * Walls is only the four sides. Solid is the problem: it is the whole selection cuboid, so a
     * 64x64x64 area is ~262k cells, and this runs every build tick plus again in
     * {@link #baritoneArrived()} while Baritone walks. Walking the full cuboid to find the few
     * dozen cells within reach is pure waste, so Solid intersects the selection with a generous
     * reach box and enumerates only that sub-cuboid.
     *
     * <p>Note the earlier version of this already called {@code getBlockState} only after
     * {@code isWithinReach}, so a cheap bounds pre-filter saves no block lookups; the cost being
     * avoided is the iteration itself, which only not-generating the cells can fix.
     */
    private List<BlockPos> collectReachableCells() {
        List<BlockPos> cells = getBuildCells();

        if (buildShape.get() != BuildShape.Solid || cells.isEmpty()) return cells;

        BlockPos feet = mc.player.getBlockPos();
        // Generous box, then the exact isWithinReach test below. Over-inclusive on purpose: being
        // generous costs a few extra candidates, being tight risks dropping reachable cells.
        int r = (int) Math.ceil(mc.player.getBlockInteractionRange()) + 2;

        int x1 = Math.max(areaSelector.minX(), feet.getX() - r);
        int x2 = Math.min(areaSelector.maxX(), feet.getX() + r);
        int y1 = Math.max(areaSelector.minY(), feet.getY() - r - 2);
        int y2 = Math.min(areaSelector.maxY(), feet.getY() + r + 2);
        int z1 = Math.max(areaSelector.minZ(), feet.getZ() - r);
        int z2 = Math.min(areaSelector.maxZ(), feet.getZ() + r);

        List<BlockPos> near = new ArrayList<>();
        if (x1 > x2 || y1 > y2 || z1 > z2) return near;

        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int y = y1; y <= y2; y++) {
            for (int x = x1; x <= x2; x++) {
                for (int z = z1; z <= z2; z++) {
                    pos.set(x, y, z);
                    near.add(pos.toImmutable());
                }
            }
        }
        return near;
    }

    private BlockPos[] reachableTargets() {
        int layerY = currentLayerY();
        List<BlockPos> positions = new ArrayList<>();
        for (BlockPos pos : collectReachableCells()) {
            if (layerY != NO_LAYER && pos.getY() != layerY) continue;
            if (!PlayerUtils.isWithinReach(pos)) continue;
            if (pendingPlacements.contains(pos)) continue;
            if (!needsBlock(mc.world.getBlockState(pos))) continue;
            // A staged build stands us on the layer below, so our own hitbox sits in one cell of
            // the layer being built. That cell can never be placed from down here, and leaving it
            // in the list keeps the layer looking unfinished forever instead of letting the climb
            // onto the layer happen (the Baritone goal stands on the built part, from where the
            // cell below us is reachable).
            if (layerY != NO_LAYER && placementEngine.intersectsPlayer(pos)) continue;
            positions.add(pos);
        }
        return positions.toArray(new BlockPos[0]);
    }

    private FindItemResult findBuildBlockInHotbar() {
        if (cachedBuildSlot != -1) {
            ItemStack stack = mc.player.getInventory().getStack(cachedBuildSlot);
            if (isAllowedStack(stack)) {
                return new FindItemResult(cachedBuildSlot, stack.getCount());
            }
            cachedBuildSlot = -1;
        }

        FindItemResult item = InvUtils.findInHotbar(this::isAllowedStack);
        if (item.found()) cachedBuildSlot = item.slot();
        return item;
    }

    /**
     * Cells the active shape builds, memoized until the selection, the shape or the
     * build level changes (all are part of the cache key).
     */
    private List<BlockPos> getBuildCells() {
        int minX = areaSelector.minX();
        int maxX = areaSelector.maxX();
        int minY = areaSelector.minY();
        int maxY = areaSelector.maxY();
        int minZ = areaSelector.minZ();
        int maxZ = areaSelector.maxZ();
        BuildShape shape = buildShape.get();
        int y = yLevel.get();
        // yLevel is part of the key, not something invalidateFlatArea() has to be told about.
        // Flat's cells are getFlatArea(yLevel), and the move-up/down keys change yLevel without
        // touching the selection so a key-only-on-selection cache went stale across the normal
        // "select, adjust Y, start" flow. cancelBuild() only invalidates while a build is running,
        // which is precisely when yLevel cannot be changed. The render path reads yLevel directly
        // and does not use this cache, so the preview showed the new Y while placement, the
        // completion scan and the restock footprint all used the old one.
        if (cachedValid && cachedShape == shape && cachedYLevel == y
            && cachedMinX == minX && cachedMaxX == maxX
            && cachedMinY == minY && cachedMaxY == maxY
            && cachedMinZ == minZ && cachedMaxZ == maxZ) {
            return cachedBuildCells;
        }
        List<BlockPos> cells;
        switch (shape) {
            case Flat -> cells = areaSelector.getFlatArea(y);
            case Walls -> cells = areaSelector.getWalls();
            case Solid -> cells = areaSelector.getMatchingBlocks(p -> true);
            default -> cells = new ArrayList<>();
        }
        completionScanIndex = 0;   // the old index refers to the superseded list
        layerBounds = null;        // and so do the old layer boundaries
        cachedBuildCells = cells;
        cachedShape = shape;
        cachedYLevel = y;
        cachedMinX = minX;
        cachedMaxX = maxX;
        cachedMinY = minY;
        cachedMaxY = maxY;
        cachedMinZ = minZ;
        cachedMaxZ = maxZ;
        cachedValid = true;
        return cachedBuildCells;
    }

    private void invalidateFlatArea() {
        cachedValid = false;
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
     * Picks which configured block to restock. Walks the list round-robin and prefers an entry
     * whose shulker is actually in the inventory, so a build that lists several materials is not
     * stuck retrying the first entry's missing shulker.
     */
    private Item pickRestockItem() {
        List<Block> blocks = allowedBlocks.get();
        if (blocks.isEmpty()) return null;

        for (int i = 0; i < blocks.size(); i++) {
            int idx = Math.floorMod(restockBlockCursor + i, blocks.size());
            Item item = blocks.get(idx).asItem();
            if (hasShulkerWith(item)) {
                restockBlockCursor = idx + 1;
                return item;
            }
        }

        // None is available right now: still advance the cursor so the next attempt starts on a
        // different entry instead of hammering the first one.
        Item item = blocks.get(Math.floorMod(restockBlockCursor, blocks.size())).asItem();
        restockBlockCursor++;
        return item;
    }

    /**
     * True if a shulker box in the player's inventory holds the given item.
     */
    private boolean hasShulkerWith(Item item) {
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!ShulkerRestockEngine.isShulkerBox(stack)) continue;
            if (ShulkerRestockEngine.shulkerContains(stack, item)) return true;
        }
        return false;
    }

    private ShulkerRestockEngine.RestockConfig buildRestockConfig() {
        // No excluded positions, whether or not the box is broken afterwards. Excluding the
        // selection reads right for `shulker-break-after-fill` off (the box then stays in the
        // world), but it also rejects the only reachable spot whenever the player stands inside the
        // footprint - the normal case for Walls and Solid. findPlacement then returns null, abort()
        // says nothing, and the restock just stops. A box left inside the selection is visible and
        // breakable by hand; a restock that silently does nothing is not. Do not add it back.
        return new ShulkerRestockEngine.RestockConfig(
            restockPlaceRange.get(),
            restockAirPlace.get(),
            restockPreferSolidBlock.get(),
            restockBreakAfterFill.get(),
            restockAutoTake.get(),
            restockRotate.get(),
            restockShulkerHotbarSlot.get(),
            restockProtectedItems.get(),
            List.of(),
            restockMaxPlacementAttempts.get(),
            restockMaxOpenAttempts.get(),
            27,
            restockOnlySingleItem.get(),
            restockPacketBreak.get(),
            restockPacketBreakGrim.get(),
            restockCollectDropped.get(),
            useBaritone.get()
        );
    }

}
