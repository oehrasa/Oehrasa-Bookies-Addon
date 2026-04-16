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
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.Set;

public class CalibratedRange extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private static final double CALIBRATED_RANGE = 16.0;  // Exact range from Minecraft code
    private static final double REGULAR_RANGE = 8.0;

    // General settings
    private final Setting<Integer> renderDistance = sgGeneral.add(new IntSetting.Builder()
        .name("render-distance")
        .description("How far away to render indicators (blocks)")
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

    private final Setting<Boolean> showDirectionalBias = sgGeneral.add(new BoolSetting.Builder()
        .name("show-directional-bias")
        .description("Show the directional sensitivity bias of calibrated sensors")
        .defaultValue(true)
        .visible(advancedView::get)
        .build()
    );

    // Render settings
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
        .build()
    );

    private final Setting<Integer> gradation = sgRender.add(new IntSetting.Builder()
        .name("gradation")
        .description("Sphere smoothness (higher = smoother but more lag)")
        .defaultValue(30)
        .min(8)
        .max(64)
        .build()
    );

    private final Setting<Boolean> renderAtPlayerHeight = sgRender.add(new BoolSetting.Builder()
        .name("render-circle-at-player-height")
        .description("Render circular range indicator at player height")
        .defaultValue(false)
        .visible(() -> shapeType.get() == ShapeType.Circle)
        .build()
    );

    private final Setting<SettingColor> sphereColor = sgRender.add(new ColorSetting.Builder()
        .name("sphere-color")
        .description("Color of the sphere outline")
        .defaultValue(new SettingColor(0, 255, 255, 100))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Color of the sphere lines")
        .defaultValue(new SettingColor(0, 255, 255, 200))
        .build()
    );

    private final Setting<SettingColor> redstoneColor = sgRender.add(new ColorSetting.Builder()
        .name("redstone-color")
        .description("Color for sensors with redstone output")
        .defaultValue(new SettingColor(255, 0, 0, 150))
        .visible(advancedView::get)
        .build()
    );

    private final Setting<SettingColor> shriekerColor = sgRender.add(new ColorSetting.Builder()
        .name("shrieker-color")
        .description("Color for sensors with shriekers in range")
        .defaultValue(new SettingColor(255, 165, 0, 150))
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
        Direction facing;
        boolean hasRedstoneOutput;
        boolean hasShriekerInRange;

        SensorData(BlockPos pos, Direction facing, boolean hasRedstoneOutput, boolean hasShriekerInRange) {
            this.pos = pos;
            this.facing = facing;
            this.hasRedstoneOutput = hasRedstoneOutput;
            this.hasShriekerInRange = hasShriekerInRange;
        }
    }

    public CalibratedRange() {
        super(Addon.CATEGORY, "Calibrated-Range", "Shows the detection range of calibrated sculk sensors");
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

    private boolean hasRedstoneOutput(BlockPos sensorPos) {
        for (Direction dir : Direction.values()) {
            BlockPos adjacentPos = sensorPos.offset(dir);
            BlockState state = mc.world.getBlockState(adjacentPos);
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

    private boolean hasShriekerInRange(BlockPos sensorPos) {
        int range = 16; // Calibrated sensors can detect shriekers up to 16 blocks
        for (BlockPos checkPos : BlockPos.iterateOutwards(sensorPos, range, range, range)) {
            BlockState state = mc.world.getBlockState(checkPos);
            if (state.getBlock() instanceof SculkShriekerBlock) return true;
        }
        return false;
    }

    // Generate accurate sphere blocks based on actual Minecraft detection logic
    private Set<BlockPos> generateDetectionSphere(BlockPos center, double radius) {
        Set<BlockPos> positions = new HashSet<>();
        int radiusCeil = (int) Math.ceil(radius);
        double radiusSq = radius * radius;

        // Use exact block position checking like Minecraft does
        for (int x = -radiusCeil; x <= radiusCeil; x++) {
            for (int y = -radiusCeil; y <= radiusCeil; y++) {
                for (int z = -radiusCeil; z <= radiusCeil; z++) {
                    BlockPos checkPos = center.add(x, y, z);
                    // Check distance from the CENTER of the sensor block
                    double dx = checkPos.getX() + 0.5 - (center.getX() + 0.5);
                    double dy = checkPos.getY() + 0.5 - (center.getY() + 0.5);
                    double dz = checkPos.getZ() + 0.5 - (center.getZ() + 0.5);
                    double distSq = dx * dx + dy * dy + dz * dz;

                    if (distSq <= radiusSq) {
                        positions.add(checkPos);
                    }
                }
            }
        }
        return positions;
    }

    // Check if a position is within the sensor's detection range (accurate to Minecraft)
    private boolean isInDetectionRange(BlockPos sensorPos, BlockPos targetPos, Direction facing) {
        Vec3d sensorCenter = new Vec3d(sensorPos.getX() + 0.5, sensorPos.getY() + 0.5, sensorPos.getZ() + 0.5);
        Vec3d targetCenter = new Vec3d(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);

        double distance = sensorCenter.distanceTo(targetCenter);

        // Base range check
        if (distance > CALIBRATED_RANGE) return false;

        // Directional bias for calibrated sensors (they are more sensitive in facing direction)
        if (showDirectionalBias.get() && facing != null) {
            Vec3d directionVec = getDirectionVector(facing);
            Vec3d toTarget = targetCenter.subtract(sensorCenter).normalize();
            double dot = directionVec.dotProduct(toTarget);

            // If facing away from the target, detection is less likely
            // (This is simplified - actual Minecraft code has more complex logic)
            if (dot < -0.5) return false; // Behind the sensor, less sensitive
        }

        return true;
    }

    private Vec3d getDirectionVector(Direction facing) {
        return switch (facing) {
            case NORTH -> new Vec3d(0, 0, -1);
            case SOUTH -> new Vec3d(0, 0, 1);
            case WEST -> new Vec3d(-1, 0, 0);
            case EAST -> new Vec3d(1, 0, 0);
            default -> new Vec3d(0, 0, 1);
        };
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null) return;

        tickCounter++;
        if (tickCounter < 10) return;
        tickCounter = 0;

        sensors.clear();
        sphereBlocks.clear();

        int playerX = mc.player.getBlockX();
        int playerZ = mc.player.getBlockZ();
        int renderDist = renderDistance.get();

        int minY = mc.world.getBottomY();
        int maxY = minY + mc.world.getHeight();

        for (int x = playerX - renderDist; x <= playerX + renderDist; x++) {
            for (int z = playerZ - renderDist; z <= playerZ + renderDist; z++) {
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = mc.world.getBlockState(pos);
                    if (state.getBlock() == Blocks.CALIBRATED_SCULK_SENSOR) {
                        Direction facing = state.contains(Properties.HORIZONTAL_FACING) ?
                            state.get(Properties.HORIZONTAL_FACING) : Direction.SOUTH;
                        boolean hasOutput = advancedView.get() ? hasRedstoneOutput(pos) : false;
                        boolean hasShrieker = advancedView.get() ? hasShriekerInRange(pos) : false;
                        SensorData sensor = new SensorData(pos.toImmutable(), facing, hasOutput, hasShrieker);
                        sensors.add(sensor);
                        sphereBlocks.addAll(generateDetectionSphere(sensor.pos, CALIBRATED_RANGE));
                    }
                }
            }
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.world == null) return;

        for (SensorData sensor : sensors) {
            if (onlyRenderImpactful.get() && advancedView.get() && !sensor.hasRedstoneOutput && !sensor.hasShriekerInRange) {
                continue;
            }

            double distanceToSensor = mc.player.getPos().distanceTo(Vec3d.ofCenter(sensor.pos));
            boolean useSphere = smartRendering.get() ? distanceToSensor <= smartRenderDistance.get() : shapeType.get() == ShapeType.Sphere;
            boolean useCircle = smartRendering.get() ? distanceToSensor > smartRenderDistance.get() : shapeType.get() == ShapeType.Circle;

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
                // Render accurate sphere blocks
                for (BlockPos pos : sphereBlocks) {
                    if (pos.isWithinDistance(sensor.pos, CALIBRATED_RANGE + 0.5)) {
                        event.renderer.box(pos, renderColor, lineColor.get(), shapeMode.get(), 0);
                    }
                }
            }

            if (useCircle) {
                double renderY = sensor.pos.getY() + 0.5;
                if (renderAtPlayerHeight.get()) {
                    renderY = mc.player.getY();
                }
                renderCircle(event, CALIBRATED_RANGE, circleThickness.get(), sensor.pos.getX() + 0.5, renderY, sensor.pos.getZ() + 0.5, renderColor);
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

    private enum ShapeType {
        Circle,
        Sphere
    }
}
