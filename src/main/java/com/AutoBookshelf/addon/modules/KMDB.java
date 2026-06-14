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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class KMDB extends Module {

    public enum BuildMode {Wither, IronGolem, SnowGolem}

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgWither = settings.createGroup("Wither Settings");
    private final SettingGroup sgPlacement = settings.createGroup("Placement");

    private final Setting<BuildMode> buildMode = sgGeneral.add(new EnumSetting.Builder<BuildMode>()
        .name("build-mode")
        .description("Which structure to build.")
        .defaultValue(BuildMode.Wither)
        .build()
    );

    private final Setting<Integer> placementDistance = sgPlacement.add(new IntSetting.Builder()
        .name("placement-distance")
        .description("Distance in front to place Iron/Snow Golems.")
        .defaultValue(3)
        .min(2)
        .max(6)
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
        public int stage;              // 0-7
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
        super(Addon.CATEGORY, "KMDB", "Builds a Wither, Iron Golem, or Snow Golem in front of you.");
    }

    @Override
    public void onActivate() {
        switch (buildMode.get()) {
            case Wither -> startWither();
            case IronGolem -> buildIronGolem();
            case SnowGolem -> buildSnowGolem();
        }
        if (buildMode.get() != BuildMode.Wither) toggle(); // instant builders
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
                    Direction dir = Direction.fromYRot(Rotations.getYaw(pos)).getOpposite();
                    if (isValidWitherSpawn(pos, dir)) {
                        candidates.add(new Wither().set(pos, dir));
                    }
                }
            }
        }

        if (candidates.isEmpty()) return null;
        candidates.sort(Comparator.comparingDouble(w -> PlayerUtils.distanceTo(w.foot)));
        return candidates.get(0);
    }

    private boolean isValidWitherSpawn(BlockPos blockPos, Direction direction) {
        if (blockPos.getY() > 252) return false;

        int widthX = 0, widthZ = 0;
        if (direction == Direction.EAST || direction == Direction.WEST) widthZ = 1;
        else widthX = 1;

        BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
        for (int x = blockPos.getX() - widthX; x <= blockPos.getX() + widthX; x++) {
            for (int z = blockPos.getZ() - widthZ; z <= blockPos.getZ(); z++) {
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
                BlockPos foot = mc.player.blockPosition().relative(facing, placementDistance.get());

                event.renderer.box(foot, golemColor.get(), golemColor.get(), shapeMode.get(), 0);   // foot
                event.renderer.box(foot.above(), golemColor.get(), golemColor.get(), shapeMode.get(), 0);  // centre body
                event.renderer.box(foot.above().relative(axis, -1), golemColor.get(), golemColor.get(), shapeMode.get(), 0); // left arm
                event.renderer.box(foot.above().relative(axis, 1), golemColor.get(), golemColor.get(), shapeMode.get(), 0);  // right arm
                event.renderer.box(foot.above(2), golemColor.get(), golemColor.get(), shapeMode.get(), 0); // pumpkin
            }
            case SnowGolem -> {
                BlockPos base = mc.player.blockPosition().relative(mc.player.getDirection(), placementDistance.get());
                event.renderer.box(base, golemColor.get(), golemColor.get(), shapeMode.get(), 0);
                event.renderer.box(base.above(), golemColor.get(), golemColor.get(), shapeMode.get(), 0);
                event.renderer.box(base.above(2), golemColor.get(), golemColor.get(), shapeMode.get(), 0);
            }
        }
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
            placeWitherBlock(soulSand, witherSkull, 0);
            placeWitherBlock(soulSand, witherSkull, 1);
            placeWitherBlock(soulSand, witherSkull, 2);
            placeWitherBlock(soulSand, witherSkull, 3);
            placeWitherBlock(witherSkull, witherSkull, 4);
            placeWitherBlock(witherSkull, witherSkull, 5);
            placeWitherBlock(witherSkull, witherSkull, 6);
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

        // Foot position
        BlockPos foot = mc.player.blockPosition().relative(facing, placementDistance.get());

        BlockPos[] ironPositions = {
            foot, // foot (Y=0)
            foot.above(), // centre body (Y=1)
            foot.above().relative(axis, -1), // left arm (Y=1)
            foot.above().relative(axis, 1) // right arm (Y=1)
        };

        // Pumpkin goes on top of the centre (Y=2)
        BlockPos pumpkinPos = foot.above(2);

        // Clearance check
        for (BlockPos pos : ironPositions) {
            if (!mc.level.getBlockState(pos).isAir()) {
                error("Not enough clear space in front of you for an iron golem.");
                return;
            }
        }
        if (!mc.level.getBlockState(pumpkinPos).isAir()) {
            error("Not enough clear space in front of you for the pumpkin.");
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

        // Place iron blocks
        for (BlockPos pos : ironPositions) {
            BlockUtils.place(pos, iron, 0, false);
        }
        BlockUtils.place(pumpkinPos, pumpkin, 0, false);

        info("Iron golem built.");
    }

    private void buildSnowGolem() {
        Direction facing = mc.player.getDirection();
        BlockPos base = mc.player.blockPosition().relative(facing, placementDistance.get());
        if (!mc.level.getBlockState(base).isAir() || !mc.level.getBlockState(base.above()).isAir() || !mc.level.getBlockState(base.above(2)).isAir()) {
            error("Not enough clear space for a snow golem.");
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

        BlockUtils.place(base, snow, 0, false);
        BlockUtils.place(base.above(), snow, 0, false);
        BlockUtils.place(base.above(2), pumpkin, 0, false);
        info("Snow golem built.");
    }
}
