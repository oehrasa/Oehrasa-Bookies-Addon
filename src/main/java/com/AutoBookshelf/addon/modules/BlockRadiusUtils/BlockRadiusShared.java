/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.BlockRadiusUtils;

import com.AutoBookshelf.addon.utils.DistanceUtil;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Vector3d;
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

import java.util.HashMap;
import java.util.Map;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * State and helpers shared by every BlockRadius feature renderer.
 * <p>
 * Each feature (beacons, lightning rods, conduits, creakings, wardens, sulfur, sponge) owns its own
 * settings and state, but they all draw through the same shape-mode gate, the same distance cull
 * and the same world-space label helper -> those live here rather than being duplicated per feature.
 */
public class BlockRadiusShared {
    /**
     * Reused mutable label background for the 2D pass (26.3); kept from allocating per line.
     */
    private final Color labelBackground = new Color();

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
        private static final int[] EMPTY_RECTS = new int[0];

        public final double minX, minY, minZ, maxX, maxY, maxZ;
        public int level; // beacon pyramid level; 0 for all other boxes
        public boolean render = true; // culling flag (beacons only)
        // Meteor `Dir` bits for the faces of this box that are interior to a merged beacon union
        // and must not be drawn. 0 everywhere else, which is Meteor's own "draw all faces".
        public int excludeDir = 0;
        // Cached solid-overlap shell for the beacon-range box, rebuilt only when the shell is
        // recomputed. Exactly one of the two forms is populated, chosen by
        // `beacon-solid-voxel-style`: one block per collected cell, or merged flat regions
        // flattened as (x1,y1,z1,x2,y2,z2) groups of six ints. Both are assigned by reference
        // from the shell cache, so a throttled scan copies nothing.
        public java.util.List<BlockPos> solidEdgeCells = java.util.List.of();
        public int[] solidEdgeRects = EMPTY_RECTS;

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

    /**
     * Small cache for translucent fill variants. Keyed on the RGB+alpha tuple rather than the
     * SettingColor instance, because SettingColor has identity-based hashCode/equals: a
     * freshly-allocated base (e.g. from getPulseColor) would otherwise never hit the cache
     * and the cache would be dead. Editing the base in the GUI changes the tuple, so a stale
     * entry is unreachable.
     */
    private record ColorKey(int r, int g, int b, int alpha) {
    }

    private final Map<ColorKey, SettingColor> fillCache = new HashMap<>();

    public SettingColor translucent(SettingColor base, int alpha) {
        ColorKey key = new ColorKey(base.r, base.g, base.b, alpha);
        SettingColor cached = this.fillCache.get(key);
        if (cached != null) return cached;
        SettingColor made = new SettingColor(base.r, base.g, base.b, alpha);
        if (this.fillCache.size() > 128) {
            this.fillCache.clear();
        }
        this.fillCache.put(key, made);
        return made;
    }

    public void renderBox(Renderer3D renderer,
                          double x1, double y1, double z1,
                          double x2, double y2, double z2,
                          SettingColor sideColor, SettingColor lineColor) {
        renderBox(renderer, x1, y1, z1, x2, y2, z2, sideColor, lineColor, shapeMode.get());
    }

    /**
     * Same draw, with the shape mode passed in. Callers that emit many boxes in one frame resolve
     * {@code shape-mode} once and use this overload instead of reading the setting per box (the
     * beacon shell can emit tens of thousands of boxes per frame).
     */
    public void renderBox(Renderer3D renderer,
                          double x1, double y1, double z1,
                          double x2, double y2, double z2,
                          SettingColor sideColor, SettingColor lineColor, ShapeMode mode) {
        if (mode.lines()) renderer.boxLines(x1, y1, z1, x2, y2, z2, lineColor, 0);
        if (mode.sides()) renderer.boxSides(x1, y1, z1, x2, y2, z2, sideColor, 0);
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

//? if <26.3 {
    /**
     * The batched label collector for this frame; created lazily on the first label of the
     * frame and drained once at {@link #flushWorldLabels()} instead of once per label line.
     * Labels submit copies of the pose into the collector, so the caller can pop its matrices
     * immediately.
     */
    private SubmitNodeStorage labelCollector;

    // Used on <26.2: 26.1.2 submits into the dispatcher's live SubmitNodeStorage instead of a
    // private collector.
    private boolean labelsSubmitted;

    private SubmitNodeStorage labelTarget() {
        //? if >=26.2 {
        if (this.labelCollector == null) {
            this.labelCollector = new SubmitNodeStorage();
        }
        return this.labelCollector;
        //?} else {
        /*this.labelsSubmitted = true;
        return mc.gameRenderer.getFeatureRenderDispatcher().getSubmitNodeStorage();*/
        //?}
    }
//?}

//? if <26.3 {
    public void drawWorldLabel(PoseStack matrices, String text, double x,
                               double y, double z, SettingColor color, float scale, float offsetPx,
                               float tickDelta) {
        matrices.pushPose();
        //? if >=26.2 {
        Vec3 cam = mc.gameRenderer.mainCamera().position();
//?} else {
        /*Vec3 cam = mc.gameRenderer.getMainCamera().position();
*///?}
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

        this.labelTarget().submitText(
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
        matrices.popPose();
    }

    /**
     * Draw every label submitted this frame, once, as a single feature pass. Called at the end
     * of the label producer's render (currently WardenEsp.render); the collector is fresh for
     * the next frame.
     */
    public void flushWorldLabels() {
//? if >=26.2 {
        SubmitNodeStorage collector = this.labelCollector;
        if (collector == null) {
            return;
        }
        this.labelCollector = null;
        mc.gameRenderer.featureRenderDispatcher().renderAllFeatures(collector);
//?} else {
        /*if (!this.labelsSubmitted) {
            return;
        }
        this.labelsSubmitted = false;
        mc.gameRenderer.getFeatureRenderDispatcher().renderAllFeatures();*/
//?}
    }

    public static float getDistanceLabelFactor(float distance) {
        if (distance <= 4F)
            return 0.30F;
        if (distance >= 28F)
            return 1.00F;
        return 0.30F + (distance - 4F) / 24F * 0.70F;
    }

//?}

    /**
     * Draws a billboard label in the 2D pass. 26.3 needs a RenderPass that only the engine's
     * frame owns, which a module cannot get, so the 26.2 world-space submit path is not
     * available; NametagUtils gives the same camera-facing billboard from Render2DEvent.
     * No distance factor: the screen-space form is already distance-invariant.
     */
//? if >=26.3 {
    /*public void drawWorldLabel(GuiGraphicsExtractor graphics, String text, double x,
                               double y, double z, SettingColor color, float scale, float offsetPx) {
        Vector3d joml = new Vector3d(x, y, z);
        if (!NametagUtils.to2D(joml, scale, false)) return;

        TextRenderer tr = TextRenderer.get();
        NametagUtils.begin(joml, graphics);

        // Measure while building: both renderers size themselves from state that begin() sets, so
        // measuring beforehand reads a stale scale. Width, height, the quad and the glyphs must
        // come from one begin().
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
*///?}

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
