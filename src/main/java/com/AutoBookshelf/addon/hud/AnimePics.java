package com.AutoBookshelf.addon.hud;

import com.AutoBookshelf.addon.Addon;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.GL;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.network.Http;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

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
        "Displays random Anime pictures from Nekos.life or WaifuIM or Safebooru.",
        AnimePics::create
    );

    private boolean locked = false;
    private boolean empty = true;
    private int ticks = 0;
    private byte[] currentImageBytes = null;   // cached PNG bytes of the displayed image
    private final PointerBuffer saveFilters;         // file filter for save dialogue
    private volatile boolean manualRefresh = false; // true = next load must use fixed tag
    private final Identifier textureId;   // unique per element

    // Settings
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public enum Source { NekosLife, WaifuIM, Safebooru }

    public enum NekosTag {
        neko, waifu, fox_girl, hug, kiss, meow, gecg,
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
    ); // oomfie rfs <3

    private static final List<String> WAIFU_CYCLE_LIST = List.of(
        "waifu", "ero", "ecchi", "oppai", "hentai", "milf", "uniform", "ass", "maid",
        "selfies", "paizuri", "oral", "genshin impact", "raiden shogun", "marin kitagawa",
        "mori calliope", "kamisato ayaka"
    );

    private final Setting<Source> source = sgGeneral.add(new EnumSetting.Builder<Source>()
        .name("source")
        .description("Image source to use.")
        .defaultValue(Source.WaifuIM)
        .onChanged(v -> refreshNow())
        .build()
    );

    private final Setting<NekosTag> nekosCategory = sgGeneral.add(new EnumSetting.Builder<NekosTag>()
        .name("nekos-category")
        .description("Category for Nekos.life.")
        .visible(() -> source.get() == Source.NekosLife)
        .defaultValue(NekosTag.neko)
        .onChanged(v -> refreshNow())
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
        .onChanged(v -> refreshNow())
        .build()
    );

    private final Setting<Boolean> cycleWaifu = sgGeneral.add(new BoolSetting.Builder()
        .name("cycle-waifu")
        .description("Cycle through WaifuIM tags on each refresh.")
        .visible(() -> source.get() == Source.WaifuIM)
        .defaultValue(true)
        .build()
    );

    private final Setting<String> safebooruTag = sgGeneral.add(new StringSetting.Builder()
        .name("safebooru-tag")
        .description("Tag for Safebooru images.")
        .visible(() -> source.get() == Source.Safebooru)
        .defaultValue("yuri")
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
        this.textureId = Identifier.of("autobookshelf", "animepics_" + UUID.randomUUID());

        // PNG filter for the save dialogue
        ByteBuffer pngFilter = MemoryUtil.memASCII("*.png");
        saveFilters = BufferUtils.createPointerBuffer(1);
        saveFilters.put(pngFilter);
        saveFilters.rewind();

        MeteorClient.EVENT_BUS.subscribe(this);
    }

    @Override
    public void remove() {
        super.remove();
        MeteorClient.EVENT_BUS.unsubscribe(this);
        if (mc.getTextureManager() != null) {
            mc.getTextureManager().destroyTexture(textureId);
        }
    }

    private static AnimePics create() {
        return new AnimePics();
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WHorizontalList buttonRow = theme.horizontalList();
        list.add(buttonRow).expandX();

        WButton refreshBtn = buttonRow.add(theme.button("Refresh Now")).widget();
        refreshBtn.action = this::refreshNow;

        WButton saveBtn = buttonRow.add(theme.button("Save Image")).widget();
        saveBtn.action = this::saveImage;

        return list;
    }

    // Forces next load to use the currently selected fixed category
    public void refreshNow() {
        manualRefresh = true;
        empty = true;
        ticks = 0;
    }

    private void saveImage() {
        if (currentImageBytes == null || currentImageBytes.length == 0) {
            MeteorClient.LOG.info("[AnimePics] No image to save.");
            return;
        }

        String suggestedName = "animepic.png";
        String path = TinyFileDialogs.tinyfd_saveFileDialog(
            "Save Image",
            new File(MeteorClient.FOLDER, suggestedName).getAbsolutePath(),
            saveFilters,
            null
        );

        if (path == null) return;   // user cancelled

        try {
            Files.write(Path.of(path), currentImageBytes);
            MeteorClient.LOG.info("[AnimePics] Image saved to " + path);
        } catch (IOException e) {
            MeteorClient.LOG.error("[AnimePics] Save error: " + e.getMessage());
        }
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
        GL.bindTexture(textureId);
        Renderer2D.TEXTURE.begin();
        Renderer2D.TEXTURE.texQuad(x, y, imgWidth.get(), imgHeight.get(), WHITE);
        Renderer2D.TEXTURE.render(null);
    }

    private void updateSize() { setSize(imgWidth.get(), imgHeight.get()); }

    // Fetch image URL based on selected source
    private String fetchImageUrl(boolean forceFixed) {
        return switch (source.get()) {
            case NekosLife -> fetchNekosLife(forceFixed);
            case WaifuIM -> fetchWaifuIM(forceFixed);
            case Safebooru -> fetchSafebooru();
        };
    }

    private String fetchNekosLife(boolean forceFixed) {
        String category;
        if (!forceFixed && cycleNekos.get()) {
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

    private String fetchWaifuIM(boolean forceFixed) {
        String tag;
        if (!forceFixed && cycleWaifu.get()) {
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

    private String fetchSafebooru() {
        String tag = safebooruTag.get();
        try {
            String encoded = URLEncoder.encode(tag, StandardCharsets.UTF_8);
            int pid = new Random().nextInt(700);
            String apiUrl = "https://safebooru.org/index.php?page=dapi&s=post&q=index&json=1"
                + "&tags=" + encoded
                + "&limit=10" // request 10 posts
                + "&pid=" + pid;

            JsonElement result = Http.get(apiUrl).sendJson(JsonElement.class);
            if (!(result instanceof JsonArray array) || array.isEmpty()) {
                return null;
            }

            // Pick a random post from the page
            JsonObject post = array.get(new Random().nextInt(array.size())).getAsJsonObject();

            // Prefer file_url, then preview_url, then construct from directory/image
            if (post.has("file_url")) {
                return post.get("file_url").getAsString();
            }
            if (post.has("preview_url")) {
                return post.get("preview_url").getAsString();
            }
            if (post.has("directory") && post.has("image")) {
                return "https://safebooru.org/images/"
                    + post.get("directory").getAsString() + "/"
                    + post.get("image").getAsString();
            }
            return null;
        } catch (Exception e) {
            MeteorClient.LOG.error("[AnimePics] Safebooru Error: " + e.getMessage());
            return null;
        }
    }

    private void loadImage() {
        if (locked) return;
        new Thread(() -> {
            try {
                locked = true;
                boolean useFixed = manualRefresh;
                manualRefresh = false;

                String url = fetchImageUrl(useFixed);
                if (url == null) { locked = false; return; }

                MeteorClient.LOG.info("[AnimePics] Image URL: " + url);
                var img = ImageIO.read(Http.get(url).sendInputStream());
                var baos = new ByteArrayOutputStream();
                ImageIO.write(img, "png", baos);

                byte[] imageBytes = baos.toByteArray();
                this.currentImageBytes = imageBytes;    // cache for saving
                mc.execute(() -> {
                    try {
                        if (mc.getTextureManager() == null) return;
                        mc.getTextureManager().registerTexture(textureId,
                            new NativeImageBackedTexture(NativeImage.read(new ByteArrayInputStream(imageBytes))));
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
