package com.AutoBookshelf.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import com.AutoBookshelf.addon.Addon;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class PlatformBuilder extends Module {
    
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgBlocks = settings.createGroup("Blocks");

    private final Setting<Integer> yLevel = sgGeneral.add(new IntSetting.Builder()
        .name("y-level")
        .description("Y-level to build the platform at.")
        .defaultValue(319)
        .min(-64)
        .max(319)
        .build()
    );

    private final Setting<Integer> maxPlacementsPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("max-placements-per-tick")
        .description("Maximum number of blocks to place per tick. Set to 0 for unlimited.")
        .defaultValue(8)
        .min(0)
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

    // Block selection settings
    private final Setting<List<Block>> allowedBlocks = sgBlocks.add(new BlockListSetting.Builder()
        .name("allowed-blocks")
        .description("Which blocks to use for building the platform.")
        .defaultValue(new ArrayList<>(List.of(Blocks.OBSIDIAN)))
        .build()
    );

    private final Setting<Boolean> useInventoryOrder = sgBlocks.add(new BoolSetting.Builder()
        .name("use-inventory-order")
        .description("Use blocks in the order they appear in your inventory.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> refillFromInventory = sgBlocks.add(new BoolSetting.Builder()
        .name("refill-from-inventory")
        .description("Automatically refill hotbar from inventory when out of blocks.")
        .defaultValue(true)
        .build()
    );

    private HashSet<BlockPos> recentPlacements = new HashSet<>();

    public PlatformBuilder() {
        super(Addon.CATEGORY, "platform-builder", "Build a platform at a given y-level once in range");
    }

    @Override
    public void onActivate() {
        recentPlacements.clear();
    }

    @Override
    public void onDeactivate() {
        recentPlacements.clear();
    }

    int delay = 0;
    
    @EventHandler
    public void onTick(TickEvent.Post event) {
        if (delay > 0) {
            delay--;
            return;
        }
        if (mc.player == null || mc.world == null) return;

        BlockPos[] blocks = reachablePositions();
        if (blocks.length == 0) {
            return;
        }

        if (!switchToBuildMat()) {
            return;
        }

        BlockPos playerPos = mc.player.getBlockPos();
        java.util.Arrays.sort(blocks, (a, b) -> {
            double distA = a.getSquaredDistance(playerPos);
            double distB = b.getSquaredDistance(playerPos);
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
                var pos = new BlockPos(mc.player.getBlockPos().getX() + x, targetY, mc.player.getBlockPos().getZ() + z);
                if (PlayerUtils.isWithinReach(pos)) {
                    positions.add(pos);
                }
            }
        }

        positions.removeIf(pos -> {
            var state = mc.world.getBlockState(pos);
            return !state.isAir() || recentPlacements.contains(pos);
        });

        return positions.toArray(new BlockPos[0]);
    }

    private boolean isAllowedBlock(Item item) {
        if (item == null) return false;
        for (Block block : allowedBlocks.get()) {
            if (block.asItem() == item) return true;
        }
        return false;
    }

    private boolean isAllowedBlock(Block block) {
        return isAllowedBlock(block.asItem());
    }

    private boolean switchToBuildMat() {
        var current = mc.player.getMainHandStack().getItem();
        if (isAllowedBlock(current)) return true;

        // Find the best block in inventory based on allowed blocks
        int bestSlot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && isAllowedBlock(stack.getItem())) {
                bestSlot = i;
                break;
            }
        }

        if (bestSlot == -1) {
            // Try to refill from inventory if enabled
            if (refillFromInventory.get()) {
                for (int i = 9; i < 36; i++) {
                    ItemStack stack = mc.player.getInventory().getStack(i);
                    if (!stack.isEmpty() && isAllowedBlock(stack.getItem())) {
                        // Find empty hotbar slot
                        int emptySlot = -1;
                        for (int j = 0; j < 9; j++) {
                            if (mc.player.getInventory().getStack(j).isEmpty()) {
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

        if (bestSlot == -1) return false;

        InvUtils.swap(bestSlot, false);
        return true;
    }

    private boolean placeBlock(BlockPos pos) {
        if (!PlayerUtils.isWithinReach(pos)) return false;
        if (recentPlacements.contains(pos)) return false;
        
        // Check if block is already placed
        if (!mc.world.getBlockState(pos).isAir()) return false;
        
        recentPlacements.add(pos);

        Direction dir = Direction.UP;
        BlockHitResult blockHitResult = new BlockHitResult(
            pos.toCenterPos(),
            dir,
            pos,
            false
        );

        // Place the block from main hand
        mc.player.networkHandler.sendPacket(
            new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, blockHitResult, 0)
        );
        
        mc.player.swingHand(Hand.MAIN_HAND);

        return true;
    }
}