package com.AutoBookshelf.addon.utils;

import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.client.Minecraft;
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
import java.util.List;

public class ShulkerRestockEngine {

    public enum Stage {
        IDLE, FIND_SHULKER, PLACE_SHULKER, OPEN_SHULKER, RESTOCK,
        CLOSE_AND_BREAK, WAIT_MANUAL_CLOSE
    }

    public record RestockConfig(
        int placeRange,
        boolean airPlace,
        boolean preferSolidBlock,
        boolean breakAfterFill,
        boolean autoTake,
        boolean rotate,
        int shulkerHotbarSlot,
        List<Item> protectedItems,
        List<BlockPos> excludedPositions
    ) {
    }

    public interface RestockCallback {
        void onInfo(String message);

        void onFinished(boolean success);
    }

    private final Minecraft mc;
    private final PlacementEngine placementEngine;
    private final RestockCallback callback;

    private Stage stage = Stage.IDLE;
    private Item currentTargetItem;
    private RestockConfig config;
    private int shulkerSlot = -1;

    private int originalSelectedSlot = -1;

    private BlockPos placedShulkerPos;
    private int delayTicks;
    private int keepFree;
    private boolean pickaxeEquipped;
    private int preBreakSlot = -1;
    private String lastFailItem = "";
    private String lastFailReason = "";
    private boolean shulkerFullyEmptied = false;

    private final List<BlockPos> failedPositions = new ArrayList<>();

    public ShulkerRestockEngine(Minecraft mc, PlacementEngine placementEngine, RestockCallback callback) {
        this.mc = mc;
        this.placementEngine = placementEngine;
        this.callback = callback;
    }

    public boolean isActive() {
        return stage != Stage.IDLE;
    }

    public void reset() {
        stage = Stage.IDLE;
        currentTargetItem = null;
        config = null;
        shulkerSlot = -1;
        originalSelectedSlot = -1;
        placedShulkerPos = null;
        delayTicks = 0;
        keepFree = 0;
        pickaxeEquipped = false;
        preBreakSlot = -1;
        shulkerFullyEmptied = false;
        failedPositions.clear();
    }

    public boolean wasShulkerFullyEmptied() {
        return shulkerFullyEmptied;
    }

    public void restoreOriginalSlotIfNeeded() {
        restoreOriginalSlot();
    }

    public void start(Item targetItem, RestockConfig config) {
        this.currentTargetItem = targetItem;
        this.config = config;
        this.originalSelectedSlot = mc.player.getInventory().getSelectedSlot();
        this.keepFree = config.breakAfterFill() ? 1 : 0;
        this.failedPositions.clear();
        this.lastFailItem = "";
        this.lastFailReason = "";
        this.stage = Stage.FIND_SHULKER;
    }

    public boolean tick() {
        if (mc.player == null || mc.level == null) return isActive();
        if (delayTicks > 0) {
            delayTicks--;
            return true;
        }

        switch (stage) {
            case FIND_SHULKER -> selectShulker();
            case PLACE_SHULKER -> placeShulker();
            case OPEN_SHULKER -> openShulker();
            case RESTOCK -> doRestock();
            case CLOSE_AND_BREAK -> closeAndBreak();
            case WAIT_MANUAL_CLOSE -> {
                if (!(mc.screen instanceof AbstractContainerScreen)) stage = Stage.CLOSE_AND_BREAK;
            }
            case IDLE -> {
            }
        }
        return isActive();
    }

    private void selectShulker() {
        shulkerSlot = -1;
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!isShulkerBox(stack)) continue;
            ItemContainerContents container = stack.get(DataComponents.CONTAINER);
            if (container == null) continue;
            for (ItemStackTemplate content : container.nonEmptyItems()) {
                if (content.is(currentTargetItem)) {
                    shulkerSlot = i;
                    break;
                }
            }
            if (shulkerSlot != -1) break;
        }
        if (shulkerSlot == -1) {
            if (shouldNotify(currentTargetItem.getName(new ItemStack(currentTargetItem)).getString(), "no shulker")) {
                callback.onInfo("No shulker with " + currentTargetItem.getName(new ItemStack(currentTargetItem)).getString());
            }
            abort(null);
            return;
        }
        stage = Stage.PLACE_SHULKER;
    }

    private void placeShulker() {
        if (mc.screen instanceof AbstractContainerScreen) {
            mc.player.closeContainer();
            delayTicks = 3;
            return;
        }

        if (shulkerSlot < 0 || shulkerSlot >= 36) {
            abort(null);
            return;
        }

        if (shulkerSlot >= 9) {
            int targetSlot = resolveHotbarSlot();
            if (targetSlot == -1) {
                abort("No available hotbar slot to place shulker (all slots are protected).");
                return;
            }
            InvUtils.move().from(shulkerSlot).toHotbar(targetSlot);
            shulkerSlot = targetSlot;
            delayTicks = 2;
            return;
        }

        ItemStack handStack = mc.player.getInventory().getItem(shulkerSlot);
        if (!isShulkerBox(handStack)) {
            abort("Shulker item missing from hotbar slot, aborting placement.");
            return;
        }

        mc.player.getInventory().setSelectedSlot(shulkerSlot);
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(shulkerSlot));

        List<BlockPos> avoid = new ArrayList<>(failedPositions);
        avoid.addAll(config.excludedPositions());

        BlockPos placePos = placementEngine.findPlacement(
            config.placeRange(), config.airPlace(), config.preferSolidBlock(), avoid);
        if (placePos == null) {
            abort(null);
            return;
        }

        placedShulkerPos = placePos;
        Vec3 hitVec = Vec3.atCenterOf(placePos);

        if (config.airPlace()) {
            BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, placePos, false);
            int revision = mc.player.containerMenu.getStateId();

            if (config.rotate()) {
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

            if (config.rotate()) {
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
        int preferred = config.shulkerHotbarSlot() - 1;

        if (!isProtected(mc.player.getInventory().getItem(preferred))) {
            return preferred;
        }

        for (int i = 0; i < 9; i++) {
            if (i == preferred) continue;
            if (!isProtected(mc.player.getInventory().getItem(i))) {
                return i;
            }
        }

        return -1;
    }

    private boolean isProtected(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return config.protectedItems().contains(stack.getItem());
    }

    private void openShulker() {
        if (placedShulkerPos == null) {
            abort(null);
            return;
        }

        if (mc.screen instanceof AbstractContainerScreen) {
            if (!config.autoTake()) {
                stage = Stage.WAIT_MANUAL_CLOSE;
                return;
            }
            stage = Stage.RESTOCK;
            delayTicks = 5;
            return;
        }

        if (!(mc.level.getBlockState(placedShulkerPos).getBlock() instanceof ShulkerBoxBlock)) {
            delayTicks = 2;
            return;
        }

        double reach = (double) config.placeRange();
        if (mc.player.distanceToSqr(Vec3.atCenterOf(placedShulkerPos)) > reach * reach) {
            abort("Shulker is too far to open. Resetting.");
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

        if (countEmptyPlayerSlots() <= keepFree) {
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
                if (countEmptyPlayerSlots() <= keepFree) break;
            }
        }

        shulkerFullyEmptied = true;
        for (int i = 0; i < 27; i++) {
            if (handler.getSlot(i).getItem().getItem() == currentTargetItem) {
                shulkerFullyEmptied = false;
                break;
            }
        }

        mc.player.closeContainer();
        delayTicks = 2;
        stage = Stage.CLOSE_AND_BREAK;
    }

    private int countEmptyPlayerSlots() {
        int empty = 0;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) empty++;
        }
        return empty;
    }

    private void restorePickaxeSlot() {
        if (pickaxeEquipped && preBreakSlot >= 0 && preBreakSlot <= 8) {
            mc.player.getInventory().setSelectedSlot(preBreakSlot);
            pickaxeEquipped = false;
            preBreakSlot = -1;
        }
    }

    private void restoreOriginalSlot() {
        if (originalSelectedSlot >= 0 && originalSelectedSlot <= 8) {
            mc.player.getInventory().setSelectedSlot(originalSelectedSlot);
            originalSelectedSlot = -1;
        }
    }

    private void closeAndBreak() {
        if (mc.screen != null) {
            mc.player.closeContainer();
            delayTicks = 2;
            return;
        }
        if (!config.breakAfterFill()) {
            succeed();
            return;
        }
        if (mc.level.getBlockState(placedShulkerPos).isAir()) {
            succeed();
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
            succeed();
        } else {
            delayTicks = 2;
        }
    }

    private boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem bi
            && bi.getBlock() instanceof ShulkerBoxBlock;
    }

    private boolean shouldNotify(String itemName, String reason) {
        if (itemName.equals(lastFailItem) && reason.equals(lastFailReason)) return false;
        lastFailItem = itemName;
        lastFailReason = reason;
        return true;
    }

    private void abort(String message) {
        if (message != null) callback.onInfo(message);
        restoreOriginalSlot();
        reset();
        callback.onFinished(false);
    }

    private void succeed() {
        restorePickaxeSlot();
        restoreOriginalSlot();
        reset();
        callback.onFinished(true);
    }
}
