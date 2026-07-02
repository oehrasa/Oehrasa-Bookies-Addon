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
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
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
        .description("Press to set y-level to your current Y position.")
        .defaultValue(Keybind.fromKey(-1))
        .build()
    );

    private final Setting<Integer> maxPlacementsPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("max-placements-per-tick")
        .description("Maximum number of blocks to place per tick.")
        .defaultValue(4)
        .min(1)
        .max(8)
        .build()
    );

    private final Setting<Integer> delayAfterPlacement = sgGeneral.add(new IntSetting.Builder()
        .name("delay-after-placement")
        .description("Delay in ticks after placing a block before placing another.")
        .defaultValue(2)
        .min(0)
        .max(20)
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
        .description("Replace grass and other replaceable blocks.")
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
        .description("Automatically move blocks from inventory to hotbar when the hotbar runs out.")
        .defaultValue(true)
        .build()
    );

    /**
     * Positions we have successfully sent a place packet for.
     * Verified each tick — removed if the world still shows air/replaceable,
     * so server rejections are automatically retried.
     */
    private final HashSet<BlockPos> pendingPlacements = new HashSet<>();

    private int delay = 0;

    public PlatformBuilder() {
        super(Addon.CATEGORY2, "Platform", "Build a platform at a given y-level once in range.");
    }

    @Override
    public void onActivate() {
        pendingPlacements.clear();
        delay = 0;
    }

    @Override
    public void onDeactivate() {
        pendingPlacements.clear();
    }

    @EventHandler
    public void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        if (setYKey.get().isPressed()) {
            yLevel.set(mc.player.getBlockY());
            info("Platform Y level set to " + mc.player.getBlockY());
        }

        // Re-verify pending placements: if the server rejected a packet the block
        // won't be there, so remove it from the set so it can be retried next cycle.
        pendingPlacements.removeIf(pos -> needsBlock(mc.world.getBlockState(pos)));

        if (delay > 0) {
            delay--;
            return;
        }

        BlockPos[] targets = reachableTargets();
        if (targets.length == 0) return;

        if (!ensureBuildMatInHand()) return;

        // Sort closest-first so we fill from the player outward
        BlockPos origin = mc.player.getBlockPos();
        java.util.Arrays.sort(targets,
            (a, b) -> Double.compare(a.getSquaredDistance(origin), b.getSquaredDistance(origin)));

        int placed = 0;
        for (BlockPos pos : targets) {
            if (placed >= maxPlacementsPerTick.get()) break;
            if (tryPlace(pos)) placed++;
        }

        if (placed > 0) {
            delay = delayAfterPlacement.get();
        }
    }

    private boolean tryPlace(BlockPos pos) {
        if (!PlayerUtils.isWithinReach(pos)) return false;
        if (pendingPlacements.contains(pos)) return false;

        BlockState state = mc.world.getBlockState(pos);
        if (!needsBlock(state)) return false;

        FindItemResult item = InvUtils.findInHotbar(stack -> isAllowedStack(stack));
        if (!item.found()) return false;

        boolean ok = BlockUtils.place(pos, item, true, 50, true, true);
        if (ok) pendingPlacements.add(pos); // only track confirmed send
        return ok;
    }

    private boolean needsBlock(BlockState state) {
        if (state.isAir()) return true;

        if (state.getFluidState().isStill() || state.getBlock() instanceof FluidBlock) {
            return ignoreLiquids.get();
        }

        if (state.isReplaceable()
            || state.getBlock() == Blocks.GRASS_BLOCK
            || state.getBlock() == Blocks.FERN) {
            return replaceBlocks.get();
        }

        return false;
    }

    private BlockPos[] reachableTargets() {
        final int maxReach = 4;
        int targetY = yLevel.get();

        if (Math.abs((int) mc.player.getY() - targetY) > maxReach) {
            return new BlockPos[0];
        }

        List<BlockPos> positions = new ArrayList<>();
        int cx = mc.player.getBlockPos().getX();
        int cz = mc.player.getBlockPos().getZ();

        for (int x = -maxReach; x <= maxReach; x++) {
            for (int z = -maxReach; z <= maxReach; z++) {
                BlockPos pos = new BlockPos(cx + x, targetY, cz + z);
                if (!PlayerUtils.isWithinReach(pos)) continue;
                if (pendingPlacements.contains(pos)) continue;
                if (!needsBlock(mc.world.getBlockState(pos))) continue;
                positions.add(pos);
            }
        }

        return positions.toArray(new BlockPos[0]);
    }

    private boolean isAllowedStack(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem bi)) return false;
        return isAllowedBlock(bi.getBlock());
    }

    private boolean isAllowedBlock(Block block) {
        return allowedBlocks.get().contains(block);
    }

    /**
     * Makes sure an allowed block is in the main hand.
     * Tries hotbar first, then moves from inventory if refillFromInventory is on.
     * Returns false if no material is available at all.
     */
    private boolean ensureBuildMatInHand() {
        // Already holding an allowed block nothing to do its joever
        ItemStack held = mc.player.getMainHandStack();
        if (isAllowedStack(held)) return true;

        // Scan hotbar
        for (int i = 0; i < 9; i++) {
            if (isAllowedStack(mc.player.getInventory().getStack(i))) {
                InvUtils.swap(i, false);
                return true;
            }
        }

        // Try pulling from inventory into an empty (or least-valuable) hotbar slot
        if (refillFromInventory.get()) {
            int emptySlot = -1;
            for (int j = 0; j < 9; j++) {
                if (mc.player.getInventory().getStack(j).isEmpty()) {
                    emptySlot = j;
                    break;
                }
            }
            if (emptySlot != -1) {
                for (int i = 9; i < 36; i++) {
                    if (isAllowedStack(mc.player.getInventory().getStack(i))) {
                        InvUtils.move().from(i).toHotbar(emptySlot);
                        InvUtils.swap(emptySlot, false);
                        return true;
                    }
                }
            }
        }

        return false;
    }
}
