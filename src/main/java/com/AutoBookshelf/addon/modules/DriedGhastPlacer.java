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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class DriedGhastPlacer extends Module {
    private enum Stage {
        IDLE,
        // Ice-mode stages
        ROTATE_TO_PLACE_ICE, PLACE_ICE, WAIT_ICE,
        ROTATE_TO_BREAK, BREAK_ICE, WAIT_BREAK,
        // Common stages
        ROTATE_TO_PLACE_GHAST, PLACE_GHAST, WAIT_GHAST
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

    private final Setting<Boolean> fastMode = sgGeneral.add(new BoolSetting.Builder()
        .name("fast-mode")
        .description("Skip ice placement – look for existing water sources on solid blocks instead.")
        .defaultValue(false)
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
    private int iceSlot = -1, pickSlot = -1, ghastSlot = -1;

    public DriedGhastPlacer() {
        super(Addon.CATEGORY, "Dried-Ghast",
            "Give these cute and tiny little creatures a second chances.");
    }

    @Override public void onActivate() { resetState(); }
    @Override public void onDeactivate() { stage = Stage.IDLE; }

    private void resetState() {
        stage = Stage.IDLE;
        targetPos = supportBlockPos = null;
        delayTicks = 0;
        placedCount = 0;
        driedGhastBlock = null;
        iceSlot = pickSlot = ghastSlot = -1;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;
        if (maxBlocks.get() > 0 && placedCount >= maxBlocks.get()) {
            info("Placed " + placedCount + " dried ghast blocks (limit reached).");
            toggle();
            return;
        }
        if (delayTicks > 0) { delayTicks--; return; }

        switch (stage) {
            case IDLE -> prepare();
            // Ice mode stages
            case ROTATE_TO_PLACE_ICE -> placeIce();
            case PLACE_ICE -> executePlaceIce();
            case WAIT_ICE -> checkIce();
            case ROTATE_TO_BREAK -> breakIce();
            case BREAK_ICE -> executeBreakIce();
            case WAIT_BREAK -> checkWater();
            // Normal stages
            case ROTATE_TO_PLACE_GHAST -> placeGhast();
            case PLACE_GHAST -> executePlaceGhast();
            case WAIT_GHAST -> finishCycle();
        }
    }

    // Idle: find items and a valid position
    private void prepare() {
        ghastSlot = findSlot(s -> s.getItem() == Items.DRIED_GHAST);
        if (ghastSlot == -1) { info("§cNo dried ghast block in hotbar."); toggle(); return; }

        Item item = mc.player.getInventory().getItem(ghastSlot).getItem();
        if (item instanceof BlockItem bi) driedGhastBlock = bi.getBlock();
        else { info("§cDried ghast item is not a block."); toggle(); return; }

        if (fastMode.get()) {
            // Fast mode: find existing water source on a solid block
            if (!findWaterPlacementPosition()) { delayTicks = 20; return; }
            InvUtils.swap(ghastSlot, true);
            if (rotate.get()) {
                Rotations.rotate(Rotations.getYaw(Vec3.atCenterOf(supportBlockPos).add(0, 0.5, 0)),
                    Rotations.getPitch(Vec3.atCenterOf(supportBlockPos).add(0, 0.5, 0)));
                delayTicks = 2;
            }
            stage = Stage.ROTATE_TO_PLACE_GHAST;
            return;
        }

        // Ice mode: need ice and a valid pickaxe
        iceSlot = findSlot(s -> s.getItem() == Items.ICE);
        if (iceSlot == -1) { info("§cNo ice blocks in hotbar."); toggle(); return; }

        pickSlot = findSlot(this::isValidPickaxe);
        if (pickSlot == -1) { info("§cNo valid pickaxe in hotbar."); toggle(); return; }

        if (!findTopPlacementPosition()) { delayTicks = 20; return; }

        InvUtils.swap(iceSlot, true);
        if (rotate.get()) {
            Vec3 hitVec = Vec3.atCenterOf(supportBlockPos).add(0, 0.5, 0);
            Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec));
            delayTicks = 2;
        }
        stage = Stage.ROTATE_TO_PLACE_ICE;
    }

    // Find position
    private boolean findTopPlacementPosition() {
        if (driedGhastBlock == null) return false;
        return findPosition(false);
    }

    /** Fast mode: target block must be water */
    private boolean findWaterPlacementPosition() {
        if (driedGhastBlock == null) return false;
        return findPosition(true);
    }

    private boolean findPosition(boolean requireWater) {
        if (driedGhastBlock == null) return false;

        BlockPos playerFeet = mc.player.blockPosition();
        BlockPos playerHead = playerFeet.above();
        double maxDistSq = range.get() * range.get();

        for (int dx = (int) -range.get(); dx <= range.get(); dx++)
            for (int dy = (int) -range.get(); dy <= range.get(); dy++)
                for (int dz = (int) -range.get(); dz <= range.get(); dz++) {
                    BlockPos pos = playerFeet.offset(dx, dy, dz);

                    // Skip blocks right at the player's feet or head
                    if (pos.equals(playerFeet) || pos.equals(playerHead)) continue;
                    if (pos.distSqr(playerFeet) > maxDistSq) continue;

                    // Skip if any entity (other than the local player) occupies this block
                    if (!mc.level.getEntities(null, new AABB(pos)).isEmpty()) continue;

                    BlockState ts = mc.level.getBlockState(pos);
                    if (requireWater) {
                        if (ts.getBlock() != Blocks.WATER) continue;
                    } else {
                        if (!ts.isAir() && ts.getBlock() != Blocks.WATER) continue;
                    }
                    if (ts.getBlock() == driedGhastBlock) continue;

                    BlockPos below = pos.below();
                    if (below.equals(playerFeet)) continue;

                    BlockState bs = mc.level.getBlockState(below);
                    if (!bs.isRedstoneConductor(mc.level, below)) continue;
                    if (bs.getBlock() == driedGhastBlock) continue;

                    targetPos = pos;
                    supportBlockPos = below;
                    return true;
                }
        return false;
    }

    private void placeIce() { stage = Stage.PLACE_ICE; }

    private void executePlaceIce() {
        Vec3 hitVec = Vec3.atCenterOf(supportBlockPos).add(0, 0.5, 0);
        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, supportBlockPos, false);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        mc.player.swing(InteractionHand.MAIN_HAND);
        delayTicks = 2; stage = Stage.WAIT_ICE;
    }
    private void checkIce() {
        if (mc.level.getBlockState(targetPos).getBlock() != Blocks.ICE) { delayTicks = 2; return; }
        InvUtils.swapBack(); InvUtils.swap(pickSlot, true);
        if (rotate.get()) Rotations.rotate(Rotations.getYaw(Vec3.atCenterOf(targetPos)), Rotations.getPitch(Vec3.atCenterOf(targetPos)), -100, () -> {});
        delayTicks = 2; stage = Stage.ROTATE_TO_BREAK;
    }
    private void breakIce() { stage = Stage.BREAK_ICE; }

    private void executeBreakIce() {
        mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, targetPos, Direction.UP));
        mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, targetPos, Direction.UP));
        mc.player.swing(InteractionHand.MAIN_HAND);
        delayTicks = 4; stage = Stage.WAIT_BREAK;
    }

    private void checkWater() {
        if (mc.level.getBlockState(targetPos).getBlock() != Blocks.WATER) { delayTicks = 2; return; }
        InvUtils.swapBack(); InvUtils.swap(ghastSlot, true);
        if (rotate.get()) Rotations.rotate(Rotations.getYaw(Vec3.atCenterOf(targetPos)), Rotations.getPitch(Vec3.atCenterOf(targetPos)), -100, () -> {});
        delayTicks = 2; stage = Stage.ROTATE_TO_PLACE_GHAST;
    }

    // Place dried ghast
    private void placeGhast() { stage = Stage.PLACE_GHAST; }
    private void executePlaceGhast() {
        Vec3 hitVec = Vec3.atCenterOf(supportBlockPos).add(0, 0.5, 0);
        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, supportBlockPos, false);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        mc.player.swing(InteractionHand.MAIN_HAND);
        delayTicks = 2; stage = Stage.WAIT_GHAST;
    }

    private void finishCycle() {
        InvUtils.swapBack();
        placedCount++;
        stage = Stage.IDLE;
        targetPos = supportBlockPos = null;
        delayTicks = placeDelay.get();
    }

    private int findSlot(java.util.function.Predicate<ItemStack> filter) {
        for (int i = 0; i < 9; i++) if (filter.test(mc.player.getInventory().getItem(i))) return i;
        return -1;
    }
    private boolean isValidPickaxe(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (!stack.is(ItemTags.PICKAXES)) return false;
        var ench = stack.get(net.minecraft.core.component.DataComponents.ENCHANTMENTS);
        if (ench != null) for (var entry : ench.entrySet())
            if (entry.getKey().is(net.minecraft.resources.Identifier.withDefaultNamespace("silk_touch"))) return false;
        return true;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || targetPos == null) return;
        event.renderer.box(targetPos, sideColor.get(), lineColor.get(), ShapeMode.Both, 0);
    }
}
