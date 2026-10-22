package com.AutoBookshelf.addon.modules.BlockRadiusUtils;

import com.AutoBookshelf.addon.utils.DistanceUtil;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * State and helpers shared by every BlockRadius feature renderer.
 * <p>
 * Each feature (beacons, lightning rods, conduits, creakings, wardens, sulfur, sponge) owns its own
 * settings and state, but they all draw through the same shape-mode gate, the same distance cull
 * and the same world-space label helper -> those live here rather than being duplicated per feature.
 */
public class BlockRadiusShared {
    public final Setting<ShapeMode> shapeMode;
    public final Setting<Double> maxRenderDistance;
    public final Setting<Integer> searchRadius;

    /**
     * Reused mutable pos to avoid allocating a new BlockPos for every block checked.
     * Shared on purpose: the scans are throttled and never interleave, so one scratch pos serves
     * every feature without the values stepping on each other mid-loop.
     */
    public final BlockPos.MutableBlockPos scanPos = new BlockPos.MutableBlockPos();

    /**
     * Reused so the per-frame label background does not allocate a Color per line.
     */
    private final Color labelBackground = new Color();

    public BlockRadiusShared(Setting<ShapeMode> shapeMode, Setting<Double> maxRenderDistance,
                             Setting<Integer> searchRadius) {
        this.shapeMode = shapeMode;
        this.maxRenderDistance = maxRenderDistance;
        this.searchRadius = searchRadius;
    }

    public double maxDistSq() {
        double d = maxRenderDistance.get();
        return d * d;
    }

    /**
     * An axis-aligned bounding box used for all flat footprints and beacon cubes.
     */
    public static class RangeBox {
        public final double minX, minY, minZ, maxX, maxY, maxZ;
        public int level; // beacon pyramid level; 0 for all other boxes
        public boolean render = true; // culling flag (beacons only)
        // Cached solid-overlap edge cells for the beacon-range box, rebuilt each scan.
        public final java.util.List<BlockPos> solidEdgeCells = new java.util.ArrayList<>();

        public RangeBox(double x1, double y1, double z1, double x2, double y2, double z2) {
            this.minX = Math.min(x1, x2);
            this.maxX = Math.max(x1, x2);
            this.minY = Math.min(y1, y2);
            this.maxY = Math.max(y1, y2);
            this.minZ = Math.min(z1, z2);
            this.maxZ = Math.max(z1, z2);
        }

        public boolean contains(RangeBox o) {
            return minX <= o.minX && maxX >= o.maxX
                && minY <= o.minY && maxY >= o.maxY
                && minZ <= o.minZ && maxZ >= o.maxZ;
        }

        public double distanceSq(Vec3 p) {
            return DistanceUtil.distanceSqToBox(p, minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    public void renderBox(Renderer3D renderer,
                          double x1, double y1, double z1,
                          double x2, double y2, double z2,
                          SettingColor sideColor, SettingColor lineColor) {
        if (shapeMode.get().lines()) renderer.boxLines(x1, y1, z1, x2, y2, z2, lineColor, 0);
        if (shapeMode.get().sides()) renderer.boxSides(x1, y1, z1, x2, y2, z2, sideColor, 0);
    }

    /**
     * AABB form, forwarding to Meteor
     */
    public void renderBox(Renderer3D renderer, AABB box,
                          SettingColor sideColor, SettingColor lineColor) {
        ShapeMode mode = shapeMode.get();
        if (!mode.lines() && !mode.sides()) return;
        renderer.box(box, sideColor, lineColor, mode == ShapeMode.Lines
            ? meteordevelopment.meteorclient.renderer.ShapeMode.Lines
            : mode == ShapeMode.Sides
              ? meteordevelopment.meteorclient.renderer.ShapeMode.Sides
              : meteordevelopment.meteorclient.renderer.ShapeMode.Both, 0);
    }

    /**
     * Draws a billboard label in the 2D pass. The old submit-pipeline form needs a RenderPass we
     * cannot obtain outside the engine's own frame, and {@link NametagUtils} gives the same
     * camera-facing result from a Render2DEvent.
     * <p>
     * No distance factor: the screen-space form is already distance-invariant, so the old
     * world-space compensation would shrink nearby labels instead of keeping them readable.
     */
    public void drawWorldLabel(GuiGraphicsExtractor graphics, String text, double x, double y,
                               double z, SettingColor color, float scale, float offsetPx) {
        Vector3d joml = new Vector3d(x, y, z);
        if (!NametagUtils.to2D(joml, scale, false)) return;

        TextRenderer tr = TextRenderer.get();
        NametagUtils.begin(joml, graphics);

        // Measure while building: both renderers size themselves from state that begin() sets, so
        // measuring beforehand reads a stale scale left by whatever drew last (VanillaTextRenderer
        // keeps it in a field). Width, height, the quad and the glyphs must come from one begin().
        tr.begin(graphics, 1, false, false);
        double w = tr.getWidth(text);
        double h = tr.getHeight();
        double top = -h + offsetPx;

        int baseAlpha = (color.getPacked() >>> 24) & 0xFF;
        labelBackground.set(0, 0, 0, (int) (0.25 * baseAlpha));
        Renderer2D.COLOR.begin();
        Renderer2D.COLOR.quad(-w / 2 - 1, top - 1, w + 2, h + 2, labelBackground);
        Renderer2D.COLOR.render();

        tr.render(text, -w / 2, top, color, false);
        tr.end();

        NametagUtils.end(graphics);
    }

    public static float clamp01(float v) {
        if (Float.isNaN(v))
            return 0F;
        return Math.max(0F, Math.min(1F, v));
    }

    public static SettingColor flashColor(SettingColor color) {
        double t = (System.currentTimeMillis() % 1000L) / 1000.0;
        double pulse = 0.5 + 0.5 * Math.sin(t * Math.PI * 2);
        int alpha = (int) (color.a * (0.4 + 0.6 * pulse));
        return new SettingColor(color.r, color.g, color.b, alpha);
    }

    public static SettingColor getPulseColor(SettingColor base, float minBrightness) {
        return getPulseColor(base, minBrightness, 180.0);
    }

    public static SettingColor getFastPulseColor(SettingColor base, float minBrightness) {
        return getPulseColor(base, minBrightness, 90.0);
    }

    public static SettingColor getPulseColor(SettingColor base, float minBrightness, double speedMs) {
        float pulse = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / speedMs));
        float t = minBrightness + (1F - minBrightness) * pulse;
        return new SettingColor(
            (int) Math.min(255, base.r * t),
            (int) Math.min(255, base.g * t),
            (int) Math.min(255, base.b * t),
            base.a);
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
}
