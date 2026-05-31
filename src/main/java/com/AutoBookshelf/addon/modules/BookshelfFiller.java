package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.entity.player.InteractBlockEvent;
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
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.io.File;
import java.nio.file.Files;
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

    private BlockPos pos1 = null;
    private BlockPos pos2 = null;
    private boolean selecting = true;
    private boolean allFull = false;

    private boolean wandModeActive = false;
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
    private int singleBlockSlotIndex = 0;
    private int extractionRetryCount = 0;
    private static final int MAX_EXTRACTION_RETRIES = 3;

    private int dedicatedSwapSlot = -1;

    private String displayText = "";
    private int displayTimer = 0;

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

    private final Setting<Item> selectionToolSetting = sgSelection.add(new ItemSetting.Builder()
        .name("selection-tool")
        .description("Which item to use for making selections.")
        .defaultValue(Items.NETHERITE_AXE)
        .build()
    );

    private final Setting<Boolean> requireToolInHand = sgSelection.add(new BoolSetting.Builder()
        .name("require-tool-in-hand")
        .description("Require the selection tool to be held in hand to make selections.")
        .defaultValue(true)
        .build()
    );

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

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders the selection area.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The side color of the selection box.")
        .defaultValue(new SettingColor(0, 255, 255, 30))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The line color of the selection box.")
        .defaultValue(new SettingColor(0, 255, 255, 255))
        .build()
    );

    private final Setting<SettingColor> pos1Color = sgRender.add(new ColorSetting.Builder()
        .name("pos1-color")
        .description("The color of the first position marker.")
        .defaultValue(new SettingColor(0, 255, 0, 255))
        .build()
    );

    private final Setting<SettingColor> pos2Color = sgRender.add(new ColorSetting.Builder()
        .name("pos2-color")
        .description("The color of the second position marker.")
        .defaultValue(new SettingColor(255, 0, 0, 255))
        .build()
    );

    private enum ExtractMode {
        ALL("All Books"),
        LIMITED("Limited Amount.");

        private final String title;
        ExtractMode(String title) { this.title = title; }
        @Override public String toString() { return title; }
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
        singleBlockSlotIndex = 0;
        originalSlot = -1;
        extractionRetryCount = 0;

        pos1 = null;
        pos2 = null;
        selecting = true;
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

        if (selectionToolSetting.get() != null && !hand.isEmpty() && hand.getItem() == selectionToolSetting.get()) {
            if (selecting) {
                if (pos1 == null) {
                    pos1 = pos;
                    info("§aPos1 set to: §f" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
                } else if (pos2 == null) {
                    pos2 = pos;
                    selecting = false;
                    wandModeActive = false;
                    info("§aPos2 set to: §f" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
                    info("§aSelection complete! now filling.");
                    initializeGrid();
                }
                event.cancel();
                return;
            } else {
                fullReset();
                info("§eSelection reset.");
                event.cancel();
                return;
            }
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

        if (extractMode.get() == ExtractMode.LIMITED && maxExtractBooks.get() > 0) {
            int targetCount = Math.min(maxExtractBooks.get(), originalSize);
            Collections.shuffle(singleBlockSlots);
            singleBlockSlots = singleBlockSlots.subList(0, targetCount);
            sendMessage("§aLIMITED mode: extracting §f" + targetCount + " §aof §f" + originalSize + " §abooks");
        } else {
            Collections.sort(singleBlockSlots);
            sendMessage("§aALL mode: extracting all §f" + originalSize + " §abooks");
        }

        singleBlockPos = pos;
        singleBlockSlotIndex = 0;
        extractingSingleBlock = true;
        originalSlot = mc.player.getInventory().getSelectedSlot();
        extractionRetryCount = 0;

        setDisplayText(String.format("Extracting %d books...", singleBlockSlots.size()));
    }

    private void updateExtract() {
        if (!extractingSingleBlock) return;
        if (extractDelay > 0) {
            extractDelay--;
            return;
        }

        if (singleBlockSlotIndex >= singleBlockSlots.size()) {
            BlockState state = mc.level.getBlockState(singleBlockPos);
            if (state.getBlock() == Blocks.CHISELED_BOOKSHELF && extractionRetryCount < MAX_EXTRACTION_RETRIES) {
                boolean hasBooksLeft = false;
                for (int slot = 0; slot < 6; slot++) {
                    if (state.getValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(slot))) {
                        hasBooksLeft = true;
                        break;
                    }
                }

                if (hasBooksLeft) {
                    extractionRetryCount++;
                    singleBlockSlots.clear();
                    for (int slot = 0; slot < 6; slot++) {
                        if (state.getValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(slot))) {
                            singleBlockSlots.add(slot);
                        }
                    }
                    Collections.sort(singleBlockSlots);
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
        singleBlockSlotIndex++;
        extractDelay = extractDelayTicks.get();
    }

    private void extractBook(BlockPos pos, int slot, int targetSlot) {
        BlockState state = mc.level.getBlockState(pos);
        Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        Vec3 hitVec = getHitVec(pos, facing, slot);

        BlockHitResult hitResult = new BlockHitResult(hitVec, facing, pos, false);
        int previousSlot = mc.player.getInventory().getSelectedSlot();

        Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec), () -> {
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
                    mc.gameMode.handleInventoryMouseClick(
                        mc.player.containerMenu.containerId,
                        targetSlot,
                        tempHotbarSlot,
                        ClickType.SWAP,
                        mc.player
                    );
                } else {
                    mc.gameMode.handleInventoryMouseClick(
                        mc.player.containerMenu.containerId,
                        targetSlot,
                        tempHotbarSlot,
                        ClickType.SWAP,
                        mc.player
                    );
                }

                mc.player.getInventory().setSelectedSlot(tempHotbarSlot);
            } else {
                mc.player.getInventory().setSelectedSlot(targetSlot);
            }

            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hitResult);
            mc.player.swing(InteractionHand.MAIN_HAND);

            if (previousSlot != mc.player.getInventory().getSelectedSlot()) {
                mc.player.getInventory().setSelectedSlot(previousSlot);
            }
        });

        extractCount++;
        if (showExtractMessages.get()) {
            sendMessage("§aExtracted book from slot §f" + (slot + 1));
        }
        setDisplayText(String.format("Extracted book from slot %d", slot + 1));
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
                info("§7Filter enabled - sorting by first number first.");
                info("§7Books found: §f" + sortedBookSlots.size());
            }
            info("§7Filling from pos1 to pos2: left to right, top to bottom.");
            if (useDedicatedSlot.get()) {
                info("§7Using dedicated swap slot: §f" + (dedicatedSwapSlot + 1));
            }
        }
    }

    private void refreshBookList() {
        sortedBookSlots.clear();
        currentBookIndex = 0;

        Pattern numberPattern = Pattern.compile("\\d+");
        Map<Integer, BookInfo> slotBookInfoMap = new HashMap<>();

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.getItem() == Items.WRITTEN_BOOK && !stack.isEmpty()) {
                String title = getBookTitle(stack);
                String author = getBookAuthor(stack);
                if (title != null) {
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
                if (enableFilter.get()) {
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

        if (selecting && selectionToolSetting.get() != null && requireToolInHand.get()) {
            ItemStack mainHand = mc.player.getMainHandItem();
            Item expectedTool = selectionToolSetting.get();
            boolean hasTool = !mainHand.isEmpty() && mainHand.getItem() == expectedTool;
            wandModeActive = hasTool;
        }

        if (selecting || pos1 == null || pos2 == null) return;
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
                info("§aFinished top half of row " + (currentRow + 1) + ", now filling bottom half...");
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

        int slotToFill = fillingBottomHalf ? currentSlot + 3 : currentSlot;

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
            Vec3 hitVec = getHitVec(pos, facing, slotToFill);

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
                        mc.gameMode.handleInventoryMouseClick(
                            mc.player.containerMenu.containerId,
                            finalBookSlot,
                            swapSlot,
                            ClickType.SWAP,
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
        if (!showOnScreen.get()) return;
        if (displayText.isEmpty()) return;

        int screenWidth = event.screenWidth;
        int screenHeight = event.screenHeight;
        double scale = 1.2;

        int x = (int) (screenWidth / 2 - (mc.font.width(displayText) * scale) / 2);
        int y = screenHeight - 50;

        event.drawContext.pose().pushMatrix();
        event.drawContext.pose().translate(x, y);
        event.drawContext.pose().scale((float) scale, (float) scale);

        event.drawContext.drawString(mc.font, displayText, 0, 0, 0xFFFFD700, true);

        event.drawContext.pose().popMatrix();
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

    public String getCurrentBookTitle() { return currentBookTitle; }
    public String getCurrentBookAuthor() { return currentBookAuthor; }
    public int getCurrentBookSlot() { return currentBookSlot; }
    public boolean isFilling() { return isFilling; }
    public int getCurrentRow() { return currentRow; }
    public int getCurrentCol() { return currentCol; }
    public int getTotalRows() { return rows.size(); }
    public int getTotalBooks() { return sortedBookSlots.size(); }
    public int getCurrentBookIndex() { return currentBookIndex; }
    public boolean isWaitingForBooks() { return waitingForRetry; }
    public boolean isContinuousCheckingEnabled() { return continuousChecking.get(); }
    public boolean shouldShowBookInfo() { return isFilling() && currentBookTitle != null && !currentBookTitle.isEmpty(); }

    private int findNextBookToPlace() {
        if (!enableFilter.get()) {
            for (int i = 0; i < 36; i++) {
                ItemStack stack = mc.player.getInventory().getItem(i);
                if (stack.getItem() == Items.WRITTEN_BOOK && !stack.isEmpty()) {
                    return i;
                }
            }
            return -1;
        }

        if (currentBookIndex < sortedBookSlots.size()) {
            int slot = sortedBookSlots.get(currentBookIndex);
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.getItem() == Items.WRITTEN_BOOK && !stack.isEmpty()) {
                return slot;
            } else {
                refreshBookList();
                return currentBookIndex < sortedBookSlots.size() ? sortedBookSlots.get(currentBookIndex) : -1;
            }
        }

        return -1;
    }

    private List<List<BlockPos>> getSortedRows() {
        List<BlockPos> all = getSelectedBlocks();
        if (all.isEmpty()) return Collections.emptyList();

        int minX = Math.min(pos1.getX(), pos2.getX());
        int maxX = Math.max(pos1.getX(), pos2.getX());
        int minY = Math.min(pos1.getY(), pos2.getY());
        int maxY = Math.max(pos1.getY(), pos2.getY());
        int minZ = Math.min(pos1.getZ(), pos2.getZ());
        int maxZ = Math.max(pos1.getZ(), pos2.getZ());

        boolean increasingX = pos2.getX() >= pos1.getX();
        boolean increasingZ = pos2.getZ() >= pos1.getZ();
        boolean increasingY = pos2.getY() >= pos1.getY();

        all.sort((a, b) -> {
            if (increasingY) {
                return Integer.compare(a.getY(), b.getY());
            } else {
                return Integer.compare(b.getY(), a.getY());
            }
        });

        Map<Integer, List<BlockPos>> yLevels = new LinkedHashMap<>();
        for (BlockPos pos : all) {
            yLevels.computeIfAbsent(pos.getY(), k -> new ArrayList<>()).add(pos);
        }

        List<List<BlockPos>> rows = new ArrayList<>();

        for (List<BlockPos> row : yLevels.values()) {
            row.sort((a, b) -> {
                int xRange = Math.abs(pos2.getX() - pos1.getX());
                int zRange = Math.abs(pos2.getZ() - pos1.getZ());

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
        List<BlockPos> list = new ArrayList<>();

        int minX = Math.min(pos1.getX(), pos2.getX());
        int maxX = Math.max(pos1.getX(), pos2.getX());
        int minY = Math.min(pos1.getY(), pos2.getY());
        int maxY = Math.max(pos1.getY(), pos2.getY());
        int minZ = Math.min(pos1.getZ(), pos2.getZ());
        int maxZ = Math.max(pos1.getZ(), pos2.getZ());

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (mc.level.getBlockState(pos).getBlock() == Blocks.CHISELED_BOOKSHELF) {
                        list.add(pos);
                    }
                }
            }
        }

        return list;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get()) return;

        if (pos1 != null) {
            event.renderer.box(pos1, pos1Color.get(), pos1Color.get(), ShapeMode.Both, 0);
        }

        if (pos2 != null) {
            event.renderer.box(pos2, pos2Color.get(), pos2Color.get(), ShapeMode.Both, 0);
        }

        if (pos1 != null && pos2 != null) {
            int minX = Math.min(pos1.getX(), pos2.getX());
            int minY = Math.min(pos1.getY(), pos2.getY());
            int minZ = Math.min(pos1.getZ(), pos2.getZ());
            int maxX = Math.max(pos1.getX(), pos2.getX());
            int maxY = Math.max(pos1.getY(), pos2.getY());
            int maxZ = Math.max(pos1.getZ(), pos2.getZ());

            event.renderer.box(new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1),
                sideColor.get(), lineColor.get(), ShapeMode.Both, 0);
        }

        if (targetPos != null) {
            event.renderer.box(targetPos, sideColor.get(), lineColor.get(), ShapeMode.Both, 0);
        }
    }

    private Vec3 getHitVec(BlockPos pos, Direction facing, int slot) {
        double x = 0, y = 0;

        switch (slot) {
            case 0 -> { x = -0.25; y = 0.25; }
            case 1 -> { x = 0.0;  y = 0.25; }
            case 2 -> { x = 0.25; y = 0.25; }
            case 3 -> { x = -0.25; y = -0.25; }
            case 4 -> { x = 0.0;  y = -0.25; }
            case 5 -> { x = 0.25; y = -0.25; }
        }

        Vec3 center = Vec3.atCenterOf(pos);

        return switch (facing) {
            case NORTH -> center.add(-x, y, -0.5);
            case SOUTH -> center.add(x, y, 0.5);
            case WEST  -> center.add(-0.5, y, x);
            case EAST  -> center.add(0.5, y, -x);
            default -> center;
        };
    }

    private void sendMessage(String msg) {
        info(msg);
        mc.player.displayClientMessage(Component.literal(msg), true);
    }

    public void resetSelection() {
        if (isFilling) {
            pendingReset = true;
        } else {
            fullReset();
        }
    }

    public void setPos1(BlockPos pos) {
        pos1 = pos;
        info("§aPos1 set to: §f" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
    }

    public void setPos2(BlockPos pos) {
        pos2 = pos;
        info("§aPos2 set to: §f" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
    }

    @Override
    public void onActivate() {
        fullReset();
        loadCache();
        String toolName = selectionToolSetting.get().getName().getString();
        String extractToolName = extractTool.get().getName().getString();
        String counterToolName = counterTool.get().getName().getString();
        info("§aBookshelf Filler is activated.");
        info("§7- §f" + toolName + " §7= select area & fill");
        info("§7- §f" + extractToolName + " §7= extract books from a bookshelf");
        info("§7- §f" + counterToolName + " §7= count books in selected area");
        if (requireToolInHand.get()) {
            info("§7Hold the tool to use it");
        }
        if (enableFilter.get()) {
            info("§7Filter enabled - automatically sort numbers from titles");
        }
        if (showOnScreen.get()) {
            info("§7Status will be shown on screen");
        }
        if (continuousChecking.get()) {
            info("§7Continuous checking §aENABLED §7- will wait for books if no books are found");
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
    }
}
