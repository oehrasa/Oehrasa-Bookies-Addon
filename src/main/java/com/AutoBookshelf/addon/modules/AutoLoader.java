package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.AbstractContainerScreenAccessor;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class AutoLoader extends Module {
    private enum Stage {
        IDLE,
        SETUP,
        PLACE,
        USE_ITEM,
        CENTER,
        PREPARE_SECOND_ECHEST,
        PLACE_SECOND_ECHEST,
        OPEN,
        WAIT_CLOSE,
        BREAK,
        BREAK_SECOND_ECHEST
    }

    // Mutable state
    private Stage stage = Stage.IDLE;
    private boolean isEnderChest = false;
    private boolean isBundle = false;
    private int containerInvSlot = -1;
    private int containerHotbarSlot = -1;

    /**
     * Position of the block to open right now (usually the first chest).
     */
    private BlockPos placedPos = null;
    /**
     * Where the first chest was placed.
     */
    private BlockPos firstPos = null;
    /**
     * Where the second chest was placed (null if none).
     */
    private BlockPos secondPos = null;
    /**
     * Horizontal direction the player faced when placing the first chest.
     */
    private Direction firstFacing = null;
    private Direction firstApproachFacing = null;
    /**
     * Yaw used for the first chest's placement hit vector.
     * Locked in when placing the second chest so both chests share the same facing direction.
     */
    private float firstYaw = 0f;
    private int delayTicks = 0;
    private boolean pickaxeEquipped = false;

    private boolean packetBreakSent = false;
    private int preBreakSlot = -1;
    private int openAttempts = 0;
    private int breakAttempts = 0;
    private int secondPlaceAttempts = 0;
    private int preActionSlot = -1;
    private boolean sneaking = false;

    private int freeingHotbarSlot = -1;
    private int freeSlotWaitTicks = 0;

    /**
     * Positions where placement was attempted but never materialized
     * (e.g. blocked by the player's own hitbox), so findPlacement() can
     * skip them on retry instead of looping on the same spot forever.
     */
    private final List<BlockPos> failedPositions = new ArrayList<>();

    /**
     * Server-side rotation lock, reapplied every tick until released.
     */
    private boolean rotationLocked = false;
    private float lockYaw = 0f;
    private float lockPitch = 0f;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPlacement = settings.createGroup("Placement");

    private final Setting<Boolean> instantShulker = sgGeneral.add(new BoolSetting.Builder()
        .name("instant-shulker")
        .description("Right-click a shulker box in your inventory to instantly place and open it.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> instantEChest = sgGeneral.add(new BoolSetting.Builder()
        .name("instant-ender-chest")
        .description("Right-click an ender chest in your inventory to instantly place and open it.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> instantBundle = sgGeneral.add(new BoolSetting.Builder()
        .name("instant-bundle")
        .description("Right-click a bundle in your inventory to instantly select and use it (no placing).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> breakAfterUse = sgGeneral.add(new BoolSetting.Builder()
        .name("break-after-use")
        .description("Automatically break the container after you close its GUI.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> doubleEChest = sgGeneral.add(new BoolSetting.Builder()
        .name("double-ender-chest")
        .description("Place a second ender chest to the left of the first, " +
            "then open the first one. Both are broken after you close.")
        .defaultValue(false)
        .visible(() -> instantEChest.get() && breakAfterUse.get())
        .build()
    );

    private final Setting<Integer> centerDelay = sgPlacement.add(new IntSetting.Builder()
        .name("center-delay")
        .description("Ticks to wait after centering before placing the second ender chest.")
        .defaultValue(10)
        .min(1)
        .sliderMax(40)
        .visible(() -> instantEChest.get() && doubleEChest.get())
        .build()
    );

    private final Setting<Boolean> packetBreak = sgGeneral.add(new BoolSetting.Builder()
        .name("packet-break")
        .description("Use instant packet based breaking")
        .defaultValue(true)
        .visible(breakAfterUse::get)
        .build()
    );

    private final Setting<Boolean> packetBreakGrim = sgGeneral.add(new BoolSetting.Builder()
        .name("grim-v3-packet")
        .description("Use the Grim-v3 packet breaking sequence.")
        .defaultValue(true)
        .visible(() -> breakAfterUse.get() && packetBreak.get())
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Send server-side rotation packets when placing or interacting with containers.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> hotbarSlotSetting = sgGeneral.add(new IntSetting.Builder()
        .name("hotbar-slot")
        .description("Preferred hotbar slot (1-9) to move the container into.")
        .defaultValue(1)
        .min(1)
        .max(9)
        .sliderRange(1, 9)
        .build()
    );

    private final Setting<List<Item>> protectedItems = sgGeneral.add(new ItemListSetting.Builder()
        .name("protected-items")
        .description("Hotbar items that must never be displaced when looking for a free slot.")
        .defaultValue(new ArrayList<>())
        .build()
    );

    private final Setting<Integer> placeRange = sgPlacement.add(new IntSetting.Builder()
        .name("place-range")
        .description("Maximum search distance at which the container may be placed.")
        .defaultValue(5)
        .min(1)
        .sliderMax(5)
        .build()
    );

    private final Setting<Boolean> airPlace = sgPlacement.add(new BoolSetting.Builder()
        .name("air-place")
        .description("Allow placing the container in freaking mid-air.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> preferSolidBlock = sgPlacement.add(new BoolSetting.Builder()
        .name("prefer-solid-block")
        .description("When air-place is on, try positions that have a solid block below first.")
        .defaultValue(true)
        .visible(airPlace::get)
        .build()
    );

    public AutoLoader() {
        super(Addon.CATEGORY, "Auto-Loader",
            "Right-click shulker boxes, ender chests, or bundles in your inventory to instantly use them.");
    }

    @Override
    public void onActivate() {
        stage = Stage.IDLE;
        resetState();
    }

    @Override
    public void onDeactivate() {
        resetState();
    }

    private void resetState() {
        if (mc.player != null) stopSneaking();
        unlockRotation();
        isEnderChest = false;
        isBundle = false;
        containerInvSlot = -1;
        containerHotbarSlot = -1;
        placedPos = null;
        firstPos = null;
        secondPos = null;
        firstFacing = null;
        firstApproachFacing = null;
        firstYaw = 0f;
        delayTicks = 0;
        pickaxeEquipped = false;
        packetBreakSent = false;
        preBreakSlot = -1;
        openAttempts = 0;
        breakAttempts = 0;
        secondPlaceAttempts = 0;
        preActionSlot = -1;
        freeingHotbarSlot = -1;
        freeSlotWaitTicks = 0;
        failedPositions.clear();
    }

    @EventHandler
    private void onMouseButton(MouseClickEvent event) {
        if (stage != Stage.IDLE) return;
        if (mc.player == null || mc.level == null) return;
        if (event.action != KeyAction.Press) return;
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return;
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) return;

        Slot focusedSlot = ((AbstractContainerScreenAccessor) screen).meteor$getHoveredSlot();
        if (focusedSlot == null || focusedSlot.container != mc.player.getInventory()) return;

        ItemStack stack = focusedSlot.getItem();
        if (stack.isEmpty()) return;

        boolean wantShulker = instantShulker.get() && isShulkerBox(stack);
        boolean wantEChest = instantEChest.get() && stack.getItem() == Items.ENDER_CHEST;
        boolean wantBundle = instantBundle.get() && isBundleItem(stack);
        if (!wantShulker && !wantEChest && !wantBundle) return;

        event.cancel();
        isEnderChest = wantEChest;
        isBundle = wantBundle;
        containerInvSlot = focusedSlot.getContainerSlot();
        stage = Stage.SETUP;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        // Server-side only, reapplied every tick so both chests place with the same facing.
        if (rotationLocked && rotate.get()) {
            Rotations.rotate(lockYaw, lockPitch, -100, null);
        }

        if (delayTicks > 0) {
            delayTicks--;
            return;
        }

        switch (stage) {
            case IDLE -> {
            }
            case SETUP -> doSetup();
            case PLACE -> doPlace();
            case USE_ITEM -> doUseItem();
            case CENTER -> doCenter();
            case PREPARE_SECOND_ECHEST -> doPrepareSecondEchest();
            case PLACE_SECOND_ECHEST -> doPlaceSecond();
            case OPEN -> doOpen();
            case WAIT_CLOSE -> {
                if (!(mc.screen instanceof AbstractContainerScreen)) {
                    if (breakAfterUse.get()) stage = Stage.BREAK;
                    else finish();
                }
            }
            case BREAK -> doBreak(false);
            case BREAK_SECOND_ECHEST -> doBreak(true);
        }
    }

    private void doSetup() {
        if (mc.screen instanceof AbstractContainerScreen) {
            mc.player.closeContainer();
            delayTicks = 5;
            return;
        }

        if (containerInvSlot >= 9) {
            if (!isExpectedItem(mc.player.getInventory().getItem(containerInvSlot))) {
                containerInvSlot = searchInventory();
                if (containerInvSlot < 0) {
                    info("Lost track of container item after inventory re-sync.");
                    stage = Stage.IDLE;
                    resetState();
                    return;
                }
            }

            if (freeingHotbarSlot != -1) {
                if (!mc.player.getInventory().getItem(freeingHotbarSlot).isEmpty()) {
                    if (++freeSlotWaitTicks > 10) {
                        info("Hotbar slot never cleared, skipping container.");
                        stage = Stage.IDLE;
                        resetState();
                        return;
                    }
                    delayTicks = 1;
                    return;
                }

                // Confirmed empty now, it's safe to move the container in.
                InvUtils.move().from(containerInvSlot).toHotbar(freeingHotbarSlot);
                containerHotbarSlot = freeingHotbarSlot;
                containerInvSlot = freeingHotbarSlot; // sync
                freeingHotbarSlot = -1;
                freeSlotWaitTicks = 0;
                delayTicks = 2;
                return;
            }

            // Already an empty hotbar slot? Just move straight there.
            int emptyHotbar = findEmptySlot(0, 9);
            if (emptyHotbar != -1) {
                InvUtils.move().from(containerInvSlot).toHotbar(emptyHotbar);
                containerHotbarSlot = emptyHotbar;
                containerInvSlot = emptyHotbar; // sync
                delayTicks = 2;
                return;
            }

            for (int i = 0; i < 9; i++) {
                ItemStack hotbarStack = mc.player.getInventory().getItem(i);
                if (hotbarStack.isEmpty() || isProtected(hotbarStack)) continue;

                int emptyMain = findEmptySlot(9, 36);
                if (emptyMain == -1) continue;

                InvUtils.move().fromHotbar(i).to(emptyMain);
                freeingHotbarSlot = i;
                freeSlotWaitTicks = 0;
                delayTicks = 2;
                return;
            }

            info("No empty hotbar slot could be freed. Skipping container.");
            stage = Stage.IDLE;
            resetState();
            return;
        }

        containerHotbarSlot = containerInvSlot;
        stage = isBundle ? Stage.USE_ITEM : Stage.PLACE;
    }

    private void doUseItem() {
        preActionSlot = mc.player.getInventory().getSelectedSlot();
        mc.player.getInventory().setSelectedSlot(containerHotbarSlot);
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(containerHotbarSlot));
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        mc.player.swing(InteractionHand.MAIN_HAND);
        if (preActionSlot >= 0 && preActionSlot <= 8 && preActionSlot != containerHotbarSlot) {
            mc.player.getInventory().setSelectedSlot(preActionSlot);
            mc.player.connection.send(new ServerboundSetCarriedItemPacket(preActionSlot));
        }
        finish();
    }

    /**
     * Places the first container and locks the rotation so the second chest matches its facing.
     */
    private void doPlace() {
        mc.player.getInventory().setSelectedSlot(containerHotbarSlot);
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(containerHotbarSlot));

        BlockPos placePos = findPlacement();
        if (placePos == null) {
            info("No valid placement position found within range.");
            stage = Stage.IDLE;
            resetState();
            delayTicks = 20;
            return;
        }

        firstPos = placePos;

        Vec3 hitVec = airPlace.get()
            ? Vec3.atCenterOf(placePos)
            : new Vec3(placePos.getX() + 0.5, placePos.getY(), placePos.getZ() + 0.5);

        // Lock onto the actual placement hit vector, not the player's current look
        // direction, so the second chest can match this exact facing every tick.
        firstYaw = (float) Rotations.getYaw(hitVec);
        lockRotation(firstYaw, (float) Rotations.getPitch(hitVec));

        firstFacing = mc.player.getDirection();

        // findPlacement() will fall back to a side/back/up/down candidate when the
        // spot directly ahead is blocked, so the chest doesn't always end up in front
        // of the player. Derive "left" from where it actually landed relative to the player,
        Direction approach = horizontalDirectionBetween(mc.player.blockPosition(), placePos);
        firstApproachFacing = approach != null ? approach : firstFacing;

        placedPos = placePos;
        placeBlock(placePos);
        delayTicks = 4;

        stage = (isEnderChest && doubleEChest.get())
            ? Stage.CENTER // Simple solution to weird yaw
            : Stage.OPEN;
    }

    private void doPlaceSecond() {
        mc.player.getInventory().setSelectedSlot(containerHotbarSlot);
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(containerHotbarSlot));

        Direction leftDir = firstApproachFacing.getCounterClockWise();
        BlockPos placePos = firstPos.relative(leftDir);

        // Already placed by an earlier attempt?
        if (mc.level.getBlockState(placePos).getBlock() instanceof EnderChestBlock) {
            stopSneaking();
            secondPos = placePos;
            placedPos = firstPos;
            stage = Stage.OPEN;
            delayTicks = 2;
            return;
        }

        // Target position is blocked or out of reach?
        if (!isValidSecondPos(placePos, leftDir)) {
            info("Can't place the second ender chest, opening first only.");
            stopSneaking();
            placedPos = firstPos;
            stage = Stage.OPEN;
            delayTicks = 2;
            return;
        }

        if (++secondPlaceAttempts > 12) {
            info("Failed to place the second ender chest after 12 attempts, opening first only.");
            stopSneaking();
            placedPos = firstPos;
            stage = Stage.OPEN;
            delayTicks = 2;
            return;
        }

        if (!sneaking) {
            startSneaking();
            delayTicks = 3;
            return;
        }

        // Place against the left face of the first chest
        // We pass firstYaw so the placed chest's horizontal facing matches the first chest.
        // (placeAgainstFaceKeepYaw only rotates pitch, keeping yaw at firstYaw)
        placeAgainstFaceKeepYaw(firstPos, leftDir, firstYaw);
        delayTicks = 4;
    }

    private void doCenter() {
        PlayerUtils.centerPlayer();
        delayTicks = centerDelay.get();
        stage = Stage.PREPARE_SECOND_ECHEST;
    }

    private void doPrepareSecondEchest() {
        int secondSlot = searchInventory();
        if (secondSlot < 0) {
            info("Only one ender chest in inventory.. opening first chest only.");
            stage = Stage.OPEN;
            return;
        }
        containerInvSlot = secondSlot;
        if (containerInvSlot >= 9) {
            int tgt = resolveHotbarSlot();
            if (tgt == -1) {
                info("No free hotbar slot for the second ender chest.. opening first only.");
                stage = Stage.OPEN;
                return;
            }
            InvUtils.move().from(containerInvSlot).toHotbar(tgt);
            containerInvSlot = tgt;
            containerHotbarSlot = tgt;
            delayTicks = 3;
        } else {
            containerHotbarSlot = containerInvSlot;
        }
        stage = Stage.PLACE_SECOND_ECHEST;
    }

    private void doOpen() {
        unlockRotation();
        if (placedPos == null) {
            stage = Stage.IDLE;
            resetState();
            return;
        }

        if (mc.screen instanceof AbstractContainerScreen) {
            openAttempts = 0;
            stage = Stage.WAIT_CLOSE;
            return;
        }

        if (++openAttempts > 20) {
            failedPositions.add(placedPos);
            BlockPos retry = findPlacement();
            if (retry == null) {
                info("Timed out waiting for the container GUI to open, and no other placement spot found.");
                stage = Stage.IDLE;
                resetState();
                return;
            }
            info("Placement at previous spot failed (likely blocked by your own hitbox), retrying elsewhere.");
            firstPos = retry;
            placedPos = retry;
            openAttempts = 0;
            Vec3 hitVec = airPlace.get()
                ? Vec3.atCenterOf(retry)
                : new Vec3(retry.getX() + 0.5, retry.getY(), retry.getZ() + 0.5);
            firstYaw = (float) Rotations.getYaw(hitVec);
            lockRotation(firstYaw, (float) Rotations.getPitch(hitVec));
            placeBlock(retry);
            delayTicks = 4;
            return;
        }

        BlockState bs = mc.level.getBlockState(placedPos);
        boolean present = isEnderChest
            ? bs.getBlock() instanceof EnderChestBlock
            : bs.getBlock() instanceof ShulkerBoxBlock;
        if (!present) {
            delayTicks = 2;
            return;
        }

        if (mc.player.distanceToSqr(Vec3.atCenterOf(placedPos)) > INTERACTION_REACH_SQ) {
            info("Container was placed out of interaction range.");
            stage = Stage.IDLE;
            resetState();
            return;
        }

        Vec3 center = Vec3.atCenterOf(placedPos);
        BlockHitResult hit = new BlockHitResult(center, Direction.UP, placedPos, false);
        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(center), Rotations.getPitch(center), -100, () -> {
                mc.player.connection.send(
                    new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, 0));
                mc.player.swing(InteractionHand.MAIN_HAND);
            });
        } else {
            mc.player.connection.send(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, 0));
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
        delayTicks = 4;
    }

    private void doBreak(boolean isSecond) {
        if (mc.screen != null) {
            mc.player.closeContainer();
            delayTicks = 2;
            return;
        }

        // Block is already gone, move on.
        if (placedPos == null || mc.level.getBlockState(placedPos).isAir()) {
            afterBreak(isSecond);
            return;
        }

        if (++breakAttempts > 40) {
            info("Timed out while breaking the container.");
            restorePreBreakSlot();
            stage = Stage.IDLE;
            resetState();
            return;
        }

        // Equip the best pickaxe for breaking
        if (!pickaxeEquipped) {
            int ps = findPickaxeSlot();
            if (ps != -1) {
                preBreakSlot = mc.player.getInventory().getSelectedSlot();
                mc.player.getInventory().setSelectedSlot(ps);
                mc.player.connection.send(new ServerboundSetCarriedItemPacket(ps));
                pickaxeEquipped = true;
            }
        }

        // Packet based instant break
        if (packetBreak.get()) {
            if (!packetBreakSent) {
                if (rotate.get()) {
                    Vec3 c = Vec3.atCenterOf(placedPos);
                    Rotations.rotate(Rotations.getYaw(c), Rotations.getPitch(c), -100, null);
                }
                // Break this chest.
                sendBreakPackets(placedPos, Direction.UP);

                // we avoid an extra BREAK_SECOND_ECHEST stage when packet mode is on.
                if (!isSecond && secondPos != null
                    && !mc.level.getBlockState(secondPos).isAir()) {
                    sendBreakPackets(secondPos, Direction.UP);
                }

                mc.player.swing(InteractionHand.MAIN_HAND);
                packetBreakSent = true;
            }
            // Wait a few ticks for the server to confirm; the isAir() check at the
            // top of the next call, will then do afterBreak().
            delayTicks = 4;
            return;
        }

        // Normal break
        mc.gameMode.continueDestroyBlock(placedPos, Direction.UP);
        mc.player.swing(InteractionHand.MAIN_HAND);
        if (mc.level.getBlockState(placedPos).isAir()) afterBreak(isSecond);
        else delayTicks = 2;
    }

    private void sendBreakPackets(BlockPos pos, Direction dir) {
        if (packetBreakGrim.get()) {
            mc.player.connection.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, dir));
            mc.player.connection.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, dir));
            mc.player.connection.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, pos, dir));
        } else {
            mc.player.connection.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, dir));
            mc.player.connection.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, pos, dir));
        }
        mc.player.connection.send(new ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, dir));
    }

    private void afterBreak(boolean wasSecond) {
        restorePreBreakSlot();
        breakAttempts = 0;
        pickaxeEquipped = false;
        packetBreakSent = false;

        // If the second echest is still standing (packet-break didn't reach it)
        // fall through to break it normally.
        if (!wasSecond && secondPos != null && !mc.level.getBlockState(secondPos).isAir()) {
            placedPos = secondPos;
            stage = Stage.BREAK_SECOND_ECHEST;
        } else {
            finish();
        }
    }

    private void finish() {
        restorePreBreakSlot();
        stage = Stage.IDLE;
        resetState();
    }

    private void placeBlock(BlockPos pos) {
        Vec3 hitVec = Vec3.atCenterOf(pos);
        if (airPlace.get()) {
            BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, pos, false);
            int rev = mc.player.containerMenu.getStateId();
            if (rotate.get())
                Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec), -100,
                    () -> sendAirPlacePackets(hit, rev));
            else
                sendAirPlacePackets(hit, rev);
        } else {
            Vec3 supportHit = new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            BlockHitResult hit = new BlockHitResult(supportHit, Direction.UP, pos.below(), false);
            if (rotate.get())
                Rotations.rotate(Rotations.getYaw(supportHit), Rotations.getPitch(supportHit), -100, () -> {
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                });
            else {
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
                mc.player.swing(InteractionHand.MAIN_HAND);
            }
        }
    }

    private void placeAgainstFaceKeepYaw(BlockPos blockPos, Direction face, float keepYaw) {
        Vec3 hitVec = Vec3.atCenterOf(blockPos).add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.5));
        BlockHitResult hit = new BlockHitResult(hitVec, face, blockPos, false);

        if (rotate.get()) {
            float pitch = (float) Rotations.getPitch(hitVec);
            Rotations.rotate(keepYaw, pitch, -100, () -> {
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
                mc.player.swing(InteractionHand.MAIN_HAND);
            });
        } else {
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
    }

    private void startSneaking() {
        if (sneaking || mc.player == null) return;
        sneaking = true;
        mc.player.setShiftKeyDown(true);
        mc.options.keyShift.setDown(true);
    }

    private void stopSneaking() {
        if (!sneaking || mc.player == null) return;
        sneaking = false;
        mc.player.setShiftKeyDown(false);
        mc.options.keyShift.setDown(false);
    }

    private void lockRotation(float yaw, float pitch) {
        rotationLocked = true;
        lockYaw = yaw;
        lockPitch = pitch;
    }

    private void unlockRotation() {
        rotationLocked = false;
    }

    private void sendAirPlacePackets(BlockHitResult hit, int revision) {
        mc.player.connection.send(new ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
        mc.player.connection.send(
            new ServerboundUseItemOnPacket(InteractionHand.OFF_HAND, hit, revision));
        mc.player.connection.send(new ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    /**
     * Real interaction reach, independent of the placement search radius.
     */
    private static final double INTERACTION_REACH_SQ = 5.0 * 5.0;

    private BlockPos findPlacement() {
        BlockPos pp = mc.player.blockPosition();
        Direction facing = mc.player.getDirection();
        double rangeSq = (double) placeRange.get() * placeRange.get();
        Vec3 playerPos = mc.player.position();
        boolean needsSecondSpot = isEnderChest && doubleEChest.get();

        List<BlockPos> cands = new ArrayList<>();
        cands.add(pp.relative(facing));
        cands.add(pp.relative(facing.getClockWise()));
        cands.add(pp.relative(facing.getCounterClockWise()));
        cands.add(pp.relative(facing.getOpposite()));
        cands.add(pp.above());
        cands.add(pp.below());
        for (int d = 1; d <= placeRange.get(); d++) {
            for (int x = -d; x <= d; x++) {
                for (int z = -d; z <= d; z++) {
                    if (Math.abs(x) == d || Math.abs(z) == d) {
                        for (int y = -1; y <= 1; y++) cands.add(pp.offset(x, y, z));
                    }
                }
            }
        }
        // Closest candidates first; ties keep their original (front-biased) order.
        cands.sort(Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(playerPos)));

        // Double ender chest needs room beside the first chest too, so a "valid"
        // first spot that's boxed in on its left side is still useless. Require
        // both spots to be free before falling back to single-spot validity,
        // otherwise we keep grabbing the closest-but-obstructed candidate.
        if (needsSecondSpot) {
            if (airPlace.get() && preferSolidBlock.get()) {
                for (BlockPos pos : cands) {
                    if (Vec3.atCenterOf(pos).distanceToSqr(playerPos) > rangeSq) continue;
                    if (!spaceAbove(pos) || !validSolidPos(pos) || intersectsPlayer(pos) || failedPositions.contains(pos)) continue;
                    if (hasRoomForSecond(pp, pos)) return pos;
                }
            }
            for (BlockPos pos : cands) {
                if (Vec3.atCenterOf(pos).distanceToSqr(playerPos) > rangeSq) continue;
                if (!spaceAbove(pos)) continue;
                boolean primaryValid = airPlace.get()
                    ? canPlaceAt(pos)
                    : (validSolidPos(pos) && !intersectsPlayer(pos) && !failedPositions.contains(pos));
                if (!primaryValid) continue;
                if (hasRoomForSecond(pp, pos)) return pos;
            }
            // No spot has room for a second chest; fall through to the normal
            // single-spot search below, doPlaceSecond() will report and skip it.
        }

        if (airPlace.get() && preferSolidBlock.get()) {
            for (BlockPos pos : cands) {
                if (Vec3.atCenterOf(pos).distanceToSqr(playerPos) > rangeSq) continue;
                if (!spaceAbove(pos) || intersectsPlayer(pos) || failedPositions.contains(pos)) continue;
                if (validSolidPos(pos)) return pos;
            }
        }
        for (BlockPos pos : cands) {
            if (Vec3.atCenterOf(pos).distanceToSqr(playerPos) > rangeSq) continue;
            if (!spaceAbove(pos)) continue;
            if (airPlace.get()) {
                if (canPlaceAt(pos)) return pos;
            } else {
                if (validSolidPos(pos) && !intersectsPlayer(pos) && !failedPositions.contains(pos)) return pos;
            }
        }
        return null;
    }

    private boolean hasRoomForSecond(BlockPos from, BlockPos pos) {
        Direction approach = horizontalDirectionBetween(from, pos);
        if (approach == null) approach = mc.player.getDirection();
        BlockPos second = pos.relative(approach.getCounterClockWise());
        return isReplaceableOrAir(second) && isReplaceableOrAir(second.above());
    }

    private boolean isValidSecondPos(BlockPos pos, Direction faceDir) {
        Vec3 hitPoint = Vec3.atCenterOf(firstPos).add(Vec3.atLowerCornerOf(faceDir.getUnitVec3i()).scale(0.5));
        return mc.player.position().distanceToSqr(hitPoint) <= INTERACTION_REACH_SQ
            && isReplaceableOrAir(pos)
            && isReplaceableOrAir(pos.above());
    }

    private boolean isReplaceableOrAir(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        return state.isAir() || state.canBeReplaced() || !state.getFluidState().isEmpty();
    }

    private boolean intersectsPlayer(BlockPos pos) {
        AABB playerBox = mc.player.getBoundingBox();
        AABB blockBox = new AABB(pos);
        return playerBox.intersects(blockBox);
    }

    private boolean canPlaceAt(BlockPos pos) {
        return isReplaceableOrAir(pos) && !intersectsPlayer(pos) && !failedPositions.contains(pos);
    }

    private boolean spaceAbove(BlockPos pos) {
        return isReplaceableOrAir(pos.above());
    }

    private boolean validSolidPos(BlockPos pos) {
        return isReplaceableOrAir(pos)
            && mc.level.getBlockState(pos.below()).isRedstoneConductor(mc.level, pos.below());
    }

    private Direction horizontalDirectionBetween(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (dx == 0 && dz == 0) return null;
        return Math.abs(dx) >= Math.abs(dz)
            ? (dx > 0 ? Direction.EAST : Direction.WEST)
            : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
    }

    private int resolveHotbarSlot() {
        int pref = hotbarSlotSetting.get() - 1;
        if (!isProtected(mc.player.getInventory().getItem(pref))) return pref;
        for (int i = 0; i < 9; i++) {
            if (i != pref && !isProtected(mc.player.getInventory().getItem(i))) return i;
        }
        return -1;
    }

    private boolean isProtected(ItemStack stack) {
        return !stack.isEmpty() && protectedItems.get().contains(stack.getItem());
    }

    private boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock;
    }

    private boolean isBundleItem(ItemStack stack) {
        return stack.getItem() instanceof BundleItem;
    }

    private int findEmptySlot(int start, int end) {
        for (int i = start; i < end; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) return i;
        }
        return -1;
    }

    private boolean isExpectedItem(ItemStack stack) {
        if (isBundle) return isBundleItem(stack);
        return isEnderChest ? stack.getItem() == Items.ENDER_CHEST : isShulkerBox(stack);
    }

    private int searchInventory() {
        for (int i = 0; i < 36; i++) {
            if (isExpectedItem(mc.player.getInventory().getItem(i))) return i;
        }
        return -1;
    }

    private int findPickaxeSlot() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(ItemTags.PICKAXES)) return i;
        }
        return -1;
    }

    private void restorePreBreakSlot() {
        if (pickaxeEquipped && preBreakSlot >= 0 && preBreakSlot <= 8) {
            mc.player.getInventory().setSelectedSlot(preBreakSlot);
            mc.player.connection.send(new ServerboundSetCarriedItemPacket(preBreakSlot));
        }
        pickaxeEquipped = false;
        preBreakSlot = -1;
    }
}
