package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class DriedGhastPlacer extends Module {
    private enum Stage {
        IDLE,
        ROTATE_TO_PLACE_ICE,
        PLACE_ICE,
        WAIT_ICE,
        ROTATE_TO_BREAK,
        BREAK_ICE,
        WAIT_BREAK,
        ROTATE_TO_PLACE_GHAST,
        PLACE_GHAST,
        WAIT_GHAST
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .defaultValue(4.5)
        .min(1)
        .sliderMax(5)
        .build());

    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay")
        .defaultValue(5)
        .min(1)
        .sliderMax(40)
        .build());

    private final Setting<Integer> maxBlocks = sgGeneral.add(new IntSetting.Builder()
        .name("max-blocks")
        .defaultValue(0)
        .min(0)
        .sliderMax(256)
        .build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .defaultValue(true)
        .build());

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .defaultValue(new SettingColor(255, 165, 0, 75))
        .build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .defaultValue(new SettingColor(255, 165, 0, 255))
        .build());

    private Stage stage = Stage.IDLE;
    private BlockPos targetPos;
    private BlockPos supportBlockPos;
    private int delayTicks = 0;
    private int placedCount = 0;
    private Block driedGhastBlock = null;
    private int iceSlot = -1;
    private int pickSlot = -1;
    private int ghastSlot = -1;

    public DriedGhastPlacer() {
        super(Addon.CATEGORY, "Dried-Ghast",
            "Give these cute and tiny little creatures a second chances.");
    }

    @Override
    public void onActivate() {
        stage = Stage.IDLE;
        targetPos = null;
        supportBlockPos = null;
        delayTicks = 0;
        placedCount = 0;
        driedGhastBlock = null;
        iceSlot = -1;
        pickSlot = -1;
        ghastSlot = -1;
    }

    @Override
    public void onDeactivate() {
        stage = Stage.IDLE;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (maxBlocks.get() > 0 && placedCount >= maxBlocks.get()) {
            info("Placed " + placedCount + " dried ghast blocks, limit reached!.");
            toggle();
            return;
        }

        if (delayTicks > 0) {
            delayTicks--;
            return;
        }

        switch (stage) {
            case IDLE -> prepare();
            case ROTATE_TO_PLACE_ICE -> placeIce();
            case PLACE_ICE -> executePlaceIce();
            case WAIT_ICE -> checkIce();
            case ROTATE_TO_BREAK -> breakIce();
            case BREAK_ICE -> executeBreakIce();
            case WAIT_BREAK -> checkWater();
            case ROTATE_TO_PLACE_GHAST -> placeGhast();
            case PLACE_GHAST -> executePlaceGhast();
            case WAIT_GHAST -> finishCycle();
        }
    }

    // When idle we find all required items and a valid position
    private void prepare() {
        // Find ice block
        iceSlot = findSlot(s -> s.getItem() == Items.ICE);
        if (iceSlot == -1) {
            info("§cNo ice blocks in hotbar.");
            toggle();
            return;
        }

        // Find a pickaxe without silk touch
        pickSlot = findSlot(this::isValidPickaxe);
        if (pickSlot == -1) {
            info("§cNo valid pickaxe in hotbar.");
            toggle();
            return;
        }

        // Find dried ghast
        ghastSlot = findSlot(s -> s.getItem() == Items.DRIED_GHAST);
        if (ghastSlot == -1) {
            info("§cNo dried ghast block in hotbar.");
            toggle();
            return;
        }

        // Determine the dried ghast block type
        Item item = mc.player.getInventory().getStack(ghastSlot).getItem();
        if (item instanceof BlockItem bi) driedGhastBlock = bi.getBlock();
        else {
            info("§cDried ghast item is not a block.");
            toggle();
            return;
        }

        if (!findTopPlacementPosition()) {
            delayTicks = 20;
            return;
        }

        // Swap to ice and rotate
        InvUtils.swap(iceSlot, true);
        if (rotate.get()) {
            Vec3d hitVec = Vec3d.ofCenter(supportBlockPos).add(0, 0.5, 0);
            Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec));
            delayTicks = 2;
        }
        stage = Stage.ROTATE_TO_PLACE_ICE;
    }

    // Place ice on support block
    private void placeIce() {
        stage = Stage.PLACE_ICE;
    }

    private void executePlaceIce() {
        Vec3d hitVec = Vec3d.ofCenter(supportBlockPos).add(0, 0.5, 0);
        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, supportBlockPos, false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);
        delayTicks = 2;
        stage = Stage.WAIT_ICE;
    }

    // Wait for ice to appear
    private void checkIce() {
        BlockState state = mc.world.getBlockState(targetPos);
        if (state.getBlock() != Blocks.ICE) {
            delayTicks = 2;   // retry
            return;
        }
        // Ice placed, swap to pickaxe for breaking
        InvUtils.swapBack();       // return ice item
        InvUtils.swap(pickSlot, true);
        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(Vec3d.ofCenter(targetPos)),
                Rotations.getPitch(Vec3d.ofCenter(targetPos)));
            delayTicks = 2;
        }
        stage = Stage.ROTATE_TO_BREAK;
    }

    private void breakIce() {
        stage = Stage.BREAK_ICE;
    }

    private void executeBreakIce() {
        // Send start and stop destroy packets
        mc.getNetworkHandler().sendPacket(
            new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, targetPos, Direction.UP));
        mc.getNetworkHandler().sendPacket(
            new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, targetPos, Direction.UP));
        mc.player.swingHand(Hand.MAIN_HAND);
        delayTicks = 4;   // wait for water to form
        stage = Stage.WAIT_BREAK;
    }

    private void checkWater() {
        BlockState state = mc.world.getBlockState(targetPos);
        if (state.getBlock() != Blocks.WATER) {
            delayTicks = 2;   // retry
            return;
        }
        // Water is present, swap to dried ghast
        InvUtils.swapBack();       // return pickaxe
        InvUtils.swap(ghastSlot, true);
        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(Vec3d.ofCenter(targetPos)),
                Rotations.getPitch(Vec3d.ofCenter(targetPos)));
            delayTicks = 2;
        }
        stage = Stage.ROTATE_TO_PLACE_GHAST;
    }

    // Place dried ghast into water
    private void placeGhast() {
        stage = Stage.PLACE_GHAST;
    }

    private void executePlaceGhast() {
        Vec3d hitVec = Vec3d.ofCenter(supportBlockPos).add(0, 0.5, 0);
        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, supportBlockPos, false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);
        delayTicks = 2;
        stage = Stage.WAIT_GHAST;
    }

    private void finishCycle() {
        InvUtils.swapBack();
        placedCount++;
        resetCycle();
    }

    private void resetCycle() {
        stage = Stage.IDLE;
        targetPos = null;
        supportBlockPos = null;
        delayTicks = placeDelay.get();
    }

    // Find position
    private boolean findTopPlacementPosition() {
        if (driedGhastBlock == null) return false;
        BlockPos playerPos = mc.player.getBlockPos();
        double maxDist = range.get();
        double maxDistSq = maxDist * maxDist;

        for (int dx = (int) -maxDist; dx <= maxDist; dx++)
            for (int dy = (int) -maxDist; dy <= maxDist; dy++)
                for (int dz = (int) -maxDist; dz <= maxDist; dz++) {
                    BlockPos pos = playerPos.add(dx, dy, dz);
                    if (pos.getSquaredDistance(playerPos) > maxDistSq) continue;

                    BlockState ts = mc.world.getBlockState(pos);
                    if (!ts.isAir() && ts.getBlock() != Blocks.WATER) continue;
                    if (ts.getBlock() == driedGhastBlock) continue;

                    BlockPos below = pos.down();
                    BlockState bs = mc.world.getBlockState(below);
                    if (!bs.isSolidBlock(mc.world, below)) continue;
                    if (bs.getBlock() == driedGhastBlock) continue;

                    targetPos = pos;
                    supportBlockPos = below;
                    return true;
                }
        return false;
    }

    // Hotbar
    private int findSlot(java.util.function.Predicate<ItemStack> filter) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (filter.test(stack)) return i;
        }
        return -1;
    }

    private boolean isValidPickaxe(ItemStack stack) {
        if (stack.isEmpty()) return false;
        // Check if it's a pickaxe via the vanilla tag
        if (!stack.isIn(net.minecraft.registry.tag.ItemTags.PICKAXES)) return false;
        // Check for Silk Touch enchantment
        var enchantments = stack.get(net.minecraft.component.DataComponentTypes.ENCHANTMENTS);
        if (enchantments != null) {
            for (var entry : enchantments.getEnchantmentEntries()) {
                if (entry.getKey().matchesId(net.minecraft.util.Identifier.ofVanilla("silk_touch"))) {
                    return false;
                }
            }
        }
        return true;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get()) return;
        if (targetPos != null) {
            event.renderer.box(targetPos, sideColor.get(), lineColor.get(), ShapeMode.Both, 0);
        }
    }
}
