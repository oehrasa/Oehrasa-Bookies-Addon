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
import meteordevelopment.meteorclient.utils.network.Http;
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
import java.io.*;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;

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
    private final PointerBuffer saveFilters;         // file filters for save dialogue
    private volatile boolean manualRefresh = false; // true = next load must use fixed tag
    private final Identifier textureId;   // unique per element

    // Save Image support: the original bytes/name of whatever was loaded (not the converted display PNG)
    private byte[] currentRawBytes = null;
    private String currentImageName = null;

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
        .onChanged(v -> {
            if (v == Source.LocalFolder) loadLocalFileList();
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

    // GIF settings
    private final Setting<Boolean> animateGifs = sgGeneral.add(new BoolSetting.Builder()
        .name("animate-gifs")
        .description("Play animated GIFs. Disable to show only the first frame — much cheaper on CPU/GPU and memory.")
        .defaultValue(true)
        .onChanged(v -> refreshNow())
        .build()
    );

    private final Setting<Boolean> animateInMenus = sgGeneral.add(new BoolSetting.Builder()
        .name("animate-in-menus")
        .description("Keep animating GIFs while a menu/screen is open (inventory, chat, settings, etc). Off by default since there's no visual benefit while the HUD isn't drawn.")
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
        manualRefresh = true;
        empty = true;
        ticks = 0;
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
            return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif");
        });
        if (files != null) localImageFiles.addAll(List.of(files));
    }

    @EventHandler
    public void onTick(TickEvent.Post event) {
        if (mc.options.hudHidden) return;

        boolean menuOpen = mc.currentScreen != null;
        if (menuOpen && !animateInMenus.get()) return;

        if (mc.world == null) return;

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

        if (activeTexture == null) return;

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
        if (locked) return;
        new Thread(() -> {
            try {
                locked = true;
                boolean useFixed = manualRefresh;
                manualRefresh = false;

                String url = fetchImageUrl(useFixed);
                if (url == null) {
                    locked = false;
                    return;
                }

                byte[] rawBytes;
                String imageName;

                if (url.startsWith("local://")) {
                    File file = getNextLocalImage();
                    if (file == null) {
                        locked = false;
                        return;
                    }
                    rawBytes = Files.readAllBytes(file.toPath());
                    imageName = file.getName();
                } else {
                    MeteorClient.LOG.info("[AnimePics] Image URL: " + url);
                    try (InputStream stream = Http.get(url).sendInputStream()) {
                        rawBytes = stream.readAllBytes();
                    }
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
            }
            locked = false;
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

        List<DecodedFrame> decoded = decodeGif(rawBytes, maxGifFrames.get());
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

    private static List<DecodedFrame> decodeGif(byte[] gifBytes, int maxFrames) throws IOException {
        List<DecodedFrame> frames = new ArrayList<>();
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
        if (!readers.hasNext()) throw new IOException("No GIF reader available");
        ImageReader reader = readers.next();

        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(gifBytes))) {
            reader.setInput(iis, false);
            int frameCount = reader.getNumImages(true);
            int limit = Math.min(frameCount, maxFrames);

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

            for (int i = 0; i < limit; i++) {
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

                frames.add(new DecodedFrame(copyImage(canvas), Math.max(delayCs * 10, 20)));

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
