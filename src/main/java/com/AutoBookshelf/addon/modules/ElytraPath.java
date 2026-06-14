package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
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

    public final Setting<Boolean> thirdPersonOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("third-person-only")
        .description("Only render the overlay when in third person.")
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
    private Vec3 smoothedVelocity = Vec3.ZERO;

    public ElytraPath() {
        super(Addon.CATEGORY, "Elytra-Path",
            "Shows your elytra flight path to destination with smooth movement. better luck next time, Pilots.");
    }

    @Override
    public void onActivate() {
        smoothedVelocity = Vec3.ZERO;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (thirdPersonOnly.get() && mc.options.getCameraType().isFirstPerson()) return;

        renderPlayerPath(event, mc.player);
        if (renderOtherPlayers.get()) {
            for (Player player : mc.level.players()) {
                if (player == mc.player) continue;
                renderPlayerPath(event, player);
            }
        }
    }

    private void renderPlayerPath(Render3DEvent event, Player player) {
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);

        if (chest.getItem() != Items.ELYTRA || !player.isFallFlying()) {
            if (player == mc.player) smoothedVelocity = Vec3.ZERO;
            return;
        }

        Vec3 startPos;

        if (player == mc.player && startFromCrosshair.get()) {
            startPos = new Vec3(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z);
        } else {
            Vec3 eyePos = player.position().add(0, player.getEyeHeight(player.getPose()), 0);
            startPos = eyePos.add(0, startOffset.get(), 0);
        }

        Vec3 rawVel = player.getDeltaMovement();
        Vec3 rawHorizontal = new Vec3(rawVel.x, 0.0, rawVel.z);

        Vec3 direction;

        if (player == mc.player) {
            double smoothFactor = velocitySmoothing.get();

            smoothedVelocity = smoothedVelocity.scale(1.0 - smoothFactor)
                .add(rawHorizontal.scale(smoothFactor));

            if (smoothedVelocity.lengthSqr() > speedThreshold.get() * speedThreshold.get()) {
                direction = smoothedVelocity.normalize().scale(smoothedVelocity.length());
            } else {
                Vec3 forward = player.getViewVector(1.0F).normalize();

                direction = new Vec3(
                    forward.x * idleSpeed.get(),
                    0.0,
                    forward.z * idleSpeed.get()
                );
            }
        } else {
            if (rawHorizontal.lengthSqr() > speedThreshold.get() * speedThreshold.get()) {
                direction = rawHorizontal.normalize().scale(rawHorizontal.length());
            } else {
                Vec3 forward = player.getViewVector(1.0F).normalize();

                direction = new Vec3(
                    forward.x * idleSpeed.get(),
                    0.0,
                    forward.z * idleSpeed.get()
                );
            }
        }

        List<Vec3> path = new ArrayList<>();
        path.add(startPos);

        Vec3 currentPos = startPos;
        BlockHitResult finalHit = null;

        for (int i = 0; i < predictionTicks.get(); i++) {
            Vec3 nextPos = currentPos.add(direction);

            if (stopAtBlock.get()) {
                BlockHitResult hit = raytraceBlock(currentPos, nextPos);

                if (hit != null) {
                    path.add(hit.getLocation());
                    finalHit = hit;
                    break;
                }
            }

            path.add(nextPos);
            currentPos = nextPos;
        }

        int segments = path.size() - 1;

        for (int i = 0; i < segments; i++) {
            Vec3 p1 = path.get(i);
            Vec3 p2 = path.get(i + 1);

            SettingColor color;

            switch (colorMode.get()) {
                case Solid -> color = lineColor.get();

                case Fade -> {
                    float progress = (float) i / (float) segments;

                    int alpha = (int) (lineColor.get().a * (1.0 - progress));

                    color = new SettingColor(
                        lineColor.get().r,
                        lineColor.get().g,
                        lineColor.get().b,
                        Math.max(alpha, 0)
                    );
                }

                case Gradient -> {
                    float t = (float) i / (float) segments;

                    SettingColor start = gradientStart.get();
                    SettingColor end = gradientEnd.get();

                    color = new SettingColor(
                        (int) (start.r + t * (end.r - start.r)),
                        (int) (start.g + t * (end.g - start.g)),
                        (int) (start.b + t * (end.b - start.b)),
                        (int) (start.a + t * (end.a - start.a))
                    );
                }

                default -> color = lineColor.get();
            }

            event.renderer.line(
                p1.x, p1.y, p1.z,
                p2.x, p2.y, p2.z,
                color
            );
        }

        if (renderImpactBox.get() && finalHit != null) {
            BlockPos bp = finalHit.getBlockPos();

            event.renderer.box(
                bp,
                impactBoxColor.get(),
                impactBoxColor.get(),
                impactBoxShape.get(),
                0
            );
        }
        // Vertical ascent/descent indicator
        if (showVerticalIndicators.get()) {
            double vy = rawVel.y;

            if (Math.abs(vy) > 0.02) {
                Vec3 verticalDir = new Vec3(0, vy, 0);

                Vec3 vertCurrentPos = startPos;
                BlockHitResult vertFinalHit = null;

                List<Vec3> vertPath = new ArrayList<>();
                vertPath.add(startPos);

                for (int i = 0; i < predictionTicks.get(); i++) {
                    Vec3 nextPos = vertCurrentPos.add(verticalDir);

                    if (stopAtBlock.get()) {
                        BlockHitResult hit = raytraceBlock(vertCurrentPos, nextPos);

                        if (hit != null) {
                            vertPath.add(hit.getLocation());
                            vertFinalHit = hit;
                            break;
                        }
                    }

                    vertPath.add(nextPos);
                    vertCurrentPos = nextPos;
                }

                SettingColor vertColor = vy > 0
                    ? ascendColor.get()
                    : descendColor.get();

                for (int i = 0; i < vertPath.size() - 1; i++) {
                    Vec3 p1 = vertPath.get(i);
                    Vec3 p2 = vertPath.get(i + 1);

                    event.renderer.line(
                        p1.x, p1.y, p1.z,
                        p2.x, p2.y, p2.z,
                        vertColor
                    );
                }

                if (renderImpactBox.get() && vertFinalHit != null) {
                    BlockPos bp = vertFinalHit.getBlockPos();

                    event.renderer.box(
                        bp,
                        impactBoxColor.get(),
                        impactBoxColor.get(),
                        impactBoxShape.get(),
                        0
                    );
                }
            }
        }
    }

    private BlockHitResult raytraceBlock(Vec3 start, Vec3 end) {
        ClipContext context = new ClipContext(
            start, end,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            mc.player
        );
        BlockHitResult result = mc.level.clip(context);
        return result.getType() == HitResult.Type.BLOCK ? result : null;
    }
}
