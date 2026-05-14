package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.*;

public class UnwaxAura extends Module {
    public enum CopperFilter {
        ALL("All Waxed Copper."),
        ONLY_OXIDIZED("Only Oxidized."),
        ONLY_WEATHERED("Only Weathered."),
        ONLY_EXPOSED("Only Exposed."),
        ONLY_UNAFFECTED("Only Normal Copper.");
        private final String title;
        CopperFilter(String title) { this.title = title; }
        @Override public String toString() { return title; }
    }

    public enum BreakMode {
        NONE("None - Just remove wax."),
        BREAK("Break - Break the block after unwaxing."),
        BREAK_ALL("Break All - Break all unwaxed copper blocks.");
        private final String title;
        BreakMode(String title) { this.title = title; }
        @Override public String toString() { return title; }
    }

    private static final Set<Block> WAXED_COPPER_BLOCKS = Set.of(
        Blocks.WAXED_COPPER_BLOCK, Blocks.WAXED_EXPOSED_COPPER, Blocks.WAXED_WEATHERED_COPPER, Blocks.WAXED_OXIDIZED_COPPER,
        Blocks.WAXED_CUT_COPPER, Blocks.WAXED_EXPOSED_CUT_COPPER, Blocks.WAXED_WEATHERED_CUT_COPPER, Blocks.WAXED_OXIDIZED_CUT_COPPER,
        Blocks.WAXED_CUT_COPPER_STAIRS, Blocks.WAXED_EXPOSED_CUT_COPPER_STAIRS, Blocks.WAXED_WEATHERED_CUT_COPPER_STAIRS, Blocks.WAXED_OXIDIZED_CUT_COPPER_STAIRS,
        Blocks.WAXED_CUT_COPPER_SLAB, Blocks.WAXED_EXPOSED_CUT_COPPER_SLAB, Blocks.WAXED_WEATHERED_CUT_COPPER_SLAB, Blocks.WAXED_OXIDIZED_CUT_COPPER_SLAB,
        Blocks.WAXED_CHISELED_COPPER, Blocks.WAXED_EXPOSED_CHISELED_COPPER, Blocks.WAXED_WEATHERED_CHISELED_COPPER, Blocks.WAXED_OXIDIZED_CHISELED_COPPER,
        Blocks.WAXED_COPPER_DOOR, Blocks.WAXED_EXPOSED_COPPER_DOOR, Blocks.WAXED_WEATHERED_COPPER_DOOR, Blocks.WAXED_OXIDIZED_COPPER_DOOR,
        Blocks.WAXED_COPPER_TRAPDOOR, Blocks.WAXED_EXPOSED_COPPER_TRAPDOOR, Blocks.WAXED_WEATHERED_COPPER_TRAPDOOR, Blocks.WAXED_OXIDIZED_COPPER_TRAPDOOR,
        Blocks.WAXED_COPPER_GRATE, Blocks.WAXED_EXPOSED_COPPER_GRATE, Blocks.WAXED_WEATHERED_COPPER_GRATE, Blocks.WAXED_OXIDIZED_COPPER_GRATE,
        Blocks.WAXED_COPPER_BULB, Blocks.WAXED_EXPOSED_COPPER_BULB, Blocks.WAXED_WEATHERED_COPPER_BULB, Blocks.WAXED_OXIDIZED_COPPER_BULB
    );

    private static final Set<Block> OXIDIZED_WAXED = Set.of(
        Blocks.WAXED_OXIDIZED_COPPER, Blocks.WAXED_OXIDIZED_CUT_COPPER, Blocks.WAXED_OXIDIZED_CUT_COPPER_STAIRS,
        Blocks.WAXED_OXIDIZED_CUT_COPPER_SLAB, Blocks.WAXED_OXIDIZED_CHISELED_COPPER, Blocks.WAXED_OXIDIZED_COPPER_DOOR,
        Blocks.WAXED_OXIDIZED_COPPER_TRAPDOOR, Blocks.WAXED_OXIDIZED_COPPER_GRATE, Blocks.WAXED_OXIDIZED_COPPER_BULB
    );

    private static final Set<Block> WEATHERED_WAXED = Set.of(
        Blocks.WAXED_WEATHERED_COPPER, Blocks.WAXED_WEATHERED_CUT_COPPER, Blocks.WAXED_WEATHERED_CUT_COPPER_STAIRS,
        Blocks.WAXED_WEATHERED_CUT_COPPER_SLAB, Blocks.WAXED_WEATHERED_CHISELED_COPPER, Blocks.WAXED_WEATHERED_COPPER_DOOR,
        Blocks.WAXED_WEATHERED_COPPER_TRAPDOOR, Blocks.WAXED_WEATHERED_COPPER_GRATE, Blocks.WAXED_WEATHERED_COPPER_BULB
    );

    private static final Set<Block> EXPOSED_WAXED = Set.of(
        Blocks.WAXED_EXPOSED_COPPER, Blocks.WAXED_EXPOSED_CUT_COPPER, Blocks.WAXED_EXPOSED_CUT_COPPER_STAIRS,
        Blocks.WAXED_EXPOSED_CUT_COPPER_SLAB, Blocks.WAXED_EXPOSED_CHISELED_COPPER, Blocks.WAXED_EXPOSED_COPPER_DOOR,
        Blocks.WAXED_EXPOSED_COPPER_TRAPDOOR, Blocks.WAXED_EXPOSED_COPPER_GRATE, Blocks.WAXED_EXPOSED_COPPER_BULB
    );

    private static final Set<Block> UNAFFECTED_WAXED = Set.of(
        Blocks.WAXED_COPPER_BLOCK, Blocks.WAXED_CUT_COPPER, Blocks.WAXED_CUT_COPPER_STAIRS,
        Blocks.WAXED_CUT_COPPER_SLAB, Blocks.WAXED_CHISELED_COPPER, Blocks.WAXED_COPPER_DOOR,
        Blocks.WAXED_COPPER_TRAPDOOR, Blocks.WAXED_COPPER_GRATE, Blocks.WAXED_COPPER_BULB
    );

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFilter = settings.createGroup("Filter");
    private final SettingGroup sgBreak = settings.createGroup("Break");
    private final SettingGroup sgESP = settings.createGroup("ESP Settings");

    private final Setting<Integer> unwaxDelay = sgGeneral.add(new IntSetting.Builder()
        .name("unwax-delay").description("Ticks after unwaxing before checking result.").defaultValue(10).min(1).max(40).sliderRange(1, 40).build());
    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range").description("Reach distance in blocks.").defaultValue(4).min(1).max(6).sliderRange(1, 6).build());
    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate").description("Rotate towards the block before acting.").defaultValue(true).build());
    private final Setting<Boolean> autoTool = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-tool").description("Auto switch to an axe for unwaxing.").defaultValue(true).build());
    private final Setting<Boolean> returnSlot = sgGeneral.add(new BoolSetting.Builder()
        .name("return-slot").description("Return to original hotbar slot after tools.").defaultValue(true).build());

    private final Setting<BreakMode> breakMode = sgBreak.add(new EnumSetting.Builder<BreakMode>()
        .name("break-mode").description("What to do after unwaxing.").defaultValue(BreakMode.NONE).build());
    private final Setting<Integer> breakDelay = sgBreak.add(new IntSetting.Builder()
        .name("break-delay").description("Ticks after breaking before scanning again.").defaultValue(10).min(1).max(40).sliderRange(1, 40)
        .visible(() -> breakMode.get() != BreakMode.NONE).build());
    private final Setting<Boolean> autoPick = sgBreak.add(new BoolSetting.Builder()
        .name("auto-pick").description("Auto switch to a pickaxe for breaking.").defaultValue(true)
        .visible(() -> breakMode.get() != BreakMode.NONE).build());

    private final Setting<CopperFilter> copperFilter = sgFilter.add(new EnumSetting.Builder<CopperFilter>()
        .name("copper-filter").description("Which waxed copper to target.").defaultValue(CopperFilter.ALL).build());

    private final Setting<Boolean> espEnabled = sgESP.add(new BoolSetting.Builder()
        .name("ESP-enabled").description("Highlight waxed copper blocks.").defaultValue(true).build());
    private final Setting<Integer> espRange = sgESP.add(new IntSetting.Builder()
        .name("ESP-range").description("Max render distance for ESP.").defaultValue(64).min(8).max(256).sliderRange(8, 256)
        .visible(espEnabled::get).build());
    private final Setting<ShapeMode> shapeMode = sgESP.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("ESP shape mode.").defaultValue(ShapeMode.Lines).visible(espEnabled::get).build());
    private final Setting<SettingColor> sideColor = sgESP.add(new ColorSetting.Builder()
        .name("side-color").description("Side color of ESP box.").defaultValue(new SettingColor(255, 200, 0, 40)).visible(espEnabled::get).build());
    private final Setting<SettingColor> lineColor = sgESP.add(new ColorSetting.Builder()
        .name("line-color").description("Line color of ESP box.").defaultValue(new SettingColor(255, 200, 0, 200)).visible(espEnabled::get).build());
    private final Setting<Boolean> tracer = sgESP.add(new BoolSetting.Builder()
        .name("tracer").description("Draw line to target.").defaultValue(true).visible(espEnabled::get).build());
    private final Setting<SettingColor> tracerColor = sgESP.add(new ColorSetting.Builder()
        .name("tracer-color").description("Tracer line color.").defaultValue(new SettingColor(255, 200, 0, 200))
        .visible(() -> espEnabled.get() && tracer.get()).build());

    private enum Stage { SCAN, ROTATE_UNWAX, UNWAX, WAIT_UNWAX, ROTATE_BREAK, START_BREAK, BREAKING, WAIT_BREAK }
    private Stage stage = Stage.SCAN;
    private BlockPos currentTarget = null;
    private int rotTimer = 0;
    private int actionTimer = 0;
    private boolean breakingStarted = false;
    private int originalSlot = -1;
    private boolean lookingForUnwaxed = false;

    public UnwaxAura() {
        super(Addon.CATEGORY, "Unwax-Aura", "Automatically removes wax from copper blocks and optionally breaks them.");
    }

    @Override
    public void onActivate() {
        stage = Stage.SCAN;
        currentTarget = null;
        rotTimer = 0;
        actionTimer = 0;
        breakingStarted = false;
        originalSlot = -1;
        lookingForUnwaxed = false;
    }

    @Override
    public void onDeactivate() {
        if (returnSlot.get() && originalSlot != -1 && originalSlot != mc.player.getInventory().getSelectedSlot()) {
            InvUtils.swap(originalSlot, false);
            originalSlot = -1;
        }
        currentTarget = null;
    }

    private boolean isWaxedCopper(BlockState state) {
        Block block = state.getBlock();
        return switch (copperFilter.get()) {
            case ONLY_OXIDIZED   -> OXIDIZED_WAXED.contains(block);
            case ONLY_WEATHERED  -> WEATHERED_WAXED.contains(block);
            case ONLY_EXPOSED    -> EXPOSED_WAXED.contains(block);
            case ONLY_UNAFFECTED -> UNAFFECTED_WAXED.contains(block);
            case ALL             -> WAXED_COPPER_BLOCKS.contains(block);
        };
    }

    private boolean isUnwaxedCopper(BlockState state) {
        Block block = state.getBlock();
        return !WAXED_COPPER_BLOCKS.contains(block) && (
            block == Blocks.COPPER_BLOCK || block == Blocks.EXPOSED_COPPER || block == Blocks.WEATHERED_COPPER || block == Blocks.OXIDIZED_COPPER ||
                block == Blocks.CUT_COPPER || block == Blocks.EXPOSED_CUT_COPPER || block == Blocks.WEATHERED_CUT_COPPER || block == Blocks.OXIDIZED_CUT_COPPER ||
                block == Blocks.CUT_COPPER_STAIRS || block == Blocks.EXPOSED_CUT_COPPER_STAIRS || block == Blocks.WEATHERED_CUT_COPPER_STAIRS || block == Blocks.OXIDIZED_CUT_COPPER_STAIRS ||
                block == Blocks.CUT_COPPER_SLAB || block == Blocks.EXPOSED_CUT_COPPER_SLAB || block == Blocks.WEATHERED_CUT_COPPER_SLAB || block == Blocks.OXIDIZED_CUT_COPPER_SLAB ||
                block == Blocks.CHISELED_COPPER || block == Blocks.EXPOSED_CHISELED_COPPER || block == Blocks.WEATHERED_CHISELED_COPPER || block == Blocks.OXIDIZED_CHISELED_COPPER ||
                block == Blocks.COPPER_DOOR || block == Blocks.EXPOSED_COPPER_DOOR || block == Blocks.WEATHERED_COPPER_DOOR || block == Blocks.OXIDIZED_COPPER_DOOR ||
                block == Blocks.COPPER_TRAPDOOR || block == Blocks.EXPOSED_COPPER_TRAPDOOR || block == Blocks.WEATHERED_COPPER_TRAPDOOR || block == Blocks.OXIDIZED_COPPER_TRAPDOOR ||
                block == Blocks.COPPER_GRATE || block == Blocks.EXPOSED_COPPER_GRATE || block == Blocks.WEATHERED_COPPER_GRATE || block == Blocks.OXIDIZED_COPPER_GRATE ||
                block == Blocks.COPPER_BULB || block == Blocks.EXPOSED_COPPER_BULB || block == Blocks.WEATHERED_COPPER_BULB || block == Blocks.OXIDIZED_COPPER_BULB
        );
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.interactionManager == null) return;
        if (mc.currentScreen != null) return;

        if (rotTimer > 0) {
            if (currentTarget != null && rotate.get()) {
                Vec3d vec = Vec3d.ofCenter(currentTarget);
                Rotations.rotate(Rotations.getYaw(vec), Rotations.getPitch(vec), 100);
            }
            rotTimer--;
            return;
        }

        if (actionTimer > 0) {
            actionTimer--;
            return;
        }

        switch (stage) {
            case SCAN -> findNextTarget();
            case ROTATE_UNWAX -> {
                if (currentTarget == null) { stage = Stage.SCAN; return; }
                stage = Stage.UNWAX;
            }
            case UNWAX -> performUnwax();
            case WAIT_UNWAX -> afterUnwax();
            case ROTATE_BREAK -> {
                if (currentTarget == null) { stage = Stage.SCAN; return; }
                stage = Stage.START_BREAK;
            }
            case START_BREAK -> startBreaking();
            case BREAKING -> continueBreaking();
            case WAIT_BREAK -> afterBreak();
        }
    }

    private void findNextTarget() {
        double closestDistSq = Double.MAX_VALUE;
        BlockPos closest = null;
        int r = range.get();
        BlockPos playerPos = mc.player.getBlockPos();
        for (BlockPos pos : BlockPos.iterateOutwards(playerPos, r, r, r)) {
            BlockState state = mc.world.getBlockState(pos);
            boolean matches = lookingForUnwaxed ? isUnwaxedCopper(state) : isWaxedCopper(state);
            if (!matches) continue;
            double dist = pos.getSquaredDistance(playerPos);
            if (dist < closestDistSq) {
                closestDistSq = dist;
                closest = pos.toImmutable();
            }
        }
        if (closest != null) {
            currentTarget = closest;
            if (lookingForUnwaxed) {
                stage = Stage.ROTATE_BREAK;
            } else {
                stage = Stage.ROTATE_UNWAX;
            }
            rotTimer = rotate.get() ? 8 : 0;
        } else {
            if (lookingForUnwaxed && breakMode.get() == BreakMode.BREAK_ALL) {
                lookingForUnwaxed = false;
                stage = Stage.SCAN;
            }
        }
    }

    private void performUnwax() {
        if (currentTarget == null) { stage = Stage.SCAN; return; }
        BlockState state = mc.world.getBlockState(currentTarget);
        if (!isWaxedCopper(state)) {
            afterUnwax(); return;
        }
        if (autoTool.get()) {
            FindItemResult axe = InvUtils.findInHotbar(
                Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE, Items.STONE_AXE, Items.WOODEN_AXE);
            if (axe.found()) {
                if (originalSlot == -1) originalSlot = mc.player.getInventory().getSelectedSlot();
                InvUtils.swap(axe.slot(), true);
            }
        }
        mc.options.sneakKey.setPressed(true);
        Vec3d hitVec = Vec3d.ofCenter(currentTarget);
        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, currentTarget, false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);
        mc.options.sneakKey.setPressed(false);
        stage = Stage.WAIT_UNWAX;
        actionTimer = unwaxDelay.get();
    }

    private void afterUnwax() {
        if (currentTarget == null) { stage = Stage.SCAN; return; }
        BlockState state = mc.world.getBlockState(currentTarget);
        if (!isUnwaxedCopper(state)) {
            stage = Stage.ROTATE_UNWAX;
            rotTimer = rotate.get() ? 5 : 0;
            return;
        }
        switch (breakMode.get()) {
            case NONE:
                stage = Stage.SCAN;
                currentTarget = null;
                break;
            case BREAK:
                stage = Stage.ROTATE_BREAK;
                rotTimer = rotate.get() ? 8 : 0;
                breakingStarted = false;
                break;
            case BREAK_ALL:
                lookingForUnwaxed = true;
                stage = Stage.SCAN;
                currentTarget = null;
                break;
        }
    }

    private void startBreaking() {
        if (currentTarget == null) { stage = Stage.SCAN; return; }
        if (autoPick.get() && !breakingStarted) {
            FindItemResult pick = InvUtils.findInHotbar(
                Items.NETHERITE_PICKAXE, Items.DIAMOND_PICKAXE, Items.IRON_PICKAXE, Items.STONE_PICKAXE, Items.WOODEN_PICKAXE);
            if (pick.found()) {
                if (originalSlot == -1) originalSlot = mc.player.getInventory().getSelectedSlot();
                InvUtils.swap(pick.slot(), true);
            }
        }
        if (!breakingStarted) {
            mc.interactionManager.attackBlock(currentTarget, Direction.UP);
            breakingStarted = true;
        } else {
            mc.interactionManager.updateBlockBreakingProgress(currentTarget, Direction.UP);
        }
        mc.player.swingHand(Hand.MAIN_HAND);
        stage = Stage.BREAKING;
        actionTimer = 1;
    }

    private void continueBreaking() {
        if (currentTarget == null) { stage = Stage.SCAN; return; }
        BlockState state = mc.world.getBlockState(currentTarget);
        if (state.isAir()) {
            stage = Stage.WAIT_BREAK;
            actionTimer = breakDelay.get();
            breakingStarted = false;
        } else {
            mc.interactionManager.updateBlockBreakingProgress(currentTarget, Direction.UP);
            mc.player.swingHand(Hand.MAIN_HAND);
            actionTimer = 1;
        }
    }

    private void afterBreak() {
        if (returnSlot.get() && originalSlot != -1) {
            InvUtils.swap(originalSlot, false);
            originalSlot = -1;
        }
        if (breakMode.get() == BreakMode.BREAK_ALL) {
            lookingForUnwaxed = true;
        } else {
            lookingForUnwaxed = false;
        }
        stage = Stage.SCAN;
        currentTarget = null;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!espEnabled.get() || mc.player == null || mc.world == null) return;
        Set<BlockPos> blocks = new HashSet<>();
        if (currentTarget != null) {
            blocks.add(currentTarget);
        } else if (stage == Stage.SCAN) {
            int r = range.get();
            BlockPos playerPos = mc.player.getBlockPos();
            for (BlockPos pos : BlockPos.iterateOutwards(playerPos, r, r, r)) {
                if (isWaxedCopper(mc.world.getBlockState(pos))) {
                    blocks.add(pos.toImmutable());
                }
            }
        }
        int rangeSq = espRange.get() * espRange.get();
        for (BlockPos pos : blocks) {
            if (mc.player.getBlockPos().getSquaredDistance(pos) > rangeSq) continue;
            event.renderer.box(pos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            if (tracer.get()) {
                event.renderer.line(
                    RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    tracerColor.get()
                );
            }
        }
    }
}
