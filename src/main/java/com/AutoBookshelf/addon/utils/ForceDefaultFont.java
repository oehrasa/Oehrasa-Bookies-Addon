package com.AutoBookshelf.addon.utils;

import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.renderer.text.VanillaTextRenderer;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;

/**
 * Shared "force-default-font" setting plus the measure/draw paths it needs.
 * <p>
 * HudRenderer only picks the vanilla font when the HUD's own custom-font setting is off, so an
 * element that forces it has to drive GuiGraphics itself. The forced branches below mirror
 * HudRenderer's vanilla branches (the -1 -> Hud text scale resolution, the *2 factor and
 * VanillaTextRenderer#render's pose/rounding) so a forcing element measures and lays out exactly
 * as it would with the HUD custom font disabled, with one deliberate difference: the caller's
 * shadow flag is passed through, whereas VanillaTextRenderer always draws its shadow.
 * <p>
 * Each instance owns its own force-default-font setting - construct one per module/HUD element,
 * passing the SettingGroup it should live in. Call sites then mirror HudRenderer's signatures
 * with the renderer as the first argument: renderer.textWidth(t, s, sc) ->
 * forceDefaultFont.width(renderer, t, s, sc).
 */
public class ForceDefaultFont {
    private final Setting<Boolean> setting;

    public ForceDefaultFont(SettingGroup group) {
        setting = group.add(new BoolSetting.Builder()
            .name("force-default-font")
            .description("Force text to render using the default Minecraft font, even if a custom font is enabled in Meteor.")
            .defaultValue(false)
            .build()
        );
    }

    /**
     * Current value of this element's force-default-font setting.
     */
    public boolean get() {
        return setting.get();
    }

    /**
     * Meteor's world/screen text renderer, honoring the setting: the vanilla font when forced,
     * otherwise the one the client config selected ({@code TextRenderer.get()}).
     */
    public TextRenderer textRenderer() {
        return get() ? VanillaTextRenderer.INSTANCE : TextRenderer.get();
    }

    public double width(HudRenderer renderer, String text) {
        return width(renderer, text, false, -1);
    }

    public double width(HudRenderer renderer, String text, boolean shadow) {
        return width(renderer, text, shadow, -1);
    }

    public double width(HudRenderer renderer, String text, boolean shadow, double scale) {
        if (!get()) return renderer.textWidth(text, shadow, scale);

        // VanillaTextRenderer.INSTANCE is a shared mutable singleton; measure with the effective
        // scale and put the previous one back so a stray measurement can't leak into other draws.
        VanillaTextRenderer vanilla = VanillaTextRenderer.INSTANCE;
        double prevScale = vanilla.scale;
        vanilla.scale = resolve(scale) * 2;
        try {
            return vanilla.getWidth(text, shadow);
        } finally {
            vanilla.scale = prevScale;
        }
    }

    public double height(HudRenderer renderer) {
        return height(renderer, false, -1);
    }

    public double height(HudRenderer renderer, boolean shadow) {
        return height(renderer, shadow, -1);
    }

    public double height(HudRenderer renderer, boolean shadow, double scale) {
        if (!get()) return renderer.textHeight(shadow, scale);

        VanillaTextRenderer vanilla = VanillaTextRenderer.INSTANCE;
        double prevScale = vanilla.scale;
        vanilla.scale = resolve(scale) * 2;
        try {
            return vanilla.getHeight(shadow);
        } finally {
            vanilla.scale = prevScale;
        }
    }

    public double text(HudRenderer renderer, String text, double x, double y, Color color, boolean shadow) {
        return text(renderer, text, x, y, color, shadow, -1);
    }

    public double text(HudRenderer renderer, String text, double x, double y, Color color, boolean shadow, double scale) {
        if (!get()) return renderer.text(text, x, y, color, shadow, scale);

        // Mirror HudRenderer's vanilla branch exactly: VanillaTextRenderer draws through
        // Font#drawInBatch in absolute physical pixels, so no pose manipulation is involved.
        VanillaTextRenderer vanilla = VanillaTextRenderer.INSTANCE;
        double prevScale = vanilla.scale;
        vanilla.scale = resolve(scale) * 2;
        try {
            return vanilla.render(text, x, y, color, shadow);
        } finally {
            vanilla.scale = prevScale;
        }
    }

    private static double resolve(double scale) {
        return scale == -1 ? Hud.get().getTextScale() : scale;
    }
}
