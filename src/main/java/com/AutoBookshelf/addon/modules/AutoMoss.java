package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.BoneMealItem;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class AutoMoss extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgMoss = settings.createGroup("Moss");
    private final SettingGroup sgTrees = settings.createGroup("Trees");

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("The range to search for blocks to bonemeal")
        .defaultValue(4.5)
        .min(1)
        .sliderMax(6)
        .build()
    );

    private final Setting<Integer> mossSpreadCooldown = sgMoss.add(new IntSetting.Builder()
        .name("moss-cooldown")
        .description("Cooldown in ticks before bone mealing the same moss block again")
        .defaultValue(100)
        .min(20)
        .sliderMax(200)
        .build()
    );

    private final Setting<Boolean> makeTrees = sgTrees.add(new BoolSetting.Builder()
        .name("make-trees")
        .description("Use bone meal on azalea bushes and saplings to grow them into trees")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> inventoryAllow = sgGeneral.add(new BoolSetting.Builder()
        .name("inventory-allow")
        .description("Take bone meal from inventory when hotbar is empty")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay between bone meal uses in ticks")
        .defaultValue(2)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<Integer> maxUsesPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("max-uses-per-tick")
        .description("Maximum number of bone meal uses per tick")
        .defaultValue(1)
        .min(1)
        .sliderMax(5)
        .build()
    );

    private int delayTimer = 0;

    // Keep track of recently used moss blocks to implement cooldown
    private final Map<BlockPos, Integer> recentlyUsedMoss = new HashMap<>();

    public AutoMoss() {
        super(Addon.CATEGORY, "Auto-Moss", "Automatically uses bone meal on specific blocks");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (delayTimer > 0) {
            delayTimer--;
            return;
        }

        if (mc.player == null || mc.world == null) return;

        // Update moss cooldowns
        updateMossCooldowns();

        // Check if player has bone meal
        int boneMealSlot = findBoneMealSlot();
        if (boneMealSlot == -1) return;

        int uses = 0;
        List<BlockPos> targets = findTargets();

        for (BlockPos blockPos : targets) {
            if (uses >= maxUsesPerTick.get()) break;

            BlockState state = mc.world.getBlockState(blockPos);
            Block block = state.getBlock();
            boolean isMoss = block.getTranslationKey().contains("moss_block");

            // Skip moss blocks on cooldown
            if (isMoss && recentlyUsedMoss.containsKey(blockPos)) {
                continue;
            }

            if (BoneMealItem.useOnFertilizable(mc.player.getInventory().getStack(boneMealSlot), mc.world, blockPos)) {
                Vec3d hitPos = new Vec3d(blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5);
                BlockHitResult hit = new BlockHitResult(hitPos, Direction.UP, blockPos, false);

                int prevSelectedSlot = mc.player.getInventory().selectedSlot;
                mc.player.getInventory().selectedSlot = boneMealSlot;

                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);

                mc.player.getInventory().selectedSlot = prevSelectedSlot;

                // Add moss blocks to cooldown map
                if (isMoss) {
                    recentlyUsedMoss.put(new BlockPos(blockPos), mossSpreadCooldown.get());
                }

                uses++;
                delayTimer = delay.get();
            }
        }
    }

    private void updateMossCooldowns() {
        Iterator<Map.Entry<BlockPos, Integer>> it = recentlyUsedMoss.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = it.next();
            int cooldown = entry.getValue() - 1;

            if (cooldown <= 0) {
                it.remove();
            } else {
                entry.setValue(cooldown);
            }
        }
    }

    private List<BlockPos> findTargets() {
        List<BlockPos> targets = new ArrayList<>();

        if (mc.player == null || mc.world == null) return targets;

        double rangeSq = range.get() * range.get();
        BlockPos playerPos = mc.player.getBlockPos();

        for (int x = (int) -range.get(); x <= range.get(); x++) {
            for (int y = (int) -range.get(); y <= range.get(); y++) {
                for (int z = (int) -range.get(); z <= range.get(); z++) {
                    BlockPos pos = playerPos.add(x, y, z);

                    if (pos.getSquaredDistance(playerPos) > rangeSq) continue;

                    // Check if we have direct line of sight
                    if (!hasLineOfSight(pos)) continue;

                    BlockState state = mc.world.getBlockState(pos);
                    Block block = state.getBlock();
                    String blockName = block.getTranslationKey().toLowerCase();

                    // Check for tree growables if make-trees is enabled
                    if (makeTrees.get()) {
                        boolean isAzalea = blockName.contains("azalea") && !blockName.contains("tree");
                        boolean isSapling = blockName.contains("sapling");

                        if (isAzalea || isSapling) {
                            targets.add(pos);
                            continue;
                        }
                    }

                    // Check for moss blocks with valid neighbors
                    boolean isMoss = blockName.contains("moss_block");
                    if (isMoss && hasValidNeighbor(pos)) {
                        targets.add(pos);
                    }
                }
            }
        }

        return targets;
    }

    private boolean hasValidNeighbor(BlockPos pos) {
        // Check all six sides of the moss block to see if it has a valid neighbor
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = pos.offset(dir);
            BlockState neighborState = mc.world.getBlockState(neighborPos);
            Block neighborBlock = neighborState.getBlock();
            String blockName = neighborBlock.getTranslationKey().toLowerCase();

            // Skip if the neighbor is in our exclusion list
            if (blockName.contains("azalea") ||
                blockName.contains("tall_grass") ||
                blockName.contains("grass") && !blockName.contains("block") ||
                blockName.contains("moss_block") ||
                blockName.contains("moss_carpet")) {
                continue;
            }

            // If we found a neighbor that's not in our exclusion list, this moss block is valid
            return true;
        }

        // No valid neighbors found
        return false;
    }

    private boolean hasLineOfSight(BlockPos pos) {
        if (mc.player == null || mc.world == null) return false;

        Vec3d eyePos = mc.player.getEyePos();
        Vec3d blockPos = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);

        RaycastContext context = new RaycastContext(
            eyePos,
            blockPos,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            mc.player
        );

        BlockHitResult result = mc.world.raycast(context);
        return result.getBlockPos().equals(pos);
    }

    private int findBoneMealSlot() {
        if (mc.player == null) return -1;

        // First try to find in hotbar
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.BONE_MEAL) {
                return i;
            }
        }

        // If inventory-allow is enabled, check main inventory and move to hotbar if found
        if (inventoryAllow.get()) {
            for (int i = 9; i < 36; i++) {
                if (mc.player.getInventory().getStack(i).getItem() == Items.BONE_MEAL) {
                    // Find an empty slot in hotbar
                    int emptySlot = -1;
                    for (int j = 0; j < 9; j++) {
                        if (mc.player.getInventory().getStack(j).isEmpty()) {
                            emptySlot = j;
                            break;
                        }
                    }

                    // If we found an empty slot, move bone meal there
                    if (emptySlot != -1) {
                        // Use clickSlot to move from inventory to hotbar
                        mc.interactionManager.clickSlot(0, i, emptySlot, SlotActionType.SWAP, mc.player);
                        return emptySlot;
                    }
                    break;
                }
            }
        }

        return -1;
    }
}