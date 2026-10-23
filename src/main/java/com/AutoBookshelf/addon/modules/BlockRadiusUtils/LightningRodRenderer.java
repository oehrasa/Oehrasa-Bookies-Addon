package com.AutoBookshelf.addon.modules.BlockRadiusUtils;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;

import static com.AutoBookshelf.addon.modules.BlockRadiusUtils.BlockRadiusShared.RangeBox;
import static com.AutoBookshelf.addon.modules.BlockRadiusUtils.BlockRadiusShared.ShapeMode;
import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Lightning rod redirect range: a flat 1-block-tall square per rod, or the merged union of
 * same-height footprints when merge-overlapping-rods is on.
 * <p>
 * Chunk section scans are cached per chunk so the throttled rescan never re-reads every cell.
 */
public class
LightningRodRenderer {
    private final BlockRadiusShared shared;

    private final Setting<Boolean> showLightningRods;
    private final Setting<Boolean> mergeOverlappingRods;
    private final Setting<Boolean> excludeInactiveRods;
    private final Setting<Double> lightningRodRange;
    private final Setting<SettingColor> sideColor;
    private final Setting<SettingColor> lineColor;

    private final List<RangeBox> boxes = new ArrayList<>();

    /**
     * WorldChunk-local lightning-rod cell cache keyed by packed chunk coordinates. Rebuilt
     * only when a chunk is first scanned or a rod block update touches it, so the
     * per-interval scan never re-reads every section cell.
     */
    private final Map<Long, List<int[]>> rodCellCache = new HashMap<>();
    private ClientWorld lastRodCacheWorld = null;

    private static final Set<Block> ROD_VARIANTS = buildRodVariants();

    private static Set<Block> buildRodVariants() {
        Set<Block> variants = new HashSet<>();
        variants.add(Blocks.LIGHTNING_ROD);
        variants.add(Blocks.EXPOSED_LIGHTNING_ROD);
        variants.add(Blocks.WEATHERED_LIGHTNING_ROD);
        variants.add(Blocks.OXIDIZED_LIGHTNING_ROD);
        variants.add(Blocks.WAXED_LIGHTNING_ROD);
        variants.add(Blocks.WAXED_EXPOSED_LIGHTNING_ROD);
        variants.add(Blocks.WAXED_WEATHERED_LIGHTNING_ROD);
        variants.add(Blocks.WAXED_OXIDIZED_LIGHTNING_ROD);
        return variants;
    }

    public LightningRodRenderer(SettingGroup sgGeneral, SettingGroup sgRender,
                                BlockRadiusShared shared, Setting<ShapeMode> shapeMode) {
        this.shared = shared;

        showLightningRods = sgGeneral.add(new BoolSetting.Builder()
            .name("show-lightning-rods")
            .description("Renders the range of a lightning rods in a flat square.")
            .defaultValue(true)
            .build()
        );

        mergeOverlappingRods = sgGeneral.add(new BoolSetting.Builder()
            .name("merge-overlapping-rods")
            .description("Draws the union of same-height lightning rod footprints as one region instead of one box per rod.")
            .defaultValue(true)
            .visible(showLightningRods::get)
            .build()
        );

        excludeInactiveRods = sgGeneral.add(new BoolSetting.Builder()
            .name("exclude-inactive-rod")
            .description("Hides lightning rods that cannot redirect lightning because their tip is covered by a block above.")
            .defaultValue(true)
            .visible(showLightningRods::get)
            .build()
        );

        lightningRodRange = sgGeneral.add(new DoubleSetting.Builder()
            .name("lightning-rod-range")
            .description("Redirect radius in blocks of a lightning rod (128 in Java, 64 in Bedrock Edition).")
            .defaultValue(128)
            .range(1, 128)
            .visible(showLightningRods::get)
            .build()
        );

        sideColor = sgRender.add(new ColorSetting.Builder()
            .name("lightning-rod-side-color")
            .description("Side color of the lightning rod 1-block-tall footprint.")
            .defaultValue(new SettingColor(255, 200, 0, 45))
            .visible(() -> shapeMode.get().sides() && showLightningRods.get())
            .build()
        );

        lineColor = sgRender.add(new ColorSetting.Builder()
            .name("lightning-rod-line-color")
            .description("Line color of the lightning rod 1-block-tall footprint.")
            .defaultValue(new SettingColor(255, 200, 0, 255))
            .visible(() -> shapeMode.get().lines() && showLightningRods.get())
            .build()
        );
    }

    /**
     * True when either side of a BlockUpdateEvent is a rod, like when the chunk cache must drop.
     */
    public boolean isRodUpdate(Block newBlock, Block oldBlock) {
        return ROD_VARIANTS.contains(newBlock) || ROD_VARIANTS.contains(oldBlock);
    }

    /**
     * Drop the cached rod list for the chunk containing this position.
     */
    public void invalidateChunkAt(BlockPos pos) {
        rodCellCache.remove(packChunk(pos.getX() >> 4, pos.getZ() >> 4));
    }

    public void invalidateChunk(int chunkX, int chunkZ) {
        rodCellCache.remove(packChunk(chunkX, chunkZ));
    }

    public void scan() {
        boxes.clear();
        if (!showLightningRods.get()) {
            rodCellCache.clear();
            return;
        }

        ClientWorld world = mc.world;
        if (world != lastRodCacheWorld) {
            rodCellCache.clear();
            lastRodCacheWorld = world;
        }

        BlockPos playerPos = mc.player.getBlockPos();
        int centerChunkX = playerPos.getX() >> 4;
        int centerChunkZ = playerPos.getZ() >> 4;
        int radius = shared.searchRadius.get();
        double range = lightningRodRange.get();
        int bottomY = world.getBottomY();

        // Rod boxes further than maxRenderDistance from the camera are never rendered,
        // so a rod more than range + maxRenderDistance away from the player can't
        // possibly render; such rods and chunks are skipped before any work is done.
        double maxDist = shared.maxRenderDistance.get();
        double maxDistSq = maxDist * maxDist;

        // Collected rod centers {x, y, z}, used to build either per-rod boxes or
        // the merged union footprint.
        List<int[]> rods = new ArrayList<>();

        Set<Long> currentChunks = new HashSet<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;
                long chunkKey = packChunk(chunkX, chunkZ);

                if (!world.isPosLoaded(new BlockPos(chunkX << 4, bottomY, chunkZ << 4))) {
                    rodCellCache.remove(chunkKey);
                    continue;
                }

                currentChunks.add(chunkKey);

                // Cheap whole-chunk cull using the squared distance to the chunk's
                // nearest corner, so far chunks never touch a single section.
                long chunkStartX = (long) chunkX << 4;
                long chunkStartZ = (long) chunkZ << 4;
                double chunkDx = Math.max(0, Math.abs(playerPos.getX() - chunkStartX) - 16 - range);
                double chunkDz = Math.max(0, Math.abs(playerPos.getZ() - chunkStartZ) - 16 - range);
                if (chunkDx * chunkDx + chunkDz * chunkDz > maxDistSq) continue;

                List<int[]> cached = rodCellCache.get(chunkKey);
                if (cached == null) {
                    cached = scanChunkForLightningRods(chunkX, chunkZ, bottomY);
                    rodCellCache.put(chunkKey, cached);
                }

                for (int[] rod : cached) {
                    double px = Math.max(0, Math.abs(rod[0] - playerPos.getX()) - range);
                    double pz = Math.max(0, Math.abs(rod[2] - playerPos.getZ()) - range);
                    if (px * px + pz * pz > maxDistSq) continue;
                    rods.add(rod);
                }
            }
        }

        // Drop cached chunks we no longer search so the cache stays bounded.
        rodCellCache.keySet().removeIf(key -> !currentChunks.contains(key));

        // Vanilla only redirects lightning to a rod whose tip is the highest block
        // of its column, so a rod with anything on top of it is dead weight.
        if (excludeInactiveRods.get()) rods.removeIf(rod -> !isLightningRodActive(rod));

        if (mergeOverlappingRods.get()) {
            mergeRodFootprints(rods, range);
        } else {
            for (int[] rod : rods) {
                // Same extent convention as beacons: range each way plus the rod
                // block itself (+1), so boxes center on the block footprint.
                boxes.add(new RangeBox(
                    rod[0] - range, rod[1], rod[2] - range,
                    rod[0] + range + 1, rod[1] + 1.0, rod[2] + range + 1
                ));
            }
        }
    }

    public void render(Render3DEvent event, Vec3d cam, double maxDistSq) {
        if (!showLightningRods.get()) return;
        for (RangeBox box : boxes) {
            if (box.distanceSq(cam) > maxDistSq) continue;
            shared.renderBox(event.renderer,
                box.minX, box.minY, box.minZ,
                box.maxX, box.maxY, box.maxZ,
                sideColor.get(), lineColor.get());
        }
    }

    public void clear() {
        boxes.clear();
        rodCellCache.clear();
    }

    /**
     * vanilla's ServerWorld.getLightningRodPos: a rod only works while it
     * is the topmost block of its column (world-surface heightmap), so any
     * non-air block directly above its tip renders it inactive.
     *
     * @param rod rod centre {x, y, z}
     */
    private boolean isLightningRodActive(int[] rod) {
        return rod[1] == mc.world.getTopY(Heightmap.Type.WORLD_SURFACE, rod[0], rod[2]) - 1;
    }

    /**
     * Unions same-height rod footprints into a single region drawn once, so
     * overlapping rods render flat instead of stacking their fill opacity.
     * Rods whose Y differs never share a volume (each box is one block tall),
     * so they are grouped by Y and processed as an XZ interval merge.
     *
     * @param rods rod centers {x, y, z} sorted by Y, then X, then Z
     */
    private void mergeRodFootprints(List<int[]> rods, double range) {
        if (rods.isEmpty()) return;

        rods.sort((a, b) -> {
            if (a[1] != b[1]) return Integer.compare(a[1], b[1]);
            if (a[0] != b[0]) return Integer.compare(a[0], b[0]);
            return Integer.compare(a[2], b[2]);
        });

        int n = rods.size();
        int i = 0;
        while (i < n) {
            int y = rods.get(i)[1];
            int j = i;
            while (j < n && rods.get(j)[1] == y) j++;
            unionSquareFootprint(rods, i, j, range, y);
            i = j;
        }
    }

    /**
     * Merges the XZ squares of the {@code [from, to)} rods (all at block height
     * {@code y}) into the minimal set of flat 1-block-tall boxes covering
     * exactly the union. Each rod's square spans {@code [c-range, c+range+1]} on
     * both axes (same extent convention as the unmerged rod and beacon boxes), so
     * edges sit at every {@code c ± range} and {@code c ± range + 1}. Z
     * breakpoints are every rod's square edge; the set of covering rods is
     * constant between two consecutive breakpoints, so each Z slab has one merged
     * {@code [x-range, x+range+1]} interval list. Consecutive slabs with the same
     * interval list (rods in a column or grid) are coalesced into a single box so
     * no interior outlines or faces appear. A lone rod therefore stays a single
     * box over {@code [z-range, z+range+1]}, matching the unmerged extent.
     */
    private void unionSquareFootprint(List<int[]> rods, int from, int to, double range, int y) {
        // Z breakpoints: every rod square starts at z - range and ends at z + range + 1.
        TreeSet<Double> zBreakpoints = new TreeSet<>();
        for (int k = from; k < to; k++) {
            int[] rod = rods.get(k);
            zBreakpoints.add(rod[2] - range);
            zBreakpoints.add(rod[2] + range + 1);
        }

        // Two reusable interval lists; each holds {lo, hi} for one slab so the
        // merge below never allocates beyond them.
        List<double[]> prevIntervals = new ArrayList<>();
        List<double[]> intervals = new ArrayList<>();
        double slabStartZ = 0;
        Double prevZ = null;

        for (double z : zBreakpoints) {
            if (prevZ == null) {
                slabStartZ = z;
                prevZ = z;
                continue;
            }

            // Midpoint of the slab [prevZ, z]: the covering rod set is constant across it.
            double midZ = (prevZ + z) / 2;

            intervals.clear();
            double curLo = 0;
            double curHi = 0;
            boolean open = false;

            // Interval-merge over the X-sorted rods in this Y group: each rod whose
            // square covers the slab contributes one [x-range, x+range+1] interval.
            for (int k = from; k < to; k++) {
                int[] rod = rods.get(k);
                // midZ never lands exactly on a square edge (breakpoints are the edges).
                if (midZ <= rod[2] - range || midZ >= rod[2] + range + 1) continue;

                double lo = rod[0] - range;
                double hi = rod[0] + range + 1;
                if (!open) {
                    curLo = lo;
                    curHi = hi;
                    open = true;
                } else if (lo > curHi) {
                    intervals.add(new double[]{curLo, curHi});
                    curLo = lo;
                    curHi = hi;
                } else if (hi > curHi) {
                    curHi = hi;
                }
            }
            if (open) {
                intervals.add(new double[]{curLo, curHi});
            }

            if (open) {
                if (!prevIntervals.isEmpty() && !sameIntervals(prevIntervals, intervals)) {
                    // Interval list changed: flush the coalesced slab [slabStartZ, prevZ].
                    emitIntervalBoxes(prevIntervals, y, slabStartZ, prevZ);
                    slabStartZ = prevZ;
                }

                // The current slab becomes the previous one; reuse the freed list.
                List<double[]> tmp = prevIntervals;
                prevIntervals = intervals;
                intervals = tmp;
            } else if (!prevIntervals.isEmpty()) {
                // Gap with no covering rods: flush, and the next region starts at z.
                emitIntervalBoxes(prevIntervals, y, slabStartZ, prevZ);
                prevIntervals.clear();
                slabStartZ = z;
            }

            prevZ = z;
        }

        if (!prevIntervals.isEmpty()) {
            emitIntervalBoxes(prevIntervals, y, slabStartZ, prevZ);
        }
    }

    /**
     * Emits one flat box per merged X interval spanning Z range
     * {@code [zFrom, zTo]} at block height {@code y}.
     */
    private void emitIntervalBoxes(List<double[]> intervals, int y, double zFrom, double zTo) {
        for (double[] interval : intervals) {
            boxes.add(new RangeBox(interval[0], y, zFrom, interval[1], y + 1.0, zTo));
        }
    }

    private boolean sameIntervals(List<double[]> a, List<double[]> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            double[] ia = a.get(i);
            double[] ib = b.get(i);
            if (ia[0] != ib[0] || ia[1] != ib[1]) return false;
        }
        return true;
    }

    private List<int[]> scanChunkForLightningRods(int chunkX, int chunkZ, int bottomY) {
        List<int[]> rods = new ArrayList<>();
        WorldChunk chunk = mc.world.getChunk(chunkX, chunkZ);
        ChunkSection[] sections = chunk.getSectionArray();

        // Iterate every section
        for (int s = 0; s < sections.length; s++) {
            ChunkSection section = sections[s];
            if (section.isEmpty()) continue; // skip fully-air sections fast

            int sectionBaseY = bottomY + (s << 4);

            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int ly = 0; ly < 16; ly++) {
                        if (!ROD_VARIANTS.contains(section.getBlockState(lx, ly, lz).getBlock()))
                            continue;

                        rods.add(new int[]{(chunkX << 4) + lx, sectionBaseY + ly, (chunkZ << 4) + lz});
                    }
                }
            }
        }

        return rods;
    }

    private static long packChunk(int chunkX, int chunkZ) {
        return (long) chunkX << 32 | (chunkZ & 0xFFFFFFFFL);
    }
}
