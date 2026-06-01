package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.util.*;

public class MaterialsRefill extends Module {
    private enum Stage {
        IDLE, FIND_SHULKER, PLACE_SHULKER, OPEN_SHULKER, RESTOCK, CLOSE_AND_BREAK, WAIT_MANUAL_CLOSE
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<List<Item>> targetItems = sgGeneral.add(new ItemListSetting.Builder()
        .name("target-items")
        .description("Items to keep stocked.")
        .defaultValue(List.of(Items.BROWN_MUSHROOM))
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
        .description("how far is the placement range.")
        .defaultValue(2)
        .min(1)
        .sliderMax(4)
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
        .description("Rotate when placing.")
        .defaultValue(true)
        .build()
    );

    private Stage stage = Stage.IDLE;
    private Item currentTargetItem;
    private int shulkerSlot = -1;
    private BlockPos placedShulkerPos;
    private int delayTicks;
    private int keepFree = 0;   // number of empty slots to preserve (0 if not breaking, 1 if breaking)
    private boolean pickaxeEquipped = false;
    private int preBreakSlot = -1;
    private String lastFailItem = "";
    private String lastFailReason = "";
    private Direction placedFace;
    private Vec3 placedHitVec;
    private BlockPos placedSupportBlock;

    public MaterialsRefill() {
        super(Addon.CATEGORY, "Mats-Refill",
            "Automatically restocks materials from shulker boxes.");
    }

    @Override public void onActivate() {
        stage = Stage.IDLE;
        resetState();
    }
    @Override public void onDeactivate() { resetState(); }

    private void resetState() {
        currentTargetItem = null;
        shulkerSlot = -1;
        placedShulkerPos = null;
        delayTicks = 0;
        keepFree = breakAfterFill.get() ? 1 : 0;
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
                // Wait until the player closes the GUI, then break the shulker
                if (!(mc.screen instanceof AbstractContainerScreen)) {
                    stage = Stage.CLOSE_AND_BREAK;
                }
            }
        }
    }

    private void checkStock() {
        for (Item item : targetItems.get()) {
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
            ItemContainerContents container = stack.get(DataComponents.CONTAINER);
            if (container == null) continue;
            for (ItemStackTemplate content : container.nonEmptyItems()) {
                if (content.item() == currentTargetItem) {
                    shulkerSlot = i; break;
                }
            }
            if (shulkerSlot != -1) break;
        }
        if (shulkerSlot == -1) {
            if (PrintInfo(currentTargetItem.getName(currentTargetItem.getDefaultInstance()).getString(), "no shulker")) {
                info("No shulker with " + currentTargetItem.getName(currentTargetItem.getDefaultInstance()).getString());
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
            InvUtils.move().from(shulkerSlot).toHotbar(0);
            shulkerSlot = 0;
            delayTicks = 2;
            return;
        }

        mc.player.getInventory().setSelectedSlot(shulkerSlot);

        BlockPos placePos = null;
        Direction placeFace = null;
        BlockPos supportBlock = null;
        Vec3 hitVec = null;

        // Normal crosshair placement
        if (mc.hitResult instanceof BlockHitResult blockHit) {
            BlockPos clickedBlock = blockHit.getBlockPos();
            Direction clickedFace = blockHit.getDirection();
            BlockPos candidate = clickedBlock.relative(clickedFace);

            double reach = mc.player.entityInteractionRange();

            if ((mc.level.getBlockState(candidate).isAir()
                || mc.level.getBlockState(candidate).canBeReplaced())
                && mc.player.position().distanceToSqr(
                candidate.getX() + 0.5,
                candidate.getY() + 0.5,
                candidate.getZ() + 0.5
            ) <= reach * reach) {

                placePos = candidate;
                placeFace = clickedFace;
                supportBlock = clickedBlock;
                hitVec = blockHit.getLocation();
            }
        }

        // Fallback placement
        if (placePos == null) {
            Vec3 eyePos = mc.player.getEyePosition();
            Vec3 lookVec = mc.player.getViewVector(1.0F);

            BlockPos targetBlock = BlockPos.containing(
                eyePos.add(lookVec.scale(placeRange.get()))
            );

            BlockPos solidBlock = null;

            for (int dy = -2; dy <= 2; dy++) {
                BlockPos check = targetBlock.offset(0, dy, 0);

                if (mc.level.getBlockState(check).isRedstoneConductor(mc.level, check)) {
                    solidBlock = check;
                    break;
                }
            }

            if (solidBlock == null) {
                if (PrintInfo("fallback", "no solid block")) {
                    info("No valid fallback placement found.");
                }
                stage = Stage.IDLE;
                resetState();
                delayTicks = 20;
                return;
            }

            BlockPos candidate = solidBlock.above();
            Direction candidateFace = Direction.UP;

            if (!mc.level.getBlockState(candidate).canBeReplaced()) {
                boolean found = false;

                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos sidePos = solidBlock.relative(dir);

                    if (mc.level.getBlockState(sidePos).canBeReplaced()) {
                        candidate = sidePos;
                        candidateFace = dir;
                        found = true;
                        break;
                    }
                }

                if (!found) {
                    if (PrintInfo("fallback", "no free side")) {
                        info("No free side around fallback block.");
                    }
                    stage = Stage.IDLE;
                    resetState();
                    delayTicks = 20;
                    return;
                }
            }

            double reach = mc.player.entityInteractionRange();

            if (mc.player.position().distanceToSqr(
                candidate.getX() + 0.5,
                candidate.getY() + 0.5,
                candidate.getZ() + 0.5
            ) > reach * reach) {
                if (PrintInfo("fallback", "out of reach")) {
                    info("Fallback placement out of reach.");
                }
                stage = Stage.IDLE;
                resetState();
                delayTicks = 20;
                return;
            }

            placePos = candidate;
            placeFace = candidateFace;

            supportBlock = candidate.relative(candidateFace.getOpposite());

            hitVec = Vec3.atCenterOf(supportBlock)
                .add(Vec3.atLowerCornerOf(candidateFace.getUnitVec3i()).scale(0.5));
        }

        // Save placement data for later opening
        placedShulkerPos = placePos;
        placedFace = placeFace;
        placedHitVec = hitVec;
        placedSupportBlock = supportBlock;

        if (rotate.get()) {
            Vec3 finalHitVec = hitVec;
            Direction finalPlaceFace = placeFace;
            BlockPos finalSupportBlock = supportBlock;

            Rotations.rotate(
                Rotations.getYaw(finalHitVec),
                Rotations.getPitch(finalHitVec),
                -100,
                () -> {
                    BlockHitResult hit = new BlockHitResult(
                        finalHitVec,
                        finalPlaceFace,
                        finalSupportBlock,
                        false
                    );

                    mc.gameMode.useItemOn(
                        mc.player,
                        InteractionHand.MAIN_HAND,
                        hit
                    );

                    mc.player.swing(InteractionHand.MAIN_HAND);
                }
            );
        } else {
            BlockHitResult hit = new BlockHitResult(
                hitVec,
                Direction.UP,
                placedShulkerPos,
                false
            );

            mc.gameMode.useItemOn(
                mc.player,
                InteractionHand.MAIN_HAND,
                hit
            );

            mc.player.swing(InteractionHand.MAIN_HAND);
        }

        delayTicks = 4;
        stage = Stage.OPEN_SHULKER;
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

        // Wait until the shulker block actually exists
        if (!(mc.level.getBlockState(placedShulkerPos).getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock)) {
            delayTicks = 2;
            return;
        }

        // Check reach
        double reach = mc.player.entityInteractionRange();
        if (mc.player.distanceToSqr(
            placedShulkerPos.getX() + 0.5,
            placedShulkerPos.getY() + 0.5,
            placedShulkerPos.getZ() + 0.5
        ) > reach * reach) {
            info("Shulker is too far to open. Resetting.");
            stage = Stage.IDLE;
            resetState();
            return;
        }

        // Always open by clicking the centre of the shulker with UP face
        BlockHitResult hit = new BlockHitResult(
            Vec3.atCenterOf(placedShulkerPos),
            Direction.UP,
            placedShulkerPos,
            false
        );
        mc.player.connection.send(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, 0));
        mc.player.swing(InteractionHand.MAIN_HAND);
        delayTicks = 4;
    }

    private void doRestock() {
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) {
            stage = Stage.CLOSE_AND_BREAK;
            return;
        }

        // Count empty slots in main inventory
        int emptySlots = 0;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) emptySlots++;
        }

        // If we have only the required number of empty slots, stop
        if (emptySlots <= keepFree) {
            mc.player.closeContainer();
            delayTicks = 2;
            stage = Stage.CLOSE_AND_BREAK;
            return;
        }

        var handler = screen.getMenu();
        int moved = 0;

        // Loop over all shulker slots and shiftclick each matching stack instantly
        for (int i = 0; i < 27; i++) {
            ItemStack stack = handler.getSlot(i).getItem();
            if (stack.getItem() == currentTargetItem) {
                mc.gameMode.handleContainerInput(handler.containerId, i, 0, ContainerInput.QUICK_MOVE, mc.player);
                moved++;

                // Stop if we have filled all slots
                emptySlots--;
                if (emptySlots <= keepFree) break;
            }
        }

        // After moving everything, close and proceed to break
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
            // Restore pickaxe if we had swapped
            restorePickaxeSlot();
            stage = Stage.IDLE;
            resetState();
            if (autoToggle.get()) toggle();
            return;
        }
        if (mc.level.getBlockState(placedShulkerPos).isAir()) {
            // Block already gone, restore slot and finish
            restorePickaxeSlot();
            stage = Stage.IDLE;
            resetState();
            if (autoToggle.get()) toggle();
            return;
        }

        // Equip a pickaxe (if there is one)
        if (!pickaxeEquipped) {
            int pickSlot = -1;
            // Search hotbar for any pickaxe item
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
            // If no pickaxe found, we just use whatever is in hand
        }

        // Continuous mining until broken
        mc.gameMode.continueDestroyBlock(placedShulkerPos, Direction.UP);
        mc.player.swing(InteractionHand.MAIN_HAND);
        delayTicks = 2;
    }

    private boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() == Items.SHULKER_BOX ||
            stack.getItem() == Items.WHITE_SHULKER_BOX ||
            stack.getItem() == Items.ORANGE_SHULKER_BOX ||
            stack.getItem() == Items.MAGENTA_SHULKER_BOX ||
            stack.getItem() == Items.LIGHT_BLUE_SHULKER_BOX ||
            stack.getItem() == Items.YELLOW_SHULKER_BOX ||
            stack.getItem() == Items.LIME_SHULKER_BOX ||
            stack.getItem() == Items.PINK_SHULKER_BOX ||
            stack.getItem() == Items.GRAY_SHULKER_BOX ||
            stack.getItem() == Items.LIGHT_GRAY_SHULKER_BOX ||
            stack.getItem() == Items.CYAN_SHULKER_BOX ||
            stack.getItem() == Items.PURPLE_SHULKER_BOX ||
            stack.getItem() == Items.BLUE_SHULKER_BOX ||
            stack.getItem() == Items.BROWN_SHULKER_BOX ||
            stack.getItem() == Items.GREEN_SHULKER_BOX ||
            stack.getItem() == Items.RED_SHULKER_BOX ||
            stack.getItem() == Items.BLACK_SHULKER_BOX;
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
