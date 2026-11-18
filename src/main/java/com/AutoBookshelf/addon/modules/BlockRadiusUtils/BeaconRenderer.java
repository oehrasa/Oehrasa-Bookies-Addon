/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.BlockRadiusUtils;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.Dir;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.function.Predicate;

import static com.AutoBookshelf.addon.modules.BlockRadiusUtils.BlockRadiusShared.RangeBox;
import static com.AutoBookshelf.addon.modules.BlockRadiusUtils.BlockRadiusShared.ShapeMode;
import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Beacon range visualisation: one box per powered beacon, sized by pyramid level and coloured
 * per level (or with a flat colour when level colours are off).
 */
public class BeaconRenderer {
    /**
     * How the solid-overlap shell is emitted: merged flat regions, or one box per block.
     */
    public enum SolidVoxelStyle {Merged, Voxels}

    /**
     * Face-axis tags for {@link #faceBoxes}: which coordinate of the face is pinned.
     */
    private static final int AXIS_X = 0;
    private static final int AXIS_Y = 1;
    private static final int AXIS_Z = 2;

    private final BlockRadiusShared shared;
    private final Setting<ShapeMode> shapeMode;

    private final Setting<Boolean> showBeacons;
    private final Setting<Boolean> cullOverlapping;
    private final Setting<Boolean> excludeInactiveBeacons;
    private final Setting<Boolean> mergeOverlappingBeacons;

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
    private final Setting<SolidVoxelStyle> solidVoxelStyle;

    // Cached SettingColors built from the per-level colour settings; rebuilt whenever a
    // level colour, the fill alpha, or the toggle changes, or on each throttled beacon scan,
    // so onRender allocates nothing per frame. Index i corresponds to pyramid level i+1.
    private final SettingColor[] levelSides = new SettingColor[4];
    private final SettingColor[] levelLines = new SettingColor[4];

    private final List<RangeBox> boxes = new ArrayList<>();

    /**
     * The union of overlapping boxes, rebuilt by {@link #scan()} when {@code
     * merge-overlapping-beacons} is on. Disjoint boxes are carried over unchanged, so a scan that
     * finds no overlap leaves this list holding the same objects {@link #boxes} does.
     */
    private final List<RangeBox> mergedBoxes = new ArrayList<>();

    /**
     * True when {@link #render} should draw {@link #mergedBoxes} instead of {@link #boxes}.
     */
    private boolean useMerged = false;

    /**
     * Scratch list of the boxes the merge runs over, i.e. the ones the cull left visible.
     */
    private final List<RangeBox> mergeSources = new ArrayList<>();

    /**
     * Boundary of the merged union, flattened as seven ints per quad, {@code (axis, plane, aStart,
     * bStart, aEnd, bEnd, level)}; one list shared by every cluster this scan. Drawn instead of
     * {@code boxSides} when the merge is active.
     */
    private final List<int[]> mergedQuads = new ArrayList<>();

    /**
     * True when a cluster this scan exceeded the compression cap and the merge fell back.
     */
    private boolean mergeTooLarge;

    /**
     * Surface shells cached per beacon, keyed by packed beacon position, reused until a block
     * update inside the box (or its one-block ring, the exposure test reads the neighbours just
     * outside it) invalidates them. The shell depends only on world blocks, so recomputing it on
     * every throttled scan was pure waste: 60002 cells for a level-4 box, several times a minute,
     * while nothing moved. `ChunkDataEvent` drops a resent chunk's entries (a full resend raises
     * no BlockUpdateEvent) and a level change clears the cache.
     */
    private final Map<Long, ShellEntry> shellCache = new HashMap<>();
    private ClientLevel shellCacheWorld = null;

    /**
     * Beacons seen by the current scan, used to prune shells of beacons that are gone or unloaded.
     */
    private final LongOpenHashSet seenBeacons = new LongOpenHashSet();

    /**
     * One beacon's cached shell. The beacon position, level and drawing style are kept so the entry
     * can be invalidated by a block update or a chunk resend without re-deriving the box, and so a
     * style change rebuilds the geometry instead of rendering a payload the other style stored.
     */
    private static final class ShellEntry {
        private final int x, y, z, level;
        private final SolidVoxelStyle style;
        private final List<BlockPos> cells;
        private final int[] rects;

        private ShellEntry(BlockPos beaconPos, int level, SolidVoxelStyle style,
                           List<BlockPos> cells, int[] rects) {
            this.x = beaconPos.getX();
            this.y = beaconPos.getY();
            this.z = beaconPos.getZ();
            this.level = level;
            this.style = style;
            this.cells = cells;
            this.rects = rects;
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
     * Vanilla's own `!isAir() && canOcclude()`; the section palette test in {@link #sectionAt}.
     */
    private static final Predicate<BlockState> OCCLUDING = state -> !state.isAir() && state.canOcclude();

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

        mergeOverlappingBeacons = sgGeneral.add(new BoolSetting.Builder()
            .name("merge-overlapping-beacons")
            .description("Draws overlapping beacon ranges as one shape, so the fill does not get darker where they overlap.")
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

        solidVoxelStyle = sgRender.add(new EnumSetting.Builder<SolidVoxelStyle>()
            .name("beacon-solid-voxel-style")
            .description("Merged fills each flat region in one draw and outlines only its border; Voxels keeps a box per block.")
            .defaultValue(SolidVoxelStyle.Merged)
            .visible(() -> highlightSolid.get() && showBeacons.get())
            .build()
        );
    }

    public void scan() {
        boxes.clear();
        mergedBoxes.clear();
        mergedQuads.clear();
        mergeSources.clear();
        mergeTooLarge = false;
        useMerged = false;
        if (!showBeacons.get()) return;

        if (beaconLevelColors.get()) updatePalette();

        // A level change invalidates every cached shell (their meaning depends on the level).
        ClientLevel world = mc.level;
        if (world != shellCacheWorld) {
            shellCache.clear();
            shellCacheWorld = world;
        }
        boolean highlight = highlightSolid.get();
        SolidVoxelStyle style = solidVoxelStyle.get();
        seenBeacons.clear();

        for (BlockEntity be : Utils.blockEntities()) {
            if (!(be instanceof BeaconBlockEntity)) continue;

            BlockPos pos = be.getBlockPos();
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
                if (entry == null || entry.level != level || entry.style != style) {
                    entry = computeShell(pos, level, box, style);
                    shellCache.put(key, entry);
                }
                // Shared by reference: the entry is immutable until it is invalidated, and a new
                // scan drops every box, so nothing outlives the list. Copying 60k references per
                // beacon per scan (the old addAll) was pure waste on a cached shell.
                box.solidEdgeCells = entry.cells;
                box.solidEdgeRects = entry.rects;
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

        rebuildMerged();
    }

    /**
     * Rebuilds {@link #mergedBoxes} from the boxes the cull left visible: each cluster of boxes
     * whose volumes overlap becomes a disjoint set of boxes covering the cluster's union exactly
     * once, tagged with the highest level covering each part and with the faces that are interior
     * to the union. A box that overlaps nothing is carried over unchanged, so a scan that finds no
     * overlap draws exactly what it drew before.
     */
    private void rebuildMerged() {
        mergedBoxes.clear();
        mergedQuads.clear();
        mergeTooLarge = false;
        if (!mergeOverlappingBeacons.get()) {
            useMerged = false;
            return;
        }

        mergeSources.clear();
        for (RangeBox box : boxes) {
            if (box.render) mergeSources.add(box);
        }
        int n = mergeSources.size();
        if (n < 2) {
            useMerged = false;
            return;
        }

        int[] bounds = new int[n * 6];
        int[] levels = new int[n];
        for (int i = 0; i < n; i++) {
            RangeBox box = mergeSources.get(i);
            int o = i * 6;
            bounds[o] = (int) box.minX;
            bounds[o + 1] = (int) box.minY;
            bounds[o + 2] = (int) box.minZ;
            bounds[o + 3] = (int) box.maxX;
            bounds[o + 4] = (int) box.maxY;
            bounds[o + 5] = (int) box.maxZ;
            levels[i] = Math.max(1, box.level);
        }

        int[] component = clusterIds(bounds, n);
        int components = 0;
        for (int id : component) components = Math.max(components, id + 1);

        for (int id = 0; id < components; id++) {
            int members = 0;
            for (int i = 0; i < n; i++) {
                if (component[i] == id) members++;
            }

            int[] memberBounds = new int[members * 6];
            int[] memberLevels = new int[members];
            int w = 0;
            for (int i = 0; i < n; i++) {
                if (component[i] != id) continue;
                System.arraycopy(bounds, i * 6, memberBounds, w * 6, 6);
                memberLevels[w++] = levels[i];
            }
            appendMerged(memberBounds, memberLevels, members);
            if (mergeTooLarge) {
                // All-or-nothing: a pathological pile of beacons falls back to the unmerged draw.
                mergedBoxes.clear();
                mergedQuads.clear();
                useMerged = false;
                return;
            }
        }
        useMerged = true;
    }

    /**
     * Groups boxes into connected components under strict volume overlap. Returns a component id
     * per box, numbered from 0 in order of first appearance.
     * <p>
     * Strict overlap, so two boxes that merely share a boundary plane stay separate: they never
     * blended into each other, and merging them would turn their shared plane into an interior one
     * and drop a visible face.
     */
    static int[] clusterIds(int[] bounds, int count) {
        int[] component = new int[count];
        Arrays.fill(component, -1);
        int next = 0;
        for (int i = 0; i < count; i++) {
            if (component[i] >= 0) continue;
            component[i] = next;
            boolean grew = true;
            while (grew) {
                grew = false;
                for (int j = 0; j < count; j++) {
                    if (component[j] >= 0) continue;
                    for (int k = 0; k < count; k++) {
                        if (component[k] != next) continue;
                        if (boxOverlaps(bounds, k, j)) {
                            component[j] = next;
                            grew = true;
                            break;
                        }
                    }
                }
            }
            next++;
        }
        return component;
    }

    /**
     * True when the two boxes of {@code bounds} share volume, not just a boundary plane.
     */
    static boolean boxOverlaps(int[] bounds, int a, int b) {
        int ao = a * 6;
        int bo = b * 6;
        return bounds[ao] < bounds[bo + 3] && bounds[bo] < bounds[ao + 3]
            && bounds[ao + 1] < bounds[bo + 4] && bounds[bo + 1] < bounds[ao + 4]
            && bounds[ao + 2] < bounds[bo + 5] && bounds[bo + 2] < bounds[ao + 5];
    }

    /**
     * Unions one overlapping cluster: exact-cover boxes (still used by the lines pass) plus the
     * boundary quads the sides pass draws. A cluster too large to compress (a grid cap only a
     * pathological pile of beacons can reach) signals {@link #mergeTooLarge} so every cluster this
     * scan falls back to drawing unmerged instead of merging partially.
     */
    private void appendMerged(int[] bounds, int[] levels, int count) {
        UnionGrid grid = buildUnionGrid(bounds, levels, count);
        if (grid == null) {
            mergeTooLarge = true;
            return;
        }

        int[] merged = mergeBoxes(grid);
        for (int i = 0; i < merged.length; i += 8) {
            RangeBox box = new RangeBox(merged[i], merged[i + 1], merged[i + 2],
                merged[i + 3], merged[i + 4], merged[i + 5]);
            box.level = merged[i + 6];
            box.excludeDir = toExcludeDir(merged[i + 7]);
            mergedBoxes.add(box);
        }

        int[] quads = boundaryQuads(grid);
        for (int i = 0; i < quads.length; i += 7) {
            mergedQuads.add(new int[]{quads[i], quads[i + 1], quads[i + 2], quads[i + 3],
                quads[i + 4], quads[i + 5], quads[i + 6]});
        }
    }

    public void render(Render3DEvent event, Vec3 cam, double maxDistSq) {
        if (!showBeacons.get()) return;

        // Resolved once per frame rather than once per box: the shell styles can emit thousands of
        // boxes, and every other feature keeps using the 8-argument overload unchanged.
        ShapeMode mode = shapeMode.get();
        boolean useLevelColors = beaconLevelColors.get();
        SettingColor flatSide = beaconSideColor.get();
        SettingColor flatLine = beaconLineColor.get();

        if (useMerged) {
            // Sides: exact boundary quads, one pass per unit face cell. Lines: per-box outlines
            // with the interior faces suppressed. Nothing of the union is drawn when every
            // merged box is out of range, same as the unmerged path.
            boolean anyInRange = false;
            for (RangeBox box : mergedBoxes) {
                if (box.distanceSq(cam) <= maxDistSq) {
                    anyInRange = true;
                    break;
                }
            }
            if (anyInRange) {
                if (mode.sides()) {
                    for (int[] q : mergedQuads) {
                        SettingColor col = quadColor(q[6], useLevelColors, flatSide);
                        int plane = q[1], aS = q[2], bS = q[3], aE = q[4], bE = q[5];
                        switch (q[0]) {
                            case 0 -> event.renderer.quad(plane, aS, bS, plane, aS, bE,
                                plane, aE, bE, plane, aE, bS, col);
                            case 1 -> event.renderer.quad(aS, plane, bS, aE, plane, bS,
                                aE, plane, bE, aS, plane, bE, col);
                            default -> event.renderer.quad(aS, bS, plane, aE, bS, plane,
                                aE, bE, plane, aS, bE, plane, col);
                        }
                    }
                }
                if (mode.lines()) {
                    for (RangeBox box : mergedBoxes) {
                        if (box.distanceSq(cam) > maxDistSq) continue;
                        int palette = paletteIndex(box, useLevelColors);
                        event.renderer.boxLines(box.minX, box.minY, box.minZ,
                            box.maxX, box.maxY, box.maxZ,
                            palette < 0 ? flatLine : levelLines[palette], box.excludeDir);
                    }
                }
            }
        } else {
            for (RangeBox box : boxes) {
                if (!box.render) continue;
                if (box.distanceSq(cam) > maxDistSq) continue;
                int palette = paletteIndex(box, useLevelColors);
                shared.renderBox(event.renderer,
                    box.minX, box.minY, box.minZ,
                    box.maxX, box.maxY, box.maxZ,
                    palette < 0 ? flatSide : levelSides[palette],
                    palette < 0 ? flatLine : levelLines[palette],
                    mode);
            }
        }

        if (!highlightSolid.get()) return;

        SettingColor solidColor = solidEdgeColor.get();
        for (RangeBox box : boxes) {
            if (!box.render) continue;
            if (box.distanceSq(cam) > maxDistSq) continue;

            int palette = paletteIndex(box, useLevelColors);
            SettingColor line = palette < 0 ? flatLine : levelLines[palette];

            int[] rects = box.solidEdgeRects;
            if (rects.length != 0) {
                for (int i = 0; i < rects.length; i += 6) {
                    shared.renderBox(event.renderer,
                        rects[i], rects[i + 1], rects[i + 2],
                        rects[i + 3], rects[i + 4], rects[i + 5],
                        solidColor, line, mode);
                }
            } else {
                for (BlockPos pos : box.solidEdgeCells) {
                    int x = pos.getX();
                    int y = pos.getY();
                    int z = pos.getZ();
                    shared.renderBox(event.renderer, x, y, z, x + 1, y + 1, z + 1,
                        solidColor, line, mode);
                }
            }
        }
    }

    /**
     * Fill colour for a boundary quad: its level's palette entry, or the flat side colour.
     */
    private SettingColor quadColor(int level, boolean useLevelColors, SettingColor flatSide) {
        int i = level - 1;
        if (useLevelColors && i >= 0 && i < levelSides.length && levelSides[i] != null) {
            return levelSides[i];
        }
        return flatSide;
    }

    /**
     * The palette entry for one box, or -1 when the flat colours apply: level colours are off, the
     * level is out of range, or the palette has not been built yet.
     */
    private int paletteIndex(RangeBox box, boolean useLevelColors) {
        if (!useLevelColors) return -1;
        int i = box.level - 1;
        if (i < 0 || i >= levelSides.length || levelSides[i] == null) return -1;
        return i;
    }

    private ShellEntry computeShell(BlockPos beaconPos, int level, RangeBox box, SolidVoxelStyle style) {
        // Solid-overlap cells along the box SURFACE (all six faces), mirroring SculkRange's
        // highlight-solid-voxels: a thin surface shell of solid voxels, not vertical pillars.
        //
        // This is O(surface area) - 60002 cells for a level-4 box (r=50), 9602 for a level-1 one
        // (r=20) - so the result is cached per beacon (see shellCache) instead of being rebuilt on
        // every throttled scan. The shell is walked in 16x16 tiles and a tile whose chunk section
        // holds no occluding block at all is skipped outright: such a section cannot contain a
        // cell that passes `!isAir() && canOcclude()`, so the collected set is identical. Section
        // access also serves the cell's own solidity read; only the neighbours (which leave the
        // box) still go through scanPos.
        //
        // Each face writes into its own 2D grid, indexed by the cell's position within the face.
        // The three face groups visit disjoint cells by construction (top/bottom pin y to the two
        // extremes, front/back pin z with y strictly interior, left/right pin x with y and z both
        // strictly interior), so the grids cannot collide and the old cross-face dedupe set is
        // gone. Voxels reads the grids cell by cell; Merged decomposes each grid into rectangles.
        boolean merged = style == SolidVoxelStyle.Merged;

        int ix1 = (int) Math.floor(box.minX);
        int ix2 = (int) Math.floor(box.maxX) - 1;
        int iy1 = (int) Math.floor(box.minY);
        int iy2 = (int) Math.floor(box.maxY) - 1;
        int iz1 = (int) Math.floor(box.minZ);
        int iz2 = (int) Math.floor(box.maxZ) - 1;

        int wX = ix2 - ix1 + 1;
        int wY = iy2 - iy1 + 1;
        int wZ = iz2 - iz1 + 1;

        int minY = mc.level.getMinY();

        List<BlockPos> cells = merged ? List.of() : new ArrayList<>();
        int[][] faceRects = merged ? new int[6][] : null;

        // Top/bottom faces (full squares, in the x/z tile grid).
        for (int pass = 0; pass < 2; pass++) {
            int y = pass == 0 ? iy1 : iy2;
            Direction outward = pass == 0 ? Direction.DOWN : Direction.UP;
            long[] bits = merged ? new long[bitWords(wX * wZ)] : null;
            int sectionY = (y - minY) >> 4;
            int sectionBaseY = minY + (sectionY << 4);
            for (int xt = floor16(ix1); xt <= ix2; xt += 16) {
                for (int zt = floor16(iz1); zt <= iz2; zt += 16) {
                    LevelChunkSection section = sectionAt(xt >> 4, sectionY, zt >> 4);
                    if (section == null) continue;
                    int xEnd = Math.min(xt + 15, ix2);
                    int zEnd = Math.min(zt + 15, iz2);
                    for (int x = Math.max(xt, ix1); x <= xEnd; x++) {
                        for (int z = Math.max(zt, iz1); z <= zEnd; z++) {
                            if (!isSolidEdgeCell(section, sectionBaseY, x, y, z, outward)) continue;
                            if (merged) setBit(bits, (z - iz1) * wX + (x - ix1));
                            else cells.add(new BlockPos(x, y, z));
                        }
                    }
                }
            }
            if (merged) faceRects[pass] = faceBoxes(mergeFace(bits, wX, wZ), ix1, iz1, AXIS_Y, y);
        }
        // Front/back faces (excluding the top/bottom rows already added, in the x/y tile grid).
        for (int pass = 0; pass < 2; pass++) {
            int z = pass == 0 ? iz1 : iz2;
            Direction outward = pass == 0 ? Direction.NORTH : Direction.SOUTH;
            long[] bits = merged ? new long[bitWords(wX * wY)] : null;
            for (int xt = floor16(ix1); xt <= ix2; xt += 16) {
                for (int yt = sectionBase(iy1 + 1, minY); yt <= iy2 - 1; yt += 16) {
                    int yLo = Math.max(yt, iy1 + 1);
                    int yHi = Math.min(yt + 15, iy2 - 1);
                    if (yLo > yHi) continue;
                    LevelChunkSection section = sectionAt(xt >> 4, (yLo - minY) >> 4, z >> 4);
                    if (section == null) continue;
                    int xEnd = Math.min(xt + 15, ix2);
                    for (int x = Math.max(xt, ix1); x <= xEnd; x++) {
                        for (int y = yLo; y <= yHi; y++) {
                            if (!isSolidEdgeCell(section, yt, x, y, z, outward)) continue;
                            if (merged) setBit(bits, (y - iy1) * wX + (x - ix1));
                            else cells.add(new BlockPos(x, y, z));
                        }
                    }
                }
            }
            if (merged) faceRects[2 + pass] = faceBoxes(mergeFace(bits, wX, wY), ix1, iy1, AXIS_Z, z);
        }
        // Left/right faces (excluding top/bottom and front/back rows, in the y/z tile grid).
        for (int pass = 0; pass < 2; pass++) {
            int x = pass == 0 ? ix1 : ix2;
            Direction outward = pass == 0 ? Direction.WEST : Direction.EAST;
            long[] bits = merged ? new long[bitWords(wZ * wY)] : null;
            for (int zt = floor16(iz1 + 1); zt <= iz2 - 1; zt += 16) {
                for (int yt = sectionBase(iy1 + 1, minY); yt <= iy2 - 1; yt += 16) {
                    int yLo = Math.max(yt, iy1 + 1);
                    int yHi = Math.min(yt + 15, iy2 - 1);
                    if (yLo > yHi) continue;
                    LevelChunkSection section = sectionAt(x >> 4, (yLo - minY) >> 4, zt >> 4);
                    if (section == null) continue;
                    int zEnd = Math.min(zt + 15, iz2 - 1);
                    for (int z = Math.max(zt, iz1 + 1); z <= zEnd; z++) {
                        for (int y = yLo; y <= yHi; y++) {
                            if (!isSolidEdgeCell(section, yt, x, y, z, outward)) continue;
                            if (merged) setBit(bits, (y - iy1) * wZ + (z - iz1));
                            else cells.add(new BlockPos(x, y, z));
                        }
                    }
                }
            }
            if (merged) faceRects[4 + pass] = faceBoxes(mergeFace(bits, wZ, wY), iz1, iy1, AXIS_X, x);
        }

        int[] rects = merged ? concat(faceRects) : new int[0];
        return new ShellEntry(beaconPos, level, style, cells, rects);
    }

    /**
     * True when the world block at this surface cell is solid and has a see-through neighbour.
     *
     * @param section      the chunk section that tile was gated on, so the cell's own state comes
     *                     out of the section array rather than a fresh chunk lookup.
     * @param sectionBaseY the world y of the section's first layer (local y = world y - this).
     * @param outward      the face normal this cell sits on, i.e. the direction pointing out of the
     *                     box. It is tested first because it is the neighbour that decides exposure
     *                     for a cell on a face floating in open air, so the ordinary case costs one
     *                     lookup, not six.
     */
    private boolean isSolidEdgeCell(LevelChunkSection section, int sectionBaseY,
                                    int x, int y, int z, Direction outward) {
        BlockState state = section.getBlockState(x & 15, y - sectionBaseY, z & 15);
        if (state.isAir() || !state.canOcclude()) return false;
        return isExposed(x, y, z, outward);
    }

    private static int bitWords(int bits) {
        return (bits + 63) >> 6;
    }

    private static void setBit(long[] words, int i) {
        words[i >> 6] |= 1L << (i & 63);
    }

    private static boolean getBit(long[] words, int i) {
        return (words[i >> 6] & (1L << (i & 63))) != 0;
    }

    private static void clearBit(long[] words, int i) {
        words[i >> 6] &= ~(1L << (i & 63));
    }

    /**
     * Greedy per-face rectangle decomposition of a width x height cell grid, returning groups of
     * four ints {@code (u, v, w, h)}. Cells are consumed as they are claimed, so every set cell
     * ends up in exactly one rectangle and no rectangle covers an unset cell.
     * <p>
     * Worst case (a checkerboard) is one rectangle per cell, i.e. no worse than drawing the grid
     * cell by cell; the ordinary case of a solid or lightly-broken surface is one rectangle per
     * face plus one per hole. Pure: no world access, so it can be spliced out and exercised
     * headlessly.
     */
    static int[] mergeFace(long[] bits, int width, int height) {
        int[] quads = new int[4 * 16];
        int count = 0;

        for (int v = 0; v < height; v++) {
            int row = v * width;
            for (int u = 0; u < width; u++) {
                if (!getBit(bits, row + u)) continue;

                int w = 1;
                while (u + w < width && getBit(bits, row + u + w)) w++;

                int h = 1;
                grow:
                while (v + h < height) {
                    int r = (v + h) * width;
                    for (int i = 0; i < w; i++) {
                        if (!getBit(bits, r + u + i)) break grow;
                    }
                    h++;
                }

                for (int j = 0; j < h; j++) {
                    int r = (v + j) * width;
                    for (int i = 0; i < w; i++) clearBit(bits, r + u + i);
                }

                if (count + 4 > quads.length) quads = Arrays.copyOf(quads, quads.length * 2);
                quads[count] = u;
                quads[count + 1] = v;
                quads[count + 2] = w;
                quads[count + 3] = h;
                count += 4;
            }
        }

        return Arrays.copyOf(quads, count);
    }

    /**
     * Converts {@link #mergeFace} quads on one face into flat box bounds, six ints each
     * {@code (x1, y1, z1, x2, y2, z2)} spanning the cells the rectangle covers (so a cell at x
     * spans x..x+1, exactly like the per-cell box it replaces).
     *
     * @param axis  which coordinate of the face is pinned: {@code AXIS_X}, {@code AXIS_Y} or
     *              {@code AXIS_Z}. The remaining two are (u, v) in that order.
     * @param plane the pinned world coordinate.
     */
    static int[] faceBoxes(int[] quads, int uOrigin, int vOrigin, int axis, int plane) {
        int[] boxes = new int[(quads.length / 4) * 6];

        for (int i = 0, o = 0; i < quads.length; i += 4, o += 6) {
            int u0 = uOrigin + quads[i];
            int v0 = vOrigin + quads[i + 1];
            int u1 = u0 + quads[i + 2];
            int v1 = v0 + quads[i + 3];

            if (axis == AXIS_X) {
                boxes[o] = plane;
                boxes[o + 1] = v0;
                boxes[o + 2] = u0;
                boxes[o + 3] = plane + 1;
                boxes[o + 4] = v1;
                boxes[o + 5] = u1;
            } else if (axis == AXIS_Y) {
                boxes[o] = u0;
                boxes[o + 1] = plane;
                boxes[o + 2] = v0;
                boxes[o + 3] = u1;
                boxes[o + 4] = plane + 1;
                boxes[o + 5] = v1;
            } else {
                boxes[o] = u0;
                boxes[o + 1] = v0;
                boxes[o + 2] = plane;
                boxes[o + 3] = u1;
                boxes[o + 4] = v1;
                boxes[o + 5] = plane + 1;
            }
        }

        return boxes;
    }

    private static int[] concat(int[][] arrays) {
        int total = 0;
        for (int[] a : arrays) total += a.length;
        int[] out = new int[total];
        int offset = 0;
        for (int[] a : arrays) {
            System.arraycopy(a, 0, out, offset, a.length);
            offset += a.length;
        }
        return out;
    }

    /**
     * Faces of a merged box that are interior to the union, in the bit order this class emits.
     * Meteor's {@code Dir} bits are a different set of values; {@link #toExcludeDir} maps them.
     */
    static final int MERGE_FACE_WEST = 1;
    static final int MERGE_FACE_EAST = 1 << 1;
    static final int MERGE_FACE_DOWN = 1 << 2;
    static final int MERGE_FACE_UP = 1 << 3;
    static final int MERGE_FACE_NORTH = 1 << 4;
    static final int MERGE_FACE_SOUTH = 1 << 5;

    /**
     * Compressed-cell budget for one cluster. The grid is at most {@code (2m-1)^3} cells for m
     * boxes, so this caps a cluster at about fifty mutually overlapping beacons.
     */
    private static final long MAX_MERGE_CELLS = 1_000_000L;

    /**
     * Compressed union of the source boxes for one cluster. The level grid is zero outside the
     * union, so every consumer (box emission, boundary quads) agrees on coverage and on the
     * highest level covering each cell.
     */
    static final class UnionGrid {
        int[] level;
        int[] xs, ys, zs;
        int nx, ny, nz;
    }

    /**
     * Compresses the boxes' breakpoints into a cell grid and fills it with the highest level
     * covering each cell. Returns null when the compressed grid would exceed
     * {@link #MAX_MERGE_CELLS}. Pure; shared by {@link #mergeBoxes} and {@link #boundaryQuads}.
     */
    static UnionGrid buildUnionGrid(int[] bounds, int[] levels, int count) {
        UnionGrid g = new UnionGrid();
        if (count <= 0) {
            g.level = new int[0];
            g.xs = g.ys = g.zs = new int[0];
            g.nx = g.ny = g.nz = 0;
            return g;
        }

        int[] xs = axisCoords(bounds, 0, 3, count);
        int[] ys = axisCoords(bounds, 1, 4, count);
        int[] zs = axisCoords(bounds, 2, 5, count);
        int nx = xs.length - 1;
        int ny = ys.length - 1;
        int nz = zs.length - 1;
        if (nx < 1 || ny < 1 || nz < 1) {
            g.level = new int[0];
            g.xs = g.ys = g.zs = new int[0];
            g.nx = g.ny = g.nz = 0;
            return g;
        }
        if ((long) nx * ny * nz > MAX_MERGE_CELLS) return null;

        int[] level = new int[nx * ny * nz];
        for (int b = 0; b < count; b++) {
            int o = b * 6;
            int i0 = indexOf(xs, bounds[o]);
            int j0 = indexOf(ys, bounds[o + 1]);
            int k0 = indexOf(zs, bounds[o + 2]);
            int i1 = indexOf(xs, bounds[o + 3]);
            int j1 = indexOf(ys, bounds[o + 4]);
            int k1 = indexOf(zs, bounds[o + 5]);
            int boxLevel = levels[b];
            for (int k = k0; k < k1; k++) {
                for (int j = j0; j < j1; j++) {
                    int row = (k * ny + j) * nx;
                    for (int i = i0; i < i1; i++) {
                        if (boxLevel > level[row + i]) level[row + i] = boxLevel;
                    }
                }
            }
        }

        g.xs = xs;
        g.ys = ys;
        g.zs = zs;
        g.nx = nx;
        g.ny = ny;
        g.nz = nz;
        g.level = level;
        return g;
    }

    /**
     * Unions axis-aligned boxes into disjoint boxes that cover each covered point exactly once.
     * <p>
     * Each emitted box carries the highest level covering it, plus the faces that are interior to
     * the union so a renderer can skip them: drawing them would blend the fill twice, which is the
     * whole point of the merge. Coordinates are compressed on all three axes first, so the work
     * depends on the number of boxes rather than on their size.
     * <p>
     * Pure. No world access and no Meteor types, so it can be spliced out and exercised headlessly
     * (see the beacon merge harness note in AGENTS.md).
     *
     * @param bounds six ints per box, {@code (x1, y1, z1, x2, y2, z2)} with {@code x1 < x2} etc.
     * @param levels one level per box, at least 1
     * @param count  number of boxes
     * @return eight ints per emitted box, {@code (x1, y1, z1, x2, y2, z2, level, interiorFaces)},
     * or null when the compressed grid would exceed {@link #MAX_MERGE_CELLS}
     */
    static int[] mergeBoxes(int[] bounds, int[] levels, int count) {
        UnionGrid g = buildUnionGrid(bounds, levels, count);
        if (g == null) return null;
        return mergeBoxes(g);
    }

    static int[] mergeBoxes(UnionGrid g) {
        int[] level = g.level;
        int nx = g.nx, ny = g.ny, nz = g.nz;
        if (level.length == 0) return new int[0];
        int[] xs = g.xs, ys = g.ys, zs = g.zs;

        boolean[] used = new boolean[level.length];
        int[] out = new int[8 * 16];
        int outCount = 0;

        for (int k = 0; k < nz; k++) {
            for (int j = 0; j < ny; j++) {
                for (int i = 0; i < nx; i++) {
                    int idx = (k * ny + j) * nx + i;
                    int boxLevel = level[idx];
                    if (boxLevel == 0 || used[idx]) continue;

                    // Grow a maximal box of equal level over cells nobody has claimed yet: +x,
                    // then +z over the full width, then +y over the full depth. Claiming as it
                    // goes makes the output an exact cover, so no cell is drawn twice.
                    int w = 1;
                    while (i + w < nx && level[idx + w] == boxLevel && !used[idx + w]) w++;

                    int d = 1;
                    growZ:
                    while (k + d < nz) {
                        int row = ((k + d) * ny + j) * nx + i;
                        for (int a = 0; a < w; a++) {
                            if (level[row + a] != boxLevel || used[row + a]) break growZ;
                        }
                        d++;
                    }

                    int h = 1;
                    growY:
                    while (j + h < ny) {
                        for (int c = 0; c < d; c++) {
                            int row = ((k + c) * ny + (j + h)) * nx + i;
                            for (int a = 0; a < w; a++) {
                                if (level[row + a] != boxLevel || used[row + a]) break growY;
                            }
                        }
                        h++;
                    }

                    for (int c = 0; c < d; c++) {
                        for (int b = 0; b < h; b++) {
                            int row = ((k + c) * ny + (j + b)) * nx;
                            for (int a = 0; a < w; a++) used[row + i + a] = true;
                        }
                    }

                    int faces = 0;
                    if (i > 0 && covered(level, nx, ny, i - 1, i, j, j + h, k, k + d)) faces |= MERGE_FACE_WEST;
                    if (i + w < nx && covered(level, nx, ny, i + w, i + w + 1, j, j + h, k, k + d)) faces |= MERGE_FACE_EAST;
                    if (j > 0 && covered(level, nx, ny, i, i + w, j - 1, j, k, k + d)) faces |= MERGE_FACE_DOWN;
                    if (j + h < ny && covered(level, nx, ny, i, i + w, j + h, j + h + 1, k, k + d)) faces |= MERGE_FACE_UP;
                    if (k > 0 && covered(level, nx, ny, i, i + w, j, j + h, k - 1, k)) faces |= MERGE_FACE_NORTH;
                    if (k + d < nz && covered(level, nx, ny, i, i + w, j, j + h, k + d, k + d + 1)) faces |= MERGE_FACE_SOUTH;

                    if (outCount + 8 > out.length) out = Arrays.copyOf(out, out.length * 2);

                    out[outCount] = xs[i];
                    out[outCount + 1] = ys[j];
                    out[outCount + 2] = zs[k];
                    out[outCount + 3] = xs[i + w];
                    out[outCount + 4] = ys[j + h];
                    out[outCount + 5] = zs[k + d];
                    out[outCount + 6] = boxLevel;
                    out[outCount + 7] = faces;
                    outCount += 8;
                }
            }
        }

        return Arrays.copyOf(out, outCount);
    }

    /**
     * The union's boundary as maximal coplanar quads, drawn exactly once per unit face cell.
     * <p>
     * A quad is emitted for every pair of neighbouring cells where one covers and the other does
     * not (the union's outer surface), or where both are covered at different levels (the higher
     * volume's edge reads as a seam). Equal-level cells of one volume produce nothing, and no
     * uncovered cell pair produces anything, so no alpha blend inside the union exists and no
     * alpha blend appears anywhere outside it.
     * <p>
     * Pure. No world access and no Meteor types.
     *
     * @param bounds six ints per box, {@code (x1, y1, z1, x2, y2, z2)} with {@code x1 < x2} etc.
     * @param levels one level per box, at least 1
     * @param count  number of boxes
     * @return seven ints per quad, {@code (axis, plane, aStart, bStart, aEnd, bEnd, level)} with
     * {@code axis} 0 = X plane spanning y×z, 1 = Y spanning x×z, 2 = Z spanning x×y and
     * the span ranges stored with the lower breakpoint first. Or null when the grid would
     * exceed {@link #MAX_MERGE_CELLS}
     */
    static int[] boundaryQuads(int[] bounds, int[] levels, int count) {
        UnionGrid g = buildUnionGrid(bounds, levels, count);
        return g == null ? null : boundaryQuads(g);
    }

    static int[] boundaryQuads(UnionGrid g) {
        int[] level = g.level;
        int nx = g.nx, ny = g.ny, nz = g.nz;
        int[] xs = g.xs, ys = g.ys, zs = g.zs;

        int[] out = new int[8 * 16];
        int outCount = 0;
        if (level.length == 0) return out;

        for (int axis = 0; axis < 3; axis++) {
            int[] at;
            int[] uAt;
            int[] vAt;
            int nu, nv, planes;
            if (axis == 0) {        // plane at x, spans y then z
                at = xs;
                uAt = ys;
                vAt = zs;
                nu = ny;
                nv = nz;
                planes = nx;
            } else if (axis == 1) { // plane at y, spans x then z
                at = ys;
                uAt = xs;
                vAt = zs;
                nu = nx;
                nv = nz;
                planes = ny;
            } else {                // plane at z, spans x then y
                at = zs;
                uAt = xs;
                vAt = ys;
                nu = nx;
                nv = ny;
                planes = nz;
            }

            int cellWords = bitWords(nu * nv);
            long[][] groupBitsAtPlane = new long[21][];

            for (int p = 0; p <= planes; p++) {
                Arrays.fill(groupBitsAtPlane, null);
                for (int u = 0; u < nu; u++) {
                    for (int v = 0; v < nv; v++) {
                        int cl = p > 0 ? cellLevel(axis, level, p - 1, u, v, nx, ny, nz) : 0;
                        int nl = p < planes ? cellLevel(axis, level, p, u, v, nx, ny, nz) : 0;
                        int side, ql;
                        if (cl > 0 && nl > 0 && cl != nl) {
                            // A seam between two different-level volumes: the higher draws once.
                            if (cl > nl) {
                                side = 1;
                                ql = cl;
                            } else {
                                side = 2;
                                ql = nl;
                            }
                        } else if (cl > 0 && nl == 0) {
                            side = 1;
                            ql = cl;
                        } else if (nl > 0 && cl == 0) {
                            side = 2;
                            ql = nl;
                        } else {
                            // Both uncovered, or two equal-level cells of one volume: interior,
                            // no boundary here, so no draw.
                            continue;
                        }
                        int key = side * 8 + ql;
                        if (groupBitsAtPlane[key] == null) groupBitsAtPlane[key] = new long[cellWords];
                        setBit(groupBitsAtPlane[key], v * nu + u);
                    }
                }

                for (int key = 1; key <= 20; key++) {
                    long[] bits = groupBitsAtPlane[key];
                    if (bits == null) continue;
                    int ql = key % 8;
                    int[] quads = mergeFace(bits, nu, nv);
                    for (int i = 0; i < quads.length; i += 4) {
                        int u0 = quads[i], v0 = quads[i + 1], w = quads[i + 2], h = quads[i + 3];
                        if (out.length - outCount < 7) out = Arrays.copyOf(out, out.length * 2);
                        out[outCount] = axis;
                        out[outCount + 1] = at[p];
                        out[outCount + 2] = uAt[u0];
                        out[outCount + 3] = vAt[v0];
                        out[outCount + 4] = uAt[u0 + w];
                        out[outCount + 5] = vAt[v0 + h];
                        out[outCount + 6] = ql;
                        outCount += 7;
                    }
                }
            }
        }
        return Arrays.copyOf(out, outCount);
    }

    /**
     * Highest level of one cell of the compressed grid; p is the cell index along {@code axis}.
     */
    private static int cellLevel(int axis, int[] level, int p, int u, int v, int nx, int ny, int nz) {
        if (axis == 0) return level[(v * ny + u) * nx + p];
        if (axis == 1) return level[(v * ny + p) * nx + u];
        return level[(p * ny + v) * nx + u];
    }

    /**
     * True when every compressed cell in the given index box is covered by the union.
     */
    private static boolean covered(int[] level, int nx, int ny,
                                   int x0, int x1, int y0, int y1, int z0, int z1) {
        for (int k = z0; k < z1; k++) {
            for (int j = y0; j < y1; j++) {
                int row = (k * ny + j) * nx;
                for (int i = x0; i < x1; i++) {
                    if (level[row + i] == 0) return false;
                }
            }
        }
        return true;
    }

    private static int[] axisCoords(int[] bounds, int lo, int hi, int count) {
        int[] values = new int[count * 2];
        for (int b = 0; b < count; b++) {
            values[b * 2] = bounds[b * 6 + lo];
            values[b * 2 + 1] = bounds[b * 6 + hi];
        }
        Arrays.sort(values);
        int unique = 0;
        for (int v : values) {
            if (unique == 0 || values[unique - 1] != v) values[unique++] = v;
        }
        return Arrays.copyOf(values, unique);
    }

    private static int indexOf(int[] sorted, int value) {
        return Arrays.binarySearch(sorted, value);
    }

    /**
     * Meteor's {@code Dir} mask for the interior faces this class flagged.
     */
    private static int toExcludeDir(int mergeFaces) {
        int dir = 0;
        if ((mergeFaces & MERGE_FACE_WEST) != 0) dir |= Dir.WEST;
        if ((mergeFaces & MERGE_FACE_EAST) != 0) dir |= Dir.EAST;
        if ((mergeFaces & MERGE_FACE_DOWN) != 0) dir |= Dir.DOWN;
        if ((mergeFaces & MERGE_FACE_UP) != 0) dir |= Dir.UP;
        if ((mergeFaces & MERGE_FACE_NORTH) != 0) dir |= Dir.NORTH;
        if ((mergeFaces & MERGE_FACE_SOUTH) != 0) dir |= Dir.SOUTH;
        return dir;
    }

    /**
     * The loaded chunk section covering a tile, or null when that tile provably holds no
     * collectable cell.
     * <p>
     * Null means "treat every cell in this tile as air". It covers: the chunk is not loaded (a box
     * near render distance can overhang one), the section index is outside the build range, the
     * section is absent, vanilla's own `hasOnlyAir` fast path, and finally `maybeHas(OCCLUDING)` -
     * a section whose palette holds no occluding block cannot contain a cell passing
     * `!isAir() && canOcclude()`. `getChunkNow` never loads or requests a chunk.
     */
    private LevelChunkSection sectionAt(int chunkX, int sectionY, int chunkZ) {
        LevelChunk chunk = mc.level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) return null;
        LevelChunkSection[] sections = chunk.getSections();
        if (sectionY < 0 || sectionY >= sections.length) return null;
        LevelChunkSection section = sections[sectionY];
        if (section == null || section.hasOnlyAir()) return null;
        return section.maybeHas(OCCLUDING) ? section : null;
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
        if (isSeeThrough(x + outward.getStepX(), y + outward.getStepY(), z + outward.getStepZ())) return true;
        for (Direction dir : Direction.values()) {
            if (dir == outward) continue;
            if (isSeeThrough(x + dir.getStepX(), y + dir.getStepY(), z + dir.getStepZ())) return true;
        }
        return false;
    }

    /**
     * The old inline `nb.isAir() || !nb.canOcclude()` test: a face you can see through.
     */
    private boolean isSeeThrough(int x, int y, int z) {
        BlockState nb = mc.level.getBlockState(shared.scanPos.set(x, y, z));
        return nb.isAir() || !nb.canOcclude();
    }

    public void clear() {
        boxes.clear();
        mergedBoxes.clear();
        mergedQuads.clear();
        mergeSources.clear();
        mergeTooLarge = false;
        useMerged = false;
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
            if (layerY < mc.level.getMinY()) break;

            boolean valid = true;
            outer:
            for (int x = -y; x <= y; x++) {
                for (int z = -y; z <= y; z++) {
                    shared.scanPos.set(beaconPos.getX() + x, layerY, beaconPos.getZ() + z);
                    if (!isValidBeaconBase(mc.level.getBlockState(shared.scanPos).getBlock())) {
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
        int topY = mc.level.getMinY() + mc.level.getHeight();
        for (int y = beaconPos.getY() + 1; y < topY; y++) {
            shared.scanPos.set(x, y, z);
            BlockState state = mc.level.getBlockState(shared.scanPos);
            if (state.getBlock() != Blocks.BEDROCK && state.getLightDampening() >= 15) {
                return true;
            }
        }
        return false;
    }
}
