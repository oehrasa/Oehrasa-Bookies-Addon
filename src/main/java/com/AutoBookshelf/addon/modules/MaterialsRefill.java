package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

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
    private String lastFailItem = "";
    private String lastFailReason = "";

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
                // Wait until the player closes the GUI, then break the shulker
                if (!(mc.currentScreen instanceof HandledScreen)) {
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
            InvUtils.move().from(shulkerSlot).toHotbar(0);
            shulkerSlot = 0;
            delayTicks = 2;
            return;
        }

        mc.player.getInventory().setSelectedSlot(shulkerSlot);

        BlockPos placePos = null;
        Direction placeFace = null;
        BlockPos supportBlock = null;
        Vec3d hitVec = null;

        // 1) Main: crosshair target
        if (mc.crosshairTarget instanceof BlockHitResult blockHit) {
            BlockPos clickedBlock = blockHit.getBlockPos();
            Direction clickedFace = blockHit.getSide();
            BlockPos candidate = clickedBlock.offset(clickedFace);

            double reach = mc.player.getEntityInteractionRange();
            if ((mc.world.getBlockState(candidate).isAir()
                || mc.world.getBlockState(candidate).getBlock() == net.minecraft.block.Blocks.WATER)
                && mc.player.getPos().squaredDistanceTo(candidate.getX() + 0.5, candidate.getY() + 0.5, candidate.getZ() + 0.5) <= reach * reach) {
                placePos = candidate;
                placeFace = clickedFace;
                supportBlock = clickedBlock;
                hitVec = blockHit.getPos();
            }
        }

        // 2) Fallback
        if (placePos == null) {
            Vec3d lookVec = mc.player.getRotationVec(1.0F).multiply(placeRange.get());
            BlockPos targetBlock = mc.player.getBlockPos().add((int) lookVec.x, 0, (int) lookVec.z);

            // Find the nearest solid block within +2 Y of the target position
            BlockPos solidBlock = null;
            for (int dy = -2; dy <= 2; dy++) {
                BlockPos check = targetBlock.add(0, dy, 0);
                if (mc.world.getBlockState(check).isSolidBlock(mc.world, check)) {
                    solidBlock = check;
                    break;
                }
            }

            if (solidBlock == null) {
                stage = Stage.IDLE;
                resetState();
                return;
            }

            // Try to place on top of the solid block
            BlockPos candidate = solidBlock.up();
            Direction candidateFace = Direction.UP;
            boolean found = false;

            if (!mc.world.getBlockState(candidate).isAir()) {
                // Top blocked? then try sides
                for (Direction dir : Direction.values()) {
                    BlockPos neighbor = solidBlock.offset(dir);
                    if (mc.world.getBlockState(neighbor).isAir()) {
                        candidate = neighbor;
                        candidateFace = dir;
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    stage = Stage.IDLE;
                    resetState();
                    return;
                }
            }

            // Ensure the placement is within range
            double reach = mc.player.getEntityInteractionRange();
            if (mc.player.getPos().squaredDistanceTo(candidate.getX() + 0.5, candidate.getY() + 0.5, candidate.getZ() + 0.5) > reach * reach) {
                stage = Stage.IDLE;
                resetState();
                return;
            }

            placePos = candidate;
            placeFace = candidateFace;
            supportBlock = placePos.offset(candidateFace.getOpposite());
            hitVec = Vec3d.ofCenter(supportBlock).add(Vec3d.of(candidateFace.getVector()).multiply(0.5));
        }

        placedShulkerPos = placePos;

        // Place the shulker
        if (rotate.get()) {
            Vec3d finalHitVec = hitVec;
            Direction finalPlaceFace = placeFace;
            BlockPos finalSupportBlock = supportBlock;
            Rotations.rotate(Rotations.getYaw(finalHitVec), Rotations.getPitch(finalHitVec), -100, () -> {
                sendPlacePacket(finalSupportBlock, finalPlaceFace, finalHitVec);
            });
        } else {
            sendPlacePacket(supportBlock, placeFace, hitVec);
        }

        delayTicks = 4;
        stage = Stage.OPEN_SHULKER;
    }

    private void sendPlacePacket(BlockPos support, Direction face, Vec3d hitVec) {
        BlockHitResult hit = new BlockHitResult(hitVec, face, support, false);
        mc.player.networkHandler.sendPacket(
            new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hit, 0)
        );
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private void openShulker() {
        if (mc.currentScreen instanceof HandledScreen) {
            if (!autoTake.get()) {
                // Do not close automatically
                stage = Stage.WAIT_MANUAL_CLOSE;
                return;
            }
            // Auto take enabled, we proceed to restock
            stage = Stage.RESTOCK;
            keepFree = breakAfterFill.get() ? 1 : 0;
            delayTicks = 5;
            return;
        }

        // Check if the shulker is within reach
        double reach = mc.player.getEntityInteractionRange();
        if (mc.player.squaredDistanceTo(placedShulkerPos.getX() + 0.5, placedShulkerPos.getY() + 0.5, placedShulkerPos.getZ() + 0.5) > reach * reach) {
            info("Shulker is too far to open. Resetting.");
            stage = Stage.IDLE;
            resetState();
            return;
        }

        // Send open packet
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

        // Count empty slots in main inventory
        int emptySlots = 0;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) emptySlots++;
        }

        // If we have only the required number of empty slots, stop
        if (emptySlots <= keepFree) {
            mc.player.closeHandledScreen();
            delayTicks = 2;
            stage = Stage.CLOSE_AND_BREAK;
            return;
        }

        var handler = screen.getScreenHandler();
        int moved = 0;

        // Loop over all shulker slots and shiftclick each matching stack instantly
        for (int i = 0; i < 27; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.getItem() == currentTargetItem) {
                mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                moved++;

                // Stop if we have filled all slots
                emptySlots--;
                if (emptySlots <= keepFree) break;
            }
        }

        // After moving everything, close and proceed to break
        mc.player.closeHandledScreen();
        delayTicks = 2;
        stage = Stage.CLOSE_AND_BREAK;
    }

    private void closeAndBreak() {
        if (mc.currentScreen != null) {
            mc.player.closeHandledScreen();
            delayTicks = 2;
            return;
        }
        if (!breakAfterFill.get()) {
            stage = Stage.IDLE;
            resetState();
            if (autoToggle.get()) toggle();
            return;
        }
        if (mc.world.getBlockState(placedShulkerPos).isAir()) {
            stage = Stage.IDLE;
            resetState();
            if (autoToggle.get()) toggle();
            return;
        }
        // Continuous mining until broken
        mc.interactionManager.updateBlockBreakingProgress(placedShulkerPos, Direction.UP);
        mc.player.swingHand(Hand.MAIN_HAND);
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
