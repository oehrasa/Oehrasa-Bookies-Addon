package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

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
        .description("Folder path containing .txt files to import.")
        .defaultValue("AutoBookshelf/books")
        .build()
    );

    private final Setting<Integer> pagesPerBook = sgGeneral.add(new IntSetting.Builder()
        .name("pages-per-book")
        .description("Maximum pages per book.")
        .defaultValue(100)
        .min(1)
        .max(100)
        .build()
    );

    private final Setting<Boolean> deleteAfterImport = sgGeneral.add(new BoolSetting.Builder()
        .name("delete-after-import")
        .description("Delete .txt files after importing.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> delayBetweenBooks = sgGeneral.add(new IntSetting.Builder()
        .name("delay-between-books")
        .description("Ticks to wait between creating each book.")
        .defaultValue(60)
        .min(0)
        .max(100)
        .build()
    );

    private final Setting<Boolean> persistentProgress = sgResume.add(new BoolSetting.Builder()
        .name("persistent-progress")
        .description("Save progress to file and resume after restart/crash.")
        .defaultValue(true)
        .build()
    );

    // Manual override
    private final Setting<Integer> startFromFileIndex = sgResume.add(new IntSetting.Builder()
        .name("start-from-file-index")
        .description("Start from file at this position (1 = first file in sorted order).")
        .defaultValue(1)
        .min(1)
        .max(100)
        .build()
    );

    private final Setting<Integer> startFromPart = sgResume.add(new IntSetting.Builder()
        .name("start-from-part")
        .description("Start from this part number within the file.")
        .defaultValue(1)
        .min(1)
        .max(100)
        .build()
    );

    private final Setting<Boolean> requireConfirmNextFile = sgResume.add(new BoolSetting.Builder()
        .name("confirm-next-file")
        .description("Wait for key press before moving to next file")
        .defaultValue(true)
        .build()
    );

    private final Setting<Keybind> confirmKey = sgResume.add(new KeybindSetting.Builder()
        .name("confirm-key")
        .description("Key to press to confirm moving to next file")
        .defaultValue(Keybind.fromKey(84))
        .visible(requireConfirmNextFile::get)
        .build()
    );

    private final Setting<Boolean> useSelectedFile = sgGeneral.add(new BoolSetting.Builder()
        .name("use-selected-file")
        .description("Use a manually selected .txt file instead of the import folder.")
        .defaultValue(false)
        .build()
    );

    private final Setting<String> selectedFilePath = sgGeneral.add(new StringSetting.Builder()
        .name("selected-file-path")
        .description("Path of the manually selected file (set via the button).")
        .defaultValue("")
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

    // Progress persistence (store completed file and part keys)
    private static final String PROGRESS_FILE = "AutoBookshelf/import_progress.json";
    private final Set<String> completedParts = new HashSet<>();  // keys = "fileName|partNumber"
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

    public BookImporter() {
        super(Addon.CATEGORY, "Book-Import", "Automatically imports text files into signed books.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.level == null) {
            error("Cannot activate module while not in a world.");
            toggle();
            return;
        }

        loadProgress();      // loads completed parts set from file
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

        // Find first incomplete task (skipping already completed parts)
        boolean foundIncomplete = false;
        if (!userOverride && persistentProgress.get()) {
            for (int i = 0; i < tasks.size(); i++) {
                ImportTask task = tasks.get(i);
                for (int part = 1; part <= task.totalParts; part++) {
                    String key = task.file.getName() + "|" + part;
                    if (!completedParts.contains(key)) {
                        startIndex = i;
                        startPart = part;
                        foundIncomplete = true;
                        sendMessage("§aResuming from incomplete: " + task.file.getName() + " part " + part + "/" + task.totalParts);
                        break;
                    }
                }
                if (foundIncomplete) break;
            }
            if (!foundIncomplete) {
                sendMessage("§eAll files appear to be fully imported. To re‑import, delete progress or files.");
                toggle();
                return;
            }
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
        saveProgress();
    }

    private void saveProgress() {
        if (!persistentProgress.get()) return;
        try {
            Path progressPath = Paths.get(mc.gameDirectory.getPath(), PROGRESS_FILE);
            Files.createDirectories(progressPath.getParent());
            Map<String, Object> data = new HashMap<>();
            data.put("completedParts", new ArrayList<>(completedParts));
            data.put("lastUpdated", System.currentTimeMillis());
            String json = gson.toJson(data);
            Files.writeString(progressPath, json);
        } catch (IOException e) {
            error("Failed to save progress: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void loadProgress() {
        completedParts.clear();
        if (!persistentProgress.get()) return;
        try {
            Path progressPath = Paths.get(mc.gameDirectory.getPath(), PROGRESS_FILE);
            if (Files.exists(progressPath)) {
                String json = Files.readString(progressPath);
                Map<String, Object> data = gson.fromJson(json, Map.class);
                if (data.containsKey("completedParts")) {
                    List<String> parts = (List<String>) data.get("completedParts");
                    completedParts.addAll(parts);
                }
            }
        } catch (Exception e) {
            error("Failed to load progress: " + e.getMessage());
        }
    }

    private void resetProgressData() {
        completedParts.clear();
        try {
            Path progressPath = Paths.get(mc.gameDirectory.getPath(), PROGRESS_FILE);
            Files.deleteIfExists(progressPath);
        } catch (IOException e) {
            error("Failed to reset progress: " + e.getMessage());
        }
        sendMessage("§aProgress has been reset!");
        if (isActive()) {
            toggle();
        }
    }

    private void scanAndQueueFiles() {
        tasks.clear();

        if (useSelectedFile.get()) {
            String path = selectedFilePath.get();
            if (path.isEmpty()) {
                sendMessage("§cNo file selected. Please use the 'Select File' button.");
                toggle();
                return;
            }
            File file = new File(path);
            if (!file.exists() || !file.getName().endsWith(".txt")) {
                sendMessage("§cSelected file is not a valid .txt file.");
                toggle();
                return;
            }
            try {
                List<String> lines = Files.readAllLines(file.toPath());
                List<String> pages = convertLinesToPages(lines);
                if (pages.isEmpty()) {
                    sendMessage("§cFile is empty.");
                    toggle();
                    return;
                }
                String baseTitle = file.getName().replace(".txt", "");
                if (baseTitle.length() > 32) baseTitle = baseTitle.substring(0, 32);
                int totalParts = (int) Math.ceil((double) pages.size() / pagesPerBook.get());
                tasks.add(new ImportTask(file, baseTitle, pages, totalParts));
                sendMessage("Queued: " + file.getName() + " (" + pages.size() + " pages, " + totalParts + " part(s))");
            } catch (IOException e) {
                sendMessage("§cFailed to read file.");
                toggle();
            }
            return;
        }

        Path folder = Paths.get(mc.gameDirectory.getPath(), importFolder.get());

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

        // Custom natural sorting
        Arrays.sort(files, (a, b) -> {
            String nameA = a.getName();
            String nameB = b.getName();
            String baseA = nameA.replaceFirst("\\d.*$", "");
            String baseB = nameB.replaceFirst("\\d.*$", "");
            int baseCompare = baseA.compareToIgnoreCase(baseB);
            if (baseCompare != 0) return baseCompare;
            Pattern pattern = Pattern.compile("\\d+");
            Matcher matcherA = pattern.matcher(nameA);
            Matcher matcherB = pattern.matcher(nameB);
            int numA1 = 0, numA2 = 0, numB1 = 0, numB2 = 0;
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
                if (baseTitle.length() > 32) baseTitle = baseTitle.substring(0, 32);
                int totalParts = (int) Math.ceil((double) pages.size() / pagesPerBook.get());
                tasks.add(new ImportTask(file, baseTitle, pages, totalParts));
                sendMessage("Queued: " + file.getName() + " (" + pages.size() + " pages, " + totalParts + " part(s))");
            } catch (IOException e) {
                error("Failed to read file: " + file.getName());
            }
        }
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WHorizontalList row = theme.horizontalList();

        WButton selectBtn = row.add(theme.button("Select File")).widget();
        selectBtn.action = () -> {
            String path = TinyFileDialogs.tinyfd_openFileDialog(
                "Select a .txt file",
                new File(mc.gameDirectory, importFolder.get()).getAbsolutePath(),
                null,
                null,
                false
            );
            if (path != null) {
                selectedFilePath.set(path);
                useSelectedFile.set(true);
                info("Selected file: " + path);
            }
        };

        WButton clearBtn = row.add(theme.button("Clear Selection")).widget();
        clearBtn.action = () -> {
            selectedFilePath.set("");
            useSelectedFile.set(false);
            info("Cleared manual file selection.");
        };

        WButton resetBtn = row.add(theme.button("Reset Progress")).widget();
        resetBtn.action = this::resetProgressData;

        WButton saveBtn = row.add(theme.button("Save Now")).widget();
        saveBtn.action = this::saveProgress;

        return row;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isImporting) return;
        if (mc.player == null || mc.level == null) {
            isImporting = false;
            return;
        }

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
            // Current file finished then mark all its parts as completed
            for (int i = 1; i <= totalParts; i++) {
                completedParts.add(currentTask.file.getName() + "|" + i);
            }
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
                sendMessage("§aPress the confirm key (" + confirmKey.get().toString() + ") to continue.");
                waitingForConfirm = true;
                pendingNextTask = nextTask;
                pendingFileIndex = nextIndex;
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
            }
            return;
        }

        // Normal book signing loop
        ItemStack mainHand = mc.player.getMainHandItem();
        if (mainHand.getItem() != Items.WRITABLE_BOOK) {
            sendMessage("Hold a writable book for: " + currentTask.baseTitle + "-" + String.format("%02d", currentPart));
            tickDelay = 80;
            return;
        }

        signCurrentBook();
        totalBooksCreated++;

        // Mark this part as completed
        completedParts.add(currentTask.file.getName() + "|" + currentPart);
        saveProgress();

        currentPart++;
        currentPageIndex += pagesPerBook.get();
        tickDelay = delayBetweenBooks.get();

        if (currentPart <= totalParts) {
            sendMessage("Part " + currentPart + "/" + totalParts + " ready for " + currentTask.baseTitle);
        }
    }

    private void applyNextFile() {
        if (pendingNextTask != null) {
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

        List<Filterable<Component>> filteredPages = new ArrayList<>();
        for (String pageContent : partPages) {
            Component pageText = Component.literal(pageContent);
            filteredPages.add(Filterable.passThrough(pageText));
        }

        WrittenBookContent content = new WrittenBookContent(
            Filterable.passThrough(titleStr),
            mc.player.getName().getString(),
            0,
            filteredPages,
            true
        );

        mc.player.getMainHandItem().set(DataComponents.WRITTEN_BOOK_CONTENT, content);
        mc.player.connection.send(new ServerboundEditBookPacket(
            mc.player.getInventory().getSelectedSlot(),
            partPages,
            Optional.of(titleStr)
        ));

        sendMessage("Created: " + titleStr + " (" + partPages.size() + " pages)");
    }

    private void finishImport() {
        isImporting = false;
        waitingForConfirm = false;
        // Do NOT reset progress, it stays as a record of what's done
        sendMessage("§aImport complete! Created " + totalBooksCreated + " books");
        toggle();
    }

    private void sendMessage(String msg) {
        info(msg);
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(msg));
        }
    }

    // Page conversion
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
        boolean lineFitsOnCurrentPage = fitsOnPage(currentPage + line + LINE_SEPARATOR);
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
        if (currentPage.length() > 0) {     // only add if not empty
            pages.add(currentPage.toString());
        }
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
        String leftover = linePartForCurrentPage.toString();
        if (!leftover.isEmpty() || !currentPage.isEmpty()) {
            if (!fitsOnPage(currentPage.toString() + leftover + LINE_SEPARATOR)) {
                startNewPage(currentPage, pages);
            }
            addLineToPage(leftover, currentPage);
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
        return text.length() < MAX_PAGE_CHARS
            && mc.font.wordWrapHeight(FormattedText.of(text), MAX_PAGE_WIDTH) <= MAX_PAGE_HEIGHT;
    }
}
