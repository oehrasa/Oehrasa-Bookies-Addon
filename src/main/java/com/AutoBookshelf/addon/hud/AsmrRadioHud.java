package com.AutoBookshelf.addon.hud;

// TODO: point this at your addon's actual main class (wherever CATEGORY / HUD_GROUP live).

import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.utils.AsmrAudioEngine;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

public class AsmrRadioHud extends HudElement {

    public static final HudElementInfo<AsmrRadioHud> INFO = new HudElementInfo<>(
        Addon.HUD_GROUP, "Asmr-Radio", "Shows the currently playing ASMR track.", AsmrRadioHud::new
    );

    private final SettingGroup sgGeneral = settings.createGroup("General");

    private final Setting<SettingColor> textColor = sgGeneral.add(new ColorSetting.Builder()
        .name("text-color")
        .description("Color of the now-playing text.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build()
    );

    private final Setting<Boolean> showStatus = sgGeneral.add(new BoolSetting.Builder()
        .name("show-status")
        .description("Show resolving/error/paused status instead of just the title.")
        .defaultValue(true)
        .build()
    );

    private static final SettingColor PLACEHOLDER_COLOR = new SettingColor(200, 200, 200, 120);
    private static final String PLACEHOLDER = "ASMR Radio";

    private String cachedDisplay;

    public AsmrRadioHud() {
        super(INFO);
    }

    @Override
    public void tick(HudRenderer renderer) {
        cachedDisplay = buildDisplay();
        setSize(
            renderer.textWidth(cachedDisplay != null ? cachedDisplay : PLACEHOLDER),
            renderer.textHeight()
        );
    }

    @Override
    public void render(HudRenderer renderer) {
        if (cachedDisplay != null) {
            SettingColor color = textColor.get();
            color.update();
            renderer.text(cachedDisplay, x, y, color, true);
        } else if (isInEditor()) {
            renderer.text(PLACEHOLDER, x, y, PLACEHOLDER_COLOR, true);
        }
    }

    private String buildDisplay() {
        AsmrAudioEngine.State state = AsmrAudioEngine.state();
        if (state == AsmrAudioEngine.State.IDLE) return null;

        if (showStatus.get() && state != AsmrAudioEngine.State.PLAYING) {
            return AsmrAudioEngine.statusMessage();
        }

        String title = AsmrAudioEngine.title();
        if (title == null || title.isBlank()) return null;
        return (AsmrAudioEngine.paused() ? "\u23F8 " : "\u25B6 ") + title;
    }
}
