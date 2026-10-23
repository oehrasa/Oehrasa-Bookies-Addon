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
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class DriedGhastPlacer extends Module {
    private enum Stage {
        IDLE,
        // Ice-mode stages
        ROTATE_TO_PLACE_ICE, PLACE_ICE, WAIT_ICE,
        ROTATE_TO_BREAK, BREAK_ICE, WAIT_BREAK,
        // Common stages
        ROTATE_TO_PLACE_GHAST, PLACE_GHAST, WAIT_GHAST
    }

    /**
     * Where to walk once the strip in front of the player has been used up.
     */
    private enum MoveMode {Backwards, Sideways, Alternate}

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

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

    private final Setting<Integer> breakTimeout = sgGeneral.add(new IntSetting.Builder()
        .name("break-timeout")
        .description("Ticks to keep mining the ice before giving up on the cycle.")
        .defaultValue(40)
        .min(10)
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
    private BlockPos targetPos;
    private BlockPos supportBlockPos;
    private int delayTicks = 0;
    private int placedCount = 0;
    private int breakWaitTicks = 0;
    private int breakWaitStep = 0;
    private Block driedGhastBlock = null;
    private int iceSlot = -1, pickSlot = -1, ghastSlot = -1;

    /**
     * Frozen on activate: placement turns the view towards its targets (see rotate), so
     * deriving the axis from yaw every cycle would flip the strip around mid-field.
     */
    private Direction stripAxis = Direction.SOUTH;
    /**
     * Relocation state. The walk is a plain straight-line walk toward {@link #relocateGoal} -
     * Baritone was removed, so there is no pathing, no goal process and no Baritone dependency.
     */
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
        stripAxis = mc.player != null ? mc.player.getHorizontalFacing() : Direction.SOUTH;
    }

    @Override
    public void onDeactivate() {
        stage = Stage.IDLE;
        // Release the held movement keys. A walk that ends without this leaves the player
        // walking (and possibly bunny-hopping) for the rest of the session.
        stopWalking();
        relocateGoal = null;
        relocateWaitTicks = 0;
    }

    private void resetState() {
        stage = Stage.IDLE;
        targetPos = supportBlockPos = null;
        delayTicks = 0;
        breakWaitTicks = 0;
        breakWaitStep = 0;
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
        if (mc.player == null || mc.world == null) return;
        if (maxBlocks.get() > 0 && placedCount >= maxBlocks.get()) {
            info("Placed " + placedCount + " dried ghast blocks (limit reached).");
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
                info("§eCouldn't reach a fresh strip, retrying.");
                delayTicks = 20;
            }
            if (relocating) return;
        }

        if (delayTicks > 0) { delayTicks--; return; }

        switch (stage) {
            case IDLE -> prepare();
            // Ice mode stages
            case ROTATE_TO_PLACE_ICE -> placeIce();
            case PLACE_ICE -> executePlaceIce();
            case WAIT_ICE -> checkIce();
            case ROTATE_TO_BREAK -> breakIce();
            case BREAK_ICE -> executeBreakIce();
            case WAIT_BREAK -> checkBreak();
            // Normal stages
            case ROTATE_TO_PLACE_GHAST -> placeGhast();
            case PLACE_GHAST -> executePlaceGhast();
            case WAIT_GHAST -> finishCycle();
        }
    }

    // Idle: find items and a valid position
    private void prepare() {
        if (!ensureItems()) return;

        if (fastMode.get()) {
            // Fast mode: find existing water source on a solid block
            if (!findWaterPlacementPosition()) {
                relocateOrRetry();
                return;
            }
            InvUtils.swap(ghastSlot, true);
            if (rotate.get()) {
                Rotations.rotate(Rotations.getYaw(Vec3d.ofCenter(supportBlockPos).add(0, 0.5, 0)),
                    Rotations.getPitch(Vec3d.ofCenter(supportBlockPos).add(0, 0.5, 0)));
                delayTicks = 2;
            }
            stage = Stage.ROTATE_TO_PLACE_GHAST;
            return;
        }

        if (!findTopPlacementPosition()) {
            relocateOrRetry();
            return;
        }

        InvUtils.swap(iceSlot, true);
        if (rotate.get()) {
            Vec3d hitVec = Vec3d.ofCenter(supportBlockPos).add(0, 0.5, 0);
            Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec));
            delayTicks = 2;
        }
        stage = Stage.ROTATE_TO_PLACE_ICE;
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
            info("§cNo dried ghast block in hotbar.");
            toggle();
            return false;
        }

        Item item = mc.player.getInventory().getStack(ghastSlot).getItem();
        if (item instanceof BlockItem bi) driedGhastBlock = bi.getBlock();
        else {
            info("§cDried ghast item is not a block.");
            toggle();
            return false;
        }

        if (fastMode.get()) return true;

        // Ice mode: need ice and a valid pickaxe
        iceSlot = findSlot(s -> s.getItem() == Items.ICE);
        if (iceSlot == -1) {
            info("§cNo ice blocks in hotbar.");
            toggle();
            return false;
        }

        pickSlot = findSlot(this::isValidPickaxe);
        if (pickSlot == -1) {
            info("§cNo valid pickaxe in hotbar.");
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

        BlockPos feet = mc.player.getBlockPos();
        if (!feet.equals(lastScanFeet)) {
            // The player moved: a cell rejected from the old spot is worth retrying, and a
            // saturated strip there says nothing about the new spot.
            rejectedTargets.clear();
            stripSaturatedNotified = false;
            lastScanFeet = feet;
        }

        Direction side = stripAxis.rotateYClockwise();
        double rangeVal = range.get();
        double maxDistSq = rangeVal * rangeVal;
        int maxFwd = (int) rangeVal;
        int minFwd = Math.max(1, Math.min(minPlaceDistance.get(), maxFwd));
        int half = (stripWidth.get() - 1) / 2;

        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int fwd = maxFwd; fwd >= minFwd; fwd--)               // farthest first
            for (int dy = -maxFwd; dy <= maxFwd; dy++)             // ground rows before stacked ones
                for (int lat = -half; lat <= stripWidth.get() - 1 - half; lat++) {
                    pos.set(
                        feet.getX() + stripAxis.getOffsetX() * fwd + side.getOffsetX() * lat,
                        feet.getY() + dy,
                        feet.getZ() + stripAxis.getOffsetZ() * fwd + side.getOffsetZ() * lat);

                    // fwd >= minFwd >= 1 makes a collision with the player's own cells
                    // impossible, but keep the guard so a future edit can't reintroduce it.
                    if (pos.equals(feet) || pos.equals(feet.up())) continue;
                    if (pos.getSquaredDistance(feet) > maxDistSq) continue;
                    if (rejectedTargets.contains(pos.asLong())) continue;

                    BlockState ts = mc.world.getBlockState(pos);
                    if (requireWater) {
                        if (ts.getBlock() != Blocks.WATER) continue;
                    } else {
                        if (!ts.isAir() && ts.getBlock() != Blocks.WATER) continue;
                    }
                    if (ts.getBlock() == driedGhastBlock) continue;

                    BlockPos below = pos.down();

                    BlockState bs = mc.world.getBlockState(below);
                    if (!bs.isFullCube(mc.world, below)) continue;
                    if (bs.getBlock() == driedGhastBlock) continue;
                    // Our own abandoned (or in-flight) ice is not a stable support: it is
                    // about to be broken, or already was, so building on it risks a floating
                    // block once the ice melts away.
                    if (bs.getBlock() == Blocks.ICE) continue;

                    // Skip if any entity (other than the local player) occupies this block.
                    // Deliberately last: this is the only expensive condition (it queries the
                    // entity lookup and allocates a result list for the block's AABB), while
                    // nearly every position in the sweep already failed a check above.
                    if (!mc.world.getOtherEntities(null, new Box(pos)).isEmpty()) continue;

                    targetPos = new BlockPos(pos.getX(), pos.getY(), pos.getZ());
                    supportBlockPos = new BlockPos(below.getX(), below.getY(), below.getZ());
                    relocateFailures = 0;
                    lastRelocationArrived = false;
                    stripSaturatedNotified = false;
                    return true;
                }
        return false;
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
            info("§eNo free cell left in the strip - move somewhere new.");
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

        Direction side = stripAxis.rotateYClockwise();
        BlockPos feet = mc.player.getBlockPos();
        boolean backwards = moveMode.get() == MoveMode.Backwards
            || (moveMode.get() == MoveMode.Alternate && relocateMoves % 2 == 0);

        BlockPos goal;
        if (backwards) {
            // Retreat by roughly one strip's worth of depth, so the walk buys a full strip
            // of fresh cells instead of a single row.
            int retreat = Math.max(1, (int) Math.floor(range.get() - minPlaceDistance.get()));
            goal = feet.offset(stripAxis, -retreat);
        } else {
            goal = feet.offset(side, stripWidth.get());
        }

        // Find a goal that is genuinely on solid walkable ground, searching the cell and the two
        // below it. If nothing passes we bail rather than walking to the raw target, which is
        // unchecked and can be water, lava, or a cell over a drop.
        BlockPos goalGround = null;
        for (int dy = 0; dy >= -2 && goal.getY() >= mc.world.getBottomY(); dy--) {
            BlockPos test = new BlockPos(goal.getX(), goal.getY() + dy, goal.getZ());
            BlockPos belowTest = test.down();
            BlockState testState = mc.world.getBlockState(test);
            // Accept air OR water. Water has no collision shape (LiquidBlock.getCollisionShape
            // resolves to Blocks.AIR's empty shape) so the player walks straight through it, and
            // it is the target medium for this module anyway; this matches the cell test
            // findPosition already uses. Lava is still rejected: no collision either, but
            // walking into it kills you. Support below must be solid.
            if (mc.world.getBlockState(belowTest).isFullCube(mc.world, belowTest)
                && (testState.isAir() || testState.getBlock() == Blocks.WATER)) {
                goalGround = test;
                break;
            }
        }
        if (goalGround == null) return false;   // relocateOrRetry falls back to its notify path
        goal = goalGround;

        relocateStep = Math.abs(goal.getX() - feet.getX()) + Math.abs(goal.getZ() - feet.getZ());
        relocateMoves++;
        relocateGoal = goal;
        relocateWaitTicks = 0;
        relocating = true;
        return true;
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

        Vec3d target = Vec3d.ofCenter(relocateGoal);
        double dx = target.x - mc.player.getX();
        double dy = target.y - mc.player.getY();
        double dz = target.z - mc.player.getZ();
        double horiz = DistanceUtil.distanceXZ(
            mc.player.getX(), mc.player.getZ(), target.x, target.z);

        mc.player.setYaw((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90));
        if (horiz > 1.0E-4) {
            mc.player.setPitch((float) -Math.toDegrees(Math.atan2(dy, horiz)));
        }

        mc.options.forwardKey.setPressed(true);
        // Jump only when it is genuinely above us and close enough that a jump can reach it;
        // holding jump while far away just stutters the walk.
        mc.options.jumpKey.setPressed(dy > 0.8 && horiz < 2.0);
    }

    /**
     * Releases the held movement keys. Every walk exit path must call this.
     */
    private void stopWalking() {
        if (mc.options == null) return;
        mc.options.forwardKey.setPressed(false);
        // The walk can hold jump; leaving it down would bunny-hop the player through the field
        // long after the walk ended.
        mc.options.jumpKey.setPressed(false);
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

    private void placeIce() { stage = Stage.PLACE_ICE; }

    private void executePlaceIce() {
        Vec3d hitVec = Vec3d.ofCenter(supportBlockPos).add(0, 0.5, 0);
        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, supportBlockPos, false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);
        confirmWaitTicks = 0;
        delayTicks = 2;
        stage = Stage.WAIT_ICE;
    }

    private void checkIce() {
        if (mc.world.getBlockState(targetPos).getBlock() != Blocks.ICE) {
            // Bounded like checkBreak's wait: a placement the server rejects produces no
            // ice, and this used to spin forever with no way out. This runs once per
            // delay (every 2+1 ticks), so charge the check interval like checkBreak does -
            // counting checks alone stretched the real wait to ~3x the intended budget.
            confirmWaitTicks += 3;
            if (confirmWaitTicks > PLACE_CONFIRM_TIMEOUT) {
                info("§cNo ice appeared at the target, skipping this spot.");
                abortCycle();
                return;
            }
            delayTicks = 2;
            return;
        }
        confirmWaitTicks = 0;
        InvUtils.swapBack(); InvUtils.swap(pickSlot, true);
        if (rotate.get()) Rotations.rotate(Rotations.getYaw(Vec3d.ofCenter(targetPos)), Rotations.getPitch(Vec3d.ofCenter(targetPos)), -100, () -> {});
        delayTicks = 2; stage = Stage.ROTATE_TO_BREAK;
    }
    private void breakIce() { stage = Stage.BREAK_ICE; }

    private void executeBreakIce() {
        sendBreakPackets();
        breakWaitTicks = 0;
        breakWaitStep = 4;
        delayTicks = 4;
        stage = Stage.WAIT_BREAK;
    }

    private void sendBreakPackets() {
        mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, targetPos, Direction.UP));
        mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, targetPos, Direction.UP));
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    /**
     * WAIT_BREAK. The break packets sent once by executeBreakIce are not enough on
     * their own: the ice is still there on the next tick, so keep re-sending them
     * until it breaks. The whole wait is bounded by break-timeout, because otherwise
     * the state spun until water appeared and hung forever whenever the ice never
     * broke (wrong tool, silk touch, another player breaking it first) or broke
     * into plain air instead of exposing water.
     */
    private void checkBreak() {
        BlockState state = mc.world.getBlockState(targetPos);
        if (state.getBlock() == Blocks.ICE) {
            if (rotate.get())
                Rotations.rotate(Rotations.getYaw(Vec3d.ofCenter(targetPos)), Rotations.getPitch(Vec3d.ofCenter(targetPos)), -100, () -> {
                });
            sendBreakPackets();
        } else if (state.getBlock() == Blocks.WATER) {
            // Ice is gone and did its job - clear the wait counters and hand over.
            breakWaitTicks = 0;
            breakWaitStep = 0;
            confirmWaitTicks = 0;
            checkWater();
            return;
        }

        // Still ice, or ice already gone with no water yet: keep the bounded wait
        // running, and give up once the budget is spent. The budget is in ticks, but
        // this runs once per delay rather than once per tick, so charge the wait the
        // delay it just spent plus the tick this check itself ran on. Counting calls
        // instead stretched the real wait to roughly three times break-timeout.
        breakWaitTicks += breakWaitStep + 1;
        if (breakWaitTicks > breakTimeout.get()) {
            info("§cNo water after " + breakTimeout.get() + " ticks, skipping this spot.");
            abortCycle();
            return;
        }
        breakWaitStep = 2;
        delayTicks = 2;
    }

    /**
     * Abandons the current placement cycle without touching the placed-block count.
     * The target moves to the rejected list: it still looks free to the scan (air or
     * water), so without this the next prepare() would pick the exact same cell and the
     * module would loop on it forever.
     */
    private void abortCycle() {
        if (targetPos != null) rejectedTargets.add(targetPos.asLong());
        InvUtils.swapBack();
        stage = Stage.IDLE;
        targetPos = supportBlockPos = null;
        breakWaitTicks = 0;
        breakWaitStep = 0;
    }

    private void checkWater() {
        if (mc.world.getBlockState(targetPos).getBlock() != Blocks.WATER) {
            // A water cell that was there at scan time can vanish before we get here
            // (flowing away, scooped up); don't spin on it forever. Same tick-charging
            // as checkIce/checkBreak: this runs once every 2+1 ticks, so charge 3 per call.
            confirmWaitTicks += 3;
            if (confirmWaitTicks > PLACE_CONFIRM_TIMEOUT) {
                info("§cNo water at the target, skipping this spot.");
                abortCycle();
                return;
            }
            delayTicks = 2;
            return;
        }
        confirmWaitTicks = 0;
        InvUtils.swapBack(); InvUtils.swap(ghastSlot, true);
        if (rotate.get()) Rotations.rotate(Rotations.getYaw(Vec3d.ofCenter(targetPos)), Rotations.getPitch(Vec3d.ofCenter(targetPos)), -100, () -> {});
        delayTicks = 2; stage = Stage.ROTATE_TO_PLACE_GHAST;
    }

    // Place dried ghast
    private void placeGhast() { stage = Stage.PLACE_GHAST; }
    private void executePlaceGhast() {
        Vec3d hitVec = Vec3d.ofCenter(supportBlockPos).add(0, 0.5, 0);
        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, supportBlockPos, false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);
        delayTicks = 2; stage = Stage.WAIT_GHAST;
    }

    private void finishCycle() {
        InvUtils.swapBack();
        placedCount++;
        stage = Stage.IDLE;
        targetPos = supportBlockPos = null;
        delayTicks = placeDelay.get();
    }

    private int findSlot(java.util.function.Predicate<ItemStack> filter) {
        for (int i = 0; i < 9; i++) if (filter.test(mc.player.getInventory().getStack(i))) return i;
        return -1;
    }
    private boolean isValidPickaxe(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (!stack.isIn(ItemTags.PICKAXES)) return false;
        var ench = stack.get(net.minecraft.component.DataComponentTypes.ENCHANTMENTS);
        if (ench != null) for (var entry : ench.getEnchantmentEntries())
            if (entry.getKey().matchesId(net.minecraft.util.Identifier.ofVanilla("silk_touch"))) return false;
        return true;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || targetPos == null) return;
        event.renderer.box(targetPos, sideColor.get(), lineColor.get(), ShapeMode.Both, 0);
    }
}
