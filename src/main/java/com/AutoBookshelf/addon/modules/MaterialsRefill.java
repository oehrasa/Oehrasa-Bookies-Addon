package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

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
            ItemStack held = mc.player.getMainHandStack();
            if (!held.isEmpty()) {
                List<Item> current = new ArrayList<>(targetItems.get());
                if (!current.contains(held.getItem())) {
                    current.add(held.getItem());
                    targetItems.set(current);
                    info("Added target item: " + held.getItem().getName().getString());
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
        if (mc.player == null || mc.world == null) return;
        if (delayTicks > 0) { delayTicks--; return; }

        switch (stage) {
            case IDLE -> checkStock();
            case FIND_SHULKER -> selectShulker();
            case PLACE_SHULKER -> placeShulker();
            case OPEN_SHULKER -> openShulker();
            case RESTOCK -> doRestock();
            case CLOSE_AND_BREAK -> closeAndBreak();
            case WAIT_MANUAL_CLOSE -> {
                if (!(mc.currentScreen instanceof HandledScreen)) {
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
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!isShulkerBox(stack)) continue;
            ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
            if (container == null) continue;
            for (ItemStack content : container.iterateNonEmpty()) {
                if (content.getItem() == currentTargetItem) {
                    shulkerSlot = i; break;
                }
            }
            if (shulkerSlot != -1) break;
        }
        if (shulkerSlot == -1) {
            if (PrintInfo(currentTargetItem.getName().getString(), "no shulker")) {
                info("No shulker with " + currentTargetItem.getName().getString());
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
        mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(shulkerSlot));

        BlockPos placePos = findPlacement();
        if (placePos == null) {
            stage = Stage.IDLE;
            resetState();
            delayTicks = 20;
            return;
        }

        placedShulkerPos = placePos;
        Vec3d hitVec = Vec3d.ofCenter(placePos);

        if (airPlace.get()) {
            BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, placePos, false);
            int revision = mc.player.currentScreenHandler.getRevision();

            if (rotate.get()) {
                Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec), -100, () -> {
                    mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                        PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
                    mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(Hand.OFF_HAND, hit, revision));
                    mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                        PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
                    mc.player.swingHand(Hand.MAIN_HAND);
                });
            } else {
                mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                    PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
                mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(Hand.OFF_HAND, hit, revision));
                mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                    PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
                mc.player.swingHand(Hand.MAIN_HAND);
            }
        } else {
            Vec3d supportHit = Vec3d.of(placePos).add(0.5, 0.0, 0.5);
            BlockHitResult hit = new BlockHitResult(supportHit, Direction.UP, placePos.down(), false);

            if (rotate.get()) {
                Rotations.rotate(Rotations.getYaw(supportHit), Rotations.getPitch(supportHit), -100, () -> {
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                    mc.player.swingHand(Hand.MAIN_HAND);
                });
            } else {
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                mc.player.swingHand(Hand.MAIN_HAND);
            }
        }

        delayTicks = 4;
        stage = Stage.OPEN_SHULKER;
    }

    private int resolveHotbarSlot() {
        int preferred = shulkerHotbarSlot.get() - 1; // convert 1-9 display to 0-8 index

        if (!isProtected(mc.player.getInventory().getStack(preferred))) {
            return preferred;
        }

        // Preferred slot is protected. find any unprotected mhmm
        for (int i = 0; i < 9; i++) {
            if (i == preferred) continue;
            if (!isProtected(mc.player.getInventory().getStack(i))) {
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
        BlockPos playerPos = mc.player.getBlockPos();
        Direction facing = mc.player.getHorizontalFacing();
        Vec3d facingVec = Vec3d.of(facing.getVector());
        double rangeSq = (double) placeRange.get() * placeRange.get();

        List<BlockPos> candidates = new ArrayList<>();

        // Direct cardinal directions, then vertical, then wider ring
        candidates.add(playerPos.offset(facing));
        candidates.add(playerPos.offset(facing.rotateYClockwise()));
        candidates.add(playerPos.offset(facing.rotateYCounterclockwise()));
        candidates.add(playerPos.offset(facing.getOpposite()));
        for (int y = 1; y >= -1; y -= 2) candidates.add(playerPos.add(0, y, 0));

        for (int d = 1; d <= placeRange.get(); d++) {
            for (int x = -d; x <= d; x++) {
                for (int z = -d; z <= d; z++) {
                    if (Math.abs(x) == d || Math.abs(z) == d) {
                        for (int y = -1; y <= 1; y++) {
                            candidates.add(playerPos.add(x, y, z));
                        }
                    }
                }
            }
        }

        // Sort wider candidates by dot product against facing so positions in front are tried first
        candidates.sort(Comparator.comparingDouble(pos -> {
            Vec3d offset = Vec3d.ofCenter(pos).subtract(mc.player.getPos()).normalize();
            return -facingVec.dotProduct(offset);
        }));

        if (airPlace.get() && preferSolidBlock.get()) {
            for (BlockPos pos : candidates) {
                if (mc.player.getPos().squaredDistanceTo(Vec3d.ofCenter(pos)) > rangeSq) continue;
                if (!hasSpaceAbove(pos)) continue;
                if (isValidPlacePosition(pos)) return pos;
            }
            // No solid-block position found in range. fall through below.
        }

        // Second pass (or only pass when prefer-solid-block is off / air-place is off):
        // apply the active mode rule and return the first matching candidate.
        for (BlockPos pos : candidates) {
            if (mc.player.getPos().squaredDistanceTo(Vec3d.ofCenter(pos)) > rangeSq) continue;

            if (!hasSpaceAbove(pos)) continue;

            if (airPlace.get()) {
                if (mc.world.getBlockState(pos).isAir()) return pos;
            } else {
                if (isValidPlacePosition(pos)) return pos;
            }
        }

        return null;
    }

    private boolean hasSpaceAbove(BlockPos pos) {
        BlockState above = mc.world.getBlockState(pos.up());
        return above.isAir();
    }

    // Support block for placing
    private boolean isValidPlacePosition(BlockPos pos) {
        return mc.world.getBlockState(pos).isAir()
            && mc.world.getBlockState(pos.down()).isSolidBlock(mc.world, pos.down());
    }

    private void openShulker() {
        if (placedShulkerPos == null) {
            stage = Stage.IDLE;
            resetState();
            return;
        }

        if (mc.currentScreen instanceof HandledScreen) {
            if (!autoTake.get()) {
                stage = Stage.WAIT_MANUAL_CLOSE;
                return;
            }
            stage = Stage.RESTOCK;
            keepFree = breakAfterFill.get() ? 1 : 0;
            delayTicks = 5;
            return;
        }

        if (!(mc.world.getBlockState(placedShulkerPos).getBlock() instanceof ShulkerBoxBlock)) {
            delayTicks = 2;
            return;
        }

        double reach = (double) placeRange.get();
        if (mc.player.squaredDistanceTo(Vec3d.ofCenter(placedShulkerPos)) > reach * reach) {
            info("Shulker is too far to open. Resetting.");
            stage = Stage.IDLE;
            resetState();
            return;
        }

        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(placedShulkerPos), Direction.UP, placedShulkerPos, false);
        mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hit, 0));
        mc.player.swingHand(Hand.MAIN_HAND);
        delayTicks = 4;
    }

    private void doRestock() {
        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) {
            stage = Stage.CLOSE_AND_BREAK;
            return;
        }

        int emptySlots = 0;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) emptySlots++;
        }

        if (emptySlots <= keepFree) {
            mc.player.closeHandledScreen();
            delayTicks = 2;
            stage = Stage.CLOSE_AND_BREAK;
            return;
        }

        var handler = screen.getScreenHandler();
        for (int i = 0; i < 27; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.getItem() == currentTargetItem) {
                mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                emptySlots--;
                if (emptySlots <= keepFree) break;
            }
        }

        mc.player.closeHandledScreen();
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
        if (mc.currentScreen != null) {
            mc.player.closeHandledScreen();
            delayTicks = 2;
            return;
        }
        if (!breakAfterFill.get()) {
            restorePickaxeSlot();
            finish();
            return;
        }
        if (mc.world.getBlockState(placedShulkerPos).isAir()) {
            restorePickaxeSlot();
            finish();
            return;
        }

        if (!pickaxeEquipped) {
            int pickSlot = -1;
            for (int i = 0; i < 9; i++) {
                if (mc.player.getInventory().getStack(i).isIn(ItemTags.PICKAXES)) {
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

        mc.interactionManager.updateBlockBreakingProgress(placedShulkerPos, Direction.UP);
        mc.player.swingHand(Hand.MAIN_HAND);

        if (mc.world.getBlockState(placedShulkerPos).isAir()) {
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
