// SPDX-License-Identifier: GPL-3.0-only
/*
 * This code partially adapted from Meteor Rejects
 * Original source: https://github.com/AntiCope/meteor-rejects/
 * Credit: Meteor Rejects contributors
 * Heavily modified with additional features by Nora Tweaks
 */
package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.entity.player.BreakBlockEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Pool;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockIterator;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class AutoFarm extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTill = settings.createGroup("Till");
    private final SettingGroup sgHarvest = settings.createGroup("Harvest Crops");
    private final SettingGroup sgTallCrops = settings.createGroup("Harvest Tall Crops");
    private final SettingGroup sgPlant = settings.createGroup("Plant");
    private final SettingGroup sgBamboo = settings.createGroup("Plant Bamboo");
    private final SettingGroup sgBonemeal = settings.createGroup("Bonemeal");

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("Auto farm range.")
        .defaultValue(4)
        .min(1)
        .sliderMax(6)
        .build()
    );

    private final Setting<Integer> bpt = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("Amount of operations that can be applied in one tick.")
        .min(1)
        .defaultValue(1)
        .sliderMax(10)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Whether or not to rotate towards block.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swingHand = sgGeneral.add(new BoolSetting.Builder()
        .name("swing-hand")
        .description("Swing hand when performing actions (helps with anti-cheat).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> till = sgTill.add(new BoolSetting.Builder()
        .name("till")
        .description("Turn nearby dirt into farmland.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> moist = sgTill.add(new BoolSetting.Builder()
        .name("moist")
        .description("Only till blocks near water.")
        .defaultValue(true)
        .visible(till::get)
        .build()
    );

    private final Setting<Boolean> harvest = sgHarvest.add(new BoolSetting.Builder()
        .name("harvest")
        .description("Harvest mature crops.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<Block>> harvestBlocks = sgHarvest.add(new BlockListSetting.Builder()
        .name("harvest-blocks")
        .description("Which crops to harvest.")
        .defaultValue()
        .filter(this::harvestFilter)
        .visible(harvest::get)
        .build()
    );

    private final Setting<Boolean> harvestTallCrops = sgTallCrops.add(new BoolSetting.Builder()
        .name("harvest-tall-crops")
        .description("Harvest sugar cane, bamboo, kelp (breaks upper blocks, leaves bottom).")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<Block>> tallCropBlocks = sgTallCrops.add(new BlockListSetting.Builder()
        .name("harvest-tall-blocks")
        .description("Which tall crops to harvest.")
        .defaultValue(Blocks.SUGAR_CANE, Blocks.BAMBOO, Blocks.KELP_PLANT)
        .filter(this::tallCropFilter)
        .visible(harvestTallCrops::get)
        .build()
    );

    private final Setting<Integer> tallCropMinHeight = sgTallCrops.add(new IntSetting.Builder()
        .name("min-height")
        .description("Minimum total height before harvesting. With min height 2, breaks when at least 2 blocks tall (keeps bottom 1).")
        .defaultValue(2)
        .min(2)
        .sliderRange(2, 10)
        .visible(harvestTallCrops::get)
        .build()
    );

    private final Setting<Boolean> tallCropSwingHand = sgTallCrops.add(new BoolSetting.Builder()
        .name("swing-hand")
        .description("Swing hand when breaking tall crops.")
        .defaultValue(true)
        .visible(harvestTallCrops::get)
        .build()
    );

    private final Setting<Boolean> plant = sgPlant.add(new BoolSetting.Builder()
        .name("plant")
        .description("Plant crops.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<Item>> plantItems = sgPlant.add(new ItemListSetting.Builder()
        .name("plant-items")
        .description("Which crops to plant.")
        .defaultValue()
        .filter(this::plantFilter)
        .visible(plant::get)
        .build()
    );

    private final Setting<Boolean> onlyReplant = sgPlant.add(new BoolSetting.Builder()
        .name("only-replant")
        .description("Only replant where crops were previously harvested.")
        .defaultValue(true)
        .visible(plant::get)
        .onChanged(b -> clearReplantMap())
        .build()
    );

    private final Setting<Boolean> plantBamboo = sgBamboo.add(new BoolSetting.Builder()
        .name("plant-bamboo")
        .description("Plant bamboo saplings in a square around the player.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> bambooAreaSize = sgBamboo.add(new IntSetting.Builder()
        .name("bamboo-area-size")
        .description("Width and length of the square planting area.")
        .defaultValue(5)
        .range(1, 15)
        .sliderRange(1, 15)
        .visible(plantBamboo::get)
        .build()
    );

    private final Setting<Integer> bambooPlaceDelay = sgBamboo.add(new IntSetting.Builder()
        .name("bamboo-place-delay")
        .description("Ticks between successful bamboo placements.")
        .defaultValue(2)
        .range(0, 20)
        .sliderRange(0, 20)
        .visible(plantBamboo::get)
        .build()
    );

    private final Setting<Boolean> bambooRotate = sgBamboo.add(new BoolSetting.Builder()
        .name("bamboo-rotate")
        .description("Rotate server-side toward each placement.")
        .defaultValue(true)
        .visible(plantBamboo::get)
        .build()
    );

    private final Setting<Boolean> bambooSwing = sgBamboo.add(new BoolSetting.Builder()
        .name("bamboo-swing")
        .description("Swing the hand when placing bamboo.")
        .defaultValue(true)
        .visible(plantBamboo::get)
        .build()
    );

    private final Setting<Boolean> bonemeal = sgBonemeal.add(new BoolSetting.Builder()
        .name("bonemeal")
        .description("Bonemeal crops.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<Block>> bonemealBlocks = sgBonemeal.add(new BlockListSetting.Builder()
        .name("bonemeal-blocks")
        .description("Which crops to bonemeal.")
        .defaultValue()
        .filter(this::bonemealFilter)
        .visible(bonemeal::get)
        .build()
    );

    private final Map<BlockPos, Item> replantMap = new HashMap<>();
    private final Pool<BlockPos.MutableBlockPos> blockPosPool = new Pool<>(BlockPos.MutableBlockPos::new);
    private final List<BlockPos.MutableBlockPos> blocks = new ArrayList<>();
    private int actions = 0;
    private int tickCounter = 0;
    private int bambooCooldown = 0;
    private static final int REPLANT_CLEANUP_INTERVAL = 100;
    private static final double REPLANT_MAX_DISTANCE = 32.0;

    public AutoFarm() {
        super(Addon.CATEGORY, "Auto-Farm", "Welcome to the rice fields, Motha fucka.");
    }

    @Override
    public void onActivate() {
        bambooCooldown = 0;
    }

    @Override
    public void onDeactivate() {
        clearReplantMap();
        freeBlockPool();
    }

    @EventHandler
    private void onBreakBlock(BreakBlockEvent event) {
        if (!onlyReplant.get()) return;

        BlockState state = mc.level.getBlockState(event.blockPos);
        Block block = state.getBlock();
        Item seedItem = getCropSeed(block);

        if (seedItem != null) {
            replantMap.put(event.blockPos.immutable(), seedItem);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        actions = 0;
        tickCounter++;

        if (bambooCooldown > 0) bambooCooldown--;

        if (tickCounter >= REPLANT_CLEANUP_INTERVAL) {
            cleanupReplantMap();
            tickCounter = 0;
        }

        collectBlocks();

        BlockIterator.after(() -> {
            sortBlocksByDistance();
            processBlocks();
            freeBlockPool();
            plantBamboo();
        });
    }

    private void collectBlocks() {
        // Squared distances order identically and skip the Vec3 allocation pair per voxel;
        // the callback runs once per voxel per tick.
        Vec3 eye = mc.player.getEyePosition();
        double rangeSq = range.get() * range.get();
        BlockIterator.register(range.get(), range.get(), (pos, state) -> {
            double dx = pos.getX() + 0.5 - eye.x;
            double dy = pos.getY() + 0.5 - eye.y;
            double dz = pos.getZ() + 0.5 - eye.z;
            if (dx * dx + dy * dy + dz * dz <= rangeSq) {
                blocks.add(blockPosPool.get().set(pos));
            }
        });
    }

    private void sortBlocksByDistance() {
        // The old comparator called getPlayerDistance() inside the comparator, which
        // allocated two Vec3 objects per comparison (~n·log n per tick). Squared
        // Euclidean distance orders identically and needs no allocation at all.
        Vec3 eye = mc.player.getEyePosition();
        blocks.sort(Comparator.comparingDouble(pos -> {
            double dx = pos.getX() + 0.5 - eye.x;
            double dy = pos.getY() + 0.5 - eye.y;
            double dz = pos.getZ() + 0.5 - eye.z;
            return dx * dx + dy * dy + dz * dz;
        }));
    }

    private void processBlocks() {
        for (BlockPos pos : blocks) {
            if (actions >= bpt.get()) break;

            BlockState state = mc.level.getBlockState(pos);
            Block block = state.getBlock();

            if (tryTill(pos, block)) continue;
            if (tryHarvest(pos, state, block)) continue;
            if (tryHarvestTallCrop(pos, block)) continue;
            if (tryPlant(pos, block)) continue;
            tryBonemeal(pos, state, block);
        }
    }

    private void freeBlockPool() {
        for (BlockPos.MutableBlockPos blockPos : blocks) {
            blockPosPool.free(blockPos);
        }
        blocks.clear();
    }

    private boolean tryTill(BlockPos pos, Block block) {
        if (!till.get()) return false;
        if (!isTillable(block)) return false;
        if (!mc.level.getBlockState(pos.above()).isAir()) return false;
        if (moist.get() && !isWaterNearby(mc.level, pos)) return false;

        FindItemResult hoe = InvUtils.findInHotbar(stack -> stack.getItem() instanceof HoeItem);
        if (!hoe.found()) return false;

        performInteraction(pos, hoe);
        actions++;
        return true;
    }

    private boolean isTillable(Block block) {
        return block == Blocks.GRASS_BLOCK ||
            block == Blocks.DIRT_PATH ||
            block == Blocks.DIRT ||
            block == Blocks.COARSE_DIRT ||
            block == Blocks.ROOTED_DIRT;
    }

    private boolean tryHarvest(BlockPos pos, BlockState state, Block block) {
        if (!harvest.get()) return false;
        if (!harvestBlocks.get().contains(block)) return false;
        if (!isMature(state, block)) return false;

        if (block instanceof SweetBerryBushBlock) {
            if (rotate.get()) {
                Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), -100, () -> {
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
                    if (swingHand.get()) mc.player.swing(InteractionHand.MAIN_HAND);
                });
            } else {
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
                if (swingHand.get()) mc.player.swing(InteractionHand.MAIN_HAND);
            }
        } else {
            if (rotate.get()) {
                Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), -100, () -> {
                    mc.gameMode.continueDestroyBlock(pos, Direction.UP);
                    if (swingHand.get()) mc.player.swing(InteractionHand.MAIN_HAND);
                });
            } else {
                mc.gameMode.continueDestroyBlock(pos, Direction.UP);
                if (swingHand.get()) mc.player.swing(InteractionHand.MAIN_HAND);
            }
        }
        actions++;
        return true;
    }

    private boolean tryHarvestTallCrop(BlockPos pos, Block block) {
        if (!harvestTallCrops.get()) return false;
        if (!tallCropBlocks.get().contains(block) && !isTallCrop(block)) return false;

        int totalHeight = getTotalTallCropHeight(pos, block);
        if (totalHeight < tallCropMinHeight.get()) return false;

        Block blockBelow = mc.level.getBlockState(pos.below()).getBlock();
        if (!isSameTallCrop(block, blockBelow)) return false;

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), -100, () -> {
                mc.gameMode.continueDestroyBlock(pos, Direction.UP);
                if (tallCropSwingHand.get()) mc.player.swing(InteractionHand.MAIN_HAND);
            });
        } else {
            mc.gameMode.continueDestroyBlock(pos, Direction.UP);
            if (tallCropSwingHand.get()) mc.player.swing(InteractionHand.MAIN_HAND);
        }
        actions++;
        return true;
    }

    private boolean isTallCrop(Block block) {
        return block == Blocks.SUGAR_CANE ||
            block == Blocks.BAMBOO ||
            block == Blocks.KELP_PLANT ||
            block == Blocks.KELP;
    }

    private boolean tallCropFilter(Block block) {
        return block == Blocks.SUGAR_CANE ||
            block == Blocks.BAMBOO ||
            block == Blocks.KELP_PLANT ||
            block == Blocks.KELP ||
            block == Blocks.CACTUS;
    }

    private boolean isSameTallCrop(Block a, Block b) {
        if ((a == Blocks.KELP || a == Blocks.KELP_PLANT) &&
            (b == Blocks.KELP || b == Blocks.KELP_PLANT)) {
            return true;
        }
        return a == b;
    }

    private int getTotalTallCropHeight(BlockPos pos, Block block) {
        int below = 0;
        BlockPos checkPos = pos.below();
        while (below < 16) {
            Block b = mc.level.getBlockState(checkPos).getBlock();
            if (!isSameTallCrop(block, b)) break;
            below++;
            checkPos = checkPos.below();
        }

        int above = 1;
        checkPos = pos.above();
        while (above < 16) {
            Block b = mc.level.getBlockState(checkPos).getBlock();
            if (!isSameTallCrop(block, b)) break;
            above++;
            checkPos = checkPos.above();
        }

        return below + above;
    }

    private boolean tryPlant(BlockPos pos, Block block) {
        if (!plant.get()) return false;
        if (!mc.level.isEmptyBlock(pos.above())) return false;
        if (!(block instanceof FarmlandBlock) && !(block instanceof SoulSandBlock)) return false;

        FindItemResult findItemResult = null;

        if (onlyReplant.get()) {
            BlockPos cropPos = pos.above();
            if (replantMap.containsKey(cropPos)) {
                findItemResult = InvUtils.findInHotbar(replantMap.get(cropPos));
                if (findItemResult.found()) {
                    replantMap.remove(cropPos);
                }
            }
        } else {
            findItemResult = findPlantableItem(block);
        }

        if (findItemResult != null && findItemResult.found()) {
            performInteraction(pos.above(), findItemResult);
            actions++;
            return true;
        }

        return false;
    }

    private FindItemResult findPlantableItem(Block soilBlock) {
        if (soilBlock instanceof FarmlandBlock) {
            return InvUtils.findInHotbar(stack -> {
                Item item = stack.getItem();
                return item != Items.NETHER_WART &&
                    item != Items.PITCHER_POD &&
                    plantItems.get().contains(item);
            });
        }

        if (soilBlock instanceof SoulSandBlock) {
            return InvUtils.findInHotbar(stack -> {
                Item item = stack.getItem();
                return item == Items.NETHER_WART && plantItems.get().contains(Items.NETHER_WART);
            });
        }

        return null;
    }

    private boolean tryBonemeal(BlockPos pos, BlockState state, Block block) {
        if (!bonemeal.get()) return false;
        if (!bonemealBlocks.get().contains(block)) return false;
        if (isMature(state, block)) return false;

        FindItemResult bonemealItem = InvUtils.findInHotbar(Items.BONE_MEAL);
        if (!bonemealItem.found()) return false;

        performInteraction(pos, bonemealItem);
        actions++;
        return true;
    }

    private void plantBamboo() {
        if (!plantBamboo.get()) return;
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (bambooCooldown > 0) return;
        if (actions >= bpt.get()) return;

        FindItemResult bamboo = InvUtils.findInHotbar(Items.BAMBOO);
        if (!bamboo.found()) return;

        for (BlockPos target : bambooTargetsNearestFirst()) {
            if (!canPlantBamboo(target)) continue;
            if (BlockUtils.place(target, bamboo, bambooRotate.get(), 10, bambooSwing.get(), true)) {
                actions++;
                bambooCooldown = bambooPlaceDelay.get();
                return;
            }
        }
    }

    private List<BlockPos> bambooTargetsNearestFirst() {
        int size = bambooAreaSize.get();
        int start = -(size / 2);
        int end = start + size;
        int plantingY = mc.player.blockPosition().getY();
        BlockPos center = mc.player.blockPosition();
        List<BlockPos> targets = new ArrayList<>(size * size);

        for (int x = start; x < end; x++) {
            for (int z = start; z < end; z++) {
                targets.add(new BlockPos(center.getX() + x, plantingY, center.getZ() + z));
            }
        }

        // The area square can extend past the module's range; only nearest-first targets
        // inside the established interaction range are reachable.
        targets.removeIf(pos -> !isWithinRange(pos));
        targets.sort(Comparator.comparingDouble(pos -> pos.distToCenterSqr(mc.player.position())));
        return targets;
    }

    private boolean canPlantBamboo(BlockPos target) {
        if (!mc.level.getBlockState(target).isAir()) return false;
        BlockState below = mc.level.getBlockState(target.below());
        if (below.is(Blocks.BAMBOO) || below.is(Blocks.BAMBOO_SAPLING)) return false;
        return Blocks.BAMBOO_SAPLING.defaultBlockState().canSurvive(mc.level, target)
            && BlockUtils.canPlace(target);
    }

    private void performInteraction(BlockPos pos, FindItemResult item) {
        Runnable action = () -> {
            boolean wasSneaking = mc.player.isShiftKeyDown();
            mc.player.setShiftKeyDown(false);
            InvUtils.swap(item.slot(), true);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
            if (swingHand.get()) mc.player.swing(InteractionHand.MAIN_HAND);
            InvUtils.swapBack();
            mc.player.setShiftKeyDown(wasSneaking);
        };

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), -100, action);
        } else {
            action.run();
        }
    }

    private boolean isWithinRange(BlockPos pos) {
        // Squared distances compare identically and skip the Vec3 allocation pair.
        Vec3 eye = mc.player.getEyePosition();
        double dx = pos.getX() + 0.5 - eye.x;
        double dy = pos.getY() + 0.5 - eye.y;
        double dz = pos.getZ() + 0.5 - eye.z;
        double range = this.range.get();
        return dx * dx + dy * dy + dz * dz <= range * range;
    }

    private boolean isWaterNearby(LevelReader world, BlockPos pos) {
        for (BlockPos blockPos : BlockPos.betweenClosed(pos.offset(-4, 0, -4), pos.offset(4, 1, 4))) {
            if (world.getFluidState(blockPos).is(FluidTags.WATER)) return true;
        }
        return false;
    }

    private void clearReplantMap() {
        replantMap.clear();
    }

    private void cleanupReplantMap() {
        if (mc.player == null || replantMap.isEmpty()) return;

        //? if >=1.21.9 {
        Vec3 playerPos = mc.player.position();
        //?} else
        /*Vec3d playerPos = mc.player.getPos();
         */

        replantMap.entrySet().removeIf(entry ->
            playerPos.distanceTo(Vec3.atCenterOf(entry.getKey())) > REPLANT_MAX_DISTANCE
        );
    }

    private boolean isMature(BlockState state, Block block) {
        if (state.is(BlockTags.CROPS)) {
            if (block instanceof CropBlock cropBlock) {
                return cropBlock.isMaxAge(state);
            }
        }

        if (block instanceof CropBlock cropBlock) {
            return cropBlock.isMaxAge(state);
        } else if (block instanceof CocoaBlock) {
            return state.getValue(CocoaBlock.AGE) >= 2;
        } else if (block instanceof StemBlock) {
            return state.getValue(StemBlock.AGE) == StemBlock.MAX_AGE;
        } else if (block instanceof SweetBerryBushBlock) {
            return state.getValue(SweetBerryBushBlock.AGE) >= 2;
        } else if (block instanceof NetherWartBlock) {
            return state.getValue(NetherWartBlock.AGE) >= 3;
        } else if (block instanceof PitcherCropBlock) {
            return state.getValue(PitcherCropBlock.AGE) >= 4;
        } else if (block == Blocks.PUMPKIN || block == Blocks.MELON) {
            // Pumpkins and melons are the fruit, not the stem: they have no age of their own,
            // so they were never considered mature and were never broken. Treat the fruit itself
            // as always harvestable so continueDestroyBlock actually runs on it.
            return true;
        }

        return false;
    }

    private Item getCropSeed(Block block) {
        if (block == Blocks.WHEAT) return Items.WHEAT_SEEDS;
        if (block == Blocks.CARROTS) return Items.CARROT;
        if (block == Blocks.POTATOES) return Items.POTATO;
        if (block == Blocks.BEETROOTS) return Items.BEETROOT_SEEDS;
        if (block == Blocks.NETHER_WART) return Items.NETHER_WART;
        if (block == Blocks.PITCHER_CROP) return Items.PITCHER_POD;
        if (block == Blocks.TORCHFLOWER) return Items.TORCHFLOWER_SEEDS;
        if (block == Blocks.TORCHFLOWER_CROP) return Items.TORCHFLOWER_SEEDS;
        return null;
    }

    private boolean bonemealFilter(Block block) {
        if (block instanceof CropBlock) return true;
        if (block instanceof StemBlock) return true;
        if (block instanceof SaplingBlock) return true;
        if (block instanceof MushroomBlock) return true;
        if (block instanceof AzaleaBlock) return true;

        return block == Blocks.COCOA ||
            block == Blocks.SWEET_BERRY_BUSH ||
            block == Blocks.PITCHER_CROP ||
            block == Blocks.TORCHFLOWER ||
            block == Blocks.TORCHFLOWER_CROP;
    }

    private boolean harvestFilter(Block block) {
        if (block instanceof CropBlock) return true;

        return block == Blocks.PUMPKIN ||
            block == Blocks.MELON ||
            block == Blocks.NETHER_WART ||
            block == Blocks.SWEET_BERRY_BUSH ||
            block == Blocks.COCOA ||
            block == Blocks.PITCHER_CROP ||
            block == Blocks.TORCHFLOWER;
    }

    private boolean plantFilter(Item item) {
        return item == Items.WHEAT_SEEDS ||
            item == Items.CARROT ||
            item == Items.POTATO ||
            item == Items.BEETROOT_SEEDS ||
            item == Items.PUMPKIN_SEEDS ||
            item == Items.MELON_SEEDS ||
            item == Items.NETHER_WART ||
            item == Items.PITCHER_POD ||
            item == Items.TORCHFLOWER_SEEDS;
    }
}
