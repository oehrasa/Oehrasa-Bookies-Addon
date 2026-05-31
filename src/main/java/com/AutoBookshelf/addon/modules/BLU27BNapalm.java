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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

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
        .defaultValue(2)
        .range(0, 100)
        .sliderRange(0, 100)
        .build()
        // Yes I'm listening to Mommy ASMR while adding these
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

        // Extinguish mode
        if (extinguishFire.get()) {
            BlockPos playerPos = mc.player.blockPosition();
            int radius = (int) Math.ceil(range.get());
            int blocksPerTick = 5;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        BlockPos pos = playerPos.offset(dx, dy, dz);
                        if (PlayerUtils.distanceTo(pos) > range.get()) continue;

                        BlockState state = mc.level.getBlockState(pos);
                        if (state.getBlock() != Blocks.FIRE && !(state.getBlock() instanceof BaseFireBlock)) continue;

                        mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, Direction.UP));
                        mc.player.swing(InteractionHand.MAIN_HAND);
                        mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, Direction.UP));

                        if (--blocksPerTick <= 0) return;
                    }
                }
            }
            return;
        }

        // Find flint and steel
        FindItemResult findFlintAndSteel = InvUtils.findInHotbar(
            itemStack -> itemStack.getItem() == Items.FLINT_AND_STEEL
                && (!antiBreak.get() || itemStack.getDamageValue() < itemStack.getMaxDamage() - 1)
        );
        if (!findFlintAndSteel.found()) return;

        RandomSource random = RandomSource.create();

        BlockPos playerPos = mc.player.blockPosition();
        int radius = (int) Math.ceil(range.get());
        BlockPos bestPos = null;
        Direction bestFace = null;
        double bestDist = Double.MAX_VALUE;

        // Ignite mode
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = playerPos.offset(dx, dy, dz);
                    double dist = PlayerUtils.distanceTo(pos);
                    if (dist > range.get()) continue;

                    BlockState state = mc.level.getBlockState(pos);
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

        if (bestPos != null) {
            targetPos = bestPos;
            targetFace = bestFace;

            if (!InvUtils.swap(findFlintAndSteel.slot(), true)) return;
            InteractionHand hand = findFlintAndSteel.getHand();

            if (rotate.get()) {
                Vec3 hitVec2 = Vec3.atCenterOf(targetPos.relative(targetFace));
                Rotations.rotate(Rotations.getYaw(hitVec2), Rotations.getPitch(hitVec2), -100,
                    () -> interact(hand));
            } else {
                interact(hand);
            }
        }
    }

    private Direction getAnyIgnitionFace(BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.TNT) {
            for (Direction dir : Direction.values()) {
                if (mc.level.getBlockState(pos.relative(dir)).isAir()) {
                    return dir;
                }
            }
            return Direction.UP;
        }

        boolean allowed = state.is(BlockTags.PLANKS)
            || state.is(BlockTags.WOODEN_SLABS)
            || state.is(BlockTags.WOODEN_STAIRS)
            || state.is(BlockTags.WOODEN_FENCES)
            || state.is(BlockTags.FENCE_GATES)
            || state.is(BlockTags.LOGS_THAT_BURN)
            || state.is(BlockTags.LEAVES)
            || state.is(BlockTags.WOOL)
            || state.is(BlockTags.WOOL_CARPETS)
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
            BlockPos neighborPos = pos.relative(dir);
            BlockState neighbor = mc.level.getBlockState(neighborPos);
            if (neighbor.isAir() && BaseFireBlock.canBePlacedAt(mc.level, neighborPos, dir.getOpposite())) {
                return dir;
            }
        }
        return null;
    }

    private void interact(InteractionHand hand) {
        if (targetPos == null || targetFace == null) return;

        BlockHitResult hit = new BlockHitResult(
            Vec3.atCenterOf(targetPos).add(Vec3.atLowerCornerOf(targetFace.getUnitVec3i()).scale(0.5)),
            targetFace,
            targetPos,
            false
        );

        mc.gameMode.useItemOn(mc.player, hand, hit);
        InvUtils.swapBack();
    }
}
