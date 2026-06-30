package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.*;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
import org.lwjgl.glfw.GLFW;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;

public class SculkRange extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private static final double SENSOR_RANGE = 16.0;
    private static final Direction[] DIRECTIONS = Direction.values();

    private final Setting<Integer> renderDistance = sgGeneral.add(new IntSetting.Builder()
        .name("render-distance")
        .description("Maximum distance from the player at which sensor spheres are drawn.")
        .defaultValue(64)
        .min(16)
        .max(128)
        .sliderRange(16, 128)
        .build());

    private final Setting<Boolean> manualMode = sgGeneral.add(new BoolSetting.Builder()
        .name("manual-mode")
        .description("Only render ranges for manually selected sensors.")
        .defaultValue(false)
        .build());

    private final Setting<Keybind> selectKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("select-key")
        .description("Press while looking at a calibrated sculk sensor to add or remove it.")
        .visible(manualMode::get)
        .defaultValue(Keybind.fromKey(GLFW.GLFW_KEY_V))
        .build());

    private final Setting<Boolean> advancedView = sgGeneral.add(new BoolSetting.Builder()
        .name("advanced-view")
        .description("Colour sensors based on whether they have redstone output or a shrieker in range.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> onlyRenderImpactful = sgGeneral.add(new BoolSetting.Builder()
        .name("only-render-impactful")
        .description("Skip sensors that have neither redstone output nor a shrieker in range.")
        .defaultValue(false)
        .visible(advancedView::get)
        .build());

    private final Setting<Boolean> union = sgGeneral.add(new BoolSetting.Builder()
        .name("union")
        .description("Merge overlapping sensor spheres into one continuous outer surface.")
        .defaultValue(false)
        .onChanged(v -> rebuildAllExposedBlocks())
        .build());

    private final Setting<Integer> gradation = sgRender.add(new IntSetting.Builder()
        .name("gradation")
        .description("Sphere shell thickness in voxels (1 = single-voxel outer skin).")
        .defaultValue(1)
        .min(1)
        .max(5)
        .sliderRange(1, 5)
        .onChanged(v -> rebuildAllSpheres())
        .build());

    private final Setting<OcclusionMode> occlusionMode = sgRender.add(new EnumSetting.Builder<OcclusionMode>()
        .name("occlusion")
        .description("Whether to hide sphere voxels that are inside solid world blocks.")
        .defaultValue(OcclusionMode.None)
        .build());

    private final Setting<Boolean> highlightSolidVoxels = sgRender.add(new BoolSetting.Builder()
        .name("highlight-solid-voxels")
        .description("Render sphere voxels that overlap a solid world block in a different colour, instead of hiding or showing them normally.")
        .defaultValue(false)
        .build());

    private final Setting<SettingColor> solidVoxelColor = sgRender.add(new ColorSetting.Builder()
        .name("solid-voxel-color")
        .description("Colour used for sphere voxels that overlap a solid world block.")
        .defaultValue(new SettingColor(255, 0, 255, 75))
        .visible(highlightSolidVoxels::get)
        .build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How each voxel box is drawn.")
        .defaultValue(ShapeMode.Lines)
        .build());

    private final Setting<SettingColor> sphereColor = sgRender.add(new ColorSetting.Builder()
        .name("sphere-color")
        .description("Default sphere colour.")
        .defaultValue(new SettingColor(0, 255, 255, 200))
        .build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Outline colour of sphere voxels.")
        .defaultValue(new SettingColor(0, 255, 255, 255))
        .build());

    private final Setting<SettingColor> redstoneColor = sgRender.add(new ColorSetting.Builder()
        .name("redstone-color")
        .description("Colour for sensors with redstone output (advanced-view).")
        .defaultValue(new SettingColor(255, 0, 0, 200))
        .visible(advancedView::get)
        .build());

    private final Setting<SettingColor> shriekerColor = sgRender.add(new ColorSetting.Builder()
        .name("shrieker-color")
        .description("Colour for sensors with a shrieker in range (advanced-view).")
        .defaultValue(new SettingColor(255, 165, 0, 200))
        .visible(advancedView::get)
        .build());

    private final Setting<Boolean> showCenterBox = sgRender.add(new BoolSetting.Builder()
        .name("show-center-box")
        .description("Draw a small box at each sensor's block position.")
        .defaultValue(true)
        .build());

    private final Setting<SettingColor> centerColor = sgRender.add(new ColorSetting.Builder()
        .name("center-color")
        .description("Colour of the center box.")
        .defaultValue(new SettingColor(255, 255, 255, 150))
        .build());

    private final Set<SensorData> sensors = new HashSet<>();
    private final Set<BlockPos> manualSensors = new HashSet<>();
    private volatile ExecutorService workerThread;
    private boolean selectKeyWasDown;

    private static final class SensorData {
        final BlockPos pos;
        boolean hasRedstoneOutput;
        boolean hasShriekerInRange;
        // Raw shell voxels (gradation-filtered hollow sphere).
        Set<BlockPos> sphereBlocks = new HashSet<>();
        // Shell voxels after culling interior blocks; this is what the renderer iterates.
        Set<BlockPos> exposedBlocks = new HashSet<>();

        SensorData(BlockPos pos, boolean hasRedstoneOutput, boolean hasShriekerInRange) {
            this.pos = pos;
            this.hasRedstoneOutput = hasRedstoneOutput;
            this.hasShriekerInRange = hasShriekerInRange;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof SensorData s && pos.equals(s.pos);
        }

        @Override
        public int hashCode() {
            return pos.hashCode();
        }
    }

    private enum OcclusionMode {
        None("None: renders whole sphere"),
        Simple("Simple: skip voxels inside a block"),
        Accurate("Accurate: also skip faces not pointing toward the player");

        private final String label;

        OcclusionMode(String l) {
            this.label = l;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public SculkRange() {
        super(Addon.CATEGORY2, "Sculk-Range", "Shows the detection range of calibrated sculk sensors.");
    }

    @Override
    public void onActivate() {
        sensors.clear();
        scanAllChunks();
    }

    @Override
    public void onDeactivate() {
        sensors.clear();
        shutdownWorker();
    }

    private synchronized ExecutorService getWorker() {
        if (workerThread == null || workerThread.isShutdown() || workerThread.isTerminated())
            workerThread = Executors.newSingleThreadExecutor();
        return workerThread;
    }

    private synchronized void shutdownWorker() {
        if (workerThread == null || workerThread.isShutdown()) return;
        workerThread.shutdownNow();
        try {
            workerThread.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        workerThread = null;
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        if (!isActive()) return;
        sensors.clear();
        scanAllChunks();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive() || !manualMode.get()) return;
        boolean down = selectKey.get().isPressed();
        if (down && !selectKeyWasDown && mc.crosshairTarget instanceof BlockHitResult hit) {
            BlockPos pos = hit.getBlockPos();
            if (mc.world.getBlockState(pos).getBlock() == Blocks.CALIBRATED_SCULK_SENSOR) {
                if (manualSensors.remove(pos)) info("Removed sensor at " + pos.toShortString());
                else {
                    manualSensors.add(pos.toImmutable());
                    info("Added sensor at " + pos.toShortString());
                }
            }
        }
        selectKeyWasDown = down;
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (!isActive()) return;
        ExecutorService w = getWorker();
        if (w.isShutdown()) return;
        w.submit(() -> {
            Set<SensorData> found = new HashSet<>();
            scanChunkForSensors(event.chunk(), found);
            if (found.isEmpty()) return;
            mc.execute(() -> {
                if (!isActive()) return;
                boolean changed = false;
                for (SensorData s : found) {
                    if (sensors.add(s)) { // skips position duplicates
                        s.sphereBlocks = generateSphere(s.pos);
                        changed = true;
                    }
                }
                if (changed) rebuildAllExposedBlocks();
            });
        });
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (!isActive()) return;
        BlockPos pos = event.pos;
        boolean wasSensor = event.oldState.getBlock() == Blocks.CALIBRATED_SCULK_SENSOR;
        boolean isSensor = event.newState.getBlock() == Blocks.CALIBRATED_SCULK_SENSOR;

        if (wasSensor && !isSensor) {
            sensors.removeIf(s -> s.pos.equals(pos));
            rebuildAllExposedBlocks();
        } else if (!wasSensor && isSensor) {
            SensorData s = makeSensor(pos);
            if (sensors.add(s)) {
                s.sphereBlocks = generateSphere(s.pos);
                rebuildAllExposedBlocks();
            }
        } else if (isSensor && advancedView.get()) {
            // Block-state is changed, but it is still a sensor. refresh advanced properties only.
            sensors.stream().filter(s -> s.pos.equals(pos)).findFirst().ifPresent(s -> {
                s.hasRedstoneOutput = hasRedstoneOutput(mc.world, pos);
                s.hasShriekerInRange = hasShriekerInRange(mc.world, pos);
            });
        }

        // Refresh nearby sensors when a shrieker or redstone component changes.
        if (advancedView.get()) {
            boolean relevant = event.oldState.getBlock() instanceof SculkShriekerBlock
                || event.newState.getBlock() instanceof SculkShriekerBlock
                || isRedstoneComponent(event.oldState)
                || isRedstoneComponent(event.newState);
            if (relevant) updateAdvancedNear(pos);
        }
    }

    private void scanAllChunks() {
        ExecutorService w = getWorker();
        if (w.isShutdown()) return;
        w.submit(() -> {
            if (!isActive()) return;
            Set<SensorData> found = new HashSet<>();
            AtomicReferenceArray<WorldChunk> chunks = mc.world.getChunkManager().chunks.chunks;
            for (int i = 0; i < chunks.length(); i++) {
                WorldChunk c = chunks.get(i);
                if (c != null && !c.isEmpty()) {
                    if (!isActive()) return;
                    scanChunkForSensors(c, found);
                }
            }
            mc.execute(() -> {
                if (!isActive()) return;
                sensors.clear();
                sensors.addAll(found);
                for (SensorData s : sensors) s.sphereBlocks = generateSphere(s.pos);
                rebuildAllExposedBlocks();
            });
        });
    }

    private void scanChunkForSensors(Chunk chunk, Set<SensorData> out) {
        int x0 = chunk.getPos().getStartX(), z0 = chunk.getPos().getStartZ();
        int y0 = chunk.getBottomY(), y1 = y0 + chunk.getHeight();
        for (int x = x0; x <= x0 + 15; x++)
            for (int z = z0; z <= z0 + 15; z++)
                for (int y = y0; y <= y1; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (mc.world.getBlockState(p).getBlock() == Blocks.CALIBRATED_SCULK_SENSOR)
                        out.add(makeSensor(p));
                }
    }

    private SensorData makeSensor(BlockPos pos) {
        pos = pos.toImmutable();
        boolean hasOut = advancedView.get() && hasRedstoneOutput(mc.world, pos);
        boolean hasShrieker = advancedView.get() && hasShriekerInRange(mc.world, pos);
        return new SensorData(pos, hasOut, hasShrieker);
    }

    /**
     * Rebuilds every sensor's raw shell then recomputes all exposed-block sets.
     */
    private void rebuildAllSpheres() {
        if (sensors.isEmpty()) return;
        for (SensorData s : sensors) s.sphereBlocks = generateSphere(s.pos);
        rebuildAllExposedBlocks();
    }

    private void rebuildAllExposedBlocks() {
        if (sensors.isEmpty()) return;
        if (union.get() && sensors.size() > 1) {
            Set<BlockPos> global = new HashSet<>();
            for (SensorData s : sensors) global.addAll(s.sphereBlocks);
            for (SensorData s : sensors) s.exposedBlocks = exposedIn(s.sphereBlocks, global);
        } else {
            for (SensorData s : sensors) s.exposedBlocks = exposedIn(s.sphereBlocks, s.sphereBlocks);
        }
    }

    private static Set<BlockPos> exposedIn(Set<BlockPos> own, Set<BlockPos> ref) {
        Set<BlockPos> out = new HashSet<>();
        for (BlockPos pos : own)
            for (Direction dir : DIRECTIONS)
                if (!ref.contains(pos.offset(dir))) {
                    out.add(pos);
                    break;
                }
        return out;
    }

    /**
     * Hollow Euclidean sphere shell centred at the center with the configured thickness.
     */
    private Set<BlockPos> generateSphere(BlockPos center) {
        int t = gradation.get();
        int ceil = (int) Math.ceil(SENSOR_RANGE);
        double outerSq = SENSOR_RANGE * SENSOR_RANGE;
        double innerSq = Math.max(0.0, (SENSOR_RANGE - t) * (SENSOR_RANGE - t));
        Set<BlockPos> out = new HashSet<>();
        for (int x = -ceil; x <= ceil; x++)
            for (int y = -ceil; y <= ceil; y++)
                for (int z = -ceil; z <= ceil; z++) {
                    double d = (double) x * x + (double) y * y + (double) z * z;
                    if (d <= outerSq && d >= innerSq) out.add(center.add(x, y, z));
                }
        return out;
    }

    private void updateAdvancedNear(BlockPos changed) {
        double rangeSq = SENSOR_RANGE * SENSOR_RANGE;
        for (SensorData s : sensors) {
            if (s.pos.getSquaredDistance(changed) <= rangeSq) {
                s.hasRedstoneOutput = hasRedstoneOutput(mc.world, s.pos);
                s.hasShriekerInRange = hasShriekerInRange(mc.world, s.pos);
            }
        }
    }

    private boolean isRedstoneComponent(BlockState state) {
        Block b = state.getBlock();
        return b == Blocks.REDSTONE_WIRE || b instanceof ComparatorBlock || b instanceof ObserverBlock;
    }

    private boolean hasRedstoneOutput(World world, BlockPos pos) {
        for (Direction dir : DIRECTIONS) {
            BlockPos adj = pos.offset(dir);
            BlockState state = world.getBlockState(adj);
            Block block = state.getBlock();
            if (block == Blocks.REDSTONE_WIRE) return true;
            if (block instanceof ComparatorBlock
                && adj.offset(state.get(ComparatorBlock.FACING).getOpposite()).equals(pos)) return true;
            if (block instanceof ObserverBlock
                && adj.offset(state.get(ObserverBlock.FACING)).equals(pos)) return true;
        }
        return false;
    }

    private boolean hasShriekerInRange(World world, BlockPos pos) {
        for (BlockPos p : BlockPos.iterateOutwards(pos, 16, 16, 16))
            if (world.getBlockState(p).getBlock() instanceof SculkShriekerBlock) return true;
        return false;
    }

    private boolean isSolidVoxel(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        return !state.isAir() && state.isOpaque();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.world == null || mc.player == null) return;

        // Cache perframe constants outside the loop.
        Vec3d playerPos = mc.player.getPos();
        OcclusionMode oMode = occlusionMode.get();
        Vec3d eyePos = oMode == OcclusionMode.Accurate ? mc.player.getEyePos() : null;
        boolean doUnion = union.get();
        ShapeMode sMode = shapeMode.get();
        SettingColor lColor = lineColor.get();
        int maxDist = renderDistance.get();
        boolean doHighlightSolid = highlightSolidVoxels.get();
        SettingColor solidColor = doHighlightSolid ? solidVoxelColor.get() : null;

        // Dedup set is only allocated in union mode; it prevents shared boundary
        // voxels (present in two sensors' exposedBlocks) from being drawn twice.
        Set<BlockPos> drawn = doUnion ? new HashSet<>() : null;

        for (SensorData sensor : sensors) {
            if (manualMode.get() && !manualSensors.contains(sensor.pos)) continue;
            if (onlyRenderImpactful.get() && advancedView.get()
                && !sensor.hasRedstoneOutput && !sensor.hasShriekerInRange) continue;
            if (playerPos.distanceTo(Vec3d.ofCenter(sensor.pos)) > maxDist) continue;

            SettingColor color = resolveColor(sensor);
            // Fall back to the raw shell only if exposedBlocks hasn't been computed yet.
            Set<BlockPos> renderSet = sensor.exposedBlocks.isEmpty()
                ? sensor.sphereBlocks : sensor.exposedBlocks;

            for (BlockPos pos : renderSet) {
                if (doUnion && !drawn.add(pos)) continue;   // skip duplicate voxels
                if (!passesOcclusion(pos, oMode, eyePos)) continue;

                // Recolour (but still draw) voxels that overlap a solid world block.
                SettingColor voxelColor = (doHighlightSolid && isSolidVoxel(pos)) ? solidColor : color;
                event.renderer.box(pos, voxelColor, lColor, sMode, 0);
            }

            if (showCenterBox.get())
                event.renderer.box(sensor.pos, centerColor.get(), centerColor.get(), ShapeMode.Lines, 0);
        }
    }

    private SettingColor resolveColor(SensorData sensor) {
        if (!advancedView.get()) return sphereColor.get();
        if (sensor.hasRedstoneOutput && sensor.hasShriekerInRange)
            return System.currentTimeMillis() / 500 % 2 == 0 ? redstoneColor.get() : shriekerColor.get();
        if (sensor.hasRedstoneOutput) return redstoneColor.get();
        if (sensor.hasShriekerInRange) return shriekerColor.get();
        return sphereColor.get();
    }

    private boolean passesOcclusion(BlockPos pos, OcclusionMode mode, Vec3d eye) {
        if (mode == OcclusionMode.None) return true;
        for (Direction dir : DIRECTIONS) {
            BlockState nb = mc.world.getBlockState(pos.offset(dir));
            if (!nb.isAir() && nb.isOpaque()) continue;   // this face is blocked by a solid world block
            if (mode == OcclusionMode.Simple) return true;
            // Accurate: the eye-to-face vector must align with the face normal.
            double fx = pos.getX() + 0.5 + dir.getOffsetX() * 0.5;
            double fy = pos.getY() + 0.5 + dir.getOffsetY() * 0.5;
            double fz = pos.getZ() + 0.5 + dir.getOffsetZ() * 0.5;
            double tx = fx - eye.x, ty = fy - eye.y, tz = fz - eye.z;
            double len = Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (len > 1e-9) {
                tx /= len;
                ty /= len;
                tz /= len;
            }
            if (tx * dir.getOffsetX() + ty * dir.getOffsetY() + tz * dir.getOffsetZ() > 0) return true;
        }
        return false;
    }
}
