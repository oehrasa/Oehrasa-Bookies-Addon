package com.AutoBookshelf.addon.modules.BlockRadiusUtils;

import com.AutoBookshelf.addon.utils.DistanceUtil;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import static meteordevelopment.meteorclient.MeteorClient.mc;

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

    public void drawWorldLabel(PoseStack matrices, String text, double x,
                               double y, double z, SettingColor color, float scale, float offsetPx,
                               float tickDelta) {
        matrices.pushPose();
        Vec3 cam = mc.gameRenderer.mainCamera().position();
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

        SubmitNodeStorage collector = new SubmitNodeStorage();
        collector.submitText(
            matrices,
            -w, 0,
            FormattedCharSequence.forward(text, Style.EMPTY),
            false,
            Font.DisplayMode.SEE_THROUGH,
            0xF000F0,
            argb,
            bg,
            0 // outlineColor: "off"
        );
        mc.gameRenderer.featureRenderDispatcher().renderAllFeatures(collector);

        matrices.popPose();
    }

    public static float getDistanceLabelFactor(float distance) {
        if (distance <= 4F)
            return 0.30F;
        if (distance >= 28F)
            return 1.00F;
        return 0.30F + (distance - 4F) / 24F * 0.70F;
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
