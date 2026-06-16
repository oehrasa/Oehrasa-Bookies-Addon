package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public class PlatformBuilder extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPlacement = settings.createGroup("Placement");
    private final SettingGroup sgBlocks = settings.createGroup("Blocks");

    private final Setting<Integer> yLevel = sgGeneral.add(new IntSetting.Builder()
        .name("y-level")
        .description("Y-level to build the platform at.")
        .defaultValue(319)
        .min(-64)
        .max(319)
        .build()
    );

    private final Setting<Keybind> setYKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("set-y-key")
        .description("Press to set y-level to start the platform to you Y position.")
        .defaultValue(Keybind.fromKey(-1))
        .build());

    private final Setting<Integer> maxPlacementsPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("max-placements-per-tick")
        .description("Maximum number of blocks to place per tick.")
        .defaultValue(8)
        .min(1)
        .max(8)
        .build()
    );

    private final Setting<Integer> delayAfterPlacement = sgGeneral.add(new IntSetting.Builder()
        .name("delay-after-placement")
        .description("Delay in ticks after placing a block before placing another.")
        .defaultValue(10)
        .min(0)
        .max(20)
        .build()
    );

    private final Setting<Boolean> airPlace = sgPlacement.add(new BoolSetting.Builder()
        .name("air-place")
        .description("Allow placing blocks in mid-air.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> ignoreLiquids = sgPlacement.add(new BoolSetting.Builder()
        .name("ignore-liquids")
        .description("Allow placing blocks in water/lava.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> replaceBlocks = sgPlacement.add(new BoolSetting.Builder()
        .name("replace-grass")
        .description("Replace grass")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<Block>> allowedBlocks = sgBlocks.add(new BlockListSetting.Builder()
        .name("allowed-blocks")
        .description("Which blocks to use for building the platform.")
        .defaultValue(new ArrayList<>(List.of(Blocks.OBSIDIAN)))
        .build()
    );

    private final Setting<Boolean> refillFromInventory = sgBlocks.add(new BoolSetting.Builder()
        .name("refill-from-inventory")
        .description("Automatically refill hotbar from inventory when out of blocks.")
        .defaultValue(true)
        .build()
    );

    public PlatformBuilder() {
        super(Addon.CATEGORY2, "Platform", "Build a platform at a given y-level once in range");
    }

    @Override
    public void onActivate() {}
    @Override
    public void onDeactivate() {}

    int delay = 0;

    @EventHandler
    public void onTick(TickEvent.Post event) {
        if (delay > 0) {
            delay--;
            return;
        }
        if (mc.player == null || mc.level == null) return;

        if (setYKey.get().isPressed()) {
            yLevel.set(mc.player.getBlockY());
            info("Platform Y level is set to " + mc.player.getBlockY());
        }

        BlockPos[] blocks = reachablePositions();
        if (blocks.length == 0) {
            return;
        }

        if (!switchToBuildMat()) {
            return;
        }

        BlockPos playerPos = mc.player.blockPosition();
        java.util.Arrays.sort(blocks, (a, b) -> {
            double distA = a.distSqr(playerPos);
            double distB = b.distSqr(playerPos);
            return Double.compare(distA, distB);
        });

        int placed = 0;
        for (BlockPos pos : blocks) {
            if (placeBlock(pos)) {
                placed++;
                if (placed >= maxPlacementsPerTick.get()) {
                    break;
                }
            }
        }
        if (placed > 0) {
            delay = delayAfterPlacement.get();
        }
    }

    private BlockPos[] reachablePositions() {
        ArrayList<BlockPos> positions = new ArrayList<>();
        final int maxReach = 4;

        int targetY = yLevel.get();
        int playerY = (int) mc.player.getY();

        if (Math.abs(playerY - targetY) > maxReach) {
            return new BlockPos[0];
        }

        for (int x = -maxReach; x <= maxReach; x++) {
            for (int z = -maxReach; z <= maxReach; z++) {
                var pos = new BlockPos(mc.player.blockPosition().getX() + x, targetY, mc.player.blockPosition().getZ() + z);
                if (PlayerUtils.isWithinReach(pos)) {
                    positions.add(pos);
                }
            }
        }

        // Filter out non-placeable positions (no recentPlacements, we retry every tick)
        positions.removeIf(pos -> !canPlaceAtPosition(pos));

        return positions.toArray(new BlockPos[0]);
    }

    // Fixed: accepts BlockPos, uses correct position for isFullCube
    private boolean canPlaceAtPosition(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);

        if (state.isAir()) return true;

        // Liquids
        if (state.getFluidState().isSource() || state.getBlock() instanceof LiquidBlock) {
            return ignoreLiquids.get();
        }

        // Replaceable blocks
        if (state.canBeReplaced() || state.getBlock() == Blocks.GRASS_BLOCK || state.getBlock() == Blocks.FERN) {
            return replaceBlocks.get();
        }

        // Air‑place: allow any non‑solid block (use the real position, not ORIGIN)
        if (airPlace.get()) {
            return !state.isCollisionShapeFullBlock(mc.level, pos);
        }

        return false;
    }

    private boolean isAllowedBlock(Block block) {
        for (Block allowedBlock : allowedBlocks.get()) {
            if (allowedBlock == block) return true;
        }
        return false;
    }

    private boolean switchToBuildMat() {
        var current = mc.player.getMainHandItem().getItem();
        if (current instanceof net.minecraft.world.item.BlockItem && isAllowedBlock(((net.minecraft.world.item.BlockItem) current).getBlock())) {
            return true;
        }

        int bestSlot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof net.minecraft.world.item.BlockItem) {
                Block block = ((net.minecraft.world.item.BlockItem) stack.getItem()).getBlock();
                if (isAllowedBlock(block)) {
                    bestSlot = i;
                    break;
                }
            }
        }

        if (bestSlot == -1) {
            if (refillFromInventory.get()) {
                for (int i = 9; i < 36; i++) {
                    ItemStack stack = mc.player.getInventory().getItem(i);
                    if (!stack.isEmpty() && stack.getItem() instanceof net.minecraft.world.item.BlockItem) {
                        Block block = ((net.minecraft.world.item.BlockItem) stack.getItem()).getBlock();
                        if (isAllowedBlock(block)) {
                            int emptySlot = -1;
                            for (int j = 0; j < 9; j++) {
                                if (mc.player.getInventory().getItem(j).isEmpty()) {
                                    emptySlot = j;
                                    break;
                                }
                            }
                            if (emptySlot != -1) {
                                InvUtils.move().from(i).toHotbar(emptySlot);
                                bestSlot = emptySlot;
                                break;
                            }
                        }
                    }
                }
            }
        }

        if (bestSlot == -1) return false;

        InvUtils.swap(bestSlot, false);
        return true;
    }

    private boolean placeBlock(BlockPos pos) {
        if (!PlayerUtils.isWithinReach(pos)) return false;

        // Block state already checked in reachablePositions, no need to check again.

        FindItemResult item = InvUtils.findInHotbar(itemStack -> {
            if (!(itemStack.getItem() instanceof net.minecraft.world.item.BlockItem)) return false;
            Block block = ((net.minecraft.world.item.BlockItem) itemStack.getItem()).getBlock();
            return isAllowedBlock(block);
        });

        if (!item.found()) return false;

        // Use the reliable Meteor placement method (handles air‑place, rotation, etc.)
        return BlockUtils.place(pos, item, true, 50, true, true);
    }
}
