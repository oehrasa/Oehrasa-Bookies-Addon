package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class KMDB extends Module {

    public enum BuildMode {Wither, IronGolem, SnowGolem, CopperGolem}

    private static final Direction[] AXIS_DIRECTIONS = {Direction.EAST, Direction.NORTH};

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgWither = settings.createGroup("Wither Settings");
    private final SettingGroup sgPlacement = settings.createGroup("Placement");
    private final SettingGroup sgCopper = settings.createGroup("Copper Golem");

    private final Setting<BuildMode> buildMode = sgGeneral.add(new EnumSetting.Builder<BuildMode>()
        .name("build-mode")
        .description("Which structure to build.")
        .defaultValue(BuildMode.Wither)
        .build()
    );

    private final Setting<Integer> placementDistance = sgPlacement.add(new IntSetting.Builder()
        .name("placement-distance")
        .description("Distance in front to place non‑Wither structures.")
        .defaultValue(3)
        .min(2)
        .max(6)
        .visible(() -> buildMode.get() != BuildMode.Wither)
        .build()
    );

    private final Setting<Integer> placementSearchRadius = sgPlacement.add(new IntSetting.Builder()
        .name("search-radius")
        .description("How far to search around the preferred spot for a clear location if it's obstructed.")
        .defaultValue(2)
        .min(0)
        .max(5)
        .visible(() -> buildMode.get() != BuildMode.Wither)
        .build()
    );

    private final Setting<Boolean> airPlace = sgPlacement.add(new BoolSetting.Builder()
        .name("air-place")
        .description("Use packet-based airplace to place structure blocks with nothing to click against - removes the need for solid ground beneath the base, and avoids the floating/detached-base gap that can happen without it.")
        .defaultValue(false)
        .visible(() -> buildMode.get() != BuildMode.Wither)
        .build()
    );

    private final Setting<Integer> witherHorizontalRadius = sgWither.add(new IntSetting.Builder()
        .name("horizontal-radius")
        .description("Horizontal search radius for valid Wither spawn positions.")
        .defaultValue(4)
        .min(0)
        .sliderMax(6)
        .visible(() -> buildMode.get() == BuildMode.Wither)
        .build()
    );

    private final Setting<Integer> witherVerticalRadius = sgWither.add(new IntSetting.Builder()
        .name("vertical-radius")
        .description("Vertical search radius for valid Wither spawn positions.")
        .defaultValue(3)
        .min(0)
        .sliderMax(6)
        .visible(() -> buildMode.get() == BuildMode.Wither)
        .build()
    );

    private final Setting<Boolean> witherRotate = sgWither.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotate to face the blocks when placing.")
        .defaultValue(true)
        .visible(() -> buildMode.get() == BuildMode.Wither)
        .build()
    );

    private final Setting<Integer> witherPlaceDelay = sgWither.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Delay between placing Wither blocks (ticks).")
        .defaultValue(1)
        .min(0)
        .sliderRange(0, 10)
        .visible(() -> buildMode.get() == BuildMode.Wither)
        .build()
    );

    private final Setting<Boolean> witherAutoToggle = sgWither.add(new BoolSetting.Builder()
        .name("auto-toggle")
        .description("Automatically disable after building one Wither.")
        .defaultValue(true)
        .visible(() -> buildMode.get() == BuildMode.Wither)
        .build()
    );

    private final Setting<Item> copperBlock = sgCopper.add(new ItemSetting.Builder()
        .name("copper-block")
        .description("The copper block variant to use for the base.")
        .defaultValue(Blocks.COPPER_BLOCK.weathering().pick(WeatheringCopper.WeatherState.UNAFFECTED).asItem())
        .visible(() -> buildMode.get() == BuildMode.CopperGolem)
        .build()
    );

    private final Setting<Item> pumpkinType = sgCopper.add(new ItemSetting.Builder()
        .name("pumpkin-type")
        .description("The carved pumpkin or jack‑o‑lantern to place on top.")
        .defaultValue(Items.CARVED_PUMPKIN)
        .filter(item -> item == Items.CARVED_PUMPKIN || item == Items.JACK_O_LANTERN)
        .visible(() -> buildMode.get() == BuildMode.CopperGolem)
        .build()
    );

    private final Setting<Boolean> skipIfOccupied = sgCopper.add(new BoolSetting.Builder()
        .name("skip-if-occupied")
        .description("Skip building if the foot position already contains a block (e.g., copper chest).")
        .defaultValue(true)
        .visible(() -> buildMode.get() == BuildMode.CopperGolem)
        .build()
    );

    private final Setting<Boolean> renderPreview = sgGeneral.add(new BoolSetting.Builder()
        .name("render-preview")
        .description("Show a preview of the structure.")
        .defaultValue(true)
        .build()
    );
    private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .defaultValue(ShapeMode.Both)
        .build()
    );
    private final Setting<SettingColor> soulSandColor = sgGeneral.add(new ColorSetting.Builder()
        .name("soul-sand-color")
        .defaultValue(new SettingColor(139, 69, 19))
        .visible(() -> renderPreview.get() && buildMode.get() == BuildMode.Wither)
        .build()
    );
    private final Setting<SettingColor> skullColor = sgGeneral.add(new ColorSetting.Builder()
        .name("skull-color")
        .defaultValue(new SettingColor(200, 200, 200))
        .visible(() -> renderPreview.get() && buildMode.get() == BuildMode.Wither)
        .build()
    );
    private final Setting<SettingColor> golemColor = sgGeneral.add(new ColorSetting.Builder()
        .name("golem-color")
        .defaultValue(new SettingColor(255, 255, 255, 150))
        .visible(() -> renderPreview.get() && buildMode.get() != BuildMode.Wither)
        .build()
    );

    private static class Wither {
        public int stage;
        public BlockPos.MutableBlockPos foot = new BlockPos.MutableBlockPos();
        public Direction facing;
        public Direction.Axis axis;

        public Wither set(BlockPos pos, Direction dir) {
            this.stage = 0;
            this.foot.set(pos);
            this.facing = dir;
            this.axis = dir.getAxis();
            return this;
        }
    }

    private Wither currentWither;
    private int witherTicksWaited;
    private int blockTicksWaited;

    public KMDB() {
        super(Addon.CATEGORY, "KMDB", "Builds Wither, Iron Golem, Snow Golem, or Copper Golem automatically.");
    }

    @Override
    public void onActivate() {
        switch (buildMode.get()) {
            case Wither -> startWither();
            case IronGolem -> buildIronGolem();
            case SnowGolem -> buildSnowGolem();
            case CopperGolem -> buildCopperGolem();
        }
        // Toggle off instantly for non Wither modes
        if (buildMode.get() != BuildMode.Wither) toggle();
    }

    @Override
    public void onDeactivate() {
        currentWither = null;
    }

    private void startWither() {
        currentWither = null;
        witherTicksWaited = 0;
        blockTicksWaited = 0;
    }

    private Wither findValidWitherSpawn() {
        int hRadius = witherHorizontalRadius.get();
        int vRadius = witherVerticalRadius.get();
        BlockPos playerPos = mc.player.blockPosition();

        List<Wither> candidates = new ArrayList<>();
        for (int y = -vRadius; y <= vRadius; y++) {
            for (int x = -hRadius; x <= hRadius; x++) {
                for (int z = -hRadius; z <= hRadius; z++) {
                    BlockPos pos = playerPos.offset(x, y, z);
                    for (Direction axisDir : AXIS_DIRECTIONS) {
                        if (isValidWitherSpawn(pos, axisDir)) {
                            candidates.add(new Wither().set(pos, axisDir));
                            break; // this spot works on one axis, no need to test the other
                        }
                    }
                }
            }
        }

        if (candidates.isEmpty()) return null;
        candidates.sort(Comparator.comparingDouble(w -> PlayerUtils.distanceTo(w.foot)));
        return candidates.get(0);
    }

    private boolean isValidWitherSpawn(BlockPos blockPos, Direction axisDirection) {
        if (blockPos.getY() > 252) return false;

        int widthX = 0, widthZ = 0;
        if (axisDirection == Direction.EAST || axisDirection == Direction.WEST) widthX = 1;
        else widthZ = 1;

        BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
        for (int x = blockPos.getX() - widthX; x <= blockPos.getX() + widthX; x++) {
            for (int z = blockPos.getZ() - widthZ; z <= blockPos.getZ() + widthZ; z++) {
                for (int y = blockPos.getY(); y <= blockPos.getY() + 2; y++) {
                    bp.set(x, y, z);
                    BlockState state = mc.level.getBlockState(bp);
                    if (!state.canBeReplaced()) return false;
                    if (!mc.level.isUnobstructed(Blocks.STONE.defaultBlockState(), bp, CollisionContext.empty())) return false;
                }
            }
        }
        return true;
    }

    private BlockPos findClearFootPosition(List<int[]> relativeOffsets, int searchRadius) {
        Direction facing = mc.player.getDirection();
        BlockPos preferred = mc.player.blockPosition().relative(facing, placementDistance.get());

        List<BlockPos> candidates = new ArrayList<>();
        for (int x = -searchRadius; x <= searchRadius; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -searchRadius; z <= searchRadius; z++) {
                    BlockPos pos = preferred.offset(x, y, z);

                    if (!airPlace.get()) {
                        BlockState belowState = mc.level.getBlockState(pos.below());
                        if (belowState.isAir() || belowState.canBeReplaced()) continue;
                    }

                    boolean clear = true;
                    for (int[] rel : relativeOffsets) {
                        BlockPos check = pos.offset(rel[0], rel[1], rel[2]);
                        BlockState state = mc.level.getBlockState(check);
                        // canPlace() checks entity collisions
                        if (!state.isAir() || !mc.level.isUnobstructed(Blocks.STONE.defaultBlockState(), check, CollisionContext.empty())) {
                            clear = false;
                            break;
                        }
                    }
                    if (clear) candidates.add(pos);
                }
            }
        }

        if (candidates.isEmpty()) return null;
        candidates.sort(Comparator.comparingDouble(PlayerUtils::distanceTo));
        return candidates.get(0);
    }

    private List<int[]> ironGolemFootprint(Direction.Axis axis) {
        int dx = axis == Direction.Axis.X ? 1 : 0;
        int dz = axis == Direction.Axis.Z ? 1 : 0;
        return List.of(
            new int[]{0, 0, 0},
            new int[]{0, 1, 0},
            new int[]{-dx, 1, -dz},
            new int[]{dx, 1, dz},
            new int[]{0, 2, 0}
        );
    }

    private List<int[]> snowGolemFootprint() {
        return List.of(
            new int[]{0, 0, 0},
            new int[]{0, 1, 0},
            new int[]{0, 2, 0}
        );
    }

    private List<int[]> copperGolemFootprint() {
        return List.of(
            new int[]{0, 0, 0},
            new int[]{0, 1, 0}
        );
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!renderPreview.get()) return;

        switch (buildMode.get()) {
            case Wither -> {
                if (currentWither != null) {
                    BlockPos foot = currentWither.foot;
                    Direction.Axis axis = currentWither.axis;

                    event.renderer.box(foot, soulSandColor.get(), soulSandColor.get(), shapeMode.get(), 0);
                    event.renderer.box(foot.above(), soulSandColor.get(), soulSandColor.get(), shapeMode.get(), 0);
                    event.renderer.box(foot.above().relative(axis, -1), soulSandColor.get(), soulSandColor.get(), shapeMode.get(), 0);
                    event.renderer.box(foot.above().relative(axis, 1), soulSandColor.get(), soulSandColor.get(), shapeMode.get(), 0);

                    BlockPos midHead = foot.above(2);
                    BlockPos leftHead = midHead.relative(axis, -1);
                    BlockPos rightHead = midHead.relative(axis, 1);
                    renderSkullBox(event, midHead);
                    renderSkullBox(event, leftHead);
                    renderSkullBox(event, rightHead);
                }
            }
            case IronGolem -> {
                Direction facing = mc.player.getDirection();
                Direction.Axis axis = facing.getAxis();
                BlockPos foot = findClearFootPosition(ironGolemFootprint(axis), placementSearchRadius.get());
                if (foot != null) {
                    int dx = axis == Direction.Axis.X ? 1 : 0;
                    int dz = axis == Direction.Axis.Z ? 1 : 0;

                    event.renderer.box(foot, golemColor.get(), golemColor.get(), shapeMode.get(), 0);
                    event.renderer.box(foot.above(), golemColor.get(), golemColor.get(), shapeMode.get(), 0);
                    event.renderer.box(foot.offset(-dx, 1, -dz), golemColor.get(), golemColor.get(), shapeMode.get(), 0);
                    event.renderer.box(foot.offset(dx, 1, dz), golemColor.get(), golemColor.get(), shapeMode.get(), 0);
                    event.renderer.box(foot.above(2), golemColor.get(), golemColor.get(), shapeMode.get(), 0);
                }
            }
            case SnowGolem -> {
                BlockPos base = findClearFootPosition(snowGolemFootprint(), placementSearchRadius.get());
                if (base != null) {
                    event.renderer.box(base, golemColor.get(), golemColor.get(), shapeMode.get(), 0);
                    event.renderer.box(base.above(), golemColor.get(), golemColor.get(), shapeMode.get(), 0);
                    event.renderer.box(base.above(2), golemColor.get(), golemColor.get(), shapeMode.get(), 0);
                }
            }
            case CopperGolem -> {
                BlockPos foot = findClearFootPosition(copperGolemFootprint(), placementSearchRadius.get());
                if (foot != null) {
                    event.renderer.box(foot, golemColor.get(), golemColor.get(), shapeMode.get(), 0);         // copper base
                    event.renderer.box(foot.above(), golemColor.get(), golemColor.get(), shapeMode.get(), 0);    // pumpkin
                }
            }
        }
    }

    private void place(BlockPos pos, FindItemResult item) {
        if (airPlace.get()) {
            airPlaceBlock(pos, item);
        } else {
            BlockUtils.place(pos, item, 0, false);
        }
    }

    private void airPlaceBlock(BlockPos pos, FindItemResult item) {
        if (!item.found()) return;

        int previousSlot = mc.player.getInventory().getSelectedSlot();
        mc.player.getInventory().setSelectedSlot(item.slot());
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(item.slot()));

        BlockHitResult bhr = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        int currentRevision = mc.player.containerMenu.getStateId();

        mc.player.connection.send(new ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));

        mc.player.connection.send(new ServerboundUseItemOnPacket(
            InteractionHand.OFF_HAND, bhr, currentRevision));

        // Swap back
        mc.player.connection.send(new ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));

        mc.player.swing(InteractionHand.MAIN_HAND);

        mc.player.getInventory().setSelectedSlot(previousSlot);
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(previousSlot));
    }

    private void renderSkullBox(Render3DEvent event, BlockPos pos) {
        event.renderer.box(pos.getX() + 0.2, pos.getY() + 0.2, pos.getZ() + 0.2,
            pos.getX() + 0.8, pos.getY() + 0.7, pos.getZ() + 0.8,
            skullColor.get(), skullColor.get(), shapeMode.get(), 0);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (buildMode.get() != BuildMode.Wither) return;

        if (currentWither == null) {
            if (witherTicksWaited < witherPlaceDelay.get()) {
                witherTicksWaited++;
                return;
            }
            currentWither = findValidWitherSpawn();
            if (currentWither == null) {
                error("No valid Wither spawn location found within range.");
                toggle();
                return;
            }
            witherTicksWaited = 0;
        }

        FindItemResult soulSand = InvUtils.findInHotbar(Items.SOUL_SAND);
        if (!soulSand.found()) soulSand = InvUtils.findInHotbar(Items.SOUL_SOIL);
        FindItemResult witherSkull = InvUtils.findInHotbar(Items.WITHER_SKELETON_SKULL);
        if (!soulSand.found() || !witherSkull.found()) {
            error("Not enough resources in hotbar.");
            toggle();
            return;
        }

        int delay = witherPlaceDelay.get();
        if (delay == 0) {
            for (int i = 0; i <= 6; i++) placeWitherBlock(soulSand, witherSkull, i);
            if (witherAutoToggle.get()) toggle();
        } else {
            if (blockTicksWaited < delay) {
                blockTicksWaited++;
                return;
            }
            blockTicksWaited = 0;
            if (currentWither.stage <= 6) {
                placeWitherBlock(soulSand, witherSkull, currentWither.stage);
                currentWither.stage++;
            }
            if (currentWither.stage > 6 && witherAutoToggle.get()) toggle();
        }
    }

    private void placeWitherBlock(FindItemResult soulSand, FindItemResult skull, int stage) {
        BlockPos pos = switch (stage) {
            case 0 -> currentWither.foot;
            case 1 -> currentWither.foot.above();
            case 2 -> currentWither.foot.above().relative(currentWither.axis, -1);
            case 3 -> currentWither.foot.above().relative(currentWither.axis, 1);
            case 4 -> currentWither.foot.above(2);
            case 5 -> currentWither.foot.above(2).relative(currentWither.axis, -1);
            case 6 -> currentWither.foot.above(2).relative(currentWither.axis, 1);
            default -> null;
        };
        if (pos == null) return;
        FindItemResult item = (stage < 4) ? soulSand : skull;
        if (witherRotate.get()) {
            Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), () -> BlockUtils.place(pos, item, 0, false));
        } else {
            BlockUtils.place(pos, item, 0, false);
        }
    }

    private void buildIronGolem() {
        Direction facing = mc.player.getDirection();
        Direction.Axis axis = facing.getAxis();
        int dx = axis == Direction.Axis.X ? 1 : 0;
        int dz = axis == Direction.Axis.Z ? 1 : 0;

        BlockPos foot = findClearFootPosition(ironGolemFootprint(axis), placementSearchRadius.get());
        if (foot == null) {
            error("Not enough clear space for an iron golem nearby.");
            return;
        }

        FindItemResult iron = InvUtils.findInHotbar(Items.IRON_BLOCK);
        if (!iron.found()) {
            error("No iron blocks in hotbar.");
            return;
        }
        FindItemResult pumpkin = InvUtils.findInHotbar(Items.CARVED_PUMPKIN);
        if (!pumpkin.found()) {
            error("No carved pumpkin in hotbar.");
            return;
        }

        place(foot, iron);
        place(foot.above(), iron);
        place(foot.offset(-dx, 1, -dz), iron);
        place(foot.offset(dx, 1, dz), iron);
        place(foot.above(2), pumpkin);
        info("Iron golem built.");
    }

    private void buildSnowGolem() {
        BlockPos base = findClearFootPosition(snowGolemFootprint(), placementSearchRadius.get());
        if (base == null) {
            error("Not enough clear space for a snow golem nearby.");
            return;
        }

        FindItemResult snow = InvUtils.findInHotbar(Items.SNOW_BLOCK);
        if (!snow.found()) {
            error("No snow blocks in hotbar.");
            return;
        }
        FindItemResult pumpkin = InvUtils.findInHotbar(Items.CARVED_PUMPKIN);
        if (!pumpkin.found()) {
            error("No carved pumpkin in hotbar.");
            return;
        }

        place(base, snow);
        place(base.above(), snow);
        place(base.above(2), pumpkin);
        info("Snow golem built.");
    }

    private void buildCopperGolem() {
        Direction facing = mc.player.getDirection();
        BlockPos preferredFoot = mc.player.blockPosition().relative(facing, placementDistance.get());

        BlockPos foot = findClearFootPosition(copperGolemFootprint(), placementSearchRadius.get());
        if (foot == null) {
            error("Not enough clear space for copper golem nearby.");
            return;
        }

        if (skipIfOccupied.get() && foot.equals(preferredFoot)
            && !mc.level.getBlockState(preferredFoot).isAir()) {
            warning("Foot position is already occupied, golem may already exist.");
            return;
        }

        FindItemResult copper = InvUtils.findInHotbar(copperBlock.get());
        if (!copper.found()) {
            error("No copper block in hotbar.");
            return;
        }
        FindItemResult pumpkin = InvUtils.findInHotbar(pumpkinType.get());
        if (!pumpkin.found()) {
            error("No suitable pumpkin in hotbar.");
            return;
        }

        place(foot, copper);
        place(foot.above(), pumpkin);
        info("Copper golem built.");
    }
}
