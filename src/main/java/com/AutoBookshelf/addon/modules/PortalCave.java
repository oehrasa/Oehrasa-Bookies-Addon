//made by etianl :D
/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.utils.DistanceUtil;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WMinus;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReferenceArray;

public class PortalCave extends Module {
    private static final Logger LOGGER = LoggerFactory.getLogger("Portal-Cave");
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup locationLogs = settings.createGroup("Location Logs");
    private final SettingGroup sgDetection = settings.createGroup("Detection");

    // Detection: new disturbance mode (regular AIR inside a CAVE_AIR region, which
    // is exactly how a torn-out portal inside a cavern looks). "Legacy" re-enables the
    // original bridge-of-AIR path; "Disturbance" uses only the new detection; "Both" runs both.
    public enum DetectionMode {Legacy, Disturbance, Both}

    private final Setting<DetectionMode> detectionMode = sgDetection.add(new EnumSetting.Builder<DetectionMode>()
        .name("detection-mode")
        .description("Legacy: bridge-of-AIR path (original). Disturbance: AIR blocks inside CAVE_AIR (port sanad). Both: run both.")
        .defaultValue(DetectionMode.Disturbance)
        .build());
    private final Setting<Boolean> overworld = sgDetection.add(new BoolSetting.Builder()
        .name("overworld").description("Scan in the Overworld.").defaultValue(true)
        .visible(() -> detectionMode.get() != DetectionMode.Legacy)
        .build());
    private final Setting<Boolean> nether = sgDetection.add(new BoolSetting.Builder()
        .name("nether").description("Scan in the Nether.").defaultValue(true)
        .visible(() -> detectionMode.get() != DetectionMode.Legacy)
        .build());
    private final Setting<Boolean> end = sgDetection.add(new BoolSetting.Builder()
        .name("end").description("Scan in the End.").defaultValue(false)
        .visible(() -> detectionMode.get() != DetectionMode.Legacy)
        .build());
    private final Setting<Integer> minY = sgDetection.add(new IntSetting.Builder()
        .name("min-y").description("Lowest Y scanned by the disturbance scan.")
        .defaultValue(-60).sliderRange(-64, 320)
        .visible(() -> detectionMode.get() != DetectionMode.Legacy)
        .build());
    private final Setting<Integer> maxY = sgDetection.add(new IntSetting.Builder()
        .name("max-y").description("Highest Y scanned by the disturbance scan.")
        .defaultValue(128).sliderRange(-64, 320)
        .visible(() -> detectionMode.get() != DetectionMode.Legacy)
        .build());
    private final Setting<Integer> maxPortalWidth = sgDetection.add(new IntSetting.Builder()
        .name("disturbance-max-portal-width").description("Maximum portal width to detect (outer frame). Larger = more false positives.")
        .defaultValue(8).sliderRange(4, 23)
        .visible(() -> detectionMode.get() != DetectionMode.Legacy)
        .build());
    private final Setting<Integer> maxPortalHeight = sgDetection.add(new IntSetting.Builder()
        .name("disturbance-max-portal-height").description("Maximum portal height to detect (outer frame). Larger = more false positives.")
        .defaultValue(10).sliderRange(5, 23)
        .visible(() -> detectionMode.get() != DetectionMode.Legacy)
        .build());
    private final Setting<Boolean> highlightAllAirInCave = sgDetection.add(new BoolSetting.Builder()
        .name("highlight-all-air-in-cave").description("Highlight every regular AIR block that is inside a CAVE_AIR region (enclosed on an axis by cave air).")
        .defaultValue(false)
        .visible(() -> detectionMode.get() != DetectionMode.Legacy)
        .build());
    private final Setting<Boolean> showAllCaveAir = sgDetection.add(new BoolSetting.Builder()
        .name("show-all-cave-air").description("Render all the CAVE_AIR blocks found by the scan, merged into large boxes.")
        .defaultValue(false)
        .visible(() -> detectionMode.get() != DetectionMode.Legacy)
        .build());
    private final Setting<Integer> caveAirRenderDist = sgDetection.add(new IntSetting.Builder()
        .name("cave-air-render-distance").description("Max distance to render cave air (in blocks).")
        .defaultValue(64).sliderRange(16, 256)
        .visible(() -> detectionMode.get() != DetectionMode.Legacy && showAllCaveAir.get())
        .build());
    private final Setting<SettingColor> caveAirFillColor = sgDetection.add(new ColorSetting.Builder()
        .name("cave-air-fill").description("Fill color for cave air.")
        .defaultValue(new SettingColor(50, 150, 255, 30))
        .visible(() -> detectionMode.get() != DetectionMode.Legacy && showAllCaveAir.get())
        .build());
    private final Setting<SettingColor> caveAirLineColor = sgDetection.add(new ColorSetting.Builder()
        .name("cave-air-line").description("Outline color for cave air.")
        .defaultValue(new SettingColor(50, 150, 255, 80))
        .visible(() -> detectionMode.get() != DetectionMode.Legacy && showAllCaveAir.get())
        .build());
    private final Setting<ShapeMode> caveAirShapeMode = sgDetection.add(new EnumSetting.Builder<ShapeMode>()
        .name("cave-air-shape").description("How to render cave air boxes.")
        .defaultValue(ShapeMode.Both)
        .visible(() -> detectionMode.get() != DetectionMode.Legacy && showAllCaveAir.get())
        .build());
    private final Setting<SettingColor> disturbanceColor = sgDetection.add(new ColorSetting.Builder()
        .name("disturbance-color").description("Color for detected portal-shape disturbances.")
        .defaultValue(new SettingColor(255, 50, 50, 150))
        .visible(() -> detectionMode.get() != DetectionMode.Legacy)
        .build());
    private final Setting<SettingColor> disturbanceLineColor = sgDetection.add(new ColorSetting.Builder()
        .name("disturbance-line-color").description("Outline color for portal-shaped disturbances.")
        .defaultValue(new SettingColor(255, 100, 100, 255))
        .visible(() -> detectionMode.get() != DetectionMode.Legacy)
        .build());
    private final Setting<Boolean> renderBeacon = sgDetection.add(new BoolSetting.Builder()
        .name("beacon").description("Draw a vertical line above portal detections.")
        .defaultValue(true)
        .visible(() -> detectionMode.get() != DetectionMode.Legacy)
        .build());
    private final Setting<Integer> beaconHeight = sgDetection.add(new IntSetting.Builder()
        .name("beacon-height").description("Height of the beacon line.")
        .defaultValue(64).sliderRange(8, 128)
        .visible(() -> detectionMode.get() != DetectionMode.Legacy && renderBeacon.get())
        .build());
    private final Setting<Boolean> portalDetectionSound = sgDetection.add(new BoolSetting.Builder()
        .name("portal-detection-sound").description("Play a sound when a portal-shaped disturbance is detected.")
        .defaultValue(true)
        .visible(() -> detectionMode.get() != DetectionMode.Legacy)
        .build());

    private final Setting<Boolean> displaycoords = sgGeneral.add(new BoolSetting.Builder()
        .name("DisplayCoords")
        .description("Displays coords of portal patterns in chat.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> ignorecorners = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-corner-blocks")
        .description("Also matches portal patterns that are missing the corner blocks.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> falsepositives1 = sgGeneral.add(new BoolSetting.Builder()
        .name("False Positive Removal")
        .description("Removes false positives in relation to the air above and below the portal pattern.")
        .defaultValue(true)
        .build()
    );
    public final Setting<Integer> nonAirPercent = sgGeneral.add(new IntSetting.Builder()
        .name("Non-Air Percent")
        .description("What percentage of the blocks in the portal shape can be non-air.")
        .defaultValue(20)
        .min(0)
        .sliderRange(0, 100)
        .build()
    );
    public final Setting<Integer> percent = sgGeneral.add(new IntSetting.Builder()
        .name("Adjacent Air Percent")
        .description("What percentage of the blocks in the portal shape that is allowed to have air blocks adjacent to it.")
        .defaultValue(15)
        .min(0)
        .sliderRange(0, 100)
        .build()
    );
    public final Setting<Integer> pWidth = sgGeneral.add(new IntSetting.Builder()
        .name("Portal Width")
        .description("finds portals that are up to this large")
        .defaultValue(5)
        .min(4)
        .sliderRange(4, 8)
        .build()
    );
    public final Setting<Integer> pHeight = sgGeneral.add(new IntSetting.Builder()
        .name("Portal Height")
        .description("finds portals that are up to this large")
        .defaultValue(5)
        .min(5)
        .sliderRange(5, 8)
        .build()
    );
    private final Setting<Boolean> removerenderdist = sgRender.add(new BoolSetting.Builder()
        .name("RemoveOutsideRenderDistance")
        .description("Removes the cached portal patterns when they leave render distance.")
        .defaultValue(true)
        .build()
    );
    public final Setting<Integer> renderDistance = sgRender.add(new IntSetting.Builder()
        .name("Render-Distance(Chunks)")
        .description("How many chunks from the character to render the portal patterns.")
        .defaultValue(32)
        .min(6)
        .sliderRange(6, 1024)
        .build()
    );
    private final Setting<Boolean> trcr = sgRender.add(new BoolSetting.Builder()
        .name("Tracers")
        .description("Show tracers to the portal patterns.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> nearesttrcr = sgRender.add(new BoolSetting.Builder()
        .name("Tracer to nearest Portal Only")
        .description("Show only one tracer to the nearest portal pattern.")
        .defaultValue(false)
        .build()
    );
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );
    private final Setting<SettingColor> portalSideColor = sgRender.add(new ColorSetting.Builder()
        .name("possible-portal-side-color")
        .description("Color of possible portal locations.")
        .defaultValue(new SettingColor(170, 0, 255, 55))
        .visible(() -> (shapeMode.get() == ShapeMode.Sides || shapeMode.get() == ShapeMode.Both))
        .build()
    );
    private final Setting<SettingColor> portalLineColor = sgRender.add(new ColorSetting.Builder()
        .name("possible-portal-line-color")
        .description("Color of possible portal locations.")
        .defaultValue(new SettingColor(170, 0, 255, 200))
        .visible(() -> (shapeMode.get() == ShapeMode.Lines || shapeMode.get() == ShapeMode.Both || trcr.get()))
        .build()
    );
    private final Setting<Boolean> locLogging = locationLogs.add(new BoolSetting.Builder()
        .name("Enable Location Logging")
        .description("Logs the locations of detected spawners to a csv file as well as a table in this options menu.")
        .defaultValue(false)
        .build()
    );
    private final Set<ChunkPos> scannedChunks = new CopyOnWriteArraySet<>();
    private final Set<AABB> possiblePortalLocations = new CopyOnWriteArraySet<>();
    private final Set<BlockPos> loggedPortalPositions = new CopyOnWriteArraySet<>();
    private int closestPortalX = 2000000000;
    private int closestPortalY = 2000000000;
    private int closestPortalZ = 2000000000;
    private double PortalDistance = 2000000000;

    // ---- Disturbance detection (new) ----
    private final java.util.Set<Long> pendingChunks = ConcurrentHashMap.newKeySet();
    private final java.util.Set<Long> processingChunks = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> notifiedPortals = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Long, ChunkData> chunkCache = new ConcurrentHashMap<>();
    private ExecutorService scanExecutor;
    private int tick;
    // Render snapshots (volatile, replaced as a unit).
    private volatile List<RenderCluster> renderClusters = Collections.emptyList();
    private volatile List<RenderBox> renderCaveAir = Collections.emptyList();
    private volatile List<long[]> renderAirInCave = Collections.emptyList();
    private Color disturbanceFill, disturbanceOutline;
    private Color caveAirFill, caveAirLine;
    private static final int[][] OFFSETS = new int[][]{{0, 1, 0}, {0, -1, 0}, {0, 0, -1}, {0, 0, 1}, {1, 0, 0}, {-1, 0, 0}};
    private final Set<AABB> disturbancePortals = new CopyOnWriteArraySet<>();

    // Packing of BlockPos -> long for disturbance clusters.
    private long packPos(int x, int y, int z) {
        return (long) (x & 67108863) << 38 | (long) (y & 4095) << 26 | z & 67108863;
    }

    private int unpackX(long p) {
        return (int) (p >> 38);
    }

    private int unpackY(long p) {
        return (int) ((p >> 26 & 4095L) << 20 >> 20);
    }

    private int unpackZ(long p) {
        return (int) (p & 67108863L) << 6 >> 6;
    }


    private final List<PortalPattern> portalPatterns = new ArrayList<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public PortalCave() {
        super(Addon.CATEGORY2, "Portal-Cave", "Scans for the shapes of broken/removed Nether Portals within the cave air blocks found in caves and underground structures in 1.13+ chunks. **May be useful for finding portal skips in the Nether**");
    }

    @Override
    public void onActivate() {
        clearChunkData();
        loadPortalPatterns();
        disturbanceActivate();
    }

    private void scanTheAir(AtomicReferenceArray<LevelChunk> chunks) {
        List<ChunkPos> chunksToProcess = new ArrayList<>();
        for (int i = 0; i < chunks.length(); i++) {
            LevelChunk chunk = chunks.get(i);
            if (chunk != null && !chunk.isEmpty()) {
                chunksToProcess.add(chunk.getPos());
            }
        }
        chunksToProcess.stream().forEach(chunkPos -> {
            LevelChunk chunk = mc.level.getChunk(chunkPos.x(), chunkPos.z());
            if (chunk != null && !chunk.isEmpty() && !scannedChunks.contains(chunk.getPos())) {
                processChunk(chunk);
                scannedChunks.add(chunk.getPos());
            }
        });
    }

    @Override
    public void onDeactivate() {
        clearChunkData();
        portalPatterns.clear();
        loggedPortalPositions.clear();
        disturbanceDeactivate();
    }

    @EventHandler
    private void onScreenOpen(OpenScreenEvent event) {
        if (event.screen instanceof DisconnectedScreen || event.screen instanceof LevelLoadingScreen) clearChunkData();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        clearChunkData();
    }

    // ---- Disturbance scan implementation ----
    private void disturbanceActivate() {
        chunkCache.clear();
        pendingChunks.clear();
        processingChunks.clear();
        notifiedPortals.clear();
        disturbancePortals.clear();
        renderClusters = Collections.emptyList();
        renderCaveAir = Collections.emptyList();
        renderAirInCave = Collections.emptyList();
        tick = 0;
        computeDisturbanceColors();
        scanExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "PortalCave-Disturbance-Scan");
            t.setDaemon(true);
            t.setPriority(1);
            return t;
        });
        seedDisturbanceScan();
    }

    /**
     * Scan every chunk the server has already delivered to the client, the same
     * chunks the legacy path drains synchronously each tick. Without this the
     * disturbance executor idles forever while no block updates happen, which is
     * precisely why activation-time appears to detect nothing.
     */
    private void seedDisturbanceScan() {
        if (mc.level == null) return;
        AtomicReferenceArray<LevelChunk> chunks = mc.level.getChunkSource().storage.chunks;
        for (int i = 0; i < chunks.length(); i++) {
            LevelChunk chunk = chunks.get(i);
            if (chunk == null) continue;
            try {
                if (chunk.isEmpty()) continue;
            } catch (Throwable ignored) {
                continue;
            }
            long key = chunk.getPos().pack();
            if (!processingChunks.contains(key) && !chunkCache.containsKey(key)) {
                pendingChunks.add(key);
            }
        }
    }

    private void disturbanceDeactivate() {
        if (scanExecutor != null) {
            scanExecutor.shutdownNow();
            scanExecutor = null;
        }
        chunkCache.clear();
        pendingChunks.clear();
        processingChunks.clear();
        notifiedPortals.clear();
        disturbancePortals.clear();
        renderClusters = Collections.emptyList();
        renderCaveAir = Collections.emptyList();
        renderAirInCave = Collections.emptyList();
    }

    private void computeDisturbanceColors() {
        SettingColor dc = disturbanceColor.get();
        SettingColor lc = disturbanceLineColor.get();
        disturbanceFill = new Color(dc.r, dc.g, dc.b, dc.a);
        disturbanceOutline = new Color(lc.r, lc.g, lc.b, lc.a);
        SettingColor caf = caveAirFillColor.get();
        SettingColor cal = caveAirLineColor.get();
        caveAirFill = new Color(caf.r, caf.g, caf.b, caf.a);
        caveAirLine = new Color(cal.r, cal.g, cal.b, cal.a);
    }

    @EventHandler
    private void onChunkDataDisturbance(ChunkDataEvent event) {
        if (!disturbanceEnabled()) return;
        if (scanExecutor == null || scanExecutor.isShutdown()) return;
        ChunkAccess chunk = event.chunk();
        long key = chunk.getPos().pack();
        if (!processingChunks.contains(key)) pendingChunks.add(key);
    }

    @EventHandler
    private void onBlockUpdateDisturbance(BlockUpdateEvent event) {
        if (!disturbanceEnabled()) return;
        BlockPos pos = event.pos;
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        long key = ChunkPos.pack(cx, cz);
        chunkCache.remove(key);
        if (!processingChunks.contains(key)) pendingChunks.add(key);
    }

    private boolean disturbanceEnabled() {
        boolean mode = detectionMode.get() == DetectionMode.Disturbance || detectionMode.get() == DetectionMode.Both;
        if (mc.level == null) return false;
        if (mc.level.dimension() == Level.OVERWORLD) return mode && overworld.get();
        if (mc.level.dimension() == Level.NETHER) return mode && nether.get();
        if (mc.level.dimension() == Level.END) return mode && end.get();
        return false;
    }

    private void processPendingChunks() {
        if (pendingChunks.isEmpty() || scanExecutor == null || scanExecutor.isShutdown()) return;
        if (mc.player == null) return;
        int playerChunkX = mc.player.chunkPosition().x();
        int playerChunkZ = mc.player.chunkPosition().z();
        List<Long> sorted = new ArrayList<>(pendingChunks);
        sorted.sort((a, b) -> {
            int ax = ChunkPos.getX(a) - playerChunkX, az = ChunkPos.getZ(a) - playerChunkZ;
            int bx = ChunkPos.getX(b) - playerChunkX, bz = ChunkPos.getZ(b) - playerChunkZ;
            return Integer.compare(ax * ax + az * az, bx * bx + bz * bz);
        });
        int processed = 0;
        for (long key : sorted) {
            if (processed >= 4) break;
            pendingChunks.remove(key);
            if (processingChunks.add(key)) {
                int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
                scanExecutor.submit(() -> {
                    try {
                        scanChunk(cx, cz);
                    } finally {
                        processingChunks.remove(key);
                    }
                });
                processed++;
            }
        }
    }

    private void evictDistantChunks() {
        if (mc.player == null) return;
        int px = mc.player.chunkPosition().x();
        int pz = mc.player.chunkPosition().z();
        int wanted = Math.max(renderDistance.get(), showAllCaveAir.get() ? caveAirRenderDist.get() : renderDistance.get());
        int radius = wanted / 16 + 2;
        if (mc.options != null) radius = Math.min(radius, mc.options.renderDistance().get() + 1);
        int distSq = radius * radius;
        java.util.Iterator<java.util.Map.Entry<Long, ChunkData>> it = chunkCache.entrySet().iterator();
        while (it.hasNext()) {
            ChunkData data = it.next().getValue();
            int dx = data.chunkX - px, dz = data.chunkZ - pz;
            if (dx * dx + dz * dz > distSq) it.remove();
        }
    }

    private BlockState getBlockStateSafe(BlockPos pos) {
        if (mc.level == null) return null;
        try {
            return mc.level.getBlockState(pos);
        } catch (Exception e) {
            return null;
        }
    }

    private void scanChunk(int cx, int cz) {
        if (mc.level == null) return;
        LevelChunk chunk;
        try {
            chunk = mc.level.getChunk(cx, cz);
        } catch (Exception e) {
            return;
        }
        if (chunk == null) return;
        int baseX = cx << 4, baseZ = cz << 4;
        int minYv = minY.get(), maxYv = maxY.get();
        boolean collectCaveAir = showAllCaveAir.get();
        boolean collectAllAir = highlightAllAirInCave.get();
        java.util.Set<Long> disturbancePositions = new HashSet<>();
        java.util.Set<Long> caveAirPositions = new HashSet<>();
        List<long[]> allAirInCaveList = new ArrayList<>();
        MutableBlockPos pos = new MutableBlockPos();
        MutableBlockPos neighborPos = new MutableBlockPos();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = baseX + lx, wz = baseZ + lz;
                for (int y = minYv; y <= maxYv; y++) {
                    pos.set(wx, y, wz);
                    BlockState state = chunk.getBlockState(pos);
                    net.minecraft.world.level.block.Block block = state.getBlock();
                    if (block == Blocks.CAVE_AIR) {
                        if (collectCaveAir) caveAirPositions.add(packPos(wx, y, wz));
                    } else if (block == Blocks.AIR) {
                        boolean[] hasCaveAir = new boolean[6];
                        int caveAirCount = 0;
                        for (int i = 0; i < OFFSETS.length; i++) {
                            int[] offset = OFFSETS[i];
                            neighborPos.set(wx + offset[0], y + offset[1], wz + offset[2]);
                            BlockState neighborState = getBlockStateSafe(neighborPos);
                            if (neighborState != null && neighborState.getBlock() == Blocks.CAVE_AIR) {
                                hasCaveAir[i] = true;
                                caveAirCount++;
                            }
                        }
                        if (caveAirCount > 0) disturbancePositions.add(packPos(wx, y, wz));
                        if (collectAllAir) {
                            boolean enclosedY = hasCaveAir[0] && hasCaveAir[1];
                            boolean enclosedZ = hasCaveAir[2] && hasCaveAir[3];
                            boolean enclosedX = hasCaveAir[4] && hasCaveAir[5];
                            if (enclosedY || enclosedZ || enclosedX) allAirInCaveList.add(new long[]{wx, y, wz});
                        }
                    }
                }
            }
        }

        List<DisturbanceCluster> clusters = clusterDisturbances(disturbancePositions);
        List<MergedBox> caveAirBoxes = greedyMesh(caveAirPositions);
        if (!clusters.isEmpty() || !caveAirBoxes.isEmpty() || !allAirInCaveList.isEmpty()) {
            long key = ChunkPos.pack(cx, cz);
            chunkCache.put(key, new ChunkData(cx, cz, clusters, caveAirBoxes, allAirInCaveList));
            for (DisturbanceCluster cluster : clusters) {
                if (cluster.portalShaped) portalFoundDisturbance(cluster);
            }
        }
    }

    private List<DisturbanceCluster> clusterDisturbances(java.util.Set<Long> positions) {
        List<DisturbanceCluster> clusters = new ArrayList<>();
        java.util.Set<Long> visited = new HashSet<>();
        int maxW = maxPortalWidth.get(), maxH = maxPortalHeight.get();

        for (long pos : positions) {
            if (visited.contains(pos)) continue;
            List<Long> cluster = new ArrayList<>();
            java.util.Queue<Long> queue = new java.util.LinkedList<>();
            queue.add(pos);
            visited.add(pos);
            int minXc = Integer.MAX_VALUE, minYc = Integer.MAX_VALUE, minZc = Integer.MAX_VALUE;
            int maxXc = Integer.MIN_VALUE, maxYc = Integer.MIN_VALUE, maxZc = Integer.MIN_VALUE;

            while (!queue.isEmpty()) {
                long current = queue.poll();
                cluster.add(current);
                int cx2 = unpackX(current), cy2 = unpackY(current), cz2 = unpackZ(current);
                minXc = Math.min(minXc, cx2);
                maxXc = Math.max(maxXc, cx2);
                minYc = Math.min(minYc, cy2);
                maxYc = Math.max(maxYc, cy2);
                minZc = Math.min(minZc, cz2);
                maxZc = Math.max(maxZc, cz2);
                for (int[] offset : OFFSETS) {
                    long neighbor = packPos(cx2 + offset[0], cy2 + offset[1], cz2 + offset[2]);
                    if (positions.contains(neighbor) && !visited.contains(neighbor)) {
                        visited.add(neighbor);
                        queue.add(neighbor);
                    }
                }
            }

            int blockCount = cluster.size();
            int widthX = maxXc - minXc + 1;
            int height = maxYc - minYc + 1;
            int widthZ = maxZc - minZc + 1;
            boolean facesX = widthX == 1;
            boolean facesZ = widthZ == 1;
            if (facesX || facesZ) {
                int W = facesX ? widthZ : widthX;
                int H = height;
                if (W >= 4 && H >= 5 && W <= maxW && H <= maxH) {
                    int filledRect = W * H;
                    int noCorners = filledRect - 4;
                    boolean validDimensions = blockCount == filledRect || blockCount == noCorners;
                    if (validDimensions) {
                        boolean validShape;
                        if (blockCount == filledRect) {
                            validShape = validateFilledRectangle(new java.util.HashSet<>(cluster), minXc, minYc, minZc, W, H, facesX);
                        } else {
                            validShape = validateFrameWithoutCorners(new java.util.HashSet<>(cluster), minXc, minYc, minZc, W, H, facesX);
                        }
                        if (validShape) {
                            MergedBox bounds = new MergedBox(minXc, minYc, minZc, maxXc, maxYc, maxZc);
                            clusters.add(new DisturbanceCluster(bounds, true));
                        } else {
                            clusters.add(new DisturbanceCluster(new MergedBox(minXc, minYc, minZc, maxXc, maxYc, maxZc), false));
                        }
                    }
                }
            }
        }
        return clusters;
    }

    private boolean validateFilledRectangle(java.util.Set<Long> positions, int minX, int minY, int minZ, int W, int H, boolean facesX) {
        if (facesX) {
            int x = minX, maxZcoord = minZ + W - 1, maxYcoord = minY + H - 1;
            for (int y = minY; y <= maxYcoord; y++) {
                for (int z = minZ; z <= maxZcoord; z++) {
                    if (!positions.contains(packPos(x, y, z))) return false;
                }
            }
        } else {
            int z = minZ, maxXcoord = minX + W - 1, maxYcoord = minY + H - 1;
            for (int y = minY; y <= maxYcoord; y++) {
                for (int x = minX; x <= maxXcoord; x++) {
                    if (!positions.contains(packPos(x, y, z))) return false;
                }
            }
        }
        return true;
    }

    private boolean validateFrameWithoutCorners(java.util.Set<Long> positions, int minX, int minY, int minZ, int W, int H, boolean facesX) {
        if (facesX) {
            int x = minX, maxZcoord = minZ + W - 1, maxYcoord = minY + H - 1;
            for (int y = minY; y <= maxYcoord; y++) {
                boolean isTopOrBottom = (y == minY || y == maxYcoord);
                for (int z = minZ; z <= maxZcoord; z++) {
                    boolean isLeftOrRight = (z == minZ || z == maxZcoord);
                    boolean isCorner = isTopOrBottom && isLeftOrRight;
                    boolean hasBlock = positions.contains(packPos(x, y, z));
                    if (isCorner) {
                        if (hasBlock) return false;
                    } else if (!hasBlock) return false;
                }
            }
        } else {
            int z = minZ, maxXcoord = minX + W - 1, maxYcoord = minY + H - 1;
            for (int y = minY; y <= maxYcoord; y++) {
                boolean isTopOrBottom = (y == minY || y == maxYcoord);
                for (int x = minX; x <= maxXcoord; x++) {
                    boolean isLeftOrRight = (x == minX || x == maxXcoord);
                    boolean isCorner = isTopOrBottom && isLeftOrRight;
                    boolean hasBlock = positions.contains(packPos(x, y, z));
                    if (isCorner) {
                        if (hasBlock) return false;
                    } else if (!hasBlock) return false;
                }
            }
        }
        return true;
    }

    private List<MergedBox> greedyMesh(java.util.Set<Long> positions) {
        if (positions.isEmpty()) return Collections.emptyList();
        List<MergedBox> boxes = new ArrayList<>();
        java.util.Set<Long> remaining = new HashSet<>(positions);
        List<Long> sorted = new ArrayList<>(positions);
        sorted.sort((a, b) -> {
            int ay = unpackY(a), by = unpackY(b);
            if (ay != by) return Integer.compare(ay, by);
            int az = unpackZ(a), bz = unpackZ(b);
            if (az != bz) return Integer.compare(az, bz);
            return Integer.compare(unpackX(a), unpackX(b));
        });
        for (long start : sorted) {
            if (!remaining.contains(start)) continue;
            remaining.remove(start);
            int startX = unpackX(start), startY = unpackY(start), startZ = unpackZ(start);
            int endX = startX;
            while (remaining.contains(packPos(endX + 1, startY, startZ))) remaining.remove(packPos(++endX, startY, startZ));
            int endZ = startZ;
            boolean canExpandZ = true;
            while (canExpandZ) {
                for (int x = startX; x <= endX; x++) {
                    if (!remaining.contains(packPos(x, startY, endZ + 1))) {
                        canExpandZ = false;
                        break;
                    }
                }
                if (canExpandZ) {
                    endZ++;
                    for (int x = startX; x <= endX; x++) remaining.remove(packPos(x, startY, endZ));
                }
            }
            int endY = startY;
            boolean canExpandY = true;
            while (canExpandY) {
                for (int x = startX; x <= endX && canExpandY; x++) {
                    for (int z = startZ; z <= endZ; z++) {
                        if (!remaining.contains(packPos(x, endY + 1, z))) {
                            canExpandY = false;
                            break;
                        }
                    }
                }
                if (canExpandY) {
                    endY++;
                    for (int x = startX; x <= endX; x++) {
                        for (int z = startZ; z <= endZ; z++) remaining.remove(packPos(x, endY, z));
                    }
                }
            }
            boxes.add(new MergedBox(startX, startY, startZ, endX, endY, endZ));
        }
        return boxes;
    }

    private void portalFoundDisturbance(DisturbanceCluster cluster) {
        String key = cluster.bounds.minX + "," + cluster.bounds.minY + "," + cluster.bounds.minZ;
        if (notifiedPortals.add(key)) {
            if (portalDetectionSound.get() && mc.player != null) {
                mc.execute(() -> {
                    if (mc.player != null) mc.player.playSound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), 1.0F, 1.5F);
                });
            }
            AABB box = new AABB(cluster.bounds.minX, cluster.bounds.minY, cluster.bounds.minZ,
                cluster.bounds.maxX + 1, cluster.bounds.maxY + 1, cluster.bounds.maxZ + 1);
            disturbancePortals.add(box);
            portalFound(box);
        }
    }

    private void rebuildRenderCache() {
        if (mc.player == null || !disturbanceEnabled()) {
            renderClusters = Collections.emptyList();
            renderCaveAir = Collections.emptyList();
            renderAirInCave = Collections.emptyList();
            return;
        }
        Vec3 playerPos = mc.player.getEyePosition();
        int rDist = renderDistance.get();
        int rDistSq = rDist * rDist;
        List<RenderCluster> newClusters = new ArrayList<>();
        List<long[]> newAirInCave = new ArrayList<>();
        for (java.util.Map.Entry<Long, ChunkData> entry : chunkCache.entrySet()) {
            ChunkData data = entry.getValue();
            if (data == null) continue;
            double chunkCenterX = data.chunkX * 16 + 8;
            double chunkCenterZ = data.chunkZ * 16 + 8;
            double chunkDistSq = (playerPos.x - chunkCenterX) * (playerPos.x - chunkCenterX)
                + (playerPos.z - chunkCenterZ) * (playerPos.z - chunkCenterZ);
            if (chunkDistSq > (long) rDistSq * 4) continue;
            if (data.clusters != null) {
                for (DisturbanceCluster c : data.clusters) {
                    if (!c.portalShaped) continue;
                    double distSq = nearestPointDistanceSq(playerPos, c.bounds);
                    if (distSq <= rDistSq) newClusters.add(new RenderCluster(c.bounds, true));
                }
            }
            if (highlightAllAirInCave.get() && data.allAirInCave != null) {
                for (long[] p : data.allAirInCave) {
                    double distSq = playerPos.distanceToSqr(p[0] + 0.5, p[1] + 0.5, p[2] + 0.5);
                    if (distSq <= rDistSq) newAirInCave.add(p);
                }
            }
        }
        renderClusters = newClusters;
        renderAirInCave = newAirInCave;
    }

    private void rebuildCaveAirCache() {
        if (!showAllCaveAir.get() || mc.player == null || !disturbanceEnabled()) {
            renderCaveAir = Collections.emptyList();
            return;
        }
        Vec3 playerPos = mc.player.getEyePosition();
        int caDist = caveAirRenderDist.get();
        int buffered = caDist + caDist / 10 + 8;
        int bufferedSq = buffered * buffered;
        List<RenderBox> newCaveAir = new ArrayList<>();
        for (java.util.Map.Entry<Long, ChunkData> entry : chunkCache.entrySet()) {
            ChunkData data = entry.getValue();
            if (data == null || data.caveAirBoxes == null) continue;
            double chunkCenterX = data.chunkX * 16 + 8;
            double chunkCenterZ = data.chunkZ * 16 + 8;
            double chunkDistSq = (playerPos.x - chunkCenterX) * (playerPos.x - chunkCenterX)
                + (playerPos.z - chunkCenterZ) * (playerPos.z - chunkCenterZ);
            if (chunkDistSq > (long) bufferedSq * 4) continue;
            for (MergedBox b : data.caveAirBoxes) {
                double distSq = nearestPointDistanceSq(playerPos, b);
                if (distSq <= bufferedSq) newCaveAir.add(new RenderBox(b));
            }
        }
        renderCaveAir = newCaveAir;
    }

    private double nearestPointDistanceSq(Vec3 point, MergedBox box) {
        double dx = Math.max(0.0, Math.max(box.minX - point.x, point.x - (box.maxX + 1)));
        double dy = Math.max(0.0, Math.max(box.minY - point.y, point.y - (box.maxY + 1)));
        double dz = Math.max(0.0, Math.max(box.minZ - point.z, point.z - (box.maxZ + 1)));
        return dx * dx + dy * dy + dz * dz;
    }

    private void tickDisturbance() {
        if (mc.player == null) return;
        tick++;
        if (tick % 20 == 0) computeDisturbanceColors();
        if (tick % 3 == 0) processPendingChunks();
        if (tick % 4 == 0) rebuildRenderCache();
        if (tick % 10 == 0) rebuildCaveAirCache();
        if (tick % 20 == 0) evictDistantChunks();
        if (tick >= 1200) tick = 0;
    }

    private static final class ChunkData {
        final int chunkX, chunkZ;
        final List<DisturbanceCluster> clusters;
        final List<MergedBox> caveAirBoxes;
        final List<long[]> allAirInCave;

        ChunkData(int cx, int cz, List<DisturbanceCluster> clusters, List<MergedBox> caveAirBoxes, List<long[]> allAirInCave) {
            this.chunkX = cx;
            this.chunkZ = cz;
            this.clusters = clusters;
            this.caveAirBoxes = caveAirBoxes;
            this.allAirInCave = allAirInCave;
        }
    }

    private static final class DisturbanceCluster {
        final MergedBox bounds;
        final boolean portalShaped;

        DisturbanceCluster(MergedBox b, boolean portalShaped) {
            this.bounds = b;
            this.portalShaped = portalShaped;
        }
    }

    private static final class MergedBox {
        final int minX, minY, minZ, maxX, maxY, maxZ;

        MergedBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }
    }

    private static final class RenderBox {
        final int minX, minY, minZ, maxX, maxY, maxZ;

        RenderBox(MergedBox b) {
            this.minX = b.minX;
            this.minY = b.minY;
            this.minZ = b.minZ;
            this.maxX = b.maxX + 1;
            this.maxY = b.maxY + 1;
            this.maxZ = b.maxZ + 1;
        }
    }

    private static final class RenderCluster {
        final int minX, minY, minZ, maxX, maxY, maxZ;
        final double centerX, centerZ;
        final boolean portalShaped;

        RenderCluster(MergedBox b, boolean portalShaped) {
            this.minX = b.minX;
            this.minY = b.minY;
            this.minZ = b.minZ;
            this.maxX = b.maxX + 1;
            this.maxY = b.maxY + 1;
            this.maxZ = b.maxZ + 1;
            this.centerX = (b.minX + b.maxX + 1) / 2.0;
            this.centerZ = (b.minZ + b.maxZ + 1) / 2.0;
            this.portalShaped = portalShaped;
        }
    }

    private void clearChunkData() {
        scannedChunks.clear();
        possiblePortalLocations.clear();
        disturbancePortals.clear();
        notifiedPortals.clear();
        chunkCache.clear();
        pendingChunks.clear();
        processingChunks.clear();
        renderClusters = Collections.emptyList();
        renderCaveAir = Collections.emptyList();
        renderAirInCave = Collections.emptyList();
        closestPortalX = 2000000000;
        closestPortalY = 2000000000;
        closestPortalZ = 2000000000;
        PortalDistance = 2000000000;
    }

    @EventHandler
    private void onPreTick(TickEvent.Pre event) {
        if (mc.level == null) return;
        tickDisturbance();
        AtomicReferenceArray<LevelChunk> chunks = mc.level.getChunkSource().storage.chunks;
        Set<LevelChunk> chunkSet = new HashSet<>();

        for (int i = 0; i < chunks.length(); i++) {
            LevelChunk chunk = chunks.get(i);
            if (chunk != null) {
                chunkSet.add(chunk);
            }
        }
        boolean modeLegacy = detectionMode.get() == DetectionMode.Legacy || detectionMode.get() == DetectionMode.Both;
        if (modeLegacy) scanTheAir(chunks);
        if (nearesttrcr.get()) {
            try {
                List<AABB> candidates = new ArrayList<>(possiblePortalLocations);
                candidates.addAll(disturbancePortals);
                int playerX = mc.player.getBlockX();
                int playerZ = mc.player.getBlockZ();
                for (int b = 0; b < candidates.size(); b++) {
                    Vec3 center = candidates.get(b).getCenter();
                    double dist = DistanceUtil.distanceXZ(
                        center.x - 1, center.z - 1, playerX, playerZ);
                    if (PortalDistance > dist) {
                        closestPortalX = Math.round((float) (center.x - 1));
                        closestPortalY = Math.round((float) (center.y - 1));
                        closestPortalZ = Math.round((float) (center.z - 1));
                        PortalDistance = dist;
                    }
                }
                PortalDistance = 2000000000;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (removerenderdist.get()) {
            removeChunksOutsideRenderDistance(chunkSet);
            removeDisturbanceBoxesOutsideRenderDistance(chunkSet);
        }
    }

    private void processChunk(LevelChunk chunk) {
        int minY = mc.level.getMinY();
        int maxY = 180;
        if (mc.level.dimension() == Level.NETHER) maxY = 126;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y <= maxY; y++) {
                    BlockPos blockPos = new BlockPos(chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z);
                    BlockState blockState = chunk.getBlockState(blockPos);

                    if (blockState.getBlock() == Blocks.CAVE_AIR) {
                        isSurroundingBlockRegAir(blockPos);
                    }
                }
            }
        }
    }

    private void isSurroundingBlockRegAir(BlockPos bPos) {
        BlockPos airPos = bPos.north();
        BlockPos blockPastTheAir = bPos.north().offset(0, 0, -1);
        for (int dir = 1; dir <= 4; dir++) {
            switch (dir) {
                case 1 -> {
                    airPos = bPos.north();
                    blockPastTheAir = bPos.north().offset(0, 0, -1);
                }
                case 2 -> {
                    airPos = bPos.south();
                    blockPastTheAir = bPos.south().offset(0, 0, 1);
                }
                case 3 -> {
                    airPos = bPos.west();
                    blockPastTheAir = bPos.west().offset(-1, 0, 0);
                }
                case 4 -> {
                    airPos = bPos.east();
                    blockPastTheAir = bPos.east().offset(1, 0, 0);
                }
            }
            if (mc.level.getBlockState(airPos).getBlock() == Blocks.AIR && mc.level.getBlockState(blockPastTheAir).getBlock() != Blocks.AIR)
                findAirShape(airPos);
        }
    }

    private void findAirShape(BlockPos pos) {
        final int squareWidth = pWidth.get();
        final int squareHeight = pHeight.get();
        int areaWidth = (squareWidth / 2) + 1;
        int areaHeight = (squareHeight / 2) + 1;

        List<BlockPos> AirBlockPatternWEast = new ArrayList<>();
        List<BlockPos> AirBlockPatternNouth = new ArrayList<>();
        int AirBlockPatternWEastREJECT = 0;
        int AirBlockPatternNouthREJECT = 0;
        int AirBlockPatternWEastREJECT2 = 0;
        int AirBlockPatternNouthREJECT2 = 0;

        for (int x = -areaWidth; x <= areaWidth; x++) {
            for (int y = -areaHeight; y <= areaHeight; y++) {
                BlockPos bPos = new BlockPos(pos.getX() + x, pos.getY() + y, pos.getZ());
                if (mc.level.getBlockState(bPos).getBlock() == Blocks.AIR) {
                    int nonairblockonsides = 0;
                    BlockPos[] surroundingPositions = new BlockPos[]{
                        bPos.north(),
                        bPos.south()
                    };
                    for (BlockPos posi : surroundingPositions) {
                        if (mc.level.getBlockState(posi).getBlock() != Blocks.AIR) {
                            nonairblockonsides++;
                        }
                    }
                    if (nonairblockonsides >= 2) AirBlockPatternWEast.add(bPos);
                    else {
                        AirBlockPatternWEastREJECT++;
                        AirBlockPatternWEast.add(bPos);
                    }
                } else if (mc.level.getBlockState(bPos).getBlock() != Blocks.AIR && mc.level.getBlockState(bPos).getBlock() != Blocks.CAVE_AIR) {
                    AirBlockPatternWEastREJECT2++;
                    AirBlockPatternWEast.add(bPos);
                }
            }
        }
        for (int z = -areaWidth; z <= areaWidth; z++) {
            for (int y = -areaHeight; y <= areaHeight; y++) {
                BlockPos bPos = new BlockPos(pos.getX(), pos.getY() + y, pos.getZ() + z);
                if (mc.level.getBlockState(bPos).getBlock() == Blocks.AIR) {
                    int nonairblockonsides = 0;
                    BlockPos[] surroundingPositions = new BlockPos[]{
                        bPos.west(),
                        bPos.east()
                    };
                    for (BlockPos posi : surroundingPositions) {
                        if (mc.level.getBlockState(posi).getBlock() != Blocks.AIR) {
                            nonairblockonsides++;
                        }
                    }
                    if (nonairblockonsides >= 2) AirBlockPatternNouth.add(bPos);
                    else {
                        AirBlockPatternNouthREJECT++;
                        AirBlockPatternNouth.add(bPos);
                    }
                } else if (mc.level.getBlockState(bPos).getBlock() != Blocks.AIR && mc.level.getBlockState(bPos).getBlock() != Blocks.CAVE_AIR) {
                    AirBlockPatternNouthREJECT2++;
                    AirBlockPatternNouth.add(bPos);
                }
            }
        }

        if (((double) AirBlockPatternWEastREJECT2 / (AirBlockPatternWEast.size() - AirBlockPatternWEastREJECT)) * 100 <= nonAirPercent.get() && ((double) AirBlockPatternWEastREJECT / AirBlockPatternWEast.size()) * 100 <= percent.get()) {
            for (BlockPos block : AirBlockPatternWEast) {
                for (int currentWidth = 4; currentWidth <= squareWidth; currentWidth++) {
                    for (int currentHeight = 5; currentHeight <= squareHeight; currentHeight++) {
                        if (isValidWEastPortalShape(AirBlockPatternWEast, block, currentWidth, currentHeight)) {
                            BlockPos boxStart = block;
                            BlockPos boxEnd = new BlockPos(boxStart.getX() + currentWidth - 1, boxStart.getY() + currentHeight - 1, boxStart.getZ());
                            boolean airfoundaboveorbelow = false;

                            if (falsepositives1.get()) {
                                for (int x = 0; x < currentWidth; x++) {
                                    BlockPos blockPos = boxStart.offset(x, -1, 0);
                                    if (mc.level.getBlockState(blockPos).getBlock() == Blocks.AIR) airfoundaboveorbelow = true;
                                }
                                for (int x = 0; x < currentWidth; x++) {
                                    BlockPos blockPos = boxStart.offset(x, currentHeight + 1, 0);
                                    if (mc.level.getBlockState(blockPos).getBlock() == Blocks.AIR) airfoundaboveorbelow = true;
                                }
                                if (airfoundaboveorbelow) continue;
                            }

                            AABB portalBox = new AABB(
                                new Vec3(boxStart.getX(), boxStart.getY(), boxStart.getZ()),
                                new Vec3(boxEnd.getX() + 1, boxEnd.getY() + 1, boxEnd.getZ() + 1)
                            );

                            boolean intersects = false;
                            for (AABB existingBox : possiblePortalLocations) {
                                if (portalBox.intersects(existingBox)) {
                                    intersects = true;
                                    break;
                                }
                            }

                            if (!intersects) {
                                portalFound(portalBox);
                                break;
                            }
                        }
                    }
                }
            }
        }

        if (((double) AirBlockPatternNouthREJECT2 / (AirBlockPatternNouth.size() - AirBlockPatternNouthREJECT)) * 100 <= nonAirPercent.get() && ((double) AirBlockPatternNouthREJECT / AirBlockPatternNouth.size()) * 100 <= percent.get()) {
            for (BlockPos block : AirBlockPatternNouth) {
                for (int currentWidth = 4; currentWidth <= squareWidth; currentWidth++) {
                    for (int currentHeight = 5; currentHeight <= squareHeight; currentHeight++) {
                        if (isValidNouthPortalShape(AirBlockPatternNouth, block, currentWidth, currentHeight)) {
                            BlockPos boxStart = block;
                            BlockPos boxEnd = new BlockPos(boxStart.getX(), boxStart.getY() + currentHeight - 1, boxStart.getZ() + currentWidth - 1);
                            boolean airfoundaboveorbelow = false;

                            if (falsepositives1.get()) {
                                for (int z = 0; z < currentWidth; z++) {
                                    BlockPos blockPos = boxStart.offset(0, -1, z);
                                    if (mc.level.getBlockState(blockPos).getBlock() == Blocks.AIR)
                                        airfoundaboveorbelow = true;
                                }
                                for (int z = 0; z < currentWidth; z++) {
                                    BlockPos blockPos = boxStart.offset(0, currentHeight + 1, z);
                                    if (mc.level.getBlockState(blockPos).getBlock() == Blocks.AIR)
                                        airfoundaboveorbelow = true;
                                }
                                if (airfoundaboveorbelow) continue;
                            }

                            AABB portalBox = new AABB(
                                new Vec3(boxStart.getX(), boxStart.getY(), boxStart.getZ()),
                                new Vec3(boxEnd.getX() + 1, boxEnd.getY() + 1, boxEnd.getZ() + 1)
                            );

                            boolean intersects = false;
                            for (AABB existingBox : possiblePortalLocations) {
                                if (portalBox.intersects(existingBox)) {
                                    intersects = true;
                                    break;
                                }
                            }

                            if (!intersects) portalFound(portalBox);
                        }
                    }
                }
            }
        }
    }

    private void portalFound(AABB portalBox) {
        if (!possiblePortalLocations.contains(portalBox)) {
            possiblePortalLocations.add(portalBox);
            mc.execute(() -> {
                if (displaycoords.get())
                    ChatUtils.sendMsg(Component.literal("Possible portal found: " + portalBox.getCenter()));
                else if (!displaycoords.get()) ChatUtils.sendMsg(Component.literal("Possible portal found!"));
            });
            BlockPos cp = new BlockPos(Math.round((float) portalBox.getCenter().x()), Math.round((float) portalBox.getCenter().y), Math.round((float) portalBox.getCenter().z()));
            if (!loggedPortalPositions.contains(cp) && locLogging.get()) {
                loggedPortalPositions.add(cp);
                portalPatterns.add(new PortalPattern(cp.getX(), cp.getY(), cp.getZ()));
                saveJson();
                saveCsv();
            }
        }
    }

    private boolean isValidWEastPortalShape(List<BlockPos> portalBlocks, BlockPos startBlock, Integer squareWidth, Integer squareHeight) {
        for (int currentWidth = 4; currentWidth <= squareWidth; currentWidth++) {
            for (int currentHeight = 5; currentHeight <= squareHeight; currentHeight++) {
                boolean validShape = true;

                for (int dx = 0; dx < currentWidth; dx++) {
                    for (int dy = 0; dy < currentHeight; dy++) {
                        BlockPos checkPos = startBlock.offset(dx, dy, 0);

                        if (ignorecorners.get() && ((dx == 0 && dy == 0) || (dx == currentWidth - 1 && dy == 0) ||
                            (dx == 0 && dy == currentHeight - 1) || (dx == currentWidth - 1 && dy == currentHeight - 1))) {
                            continue;
                        }

                        if (!portalBlocks.contains(checkPos)) {
                            validShape = false;
                            break;
                        }
                    }
                    if (!validShape) break;
                }

                if (validShape) return true;
            }
        }
        return false;
    }

    private boolean isValidNouthPortalShape(List<BlockPos> portalBlocks, BlockPos startBlock, Integer squareWidth, Integer squareHeight) {
        for (int currentWidth = 4; currentWidth <= squareWidth; currentWidth++) {
            for (int currentHeight = 5; currentHeight <= squareHeight; currentHeight++) {
                boolean validShape = true;

                for (int dz = 0; dz < currentWidth; dz++) {
                    for (int dy = 0; dy < currentHeight; dy++) {
                        BlockPos checkPos = startBlock.offset(0, dy, dz);

                        if (ignorecorners.get() && ((dz == 0 && dy == 0) || (dz == currentWidth - 1 && dy == 0) ||
                            (dz == 0 && dy == currentHeight - 1) || (dz == currentWidth - 1 && dy == currentHeight - 1))) {
                            continue;
                        }

                        if (!portalBlocks.contains(checkPos)) {
                            validShape = false;
                            break;
                        }
                    }
                    if (!validShape) break;
                }

                if (validShape) return true;
            }
        }
        return false;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        boolean legacyMode = detectionMode.get() == DetectionMode.Legacy || detectionMode.get() == DetectionMode.Both;
        boolean disturbanceMode = detectionMode.get() == DetectionMode.Disturbance || detectionMode.get() == DetectionMode.Both;
        if (legacyMode && (portalSideColor.get().a > 5 || portalLineColor.get().a > 5)) {
            synchronized (possiblePortalLocations) {
                for (AABB box : possiblePortalLocations) {
                    if (box == null) continue;
                    BlockPos playerPos = new BlockPos(mc.player.getBlockX(), Math.round((float) box.getCenter().y), mc.player.getBlockZ());
                    if (playerPos.closerToCenterThan(box.getCenter(), renderDistance.get() * 16)) {
                        render(box, portalSideColor.get(), portalLineColor.get(), shapeMode.get(), event);
                    }
                }
                if (nearesttrcr.get()) {
                    render2(new AABB(new Vec3(closestPortalX, closestPortalY, closestPortalZ), new Vec3(closestPortalX, closestPortalY, closestPortalZ)), portalSideColor.get(), portalLineColor.get(), ShapeMode.Sides, event);
                }
            }
        }
        // Disturbance rendering path: portal-shaped clusters with disturbance colors, plus
        // any merged cave air / all-air-in-cave boxes if enabled.
        if (!disturbanceMode) return;
        List<RenderCluster> clusters = renderClusters;
        List<RenderBox> caveAir = renderCaveAir;
        List<long[]> airInCave = renderAirInCave;
        for (RenderCluster c : clusters) {
            event.renderer.box(c.minX, c.minY, c.minZ, c.maxX, c.maxY, c.maxZ, disturbanceFill, disturbanceOutline, ShapeMode.Both, 0);
            if (renderBeacon.get()) {
                int h = beaconHeight.get();
                event.renderer.line(c.centerX, c.maxY, c.centerZ, c.centerX, c.maxY + h, c.centerZ, disturbanceOutline);
            }
        }
        if (highlightAllAirInCave.get()) {
            for (long[] pos : airInCave) {
                int x = (int) pos[0], y = (int) pos[1], z = (int) pos[2];
                event.renderer.box(x, y, z, x + 1, y + 1, z + 1, caveAirFill, caveAirLine, caveAirShapeMode.get(), 0);
            }
        }
        if (showAllCaveAir.get()) {
            ShapeMode caShape = caveAirShapeMode.get();
            for (RenderBox b : caveAir) {
                event.renderer.box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, caveAirFill, caveAirLine, caShape, 0);
            }
        }
        if (nearesttrcr.get() && !disturbancePortals.isEmpty()) {
            render2(new AABB(new Vec3(closestPortalX, closestPortalY, closestPortalZ), new Vec3(closestPortalX, closestPortalY, closestPortalZ)), disturbanceColor.get(), disturbanceLineColor.get(), ShapeMode.Sides, event);
        }
        if (trcr.get() && !nearesttrcr.get()) {
            for (AABB box : disturbancePortals) {
                if (Math.abs(box.minX - RenderUtils.center.x) <= renderDistance.get() * 16 && Math.abs(box.minZ - RenderUtils.center.z) <= renderDistance.get() * 16) {
                    event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z, box.minX + 0.5, box.minY + ((box.maxY - box.minY) / 2), box.minZ + 0.5, disturbanceLineColor.get());
                }
            }
        }
    }

    // The box outline is drawn by the explicit line() calls in render()/render2(), so the
    // box's own line colour is always fully transparent - one shared instance is enough.
    private static final Color NO_LINE = new Color(0, 0, 0, 0);

    private void render(AABB box, Color sides, Color lines, ShapeMode shapeMode, Render3DEvent event) {
        if (trcr.get() && Math.abs(box.minX - RenderUtils.center.x) <= renderDistance.get() * 16 && Math.abs(box.minZ - RenderUtils.center.z) <= renderDistance.get() * 16)
            if (!nearesttrcr.get())
                event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z, box.minX + 0.5, box.minY + ((box.maxY - box.minY) / 2), box.minZ + 0.5, lines);
        event.renderer.box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, sides, NO_LINE, shapeMode, 0);
    }

    private void render2(AABB box, Color sides, Color lines, ShapeMode shapeMode, Render3DEvent event) {
        if (trcr.get() && Math.abs(box.minX - RenderUtils.center.x) <= renderDistance.get() * 16 && Math.abs(box.minZ - RenderUtils.center.z) <= renderDistance.get() * 16)
            event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z, box.minX + 0.5, box.minY + ((box.maxY - box.minY) / 2), box.minZ + 0.5, lines);
        event.renderer.box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, sides, NO_LINE, shapeMode, 0);
    }

    private void removeChunksOutsideRenderDistance(Set<LevelChunk> worldChunks) {
        removechunksOutsideRenderDistance(scannedChunks, worldChunks);
        removeChunksOutsideRenderDistance(possiblePortalLocations, worldChunks);
        removeChunksOutsideRenderDistance(disturbancePortals, worldChunks);
    }

    private void removeDisturbanceBoxesOutsideRenderDistance(Set<LevelChunk> worldChunks) {
        removeChunksOutsideRenderDistance(disturbancePortals, worldChunks);
    }

    private void removeChunksOutsideRenderDistance(Set<AABB> boxSet, Set<LevelChunk> worldChunks) {
        boxSet.removeIf(box -> {
            BlockPos boxPos = new BlockPos((int) Math.floor(box.getCenter().x()), (int) Math.floor(box.getCenter().y), (int) Math.floor(box.getCenter().z()));
            assert mc.level != null;
            return !worldChunks.contains(mc.level.getChunk(boxPos));
        });
    }

    private void removechunksOutsideRenderDistance(Set<ChunkPos> chunkSet, Set<LevelChunk> worldChunks) {
        chunkSet.removeIf(c -> {
            assert mc.level != null;
            return !worldChunks.contains(mc.level.getChunk(c.x(), c.z()));
        });
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        portalPatterns.sort(Comparator.comparingInt(a -> a.y));
        WVerticalList list = theme.verticalList();
        WButton clear = list.add(theme.button("Clear Logged Positions")).widget();
        WTable table = new WTable();
        if (!portalPatterns.isEmpty()) list.add(table);
        clear.action = () -> {
            portalPatterns.clear();
            loggedPortalPositions.clear();
            possiblePortalLocations.clear();
            table.clear();
            saveJson();
            saveCsv();
        };
        fillTable(theme, table);
        return list;
    }

    private void fillTable(GuiTheme theme, WTable table) {
        List<PortalPattern> portalCoords = new ArrayList<>();
        for (PortalPattern p : portalPatterns) {
            if (!portalCoords.contains(p)) {
                portalCoords.add(p);
                table.add(theme.label("Pos: " + p.x + ", " + p.y + ", " + p.z));
                WButton gotoBtn = table.add(theme.button("Goto")).widget();
                gotoBtn.action = () -> PathManagers.get().moveTo(new BlockPos(p.x, p.y, p.z), true);
                WMinus delete = table.add(theme.minus()).widget();
                delete.action = () -> {
                    portalPatterns.remove(p);
                    loggedPortalPositions.remove(new BlockPos(p.x, p.y, p.z));
                    possiblePortalLocations.removeIf(box -> {
                        BlockPos cp = new BlockPos(Math.round((float) box.getCenter().x()), Math.round((float) box.getCenter().y), Math.round((float) box.getCenter().z()));
                        return cp.equals(new BlockPos(p.x, p.y, p.z));
                    });
                    table.clear();
                    fillTable(theme, table);
                    saveJson();
                    saveCsv();
                };
                table.row();
            }
        }
    }

    private void loadPortalPatterns() {
        File file = getJsonFile();
        boolean loaded = false;
        if (file.exists()) {
            try (FileReader reader = new FileReader(file)) {
                List<PortalPattern> data = GSON.fromJson(reader, new TypeToken<List<PortalPattern>>() {
                }.getType());
                if (data != null) {
                    portalPatterns.addAll(data);
                    for (PortalPattern p : data) {
                        loggedPortalPositions.add(new BlockPos(p.x, p.y, p.z));
                    }
                    loaded = true;
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to load portal pattern JSON", e);
            }
        }
        if (!loaded) {
            file = getCsvFile();
            if (file.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                    reader.readLine();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] values = line.split(",");
                        PortalPattern p = new PortalPattern(
                            Integer.parseInt(values[0]),
                            Integer.parseInt(values[1]),
                            Integer.parseInt(values[2])
                        );
                        portalPatterns.add(p);
                        loggedPortalPositions.add(new BlockPos(p.x, p.y, p.z));
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to load portal pattern CSV", e);
                }
            }
        }
    }

    private void saveCsv() {
        try {
            File file = getCsvFile();
            file.getParentFile().mkdirs();
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                writer.write("X,Y,Z\n");
                for (PortalPattern p : portalPatterns) {
                    p.write(writer);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to save portal pattern CSV", e);
        }
    }

    private void saveJson() {
        try {
            File file = getJsonFile();
            file.getParentFile().mkdirs();
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                GSON.toJson(portalPatterns, writer);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to save portal pattern JSON", e);
        }
    }

    private File getJsonFile() {
        return new File(new File(new File("TrouserStreak", "PortalPatterns"), Utils.getFileWorldName()), "portalpatterns.json");
    }

    private File getCsvFile() {
        return new File(new File(new File("TrouserStreak", "PortalPatterns"), Utils.getFileWorldName()), "portalpatterns.csv");
    }

    private static class PortalPattern {
        private static final StringBuilder sb = new StringBuilder();
        public int x, y, z;

        public PortalPattern(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public void write(Writer writer) throws IOException {
            sb.setLength(0);
            sb.append(x).append(',').append(y).append(',').append(z).append('\n');
            writer.write(sb.toString());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PortalPattern pattern = (PortalPattern) o;
            return x == pattern.x && y == pattern.y && z == pattern.z;
        }

        @Override
        public int hashCode() {
            return Objects.hash(x, y, z);
        }
    }
}
