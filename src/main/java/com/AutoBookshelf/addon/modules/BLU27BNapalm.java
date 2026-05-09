package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

public class BLU27BNapalm extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("How far to search for flammable blocks.")
        .min(1)
        .max(10)
        .sliderRange(1, 6)
        .defaultValue(5)
        .build()
    );

    private final Setting<Boolean> antiBreak = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-break")
        .description("Prevents flint and steel from being broken.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Automatically faces the block being ignited.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> tickInterval = sgGeneral.add(new IntSetting.Builder()
        .name("tick-interval")
        .description("Minimum ticks between ignition attempts.")
        .defaultValue(5)
        .build()
    );

    private final Setting<Integer> igniteChance = sgGeneral.add(new IntSetting.Builder()
        .name("ignite-chance")
        .description("Probability 0 to 100% that a flammable block will actually be ignited.")
        .defaultValue(8)
        .range(0, 100)
        .sliderRange(0, 100)
        .build()
        // Yes I'm listening to mommy ASMR while adding these
    );

    private final Setting<Boolean> extinguishFire = sgGeneral.add(new BoolSetting.Builder()
        .name("extinguish-fire")
        .description("Instead of burning Vietcong jungles, extinguish nearby fire blocks.")
        .defaultValue(false)
        .build()
    );

    private int ticks = 0;
    private BlockPos targetPos;
    private Direction targetFace;

    public BLU27BNapalm() {
        super(Addon.CATEGORY, "BLU-27/B-Napalm", "I love the smell of Napalm in the morning, Commit some trolling against the Vietnamese");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        ticks++;
        if (ticks < tickInterval.get()) return;
        ticks = 0;

        targetPos = null;

        // Find flint and steel
        FindItemResult findFlintAndSteel = InvUtils.findInHotbar(
            itemStack -> itemStack.getItem() == Items.FLINT_AND_STEEL
                && (!antiBreak.get() || itemStack.getDamage() < itemStack.getMaxDamage() - 1)
        );
        if (!findFlintAndSteel.found()) return;

        Random random = Random.create();

        BlockPos playerPos = mc.player.getBlockPos();
        int radius = (int) Math.ceil(range.get());
        BlockPos bestPos = null;
        Direction bestFace = null;
        double bestDist = Double.MAX_VALUE;

        if (extinguishFire.get()) {
            // Extinguish mode
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        BlockPos pos = playerPos.add(dx, dy, dz);
                        double dist = PlayerUtils.distanceTo(pos);
                        if (dist > range.get()) continue;

                        BlockState state = mc.world.getBlockState(pos);
                        if (state.getBlock() != Blocks.FIRE && !(state.getBlock() instanceof AbstractFireBlock)) continue;
                        if (!PlayerUtils.isWithinReach(pos)) continue;

                        if (random.nextInt(100) >= igniteChance.get()) continue;

                        if (dist < bestDist) {
                            bestDist = dist;
                            bestPos = pos;
                            bestFace = Direction.UP;   // any direction works for breaking fire
                        }
                    }
                }
            }
        } else {
            // Ignite mode
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        BlockPos pos = playerPos.add(dx, dy, dz);
                        double dist = PlayerUtils.distanceTo(pos);
                        if (dist > range.get()) continue;

                        BlockState state = mc.world.getBlockState(pos);
                        if (state.isAir()) continue;

                        Direction face = getAnyIgnitionFace(pos, state);
                        if (!PlayerUtils.isWithinReach(pos)) continue;
                        if (face != null) {
                            if (random.nextInt(100) >= igniteChance.get()) continue;

                            if (dist < bestDist) {
                                bestDist = dist;
                                bestPos = pos;
                                bestFace = face;
                            }
                        }
                    }
                }
            }
        }

        if (bestPos != null) {
            targetPos = bestPos;
            targetFace = bestFace;

            if (!InvUtils.swap(findFlintAndSteel.slot(), true)) return;
            Hand hand = findFlintAndSteel.getHand();

            if (extinguishFire.get()) {
                // Attack the fire block
                Vec3d hitVec = Vec3d.ofCenter(targetPos);
                if (rotate.get()) {
                    Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec), -100, () -> {
                        mc.interactionManager.attackBlock(targetPos, Direction.UP);
                        mc.player.swingHand(hand);
                    });
                } else {
                    mc.interactionManager.attackBlock(targetPos, Direction.UP);
                    mc.player.swingHand(hand);
                }
                InvUtils.swapBack();
            } else {
                // Ignite interaction
                if (rotate.get()) {
                    Vec3d hitVec2 = Vec3d.ofCenter(targetPos.offset(targetFace));
                    Rotations.rotate(Rotations.getYaw(hitVec2), Rotations.getPitch(hitVec2), -100,
                        () -> interact(hand));
                } else {
                    interact(hand);
                }
            }
        }
    }

    private Direction getAnyIgnitionFace(BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.TNT) {
            for (Direction dir : Direction.values()) {
                if (mc.world.getBlockState(pos.offset(dir)).isAir()) {
                    return dir;
                }
            }
            return Direction.UP;
        }

        boolean allowed = state.isIn(BlockTags.PLANKS)
            || state.isIn(BlockTags.WOODEN_SLABS)
            || state.isIn(BlockTags.WOODEN_STAIRS)
            || state.isIn(BlockTags.WOODEN_FENCES)
            || state.isIn(BlockTags.FENCE_GATES)
            || state.isIn(BlockTags.LOGS_THAT_BURN)
            || state.isIn(BlockTags.LEAVES)
            || state.isIn(BlockTags.WOOL)
            || state.isIn(BlockTags.WOOL_CARPETS)
            || block == Blocks.STRIPPED_OAK_WOOD || block == Blocks.STRIPPED_SPRUCE_WOOD
            || block == Blocks.STRIPPED_BIRCH_WOOD || block == Blocks.STRIPPED_JUNGLE_WOOD
            || block == Blocks.STRIPPED_ACACIA_WOOD || block == Blocks.STRIPPED_CHERRY_WOOD
            || block == Blocks.STRIPPED_DARK_OAK_WOOD || block == Blocks.STRIPPED_MANGROVE_WOOD
            || block == Blocks.STRIPPED_BAMBOO_BLOCK
            || block == Blocks.BAMBOO_BLOCK || block == Blocks.BAMBOO_MOSAIC
            || block == Blocks.TNT || block == Blocks.HAY_BLOCK || block == Blocks.TARGET
            || block == Blocks.LECTERN || block == Blocks.COMPOSTER || block == Blocks.BEEHIVE
            || block == Blocks.DRIED_KELP_BLOCK || block == Blocks.BAMBOO || block == Blocks.SCAFFOLDING
            || block == Blocks.VINE
            || block == Blocks.CAVE_VINES || block == Blocks.CAVE_VINES_PLANT
            || block == Blocks.SPORE_BLOSSOM || block == Blocks.BIG_DRIPLEAF || block == Blocks.BIG_DRIPLEAF_STEM
            || block == Blocks.SMALL_DRIPLEAF || block == Blocks.HANGING_ROOTS
            || block == Blocks.GLOW_LICHEN || block == Blocks.AZALEA || block == Blocks.FLOWERING_AZALEA
            || block == Blocks.MANGROVE_ROOTS || block == Blocks.COAL_BLOCK
//            || block == Blocks.SHORT_GRASS || block == Blocks.TALL_GRASS || block == Blocks.FERN
//            || block == Blocks.LARGE_FERN || block == Blocks.DANDELION || block == Blocks.POPPY
//            || block == Blocks.BLUE_ORCHID || block == Blocks.ALLIUM || block == Blocks.AZURE_BLUET
//            || block == Blocks.RED_TULIP || block == Blocks.ORANGE_TULIP || block == Blocks.WHITE_TULIP
//            || block == Blocks.PINK_TULIP || block == Blocks.OXEYE_DAISY || block == Blocks.CORNFLOWER
//            || block == Blocks.LILY_OF_THE_VALLEY || block == Blocks.WITHER_ROSE || block == Blocks.SUNFLOWER
//            || block == Blocks.LILAC || block == Blocks.ROSE_BUSH || block == Blocks.PEONY
//            || block == Blocks.DEAD_BUSH || block == Blocks.PINK_PETALS
            ;

        if (!allowed) return null;

        // Find a valid air neighbor where fire can be placed
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = pos.offset(dir);
            BlockState neighbor = mc.world.getBlockState(neighborPos);
            if (neighbor.isAir() && AbstractFireBlock.canPlaceAt(mc.world, neighborPos, dir.getOpposite())) {
                return dir;
            }
        }
        return null;
    }

    private void interact(Hand hand) {
        if (targetPos == null || targetFace == null) return;

        BlockHitResult hit = new BlockHitResult(
            Vec3d.ofCenter(targetPos).add(Vec3d.of(targetFace.getVector()).multiply(0.5)),
            targetFace,
            targetPos,
            false
        );

        mc.interactionManager.interactBlock(mc.player, hand, hit);
        InvUtils.swapBack();
    }
}
