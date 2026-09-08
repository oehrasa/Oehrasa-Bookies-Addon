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
        IDLE, FIND_SHULKER,
        PLACE_SHULKER, WAIT_FOR_PLACEMENT,
        OPEN_SHULKER, WAIT_FOR_OPEN,
        RESTOCK,
        CLOSE_SHULKER, START_BREAK, WAIT_FOR_BREAK,
        WAIT_MANUAL_CLOSE
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

    // Bounded wait times for each confirmation state, in client ticks. These
    // exist because sending a packet is not confirmation that the server
    // acted on it - every WAIT_FOR_* state polls the actual world/UI state
    // each tick instead of just assuming success after a fixed delay.
    private static final int PLACEMENT_TIMEOUT_TICKS = 20;
    private static final int OPEN_TIMEOUT_TICKS = 20;
    private static final int BREAK_TIMEOUT_TICKS = 200;
    private static final int MAX_PLACEMENT_ATTEMPTS = 5;
    private static final int MAX_OPEN_ATTEMPTS = 3;

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
    private int stateTicks;
    private int placementAttempts;
    private int openAttempts;
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
        stateTicks = 0;
        placementAttempts = 0;
        openAttempts = 0;
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
        this.placementAttempts = 0;
        this.openAttempts = 0;
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
            case WAIT_FOR_PLACEMENT -> waitForPlacement();
            case OPEN_SHULKER -> openShulker();
            case WAIT_FOR_OPEN -> waitForOpen();
            case RESTOCK -> doRestock();
            case CLOSE_SHULKER -> closeShulker();
            case START_BREAK -> startBreak();
            case WAIT_FOR_BREAK -> waitForBreak();
            case WAIT_MANUAL_CLOSE -> {
                if (!(mc.currentScreen instanceof HandledScreen)) stage = Stage.CLOSE_SHULKER;
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

        // Placement is always top-face: we click Direction.UP on placePos.down()
        // (or, in air-place mode, simulate that same click via the offhand-swap
        // trick). There is no side-face placement path.
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

        // Sending the interaction packet is not confirmation the server placed
        // anything - WAIT_FOR_PLACEMENT polls the real world state for the
        // shulker before we ever try to open it.
        stateTicks = 0;
        stage = Stage.WAIT_FOR_PLACEMENT;
    }

    private void waitForPlacement() {
        if (mc.world.getBlockState(placedShulkerPos).getBlock() instanceof ShulkerBoxBlock) {
            stateTicks = 0;
            stage = Stage.OPEN_SHULKER;
            return;
        }

        stateTicks++;
        if (stateTicks < PLACEMENT_TIMEOUT_TICKS) return;

        // Timed out - the world never showed a shulker at this position.
        // Record it as failed so the next candidate search skips it, and try
        // again from scratch rather than waiting forever.
        failedPositions.add(placedShulkerPos);
        placementAttempts++;
        if (placementAttempts >= MAX_PLACEMENT_ATTEMPTS) {
            abort("Couldn't confirm shulker placement after " + MAX_PLACEMENT_ATTEMPTS + " attempts. Resetting.");
            return;
        }
        stateTicks = 0;
        stage = Stage.PLACE_SHULKER;
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

        double reach = (double) config.placeRange();
        if (mc.player.squaredDistanceTo(Vec3d.ofCenter(placedShulkerPos)) > reach * reach) {
            abort("Shulker is too far to open. Resetting.");
            return;
        }

        // WAIT_FOR_PLACEMENT already confirmed a shulker sits here, so we can
        // go straight to interacting with it.
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(placedShulkerPos), Direction.UP, placedShulkerPos, false);
        mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hit, 0));
        mc.player.swingHand(Hand.MAIN_HAND);

        stateTicks = 0;
        stage = Stage.WAIT_FOR_OPEN;
    }

    private void waitForOpen() {
        if (mc.currentScreen instanceof HandledScreen) {
            stateTicks = 0;
            openAttempts = 0;
            stage = config.autoTake() ? Stage.RESTOCK : Stage.WAIT_MANUAL_CLOSE;
            return;
        }

        if (!(mc.world.getBlockState(placedShulkerPos).getBlock() instanceof ShulkerBoxBlock)) {
            // The block itself vanished (broken, exploded, replaced by
            // someone/something else) while we were waiting on the GUI.
            abort("Shulker disappeared before it could be opened.");
            return;
        }

        stateTicks++;
        if (stateTicks < OPEN_TIMEOUT_TICKS) return;

        openAttempts++;
        if (openAttempts >= MAX_OPEN_ATTEMPTS) {
            abort("Couldn't open the shulker after " + MAX_OPEN_ATTEMPTS + " attempts. Resetting.");
            return;
        }
        stateTicks = 0;
        stage = Stage.OPEN_SHULKER;
    }

    private void doRestock() {
        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) {
            // GUI closed on its own (e.g. server-side kick from the container);
            // fall through to the close/break flow, which handles a
            // already-closed screen cleanly.
            stage = Stage.CLOSE_SHULKER;
            return;
        }

        if (countEmptyPlayerSlots() <= keepFree) {
            mc.player.closeHandledScreen();
            stage = Stage.CLOSE_SHULKER;
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
        stage = Stage.CLOSE_SHULKER;
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

    private void closeShulker() {
        if (mc.currentScreen instanceof HandledScreen) {
            mc.player.closeHandledScreen();
            delayTicks = 2; // brief client-side settle before we re-check currentScreen
            return;
        }
        if (!config.breakAfterFill()) {
            succeed();
            return;
        }
        stateTicks = 0;
        stage = Stage.START_BREAK;
    }

    private void startBreak() {
        if (mc.world.getBlockState(placedShulkerPos).isAir()) {
            // Already gone somehow (e.g. something else broke it) - nothing to do.
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
                mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(pickSlot));
                pickaxeEquipped = true;
            }
        }

        stateTicks = 0;
        stage = Stage.WAIT_FOR_BREAK;
    }

    private void waitForBreak() {
        if (mc.world.getBlockState(placedShulkerPos).isAir()) {
            succeed();
            return;
        }

        // Sending another breaking-progress tick is not confirmation of
        // anything either - we keep polling the block state above every tick,
        // and only give up once BREAK_TIMEOUT_TICKS is exceeded, instead of
        // looping on this indefinitely (e.g. because no pickaxe was found).
        mc.interactionManager.updateBlockBreakingProgress(placedShulkerPos, Direction.UP);
        mc.player.swingHand(Hand.MAIN_HAND);

        stateTicks++;
        if (stateTicks >= BREAK_TIMEOUT_TICKS) {
            abort("Couldn't break the shulker box at " + placedShulkerPos + " within the time limit.");
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
        // A break attempt may have been in progress (pickaxe already swapped
        // in) when the abort was triggered - restore it before the original
        // slot, same ordering succeed() uses.
        restorePickaxeSlot();
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
