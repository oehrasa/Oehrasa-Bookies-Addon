package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.utils.MathUtils;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;

import java.util.ArrayList;
import java.util.List;

public class MapGridZone extends Module {
    private final SettingGroup sgDefault = settings.getDefaultGroup();

    public final Setting<Boolean> mapZoneRender = sgDefault.add(new BoolSetting.Builder()
        .name("map-zones")
        .description("Render zones of map boundaries.")
        .defaultValue(false)
        .build());

    private final Setting<Integer> mapZoneRange = sgDefault.add(new IntSetting.Builder()
        .name("map-zone-range")
        .description("Amount of map zones around the player to highlight.")
        .visible(mapZoneRender::get)
        .defaultValue(4).sliderRange(1, 10)
        .build());

    private final Setting<Integer> mapZoneYStart = sgDefault.add(new IntSetting.Builder()
        .name("map-zone-y-start")
        .description("Minimum Y level to render the zone at.")
        .visible(mapZoneRender::get)
        .defaultValue(63)
        .sliderRange(-64, 319)
        .build());

    private final Setting<Keybind> setYKey = sgDefault.add(new KeybindSetting.Builder()
        .name("set-y-key")
        .description("Press to set map-zone-y-start to your current Y position.")
        .defaultValue(Keybind.fromKey(-1))
        .build());

    private final Setting<Integer> mapZoneYOffset = sgDefault.add(new IntSetting.Builder()
        .name("map-zone-y-offset")
        .description("How far to offset off of the start.")
        .visible(mapZoneRender::get)
        .defaultValue(2)
        .sliderRange(0, 16)
        .build());

    private final Setting<SettingColor> mapColor = sgDefault.add(new ColorSetting.Builder()
        .name("map-color")
        .visible(mapZoneRender::get)
        .defaultValue(new Color(187, 115, 236, 255))
        .build());

    private final Setting<Integer> mapZoneSidesAlpha = sgDefault.add(new IntSetting.Builder()
        .name("map-zone-sides-alpha")
        .description("What to change the alpha to for the sides.")
        .visible(mapZoneRender::get)
        .defaultValue(60)
        .sliderRange(12, 255)
        .build());

    private final Setting<Integer> refreshDelay = sgDefault.add(new IntSetting.Builder()
        .name("refresh-delay")
        .description("How often to refresh the map zone list, in ticks.")
        .defaultValue(4)
        .sliderRange(0, 10)
        .build());

    private final List<double[]> mapQuads = new ArrayList<>();
    private int ticks = 0;

    public MapGridZone() {
        super(Addon.CATEGORY, "Map-Grid", "Highlights map grid boundaries around the player.");
    }

    @EventHandler
    public void onTickPre(TickEvent.Pre event) {
        // Keybind to set the map grid Y start to current player Y
        if (setYKey.get().isPressed()) {
            mapZoneYStart.set(mc.player.getBlockY());
            info("Map zone Y start set to " + mc.player.getBlockY());
        }

        if (ticks < refreshDelay.get()) {
            ticks++;
            return;
        }

        if (!mapQuads.isEmpty()) {
            mapQuads.clear();
        }

        try {
            if (mapZoneRender.get()) {
                double currentMapQuadX = MathUtils.toMapQuad(mc.player.getX());
                double currentMapQuadZ = MathUtils.toMapQuad(mc.player.getZ());

                for (int x = -mapZoneRange.get(); x <= mapZoneRange.get(); x++) {
                    for (int z = -mapZoneRange.get(); z <= mapZoneRange.get(); z++) {
                        mapQuads.add(new double[]{(currentMapQuadX + x) * 128, (currentMapQuadZ + z) * 128});
                    }
                }
            }
        } catch (Exception ignored) {}

        ticks = 0;
    }

    @EventHandler
    public void render3DEvent(Render3DEvent event) {
        if (!mapZoneRender.get()) return;

        for (double[] quad : mapQuads) {
            renderMapQuad(event.renderer, quad[0], quad[1]);
        }
    }

    private void renderMapQuad(Renderer3D renderer, double x, double z) {
        int origAlpha = mapColor.get().a;

        renderer.boxLines(x - 64, mapZoneYStart.get(), z - 64,
            x + 64, mapZoneYStart.get() + mapZoneYOffset.get(), z + 64, mapColor.get(), 0);
        mapColor.get().a(mapZoneSidesAlpha.get());
        renderer.boxSides(x - 64, mapZoneYStart.get(), z - 64,
            x + 64, mapZoneYStart.get() + mapZoneYOffset.get(), z + 64, mapColor.get(), 0);
        mapColor.get().a(origAlpha);
    }
}
