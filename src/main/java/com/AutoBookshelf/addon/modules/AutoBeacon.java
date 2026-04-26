package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.*;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public class AutoBeacon extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgControls = settings.createGroup("Controls");
    private final SettingGroup sgPlacement = settings.createGroup("Placement");
    private final SettingGroup sgBaritone = settings.createGroup("Baritone");

    public enum OriginMode {
        PlayerPosition,
        SelectedBlock
    }

    // ----- Origin selection -----
    private final Setting<OriginMode> originMode = sgControls.add(new EnumSetting.Builder<OriginMode>()
        .name("origin-mode")
        .description("How to set the build location.")
        .defaultValue(OriginMode.SelectedBlock)
        .build()
    );

    private final Setting<Keybind> setOriginKey = sgControls.add(new KeybindSetting.Builder()
        .name("set-origin-key")
        .description("Key to set the build origin to the block you are looking at.")
        .defaultValue(Keybind.fromKey(-1))
        .visible(() -> originMode.get() == OriginMode.SelectedBlock)
        .build()
    );

    private final Setting<Keybind> buildKey = sgControls.add(new KeybindSetting.Builder()
        .name("build-key")
        .description("Key to start building the pyramid after origin is set.")
        .defaultValue(Keybind.fromKey(-1))
        .visible(() -> originMode.get() == OriginMode.SelectedBlock)
        .build()
    );

    private final Setting<Boolean> preview = sgRender.add(new BoolSetting.Builder()
        .name("preview")
        .description("Preview the planned pyramid before building starts.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> verticalOffset = sgGeneral.add(new IntSetting.Builder()
        .name("vertical-offset")
        .description("How many blocks above the origin the bottom layer will start.")
        .defaultValue(1)
        .min(0)
        .max(10)
        .build()
    );

    // ----- Placement settings -----
    private final Setting<Boolean> replaceGrass = sgPlacement.add(new BoolSetting.Builder()
        .name("replace-grass")
        .description("Replace grass, tall grass, seagrass, ferns, etc.")
        .defaultValue(true)
        .build()
    );

    // ----- Baritone assistance -----
    private final Setting<Boolean> useBaritone = sgBaritone.add(new BoolSetting.Builder()
        .name("use-baritone")
        .description("Use Baritone to move to out‑of‑reach block positions (still uses Meteor for placement).")
        .defaultValue(false)
        .build()
    );

    // ----- Mineral block selection -----
    private final Setting<List<Block>> allowedBlocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("allowed-blocks")
        .description("Which mineral blocks to use (iron, gold, emerald, diamond, netherite).")
        .defaultValue(Blocks.IRON_BLOCK, Blocks.GOLD_BLOCK, Blocks.EMERALD_BLOCK, Blocks.DIAMOND_BLOCK, Blocks.NETHERITE_BLOCK)
        .build()
    );

    private final Setting<Integer> delayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Ticks between each block placement.")
        .defaultValue(2)
        .min(0)
        .max(10)
        .build()
    );

    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("How many blocks to place per tick (recommended 1).")
        .defaultValue(1)
        .min(1)
        .max(5)
        .build()
    );

    private final Setting<Boolean> placeBeacons = sgGeneral.add(new BoolSetting.Builder()
        .name("place-beacons")
        .description("Place four beacons on top after building the pyramid.")
        .defaultValue(true)
        .build()
    );

    // ----- Render settings -----
    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Render the remaining blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the box is rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .defaultValue(new SettingColor(255, 255, 255, 20))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .defaultValue(new SettingColor(255, 255, 255, 200))
        .build()
    );

    private final Setting<SettingColor> previewColor = sgRender.add(new ColorSetting.Builder()
        .name("preview-color")
        .description("Color of the preview boxes before building starts.")
        .defaultValue(new SettingColor(0, 255, 0, 70))
        .visible(preview::get)
        .build()
    );

    // ----- State -----
    private BlockPos origin = null;
    private List<BlockPos> blocksToPlace = new ArrayList<>();
    private List<BlockPos> beaconsToPlace = new ArrayList<>();
    private List<BlockPos> remainingBlocks = new ArrayList<>();
    private boolean placingBeacons = false;
    private boolean building = false;
    private boolean originSet = false;
    private int placeTimer = 0;
    private boolean waitingForBaritone = false;
    private BlockPos currentTargetPos = null;
    private IBaritone baritone = null;

    private BlockPos pendingVerification = null;

    public AutoBeacon() {
        super(Addon.CATEGORY, "auto-beacon", "Builds a 4‑beacon pyramid at a selected location");
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
            origin = mc.player.getBlockPos();
            generateBlockList();
            originSet = true;
            building = true;
            info("Building pyramid at player position (offset by " + verticalOffset.get() + " blocks up)");
        }
    }

    private void resetState() {
        blocksToPlace.clear();
        beaconsToPlace.clear();
        remainingBlocks.clear();
        placingBeacons = false;
        building = false;
        originSet = false;
        placeTimer = 0;
        waitingForBaritone = false;
        currentTargetPos = null;
        origin = null;
        pendingVerification = null;
    }

    @Override
    public void onDeactivate() {
        if (baritone != null) baritone.getPathingBehavior().cancelEverything();
        resetState();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        // --- Origin setting (selected block mode) ---
        if (originMode.get() == OriginMode.SelectedBlock) {
            if (!originSet && setOriginKey.get().isPressed()) {
                if (mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.BLOCK) {
                    BlockHitResult hit = (BlockHitResult) mc.crosshairTarget;
                    origin = hit.getBlockPos();
                    generateBlockList();
                    originSet = true;
                    info("Build origin set to " + origin.getX() + ", " + origin.getY() + ", " + origin.getZ() +
                        " (bottom layer starts " + verticalOffset.get() + " blocks above)");
                    info("Press your build key to start constructing");
                } else {
                    error("You must look at a block to set origin");
                }
                return;
            }

            if (originSet && !building && buildKey.get().isPressed()) {
                building = true;
                remainingBlocks.clear();
                remainingBlocks.addAll(blocksToPlace);
                info("Starting construction...");
                return;
            }
        }

        if (!building) return;

        // --- Verification of previous placement (with 5 tick delay) ---
        if (pendingVerification != null) {
            Block expected = findExpectedBlock();
            BlockState state = mc.world.getBlockState(pendingVerification);
            if (expected != null && state.getBlock() == expected) {
                remainingBlocks.remove(pendingVerification);
            }
            pendingVerification = null;
            placeTimer = delayTicks.get();
            return;
        }

        // Handle Baritone movement if waiting for a target
        if (waitingForBaritone && baritone != null && useBaritone.get()) {
            if (!baritone.getPathingBehavior().isPathing() && PlayerUtils.isWithinReach(currentTargetPos)) {
                waitingForBaritone = false;
                baritone.getPathingBehavior().cancelEverything();
                currentTargetPos = null;
                placeTimer = 0;
            }
            return;
        }

        // Delay between placements
        if (placeTimer > 0) {
            placeTimer--;
            return;
        }

        // Process placements (pyramid + beacons)
        if (!placingBeacons) {
            boolean placedSomething = false;
            for (int i = 0; i < blocksPerTick.get() && !remainingBlocks.isEmpty(); i++) {
                BlockPos target = findNextPlaceable();
                if (target == null) {
                    if (useBaritone.get() && baritone != null && !waitingForBaritone) {
                        BlockPos closest = findClosestBlock();
                        if (closest != null) {
                            BlockPos goal = getBaritoneGoal(closest);
                            if (goal != null) {
                                waitingForBaritone = true;
                                currentTargetPos = closest;
                                baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(goal));
                                info("Moving to " + goal.toShortString());
                                return;
                            }
                        }
                    }
                    break;
                }
                if (placeBlock(target)) {
                    pendingVerification = target;
                    placeTimer = delayTicks.get() + 5;
                    placedSomething = true;
                    return;
                } else {
                    return;
                }
            }
            if (placedSomething) return;

            if (remainingBlocks.isEmpty()) {
                if (placeBeacons.get() && !beaconsToPlace.isEmpty()) {
                    placingBeacons = true;
                    remainingBlocks.clear();
                    remainingBlocks.addAll(beaconsToPlace);
                    info("Pyramid is done, Placing beacons...");
                } else {
                    finish();
                }
            }
        } else {
            // Beacon placement
            boolean placedBeacon = false;
            for (int i = 0; i < blocksPerTick.get() && !remainingBlocks.isEmpty(); i++) {
                BlockPos target = findNextPlaceable();
                if (target == null) {
                    if (useBaritone.get() && baritone != null && !waitingForBaritone) {
                        BlockPos closest = findClosestBlock();
                        if (closest != null) {
                            BlockPos goal = getBaritoneGoal(closest);
                            if (goal != null) {
                                waitingForBaritone = true;
                                currentTargetPos = closest;
                                baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(goal));
                                info("Moving to " + goal.toShortString());
                                return;
                            }
                        }
                    }
                    break;
                }
                if (placeBeacon(target)) {
                    pendingVerification = target;
                    placeTimer = delayTicks.get() + 5;
                    placedBeacon = true;
                    return;
                } else {
                    return;
                }
            }
            if (placedBeacon) return;

            if (remainingBlocks.isEmpty()) finish();
        }
    }

    // --- New helper: decide where Baritone should go ---
    private BlockPos getBaritoneGoal(BlockPos target) {
        if (isInsidePyramidBoundingBox(target)) {
            // Must stand outside the pyramid – return a safe adjacent spot
            return findSafeAdjacent(target);
        } else {
            // Target is outside the pyramid; walking directly to it is safe
            return target;
        }
    }

    private BlockPos findNextPlaceable() {
        for (BlockPos pos : remainingBlocks) {
            if (canPlaceAtPosition(pos) && isPosPlaceableThroughWall(pos)) {
                return pos;
            }
        }
        return null;
    }

    private BlockPos findClosestBlock() {
        BlockPos playerPos = mc.player.getBlockPos();
        double closestDist = Double.MAX_VALUE;
        BlockPos closest = null;
        for (BlockPos pos : remainingBlocks) {
            double dist = pos.getSquaredDistance(playerPos);
            if (dist < closestDist) {
                closestDist = dist;
                closest = pos;
            }
        }
        return closest;
    }

    private boolean canPlaceAtPosition(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        if (state.isAir()) return true;
        if (!replaceGrass.get()) return false;
        Block block = state.getBlock();
        return block == Blocks.GRASS_BLOCK ||
            block == Blocks.TALL_GRASS ||
            block == Blocks.FERN ||
            block == Blocks.SEAGRASS ||
            block == Blocks.TALL_SEAGRASS;
    }

    private boolean isPosPlaceableThroughWall(BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.offset(dir);
            if (!mc.world.getBlockState(neighbor).isReplaceable() && PlayerUtils.isWithinReach(neighbor)) {
                return true;
            }
        }
        return false;
    }

    private BlockPos findSafeAdjacent(BlockPos target) {
        BlockPos playerPos = mc.player.getBlockPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        Vec3d targetCenter = target.toCenterPos();
        double reach = mc.player.getBlockInteractionRange();

        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    BlockPos p = target.add(dx, dy, dz);
                    if (!mc.world.getBlockState(p).isAir()) continue;
                    if (isInsidePyramidBoundingBox(p)) continue;
                    if (p.toCenterPos().distanceTo(targetCenter) > reach) continue;
                    double dist = p.getSquaredDistance(playerPos);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = p;
                    }
                }
            }
        }
        return best == null ? null : best.toImmutable();
    }

    private boolean isInsidePyramidBoundingBox(BlockPos pos) {
        if (origin == null) return false;
        int baseY = origin.getY() + verticalOffset.get();
        for (int y = baseY; y <= baseY + 3; y++) {
            int dy = y - baseY;
            int minX = origin.getX() - 3 + dy;
            int maxX = origin.getX() + 6 - dy;
            int minZ = origin.getZ() - 3 + dy;
            int maxZ = origin.getZ() + 6 - dy;
            if (pos.getY() == y && pos.getX() >= minX && pos.getX() <= maxX
                && pos.getZ() >= minZ && pos.getZ() <= maxZ) {
                return true;
            }
        }
        return false;
    }

    private Block findExpectedBlock() {
        for (Block b : allowedBlocks.get()) {
            if (InvUtils.testInHands(stack -> stack.getItem() instanceof BlockItem bi && bi.getBlock() == b)) {
                return b;
            }
        }
        if (placingBeacons) return Blocks.BEACON;
        return null;
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
            BlockPos neighbor = pos.offset(dir);
            BlockState neighborState = mc.world.getBlockState(neighbor);
            if (neighborState.isReplaceable()) continue;
            if (!PlayerUtils.isWithinReach(neighbor)) continue;

            Vec3d hitPos = Vec3d.ofCenter(neighbor).add(
                dir.getOffsetX() * 0.5,
                dir.getOffsetY() * 0.5,
                dir.getOffsetZ() * 0.5
            );
            Direction face = dir.getOpposite();
            BlockHitResult hit = new BlockHitResult(hitPos, face, neighbor, false);

            int prevSlot = mc.player.getInventory().selectedSlot;
            Rotations.rotate(Rotations.getYaw(hitPos), Rotations.getPitch(hitPos), () -> {
                InvUtils.swap(item.slot(), true);
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                mc.player.swingHand(Hand.MAIN_HAND);
            });
            InvUtils.swapBack();
            return true;
        }
        InvUtils.swap(item.slot(), true);
        boolean result = BlockUtils.place(pos, item, true, 50, true, true);
        InvUtils.swapBack();
        return result;
    }

    private FindItemResult findBlockInHotbar() {
        ItemStack mainHand = mc.player.getMainHandStack();
        if (!mainHand.isEmpty() && mainHand.getItem() instanceof BlockItem handBlock) {
            if (allowedBlocks.get().contains(handBlock.getBlock())) {
                return new FindItemResult(mc.player.getInventory().selectedSlot, mainHand.getCount());
            }
        }
        ItemStack offHand = mc.player.getOffHandStack();
        if (!offHand.isEmpty() && offHand.getItem() instanceof BlockItem offBlock) {
            if (allowedBlocks.get().contains(offBlock.getBlock())) {
                return new FindItemResult(SlotUtils.OFFHAND, offHand.getCount());
            }
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem bi) {
                if (allowedBlocks.get().contains(bi.getBlock())) {
                    return new FindItemResult(i, stack.getCount());
                }
            }
        }
        for (Block allowed : allowedBlocks.get()) {
            FindItemResult result = InvUtils.find(stack ->
                stack.getItem() instanceof BlockItem bi && bi.getBlock() == allowed
            );
            if (result.found() && !result.isHotbar()) {
                FindItemResult empty = InvUtils.find(ItemStack::isEmpty, 0, 8);
                if (empty.found()) {
                    InvUtils.move().from(result.slot()).toHotbar(empty.slot());
                    return InvUtils.findInHotbar(stack ->
                        stack.getItem() instanceof BlockItem bi && bi.getBlock() == allowed
                    );
                } else {
                    error("Block in inventory but hotbar full.");
                    return new FindItemResult(-1, 0);
                }
            }
        }
        return new FindItemResult(-1, 0);
    }

    private FindItemResult findBeaconInInventory() {
        FindItemResult result = InvUtils.findInHotbar(stack -> stack.getItem() == Items.BEACON);
        if (result.found()) return result;
        result = InvUtils.find(stack -> stack.getItem() == Items.BEACON);
        if (result.found() && !result.isHotbar()) {
            FindItemResult empty = InvUtils.find(ItemStack::isEmpty, 0, 8);
            if (empty.found()) {
                InvUtils.move().from(result.slot()).toHotbar(empty.slot());
                return InvUtils.findInHotbar(stack -> stack.getItem() == Items.BEACON);
            } else {
                error("Beacon in inventory but hotbar full");
            }
        }
        return new FindItemResult(-1, 0);
    }

    private void generateBlockList() {
        blocksToPlace.clear();
        beaconsToPlace.clear();
        if (origin == null) return;

        int baseY = origin.getY() + verticalOffset.get();
        for (int y = baseY; y <= baseY + 3; y++) {
            int offset = y - baseY;
            int startX = origin.getX() - 3 + offset;
            int endX = origin.getX() + 6 - offset;
            int startZ = origin.getZ() - 3 + offset;
            int endZ = origin.getZ() + 6 - offset;
            for (int x = startX; x <= endX; x++) {
                for (int z = startZ; z <= endZ; z++) {
                    blocksToPlace.add(new BlockPos(x, y, z));
                }
            }
        }
        if (placeBeacons.get()) {
            int beaconY = baseY + 4;
            beaconsToPlace.add(new BlockPos(origin.getX() + 1, beaconY, origin.getZ() + 1));
            beaconsToPlace.add(new BlockPos(origin.getX() + 2, beaconY, origin.getZ() + 1));
            beaconsToPlace.add(new BlockPos(origin.getX() + 1, beaconY, origin.getZ() + 2));
            beaconsToPlace.add(new BlockPos(origin.getX() + 2, beaconY, origin.getZ() + 2));
        }
    }

    private void finish() {
        info("beacon pyramid built successfully!");
        building = false;
        toggle();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get()) return;
        if (!originSet) return;

        if (building) {
            for (BlockPos pos : remainingBlocks) {
                event.renderer.box(pos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            }
        } else if (preview.get()) {
            for (BlockPos pos : blocksToPlace) {
                event.renderer.box(pos, previewColor.get(), previewColor.get(), shapeMode.get(), 0);
            }
            for (BlockPos pos : beaconsToPlace) {
                event.renderer.box(pos, previewColor.get(), previewColor.get(), shapeMode.get(), 0);
            }
        }
    }
}
