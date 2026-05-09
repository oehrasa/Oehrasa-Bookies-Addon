package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
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
import net.minecraft.world.chunk.Chunk;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SculkRange extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private static final double SENSOR_RANGE = 16.0;

    private final Setting<Integer> renderDistance = sgGeneral.add(new IntSetting.Builder()
        .name("render-distance")
        .description("How far away to render spheres.")
        .defaultValue(64)
        .min(16)
        .max(128)
        .sliderRange(16, 128)
        .build()
    );

    private final Setting<Boolean> smartRendering = sgGeneral.add(new BoolSetting.Builder()
        .name("smart-rendering")
        .description("Render as circle when far, sphere when close.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> smartRenderDistance = sgGeneral.add(new IntSetting.Builder()
        .name("smart-render-distance")
        .description("Distance to switch from circle to sphere.")
        .defaultValue(20)
        .min(5)
        .max(50)
        .visible(smartRendering::get)
        .build()
    );

    private final Setting<Boolean> advancedView = sgGeneral.add(new BoolSetting.Builder()
        .name("advanced-view")
        .description("Detect redstone connections and shriekers in range.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> onlyRenderImpactful = sgGeneral.add(new BoolSetting.Builder()
        .name("only-render-impactful")
        .description("Only render sensors that have redstone output or shriekers in range.")
        .defaultValue(false)
        .visible(advancedView::get)
        .build()
    );

    private final Setting<OcclusionMode> occlusionMode = sgRender.add(new EnumSetting.Builder<OcclusionMode>()
        .name("occlusion-mode")
        .description("How to handle occlusion (hiding blocks behind other blocks).")
        .defaultValue(OcclusionMode.None)
        .build()
    );

    private final Setting<ShapeType> shapeType = sgRender.add(new EnumSetting.Builder<ShapeType>()
        .name("shape-type")
        .description("What shape to render (overridden by smart-rendering if enabled).")
        .defaultValue(ShapeType.Sphere)
        .build()
    );

    private final Setting<Double> circleThickness = sgRender.add(new DoubleSetting.Builder()
        .name("circle-thickness")
        .description("Thickness of circle outline.")
        .defaultValue(0.08)
        .min(0.02)
        .max(0.5)
        .visible(() -> shapeType.get() == ShapeType.Circle || shapeType.get() == ShapeType.Both || smartRendering.get())
        .build()
    );

    private final Setting<Integer> gradation = sgRender.add(new IntSetting.Builder()
        .name("gradation")
        .description("Sphere thickness (1-5). Higher = thicker sphere shell.")
        .defaultValue(1)
        .min(1)
        .max(5)
        .sliderRange(1, 5)
        .visible(() -> shapeType.get() == ShapeType.Sphere || shapeType.get() == ShapeType.Both)
        .build()
    );

    private final Setting<Boolean> renderAtPlayerHeight = sgRender.add(new BoolSetting.Builder()
        .name("render-circle-at-player-height")
        .description("Render circular range indicator at player height instead of sensor height.")
        .defaultValue(false)
        .visible(() -> shapeType.get() == ShapeType.Circle || shapeType.get() == ShapeType.Both)
        .build()
    );

    private final Setting<SettingColor> sphereColor = sgRender.add(new ColorSetting.Builder()
        .name("sphere-color")
        .description("Color of the sphere outline.")
        .defaultValue(new SettingColor(0, 255, 255, 200))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Color of the sphere lines.")
        .defaultValue(new SettingColor(0, 255, 255, 255))
        .build()
    );

    private final Setting<SettingColor> redstoneColor = sgRender.add(new ColorSetting.Builder()
        .name("redstone-color")
        .description("Color for sensors with redstone output.")
        .defaultValue(new SettingColor(255, 0, 0, 200))
        .visible(advancedView::get)
        .build()
    );

    private final Setting<SettingColor> shriekerColor = sgRender.add(new ColorSetting.Builder()
        .name("shrieker-color")
        .description("Color for sensors with shriekers in range.")
        .defaultValue(new SettingColor(255, 165, 0, 200))
        .visible(advancedView::get)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the sphere is rendered.")
        .defaultValue(ShapeMode.Lines)
        .build()
    );

    private final Setting<Boolean> showCenterBox = sgRender.add(new BoolSetting.Builder()
        .name("show-center-box")
        .description("Show a box at the sensor location.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> centerColor = sgRender.add(new ColorSetting.Builder()
        .name("center-color")
        .description("Color of the center box.")
        .defaultValue(new SettingColor(255, 255, 255, 150))
        .build()
    );

    // Store sensors and their data
    private final Set<SensorData> sensors = new HashSet<>();
    private final Set<BlockPos> sphereBlocks = new HashSet<>();

    // Track if we need to regenerate spheres
    private boolean needsUpdate = true;

    // Worker thread for background scanning
    private volatile ExecutorService workerThread = null;

    private static class SensorData {
        BlockPos pos;
        boolean hasRedstoneOutput;
        boolean hasShriekerInRange;

        SensorData(BlockPos pos, boolean hasRedstoneOutput, boolean hasShriekerInRange) {
            this.pos = pos;
            this.hasRedstoneOutput = hasRedstoneOutput;
            this.hasShriekerInRange = hasShriekerInRange;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            SensorData that = (SensorData) obj;
            return pos.equals(that.pos);
        }

        @Override
        public int hashCode() {
            return pos.hashCode();
        }
    }

    public SculkRange() {
        super(Addon.CATEGORY, "Sculk-Range", "Shows the detection range of calibrated sculk sensors.");
    }

    private synchronized ExecutorService getWorkerThread() {
        if (workerThread == null || workerThread.isShutdown() || workerThread.isTerminated()) {
            workerThread = Executors.newSingleThreadExecutor();
        }
        return workerThread;
    }

    private synchronized void shutdownWorkerThread() {
        if (workerThread != null && !workerThread.isShutdown()) {
            workerThread.shutdownNow();
            try {
                workerThread.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
            workerThread = null;
        }
    }

    @Override
    public void onActivate() {
        sensors.clear();
        sphereBlocks.clear();
        needsUpdate = true;
        scanAllChunks();
    }

    @Override
    public void onDeactivate() {
        sensors.clear();
        sphereBlocks.clear();
        needsUpdate = true;
        shutdownWorkerThread();
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        if (!isActive()) return;
        sensors.clear();
        sphereBlocks.clear();
        needsUpdate = true;
        scanAllChunks();
    }

    private void scanAllChunks() {
        ExecutorService thread = getWorkerThread();
        if (thread.isShutdown()) return;

        thread.submit(() -> {
            if (!isActive()) return;

            Set<BlockPos> newSensors = new HashSet<>();

            for (Chunk chunk : Utils.chunks()) {
                if (!isActive()) return;
                scanChunkForSensors(chunk, newSensors);
            }

            mc.execute(() -> {
                if (!isActive()) return;
                sensors.clear();
                for (BlockPos pos : newSensors) {
                    boolean hasOutput = advancedView.get() ? hasRedstoneOutput(mc.world, pos) : false;
                    boolean hasShrieker = advancedView.get() ? hasShriekerInRange(mc.world, pos) : false;
                    sensors.add(new SensorData(pos, hasOutput, hasShrieker));
                }
                needsUpdate = true;
            });
        });
    }

    private void scanChunkForSensors(Chunk chunk, Set<BlockPos> outSensors) {
        int minX = chunk.getPos().getStartX();
        int minZ = chunk.getPos().getStartZ();
        int maxX = minX + 15;
        int maxZ = minZ + 15;
        int minY = chunk.getBottomY();
        int maxY = minY + chunk.getHeight();

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (mc.world.getBlockState(pos).getBlock() == Blocks.CALIBRATED_SCULK_SENSOR) {
                        outSensors.add(pos.toImmutable());
                    }
                }
            }
        }
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (!isActive()) return;

        ExecutorService thread = getWorkerThread();
        if (thread.isShutdown()) return;

        thread.submit(() -> {
            Set<BlockPos> newSensors = new HashSet<>();
            scanChunkForSensors(event.chunk(), newSensors);

            if (!newSensors.isEmpty()) {
                mc.execute(() -> {
                    if (!isActive()) return;
                    for (BlockPos pos : newSensors) {
                        boolean hasOutput = advancedView.get() ? hasRedstoneOutput(mc.world, pos) : false;
                        boolean hasShrieker = advancedView.get() ? hasShriekerInRange(mc.world, pos) : false;
                        sensors.add(new SensorData(pos, hasOutput, hasShrieker));
                    }
                    needsUpdate = true;
                });
            }
        });
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (!isActive()) return;

        BlockPos pos = event.pos;
        BlockState newState = event.newState;
        BlockState oldState = event.oldState;

        boolean wasSensor = oldState.getBlock() == Blocks.CALIBRATED_SCULK_SENSOR;
        boolean isSensor = newState.getBlock() == Blocks.CALIBRATED_SCULK_SENSOR;

        if (wasSensor && !isSensor) {
            sensors.removeIf(s -> s.pos.equals(pos));
            needsUpdate = true;
        } else if (!wasSensor && isSensor) {
            boolean hasOutput = advancedView.get() ? hasRedstoneOutput(mc.world, pos) : false;
            boolean hasShrieker = advancedView.get() ? hasShriekerInRange(mc.world, pos) : false;
            sensors.add(new SensorData(pos.toImmutable(), hasOutput, hasShrieker));
            needsUpdate = true;
        } else if (isSensor && advancedView.get()) {
            boolean hasOutput = hasRedstoneOutput(mc.world, pos);
            boolean hasShrieker = hasShriekerInRange(mc.world, pos);
            sensors.removeIf(s -> s.pos.equals(pos));
            sensors.add(new SensorData(pos.toImmutable(), hasOutput, hasShrieker));
            needsUpdate = true;
        }

        boolean wasShrieker = oldState.getBlock() instanceof SculkShriekerBlock;
        boolean isShrieker = newState.getBlock() instanceof SculkShriekerBlock;

        if ((wasShrieker || isShrieker) && advancedView.get()) {
            updateSensorsNearPosition(pos);
        }

        if (advancedView.get() && (isRedstoneComponent(oldState) || isRedstoneComponent(newState))) {
            updateSensorsNearPosition(pos);
        }

        if (needsUpdate) {
            regenerateSpheres();
            needsUpdate = false;
        }
    }

    private void updateSensorsNearPosition(BlockPos pos) {
        for (SensorData sensor : sensors) {
            double distance = Math.sqrt(sensor.pos.getSquaredDistance(pos));
            if (distance <= SENSOR_RANGE) {
                boolean hasOutput = hasRedstoneOutput(mc.world, sensor.pos);
                boolean hasShrieker = hasShriekerInRange(mc.world, sensor.pos);
                sensor.hasRedstoneOutput = hasOutput;
                sensor.hasShriekerInRange = hasShrieker;
                needsUpdate = true;
            }
        }
    }

    private boolean isRedstoneComponent(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.REDSTONE_WIRE ||
            block instanceof ComparatorBlock ||
            block instanceof ObserverBlock;
    }

    private void regenerateSpheres() {
        sphereBlocks.clear();
        for (SensorData sensor : sensors) {
            sphereBlocks.addAll(generateHollowEuclideanSphere(sensor.pos, SENSOR_RANGE, gradation.get()));
        }
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
        int range = 16;
        for (BlockPos checkPos : BlockPos.iterateOutwards(sensorPos, range, range, range)) {
            BlockState state = world.getBlockState(checkPos);
            if (state.getBlock() instanceof SculkShriekerBlock) return true;
        }
        return false;
    }

    private Set<BlockPos> generateHollowEuclideanSphere(BlockPos center, double radius, int thickness) {
        Set<BlockPos> positions = new HashSet<>();
        int radiusCeil = (int) Math.ceil(radius);
        double radiusSq = radius * radius;

        // Gradation controls how thick the shell is
        double innerRadiusSq = Math.max(0, (radius - thickness) * (radius - thickness));

        double centerX = center.getX() + 0.5;
        double centerY = center.getY() + 0.5;
        double centerZ = center.getZ() + 0.5;

        for (int x = -radiusCeil; x <= radiusCeil; x++) {
            for (int y = -radiusCeil; y <= radiusCeil; y++) {
                for (int z = -radiusCeil; z <= radiusCeil; z++) {
                    BlockPos checkPos = center.add(x, y, z);

                    double dx = (checkPos.getX() + 0.5) - centerX;
                    double dy = (checkPos.getY() + 0.5) - centerY;
                    double dz = (checkPos.getZ() + 0.5) - centerZ;

                    double distSq = dx * dx + dy * dy + dz * dz;

                    // Include blocks within the shell thickness
                    if (distSq <= radiusSq && distSq >= innerRadiusSq) {
                        positions.add(checkPos);
                    }
                }
            }
        }
        return positions;
    }

    private boolean isVisible(BlockPos pos) {
        OcclusionMode mode = occlusionMode.get();
        if (mode == OcclusionMode.None) return true;

        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = pos.offset(dir);
            BlockState neighborState = mc.world.getBlockState(neighborPos);

            if (neighborState.isAir() || !neighborState.isOpaque()) {
                if (mode == OcclusionMode.Simple) return true;

                if (mode == OcclusionMode.Accurate) {
                    Vec3d playerPos = mc.player.getEyePos();
                    Vec3d blockCenter = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    Vec3d faceCenter = blockCenter.add(dir.getOffsetX() * 0.5, dir.getOffsetY() * 0.5, dir.getOffsetZ() * 0.5);
                    Vec3d toFace = faceCenter.subtract(playerPos).normalize();
                    Vec3d faceNormal = new Vec3d(dir.getOffsetX(), dir.getOffsetY(), dir.getOffsetZ());

                    if (faceNormal.dotProduct(toFace) > 0) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.world == null) return;

        for (SensorData sensor : sensors) {
            if (onlyRenderImpactful.get() && advancedView.get() && !sensor.hasRedstoneOutput && !sensor.hasShriekerInRange) {
                continue;
            }

            double distanceToSensor = mc.player.getPos().distanceTo(Vec3d.ofCenter(sensor.pos));

            ShapeType effectiveShape = shapeType.get();
            if (smartRendering.get()) {
                effectiveShape = distanceToSensor <= smartRenderDistance.get() ? ShapeType.Sphere : ShapeType.Circle;
            }

            boolean useSphere = effectiveShape == ShapeType.Sphere || effectiveShape == ShapeType.Both;
            boolean useCircle = effectiveShape == ShapeType.Circle || effectiveShape == ShapeType.Both;

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
                    double dx = (pos.getX() + 0.5) - (sensor.pos.getX() + 0.5);
                    double dy = (pos.getY() + 0.5) - (sensor.pos.getY() + 0.5);
                    double dz = (pos.getZ() + 0.5) - (sensor.pos.getZ() + 0.5);
                    double distSq = dx * dx + dy * dy + dz * dz;

                    if (distSq > SENSOR_RANGE * SENSOR_RANGE) continue;
                    if (!isVisible(pos)) continue;

                    event.renderer.box(pos, renderColor, lineColor.get(), shapeMode.get(), 0);
                }
            }

            if (useCircle) {
                double renderY;
                if (renderAtPlayerHeight.get()) {
                    renderY = mc.player.getY();
                } else {
                    renderY = sensor.pos.getY() + 0.5;
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

    private enum OcclusionMode {
        None("None : Render all blocks."),
        Simple("Simple : Hide blocks behind solid faces."),
        Accurate("Accurate : Hide faces not facing the player.");

        private final String title;
        OcclusionMode(String title) { this.title = title; }
        @Override public String toString() { return title; }
    }
}
