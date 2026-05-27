package com.AutoBookshelf.addon.hud;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.util.Identifier;

public class MayaChan extends HudElement {
    public enum MayaImage {
        DEFAULT("textures/maya-chan.png", 221, 339),
        BLACK ("textures/maya-black.png", 255, 158),
        WHITE ("textures/maya-white.png", 277, 302),
        SULK ("textures/maya-sulk.png", 384, 512);

        public final Identifier id;
        public final int defaultWidth;
        public final int defaultHeight;

        MayaImage(String path, int defaultWidth, int defaultHeight) {
            this.id = Identifier.of("oehrasa", path);
            this.defaultWidth = defaultWidth;
            this.defaultHeight = defaultHeight;
        }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> width = sgGeneral.add(new IntSetting.Builder()
        .name("Width")
        .description("Width of Maya Chan.")
        .defaultValue(MayaImage.DEFAULT.defaultWidth)
        .min(1)
        .max(800)
        .sliderMax(800)
        .build()
    );

    private final Setting<Integer> height = sgGeneral.add(new IntSetting.Builder()
        .name("Height")
        .description("Height of Maya Chan.")
        .defaultValue(MayaImage.DEFAULT.defaultHeight)
        .min(1)
        .max(800)
        .sliderMax(800)
        .build()
    );

    private final Setting<MayaImage> image = sgGeneral.add(new EnumSetting.Builder<MayaImage>()
        .name("image")
        .description("Which Maya to show.")
        .defaultValue(MayaImage.DEFAULT)
        .onChanged(newValue -> {
            width.set(newValue.defaultWidth);
            height.set(newValue.defaultHeight);
        })
        .build()
    );

    public static final HudElementInfo<MayaChan> INFO = new HudElementInfo<>(
        Addon.HUD_GROUP, "MayaChan", "Renders oehrasa beloved OC's : Nishizumi Maya.", MayaChan::new); // Drawing by Kodack, me, A-Chan & sis

    public MayaChan() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        double w = width.get();
        double h = height.get();
        setSize(w, h);
        renderer.texture(image.get().id, x, y, w, h, Color.WHITE);
    }
}
