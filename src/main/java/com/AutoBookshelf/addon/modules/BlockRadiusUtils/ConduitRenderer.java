package com.AutoBookshelf.addon.modules.BlockRadiusUtils;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ConduitBlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.AutoBookshelf.addon.modules.BlockRadiusUtils.BlockRadiusShared.RangeBox;
import static com.AutoBookshelf.addon.modules.BlockRadiusUtils.BlockRadiusShared.ShapeMode;
import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Conduit range: the Conduit Power radius as a flat hollow circle, plus the 8-block mob-attack
 * footprint for fully activated (42 prismarine) conduits.
 */
public class ConduitRenderer {
    private final BlockRadiusShared shared;

    private final Setting<Boolean> showConduits;
    private final Setting<Boolean> showConduitMobRange;
    private final Setting<SettingColor> sideColor;
    private final Setting<SettingColor> lineColor;
    private final Setting<SettingColor> mobSideColor;
    private final Setting<SettingColor> mobLineColor;

    private static class ConduitData {
        final List<RangeBox> footprintRows;   // voxel circle rows (always present, never empty)
        final RangeBox footprintBounds; // Box enclosing all footprintRows
        final RangeBox mobBox;          // 8-block mob-attack footprint (non-null when fullFrame)

        ConduitData(List<RangeBox> footprintRows, RangeBox footprintBounds, RangeBox mobBox) {
            this.footprintRows = footprintRows;
            this.footprintBounds = footprintBounds;
            this.mobBox = mobBox;
        }
    }

    private static final int[][] FRAME_OFFSETS = buildFrameOffsets();

    private final List<ConduitData> datas = new ArrayList<>();

    public ConduitRenderer(SettingGroup sgGeneral, SettingGroup sgRender,
                           BlockRadiusShared shared, Setting<ShapeMode> shapeMode) {
        this.shared = shared;

        showConduits = sgGeneral.add(new BoolSetting.Builder()
            .name("show-conduits")
            .description("Renders the Conduit Power range of active conduits as a flat hollow sphere ring.")
            .defaultValue(true)
            .build()
        );

        showConduitMobRange = sgGeneral.add(new BoolSetting.Builder()
            .name("show-conduit-mob-range")
            .description("Also renders the 8-block mob-attack of fully activated conduits (42 prism blocks).")
            .defaultValue(true)
            .visible(showConduits::get)
            .build()
        );

        sideColor = sgRender.add(new ColorSetting.Builder()
            .name("conduit-side-color")
            .description("Side color of conduit Conduit Power range footprint.")
            .defaultValue(new SettingColor(0, 150, 255, 40))
            .visible(() -> shapeMode.get().sides() && showConduits.get())
            .build()
        );

        lineColor = sgRender.add(new ColorSetting.Builder()
            .name("conduit-line-color")
            .description("Line color of conduit Conduit Power range footprint.")
            .defaultValue(new SettingColor(0, 150, 255, 255))
            .visible(() -> shapeMode.get().lines() && showConduits.get())
            .build()
        );

        mobSideColor = sgRender.add(new ColorSetting.Builder()
            .name("conduit-mob-side-color")
            .description("Side color of the 8-block mob-attack footprint (fully activated conduit only).")
            .defaultValue(new SettingColor(255, 60, 60, 50))
            .visible(() -> shapeMode.get().sides() && showConduits.get() && showConduitMobRange.get())
            .build()
        );

        mobLineColor = sgRender.add(new ColorSetting.Builder()
            .name("conduit-mob-line-color")
            .description("Line color of the 8-block mob-attack footprint (fully activated conduit only).")
            .defaultValue(new SettingColor(255, 60, 60, 255))
            .visible(() -> shapeMode.get().lines() && showConduits.get() && showConduitMobRange.get())
            .build()
        );
    }

    public void scan() {
        datas.clear();
        if (!showConduits.get()) return;

        for (BlockEntity be : Utils.blockEntities()) {
            if (!(be instanceof ConduitBlockEntity)) continue;

            BlockPos pos = be.getPos();
            int frameCount = countConduitFrame(pos);
            if (frameCount < 16) continue; // not yet activated

            int range = (int) Math.floor(frameCount / 7.0) * 16;
            double cx = pos.getX();
            double cy = pos.getY();
            double cz = pos.getZ();
            boolean fullFrame = frameCount >= 42;

            // Voxelized circular footprint (Euclidean/spherical distance), 1 block tall.
            List<RangeBox> footprintRows = buildCircularFootprint(cx, cy, cz, range);

            // Cheap Box covering the whole footprint, used to cull the entire conduit
            // with one distance check instead of one per row box (see render).
            RangeBox footprintBounds = new RangeBox(
                cx - range, cy, cz - range,
                cx + range, cy + 1.0, cz + range
            );

            // Mob-attack footprint: vanilla builds new Box(pos).expand(8.0), so the box runs
            // from x-8 to x+9 in each axis (the conduit's own 1-block extent plus 8 either way),
            // not x-8..x+8. That box is the query getEntitiesOfClass tests against entity hitboxes,
            // filtered to Enemy && isInWaterOrRain and gated on size >= 42 + gameTime % 40.
            // Keeping the SAME target is stricter (blockPosition().closerThan(pos, 8.0) = d2 <= 63),
            // but a dropped target is re-acquired from this box, so the box is the damage region.
            // Only the X/Z extent is drawn; Y stays the conduit's single layer.
            RangeBox mobBox = null;
            if (fullFrame && showConduitMobRange.get()) {
                mobBox = new RangeBox(
                    cx - 8, cy, cz - 8,
                    cx + 9, cy + 1.0, cz + 9
                );
            }

            datas.add(new ConduitData(footprintRows, footprintBounds, mobBox));
        }
    }

    public void render(Render3DEvent event, Vec3d cam, double maxDistSq) {
        if (!showConduits.get()) return;

        for (ConduitData data : datas) {
            // One distance check culls every row of this conduit's footprint at once,
            // instead of testing each of the up-to-193 row boxes against the camera.
            if (data.footprintBounds.distanceSq(cam) <= maxDistSq) {
                for (RangeBox row : data.footprintRows) {
                    shared.renderBox(event.renderer,
                        row.minX, row.minY, row.minZ,
                        row.maxX, row.maxY, row.maxZ,
                        sideColor.get(), lineColor.get());
                }
            }

            if (showConduitMobRange.get() && data.mobBox != null) {
                RangeBox mb = data.mobBox;
                if (mb.distanceSq(cam) <= maxDistSq) {
                    shared.renderBox(event.renderer,
                        mb.minX, mb.minY, mb.minZ,
                        mb.maxX, mb.maxY, mb.maxZ,
                        mobSideColor.get(), mobLineColor.get());
                }
            }
        }
    }

    public void clear() {
        datas.clear();
    }

    private List<RangeBox> buildCircularFootprint(double centerX, double centerY, double centerZ, int range) {
        List<RangeBox> rows = new ArrayList<>(range * 4 + 2);

        int blockX = (int) Math.floor(centerX);
        int blockZ = (int) Math.floor(centerZ);
        // Vanilla tests Vec3i#closerThan, a strict "<", against whole-block offsets, so the
        // affected set is ox*ox + oz*oz <= range*range - 1. A row at |oz| == range is now empty
        // and falls out through the outerRemaining < 0 guard.
        long outerRangeSq = (long) range * range - 1;
        int innerRange = range - 1;
        // Strict on the inner edge too: the drawn shell is the outermost block shell of the
        // affected disc, so both bounds have to read "inside" the same way the vanilla test does.
        long innerRangeSq = (long) innerRange * innerRange - 1;

        for (int oz = -range; oz <= range; oz++) {
            long outerRemaining = outerRangeSq - (long) oz * oz;
            if (outerRemaining < 0) continue; // out of range at this row (only possible at the extreme ends)
            int oxOuter = (int) Math.floor(Math.sqrt((double) outerRemaining));

            if (innerRange < 0 || Math.abs(oz) >= innerRange) {
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
            shared.scanPos.set(conduitPos.getX() + off[0],
                conduitPos.getY() + off[1],
                conduitPos.getZ() + off[2]);
            if (isValidConduitFrame(mc.world.getBlockState(shared.scanPos).getBlock())) count++;
        }
        return count;
    }

    private boolean isValidConduitFrame(Block b) {
        return b == Blocks.PRISMARINE ||
            b == Blocks.DARK_PRISMARINE ||
            b == Blocks.PRISMARINE_BRICKS ||
            b == Blocks.SEA_LANTERN;
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
}
