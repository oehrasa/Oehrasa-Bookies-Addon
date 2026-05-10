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

import java.util.HashSet;
import java.util.Set;

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

    // All waxed copper variants from Blocks class
    private static final Set<Block> WAXED_COPPER_BLOCKS = Set.of(
        Blocks.WAXED_COPPER_BLOCK,
        Blocks.WAXED_EXPOSED_COPPER,
        Blocks.WAXED_WEATHERED_COPPER,
        Blocks.WAXED_OXIDIZED_COPPER,
        Blocks.WAXED_CUT_COPPER,
        Blocks.WAXED_EXPOSED_CUT_COPPER,
        Blocks.WAXED_WEATHERED_CUT_COPPER,
        Blocks.WAXED_OXIDIZED_CUT_COPPER,
        Blocks.WAXED_CUT_COPPER_STAIRS,
        Blocks.WAXED_EXPOSED_CUT_COPPER_STAIRS,
        Blocks.WAXED_WEATHERED_CUT_COPPER_STAIRS,
        Blocks.WAXED_OXIDIZED_CUT_COPPER_STAIRS,
        Blocks.WAXED_CUT_COPPER_SLAB,
        Blocks.WAXED_EXPOSED_CUT_COPPER_SLAB,
        Blocks.WAXED_WEATHERED_CUT_COPPER_SLAB,
        Blocks.WAXED_OXIDIZED_CUT_COPPER_SLAB,
        Blocks.WAXED_CHISELED_COPPER,
        Blocks.WAXED_EXPOSED_CHISELED_COPPER,
        Blocks.WAXED_WEATHERED_CHISELED_COPPER,
        Blocks.WAXED_OXIDIZED_CHISELED_COPPER,
        Blocks.WAXED_COPPER_DOOR,
        Blocks.WAXED_EXPOSED_COPPER_DOOR,
        Blocks.WAXED_WEATHERED_COPPER_DOOR,
        Blocks.WAXED_OXIDIZED_COPPER_DOOR,
        Blocks.WAXED_COPPER_TRAPDOOR,
        Blocks.WAXED_EXPOSED_COPPER_TRAPDOOR,
        Blocks.WAXED_WEATHERED_COPPER_TRAPDOOR,
        Blocks.WAXED_OXIDIZED_COPPER_TRAPDOOR,
        Blocks.WAXED_COPPER_GRATE,
        Blocks.WAXED_EXPOSED_COPPER_GRATE,
        Blocks.WAXED_WEATHERED_COPPER_GRATE,
        Blocks.WAXED_OXIDIZED_COPPER_GRATE,
        Blocks.WAXED_COPPER_BULB,
        Blocks.WAXED_EXPOSED_COPPER_BULB,
        Blocks.WAXED_WEATHERED_COPPER_BULB,
        Blocks.WAXED_OXIDIZED_COPPER_BULB
    );

    private static final Set<Block> OXIDIZED_WAXED = Set.of(
        Blocks.WAXED_OXIDIZED_COPPER,
        Blocks.WAXED_OXIDIZED_CUT_COPPER,
        Blocks.WAXED_OXIDIZED_CUT_COPPER_STAIRS,
        Blocks.WAXED_OXIDIZED_CUT_COPPER_SLAB,
        Blocks.WAXED_OXIDIZED_CHISELED_COPPER,
        Blocks.WAXED_OXIDIZED_COPPER_DOOR,
        Blocks.WAXED_OXIDIZED_COPPER_TRAPDOOR,
        Blocks.WAXED_OXIDIZED_COPPER_GRATE,
        Blocks.WAXED_OXIDIZED_COPPER_BULB
    );

    private static final Set<Block> WEATHERED_WAXED = Set.of(
        Blocks.WAXED_WEATHERED_COPPER,
        Blocks.WAXED_WEATHERED_CUT_COPPER,
        Blocks.WAXED_WEATHERED_CUT_COPPER_STAIRS,
        Blocks.WAXED_WEATHERED_CUT_COPPER_SLAB,
        Blocks.WAXED_WEATHERED_CHISELED_COPPER,
        Blocks.WAXED_WEATHERED_COPPER_DOOR,
        Blocks.WAXED_WEATHERED_COPPER_TRAPDOOR,
        Blocks.WAXED_WEATHERED_COPPER_GRATE,
        Blocks.WAXED_WEATHERED_COPPER_BULB
    );

    private static final Set<Block> EXPOSED_WAXED = Set.of(
        Blocks.WAXED_EXPOSED_COPPER,
        Blocks.WAXED_EXPOSED_CUT_COPPER,
        Blocks.WAXED_EXPOSED_CUT_COPPER_STAIRS,
        Blocks.WAXED_EXPOSED_CUT_COPPER_SLAB,
        Blocks.WAXED_EXPOSED_CHISELED_COPPER,
        Blocks.WAXED_EXPOSED_COPPER_DOOR,
        Blocks.WAXED_EXPOSED_COPPER_TRAPDOOR,
        Blocks.WAXED_EXPOSED_COPPER_GRATE,
        Blocks.WAXED_EXPOSED_COPPER_BULB
    );

    private static final Set<Block> UNAFFECTED_WAXED = Set.of(
        Blocks.WAXED_COPPER_BLOCK,
        Blocks.WAXED_CUT_COPPER,
        Blocks.WAXED_CUT_COPPER_STAIRS,
        Blocks.WAXED_CUT_COPPER_SLAB,
        Blocks.WAXED_CHISELED_COPPER,
        Blocks.WAXED_COPPER_DOOR,
        Blocks.WAXED_COPPER_TRAPDOOR,
        Blocks.WAXED_COPPER_GRATE,
        Blocks.WAXED_COPPER_BULB
    );

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFilter = settings.createGroup("Filter");
    private final SettingGroup sgBreak = settings.createGroup("Break");
    private final SettingGroup sgESP = settings.createGroup("ESP Settings");

    // General settings
    private final Setting<Integer> unwaxDelay = sgGeneral.add(new IntSetting.Builder()
        .name("unwax-delay")
        .description("Delay between removing wax from blocks in ticks.")
        .defaultValue(10)
        .min(1)
        .max(40)
        .sliderRange(1, 40)
        .build()
    );

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("How far to reach for blocks.")
        .defaultValue(4)
        .min(1)
        .max(6)
        .sliderRange(1, 6)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotate to face the block before removing wax.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoTool = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-tool")
        .description("Automatically switch to an axe for unwaxing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> returnSlot = sgGeneral.add(new BoolSetting.Builder()
        .name("return-slot")
        .description("Return to previous hotbar slot after using tools")
        .defaultValue(true)
        .build()
    );

    private final Setting<BreakMode> breakMode = sgBreak.add(new EnumSetting.Builder<BreakMode>()
        .name("break-mode")
        .description("What to do with the block after removing wax.")
        .defaultValue(BreakMode.NONE)
        .build()
    );

    private final Setting<Integer> breakDelay = sgBreak.add(new IntSetting.Builder()
        .name("break-delay")
        .description("Delay between breaking blocks in ticks after unwaxing.")
        .defaultValue(10)
        .min(1)
        .max(40)
        .sliderRange(1, 40)
        .visible(() -> breakMode.get() != BreakMode.NONE)
        .build()
    );

    private final Setting<Boolean> autoPick = sgBreak.add(new BoolSetting.Builder()
        .name("auto-pick")
        .description("Automatically switch to a pickaxe for breaking.")
        .defaultValue(true)
        .visible(() -> breakMode.get() != BreakMode.NONE)
        .build()
    );

    // Filter settings
    private final Setting<CopperFilter> copperFilter = sgFilter.add(new EnumSetting.Builder<CopperFilter>()
        .name("copper-filter")
        .description("Which type of waxed copper to target.")
        .defaultValue(CopperFilter.ALL)
        .build()
    );

    // ESP settings
    private final Setting<Boolean> espEnabled = sgESP.add(new BoolSetting.Builder()
        .name("ESP-enabled")
        .description("Render waxed copper blocks through walls.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> espRange = sgESP.add(new IntSetting.Builder()
        .name("ESP-range")
        .description("Range in blocks to render waxed copper")
        .defaultValue(64)
        .min(8)
        .max(256)
        .sliderRange(8, 256)
        .visible(espEnabled::get)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgESP.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the ESP is rendered.")
        .defaultValue(ShapeMode.Lines)
        .visible(espEnabled::get)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgESP.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The side color of the ESP box.")
        .defaultValue(new SettingColor(255, 200, 0, 40))
        .visible(espEnabled::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgESP.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The line color of the ESP box.")
        .defaultValue(new SettingColor(255, 200, 0, 200))
        .visible(espEnabled::get)
        .build()
    );

    private final Setting<Boolean> tracer = sgESP.add(new BoolSetting.Builder()
        .name("tracer")
        .description("Draw a line to the block.")
        .defaultValue(true)
        .visible(espEnabled::get)
        .build()
    );

    private final Setting<SettingColor> tracerColor = sgESP.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("The color of the tracer line.")
        .defaultValue(new SettingColor(255, 200, 0, 200))
        .visible(() -> espEnabled.get() && tracer.get())
        .build()
    );

    private int unwaxTimer = 0;
    private int breakTimer = 0;
    private Set<BlockPos> targetBlocks = new HashSet<>();
    private BlockPos currentTarget = null;
    private int originalSlot = -1;
    private int currentStage = 0; // 0 = unwax, 1 = break

    public UnwaxAura() {
        super(Addon.CATEGORY, "Unwax-Aura", "Automatically removes wax from waxed copper blocks and breaks them.");
    }

    @Override
    public void onActivate() {
        targetBlocks.clear();
        currentTarget = null;
        unwaxTimer = 0;
        breakTimer = 0;
        originalSlot = -1;
        currentStage = 0;
    }

    @Override
    public void onDeactivate() {
        targetBlocks.clear();
        currentTarget = null;
        unwaxTimer = 0;
        breakTimer = 0;
        currentStage = 0;

        if (returnSlot.get() && originalSlot != -1 && originalSlot != mc.player.getInventory().getSelectedSlot()) {
            InvUtils.swap(originalSlot, false);
            originalSlot = -1;
        }
    }

    private boolean isWaxedCopper(BlockState state) {
        Block block = state.getBlock();

        return switch (copperFilter.get()) {
            case ONLY_OXIDIZED -> OXIDIZED_WAXED.contains(block);
            case ONLY_WEATHERED -> WEATHERED_WAXED.contains(block);
            case ONLY_EXPOSED -> EXPOSED_WAXED.contains(block);
            case ONLY_UNAFFECTED -> UNAFFECTED_WAXED.contains(block);
            case ALL -> WAXED_COPPER_BLOCKS.contains(block);
        };
    }

    private boolean isUnwaxedCopper(BlockState state) {
        Block block = state.getBlock();
        return (block == Blocks.COPPER_BLOCK ||
                block == Blocks.EXPOSED_COPPER ||
                block == Blocks.WEATHERED_COPPER ||
                block == Blocks.OXIDIZED_COPPER ||
                block == Blocks.CUT_COPPER ||
                block == Blocks.EXPOSED_CUT_COPPER ||
                block == Blocks.WEATHERED_CUT_COPPER ||
                block == Blocks.OXIDIZED_CUT_COPPER ||
                block == Blocks.CUT_COPPER_STAIRS ||
                block == Blocks.EXPOSED_CUT_COPPER_STAIRS ||
                block == Blocks.WEATHERED_CUT_COPPER_STAIRS ||
                block == Blocks.OXIDIZED_CUT_COPPER_STAIRS ||
                block == Blocks.CUT_COPPER_SLAB ||
                block == Blocks.EXPOSED_CUT_COPPER_SLAB ||
                block == Blocks.WEATHERED_CUT_COPPER_SLAB ||
                block == Blocks.OXIDIZED_CUT_COPPER_SLAB ||
                block == Blocks.CHISELED_COPPER ||
                block == Blocks.EXPOSED_CHISELED_COPPER ||
                block == Blocks.WEATHERED_CHISELED_COPPER ||
                block == Blocks.OXIDIZED_CHISELED_COPPER ||
                block == Blocks.COPPER_DOOR ||
                block == Blocks.EXPOSED_COPPER_DOOR ||
                block == Blocks.WEATHERED_COPPER_DOOR ||
                block == Blocks.OXIDIZED_COPPER_DOOR ||
                block == Blocks.COPPER_TRAPDOOR ||
                block == Blocks.EXPOSED_COPPER_TRAPDOOR ||
                block == Blocks.WEATHERED_COPPER_TRAPDOOR ||
                block == Blocks.OXIDIZED_COPPER_TRAPDOOR ||
                block == Blocks.COPPER_GRATE ||
                block == Blocks.EXPOSED_COPPER_GRATE ||
                block == Blocks.WEATHERED_COPPER_GRATE ||
                block == Blocks.OXIDIZED_COPPER_GRATE ||
                block == Blocks.COPPER_BULB ||
                block == Blocks.EXPOSED_COPPER_BULB ||
                block == Blocks.WEATHERED_COPPER_BULB ||
                block == Blocks.OXIDIZED_COPPER_BULB) &&
                !WAXED_COPPER_BLOCKS.contains(block);
    }

    private void findTargetBlocks() {
        targetBlocks.clear();
        if (mc.player == null || mc.world == null) return;

        int r = range.get();
        BlockPos playerPos = mc.player.getBlockPos();

        for (BlockPos pos : BlockPos.iterateOutwards(playerPos, r, r, r)) {
            BlockState state = mc.world.getBlockState(pos);

            if (currentStage == 0) {
                if (isWaxedCopper(state)) {
                    if (mc.player.getBlockPos().getSquaredDistance(pos) <= r * r) {
                        targetBlocks.add(pos.toImmutable());
                    }
                }
            } else if (currentStage == 1 && breakMode.get() == BreakMode.BREAK_ALL) {
                if (isUnwaxedCopper(state)) {
                    if (mc.player.getBlockPos().getSquaredDistance(pos) <= r * r) {
                        targetBlocks.add(pos.toImmutable());
                    }
                }
            }
        }
    }

    private void unwaxBlock(BlockPos pos) {
        if (mc.player == null || mc.interactionManager == null) return;

        BlockState state = mc.world.getBlockState(pos);
        if (!isWaxedCopper(state)) return;

        mc.options.sneakKey.setPressed(true);

        mc.execute(() -> {
            Vec3d hitVec = Vec3d.ofCenter(pos);
            BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, pos, false);

            if (autoTool.get()) {
                FindItemResult axe = InvUtils.findInHotbar(
                    Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE, Items.STONE_AXE, Items.WOODEN_AXE
                );

                if (axe.found()) {
                    if (originalSlot == -1) originalSlot = mc.player.getInventory().getSelectedSlot();
                    InvUtils.swap(axe.slot(), true);
                } else {
                    mc.options.sneakKey.setPressed(false);
                    return;
                }
            }

            if (rotate.get()) {
                Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec), 50, () -> {
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    mc.options.sneakKey.setPressed(false);
                });
            } else {
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
                mc.player.swingHand(Hand.MAIN_HAND);
                mc.options.sneakKey.setPressed(false);
            }
        });
    }

    private void breakBlock(BlockPos pos) {
        if (mc.player == null || mc.interactionManager == null) return;

        mc.execute(() -> {
            if (autoPick.get()) {
                FindItemResult pick = InvUtils.findInHotbar(
                    Items.NETHERITE_PICKAXE, Items.DIAMOND_PICKAXE, Items.IRON_PICKAXE, Items.STONE_PICKAXE, Items.WOODEN_PICKAXE
                );

                if (pick.found()) {
                    if (originalSlot == -1) originalSlot = mc.player.getInventory().getSelectedSlot();
                    InvUtils.swap(pick.slot(), true);
                }
            }

            if (rotate.get()) {
                Rotations.rotate(Rotations.getYaw(Vec3d.ofCenter(pos)), Rotations.getPitch(Vec3d.ofCenter(pos)), 50, () -> {
                    mc.interactionManager.attackBlock(pos, Direction.UP);
                    mc.player.swingHand(Hand.MAIN_HAND);
                });
            } else {
                mc.interactionManager.attackBlock(pos, Direction.UP);
                mc.player.swingHand(Hand.MAIN_HAND);
            }
        });
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.interactionManager == null) return;
        if (mc.currentScreen != null) return;

        // If break mode is NONE, just do unwaxing
        if (breakMode.get() == BreakMode.NONE) {
            currentStage = 0;
            findTargetBlocks();

            if (targetBlocks.isEmpty()) return;

            BlockPos closest = targetBlocks.stream()
                .min((a, b) -> Double.compare(
                    mc.player.getBlockPos().getSquaredDistance(a),
                    mc.player.getBlockPos().getSquaredDistance(b)
                ))
                .orElse(null);

            if (closest == null) return;

            if (unwaxTimer >= unwaxDelay.get()) {
                unwaxTimer = 0;
                currentTarget = closest;
                unwaxBlock(currentTarget);
            } else {
                unwaxTimer++;
            }
            return;
        }

        // Handle both unwaxing and breaking
        if (currentStage == 0) {
            findTargetBlocks();

            if (targetBlocks.isEmpty()) {
                if (breakMode.get() != BreakMode.NONE) {
                    currentStage = 1;
                    unwaxTimer = 0;
                    breakTimer = 0;
                }
                return;
            }

            BlockPos closest = targetBlocks.stream()
                .min((a, b) -> Double.compare(
                    mc.player.getBlockPos().getSquaredDistance(a),
                    mc.player.getBlockPos().getSquaredDistance(b)
                ))
                .orElse(null);

            if (closest == null) return;

            if (unwaxTimer >= unwaxDelay.get()) {
                unwaxTimer = 0;
                currentTarget = closest;
                unwaxBlock(currentTarget);
            } else {
                unwaxTimer++;
            }
        } else if (currentStage == 1) {
            findTargetBlocks();

            if (targetBlocks.isEmpty()) {
                if (breakMode.get() == BreakMode.BREAK && currentTarget != null && isUnwaxedCopper(mc.world.getBlockState(currentTarget))) {
                    breakBlock(currentTarget);
                }
                currentStage = 0;
                return;
            }

            BlockPos closest = targetBlocks.stream()
                .min((a, b) -> Double.compare(
                    mc.player.getBlockPos().getSquaredDistance(a),
                    mc.player.getBlockPos().getSquaredDistance(b)
                ))
                .orElse(null);

            if (closest == null) return;

            if (breakTimer >= breakDelay.get()) {
                breakTimer = 0;
                breakBlock(closest);
            } else {
                breakTimer++;
            }
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!espEnabled.get() || mc.player == null || mc.world == null) return;
        if (targetBlocks.isEmpty()) return;

        int rangeSq = espRange.get() * espRange.get();

        for (BlockPos pos : targetBlocks) {
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
