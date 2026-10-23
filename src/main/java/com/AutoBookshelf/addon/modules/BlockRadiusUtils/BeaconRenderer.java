package com.AutoBookshelf.addon.modules.BlockRadiusUtils;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BeaconBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static com.AutoBookshelf.addon.modules.BlockRadiusUtils.BlockRadiusShared.RangeBox;
import static com.AutoBookshelf.addon.modules.BlockRadiusUtils.BlockRadiusShared.ShapeMode;
import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Beacon range visualisation: one box per powered beacon, sized by pyramid level and coloured
 * per level (or with a flat colour when level colours are off).
 */
public class BeaconRenderer {
    private final BlockRadiusShared shared;
    private final Setting<ShapeMode> shapeMode;

    private final Setting<Boolean> showBeacons;
    private final Setting<Boolean> cullOverlapping;
    private final Setting<Boolean> excludeInactiveBeacons;

    private final Setting<Boolean> beaconLevelColors;
    private final Setting<Integer> beaconFillAlpha;
    private final Setting<SettingColor> beaconLevelColor1;
    private final Setting<SettingColor> beaconLevelColor2;
    private final Setting<SettingColor> beaconLevelColor3;
    private final Setting<SettingColor> beaconLevelColor4;
    private final Setting<SettingColor> beaconSideColor;
    private final Setting<SettingColor> beaconLineColor;

    private final Setting<Boolean> highlightSolid;
    private final Setting<SettingColor> solidEdgeColor;

    // Cached SettingColors built from the per-level colour settings; rebuilt whenever a
    // level colour, the fill alpha, or the toggle changes, or on each throttled beacon scan,
    // so onRender allocates nothing per frame. Index i corresponds to pyramid level i+1.
    private final SettingColor[] levelSides = new SettingColor[4];
    private final SettingColor[] levelLines = new SettingColor[4];

    private final List<RangeBox> boxes = new ArrayList<>();

    // Dedup keys for one box's surface shell, cleared per box. asLong() keeps this O(1); the
    // previous ArrayList<BlockPos>.contains() was a linear scan run once per shell cell, i.e.
    // quadratic in the number of collected cells (~10^8 comparisons for a level-4 beacon whose
    // shell intersects terrain).
    private final LongOpenHashSet edgeCellKeys = new LongOpenHashSet();

    /**
     * Surface shells cached per beacon, keyed by packed beacon position, reused until a block
     * update inside the box (or its one-block ring, the exposure test reads the neighbours just
     * outside it) invalidates them. The shell depends only on world blocks, so recomputing it on
     * every throttled scan was pure waste: 60002 cells for a level-4 box, several times a minute,
     * while nothing moved. `ChunkDataEvent` drops a resent chunk's entries (a full resend raises
     * no BlockUpdateEvent) and a level change clears the cache.
     */
    private final Map<Long, ShellEntry> shellCache = new HashMap<>();
    private ClientWorld shellCacheWorld = null;

    /**
     * Beacons seen by the current scan, used to prune shells of beacons that are gone or unloaded.
     */
    private final LongOpenHashSet seenBeacons = new LongOpenHashSet();

    /**
     * One beacon's cached shell. The beacon position and level are kept so the entry can be
     * invalidated by a block update or a chunk resend without re-deriving the box.
     */
    private static final class ShellEntry {
        private final int x, y, z, level;
        private final List<BlockPos> cells;

        private ShellEntry(BlockPos beaconPos, int level, List<BlockPos> cells) {
            this.x = beaconPos.getX();
            this.y = beaconPos.getY();
            this.z = beaconPos.getZ();
            this.level = level;
            this.cells = cells;
        }

        /**
         * True when a block at {@code pos} can change this shell: the box plus its one-block ring.
         */
        private boolean affectedBy(BlockPos pos) {
            int r = shellMargin(level);
            return Math.abs(pos.getX() - x) <= r
                && Math.abs(pos.getY() - y) <= r
                && Math.abs(pos.getZ() - z) <= r;
        }

        /**
         * True when this shell's box (plus its ring) intersects the given chunk column.
         */
        private boolean intersectsChunk(int minX, int maxX, int minZ, int maxZ) {
            int r = shellMargin(level);
            return x + r >= minX && x - r <= maxX && z + r >= minZ && z - r <= maxZ;
        }
    }

    /**
     * The box half-extent plus one block. The shell spans +-range (range = 10 + level * 10) and
     * the exposure test reads the neighbours one block further out, so a block up to
     * range + 1 away from the beacon can change the shell.
     */
    private static int shellMargin(int level) {
        return 11 + level * 10;
    }

    /**
     * Vanilla's own `!isAir() && isOpaque()`; the section palette test in {@link #sectionAt}.
     */
    private static final Predicate<BlockState> OCCLUDING = state -> !state.isAir() && state.isOpaque();

    public BeaconRenderer(SettingGroup sgGeneral, SettingGroup sgRender,
                          BlockRadiusShared shared, Setting<ShapeMode> shapeMode) {
        this.shared = shared;
        this.shapeMode = shapeMode;

        showBeacons = sgGeneral.add(new BoolSetting.Builder()
            .name("show-beacons")
            .description("Renders the range of powered beacons.")
            .defaultValue(true)
            .build()
        );

        cullOverlapping = sgGeneral.add(new BoolSetting.Builder()
            .name("cull-overlapping")
            .description("Hides a beacon box fully contained inside a higher-level beacon's box.")
            .defaultValue(true)
            .visible(showBeacons::get)
            .build()
        );

        excludeInactiveBeacons = sgGeneral.add(new BoolSetting.Builder()
            .name("exclude-inactive-beacons")
            .description("Hides beacons that have a valid pyramid but is blocked by an opaque block above.")
            .defaultValue(true)
            .visible(showBeacons::get)
            .build()
        );

        beaconLevelColors = sgRender.add(new BoolSetting.Builder()
            .name("beacon-level-colors")
            .description("Colors each beacon box by pyramid level using the per-level color settings below.")
            .defaultValue(true)
            .visible(showBeacons::get)
            .onChanged(v -> updatePalette())
            .build()
        );

        beaconFillAlpha = sgRender.add(new IntSetting.Builder()
            .name("beacon-fill-alpha")
            .description("Opacity of the level-colored beacon fill.")
            .defaultValue(38)
            .range(0, 255)
            .visible(() -> beaconLevelColors.get() && showBeacons.get())
            .onChanged(v -> updatePalette())
            .build()
        );

        beaconLevelColor1 = sgRender.add(new ColorSetting.Builder()
            .name("beacon-level-1-color")
            .description("Color for level 1 beacon ranges.")
            .defaultValue(new SettingColor(255, 255, 255, 38))
            .visible(() -> beaconLevelColors.get() && showBeacons.get())
            .onChanged(v -> updatePalette())
            .build()
        );

        beaconLevelColor2 = sgRender.add(new ColorSetting.Builder()
            .name("beacon-level-2-color")
            .description("Color for level 2 beacon ranges.")
            .defaultValue(new SettingColor(102, 204, 255, 38))
            .visible(() -> beaconLevelColors.get() && showBeacons.get())
            .onChanged(v -> updatePalette())
            .build()
        );

        beaconLevelColor3 = sgRender.add(new ColorSetting.Builder()
            .name("beacon-level-3-color")
            .description("Color for level 3 beacon ranges.")
            .defaultValue(new SettingColor(102, 255, 128, 38))
            .visible(() -> beaconLevelColors.get() && showBeacons.get())
            .onChanged(v -> updatePalette())
            .build()
        );

        beaconLevelColor4 = sgRender.add(new ColorSetting.Builder()
            .name("beacon-level-4-color")
            .description("Color for level 4 beacon ranges.")
            .defaultValue(new SettingColor(255, 217, 77, 38))
            .visible(() -> beaconLevelColors.get() && showBeacons.get())
            .onChanged(v -> updatePalette())
            .build()
        );

        beaconSideColor = sgRender.add(new ColorSetting.Builder()
            .name("beacon-side-color")
            .description("Side color of beacon range boxes (used when beacon-level-colors is off).")
            .defaultValue(new SettingColor(0, 255, 255, 60))
            .visible(() -> !beaconLevelColors.get() && shapeMode.get().sides() && showBeacons.get())
            .build()
        );

        beaconLineColor = sgRender.add(new ColorSetting.Builder()
            .name("beacon-line-color")
            .description("Line color of beacon range boxes (used when beacon-level-colors is off).")
            .defaultValue(new SettingColor(0, 255, 255, 255))
            .visible(() -> !beaconLevelColors.get() && shapeMode.get().lines() && showBeacons.get())
            .build()
        );

        highlightSolid = sgRender.add(new BoolSetting.Builder()
            .name("beacon-highlight-solid-voxels")
            .description("Render range box surface voxels that overlap an exposed solid world block in a different colour.")
            .defaultValue(false)
            .visible(showBeacons::get)
            .build()
        );

        solidEdgeColor = sgRender.add(new ColorSetting.Builder()
            .name("beacon-solid-voxel-color")
            .description("Colour for range box voxels that overlap a solid world block.")
            .defaultValue(new SettingColor(255, 0, 255, 75))
            .visible(() -> highlightSolid.get() && showBeacons.get())
            .build()
        );
    }

    public void scan() {
        boxes.clear();
        if (!showBeacons.get()) return;

        if (beaconLevelColors.get()) updatePalette();

        // A level change invalidates every cached shell (their meaning depends on the level).
        ClientWorld world = mc.world;
        if (world != shellCacheWorld) {
            shellCache.clear();
            shellCacheWorld = world;
        }
        boolean highlight = highlightSolid.get();
        seenBeacons.clear();

        for (BlockEntity be : Utils.blockEntities()) {
            if (!(be instanceof BeaconBlockEntity)) continue;

            BlockPos pos = be.getPos();
            int level = getBeaconLevel(pos);
            if (level < 1) continue;

            // A beacon is only actually active if its beam isn't blocked by an opaque
            // block above it (bedrock doesn't block beams, so Nether-ceiling beacons still work).
            if (excludeInactiveBeacons.get() && isBeaconBeamBlocked(pos)) continue;

            double range = 10 + (level * 10);
            RangeBox box = new RangeBox(
                pos.getX() - range, pos.getY() - range, pos.getZ() - range,
                pos.getX() + range + 1, pos.getY() + range + 1, pos.getZ() + range + 1
            );
            box.level = level;

            long key = pos.asLong();
            seenBeacons.add(key);
            if (highlight) {
                ShellEntry entry = shellCache.get(key);
                if (entry == null || entry.level != level) {
                    entry = new ShellEntry(pos, level, computeSolidEdgeCells(box));
                    shellCache.put(key, entry);
                }
                box.solidEdgeCells.addAll(entry.cells);
            }
            boxes.add(box);
        }

        // Drop the shells of beacons that are gone (or whose chunks unloaded), so the cache is
        // bounded by the beacons actually in the world. A beacon whose chunk reloads later simply
        // recomputes its shell then.
        if (!shellCache.isEmpty()) shellCache.keySet().removeIf(k -> !seenBeacons.contains(k.longValue()));

        // O(n²) containment cull; runs once per scan, not per frame.
        //
        // The tie-breaker is the list index, not hashCode(). RangeBox overrides neither hashCode
        // nor equals, so hashCode() was identity hash -> non-deterministic across runs, and when
        // two same-level boxes happened to collide neither of the mutually-containing pair would
        // be culled (both render). List order is stable within a run and guarantees exactly one of
        // the pair is culled: for equal-level boxes A(idx i) and B(idx j, i<j), B's check sees
        // i<j so A is culled by B, while A's check on B sees j>i and culls nothing. Higher-level
        // precedence is preserved.
        for (int i = 0; i < boxes.size(); i++) {
            RangeBox box = boxes.get(i);
            box.render = true;
            if (!cullOverlapping.get()) continue;
            for (int j = 0; j < boxes.size(); j++) {
                RangeBox other = boxes.get(j);
                if (i == j) continue;
                if ((other.level > box.level || (other.level == box.level && j > i))
                    && other.contains(box)) {
                    box.render = false;
                    break;
                }
            }
        }
    }

    public void render(Render3DEvent event, Vec3d cam, double maxDistSq) {
        if (!showBeacons.get()) return;
        boolean useLevelColors = beaconLevelColors.get();

        for (RangeBox box : boxes) {
            if (!box.render) continue;
            if (box.distanceSq(cam) > maxDistSq) continue;

            SettingColor side = beaconSideColor.get();
            SettingColor line = beaconLineColor.get();
            if (useLevelColors && box.level >= 1 && box.level <= levelSides.length) {
                SettingColor s = levelSides[box.level - 1];
                SettingColor l = levelLines[box.level - 1];
                if (s != null) {
                    side = s;
                    line = l;
                }
            }

            shared.renderBox(event.renderer,
                box.minX, box.minY, box.minZ,
                box.maxX, box.maxY, box.maxZ,
                side, line);

            if (highlightSolid.get() && !box.solidEdgeCells.isEmpty()) {
                SettingColor c = solidEdgeColor.get();
                for (BlockPos pos : box.solidEdgeCells) {
                    shared.renderBox(event.renderer,
                        pos.getX(), pos.getY(), pos.getZ(),
                        pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1,
                        c, line);
                }
            }
        }
    }

    private List<BlockPos> computeSolidEdgeCells(RangeBox box) {
        // Solid-overlap cells along the box SURFACE (all six faces), mirroring SculkRange's
        // highlight-solid-voxels: a thin surface shell of solid voxels, not vertical pillars.
        //
        // This is O(surface area) - 60002 cells for a level-4 box (r=50), 9602 for a level-1 one
        // (r=20) - so the result is cached per beacon (see shellCache) instead of being rebuilt on
        // every throttled scan. The shell is walked in 16x16 tiles and a tile whose chunk section
        // holds no occluding block at all is skipped outright: such a section cannot contain a
        // cell that passes `!isAir() && isOpaque()`, so the collected set is identical. Section
        // access also serves the cell's own solidity read; only the neighbours (which leave the
        // box) still go through scanPos. Nothing is allocated per cell here; a collected cell
        // allocates its BlockPos.
        List<BlockPos> cells = new ArrayList<>();
        edgeCellKeys.clear();

        int ix1 = (int) Math.floor(box.minX);
        int ix2 = (int) Math.floor(box.maxX) - 1;
        int iy1 = (int) Math.floor(box.minY);
        int iy2 = (int) Math.floor(box.maxY) - 1;
        int iz1 = (int) Math.floor(box.minZ);
        int iz2 = (int) Math.floor(box.maxZ) - 1;

        int minY = mc.world.getBottomY();

        // The three face groups below visit disjoint cells by construction: top/bottom pin y to
        // the two extremes, front/back pin z to the extremes with y strictly interior, and
        // left/right pin x to the extremes with y and z both strictly interior. edgeCellKeys is
        // therefore a guard against a future loop edit (and against a degenerate zero-extent box,
        // which a beacon box can never be), not dedup these loops need.

        // Top/bottom faces (full squares, in the x/z tile grid).
        for (int pass = 0; pass < 2; pass++) {
            int y = pass == 0 ? iy1 : iy2;
            Direction outward = pass == 0 ? Direction.DOWN : Direction.UP;
            int sectionY = (y - minY) >> 4;
            int sectionBaseY = minY + (sectionY << 4);
            for (int xt = floor16(ix1); xt <= ix2; xt += 16) {
                for (int zt = floor16(iz1); zt <= iz2; zt += 16) {
                    ChunkSection section = sectionAt(xt >> 4, sectionY, zt >> 4);
                    if (section == null) continue;
                    int xEnd = Math.min(xt + 15, ix2);
                    int zEnd = Math.min(zt + 15, iz2);
                    for (int x = Math.max(xt, ix1); x <= xEnd; x++) {
                        for (int z = Math.max(zt, iz1); z <= zEnd; z++) {
                            checkSolidEdgeCell(cells, section, sectionBaseY, x, y, z, outward);
                        }
                    }
                }
            }
        }
        // Front/back faces (excluding the top/bottom rows already added, in the x/y tile grid).
        for (int pass = 0; pass < 2; pass++) {
            int z = pass == 0 ? iz1 : iz2;
            Direction outward = pass == 0 ? Direction.NORTH : Direction.SOUTH;
            for (int xt = floor16(ix1); xt <= ix2; xt += 16) {
                for (int yt = sectionBase(iy1 + 1, minY); yt <= iy2 - 1; yt += 16) {
                    int yLo = Math.max(yt, iy1 + 1);
                    int yHi = Math.min(yt + 15, iy2 - 1);
                    if (yLo > yHi) continue;
                    ChunkSection section = sectionAt(xt >> 4, (yLo - minY) >> 4, z >> 4);
                    if (section == null) continue;
                    int xEnd = Math.min(xt + 15, ix2);
                    for (int x = Math.max(xt, ix1); x <= xEnd; x++) {
                        for (int y = yLo; y <= yHi; y++) {
                            checkSolidEdgeCell(cells, section, yt, x, y, z, outward);
                        }
                    }
                }
            }
        }
        // Left/right faces (excluding top/bottom and front/back rows, in the y/z tile grid).
        for (int pass = 0; pass < 2; pass++) {
            int x = pass == 0 ? ix1 : ix2;
            Direction outward = pass == 0 ? Direction.WEST : Direction.EAST;
            for (int zt = floor16(iz1 + 1); zt <= iz2 - 1; zt += 16) {
                for (int yt = sectionBase(iy1 + 1, minY); yt <= iy2 - 1; yt += 16) {
                    int yLo = Math.max(yt, iy1 + 1);
                    int yHi = Math.min(yt + 15, iy2 - 1);
                    if (yLo > yHi) continue;
                    ChunkSection section = sectionAt(x >> 4, (yLo - minY) >> 4, zt >> 4);
                    if (section == null) continue;
                    int zEnd = Math.min(zt + 15, iz2 - 1);
                    for (int z = Math.max(zt, iz1 + 1); z <= zEnd; z++) {
                        for (int y = yLo; y <= yHi; y++) {
                            checkSolidEdgeCell(cells, section, yt, x, y, z, outward);
                        }
                    }
                }
            }
        }

        return cells;
    }

    /**
     * Collects one surface cell if the world block there is solid and has a see-through neighbour.
     *
     * @param section      the chunk section that tile was gated on, so the cell's own state comes
     *                     out of the section array rather than a fresh chunk lookup.
     * @param sectionBaseY the world y of the section's first layer (local y = world y - this).
     * @param outward      the face normal this cell sits on, i.e. the direction pointing out of the
     *                     box. It is tested first because it is the neighbour that decides exposure
     *                     for a cell on a face floating in open air, so the ordinary case costs one
     *                     lookup, not six.
     */
    private void checkSolidEdgeCell(List<BlockPos> out, ChunkSection section, int sectionBaseY,
                                    int x, int y, int z, Direction outward) {
        BlockState state = section.getBlockState(x & 15, y - sectionBaseY, z & 15);
        if (state.isAir() || !state.isOpaque()) return;
        if (!isExposed(x, y, z, outward)) return;
        if (!edgeCellKeys.add(BlockPos.asLong(x, y, z))) return;
        out.add(new BlockPos(x, y, z));
    }

    /**
     * The loaded chunk section covering a tile, or null when that tile provably holds no
     * collectable cell.
     * <p>
     * Null means "treat every cell in this tile as air". It covers: the chunk is not loaded (a box
     * near render distance can overhang one), the section index is outside the build range, the
     * section is absent, vanilla's own `hasOnlyAir` fast path, and finally `hasAny(OCCLUDING)` -
     * a section whose palette holds no occluding block cannot contain a cell passing
     * `!isAir() && isOpaque()`. `getChunkNow` never loads or requests a chunk.
     */
    private ChunkSection sectionAt(int chunkX, int sectionY, int chunkZ) {
        WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(chunkX, chunkZ);
        if (chunk == null) return null;
        ChunkSection[] sections = chunk.getSectionArray();
        if (sectionY < 0 || sectionY >= sections.length) return null;
        ChunkSection section = sections[sectionY];
        if (section == null || section.isEmpty()) return null;
        return section.hasAny(OCCLUDING) ? section : null;
    }

    /**
     * The lowest section-aligned world y at or below {@code v}.
     */
    private static int sectionBase(int v, int minY) {
        return minY + (((v - minY) >> 4) << 4);
    }

    /**
     * The lowest 16-aligned x/z at or below {@code v} (the chunk grid is absolute).
     */
    private static int floor16(int v) {
        return v & ~15;
    }

    private boolean isExposed(int x, int y, int z, Direction outward) {
        if (isSeeThrough(x + outward.getOffsetX(), y + outward.getOffsetY(), z + outward.getOffsetZ())) return true;
        for (Direction dir : Direction.values()) {
            if (dir == outward) continue;
            if (isSeeThrough(x + dir.getOffsetX(), y + dir.getOffsetY(), z + dir.getOffsetZ())) return true;
        }
        return false;
    }

    /**
     * The old inline `nb.isAir() || !nb.isOpaque()` test: a face you can see through.
     */
    private boolean isSeeThrough(int x, int y, int z) {
        BlockState nb = mc.world.getBlockState(shared.scanPos.set(x, y, z));
        return nb.isAir() || !nb.isOpaque();
    }

    public void clear() {
        boxes.clear();
        shellCache.clear();
        shellCacheWorld = null;
    }

    /**
     * Drops cached shells a block update at {@code pos} can change: the box itself, or the
     * one-block ring the exposure test reads.
     */
    public void invalidateAt(BlockPos pos) {
        if (shellCache.isEmpty()) return;
        shellCache.values().removeIf(entry -> entry.affectedBy(pos));
    }

    /**
     * Drops cached shells whose box intersects a chunk column that was just resent in full.
     */
    public void invalidateChunk(int chunkX, int chunkZ) {
        if (shellCache.isEmpty()) return;
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        shellCache.values().removeIf(entry -> entry.intersectsChunk(minX, minX + 15, minZ, minZ + 15));
    }

    private void updatePalette() {
        int alpha = beaconFillAlpha.get();
        SettingColor[] src = {
            beaconLevelColor1.get(),
            beaconLevelColor2.get(),
            beaconLevelColor3.get(),
            beaconLevelColor4.get()
        };
        for (int i = 0; i < src.length; i++) {
            SettingColor c = src[i];
            levelSides[i] = new SettingColor(c.r, c.g, c.b, alpha);
            levelLines[i] = new SettingColor(c.r, c.g, c.b, 255);
        }
    }

    private int getBeaconLevel(BlockPos beaconPos) {
        int level = 0;
        for (int y = 1; y <= 4; y++) {
            int layerY = beaconPos.getY() - y;
            if (layerY < mc.world.getBottomY()) break;

            boolean valid = true;
            outer:
            for (int x = -y; x <= y; x++) {
                for (int z = -y; z <= y; z++) {
                    shared.scanPos.set(beaconPos.getX() + x, layerY, beaconPos.getZ() + z);
                    if (!isValidBeaconBase(mc.world.getBlockState(shared.scanPos).getBlock())) {
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

    /**
     * Matches vanilla: a beacon is deactivated (no beam, no effects) when its
     * beam intersects any fully opaque block above it; bedrock is the one
     * exception so beacons work below the Nether ceiling.
     */
    private boolean isBeaconBeamBlocked(BlockPos beaconPos) {
        int x = beaconPos.getX();
        int z = beaconPos.getZ();
        int topY = mc.world.getBottomY() + mc.world.getHeight();
        for (int y = beaconPos.getY() + 1; y < topY; y++) {
            shared.scanPos.set(x, y, z);
            BlockState state = mc.world.getBlockState(shared.scanPos);
            if (state.getBlock() != Blocks.BEDROCK && state.getOpacity() >= 15) {
                return true;
            }
        }
        return false;
    }
}
