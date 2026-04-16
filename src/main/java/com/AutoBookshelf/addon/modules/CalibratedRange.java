package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ComparatorBlock;
import net.minecraft.block.ObserverBlock;
import net.minecraft.block.SculkShriekerBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.HashSet;
import java.util.Set;

public class CalibratedRange extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private static final double SENSOR_RANGE = 16.0;

    // General settings
    private final Setting<Integer> renderDistance = sgGeneral.add(new IntSetting.Builder()
        .name("render-distance")
        .description("How far away to render spheres (blocks)")
        .defaultValue(64)
        .min(16)
        .max(128)
        .sliderRange(16, 128)
        .build()
    );

    private final Setting<Boolean> smartRendering = sgGeneral.add(new BoolSetting.Builder()
        .name("smart-rendering")
        .description("Render as circle when far, sphere when close")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> smartRenderDistance = sgGeneral.add(new IntSetting.Builder()
        .name("smart-render-distance")
        .description("Distance to switch from circle to sphere")
        .defaultValue(20)
        .min(5)
        .max(50)
        .visible(smartRendering::get)
        .build()
    );

    private final Setting<Boolean> advancedView = sgGeneral.add(new BoolSetting.Builder()
        .name("advanced-view")
        .description("Detect redstone connections and shriekers in range")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> onlyRenderImpactful = sgGeneral.add(new BoolSetting.Builder()
        .name("only-render-impactful")
        .description("Only render sensors that have redstone output or shriekers in range")
        .defaultValue(false)
        .visible(advancedView::get)
        .build()
    );

    // Render settings
    private final Setting<Boolean> occlusion = sgRender.add(new BoolSetting.Builder()
        .name("occlusion")
        .description("Only render sphere blocks that are visible (not inside other blocks)")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeType> shapeType = sgRender.add(new EnumSetting.Builder<ShapeType>()
        .name("shape-type")
        .description("What shape to render (when smart-rendering is off)")
        .defaultValue(ShapeType.Sphere)
        .visible(() -> !smartRendering.get())
        .build()
    );

    private final Setting<Double> circleThickness = sgRender.add(new DoubleSetting.Builder()
        .name("circle-thickness")
        .description("Thickness of circle outline")
        .defaultValue(0.08)
        .min(0.02)
        .max(0.5)
        .visible(() -> (!smartRendering.get() && shapeType.get() == ShapeType.Circle) || (smartRendering.get()))
        .build()
    );

    private final Setting<Integer> gradation = sgRender.add(new IntSetting.Builder()
        .name("gradation")
        .description("Sphere smoothness (higher = smoother but more lag)")
        .defaultValue(30)
        .min(8)
        .max(64)
        .visible(() -> (!smartRendering.get() && shapeType.get() == ShapeType.Sphere) || (smartRendering.get()))
        .build()
    );

    private final Setting<Boolean> renderAtPlayerHeight = sgRender.add(new BoolSetting.Builder()
        .name("render-circle-at-player-height")
        .description("Render circular range indicator at player height")
        .defaultValue(false)
        .visible(() -> !smartRendering.get() && shapeType.get() == ShapeType.Circle)
        .build()
    );

    private final Setting<SettingColor> sphereColor = sgRender.add(new ColorSetting.Builder()
        .name("sphere-color")
        .description("Color of the sphere outline")
        .defaultValue(new SettingColor(0, 255, 255, 200))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Color of the sphere lines")
        .defaultValue(new SettingColor(0, 255, 255, 255))
        .build()
    );

    private final Setting<SettingColor> redstoneColor = sgRender.add(new ColorSetting.Builder()
        .name("redstone-color")
        .description("Color for sensors with redstone output")
        .defaultValue(new SettingColor(255, 0, 0, 200))
        .visible(advancedView::get)
        .build()
    );

    private final Setting<SettingColor> shriekerColor = sgRender.add(new ColorSetting.Builder()
        .name("shrieker-color")
        .description("Color for sensors with shriekers in range")
        .defaultValue(new SettingColor(255, 165, 0, 200))
        .visible(advancedView::get)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the sphere is rendered")
        .defaultValue(ShapeMode.Lines)
        .build()
    );

    private final Setting<Boolean> showCenterBox = sgRender.add(new BoolSetting.Builder()
        .name("show-center-box")
        .description("Show a box at the sensor location")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> centerColor = sgRender.add(new ColorSetting.Builder()
        .name("center-color")
        .description("Color of the center box")
        .defaultValue(new SettingColor(255, 255, 255, 150))
        .build()
    );

    private Set<SensorData> sensors = new HashSet<>();
    private Set<BlockPos> sphereBlocks = new HashSet<>();
    private int tickCounter = 0;

    private static class SensorData {
        BlockPos pos;
        boolean hasRedstoneOutput;
        boolean hasShriekerInRange;

        SensorData(BlockPos pos, boolean hasRedstoneOutput, boolean hasShriekerInRange) {
            this.pos = pos;
            this.hasRedstoneOutput = hasRedstoneOutput;
            this.hasShriekerInRange = hasShriekerInRange;
        }
    }

    public CalibratedRange() {
        super(Addon.CATEGORY, "Cal-Range", "Shows the detection range of calibrated sculk sensors");
    }

    @Override
    public void onActivate() {
        sensors.clear();
        sphereBlocks.clear();
        tickCounter = 0;
    }

    @Override
    public void onDeactivate() {
        sensors.clear();
        sphereBlocks.clear();
    }

    private boolean hasRedstoneOutput(World world, BlockPos sensorPos) {
        for (Direction dir : Direction.values()) {
            BlockPos adjacentPos = sensorPos.offset(dir);
            BlockState state = world.getBlockState(adjacentPos);
            Block block = state.getBlock();

            if (block == Blocks.REDSTONE_WIRE) return true;

            if (block instanceof ComparatorBlock) {
                Direction facing = state.get(ComparatorBlock.FACING);
                if (adjacentPos.offset(facing.getOpposite()).equals(sensorPos)) return true;
            }

            if (block instanceof ObserverBlock) {
                Direction facing = state.get(ObserverBlock.FACING);
                if (adjacentPos.offset(facing).equals(sensorPos)) return true;
            }
        }
        return false;
    }

    private boolean hasShriekerInRange(World world, BlockPos sensorPos) {
        int range = 8;
        for (BlockPos checkPos : BlockPos.iterateOutwards(sensorPos, range, range, range)) {
            BlockState state = world.getBlockState(checkPos);
            if (state.getBlock() instanceof SculkShriekerBlock) return true;
        }
        return false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null) return;

        tickCounter++;
        if (tickCounter < 10) return;
        tickCounter = 0;

        sensors.clear();
        int playerX = mc.player.getBlockX();
        int playerZ = mc.player.getBlockZ();
        int renderDist = renderDistance.get();

        int minY = mc.world.getBottomY();
        int maxY = minY + mc.world.getHeight();

        for (int x = playerX - renderDist; x <= playerX + renderDist; x++) {
            for (int z = playerZ - renderDist; z <= playerZ + renderDist; z++) {
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (mc.world.getBlockState(pos).getBlock() == Blocks.CALIBRATED_SCULK_SENSOR) {
                        boolean hasOutput = advancedView.get() ? hasRedstoneOutput(mc.world, pos) : false;
                        boolean hasShrieker = advancedView.get() ? hasShriekerInRange(mc.world, pos) : false;
                        sensors.add(new SensorData(pos.toImmutable(), hasOutput, hasShrieker));
                    }
                }
            }
        }

        sphereBlocks.clear();
        for (SensorData sensor : sensors) {
            sphereBlocks.addAll(generateHollowSphere(sensor.pos, SENSOR_RANGE));
        }
    }

    private Set<BlockPos> generateHollowSphere(BlockPos center, double radius) {
        Set<BlockPos> positions = new HashSet<>();
        int r = (int) Math.ceil(radius);
        double invRadius = 1.0 / (radius + 0.5);

        for (int x = 0; x <= r; x++) {
            double xn = x * invRadius;
            double nextXn = (x + 1) * invRadius;

            for (int y = 0; y <= r; y++) {
                double yn = y * invRadius;
                double nextYn = (y + 1) * invRadius;

                for (int z = 0; z <= r; z++) {
                    double zn = z * invRadius;
                    double nextZn = (z + 1) * invRadius;

                    double distSq = xn * xn + yn * yn + zn * zn;
                    if (distSq > 1) {
                        if (z == 0) {
                            if (y == 0) break;
                            break;
                        }
                        break;
                    }

                    boolean isInterior = (nextXn * nextXn + yn * yn + zn * zn <= 1) &&
                                         (xn * xn + nextYn * nextYn + zn * zn <= 1) &&
                                         (xn * xn + yn * yn + nextZn * nextZn <= 1);

                    if (!isInterior) {
                        positions.add(center.add(x, y, z));
                        if (x != 0) positions.add(center.add(-x, y, z));
                        if (y != 0) positions.add(center.add(x, -y, z));
                        if (z != 0) positions.add(center.add(x, y, -z));
                        if (x != 0 && y != 0) positions.add(center.add(-x, -y, z));
                        if (x != 0 && z != 0) positions.add(center.add(-x, y, -z));
                        if (y != 0 && z != 0) positions.add(center.add(x, -y, -z));
                        if (x != 0 && y != 0 && z != 0) positions.add(center.add(-x, -y, -z));
                    }
                }
            }
        }
        return positions;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.world == null) return;

        for (SensorData sensor : sensors) {
            if (onlyRenderImpactful.get() && advancedView.get() && !sensor.hasRedstoneOutput && !sensor.hasShriekerInRange) {
                continue;
            }

            double distanceToSensor = mc.player.getPos().distanceTo(Vec3d.ofCenter(sensor.pos));
            boolean useSphere = smartRendering.get() ? distanceToSensor <= smartRenderDistance.get() : shapeType.get() == ShapeType.Sphere || shapeType.get() == ShapeType.Both;
            boolean useCircle = smartRendering.get() ? distanceToSensor > smartRenderDistance.get() : shapeType.get() == ShapeType.Circle || shapeType.get() == ShapeType.Both;

            SettingColor renderColor = sphereColor.get();
            if (advancedView.get()) {
                if (sensor.hasRedstoneOutput && sensor.hasShriekerInRange) {
                    renderColor = (System.currentTimeMillis() / 500 % 2 == 0) ? redstoneColor.get() : shriekerColor.get();
                } else if (sensor.hasRedstoneOutput) {
                    renderColor = redstoneColor.get();
                } else if (sensor.hasShriekerInRange) {
                    renderColor = shriekerColor.get();
                }
            }

            if (useSphere) {
                for (BlockPos pos : sphereBlocks) {
                    if (occlusion.get() && !mc.world.getBlockState(pos).isAir()) continue;
                    event.renderer.box(pos, renderColor, lineColor.get(), shapeMode.get(), 0);
                }
            }

            if (useCircle) {
                double renderY = sensor.pos.getY() + 0.5;
                if (renderAtPlayerHeight.get() && shapeType.get() == ShapeType.Circle) {
                    renderY = mc.player.getY();
                }
                renderCircle(event, SENSOR_RANGE, circleThickness.get(), sensor.pos.getX() + 0.5, renderY, sensor.pos.getZ() + 0.5, renderColor);
            }
        }

        if (showCenterBox.get()) {
            for (SensorData sensor : sensors) {
                event.renderer.box(sensor.pos, centerColor.get(), centerColor.get(), ShapeMode.Lines, 0);
            }
        }
    }

    private void renderCircle(Render3DEvent event, double radius, double thickness, double cx, double cy, double cz, SettingColor color) {
        final double maxSegmentLength = 0.2;
        int segments = (int) Math.ceil(2 * Math.PI * radius / maxSegmentLength);
        segments = Math.max(16, segments);

        Vec3d[] outerPts = new Vec3d[segments];
        Vec3d[] innerPts = new Vec3d[segments];

        double outerRadius = radius + thickness / 2.0;
        double innerRadius = Math.max(radius - thickness / 2.0, 0);

        for (int s = 0; s < segments; s++) {
            double angle = 2 * Math.PI * s / segments;
            double sin = Math.sin(angle);
            double cos = Math.cos(angle);

            outerPts[s] = new Vec3d(cx + sin * outerRadius, cy, cz + cos * outerRadius);
            innerPts[s] = new Vec3d(cx + sin * innerRadius, cy, cz + cos * innerRadius);
        }

        for (int s = 0; s < segments; s++) {
            int next = (s + 1) % segments;

            int outer1 = event.renderer.triangles.vec3(outerPts[s].x, outerPts[s].y, outerPts[s].z).color(color).next();
            int outer2 = event.renderer.triangles.vec3(outerPts[next].x, outerPts[next].y, outerPts[next].z).color(color).next();
            int inner1 = event.renderer.triangles.vec3(innerPts[s].x, innerPts[s].y, innerPts[s].z).color(color).next();
            int inner2 = event.renderer.triangles.vec3(innerPts[next].x, innerPts[next].y, innerPts[next].z).color(color).next();

            event.renderer.triangles.triangle(outer1, outer2, inner1);
            event.renderer.triangles.triangle(inner1, outer2, inner2);
        }
    }

    private enum ShapeType { Circle, Sphere, Both }
}
