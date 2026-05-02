package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.Box;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ItemDespawn extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> despawnTime = sgGeneral.add(new IntSetting.Builder()
        .name("despawn-time")
        .description("Total despawn time in ticks (6000 ticks = 5 minutes).")
        .defaultValue(6000)
        .min(100)
        .max(36000)
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

    // Render settings
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
        .name("side-opacity.")
        .description("Opacity of the box sides (0-255).")
        .defaultValue(75)
        .min(0)
        .max(255)
        .sliderRange(0, 255)
        .build()
    );

    private final Setting<Boolean> trackItems = sgGeneral.add(new BoolSetting.Builder()
        .name("track-items")
        .description("Store entity IDs of all item entities while the module is active (cleared on disable).")
        .defaultValue(false)
        .build()
    );

    private final ConcurrentHashMap<Integer, Integer> itemAges = new ConcurrentHashMap<>();
    private final Set<Integer> trackedIds = new HashSet<>();   // stored entity IDs

    public ItemDespawn() {
        super(Addon.CATEGORY, "Item-Despawn", "Highlights items that are about to despawn.");
    }

    @Override
    public void onActivate() {
        itemAges.clear();
        trackedIds.clear();
    }

    @Override
    public void onDeactivate() {
        itemAges.clear();
        trackedIds.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null) return;

        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof ItemEntity item) {
                int id = entity.getId();
                itemAges.put(id, item.age);

                // Track all item IDs when enabled
                if (trackItems.get()) {
                    trackedIds.add(id);
                }
            }
        }
        itemAges.keySet().removeIf(id -> mc.world.getEntityById(id) == null);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.world == null || mc.player == null) return;

        int rendered = 0;
        int max = maxRender.get();
        double rangeSq = renderRange.get() * renderRange.get();

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity)) continue;

            if (mc.player.squaredDistanceTo(entity) > rangeSq) continue;

            Integer age = itemAges.get(entity.getId());
            if (age == null) continue;

            int timeLeft = despawnTime.get() - age;
            if (timeLeft <= 0) continue;

            Color color;
            if (computeColorFromTime.get()) {
                color = despawnColor(timeLeft, despawnTime.get());
            } else {
                color = customColor.get();
            }

            Color sideColor = new Color(color.r, color.g, color.b, sideOpacity.get());
            Color lineColor = new Color(color.r, color.g, color.b, lineOpacity.get());

            Box box = entity.getBoundingBox();
            event.renderer.box(box, sideColor, lineColor, shapeMode.get(), 0);

            rendered++;
            if (max > 0 && rendered >= max) break;
        }
    }

    private Color despawnColor(int timeLeft, int totalTime) {
        double percent = (double) timeLeft / totalTime;
        percent = Math.clamp(percent, 0.0, 1.0);

        int r = (int) (255 * (1.0 - percent));
        int g = (int) (255 * percent);
        return new Color(r, g, 0);
    }

    public Set<Integer> getTrackedIds() {
        return trackedIds;
    }
}
