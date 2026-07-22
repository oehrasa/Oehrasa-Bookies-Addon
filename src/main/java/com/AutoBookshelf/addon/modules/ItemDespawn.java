package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;

public class ItemDespawn extends Module {
    private static final int VANILLA_LIFETIME = 6000;
    private static final int EXTENDED_LIFETIME = VANILLA_LIFETIME + 6000; // extended items get +6000 ticks

    // Sentinel age values set by ItemEntity#setUnlimitedLifetime() / #setExtendedLifetime().
    // See ItemEntity.tick(): "if (this.age != -32768) this.age++;" and the discard check
    // "this.age >= 6000". An age of -32768 means the item never increments and never
    // despawns; extended-lifetime items start at -6000 (get an extra 6000 ticks).
    private static final int UNLIMITED_LIFETIME_AGE = -32768;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> warnThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("warn-threshold")
        .description("Ticks remaining before despawn at which to start highlighting (vanilla despawn is fixed at 6000 total ticks).")
        .defaultValue(6000)
        .min(100)
        .max(12000) // extended-lifetime items can have up to 12000 effective ticks
        .build()
    );

    private final Setting<Boolean> computeColorFromTime = sgGeneral.add(new BoolSetting.Builder()
        .name("compute-color-from-time")
        .description("Smoothly transition color based on remaining time.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> renderRange = sgGeneral.add(new IntSetting.Builder()
        .name("render-range")
        .description("How far away to render despawn indicators.")
        .defaultValue(32)
        .min(8)
        .max(128)
        .sliderRange(8, 128)
        .build()
    );

    private final Setting<Integer> maxRender = sgGeneral.add(new IntSetting.Builder()
        .name("max-render")
        .description("Maximum number of items to render (0 = unlimited).")
        .defaultValue(50)
        .min(0)
        .max(200)
        .sliderRange(0, 200)
        .build()
    );

    private final Setting<Boolean> closestFirst = sgGeneral.add(new BoolSetting.Builder()
        .name("closest-first")
        .description("When max-render is exceeded, prioritize the closest items instead of an arbitrary order.")
        .defaultValue(true)
        .visible(() -> maxRender.get() > 0)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the items are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> customColor = sgRender.add(new ColorSetting.Builder()
        .name("custom-color")
        .description("Color when compute-color-from-time is disabled.")
        .defaultValue(new SettingColor(255, 0, 0, 150))
        .visible(() -> !computeColorFromTime.get())
        .build()
    );

    private final Setting<Integer> lineOpacity = sgRender.add(new IntSetting.Builder()
        .name("line-opacity")
        .description("Opacity of the box lines (0-255).")
        .defaultValue(255)
        .min(0)
        .max(255)
        .sliderRange(0, 255)
        .build()
    );

    private final Setting<Integer> sideOpacity = sgRender.add(new IntSetting.Builder()
        .name("side-opacity")
        .description("Opacity of the box sides (0-255).")
        .defaultValue(75)
        .min(0)
        .max(255)
        .sliderRange(0, 255)
        .build()
    );

    // Bounded max-heap keyed by squared distance (farthest at the top). Used to keep only
    // the closest `max` candidates without sorting the full candidate set.
    private final java.util.PriorityQueue<ItemEntity> closestHeap = new java.util.PriorityQueue<>(11,
        (a, b) -> Double.compare(mc.player.distanceToSqr(b), mc.player.distanceToSqr(a)));

    public ItemDespawn() {
        super(Addon.CATEGORY, "Item-Despawn", "Highlights items that are about to despawn.");
    }

    @Override
    public void onDeactivate() {
        closestHeap.clear();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.level == null || mc.player == null) return;

        int max = maxRender.get();
        double rangeSq = (double) renderRange.get() * renderRange.get();
        int warn = warnThreshold.get();

        boolean useHeap = max > 0 && closestFirst.get();

        if (useHeap) {
            closestHeap.clear();

            for (Entity entity : mc.level.entitiesForRendering()) {
                if (!(entity instanceof ItemEntity item)) continue;
                double distSq = mc.player.distanceToSqr(entity);
                if (distSq > rangeSq) continue;

                int age = item.getAge();
                if (age == UNLIMITED_LIFETIME_AGE) continue;

                int timeLeft = timeLeft(age);
                if (timeLeft <= 0 || timeLeft > warn) continue;

                closestHeap.offer(item);
                if (closestHeap.size() > max) {
                    closestHeap.poll(); // discard farthest
                }
            }

            for (ItemEntity item : closestHeap) {
                renderItem(event, item, warn);
            }
        } else {
            // No bound, or arbitrary-order truncation requested: single pass, cheapest path.
            int rendered = 0;

            for (Entity entity : mc.level.entitiesForRendering()) {
                if (!(entity instanceof ItemEntity item)) continue;
                if (mc.player.distanceToSqr(entity) > rangeSq) continue;

                int age = item.getAge();
                if (age == UNLIMITED_LIFETIME_AGE) continue;

                int timeLeft = timeLeft(age);
                if (timeLeft <= 0 || timeLeft > warn) continue;

                renderItem(event, item, warn);

                if (max > 0 && ++rendered >= max) break;
            }
        }
    }

    private static int timeLeft(int age) {
        return VANILLA_LIFETIME - age;
    }

    private void renderItem(Render3DEvent event, ItemEntity item, int warn) {
        int age = item.getAge();
        int timeLeft = timeLeft(age);

        // Normalize against the item's own max lifetime, not just the raw warn-threshold
        // setting, so extended-lifetime items (age starts at -6000, max timeLeft 12000)
        // don't get squashed into a gradient sized for normal items (max timeLeft 6000),
        // and vice versa when warn-threshold is raised above 6000 to catch extended items.
        boolean extended = age < 0;
        int itemMaxLifetime = extended ? EXTENDED_LIFETIME : VANILLA_LIFETIME;
        int denom = Math.min(warn, itemMaxLifetime);

        Color color = computeColorFromTime.get()
            ? despawnColor(timeLeft, denom)
            : customColor.get();

        Color sideColor = new Color(color.r, color.g, color.b, sideOpacity.get());
        Color lineColor = new Color(color.r, color.g, color.b, lineOpacity.get());

        event.renderer.box(item.getBoundingBox(), sideColor, lineColor, shapeMode.get(), 0);
    }

    private Color despawnColor(int timeLeft, int denom) {
        double percent = (double) timeLeft / denom;
        percent = Math.clamp(percent, 0.0, 1.0);

        // Straight RGB lerp from (0,255,0) to (255,0,0) passes through (127,127,0)
        float hue = (float) (percent * 120.0 / 360.0); // 120/360 = green, 0 = red
        int rgb = java.awt.Color.HSBtoRGB(hue, 1.0f, 1.0f);

        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return new Color(r, g, b);
    }
}
