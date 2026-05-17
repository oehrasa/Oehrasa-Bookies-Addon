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
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.List;

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
        .sliderMax(0.2).build()
    );

    private final Setting<Boolean> startFromCrosshair = sgGeneral.add(new BoolSetting.Builder()
        .name("start-from-crosshair")
        .description("Start the line from your exact crosshair position.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> startOffset = sgGeneral.add(new DoubleSetting.Builder()
        .name("start-offset")
        .description("Vertical offset below the eye when are not using crosshair.")
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
        .defaultValue(new SettingColor(0, 87, 183, 200))// hours of joy
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
        .defaultValue(new SettingColor(255, 0, 0, 255)).build()
    );

    private final Setting<Boolean> renderImpactBox = sgGeneral.add(new BoolSetting.Builder()
        .name("render-impact-box")
        .description("Draw a box at the block the line would hit.")
        .defaultValue(true).build()
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

    // Smoothed velocity for non‑janky movement
    private Vec3d smoothedVelocity = Vec3d.ZERO;

    public ElytraPath() {
        super(Addon.CATEGORY, "Elytra-Path",
            "Shows your elytra flight path to destination with smooth movement. better luck next time, Pilots.");
    }

    @Override
    public void onActivate() {
        smoothedVelocity = Vec3d.ZERO;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;

        ItemStack chest = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (chest.getItem() != Items.ELYTRA || !mc.player.isGliding()) {
            smoothedVelocity = Vec3d.ZERO;
            return;
        }

        // Starting point: crosshair or eye + offset
        Vec3d startPos;
        if (startFromCrosshair.get()) {
            startPos = new Vec3d(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z);
        } else {
            Vec3d eyePos = mc.player.getPos().add(0, mc.player.getEyeHeight(mc.player.getPose()), 0);
            startPos = eyePos.add(0, startOffset.get(), 0);
        }

        // Smooth the horizontal velocity
        Vec3d rawVel = mc.player.getVelocity();
        Vec3d rawHorizontal = new Vec3d(rawVel.x, 0.0, rawVel.z);
        double smoothFactor = velocitySmoothing.get();
        smoothedVelocity = smoothedVelocity.multiply(1.0 - smoothFactor)
            .add(rawHorizontal.multiply(smoothFactor));

        // Determine the direction to draw (horizontal only)
        Vec3d direction;
        if (smoothedVelocity.lengthSquared() > speedThreshold.get() * speedThreshold.get()) {
            direction = smoothedVelocity.normalize().multiply(smoothedVelocity.length());
        } else {
            Vec3d forward = mc.player.getRotationVec(1.0F).normalize();
            direction = new Vec3d(forward.x * idleSpeed.get(), 0.0, forward.z * idleSpeed.get());
        }

        // Build the forward path (horizontal)
        List<Vec3d> path = new ArrayList<>();
        path.add(startPos);

        Vec3d currentPos = startPos;
        Vec3d currentDir = direction;
        BlockHitResult finalHit = null;

        for (int i = 0; i < predictionTicks.get(); i++) {
            Vec3d nextPos = currentPos.add(currentDir);

            if (stopAtBlock.get()) {
                BlockHitResult hit = raytraceBlock(currentPos, nextPos);
                if (hit != null) {
                    path.add(hit.getPos());
                    finalHit = hit;
                    break;
                }
            }

            path.add(nextPos);
            currentPos = nextPos;
        }

        // Draw the forward path segments
        int segments = path.size() - 1;
        for (int i = 0; i < segments; i++) {
            Vec3d p1 = path.get(i);
            Vec3d p2 = path.get(i + 1);
            SettingColor color;

            switch (colorMode.get()) {
                case Solid -> color = lineColor.get();

                case Fade -> {
                    float progress = (float) i / (float) segments;
                    int alpha = (int) (lineColor.get().a * (1.0 - progress));
                    if (alpha < 0) alpha = 0;
                    color = new SettingColor(lineColor.get().r, lineColor.get().g, lineColor.get().b, alpha);
                }

                case Gradient -> {
                    float t = (float) i / (float) segments;
                    SettingColor start = gradientStart.get();
                    SettingColor end   = gradientEnd.get();

                    int red   = (int) (start.r + t * (end.r - start.r));
                    int green = (int) (start.g + t * (end.g - start.g));
                    int blue  = (int) (start.b + t * (end.b - start.b));
                    int alpha = (int) (start.a + t * (end.a - start.a));

                    red   = Math.clamp(red, 0, 255);
                    green = Math.clamp(green, 0, 255);
                    blue  = Math.clamp(blue, 0, 255);
                    alpha = Math.clamp(alpha, 0, 255);

                    color = new SettingColor(red, green, blue, alpha);
                }

                default -> color = lineColor.get();
            }

            event.renderer.line(p1.x, p1.y, p1.z, p2.x, p2.y, p2.z, color);
        }

        // Impact box for the forward path
        if (renderImpactBox.get() && finalHit != null) {
            BlockPos bp = finalHit.getBlockPos();
            event.renderer.box(bp, impactBoxColor.get(), impactBoxColor.get(), impactBoxShape.get(), 0);
        }

        // Vertical ascent / descent indicator
        if (showVerticalIndicators.get()) {
            double vy = rawVel.y;
            if (Math.abs(vy) > 0.02) {
                Vec3d verticalDir = new Vec3d(0, vy, 0);
                Vec3d vertCurrentPos = startPos;
                BlockHitResult vertFinalHit = null;
                List<Vec3d> vertPath = new ArrayList<>();
                vertPath.add(startPos);

                for (int i = 0; i < predictionTicks.get(); i++) {
                    Vec3d nextPos = vertCurrentPos.add(verticalDir);
                    if (stopAtBlock.get()) {
                        BlockHitResult hit = raytraceBlock(vertCurrentPos, nextPos);
                        if (hit != null) {
                            vertPath.add(hit.getPos());
                            vertFinalHit = hit;
                            break;
                        }
                    }
                    vertPath.add(nextPos);
                    vertCurrentPos = nextPos;
                }

                // Draw the vertical line (solid colour, just like before)
                SettingColor vertColor = vy > 0 ? ascendColor.get() : descendColor.get();
                for (int i = 0; i < vertPath.size() - 1; i++) {
                    Vec3d p1 = vertPath.get(i);
                    Vec3d p2 = vertPath.get(i + 1);
                    event.renderer.line(p1.x, p1.y, p1.z, p2.x, p2.y, p2.z, vertColor);
                }

                // Impact box for the vertical line
                if (renderImpactBox.get() && vertFinalHit != null) {
                    BlockPos bp = vertFinalHit.getBlockPos();
                    event.renderer.box(bp, impactBoxColor.get(), impactBoxColor.get(), impactBoxShape.get(), 0);
                }
            }
        }
    }

    private BlockHitResult raytraceBlock(Vec3d start, Vec3d end) {
        RaycastContext context = new RaycastContext(
            start, end,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            mc.player
        );
        BlockHitResult result = mc.world.raycast(context);
        return result.getType() == HitResult.Type.BLOCK ? result : null;
    }
}
