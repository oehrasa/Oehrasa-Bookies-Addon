package com.AutoBookshelf.addon.utils;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
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
import java.util.function.Consumer;

public class ShulkerRestockEngine {

    public enum Stage {
        IDLE, FIND_SHULKER,
        PLACE_SHULKER, WAIT_FOR_PLACEMENT,
        OPEN_SHULKER, WAIT_FOR_OPEN,
        RESTOCK,
        CLOSE_SHULKER, START_BREAK, WAIT_FOR_BREAK,
        WAIT_MANUAL_CLOSE,
        // Optional post-break step: walk to the shulker box item the break just dropped.
        COLLECT_DROPPED_SHULKER
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
        List<BlockPos> excludedPositions,
        int maxPlacementAttempts,
        int maxOpenAttempts,
        int maxStacksPerRestock,
        // Leave a container alone if it holds more than one distinct item type (AutoLoader semantics).
        boolean onlySingleItem,
        // Use the packet-based instant break (AutoLoader) instead of vanilla hold-to-dig.
        boolean packetBreak,
        // Use the Grim-v3 packet breaking sequence.
        boolean packetBreakGrim,
        // After breaking the shulker, pathfind to the dropped box item and pick it up.
        boolean collectDroppedShulker,
        // Let the collector use Baritone for the walk when it is available.
        boolean useBaritone
    ) {
    }

    public interface RestockCallback {
        void onInfo(String message);

        void onFinished(boolean success);
    }

    // Bounded wait times for each confirmation state, in client ticks. These
    // exist because sending a packet is not confirmation that the server
    // acted on it every WAIT_FOR_* state polls the actual world/UI state
    // each tick instead of just assuming success after a fixed delay.
    private static final int PLACEMENT_TIMEOUT_TICKS = 20;
    private static final int OPEN_TIMEOUT_TICKS = 20;
    private static final int BREAK_TIMEOUT_TICKS = 200;

    private final Minecraft mc;
    private final PlacementEngine placementEngine;
    private final RestockCallback callback;
    private final DropCollector dropCollector;

    private Stage stage = Stage.IDLE;
    private Item currentTargetItem;
    private RestockConfig config;
    private int shulkerSlot = -1;

    private int originalSelectedSlot = -1;

    /**
     * The hotbar index the shulker was swapped INTO and the inventory index it came FROM, or -1
     * when no swap happened. The displaced stack is put back by {@link #restoreSwappedSlot()}.
     */
    private int swappedHotbarSlot = -1;
    private int swappedSourceSlot = -1;

    private BlockPos placedShulkerPos;
    private int delayTicks;
    private int stateTicks;
    private int placementAttempts;
    private int openAttempts;
    private int keepFree;
    private boolean pickaxeEquipped;
    private boolean packetBreakSent;
    private int preBreakSlot = -1;
    private String lastFailItem = "";
    private String lastFailReason = "";
    private boolean shulkerFullyEmptied = false;

    /**
     * Value of {@link #shulkerFullyEmptied} captured just before {@link #reset()} clears it, so the
     * {@link RestockCallback#onFinished(boolean)} handler can still tell a fully-emptied box from
     * one broken with leftovers (the callback runs after the reset).
     */
    private boolean lastShulkerFullyEmptied = false;

    /**
     * Positions where placement was attempted but never materialized, so
     * PlacementEngine can skip them on retry within the same attempt.
     */
    private final List<BlockPos> failedPositions = new ArrayList<>();

    /**
     * Reused scratch list combining failed placement positions with the config's
     * excluded positions; avoid+alloc is per placement attempt, this just keeps
     * the ArrayList allocation from being repeated.
     */
    private final List<BlockPos> avoidScratch = new ArrayList<>();

    public ShulkerRestockEngine(Minecraft mc, PlacementEngine placementEngine, RestockCallback callback) {
        this.mc = mc;
        this.placementEngine = placementEngine;
        this.callback = callback;
        this.dropCollector = new DropCollector(mc, callback::onInfo);
    }

    public boolean isActive() {
        return stage != Stage.IDLE;
    }

    public void reset() {
        restoreSwappedSlot();
        stage = Stage.IDLE;
        currentTargetItem = null;
        config = null;
        shulkerSlot = -1;
        originalSelectedSlot = -1;
        swappedHotbarSlot = -1;
        swappedSourceSlot = -1;
        placedShulkerPos = null;
        delayTicks = 0;
        stateTicks = 0;
        placementAttempts = 0;
        openAttempts = 0;
        keepFree = 0;
        pickaxeEquipped = false;
        packetBreakSent = false;
        preBreakSlot = -1;
        shulkerFullyEmptied = false;
        failedPositions.clear();
        dropCollector.stop();
    }

    /**
     * True if, on the most recently completed attempt, every stack of the target
     * item was taken out of the shulker before it was broken. False means the
     * box was broken (or left placed) while still holding leftover contents
     */
    public boolean wasShulkerFullyEmptied() {
        return lastShulkerFullyEmptied;
    }

    public void restoreOriginalSlotIfNeeded() {
        restoreSwappedSlot();
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
        this.packetBreakSent = false;
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
        if (mc.player == null || mc.level == null) return isActive();
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
                if (!(mc.gui.screen() instanceof AbstractContainerScreen)) stage = Stage.CLOSE_SHULKER;
            }
            case COLLECT_DROPPED_SHULKER -> {
                if (!dropCollector.tick(config.useBaritone())) succeed();
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
            if (config.onlySingleItem()) {
                Item first = null;
                boolean mixed = false;
                for (ItemStackTemplate c : container.nonEmptyItems()) {
                    Item it = c.item().value();
                    if (first == null) first = it;
                    else if (first != it) {
                        mixed = true;
                        break;
                    }
                }
                if (mixed) continue;
            }
            for (ItemStackTemplate content : container.nonEmptyItems()) {
                if (content.item().value() == currentTargetItem) {
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
        if (mc.gui.screen() instanceof AbstractContainerScreen) {
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
            // Single atomic swap
            mc.gameMode.handleContainerInput(
                mc.player.containerMenu.containerId,
                shulkerSlot,
                targetSlot,
                ContainerInput.SWAP,
                mc.player
            );
            swappedHotbarSlot = targetSlot;
            swappedSourceSlot = shulkerSlot;
            shulkerSlot = targetSlot;
            delayTicks = 2;
            return;
        }

        ItemStack handStack = mc.player.getInventory().getItem(shulkerSlot);
        if (!isShulkerBox(handStack)) {
            abort("Shulker item missing from hotbar slot, aborting placement.");
            return;
        }

        selectSlot(shulkerSlot);

        List<BlockPos> avoid = avoidScratch;
        avoid.clear();
        avoid.addAll(failedPositions);
        avoid.addAll(config.excludedPositions());

        BlockPos placePos = placementEngine.findPlacement(
            config.placeRange(), config.airPlace(), config.preferSolidBlock(), avoid);
        if (placePos == null) {
            abort(null);
            return;
        }

        placedShulkerPos = placePos;
        Vec3 hitVec = Vec3.atCenterOf(placePos);

        // Placement is always top-face: we click Direction.UP on placePos.below()
        // (or, in air-place mode, simulate that same click via the offhand-swap
        // trick). There is no side-face placement path.
        //
        // The box is selected only for the placement packet itself. Opening, restocking and
        // breaking all address the block, not the held item, so the caller's slot is handed back
        // as soon as the packet is on the wire - the server resumes holding build material instead
        // of a shulker. In the rotate branches the packet is deferred to the movement phase, so the
        // hand-back happens inside the callback, after it.
        if (config.airPlace()) {
            BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, placePos, false);
            int revision = mc.player.containerMenu.getStateId();

            if (config.rotate()) {
                Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec), -100, () -> {
                    mc.getConnection().send(new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
                    mc.getConnection().send(new ServerboundUseItemOnPacket(InteractionHand.OFF_HAND, hit, revision));
                    mc.getConnection().send(new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    restoreSlotAfterPlacement();
                });
            } else {
                mc.getConnection().send(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
                mc.getConnection().send(new ServerboundUseItemOnPacket(InteractionHand.OFF_HAND, hit, revision));
                mc.getConnection().send(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
                mc.player.swing(InteractionHand.MAIN_HAND);
                restoreSlotAfterPlacement();
            }
        } else {
            Vec3 supportHit = Vec3.atLowerCornerOf(placePos).add(0.5, 0.0, 0.5);
            BlockHitResult hit = new BlockHitResult(supportHit, Direction.UP, placePos.below(), false);

            if (config.rotate()) {
                Rotations.rotate(Rotations.getYaw(supportHit), Rotations.getPitch(supportHit), -100, () -> {
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    restoreSlotAfterPlacement();
                });
            } else {
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
                mc.player.swing(InteractionHand.MAIN_HAND);
                restoreSlotAfterPlacement();
            }
        }

        // Sending the interaction packet is not confirmation the server placed
        // anything
        stateTicks = 0;
        stage = Stage.WAIT_FOR_PLACEMENT;
    }

    private void waitForPlacement() {
        if (mc.level.getBlockState(placedShulkerPos).getBlock() instanceof ShulkerBoxBlock) {
            stateTicks = 0;
            stage = Stage.OPEN_SHULKER;
            return;
        }

        stateTicks++;
        if (stateTicks < PLACEMENT_TIMEOUT_TICKS) return;

        // Timeout, the world never showed a shulker at this position.
        // Record it as failed so the next candidate search skips it, and try
        // again from scratch rather than waiting forever.
        failedPositions.add(placedShulkerPos);
        placementAttempts++;
        if (placementAttempts >= config.maxPlacementAttempts()) {
            abort("Couldn't confirm shulker placement after " + config.maxPlacementAttempts() + " attempts. Resetting.");
            return;
        }
        stateTicks = 0;
        stage = Stage.PLACE_SHULKER;
    }

    private int resolveHotbarSlot() {
        int preferred = config.shulkerHotbarSlot() - 1; // convert 1-9 display to 0-8 index

        // Nothing is displaced when an empty slot takes the box, so try that first.
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) return i;
        }

        if (!isProtected(mc.player.getInventory().getItem(preferred))) {
            return preferred;
        }

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
        return config.protectedItems().contains(stack.getItem());
    }

    private void openShulker() {
        if (placedShulkerPos == null) {
            abort(null);
            return;
        }

        double reach = (double) config.placeRange();
        if (mc.player.distanceToSqr(Vec3.atCenterOf(placedShulkerPos)) > reach * reach) {
            abort("Shulker is too far to open. Resetting.");
            return;
        }

        // WAIT_FOR_PLACEMENT already confirmed a shulker sits here, so we can
        // go straight to interacting with it.
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(placedShulkerPos), Direction.UP, placedShulkerPos, false);
        mc.getConnection().send(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, 0));
        mc.player.swing(InteractionHand.MAIN_HAND);

        stateTicks = 0;
        stage = Stage.WAIT_FOR_OPEN;
    }

    private void waitForOpen() {
        if (mc.gui.screen() instanceof AbstractContainerScreen) {
            stateTicks = 0;
            openAttempts = 0;
            stage = config.autoTake() ? Stage.RESTOCK : Stage.WAIT_MANUAL_CLOSE;
            return;
        }

        if (!(mc.level.getBlockState(placedShulkerPos).getBlock() instanceof ShulkerBoxBlock)) {
            // The block itself vanished (broken, exploded, replaced by
            // someone/something else) while we were waiting on the GUI.
            abort("Shulker disappeared before it could be opened.");
            return;
        }

        stateTicks++;
        if (stateTicks < OPEN_TIMEOUT_TICKS) return;

        openAttempts++;
        if (openAttempts >= config.maxOpenAttempts()) {
            abort("Couldn't open the shulker after " + config.maxOpenAttempts() + " attempts. Resetting.");
            return;
        }
        stateTicks = 0;
        stage = Stage.OPEN_SHULKER;
    }

    private void doRestock() {
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
            // GUI closed on its own (e.g. server-side kick from the container);
            // fall through to the close/break flow, which handles a
            // already-closed screen cleanly.
            stage = Stage.CLOSE_SHULKER;
            return;
        }

        if (countEmptyPlayerSlots() <= keepFree) {
            mc.player.closeContainer();
            stage = Stage.CLOSE_SHULKER;
            return;
        }

        var handler = screen.getMenu();
        if (config.onlySingleItem() && hasMixedItems(handler)) {
            // AutoLoader semantics: a mixed container is left for manual take, but fall through
            // to the close/break path so breakAfterFill still runs.
            mc.player.closeContainer();
            stage = Stage.CLOSE_SHULKER;
            return;
        }
        // Single pass over the 27 box slots: quick-move every matching stack while
        // remembering if any slot kept a partial stack after its own move, instead of
        // re-walking all 27 slots a second time to detect leftovers.
        boolean targetStillLeft = false;
        int stacksTaken = 0;
        int stackLimit = Math.max(1, config.maxStacksPerRestock());
        int i = 0;
        // Which loop exit we took. They leave `i` pointing at different slots: the full-inventory
        // break leaves i ON the slot just moved (already inspected below), while the stackLimit
        // exit leaves i on the next UNVISITED slot. The leftover scan has to start accordingly or
        // it skips a slot and can wrongly conclude the shulker is empty.
        boolean stoppedOnFullInventory = false;
        while (i < 27 && stacksTaken < stackLimit) {
            ItemStack stack = handler.getSlot(i).getItem();
            if (stack.getItem() != currentTargetItem) {
                i++;
                continue;
            }

            mc.gameMode.handleContainerInput(handler.containerId, i, 0, ContainerInput.QUICK_MOVE, mc.player);
            stacksTaken++;
            // A quick-move can leave a partial stack behind in this slot.
            if (handler.getSlot(i).getItem().getItem() == currentTargetItem) targetStillLeft = true;

            // Re-check the real inventory rather than assuming this quick-move
            // consumed an empty slot.
            if (countEmptyPlayerSlots() <= keepFree) {
                stoppedOnFullInventory = true;
                break;
            }
            i++;
        }

        // We may have stopped before reaching slot 27 (hit the stack cap, or ran out of
        // inventory room). The slots after the last one we looked at were never
        // touched, so any of them still holding the target means we stopped short
        // even though the last move drained its own slot.
        if (!targetStillLeft && i < 27) {
            int from = stoppedOnFullInventory ? i + 1 : i;
            for (int j = from; j < 27; j++) {
                if (handler.getSlot(j).getItem().getItem() == currentTargetItem) {
                    targetStillLeft = true;
                    break;
                }
            }
        }

        // If any target-item slot in the shulker still holds a stack we stopped short
        // of fully emptying it (either genuinely out of inventory room, or - previously -
        // due to the miscounted early exit). Record that so the caller can tell a
        // "fully emptied" success apart from a "still has contents" one and decide
        // whether to bother chasing down the dropped box after it's broken.
        shulkerFullyEmptied = !targetStillLeft;

        mc.player.closeContainer();
        stage = Stage.CLOSE_SHULKER;
    }

    /**
     * Shared pickaxe-finder used by the engine and by AutoLoader (de-duplicated primitive).
     */
    public static int findPickaxeSlot(Minecraft mc) {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(net.minecraft.tags.ItemTags.PICKAXES)) return i;
        }
        return -1;
    }

    /**
     * Shared empty-player-slot counter used by AutoLoader and the engine's auto-take (de-dup primitive).
     */
    public static int countEmptyPlayerSlots(Minecraft mc) {
        if (mc.player == null) return 0;
        int empty = 0;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) empty++;
        }
        return empty;
    }

    /**
     * Shared reserve rule: keep 1 empty slot whenever a post-restock break is configured.
     */
    public static int keepFreeSlots(boolean breakAfterUse) {
        return breakAfterUse ? 1 : 0;
    }

    private int countEmptyPlayerSlots() {
        return countEmptyPlayerSlots(mc);
    }

    private void restorePickaxeSlot() {
        if (pickaxeEquipped && preBreakSlot >= 0 && preBreakSlot <= 8) {
            selectSlot(preBreakSlot);
            pickaxeEquipped = false;
            preBreakSlot = -1;
        }
    }

    /**
     * Switches the selected hotbar slot on the client and tells the server about it.
     *
     * <p>{@code Inventory#setSelectedSlot} is a plain setter (javap-verified on 26.2); it does not
     * send {@code ServerboundSetCarriedItemPacket}, and the server decides which item a use-on-block
     * packet places from <em>its own</em> carried slot. Changing the client's selection without the
     * packet left the server holding the shulker box for the rest of the restock, so the next
     * platform placement resolved against a box and the server placed it as build material.
     */
    private void selectSlot(int slot) {
        if (slot < 0 || slot > 8) return;
        if (mc.player == null || mc.getConnection() == null) return;
        mc.player.getInventory().setSelectedSlot(slot);
        mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
    }

    /**
     * Hands the caller's slot back once the shulker placement packet is on the wire, without
     * forgetting it: a timed-out attempt re-selects the box, and the end of the attempt still
     * needs to restore. Unlike {@link #restoreOriginalSlot()} this never clears the saved slot.
     */
    private void restoreSlotAfterPlacement() {
        if (originalSelectedSlot >= 0 && originalSelectedSlot <= 8) selectSlot(originalSelectedSlot);
    }

    /**
     * Restores whatever the player had selected before start() ran (their platform
     * material, most likely). Runs after restorePickaxeSlot()
     */
    private void restoreOriginalSlot() {
        if (originalSelectedSlot >= 0 && originalSelectedSlot <= 8) {
            selectSlot(originalSelectedSlot);
            originalSelectedSlot = -1;
        }
    }

    /**
     * Puts the shulker stack back where it came from and returns whatever was displaced when it
     * was pulled into the hotbar.
     *
     * <p>Only the selection ever used to be restored, so a stack that lived in the target hotbar
     * slot - a build material, a tool - stayed in the inventory for good. Same fix AutoPot needs
     * for its potion swap.
     */
    private void restoreSwappedSlot() {
        if (swappedHotbarSlot < 0 || swappedSourceSlot < 0) return;
        if (mc.player == null || mc.player.containerMenu != mc.player.inventoryMenu) return;

        // The player has the whole restock to rearrange their inventory, and blindly swapping
        // would relocate whatever they put in the slot instead. Only move it back while the slot
        // still holds the box we put there, or nothing once that box has been placed.
        ItemStack inHotbar = mc.player.getInventory().getItem(swappedHotbarSlot);
        if (!inHotbar.isEmpty() && !isShulkerBox(inHotbar)) return;

        mc.gameMode.handleContainerInput(
            mc.player.containerMenu.containerId,
            swappedSourceSlot,
            swappedHotbarSlot,
            ContainerInput.SWAP,
            mc.player
        );
        swappedHotbarSlot = -1;
        swappedSourceSlot = -1;
    }

    private void closeShulker() {
        if (mc.gui.screen() instanceof AbstractContainerScreen) {
            mc.player.closeContainer();
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
        if (mc.level.getBlockState(placedShulkerPos).isAir()) {
            // Already gone somehow (something else broke it) nothing to do.
            finishBreak();
            return;
        }

        if (!pickaxeEquipped) {
            int pickSlot = findPickaxeSlot(mc);
            if (pickSlot != -1) {
                preBreakSlot = mc.player.getInventory().getSelectedSlot();
                selectSlot(pickSlot);
                pickaxeEquipped = true;
            }
        }

        if (config.packetBreak()) {
            if (packetBreakSent) return;
            if (config.rotate()) {
                // Same ordering as the placement path: the look is applied in the movement phase,
                // so the break burst has to be sent from inside the callback to follow it.
                Vec3 c = Vec3.atCenterOf(placedShulkerPos);
                Rotations.rotate(Rotations.getYaw(c), Rotations.getPitch(c), -100, () -> {
                    sendBreakPackets(placedShulkerPos, Direction.UP);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                });
            } else {
                sendBreakPackets(placedShulkerPos, Direction.UP);
                mc.player.swing(InteractionHand.MAIN_HAND);
            }
            packetBreakSent = true;
            stateTicks = 0;
            delayTicks = 4;
            stage = Stage.WAIT_FOR_BREAK;
            return;
        }

        stateTicks = 0;
        stage = Stage.WAIT_FOR_BREAK;
    }

    private void waitForBreak() {
        if (mc.level.getBlockState(placedShulkerPos).isAir()) {
            restoreOriginalSlot();
            pickaxeEquipped = false;
            packetBreakSent = false;
            finishBreak();
            return;
        }

        if (config.packetBreak()) {
            stateTicks++;
            if (stateTicks >= BREAK_TIMEOUT_TICKS) {
                abort("Couldn't break the shulker box at " + placedShulkerPos + " within the time limit.");
            }
            return;
        }

        mc.gameMode.continueDestroyBlock(placedShulkerPos, Direction.UP);
        mc.player.swing(InteractionHand.MAIN_HAND);

        stateTicks++;
        if (stateTicks >= BREAK_TIMEOUT_TICKS) {
            abort("Couldn't break the shulker box at " + placedShulkerPos + " within the time limit.");
        }
    }

    /**
     * True if the stack is any shulker box item. Shared by the engine and callers' stock checks.
     */
    public static boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem bi
            && bi.getBlock() instanceof ShulkerBoxBlock;
    }

    /**
     * True if the shulker box stack's stored contents hold the given item.
     */
    public static boolean shulkerContains(ItemStack stack, Item item) {
        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        if (container == null) return false;
        for (ItemStackTemplate content : container.nonEmptyItems()) {
            if (content.item().value() == item) return true;
        }
        return false;
    }

    private boolean shouldNotify(String itemName, String reason) {
        if (itemName.equals(lastFailItem) && reason.equals(lastFailReason)) return false;
        lastFailItem = itemName;
        lastFailReason = reason;
        return true;
    }

    private void sendBreakPackets(BlockPos pos, Direction dir) {
        if (config.packetBreakGrim()) {
            mc.getConnection().send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, dir));
            mc.getConnection().send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, dir));
            mc.getConnection().send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, pos, dir));
        } else {
            mc.getConnection().send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, dir));
            mc.getConnection().send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, pos, dir));
        }
        mc.getConnection().send(new ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, dir));
    }

    /**
     * True if the container's 27 slots hold more than one distinct item type. Used by only-single-item.
     */
    public static boolean hasMixedItems(net.minecraft.world.inventory.AbstractContainerMenu handler) {
        net.minecraft.world.item.Item found = null;
        for (int i = 0; i < 27; i++) {
            net.minecraft.world.item.ItemStack stack = handler.getSlot(i).getItem();
            if (stack.isEmpty()) continue;
            if (found == null) found = stack.getItem();
            else if (found != stack.getItem()) return true;
        }
        return false;
    }

    /**
     * Ends a successful break. When collection is enabled the engine keeps running and walks to the
     * box the break just dropped before it reports success; otherwise it succeeds immediately.
     */
    private void finishBreak() {
        if (config.collectDroppedShulker()) {
            dropCollector.start(placedShulkerPos);
            stage = Stage.COLLECT_DROPPED_SHULKER;
            return;
        }
        succeed();
    }

    private void abort(String message) {
        if (message != null) callback.onInfo(message);
        // A break attempt may have been in progress (pickaxe already swapped
        // in) when the abort was triggered, restore it before the original
        // slot, same ordering succeed() uses.
        lastShulkerFullyEmptied = shulkerFullyEmptied;
        restorePickaxeSlot();
        restoreOriginalSlot();
        reset();
        callback.onFinished(false);
    }

    private void succeed() {
        lastShulkerFullyEmptied = shulkerFullyEmptied;
        restorePickaxeSlot();
        restoreOriginalSlot();
        reset();
        callback.onFinished(true);
    }

    /**
     * Walks to a shulker box item the break just dropped and picks it up.
     *
     * <p>Shared by the engine's {@link Stage#COLLECT_DROPPED_SHULKER} step and by modules that run
     * their own break flow (AutoLoader). Success is an inventory delta - the box actually appearing
     * in the player's inventory - never "the entity disappeared", which is equally true when the
     * item despawns or is left behind.
     */
    public static final class DropCollector {
        private static final int WALK_TIMEOUT_TICKS = 100;
        private static final int ACQUIRE_GRACE_TICKS = 20;
        private static final double SEARCH_RADIUS_SQ = 400.0;
        /**
         * Squared radius around the broken block within which a dropped box is considered ours.
         */
        private static final double ORIGIN_RADIUS_SQ = 16.0;

        private final Minecraft mc;
        private final Consumer<String> info;

        private ItemEntity target;
        private int walkTimeout;
        private int acquireTicks;
        private int boxesAtStart;
        private int collected;
        private BlockPos lastGoal;
        private BlockPos origin;
        private IBaritone baritone;
        private boolean baritoneUnavailable;
        private boolean ownsBaritoneGoal;

        /**
         * True once {@link #walkToward()} has pressed the movement keys, so {@link #stop()} only
         * releases keys this collector owns. On the Baritone path the keys are never touched, and
         * releasing them there would fight a player physically holding them.
         */
        private boolean keysPressed;

        public DropCollector(Minecraft mc, Consumer<String> info) {
            this.mc = mc;
            this.info = info;
        }

        /**
         * Arm the collector: baseline the inventory, reset the acquire/walk budgets, and record
         * where the box was broken so the search can ignore unrelated drops elsewhere.
         */
        public void start(BlockPos origin) {
            this.origin = origin;
            target = null;
            walkTimeout = WALK_TIMEOUT_TICKS;
            acquireTicks = ACQUIRE_GRACE_TICKS;
            boxesAtStart = countShulkerBoxes();
            collected = 0;
            lastGoal = null;
            ownsBaritoneGoal = false;
        }

        /**
         * Stop walking and release the movement keys and any Baritone path.
         */
        public void stop() {
            target = null;
            walkTimeout = 0;
            acquireTicks = 0;
            lastGoal = null;
            origin = null;
            if (ownsBaritoneGoal && baritone != null) {
                baritone.getPathingBehavior().cancelEverything();
                ownsBaritoneGoal = false;
            }
            if (keysPressed) {
                mc.options.keyUp.setDown(false);
                mc.options.keyJump.setDown(false);
                keysPressed = false;
            }
        }

        /**
         * Drive one tick. Returns true while still collecting; false once finished (picked up,
         * timed out, or nothing worth collecting).
         */
        public boolean tick(boolean useBaritone) {
            if (mc.player == null || mc.level == null) {
                stop();
                return false;
            }

            // Success is an inventory delta, checked before targeting so a pickup is credited the
            // tick it happens. Re-baseline on each hit so a box the player already held is not
            // counted as one we collected.
            int boxes = countShulkerBoxes();
            if (boxes > boxesAtStart) {
                boxesAtStart = boxes;
                collected++;
                info.accept("§aCollected a dropped shulker (§7" + collected + "§a).");
                target = null;
                lastGoal = null;
                if (ownsBaritoneGoal && baritone != null) {
                    baritone.getPathingBehavior().cancelEverything();
                    ownsBaritoneGoal = false;
                }
            }

            // Acquire a target, with a grace window so the drop's spawn latency does not abort us.
            if (target == null || !target.isAlive()) {
                target = findDroppedShulker();
                if (target == null) {
                    if (acquireTicks > 0) {
                        acquireTicks--;
                        return true;
                    }
                    // Nothing within reach to pick up (the drop may have despawned or been
                    // destroyed); say so once rather than silently resuming.
                    if (collected == 0) info.accept("§7No dropped shulker to collect.");
                    stop();
                    return false;
                }
                acquireTicks = 0;
                walkTimeout = WALK_TIMEOUT_TICKS;
                lastGoal = null;
            }

            if (walkTimeout <= 0) {
                info.accept("§eTimed out reaching the dropped shulker.");
                stop();
                return false;
            }
            walkTimeout--;

            BlockPos itemPos = target.blockPosition();
            if (useBaritone && resolveBaritone()) {
                if (!itemPos.equals(lastGoal) || !baritone.getPathingBehavior().isPathing()) {
                    lastGoal = itemPos;
                    baritone.getCustomGoalProcess().setGoalAndPath(
                        new GoalBlock(itemPos.getX(), itemPos.getY(), itemPos.getZ()));
                    ownsBaritoneGoal = true;
                }
            } else {
                walkToward();
            }
            return true;
        }

        private boolean resolveBaritone() {
            if (baritone != null) return true;
            if (baritoneUnavailable) return false;
            try {
                baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
                return baritone != null;
            } catch (Throwable t) {
                // Baritone absent or not initialised; fall back to the manual walk.
                baritoneUnavailable = true;
                return false;
            }
        }

        /**
         * Manual fallback for when Baritone is off: face the item, pitch at it so the pickup ray
         * lines up, and jump when it sits above head height.
         */
        private void walkToward() {
            Vec3 p = target.position();
            double dx = p.x - mc.player.getX();
            double dy = p.y - mc.player.getY();
            double dz = p.z - mc.player.getZ();
            double horiz = DistanceUtil.distanceXZ(mc.player.getX(), mc.player.getZ(), p.x, p.z);

            mc.player.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90));
            if (horiz > 1.0E-4) {
                mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, horiz)));
            }
            mc.options.keyUp.setDown(true);
            // Jump only when it is genuinely above us and close enough that a jump can reach it;
            // holding jump while far away just stutters the walk.
            mc.options.keyJump.setDown(dy > 0.8 && horiz < 2.0);
            keysPressed = true;
        }

        /**
         * Shulker boxes currently held, counting stack sizes across the main inventory and offhand.
         */
        private int countShulkerBoxes() {
            if (mc.player == null) return 0;
            int count = 0;
            for (int i = 0; i < 36; i++) {
                ItemStack stack = mc.player.getInventory().getItem(i);
                if (isShulkerBox(stack)) count += stack.getCount();
            }
            ItemStack offhand = mc.player.getOffhandItem();
            if (isShulkerBox(offhand)) count += offhand.getCount();
            return count;
        }

        /**
         * Nearest dropped shulker box item within reach of the break, or null.
         *
         * <p>When {@link #start} supplied an origin, only drops within a small radius of it are
         * eligible: the break happened there, so a nearer unrelated box elsewhere must not be
         * walked to instead. Within that area the nearest to the player still wins.
         */
        private ItemEntity findDroppedShulker() {
            Vec3 originCenter = origin == null ? null : Vec3.atCenterOf(origin);
            ItemEntity closest = null;
            double minDist = Double.MAX_VALUE;
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (!(entity instanceof ItemEntity item) || !item.isAlive()) continue;
                if (!isShulkerBox(item.getItem())) continue;
                if (originCenter != null && entity.position().distanceToSqr(originCenter) > ORIGIN_RADIUS_SQ) continue;
                double dist = mc.player.distanceToSqr(entity);
                if (dist > SEARCH_RADIUS_SQ) continue;
                if (dist < minDist) {
                    minDist = dist;
                    closest = item;
                }
            }
            return closest;
        }
    }
}
