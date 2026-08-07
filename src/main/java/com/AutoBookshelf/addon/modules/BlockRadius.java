package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.utils.DistanceUtil;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.monster.creaking.Creaking;
import net.minecraft.world.entity.monster.warden.AngerLevel;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ConduitBlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.awt.*;
import java.util.*;
import java.util.List;

public class BlockRadius extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgCreaking = settings.createGroup("Creaking");
    private final SettingGroup sgWarden = settings.createGroup("Warden");

    private final Setting<Boolean> showBeacons = sgGeneral.add(new BoolSetting.Builder()
        .name("show-beacons")
        .description("Renders the range of powered beacons.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showLightningRods = sgGeneral.add(new BoolSetting.Builder()
        .name("show-lightning-rods")
        .description("Renders the range of a lightning rods in a flat square.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showConduits = sgGeneral.add(new BoolSetting.Builder()
        .name("show-conduits")
        .description("Renders the Conduit Power range of active conduits as a flat hollow sphere ring.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showConduitMobRange = sgGeneral.add(new BoolSetting.Builder()
        .name("show-conduit-mob-range")
        .description("Also renders the 8-block mob-attack of fully activated conduits (42 prism blocks).")
        .defaultValue(true)
        .visible(showConduits::get)
        .build()
    );

    private final Setting<Boolean> cullOverlapping = sgGeneral.add(new BoolSetting.Builder()
        .name("cull-overlapping")
        .description("Hides a beacon box fully contained inside a higher-level beacon's box.")
        .defaultValue(true)
        .visible(showBeacons::get)
        .build()
    );

    private final Setting<Integer> updateInterval = sgGeneral.add(new IntSetting.Builder()
        .name("update-interval")
        .description("How often (in ticks) blocks/wardens/creakings are rescanned. Raise for better performance.")
        .defaultValue(20)
        .range(2, 200)
        .build()
    );

    private final Setting<Integer> searchRadius = sgGeneral.add(new IntSetting.Builder()
        .name("search-radius")
        .description("Horizontal search radius in chunks.")
        .defaultValue(6)
        .range(1, 16)
        .build()
    );

    private final Setting<Double> lightningRodRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("lightning-rod-range")
        .description("Redirect radius in blocks of a lightning rod (128 in Java, 64 in Bedrock Edition).")
        .defaultValue(128)
        .range(1, 128)
        .visible(showLightningRods::get)
        .build()
    );

    private final Setting<Double> maxRenderDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-render-distance")
        .description("Boxes farther than this from the camera are skipped.")
        .defaultValue(256.0)
        .range(16, 1024)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> beaconSideColor = sgRender.add(new ColorSetting.Builder()
        .name("beacon-side-color")
        .description("Side color of beacon range boxes.")
        .defaultValue(new SettingColor(0, 255, 255, 60))
        .visible(() -> shapeMode.get().sides() && showBeacons.get())
        .build()
    );

    private final Setting<SettingColor> beaconLineColor = sgRender.add(new ColorSetting.Builder()
        .name("beacon-line-color")
        .description("Line color of beacon range boxes.")
        .defaultValue(new SettingColor(0, 255, 255, 255))
        .visible(() -> shapeMode.get().lines() && showBeacons.get())
        .build()
    );

    private final Setting<SettingColor> lightningRodSideColor = sgRender.add(new ColorSetting.Builder()
        .name("lightning-rod-side-color")
        .description("Side color of the lightning rod 1-block-tall footprint.")
        .defaultValue(new SettingColor(255, 200, 0, 45))
        .visible(() -> shapeMode.get().sides() && showLightningRods.get())
        .build()
    );

    private final Setting<SettingColor> lightningRodLineColor = sgRender.add(new ColorSetting.Builder()
        .name("lightning-rod-line-color")
        .description("Line color of the lightning rod 1-block-tall footprint.")
        .defaultValue(new SettingColor(255, 200, 0, 255))
        .visible(() -> shapeMode.get().lines() && showLightningRods.get())
        .build()
    );

    private final Setting<SettingColor> conduitSideColor = sgRender.add(new ColorSetting.Builder()
        .name("conduit-side-color")
        .description("Side color of conduit Conduit Power range footprint.")
        .defaultValue(new SettingColor(0, 150, 255, 40))
        .visible(() -> shapeMode.get().sides() && showConduits.get())
        .build()
    );

    private final Setting<SettingColor> conduitLineColor = sgRender.add(new ColorSetting.Builder()
        .name("conduit-line-color")
        .description("Line color of conduit Conduit Power range footprint.")
        .defaultValue(new SettingColor(0, 150, 255, 255))
        .visible(() -> shapeMode.get().lines() && showConduits.get())
        .build()
    );

    private final Setting<SettingColor> conduitMobSideColor = sgRender.add(new ColorSetting.Builder()
        .name("conduit-mob-side-color")
        .description("Side color of the 8-block mob-attack footprint (fully activated conduit only).")
        .defaultValue(new SettingColor(255, 60, 60, 50))
        .visible(() -> shapeMode.get().sides() && showConduits.get() && showConduitMobRange.get())
        .build()
    );

    private final Setting<SettingColor> conduitMobLineColor = sgRender.add(new ColorSetting.Builder()
        .name("conduit-mob-line-color")
        .description("Line color of the 8-block mob-attack footprint (fully activated conduit only).")
        .defaultValue(new SettingColor(255, 60, 60, 255))
        .visible(() -> shapeMode.get().lines() && showConduits.get() && showConduitMobRange.get())
        .build()
    );

    private final Setting<Boolean> showCreakings = sgCreaking.add(new BoolSetting.Builder()
        .name("show-creakings")
        .description("Highlights linked creakings and their creaking heart in matching per-mob colors.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showCreakingConnection = sgCreaking.add(new BoolSetting.Builder()
        .name("show-creaking-connection")
        .description("Draws a line from each creaking to its linked heart block.")
        .defaultValue(true)
        .visible(showCreakings::get)
        .build()
    );

    private final Setting<Integer> creakingAlpha = sgCreaking.add(new IntSetting.Builder()
        .name("creaking-alpha")
        .description("Side fill alpha for creaking/heart ESP boxes.")
        .defaultValue(60)
        .range(0, 255)
        .visible(showCreakings::get)
        .build()
    );

    private final Setting<Double> creakingSaturation = sgCreaking.add(new DoubleSetting.Builder()
        .name("creaking-color-saturation")
        .description("Saturation of the auto-generated per-creaking colors.")
        .defaultValue(0.85)
        .range(0, 1)
        .visible(showCreakings::get)
        .build()
    );

    private final Setting<Double> creakingBrightness = sgCreaking.add(new DoubleSetting.Builder()
        .name("creaking-color-brightness")
        .description("Brightness of the auto-generated per-creaking colors.")
        .defaultValue(1.0)
        .range(0, 1)
        .visible(showCreakings::get)
        .build()
    );

    private final Setting<Boolean> showWardens = sgWarden.add(new BoolSetting.Builder()
        .name("show-wardens")
        .description("Enables Warden ESP: color-coded shapes, labels and tracers.")
        .defaultValue(true)
        .build()
    );

    private final Setting<WardenShape> wardenShape = sgWarden.add(new EnumSetting.Builder<WardenShape>()
        .name("warden-shape")
        .description("Warden ESP shape style.")
        .defaultValue(WardenShape.Box)
        .visible(showWardens::get)
        .build()
    );

    private final Setting<Boolean> fillWardenShapes = sgWarden.add(new BoolSetting.Builder()
        .name("warden-fill-shapes")
        .description("Render filled versions of the Warden ESP shapes.")
        .defaultValue(false)
        .visible(showWardens::get)
        .build()
    );

    private final Setting<WardenTracerMode> wardenTracerMode = sgWarden.add(new EnumSetting.Builder<WardenTracerMode>()
        .name("warden-tracers")
        .description("When to draw tracers to wardens.")
        .defaultValue(WardenTracerMode.Targeting)
        .visible(showWardens::get)
        .build()
    );

    private final Setting<Boolean> wardenTracerFlash = sgWarden.add(new BoolSetting.Builder()
        .name("warden-tracer-flash")
        .description("Make warden tracers pulse with a smooth fade.")
        .defaultValue(false)
        .visible(showWardens::get)
        .build()
    );

    private final Setting<Boolean> showWardenTargetLabel = sgWarden.add(new BoolSetting.Builder()
        .name("warden-target-label")
        .description("Show TARGET: YOU/OTHER label.")
        .defaultValue(true)
        .visible(showWardens::get)
        .build()
    );

    private final Setting<Boolean> showSniffingPulse = sgWarden.add(new BoolSetting.Builder()
        .name("warden-sniff-pulse")
        .description("Show a brief pulsing overlay when the Warden is sniffing.")
        .defaultValue(true)
        .visible(showWardens::get)
        .build()
    );

    private final Setting<Boolean> showImminentIndicator = sgWarden.add(new BoolSetting.Builder()
        .name("warden-combat-imminence")
        .description("Show an indicator when combat/sonic attack is imminent.")
        .defaultValue(true)
        .visible(showWardens::get)
        .build()
    );

    private final Setting<Boolean> showAngerTrend = sgWarden.add(new BoolSetting.Builder()
        .name("warden-anger-trend")
        .description("Show a simple up/down indicator when anger changes.")
        .defaultValue(false)
        .visible(showWardens::get)
        .build()
    );

    private final Setting<Double> wardenLabelScale = sgWarden.add(new DoubleSetting.Builder()
        .name("warden-label-scale")
        .description("Scale of the world-space Warden labels.")
        .defaultValue(1.2)
        .min(0.7)
        .sliderRange(0.7, 10.0)
        .visible(showWardens::get)
        .build()
    );

    private final Setting<SettingColor> wardenCalmColor = sgWarden.add(new ColorSetting.Builder()
        .name("warden-calm-color")
        .description("Color when the warden is calm.")
        .defaultValue(new SettingColor(85, 255, 85))
        .visible(showWardens::get)
        .build()
    );

    private final Setting<SettingColor> wardenSearchingColor = sgWarden.add(new ColorSetting.Builder()
        .name("warden-searching-color")
        .description("Color when the warden has nonzero anger but isn't agitated/locked.")
        .defaultValue(new SettingColor(255, 255, 85))
        .visible(showWardens::get)
        .build()
    );

    private final Setting<SettingColor> wardenAgitatedColor = sgWarden.add(new ColorSetting.Builder()
        .name("warden-agitated-color")
        .description("Color when sniffing or agitated.")
        .defaultValue(new SettingColor(255, 170, 0))
        .visible(showWardens::get)
        .build()
    );

    private final Setting<SettingColor> wardenLockedColor = sgWarden.add(new ColorSetting.Builder()
        .name("warden-locked-color")
        .description("Color when the warden has a target or is angry.")
        .defaultValue(new SettingColor(255, 85, 85))
        .visible(showWardens::get)
        .build()
    );

    private final Setting<SettingColor> wardenDiggingColor = sgWarden.add(new ColorSetting.Builder()
        .name("warden-digging-color")
        .description("Color when digging or emerging.")
        .defaultValue(new SettingColor(85, 255, 255))
        .visible(showWardens::get)
        .build()
    );

    private final Setting<SettingColor> wardenOtherTargetColor = sgWarden.add(new ColorSetting.Builder()
        .name("warden-other-target-color")
        .description("Color for the 'TARGET: OTHER' label.")
        .defaultValue(new SettingColor(170, 170, 170))
        .visible(showWardens::get)
        .build()
    );

    private static final int[][] FRAME_OFFSETS = buildFrameOffsets();
    private static final Set<Block> LIGHTNING_ROD_VARIANTS = Set.of(
        Blocks.LIGHTNING_ROD,
        Blocks.EXPOSED_LIGHTNING_ROD,
        Blocks.WEATHERED_LIGHTNING_ROD,
        Blocks.OXIDIZED_LIGHTNING_ROD,
        Blocks.WAXED_LIGHTNING_ROD,
        Blocks.WAXED_EXPOSED_LIGHTNING_ROD,
        Blocks.WAXED_WEATHERED_LIGHTNING_ROD,
        Blocks.WAXED_OXIDIZED_LIGHTNING_ROD
    );

    // Hue step between successive creaking colours; the golden angle maximizes hue
    // separation for any number of mobs without needing a fixed palette.
    private static final double GOLDEN_ANGLE = 137.50776;

    /**
     * An axis-aligned bounding box used for all flat footprints and beacon cubes.
     */
    private static class RangeBox {
        final double minX, minY, minZ, maxX, maxY, maxZ;
        int level; // beacon pyramid level; 0 for all other boxes
        boolean render = true; // culling flag (beacons only)

        RangeBox(double x1, double y1, double z1, double x2, double y2, double z2) {
            this.minX = Math.min(x1, x2);
            this.maxX = Math.max(x1, x2);
            this.minY = Math.min(y1, y2);
            this.maxY = Math.max(y1, y2);
            this.minZ = Math.min(z1, z2);
            this.maxZ = Math.max(z1, z2);
        }

        boolean contains(RangeBox o) {
            return minX <= o.minX && maxX >= o.maxX
                && minY <= o.minY && maxY >= o.maxY
                && minZ <= o.minZ && maxZ >= o.maxZ;
        }

        double distanceSq(Vec3 p) {
            return DistanceUtil.distanceSqToBox(p, minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    private static class ConduitData {
        final List<RangeBox> footprintRows;   // voxel circle rows (always present, never empty)
        final RangeBox footprintBounds; // AABB enclosing all footprintRows
        final RangeBox mobBox;          // 8-block mob-attack footprint (non-null when fullFrame)

        ConduitData(List<RangeBox> footprintRows, RangeBox footprintBounds, RangeBox mobBox) {
            this.footprintRows = footprintRows;
            this.footprintBounds = footprintBounds;
            this.mobBox = mobBox;
        }
    }

    private static class CreakingLink {
        final Creaking entity;
        final RangeBox heartBox; // heart position never moves, so this is built exactly once
        final Vec3 heartCenter; // cached from heartBox,
        final SettingColor sideColor;
        final SettingColor lineColor;

        CreakingLink(Creaking entity, BlockPos heartPos, SettingColor sideColor, SettingColor lineColor) {
            this.entity = entity;
            this.heartBox = new RangeBox(
                heartPos.getX(), heartPos.getY(), heartPos.getZ(),
                heartPos.getX() + 1.0, heartPos.getY() + 1.0, heartPos.getZ() + 1.0
            );
            this.heartCenter = new Vec3(
                heartPos.getX() + 0.5, heartPos.getY() + 0.5, heartPos.getZ() + 0.5
            );
            this.sideColor = sideColor;
            this.lineColor = lineColor;
        }
    }

    private record WardenState(SettingColor baseColor, String label, boolean locked,
                               boolean sniffing, boolean digging, boolean emerging,
                               boolean lockedOnYou, boolean lockedOnOther, boolean hasTarget) {
    }

    private final List<RangeBox> beaconBoxes = new ArrayList<>();
    private final List<RangeBox> lightningRodBoxes = new ArrayList<>();
    private final List<ConduitData> conduitDatas = new ArrayList<>();
    private final Map<Integer, CreakingLink> creakingLinks = new HashMap<>();
    private int creakingColorCounter = 0;

    // Warden ESP state. wardens is refreshed on the same throttled cadence as the
    // other scans (see onTick); per-frame anger/pose/animation state is read live
    // off the entities in onRender since that doesn't need a full world scan.
    private final List<LivingEntity> wardens = new ArrayList<>();
    private final Map<Integer, Integer> lastAnger = new HashMap<>();
    private final Map<Integer, Long> attackExpiry = new HashMap<>();

    /**
     * Reused mutable pos to avoid allocating a new BlockPos for every block checked.
     */
    private final BlockPos.MutableBlockPos scanPos = new BlockPos.MutableBlockPos();

    private int tickCounter = 0;

    public BlockRadius() {
        super(Addon.CATEGORY, "Block-Radius",
            "Renders the range of powered beacons, lightning rods, active conduits, linked creakings, and Warden ESP.");
    }

    @Override
    public void onActivate() {
        // Force an immediate scan on the first tick instead of waiting a full interval.
        tickCounter = updateInterval.get() - 1;
        wardens.clear();
        lastAnger.clear();
        attackExpiry.clear();
    }

    @Override
    public void onDeactivate() {
        beaconBoxes.clear();
        lightningRodBoxes.clear();
        conduitDatas.clear();
        creakingLinks.clear();
        creakingColorCounter = 0;
        wardens.clear();
        lastAnger.clear();
        attackExpiry.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null) return;
        if (++tickCounter < updateInterval.get()) return;
        tickCounter = 0;

        scanBeacons();
        scanLightningRods();
        scanConduits();
        scanCreakings();
        scanWardens();
    }

    private void scanBeacons() {
        beaconBoxes.clear();
        if (!showBeacons.get()) return;

        for (BlockEntity be : Utils.blockEntities()) {
            if (!(be instanceof BeaconBlockEntity)) continue;

            BlockPos pos = be.getBlockPos();
            int level = getBeaconLevel(pos);
            if (level < 1) continue;

            double range = 10 + (level * 10);
            RangeBox box = new RangeBox(
                pos.getX() - range, pos.getY() - range, pos.getZ() - range,
                pos.getX() + range, pos.getY() + range, pos.getZ() + range
            );
            box.level = level;
            beaconBoxes.add(box);
        }

        // O(n²) containment cull; runs once per scan, not per frame.
        for (RangeBox box : beaconBoxes) {
            box.render = true;
            if (!cullOverlapping.get()) continue;
            for (RangeBox other : beaconBoxes) {
                if (box == other) continue;
                if (other.level > box.level && other.contains(box)) {
                    box.render = false;
                    break;
                }
            }
        }
    }

    private int getBeaconLevel(BlockPos beaconPos) {
        int level = 0;
        for (int y = 1; y <= 4; y++) {
            int layerY = beaconPos.getY() - y;
            if (layerY < mc.level.getMinY()) break;

            boolean valid = true;
            outer:
            for (int x = -y; x <= y; x++) {
                for (int z = -y; z <= y; z++) {
                    scanPos.set(beaconPos.getX() + x, layerY, beaconPos.getZ() + z);
                    if (!isValidBeaconBase(mc.level.getBlockState(scanPos).getBlock())) {
                        valid = false;
                        break outer;
                    }
                }
            }
            if (!valid) break;
            level++;
        }
        return level;
    }

    private boolean isValidBeaconBase(Block b) {
        return b == Blocks.IRON_BLOCK || b == Blocks.GOLD_BLOCK ||
            b == Blocks.EMERALD_BLOCK || b == Blocks.DIAMOND_BLOCK ||
            b == Blocks.NETHERITE_BLOCK;
    }

    private void scanLightningRods() {
        lightningRodBoxes.clear();
        if (!showLightningRods.get()) return;

        ClientLevel world = mc.level;
        BlockPos playerPos = mc.player.blockPosition();
        int centerChunkX = playerPos.getX() >> 4;
        int centerChunkZ = playerPos.getZ() >> 4;
        int radius = searchRadius.get();
        double range = lightningRodRange.get();
        int bottomY = world.getMinY();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;

                if (!world.getChunkSource().hasChunk(chunkX, chunkZ)) continue;

                ChunkAccess chunk = world.getChunk(chunkX, chunkZ);
                LevelChunkSection[] sections = chunk.getSections();

                // Iterate every section
                for (int s = 0; s < sections.length; s++) {
                    LevelChunkSection section = sections[s];
                    if (section.hasOnlyAir()) continue; // skip fully-air sections fast

                    int sectionBaseY = bottomY + (s << 4);

                    for (int lx = 0; lx < 16; lx++) {
                        for (int lz = 0; lz < 16; lz++) {
                            for (int ly = 0; ly < 16; ly++) {
                                if (!LIGHTNING_ROD_VARIANTS.contains(section.getBlockState(lx, ly, lz).getBlock()))
                                    continue;

                                int worldX = (chunkX << 4) + lx;
                                int worldY = sectionBaseY + ly;
                                int worldZ = (chunkZ << 4) + lz;

                                // minY = bottom face of rod block, maxY = top face.
                                lightningRodBoxes.add(new RangeBox(
                                    worldX - range, worldY, worldZ - range,
                                    worldX + range, worldY + 1.0, worldZ + range
                                ));
                            }
                        }
                    }
                }
            }
        }
    }

    private void scanConduits() {
        conduitDatas.clear();
        if (!showConduits.get()) return;

        for (BlockEntity be : Utils.blockEntities()) {
            if (!(be instanceof ConduitBlockEntity)) continue;

            BlockPos pos = be.getBlockPos();
            int frameCount = countConduitFrame(pos);
            if (frameCount < 16) continue; // not yet activated

            int range = (int) Math.floor(frameCount / 7.0) * 16;
            double cx = pos.getX();
            double cy = pos.getY();
            double cz = pos.getZ();
            boolean fullFrame = frameCount >= 42;

            // Voxelized circular footprint (Euclidean/spherical distance), 1 block tall.
            List<RangeBox> footprintRows = buildCircularFootprint(cx, cy, cz, range);

            // Cheap AABB covering the whole footprint, used to cull the entire conduit
            // with one distance check instead of one per row box (see onRender).
            RangeBox footprintBounds = new RangeBox(
                cx - range, cy, cz - range,
                cx + range, cy + 1.0, cz + range
            );

            // Mob-attack footprint (8-block radius, still square — unchanged) — only for
            // fully activated conduits.
            RangeBox mobBox = null;
            if (fullFrame && showConduitMobRange.get()) {
                mobBox = new RangeBox(
                    cx - 8, cy, cz - 8,
                    cx + 8, cy + 1.0, cz + 8
                );
            }

            conduitDatas.add(new ConduitData(footprintRows, footprintBounds, mobBox));
        }
    }

    private List<RangeBox> buildCircularFootprint(double centerX, double centerY, double centerZ, int range) {
        List<RangeBox> rows = new ArrayList<>(range * 4 + 2);

        int blockX = (int) Math.floor(centerX);
        int blockZ = (int) Math.floor(centerZ);
        long outerRangeSq = (long) range * range;
        int innerRange = range - 1;
        long innerRangeSq = (long) innerRange * innerRange;

        for (int oz = -range; oz <= range; oz++) {
            long outerRemaining = outerRangeSq - (long) oz * oz;
            if (outerRemaining < 0) continue; // out of range at this row (only possible at the extreme ends)
            int oxOuter = (int) Math.floor(Math.sqrt((double) outerRemaining));

            if (innerRange < 0 || Math.abs(oz) > innerRange) {
                // This row never reaches the inner disc, so its entire outer span is shell.
                addFootprintRow(rows, blockX - oxOuter, blockX + oxOuter, blockZ + oz, centerY);
                continue;
            }

            int oxInner = (int) Math.floor(Math.sqrt((double) (innerRangeSq - (long) oz * oz)));

            // Left shell segment: outer edge up to (but not including) the inner disc.
            addFootprintRow(rows, -oxOuter + blockX, -oxInner - 1 + blockX, blockZ + oz, centerY);
            // Right shell segment: mirror of the left segment.
            addFootprintRow(rows, oxInner + 1 + blockX, oxOuter + blockX, blockZ + oz, centerY);
        }

        return rows;
    }

    private void addFootprintRow(List<RangeBox> rows, int xStart, int xEnd, int blockZ, double centerY) {
        if (xStart > xEnd) return;
        rows.add(new RangeBox(
            xStart, centerY, blockZ,
            xEnd + 1.0, centerY + 1.0, blockZ + 1.0
        ));
    }

    /**
     * Counts how many of the 42 conduit frame positions contain a valid prismarine block.
     */
    private int countConduitFrame(BlockPos conduitPos) {
        int count = 0;
        for (int[] off : FRAME_OFFSETS) {
            scanPos.set(conduitPos.getX() + off[0],
                conduitPos.getY() + off[1],
                conduitPos.getZ() + off[2]);
            if (isValidConduitFrame(mc.level.getBlockState(scanPos).getBlock())) count++;
        }
        return count;
    }

    private boolean isValidConduitFrame(Block b) {
        return b == Blocks.PRISMARINE ||
            b == Blocks.DARK_PRISMARINE ||
            b == Blocks.PRISMARINE_BRICKS ||
            b == Blocks.SEA_LANTERN;
    }

    private void scanCreakings() {
        if (!showCreakings.get()) {
            creakingLinks.clear();
            return;
        }

        Set<Integer> seen = new HashSet<>();

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Creaking creaking)) continue;

            BlockPos heartPos = creaking.getHomePos();
            if (heartPos == null) continue;

            seen.add(entity.getId());

            // computeIfAbsent: colour + heart box are built exactly once per creaking,
            // never rebuilt while it's alive.
            creakingLinks.computeIfAbsent(entity.getId(), id -> {
                float hue = (float) ((creakingColorCounter++ * GOLDEN_ANGLE) % 360.0) / 360f;
                int rgb = Color.HSBtoRGB(hue, creakingSaturation.get().floatValue(), creakingBrightness.get().floatValue());
                Color c = new Color(rgb);
                return new CreakingLink(
                    creaking, heartPos,
                    new SettingColor(c.getRed(), c.getGreen(), c.getBlue(), creakingAlpha.get()),
                    new SettingColor(c.getRed(), c.getGreen(), c.getBlue(), 255)
                );
            });
        }

        // O(tracked creakings) clean-up, not a full rebuild.
        creakingLinks.keySet().removeIf(id -> !seen.contains(id));
    }

    private void scanWardens() {
        wardens.clear();
        if (!showWardens.get()) {
            lastAnger.clear();
            attackExpiry.clear();
            return;
        }

        Set<Integer> seen = new HashSet<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity le)) continue;
            if (e.isRemoved() || le.getHealth() <= 0) continue;
            if (e instanceof Warden) {
                wardens.add(le);
                seen.add(e.getId());
            }
        }

        // Drop tracking state for wardens that despawned/died between scans.
        lastAnger.keySet().removeIf(id -> !seen.contains(id));
        attackExpiry.keySet().removeIf(id -> !seen.contains(id));
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.level == null) return;

        Vec3 cam = mc.gameRenderer.getMainCamera().position();
        double maxDistSq = maxRenderDistance.get() * maxRenderDistance.get();

        // Beacons
        if (showBeacons.get()) {
            for (RangeBox box : beaconBoxes) {
                if (!box.render) continue;
                if (box.distanceSq(cam) > maxDistSq) continue;
                renderBox(event.renderer,
                    box.minX, box.minY, box.minZ,
                    box.maxX, box.maxY, box.maxZ,
                    beaconSideColor.get(), beaconLineColor.get());
            }
        }

        if (showLightningRods.get()) {
            for (RangeBox box : lightningRodBoxes) {
                if (box.distanceSq(cam) > maxDistSq) continue;
                renderBox(event.renderer,
                    box.minX, box.minY, box.minZ,
                    box.maxX, box.maxY, box.maxZ,
                    lightningRodSideColor.get(), lightningRodLineColor.get());
            }
        }

        if (showConduits.get()) {
            for (ConduitData data : conduitDatas) {
                // One distance check culls every row of this conduit's footprint at once,
                // instead of testing each of the up-to-193 row boxes against the camera.
                if (data.footprintBounds.distanceSq(cam) <= maxDistSq) {
                    for (RangeBox row : data.footprintRows) {
                        renderBox(event.renderer,
                            row.minX, row.minY, row.minZ,
                            row.maxX, row.maxY, row.maxZ,
                            conduitSideColor.get(), conduitLineColor.get());
                    }
                }

                if (showConduitMobRange.get() && data.mobBox != null) {
                    RangeBox mb = data.mobBox;
                    if (mb.distanceSq(cam) <= maxDistSq) {
                        renderBox(event.renderer,
                            mb.minX, mb.minY, mb.minZ,
                            mb.maxX, mb.maxY, mb.maxZ,
                            conduitMobSideColor.get(), conduitMobLineColor.get());
                    }
                }
            }
        }

        if (showCreakings.get()) {
            for (CreakingLink link : creakingLinks.values()) {
                if (link.entity.isRemoved()) continue;

                AABB box = link.entity.getBoundingBox();
                boolean entityVisible = cam.distanceToSqr(box.getCenter()) <= maxDistSq;
                boolean heartVisible = link.heartBox.distanceSq(cam) <= maxDistSq;

                if (entityVisible) {
                    renderBox(event.renderer,
                        box.minX, box.minY, box.minZ,
                        box.maxX, box.maxY, box.maxZ,
                        link.sideColor, link.lineColor);
                }

                if (heartVisible) {
                    renderBox(event.renderer,
                        link.heartBox.minX, link.heartBox.minY, link.heartBox.minZ,
                        link.heartBox.maxX, link.heartBox.maxY, link.heartBox.maxZ,
                        link.sideColor, link.lineColor);
                }

                if (showCreakingConnection.get() && (entityVisible || heartVisible)) {
                    Vec3 entityCenter = box.getCenter();
                    event.renderer.line(
                        entityCenter.x, entityCenter.y, entityCenter.z,
                        link.heartCenter.x, link.heartCenter.y, link.heartCenter.z,
                        link.lineColor);
                }
            }
        }

        if (showWardens.get() && !wardens.isEmpty()) {
            renderWardens(event);
        }
    }

    private void renderWardens(Render3DEvent event) {
        WardenShape currentShape = wardenShape.get();
        boolean drawShape = currentShape != WardenShape.None;
        boolean drawFill = drawShape && fillWardenShapes.get();

        for (LivingEntity w : wardens) {
            try {
                if (!(w instanceof Warden warden)) continue;

                // EntityUtils has no lerped-box helper; interpolate manually.
                Vec3 lerpedPos = w.getPosition(event.tickDelta);
                AABB lerped = w.getBoundingBox().move(lerpedPos.subtract(w.position()));

                int anger = warden.getClientAngerLevel();
                Integer prev = lastAnger.get(w.getId());
                lastAnger.put(w.getId(), anger);
                AngerLevel angriness = AngerLevel.byAnger(anger);

                WardenState state = classifyWarden(warden, anger, angriness);
                SettingColor color = state.baseColor();

                boolean sonicCharge = warden.sonicBoomAnimationState.isStarted();
                boolean attackAnim = warden.attackAnimationState.isStarted();
                boolean imminent = sonicCharge || attackAnim;

                long nowTick = warden.tickCount;
                if (imminent)
                    attackExpiry.put(w.getId(), nowTick + 100L);
                long expiry = attackExpiry.getOrDefault(w.getId(), 0L);
                boolean attackFlash = nowTick <= expiry && !state.digging() && !state.emerging();

                if (attackFlash)
                    color = getFastPulseColor(wardenLockedColor.get(), 0.35F);

                if (drawShape) {
                    int fillAlpha = drawFill ? 40 : 0;
                    SettingColor boxColor = drawFill
                        ? new SettingColor(color.r, color.g, color.b, fillAlpha)
                        : color;
                    meteordevelopment.meteorclient.renderer.ShapeMode mode = drawFill
                        ? meteordevelopment.meteorclient.renderer.ShapeMode.Both
                        : meteordevelopment.meteorclient.renderer.ShapeMode.Lines;

                    switch (currentShape) {
                        case Box -> event.renderer.box(lerped, boxColor, color, mode, 0);
                        // No octahedron primitive in Meteor; falls back to box.
                        case Octahedron -> event.renderer.box(lerped, boxColor, color, mode, 0);
                        default -> {
                        }
                    }
                }

                boolean drawTracer = switch (wardenTracerMode.get()) {
                    case Always -> true;
                    case Targeting -> state.hasTarget() || state.locked() || imminent;
                    default -> false;
                };
                if (drawTracer) {
                    SettingColor tracerColor;
                    if (attackFlash)
                        tracerColor = getFastPulseColor(wardenLockedColor.get(), 0.35F);
                    else if (state.lockedOnYou())
                        tracerColor = getPulseColor(wardenLockedColor.get(), 0.55F);
                    else
                        tracerColor = color;
                    if (wardenTracerFlash.get())
                        tracerColor = flashColor(tracerColor);

                    Vec3 eyes = mc.player.getEyePosition(event.tickDelta);
                    Vec3 center = lerped.getCenter();
                    event.renderer.line(eyes.x, eyes.y, eyes.z,
                        center.x, center.y, center.z, tracerColor);
                }

                if (state.sniffing() && showSniffingPulse.get()) {
                    float pulse = (float) (0.5
                        + 0.5 * Math.sin(System.currentTimeMillis() / 200.0));
                    int alpha = (int) (clamp01(pulse) * 255);
                    SettingColor fill = new SettingColor(
                        wardenAgitatedColor.get().r, wardenAgitatedColor.get().g,
                        wardenAgitatedColor.get().b, alpha);
                    event.renderer.box(lerped, fill, fill, meteordevelopment.meteorclient.renderer.ShapeMode.Sides, 0);
                }

                float labelScaleValue = wardenLabelScale.get().floatValue();
                float lineSpacing = 12F * (0.9F + 0.1F * labelScaleValue);
                float baseOffset = (float) (lerped.getYsize() / 2.0 + 0.55
                    + 0.25 * (labelScaleValue - 1.0));
                int line = 0;

                if (attackFlash && showImminentIndicator.get()) {
                    drawWorldLabel(event.matrices, "! ATTACK",
                        lerped.getCenter().x, lerped.getCenter().y + baseOffset,
                        lerped.getCenter().z, wardenLockedColor.get(),
                        1.15F * labelScaleValue, -lineSpacing * line, event.tickDelta);
                    line += 1;
                }

                drawWorldLabel(event.matrices, state.label(), lerped.getCenter().x,
                    lerped.getCenter().y + baseOffset, lerped.getCenter().z,
                    color, labelScaleValue, -lineSpacing * line, event.tickDelta);
                line += 1;

                if ((state.lockedOnYou() || state.lockedOnOther()) && showWardenTargetLabel.get()) {
                    String tgt = state.lockedOnYou() ? "TARGET: YOU" : "TARGET: OTHER";
                    SettingColor tc = state.lockedOnYou()
                        ? (attackFlash ? getFastPulseColor(wardenLockedColor.get(), 0.35F)
                           : getPulseColor(wardenLockedColor.get(), 0.6F))
                        : wardenOtherTargetColor.get();
                    drawWorldLabel(event.matrices, tgt, lerped.getCenter().x,
                        lerped.getCenter().y + baseOffset, lerped.getCenter().z,
                        tc, 0.95F * labelScaleValue, -lineSpacing * line, event.tickDelta);
                    line += 1;
                }

                if (state.sniffing() && showSniffingPulse.get()) {
                    drawWorldLabel(event.matrices, "SNIFFING",
                        lerped.getCenter().x, lerped.getCenter().y + baseOffset,
                        lerped.getCenter().z, new SettingColor(255, 255, 170),
                        0.9F * labelScaleValue, -lineSpacing * line, event.tickDelta);
                    line += 1;
                }

                if (state.digging() || state.emerging()) {
                    String label = state.digging() ? "DIGGING" : "EMERGING";
                    drawWorldLabel(event.matrices, label, lerped.getCenter().x,
                        lerped.getCenter().y + baseOffset, lerped.getCenter().z,
                        wardenDiggingColor.get(), 0.9F * labelScaleValue,
                        -lineSpacing * line, event.tickDelta);
                    line += 1;
                }

                if (showAngerTrend.get() && prev != null) {
                    int delta = anger - prev;
                    if (delta != 0) {
                        Vec3 top = lerped.getCenter().add(0, lerped.getYsize() / 2.0, 0);
                        Vec3 to = top.add(0, delta > 0 ? 0.6 : -0.6, 0);
                        SettingColor col = delta > 0 ? wardenSearchingColor.get() : wardenCalmColor.get();
                        event.renderer.line(top.x, top.y, top.z, to.x, to.y, to.z, col);
                    }
                }
            } catch (Throwable t) {
                // ignore per-entity errors
            }
        }
    }

    private WardenState classifyWarden(Warden warden, int anger, AngerLevel angriness) {
        boolean sniffing = warden.getPose() == Pose.SNIFFING;
        boolean digging = warden.getPose() == Pose.DIGGING;
        boolean emerging = warden.getPose() == Pose.EMERGING;

        LivingEntity target = warden.getTarget();
        boolean hasTarget = target != null;
        boolean lockedOnYou = hasTarget && target == mc.player;
        boolean lockedOnOther = hasTarget && !lockedOnYou;

        boolean locked = hasTarget || angriness.isAngry() || lockedOnYou || lockedOnOther;
        boolean agitated = sniffing || angriness == AngerLevel.AGITATED;

        SettingColor color;
        String label;
        if (digging || emerging) {
            color = wardenDiggingColor.get();
            label = digging ? "DIGGING" : "EMERGING";
        } else if (locked) {
            color = wardenLockedColor.get();
            label = "LOCKED";
        } else if (agitated) {
            color = wardenAgitatedColor.get();
            label = "AGITATED";
        } else if (anger > 0) {
            color = wardenSearchingColor.get();
            label = "SEARCHING";
        } else {
            color = wardenCalmColor.get();
            label = "CALM";
        }

        return new WardenState(color, label, locked, sniffing, digging, emerging,
            lockedOnYou, lockedOnOther, hasTarget);
    }

    private void drawWorldLabel(PoseStack matrices, String text, double x,
                                double y, double z, SettingColor color, float scale, float offsetPx,
                                float tickDelta) {
        matrices.pushPose();
        Vec3 cam = mc.gameRenderer.getMainCamera().position();
        matrices.translate(x - cam.x, y - cam.y, z - cam.z);
        Entity camEntity = mc.getCameraEntity();
        if (camEntity != null) {
            matrices.mulPose(Axis.YP.rotationDegrees(-camEntity.getViewYRot(tickDelta)));
            matrices.mulPose(Axis.XP.rotationDegrees(camEntity.getViewXRot(tickDelta)));
        }
        matrices.mulPose(Axis.YP.rotationDegrees(180.0F));

        float distance = (float) DistanceUtil.distance(cam, x, y, z);
        float distanceFactor = getDistanceLabelFactor(distance);
        float s = 0.018F * scale * distanceFactor;
        matrices.scale(s, -s, s);
        matrices.translate(0, offsetPx, 0);

        Font tr = mc.font;
        float w = tr.width(text) / 2F;

        int argb = color.getPacked();
        int baseAlpha = (argb >>> 24) & 0xFF;
        int bg = (int) (0.25F * baseAlpha) << 24;

        tr.drawInBatch(text, -w, 0, argb, false, matrices.last().pose(),
            mc.renderBuffers().bufferSource(),
            Font.DisplayMode.SEE_THROUGH, bg, 0xF000F0);
        matrices.popPose();
    }

    private static float getDistanceLabelFactor(float distance) {
        if (distance <= 4F)
            return 0.30F;
        if (distance >= 28F)
            return 1.00F;
        return 0.30F + (distance - 4F) / 24F * 0.70F;
    }

    private static float clamp01(float v) {
        if (Float.isNaN(v))
            return 0F;
        return Math.max(0F, Math.min(1F, v));
    }

    private static SettingColor flashColor(SettingColor color) {
        double t = (System.currentTimeMillis() % 1000L) / 1000.0;
        double pulse = 0.5 + 0.5 * Math.sin(t * Math.PI * 2);
        int alpha = (int) (color.a * (0.4 + 0.6 * pulse));
        return new SettingColor(color.r, color.g, color.b, alpha);
    }

    private static SettingColor getPulseColor(SettingColor base, float minBrightness) {
        return getPulseColor(base, minBrightness, 180.0);
    }

    private static SettingColor getFastPulseColor(SettingColor base, float minBrightness) {
        return getPulseColor(base, minBrightness, 90.0);
    }

    private static SettingColor getPulseColor(SettingColor base, float minBrightness, double speedMs) {
        float pulse = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / speedMs));
        float t = minBrightness + (1F - minBrightness) * pulse;
        return new SettingColor(
            (int) Math.min(255, base.r * t),
            (int) Math.min(255, base.g * t),
            (int) Math.min(255, base.b * t),
            base.a);
    }

    private void renderBox(Renderer3D renderer,
                           double x1, double y1, double z1,
                           double x2, double y2, double z2,
                           SettingColor sideColor, SettingColor lineColor) {
        if (shapeMode.get().lines()) renderer.boxLines(x1, y1, z1, x2, y2, z2, lineColor, 0);
        if (shapeMode.get().sides()) renderer.boxSides(x1, y1, z1, x2, y2, z2, sideColor, 0);
    }

    /**
     * Builds the 42 unique relative positions of the conduit activation frame.
     * Three 5×5 hollow-ring planes (XZ at y=0, XY at z=0, YZ at x=0) share 6 corner
     * positions that's 3×16 − 6 = 42 unique positions.
     */
    private static int[][] buildFrameOffsets() {
        Set<Long> seen = new HashSet<>();
        List<int[]> offsets = new ArrayList<>();

        // XZ ring (y = 0)
        for (int x = -2; x <= 2; x++)
            for (int z = -2; z <= 2; z++)
                if (Math.abs(x) == 2 || Math.abs(z) == 2)
                    addFrameOffset(seen, offsets, x, 0, z);

        // XY ring (z = 0)
        for (int x = -2; x <= 2; x++)
            for (int y = -2; y <= 2; y++)
                if (Math.abs(x) == 2 || Math.abs(y) == 2)
                    addFrameOffset(seen, offsets, x, y, 0);

        // YZ ring (x = 0)
        for (int y = -2; y <= 2; y++)
            for (int z = -2; z <= 2; z++)
                if (Math.abs(y) == 2 || Math.abs(z) == 2)
                    addFrameOffset(seen, offsets, 0, y, z);

        return offsets.toArray(new int[0][]);
    }

    private static void addFrameOffset(Set<Long> seen, List<int[]> offsets, int x, int y, int z) {
        long key = ((long) (x + 3)) * 49L + ((long) (y + 3)) * 7L + (z + 3);
        if (seen.add(key)) offsets.add(new int[]{x, y, z});
    }

    public enum ShapeMode {
        Lines, Sides, Both;

        public boolean lines() {
            return this == Lines || this == Both;
        }

        public boolean sides() {
            return this == Sides || this == Both;
        }
    }

    // Absolute shitbox
    public enum WardenShape {
        None, Box, Octahedron
    }

    public enum WardenTracerMode {
        Off, Targeting, Always
    }
}
