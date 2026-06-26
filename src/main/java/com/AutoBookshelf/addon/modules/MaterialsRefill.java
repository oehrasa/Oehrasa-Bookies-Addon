package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class MaterialsRefill extends Module {
    private enum Stage {
        IDLE, FIND_SHULKER, PLACE_SHULKER, OPEN_SHULKER, RESTOCK,
        CLOSE_AND_BREAK, WAIT_MANUAL_CLOSE
    }

    private Stage stage = Stage.IDLE;
    private Item currentTargetItem;
    private int shulkerSlot = -1;
    private BlockPos placedShulkerPos;
    private int delayTicks;
    private int keepFree = 0;
    private boolean pickaxeEquipped = false;
    private int preBreakSlot = -1;
    private String lastFailItem = "";
    private String lastFailReason = "";

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgControls = settings.createGroup("Controls");

    private final Setting<List<Item>> targetItems = sgGeneral.add(new ItemListSetting.Builder()
        .name("target-items")
        .description("Items to keep stocked.")
        .defaultValue(new ArrayList<>())
        .build()
    );

    private final Setting<Integer> restockThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("restock-threshold")
        .description("Restock when total count falls below this number.")
        .defaultValue(16)
        .min(1)
        .sliderRange(1, 64)
        .build()
    );

    private final Setting<Integer> placeRange = sgGeneral.add(new IntSetting.Builder()
        .name("place-range")
        .description("How far the placement range is.")
        .defaultValue(2)
        .min(1)
        .sliderMax(5)
        .build()
    );

    private final Setting<Boolean> airPlace = sgGeneral.add(new BoolSetting.Builder()
        .name("air-place")
        .description("Place the shulker in mid-air.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> preferSolidBlock = sgGeneral.add(new BoolSetting.Builder()
        .name("prefer-solid-block")
        .description("When air place is on, try solid block positions first.")
        .defaultValue(true)
        .visible(airPlace::get)
        .build()
    );

    private final Setting<Boolean> breakAfterFill = sgGeneral.add(new BoolSetting.Builder()
        .name("break-after-fill")
        .description("Break the shulker after restocking. Disable to leave it placed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoTake = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-take")
        .description("Automatically take items from the shulker. If disabled, only open/close.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoToggle = sgGeneral.add(new BoolSetting.Builder()
        .name("toggle-off")
        .description("Automatically toggle the module off after breaking the shulker.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotate when placing / interacting.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> shulkerHotbarSlot = sgGeneral.add(new IntSetting.Builder()
        .name("shulker-hotbar-slot")
        .description("Hotbar slot (1‑9) used when moving the shulker from inventory.")
        .defaultValue(1)
        .min(1)
        .max(9)
        .sliderRange(1, 9)
        .build()
    );

    private final Setting<List<Item>> protectedItems = sgGeneral.add(new ItemListSetting.Builder()
        .name("protected-items")
        .description("Items in the hotbar that must never be swapped out.")
        .defaultValue(new ArrayList<>())
        .build()
    );

    @SuppressWarnings("unused")
    private final Setting<Keybind> setTargetFromHeld = sgControls.add(new KeybindSetting.Builder()
        .name("set-target-from-held")
        .description("Set the held item as the target item for restocking.")
        .defaultValue(Keybind.none())
        .action(() -> {
            if (!isActive()) return;
            ItemStack held = mc.player.getMainHandItem();
            if (!held.isEmpty()) {
                List<Item> current = new ArrayList<>(targetItems.get());
                if (!current.contains(held.getItem())) {
                    current.add(held.getItem());
                    targetItems.set(current);
                    info("Added target item: " + held.getItem().getName(held).getString());
                } else {
                    info("Item already in target list.");
                }
            }
        })
        .build()
    );

    public MaterialsRefill() {
        super(Addon.CATEGORY, "Mats-Refill",
            "Automatically restocks materials from shulker boxes.");
    }

    @Override public void onActivate() {
        stage = Stage.IDLE;
        resetState();
    }

    @Override
    public void onDeactivate() {
        resetState();
    }

    private void resetState() {
        currentTargetItem = null;
        shulkerSlot = -1;
        placedShulkerPos = null;
        delayTicks = 0;
        keepFree = breakAfterFill.get() ? 1 : 0;
        pickaxeEquipped = false;
        preBreakSlot = -1;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;
        if (delayTicks > 0) { delayTicks--; return; }

        switch (stage) {
            case IDLE -> checkStock();
            case FIND_SHULKER -> selectShulker();
            case PLACE_SHULKER -> placeShulker();
            case OPEN_SHULKER -> openShulker();
            case RESTOCK -> doRestock();
            case CLOSE_AND_BREAK -> closeAndBreak();
            case WAIT_MANUAL_CLOSE -> {
                if (!(mc.screen instanceof AbstractContainerScreen)) {
                    stage = Stage.CLOSE_AND_BREAK;
                }
            }
        }
    }

    private void checkStock() {
        List<Item> items = targetItems.get();
        if (items.isEmpty()) return;

        for (Item item : items) {
            if (InvUtils.find(item).count() < restockThreshold.get()) {
                currentTargetItem = item;
                stage = Stage.FIND_SHULKER;
                return;
            }
        }
    }

    private void selectShulker() {
        shulkerSlot = -1;
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!isShulkerBox(stack)) continue;
            ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
            if (contents == null) continue;

            for (ItemStackTemplate template : contents.nonEmptyItems()) {
                if (template.item().value() == currentTargetItem) {
                    shulkerSlot = i;
                    break;
                }
            }
            if (shulkerSlot != -1) break;
        }
        if (shulkerSlot == -1) {
            if (PrintInfo(currentTargetItem.getName(new ItemStack(currentTargetItem)).getString(), "no shulker")) {
                info("No shulker with " + currentTargetItem.getName(new ItemStack(currentTargetItem)).getString());
            }
            stage = Stage.IDLE;
            resetState();
            delayTicks = 20;
            return;
        }
        resetFail();
        stage = Stage.PLACE_SHULKER;
    }

    private void placeShulker() {
        if (shulkerSlot < 0 || shulkerSlot >= 36) {
            stage = Stage.IDLE;
            resetState();
            return;
        }

        if (shulkerSlot >= 9) {
            int targetSlot = resolveHotbarSlot();
            if (targetSlot == -1) {
                info("No available hotbar slot to place shulker (all slots are protected).");
                stage = Stage.IDLE;
                resetState();
                return;
            }
            InvUtils.move().from(shulkerSlot).toHotbar(targetSlot);
            shulkerSlot = targetSlot;
            delayTicks = 2;
            return;
        }

        mc.player.getInventory().setSelectedSlot(shulkerSlot);
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(shulkerSlot));

        BlockPos placePos = findPlacement();
        if (placePos == null) {
            stage = Stage.IDLE;
            resetState();
            delayTicks = 20;
            return;
        }

        placedShulkerPos = placePos;
        Vec3 hitVec = Vec3.atCenterOf(placePos);

        if (airPlace.get()) {
            BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, placePos, false);
            int revision = mc.player.containerMenu.getStateId();

            if (rotate.get()) {
                Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec), -100, () -> {
                    mc.player.connection.send(new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
                    mc.player.connection.send(new ServerboundUseItemOnPacket(InteractionHand.OFF_HAND, hit, revision));
                    mc.player.connection.send(new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
                    mc.player.swing(InteractionHand.MAIN_HAND);
                });
            } else {
                mc.player.connection.send(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
                mc.player.connection.send(new ServerboundUseItemOnPacket(InteractionHand.OFF_HAND, hit, revision));
                mc.player.connection.send(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
                mc.player.swing(InteractionHand.MAIN_HAND);
            }
        } else {
            Vec3 supportHit = Vec3.atLowerCornerOf(placePos).add(0.5, 0.0, 0.5);
            BlockHitResult hit = new BlockHitResult(supportHit, Direction.UP, placePos.below(), false);

            if (rotate.get()) {
                Rotations.rotate(Rotations.getYaw(supportHit), Rotations.getPitch(supportHit), -100, () -> {
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                });
            } else {
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
                mc.player.swing(InteractionHand.MAIN_HAND);
            }
        }

        delayTicks = 4;
        stage = Stage.OPEN_SHULKER;
    }

    private int resolveHotbarSlot() {
        int preferred = shulkerHotbarSlot.get() - 1; // convert 1-9 display to 0-8 index

        if (!isProtected(mc.player.getInventory().getItem(preferred))) {
            return preferred;
        }

        // Preferred slot is protected. find any unprotected mhmm
        for (int i = 0; i < 9; i++) {
            if (i == preferred) continue;
            if (!isProtected(mc.player.getInventory().getItem(i))) {
                return i;
            }
        }

        return -1; // all hotbar slots are protected
    }

    private boolean isProtected(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return protectedItems.get().contains(stack.getItem());
    }

    private BlockPos findPlacement() {
        BlockPos playerPos = mc.player.blockPosition();
        Direction facing = mc.player.getDirection();

        Vec3 facingVec = new Vec3(facing.getStepX(), facing.getStepY(), facing.getStepZ());

        double rangeSq = (double) placeRange.get() * placeRange.get();

        List<BlockPos> candidates = new ArrayList<>();

        // Direct cardinal directions, then vertical, then wider ring
        candidates.add(playerPos.relative(facing));
        candidates.add(playerPos.relative(facing.getClockWise()));
        candidates.add(playerPos.relative(facing.getCounterClockWise()));
        candidates.add(playerPos.relative(facing.getOpposite()));
        for (int y = 1; y >= -1; y -= 2) candidates.add(playerPos.offset(0, y, 0));

        for (int d = 1; d <= placeRange.get(); d++) {
            for (int x = -d; x <= d; x++) {
                for (int z = -d; z <= d; z++) {
                    if (Math.abs(x) == d || Math.abs(z) == d) {
                        for (int y = -1; y <= 1; y++) {
                            candidates.add(playerPos.offset(x, y, z));
                        }
                    }
                }
            }
        }

        // Sort by dot product against facing so positions in front are tried first
        candidates.sort(Comparator.comparingDouble(pos -> {
            Vec3 offset = Vec3.atCenterOf(pos).subtract(mc.player.position()).normalize();
            return -facingVec.dot(offset);
        }));

        // First pass (only when air-place + prefer-solid-block): prefer positions
        // that have a solid block below, before falling back to true air placement.
        if (airPlace.get() && preferSolidBlock.get()) {
            for (BlockPos pos : candidates) {
                if (mc.player.position().distanceToSqr(Vec3.atCenterOf(pos)) > rangeSq) continue;
                if (!hasSpaceAbove(pos)) continue;
                if (isValidPlacePosition(pos)) return pos;
            }
            // No solid-block position found — fall through to the air-place pass below.
        }

        // Second pass (or only pass when prefer-solid-block is off / air-place is off):
        // apply the active mode rule and return the first matching candidate.
        for (BlockPos pos : candidates) {
            if (mc.player.position().distanceToSqr(Vec3.atCenterOf(pos)) > rangeSq) continue;
            if (!hasSpaceAbove(pos)) continue;
            if (airPlace.get()) {
                if (mc.level.getBlockState(pos).isAir()) return pos;
            } else {
                if (isValidPlacePosition(pos)) return pos;
            }
        }

        return null;
    }

    // position is clear so the shulker can open
    private boolean hasSpaceAbove(BlockPos pos) {
        return mc.level.getBlockState(pos.above()).isAir();
    }

    private boolean isValidPlacePosition(BlockPos pos) {
        return mc.level.getBlockState(pos).isAir()
            && mc.level.getBlockState(pos.below()).isSolidRender();
    }

    private void openShulker() {
        if (placedShulkerPos == null) {
            stage = Stage.IDLE;
            resetState();
            return;
        }

        if (mc.screen instanceof AbstractContainerScreen) {
            if (!autoTake.get()) {
                stage = Stage.WAIT_MANUAL_CLOSE;
                return;
            }
            stage = Stage.RESTOCK;
            keepFree = breakAfterFill.get() ? 1 : 0;
            delayTicks = 5;
            return;
        }

        if (!(mc.level.getBlockState(placedShulkerPos).getBlock() instanceof ShulkerBoxBlock)) {
            delayTicks = 2;
            return;
        }

        // Use placeRange instead of blockInteractionRange()
        double reach = (double) placeRange.get();
        if (mc.player.distanceToSqr(Vec3.atCenterOf(placedShulkerPos)) > reach * reach) {
            info("Shulker is too far to open. Resetting.");
            stage = Stage.IDLE;
            resetState();
            return;
        }

        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(placedShulkerPos), Direction.UP, placedShulkerPos, false);
        mc.player.connection.send(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, 0));
        mc.player.swing(InteractionHand.MAIN_HAND);
        delayTicks = 4;
    }

    private void doRestock() {
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) {
            stage = Stage.CLOSE_AND_BREAK;
            return;
        }

        int emptySlots = 0;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) emptySlots++;
        }

        if (emptySlots <= keepFree) {
            mc.player.closeContainer();
            delayTicks = 2;
            stage = Stage.CLOSE_AND_BREAK;
            return;
        }

        var handler = screen.getMenu();
        for (int i = 0; i < 27; i++) {
            ItemStack stack = handler.getSlot(i).getItem();
            if (stack.getItem() == currentTargetItem) {
                mc.gameMode.handleContainerInput(handler.containerId, i, 0, ContainerInput.QUICK_MOVE, mc.player);
                emptySlots--;
                if (emptySlots <= keepFree) break;
            }
        }

        mc.player.closeContainer();
        delayTicks = 2;
        stage = Stage.CLOSE_AND_BREAK;
    }

    private void restorePickaxeSlot() {
        if (pickaxeEquipped && preBreakSlot >= 0 && preBreakSlot <= 8) {
            mc.player.getInventory().setSelectedSlot(preBreakSlot);
            pickaxeEquipped = false;
            preBreakSlot = -1;
        }
    }

    private void closeAndBreak() {
        if (mc.screen != null) {
            mc.player.closeContainer();
            delayTicks = 2;
            return;
        }
        if (!breakAfterFill.get()) {
            restorePickaxeSlot();
            finish();
            return;
        }
        if (mc.level.getBlockState(placedShulkerPos).isAir()) {
            restorePickaxeSlot();
            finish();
            return;
        }

        if (!pickaxeEquipped) {
            int pickSlot = -1;
            for (int i = 0; i < 9; i++) {
                if (mc.player.getInventory().getItem(i).is(ItemTags.PICKAXES)) {
                    pickSlot = i;
                    break;
                }
            }
            if (pickSlot != -1) {
                preBreakSlot = mc.player.getInventory().getSelectedSlot();
                mc.player.getInventory().setSelectedSlot(pickSlot);
                pickaxeEquipped = true;
            }
        }

        mc.gameMode.continueDestroyBlock(placedShulkerPos, Direction.UP);
        mc.player.swing(InteractionHand.MAIN_HAND);

        if (mc.level.getBlockState(placedShulkerPos).isAir()) {
            restorePickaxeSlot();
            finish();
        } else {
            delayTicks = 2;
        }
    }

    private void finish() {
        restorePickaxeSlot();
        stage = Stage.IDLE;
        resetState();
        if (autoToggle.get()) toggle();
    }

    private boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem bi
            && bi.getBlock() instanceof ShulkerBoxBlock;
    }

    private boolean PrintInfo(String itemName, String reason) {
        if (itemName.equals(lastFailItem) && reason.equals(lastFailReason)) return false;
        lastFailItem = itemName;
        lastFailReason = reason;
        return true;
    }

    private void resetFail() {
        lastFailItem = "";
        lastFailReason = "";
    }
}
