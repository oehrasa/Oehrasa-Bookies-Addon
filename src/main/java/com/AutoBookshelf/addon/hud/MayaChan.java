package com.AutoBookshelf.addon.hud;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.util.Identifier;

public class MayaChan extends HudElement {
    private final Identifier TEXTURE = Identifier.of("oehrasa", "textures/maya-chan.png");
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> width = sgGeneral.add(new IntSetting.Builder()
        .name("Width")
        .description("Width of MayaChan")
        .defaultValue(150)
        .min(1)
        .max(800)
        .sliderMax(800)
        .build()
    );

    private final Setting<Integer> height = sgGeneral.add(new IntSetting.Builder()
        .name("Height")
        .description("Height of MayaChan")
        .defaultValue(100)
        .min(1)
        .max(800)
        .sliderMax(800)
        .build()
    );

    public static final HudElementInfo<MayaChan> INFO = new HudElementInfo<>(
        Addon.HUD_GROUP, "MayaChan", "Render oehrasa beloved OC's : Nishizumi Maya", MayaChan::new);
    // Credit to Kodack for the drawing

    public MayaChan() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        double w = width.get();
        double h = height.get();

        // Set the HUD element size
        setSize(w, h);

        // Draw the texture
        renderer.texture(TEXTURE, x, y, w, h, Color.WHITE);
    }
}
