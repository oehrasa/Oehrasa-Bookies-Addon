package com.AutoBookshelf.addon.modules.BlockRadiusUtils;

import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.monster.warden.AngerLevel;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Warden ESP: colour-coded shapes, world-space labels, tracers and an anger-trend arrow.
 * <p>
 * The tracked list is refreshed on the module's throttled scan cadence, but anger/pose/animation
 * state is read live off the entities every frame since that needs no world scan.
 */
public class WardenEsp {
    private final BlockRadiusShared shared;

    private final Setting<Boolean> showWardens;
    private final Setting<WardenShape> wardenShape;
    private final Setting<Boolean> fillWardenShapes;
    private final Setting<WardenTracerMode> wardenTracerMode;
    private final Setting<Boolean> wardenTracerFlash;
    private final Setting<Boolean> showWardenTargetLabel;
    private final Setting<Boolean> showSniffingPulse;
    private final Setting<Boolean> showImminentIndicator;
    private final Setting<Boolean> showAngerTrend;
    private final Setting<Double> wardenLabelScale;
    private final Setting<SettingColor> wardenCalmColor;
    private final Setting<SettingColor> wardenSearchingColor;
    private final Setting<SettingColor> wardenAgitatedColor;
    private final Setting<SettingColor> wardenLockedColor;
    private final Setting<SettingColor> wardenDiggingColor;
    private final Setting<SettingColor> wardenOtherTargetColor;

    private record WardenState(SettingColor baseColor, String label, boolean locked,
                               boolean sniffing, boolean digging, boolean emerging,
                               boolean lockedOnYou, boolean hasTarget, LivingEntity target) {
    }

    private final List<LivingEntity> wardens = new ArrayList<>();
    private final Map<Integer, Integer> lastAnger = new HashMap<>();
    private final Map<Integer, Long> attackExpiry = new HashMap<>();
    private final Map<Integer, Long> angerTrendExpiry = new HashMap<>();
    private final Map<Integer, Boolean> angerTrendUp = new HashMap<>();
    private static final long ANGER_TREND_HOLD_TICKS = 30L; // ~1.5s

    public WardenEsp(SettingGroup sgWarden, BlockRadiusShared shared) {
        this.shared = shared;

        showWardens = sgWarden.add(new BoolSetting.Builder()
            .name("show-wardens")
            .description("Enables Warden ESP: color-coded shapes, labels and tracers.")
            .defaultValue(true)
            .build()
        );

        wardenShape = sgWarden.add(new EnumSetting.Builder<WardenShape>()
            .name("warden-shape")
            .description("Warden ESP shape style.")
            .defaultValue(WardenShape.Box)
            .visible(showWardens::get)
            .build()
        );

        fillWardenShapes = sgWarden.add(new BoolSetting.Builder()
            .name("warden-fill-shapes")
            .description("Render filled versions of the Warden ESP shapes.")
            .defaultValue(false)
            .visible(showWardens::get)
            .build()
        );

        wardenTracerMode = sgWarden.add(new EnumSetting.Builder<WardenTracerMode>()
            .name("warden-tracers")
            .description("When to draw tracers to wardens.")
            .defaultValue(WardenTracerMode.Targeting)
            .visible(showWardens::get)
            .build()
        );

        wardenTracerFlash = sgWarden.add(new BoolSetting.Builder()
            .name("warden-tracer-flash")
            .description("Make warden tracers pulse with a smooth fade.")
            .defaultValue(false)
            .visible(showWardens::get)
            .build()
        );

        showWardenTargetLabel = sgWarden.add(new BoolSetting.Builder()
            .name("warden-target-label")
            .description("Show TARGET: YOU/OTHER label.")
            .defaultValue(true)
            .visible(showWardens::get)
            .build()
        );

        showSniffingPulse = sgWarden.add(new BoolSetting.Builder()
            .name("warden-sniff-pulse")
            .description("Show a brief pulsing overlay when the Warden is sniffing.")
            .defaultValue(true)
            .visible(showWardens::get)
            .build()
        );

        showImminentIndicator = sgWarden.add(new BoolSetting.Builder()
            .name("warden-combat-imminence")
            .description("Show an indicator when combat/sonic attack is imminent.")
            .defaultValue(true)
            .visible(showWardens::get)
            .build()
        );

        showAngerTrend = sgWarden.add(new BoolSetting.Builder()
            .name("warden-anger-trend")
            .description("Show a simple up/down indicator when anger changes.")
            .defaultValue(false)
            .visible(showWardens::get)
            .build()
        );

        wardenLabelScale = sgWarden.add(new DoubleSetting.Builder()
            .name("warden-label-scale")
            .description("Scale of the Warden labels.")
            .defaultValue(1.2)
            .min(0.7)
            .max(10.0)
            .sliderRange(0.7, 10.0)
            .visible(showWardens::get)
            .build()
        );

        wardenCalmColor = sgWarden.add(new ColorSetting.Builder()
            .name("warden-calm-color")
            .description("Color when the warden is calm.")
            .defaultValue(new SettingColor(85, 255, 85))
            .visible(showWardens::get)
            .build()
        );

        wardenSearchingColor = sgWarden.add(new ColorSetting.Builder()
            .name("warden-searching-color")
            .description("Color when the warden has nonzero anger but isn't agitated/locked.")
            .defaultValue(new SettingColor(255, 255, 85))
            .visible(showWardens::get)
            .build()
        );

        wardenAgitatedColor = sgWarden.add(new ColorSetting.Builder()
            .name("warden-agitated-color")
            .description("Color when sniffing or agitated.")
            .defaultValue(new SettingColor(255, 170, 0))
            .visible(showWardens::get)
            .build()
        );

        wardenLockedColor = sgWarden.add(new ColorSetting.Builder()
            .name("warden-locked-color")
            .description("Color when the warden has a target or is angry.")
            .defaultValue(new SettingColor(255, 85, 85))
            .visible(showWardens::get)
            .build()
        );

        wardenDiggingColor = sgWarden.add(new ColorSetting.Builder()
            .name("warden-digging-color")
            .description("Color when digging or emerging.")
            .defaultValue(new SettingColor(85, 255, 255))
            .visible(showWardens::get)
            .build()
        );

        wardenOtherTargetColor = sgWarden.add(new ColorSetting.Builder()
            .name("warden-other-target-color")
            .description("Color for the 'TARGET: OTHER' label.")
            .defaultValue(new SettingColor(170, 170, 170))
            .visible(showWardens::get)
            .build()
        );
    }

    public void scan() {
        wardens.clear();
        if (!showWardens.get()) {
            lastAnger.clear();
            attackExpiry.clear();
            angerTrendExpiry.clear();
            angerTrendUp.clear();
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
        angerTrendExpiry.keySet().removeIf(id -> !seen.contains(id));
        angerTrendUp.keySet().removeIf(id -> !seen.contains(id));
    }

    public void clear() {
        wardens.clear();
        lastAnger.clear();
        attackExpiry.clear();
        angerTrendExpiry.clear();
        angerTrendUp.clear();
    }

    public void render(Render3DEvent event) {
        if (!showWardens.get() || wardens.isEmpty()) return;

        WardenShape currentShape = wardenShape.get();
        boolean drawShape = currentShape != WardenShape.None;
        boolean drawFill = drawShape && fillWardenShapes.get();

        for (LivingEntity w : wardens) {
            if (!(w instanceof Warden warden)) continue;

            int anger = warden.getClientAngerLevel();
            Integer prev = lastAnger.get(w.getId());
            lastAnger.put(w.getId(), anger);
            long nowTick = warden.tickCount;

            if (showAngerTrend.get()) {
                try {
                    // EntityUtils has no lerped-box helper; interpolate manually.
                    Vec3 lerpedPos = w.getPosition(event.tickDelta);
                    AABB lerpedForTrend = w.getBoundingBox().move(lerpedPos.subtract(w.position()));
                    renderAngerTrend(event, w.getId(), anger, prev, nowTick, lerpedForTrend);
                } catch (Throwable t) {
                    // isolated: a trend-render failure must never block the rest of this warden's rendering
                }
            }

            try {
                // EntityUtils has no lerped-box helper; interpolate manually.
                Vec3 lerpedPos = w.getPosition(event.tickDelta);
                AABB lerped = w.getBoundingBox().move(lerpedPos.subtract(w.position()));

                // Derive from the synced client anger value (the same `anger`
                // used for the trend indicator) rather than
                // warden.getAngerLevel(): that reads angerManagement's
                // *server-side* active anger, which is not what the client has
                // synced, so the two disagree for a non-hostile warden.
                AngerLevel angriness = AngerLevel.byAnger(anger);

                WardenState state = classifyWarden(warden, anger, angriness);
                SettingColor color = state.baseColor();

                // 26.1.2's AnimationState dropped isRunning(); isStarted() is the public
                // equivalent (it checks the startTick sentinel, unlike getTimeInMillis
                // which is >0 on any unstarted state once tickDelta is nonzero).
                boolean sonicCharge = isAnimationPlaying(warden.sonicBoomAnimationState);
                boolean attackAnim = isAnimationPlaying(warden.attackAnimationState);
                boolean imminent = sonicCharge || attackAnim;

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
                        // Meteor's Renderer3D has no octahedron primitive, so build the 8 faces
                        // from quads + edges instead of silently falling back to a box.
                        case Octahedron -> renderOctahedron(event.renderer, lerped, boxColor, color, mode);
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
                    event.renderer.box(lerped, fill, fill,
                        meteordevelopment.meteorclient.renderer.ShapeMode.Sides, 0);
                }

            } catch (Throwable t) {
                // ignore per-entity errors
            }
        }
    }

    /**
     * Draws the warden labels. They live in the 2D pass because the world-space submit pipeline
     * needs a RenderPass that only the engine's own frame owns; {@link
     * meteordevelopment.meteorclient.utils.render.NametagUtils} projects the same billboard.
     */
    public void renderLabels(Render2DEvent event) {
        if (!showWardens.get() || wardens.isEmpty()) return;

        float labelScaleValue = wardenLabelScale.get().floatValue();
        // Pitch comes from the active renderer, not a constant: both fonts draw ~18px-tall lines
        // (VanillaTextRenderer begin(..., 1) draws at 2x, the custom font is 27 / 1.5), so the old
        // 12px world-space constant from the 9px mc.font path overlapped every line and merged the
        // padded background quads into one slab. 1.5 keeps the gap ratio the world-space label had.
        float lineSpacing = (float) (TextRenderer.get().getHeight() * 1.5);

        for (LivingEntity w : wardens) {
            if (!(w instanceof Warden warden)) continue;

            try {
                Vec3 lerpedPos = w.getPosition(event.tickDelta);
                AABB lerped = w.getBoundingBox().move(lerpedPos.subtract(w.position()));

                int anger = warden.getClientAngerLevel();
                WardenState state = classifyWarden(warden, anger, AngerLevel.byAnger(anger));

                long expiry = attackExpiry.getOrDefault(w.getId(), 0L);
                boolean attackFlash = warden.tickCount <= expiry
                    && !state.digging() && !state.emerging();
                SettingColor color = attackFlash
                    ? getFastPulseColor(wardenLockedColor.get(), 0.35F)
                    : state.baseColor();

                float baseOffset = (float) (lerped.getYsize() / 2.0 + 0.55
                    + 0.25 * (labelScaleValue - 1.0));
                int line = 0;

                if (attackFlash && showImminentIndicator.get()) {
                    shared.drawWorldLabel(event.graphics, "! ATTACK",
                        lerped.getCenter().x, lerped.getCenter().y + baseOffset,
                        lerped.getCenter().z, wardenLockedColor.get(),
                        1.15F * labelScaleValue, -lineSpacing * line);
                    line += 1;
                }

                shared.drawWorldLabel(event.graphics, state.label(), lerped.getCenter().x,
                    lerped.getCenter().y + baseOffset, lerped.getCenter().z,
                    color, labelScaleValue, -lineSpacing * line);
                line += 1;

                // Target label keys off hasTarget(), not lockedOnYou/lockedOnOther: those require
                // warden.getTarget() to resolve to a live entity reference, which it does on the
                // server but is unreliable client-side. hasTarget is the same signal with one less
                // identity comparison that can silently fail, so the label can actually appear.
                if (state.hasTarget() && showWardenTargetLabel.get()) {
                    String tgt = targetLabel(state);
                    SettingColor tc = state.lockedOnYou()
                        ? (attackFlash ? getFastPulseColor(wardenLockedColor.get(), 0.35F)
                           : getPulseColor(wardenLockedColor.get(), 0.6F))
                        : wardenOtherTargetColor.get();
                    shared.drawWorldLabel(event.graphics, tgt, lerped.getCenter().x,
                        lerped.getCenter().y + baseOffset, lerped.getCenter().z,
                        tc, 0.95F * labelScaleValue, -lineSpacing * line);
                    line += 1;
                }

                if (state.sniffing() && showSniffingPulse.get()) {
                    shared.drawWorldLabel(event.graphics, "SNIFFING",
                        lerped.getCenter().x, lerped.getCenter().y + baseOffset,
                        lerped.getCenter().z, new SettingColor(255, 255, 170),
                        0.9F * labelScaleValue, -lineSpacing * line);
                    line += 1;
                }

                if (state.digging() || state.emerging()) {
                    String label = state.digging() ? "DIGGING" : "EMERGING";
                    shared.drawWorldLabel(event.graphics, label, lerped.getCenter().x,
                        lerped.getCenter().y + baseOffset, lerped.getCenter().z,
                        wardenDiggingColor.get(), 0.9F * labelScaleValue,
                        -lineSpacing * line);
                }
            } catch (Throwable t) {
                // ignore per-entity errors
            }
        }
    }

    /**
     * Names the entity the warden is targeting.
     * <p>
     * Only possible when getTarget() actually resolves a live reference. On vanilla it effectively
     * never does (brain memory is not synced), so the honest answer is UNKNOWN rather than a guess -
     * the ANGRY state label already communicates that something has its attention.
     */
    private String targetLabel(WardenState state) {
        if (state.lockedOnYou()) return "TARGET: YOU";

        LivingEntity target = state.target();
        if (target == null) return "TARGET: UNKNOWN";

        var name = target.getName();
        String who = name == null ? "" : name.getString();
        if (who.isBlank()) who = target.getType().getDescription().getString();
        return "TARGET: " + (who.isBlank() ? "UNKNOWN" : who);
    }

    private boolean isAnimationPlaying(AnimationState state) {
        return state.isStarted();
    }

    /**
     * Octahedron inscribed in the given box: top/bottom apexes plus four equatorial points.
     * Faces are drawn with Renderer3D#quad and edges with Renderer3D#line, honouring ShapeMode
     * exactly like Renderer3D#box does, so fill-warden-shapes and line colour still apply.
     */
    private void renderOctahedron(Renderer3D r, AABB box, SettingColor sideColor, SettingColor lineColor,
                                  meteordevelopment.meteorclient.renderer.ShapeMode mode) {
        double cx = (box.minX + box.maxX) / 2.0;
        double cy = (box.minY + box.maxY) / 2.0;
        double cz = (box.minZ + box.maxZ) / 2.0;
        double hx = (box.maxX - box.minX) / 2.0;
        double hz = (box.maxZ - box.minZ) / 2.0;

        // Equatorial ring in the XZ plane, counter-clockwise seen from above: +X, +Z, -X, -Z.
        // All four share y = cy (the ring is flat); the apexes are the box's top/bottom centres.
        double[] ex = {cx + hx, cx, cx - hx, cx};
        double[] ey = {cy, cy, cy, cy};
        double[] ez = {cz, cz + hz, cz, cz - hz};

        boolean sides = mode == meteordevelopment.meteorclient.renderer.ShapeMode.Sides
            || mode == meteordevelopment.meteorclient.renderer.ShapeMode.Both;
        boolean lines = mode == meteordevelopment.meteorclient.renderer.ShapeMode.Lines
            || mode == meteordevelopment.meteorclient.renderer.ShapeMode.Both;

        // Top apex fans down to each ring edge (i -> i+1); bottom apex fans up to the same edges.
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            if (sides) {
                // Renderer3D#quad is always 4 points; repeating the last one degenerates it into
                // the triangle we actually want (a,b,c,c).
                r.quad(cx, box.maxY, cz, ex[i], ey[i], ez[i], ex[j], ey[j], ez[j],
                    ex[j], ey[j], ez[j], sideColor);
                r.quad(ex[i], ey[i], ez[i], cx, box.minY, cz, ex[j], ey[j], ez[j],
                    ex[j], ey[j], ez[j], sideColor);
            }
            if (lines) {
                r.line(cx, box.maxY, cz, ex[i], ey[i], ez[i], lineColor);
                r.line(cx, box.maxY, cz, ex[j], ey[j], ez[j], lineColor);
                r.line(cx, box.minY, cz, ex[i], ey[i], ez[i], lineColor);
                r.line(cx, box.minY, cz, ex[j], ey[j], ez[j], lineColor);
            }
        }
        if (lines) {
            // Equatorial ring.
            for (int i = 0; i < 4; i++) {
                int j = (i + 1) % 4;
                r.line(ex[i], ey[i], ez[i], ex[j], ey[j], ez[j], lineColor);
            }
        }
    }

    private WardenState classifyWarden(Warden warden, int anger, AngerLevel angriness) {
        boolean sniffing = warden.getPose() == Pose.SNIFFING;
        boolean digging = warden.getPose() == Pose.DIGGING;
        boolean emerging = warden.getPose() == Pose.EMERGING;

        // Warden#getTarget() delegates to getTargetFromBrain(), i.e. Brain.getMemory(ATTACK_TARGET).
        // verified on 26.2: Mob's only EntityDataAccessor is DATA_MOB_FLAGS_ID, Warden adds
        // only CLIENT_ANGER_LEVEL, and net.minecraft.network.protocol.game contains no brain-memory
        // packet at all. The target is therefore genuinely unknowable client-side. An earlier
        // version inferred "it's you" from anger + proximity, which confidently named YOU while the
        // warden was actually hitting an iron golem -> a wrong answer is worse than none, so that
        // inference is gone. What we can see is the synced anger total, which at least says
        // "something is aggroed", never who.
        LivingEntity resolved = warden.getTarget();
        boolean hasTarget = resolved != null || angriness.isAngry();
        boolean lockedOnYou = resolved != null && resolved == mc.player;

        boolean locked = hasTarget || angriness.isAngry();
        boolean agitated = sniffing || angriness == AngerLevel.AGITATED;

        SettingColor color;
        String label;
        // Ordered hottest-first, and every branch that is not calm/agitated resets the label
        // explicitly: anger falls back to CALM on its own once the client anger level drains to
        // 0, so the text can never get stuck on a stale state.
        if (digging || emerging) {
            color = wardenDiggingColor.get();
            label = digging ? "DIGGING" : "EMERGING";
        } else if (locked) {
            color = wardenLockedColor.get();
            label = "ANGRY";
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
            lockedOnYou, hasTarget, resolved);
    }

    /**
     * Anger trend arrow.
     * <p>
     * Previously gated on a 1.5s expiry that only got refreshed on a change, so the arrow blinked
     * out mid-climb and was invisible for the rest of the time the warden was actually angry,
     * which is the only time it matters. It now stays up for as long as there is anger to show,
     * and the 1.5s window is kept only as a short grace after anger returns to zero.
     */
    private void renderAngerTrend(Render3DEvent event, int id, int anger, Integer prev,
                                  long nowTick, AABB lerped) {
        if (prev != null) {
            int delta = anger - prev;
            if (delta != 0) {
                angerTrendUp.put(id, delta > 0);
                angerTrendExpiry.put(id, nowTick + ANGER_TREND_HOLD_TICKS);
            }
        }

        boolean show = anger > 0;
        if (!show) {
            Long trendExpiry = angerTrendExpiry.get(id);
            show = trendExpiry != null && nowTick <= trendExpiry;
        }
        if (!show) return;

        boolean up = angerTrendUp.getOrDefault(id, true);
        SettingColor col = up ? wardenSearchingColor.get() : wardenCalmColor.get();

        Vec3 base = lerped.getCenter().add(0, lerped.getYsize() / 2.0 + 0.1, 0);
        Vec3 tip = base.add(0, up ? 1.0 : -1.0, 0);
        event.renderer.line(base.x, base.y, base.z, tip.x, tip.y, tip.z, col);

        double arm = 0.25, armY = up ? -arm : arm;
        Vec3 armA = tip.add(-arm, armY, 0);
        Vec3 armB = tip.add(arm, armY, 0);
        event.renderer.line(tip.x, tip.y, tip.z, armA.x, armA.y, armA.z, col);
        event.renderer.line(tip.x, tip.y, tip.z, armB.x, armB.y, armB.z, col);
    }

    private static SettingColor flashColor(SettingColor color) {
        return BlockRadiusShared.flashColor(color);
    }

    private static SettingColor getPulseColor(SettingColor base, float minBrightness) {
        return BlockRadiusShared.getPulseColor(base, minBrightness);
    }

    private static SettingColor getFastPulseColor(SettingColor base, float minBrightness) {
        return BlockRadiusShared.getFastPulseColor(base, minBrightness);
    }

    private static float clamp01(float v) {
        return BlockRadiusShared.clamp01(v);
    }

    // Absolute shitbox
    public enum WardenShape {
        None, Box, Octahedron
    }

    public enum WardenTracerMode {
        Off, Targeting, Always
    }
}
