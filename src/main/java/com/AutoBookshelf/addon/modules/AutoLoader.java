package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.utils.PlacementEngine;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.AbstractContainerScreenAccessor;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
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
        AUTO_TAKE,
        WAIT_CLOSE,
        BREAK,
        BREAK_SECOND_ECHEST
    }

    /**
     * Shared placement search logic, also used by MaterialsRefill.
     */
    private final PlacementEngine placementEngine = new PlacementEngine();

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

    /**
     * AUTO_TAKE settle/retry budget. The container GUI becoming visible and its
     * contents actually arriving are two separate packets, so we wait a couple
     * ticks (and re-read the handler every tick) before clicking. This keeps the
     * quick-move burst from being dropped server-side for acting on a stale view.
     */
    private static final int AUTO_TAKE_SETTLE_TICKS = 3;
    private static final int AUTO_TAKE_MAX_TICKS = 15;
    private int autoTakeWaitTicks = 0;
    private int autoTakeTicks = 0;

    /**
     * Positions where placement was attempted but never materialized
     * (blocked by the player's own hitbox), so PlacementEngine can
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
    private final SettingGroup sgRetries = settings.createGroup("Retries");

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
        .description("Place a second ender chest mirrored next to the first (same facing, same Y), " +
            "then open the first one. Both are broken after you close.")
        .defaultValue(false)
        .visible(() -> instantEChest.get() && breakAfterUse.get())
        .build()
    );

    private final Setting<Boolean> autoTake = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-take")
        .description("Automatically take all items from the container before it closes, instead of waiting for you to take them manually.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> onlySingleItem = sgGeneral.add(new BoolSetting.Builder()
        .name("only-single-item")
        .description("Only auto-take if the container holds just one distinct item; otherwise leave it for you to take manually.")
        .defaultValue(false)
        .visible(autoTake::get)
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

    private final Setting<Boolean> resyncAfterPlace = sgGeneral.add(new BoolSetting.Builder()
        .name("resync-after-place")
        .description("Sends an inventory resync packet shortly after placing a container.")
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
        .defaultValue(2)
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

    private final Setting<Integer> maxOpenAttempts = sgRetries.add(new IntSetting.Builder()
        .name("max-open-attempts")
        .description("How many ticks to retry waiting for the container to appear/open before giving up or trying a new spot.")
        .defaultValue(20)
        .min(1)
        .sliderMax(60)
        .build()
    );

    private final Setting<Integer> maxSecondPlaceAttempts = sgRetries.add(new IntSetting.Builder()
        .name("max-second-place-attempts")
        .description("How many times to retry placing the second ender chest before giving up and opening the first only.")
        .defaultValue(12)
        .min(1)
        .sliderMax(40)
        .visible(() -> instantEChest.get() && doubleEChest.get())
        .build()
    );

    private final Setting<Integer> maxBreakAttempts = sgRetries.add(new IntSetting.Builder()
        .name("max-break-attempts")
        .description("How many times to retry breaking the container before giving up.")
        .defaultValue(40)
        .min(1)
        .sliderMax(100)
        .visible(breakAfterUse::get)
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
        autoTakeWaitTicks = 0;
        autoTakeTicks = 0;
        failedPositions.clear();
    }

    @EventHandler
    private void onMouseButton(MouseClickEvent event) {
        if (stage != Stage.IDLE) return;
        if (mc.player == null || mc.level == null) return;
        if (event.action != KeyAction.Press) return;
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return;
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) return;

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
            case AUTO_TAKE -> doAutoTake();
            case WAIT_CLOSE -> {
                if (!(mc.gui.screen() instanceof AbstractContainerScreen)) {
                    if (breakAfterUse.get()) stage = Stage.BREAK;
                    else finish();
                }
            }
            case BREAK -> doBreak(false);
            case BREAK_SECOND_ECHEST -> doBreak(true);
        }
    }

    private void doSetup() {
        if (mc.gui.screen() instanceof AbstractContainerScreen) {
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

            int targetSlot = resolveHotbarSlot();
            if (targetSlot == -1) {
                info("No available hotbar slot (all slots are protected). Skipping container.");
                stage = Stage.IDLE;
                resetState();
                return;
            }

            // Single atomic swap
            mc.gameMode.handleContainerInput(
                mc.player.inventoryMenu.containerId,
                containerInvSlot,
                targetSlot,
                ContainerInput.SWAP,
                mc.player
            );
            containerInvSlot = targetSlot; // now <9, next doSetup() call falls through below
            delayTicks = 2;
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
        if (mc.gui.screen() instanceof AbstractContainerScreen) {
            mc.player.closeContainer();
            delayTicks = 3;
            return;
        }

        ItemStack handStack = mc.player.getInventory().getItem(containerHotbarSlot);
        if (!isExpectedItem(handStack)) {
            info("Container item missing from hotbar slot, aborting placement.");
            stage = Stage.IDLE;
            resetState();
            return;
        }

        mc.player.getInventory().setSelectedSlot(containerHotbarSlot);
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(containerHotbarSlot));

        boolean needsSecondSpot = isEnderChest && doubleEChest.get();
        BlockPos placePos = placementEngine.findPlacement(
            placeRange.get(), airPlace.get(), preferSolidBlock.get(), failedPositions, needsSecondSpot);
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

        // The placement engine will fall back to a side/back candidate when the
        // spot directly ahead is blocked, so the chest doesn't always end up in
        // front of the player. Derive "left" from where it actually landed
        // relative to the player.
        Direction approach = placementEngine.horizontalDirectionBetween(mc.player.blockPosition(), placePos);
        firstApproachFacing = approach != null ? approach : firstFacing;

        placedPos = placePos;
        placeBlock(placePos);
        delayTicks = 4;

        stage = needsSecondSpot
            ? Stage.CENTER // Simple solution to weird yaw
            : Stage.OPEN;
    }

    private boolean secondPlacementPending = false;
    private int secondPlacementWaitTicks = 0;

    private void doPlaceSecond() {
        if (mc.gui.screen() instanceof AbstractContainerScreen) {
            mc.player.closeContainer();
            delayTicks = 3;
            return;
        }

        mc.player.getInventory().setSelectedSlot(containerHotbarSlot);
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(containerHotbarSlot));

        Direction leftDir = firstApproachFacing.getCounterClockWise();
        BlockPos placePos = firstPos.relative(leftDir);

        if (mc.level.getBlockState(placePos).getBlock() instanceof EnderChestBlock) {
            secondPos = placePos;
            placedPos = firstPos;
            secondPlacementPending = false;
            stage = Stage.OPEN;
            delayTicks = 2;
            return;
        }

        if (secondPlacementPending) {
            if (++secondPlacementWaitTicks > 20) { // ~1s, generous vs. a 4-tick retry
                secondPlacementPending = false; // give up on this attempt, allow a genuine retry
            } else {
                delayTicks = 2;
                return;
            }
        }

        ItemStack handStack = mc.player.getInventory().getItem(containerHotbarSlot);
        if (handStack.isEmpty() || handStack.getItem() != Items.ENDER_CHEST) {
            info("Second ender chest item is gone, opening first only.");
            placedPos = firstPos;
            stage = Stage.OPEN;
            delayTicks = 2;
            return;
        }

        if (!placementEngine.isValidSecondPos(firstPos, placePos, leftDir)) {
            info("Can't place the second ender chest, opening first only.");
            placedPos = firstPos;
            stage = Stage.OPEN;
            delayTicks = 2;
            return;
        }

        if (++secondPlaceAttempts > maxSecondPlaceAttempts.get()) {
            info("Failed to place the second ender chest after " + maxSecondPlaceAttempts.get() + " attempts, opening first only.");
            placedPos = firstPos;
            stage = Stage.OPEN;
            delayTicks = 2;
            return;
        }

        secondPos = placePos;
        placeMirrored(placePos, firstYaw);
        secondPlacementPending = true;
        secondPlacementWaitTicks = 0;
        delayTicks = 2;
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
            mc.gameMode.handleContainerInput(
                mc.player.inventoryMenu.containerId,
                containerInvSlot,
                tgt,
                ContainerInput.SWAP,
                mc.player
            );
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

        if (mc.gui.screen() instanceof AbstractContainerScreen) {
            openAttempts = 0;
            stage = autoTake.get() ? Stage.AUTO_TAKE : Stage.WAIT_CLOSE;
            return;
        }

        BlockState bs = mc.level.getBlockState(placedPos);
        boolean present = isEnderChest
            ? bs.getBlock() instanceof EnderChestBlock
            : bs.getBlock() instanceof ShulkerBoxBlock;

        if (!present) {
            // Genuinely never materialized, this is the only case where it's
            // safe to abandon and try elsewhere.
            if (++openAttempts > maxOpenAttempts.get()) {
                failedPositions.add(placedPos);
                boolean needsSecondSpot = isEnderChest && doubleEChest.get();
                BlockPos retry = placementEngine.findPlacement(
                    placeRange.get(), airPlace.get(), preferSolidBlock.get(), failedPositions, needsSecondSpot);
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
            delayTicks = 2;
            return;
        }

        openAttempts++;
        if (openAttempts % 20 == 0) {
            sendResyncPacket();
        }

        if (mc.player.distanceToSqr(Vec3.atCenterOf(placedPos)) > 25.0) {
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

    private void doAutoTake() {
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
            // Already closed somehow; let WAIT_CLOSE pick up cleanly from here.
            stage = Stage.WAIT_CLOSE;
            return;
        }

        AbstractContainerMenu handler = screen.getMenu();

        if (onlySingleItem.get() && hasMixedItems(handler)) {
            // Leave the GUI open
            stage = Stage.WAIT_CLOSE;
            return;
        }

        // The GUI being visible and its contents having arrived are not the same
        // thing: the open-screen packet and the container-content sync can land
        // in separate ticks.
        if (autoTakeWaitTicks < AUTO_TAKE_SETTLE_TICKS) {
            autoTakeWaitTicks++;
            delayTicks = 1;
            return;
        }

        // Nothing left in the container, the take is complete.
        if (!stillHoldsItems(handler)) {
            mc.player.closeContainer();
            stage = Stage.WAIT_CLOSE;
            return;
        }

        // Genuinely nowhere to put what's left (no free slot beyond the reserved
        // one and nothing can merge into a partial stack): leave it for the
        // player instead of silently breaking the container with its contents.
        if (!hasRoomForContents(handler)) {
            info("No room in inventory for the container's items, leaving for manual take.");
            stage = Stage.WAIT_CLOSE;
            return;
        }

        // Bounded retry: if the server drops some of the burst (laggy tick,
        // stale revision), the handler gets re-synced and re-reading it next
        // tick lets us pick up whatever was left behind.
        if (++autoTakeTicks > AUTO_TAKE_MAX_TICKS) {
            warning("Couldn't take all items from the container in time, leaving for manual take.");
            stage = Stage.WAIT_CLOSE;
            return;
        }

        for (int i = 0; i < 27; i++) {
            ItemStack stack = handler.getSlot(i).getItem();
            if (stack.isEmpty()) continue;
            mc.gameMode.handleContainerInput(handler.containerId, i, 0, ContainerInput.QUICK_MOVE, mc.player);
            // Re-check the real inventory rather than assuming this quick-move
            // consumed an empty slot.
            if (!hasRoomForContents(handler)) break;
        }

        delayTicks = 1;
    }

    /**
     * True if any of the container's 27 slots still holds an item.
     */
    private boolean stillHoldsItems(AbstractContainerMenu handler) {
        for (int i = 0; i < 27; i++) {
            if (!handler.getSlot(i).getItem().isEmpty()) return true;
        }
        return false;
    }

    /**
     * True if at least one item in the container could be moved into the player
     * inventory right now: either a free slot beyond the ones keepFreeSlots()
     * reserves for picking up the broken container, or a partial stack the item
     * can merge into without consuming a new slot.
     */
    private boolean hasRoomForContents(AbstractContainerMenu handler) {
        for (int i = 0; i < 27; i++) {
            ItemStack stack = handler.getSlot(i).getItem();
            if (stack.isEmpty()) continue;

            if (countEmptyPlayerSlots() > keepFreeSlots()) return true;

            for (int p = 0; p < 36; p++) {
                ItemStack playerStack = mc.player.getInventory().getItem(p);
                if (playerStack.isEmpty()) continue;
                if (playerStack.getCount() < playerStack.getItem().getDefaultMaxStackSize()
                    && ItemStack.isSameItemSameComponents(stack, playerStack)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * True if the container's 27 slots hold more than one distinct item type.
     * Empty slots don't count.
     */
    private boolean hasMixedItems(AbstractContainerMenu handler) {
        Item found = null;
        for (int i = 0; i < 27; i++) {
            ItemStack stack = handler.getSlot(i).getItem();
            if (stack.isEmpty()) continue;
            if (found == null) {
                found = stack.getItem();
            } else if (stack.getItem() != found) {
                return true;
            }
        }
        return false;
    }

    private int keepFreeSlots() {
        return breakAfterUse.get() ? 1 : 0;
    }

    private int countEmptyPlayerSlots() {
        int empty = 0;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) empty++;
        }
        return empty;
    }

    private void doBreak(boolean isSecond) {
        if (mc.gui.screen() != null) {
            mc.player.closeContainer();
            delayTicks = 2;
            return;
        }

        // Block is already gone, move on.
        if (placedPos == null || mc.level.getBlockState(placedPos).isAir()) {
            afterBreak(isSecond);
            return;
        }

        if (++breakAttempts > maxBreakAttempts.get()) {
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
        if (resyncAfterPlace.get()) sendResyncPacket();
    }

    private void placeMirrored(BlockPos pos, float yaw) {
        Vec3 hitVec = Vec3.atCenterOf(pos);
        if (airPlace.get()) {
            BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, pos, false);
            int rev = mc.player.containerMenu.getStateId();
            float pitch = (float) Rotations.getPitch(hitVec);
            if (rotate.get())
                Rotations.rotate(yaw, pitch, -100, () -> sendAirPlacePackets(hit, rev));
            else
                sendAirPlacePackets(hit, rev);
        } else {
            Vec3 supportHit = new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            BlockHitResult hit = new BlockHitResult(supportHit, Direction.UP, pos.below(), false);
            float pitch = (float) Rotations.getPitch(supportHit);
            if (rotate.get())
                Rotations.rotate(yaw, pitch, -100, () -> {
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                });
            else {
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
                mc.player.swing(InteractionHand.MAIN_HAND);
            }
        }
        if (resyncAfterPlace.get()) sendResyncPacket();
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

    private void sendResyncPacket() {
        if (mc.player == null || mc.player.connection == null) return;
        // Never resync while any GUI is open: a stray resync sent mid-take can
        // desync the open container's handler and make the server silently drop
        // the subsequent quick-move clicks.
        if (mc.gui.screen() != null) return;

        AbstractContainerMenu handler = mc.player.containerMenu;
        Int2ObjectMap<HashedStack> modifiedStacks = new Int2ObjectOpenHashMap<>();
        mc.player.connection.send(new ServerboundContainerClickPacket(
            handler.containerId,
            handler.getStateId(),
            (short) -1,
            (byte) 0,
            ContainerInput.CLONE,
            modifiedStacks,
            HashedStack.EMPTY
        ));
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
