package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.entity.EntityRemovedEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import java.util.Set;
import java.util.UUID;
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
        .description("Store UUIDs of all item entities while the module is active (cleared on disable).")
        .defaultValue(false)
        .build()
    );

    private static class TrackedItem {
        int totalAge;   // estimated total age in ticks
        long lastTickSeen;  // world time when last present
    }

    private final ConcurrentHashMap<UUID, TrackedItem> trackedItems = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> renderAges = new ConcurrentHashMap<>();

    public ItemDespawn() {
        super(Addon.CATEGORY, "Item-Despawn", "Highlights items that are about to despawn.");
    }

    @Override
    public void onActivate() {
        trackedItems.clear();
        renderAges.clear();
    }

    @Override
    public void onDeactivate() {
        trackedItems.clear();
        renderAges.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.level == null) return;

        long currentTick = mc.level.getGameTime();

        // Update ages for all visible items
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof ItemEntity item)) continue;

            UUID uuid = item.getUUID();
            TrackedItem tracked = trackedItems.computeIfAbsent(uuid, k -> {
                TrackedItem t = new TrackedItem();
                t.totalAge = 0;
                t.lastTickSeen = currentTick;
                return t;
            });

            tracked.lastTickSeen = currentTick;

            // Store the age for rendering
            renderAges.put(uuid, tracked.totalAge);

            // Increase for the next tick
            tracked.totalAge++;
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.level == null || mc.player == null) return;

        int rendered = 0;
        int max = maxRender.get();
        double rangeSq = renderRange.get() * renderRange.get();

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof ItemEntity)) continue;
            if (mc.player.distanceToSqr(entity) > rangeSq) continue;

            Integer age = renderAges.get(entity.getUUID());
            if (age == null) continue;

            int timeLeft = despawnTime.get() - age;
            if (timeLeft <= 0) continue;

            Color color = computeColorFromTime.get()
                ? despawnColor(timeLeft, despawnTime.get())
                : customColor.get();

            Color sideColor = new Color(color.r, color.g, color.b, sideOpacity.get());
            Color lineColor = new Color(color.r, color.g, color.b, lineOpacity.get());

            AABB box = entity.getBoundingBox();
            event.renderer.box(box, sideColor, lineColor, shapeMode.get(), 0);

            rendered++;
            if (max > 0 && rendered >= max) break;
        }
    }

    @EventHandler
    private void onEntityRemoved(EntityRemovedEvent event) {
        if (!(event.entity instanceof ItemEntity item)) return;

        UUID uuid = item.getUUID();
        // Only remove tracking when the item is truly destroyed
        if (item.getRemovalReason() != Entity.RemovalReason.UNLOADED_TO_CHUNK) {
            trackedItems.remove(uuid);
            renderAges.remove(uuid);
        }
        // If unloaded, leave the tracked item in the map so age persists
    }

    private Color despawnColor(int timeLeft, int totalTime) {
        double percent = (double) timeLeft / totalTime;
        percent = Math.clamp(percent, 0.0, 1.0);

        int r = (int) (255 * (1.0 - percent));
        int g = (int) (255 * percent);
        return new Color(r, g, 0);
    }

    // Tracked UUIDs for external use
    public Set<UUID> getTrackedIds() {
        return trackedItems.keySet();
    }
}
