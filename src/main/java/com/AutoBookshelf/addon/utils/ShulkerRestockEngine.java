package com.AutoBookshelf.addon.utils;

import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
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
import java.util.List;

public class ShulkerRestockEngine {

    public enum Stage {
        IDLE, FIND_SHULKER, PLACE_SHULKER, OPEN_SHULKER, RESTOCK,
        CLOSE_AND_BREAK, WAIT_MANUAL_CLOSE
    }

    /**
     * Per-attempt settings, gathered by the caller from its own module settings.
     */
    public record RestockConfig(
        int placeRange,
        boolean airPlace,
        boolean preferSolidBlock,
        boolean breakAfterFill,
        boolean autoTake,
        boolean rotate,
        int shulkerHotbarSlot, // 1-9, display
        List<Item> protectedItems,
        List<BlockPos> excludedPositions
    ) {
    }

    public interface RestockCallback {
        void onInfo(String message);

        void onFinished(boolean success);
    }

    private final MinecraftClient mc;
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

    /**
     * Positions where placement was attempted but never materialized, so
     * PlacementEngine can skip them on retry within the same attempt.
     */
    private final List<BlockPos> failedPositions = new ArrayList<>();

    public ShulkerRestockEngine(MinecraftClient mc, PlacementEngine placementEngine, RestockCallback callback) {
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

    /**
     * True if, on the most recently completed attempt, every stack of the target
     * item was taken out of the shulker before it was broken. False means the
     * box was broken (or left placed) while still holding leftover contents -
     * callers that break-after-fill should treat that dropped box as worth
     * chasing down, unlike a fully-emptied one.
     */
    public boolean wasShulkerFullyEmptied() {
        return shulkerFullyEmptied;
    }

    public void restoreOriginalSlotIfNeeded() {
        restoreOriginalSlot();
    }

    /**
     * Begin restocking targetItem from a shulker box somewhere in the player's inventory.
     */
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

    /**
     * Drive the state machine one tick. Returns true while still working.
     */
    public boolean tick() {
        if (mc.player == null || mc.world == null) return isActive();
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
                if (!(mc.currentScreen instanceof HandledScreen)) stage = Stage.CLOSE_AND_BREAK;
            }
            case IDLE -> {
            }
        }
        return isActive();
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
                    shulkerSlot = i;
                    break;
                }
            }
            if (shulkerSlot != -1) break;
        }
        if (shulkerSlot == -1) {
            if (shouldNotify(currentTargetItem.getName().getString(), "no shulker")) {
                callback.onInfo("No shulker with " + currentTargetItem.getName().getString());
            }
            abort(null);
            return;
        }
        stage = Stage.PLACE_SHULKER;
    }

    private void placeShulker() {
        if (mc.currentScreen instanceof HandledScreen) {
            mc.player.closeHandledScreen();
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

        ItemStack handStack = mc.player.getInventory().getStack(shulkerSlot);
        if (!isShulkerBox(handStack)) {
            abort("Shulker item missing from hotbar slot, aborting placement.");
            return;
        }

        mc.player.getInventory().setSelectedSlot(shulkerSlot);
        mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(shulkerSlot));

        List<BlockPos> avoid = new ArrayList<>(failedPositions);
        avoid.addAll(config.excludedPositions());

        BlockPos placePos = placementEngine.findPlacement(
            config.placeRange(), config.airPlace(), config.preferSolidBlock(), avoid);
        if (placePos == null) {
            abort(null);
            return;
        }

        placedShulkerPos = placePos;
        Vec3d hitVec = Vec3d.ofCenter(placePos);

        if (config.airPlace()) {
            BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, placePos, false);
            int revision = mc.player.currentScreenHandler.getRevision();

            if (config.rotate()) {
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

            if (config.rotate()) {
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
        int preferred = config.shulkerHotbarSlot() - 1; // convert 1-9 display to 0-8 index

        if (!isProtected(mc.player.getInventory().getStack(preferred))) {
            return preferred;
        }

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
        return config.protectedItems().contains(stack.getItem());
    }

    private void openShulker() {
        if (placedShulkerPos == null) {
            abort(null);
            return;
        }

        if (mc.currentScreen instanceof HandledScreen) {
            if (!config.autoTake()) {
                stage = Stage.WAIT_MANUAL_CLOSE;
                return;
            }
            stage = Stage.RESTOCK;
            delayTicks = 5;
            return;
        }

        if (!(mc.world.getBlockState(placedShulkerPos).getBlock() instanceof ShulkerBoxBlock)) {
            delayTicks = 2;
            return;
        }

        double reach = (double) config.placeRange();
        if (mc.player.squaredDistanceTo(Vec3d.ofCenter(placedShulkerPos)) > reach * reach) {
            abort("Shulker is too far to open. Resetting.");
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

        if (countEmptyPlayerSlots() <= keepFree) {
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
                // Re-check the real inventory rather than assuming this quick-move
                // consumed an empty slot. currentTargetItem is the block the player
                // is actively building with, so a partial stack of it almost always
                // already exists in the hotbar/inventory - QUICK_MOVE merges into
                // that existing stack instead of using a new slot in that case, and
                // blindly decrementing here caused the loop to stop early "to keep
                // 1 slot free" when no slot had actually been spent, leaving items
                // behind in the shulker for no reason.
                if (countEmptyPlayerSlots() <= keepFree) break;
            }
        }

        // Any target-item slot in the shulker still holding a stack after the loop
        // above means we stopped short of fully emptying it (either genuinely out
        // of inventory room, or - previously - due to the miscounted early exit).
        // Record that so the caller can tell a "fully emptied" success apart from
        // a "still has contents" one and decide whether to bother chasing down
        // the dropped box after it's broken.
        shulkerFullyEmptied = true;
        for (int i = 0; i < 27; i++) {
            if (handler.getSlot(i).getStack().getItem() == currentTargetItem) {
                shulkerFullyEmptied = false;
                break;
            }
        }

        mc.player.closeHandledScreen();
        delayTicks = 2;
        stage = Stage.CLOSE_AND_BREAK;
    }

    private int countEmptyPlayerSlots() {
        int empty = 0;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) empty++;
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

    /**
     * Restores whatever the player had selected before start() ran (their platform
     * material, most likely). Runs after restorePickaxeSlot()
     */
    private void restoreOriginalSlot() {
        if (originalSelectedSlot >= 0 && originalSelectedSlot <= 8) {
            mc.player.getInventory().setSelectedSlot(originalSelectedSlot);
            originalSelectedSlot = -1;
        }
    }

    private void closeAndBreak() {
        if (mc.currentScreen != null) {
            mc.player.closeHandledScreen();
            delayTicks = 2;
            return;
        }
        if (!config.breakAfterFill()) {
            succeed();
            return;
        }
        if (mc.world.getBlockState(placedShulkerPos).isAir()) {
            succeed();
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
