package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public class MaterialsRefill extends Module {
    private enum Stage {
        IDLE, FIND_SHULKER, PLACE_SHULKER, OPEN_SHULKER, RESTOCK,
        CLOSE_AND_BREAK, WAIT_MANUAL_CLOSE
    }

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
        .description("how far is the placement range.")
        .defaultValue(2)
        .min(1)
        .sliderMax(5)
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

    private final Setting<Keybind> setTargetFromHeld = sgControls.add(new KeybindSetting.Builder()
        .name("set-target-from-held")
        .description("Set the held item as the target item for restocking.")
        .defaultValue(Keybind.none())
        .action(() -> {
            if (!isActive()) return;
            ItemStack held = mc.player.getMainHandStack();
            if (!held.isEmpty()) {
                targetItems.set(List.of(held.getItem()));
                info("Target item set to: " + held.getItem().getName().getString());
            }
        })
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
    private Vec3d placedHitVec;
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

        BlockPos placePos = findPlacement();
        if (placePos == null) {
            stage = Stage.IDLE;
            resetState();
            delayTicks = 20;
            return;
        }

        // Save placement data
        placedShulkerPos = placePos;
        placedFace = Direction.UP; // we don't need exact face for opening, just position
        placedHitVec = Vec3d.ofCenter(placePos);
        placedSupportBlock = placePos.down();

        // Perform the placement
        if (rotate.get()) {
            Rotations.rotate(
                Rotations.getYaw(placedHitVec),
                Rotations.getPitch(placedHitVec),
                -100,
                () -> {
                    BlockHitResult hit = new BlockHitResult(placedHitVec, Direction.UP, placedShulkerPos, false);
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                    mc.player.swingHand(Hand.MAIN_HAND);
                }
            );
        } else {
            BlockHitResult hit = new BlockHitResult(placedHitVec, Direction.UP, placedShulkerPos, false);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            mc.player.swingHand(Hand.MAIN_HAND);
        }

        delayTicks = 4;
        stage = Stage.OPEN_SHULKER;
    }

    /**
     * Hybrid placement logic: first try crosshair, then the radius search.
     */
    private BlockPos findPlacement() {
        // 1. Crosshair placement
        if (mc.crosshairTarget instanceof BlockHitResult blockHit) {
            BlockPos candidate = blockHit.getBlockPos().offset(blockHit.getSide());
            if (isValidPlacePosition(candidate) && mc.player.getEntityPos().squaredDistanceTo(Vec3d.ofCenter(candidate)) <= placeRange.get() * placeRange.get()) {
                return candidate;
            }
        }

        // 2. Fallback: Radius around player
        BlockPos playerPos = mc.player.getBlockPos();
        Direction facing = mc.player.getHorizontalFacing();

        // try facing direction first
        BlockPos testPos = playerPos.offset(facing);
        if (isValidPlacePosition(testPos) && inRange(testPos)) return testPos;

        // adjacent sides
        Direction[] adjacents = {facing.rotateYClockwise(), facing.rotateYCounterclockwise(), facing.getOpposite()};
        for (Direction dir : adjacents) {
            testPos = playerPos.offset(dir);
            if (isValidPlacePosition(testPos) && inRange(testPos)) return testPos;
        }

        // vertical
        for (int y = 1; y >= -1; y -= 2) {
            testPos = playerPos.add(0, y, 0);
            if (isValidPlacePosition(testPos) && inRange(testPos)) return testPos;
        }

        // diagonals up to 3 blocks
        for (int d = 1; d <= placeRange.get(); d++) {
            for (int x = -d; x <= d; x++) {
                for (int z = -d; z <= d; z++) {
                    // skip direct adjacents we already tested
                    if ((Math.abs(x) == 1 && z == 0) || (x == 0 && Math.abs(z) == 1) || (x == 0 && z == 0)) continue;
                    if (Math.abs(x) == d || Math.abs(z) == d) {
                        for (int y = -1; y <= 1; y++) {
                            testPos = playerPos.add(x, y, z);
                            if (isValidPlacePosition(testPos) && inRange(testPos)) return testPos;
                        }
                    }
                }
            }
        }

        return null;
    }

    private boolean isValidPlacePosition(BlockPos pos) {
        return mc.world.getBlockState(pos).isAir()
            && mc.world.getBlockState(pos.down()).isSolidBlock(mc.world, pos.down())
            && mc.player.getEntityPos().squaredDistanceTo(Vec3d.ofCenter(pos)) <= placeRange.get() * placeRange.get();
    }

    private boolean inRange(BlockPos pos) {
        return mc.player.getEntityPos().squaredDistanceTo(Vec3d.ofCenter(pos)) <= placeRange.get() * placeRange.get();
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

        if (!(mc.world.getBlockState(placedShulkerPos).getBlock() instanceof net.minecraft.block.ShulkerBoxBlock)) {
            delayTicks = 2;
            return;
        }

        double reach = mc.player.getEntityInteractionRange();
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

        // Block is broken. finish immediately
        if (mc.world.getBlockState(placedShulkerPos).isAir()) {
            restorePickaxeSlot();
            finish();
        } else {
            delayTicks = 2; // continue breaking
        }
    }

    private void finish() {
        restorePickaxeSlot();
        stage = Stage.IDLE;
        resetState();
        if (autoToggle.get()) toggle();
    }

    private boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof net.minecraft.item.BlockItem bi
            && bi.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock;
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
