package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SculkSensorPhase;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
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
    private final SettingGroup sgExperimental = settings.createGroup("Experimental");

    private static final double CALIBRATED_RANGE = 16.0;
    private static final double NORMAL_RANGE = 8.0;
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

    private final Setting<Boolean> showNormalSculk = sgGeneral.add(new BoolSetting.Builder()
        .name("show-normal-sculk-sensors")
        .description("Also track and render range for regular (non calibrated) sculk sensors, 8-block radius.")
        .defaultValue(true)
        .onChanged(v -> rescanAll())
        .build());

    private final Setting<Boolean> showShriekers = sgGeneral.add(new BoolSetting.Builder()
        .name("show-sculk-shriekers")
        .description("Track and render a listening-range sphere around sculk shriekers.")
        .defaultValue(true)
        .onChanged(v -> rescanAll())
        .build());

    private final Setting<Integer> shriekerRange = sgGeneral.add(new IntSetting.Builder()
        .name("shrieker-range")
        .description("Radius to render around sculk shriekers. Defaults to the 8-block radius sculk shriekers listen at.")
        .defaultValue(8)
        .min(1)
        .max(32)
        .sliderRange(1, 32)
        .visible(showShriekers::get)
        .onChanged(v -> rebuildAllSpheres())
        .build());

    private final Setting<Boolean> advancedView = sgGeneral.add(new BoolSetting.Builder()
        .name("advanced-view")
        .description("Colour sculk sensors based on whether they have redstone output or a shrieker in range.")
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
        .name("calibrated-color")
        .description("Sphere colour for calibrated sculk sensors.")
        .defaultValue(new SettingColor(0, 255, 255, 200))
        .build());

    private final Setting<SettingColor> normalSculkColor = sgRender.add(new ColorSetting.Builder()
        .name("normal-sculk-color")
        .description("Sphere colour for regular sculk sensors.")
        .defaultValue(new SettingColor(150, 255, 150, 200))
        .visible(showNormalSculk::get)
        .build());

    private final Setting<SettingColor> shriekerRangeColor = sgRender.add(new ColorSetting.Builder()
        .name("shrieker-range-color")
        .description("Sphere colour for sculk shrieker listening range.")
        .defaultValue(new SettingColor(255, 80, 200, 200))
        .visible(showShriekers::get)
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

    private final Setting<SettingColor> shriekerNearColor = sgRender.add(new ColorSetting.Builder()
        .name("shrieker-near-color")
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

    private final Setting<Boolean> showActivationPower = sgExperimental.add(new BoolSetting.Builder()
        .name("show-activation-power")
        .description("When a sensor activates, show its synced power/signal-strength value above it. Calibrated sensors also show their exact triggering frequency.")
        .defaultValue(false)
        .build());

    private final Setting<Double> vibrationTextScale = sgExperimental.add(new DoubleSetting.Builder()
        .name("power-text-scale")
        .description("How big the power/frequency text should be.")
        .defaultValue(1.25)
        .min(0.5)
        .sliderMax(4)
        .visible(showActivationPower::get)
        .build());

    private final Setting<SettingColor> vibrationTextColor = sgExperimental.add(new ColorSetting.Builder()
        .name("power-text-color")
        .description("Colour of the power text.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .visible(showActivationPower::get)
        .build());

    private final Setting<SettingColor> frequencyTextColor = sgExperimental.add(new ColorSetting.Builder()
        .name("frequency-text-color")
        .description("Colour of a calibrated sensor's exact triggering-frequency label.")
        .defaultValue(new SettingColor(255, 220, 100, 255))
        .visible(showActivationPower::get)
        .build());

    private static final String[] VIBRATION_LABELS = {
        "?",
        "Movement",             // 1: step, swim, flap
        "Landing/Splash",       // 2: projectile land, hit ground, splash
        "Item/Instrument",      // 3: item interact finish, projectile shoot, instrument play
        "Entity Action",        // 4: entity action, elytra glide, unequip
        "Dismount/Equip",       // 5: entity dismount, equip
        "Interact/Mount",       // 6: entity interact, shear, entity mount
        "Combat",               // 7: entity damage
        "Eat/Drink",            // 8: drink, eat
        "Block Close",          // 9: container close, block close/deactivate/detach
        "Block Open",           // 10: container open, block open/activate/attach, prime fuse, note block
        "Block Change",         // 11: block change
        "Block Destroy",        // 12: block destroy, fluid pickup
        "Block Place",          // 13: block place, fluid place
        "Entity Place/Teleport",// 14: entity place, lightning strike, teleport
        "Death/Explosion"       // 15: entity die, explode
    };

    private final Set<SensorData> sensors = new HashSet<>();
    private final Set<BlockPos> manualSensors = new HashSet<>();
    private volatile ExecutorService workerThread;
    private boolean selectKeyWasDown;

    private enum SensorType {
        CALIBRATED, NORMAL, SHRIEKER
    }

    private final class SensorData {
        final BlockPos pos;
        final SensorType type;
        boolean hasRedstoneOutput;
        boolean hasShriekerInRange;
        // Raw shell voxels (gradation-filtered hollow sphere).
        Set<BlockPos> sphereBlocks = new HashSet<>();
        // Shell voxels after culling interior blocks; this is what the renderer iterates.
        Set<BlockPos> exposedBlocks = new HashSet<>();

        SensorData(BlockPos pos, SensorType type, boolean hasRedstoneOutput, boolean hasShriekerInRange) {
            this.pos = pos;
            this.type = type;
            this.hasRedstoneOutput = hasRedstoneOutput;
            this.hasShriekerInRange = hasShriekerInRange;
        }

        double range() {
            return switch (type) {
                case CALIBRATED -> CALIBRATED_RANGE;
                case NORMAL -> NORMAL_RANGE;
                case SHRIEKER -> shriekerRange.get();
            };
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
        super(Addon.CATEGORY2, "Sculk-Range", "Shows the detection range of normal or calibrated sculk sensors  and shriekers.");
    }

    @Override
    public void onActivate() {
        if (mc.level == null) return;
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

    private void rescanAll() {
        if (!isActive() || mc.level == null) return;
        sensors.clear();
        scanAllChunks();
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
        if (down && !selectKeyWasDown && mc.hitResult instanceof BlockHitResult hit) {
            BlockPos pos = hit.getBlockPos();
            if (typeOf(mc.level.getBlockState(pos).getBlock()) != null) {
                if (manualSensors.remove(pos)) info("Removed sensor at " + pos.toShortString());
                else {
                    manualSensors.add(pos.immutable());
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
        boolean trackNormal = showNormalSculk.get();
        boolean trackShrieker = showShriekers.get();
        boolean advanced = advancedView.get();
        w.submit(() -> {
            Set<SensorData> found = new HashSet<>();
            scanChunkForSensors(event.chunk(), found, trackNormal, trackShrieker, advanced);
            if (found.isEmpty()) return;
            mc.execute(() -> {
                if (!isActive()) return;
                boolean changed = false;
                for (SensorData s : found) {
                    if (sensors.add(s)) {
                        s.sphereBlocks = generateSphere(s.pos, s.range());
                        changed = true;
                    }
                }
                if (changed) rebuildAllExposedBlocks();
            });
        });
    }

    // Returns the SensorType this block corresponds to
    private SensorType typeOf(Block block) {
        return typeOf(block, showNormalSculk.get(), showShriekers.get());
    }

    private SensorType typeOf(Block block, boolean trackNormal, boolean trackShrieker) {
        if (block == Blocks.CALIBRATED_SCULK_SENSOR) return SensorType.CALIBRATED;
        if (block == Blocks.SCULK_SENSOR) return trackNormal ? SensorType.NORMAL : null;
        if (block == Blocks.SCULK_SHRIEKER) return trackShrieker ? SensorType.SHRIEKER : null;
        return null;
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (!isActive()) return;
        BlockPos pos = event.pos;

        SensorType wasType = typeOf(event.oldState.getBlock());
        SensorType isType = typeOf(event.newState.getBlock());

        if (wasType != null && isType == null) {
            sensors.removeIf(s -> s.pos.equals(pos));
            rebuildAllExposedBlocks();
        } else if (wasType == null && isType != null) {
            SensorData s = makeSensor(pos, isType);
            if (sensors.add(s)) {
                s.sphereBlocks = generateSphere(s.pos, s.range());
                rebuildAllExposedBlocks();
            }
        } else if (wasType != null && isType != null && wasType != isType) {
            // Sensor swapped type in place
            sensors.removeIf(s -> s.pos.equals(pos));
            SensorData s = makeSensor(pos, isType);
            if (sensors.add(s)) s.sphereBlocks = generateSphere(s.pos, s.range());
            rebuildAllExposedBlocks();
        } else if (isType != null && isType != SensorType.SHRIEKER) {
            // Same sensor type, block-state just changed (redstone neighbour, phase transition, etc).
            for (SensorData s : sensors) {
                if (!s.pos.equals(pos)) continue;
                if (advancedView.get()) {
                    s.hasRedstoneOutput = hasRedstoneOutput(mc.level, pos);
                    s.hasShriekerInRange = hasShriekerInRange(mc.level, pos);
                }
                break;
            }
        }

        if (advancedView.get()) {
            boolean relevant = event.oldState.getBlock() instanceof SculkShriekerBlock
                || event.newState.getBlock() instanceof SculkShriekerBlock
                || isRedstoneComponent(event.oldState)
                || isRedstoneComponent(event.newState);
            if (relevant) updateAdvancedNear(pos);
        }
    }

    /**
     * Maps a calibrated sculk sensor's power value directly to the vanilla note-block
     * frequency it corresponds to.
     */
    private String exactFrequencyLabel(int power) {
        return (power >= 1 && power < VIBRATION_LABELS.length) ? VIBRATION_LABELS[power] : null;
    }

    private void scanAllChunks() {
        ExecutorService w = getWorker();
        if (w.isShutdown()) return;
        boolean trackNormal = showNormalSculk.get();
        boolean trackShrieker = showShriekers.get();
        boolean advanced = advancedView.get();
        w.submit(() -> {
            if (!isActive() || mc.level == null) return;
            Set<SensorData> found = new HashSet<>();
            AtomicReferenceArray<LevelChunk> chunks = mc.level.getChunkSource().storage.chunks;
            for (int i = 0; i < chunks.length(); i++) {
                LevelChunk c = chunks.get(i);
                if (c != null && !c.isEmpty()) {
                    if (!isActive()) return;
                    scanChunkForSensors(c, found, trackNormal, trackShrieker, advanced);
                }
            }
            mc.execute(() -> {
                if (!isActive()) return;
                sensors.clear();
                sensors.addAll(found);
                for (SensorData s : sensors) s.sphereBlocks = generateSphere(s.pos, s.range());
                rebuildAllExposedBlocks();
            });
        });
    }

    private SensorData makeSensor(BlockPos pos, SensorType type) {
        return makeSensor(pos, type, advancedView.get());
    }

    private SensorData makeSensor(BlockPos pos, SensorType type, boolean advanced) {
        pos = pos.immutable();
        boolean hasOut = type != SensorType.SHRIEKER && advanced && hasRedstoneOutput(mc.level, pos);
        boolean hasShrieker = type != SensorType.SHRIEKER && advanced && hasShriekerInRange(mc.level, pos);
        return new SensorData(pos, type, hasOut, hasShrieker);
    }

    private void scanChunkForSensors(ChunkAccess chunk, Set<SensorData> out, boolean trackNormal, boolean trackShrieker, boolean advanced) {
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        int y0 = chunk.getMinY(), y1 = y0 + chunk.getHeight();
        for (int x = x0; x <= x0 + 15; x++)
            for (int z = z0; z <= z0 + 15; z++)
                for (int y = y0; y <= y1; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    SensorType type = typeOf(mc.level.getBlockState(p).getBlock(), trackNormal, trackShrieker);
                    if (type != null) out.add(makeSensor(p, type, advanced));
                }
    }

    /**
     * Rebuilds every sensor's raw shell then recomputes all exposed-block sets.
     */
    private void rebuildAllSpheres() {
        if (sensors.isEmpty()) return;
        for (SensorData s : sensors) s.sphereBlocks = generateSphere(s.pos, s.range());
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
                if (!ref.contains(pos.relative(dir))) {
                    out.add(pos);
                    break;
                }
        return out;
    }

    /**
     * Hollow Euclidean sphere shell centred at the center with the configured thickness, for the given radius.
     */
    private Set<BlockPos> generateSphere(BlockPos center, double range) {
        int t = gradation.get();
        int ceil = (int) Math.ceil(range);
        double outerSq = range * range;
        double innerSq = Math.max(0.0, (range - t) * (range - t));
        Set<BlockPos> out = new HashSet<>();
        for (int x = -ceil; x <= ceil; x++)
            for (int y = -ceil; y <= ceil; y++)
                for (int z = -ceil; z <= ceil; z++) {
                    double d = (double) x * x + (double) y * y + (double) z * z;
                    if (d <= outerSq && d >= innerSq) out.add(center.offset(x, y, z));
                }
        return out;
    }

    private void updateAdvancedNear(BlockPos changed) {
        double rangeSq = CALIBRATED_RANGE * CALIBRATED_RANGE;
        for (SensorData s : sensors) {
            if (s.type == SensorType.SHRIEKER) continue;
            if (s.pos.distSqr(changed) <= rangeSq) {
                s.hasRedstoneOutput = hasRedstoneOutput(mc.level, s.pos);
                s.hasShriekerInRange = hasShriekerInRange(mc.level, s.pos);
            }
        }
    }

    private boolean isRedstoneComponent(BlockState state) {
        Block b = state.getBlock();
        return b == Blocks.REDSTONE_WIRE || b instanceof ComparatorBlock || b instanceof ObserverBlock;
    }

    private boolean hasRedstoneOutput(Level world, BlockPos pos) {
        for (Direction dir : DIRECTIONS) {
            BlockPos adj = pos.relative(dir);
            BlockState state = world.getBlockState(adj);
            Block block = state.getBlock();
            if (block == Blocks.REDSTONE_WIRE) return true;
            if (block instanceof ComparatorBlock
                && adj.relative(state.getValue(ComparatorBlock.FACING).getOpposite()).equals(pos)) return true;
            if (block instanceof ObserverBlock
                && adj.relative(state.getValue(ObserverBlock.FACING)).equals(pos)) return true;
        }
        return false;
    }

    private boolean hasShriekerInRange(Level world, BlockPos pos) {
        for (BlockPos p : BlockPos.withinManhattan(pos, 16, 16, 16))
            if (world.getBlockState(p).getBlock() instanceof SculkShriekerBlock) return true;
        return false;
    }

    private boolean isSolidVoxel(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        return !state.isAir() && state.canOcclude();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.level == null || mc.player == null) return;

        // Cache perframe constants outside the loop.
        Vec3 playerPos = mc.player.position();
        OcclusionMode oMode = occlusionMode.get();
        Vec3 eyePos = oMode == OcclusionMode.Accurate ? mc.player.getEyePosition() : null;
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
            if (onlyRenderImpactful.get() && sensor.type != SensorType.SHRIEKER && advancedView.get()
                && !sensor.hasRedstoneOutput && !sensor.hasShriekerInRange) continue;
            if (playerPos.distanceTo(Vec3.atCenterOf(sensor.pos)) > maxDist) continue;

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
        if (sensor.type == SensorType.SHRIEKER) return shriekerRangeColor.get();
        if (sensor.type == SensorType.NORMAL && !advancedView.get()) return normalSculkColor.get();
        if (!advancedView.get()) return sphereColor.get();
        if (sensor.hasRedstoneOutput && sensor.hasShriekerInRange)
            return System.currentTimeMillis() / 500 % 2 == 0 ? redstoneColor.get() : shriekerNearColor.get();
        if (sensor.hasRedstoneOutput) return redstoneColor.get();
        if (sensor.hasShriekerInRange) return shriekerNearColor.get();
        return sensor.type == SensorType.NORMAL ? normalSculkColor.get() : sphereColor.get();
    }

    private boolean passesOcclusion(BlockPos pos, OcclusionMode mode, Vec3 eye) {
        if (mode == OcclusionMode.None) return true;
        for (Direction dir : DIRECTIONS) {
            BlockState nb = mc.level.getBlockState(pos.relative(dir));
            if (!nb.isAir() && nb.canOcclude()) continue;   // this face is blocked by a solid world block
            if (mode == OcclusionMode.Simple) return true;
            // Accurate: the eye-to-face vector must align with the face normal.
            double fx = pos.getX() + 0.5 + dir.getStepX() * 0.5;
            double fy = pos.getY() + 0.5 + dir.getStepY() * 0.5;
            double fz = pos.getZ() + 0.5 + dir.getStepZ() * 0.5;
            double tx = fx - eye.x, ty = fy - eye.y, tz = fz - eye.z;
            double len = Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (len > 1e-9) {
                tx /= len;
                ty /= len;
                tz /= len;
            }
            if (tx * dir.getStepX() + ty * dir.getStepY() + tz * dir.getStepZ() > 0) return true;
        }
        return false;
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!showActivationPower.get() || mc.level == null || mc.player == null) return;

        for (SensorData sensor : sensors) {
            if (sensor.type == SensorType.SHRIEKER) continue;

            BlockState state = mc.level.getBlockState(sensor.pos);
            if (!(state.getBlock() instanceof SculkSensorBlock)) continue;
            if (state.getValue(SculkSensorBlock.PHASE) != SculkSensorPhase.ACTIVE) continue;

            int power = state.getValue(SculkSensorBlock.POWER);
            String powerText = "Power " + power;

            // Calibrated sensors output the triggering event's vanilla note-block
            // frequency as their power, independent of distance — this is exact,
            // not a guess. Normal sensors only encode distance in power, so there's
            // nothing meaningful to show beyond the power value itself.
            String freqText = sensor.type == SensorType.CALIBRATED ? exactFrequencyLabel(power) : null;

            Vector3d vec3 = new Vector3d(sensor.pos.getX() + 0.5, sensor.pos.getY() + 1.3, sensor.pos.getZ() + 0.5);
            if (NametagUtils.to2D(vec3, vibrationTextScale.get())) {
                NametagUtils.begin(vec3, event.graphics);
                TextRenderer.get().begin(1, false, true);

                double powerWidth = TextRenderer.get().getWidth(powerText);
                double lineHeight = TextRenderer.get().getHeight();

                Color powerColor = vibrationTextColor.get();
                if (freqText != null) {
                    // Two lines: power above, exact frequency below, small gap between them.
                    TextRenderer.get().render(powerText, -powerWidth / 2, -lineHeight - 1, powerColor, true);

                    double freqWidth = TextRenderer.get().getWidth(freqText);
                    TextRenderer.get().render(freqText, -freqWidth / 2, 1, frequencyTextColor.get(), true);
                } else {
                    TextRenderer.get().render(powerText, -powerWidth / 2, -lineHeight / 2, powerColor, true);
                }

                TextRenderer.get().end();
                NametagUtils.end(event.graphics);
            }
        }
    }
}
