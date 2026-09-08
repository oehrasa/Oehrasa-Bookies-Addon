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
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static meteordevelopment.meteorclient.MeteorClient.mc;
import static meteordevelopment.meteorclient.utils.Utils.WHITE;

public class AnimePics extends HudElement {
    public static final HudElementInfo<AnimePics> INFO = new HudElementInfo<>(
        Addon.HUD_GROUP,
        "Anime-Pics",
        "Displays random Anime pictures/GIF from Nekos.life, WaifuIM, Safebooru, Yande.re, Konachan, PurrBot or a Local Folder.",
        AnimePics::create
    );

    private final AtomicBoolean locked = new AtomicBoolean(false);
    private boolean empty = true;
    private int ticks = 0;
    private final PointerBuffer saveFilters;         // file filters for save dialogue
    private volatile boolean manualRefresh = false; // true = next load must use fixed tag
    private final Identifier textureId;   // unique per element

    // Save Image support: the original bytes/name of whatever was loaded (not the converted display PNG)
    private byte[] currentRawBytes = null;
    private String currentImageName = null;

    // Metadata for the currently-loaded image, populated by whichever fetch*() resolved the URL.
    // Null for sources that don't return metadata (NekosLife/Safebooru/LocalFolder currently don't populate it).
    private volatile ImageMetadata lastMetadata = null;

    // Persistent GPU texture. Recreated only when the pixel dimensions actually change; otherwise every
    // frame swap (GIF animation or a same-size static image) reuses it via copyFrom()+upload() so no
    // repeated GL texture allocation.
    private NativeImageBackedTexture activeTexture = null;
    private int textureWidth = -1;
    private int textureHeight = -1;

    private List<NativeImage> gifFrames = null;
    private int[] gifDelaysMs = null;
    private int gifFrameIndex = 0;
    private int gifElapsedMs = 0;

    // Debounced live-refresh: typing into a tag field schedules a refresh a short delay after the
    // last keystroke instead of firing a request per character.
    private int liveRefreshDebounceTicks = -1; // -1 = no pending refresh
    private static final int DEBOUNCE_TICKS = 12; // ~600ms at 20 ticks/sec after the last change

    // Settings
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public enum Source {NekosLife, WaifuIM, Safebooru, YandeRE, Konachan, PurrBot, LocalFolder}

    public enum NekosTag {
        neko, waifu, fox_girl, hug, kiss, meow, gecg,
        avatar, feed, cuddle, woof, smug, tickle, slap, pat, wallpaper
    }

    public enum WaifimTag {
        waifu, ero, ecchi, oppai, hentai, milf, uniform, ass, maid,
        selfies, paizuri, oral, genshin_impact, raiden_shogun, marin_kitagawa,
        mori_calliope, kamisato_ayaka
    }

    public enum BooruRating {
        Explicit("rating:e"), Questionable("rating:q"), Safe("rating:s");

        public final String param;

        BooruRating(String param) {
            this.param = param;
        }
    }

    public enum PurrBotTag {fuck, blowjob, cum, anal, pussylick, solo, yaoi, yuri, neko}

    private static final List<String> NEKOS_CYCLE_LIST = List.of(
        "neko", "waifu", "fox_girl", "hug", "kiss", "meow", "lizard", "goose", "gecg",
        "avatar", "feed", "cuddle", "woof", "smug", "tickle", "slap", "pat", "wallpaper"
    ); // oomfie rfs <3

    private static final List<String> WAIFU_CYCLE_LIST = List.of(
        "waifu", "ero", "ecchi", "oppai", "hentai", "milf", "uniform", "ass", "maid",
        "selfies", "paizuri", "oral", "genshin impact", "raiden shogun", "marin kitagawa",
        "mori calliope", "kamisato ayaka"
    );

    private static final List<String> PURR_CYCLE_LIST = List.of(
        "fuck", "blowjob", "cum", "anal", "pussylick", "solo", "yaoi", "yuri", "neko"
    );

    private final Setting<Source> source = sgGeneral.add(new EnumSetting.Builder<Source>()
        .name("source")
        .description("Image source to use.")
        .defaultValue(Source.WaifuIM)
        .onChanged(v -> {
            loggedEmptyFolder = false;
            refreshNow();
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

    private final Setting<Boolean> waifuUseCustomTag = sgGeneral.add(new BoolSetting.Builder()
        .name("waifu-use-custom-tag")
        .description("Type a custom tag instead of using the predefined dropdown below.")
        .visible(() -> source.get() == Source.WaifuIM)
        .defaultValue(false)
        .onChanged(v -> refreshNow())
        .build()
    );

    private final Setting<String> waifuCustomTag = sgGeneral.add(new StringSetting.Builder()
        .name("waifu-custom-tag")
        .description("Custom WaifuIM tag keyword(s).")
        .visible(() -> source.get() == Source.WaifuIM && waifuUseCustomTag.get())
        .defaultValue("")
        .onChanged(v -> scheduleLiveRefresh())
        .build()
    );

    private final Setting<WaifimTag> waifuTag = sgGeneral.add(new EnumSetting.Builder<WaifimTag>()
        .name("waifu-tag")
        .description("Predefined image category for WaifuIM.")
        .visible(() -> source.get() == Source.WaifuIM && !waifuUseCustomTag.get())
        .defaultValue(WaifimTag.waifu)
        .onChanged(v -> refreshNow())
        .build()
    );

    private final Setting<Boolean> cycleWaifu = sgGeneral.add(new BoolSetting.Builder()
        .name("cycle-waifu")
        .description("Cycle through WaifuIM tags on each refresh. Ignored while a custom tag is set.")
        .visible(() -> source.get() == Source.WaifuIM && !waifuUseCustomTag.get())
        .defaultValue(true)
        .build()
    );

    private final Setting<String> safebooruTag = sgGeneral.add(new StringSetting.Builder()
        .name("safebooru-tag")
        .description("Tag for Safebooru images.")
        .visible(() -> source.get() == Source.Safebooru)
        .defaultValue("yuri")
        .onChanged(v -> scheduleLiveRefresh())
        .build()
    );

    private int waifuCycleIndex = 0;

    // Yande.re
    private final Setting<String> yandeTags = sgGeneral.add(new StringSetting.Builder()
        .name("yande-tags")
        .description("Search tags for Yande.re (space or comma separated). Leave blank for random.")
        .visible(() -> source.get() == Source.YandeRE)
        .defaultValue("")
        .onChanged(v -> scheduleLiveRefresh())
        .build()
    );

    private final Setting<BooruRating> yandeRating = sgGeneral.add(new EnumSetting.Builder<BooruRating>()
        .name("yande-rating")
        .description("Rating filter for Yande.re.")
        .visible(() -> source.get() == Source.YandeRE)
        .defaultValue(BooruRating.Safe)
        .onChanged(v -> refreshNow())
        .build()
    );

    private final Setting<Boolean> yandeRandomPage = sgGeneral.add(new BoolSetting.Builder()
        .name("yande-random-page")
        .description("Pull from a random results page instead of only the first page.")
        .visible(() -> source.get() == Source.YandeRE)
        .defaultValue(true)
        .build()
    );

    // Konachan
    private final Setting<String> konachanTags = sgGeneral.add(new StringSetting.Builder()
        .name("konachan-tags")
        .description("Search tags for Konachan (space or comma separated). Leave blank for random.")
        .visible(() -> source.get() == Source.Konachan)
        .defaultValue("")
        .onChanged(v -> scheduleLiveRefresh())
        .build()
    );

    private final Setting<BooruRating> konachanRating = sgGeneral.add(new EnumSetting.Builder<BooruRating>()
        .name("konachan-rating")
        .description("Rating filter for Konachan.")
        .visible(() -> source.get() == Source.Konachan)
        .defaultValue(BooruRating.Safe)
        .onChanged(v -> refreshNow())
        .build()
    );

    private final Setting<Boolean> konachanRandomPage = sgGeneral.add(new BoolSetting.Builder()
        .name("konachan-random-page")
        .description("Pull from a random results page instead of only the first page.")
        .visible(() -> source.get() == Source.Konachan)
        .defaultValue(true)
        .build()
    );

    // PurrBot
    private final Setting<PurrBotTag> purrTag = sgGeneral.add(new EnumSetting.Builder<PurrBotTag>()
        .name("purr-tag")
        .description("GIF category for PurrBot.")
        .visible(() -> source.get() == Source.PurrBot)
        .defaultValue(PurrBotTag.neko)
        .onChanged(v -> refreshNow())
        .build()
    );

    private final Setting<Boolean> cyclePurr = sgGeneral.add(new BoolSetting.Builder()
        .name("cycle-purr")
        .description("Cycle through PurrBot categories on each refresh.")
        .visible(() -> source.get() == Source.PurrBot)
        .defaultValue(true)
        .build()
    );

    private int purrCycleIndex = 0;

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

    // GIF settings
    private final Setting<Boolean> animateGifs = sgGeneral.add(new BoolSetting.Builder()
        .name("animate-gifs")
        .description("Play animated GIFs. Disable to show only the first frame.")
        .defaultValue(true)
        .onChanged(v -> refreshNow())
        .build()
    );

    private final Setting<Boolean> animateInMenus = sgGeneral.add(new BoolSetting.Builder()
        .name("animate-in-menus")
        .description("Keep animating GIFs while a menu/screen is open (inventory, chat, settings, etc).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> maxGifFrames = sgGeneral.add(new IntSetting.Builder()
        .name("max-gif-frames")
        .description("Max frames decoded from a GIF. Long or high-fps GIFs get truncated to this to bound memory and decode time.")
        .defaultValue(150)
        .min(2)
        .max(500)
        .sliderRange(2, 500)
        .build()
    );

    private final Setting<Integer> minFrameIntervalMs = sgGeneral.add(new IntSetting.Builder()
        .name("min-gif-frame-interval")
        .description("Minimum milliseconds between GIF frame swaps, regardless of the GIF's own timing. Raise this if fast GIFs cause stutter.")
        .defaultValue(150)
        .min(16)
        .max(1000)
        .sliderRange(16, 1000)
        .build()
    );

    // Local folder cycle
    private List<File> localImageFiles = new ArrayList<>();
    private int localImageIndex = 0;
    private boolean loggedEmptyFolder = false;
    private String loadedFolderPath = null; // path localImageFiles was last built from; null = never loaded

    public AnimePics() {
        super(INFO);
        this.textureId = Identifier.of("autobookshelf", "animepics_" + UUID.randomUUID());

        // Save dialogue filters: png (converted stills) + gif/jpg/jpeg (original downloaded formats)
        String[] filterPatterns = {"*.png", "*.gif", "*.jpg", "*.jpeg"};
        saveFilters = BufferUtils.createPointerBuffer(filterPatterns.length);
        for (String pattern : filterPatterns) {
            saveFilters.put(MemoryUtil.memASCII(pattern));
        }
        saveFilters.rewind();

        MeteorClient.EVENT_BUS.subscribe(this);
        updateSize();
    }

    @Override
    public void remove() {
        super.remove();
        MeteorClient.EVENT_BUS.unsubscribe(this);
        closeGifFrames(); // cached frames hold native memory — must be freed explicitly, GC won't do it
        if (mc.getTextureManager() != null) {
            mc.getTextureManager().destroyTexture(textureId);
        }
        activeTexture = null;
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
        folderRow = theme.horizontalList();
        row.add(folderRow);
        WButton selectFolderBtn = folderRow.add(theme.button("Select Folder")).widget();
        selectFolderBtn.action = this::selectLocalFolder;

        // Switch to online (visible only when LocalFolder)
        onlineRow = theme.horizontalList();
        row.add(onlineRow);
        WButton onlineBtn = onlineRow.add(theme.button("Select Online")).widget();
        onlineBtn.action = () -> {
            source.set(Source.WaifuIM);
            refreshNow();
        };

        updateSourceButtonsVisibility();
        return row;
    }

    private void updateSourceButtonsVisibility() {
        if (folderRow != null) folderRow.visible = source.get() != Source.LocalFolder;
        if (onlineRow != null) onlineRow.visible = source.get() == Source.LocalFolder;
    }

    // Forces next load to use the currently selected fixed category
    public void refreshNow() {
        liveRefreshDebounceTicks = -1; // an explicit refresh supersedes any pending debounced one
        manualRefresh = true;
        empty = true;
        ticks = 0; // next scheduled refresh is a full refreshRate ticks after this action
    }

    private void scheduleLiveRefresh() {
        liveRefreshDebounceTicks = DEBOUNCE_TICKS;
    }

    private void saveImage() {
        if (currentRawBytes == null || currentRawBytes.length == 0) {
            MeteorClient.LOG.info("[AnimePics] No image to save.");
            return;
        }

        String suggestedName = currentImageName != null ? currentImageName : "animepic.png";

        String path = TinyFileDialogs.tinyfd_saveFileDialog(
            "Save Image",
            new File(MeteorClient.FOLDER, suggestedName).getAbsolutePath(),
            saveFilters,
            null
        );

        if (path == null) return;   // user cancelled

        try {
            Files.write(Path.of(path), currentRawBytes);
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
            loadedFolderPath = null; // force ensureLocalFolderLoaded() to reload even if source didn't change
            loggedEmptyFolder = false;
            refreshNow();
        }
    }

    private void ensureLocalFolderLoaded() {
        String path = localFolderPath.get();
        if (!Objects.equals(path, loadedFolderPath)) {
            loadLocalFileList();
            loadedFolderPath = path;
            loggedEmptyFolder = false;
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
            return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif");
        });
        if (files != null) localImageFiles.addAll(List.of(files));
    }

    @EventHandler
    public void onTick(TickEvent.Post event) {
        if (mc.world == null) return;
        if (liveRefreshDebounceTicks >= 0) {
            liveRefreshDebounceTicks--;
            if (liveRefreshDebounceTicks < 0) refreshNow();
        }

        if (mc.options.hudHidden) return;

        boolean menuOpen = mc.currentScreen != null;
        if (menuOpen && !animateInMenus.get()) return;

        // Advance GIF animation at tick resolution (20Hz ceiling) rather than every render call, so
        // animation speed is decoupled from FPS.
        if (gifFrames != null && gifFrames.size() > 1 && animateGifs.get()) {
            gifElapsedMs += 50; // one client tick ≈ 50ms
            int delay = Math.max(gifDelaysMs[gifFrameIndex], minFrameIntervalMs.get());
            if (gifElapsedMs >= delay) {
                gifElapsedMs -= delay;
                gifFrameIndex = (gifFrameIndex + 1) % gifFrames.size();
                applyFrame(gifFrames.get(gifFrameIndex));
            }
        }

        // A menu being open only pauses fetching *new* images, not the animation above.
        if (menuOpen) return;

        // If source is local, keep the file list in sync with the current path (handles
        // startup ordering and folder changes) before deciding whether to refresh.
        if (source.get() == Source.LocalFolder) {
            ensureLocalFolderLoaded();
            if (localImageFiles.isEmpty()) {
                if (!loggedEmptyFolder) {
                    MeteorClient.LOG.error("[AnimePics] No images found in folder.");
                    loggedEmptyFolder = true;
                }
                return;
            }
        } else {
            loggedEmptyFolder = false;
        }

        if (pauseRefresh.get() && !manualRefresh) return; // manual refresh still fires even while paused

        ticks++;
        if (manualRefresh || empty || ticks >= refreshRate.get()) {
            ticks = 0;
            loadImage();
        }
    }

    @Override
    public void render(HudRenderer renderer) {
        if (empty || activeTexture == null) return; // loading is onTick's responsibility; render only draws

        Renderer2D.TEXTURE.begin();
        Renderer2D.TEXTURE.texQuad(x, y, imgWidth.get(), imgHeight.get(), WHITE);
        // Binding is no longer a separate GL call which the texture view + sampler
        // are passed straight into render(), which does the bind internally.
        Renderer2D.TEXTURE.render(activeTexture.getGlTextureView(), activeTexture.getSampler());
    }

    private void updateSize() { setSize(imgWidth.get(), imgHeight.get()); }

    // Fetch image URL based on selected source
    private String fetchImageUrl(boolean forceFixed) {
        return switch (source.get()) {
            case NekosLife -> fetchNekosLife(forceFixed);
            case WaifuIM -> fetchWaifuIM(forceFixed);
            case Safebooru -> fetchSafebooru();
            case YandeRE -> fetchMoebooruSource("https://yande.re/post.json", yandeTags.get(), yandeRating.get(),
                yandeRandomPage.get(), "Yande.re", "https://yande.re/post/show/");
            case Konachan -> fetchMoebooruSource("https://konachan.com/post.json", konachanTags.get(), konachanRating.get(),
                konachanRandomPage.get(), "Konachan", "https://konachan.com/post/show/");
            case PurrBot -> fetchPurrBot(forceFixed);
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
            MeteorClient.LOG.info("[AnimePics] Requesting: " + apiUrl);
            JsonObject response = AnimeHttp.getJson(apiUrl).getAsJsonObject();
            return response.get("url").getAsString();
        } catch (Exception e) {
            MeteorClient.LOG.error("[AnimePics] Nekos.life Error: " + e.getMessage());
            return null;
        }
    }

    private String fetchWaifuIM(boolean forceFixed) {
        String tag;
        if (waifuUseCustomTag.get() && !waifuCustomTag.get().isBlank()) {
            tag = waifuCustomTag.get().trim();
        } else if (!forceFixed && cycleWaifu.get()) {
            tag = WAIFU_CYCLE_LIST.get(waifuCycleIndex);
            waifuCycleIndex = (waifuCycleIndex + 1) % WAIFU_CYCLE_LIST.size();
        } else {
            tag = waifuTag.get().name().replace('_', ' ');
        }
        String apiUrl = "https://api.waifu.im/images?IncludedTags="
            + URLEncoder.encode(tag, StandardCharsets.UTF_8)
            + "&IsNsfw=All&PageSize=20";
        try {
            MeteorClient.LOG.info("[AnimePics] Requesting: " + apiUrl);
            JsonObject response = AnimeHttp.getJson(apiUrl).getAsJsonObject();
            JsonArray items = response.getAsJsonArray("items");
            if (items.isEmpty()) return null;
            JsonObject image = items.get(new Random().nextInt(items.size())).getAsJsonObject();

            ImageMetadata meta = new ImageMetadata(image.get("url").getAsString(), "WaifuIM");
            if (image.has("source") && !image.get("source").isJsonNull()) meta.sourceOrigin = image.get("source").getAsString();
            if (image.has("width")) meta.width = image.get("width").getAsInt();
            if (image.has("height")) meta.height = image.get("height").getAsInt();
            if (image.has("tags") && image.get("tags").isJsonArray()) {
                List<String> tagNames = new ArrayList<>();
                for (JsonElement t : image.getAsJsonArray("tags")) {
                    JsonObject tObj = t.getAsJsonObject();
                    if (tObj.has("name")) tagNames.add(tObj.get("name").getAsString());
                }
                meta.tags = tagNames;
            }
            lastMetadata = meta;

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

            MeteorClient.LOG.info("[AnimePics] Requesting: " + apiUrl);
            JsonElement result = AnimeHttp.getJson(apiUrl);
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

    private static String buildTagQuery(String userTags, String ratingParam) {
        List<String> parts = new ArrayList<>();
        if (ratingParam != null && !ratingParam.isEmpty()) parts.add(ratingParam);
        if (userTags != null) {
            String trimmed = userTags.trim();
            if (!trimmed.isEmpty()) {
                for (String token : trimmed.split("[,\\s]+")) {
                    if (!token.isEmpty()) parts.add(URLEncoder.encode(token, StandardCharsets.UTF_8));
                }
            }
        }
        return String.join("+", parts);
    }

    /**
     * Fetches one random post from a Moebooru-style (Yande.re/Konachan) JSON endpoint.
     */
    private JsonObject fetchMoebooruPost(String baseUrl, String tagQuery, int page) {
        String url = baseUrl + "?limit=50&page=" + page + (tagQuery.isEmpty() ? "" : "&tags=" + tagQuery);
        try {
            MeteorClient.LOG.info("[AnimePics] Requesting: " + url);
            JsonElement root = AnimeHttp.getJson(url);
            if (!root.isJsonArray()) return null;
            JsonArray posts = root.getAsJsonArray();
            if (posts.isEmpty()) return null;
            return posts.get(new Random().nextInt(posts.size())).getAsJsonObject();
        } catch (Exception e) {
            MeteorClient.LOG.error("[AnimePics] Moebooru fetch error (" + baseUrl + "): " + e.getMessage());
            return null;
        }
    }

    /**
     * Shared fetch path for Yande.re and Konachan. Includes smart fallback: if a random high
     * page comes back empty for a niche tag combination, retries page 1 before giving up so a
     * valid search still reliably returns a result.
     */
    private String fetchMoebooruSource(String baseUrl, String tags, BooruRating rating, boolean randomPage,
                                       String siteName, String postBaseUrl) {
        String tagQuery = buildTagQuery(tags, rating.param);
        int page = randomPage ? (new Random().nextInt(35) + 1) : 1;

        JsonObject post = fetchMoebooruPost(baseUrl, tagQuery, page);
        if (post == null && page != 1) {
            post = fetchMoebooruPost(baseUrl, tagQuery, 1);
        }
        if (post == null) return null;

        String imgUrl = (post.has("sample_url") && !post.get("sample_url").isJsonNull()) ? post.get("sample_url").getAsString()
            : (post.has("file_url") && !post.get("file_url").isJsonNull()) ? post.get("file_url").getAsString()
              : null;
        if (imgUrl == null) return null;

        ImageMetadata meta = new ImageMetadata(imgUrl, siteName);
        populateMoebooruMetadata(meta, post, postBaseUrl);
        lastMetadata = meta;

        return imgUrl;
    }

    private static void populateMoebooruMetadata(ImageMetadata meta, JsonObject post, String postBaseUrl) {
        if (post.has("id")) meta.postUrl = postBaseUrl + post.get("id").getAsString();
        if (post.has("author")) meta.author = post.get("author").getAsString();
        if (post.has("source") && !post.get("source").isJsonNull()) meta.sourceOrigin = post.get("source").getAsString();
        if (post.has("rating")) meta.rating = parseRating(post.get("rating").getAsString());
        if (post.has("width")) meta.width = post.get("width").getAsInt();
        if (post.has("height")) meta.height = post.get("height").getAsInt();
        if (post.has("tags")) meta.tags = Arrays.asList(post.get("tags").getAsString().split("\\s+"));
    }

    private static String parseRating(String r) {
        if (r == null) return "Unknown";
        if (r.equalsIgnoreCase("e") || r.equalsIgnoreCase("explicit")) return "Explicit";
        if (r.equalsIgnoreCase("q") || r.equalsIgnoreCase("questionable")) return "Questionable";
        if (r.equalsIgnoreCase("s") || r.equalsIgnoreCase("safe")) return "Safe";
        return r;
    }

    private String fetchPurrBot(boolean forceFixed) {
        String tag;
        if (!forceFixed && cyclePurr.get()) {
            tag = PURR_CYCLE_LIST.get(purrCycleIndex);
            purrCycleIndex = (purrCycleIndex + 1) % PURR_CYCLE_LIST.size();
        } else {
            tag = purrTag.get().name();
        }

        String url = "https://api.purrbot.site/v2/img/nsfw/" + tag + "/gif";
        try {
            MeteorClient.LOG.info("[AnimePics] Requesting: " + url);
            JsonObject resObj = AnimeHttp.getJson(url).getAsJsonObject();
            if (!resObj.has("link") || resObj.get("link").isJsonNull()) return null;
            String gifUrl = resObj.get("link").getAsString();

            ImageMetadata meta = new ImageMetadata(gifUrl, "PurrBot.site");
            meta.rating = "Explicit";
            meta.tags = List.of(tag, "nsfw_gif");
            lastMetadata = meta;

            return gifUrl;
        } catch (Exception e) {
            MeteorClient.LOG.error("[AnimePics] PurrBot Error: " + e.getMessage());
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

    /**
     * Derives a display filename (with extension) from an image URL, for use in Save Image.
     */
    private static String deriveFileName(String url) {
        if (url == null) return null;
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        try {
            name = URLDecoder.decode(name, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // keep raw name if decoding fails
        }
        if (name.isEmpty() || !name.contains(".")) return null; // no usable extension, caller falls back
        return name;
    }

    private void loadImage() {
        if (!locked.compareAndSet(false, true)) return; // already loading; avoids duplicate concurrent loads
        new Thread(() -> {
            try {
                boolean useFixed = manualRefresh;
                manualRefresh = false;
                lastMetadata = null; // cleared up front; fetch*() repopulates it on success

                String url = fetchImageUrl(useFixed);
                if (url == null) {
                    return;
                }

                byte[] rawBytes;
                String imageName;

                if (url.startsWith("local://")) {
                    File file = getNextLocalImage();
                    if (file == null) {
                        return;
                    }
                    rawBytes = Files.readAllBytes(file.toPath());
                    imageName = file.getName();
                } else {
                    MeteorClient.LOG.info("[AnimePics] Image URL: " + url);
                    rawBytes = AnimeHttp.getBytes(url);
                    imageName = deriveFileName(url);
                }

                if (imageName == null) {
                    String ext = isGIF(rawBytes) ? ".gif" : isPNG(rawBytes) ? ".png" : ".jpg";
                    imageName = "animepic" + ext;
                }

                currentRawBytes = rawBytes;
                currentImageName = imageName;

                if (isGIF(rawBytes)) {
                    handleGif(rawBytes);
                } else {
                    handleStaticImage(rawBytes, url);
                }
            } catch (Exception e) {
                MeteorClient.LOG.error("[AnimePics] " + e.getMessage());
            } finally {
                locked.set(false);
            }
        }).start();
        updateSize();
    }

    private void handleStaticImage(byte[] rawBytes, String url) throws IOException {
        NativeImage frame = isPNG(rawBytes) ? NativeImage.read(rawBytes) : null;
        if (frame == null) {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(rawBytes));
            if (img == null) throw new IOException("Unsupported image format for URL: " + url);
            frame = bufferedImageToNativeImage(img);
        }

        NativeImage finalFrame = frame;
        mc.execute(() -> {
            closeGifFrames(); // stop and free any previous animation
            applyFrame(finalFrame);
            finalFrame.close(); // was only a copyFrom() source, not owned by the texture
            empty = false;
            MeteorClient.LOG.info("[AnimePics] Image loaded!");
        });
    }

    private void handleGif(byte[] rawBytes) throws IOException {
        // Never decode above what's actually going to be rendered.
        int decodeCap = (int) Math.max(imgWidth.get(), imgHeight.get());

        if (!animateGifs.get()) {
            BufferedImage first = ImageIO.read(new ByteArrayInputStream(rawBytes)); // ImageIO reads only frame 0 for GIFs
            if (first == null) throw new IOException("Could not read GIF");
            NativeImage frame = bufferedImageToNativeImage(first);

            mc.execute(() -> {
                closeGifFrames();
                applyFrame(frame);
                frame.close();
                empty = false;
                MeteorClient.LOG.info("[AnimePics] Image loaded! (GIF animation disabled)");
            });
            return;
        }

        List<DecodedFrame> decoded = decodeGif(rawBytes, maxGifFrames.get(), decodeCap);
        if (decoded.isEmpty()) throw new IOException("GIF had no readable frames");

        List<NativeImage> frames = new ArrayList<>(decoded.size());
        int[] delays = new int[decoded.size()];
        for (int i = 0; i < decoded.size(); i++) {
            frames.add(bufferedImageToNativeImage(decoded.get(i).image()));
            delays[i] = decoded.get(i).delayMs();
        }

        mc.execute(() -> {
            closeGifFrames(); // free the previous GIF's cached frames before replacing them
            gifFrames = frames;
            gifDelaysMs = delays;
            gifFrameIndex = 0;
            gifElapsedMs = 0;
            applyFrame(frames.get(0));
            empty = false;
            MeteorClient.LOG.info("[AnimePics] Image loaded! (" + frames.size() + " GIF frames)");
        });
    }

    /**
     * Closes and releases all cached GIF frame NativeImages. Must be called before replacing them or on removal.
     */
    private void closeGifFrames() {
        if (gifFrames != null) {
            for (NativeImage frame : gifFrames) frame.close();
        }
        gifFrames = null;
        gifDelaysMs = null;
    }

    private static List<DecodedFrame> decodeGif(byte[] gifBytes, int maxFrames, int decodeCap) throws IOException {
        List<DecodedFrame> frames = new ArrayList<>();
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
        if (!readers.hasNext()) throw new IOException("No GIF reader available");
        ImageReader reader = readers.next();

        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(gifBytes))) {
            reader.setInput(iis, false);
            int frameCount = reader.getNumImages(true);

            // For long/high-fps GIFs, skip every other source frame so the full loop is still
            // covered (at lower temporal resolution) instead of decoding only the first maxFrames
            // frames and cutting off partway through. The index limit is scaled by `step` so the
            // walked range actually extends to cover more of the GIF, not just fewer frames of
            // the same range.
            int step = (frameCount > 80 && maxFrames <= 60) ? 2 : 1;
            int limit = Math.min(frameCount, maxFrames * step);

            // Logical screen size
            int screenW = -1, screenH = -1;
            IIOMetadata streamMetadata = reader.getStreamMetadata();
            if (streamMetadata != null) {
                IIOMetadataNode streamRoot = (IIOMetadataNode) streamMetadata.getAsTree("javax_imageio_gif_stream_1.0");
                IIOMetadataNode lsd = getChildNode(streamRoot, "LogicalScreenDescriptor");
                if (lsd != null) {
                    screenW = parseIntSafe(lsd.getAttribute("logicalScreenWidth"), -1);
                    screenH = parseIntSafe(lsd.getAttribute("logicalScreenHeight"), -1);
                }
            }

            BufferedImage canvas = null;
            BufferedImage restoreSnapshot = null;

            for (int i = 0; i < limit; i += step) {
                BufferedImage frame = reader.read(i);
                IIOMetadata metadata = reader.getImageMetadata(i);
                IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree("javax_imageio_gif_image_1.0");

                int delayCs = 10; // default 100ms if metadata is missing
                String disposal = "none";
                int fx = 0, fy = 0;

                IIOMetadataNode gce = getChildNode(root, "GraphicControlExtension");
                if (gce != null) {
                    delayCs = parseIntSafe(gce.getAttribute("delayTime"), delayCs);
                    String disp = gce.getAttribute("disposalMethod");
                    if (disp != null && !disp.isEmpty()) disposal = disp;
                }
                IIOMetadataNode descriptor = getChildNode(root, "ImageDescriptor");
                if (descriptor != null) {
                    fx = parseIntSafe(descriptor.getAttribute("imageLeftPosition"), 0);
                    fy = parseIntSafe(descriptor.getAttribute("imageTopPosition"), 0);
                }

                if (canvas == null) {
                    int w = screenW > 0 ? screenW : Math.max(frame.getWidth(), fx + frame.getWidth());
                    int h = screenH > 0 ? screenH : Math.max(frame.getHeight(), fy + frame.getHeight());
                    canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                }

                if ("restoreToPrevious".equals(disposal)) {
                    restoreSnapshot = copyImage(canvas);
                }

                Graphics2D g = canvas.createGraphics();
                g.drawImage(frame, fx, fy, null);
                g.dispose();

                BufferedImage snapshot = copyImage(canvas);
                BufferedImage optimized = decodeCap > 0 ? downscaleIfNeeded(snapshot, decodeCap) : snapshot;
                frames.add(new DecodedFrame(optimized, Math.max(delayCs * 10 * step, 20)));

                switch (disposal) {
                    case "restoreToBackgroundColor" -> {
                        Graphics2D clear = canvas.createGraphics();
                        clear.setComposite(AlphaComposite.Clear);
                        clear.fillRect(fx, fy, frame.getWidth(), frame.getHeight());
                        clear.dispose();
                    }
                    case "restoreToPrevious" -> {
                        if (restoreSnapshot != null) canvas = restoreSnapshot;
                    }
                    default -> { /* "none" / "doNotDispose" / "unspecified" so leave canvas as-is */ }
                }
            }
        } finally {
            reader.dispose();
        }
        return frames;
    }

    private record DecodedFrame(BufferedImage image, int delayMs) {
    }

    private static IIOMetadataNode getChildNode(IIOMetadataNode root, String name) {
        if (root == null) return null;
        for (int i = 0; i < root.getLength(); i++) {
            if (root.item(i).getNodeName().equalsIgnoreCase(name)) return (IIOMetadataNode) root.item(i);
        }
        return null;
    }

    private static int parseIntSafe(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static BufferedImage copyImage(BufferedImage src) {
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return copy;
    }

    /**
     * Downscales an image to fit within maxDim x maxDim, preserving aspect ratio. No-op if already smaller.
     */
    private static BufferedImage downscaleIfNeeded(BufferedImage src, int maxDim) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= maxDim && h <= maxDim) return src;

        double scale = Math.min((double) maxDim / w, (double) maxDim / h);
        int targetW = Math.max(1, (int) (w * scale));
        int targetH = Math.max(1, (int) (h * scale));

        BufferedImage scaled = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = scaled.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        g2.drawImage(src, 0, 0, targetW, targetH, null);
        g2.dispose();
        return scaled;
    }

    private static byte[] bufferedImageToPng(BufferedImage img) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    /**
     * Converts a BufferedImage to a NativeImage via a PNG round-trip.
     */
    private static NativeImage bufferedImageToNativeImage(BufferedImage img) throws IOException {
        return NativeImage.read(bufferedImageToPng(img));
    }

    private void applyFrame(NativeImage frame) {
        if (mc.getTextureManager() == null) return;

        if (activeTexture == null || frame.getWidth() != textureWidth || frame.getHeight() != textureHeight) {
            mc.getTextureManager().destroyTexture(textureId); // closes the previous texture + its owned image, if any
            NativeImage owned = new NativeImage(frame.getWidth(), frame.getHeight(), false);
            owned.copyFrom(frame);
            Supplier<String> nameSupplier = textureId::toString;
            activeTexture = new NativeImageBackedTexture(nameSupplier, owned);
            mc.getTextureManager().registerTexture(textureId, activeTexture);
            textureWidth = frame.getWidth();
            textureHeight = frame.getHeight();
        } else {
            NativeImage current = activeTexture.getImage();
            if (current == null) return; // texture was disposed elsewhere; the next load will recreate it
            current.copyFrom(frame);
            activeTexture.upload();
        }
    }

    /** Checks if the given bytes start with the PNG signature. */
    private static boolean isPNG(byte[] bytes) {
        if (bytes.length < 8) return false;
        // PNG signature: 0x89 P N G \r \n 0x1A \n
        return bytes[0] == (byte)0x89 &&
            bytes[1] == 0x50 &&
            bytes[2] == 0x4E &&
            bytes[3] == 0x47 &&
            bytes[4] == 0x0D &&
            bytes[5] == 0x0A &&
            bytes[6] == 0x1A &&
            bytes[7] == 0x0A;
    }

    /**
     * Checks if the given bytes start with the GIF signature (GIF87a or GIF89a).
     */
    private static boolean isGIF(byte[] bytes) {
        if (bytes.length < 6) return false;
        return bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8'
            && (bytes[4] == '7' || bytes[4] == '9') && bytes[5] == 'a';
    }
}
