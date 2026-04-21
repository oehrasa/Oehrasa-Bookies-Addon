package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.TextHandlerAccessor;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.font.TextHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.network.packet.c2s.play.BookUpdateC2SPacket;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.RawFilteredPair;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BookImporter extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgResume = settings.createGroup("Resume");

    private final Setting<String> importFolder = sgGeneral.add(new StringSetting.Builder()
        .name("import-folder")
        .description("Folder path containing .txt files to import")
        .defaultValue("AutoBookshelf/books")
        .build()
    );

    private final Setting<Integer> pagesPerBook = sgGeneral.add(new IntSetting.Builder()
        .name("pages-per-book")
        .description("Maximum pages per book (100 is Minecraft limit)")
        .defaultValue(100)
        .min(1)
        .max(100)
        .build()
    );

    private final Setting<Boolean> deleteAfterImport = sgGeneral.add(new BoolSetting.Builder()
        .name("delete-after-import")
        .description("Delete .txt files after importing")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> delayBetweenBooks = sgGeneral.add(new IntSetting.Builder()
        .name("delay-between-books")
        .description("Ticks to wait between creating each book")
        .defaultValue(60)
        .min(0)
        .max(100)
        .build()
    );

    // Persistent progress
    private final Setting<Boolean> persistentProgress = sgResume.add(new BoolSetting.Builder()
        .name("persistent-progress")
        .description("Save progress to file and resume after restart/crash")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> saveNow = sgResume.add(new BoolSetting.Builder()
        .name("save-now")
        .description("Manually save current progress (toggle ON, then OFF manually)")
        .defaultValue(false)
        .onChanged(value -> {
            if (value) {
                saveProgress();
                sendMessage("§aProgress saved manually.");
            }
        })
        .build()
    );

    // Manual override (start from specific file/part)
    private final Setting<Integer> startFromFileIndex = sgResume.add(new IntSetting.Builder()
        .name("start-from-file-index")
        .description("Start from file at this position (1 = first file in sorted order)")
        .defaultValue(1)
        .min(1)
        .max(100)
        .build()
    );

    private final Setting<Integer> startFromPart = sgResume.add(new IntSetting.Builder()
        .name("start-from-part")
        .description("Start from this part number within the file")
        .defaultValue(1)
        .min(1)
        .max(100)
        .build()
    );

    // Confirmation before next file
    private final Setting<Boolean> requireConfirmNextFile = sgResume.add(new BoolSetting.Builder()
        .name("confirm-next-file")
        .description("Wait for key press before moving to next file")
        .defaultValue(true)
        .build()
    );

    private final Setting<Keybind> confirmKey = sgResume.add(new KeybindSetting.Builder()
        .name("confirm-key")
        .description("Key to press to confirm moving to next file")
        .defaultValue(Keybind.fromKey(84)) // Y key
        .visible(requireConfirmNextFile::get)
        .build()
    );

    private final Setting<Boolean> resetProgress = sgResume.add(new BoolSetting.Builder()
        .name("reset-progress")
        .description("Reset saved progress (toggle ON, then OFF manually)")
        .defaultValue(false)
        .onChanged(value -> {
            if (value) {
                resetProgressData();
            }
        })
        .build()
    );

    // Page constraints
    private static final int MAX_PAGE_CHARS = 1024;
    private static final int MAX_PAGE_WIDTH = 114;
    private static final int MAX_PAGE_HEIGHT = 128;
    private static final String LINE_SEPARATOR = "\n";

    // State
    private boolean isImporting = false;
    private final List<ImportTask> tasks = new ArrayList<>();
    private ImportTask currentTask = null;
    private int currentPart = 1;
    private int currentPageIndex = 0;
    private int totalParts = 1;
    private List<String> allPages = new ArrayList<>();
    private int tickDelay = 0;
    private int totalBooksCreated = 0;
    private int currentFileIndex = 0;

    // Waiting for confirmation
    private boolean waitingForConfirm = false;
    private ImportTask pendingNextTask = null;
    private int pendingFileIndex = -1;

    // Progress persistence
    private static final String PROGRESS_FILE = "AutoBookshelf/import_progress.json";
    private ProgressData savedProgress = null;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private static class ImportTask {
        File file;
        String baseTitle;
        List<String> allPages;
        int totalParts;

        ImportTask(File file, String baseTitle, List<String> allPages, int totalParts) {
            this.file = file;
            this.baseTitle = baseTitle;
            this.allPages = allPages;
            this.totalParts = totalParts;
        }
    }

    private static class ProgressData {
        String fileName;
        int partNumber;
        int fileIndex;
        long lastUpdated;

        ProgressData(String fileName, int partNumber, int fileIndex) {
            this.fileName = fileName;
            this.partNumber = partNumber;
            this.fileIndex = fileIndex;
            this.lastUpdated = System.currentTimeMillis();
        }
    }

    public BookImporter() {
        super(Addon.CATEGORY, "book-importer", "Automatically imports text files into signed books");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.world == null) {
            error("Cannot activate module while not in a world.");
            toggle();
            return;
        }

        loadProgress();
        scanAndQueueFiles();

        if (tasks.isEmpty()) {
            sendMessage("No .txt files found in " + importFolder.get());
            toggle();
            return;
        }

        int startIndex = startFromFileIndex.get() - 1;
        int startPart = startFromPart.get();

        if (startIndex < 0) startIndex = 0;
        if (startIndex >= tasks.size()) {
            startIndex = 0;
            sendMessage("§eFile index out of range, starting from first file");
        }

        boolean userOverride = (startFromFileIndex.get() != 1) || (startFromPart.get() != 1);
        sendMessage("§7Manual override: " + (userOverride ? "YES" : "NO"));

        // Resume logic: use saved progress only if no override and persistent enabled
        if (!userOverride && persistentProgress.get() && savedProgress != null) {
            // Try to find file by name first (most reliable)
            int foundIndex = -1;
            for (int i = 0; i < tasks.size(); i++) {
                if (tasks.get(i).file.getName().equals(savedProgress.fileName)) {
                    foundIndex = i;
                    break;
                }
            }
            if (foundIndex >= 0) {
                startIndex = foundIndex;
                startPart = savedProgress.partNumber;
                sendMessage("§aResuming from saved progress (by name): " + tasks.get(startIndex).file.getName() + " part " + startPart);
            } else if (savedProgress.fileIndex < tasks.size()) {
                // Fallback to index (if file was renamed, but unlikely)
                startIndex = savedProgress.fileIndex;
                startPart = savedProgress.partNumber;
                sendMessage("§aResuming from saved progress (by index): " + tasks.get(startIndex).file.getName() + " part " + startPart);
            } else {
                sendMessage("§eSaved file not found, starting from beginning.");
            }
        }

        if (userOverride) {
            sendMessage("§eManual override: ignoring saved progress");
        }

        isImporting = true;
        waitingForConfirm = false;
        tickDelay = 20;
        totalBooksCreated = 0;
        currentFileIndex = startIndex;
        currentTask = tasks.get(currentFileIndex);
        totalParts = currentTask.totalParts;

        if (startPart > totalParts) {
            sendMessage("§eWarning: Start part " + startPart + " exceeds total parts (" + totalParts + "). Starting from last part.");
            startPart = totalParts;
        } else if (startPart < 1) {
            startPart = 1;
        }

        currentPart = startPart;
        currentPageIndex = (startPart - 1) * pagesPerBook.get();

        if (currentPageIndex >= currentTask.allPages.size()) {
            currentPageIndex = Math.max(0, currentTask.allPages.size() - pagesPerBook.get());
            currentPart = (currentPageIndex / pagesPerBook.get()) + 1;
            sendMessage("§eAdjusted to part " + currentPart + " due to page count.");
        }

        allPages = currentTask.allPages;

        sendMessage("Found " + tasks.size() + " file(s) to import");
        sendMessage("Starting with: " + currentTask.file.getName() + " part " + currentPart + "/" + totalParts);

        // If we used a manual override, reset the override settings so they don't persist
        if (userOverride) {
            mc.execute(() -> {
                startFromFileIndex.set(1);
                startFromPart.set(1);
                sendMessage("§aOverride settings reset to 1 for future runs.");
            });
        }
    }

    @Override
    public void onDeactivate() {
        isImporting = false;
        waitingForConfirm = false;
        tasks.clear();
        currentTask = null;
        tickDelay = 0;
        saveProgress(); // Save on deactivation
    }

    private void saveProgress() {
        if (!persistentProgress.get()) return;
        if (currentTask != null && isImporting) {
            try {
                Path progressPath = Paths.get(mc.runDirectory.getPath(), PROGRESS_FILE);
                Files.createDirectories(progressPath.getParent());
                ProgressData data = new ProgressData(currentTask.file.getName(), currentPart, currentFileIndex);
                String json = gson.toJson(data);
                Files.writeString(progressPath, json);
            } catch (IOException e) {
                error("Failed to save progress: " + e.getMessage());
            }
        }
    }

    private void loadProgress() {
        if (!persistentProgress.get()) {
            savedProgress = null;
            return;
        }
        try {
            Path progressPath = Paths.get(mc.runDirectory.getPath(), PROGRESS_FILE);
            if (Files.exists(progressPath)) {
                String json = Files.readString(progressPath);
                savedProgress = gson.fromJson(json, ProgressData.class);
            }
        } catch (IOException e) {
            savedProgress = null;
        }
    }

    private void resetProgressData() {
        try {
            Path progressPath = Paths.get(mc.runDirectory.getPath(), PROGRESS_FILE);
            Files.deleteIfExists(progressPath);
            savedProgress = null;
            sendMessage("§aProgress has been reset!");
        } catch (IOException e) {
            error("Failed to reset progress: " + e.getMessage());
        }
    }

    private void scanAndQueueFiles() {
        tasks.clear();
        Path folder = Paths.get(mc.runDirectory.getPath(), importFolder.get());

        if (!Files.exists(folder)) {
            try {
                Files.createDirectories(folder);
                sendMessage("§aCreated folder: " + folder);
                sendMessage("§7Place your .txt files in this folder, then re-enable the module");
                toggle();
                return;
            } catch (IOException e) {
                error("Failed to create folder: " + folder);
                return;
            }
        }

        File[] files = folder.toFile().listFiles((dir, name) -> name.endsWith(".txt"));
        if (files == null) return;

        // Custom natural sorting: by base name, then first number, then second number
        Arrays.sort(files, (a, b) -> {
            String nameA = a.getName();
            String nameB = b.getName();

            // Base name = everything before the first digit
            String baseA = nameA.replaceFirst("\\d.*$", "");
            String baseB = nameB.replaceFirst("\\d.*$", "");
            int baseCompare = baseA.compareToIgnoreCase(baseB);
            if (baseCompare != 0) return baseCompare;

            // Extract numbers
            Pattern pattern = Pattern.compile("\\d+");
            Matcher matcherA = pattern.matcher(nameA);
            Matcher matcherB = pattern.matcher(nameB);

            int numA1 = 0, numA2 = 0;
            int numB1 = 0, numB2 = 0;

            if (matcherA.find()) numA1 = Integer.parseInt(matcherA.group());
            if (matcherA.find()) numA2 = Integer.parseInt(matcherA.group());
            if (matcherB.find()) numB1 = Integer.parseInt(matcherB.group());
            if (matcherB.find()) numB2 = Integer.parseInt(matcherB.group());

            if (numA1 != numB1) return Integer.compare(numA1, numB1);
            return Integer.compare(numA2, numB2);
        });

        for (File file : files) {
            if (file == null) continue;
            try {
                List<String> lines = Files.readAllLines(file.toPath());
                List<String> pages = convertLinesToPages(lines);

                if (pages.isEmpty()) {
                    error("File is empty: " + file.getName());
                    continue;
                }

                String baseTitle = file.getName().replace(".txt", "");
                if (baseTitle.length() > 32) {
                    baseTitle = baseTitle.substring(0, 32);
                }

                int totalParts = (int) Math.ceil((double) pages.size() / pagesPerBook.get());

                tasks.add(new ImportTask(file, baseTitle, pages, totalParts));
                sendMessage("Queued: " + file.getName() + " (" + pages.size() + " pages, " + totalParts + " part(s))");

            } catch (IOException e) {
                error("Failed to read file: " + file.getName());
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isImporting) return;
        if (mc.player == null || mc.world == null) {
            isImporting = false;
            return;
        }

        // Handle waiting for confirmation before next file
        if (waitingForConfirm) {
            if (requireConfirmNextFile.get() && confirmKey.get().isPressed()) {
                waitingForConfirm = false;
                applyNextFile();
                tickDelay = 20;
            }
            return;
        }

        if (tickDelay > 0) {
            tickDelay--;
            return;
        }

        if (currentPart > totalParts) {
            // Current file finished
            saveProgress();

            if (deleteAfterImport.get() && currentTask != null && currentTask.file != null) {
                try {
                    Files.delete(currentTask.file.toPath());
                    sendMessage("Deleted: " + currentTask.file.getName());
                } catch (IOException e) {
                    error("Failed to delete: " + currentTask.file.getName());
                }
            }

            int nextIndex = currentFileIndex + 1;
            if (nextIndex >= tasks.size()) {
                finishImport();
                return;
            }

            ImportTask nextTask = tasks.get(nextIndex);
            if (requireConfirmNextFile.get()) {
                sendMessage("§6=== File completed: " + currentTask.file.getName() + " ===");
                sendMessage("§eNext file: " + nextTask.file.getName() + " (" + nextTask.totalParts + " parts)");
                sendMessage("§aPress the confirm key (" + confirmKey.get().toString() + ") to continue, or disable/re-enable module to skip.");
                waitingForConfirm = true;
                pendingNextTask = nextTask;
                pendingFileIndex = nextIndex;
                return;
            } else {
                currentFileIndex = nextIndex;
                currentTask = nextTask;
                currentPart = 1;
                currentPageIndex = 0;
                totalParts = currentTask.totalParts;
                allPages = currentTask.allPages;
                tickDelay = 20;
                sendMessage("Moving to next file: " + currentTask.file.getName());
                sendMessage("Part 1/" + totalParts + " ready for " + currentTask.baseTitle);
                saveProgress();
                return;
            }
        }

        // Normal book signing loop
        ItemStack mainHand = mc.player.getMainHandStack();
        if (mainHand.getItem() != Items.WRITABLE_BOOK) {
            sendMessage("Hold a writable book for: " + currentTask.baseTitle + "-" + String.format("%02d", currentPart));
            tickDelay = 80;
            return;
        }

        signCurrentBook();
        totalBooksCreated++;

        currentPart++;
        currentPageIndex += pagesPerBook.get();
        tickDelay = delayBetweenBooks.get();

        saveProgress();

        if (currentPart <= totalParts) {
            sendMessage("Part " + currentPart + "/" + totalParts + " ready for " + currentTask.baseTitle);
        }
    }

    private void applyNextFile() {
        if (pendingNextTask != null) {
            // Verify the pending file still exists in the tasks list (in case list changed)
            boolean stillExists = false;
            for (int i = 0; i < tasks.size(); i++) {
                if (tasks.get(i).file.getName().equals(pendingNextTask.file.getName())) {
                    stillExists = true;
                    pendingFileIndex = i;
                    break;
                }
            }
            if (!stillExists) {
                sendMessage("§cThe next file no longer exists in the queue. Stopping import.");
                finishImport();
                return;
            }
            currentFileIndex = pendingFileIndex;
            currentTask = pendingNextTask;
            currentPart = 1;
            currentPageIndex = 0;
            totalParts = currentTask.totalParts;
            allPages = currentTask.allPages;
            pendingNextTask = null;
            pendingFileIndex = -1;
            sendMessage("Moving to next file: " + currentTask.file.getName());
            sendMessage("Part 1/" + totalParts + " ready for " + currentTask.baseTitle);
            saveProgress();
        }
    }

    private void signCurrentBook() {
        if (mc.player == null) return;

        if (currentPageIndex >= allPages.size()) {
            sendMessage("§cNo pages remaining for part " + currentPart + ". Skipping.");
            currentPart++;
            currentPageIndex += pagesPerBook.get();
            tickDelay = 10;
            return;
        }

        int endPage = Math.min(currentPageIndex + pagesPerBook.get(), allPages.size());
        if (currentPageIndex >= endPage) {
            sendMessage("§cInvalid page range for part " + currentPart);
            currentPart++;
            currentPageIndex += pagesPerBook.get();
            tickDelay = 10;
            return;
        }

        List<String> partPages = allPages.subList(currentPageIndex, endPage);

        String titleStr;
        if (totalParts > 1) {
            int digits = String.valueOf(totalParts).length();
            if (digits < 2) digits = 2;
            titleStr = currentTask.baseTitle + "-" + String.format("%0" + digits + "d", currentPart);
        } else {
            titleStr = currentTask.baseTitle;
        }

        List<RawFilteredPair<Text>> filteredPages = new ArrayList<>();
        for (String pageContent : partPages) {
            Text pageText = Text.literal(pageContent);
            filteredPages.add(RawFilteredPair.of(pageText));
        }

        WrittenBookContentComponent content = new WrittenBookContentComponent(
            RawFilteredPair.of(titleStr),
            mc.player.getName().getString(),
            0,
            filteredPages,
            true
        );

        mc.player.getMainHandStack().set(DataComponentTypes.WRITTEN_BOOK_CONTENT, content);
        mc.player.networkHandler.sendPacket(new BookUpdateC2SPacket(
            mc.player.getInventory().selectedSlot,
            partPages,
            Optional.of(titleStr)
        ));

        sendMessage("Created: " + titleStr + " (" + partPages.size() + " pages)");
    }

    private void finishImport() {
        isImporting = false;
        waitingForConfirm = false;
        // Do NOT reset progress – keep it for future runs
        sendMessage("§aImport complete! Created " + totalBooksCreated + " book(s)");
        toggle();
    }

    private void sendMessage(String msg) {
        info(msg);
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal(msg), false);
        }
    }

    // ========== Page conversion (Textbook logic) ==========
    private List<String> convertLinesToPages(List<String> lines) {
        List<String> pages = new ArrayList<>();
        StringBuilder currentPage = new StringBuilder();
        for (String line : lines) {
            processLine(line, currentPage, pages);
        }
        if (!currentPage.isEmpty()) {
            pages.add(currentPage.toString());
        }
        return pages;
    }

    private void processLine(String line, StringBuilder currentPage, List<String> pages) {
        boolean lineFitsOnCurrentPage = fitsOnPage(currentPage + line);
        if (lineFitsOnCurrentPage) {
            addLineToPage(line, currentPage);
            return;
        }
        boolean lineFitsOnNewPage = fitsOnPage(line);
        if (lineFitsOnNewPage) {
            startNewPage(currentPage, pages);
            addLineToPage(line, currentPage);
            return;
        }
        processLineLongerThanPage(line, currentPage, pages);
    }

    private void addLineToPage(String line, StringBuilder currentPage) {
        currentPage.append(line).append(LINE_SEPARATOR);
    }

    private void startNewPage(StringBuilder currentPage, List<String> pages) {
        pages.add(currentPage.toString());
        currentPage.setLength(0);
    }

    private void processLineLongerThanPage(String line, StringBuilder currentPage, List<String> pages) {
        String[] words = line.split(" ");
        StringBuilder linePartForCurrentPage = new StringBuilder();
        for (String word : words) {
            boolean pageHasRoomForWord = fitsOnPage(currentPage.toString() + linePartForCurrentPage.toString() + word + " ");
            if (pageHasRoomForWord) {
                linePartForCurrentPage.append(word).append(" ");
                continue;
            }
            boolean pageHasContents = !currentPage.isEmpty() || !linePartForCurrentPage.isEmpty();
            if (pageHasContents) {
                currentPage.append(linePartForCurrentPage);
                startNewPage(currentPage, pages);
                linePartForCurrentPage.setLength(0);
            }
            boolean blankPageCanHoldWord = fitsOnPage(word);
            if (blankPageCanHoldWord) {
                linePartForCurrentPage.append(word).append(" ");
                continue;
            }
            splitLongWordAcrossPages(word, currentPage, pages);
        }
        if (!currentPage.isEmpty() || !linePartForCurrentPage.isEmpty()) {
            addLineToPage(linePartForCurrentPage.toString(), currentPage);
        }
    }

    private void splitLongWordAcrossPages(String word, StringBuilder currentPage, List<String> pages) {
        char[] chars = word.toCharArray();
        for (char c : chars) {
            if (!fitsOnPage(currentPage.toString() + c)) {
                startNewPage(currentPage, pages);
            }
            currentPage.append(c);
        }
        if (fitsOnPage(currentPage + " ")) {
            currentPage.append(" ");
        } else {
            startNewPage(currentPage, pages);
        }
    }

    private boolean fitsOnPage(String text) {
        if (text.length() >= MAX_PAGE_CHARS) return false;
        TextHandler textHandler = mc.textRenderer.getTextHandler();
        TextHandler.WidthRetriever widthRetriever = ((TextHandlerAccessor) textHandler).getWidthRetriever();
        float currentLineWidth = 0;
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                currentLineWidth = 0;
                lines++;
                continue;
            }
            float charWidth = widthRetriever.getWidth(c, Style.EMPTY);
            if (currentLineWidth + charWidth > MAX_PAGE_WIDTH && currentLineWidth > 0) {
                currentLineWidth = charWidth;
                lines++;
            } else {
                currentLineWidth += charWidth;
            }
            if (lines > MAX_PAGE_HEIGHT / 9) {
                return false;
            }
        }
        int totalHeight = lines * 9;
        return totalHeight <= MAX_PAGE_HEIGHT;
    }
}
