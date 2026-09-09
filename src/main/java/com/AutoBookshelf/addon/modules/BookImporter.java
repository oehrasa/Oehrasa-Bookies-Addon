package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.mixin.accessor.AbstractSignEditScreenAccessor;
import com.AutoBookshelf.addon.modules.remote.BookEntry;
import com.AutoBookshelf.addon.modules.remote.RemoteBookSelectScreen;
import com.AutoBookshelf.addon.modules.remote.RemoteLibrary;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.BookUpdateC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;
import net.minecraft.text.RawFilteredPair;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Text;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Imports .txt files into signed books, either from a local folder/file,
 * or by browsing Ashurbanipal public GitHub-Pages
 */
public class BookImporter extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRemote = settings.createGroup("Remote Library");
    private final SettingGroup sgResume = settings.createGroup("Resume");
    private final SettingGroup sgSign = settings.createGroup("Group Sign (Remote Mode)");

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
        .description("Delete .txt files after importing. Ignored for remote-library imports.")
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

    private final Setting<String> manifestUrl = sgRemote.add(new StringSetting.Builder()
        .name("manifest-url")
        .description("URL to index.json on GitHub Page.")
        .defaultValue("https://oehrasa.github.io/Ashurbanipal/index.json")
        .build()
    );

    public enum ImportSource {
        LocalFolder,
        RemoteLibrary
    }

    private final Setting<ImportSource> importSource = sgGeneral.add(new EnumSetting.Builder<ImportSource>()
        .name("import-source")
        .description("Which source to pull from when the module is activated.")
        .defaultValue(ImportSource.LocalFolder)
        .onChanged(v -> cancelActiveImport())
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

    // Group-sign settings (story-mode fill of the manifest's "group" title)
    private final Setting<Boolean> writeGroupSign = sgSign.add(new BoolSetting.Builder()
        .name("write-group-sign")
        .description("After a remote book finishes importing, fill a sign you place with its 'group' title.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> signPacketDelay = sgSign.add(new IntSetting.Builder()
        .name("sign-packet-delay")
        .description("Ticks to delay before sending each sign update packet.")
        .defaultValue(20)
        .min(0)
        .max(200)
        .visible(writeGroupSign::get)
        .build()
    );

    // Page constraints
    private static final int MAX_PAGE_CHARS = 1024;
    private static final int MAX_PAGE_WIDTH = 114;
    private static final int MAX_PAGE_HEIGHT = 128;
    private static final String LINE_SEPARATOR = "\n";
    private static final int MAX_SIGN_LINE_WIDTH = 90;

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
    private boolean manuallySubscribed = false;

    // Progress persistence (store completed source and part keys)
    private static final String PROGRESS_FILE = "AutoBookshelf/import_progress.json";
    private final Set<String> completedParts = new HashSet<>();  // keys = "sourceName|partNumber"
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // Group-sign story-mode state
    private final Deque<String> pendingSignGroups = new ArrayDeque<>();
    private final List<String> currentSignWords = new ArrayList<>();
    private int signWordIndex = 0;
    private final ArrayDeque<UpdateSignC2SPacket> signPacketQueue = new ArrayDeque<>();
    private int signPacketTimer = 0;

    private static class ImportTask {
        File file;          // null for remote tasks
        BookEntry entry;    // null for local tasks
        String sourceName;  // file.getName() locally, entry.file remotely
        String baseTitle;
        String group;       // null for local tasks; manifest "group" for remote tasks
        List<String> allPages;
        int totalParts;

        static ImportTask local(File file, String baseTitle, List<String> allPages, int totalParts) {
            ImportTask t = new ImportTask();
            t.file = file;
            t.sourceName = file.getName();
            t.baseTitle = baseTitle;
            t.allPages = allPages;
            t.totalParts = totalParts;
            return t;
        }

        static ImportTask remote(BookEntry entry, String baseTitle, List<String> allPages, int totalParts) {
            ImportTask t = new ImportTask();
            t.entry = entry;
            t.sourceName = entry.file;
            t.baseTitle = baseTitle;
            t.group = entry.group;
            t.allPages = allPages;
            t.totalParts = totalParts;
            return t;
        }

        boolean isRemote() {
            return entry != null;
        }
    }

    public BookImporter() {
        super(Addon.CATEGORY, "Book-Import", "Automatically imports text files into signed books.");
    }

    private enum PendingSource {NONE, REMOTE}

    private PendingSource pendingSource = PendingSource.NONE;

    @Override
    public void onActivate() {
        if (manuallySubscribed) {
            MeteorClient.EVENT_BUS.unsubscribe(this);
            manuallySubscribed = false;
        }

        if (mc.player == null || mc.world == null) {
            error("Cannot activate module while not in a world.");
            toggle();
            return;
        }

        if (pendingSource == PendingSource.REMOTE) {
            // tasks already populated by onRemoteSelectionConfirmed(). cant scan local here.
            pendingSource = PendingSource.NONE;
            if (tasks.isEmpty()) {
                sendMessage("No remote books were queued.");
                toggle();
                return;
            }
            beginImport();
            return;
        }

        if (importSource.get() == ImportSource.RemoteLibrary) {
            // Can't produce tasks synchronously (manifest fetch + download are async)
            toggle();
            openRemoteBrowser();
            return;
        }

        loadProgress();      // loads completed parts set from file
        if (!scanAndQueueFiles()) {
            toggle();
            return;
        }

        if (tasks.isEmpty()) {
            sendMessage("No .txt files found in " + importFolder.get());
            toggle();
            return;
        }

        beginImport();
    }

    private void openRemoteBrowser() {
        sendMessage("Fetching remote library manifest...");

        CompletableFuture
            .supplyAsync(() -> {
                try {
                    return RemoteLibrary.fetchManifest(manifestUrl.get());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            })
            .whenComplete((entries, throwable) -> mc.execute(() -> {
                if (throwable != null) {
                    error("Failed to load remote manifest: " + throwable.getCause());
                    return;
                }
                if (entries.isEmpty()) {
                    sendMessage("Remote manifest is empty.");
                    return;
                }

                mc.setScreen(new RemoteBookSelectScreen(entries, this::onRemoteSelectionConfirmed));
            }));
    }

    private void onRemoteSelectionConfirmed(List<BookEntry> selected) {
        if (mc.player == null || mc.world == null) {
            error("Cannot start import while not in a world.");
            return;
        }

        sendMessage("Downloading " + selected.size() + " book(s)...");

        CompletableFuture
            .supplyAsync(() -> {
                Map<BookEntry, List<String>> fetched = new LinkedHashMap<>();
                for (BookEntry entry : selected) {
                    try {
                        fetched.put(entry, RemoteLibrary.fetchLines(entry.url));
                    } catch (Exception e) {
                        mc.execute(() -> error("Failed to download " + entry.file + ": " + e.getMessage()));
                    }
                }
                return fetched;
            })
            .whenComplete((fetched, throwable) -> mc.execute(() -> {
                if (throwable != null) {
                    error("Download failed: " + throwable.getCause());
                    return;
                }

                List<ImportTask> queued = new ArrayList<>();
                for (Map.Entry<BookEntry, List<String>> e : fetched.entrySet()) {
                    BookEntry entry = e.getKey();
                    List<String> pages = convertLinesToPages(e.getValue());
                    if (pages.isEmpty()) continue;

                    String baseTitle = entry.title != null ? entry.title : entry.file;
                    if (baseTitle.length() > 32) baseTitle = baseTitle.substring(0, 32);

                    int parts = (int) Math.ceil((double) pages.size() / pagesPerBook.get());
                    queued.add(ImportTask.remote(entry, baseTitle, pages, parts));
                }

                if (queued.isEmpty()) {
                    sendMessage("Nothing downloaded successfully, cancelling.");
                    return;
                }

                loadProgress();
                tasks.clear();
                tasks.addAll(queued);
                for (ImportTask t : queued) {
                    sendMessage("Queued: " + t.sourceName + " (" + t.allPages.size() + " pages, " + t.totalParts + " part(s))");
                }

                if (!isActive()) {
                    pendingSource = PendingSource.REMOTE;
                    toggle();
                } else {
                    beginImport();
                }
            }));
    }

    // Shared start-up logic (resume position, progress lookup) for both local and remote task lists.
    private void beginImport() {
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
                    String key = task.sourceName + "|" + part;
                    if (!completedParts.contains(key)) {
                        startIndex = i;
                        startPart = part;
                        foundIncomplete = true;
                        sendMessage("§aResuming from incomplete: " + task.sourceName + " part " + part + "/" + task.totalParts);
                        break;
                    }
                }
                if (foundIncomplete) break;
            }
            if (!foundIncomplete) {
                sendMessage("§eAll files appear to be fully imported. To re-import, delete progress or files.");
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
        sendMessage("Starting with: " + currentTask.sourceName + " part " + currentPart + "/" + totalParts);

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
        pendingSource = PendingSource.NONE;
        tasks.clear();
        currentTask = null;
        tickDelay = 0;
        saveProgress();

        if ((!pendingSignGroups.isEmpty() || !currentSignWords.isEmpty() || !signPacketQueue.isEmpty())
            && Utils.canUpdate()) {
            MeteorClient.EVENT_BUS.subscribe(this);
            manuallySubscribed = true;
        }
    }

    private void saveProgress() {
        if (!persistentProgress.get()) return;
        try {
            Path progressPath = Paths.get(mc.runDirectory.getPath(), PROGRESS_FILE);
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
            Path progressPath = Paths.get(mc.runDirectory.getPath(), PROGRESS_FILE);
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
            Path progressPath = Paths.get(mc.runDirectory.getPath(), PROGRESS_FILE);
            Files.deleteIfExists(progressPath);
        } catch (IOException e) {
            error("Failed to reset progress: " + e.getMessage());
        }
        sendMessage("§aProgress has been reset!");
        if (isActive()) {
            toggle();
        }
    }

    private boolean scanAndQueueFiles() {
        tasks.clear();

        if (useSelectedFile.get()) {
            String path = selectedFilePath.get();
            if (path.isEmpty()) {
                sendMessage("§cNo file selected. Please use the 'Select File' button.");
                return false;
            }
            File file = new File(path);
            if (!file.exists() || !file.getName().endsWith(".txt")) {
                sendMessage("§cSelected file is not a valid .txt file.");
                return false;
            }
            try {
                List<String> lines = Files.readAllLines(file.toPath());
                List<String> pages = convertLinesToPages(lines);
                if (pages.isEmpty()) {
                    sendMessage("§cFile is empty.");
                    return false;
                }
                String baseTitle = file.getName().replace(".txt", "");
                if (baseTitle.length() > 32) baseTitle = baseTitle.substring(0, 32);
                int totalParts = (int) Math.ceil((double) pages.size() / pagesPerBook.get());
                tasks.add(ImportTask.local(file, baseTitle, pages, totalParts));
                sendMessage("Queued: " + file.getName() + " (" + pages.size() + " pages, " + totalParts + " part(s))");
            } catch (IOException e) {
                sendMessage("§cFailed to read file.");
                return false;
            }
            return true;
        }

        Path folder = Paths.get(mc.runDirectory.getPath(), importFolder.get());

        if (!Files.exists(folder)) {
            try {
                Files.createDirectories(folder);
                sendMessage("§aCreated folder: " + folder);
                sendMessage("§7Place your .txt files in this folder, then re-enable the module");
                return false;
            } catch (IOException e) {
                error("Failed to create folder: " + folder);
                return false;
            }
        }

        File[] files = folder.toFile().listFiles((dir, name) -> name.endsWith(".txt"));
        if (files == null) return true;

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
                tasks.add(ImportTask.local(file, baseTitle, pages, totalParts));
                sendMessage("Queued: " + file.getName() + " (" + pages.size() + " pages, " + totalParts + " part(s))");
            } catch (IOException e) {
                error("Failed to read file: " + file.getName());
            }
        }
        return true;
    }

    private void cancelActiveImport() {
        if (isActive()) {
            sendMessage("§eCancelling current import to switch source...");
            toggle(); // synchronously runs onDeactivate(): clears tasks, resets isImporting/currentTask, saves progress
        }
        pendingSource = PendingSource.NONE;
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WHorizontalList row = theme.horizontalList();

        WButton selectBtn = row.add(theme.button("Select File")).widget();
        selectBtn.action = () -> {
            String path = TinyFileDialogs.tinyfd_openFileDialog(
                "Select a .txt file",
                new File(mc.runDirectory, importFolder.get()).getAbsolutePath(),
                null,
                null,
                false
            );
            if (path != null) {
                cancelActiveImport();
                selectedFilePath.set(path);
                useSelectedFile.set(true);
                importSource.set(ImportSource.LocalFolder);
                info("Selected file: " + path);
            }
        };

        WButton clearBtn = row.add(theme.button("ManFile Clear")).widget();
        clearBtn.action = () -> {
            cancelActiveImport();
            selectedFilePath.set("");
            useSelectedFile.set(false);
            importSource.set(ImportSource.LocalFolder);
            info("Cleared manual file selection.");
        };

        WButton browseRemoteBtn = row.add(theme.button("Browse Remote Library")).widget();
        browseRemoteBtn.action = () -> {
            cancelActiveImport();
            importSource.set(ImportSource.RemoteLibrary);
            openRemoteBrowser();
        };

        WButton resetBtn = row.add(theme.button("Reset Progress")).widget();
        resetBtn.action = this::resetProgressData;

        WButton saveBtn = row.add(theme.button("Save Now")).widget();
        saveBtn.action = this::saveProgress;

        return row;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!signPacketQueue.isEmpty()) {
            signPacketTimer++;
            if (signPacketTimer >= signPacketDelay.get() && mc.getNetworkHandler() != null) {
                signPacketTimer = 0;
                mc.getNetworkHandler().getConnection().send(signPacketQueue.removeFirst(), null);
            }
        } else if (!isActive() && pendingSignGroups.isEmpty() && currentSignWords.isEmpty()) {
            MeteorClient.EVENT_BUS.unsubscribe(this);
            manuallySubscribed = false;
        }

        if (!isImporting) return;
        if (mc.player == null || mc.world == null) {
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
                completedParts.add(currentTask.sourceName + "|" + i);
            }
            saveProgress();

            // Queue this book's manifest "group" title for the next sign the player places.
            if (writeGroupSign.get() && currentTask.isRemote() && currentTask.group != null && !currentTask.group.isBlank()) {
                pendingSignGroups.addLast(currentTask.group);
            }

            if (deleteAfterImport.get() && !currentTask.isRemote() && currentTask.file != null) {
                try {
                    Files.delete(currentTask.file.toPath());
                    sendMessage("Deleted: " + currentTask.sourceName);
                } catch (IOException e) {
                    error("Failed to delete: " + currentTask.sourceName);
                }
            }

            int nextIndex = currentFileIndex + 1;
            if (nextIndex >= tasks.size()) {
                finishImport();
                return;
            }

            ImportTask nextTask = tasks.get(nextIndex);
            if (requireConfirmNextFile.get()) {
                sendMessage("§6=== File completed: " + currentTask.sourceName + " ===");
                sendMessage("§eNext file: " + nextTask.sourceName + " (" + nextTask.totalParts + " parts)");
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
                sendMessage("Moving to next file: " + currentTask.sourceName);
                sendMessage("Part 1/" + totalParts + " ready for " + currentTask.baseTitle);
                saveProgress();
            }
            return;
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

        // Mark this part as completed
        completedParts.add(currentTask.sourceName + "|" + currentPart);
        saveProgress();

        currentPart++;
        currentPageIndex += pagesPerBook.get();
        tickDelay = delayBetweenBooks.get();

        if (currentPart <= totalParts) {
            sendMessage("Part " + currentPart + "/" + totalParts + " ready for " + currentTask.baseTitle);
        }
    }

    @EventHandler
    private void onSignScreenOpened(OpenScreenEvent event) {
        if (!writeGroupSign.get()) return;
        if (!(event.screen instanceof AbstractSignEditScreen editScreen)) return;
        if (!refillCurrentSignWords()) return;

        SignBlockEntity sign = ((AbstractSignEditScreenAccessor) editScreen).getBlockEntity();
        if (sign == null) return;

        event.cancel();
        String[] lines = getNextGroupSignLines();
        if (signPacketQueue.isEmpty()) signPacketTimer = 0;
        signPacketQueue.addLast(new UpdateSignC2SPacket(sign.getPos(), true, lines[0], lines[1], lines[2], lines[3]));
    }

    private boolean refillCurrentSignWords() {
        if (!currentSignWords.isEmpty()) return true;
        if (pendingSignGroups.isEmpty()) return false;
        String group = pendingSignGroups.pollFirst();
        for (String w : group.trim().split("\\s+")) {
            if (!w.isEmpty()) currentSignWords.add(w);
        }
        signWordIndex = 0;
        return !currentSignWords.isEmpty();
    }

    private String[] getNextGroupSignLines() {
        String[] lines = new String[4];
        for (int n = 0; n < 4; n++) {
            StringBuilder line = new StringBuilder();
            while (signWordIndex < currentSignWords.size()) {
                String word = currentSignWords.get(signWordIndex);
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (mc.textRenderer.getWidth(candidate) > MAX_SIGN_LINE_WIDTH) {
                    if (line.isEmpty()) {
                        line.append(mc.textRenderer.trimToWidth(word, MAX_SIGN_LINE_WIDTH));
                        signWordIndex++;
                    }
                    break;
                }
                line = new StringBuilder(candidate);
                signWordIndex++;
            }
            lines[n] = line.toString();
        }
        // Always start fresh on the next sign.
        currentSignWords.clear();
        signWordIndex = 0;
        return lines;
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
            sendMessage("Moving to next file: " + currentTask.sourceName);
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
            mc.player.sendMessage(Text.literal(msg), false);
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
            && mc.textRenderer.getWrappedLinesHeight(StringVisitable.plain(text), MAX_PAGE_WIDTH) <= MAX_PAGE_HEIGHT;
    }
}
