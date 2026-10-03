package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.utils.AreaSelector;
import com.AutoBookshelf.addon.utils.BookUtils;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import meteordevelopment.meteorclient.events.entity.player.InteractBlockEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BookshelfFiller extends Module {
    private int delayLeft = 0;
    private BlockPos targetPos = null;
    private int retryCount = 0;
    private int stuckCounter = 0;
    private BlockPos lastPos = null;
    private int lastSlot = -1;
    private boolean isFilling = false;

    private boolean allFull = false;
    private boolean pendingReset = false;

    private int currentRow = 0;
    private int currentCol = 0;
    private int currentSlot = 0;
    private boolean fillingBottomHalf = false;
    private List<List<BlockPos>> rows = new ArrayList<>();

    private List<Integer> sortedBookSlots = new ArrayList<>();
    private int currentBookIndex = 0;

    private String currentBookTitle = "";
    private String currentBookAuthor = "";
    private int currentBookSlot = -1;

    private String lastDisplayedBookKey = "";
    private int lastDisplayedTick = 0;

    private boolean waitingForRetry = false;
    private int retryWaitCounter = 0;
    private boolean hasShownNoBooksMessage = false;

    private boolean extracting = false;
    private List<ExtractTarget> extractQueue = new ArrayList<>();
    private int extractIndex = 0;
    private int extractDelay = 0;
    private int extractCount = 0;
    private int extractTotal = 0;
    private int originalSlot = -1;
    private boolean extractingSingleBlock = false;
    private BlockPos singleBlockPos = null;
    private List<Integer> singleBlockSlots = new ArrayList<>();
    // Slots chosen at start (respecting extract-mode): retries only re-target these,
    // so LIMITED mode doesn't end up extracting everything anyway.
    private List<Integer> extractOriginalSlots = new ArrayList<>();
    private int singleBlockSlotIndex = 0;
    private int extractionRetryCount = 0;
    private static final int MAX_EXTRACTION_RETRIES = 3;
    // The shelf slot whose take is awaiting server confirmation (client block-state
    // flips to empty). The extract loop only advances after the confirm or a timeout,
    // so it never re-clicks a slot the server already emptied while the previous book
    // is still in hotbar transit - the failure mode that placed a book back in.
    private int pendingExtractSlot = -1;
    private int pendingExtractTicks = 0;
    private static final int EXTRACT_CONFIRM_MAX_TICKS = 40;

    private int dedicatedSwapSlot = -1;

    private static final Pattern TITLE_NUMBER_PATTERN = Pattern.compile("\\d+");

    private int cachedBookSlot = -1;

    private String displayText = "";
    private int displayTimer = 0;

    // Book log (which book title went into which shelf slot) - persisted to disk, hover display.
    // loggedMillis is the wall-clock write time of the entry (0 = loaded from disk); it powers
    // the "remember" grace window so fresh placements aren't pruned while the server syncs.
    private record BookLogEntry(String title, String author, long loggedMillis) {
    }

    private final Map<String, Map<String, Map<Integer, BookLogEntry>>> bookLog = new HashMap<>();
    private File bookLogFile;
    private boolean bookLogLoaded = false;
    private boolean bookLogLoadFailed = false;
    private boolean bookLogDirty = false;
    private long bookLogLastSaveMs = 0;
    private int bookLogVerifyTicks = 0;
    private static final long BOOK_LOG_SAVE_INTERVAL_MS = 2000;
    private static final int BOOK_LOG_VERIFY_INTERVAL_TICKS = 20;
    private static final long BOOK_LOG_REMEMBER_MS = 30_000;
    // A book whose logged slot reads empty is "claimed" by that slot whenever the
    // refill pass could act on it (player in range, chunk loaded, chiseled shelf):
    // while the fill is placing into new slots a book taken out of an old slot stays
    // reserved for its slot, the refill pass returns it there during fill pauses
    // (waiting for books / inter-place delay) instead of it flowing into the next new
    // slot.
    private static final double REFILL_MAX_DISTANCE = 6.0;

    private long claimedTitlesTick = -1;
    private final Set<String> claimedTitlesCache = new HashSet<>();

    // Idle refill pass: re-puts removed books into their remembered slots.
    private static class RefillTask {
        final BlockPos pos;
        final int slot;
        final String title;
        final String author;

        RefillTask(BlockPos pos, int slot, String title, String author) {
            this.pos = pos;
            this.slot = slot;
            this.title = title;
            this.author = author;
        }
    }

    private final List<RefillTask> refillQueue = new ArrayList<>();
    private int refillIndex = 0;
    private int refillDelay = 0;
    private int refillScanTicks = 0;
    // Consecutive failed placement clicks per refill slot (scoped by world/dimension and
    // shelf position). A slot that keeps failing must be abandoned, or the endless retry
    // also re-arms the remember grace on every attempt, keeping its stale log entry alive.
    // Counts are cleared in verifyBookLog whenever a slot is seen occupied.
    private final Map<String, Integer> refillStrikeCount = new HashMap<>();
    private static final int REFILL_MAX_STRIKES = 3;

    private String refillStrikeKey(BlockPos pos, int slot) {
        return bookLogScopeKey() + "|" + shelfPosKey(pos) + ":" + slot;
    }

    // Book counter state
    private boolean countingMode = false;
    private BlockPos countPos1 = null;
    private BlockPos countPos2 = null;

    // Cache for book counts
    private final Map<String, Map<String, Integer>> savedCounts = new HashMap<>();
    private File cacheFile;

    private static class BookInfo {
        final String title;
        final String author;
        final List<Integer> numbers;

        BookInfo(String title, String author, List<Integer> numbers) {
            this.title = title;
            this.author = author;
            this.numbers = numbers;
        }
    }

    private static class ExtractTarget {
        BlockPos pos;
        int slot;
        ExtractTarget(BlockPos pos, int slot) {
            this.pos = pos;
            this.slot = slot;
        }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSelection = settings.createGroup("Selection");
    private final SettingGroup sgExtract = settings.createGroup("Extract");
    private final SettingGroup sgFilter = settings.createGroup("Filter");
    private final SettingGroup sgDisplay = settings.createGroup("Display");
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgProtection = settings.createGroup("Protection");
    private final SettingGroup sgCounter = settings.createGroup("Book Counter");
    private final SettingGroup sgBookLog = settings.createGroup("Book Log");

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay between actions in ticks.")
        .defaultValue(10)
        .min(0)
        .sliderMax(30)
        .build()
    );

    private final Setting<Boolean> continuousChecking = sgGeneral.add(new BoolSetting.Builder()
        .name("continuous-checking")
        .description("Never stop checking for books, continuously monitor inventory for new books.")
        .defaultValue(true)
        .build()
    );

    private final Setting<FillLayout> fillLayout = sgGeneral.add(new EnumSetting.Builder<FillLayout>()
        .name("fill-layout")
        .description("Order across the wall and within each shelf: bottom-first fills the lowest Y layer of the selected wall upward (bottom shelf row before the top); top-first is the reverse.")
        .defaultValue(FillLayout.TOP_FIRST)
        .build()
    );

    private final AreaSelector areaSelector = new AreaSelector(sgSelection, sgRender, Items.NETHERITE_AXE);

    private final Setting<Item> counterTool = sgCounter.add(new ItemSetting.Builder()
        .name("counter-tool")
        .description("Tool to use for counting books in bookshelves (right-click to select area).")
        .defaultValue(Items.NETHERITE_PICKAXE)
        .build()
    );

    private final Setting<Boolean> requireCounterTool = sgCounter.add(new BoolSetting.Builder()
        .name("require-counter-tool")
        .description("Require the counter tool to be held to count books.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> persistentCache = sgCounter.add(new BoolSetting.Builder()
        .name("persistent-cache")
        .description("Save book counts to disk and load on startup.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showCountMessages = sgCounter.add(new BoolSetting.Builder()
        .name("show-count-messages")
        .description("Show messages when counting books.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showChanges = sgCounter.add(new BoolSetting.Builder()
        .name("show-changes")
        .description("Show detailed changes when updating counts (new/updated/removed bookshelves).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> resetAllCounts = sgCounter.add(new BoolSetting.Builder()
        .name("reset-all-counts")
        .description("RESET ALL SAVED BOOK COUNTS.")
        .defaultValue(false)
        .onChanged(value -> {
            if (value) {
                resetAllCounts();
                info("§aCounts reset! You can now turn this setting off.");
            }
        })
        .build()
    );

    private final Setting<Boolean> bookLogEnabled = sgBookLog.add(new BoolSetting.Builder()
        .name("book-log")
        .description("Track which book title is placed into which chiseled bookshelf slot and persist it to AutoBookshelf/bookshelf_books.json. Entries are removed when the slot empties.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hoverShowBooks = sgBookLog.add(new BoolSetting.Builder()
        .name("hover-display")
        .description("Show the logged book title above a chiseled bookshelf slot while hovering it.")
        .visible(bookLogEnabled::get)
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> hoverTextScale = sgBookLog.add(new DoubleSetting.Builder()
        .name("hover-text-scale")
        .description("Text scale of the hovered book title.")
        .visible(bookLogEnabled::get)
        .defaultValue(1.1)
        .min(0.5)
        .sliderMax(4)
        .build()
    );

    private final Setting<SettingColor> hoverTextColor = sgBookLog.add(new ColorSetting.Builder()
        .name("hover-text-color")
        .description("Colour of the hovered book title text.")
        .visible(bookLogEnabled::get)
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build()
    );

    private final Setting<SettingColor> hoverAuthorColor = sgBookLog.add(new ColorSetting.Builder()
        .name("hover-author-color")
        .description("Colour of the hovered book author line.")
        .visible(bookLogEnabled::get)
        .defaultValue(new SettingColor(140, 140, 140, 255))
        .build()
    );

    private final Setting<Boolean> remember = sgBookLog.add(new BoolSetting.Builder()
        .name("remember")
        .description("Keep matching the book that is being put into each slot for a short period even if the slot reads empty")
        .visible(bookLogEnabled::get)
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> refillRemoved = sgBookLog.add(new BoolSetting.Builder()
        .name("refill-removed")
        .description("When idle, scan the log and place the same book back into any remembered slot that has become empty.")
        .visible(remember::get)
        .defaultValue(true)
        .build()
    );

    private final Setting<Item> extractTool = sgExtract.add(new ItemSetting.Builder()
        .name("extract-tool")
        .description("Tool to use for extracting books (right-click on bookshelf).")
        .defaultValue(Items.ENCHANTED_GOLDEN_APPLE)
        .build()
    );

    private final Setting<Boolean> requireExtractTool = sgExtract.add(new BoolSetting.Builder()
        .name("require-extract-tool")
        .description("Require the extract tool to be held to extract books.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> extractDelayTicks = sgExtract.add(new IntSetting.Builder()
        .name("extract-delay")
        .description("Delay between extracting each book in ticks.")
        .defaultValue(8)
        .min(2)
        .max(40)
        .build()
    );

    private final Setting<ExtractMode> extractMode = sgExtract.add(new EnumSetting.Builder<ExtractMode>()
        .name("extract-mode")
        .description("Which books to extract from a single bookshelf.")
        .defaultValue(ExtractMode.ALL)
        .build()
    );

    private final Setting<Integer> maxExtractBooks = sgExtract.add(new IntSetting.Builder()
        .name("max-extract-books")
        .description("Maximum number of books to extract from a single bookshelf.")
        .defaultValue(3)
        .min(1)
        .max(6)
        .sliderMax(6)
        .visible(() -> extractMode.get() == ExtractMode.LIMITED)
        .build()
    );

    private final Setting<Boolean> showExtractMessages = sgExtract.add(new BoolSetting.Builder()
        .name("show-extract-messages")
        .description("Show messages when extracting books.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> enableFilter = sgFilter.add(new BoolSetting.Builder()
        .name("enable-filter")
        .description("Enable book title filtering (extracts numbers from titles).")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<Item>> protectedItems = sgProtection.add(new ItemListSetting.Builder()
        .name("protected-items")
        .description("Items that will never be replaced (will use dedicated swap slot instead).")
        .defaultValue(new ArrayList<>(Arrays.asList(
            Items.NETHERITE_PICKAXE,
            Items.NETHERITE_AXE,
            Items.NETHERITE_SHOVEL,
            Items.NETHERITE_SWORD,
            Items.TOTEM_OF_UNDYING,
            Items.ENCHANTED_GOLDEN_APPLE,
            Items.SHULKER_BOX,
            Items.WHITE_SHULKER_BOX,
            Items.ORANGE_SHULKER_BOX,
            Items.MAGENTA_SHULKER_BOX,
            Items.LIGHT_BLUE_SHULKER_BOX,
            Items.YELLOW_SHULKER_BOX,
            Items.LIME_SHULKER_BOX,
            Items.PINK_SHULKER_BOX,
            Items.GRAY_SHULKER_BOX,
            Items.LIGHT_GRAY_SHULKER_BOX,
            Items.CYAN_SHULKER_BOX,
            Items.PURPLE_SHULKER_BOX,
            Items.BLUE_SHULKER_BOX,
            Items.BROWN_SHULKER_BOX,
            Items.GREEN_SHULKER_BOX,
            Items.RED_SHULKER_BOX,
            Items.BLACK_SHULKER_BOX
        )))
        .build()
    );

    private final Setting<Integer> dedicatedSwapSlotIndex = sgProtection.add(new IntSetting.Builder()
        .name("dedicated-swap-slot")
        .description("Hotbar slot (0-8) to use for swapping books.")
        .defaultValue(8)
        .min(0)
        .max(8)
        .sliderMax(8)
        .build()
    );

    private final Setting<Boolean> useDedicatedSlot = sgProtection.add(new BoolSetting.Builder()
        .name("use-dedicated-slot")
        .description("Use a dedicated hotbar slot for book swapping (recommended).")
        .defaultValue(true)
        .build()
    );

    // Display settings
    private final Setting<Boolean> showBookInChat = sgDisplay.add(new BoolSetting.Builder()
        .name("show-book-in-chat")
        .description("Show the book title and author in chat when placing.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> showBookCooldown = sgDisplay.add(new BoolSetting.Builder()
        .name("show-book-cooldown")
        .description("Show book info chat with a cooldown to prevent spam.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> chatCooldownTicks = sgDisplay.add(new IntSetting.Builder()
        .name("chat-cooldown-ticks")
        .description("Minimum ticks between showing book info in chat.")
        .defaultValue(20)
        .min(0)
        .sliderMax(100)
        .visible(showBookCooldown::get)
        .build()
    );

    private final Setting<Boolean> showOnScreen = sgDisplay.add(new BoolSetting.Builder()
        .name("show-on-screen")
        .description("Show current book status on screen.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> onScreenDuration = sgDisplay.add(new IntSetting.Builder()
        .name("on-screen-duration")
        .description("How many ticks to show messages on screen.")
        .defaultValue(40)
        .min(10)
        .sliderMax(100)
        .visible(showOnScreen::get)
        .build()
    );

    private final Setting<Integer> retryCheckInterval = sgDisplay.add(new IntSetting.Builder()
        .name("retry-check-interval")
        .description("How many ticks to wait between checking for books when none are found.")
        .defaultValue(20)
        .min(5)
        .sliderMax(100)
        .visible(() -> continuousChecking.get())
        .build()
    );

    private final Setting<Boolean> verboseChecking = sgDisplay.add(new BoolSetting.Builder()
        .name("verbose-checking")
        .description("Show messages when waiting for and finding new books.")
        .defaultValue(true)
        .visible(() -> continuousChecking.get())
        .build()
    );

    private enum ExtractMode {
        ALL("All Books"),
        LIMITED("Limited Amount.");

        private final String title;
        ExtractMode(String title) { this.title = title; }
        @Override public String toString() { return title; }
    }

    private enum FillLayout {
        TOP_FIRST("Top row first"),
        BOTTOM_FIRST("Bottom row first");

        private final String title;

        FillLayout(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    public BookshelfFiller() {
        super(Addon.CATEGORY, "Bookshelf-Filler", "oeh Yuri romcom bookshelves restocker.");
    }

    private void setDisplayText(String text) {
        if (showOnScreen.get()) {
            this.displayText = text;
            this.displayTimer = onScreenDuration.get();
        }
    }

    private boolean isProtectedItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return protectedItems.get().contains(stack.getItem());
    }

    private int findSwapSlot() {
        if (useDedicatedSlot.get()) {
            return dedicatedSwapSlotIndex.get();
        }

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!isProtectedItem(stack)) {
                return i;
            }
        }
        return 0;
    }

    private void fullReset() {
        extractingSingleBlock = false;
        singleBlockPos = null;
        singleBlockSlots.clear();
        extractOriginalSlots.clear();
        singleBlockSlotIndex = 0;
        originalSlot = -1;
        extractionRetryCount = 0;
        pendingExtractSlot = -1;
        pendingExtractTicks = 0;

        areaSelector.reset();
        targetPos = null;

        allFull = false;
        fillingBottomHalf = false;
        currentRow = 0;
        currentCol = 0;
        currentSlot = 0;
        retryCount = 0;
        stuckCounter = 0;
        lastPos = null;
        lastSlot = -1;
        isFilling = false;
        waitingForRetry = false;
        retryWaitCounter = 0;
        hasShownNoBooksMessage = false;
        rows.clear();
        sortedBookSlots.clear();
        currentBookIndex = 0;
        cachedBookSlot = -1;
        currentBookTitle = "";
        currentBookAuthor = "";
        currentBookSlot = -1;

        countingMode = false;
        countPos1 = null;
        countPos2 = null;

        displayText = "";
        displayTimer = 0;
    }

    private String getWorldName() {
        if (mc.level == null) return "unknown";
        return mc.level.dimension().identifier().toString();
    }

    private void loadCache() {
        if (!persistentCache.get()) return;
        try {
            cacheFile = new File(mc.gameDirectory, "AutoBookshelf/bookshelf_counts.json");
            if (cacheFile.exists()) {
                String json = new String(Files.readAllBytes(cacheFile.toPath()));
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();

                for (Map.Entry<String, com.google.gson.JsonElement> entry : root.entrySet()) {
                    String worldName = entry.getKey();
                    JsonObject worldData = entry.getValue().getAsJsonObject();
                    Map<String, Integer> counts = new HashMap<>();

                    for (Map.Entry<String, com.google.gson.JsonElement> posEntry : worldData.entrySet()) {
                        counts.put(posEntry.getKey(), posEntry.getValue().getAsInt());
                    }
                    savedCounts.put(worldName, counts);
                }

                if (showCountMessages.get()) {
                    info("§aLoaded cache: §f" + savedCounts.size() + " §aworlds");
                }
            }
        } catch (Exception e) {
            error("Failed to load cache: " + e.getMessage());
        }
    }

    private void saveCache() {
        if (!persistentCache.get()) return;
        if (cacheFile == null) {
            cacheFile = new File(mc.gameDirectory, "AutoBookshelf/bookshelf_counts.json");
        }

        try {
            JsonObject root = new JsonObject();
            for (Map.Entry<String, Map<String, Integer>> worldEntry : savedCounts.entrySet()) {
                JsonObject worldData = new JsonObject();
                for (Map.Entry<String, Integer> posEntry : worldEntry.getValue().entrySet()) {
                    worldData.addProperty(posEntry.getKey(), posEntry.getValue());
                }
                root.add(worldEntry.getKey(), worldData);
            }

            Files.createDirectories(cacheFile.getParentFile().toPath());
            Files.write(cacheFile.toPath(), root.toString().getBytes());
        } catch (Exception e) {
            error("Failed to save cache: " + e.getMessage());
        }
    }

    private String shelfPosKey(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static BlockPos parseShelfPosKey(String key) {
        try {
            String[] parts = key.split(",");
            if (parts.length != 3) return null;
            return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (Exception e) {
            return null;
        }
    }

    // The log is keyed per server/save + dimension so coordinates on one server can
    // never show or prune another server's entries.
    private String bookLogScopeKey() {
        String server;
        if (mc.getCurrentServer() != null) {
            server = mc.getCurrentServer().ip;
        } else if (mc.getSingleplayerServer() != null) {
            server = mc.getSingleplayerServer().getWorldData().getLevelName();
        } else {
            server = "unknown";
        }
        return server + "|" + getWorldName();
    }

    private Map<String, Map<Integer, BookLogEntry>> bookLogForWorld() {
        ensureBookLogLoaded();
        return bookLog.computeIfAbsent(bookLogScopeKey(), k -> new HashMap<>());
    }

    private void ensureBookLogLoaded() {
        if (bookLogLoaded) return;
        loadBookLog();
    }

    private void loadBookLog() {
        if (bookLogLoaded) return;
        if (!bookLogEnabled.get()) return;
        bookLogLoaded = true;
        bookLogLoadFailed = false;
        try {
            bookLogFile = new File(mc.gameDirectory, "AutoBookshelf/bookshelf_books.json");
            if (!bookLogFile.exists()) return;

            String json = new String(Files.readAllBytes(bookLogFile.toPath()));
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            // Parse into a temp map and swap it in only on success so a malformed entry
            // can't leave a partial log that the next save would persist over the real file.
            Map<String, Map<String, Map<Integer, BookLogEntry>>> parsed = new HashMap<>();
            for (Map.Entry<String, com.google.gson.JsonElement> worldEntry : root.entrySet()) {
                Map<String, Map<Integer, BookLogEntry>> shelves = new HashMap<>();
                for (Map.Entry<String, com.google.gson.JsonElement> shelfEntry : worldEntry.getValue().getAsJsonObject().entrySet()) {
                    Map<Integer, BookLogEntry> slotsMap = new HashMap<>();
                    for (Map.Entry<String, com.google.gson.JsonElement> slotEntry : shelfEntry.getValue().getAsJsonObject().entrySet()) {
                        try {
                            int slot = Integer.parseInt(slotEntry.getKey());
                            if (slot < 0 || slot > 5) continue;
                            JsonObject entryObj = slotEntry.getValue().getAsJsonObject();
                            String title = entryObj.has("title") ? entryObj.get("title").getAsString() : "Unknown";
                            String author = entryObj.has("author") ? entryObj.get("author").getAsString() : "";
                            // Loaded entries never start with the remember grace: it is
                            // re-armed only when the module actively places a book, so
                            // persisted entries for slots that are genuinely empty get
                            // pruned by the sweep instead of lingering as ghosts.
                            slotsMap.put(slot, new BookLogEntry(title, author, 0));
                        } catch (Exception ignored) {
                        }
                    }
                    if (!slotsMap.isEmpty()) shelves.put(shelfEntry.getKey(), slotsMap);
                }
                if (!shelves.isEmpty()) parsed.put(worldEntry.getKey(), shelves);
            }
            bookLog.clear();
            bookLog.putAll(parsed);
        } catch (Exception e) {
            // Keep the on-disk log authoritative: in-memory stays as it was and saves
            // are blocked until a successful load (see saveBookLog).
            bookLogLoadFailed = true;
            error("Failed to load book log: " + e.getMessage());
        }
    }

    private void markBookLogDirty() {
        bookLogDirty = true;
    }

    private void maybeSaveBookLog() {
        if (!bookLogDirty) return;
        long now = System.currentTimeMillis();
        if (now - bookLogLastSaveMs < BOOK_LOG_SAVE_INTERVAL_MS) return;
        saveBookLog();
    }

    private void forceSaveBookLog() {
        if (bookLogDirty) saveBookLog();
    }

    private void saveBookLog() {
        if (!bookLogEnabled.get()) return;
        if (bookLogLoadFailed) return;
        ensureBookLogLoaded();
        if (bookLogFile == null) {
            bookLogFile = new File(mc.gameDirectory, "AutoBookshelf/bookshelf_books.json");
        }

        try {
            JsonObject root = new JsonObject();
            for (Map.Entry<String, Map<String, Map<Integer, BookLogEntry>>> worldEntry : bookLog.entrySet()) {
                JsonObject worldData = new JsonObject();
                for (Map.Entry<String, Map<Integer, BookLogEntry>> shelfEntry : worldEntry.getValue().entrySet()) {
                    JsonObject slotsData = new JsonObject();
                    for (Map.Entry<Integer, BookLogEntry> slotEntry : shelfEntry.getValue().entrySet()) {
                        BookLogEntry entry = slotEntry.getValue();
                        JsonObject entryObj = new JsonObject();
                        entryObj.addProperty("title", entry.title());
                        entryObj.addProperty("author", entry.author());
                        slotsData.add(slotEntry.getKey().toString(), entryObj);
                    }
                    worldData.add(shelfEntry.getKey(), slotsData);
                }
                root.add(worldEntry.getKey(), worldData);
            }

            Files.createDirectories(bookLogFile.getParentFile().toPath());
            java.nio.file.Path target = bookLogFile.toPath();
            java.nio.file.Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(tmp, root.toString().getBytes());
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            bookLogDirty = false;
            bookLogLastSaveMs = System.currentTimeMillis();
        } catch (Exception e) {
            error("Failed to save book log: " + e.getMessage());
        }
    }

    private void logBookPlacement(BlockPos pos, int slot, String title, String author) {
        if (!bookLogEnabled.get()) return;
        if (title == null || title.isEmpty()) title = "Unknown";
        if (author == null) author = "";
        BookLogEntry entry = new BookLogEntry(title, author, System.currentTimeMillis());
        bookLogForWorld().computeIfAbsent(shelfPosKey(pos), k -> new HashMap<>()).put(slot, entry);
        markBookLogDirty();
    }

    // When the fill places a book into a NEW slot, drop any other logged entry for the
    // same title whose slot currently reads empty (it was the old home the book left).
    // One book = one entry; the old empty slot stops expecting the title instead of
    // lingering until the sweep prunes it. Occupied slots are never touched.
    private void pruneStaleDuplicateEntries(BlockPos placedPos, int placedSlot, String title) {
        if (title == null || title.isEmpty()) return;
        ensureBookLogLoaded();
        Map<String, Map<Integer, BookLogEntry>> worldBooks = bookLog.get(bookLogScopeKey());
        if (worldBooks == null || worldBooks.isEmpty()) return;

        boolean changed = false;
        Iterator<Map.Entry<String, Map<Integer, BookLogEntry>>> shelfIt = worldBooks.entrySet().iterator();
        while (shelfIt.hasNext()) {
            Map.Entry<String, Map<Integer, BookLogEntry>> shelf = shelfIt.next();
            BlockPos pos = parseShelfPosKey(shelf.getKey());
            if (pos == null) continue;

            Iterator<Map.Entry<Integer, BookLogEntry>> slotIt = shelf.getValue().entrySet().iterator();
            while (slotIt.hasNext()) {
                Map.Entry<Integer, BookLogEntry> slotEntry = slotIt.next();
                if (pos.equals(placedPos) && slotEntry.getKey() == placedSlot) continue;
                BookLogEntry entry = slotEntry.getValue();
                if (entry.title() == null || !entry.title().trim().equalsIgnoreCase(title)) continue;
                if (!isLoggedSlotEmpty(pos, slotEntry.getKey())) continue;
                slotIt.remove();
                changed = true;
            }

            if (shelf.getValue().isEmpty()) shelfIt.remove();
        }

        if (changed) markBookLogDirty();
    }

    // A freshly-written placement stays "remembered" for the grace window: the module
    // is still putting that book (or the server hasn't synced the slot yet), so the
    // entry must not be pruned or dropped by the hover path, and a re-fill of the same
    // position overwrites it rather than registering a new addition.
    private boolean isRemembered(BookLogEntry entry) {
        if (entry == null || !remember.get()) return false;
        return System.currentTimeMillis() - entry.loggedMillis() < BOOK_LOG_REMEMBER_MS;
    }

    /**
     * All claimed titles, rebuilt once per game tick (mc.player.tickCount) instead of
     * per-title: refreshBookList()/findNextBookToPlace() previously called this
     * scan up to 36x per tick, each one re-walking every logged shelf and slot.
     */
    private Set<String> claimedTitles() {
        if (mc.player == null) return claimedTitlesCache;
        long tick = mc.player.tickCount;
        if (tick == claimedTitlesTick) return claimedTitlesCache;
        claimedTitlesTick = tick;
        claimedTitlesCache.clear();

        if (!bookLogEnabled.get() || !remember.get() || !refillRemoved.get()) return claimedTitlesCache;
        ensureBookLogLoaded();
        Map<String, Map<Integer, BookLogEntry>> worldBooks = bookLog.get(bookLogScopeKey());
        if (worldBooks == null || worldBooks.isEmpty()) return claimedTitlesCache;

        for (Map.Entry<String, Map<Integer, BookLogEntry>> shelf : worldBooks.entrySet()) {
            BlockPos pos = parseShelfPosKey(shelf.getKey());
            if (pos == null) continue;

            for (Map.Entry<Integer, BookLogEntry> slotEntry : shelf.getValue().entrySet()) {
                BookLogEntry entry = slotEntry.getValue();
                if (entry.title() == null || entry.title().isEmpty()) continue;
                if (!isBookSlotRefillCandidate(pos, slotEntry.getKey(), entry)) continue;
                claimedTitlesCache.add(entry.title().trim().toLowerCase(Locale.ROOT));
            }
        }
        return claimedTitlesCache;
    }

    private boolean isTitleClaimedByRemovedBook(String title) {
        if (!bookLogEnabled.get() || !remember.get() || !refillRemoved.get()) return false;
        if (title == null || title.isEmpty()) return false;
        return claimedTitles().contains(title.trim().toLowerCase(Locale.ROOT));
    }

    // True when the given logged slot is empty right now and the refill pass could
    // actually act on it (loaded chunk, chiseled bookshelf, within refill range).
    // Shared by buildRefillQueue and the claim check so they can never disagree.
    private boolean isBookSlotRefillCandidate(BlockPos pos, int slot, BookLogEntry entry) {
        if (entry == null || mc.player == null || mc.level == null) return false;
        // Only refill shelves the player could actually click: the block
        // interaction range is the source of truth, with REFILL_MAX_DISTANCE as
        // a hard cap so reach-boosted servers still behave predictably.
        double reach = Math.min(mc.player.blockInteractionRange(), REFILL_MAX_DISTANCE);
        if (mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > reach) return false;
        return isLoggedSlotEmpty(pos, slot);
    }

    private boolean isLoggedSlotEmpty(BlockPos pos, int slot) {
        if (!mc.level.isLoaded(pos)) return false;
        BlockState state = mc.level.getBlockState(pos);
        if (state.getBlock() != Blocks.CHISELED_BOOKSHELF) return false;
        return !state.getValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(slot));
    }

    // Refill runs only when the fill cannot race it: never during extraction/selection,
    // and during a fill only on ticks where the fill is actually blocked waiting for
    // more books.
    private boolean canRefillNow() {
        if (extractingSingleBlock || !extractQueue.isEmpty()) return false;
        if (areaSelector.isSelecting()) return false;
        if (!isFilling) return true;
        return waitingForRetry;
    }

    private int findBookSlotByTitle(String title) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.getItem() == Items.WRITTEN_BOOK && !stack.isEmpty()) {
                String t = getBookTitle(stack);
                if (t != null && t.trim().equalsIgnoreCase(title)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private void buildRefillQueue() {
        refillQueue.clear();
        ensureBookLogLoaded();
        Map<String, Map<Integer, BookLogEntry>> worldBooks = bookLog.get(bookLogScopeKey());
        if (worldBooks == null || worldBooks.isEmpty()) return;

        for (Map.Entry<String, Map<Integer, BookLogEntry>> shelf : worldBooks.entrySet()) {
            BlockPos pos = parseShelfPosKey(shelf.getKey());
            if (pos == null) continue;

            for (Map.Entry<Integer, BookLogEntry> slotEntry : shelf.getValue().entrySet()) {
                int slot = slotEntry.getKey();
                BookLogEntry entry = slotEntry.getValue();
                if (!isBookSlotRefillCandidate(pos, slot, entry)) continue;
                if (findBookSlotByTitle(entry.title()) == -1) continue;
                refillQueue.add(new RefillTask(pos, slot, entry.title(), entry.author()));
            }
        }
    }

    private boolean refillPlace(RefillTask task) {
        String refillKey = refillStrikeKey(task.pos, task.slot);
        // A queued task can be many ticks old: the shelf may have been broken, the
        // slot re-occupied, or the player walked out of range. Revalidate before the
        // click so we never throw on a missing facing, yank a book out of an occupied
        // slot, or interact from too far away.
        BookLogEntry entry = lookupBookLog(task.pos, task.slot);
        if (!isBookSlotRefillCandidate(task.pos, task.slot, entry)) {
            // Slot now occupied (placement confirmed) or the entry aged out: any
            // accumulated failure strikes are stale.
            refillStrikeCount.remove(refillKey);
            return true;
        }
        int bookSlot = findBookSlotByTitle(task.title);
        if (bookSlot == -1) return true;

        int strikes = refillStrikeCount.getOrDefault(refillKey, 0);
        if (strikes >= REFILL_MAX_STRIKES) {
            if (strikes == REFILL_MAX_STRIKES) {
                info("§cRefill: gave up on slot " + (task.slot + 1) + " (" + BookUtils.sanitizeForChat(task.title) + ") - placement keeps failing");
                refillStrikeCount.put(refillKey, strikes + 1); // log once per give-up
            }
            return true;
        }
        refillStrikeCount.put(refillKey, strikes + 1);

        updateCurrentBookStatus(bookSlot);
        // Re-arm the remember grace so the sweep/hover keep matching this placement,
        // mapping the re-fill back to the same barrier/slot.
        logBookPlacement(task.pos, task.slot, task.title, task.author);

        BlockState state = mc.level.getBlockState(task.pos);
        Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        Vec3 hitVec = BookUtils.getSlotHitVec(task.pos, facing, task.slot);
        BlockHitResult hitResult = new BlockHitResult(hitVec, facing, task.pos, false);

        int previousSlot = mc.player.getInventory().getSelectedSlot();
        Rotations.rotate(
            Rotations.getYaw(hitVec),
            Rotations.getPitch(hitVec),
            () -> {
                int swapSlot = findSwapSlot();
                if (bookSlot >= 9) {
                    mc.gameMode.handleContainerInput(
                        mc.player.containerMenu.containerId,
                        bookSlot,
                        swapSlot,
                        ContainerInput.SWAP,
                        mc.player
                    );
                    mc.player.getInventory().setSelectedSlot(swapSlot);
                } else {
                    mc.player.getInventory().setSelectedSlot(bookSlot);
                }

                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hitResult);
                mc.player.swing(InteractionHand.MAIN_HAND);

                if (previousSlot != mc.player.getInventory().getSelectedSlot()) {
                    mc.player.getInventory().setSelectedSlot(previousSlot);
                }
            }
        );

        return true;
    }

    private void handleRefill() {
        if (!bookLogEnabled.get() || !remember.get() || !refillRemoved.get()) return;
        if (mc.player == null || mc.level == null) return;

        // Maintenance pass may interleave with fill pauses but never on a tick where the
        // fill or an extraction is about to click.
        if (!canRefillNow()) return;

        if (refillDelay > 0) {
            refillDelay--;
            return;
        }

        if (refillQueue.isEmpty()) {
            if (--refillScanTicks > 0) return;
            refillScanTicks = BOOK_LOG_VERIFY_INTERVAL_TICKS;
            buildRefillQueue();
            if (refillQueue.isEmpty()) return;
            refillIndex = 0;
        }

        RefillTask task = refillQueue.get(refillIndex);
        refillIndex++;
        if (refillIndex >= refillQueue.size()) refillQueue.clear();

        refillPlace(task);
        refillDelay = Math.max(delay.get(), 8);
    }

    private void logBookRemoved(BlockPos pos, int slot) {
        if (!bookLogEnabled.get()) return;
        ensureBookLogLoaded();
        Map<String, Map<Integer, BookLogEntry>> worldBooks = bookLog.get(bookLogScopeKey());
        if (worldBooks == null) return;
        Map<Integer, BookLogEntry> slotsMap = worldBooks.get(shelfPosKey(pos));
        if (slotsMap == null) return;
        if (slotsMap.remove(slot) != null) {
            if (slotsMap.isEmpty()) worldBooks.remove(shelfPosKey(pos));
            if (worldBooks.isEmpty()) bookLog.remove(bookLogScopeKey());
            markBookLogDirty();
        }
    }

    private BookLogEntry lookupBookLog(BlockPos pos, int slot) {
        ensureBookLogLoaded();
        Map<String, Map<Integer, BookLogEntry>> worldBooks = bookLog.get(bookLogScopeKey());
        if (worldBooks == null) return null;
        Map<Integer, BookLogEntry> slotsMap = worldBooks.get(shelfPosKey(pos));
        return slotsMap == null ? null : slotsMap.get(slot);
    }

    private void verifyBookLog() {
        if (!bookLogEnabled.get() || bookLog.isEmpty()) return;
        ensureBookLogLoaded();
        Map<String, Map<Integer, BookLogEntry>> worldBooks = bookLog.get(bookLogScopeKey());
        if (worldBooks == null || worldBooks.isEmpty()) return;

        boolean changed = false;
        Iterator<Map.Entry<String, Map<Integer, BookLogEntry>>> shelfIt = worldBooks.entrySet().iterator();
        while (shelfIt.hasNext()) {
            Map.Entry<String, Map<Integer, BookLogEntry>> shelf = shelfIt.next();

            BlockPos pos = parseShelfPosKey(shelf.getKey());
            if (pos == null) {
                shelfIt.remove();
                changed = true;
                continue;
            }

            // Only check loaded shelves so logs for unloaded chunks survive.
            if (!mc.level.isLoaded(pos)) continue;

            BlockState state = mc.level.getBlockState(pos);
            if (state.getBlock() != Blocks.CHISELED_BOOKSHELF) {
                shelfIt.remove();
                changed = true;
                continue;
            }

            Iterator<Map.Entry<Integer, BookLogEntry>> slotIt = shelf.getValue().entrySet().iterator();
            while (slotIt.hasNext()) {
                Map.Entry<Integer, BookLogEntry> slotEntry = slotIt.next();
                boolean occupied = state.getValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(slotEntry.getKey()));
                if (occupied) {
                    // Placement confirmed: drop any refill failure strikes for this slot.
                    refillStrikeCount.remove(refillStrikeKey(pos, slotEntry.getKey()));
                } else if (!isRemembered(slotEntry.getValue())) {
                    slotIt.remove();
                    refillStrikeCount.remove(refillStrikeKey(pos, slotEntry.getKey()));
                    changed = true;
                }
            }

            if (shelf.getValue().isEmpty()) shelfIt.remove();
        }

        if (changed) markBookLogDirty();
    }

    private void resetAllCounts() {
        savedCounts.clear();
        saveCache();
        sendMessage("§cAll saved book counts have been reset!");
    }

    private int countBooksInShelf(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        if (state.getBlock() != Blocks.CHISELED_BOOKSHELF) return 0;

        int count = 0;
        for (int slot = 0; slot < 6; slot++) {
            if (state.getValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(slot))) {
                count++;
            }
        }
        return count;
    }

    private void countSelectedArea() {
        if (countPos1 == null || countPos2 == null) return;

        int minX = Math.min(countPos1.getX(), countPos2.getX());
        int maxX = Math.max(countPos1.getX(), countPos2.getX());
        int minY = Math.min(countPos1.getY(), countPos2.getY());
        int maxY = Math.max(countPos1.getY(), countPos2.getY());
        int minZ = Math.min(countPos1.getZ(), countPos2.getZ());
        int maxZ = Math.max(countPos1.getZ(), countPos2.getZ());

        int newBookshelfCount = 0;
        int updatedBookshelfCount = 0;
        int removedBookshelfCount = 0;
        int newTotalBooks = 0;
        int updatedTotalBooksDelta = 0;

        String worldName = getWorldName();
        Map<String, Integer> worldCounts = savedCounts.getOrDefault(worldName, new HashMap<>());

        // Track current bookshelves in the area
        Map<String, Integer> currentAreaCounts = new HashMap<>();

        // First pass: count current bookshelves
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = mc.level.getBlockState(pos);
                    if (state.getBlock() == Blocks.CHISELED_BOOKSHELF) {
                        int bookCount = countBooksInShelf(pos);
                        String key = x + "," + y + "," + z;
                        currentAreaCounts.put(key, bookCount);
                    }
                }
            }
        }

        // Process current bookshelves
        for (Map.Entry<String, Integer> entry : currentAreaCounts.entrySet()) {
            String key = entry.getKey();
            int currentCount = entry.getValue();

            if (!worldCounts.containsKey(key)) {
                worldCounts.put(key, currentCount);
                newBookshelfCount++;
                newTotalBooks += currentCount;
                if (showChanges.get()) {
                    sendMessage("§a+ New: §f" + key + " §a-> §f" + currentCount + " §abooks");
                }
            } else {
                int oldCount = worldCounts.get(key);
                if (oldCount != currentCount) {
                    worldCounts.put(key, currentCount);
                    updatedBookshelfCount++;
                    updatedTotalBooksDelta += (currentCount - oldCount);
                    if (showChanges.get()) {
                        String changeIcon = currentCount > oldCount ? "§a+" : "§c-";
                        sendMessage(changeIcon + " Update: §f" + key + " §7was §f" + oldCount + " §7now §f" + currentCount + " §abooks");
                    }
                }
            }
        }

        // Check for missing bookshelves
        Set<String> toRemove = new HashSet<>();
        for (String key : worldCounts.keySet()) {
            if (!currentAreaCounts.containsKey(key)) {
                String[] parts = key.split(",");
                int x = Integer.parseInt(parts[0]);
                int y = Integer.parseInt(parts[1]);
                int z = Integer.parseInt(parts[2]);

                if (x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ) {
                    toRemove.add(key);
                    removedBookshelfCount++;
                    if (showChanges.get()) {
                        sendMessage("§c- Removed: §f" + key + " §c(bookshelf no longer exists)");
                    }
                }
            }
        }
        for (String key : toRemove) {
            worldCounts.remove(key);
        }

        if (showCountMessages.get()) {
            if (newBookshelfCount > 0 || updatedBookshelfCount > 0 || removedBookshelfCount > 0) {
                int totalBooks = worldCounts.values().stream().mapToInt(Integer::intValue).sum();
                savedCounts.put(worldName, worldCounts);
                saveCache();

                sendMessage("§a§l=== Book Counter Summary ===");
                if (newBookshelfCount > 0) {
                    sendMessage("§a+ New bookshelves: §f" + newBookshelfCount + " §a(§f" + newTotalBooks + " §abooks)");
                }
                if (updatedBookshelfCount > 0) {
                    String deltaColor = updatedTotalBooksDelta >= 0 ? "§a" : "§c";
                    sendMessage("§e~ Updated bookshelves: §f" + updatedBookshelfCount + " §e(delta: " + deltaColor + updatedTotalBooksDelta + "§e)");
                }
                if (removedBookshelfCount > 0) {
                    sendMessage("§c- Removed bookshelves: §f" + removedBookshelfCount);
                }
                sendMessage("§7Total tracked: §f" + worldCounts.size() + " §7bookshelves, §f" + totalBooks + " §7books");
            } else {
                info("§e[Book Counter] No changes detected in this area.");
                int totalBooks = worldCounts.values().stream().mapToInt(Integer::intValue).sum();
                sendMessage("§eTotal tracked: §f" + worldCounts.size() + " §ebookshelves, §f" + totalBooks + " §ebooks");
            }
        }

        countingMode = false;
        countPos1 = null;
        countPos2 = null;
    }


    @EventHandler
    private void onInteract(InteractBlockEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (event.hand != InteractionHand.MAIN_HAND) return;

        ItemStack hand = mc.player.getMainHandItem();
        BlockHitResult hitResult = event.result;
        if (hitResult == null) return;

        BlockPos pos = hitResult.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (state.getBlock() != Blocks.CHISELED_BOOKSHELF) return;

        // Counter tool
        if (counterTool.get() != null && !hand.isEmpty() && hand.getItem() == counterTool.get()) {
            if (requireCounterTool.get() && hand.getItem() != counterTool.get()) {
                return;
            }

            if (!countingMode) {
                countPos1 = pos;
                countingMode = true;
                sendMessage("§a[Book Counter] First position set to §f" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
                event.cancel();
                return;
            } else {
                countPos2 = pos;
                sendMessage("§a[Book Counter] Second position set to §f" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
                countSelectedArea();
                event.cancel();
                return;
            }
        }

        if (extractTool.get() != null && !hand.isEmpty() && hand.getItem() == extractTool.get()) {
            fullReset();
            startSingleBlockExtract(pos);
            event.cancel();
            return;
        }

        // Selection tool handling with respect to require-tool-in-hand setting
        if (!areaSelector.requiresToolInHand() || areaSelector.isToolStack(hand)) {
            AreaSelector.Result result = areaSelector.handleClick(pos);
            switch (result) {
                case POS1_SET -> info("§aPos1 set to: §f" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
                case COMPLETE -> {
                    info("§aPos2 set to: §f" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
                    info("§aSelection complete! now filling.");
                    initializeGrid();
                }
                case RESET -> {
                    fullReset();
                    info("§eSelection reset.");
                }
            }
            event.cancel();
        }
    }

    private void startSingleBlockExtract(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        if (state.getBlock() != Blocks.CHISELED_BOOKSHELF) return;

        singleBlockSlots.clear();
        for (int slot = 0; slot < 6; slot++) {
            boolean occupied = state.getValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(slot));
            if (occupied) {
                singleBlockSlots.add(slot);
            }
        }

        if (singleBlockSlots.isEmpty()) {
            sendMessage("§eNo books found in this bookshelf!");
            return;
        }

        int originalSize = singleBlockSlots.size();

        List<Integer> chosen;
        if (extractMode.get() == ExtractMode.LIMITED && maxExtractBooks.get() > 0) {
            int targetCount = Math.min(maxExtractBooks.get(), originalSize);
            Collections.shuffle(singleBlockSlots);
            chosen = new ArrayList<>(singleBlockSlots.subList(0, targetCount));
            sendMessage("§aLIMITED mode: extracting §f" + targetCount + " §aof §f" + originalSize + " §abooks");
        } else {
            Collections.sort(singleBlockSlots);
            chosen = new ArrayList<>(singleBlockSlots);
            sendMessage("§aALL mode: extracting all §f" + originalSize + " §abooks");
        }

        singleBlockSlots = chosen;
        extractOriginalSlots = new ArrayList<>(chosen);
        singleBlockPos = pos;
        singleBlockSlotIndex = 0;
        extractingSingleBlock = true;
        originalSlot = mc.player.getInventory().getSelectedSlot();
        extractionRetryCount = 0;
        pendingExtractSlot = -1;
        pendingExtractTicks = 0;

        setDisplayText(String.format("Extracting %d books...", singleBlockSlots.size()));
    }

    private void updateExtract() {
        if (!extractingSingleBlock) return;
        if (extractDelay > 0) {
            extractDelay--;
            return;
        }

        // Wait for the previous take to be confirmed (the client block-state flips the
        // slot back to empty) before issuing the next click. Without this, a stale
        // "still occupied" slot gets re-clicked while the taken book sits in the hotbar
        // and the server inserts it back into the shelf.
        if (pendingExtractSlot != -1) {
            BlockState confirmState = mc.level.getBlockState(singleBlockPos);
            boolean emptied = confirmState.getBlock() != Blocks.CHISELED_BOOKSHELF
                || !confirmState.getValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(pendingExtractSlot));
            if (emptied) {
                pendingExtractSlot = -1;
            } else if (++pendingExtractTicks >= EXTRACT_CONFIRM_MAX_TICKS) {
                pendingExtractSlot = -1;
            } else {
                extractDelay = 1;
                return;
            }
        }

        if (singleBlockSlotIndex >= singleBlockSlots.size()) {
            BlockState state = mc.level.getBlockState(singleBlockPos);
            if (state.getBlock() == Blocks.CHISELED_BOOKSHELF && extractionRetryCount < MAX_EXTRACTION_RETRIES) {
                // Only re-target slots from the original selection, so LIMITED mode
                // doesn't end up harvesting every remaining book anyway.
                boolean hasBooksLeft = false;
                for (int slot : extractOriginalSlots) {
                    if (state.getValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(slot))) {
                        hasBooksLeft = true;
                        break;
                    }
                }

                if (hasBooksLeft) {
                    extractionRetryCount++;
                    singleBlockSlots.clear();
                    for (int slot : extractOriginalSlots) {
                        if (state.getValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(slot))) {
                            singleBlockSlots.add(slot);
                        }
                    }
                    singleBlockSlotIndex = 0;
                    sendMessage("§eRetrying extraction for remaining books... (" + extractionRetryCount + "/" + MAX_EXTRACTION_RETRIES + ")");
                    setDisplayText("Retrying extraction...");
                    return;
                }
            }

            extractingSingleBlock = false;
            singleBlockPos = null;
            singleBlockSlots.clear();

            if (originalSlot != -1 && originalSlot != mc.player.getInventory().getSelectedSlot()) {
                mc.player.getInventory().setSelectedSlot(originalSlot);
            }

            sendMessage("§aExtraction complete!");
            setDisplayText("Extraction complete!");
            return;
        }

        int slot = singleBlockSlots.get(singleBlockSlotIndex);

        BlockState state = mc.level.getBlockState(singleBlockPos);
        if (state.getBlock() != Blocks.CHISELED_BOOKSHELF ||
            !state.getValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(slot))) {
            singleBlockSlotIndex++;
            extractDelay = 2;
            return;
        }

        int emptySlot = -1;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) {
                emptySlot = i;
                break;
            }
        }

        if (emptySlot == -1) {
            sendMessage("§cInventory full!");
            setDisplayText("§cInventory full!");
            extractingSingleBlock = false;
            return;
        }

        extractBook(singleBlockPos, slot, emptySlot);
        pendingExtractSlot = slot;
        pendingExtractTicks = 0;
        singleBlockSlotIndex++;
        extractDelay = extractDelayTicks.get();
    }

    // Makes sure the selected hotbar slot holds nothing before an extraction click so
    // the click takes the shelf book into the hand/inventory instead of inserting the
    // held item. When the hand holds something, swaps it into an empty main-inventory
    // slot (inventory index 9-35, matching the codebase's clickSlot convention).
    private boolean ensureEmptyHand(int selectedSlot) {
        if (mc.player.getInventory().getItem(selectedSlot).isEmpty()) return true;
        int emptyMain = -1;
        for (int i = 9; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) {
                emptyMain = i;
                break;
            }
        }
        if (emptyMain == -1) return false;
        mc.gameMode.handleContainerInput(
            mc.player.containerMenu.containerId,
            emptyMain,
            selectedSlot,
            ContainerInput.SWAP,
            mc.player
        );
        return true;
    }

    private void extractBook(BlockPos pos, int slot, int targetSlot) {
        BlockState state = mc.level.getBlockState(pos);
        Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        Vec3 hitVec = BookUtils.getSlotHitVec(pos, facing, slot);

        BlockHitResult hitResult = new BlockHitResult(hitVec, facing, pos, false);
        int previousSlot = mc.player.getInventory().getSelectedSlot();

        Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec), () -> {
            // Re-check the slot right before the click: it may have emptied (or the
            // shelf may be gone) since this click was queued. Taking an empty slot while
            // the prior book is still in a nearby hotbar slot is what makes a book flip
            // back into the shelf, so skip rather than click.
            BlockState current = mc.level.getBlockState(pos);
            if (current.getBlock() != Blocks.CHISELED_BOOKSHELF
                || !current.getValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(slot))) {
                if (previousSlot != mc.player.getInventory().getSelectedSlot()) {
                    mc.player.getInventory().setSelectedSlot(previousSlot);
                }
                return;
            }

            int handSlot;
            if (targetSlot >= 9) {
                int tempHotbarSlot = -1;
                for (int i = 0; i < 9; i++) {
                    if (mc.player.getInventory().getItem(i).isEmpty()) {
                        tempHotbarSlot = i;
                        break;
                    }
                }

                if (tempHotbarSlot == -1) {
                    tempHotbarSlot = findSwapSlot();
                }
                mc.gameMode.handleContainerInput(
                    mc.player.containerMenu.containerId,
                    targetSlot,
                    tempHotbarSlot,
                    ContainerInput.SWAP,
                    mc.player
                );
                mc.player.getInventory().setSelectedSlot(tempHotbarSlot);
                handSlot = tempHotbarSlot;
            } else {
                mc.player.getInventory().setSelectedSlot(targetSlot);
                handSlot = targetSlot;
            }

            // The click must extract (empty hand) or vanilla will insert whatever it is
            // holding into the shelf. On a stale-occupancy retry the server may have
            // already removed the book while the client hand still carries it, swap
            // the occupant free first so the take actually happens.
            if (!ensureEmptyHand(handSlot)) {
                // No extraction click was sent; put the previously selected slot back
                // and skip the log/count below so nothing reports a book was removed.
                if (previousSlot != mc.player.getInventory().getSelectedSlot()) {
                    mc.player.getInventory().setSelectedSlot(previousSlot);
                }
                return;
            }

            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hitResult);
            mc.player.swing(InteractionHand.MAIN_HAND);

            logBookRemoved(pos, slot);
            extractCount++;
            if (showExtractMessages.get()) {
                sendMessage("§aExtracted book from slot §f" + (slot + 1));
            }
            setDisplayText(String.format("Extracted book from slot %d", slot + 1));

            if (previousSlot != mc.player.getInventory().getSelectedSlot()) {
                mc.player.getInventory().setSelectedSlot(previousSlot);
            }
        });
    }

    private void initializeGrid() {
        rows = getSortedRows();
        currentRow = 0;
        currentCol = 0;
        currentSlot = 0;
        fillingBottomHalf = false;
        allFull = false;
        retryCount = 0;
        stuckCounter = 0;
        lastPos = null;
        lastSlot = -1;
        isFilling = true;
        currentBookTitle = "";
        currentBookAuthor = "";
        currentBookSlot = -1;
        waitingForRetry = false;
        retryWaitCounter = 0;
        hasShownNoBooksMessage = false;

        dedicatedSwapSlot = dedicatedSwapSlotIndex.get();

        if (enableFilter.get()) {
            refreshBookList();
        }

        if (!rows.isEmpty()) {
            int totalBookshelves = rows.stream().mapToInt(List::size).sum();
            info("§aFound §f" + rows.size() + " §arows with §f" + totalBookshelves + " §abookshelves total");
            if (enableFilter.get()) {
                info("§7Filter enabled -> sorting by first number first.");
                info("§7Books found: §f" + sortedBookSlots.size());
            }
            if (fillLayout.get() == FillLayout.BOTTOM_FIRST) {
                info("§7Layout: lowest Y layer of the wall first, bottom row of each shelf first (fills upward).");
            } else {
                info("§7Layout: highest Y layer of the wall first, top row of each shelf first (fills downward).");
            }
            if (useDedicatedSlot.get()) {
                info("§7Using dedicated swap slot: §f" + (dedicatedSwapSlot + 1));
            }
        }
    }

    private void refreshBookList() {
        sortedBookSlots.clear();
        currentBookIndex = 0;

        Pattern numberPattern = TITLE_NUMBER_PATTERN;
        Map<Integer, BookInfo> slotBookInfoMap = new HashMap<>();
        int claimedCount = 0;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.getItem() == Items.WRITTEN_BOOK && !stack.isEmpty()) {
                String title = getBookTitle(stack);
                String author = getBookAuthor(stack);
                if (title != null) {
                    // A book that was just taken out of a remembered slot is claimed by
                    // it; the fill leaves it alone (refill returns it there).
                    if (isTitleClaimedByRemovedBook(title)) {
                        claimedCount++;
                        continue;
                    }

                    List<Integer> numbers = new ArrayList<>();
                    Matcher matcher = numberPattern.matcher(title);
                    while (matcher.find()) {
                        numbers.add(Integer.parseInt(matcher.group()));
                    }

                    if (!numbers.isEmpty() || !enableFilter.get()) {
                        slotBookInfoMap.put(i, new BookInfo(title, author, numbers));
                        if (verboseChecking.get() && !waitingForRetry) {
                            if (enableFilter.get()) {
                                info("§7Found: §f" + title + " §7by §f" + (author != null ? author : "Unknown") + " §7in slot §f" + i + " §7(numbers: §f" + numbers + "§7)");
                            } else {
                                info("§7Found: §f" + title + " §7by §f" + (author != null ? author : "Unknown") + " §7in slot §f" + i);
                            }
                        }
                    }
                }
            }
        }

        if (slotBookInfoMap.isEmpty()) {
            if (verboseChecking.get() && !hasShownNoBooksMessage && continuousChecking.get()) {
                if (claimedCount > 0) {
                    info("§eAll remaining books are claimed by emptied slots; waiting for refill.");
                } else if (enableFilter.get()) {
                    info("§eNo numbers found in books! Waiting.");
                } else {
                    info("§eNo books found in inventory! Waiting.");
                }
                hasShownNoBooksMessage = true;
            }
            return;
        }

        hasShownNoBooksMessage = false;

        if (enableFilter.get()) {
            sortedBookSlots = slotBookInfoMap.entrySet().stream()
                .sorted((a, b) -> {
                    List<Integer> numsA = a.getValue().numbers;
                    List<Integer> numsB = b.getValue().numbers;

                    for (int i = 0; i < Math.min(numsA.size(), numsB.size()); i++) {
                        int cmp = Integer.compare(numsA.get(i), numsB.get(i));
                        if (cmp != 0) return cmp;
                    }
                    return Integer.compare(numsA.size(), numsB.size());
                })
                .map(Map.Entry::getKey)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        } else {
            sortedBookSlots = new ArrayList<>(slotBookInfoMap.keySet());
        }

        if (verboseChecking.get() && waitingForRetry) {
            info("§aFound §f" + sortedBookSlots.size() + " §anew books! Resuming");
        } else if (verboseChecking.get()) {
            info("§aFound §f" + sortedBookSlots.size() + " §abooks");
        }

        if (enableFilter.get() && verboseChecking.get()) {
            info("§7Sorting order:");
            for (int idx = 0; idx < Math.min(10, sortedBookSlots.size()); idx++) {
                int slot = sortedBookSlots.get(idx);
                BookInfo info = slotBookInfoMap.get(slot);
                if (info != null) {
                    info("§7  " + (idx + 1) + ". §f" + info.title + " §7by §f" + (info.author != null ? info.author : "Unknown") + " §7(numbers: §f" + info.numbers + "§7)");
                }
            }
            if (sortedBookSlots.size() > 10) {
                info("§7  ... and " + (sortedBookSlots.size() - 10) + " more");
            }
        }
    }

    private String getBookTitle(ItemStack bookStack) {
        try {
            if (bookStack.getItem() == Items.WRITTEN_BOOK) {
                WrittenBookContent content = bookStack.get(DataComponents.WRITTEN_BOOK_CONTENT);
                if (content != null) {
                    return content.title().raw();
                }
            }
        } catch (Exception e) {
        }
        return null;
    }

    private String getBookAuthor(ItemStack bookStack) {
        try {
            if (bookStack.getItem() == Items.WRITTEN_BOOK) {
                WrittenBookContent content = bookStack.get(DataComponents.WRITTEN_BOOK_CONTENT);
                if (content != null) {
                    return content.author();
                }
            }
        } catch (Exception e) {
        }
        return null;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null) return;

        if (--bookLogVerifyTicks <= 0) {
            bookLogVerifyTicks = BOOK_LOG_VERIFY_INTERVAL_TICKS;
            verifyBookLog();
        }
        maybeSaveBookLog();
        handleRefill();

        if (displayTimer > 0) {
            displayTimer--;
            if (displayTimer == 0) {
                displayText = "";
            }
        }

        updateExtract();

        if (extractingSingleBlock) {
            return;
        }

        if (pendingReset) {
            pendingReset = false;
            fullReset();
        }

        if (waitingForRetry) {
            retryWaitCounter++;
            if (retryWaitCounter >= retryCheckInterval.get()) {
                waitingForRetry = false;
                retryWaitCounter = 0;
                refreshBookList();
                if (sortedBookSlots.isEmpty() && continuousChecking.get()) {
                    waitingForRetry = true;
                } else if (!sortedBookSlots.isEmpty()) {
                    if (verboseChecking.get()) {
                        setDisplayText("Books found! Filling");
                    }
                    setDisplayText("Books found! Resuming");
                }
            }
            return;
        }

        if (areaSelector.isSelecting()) {
            areaSelector.updateWandState(mc.player.getMainHandItem());
        }

        if (areaSelector.isSelecting() || !areaSelector.hasCompleteSelection()) return;
        if (allFull) {
            if (isFilling) {
                isFilling = false;
                currentBookTitle = "";
                currentBookAuthor = "";
                currentBookSlot = -1;
                info("§aAll bookshelves are full!");
                setDisplayText("All bookshelves are full!");
            }
            fullReset();
            return;
        }

        if (delayLeft > 0) {
            delayLeft--;
            return;
        }

        fillNextSlot();
    }

    private void fillNextSlot() {
        if (rows.isEmpty()) {
            allFull = true;
            isFilling = false;
            return;
        }

        if (currentRow >= rows.size()) {
            allFull = true;
            isFilling = false;
            info("§aAll bookshelves are completely full!");
            setDisplayText("All bookshelves full!");
            return;
        }

        List<BlockPos> currentRowBlocks = rows.get(currentRow);

        if (currentCol >= currentRowBlocks.size()) {
            if (!fillingBottomHalf) {
                fillingBottomHalf = true;
                currentCol = 0;
                currentSlot = 0;
                retryCount = 0;
                stuckCounter = 0;
                if (fillLayout.get() == FillLayout.BOTTOM_FIRST) {
                    info("§aFinished bottom half of row " + (currentRow + 1) + ", now filling top half...");
                } else {
                    info("§aFinished top half of row " + (currentRow + 1) + ", now filling bottom half...");
                }
                delayLeft = delay.get();
                return;
            } else {
                currentRow++;
                currentCol = 0;
                currentSlot = 0;
                fillingBottomHalf = false;
                retryCount = 0;
                stuckCounter = 0;
                delayLeft = delay.get();
                return;
            }
        }

        BlockPos pos = currentRowBlocks.get(currentCol);
        BlockState state = mc.level.getBlockState(pos);

        if (state.getBlock() != Blocks.CHISELED_BOOKSHELF) {
            info("§cWarning: Block at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + " is no longer a bookshelf! Skipping");
            currentCol++;
            retryCount = 0;
            stuckCounter = 0;
            delayLeft = delay.get();
            return;
        }

        int slotToFill;
        if (fillLayout.get() == FillLayout.BOTTOM_FIRST) {
            slotToFill = fillingBottomHalf ? currentSlot : currentSlot + 3;
        } else {
            slotToFill = fillingBottomHalf ? currentSlot + 3 : currentSlot;
        }

        double distance = mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(pos));
        if (distance > 5.0) {
            delayLeft = 10;
            return;
        }

        if (lastPos != null && lastPos.equals(pos) && lastSlot == slotToFill) {
            stuckCounter++;
            delayLeft = Math.max(delay.get(), 20);
            return;
        } else {
            stuckCounter = 0;
        }

        boolean isSlotEmpty = !state.getValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(slotToFill));

        if (isSlotEmpty) {
            int bookSlot = findNextBookToPlace();
            if (bookSlot == -1) {
                if (continuousChecking.get()) {
                    if (!waitingForRetry) {
                        if (verboseChecking.get()) {
                            if (enableFilter.get()) {
                                info("§eNo more books with numbers found! Waiting");
                            } else {
                                info("§eNo written books found! Waiting");
                            }
                        }
                        waitingForRetry = true;
                        retryWaitCounter = 0;
                        hasShownNoBooksMessage = true;
                    }
                    delayLeft = delay.get();
                    return;
                } else {
                    if (enableFilter.get()) {
                        info("§cNo more numbers found in books, Stopping. Enable 'continuous-checking' to continue.");
                    } else {
                        info("§cNo written books found! Stopping. Enable 'continuous-checking' to continue.");
                    }
                    allFull = true;
                    isFilling = false;
                    return;
                }
            }

            updateCurrentBookStatus(bookSlot);

            logBookPlacement(pos, slotToFill, currentBookTitle, currentBookAuthor);
            pruneStaleDuplicateEntries(pos, slotToFill, currentBookTitle);

            if (showOnScreen.get()) {
                String authorText = (currentBookAuthor != null && !currentBookAuthor.isEmpty()) ? " by " + currentBookAuthor : "";
                String displayMsg = String.format("Put: %s%s to slot %d", currentBookTitle, authorText, slotToFill + 1);
                if (!showBookInChat.get()) {
                    sendMessage("§a" + displayMsg);
                }
                setDisplayText(displayMsg);
            }

            if (showBookInChat.get()) {
                displayBookInfoInChat(currentBookTitle, currentBookAuthor);
            }

            targetPos = pos;
            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            Vec3 hitVec = BookUtils.getSlotHitVec(pos, facing, slotToFill);

            BlockHitResult hitResult = new BlockHitResult(hitVec, facing, pos, false);
            lastPos = pos;
            lastSlot = slotToFill;

            int finalBookSlot = bookSlot;
            int previousSlot = mc.player.getInventory().getSelectedSlot();

            Rotations.rotate(
                Rotations.getYaw(hitVec),
                Rotations.getPitch(hitVec),
                () -> {
                    int swapSlot = findSwapSlot();

                    if (finalBookSlot >= 9) {
                        mc.gameMode.handleContainerInput(
                            mc.player.containerMenu.containerId,
                            finalBookSlot,
                            swapSlot,
                            ContainerInput.SWAP,
                            mc.player
                        );
                        mc.player.getInventory().setSelectedSlot(swapSlot);
                    } else {
                        mc.player.getInventory().setSelectedSlot(finalBookSlot);
                    }

                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hitResult);
                    mc.player.swing(InteractionHand.MAIN_HAND);

                    if (previousSlot != mc.player.getInventory().getSelectedSlot()) {
                        mc.player.getInventory().setSelectedSlot(previousSlot);
                    }
                }
            );

            retryCount = 0;

            currentSlot++;
            if (currentSlot >= 3) {
                currentSlot = 0;
                currentCol++;
            }

            if (enableFilter.get() && currentBookIndex < sortedBookSlots.size()) {
                currentBookIndex++;
            }

            lastPos = null;
            lastSlot = -1;
            stuckCounter = 0;

            delayLeft = delay.get();
            return;
        } else {
            currentSlot++;
            if (currentSlot >= 3) {
                currentSlot = 0;
                currentCol++;
            }
            retryCount = 0;
            stuckCounter = 0;
            lastPos = null;
            lastSlot = -1;
            delayLeft = delay.get();
            return;
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        renderBookLogHover(event);

        if (!showOnScreen.get()) return;
        if (displayText.isEmpty()) return;

        int screenWidth = event.screenWidth;
        int screenHeight = event.screenHeight;
        double scale = 1.2;

        int x = (int) (screenWidth / 2 - (mc.font.width(displayText) * scale) / 2);
        int y = screenHeight - 50;

        event.graphics.pose().pushMatrix();
        event.graphics.pose().translate(x, y);
        event.graphics.pose().scale((float) scale, (float) scale);

        event.graphics.text(mc.font, displayText, 0, 0, 0xFFFFD700, true);

        event.graphics.pose().popMatrix();
    }

    private void renderBookLogHover(Render2DEvent event) {
        if (!bookLogEnabled.get() || !hoverShowBooks.get()) return;
        if (mc.level == null || mc.player == null) return;
        if (!(mc.hitResult instanceof BlockHitResult hit)) return;

        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (state.getBlock() != Blocks.CHISELED_BOOKSHELF) return;

        int slot = BookUtils.getSlotFromHit(hit);
        if (slot == -1) return;

        // Only show a title over an actually occupied slot. Remembered-but-empty slots
        // are either being re-filled or were taken out on purpose; painting a phantom
        // title over them is what the "ghost entry" confusion comes from. The sweep
        // prunes them once the grace window elapses (refill re-arms the entry).
        if (!state.getValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(slot))) return;

        BookLogEntry entry = lookupBookLog(pos, slot);
        if (entry == null) return;

        BookUtils.renderSlotHover(event, pos, state.getValue(BlockStateProperties.HORIZONTAL_FACING), slot,
            entry.title(), entry.author(), hoverTextScale.get(), hoverTextColor.get(), hoverAuthorColor.get());
    }

    private void displayBookInfoInChat(String title, String author) {
        if (!showBookInChat.get()) return;
        if (title == null || title.isEmpty()) return;

        if (showBookCooldown.get()) {
            String bookKey = title + "|" + author;
            int currentTick = mc.player.tickCount;

            if (bookKey.equals(lastDisplayedBookKey) &&
                (currentTick - lastDisplayedTick) < chatCooldownTicks.get()) {
                return;
            }

            lastDisplayedBookKey = bookKey;
            lastDisplayedTick = currentTick;
        }

        String authorText = (author != null && !author.isEmpty()) ? " by §f" + author : "";
        info("§7Placing: §f" + title + "§7" + authorText);
    }

    private void updateCurrentBookStatus(int slot) {
        currentBookSlot = slot;
        ItemStack stack = mc.player.getInventory().getItem(slot);
        if (stack.getItem() == Items.WRITTEN_BOOK) {
            currentBookTitle = getBookTitle(stack);
            currentBookAuthor = getBookAuthor(stack);
            if (currentBookTitle == null) currentBookTitle = "Unknown";
            if (currentBookAuthor == null) currentBookAuthor = "Unknown";
        } else {
            currentBookTitle = "";
            currentBookAuthor = "";
        }
    }

    private int findNextBookToPlace() {
        if (!enableFilter.get()) {
            if (cachedBookSlot != -1) {
                ItemStack cached = mc.player.getInventory().getItem(cachedBookSlot);
                if (cached.getItem() == Items.WRITTEN_BOOK && !cached.isEmpty()) {
                    String cachedTitle = getBookTitle(cached);
                    if (cachedTitle == null || !isTitleClaimedByRemovedBook(cachedTitle)) {
                        return cachedBookSlot;
                    }
                }
                cachedBookSlot = -1;
            }
            for (int i = 0; i < 36; i++) {
                ItemStack stack = mc.player.getInventory().getItem(i);
                if (stack.getItem() == Items.WRITTEN_BOOK && !stack.isEmpty()) {
                    String foundTitle = getBookTitle(stack);
                    if (foundTitle != null && isTitleClaimedByRemovedBook(foundTitle)) continue;
                    cachedBookSlot = i;
                    return i;
                }
            }
            cachedBookSlot = -1;
            return -1;
        }

        if (currentBookIndex < sortedBookSlots.size()) {
            int slot = sortedBookSlots.get(currentBookIndex);
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.getItem() == Items.WRITTEN_BOOK && !stack.isEmpty()) {
                String foundTitle = getBookTitle(stack);
                if (foundTitle != null && !isTitleClaimedByRemovedBook(foundTitle)) {
                    return slot;
                }
            }
            // Slot is gone or the book is claimed by a just-emptied shelf slot
            // (refill puts it back there); rebuild the pool and take the next one.
            refreshBookList();
            return currentBookIndex < sortedBookSlots.size() ? sortedBookSlots.get(currentBookIndex) : -1;
        }

        return -1;
    }

    private List<List<BlockPos>> getSortedRows() {
        List<BlockPos> all = getSelectedBlocks();
        if (all.isEmpty()) return Collections.emptyList();

        boolean increasingX = areaSelector.isXIncreasing();
        boolean increasingZ = areaSelector.isZIncreasing();

        // The fill layout dictates the wall direction: BOTTOM_FIRST starts at the
        // lowest Y layer of the selected wall and fills upward, TOP_FIRST starts at
        // the highest layer and fills downward (selection click order is irrelevant).
        boolean fillToTop = fillLayout.get() == FillLayout.BOTTOM_FIRST;

        all.sort((a, b) -> fillToTop ? Integer.compare(a.getY(), b.getY()) : Integer.compare(b.getY(), a.getY()));

        Map<Integer, List<BlockPos>> yLevels = new LinkedHashMap<>();
        for (BlockPos pos : all) {
            yLevels.computeIfAbsent(pos.getY(), k -> new ArrayList<>()).add(pos);
        }

        List<List<BlockPos>> rows = new ArrayList<>();

        for (List<BlockPos> row : yLevels.values()) {
            row.sort((a, b) -> {
                int xRange = Math.abs(areaSelector.getPos2().getX() - areaSelector.getPos1().getX());
                int zRange = Math.abs(areaSelector.getPos2().getZ() - areaSelector.getPos1().getZ());

                if (xRange >= zRange) {
                    if (increasingX) {
                        int compare = Integer.compare(a.getX(), b.getX());
                        if (compare != 0) return compare;
                        if (increasingZ) {
                            return Integer.compare(a.getZ(), b.getZ());
                        } else {
                            return Integer.compare(b.getZ(), a.getZ());
                        }
                    } else {
                        int compare = Integer.compare(b.getX(), a.getX());
                        if (compare != 0) return compare;
                        if (increasingZ) {
                            return Integer.compare(a.getZ(), b.getZ());
                        } else {
                            return Integer.compare(b.getZ(), a.getZ());
                        }
                    }
                } else {
                    if (increasingZ) {
                        int compare = Integer.compare(a.getZ(), b.getZ());
                        if (compare != 0) return compare;
                        if (increasingX) {
                            return Integer.compare(a.getX(), b.getX());
                        } else {
                            return Integer.compare(b.getX(), a.getX());
                        }
                    } else {
                        int compare = Integer.compare(b.getZ(), a.getZ());
                        if (compare != 0) return compare;
                        if (increasingX) {
                            return Integer.compare(a.getX(), b.getX());
                        } else {
                            return Integer.compare(b.getX(), a.getX());
                        }
                    }
                }
            });
            rows.add(row);
        }

        return rows;
    }

    private List<BlockPos> getSelectedBlocks() {
        return areaSelector.getMatchingBlocks(pos -> mc.level.getBlockState(pos).getBlock() == Blocks.CHISELED_BOOKSHELF);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        areaSelector.render(event);

        if (targetPos != null) {
            event.renderer.box(targetPos, areaSelector.getSideColor(), areaSelector.getLineColor(), ShapeMode.Both, 0);
        }
    }

    private void sendMessage(String msg) {
        info(msg);
        if (mc.player != null) mc.player.sendOverlayMessage(Component.literal(msg));
    }

    public void resetSelection() {
        if (isFilling) {
            pendingReset = true;
        } else {
            fullReset();
        }
    }

    public void setPos1(BlockPos pos) {
        areaSelector.setPos1(pos);
        info("§aPos1 set to: §f" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
    }

    public void setPos2(BlockPos pos) {
        areaSelector.setPos2(pos);
        info("§aPos2 set to: §f" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
    }

    @Override
    public void onActivate() {
        // A failed load used to leave bookLogLoaded=true, locking the module out of
        // re-reading the file for the rest of the session; retry on activation so
        // fixing a malformed bookshelf_books.json actually takes effect.
        if (bookLogLoadFailed) bookLogLoaded = false;
        fullReset();
        loadCache();
        loadBookLog();
        String toolName = areaSelector.getSelectionToolItem().getName(ItemStack.EMPTY).getString();
        String extractToolName = extractTool.get().getName(ItemStack.EMPTY).getString();
        String counterToolName = counterTool.get().getName(ItemStack.EMPTY).getString();
        info("§aBookshelf Filler is activated.");
        info("§7- §f" + toolName + " §7= select area & fill");
        info("§7- §f" + extractToolName + " §7= extract books from a bookshelf");
        info("§7- §f" + counterToolName + " §7= count books in selected area");
        if (areaSelector.requiresToolInHand()) {
            info("§7Hold the tool to use it");
        }
        if (enableFilter.get()) {
            info("§7Filter enabled -> automatically sort numbers from titles");
        }
        if (showOnScreen.get()) {
            info("§7Status will be shown on screen");
        }
        if (continuousChecking.get()) {
            info("§7Continuous checking §aENABLED §7-> will wait for books if no books are found");
        }
        if (useDedicatedSlot.get()) {
            info("§7Using dedicated swap slot: §f" + (dedicatedSwapSlotIndex.get() + 1));
        }
        if (persistentCache.get()) {
            info("§7Book counts will be saved to file");
        }
    }

    @Override
    public void onDeactivate() {
        fullReset();
        extractingSingleBlock = false;
        singleBlockPos = null;
        singleBlockSlots.clear();
        displayText = "";
        displayTimer = 0;
        countingMode = false;
        countPos1 = null;
        countPos2 = null;
        saveCache();
        forceSaveBookLog();
    }
}
