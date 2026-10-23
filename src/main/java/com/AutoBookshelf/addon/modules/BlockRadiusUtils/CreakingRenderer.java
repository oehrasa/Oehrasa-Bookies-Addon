package com.AutoBookshelf.addon.modules.BlockRadiusUtils;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.CreakingEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.awt.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.AutoBookshelf.addon.modules.BlockRadiusUtils.BlockRadiusShared.RangeBox;
import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * CreakingEntity ESP: one box on each linked creaking plus its home heart, in a per-mob colour derived
 * from the golden angle so any number of mobs stay visually distinct.
 */
public class CreakingRenderer {
    private final BlockRadiusShared shared;

    private final Setting<Boolean> showCreakingEntitys;
    private final Setting<Boolean> showCreakingEntityConnection;
    private final Setting<Integer> creakingAlpha;
    private final Setting<Double> creakingSaturation;
    private final Setting<Double> creakingBrightness;

    private static class CreakingEntityLink {
        final CreakingEntity entity;
        final RangeBox heartBox; // heart position never moves, so this is built exactly once
        final Vec3d heartCenter; // cached from heartBox,
        final SettingColor sideColor;
        final SettingColor lineColor;

        CreakingEntityLink(CreakingEntity entity, BlockPos heartPos, SettingColor sideColor, SettingColor lineColor) {
            this.entity = entity;
            this.heartBox = new RangeBox(
                heartPos.getX(), heartPos.getY(), heartPos.getZ(),
                heartPos.getX() + 1.0, heartPos.getY() + 1.0, heartPos.getZ() + 1.0
            );
            this.heartCenter = new Vec3d(
                heartPos.getX() + 0.5, heartPos.getY() + 0.5, heartPos.getZ() + 0.5
            );
            this.sideColor = sideColor;
            this.lineColor = lineColor;
        }
    }

    // Hue step between successive creaking colours; the golden angle maximizes hue
    // separation for any number of mobs without needing a fixed palette.
    private static final double GOLDEN_ANGLE = 137.50776;

    private final Map<Integer, CreakingEntityLink> links = new HashMap<>();
    private int colorCounter = 0;

    public CreakingRenderer(SettingGroup sgCreakingEntity, BlockRadiusShared shared) {
        this.shared = shared;

        showCreakingEntitys = sgCreakingEntity.add(new BoolSetting.Builder()
            .name("show-creakings")
            .description("Highlights linked creakings and their creaking heart in matching per-mob colors.")
            .defaultValue(true)
            .build()
        );

        showCreakingEntityConnection = sgCreakingEntity.add(new BoolSetting.Builder()
            .name("show-creaking-connection")
            .description("Draws a line from each creaking to its linked heart block.")
            .defaultValue(true)
            .visible(showCreakingEntitys::get)
            .build()
        );

        creakingAlpha = sgCreakingEntity.add(new IntSetting.Builder()
            .name("creaking-alpha")
            .description("Side fill alpha for creaking/heart ESP boxes.")
            .defaultValue(60)
            .range(0, 255)
            .visible(showCreakingEntitys::get)
            .build()
        );

        creakingSaturation = sgCreakingEntity.add(new DoubleSetting.Builder()
            .name("creaking-color-saturation")
            .description("Saturation of the auto-generated per-creaking colors.")
            .defaultValue(0.85)
            .range(0, 1)
            .visible(showCreakingEntitys::get)
            .build()
        );

        creakingBrightness = sgCreakingEntity.add(new DoubleSetting.Builder()
            .name("creaking-color-brightness")
            .description("Brightness of the auto-generated per-creaking colors.")
            .defaultValue(1.0)
            .range(0, 1)
            .visible(showCreakingEntitys::get)
            .build()
        );
    }

    public void scan() {
        if (!showCreakingEntitys.get()) {
            links.clear();
            return;
        }

        Set<Integer> seen = new HashSet<>();

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof CreakingEntity creaking)) continue;

            BlockPos heartPos = creaking.getHomePos();
            if (heartPos == null) continue;

            seen.add(entity.getId());

            // computeIfAbsent: colour + heart box are built exactly once per creaking,
            // never rebuilt while it's alive.
            links.computeIfAbsent(entity.getId(), id -> {
                float hue = (float) ((colorCounter++ * GOLDEN_ANGLE) % 360.0) / 360f;
                int rgb = Color.HSBtoRGB(hue, creakingSaturation.get().floatValue(),
                    creakingBrightness.get().floatValue());
                Color c = new Color(rgb);
                return new CreakingEntityLink(
                    creaking, heartPos,
                    new SettingColor(c.getRed(), c.getGreen(), c.getBlue(), creakingAlpha.get()),
                    new SettingColor(c.getRed(), c.getGreen(), c.getBlue(), 255)
                );
            });
        }

        // O(tracked creakings) clean-up, not a full rebuild.
        links.keySet().removeIf(id -> !seen.contains(id));
    }

    public void render(Render3DEvent event, Vec3d cam, double maxDistSq) {
        if (!showCreakingEntitys.get()) return;

        for (CreakingEntityLink link : links.values()) {
            if (link.entity.isRemoved()) continue;

            Box box = link.entity.getBoundingBox();

            // Meteor ESP's tick interpolation: getBoundingBox() is the box as of the last tick,
            // so it is offset to where the entity is actually drawn this frame. The cull below
            // deliberately uses the *same* offset box as the draw - otherwise a link could be
            // culled at maxDistSq while the box that would have been drawn was still on screen.
            double dx = MathHelper.lerp(event.tickDelta, link.entity.lastRenderX, link.entity.getX()) - link.entity.getX();
            double dy = MathHelper.lerp(event.tickDelta, link.entity.lastRenderY, link.entity.getY()) - link.entity.getY();
            double dz = MathHelper.lerp(event.tickDelta, link.entity.lastRenderZ, link.entity.getZ()) - link.entity.getZ();
            Box lerped = box.offset(dx, dy, dz);

            boolean entityVisible = cam.squaredDistanceTo(lerped.getCenter()) <= maxDistSq;
            boolean heartVisible = link.heartBox.distanceSq(cam) <= maxDistSq;

            if (entityVisible) {
                shared.renderBox(event.renderer,
                    lerped.minX, lerped.minY, lerped.minZ,
                    lerped.maxX, lerped.maxY, lerped.maxZ,
                    link.sideColor, link.lineColor);
            }

            if (heartVisible) {
                shared.renderBox(event.renderer,
                    link.heartBox.minX, link.heartBox.minY, link.heartBox.minZ,
                    link.heartBox.maxX, link.heartBox.maxY, link.heartBox.maxZ,
                    link.sideColor, link.lineColor);
            }

            if (showCreakingEntityConnection.get() && (entityVisible || heartVisible)) {
                Vec3d entityCenter = lerped.getCenter();
                event.renderer.line(
                    entityCenter.x, entityCenter.y, entityCenter.z,
                    link.heartCenter.x, link.heartCenter.y, link.heartCenter.z,
                    link.lineColor);
            }
        }
    }

    public void clear() {
        links.clear();
        colorCounter = 0;
    }
}
