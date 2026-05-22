package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import com.mojang.text2speech.Narrator;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.WritableBookContentComponent;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.RawFilteredPair;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class AudiobookReader extends Module {
    public enum DisplayMode {
        NONE("None"),
        CHAT("Chat Only"),
        SCREEN("Screen Only"),
        BOTH("Both");

        private final String title;
        DisplayMode(String title) { this.title = title; }
        @Override public String toString() { return title; }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgVoice = settings.createGroup("Voice Settings");
    private final SettingGroup sgDisplay = settings.createGroup("Display");

    // General settings
    private final Setting<Keybind> playKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("play-key")
        .description("Key to start reading the book")
        .defaultValue(Keybind.fromKey(GLFW.GLFW_KEY_V))
        .build()
    );

    private final Setting<Keybind> stopKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("stop-key")
        .description("Key to stop reading")
        .defaultValue(Keybind.fromKey(GLFW.GLFW_KEY_B))
        .build()
    );

    // Voice settings for duration estimation
    private final Setting<Integer> wordsPerMinute = sgVoice.add(new IntSetting.Builder()
        .name("words-per-minute")
        .description("Estimated narrator speaking speed (words per minute)")
        .defaultValue(150)
        .min(80)
        .max(250)
        .sliderRange(80, 250)
        .build()
    );

    // Display settings
    private final Setting<DisplayMode> displayMode = sgDisplay.add(new EnumSetting.Builder<DisplayMode>()
        .name("display-mode")
        .description("Where to show the reading progress")
        .defaultValue(DisplayMode.BOTH)
        .build()
    );

    private final Setting<Boolean> showProgressBar = sgDisplay.add(new BoolSetting.Builder()
        .name("show-progress-bar")
        .description("Show a progress bar for current page")
        .defaultValue(true)
        .visible(() -> displayMode.get() != DisplayMode.NONE)
        .build()
    );

    private final Setting<Double> screenScale = sgDisplay.add(new DoubleSetting.Builder()
        .name("screen-scale")
        .description("Scale of the on-screen text")
        .defaultValue(1.2)
        .min(0.5)
        .max(2.0)
        .sliderRange(0.5, 2.0)
        .visible(() -> displayMode.get() == DisplayMode.SCREEN || displayMode.get() == DisplayMode.BOTH)
        .build()
    );

    private Narrator narrator;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> currentTask;
    private boolean isReading = false;
    private List<String> currentPages = new ArrayList<>();
    private int currentPage = 0;
    private String currentBookTitle = "";

    // Page progress tracking
    private String currentPageText = "";
    private int totalCharsInPage = 0;
    private int spokenChars = 0;
    private int currentWordIndex = 0;
    private List<Integer> wordPositions = new ArrayList<>();
    private long pageStartTime = 0;
    private long estimatedPageDuration = 0;

    // On-screen display
    private String displayText = "";
    private int displayTimer = 0;
    private float pageProgress = 0;

    public AudiobookReader() {
        super(Addon.CATEGORY, "Audio-Book", "Reads books aloud using narrator feature.");
    }

    @Override
    public void onActivate() {
        try {
            narrator = Narrator.getNarrator();
            executor = Executors.newSingleThreadScheduledExecutor();
            info("§aAudiobook Reader activated.");
        } catch (Exception e) {
            error("§cFailed to initialize: " + e.getMessage());
        }
    }

    @Override
    public void onDeactivate() {
        stopReading();
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        narrator = null;
        displayText = "";
        displayTimer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;

        if (isReading && currentPages.size() > 0) {
            float pageFraction = 0;
            if (estimatedPageDuration > 0) {
                long elapsed = System.currentTimeMillis() - pageStartTime;
                pageFraction = Math.min(1.0f, (float) elapsed / estimatedPageDuration);
            }
            // currentPage already points to the next page, so subtract 1
            pageProgress = (currentPage - 1 + pageFraction) / currentPages.size();

            if (displayTimer < 5) displayTimer = 5;
        }

        if (playKey.get().isPressed() && !isReading) {
            ItemStack stack = mc.player.getMainHandStack();
            if (isReadableBook(stack)) {
                startReadingBook(stack);
            }
        }

        if (stopKey.get().isPressed() && isReading) {
            stopReading();
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (displayMode.get() == DisplayMode.NONE) return;

        int screenWidth = event.screenWidth;
        int screenHeight = event.screenHeight;
        double scale = screenScale.get();

        int x = (int) (screenWidth / 2 - (mc.textRenderer.getWidth(displayText) * scale) / 2);
        int y = screenHeight - 70;

        event.drawContext.getMatrices().pushMatrix();
        event.drawContext.getMatrices().translate(x, y);
        event.drawContext.getMatrices().scale((float) scale, (float) scale);

        // Draw text
        event.drawContext.drawText(mc.textRenderer, displayText, 0, 0, 0xFFFFD700, true);

        // Draw progress bar if enabled
        if (showProgressBar.get() && isReading && currentPages.size() > 0) {
            int barWidth = mc.textRenderer.getWidth(displayText);
            int barHeight = 3;
            int barY = mc.textRenderer.fontHeight + 2;

            // Background
            event.drawContext.fill(0, barY, barWidth, barY + barHeight, 0x44000000);
            // Global progress
            int progressWidth = (int) (barWidth * pageProgress);
            event.drawContext.fill(0, barY, progressWidth, barY + barHeight, 0xFFFFD700);
        }

        event.drawContext.getMatrices().popMatrix();
    }

    private void sendMessage(String msg) {
        if (displayMode.get() == DisplayMode.CHAT || displayMode.get() == DisplayMode.BOTH) {
            info(msg);
        }
    }

    private void startReadingBook(ItemStack stack) {
        List<String> pages = getPagesFromBook(stack);
        if (pages.isEmpty()) {
            sendMessage("§cThis book has no content to read!");
            return;
        }

        currentPages = pages;
        currentPage = 0;
        currentBookTitle = getBookTitle(stack);
        isReading = true;

        sendMessage("§aNow reading: §f" + currentBookTitle);

        readNextPage();
    }

    private void updateDisplayForCurrentPage() {
        int pageNumber = currentPage + 1;
        int totalPages = currentPages.size();
        String progressText = String.format("Reading page %d/%d", pageNumber, totalPages);

        if (displayMode.get() == DisplayMode.CHAT || displayMode.get() == DisplayMode.BOTH) {
            info("§7" + progressText);
        }

        if (displayMode.get() == DisplayMode.SCREEN || displayMode.get() == DisplayMode.BOTH) {
            displayText = progressText;
            displayTimer = (int) (estimatedPageDuration / 50) + 20; // Keep visible during page
        }
    }

    private void readNextPage() {
        if (!isReading || currentPage >= currentPages.size()) {
            finishReading();
            return;
        }

        currentPageText = stripMinecraftFormatting(currentPages.get(currentPage));
        totalCharsInPage = currentPageText.length();

        // Estimate page duration based on words and speaking rate
        int wordCount = currentPageText.split("\\s+").length;
        estimatedPageDuration = (long) (wordCount * 60000.0 / wordsPerMinute.get());

        pageStartTime = System.currentTimeMillis();
        pageProgress = 0;

        updateDisplayForCurrentPage();

        // Speak the page
        if (narrator != null && !currentPageText.isEmpty()) {
            narrator.say(currentPageText, false, 1.0f);
        }

        currentPage++;

        // Schedule next page based on estimated duration
        if (currentPage < currentPages.size()) {
            currentTask = executor.schedule(() -> {
                mc.execute(this::readNextPage);
            }, estimatedPageDuration, TimeUnit.MILLISECONDS);
        } else {
            finishReading();
        }
    }

    private void stopReading() {
        if (currentTask != null) {
            currentTask.cancel(false);
            currentTask = null;
        }
        if (narrator != null) {
            narrator.clear();
        }
        isReading = false;
        pageProgress = 0;
        sendMessage("§aReading stopped");
        displayText = "Reading stopped";
        displayTimer = 40;
    }

    private void finishReading() {
        isReading = false;
        currentPages.clear();
        pageProgress = 0;
        sendMessage("§aFinished reading the book!");
        displayText = "Finished reading!";
        displayTimer = 60;
    }

    private String stripMinecraftFormatting(String text) {
        return text.replaceAll("§[0-9a-fk-or]", "");
    }

    private List<String> getPagesFromBook(ItemStack stack) {
        List<String> pages = new ArrayList<>();

        WrittenBookContentComponent writtenContent = stack.get(DataComponentTypes.WRITTEN_BOOK_CONTENT);
        if (writtenContent != null) {
            for (Text page : writtenContent.getPages(false)) {
                pages.add(page.getString());
            }
        }

        WritableBookContentComponent writableContent = stack.get(DataComponentTypes.WRITABLE_BOOK_CONTENT);
        if (writableContent != null) {
            for (RawFilteredPair<String> pair : writableContent.pages()) {
                pages.add(pair.get(false));
            }
        }

        return pages;
    }

    private boolean isReadableBook(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return stack.getItem() == Items.WRITTEN_BOOK ||
            stack.getItem() == Items.WRITABLE_BOOK;
    }

    private String getBookTitle(ItemStack stack) {
        WrittenBookContentComponent content = stack.get(DataComponentTypes.WRITTEN_BOOK_CONTENT);
        if (content != null) {
            return content.title().raw();
        }
        return "Untitled Book";
    }
}
