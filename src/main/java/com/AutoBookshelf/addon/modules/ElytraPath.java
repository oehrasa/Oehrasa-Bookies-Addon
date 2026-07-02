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

    public ElytraPath() {
        super(Addon.CATEGORY, "Elytra-Path",
            "Shows your elytra flight path to destination with smooth movement. Better luck next time, Pilots.");
    }

    @Override
    public void onActivate() {
        smoothedVelocity = Vec3d.ZERO;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;
        if (thirdPersonOnly.get() && mc.options.getPerspective().isFirstPerson()) return;

        renderPlayerPath(event, mc.player);

        if (renderOtherPlayers.get()) {
            for (PlayerEntity player : mc.world.getPlayers()) {
                if (player == mc.player) continue;
                renderPlayerPath(event, player);
            }
        }
    }

    private void renderPlayerPath(Render3DEvent event, PlayerEntity player) {
        ItemStack chest = player.getEquippedStack(EquipmentSlot.CHEST);

        if (chest.getItem() != Items.ELYTRA || !player.isGliding()) {
            if (player == mc.player) smoothedVelocity = Vec3d.ZERO;
            return;
        }

        Vec3d startPos = computeStartPos(player);
        Vec3d rawVel = player.getVelocity();
        Vec3d hDir = computeHorizontalDirection(player, rawVel);

        // Main horizontal path (single pass: compute and render together)
        drawPath(event, startPos, hDir, false);

        // Vertical indicator
        if (showVerticalIndicators.get() && Math.abs(rawVel.y) > 0.02) {
            Vec3d vertDir = new Vec3d(0, rawVel.y, 0);
            SettingColor c = rawVel.y > 0 ? ascendColor.get() : descendColor.get();
            drawPath(event, startPos, vertDir, true, c);
        }
    }

    /**
     * Draws the horizontal path using the current colorMode.
     */
    private void drawPath(Render3DEvent event, Vec3d start, Vec3d step, boolean isVertical) {
        drawPath(event, start, step, isVertical, null);
    }

    private void drawPath(Render3DEvent event, Vec3d start, Vec3d step,
                          boolean isVertical, SettingColor overrideColor) {
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
                    renderSegment(event, prevPos, hit.getPos(),
                        segmentColor(overrideColor, mode, solid, fade, gStart, gEnd, i, maxT));
                    impactHit = hit;
                    break;
                }
            }

            renderSegment(event, prevPos, nextPos,
                segmentColor(overrideColor, mode, solid, fade, gStart, gEnd, i, maxT));

            prevPos = nextPos;
        }

        if (renderImpactBox.get() && impactHit != null) {
            BlockPos bp = impactHit.getBlockPos();
            event.renderer.box(bp, impactBoxColor.get(), impactBoxColor.get(), impactBoxShape.get(), 0);
        }
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
            case Solid -> {
                return solid;
            }

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

    private void renderSegment(Render3DEvent event, Vec3d p1, Vec3d p2, SettingColor color) {
        event.renderer.line(p1.x, p1.y, p1.z, p2.x, p2.y, p2.z, color);
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
