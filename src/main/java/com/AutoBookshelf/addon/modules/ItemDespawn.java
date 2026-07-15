package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;

public class ItemDespawn extends Module {
    private static final int VANILLA_LIFETIME = 6000;

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
    private java.util.PriorityQueue<ItemEntity> closestHeap;

    public ItemDespawn() {
        super(Addon.CATEGORY, "Item-Despawn", "Highlights items that are about to despawn.");
    }

    @Override
    public void onDeactivate() {
        if (closestHeap != null) closestHeap.clear();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.world == null || mc.player == null) return;

        int max = maxRender.get();
        double rangeSq = (double) renderRange.get() * renderRange.get();
        int warn = warnThreshold.get();

        boolean useHeap = max > 0 && closestFirst.get();

        if (useHeap) {
            if (closestHeap == null) {
                closestHeap = new java.util.PriorityQueue<>(max + 1,
                    (a, b) -> Double.compare(mc.player.squaredDistanceTo(b), mc.player.squaredDistanceTo(a)));
            } else {
                closestHeap.clear();
            }

            for (Entity entity : mc.world.getEntities()) {
                if (!(entity instanceof ItemEntity item)) continue;
                double distSq = mc.player.squaredDistanceTo(entity);
                if (distSq > rangeSq) continue;

                int age = item.getItemAge();
                if (age == UNLIMITED_LIFETIME_AGE) continue;

                int timeLeft = VANILLA_LIFETIME - age;
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

            for (Entity entity : mc.world.getEntities()) {
                if (!(entity instanceof ItemEntity item)) continue;
                if (mc.player.squaredDistanceTo(entity) > rangeSq) continue;

                int age = item.getItemAge();
                if (age == UNLIMITED_LIFETIME_AGE) continue;

                int timeLeft = VANILLA_LIFETIME - age;
                if (timeLeft <= 0 || timeLeft > warn) continue;

                renderItem(event, item, warn);

                if (max > 0 && ++rendered >= max) break;
            }
        }
    }

    private void renderItem(Render3DEvent event, ItemEntity item, int warn) {
        int timeLeft = VANILLA_LIFETIME - item.getItemAge();

        Color color = computeColorFromTime.get()
            ? despawnColor(timeLeft, warn)
            : customColor.get();

        Color sideColor = new Color(color.r, color.g, color.b, sideOpacity.get());
        Color lineColor = new Color(color.r, color.g, color.b, lineOpacity.get());

        event.renderer.box(item.getBoundingBox(), sideColor, lineColor, shapeMode.get(), 0);
    }

    private Color despawnColor(int timeLeft, int warnWindow) {
        double percent = (double) timeLeft / warnWindow;
        percent = Math.clamp(percent, 0.0, 1.0);

        int r = (int) (255 * (1.0 - percent));
        int g = (int) (255 * percent);
        return new Color(r, g, 0);
    }
}
