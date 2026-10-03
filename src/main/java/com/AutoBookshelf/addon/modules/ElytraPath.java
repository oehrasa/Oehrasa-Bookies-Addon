package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.*;

public class ElytraPath extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> predictionTicks = sgGeneral.add(new IntSetting.Builder()
        .name("prediction-ticks")
        .description("How many ticks ahead to draw.")
        .defaultValue(60)
        .min(10)
        .sliderMax(200)
        .build()
    );

    private final Setting<Double> idleSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("idle-speed")
        .description("Forward speed when you are gliding but not moving.")
        .defaultValue(0.6)
        .min(0.1)
        .sliderMax(2.0)
        .build()
    );

    private final Setting<Double> speedThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed-threshold")
        .description("Speed below which the idle camera path is used.")
        .defaultValue(0.05).min(0.01)
        .sliderMax(0.2)
        .build()
    );

    private final Setting<Boolean> startFromCrosshair = sgGeneral.add(new BoolSetting.Builder()
        .name("start-from-crosshair")
        .description("Start the line from your exact crosshair position.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> startOffset = sgGeneral.add(new DoubleSetting.Builder()
        .name("start-offset")
        .description("Vertical offset below the eye when not using crosshair.")
        .defaultValue(-0.4)
        .min(-1.0)
        .max(1.0)
        .sliderRange(-1.0, 1.0)
        .visible(() -> !startFromCrosshair.get())
        .build()
    );

    private final Setting<Double> velocitySmoothing = sgGeneral.add(new DoubleSetting.Builder()
        .name("velocity-smoothing")
        .description("How smooth the movement direction changes.")
        .defaultValue(0.3)
        .min(0.0)
        .max(1.0)
        .sliderRange(0.0, 1.0)
        .build()
    );

    private final Setting<Boolean> stopAtBlock = sgGeneral.add(new BoolSetting.Builder()
        .name("stop-at-block")
        .description("Stop the line at the first solid block.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> thirdPersonOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("third-person-only")
        .description("Only render the path in third-person view.")
        .defaultValue(false)
        .build()
    );

    public enum ColorMode { Solid, Fade, Gradient }

    private final Setting<ColorMode> colorMode = sgGeneral.add(new EnumSetting.Builder<ColorMode>()
        .name("color-mode")
        .description("How the line is coloured.")
        .defaultValue(ColorMode.Gradient)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgGeneral.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Colour of the indicator line.")
        .defaultValue(new SettingColor(0, 255, 255, 200))
        .build()
    );

    private final Setting<Boolean> showVerticalIndicators = sgGeneral.add(new BoolSetting.Builder()
        .name("vertical-indicators")
        .description("Draw a vertical line when ascending or descending.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> renderOtherPlayers = sgGeneral.add(new BoolSetting.Builder()
        .name("other-players")
        .description("Render elytra path prediction for other players too.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> ascendColor = sgGeneral.add(new ColorSetting.Builder()
        .name("ascend-color")
        .description("Colour of the ascending line.")
        .visible(showVerticalIndicators::get)
        .defaultValue(new SettingColor(255, 215, 0, 200))
        .build()
    );

    private final Setting<SettingColor> descendColor = sgGeneral.add(new ColorSetting.Builder()
        .name("descend-color")
        .description("Colour of the descending line.")
        .visible(showVerticalIndicators::get)
        .defaultValue(new SettingColor(0, 87, 183, 200))
        .build()
    );

    private final Setting<SettingColor> gradientStart = sgGeneral.add(new ColorSetting.Builder()
        .name("gradient-start")
        .description("Colour at the player.")
        .visible(() -> colorMode.get() == ColorMode.Gradient)
        .defaultValue(new SettingColor(0, 255, 0, 255))
        .build()
    );

    private final Setting<SettingColor> gradientEnd = sgGeneral.add(new ColorSetting.Builder()
        .name("gradient-end")
        .description("Colour at the furthest point.")
        .visible(() -> colorMode.get() == ColorMode.Gradient)
        .defaultValue(new SettingColor(255, 0, 0, 255))
        .build()
    );

    private final Setting<Boolean> renderImpactBox = sgGeneral.add(new BoolSetting.Builder()
        .name("render-impact-box")
        .description("Draw a box at the block the line would hit.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> impactBoxColor = sgGeneral.add(new ColorSetting.Builder()
        .name("impact-box-color")
        .description("Colour of the impact box.")
        .visible(renderImpactBox::get)
        .defaultValue(new SettingColor(255, 255, 255, 200))
        .build()
    );

    private final Setting<ShapeMode> impactBoxShape = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("impact-box-shape")
        .description("How the impact box is rendered.")
        .visible(renderImpactBox::get)
        .defaultValue(ShapeMode.Lines)
        .build()
    );

    /**
     * Scratch colour mutated per-segment so Fade/Gradient never allocate.
     */
    private final SettingColor scratchColor = new SettingColor(0, 0, 0, 255);

    /**
     * Smoothed horizontal velocity for the local player.
     */
    private Vec3d smoothedVelocity = Vec3d.ZERO;

    /**
     * Path invalidation is content-based, not frame-count based. Each
     * mc.world.raycast per segment is the dominant per-frame cost (up to
     * predictionTicks raycasts per path), so the simulated segments are cached
     * and only re-simulated when an input they depend on actually changes: the
     * horizontal heading (direction + magnitude) deviating,
     */
    private static final double HEADING_CHANGE_RATIO = 0.0025; // ≈4° at constant speed

    /**
     * Per-player path state, so each gliding player renders their own segments/impact.
     */
    private static final class PathCache {
        boolean initialized = false;
        BlockPos impact = null;
        Vec3d origin = Vec3d.ZERO;
        Vec3d dir = Vec3d.ZERO; // cached horizontal step (direction + magnitude)
        double vertY = 0.0;     // cached rawVel.y used for the vertical indicator
        int configSig = 0;      // settings fingerprint the cached segments were built from
        final List<Segment> segments = new ArrayList<>();
    }

    private final Map<UUID, PathCache> pathCaches = new HashMap<>();

    public ElytraPath() {
        super(Addon.CATEGORY, "Elytra-Path",
            "Shows your elytra flight path to destination with smooth movement. Better luck next time, Pilots.");
    }

    @Override
    public void onActivate() {
        smoothedVelocity = Vec3d.ZERO;
        pathCaches.clear();
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;
        if (thirdPersonOnly.get() && mc.options.getPerspective().isFirstPerson()) return;

        renderPlayerPath(event, mc.player);

        if (renderOtherPlayers.get()) {
            Set<UUID> present = new HashSet<>();
            present.add(mc.player.getUuid());
            for (PlayerEntity player : mc.world.getPlayers()) {
                if (player == mc.player) continue;
                present.add(player.getUuid());
                renderPlayerPath(event, player);
            }
            // Drop cached state for players no longer present so it doesn't accumulate forever.
            pathCaches.keySet().removeIf(uuid -> !present.contains(uuid));
        }
    }

    private void renderPlayerPath(Render3DEvent event, PlayerEntity player) {
        ItemStack chest = player.getEquippedStack(EquipmentSlot.CHEST);
        UUID uuid = player.getUuid();

        if (chest.getItem() != Items.ELYTRA || !player.isGliding()) {
            if (player == mc.player) {
                smoothedVelocity = Vec3d.ZERO;
            }
            pathCaches.remove(uuid);
            return;
        }

        PathCache cache = pathCaches.computeIfAbsent(uuid, k -> new PathCache());

        Vec3d startPos = computeStartPos(player);
        Vec3d rawVel = player.getVelocity();
        Vec3d hDir = computeHorizontalDirection(player, rawVel);

        int configSig = configSignature();
        double stepSq = Math.max(hDir.lengthSquared(), 1e-4);
        if (!cache.initialized
            || cache.configSig != configSig
            || headingChanged(cache.dir, hDir)
            || Math.abs(rawVel.y - cache.vertY) > 0.02
            || startPos.squaredDistanceTo(cache.origin) > stepSq) {
            simulatePath(cache, startPos, hDir, rawVel);
            cache.origin = startPos;
            cache.dir = hDir;
            cache.vertY = rawVel.y;
            cache.configSig = configSig;
            cache.initialized = true;
        }

        renderCachedPath(event, cache, startPos.subtract(cache.origin));
    }

    /**
     * Fingerprint of the settings that shape the cached segments. A change in
     * any of them (color mode/palette, stop-at-block, tick count, vertical
     * indicator colors) must re-simulate, even if heading and speed are steady.
     */
    private int configSignature() {
        int h = 1;
        h = 31 * h + predictionTicks.get();
        h = 31 * h + colorMode.get().ordinal();
        h = 31 * h + stopAtBlock.get().hashCode();
        h = 31 * h + showVerticalIndicators.get().hashCode();
        h = 31 * h + lineColor.get().r;
        h = 31 * h + lineColor.get().g;
        h = 31 * h + lineColor.get().b;
        h = 31 * h + lineColor.get().a;
        h = 31 * h + gradientStart.get().r;
        h = 31 * h + gradientStart.get().g;
        h = 31 * h + gradientStart.get().b;
        h = 31 * h + gradientStart.get().a;
        h = 31 * h + gradientEnd.get().r;
        h = 31 * h + gradientEnd.get().g;
        h = 31 * h + gradientEnd.get().b;
        h = 31 * h + gradientEnd.get().a;
        h = 31 * h + ascendColor.get().r;
        h = 31 * h + ascendColor.get().g;
        h = 31 * h + ascendColor.get().b;
        h = 31 * h + ascendColor.get().a;
        h = 31 * h + descendColor.get().r;
        h = 31 * h + descendColor.get().g;
        h = 31 * h + descendColor.get().b;
        h = 31 * h + descendColor.get().a;
        return h;
    }

    /**
     * True when the cached step vector no longer matches the current one. Both a
     * heading change and a speed change invalidate, keeping the cached segments
     * faithful in direction and per-tick length. The comparison is relative, so
     * small frame-to-frame drift (velocity smoothing, lateral motion) reuses the
     * cache while any real steering recomputes it.
     */
    private static boolean headingChanged(Vec3d cached, Vec3d current) {
        double lenSq = cached.lengthSquared();
        if (lenSq <= 1e-8) return current.lengthSquared() > 1e-8;
        return current.squaredDistanceTo(cached) > HEADING_CHANGE_RATIO * lenSq;
    }

    /**
     * Recomputes the horizontal path (and optionally the vertical indicator)
     * into the given player's segment cache. Only called when the path inputs
     * actually changed (see renderPlayerPath), not per frame.
     */
    private void simulatePath(PathCache cache, Vec3d startPos, Vec3d hDir, Vec3d rawVel) {
        cache.segments.clear();
        cache.impact = null;

        simulateIntoCache(cache, startPos, hDir, false, null);

        if (showVerticalIndicators.get() && Math.abs(rawVel.y) > 0.02) {
            Vec3d vertDir = new Vec3d(0, rawVel.y, 0);
            SettingColor c = rawVel.y > 0 ? ascendColor.get() : descendColor.get();
            simulateIntoCache(cache, startPos, vertDir, true, c);
        }
    }

    private void simulateIntoCache(PathCache cache, Vec3d start, Vec3d step, boolean isVertical, SettingColor overrideColor) {
        final int maxTicks = predictionTicks.get();
        final ColorMode mode = colorMode.get();

        // Pre-read gradient values once to avoid repeated .get() inside the loop
        final SettingColor gStart = (overrideColor == null && mode == ColorMode.Gradient) ? gradientStart.get() : null;
        final SettingColor gEnd = (overrideColor == null && mode == ColorMode.Gradient) ? gradientEnd.get() : null;
        final SettingColor solid = (overrideColor == null && mode == ColorMode.Solid) ? lineColor.get() : null;
        final SettingColor fade = (overrideColor == null && mode == ColorMode.Fade) ? lineColor.get() : null;
        final float maxT = maxTicks - 1f; // denominator for t, avoids recomputing

        Vec3d prevPos = start;
        BlockHitResult impactHit = null;

        for (int i = 0; i < maxTicks; i++) {
            Vec3d nextPos = prevPos.add(step);

            if (stopAtBlock.get()) {
                BlockHitResult hit = raytraceBlock(prevPos, nextPos);
                if (hit != null) {
                    // Render the final partial segment up to the hit surface
                    appendSegment(cache, prevPos, hit.getPos(),
                        segmentColor(overrideColor, mode, solid, fade, gStart, gEnd, i, maxT));
                    impactHit = hit;
                    break;
                }
            }

            appendSegment(cache, prevPos, nextPos,
                segmentColor(overrideColor, mode, solid, fade, gStart, gEnd, i, maxT));

            prevPos = nextPos;
        }

        // The horizontal pass owns the impact box whenever it hits one; the vertical
        // indicator must not overwrite the block the flight path itself will hit. But
        // the horizontal step is a flat line at eye level, so in open air it rarely
        // hits anything while the descending vertical pass always reaches the ground.
        // Let the vertical pass supply the impact only as a fallback when the horizontal
        // pass found none, otherwise the box never renders on an unobstructed glide.
        if (impactHit != null && (!isVertical || cache.impact == null)) {
            cache.impact = impactHit.getBlockPos();
        }
    }

    // segmentColor() returns the mutated scratchColor for Fade/Gradient, so the
    // colour must be copied when storing into the cache; the copy only happens on
    // the throttled recompute, not per frame.
    private void appendSegment(PathCache cache, Vec3d p1, Vec3d p2, SettingColor color) {
        cache.segments.add(new Segment(p1, p2, new SettingColor(color.r, color.g, color.b, color.a)));
    }

    private void renderCachedPath(Render3DEvent event, PathCache cache, Vec3d offset) {
        for (Segment segment : cache.segments) {
            event.renderer.line(
                segment.a.x + offset.x, segment.a.y + offset.y, segment.a.z + offset.z,
                segment.b.x + offset.x, segment.b.y + offset.y, segment.b.z + offset.z,
                segment.color);
        }

        if (renderImpactBox.get() && cache.impact != null) {
            event.renderer.box(cache.impact, impactBoxColor.get(), impactBoxColor.get(), impactBoxShape.get(), 0);
        }
    }

    private record Segment(Vec3d a, Vec3d b, SettingColor color) {
    }

    private SettingColor segmentColor(SettingColor override,
                                      ColorMode mode,
                                      SettingColor solid,
                                      SettingColor fade,
                                      SettingColor gStart,
                                      SettingColor gEnd,
                                      int i, float maxT) {
        if (override != null) return override;

        switch (mode) {

            case Fade -> {
                float progress = i / maxT;
                scratchColor.r = fade.r;
                scratchColor.g = fade.g;
                scratchColor.b = fade.b;
                scratchColor.a = Math.max(0, (int) (fade.a * (1f - progress)));
                return scratchColor;
            }

            case Gradient -> {
                float t = i / maxT;
                scratchColor.r = (int) (gStart.r + t * (gEnd.r - gStart.r));
                scratchColor.g = (int) (gStart.g + t * (gEnd.g - gStart.g));
                scratchColor.b = (int) (gStart.b + t * (gEnd.b - gStart.b));
                scratchColor.a = (int) (gStart.a + t * (gEnd.a - gStart.a));
                return scratchColor;
            }

            default -> {
                return solid;
            }
        }
    }

    private Vec3d computeStartPos(PlayerEntity player) {
        if (player == mc.player && startFromCrosshair.get()) {
            return new Vec3d(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z);
        }
        Vec3d eye = player.getEntityPos().add(0, player.getEyeHeight(player.getPose()), 0);
        return eye.add(0, startOffset.get(), 0);
    }

    private Vec3d computeHorizontalDirection(PlayerEntity player, Vec3d rawVel) {
        Vec3d rawHoriz = new Vec3d(rawVel.x, 0.0, rawVel.z);
        double threshSq = speedThreshold.get() * speedThreshold.get();

        if (player == mc.player) {
            double sf = velocitySmoothing.get();
            smoothedVelocity = smoothedVelocity.multiply(1.0 - sf).add(rawHoriz.multiply(sf));

            if (smoothedVelocity.lengthSquared() > threshSq) {
                return smoothedVelocity; // already carries magnitude, no need to re-scale
            }
        } else {
            if (rawHoriz.lengthSquared() > threshSq) {
                return rawHoriz;
            }
        }

        // Idle: project forward vector at configured speed
        Vec3d forward = player.getRotationVec(1.0F);
        return new Vec3d(forward.x * idleSpeed.get(), 0.0, forward.z * idleSpeed.get());
    }


    private BlockHitResult raytraceBlock(Vec3d start, Vec3d end) {
        RaycastContext ctx = new RaycastContext(
            start, end,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            mc.player
        );
        BlockHitResult result = mc.world.raycast(ctx);
        return result.getType() == HitResult.Type.BLOCK ? result : null;
    }
}
