package com.AutoBookshelf.addon.hud;

import com.AutoBookshelf.addon.Addon;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import net.minecraft.client.texture.AbstractTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.gl.GpuSampler;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.network.Http;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import static meteordevelopment.meteorclient.MeteorClient.mc;
import static meteordevelopment.meteorclient.utils.Utils.WHITE;

public class AnimePics extends HudElement {
    public static final HudElementInfo<AnimePics> INFO = new HudElementInfo<>(
        Addon.HUD_GROUP,
        "Anime-Pics",
        "Displays random Anime pictures from Nekos.life or WaifuIM.",
        AnimePics::create
    );
    // Lowercase for identifier is not allowed btw
    private static final Identifier TEXID = Identifier.of("autobookshelf", "animepics");
    private boolean locked = false;
    private boolean empty = true;
    private int ticks = 0;

    // Settings
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public enum Source { NekosLife, WaifuIM }

    public enum NekosTag {
        neko, waifu, fox_girl, hug, kiss, meow, lizard, goose, gecg,
        avatar, feed, cuddle, woof, smug, tickle, slap, pat, wallpaper
    }

    public enum WaifimTag {
        waifu, ero, ecchi, oppai, hentai, milf, uniform, ass, maid,
        selfies, paizuri, oral, genshin_impact, raiden_shogun, marin_kitagawa,
        mori_calliope, kamisato_ayaka
    }

    private static final List<String> NEKOS_CYCLE_LIST = List.of(
        "neko", "waifu", "fox_girl", "hug", "kiss", "meow", "lizard", "goose", "gecg",
        "avatar", "feed", "cuddle", "woof", "smug", "tickle", "slap", "pat", "wallpaper"
    );


    private static final List<String> WAIFU_CYCLE_LIST = List.of(
        "waifu", "ero", "ecchi", "oppai", "hentai", "milf", "uniform", "ass", "maid",
        "selfies", "paizuri", "oral", "genshin impact", "raiden shogun", "marin kitagawa",
        "mori calliope", "kamisato ayaka"
    );

    private final Setting<Source> source = sgGeneral.add(new EnumSetting.Builder<Source>()
        .name("source")
        .description("Image source to use.")
        .defaultValue(Source.WaifuIM)
        .onChanged(v -> empty = true)
        .build()
    );

    private final Setting<NekosTag> nekosCategory = sgGeneral.add(new EnumSetting.Builder<NekosTag>()
        .name("nekos-category")
        .description("Category for Nekos.life.")
        .visible(() -> source.get() == Source.NekosLife)
        .defaultValue(NekosTag.neko)
        .onChanged(v -> empty = true)
        .build()
    );

    private final Setting<Boolean> cycleNekos = sgGeneral.add(new BoolSetting.Builder()
        .name("cycle-nekos")
        .description("Cycle through Nekos.life categories on each refresh.")
        .visible(() -> source.get() == Source.NekosLife)
        .defaultValue(true)
        .build()
    );

    private int nekosCycleIndex = 0;

    private final Setting<WaifimTag> waifuTag = sgGeneral.add(new EnumSetting.Builder<WaifimTag>()
        .name("waifu-tag")
        .description("Image category for WaifuIM.")
        .visible(() -> source.get() == Source.WaifuIM)
        .defaultValue(WaifimTag.waifu)
        .onChanged(v -> empty = true)
        .build()
    );

    private final Setting<Boolean> cycleWaifu = sgGeneral.add(new BoolSetting.Builder()
        .name("cycle-waifu")
        .description("Cycle through WaifuIM tags on each refresh.")
        .visible(() -> source.get() == Source.WaifuIM)
        .defaultValue(true)
        .build()
    );

    private int waifuCycleIndex = 0;

    private final Setting<Double> imgWidth = sgGeneral.add(new DoubleSetting.Builder()
        .name("width")
        .description("Image width on screen.")
        .defaultValue(200).min(50)
        .sliderRange(50, 800)
        .onChanged(o -> updateSize())
        .build()
    );

    private final Setting<Double> imgHeight = sgGeneral.add(new DoubleSetting.Builder()
        .name("height")
        .description("Image height on screen.")
        .defaultValue(200).min(50)
        .sliderRange(50, 800)
        .onChanged(o -> updateSize()).build()
    );

    private final Setting<Boolean> pauseRefresh = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-refresh")
        .description("Stop refreshing image, The current image stays on screen.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> refreshRate = sgGeneral.add(new IntSetting.Builder()
        .name("refresh-rate")
        .description("Ticks between image refresh.")
        .defaultValue(1200)
        .min(100)
        .max(72000)
        .sliderRange(100, 72000)
        .build()
    );

    // Event bus subscription for tick updates
    public AnimePics() {
        super(INFO);
        MeteorClient.EVENT_BUS.subscribe(this);
    }

    @Override
    public void remove() {
        super.remove();
        MeteorClient.EVENT_BUS.unsubscribe(this);
    }

    private static AnimePics create() {
        return new AnimePics();
    }

    @EventHandler
    public void onTick(TickEvent.Post event) {
        if (pauseRefresh.get()) return;
        ticks++;
        if (ticks >= refreshRate.get()) {
            ticks = 0;
            loadImage();
        }
    }

    @Override
    public void render(HudRenderer renderer) {
        if (empty) {
            loadImage();
            return;
        }

        AbstractTexture tex = mc.getTextureManager().getTexture(TEXID);
        if (tex == null) return;

        GpuTextureView textureView = tex.getGlTextureView();
        GpuSampler sampler = tex.getSampler();

        Renderer2D.TEXTURE.begin();
        Renderer2D.TEXTURE.texQuad(x, y, imgWidth.get(), imgHeight.get(), WHITE);
        Renderer2D.TEXTURE.render(textureView, sampler);
    }

    private void updateSize() { setSize(imgWidth.get(), imgHeight.get()); }

    // Fetch image URL based on selected source
    private String fetchImageUrl() {
        return switch (source.get()) {
            case NekosLife -> fetchNekosLife();
            case WaifuIM -> fetchWaifuIM();
        };
    }

    private String fetchNekosLife() {
        String category;
        if (cycleNekos.get()) {
            category = NEKOS_CYCLE_LIST.get(nekosCycleIndex);
            nekosCycleIndex = (nekosCycleIndex + 1) % NEKOS_CYCLE_LIST.size();
        } else {
            category = nekosCategory.get().name();
        }
        String apiUrl = "https://nekos.life/api/v2/img/" + category;
        try {
            JsonObject response = Http.get(apiUrl).sendJson(JsonObject.class);
            if (response == null) return null;
            return response.get("url").getAsString();
        } catch (Exception e) {
            MeteorClient.LOG.error("[AnimePics] Nekos.life Error: " + e.getMessage());
            return null;
        }
    }

    private String fetchWaifuIM() {
        String tag;
        if (cycleWaifu.get()) {
            tag = WAIFU_CYCLE_LIST.get(waifuCycleIndex);
            waifuCycleIndex = (waifuCycleIndex + 1) % WAIFU_CYCLE_LIST.size();
        } else {
            tag = waifuTag.get().name().replace('_', ' ');
        }
        String apiUrl = "https://api.waifu.im/images?IncludedTags="
            + URLEncoder.encode(tag, StandardCharsets.UTF_8)
            + "&IsNsfw=All&PageSize=20";
        try {
            JsonObject response = Http.get(apiUrl)
                .header("Accept", "application/json")
                .sendJson(JsonObject.class);
            if (response == null) return null;
            JsonArray items = response.getAsJsonArray("items");
            if (items.isEmpty()) return null;
            JsonObject image = items.get(new Random().nextInt(items.size())).getAsJsonObject();
            return image.get("url").getAsString();
        } catch (Exception e) {
            MeteorClient.LOG.error("[AnimePics] WaifuIM Error: " + e.getMessage());
            return null;
        }
    }

    private void loadImage() {
        if (locked) return;
        new Thread(() -> {
            try {
                locked = true;
                String url = fetchImageUrl();
                if (url == null) { locked = false; return; }

                MeteorClient.LOG.info("[AnimePics] Image URL: " + url);
                var img = ImageIO.read(Http.get(url).sendInputStream());
                var baos = new ByteArrayOutputStream();
                ImageIO.write(img, "png", baos);

                byte[] imageBytes = baos.toByteArray();
                mc.execute(() -> {
                    try {
                        if (mc.getTextureManager() == null) return;
                        NativeImage nativeImage = NativeImage.read(new ByteArrayInputStream(imageBytes));
                        mc.getTextureManager().registerTexture(TEXID,
                            new NativeImageBackedTexture(() -> "AnimePics", nativeImage));
                        empty = false;
                        MeteorClient.LOG.info("[AnimePics] Image loaded!");
                    } catch (Exception ex) {
                        MeteorClient.LOG.error("[AnimePics] Texture register error: " + ex.getMessage());
                    }
                });
            } catch (Exception e) {
                MeteorClient.LOG.error("[AnimePics] " + e.getMessage());
            }
            locked = false;
        }).start();
        updateSize();
    }
}
