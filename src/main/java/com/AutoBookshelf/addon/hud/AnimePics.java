package com.AutoBookshelf.addon.hud;

import com.AutoBookshelf.addon.Addon;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.network.Http;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.texture.AbstractTexture;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static meteordevelopment.meteorclient.MeteorClient.mc;
import static meteordevelopment.meteorclient.utils.Utils.WHITE;

public class AnimePics extends HudElement {
    public static final HudElementInfo<AnimePics> INFO = new HudElementInfo<>(
        Addon.HUD_GROUP,
        "Anime-Pics",
        "Displays random Anime pictures from Nekos.life or WaifuIM or Safebooru or even Custom.",
        AnimePics::create
    );

    private boolean locked = false;
    private boolean empty = true;
    private int ticks = 0;
    private byte[] currentImageBytes = null; // cache for saving
    private final PointerBuffer saveFilters; // file dialogue filter
    private volatile boolean manualRefresh = false; // true = next load uses fixed tag
    private final Identifier textureId; // unique per element

    // Settings
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public enum Source { NekosLife, WaifuIM, Safebooru, LocalFolder }

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
        .onChanged(v -> {
            if (v == Source.LocalFolder) loadLocalFileList();   // reload list when switching to local
            loggedEmptyFolder = false;
            refreshNow();
            if (this.settings != null) this.settings.invalidate();
            updateSourceButtonsVisibility();
        })
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

    private final Setting<String> localFolderPath = sgGeneral.add(new StringSetting.Builder()
        .name("local-folder-path")
        .description("Path to the folder containing images for Local Folder.")
        .visible(() -> source.get() == Source.LocalFolder)
        .defaultValue("")
        .build()
    );

    // Local folder cycle
    private List<File> localImageFiles = new ArrayList<>();
    private int localImageIndex = 0;
    private boolean loggedEmptyFolder = false;

    public AnimePics() {
        super(INFO);
        this.textureId = Identifier.of("autobookshelf", "animepics_" + UUID.randomUUID());

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

    // Widgets
    private WHorizontalList folderRow;
    private WHorizontalList onlineRow;

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WHorizontalList row = theme.horizontalList();

        WButton refreshBtn = row.add(theme.button("Refresh Now")).widget();
        refreshBtn.action = this::refreshNow;

        WButton saveBtn = row.add(theme.button("Save Image")).widget();
        saveBtn.action = this::saveImage;

        // Folder selector (visible when is not LocalFolder)
        WHorizontalList folderRow = theme.horizontalList();
        row.add(folderRow);
        WButton selectFolderBtn = folderRow.add(theme.button("Select Folder")).widget();
        selectFolderBtn.action = this::selectLocalFolder;
        this.folderRow = folderRow;

        // Switch to online (visible only when LocalFolder)
        WHorizontalList onlineRow = theme.horizontalList();
        row.add(onlineRow);
        WButton onlineBtn = onlineRow.add(theme.button("Switch Online")).widget();
        onlineBtn.action = () -> {
            source.set(Source.WaifuIM);
            refreshNow();
        };
        this.onlineRow = onlineRow;

        updateSourceButtonsVisibility();
        return row;
    }

    private void updateSourceButtonsVisibility() {
        if (folderRow != null) folderRow.visible = source.get() != Source.LocalFolder;
        if (onlineRow != null) onlineRow.visible = source.get() == Source.LocalFolder;
    }

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

    private void selectLocalFolder() {
        String path = TinyFileDialogs.tinyfd_selectFolderDialog(
            "Choose image folder",
            localFolderPath.get().isEmpty()
                ? new File(MeteorClient.FOLDER, "images").getAbsolutePath()
                : localFolderPath.get()
        );
        if (path != null) {
            localFolderPath.set(path);
            source.set(Source.LocalFolder);
            MeteorClient.LOG.info("Image folder set to " + path);
            loggedEmptyFolder = false;
            refreshNow();
        }
    }

    /** reloads the list of image files from the current local folder. */
    private void loadLocalFileList() {
        localImageFiles.clear();
        localImageIndex = 0;
        String folderPath = localFolderPath.get();
        if (folderPath.isEmpty()) return;

        File dir = new File(folderPath);
        File[] files = dir.listFiles((d, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
        });
        if (files != null) localImageFiles.addAll(List.of(files));
    }

    @EventHandler
    public void onTick(TickEvent.Post event) {
        if (pauseRefresh.get()) return;

        // If source is local but the file list was never loaded (after relog), load it now
        if (source.get() == Source.LocalFolder && localImageFiles.isEmpty()) {
            loadLocalFileList();
        }

        // If the folder is still empty, log once and stop refreshing
        if (source.get() == Source.LocalFolder && localImageFiles.isEmpty()) {
            if (!loggedEmptyFolder) {
                MeteorClient.LOG.error("[AnimePics] No images found in folder.");
                loggedEmptyFolder = true;
            }
            return;
        } else {
            loggedEmptyFolder = false;
        }

        ticks++;
        if (ticks >= refreshRate.get()) {
            ticks = 0;
            loadImage();
        }
    }

    @Override
    public void render(HudRenderer renderer) {
        if (empty) {
            // If local folder is empty, don't keep trying
            if (source.get() == Source.LocalFolder && localImageFiles.isEmpty()) {
                return;
            }
            loadImage();
            return;
        }

        AbstractTexture tex = mc.getTextureManager().getTexture(textureId);
        if (tex == null) return;

        var textureView = tex.getGlTextureView();
        var sampler = tex.getSampler();

        Renderer2D.TEXTURE.begin();
        Renderer2D.TEXTURE.texQuad(x, y, imgWidth.get(), imgHeight.get(), WHITE);
        Renderer2D.TEXTURE.render(textureView, sampler);
    }

    private void updateSize() { setSize(imgWidth.get(), imgHeight.get()); }

    private String fetchImageUrl(boolean forceFixed) {
        return switch (source.get()) {
            case NekosLife -> fetchNekosLife(forceFixed);
            case WaifuIM -> fetchWaifuIM(forceFixed);
            case Safebooru -> fetchSafebooru();
            case LocalFolder -> "local://" + (localFolderPath.get());
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
                + "&limit=10"
                + "&pid=" + pid;

            JsonElement result = Http.get(apiUrl).sendJson(JsonElement.class);
            if (!(result instanceof JsonArray array) || array.isEmpty()) return null;

            JsonObject post = array.get(new Random().nextInt(array.size())).getAsJsonObject();

            if (post.has("file_url")) return post.get("file_url").getAsString();
            if (post.has("preview_url")) return post.get("preview_url").getAsString();
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

    /** Returns the next local image file, cycling through the list. */
    private File getNextLocalImage() {
        if (localImageFiles.isEmpty()) return null;
        File file = localImageFiles.get(localImageIndex);
        localImageIndex = (localImageIndex + 1) % localImageFiles.size();
        return file;
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

                byte[] imageBytes;

                if (url.startsWith("local://")) {
                    // Use cycling instead of random
                    File file = getNextLocalImage();
                    if (file == null) {
                        locked = false;
                        return;
                    }
                    // If already PNG, read raw bytes, otherwise convert via ImageIO
                    if (file.getName().toLowerCase().endsWith(".png")) {
                        imageBytes = Files.readAllBytes(file.toPath());
                    } else {
                        java.awt.image.BufferedImage img = ImageIO.read(file);
                        if (img == null) {
                            MeteorClient.LOG.error("[AnimePics] Could not read image: " + file.getName());
                            locked = false;
                            return;
                        }
                        var baos = new ByteArrayOutputStream();
                        ImageIO.write(img, "png", baos);
                        imageBytes = baos.toByteArray();
                    }
                } else {
                    MeteorClient.LOG.info("[AnimePics] Image URL: " + url);
                    var img = ImageIO.read(Http.get(url).sendInputStream());
                    var baos = new ByteArrayOutputStream();
                    ImageIO.write(img, "png", baos);
                    imageBytes = baos.toByteArray();
                }

                this.currentImageBytes = imageBytes;
                byte[] finalImageBytes = imageBytes;

                mc.execute(() -> {
                    try {
                        if (mc.getTextureManager() == null) return;
                        NativeImage nativeImage = NativeImage.read(new ByteArrayInputStream(finalImageBytes));
                        mc.getTextureManager().registerTexture(textureId,
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
