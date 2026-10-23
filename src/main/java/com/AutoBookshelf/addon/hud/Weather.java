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
import net.minecraft.client.MinecraftClient;


public class Weather extends HudElement {
    public static final HudElementInfo<Weather> INFO = new HudElementInfo<>(Addon.HUD_GROUP, "Weather", "Displays current weather", Weather::new);
    private MinecraftClient mc = null;
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

    public Weather() {
        super(INFO);
        mc = MinecraftClient.getInstance();
    }

    @Override
    public void render(HudRenderer renderer) {
        String textWidth = "Weather:            ";
        setSize(forceDefaultFont.width(renderer, textWidth, true, scale.get()), forceDefaultFont.height(renderer, true, scale.get()));
        String weather = "None";
        if (mc.world != null && mc.world.canHaveWeather()) {
            if (mc.world.isThundering()) {
                weather = "Thundering";
            } else if (mc.world.isRaining()) {
                weather = "Raining";
            } else {
                weather = "Clear";
            }
        }
        forceDefaultFont.text(renderer, "Weather: ", x, y, Color.WHITE, true, scale.get());
        forceDefaultFont.text(renderer, weather, forceDefaultFont.width(renderer, "Weather: ", true, scale.get()) + x, y, Color.LIGHT_GRAY, true, scale.get());
    }
}
