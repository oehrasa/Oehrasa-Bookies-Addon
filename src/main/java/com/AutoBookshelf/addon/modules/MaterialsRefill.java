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
        .defaultValue(List.of())
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
            ItemStack held = mc.player.getMainHandItem();
            if (!held.isEmpty()) {
                targetItems.set(List.of(held.getItem()));
                info("Target item set to: " + held.getHoverName().getString());
            }
        })
        .build()
    );

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

            // 26.1.2: nonEmptyItems() returns List<ItemStackTemplate>
            for (ItemStackTemplate template : container.nonEmptyItems()) {
                if (template.item().value() == currentTargetItem) {
                    shulkerSlot = i;
                    break;
                }
            }
            if (shulkerSlot != -1) break;
        }

        if (shulkerSlot == -1) {
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

        // Move to hotbar if not already there
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

        placedShulkerPos = placePos;
        placedFace = Direction.UP;
        placedHitVec = Vec3.atCenterOf(placePos);
        placedSupportBlock = placePos.below();

        if (rotate.get()) {
            Rotations.rotate(
                Rotations.getYaw(placedHitVec),
                Rotations.getPitch(placedHitVec),
                -100,
                () -> {
                    BlockHitResult hit = new BlockHitResult(placedHitVec, Direction.UP, placedShulkerPos, false);
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                }
            );
        } else {
            BlockHitResult hit = new BlockHitResult(placedHitVec, Direction.UP, placedShulkerPos, false);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
            mc.player.swing(InteractionHand.MAIN_HAND);
        }

        delayTicks = 4;
        stage = Stage.OPEN_SHULKER;
    }

    private BlockPos findPlacement() {
        // Crosshair placement
        if (mc.hitResult instanceof BlockHitResult blockHit) {
            BlockPos candidate = blockHit.getBlockPos().relative(blockHit.getDirection());
            if (isValidPlacePosition(candidate) && inRange(candidate)) {
                return candidate;
            }
        }

        // Fallback radius search
        BlockPos playerPos = mc.player.blockPosition();
        Direction facing = mc.player.getDirection();

        BlockPos testPos = playerPos.relative(facing);
        if (isValidPlacePosition(testPos) && inRange(testPos)) return testPos;

        Direction[] adjacents = {facing.getClockWise(), facing.getCounterClockWise(), facing.getOpposite()};
        for (Direction dir : adjacents) {
            testPos = playerPos.relative(dir);
            if (isValidPlacePosition(testPos) && inRange(testPos)) return testPos;
        }

        for (int y = 1; y >= -1; y -= 2) {
            testPos = playerPos.offset(0, y, 0);
            if (isValidPlacePosition(testPos) && inRange(testPos)) return testPos;
        }

        for (int d = 1; d <= placeRange.get(); d++) {
            for (int x = -d; x <= d; x++) {
                for (int z = -d; z <= d; z++) {
                    if ((Math.abs(x) == 1 && z == 0) || (x == 0 && Math.abs(z) == 1) || (x == 0 && z == 0)) continue;
                    if (Math.abs(x) == d || Math.abs(z) == d) {
                        for (int y = -1; y <= 1; y++) {
                            testPos = playerPos.offset(x, y, z);
                            if (isValidPlacePosition(testPos) && inRange(testPos)) return testPos;
                        }
                    }
                }
            }
        }

        return null;
    }

    private boolean isValidPlacePosition(BlockPos pos) {
        return mc.level.getBlockState(pos).isAir()
            && mc.level.getBlockState(pos.below()).isRedstoneConductor(mc.level, pos.below())
            && inRange(pos);
    }

    private boolean inRange(BlockPos pos) {
        return mc.player.position().distanceToSqr(Vec3.atCenterOf(pos)) <= placeRange.get() * placeRange.get();
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

        double reach = mc.player.entityInteractionRange();
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
                // 26.1.2 quick move uses handleContainerInput
                mc.gameMode.handleContainerInput(handler.containerId, i, 0, ContainerInput.QUICK_MOVE, mc.player);
                emptySlots--;
                if (emptySlots <= keepFree) break;
            }
        }

        mc.player.closeContainer();
        delayTicks = 2;
        stage = Stage.CLOSE_AND_BREAK;
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

    private void restorePickaxeSlot() {
        if (pickaxeEquipped && preBreakSlot >= 0 && preBreakSlot <= 8) {
            mc.player.getInventory().setSelectedSlot(preBreakSlot);
            pickaxeEquipped = false;
            preBreakSlot = -1;
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
