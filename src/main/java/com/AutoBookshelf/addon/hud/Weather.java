/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.hud;

import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.utils.ForceDefaultFont;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.Minecraft;


public class Weather extends HudElement {
    public static final HudElementInfo<Weather> INFO = new HudElementInfo<>(Addon.HUD_GROUP, "Weather", "Displays current weather", Weather::new);
    private Minecraft mc = null;
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Custom scale.")
        .defaultValue(1)
        .min(0.5)
        .sliderRange(0.5, 3)
        .build()
    );

    private final ForceDefaultFont forceDefaultFont = new ForceDefaultFont(sgGeneral);

    private double cachedSizeWidth = -1;
    private double cachedPrefixWidth = -1;
    private double cachedForScale = -1;

    public Weather() {
        super(INFO);
        mc = Minecraft.getInstance();
    }

    @Override
    public void render(HudRenderer renderer) {
        double scaleValue = scale.get();
        if (cachedSizeWidth < 0 || cachedForScale != scaleValue) {
            cachedForScale = scaleValue;
            cachedSizeWidth = forceDefaultFont.width(renderer, "Weather:            ", true, scaleValue);
            cachedPrefixWidth = forceDefaultFont.width(renderer, "Weather: ", true, scaleValue);
        }
        setSize(cachedSizeWidth, forceDefaultFont.height(renderer, true, scaleValue));
        String weather = "None";
        if (mc.level != null && mc.level.canHaveWeather()) {
            if (mc.level.isThundering()) {
                weather = "Thundering";
            } else if (mc.level.isRaining()) {
                weather = "Raining";
            } else {
                weather = "Clear";
            }
        }
        forceDefaultFont.text(renderer, "Weather: ", x, y, Color.WHITE, true, scaleValue);
        forceDefaultFont.text(renderer, weather, cachedPrefixWidth + x, y, Color.LIGHT_GRAY, true, scaleValue);
    }
}
