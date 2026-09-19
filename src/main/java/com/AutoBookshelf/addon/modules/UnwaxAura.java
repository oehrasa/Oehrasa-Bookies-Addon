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
import net.minecraft.block.Oxidizable;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.CopperGolemEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

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
        NONE("None -> Just remove wax."),
        BREAK("Break -> Break the block after unwaxing."),
        BREAK_ALL("Break All -> Break all unwaxed copper blocks.");
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
        Blocks.WAXED_COPPER_BULB, Blocks.WAXED_EXPOSED_COPPER_BULB, Blocks.WAXED_WEATHERED_COPPER_BULB, Blocks.WAXED_OXIDIZED_COPPER_BULB,
        Blocks.WAXED_COPPER_GOLEM_STATUE, Blocks.WAXED_EXPOSED_COPPER_GOLEM_STATUE, Blocks.WAXED_WEATHERED_COPPER_GOLEM_STATUE, Blocks.WAXED_OXIDIZED_COPPER_GOLEM_STATUE
    );

    private static final Set<Block> OXIDIZED_WAXED = Set.of(
        Blocks.WAXED_OXIDIZED_COPPER, Blocks.WAXED_OXIDIZED_CUT_COPPER, Blocks.WAXED_OXIDIZED_CUT_COPPER_STAIRS,
        Blocks.WAXED_OXIDIZED_CUT_COPPER_SLAB, Blocks.WAXED_OXIDIZED_CHISELED_COPPER, Blocks.WAXED_OXIDIZED_COPPER_DOOR,
        Blocks.WAXED_OXIDIZED_COPPER_TRAPDOOR, Blocks.WAXED_OXIDIZED_COPPER_GRATE, Blocks.WAXED_OXIDIZED_COPPER_BULB,
        Blocks.WAXED_OXIDIZED_COPPER_GOLEM_STATUE
    );

    private static final Set<Block> WEATHERED_WAXED = Set.of(
        Blocks.WAXED_WEATHERED_COPPER, Blocks.WAXED_WEATHERED_CUT_COPPER, Blocks.WAXED_WEATHERED_CUT_COPPER_STAIRS,
        Blocks.WAXED_WEATHERED_CUT_COPPER_SLAB, Blocks.WAXED_WEATHERED_CHISELED_COPPER, Blocks.WAXED_WEATHERED_COPPER_DOOR,
        Blocks.WAXED_WEATHERED_COPPER_TRAPDOOR, Blocks.WAXED_WEATHERED_COPPER_GRATE, Blocks.WAXED_WEATHERED_COPPER_BULB,
        Blocks.WAXED_WEATHERED_COPPER_GOLEM_STATUE
    );

    private static final Set<Block> EXPOSED_WAXED = Set.of(
        Blocks.WAXED_EXPOSED_COPPER, Blocks.WAXED_EXPOSED_CUT_COPPER, Blocks.WAXED_EXPOSED_CUT_COPPER_STAIRS,
        Blocks.WAXED_EXPOSED_CUT_COPPER_SLAB, Blocks.WAXED_EXPOSED_CHISELED_COPPER, Blocks.WAXED_EXPOSED_COPPER_DOOR,
        Blocks.WAXED_EXPOSED_COPPER_TRAPDOOR, Blocks.WAXED_EXPOSED_COPPER_GRATE, Blocks.WAXED_EXPOSED_COPPER_BULB,
        Blocks.WAXED_EXPOSED_COPPER_GOLEM_STATUE
    );

    private static final Set<Block> UNAFFECTED_WAXED = Set.of(
        Blocks.WAXED_COPPER_BLOCK, Blocks.WAXED_CUT_COPPER, Blocks.WAXED_CUT_COPPER_STAIRS,
        Blocks.WAXED_CUT_COPPER_SLAB, Blocks.WAXED_CHISELED_COPPER, Blocks.WAXED_COPPER_DOOR,
        Blocks.WAXED_COPPER_TRAPDOOR, Blocks.WAXED_COPPER_GRATE, Blocks.WAXED_COPPER_BULB,
        Blocks.WAXED_COPPER_GOLEM_STATUE
    );

    // Unwaxed copper (plain + copper golem statue) grouped by oxidation level.
    private static final Set<Block> OXIDIZED_COPPER = Set.of(
        Blocks.OXIDIZED_COPPER, Blocks.OXIDIZED_CUT_COPPER, Blocks.OXIDIZED_CUT_COPPER_STAIRS,
        Blocks.OXIDIZED_CUT_COPPER_SLAB, Blocks.OXIDIZED_CHISELED_COPPER, Blocks.OXIDIZED_COPPER_DOOR,
        Blocks.OXIDIZED_COPPER_TRAPDOOR, Blocks.OXIDIZED_COPPER_GRATE, Blocks.OXIDIZED_COPPER_BULB,
        Blocks.OXIDIZED_COPPER_GOLEM_STATUE
    );

    private static final Set<Block> WEATHERED_COPPER = Set.of(
        Blocks.WEATHERED_COPPER, Blocks.WEATHERED_CUT_COPPER, Blocks.WEATHERED_CUT_COPPER_STAIRS,
        Blocks.WEATHERED_CUT_COPPER_SLAB, Blocks.WEATHERED_CHISELED_COPPER, Blocks.WEATHERED_COPPER_DOOR,
        Blocks.WEATHERED_COPPER_TRAPDOOR, Blocks.WEATHERED_COPPER_GRATE, Blocks.WEATHERED_COPPER_BULB,
        Blocks.WEATHERED_COPPER_GOLEM_STATUE
    );

    private static final Set<Block> EXPOSED_COPPER = Set.of(
        Blocks.EXPOSED_COPPER, Blocks.EXPOSED_CUT_COPPER, Blocks.EXPOSED_CUT_COPPER_STAIRS,
        Blocks.EXPOSED_CUT_COPPER_SLAB, Blocks.EXPOSED_CHISELED_COPPER, Blocks.EXPOSED_COPPER_DOOR,
        Blocks.EXPOSED_COPPER_TRAPDOOR, Blocks.EXPOSED_COPPER_GRATE, Blocks.EXPOSED_COPPER_BULB,
        Blocks.EXPOSED_COPPER_GOLEM_STATUE
    );

    private static final Set<Block> UNAFFECTED_COPPER = Set.of(
        Blocks.COPPER_BLOCK, Blocks.CUT_COPPER, Blocks.CUT_COPPER_STAIRS,
        Blocks.CUT_COPPER_SLAB, Blocks.CHISELED_COPPER, Blocks.COPPER_DOOR,
        Blocks.COPPER_TRAPDOOR, Blocks.COPPER_GRATE, Blocks.COPPER_BULB,
        Blocks.COPPER_GOLEM_STATUE
    );

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFilter = settings.createGroup("Filter");
    private final SettingGroup sgBreak = settings.createGroup("Break");
    private final SettingGroup sgESP = settings.createGroup("ESP Settings");

    private final Setting<Integer> unwaxDelay = sgGeneral.add(new IntSetting.Builder()
        .name("unwax-delay")
        .description("Ticks after unwaxing before checking result.")
        .defaultValue(10)
        .min(1)
        .max(40)
        .sliderRange(1, 40)
        .build());

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("Reach distance in blocks.")
        .defaultValue(4)
        .min(1)
        .max(6)
        .sliderRange(1, 6)
        .build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotate towards the block before acting.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> autoTool = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-tool")
        .description("Auto switch to an axe for unwaxing.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> returnSlot = sgGeneral.add(new BoolSetting.Builder()
        .name("return-slot")
        .description("Return to original hotbar slot after tools.")
        .defaultValue(true)
        .build());

    private final Setting<BreakMode> breakMode = sgBreak.add(new EnumSetting.Builder<BreakMode>()
        .name("break-mode")
        .description("What to do after unwaxing.")
        .defaultValue(BreakMode.NONE)
        .build());

    private final Setting<Integer> breakDelay = sgBreak.add(new IntSetting.Builder()
        .name("break-delay")
        .description("Ticks after breaking before scanning again.")
        .defaultValue(10)
        .min(1)
        .max(40)
        .sliderRange(1, 40)
        .visible(() -> breakMode.get() != BreakMode.NONE)
        .build());

    private final Setting<Boolean> autoPick = sgBreak.add(new BoolSetting.Builder()
        .name("auto-pick")
        .description("Auto switch to a pickaxe for breaking.")
        .defaultValue(true)
        .visible(() -> breakMode.get() != BreakMode.NONE)
        .build());

    private final Setting<CopperFilter> copperFilter = sgFilter.add(new EnumSetting.Builder<CopperFilter>()
        .name("copper-filter")
        .description("Which waxed copper to target.")
        .defaultValue(CopperFilter.ALL)
        .build());

    private final Setting<Oxidizable.OxidationLevel> targetOxidation = sgFilter.add(new EnumSetting.Builder<Oxidizable.OxidationLevel>()
        .name("target-oxidation")
        .description("Scrape copper golems (and, when strip-oxidation is on, unwaxed copper) down to this oxidation level with an axe.")
        .defaultValue(Oxidizable.OxidationLevel.UNAFFECTED)
        .build());

    private final Setting<Boolean> stripOxidation = sgFilter.add(new BoolSetting.Builder()
        .name("strip-oxidation")
        .description("Also scrape down already-unwaxed oxidized copper blocks, statues and golem entities to the target oxidation level.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> espEnabled = sgESP.add(new BoolSetting.Builder()
        .name("ESP-enabled")
        .description("Highlight waxed copper blocks.")
        .defaultValue(true)
        .build());
    private final Setting<Integer> espRange = sgESP.add(new IntSetting.Builder()
        .name("ESP-range")
        .description("Max render distance for ESP.")
        .defaultValue(64)
        .min(8)
        .max(256)
        .sliderRange(8, 256)
        .visible(espEnabled::get)
        .build());

    private final Setting<ShapeMode> shapeMode = sgESP.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("ESP shape mode.")
        .defaultValue(ShapeMode.Lines)
        .visible(espEnabled::get)
        .build());

    private final Setting<SettingColor> sideColor = sgESP.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Side color of ESP box.")
        .defaultValue(new SettingColor(255, 200, 0, 40)).visible(espEnabled::get)
        .build());

    private final Setting<SettingColor> lineColor = sgESP.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Line color of ESP box.")
        .defaultValue(new SettingColor(255, 200, 0, 200)).visible(espEnabled::get)
        .build());

    private final Setting<Boolean> tracer = sgESP.add(new BoolSetting.Builder()
        .name("tracer")
        .description("Draw line to target.")
        .defaultValue(true).visible(espEnabled::get)
        .build());

    private final Setting<SettingColor> tracerColor = sgESP.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("Tracer line color.")
        .defaultValue(new SettingColor(255, 200, 0, 200))
        .visible(() -> espEnabled.get() && tracer.get())
        .build());
    // Honestly overlooked this thing
    private enum Stage { SCAN, ROTATE_UNWAX, UNWAX, WAIT_UNWAX, ROTATE_BREAK, START_BREAK, BREAKING, WAIT_BREAK }
    private Stage stage = Stage.SCAN;
    private BlockPos currentTarget = null;
    private Entity currentEntityTarget = null;
    private final Set<UUID> pokedGolems = new HashSet<>();
    private int rotTimer = 0;
    private int actionTimer = 0;
    private boolean breakingStarted = false;
    private int originalSlot = -1;
    private boolean lookingForUnwaxed = false;

    public UnwaxAura() {
        super(Addon.CATEGORY2, "Unwax-Aura", "Automatically removes wax from copper blocks and optionally breaks them.");
    }

    @Override
    public void onActivate() {
        stage = Stage.SCAN;
        currentTarget = null;
        currentEntityTarget = null;
        pokedGolems.clear();
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
        currentEntityTarget = null;
        pokedGolems.clear();
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
        return copperOxidationLevel(state.getBlock()) != -1;
    }

    // Returns the oxidation ordinal (0=unaffected .. 3=oxidized) for an
    // UNWAXED copper block/statue, or -1 when it isn't relevant.
    private static int copperOxidationLevel(Block block) {
        if (OXIDIZED_COPPER.contains(block)) return 3;
        if (WEATHERED_COPPER.contains(block)) return 2;
        if (EXPOSED_COPPER.contains(block)) return 1;
        if (UNAFFECTED_COPPER.contains(block)) return 0;
        return -1;
    }

    private boolean needsStripping(Block block) {
        return stripOxidation.get() && copperOxidationLevel(block) > targetOxidation.get().ordinal();
    }

    private boolean isBlockTarget(BlockState state) {
        Block block = state.getBlock();
        if (lookingForUnwaxed) {
            return isUnwaxedCopper(state) && !needsStripping(block);
        }
        return isWaxedCopper(state) || needsStripping(block);
    }

    // The client can't see a copper golem's "waxed" flag (next_weather_age is a
    // server-only field), so it works from the synced oxidation level instead:
    //  > if strip-oxidation is on and the golem is more oxidized than the target,
    //    keep scraping it down with the axe;
    //  > otherwise, if it sits exactly at the target level, poke it once with an
    //    axe in case it still holds wax (the wax-off branch runs before scrape).
    private boolean isValidGolemTarget(CopperGolemEntity golem) {
        if (!golem.isAlive()) return false;
        int level = golem.getOxidationLevel().ordinal();
        int target = targetOxidation.get().ordinal();
        if (stripOxidation.get() && level > target) return true;
        return level == target && !pokedGolems.contains(golem.getUuid());
    }

    private CopperGolemEntity findGolem() {
        CopperGolemEntity best = null;
        double bestDistSq = Double.MAX_VALUE;
        int r = range.get();
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof CopperGolemEntity golem)) continue;
            if (!isValidGolemTarget(golem)) continue;
            double distSq = mc.player.squaredDistanceTo(golem);
            if (distSq <= r * r && distSq < bestDistSq) {
                bestDistSq = distSq;
                best = golem;
            }
        }
        return best;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.interactionManager == null) return;
        if (mc.currentScreen != null) return;

        if (rotTimer > 0) {
            if (rotate.get()) {
                Vec3d vec = currentTarget != null ? Vec3d.ofCenter(currentTarget)
                    : (currentEntityTarget != null ? currentEntityTarget.getEyePos() : null);
                if (vec != null) {
                    Rotations.rotate(Rotations.getYaw(vec), Rotations.getPitch(vec), 100);
                }
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
                if (currentTarget == null && currentEntityTarget == null) {
                    stage = Stage.SCAN;
                    return;
                }
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
            if (!isBlockTarget(state)) continue;
            double dist = pos.getSquaredDistance(playerPos);
            if (dist < closestDistSq) {
                closestDistSq = dist;
                closest = pos.toImmutable();
            }
        }
        if (closest != null) {
            currentTarget = closest;
            currentEntityTarget = null;
            if (lookingForUnwaxed) {
                stage = Stage.ROTATE_BREAK;
            } else {
                stage = Stage.ROTATE_UNWAX;
            }
            rotTimer = rotate.get() ? 8 : 0;
            return;
        }
        if (!lookingForUnwaxed) {
            CopperGolemEntity golem = findGolem();
            if (golem != null) {
                currentTarget = null;
                currentEntityTarget = golem;
                stage = Stage.ROTATE_UNWAX;
                rotTimer = rotate.get() ? 8 : 0;
                return;
            }
        }
        if (lookingForUnwaxed && breakMode.get() == BreakMode.BREAK_ALL) {
            lookingForUnwaxed = false;
            stage = Stage.SCAN;
        }
    }

    private void performUnwax() {
        if (currentEntityTarget != null) {
            performEntityUnwax();
            return;
        }
        if (currentTarget == null) { stage = Stage.SCAN; return; }
        BlockState state = mc.world.getBlockState(currentTarget);
        if (!isWaxedCopper(state) && !needsStripping(state.getBlock())) {
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

    private void performEntityUnwax() {
        if (!(currentEntityTarget instanceof CopperGolemEntity golem) || !golem.isAlive() || !isValidGolemTarget(golem)) {
            currentEntityTarget = null;
            stage = Stage.SCAN;
            return;
        }
        if (autoTool.get()) {
            FindItemResult axe = InvUtils.findInHotbar(
                Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE, Items.STONE_AXE, Items.WOODEN_AXE);
            if (axe.found()) {
                if (originalSlot == -1) originalSlot = mc.player.getInventory().getSelectedSlot();
                InvUtils.swap(axe.slot(), true);
            }
        }
        mc.interactionManager.interactEntity(mc.player, golem, Hand.MAIN_HAND);
        mc.player.swingHand(Hand.MAIN_HAND);
        stage = Stage.WAIT_UNWAX;
        actionTimer = unwaxDelay.get();
    }

    private void afterUnwax() {
        if (currentEntityTarget != null) {
            afterEntityUnwax();
            return;
        }
        if (currentTarget == null) { stage = Stage.SCAN; return; }
        BlockState state = mc.world.getBlockState(currentTarget);
        if (isWaxedCopper(state) || needsStripping(state.getBlock())) {
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

    private void afterEntityUnwax() {
        if (!(currentEntityTarget instanceof CopperGolemEntity golem) || !golem.isAlive()) {
            currentEntityTarget = null;
            stage = Stage.SCAN;
            return;
        }
        int level = golem.getOxidationLevel().ordinal();
        int target = targetOxidation.get().ordinal();
        if (stripOxidation.get() && level > target) {
            stage = Stage.ROTATE_UNWAX;
            rotTimer = rotate.get() ? 5 : 0;
            return;
        }
        pokedGolems.add(golem.getUuid());
        currentEntityTarget = null;
        stage = Stage.SCAN;
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
        int rangeSq = espRange.get() * espRange.get();
        Set<BlockPos> blocks = new HashSet<>();
        if (currentTarget != null) {
            blocks.add(currentTarget);
        } else if (stage == Stage.SCAN) {
            int r = range.get();
            BlockPos playerPos = mc.player.getBlockPos();
            for (BlockPos pos : BlockPos.iterateOutwards(playerPos, r, r, r)) {
                if (isBlockTarget(mc.world.getBlockState(pos))) {
                    blocks.add(pos.toImmutable());
                }
            }
        }
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
        Set<CopperGolemEntity> golems = new HashSet<>();
        if (currentEntityTarget instanceof CopperGolemEntity current) {
            golems.add(current);
        } else if (stage == Stage.SCAN) {
            for (Entity entity : mc.world.getEntities()) {
                if (entity instanceof CopperGolemEntity golem && isValidGolemTarget(golem)) {
                    golems.add(golem);
                }
            }
        }
        for (CopperGolemEntity golem : golems) {
            if (mc.player.squaredDistanceTo(golem) > rangeSq) continue;
            Box box = golem.getBoundingBox();
            event.renderer.box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            if (tracer.get()) {
                event.renderer.line(
                    RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
                    golem.getX(), golem.getY() + golem.getHeight() / 2, golem.getZ(),
                    tracerColor.get()
                );
            }
        }
    }
}
