/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

public class ItemDespawn extends Module {
    private static final int VANILLA_LIFETIME = 6000;

    // Sentinel age values set by ItemEntity#setUnlimitedLifetime() / #setExtendedLifetime().
    // See ItemEntity.tick(): "if (this.age != -32768) this.age++;" and the discard check
    // "this.age >= 6000". An age of -32768 means the item never increments and never
    // despawns; extended-lifetime items start at -6000 (get an extra 6000 ticks).
    // UNLIMITED_LIFETIME_AGE items are skipped rather than rendered near-infinite life.
    private static final int UNLIMITED_LIFETIME_AGE = -32768;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> warnThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("warn-threshold")
        .description("Ticks remaining before despawn at which to start highlighting (vanilla despawn is fixed at 6000; extended-lifetime items can reach 12000).")
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
        (a, b) -> Double.compare(distSqOf(b), distSqOf(a)));

    /**
     * Per-frame distance cache for the heap comparator: comparing spent one distanceToSqr
     * per operand per comparison (~N·log N per frame). Filled while the heap is built.
     */
    private final java.util.IdentityHashMap<ItemEntity, Double> distSqScratch = new java.util.IdentityHashMap<>();

    private double distSqOf(ItemEntity item) {
        Double cached = this.distSqScratch.get(item);
        return cached != null ? cached : mc.player.distanceToSqr(item);
    }

    private static final int SCAN_INTERVAL = 20; // frames between world item rescans
    private final List<ItemEntity> itemCache = new ArrayList<>();
    private int frameCounter = SCAN_INTERVAL;

    public ItemDespawn() {
        super(Addon.CATEGORY2, "Item-Despawn", "Highlights items that are about to despawn (item age is client-tracked, so items that predate this client appear younger than they are).");
    }

    /**
     * Red→green ramp for the warn window: 1.0 while far from despawn (green), 0.0 right before
     * (red). Computed straight from the clamped fraction each frame; allocations here are one
     * or two Color objects per item per frame, negligible next to the box the renderer emits.
     */
    private static Color despawnColor(double percent, int opacity) {
        double p = Math.clamp(percent, 0.0, 1.0);
        return new Color((int) (255 * (1.0 - p)), (int) (255 * p), 0, opacity);
    }

    @Override
    public void onDeactivate() {
        closestHeap.clear();
        distSqScratch.clear();
        itemCache.clear();
        frameCounter = SCAN_INTERVAL;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.level == null || mc.player == null) return;

        if (++frameCounter >= SCAN_INTERVAL) {
            frameCounter = 0;
            refreshItemCache();
        }

        int max = maxRender.get();
        double rangeSq = (double) renderRange.get() * renderRange.get();
        int warn = warnThreshold.get();

        boolean useHeap = max > 0 && closestFirst.get();

        if (useHeap) {
            closestHeap.clear();
            distSqScratch.clear();

            for (ItemEntity item : itemCache) {
                double distSq = mc.player.distanceToSqr(item);
                if (!isRenderableItem(item, rangeSq, warn, distSq)) continue;
                distSqScratch.put(item, distSq);
                closestHeap.offer(item);
                if (closestHeap.size() > max) {
                    ItemEntity farthest = closestHeap.poll(); // discard farthest
                    distSqScratch.remove(farthest);
                }
            }

            for (ItemEntity item : closestHeap) {
                renderItem(event, item, warn);
            }
        } else {
            // No bound, or arbitrary-order truncation requested: single pass over the cached list.
            int rendered = 0;

            for (ItemEntity item : itemCache) {
                double distSq = mc.player.distanceToSqr(item);
                if (!isRenderableItem(item, rangeSq, warn, distSq)) continue;

                renderItem(event, item, warn);

                if (max > 0 && ++rendered >= max) break;
            }
        }
    }

    // Refreshes the cached candidate item list from the world; on non-refresh frames
    // rendering keeps using this list and only re-checks each cached entity cheaply.
    private void refreshItemCache() {
        itemCache.clear();
        double rangeSq = (double) renderRange.get() * renderRange.get();
        int warn = warnThreshold.get();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof ItemEntity item)) continue;
            if (!isRenderableItem(item, rangeSq, warn)) continue;
            itemCache.add(item);
        }
    }

    private boolean isRenderableItem(ItemEntity item, double rangeSq, int warn) {
        return isRenderableItem(item, rangeSq, warn, mc.player.distanceToSqr(item));
    }

    private boolean isRenderableItem(ItemEntity item, double rangeSq, int warn, double distSq) {
        if (item.isRemoved()) return false;
        if (distSq > rangeSq) return false;

        int age = item.getAge();
        if (age == UNLIMITED_LIFETIME_AGE) return false;

        int timeLeft = VANILLA_LIFETIME - age;
        return timeLeft > 0 && timeLeft <= warn;
    }

    private void renderItem(Render3DEvent event, ItemEntity item, int warn) {
        int timeLeft = VANILLA_LIFETIME - item.getAge();

        // Both branches allocate at most two Color objects per item per frame; negligible
        // next to the box the renderer emits.
        Color sideColor;
        Color lineColor;
        if (computeColorFromTime.get()) {
            double percent = (double) timeLeft / warn;
            sideColor = despawnColor(percent, sideOpacity.get());
            lineColor = despawnColor(percent, lineOpacity.get());
        } else {
            Color color = customColor.get();
            sideColor = new Color(color.r, color.g, color.b, sideOpacity.get());
            lineColor = new Color(color.r, color.g, color.b, lineOpacity.get());
        }

        // Meteor ESP's tick interpolation: getBoundingBox() is the box as of the last tick, so
        // offset it to where the item is drawn this frame. Dropped items drift and fall, so
        // this is visible on them.
        AABB box = item.getBoundingBox();
        double x = Mth.lerp(event.tickDelta, item.xOld, item.getX()) - item.getX();
        double y = Mth.lerp(event.tickDelta, item.yOld, item.getY()) - item.getY();
        double z = Mth.lerp(event.tickDelta, item.zOld, item.getZ()) - item.getZ();
        event.renderer.box(
            x + box.minX, y + box.minY, z + box.minZ,
            x + box.maxX, y + box.maxY, z + box.maxZ,
            sideColor, lineColor, shapeMode.get(), 0);
    }
}
