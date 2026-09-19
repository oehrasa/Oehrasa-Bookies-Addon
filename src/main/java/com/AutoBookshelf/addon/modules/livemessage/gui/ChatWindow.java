package com.AutoBookshelf.addon.modules.livemessage.gui;

import com.AutoBookshelf.addon.modules.livemessage.LiveMessage;
import com.AutoBookshelf.addon.modules.livemessage.util.LiveProfileCache;
import com.AutoBookshelf.addon.modules.livemessage.util.LiveSkinUtil;
import com.AutoBookshelf.addon.modules.livemessage.util.LivemessageUtil;
import com.AutoBookshelf.addon.modules.livemessage.util.WhisperRateLimiter;
import com.AutoBookshelf.addon.utils.EnemyManager;
import com.AutoBookshelf.addon.utils.QueueUtil;
import com.google.gson.Gson;
import meteordevelopment.meteorclient.systems.friends.Friend;
import meteordevelopment.meteorclient.systems.friends.Friends;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.io.*;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatWindow extends LiveWindow {
    private static final Gson GSON = new Gson();
    private static final DateFormat DATE_FORMAT = new SimpleDateFormat("MMMM dd, yyyy");
    private static final DateFormat TIME_FORMAT = new SimpleDateFormat("<HH:mm> ");

    // How close two (sentByMe, message) entries' timestamps have to be treated as
    // the same logical message rather than two separate ones. Kept in sync with the old
    // containsMessage() tolerance so syncHistoryFromFile()/appendMessageIfNew() agree on
    // what counts as a duplicate.
    private static final long DUPLICATE_WINDOW_MS = 5000L;

    boolean valid;
    public LiveProfileCache.LiveProfile liveProfile;
    String msgString;
    int maxLineLength;
    int scrollBarHeight = 50;
    int chatScrollPosition = 0;
    boolean scrolling = false;
    public boolean chatScrolledToBottom = true;
    public MultilineInputBox inputBox = new MultilineInputBox();
    public boolean inputFocused = true;
    public LivemessageUtil.ChatSettings chatSettings;
    final int chatBoxY = titlebarHeight + 44;
    final int chatBoxX = 5;
    private static final int CHAT_INNER_PADDING = 5;
    private static final int MESSAGE_LINE_HEIGHT = 12;
    private static final int INPUT_LINE_HEIGHT = 11;
    private static final int INPUT_VERTICAL_PADDING = 4; // 2px top + 2px bottom inside the input box
    private static final int INPUT_TOP_MARGIN = 1;        // gap between chat box and input box
    private static final int INPUT_BOTTOM_MARGIN = 5;      // gap between input box and window bottom edge
    private static final int MAX_VISIBLE_INPUT_LINES = 4;
    private static final int TRIM_INTERVAL = 20;           // rewrite the history file every N saved messages, not every single one
    private boolean inputDragging = false;
    private int savesSinceTrim = 0;
    List<ChatWindow.ChatMessage> chatHistory = new ArrayList<>();
    List<ChatWindow.ChatMessage> pendingMessages = new ArrayList<>();
    List<ChatWindow.ClickableLink> clickableLinks = new ArrayList<>();
    List<ChatWindow.ClickableChatLine> clickableChatLines = new ArrayList<>();
    private int historyVersion = 0;
    private int renderedCacheVersion = Integer.MIN_VALUE;
    private int renderedCacheWidth = -1;
    private String renderedCacheDay = "";
    private List<ChatWindow.RenderedLine> renderedLinesCache = new ArrayList<>();
    private String pendingUrl = null;
    private long pendingUrlExpireAt = 0L;
    private String copyFeedbackText = null;
    private long copyFeedbackExpireAt = 0L;
    private int copyFeedbackX = 0;
    private int copyFeedbackY = 0;
    // Total on-disk message count for this conversation, refreshed lazily (see countMessagesOnDisk).
    private long totalMessagesOnDisk = -1;
    private long totalMessagesCountedVersion = Long.MIN_VALUE;
    private long totalMessagesCountedAtMs = 0L;
    private static final long DISK_COUNT_REFRESH_MS = 2000L;
    // Set right before any sendChatCommand()
    private long suppressEchoUntil = 0L;
    LiveSkinUtil liveSkinUtil;
    GuiUtil.QuintAnimation fullSkinAnim = new GuiUtil.QuintAnimation(600, 0.0F);
    private static final Pattern URL_PATTERN = Pattern.compile("(https?://[^\\s]+|www\\.[^\\s]+)", 2);
    private static final Formatting[] MINECRAFT_COLORS = new Formatting[]{
        Formatting.BLACK,
        Formatting.DARK_BLUE,
        Formatting.DARK_GREEN,
        Formatting.DARK_AQUA,
        Formatting.DARK_RED,
        Formatting.DARK_PURPLE,
        Formatting.GOLD,
        Formatting.GRAY,
        Formatting.DARK_GRAY,
        Formatting.BLUE,
        Formatting.GREEN,
        Formatting.AQUA,
        Formatting.RED,
        Formatting.LIGHT_PURPLE,
        Formatting.YELLOW,
        Formatting.WHITE
    };

    ChatWindow(UUID uuid) {
        this(LiveProfileCache.getLiveprofileFromUUID(uuid, false));
    }

    public ChatWindow(LiveProfileCache.LiveProfile liveProfile) {
        if (liveProfile == null) {
            LiveMessage.LOG.warn("Tried to open an invalid chat window - offline mode?");
            this.valid = false;
        } else {
            this.valid = true;
            this.minw = 280;
            this.minh = 150;
            this.w = LiveMessage.INSTANCE.defaultChatWidth.get();
            this.h = LiveMessage.INSTANCE.defaultChatHeight.get();
            this.x = Math.min(this.x, Math.max(0, LivemessageGui.screenWidth - this.w));
            this.y = Math.min(this.y, Math.max(0, LivemessageGui.screenHeight - this.h));
            this.liveProfile = liveProfile;
            this.chatSettings = LivemessageUtil.getChatSettings(liveProfile.uuid);
            this.loadWindowColor();
            this.loadChatHistory();
            this.initButtons();
            this.liveSkinUtil = LiveSkinUtil.get(liveProfile.uuid);
            this.msgString = "/" + LiveMessage.INSTANCE.getPmCommand() + " " + liveProfile.username + " ";
            // Each individual queued/sent line still has to fit in one whisper command packet.
            this.maxLineLength = Math.max(1, 256 - this.msgString.length());
            this.inputBox.setMaxTotalLength(this.maxLineLength * 8); // room for several queued lines
            this.inputBox.setMaxVisibleSegments(MAX_VISIBLE_INPUT_LINES);
            this.inputFocused = true;
            this.scrollToBottom();
            this.animateInStart = System.currentTimeMillis();
        }
    }

    public void initButtons() {
        this.liveButtons.add(new LiveWindow.LiveButton(0, 14, titlebarHeight + 3, 11, 11, true, 0, "Toggle friend/enemy", () -> {
            this.toggleFriendEnemy();
            this.updateButtonStates();
        }));
        this.liveButtons.add(new LiveWindow.LiveButton(1, 14, titlebarHeight + 3 + 13, 11, 11, true, 3, "Request teleport (tpa)", this::requestTeleport));
        this.liveButtons.add(new LiveWindow.LiveButton(2, 14, titlebarHeight + 3 + 26, 11, 11, true, 2, "Custom color", () -> {
            this.toggleColor();
            this.updateButtonStates();
        }));
        this.liveButtons.add(new LiveWindow.LiveButton(3, 14, titlebarHeight + 3 + 39, 11, 11, true, 1, "Ignore player", () -> this.ignorePlayer()));
        this.updateButtonStates();
    }

    private void updateButtonStates() {
        Friends friends = Friends.get();
        EnemyManager enemyManager = EnemyManager.get();
        boolean isFriend = friends.get(this.liveProfile.username) != null;
        boolean isEnemy = enemyManager.isEnemy(this.liveProfile.username);

        for (LiveWindow.LiveButton btn : this.liveButtons) {
            if (btn.id == 0) {
                btn.iconActive = isFriend || isEnemy;
                if (isFriend) {
                    btn.iconColor = GuiUtil.getRGB(85, 255, 85);
                } else if (isEnemy) {
                    btn.iconColor = GuiUtil.getRGB(255, 85, 85);
                } else {
                    btn.iconColor = -1;
                }
            } else if (btn.id == 2) {
                btn.iconActive = this.chatSettings.customColor != 0;
            }
        }
    }

    private void ignorePlayer() {
        if (this.mc.player != null) {
            this.mc.player.networkHandler.sendChatCommand(LiveMessage.INSTANCE.getIgnoreCommand() + " " + this.liveProfile.username);
        }

        LivemessageGui.liveWindows.remove(this);
        if (!LivemessageGui.liveWindows.isEmpty()) {
            LivemessageGui.liveWindows.get(LivemessageGui.liveWindows.size() - 1).activateWindow();
        }
    }

    private void requestTeleport() {
        if (this.mc.player != null) {
            this.mc.player.networkHandler.sendChatCommand(LiveMessage.INSTANCE.getTpaCommand() + " " + this.liveProfile.username);
        }
    }

    public void toggleFriendEnemy() {
        Friends friends = Friends.get();
        EnemyManager enemies = EnemyManager.get();
        String username = this.liveProfile.username;

        if (friends.get(username) != null) {
            friends.remove(friends.get(username));
            enemies.add(username);
        } else if (enemies.isEnemy(username)) {
            enemies.remove(username);
        } else {
            friends.add(new Friend(username));
        }
    }

    public void toggleColor() {
        int currentIndex = -1;
        if (this.chatSettings.customColor == 0) {
            currentIndex = -1;
        } else {
            for (int i = 0; i < MINECRAFT_COLORS.length; i++) {
                if (MINECRAFT_COLORS[i].isColor() && MINECRAFT_COLORS[i].getColorValue() != null) {
                    int colorValue = MINECRAFT_COLORS[i].getColorValue() | 0xFF000000;
                    if (this.chatSettings.customColor == colorValue) {
                        currentIndex = i;
                        break;
                    }
                }
            }
        }

        currentIndex = (currentIndex + 1) % (MINECRAFT_COLORS.length + 1);
        if (currentIndex == MINECRAFT_COLORS.length) {
            this.chatSettings.customColor = 0;
            this.primaryColor = GuiUtil.getWindowColor(this.liveProfile.uuid);
            LiveMessage.LOG.info("Reset window color for {} to default (0x{})", this.liveProfile.username, Integer.toHexString(this.primaryColor).toUpperCase());
        } else if (MINECRAFT_COLORS[currentIndex].isColor() && MINECRAFT_COLORS[currentIndex].getColorValue() != null) {
            this.chatSettings.customColor = MINECRAFT_COLORS[currentIndex].getColorValue() | 0xFF000000;
            this.primaryColor = this.chatSettings.customColor;
            LiveMessage.LOG.info("Changed window color for {} to custom 0x{} (index {})",
                this.liveProfile.username, Integer.toHexString(this.chatSettings.customColor).toUpperCase(), currentIndex
            );
        }

        LivemessageUtil.saveChatSettings(this.liveProfile.uuid, this.chatSettings);
    }

    public void loadWindowColor() {
        if (this.chatSettings.customColor != 0) {
            this.primaryColor = this.chatSettings.customColor;
            LiveMessage.LOG.info("Loaded custom window color for {} = 0x{}", this.liveProfile.username, Integer.toHexString(this.primaryColor).toUpperCase());
        } else {
            this.primaryColor = GuiUtil.getWindowColor(this.liveProfile.uuid);
            LiveMessage.LOG.info("Loaded default window color for {} = 0x{}", this.liveProfile.username, Integer.toHexString(this.primaryColor).toUpperCase());
        }
    }

    public void reloadWindowColor() {
        if (this.chatSettings.customColor == 0) {
            this.primaryColor = GuiUtil.getWindowColor(this.liveProfile.uuid);
            LiveMessage.LOG.info("Reloaded default window color for {} = 0x{}", this.liveProfile.username, Integer.toHexString(this.primaryColor).toUpperCase());
        }
    }

    public void loadChatHistory() {
        if (LiveMessage.INSTANCE != null) {
            LivemessageUtil.trimHistory(this.liveProfile.uuid, LiveMessage.INSTANCE.maxHistoryLines.get());
        }

        List<String> allLines = new ArrayList<>();

        String line;
        try (BufferedReader reader = new BufferedReader(new FileReader(LivemessageUtil.MESSAGES_FOLDER.resolve(this.liveProfile.uuid.toString() + ".jsonl").toFile())
        )) {
            while ((line = reader.readLine()) != null) {
                allLines.add(line);
            }
        } catch (IOException var9) {
        }

        int startIndex = Math.max(0, allLines.size() - 100);

        for (int i = startIndex; i < allLines.size(); i++) {
            try {
                ChatWindow.ChatMessage parsed = GSON.fromJson(allLines.get(i), ChatWindow.ChatMessage.class);
                if (parsed != null && parsed.message != null) this.chatHistory.add(parsed);
            } catch (Exception e) {
                LiveMessage.logError("Failed to parse chat message from history file for UUID: {}", this.liveProfile.uuid, e);
            }
        }

        for (ChatWindow.ChatMessage msg : this.chatHistory) {
            if (msg.pending) {
                this.pendingMessages.add(msg);
            }
        }

        this.historyVersion++;
    }

    public void saveChatMessage(ChatWindow.ChatMessage message) {
        try (FileWriter writer = new FileWriter(LivemessageUtil.MESSAGES_FOLDER.resolve(this.liveProfile.uuid.toString() + ".jsonl").toFile(), true)) {
            writer.write(GSON.toJson(message) + "\n");
        } catch (IOException e) {
            LiveMessage.logError("Failed to save chat message to history file for UUID: {}", this.liveProfile.uuid, e);
        }

        // trimHistory rewrites the whole file, so we throttle it instead of doing it on every
        // single message; the file only ever grows by TRIM_INTERVAL lines beyond the cap between trims.
        if (LiveMessage.INSTANCE != null) {
            this.savesSinceTrim++;
            if (this.savesSinceTrim >= TRIM_INTERVAL) {
                LivemessageUtil.trimHistory(this.liveProfile.uuid, LiveMessage.INSTANCE.maxHistoryLines.get());
                this.savesSinceTrim = 0;
            }
        }
    }

    public void addMessage(String message, boolean sentByMe) {
        ChatWindow.ChatMessage chatMessage = new ChatWindow.ChatMessage(message, sentByMe, System.currentTimeMillis(), this.mc.player.getUuid());
        boolean wasAtBottom = this.isAtBottom();
        this.chatHistory.add(chatMessage);
        this.saveChatMessage(chatMessage);
        LivemessageGui.recordRecentLog(this.liveProfile.uuid, message, sentByMe);
        this.historyVersion++;
        this.clampScrollPosition();
        if (wasAtBottom || sentByMe) {
            this.scrollToBottom();
        }

        if (!sentByMe && !this.active) {
            int unreads = LivemessageGui.unreadMessages.getOrDefault(this.liveProfile.uuid, 0);
            LivemessageGui.unreadMessages.put(this.liveProfile.uuid, unreads + 1);
        }
    }

    private void queueMessage(String message) {
        ChatWindow.ChatMessage queued = new ChatWindow.ChatMessage(message, true, System.currentTimeMillis(), this.mc.player.getUuid());
        queued.pending = true;

        this.chatHistory.add(queued);
        this.saveChatMessage(queued);
        this.pendingMessages.add(queued);
        LivemessageGui.recordRecentLog(this.liveProfile.uuid, message, true);
        this.historyVersion++;
        this.clampScrollPosition();
        this.scrollToBottom();
    }

    // Sends at most one queued message per call, suppressed by the global whisper cooldown.
    // Pops through QueueUtil (the same atomic, per-uuid-locked call LiveMessage's background
    // flush uses) instead of trusting this window's own cached pendingMessages list, so the
    // two consumers can never both send the same on-disk pending line
    private void flushPendingMessages() {
        if (this.pendingMessages.isEmpty() || this.mc.player == null) {
            return;
        }
        if (WhisperRateLimiter.isOnCooldown()) {
            return;
        }

        ChatWindow.ChatMessage popped = QueueUtil.popOldestPending(this.liveProfile.uuid);
        if (popped == null) {
            // Nothing actually pending on disk any more
            this.pendingMessages.clear();
            return;
        }

        this.suppressEchoUntil = System.currentTimeMillis() + 3000L;
        WhisperRateLimiter.markSelfInitiated(this.liveProfile.username, popped.message);
        this.mc.player.networkHandler.sendChatCommand(LiveMessage.INSTANCE.getPmCommand() + " " + this.liveProfile.username + " " + popped.message);
        WhisperRateLimiter.recordSent();

        // popOldestPending() already rewrote the file; mirror that into our own cached lists
        // so the "(pending)" tag disappears from the rendered history immediately.
        this.pendingMessages.removeIf(m -> matchesPopped(m, popped));
        for (ChatWindow.ChatMessage m : this.chatHistory) {
            if (m.pending && matchesPopped(m, popped)) {
                m.pending = false;
                break;
            }
        }

        this.historyVersion++;
    }

    private static boolean matchesPopped(ChatWindow.ChatMessage m, ChatWindow.ChatMessage popped) {
        return m.timestamp == popped.timestamp
            && m.sentByMe == popped.sentByMe
            && Objects.equals(m.message, popped.message);
    }

    public void appendMessageIfNew(String message, boolean sentByMe, long timestamp) {
        if (sentByMe && System.currentTimeMillis() < this.suppressEchoUntil) {
            return;
        }
        if (this.containsMessage(message, sentByMe, timestamp)) {
            return;
        }

        boolean wasAtBottom = this.isAtBottom();
        this.chatHistory.add(new ChatWindow.ChatMessage(message, sentByMe, timestamp));
        this.historyVersion++;
        this.clampScrollPosition();
        if (wasAtBottom) {
            this.scrollToBottom();
        }
    }

    private boolean containsMessage(String message, boolean sentByMe, long timestamp) {
        for (ChatWindow.ChatMessage existing : this.chatHistory) {
            if (existing.sentByMe == sentByMe
                && existing.message.equals(message)
                && Math.abs(existing.timestamp - timestamp) < DUPLICATE_WINDOW_MS) {
                return true;
            }
        }

        return false;
    }

    public void clampScrollPosition() {
        this.chatScrollPosition = MathHelper.clamp(this.chatScrollPosition, 0, this.getMaxScrollPosition());
    }

    public void syncHistoryFromFile() {
        Map<String, List<Long>> existingByText = this.buildMessageIndex();
        this.loadMessagesFromFile(this.liveProfile.uuid, existingByText);
        UUID offlineUuid = UUID.nameUUIDFromBytes(
            ("OfflinePlayer:" + this.liveProfile.username.toLowerCase(Locale.ROOT)).getBytes()
        );
        if (!offlineUuid.equals(this.liveProfile.uuid)) {
            this.loadMessagesFromFile(offlineUuid, existingByText);
        }

        this.chatHistory.sort((a, b) -> Long.compare(a.timestamp, b.timestamp));
        this.historyVersion++;
        this.clampScrollPosition();
    }

    private Map<String, List<Long>> buildMessageIndex() {
        Map<String, List<Long>> index = new HashMap<>(this.chatHistory.size() * 2);
        for (ChatWindow.ChatMessage m : this.chatHistory) {
            index.computeIfAbsent(textKey(m.sentByMe, m.message), k -> new ArrayList<>()).add(m.timestamp);
        }
        return index;
    }

    private static String textKey(boolean sentByMe, String message) {
        return sentByMe + "|" + message;
    }

    private static boolean withinDuplicateWindow(List<Long> timestamps, long timestamp) {
        for (long existing : timestamps) {
            if (Math.abs(existing - timestamp) < DUPLICATE_WINDOW_MS) {
                return true;
            }
        }
        return false;
    }

    private void loadMessagesFromFile(UUID uuid, Map<String, List<Long>> existingByText) {
        try (BufferedReader reader = new BufferedReader(
            new FileReader(LivemessageUtil.MESSAGES_FOLDER.resolve(uuid.toString() + ".jsonl").toFile())
        )) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    ChatWindow.ChatMessage loaded = GSON.fromJson(line, ChatWindow.ChatMessage.class);
                    if (loaded != null && loaded.message != null) {
                        String key = textKey(loaded.sentByMe, loaded.message);
                        List<Long> bucket = existingByText.computeIfAbsent(key, k -> new ArrayList<>());
                        if (!withinDuplicateWindow(bucket, loaded.timestamp)) {
                            bucket.add(loaded.timestamp);
                            this.chatHistory.add(loaded);
                            if (loaded.pending) {
                                this.pendingMessages.add(loaded);
                            }
                            this.historyVersion++;
                        }
                    }
                } catch (Exception e) {
                    LiveMessage.logError("Failed to parse chat message while syncing history for UUID: {}", uuid, e);
                }
            }
        } catch (IOException var9) {
        }
    }

    public int getVisibleMessageLines() {
        return Math.max(1, this.getMessageAreaHeight() / MESSAGE_LINE_HEIGHT);
    }

    private int getInputBoxHeight() {
        int lineCount = MathHelper.clamp(this.inputBox.wrappedLines(this.fontRenderer, this.w - 18).size(), 1, MAX_VISIBLE_INPUT_LINES);
        return lineCount * INPUT_LINE_HEIGHT + INPUT_VERTICAL_PADDING;
    }

    private int getInputBoxTop() {
        return this.h - INPUT_BOTTOM_MARGIN - this.getInputBoxHeight();
    }

    private int getChatAreaBottom() {
        return this.getInputBoxTop() - INPUT_TOP_MARGIN;
    }

    private int getChatBoxHeight() {
        return Math.max(0, this.getChatAreaBottom() - this.chatBoxY);
    }

    private int getMessageAreaTop() {
        return this.chatBoxY + CHAT_INNER_PADDING;
    }

    private int getMessageAreaBottomEdge() {
        return this.chatBoxY + this.getChatBoxHeight() - CHAT_INNER_PADDING;
    }

    private int getMessageAreaHeight() {
        return Math.max(MESSAGE_LINE_HEIGHT, this.getMessageAreaBottomEdge() - this.getMessageAreaTop());
    }

    public int getMaxScrollPosition() {
        return Math.max(0, this.getRenderedLines().size() - this.getVisibleMessageLines());
    }

    public boolean isAtBottom() {
        return this.chatScrollPosition >= this.getMaxScrollPosition();
    }

    public void scrollToBottom() {
        this.chatScrollPosition = this.getMaxScrollPosition();
        this.chatScrolledToBottom = true;
    }

    private boolean autocompletePlayerName() {
        // The old buffer-position backspace loop below assumes every backspace() call removes
        // exactly one character; with selection support, backspace() clears a selection first
        // instead, so a leftover selection would desync the loop from `partial`'s length.
        this.inputBox.clearSelection();

        String text = this.inputBox.getText();
        int cursor = this.inputBox.getCursor();
        int wordStart = Math.max(text.lastIndexOf(' ', cursor - 1), text.lastIndexOf('\n', cursor - 1)) + 1;
        String partial = text.substring(wordStart, cursor);
        if (partial.isEmpty() || this.mc.getNetworkHandler() == null) return false;

        String match = null;
        for (PlayerListEntry entry : this.mc.getNetworkHandler().getPlayerList()) {
            String name = entry.getProfile().name();
            if (name.regionMatches(true, 0, partial, 0, partial.length())
                && (this.mc.player == null || !entry.getProfile().id().equals(this.mc.player.getUuid()))) {
                match = name;
                break;
            }
        }
        if (match == null) return false;

        for (int i = 0; i < partial.length(); i++) this.inputBox.backspace();
        for (char c : match.toCharArray()) this.inputBox.insertChar(c);
        this.inputBox.insertChar(' ');
        return true;
    }

    private void sendOrQueue() {
        String full = this.inputBox.getText();
        if (full.isBlank() || this.mc.player == null) {
            return;
        }

        boolean online = LivemessageUtil.checkOnlineStatus(this.liveProfile.uuid);
        boolean firstLine = true;

        for (String rawLine : full.split("\n")) {
            String trimmedLine = rawLine.trim();
            if (trimmedLine.isEmpty()) continue;

            for (String chunk : splitToFit(trimmedLine, this.maxLineLength)) {
                if (chunk.isEmpty()) continue;

                if (firstLine && online && !WhisperRateLimiter.isOnCooldown()) {
                    this.suppressEchoUntil = System.currentTimeMillis() + 3000L;
                    WhisperRateLimiter.markSelfInitiated(this.liveProfile.username, chunk);
                    this.mc.player.networkHandler.sendChatCommand(LiveMessage.INSTANCE.getPmCommand() + " " + this.liveProfile.username + " " + chunk);
                    WhisperRateLimiter.recordSent();
                    this.addMessage(chunk, true);
                } else {
                    this.queueMessage(chunk);
                }
                firstLine = false;
            }
        }

        this.inputBox.clear();
    }

    private static List<String> splitToFit(String text, int maxLen) {
        List<String> out = new ArrayList<>();
        String remaining = text;

        while (remaining.length() > maxLen) {
            int breakAt = remaining.lastIndexOf(' ', maxLen);
            if (breakAt <= 0) {
                breakAt = maxLen; // no space to break on hard cut
            }
            out.add(remaining.substring(0, breakAt).trim());
            remaining = remaining.substring(breakAt).trim();
        }

        if (!remaining.isEmpty()) {
            out.add(remaining);
        }

        return out;
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) {
        this.markAsRead();
        int inputWidth = this.w - 18;
        boolean shift = this.isShiftHeld();
        boolean ctrl = this.isCtrlHeld();
        this.inputBox.setSelecting(shift);

        if (keyCode == 257 || keyCode == 335) { // Enter / numpad Enter
            if (shift) {
                this.inputBox.newline();
            } else {
                this.sendOrQueue();
            }
        } else if (keyCode == GLFW.GLFW_KEY_A && ctrl) {
            this.inputBox.selectAll();
        } else if (keyCode == GLFW.GLFW_KEY_C && ctrl) {
            if (this.mc.keyboard != null) {
                // Selection if there is one, otherwise the whole box
                String copied = this.inputBox.hasSelection() ? this.inputBox.getSelectedText() : this.inputBox.getText();
                if (!copied.isEmpty()) this.mc.keyboard.setClipboard(copied);
            }
        } else if (keyCode == GLFW.GLFW_KEY_X && ctrl) {
            if (this.mc.keyboard != null) {
                String cut = this.inputBox.hasSelection() ? this.inputBox.getSelectedText() : this.inputBox.getText();
                if (!cut.isEmpty()) this.mc.keyboard.setClipboard(cut);
            }
            if (!this.inputBox.deleteSelection()) {
                this.inputBox.clear();
            }
        } else if (keyCode == GLFW.GLFW_KEY_V && ctrl) {
            if (this.mc.keyboard != null) {
                String clipboard = this.mc.keyboard.getClipboard();
                if (clipboard != null && !clipboard.isEmpty()) {
                    this.inputBox.insertText(clipboard);
                }
            }
        } else if (keyCode == 259) { // Backspace
            this.inputBox.backspace();
        } else if (keyCode == 261) { // Delete
            this.inputBox.delete();
        } else if (keyCode == 263) { // Left
            if (ctrl) this.inputBox.moveLeftWord();
            else this.inputBox.moveLeft();
        } else if (keyCode == 262) { // Right
            if (ctrl) this.inputBox.moveRightWord();
            else this.inputBox.moveRight();
        } else if (keyCode == 268) { // Home
            this.inputBox.moveHome();
        } else if (keyCode == 269) { // End
            this.inputBox.moveEnd();
        } else if (keyCode == 265) { // Up
            this.inputBox.moveUp(this.fontRenderer, inputWidth);
        } else if (keyCode == 264) { // Down
            this.inputBox.moveDown(this.fontRenderer, inputWidth);
        } else if (keyCode == 266) { // Page Up chat history scroll
            this.chatScrollPosition = MathHelper.clamp(this.chatScrollPosition - 10, 0, this.getMaxScrollPosition());
            this.chatScrolledToBottom = this.isAtBottom();
        } else if (keyCode == 267) { // Page Down
            this.chatScrollPosition = MathHelper.clamp(this.chatScrollPosition + 10, 0, this.getMaxScrollPosition());
            this.chatScrolledToBottom = this.isAtBottom();
        } else if (keyCode == 258) { // Tab
            if (!ctrl && this.autocompletePlayerName()) {
                this.inputBox.ensureCaretVisible(this.fontRenderer, inputWidth);
                return; // consumed by autocomplete
            }
            // no match (or ctrl held), fall through to super.keyTyped for window-cycling
        } else if (!ctrl && typedChar != 0 && typedChar != '\n' && typedChar != '\r' && typedChar >= ' ') {
            this.inputBox.insertChar(typedChar);
        }

        this.inputBox.ensureCaretVisible(this.fontRenderer, inputWidth);
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void mouseWheel(int mWheelState) {
        this.markAsRead();
        boolean shift = GLFW.glfwGetKey(this.mc.getWindow().getHandle(), 340) == 1;
        int scrollAmount = shift ? 10 : 1;

        // Wheel over the input box scrolls the input's wrapped text (so the top
        // lines are reachable once they wrap past the visible box), not the chat.
        int inputBoxTop = this.getInputBoxTop();
        int inputBoxHeight = this.getInputBoxHeight();
        if (this.mouseInRect(5, inputBoxTop, this.w - 10, inputBoxHeight, this.lastMouseX, this.lastMouseY)) {
            this.inputBox.scrollBy(mWheelState < 0 ? 1 : -1, this.fontRenderer, this.w - 18);
            return;
        }

        if (mWheelState < 0) {
            this.chatScrollPosition = Math.min(this.chatScrollPosition + scrollAmount, this.getMaxScrollPosition());
        } else {
            this.chatScrollPosition = Math.max(this.chatScrollPosition - scrollAmount, 0);
        }

        this.chatScrolledToBottom = this.isAtBottom();

        super.mouseWheel(mWheelState);
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int state) {
        this.scrolling = false;
        this.inputDragging = false;
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    public void handleMouseDrag(double mouseX, double mouseY) {
        // The input box sits right where LiveWindow's resize grip lives (near the bottom-right
        // corner), so mouseClicked can set both inputDragging and this.resizing on the same
        // click.
        if (this.inputDragging && !this.resizing && !this.dragging) {
            this.placeCursorFromMouse((int) mouseX, (int) mouseY, true);
            return;
        }
        if (this.scrolling && this.getMaxScrollPosition() > 0) {
            int totalPixels = this.getChatBoxHeight() - this.scrollBarHeight;
            int maxScroll = this.getMaxScrollPosition();
            int relativeMouseY = (int) mouseY - (this.dragY + this.chatBoxY + this.y);
            this.chatScrollPosition = (int) MathHelper.clamp((float) (relativeMouseY * maxScroll) / totalPixels, 0.0F, maxScroll);
            this.chatScrolledToBottom = this.isAtBottom();
        } else {
            super.handleMouseDrag(mouseX, mouseY);
        }
    }

    @Override
    public void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (this.inputDragging && !this.resizing && !this.dragging) {
            this.placeCursorFromMouse(mouseX, mouseY, true);
            return;
        }
        if (this.scrolling && this.getMaxScrollPosition() > 0) {
            int totalPixels = this.getChatBoxHeight() - this.scrollBarHeight;
            int maxScroll = this.getMaxScrollPosition();
            int relativeMouseY = mouseY - (this.dragY + this.chatBoxY + this.y);
            this.chatScrollPosition = (int) MathHelper.clamp((float) (relativeMouseY * maxScroll) / totalPixels, 0.0F, maxScroll);
            this.chatScrolledToBottom = this.isAtBottom();
        }

        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        if (this.mouseInRect(0, 0, this.w, this.h, mouseX, mouseY)) {
            this.markAsRead();
        }

        if (mouseButton == 0) {
            for (ChatWindow.ClickableLink link : this.clickableLinks) {
                if (link.contains(mouseX, mouseY)) {
                    this.handleUrlClick(link.url);
                    return;
                }
            }

            for (ChatWindow.ClickableChatLine line : this.clickableChatLines) {
                if (line.contains(mouseX, mouseY)) {
                    if (line.messageIndex >= 0 && line.messageIndex < this.chatHistory.size()) {
                        this.copyMessageToClipboard(this.chatHistory.get(line.messageIndex).message);
                        this.copyFeedbackX = mouseX - this.x + 4;
                        this.copyFeedbackY = mouseY - this.y - 12;
                    }
                    return;
                }
            }
        } else if (mouseButton == 1) {
            for (ChatWindow.ClickableChatLine line : this.clickableChatLines) {
                if (line.contains(mouseX, mouseY) && line.messageIndex >= 0 && line.messageIndex < this.chatHistory.size()) {
                    ChatWindow.ChatMessage msg = this.chatHistory.get(line.messageIndex);
                    if (msg.pending) {
                        QueueUtil.deleteQueued(this.liveProfile.uuid, msg.message);
                        this.copyFeedbackText = "Removed from queue";
                        this.copyFeedbackExpireAt = System.currentTimeMillis() + 1200L;
                        this.copyFeedbackX = mouseX - this.x + 4;
                        this.copyFeedbackY = mouseY - this.y - 12;
                    }
                    return;
                }
            }
        }

        boolean buttonClicked = false;

        for (LiveWindow.LiveButton btn : this.liveButtons) {
            if (btn.isMouseOver()) {
                LiveMessage.LOG
                    .info("Button {} clicked at ({}, {}) - btn pos: ({}, {}) window pos: ({}, {})",
                        btn.id, mouseX, mouseY, btn.gx(), btn.by, this.x, this.y
                    );

                try {
                    btn.action.run();
                    buttonClicked = true;
                } catch (Exception e) {
                    LiveMessage.logError("Error executing button action for button {}", btn.id, e);
                }
                break;
            }
        }

        if (!buttonClicked) {
            int inputBoxTop = this.getInputBoxTop();
            int inputBoxHeight = this.getInputBoxHeight();
            if (this.mouseInRect(5, inputBoxTop, this.w - 10, inputBoxHeight, mouseX, mouseY)) {
                this.inputFocused = true;
                this.placeCursorFromMouse(mouseX, mouseY, this.isShiftHeld());
                this.inputDragging = true;
            } else {
                this.inputFocused = false;
                this.inputBox.clearSelection();
            }

            if (this.getMaxScrollPosition() > 0
                && this.mouseInRect(5 + this.w - 10 - 10, this.chatBoxY, 10, this.getChatBoxHeight(), mouseX, mouseY)) {
                this.scrolling = true;
                int maxScroll = this.getMaxScrollPosition();
                if (maxScroll > 0) {
                    int availableScrollArea = this.getChatBoxHeight() - this.scrollBarHeight;
                    int scrollY = this.chatBoxY + availableScrollArea * this.chatScrollPosition / maxScroll;
                    this.dragY = mouseY - (this.y + scrollY);
                }
            }

            super.mouseClicked(mouseX, mouseY, mouseButton);
        }
    }

    private void handleUrlClick(String url) {
        long now = System.currentTimeMillis();
        if (url.equals(this.pendingUrl) && now < this.pendingUrlExpireAt) {
            this.pendingUrl = null;
            this.openUrl(url);
        } else {
            this.pendingUrl = url;
            this.pendingUrlExpireAt = now + 4000L;
        }
    }

    // Click-to-copy: copies the full original message
    private void copyMessageToClipboard(String text) {
        String safe = LivemessageUtil.stripChatDecorations(text);
        if (this.mc.keyboard != null) {
            this.mc.keyboard.setClipboard(safe);
        }
        this.copyFeedbackText = "Copied!";
        this.copyFeedbackExpireAt = System.currentTimeMillis() + 1200L;
    }

    private boolean isShiftHeld() {
        return GLFW.glfwGetKey(this.mc.getWindow().getHandle(), GLFW.GLFW_KEY_LEFT_SHIFT) == 1
            || GLFW.glfwGetKey(this.mc.getWindow().getHandle(), GLFW.GLFW_KEY_RIGHT_SHIFT) == 1;
    }

    private void placeCursorFromMouse(int mouseX, int mouseY, boolean selecting) {
        int inputWidth = this.w - 18;
        int lineCount = Math.max(1, this.inputBox.wrapSegments(this.fontRenderer, inputWidth).size());
        this.inputBox.clampScroll(this.fontRenderer, inputWidth);
        int start = this.inputBox.getScrollIndex();
        int relY = mouseY - this.y - (this.getInputBoxTop() + 2);
        int lineIdx = MathHelper.clamp(start + Math.floorDiv(relY, INPUT_LINE_HEIGHT), 0, lineCount - 1);
        this.inputBox.setCursorAt(this.fontRenderer, inputWidth, lineIdx, mouseX - this.x - 8, selecting);
        this.inputBox.ensureCaretVisible(this.fontRenderer, inputWidth);
    }

    private boolean isCtrlHeld() {
        return GLFW.glfwGetKey(this.mc.getWindow().getHandle(), GLFW.GLFW_KEY_LEFT_CONTROL) == 1
            || GLFW.glfwGetKey(this.mc.getWindow().getHandle(), GLFW.GLFW_KEY_RIGHT_CONTROL) == 1;
    }

    private void openUrl(String url) {
        try {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }

            Util.getOperatingSystem().open(url);
            LiveMessage.LOG.info("Opening URL: {}", url);
        } catch (Exception e) {
            LiveMessage.logError("Failed to open URL: {}", url, e);
        }
    }

    @Override
    public void activateWindow() {
        this.markAsRead();
        this.syncHistoryFromFile();
        this.scrollToBottom();
        super.activateWindow();
    }

    public void markAsRead() {
        LivemessageGui.unreadMessages.put(this.liveProfile.uuid, 0);
    }

    public static void refreshIfOpen(UUID uuid) {
        for (LiveWindow window : LivemessageGui.liveWindows) {
            if (window instanceof ChatWindow chatWindow && chatWindow.liveProfile.uuid.equals(uuid)) {
                chatWindow.chatHistory.clear();
                chatWindow.pendingMessages.clear();
                chatWindow.historyVersion++;
                chatWindow.loadChatHistory();
            }
        }
    }

    private List<ChatWindow.RenderedLine> buildRenderedLines() {
        List<ChatWindow.RenderedLine> lines = new ArrayList<>();
        if (this.chatHistory.isEmpty()) {
            return lines;
        }

        String lastDay = DATE_FORMAT.format(new Date(System.currentTimeMillis()));

        for (int i = 0; i < this.chatHistory.size(); i++) {
            ChatWindow.ChatMessage chatMessage = this.chatHistory.get(i);
            boolean isTrimmed = false;
            String message = LivemessageUtil.stripChatDecorations(chatMessage.message);
            Date timestamp = new Date(chatMessage.timestamp);
            int baseColor = chatMessage.sentByMe ? GuiUtil.getSingleRGB(255) : GuiUtil.getSingleRGB(252);

            if (chatMessage.pending) {
                message = message + " (pending)";
                baseColor = GuiUtil.getSingleRGB(140);
            }

            if (chatMessage.notAccepted) {
                message = message + " (not accepted)";
                baseColor = GuiUtil.getRGB(255, 100, 100);
            }

            while (true) {
                String thisDay = DATE_FORMAT.format(timestamp);
                if (!thisDay.equals(lastDay)) {
                    lastDay = thisDay;
                    lines.add(new ChatWindow.RenderedLine(thisDay, this.chatBoxX + 4, GuiUtil.getSingleRGB(64), false, -1));
                } else {
                    if (!isTrimmed) {
                        message = TIME_FORMAT.format(timestamp) + message;
                    }

                    int trimIndent = isTrimmed ? this.getTextWidth("<00:00> ") : 0;
                    int maxWidth = this.w - (this.chatBoxX * 2 + 8 + trimIndent + 10 - 5);
                    String trimmed = GuiUtil.wrapWordBoundary(this.fontRenderer, message, maxWidth);
                    lines.add(new ChatWindow.RenderedLine(trimmed.stripTrailing(), this.chatBoxX + 4 + trimIndent, baseColor, true, i));
                    if (trimmed.length() >= message.length()) {
                        break;
                    }

                    message = message.substring(trimmed.length());
                    isTrimmed = true;
                }
            }
        }

        return lines;
    }

    // chatScrollPosition indexes into the full rendered-line list for the whole history, so the
    // list is cached and only rebuilt when the history, window width, or current day changes.
    private List<ChatWindow.RenderedLine> getRenderedLines() {
        String day = DATE_FORMAT.format(new Date(System.currentTimeMillis()));
        if (this.renderedCacheVersion != this.historyVersion
            || this.renderedCacheWidth != this.w
            || !this.renderedCacheDay.equals(day)) {
            this.renderedLinesCache = this.buildRenderedLines();
            this.renderedCacheVersion = this.historyVersion;
            this.renderedCacheWidth = this.w;
            this.renderedCacheDay = day;
        }

        return this.renderedLinesCache;
    }

    private void drawChatHistory(DrawContext context, int chatColorMe, int chatColorOther) {
        this.clickableLinks.clear();
        this.clickableChatLines.clear();
        this.clampScrollPosition();

        List<ChatWindow.RenderedLine> lines = this.getRenderedLines();
        int totalLines = lines.size();

        if (totalLines == 0) {
            int placeholderY = this.getMessageAreaBottomEdge() - MESSAGE_LINE_HEIGHT;
            this.drawText(context, "You're chatting with " + this.liveProfile.username, this.chatBoxX + 4, placeholderY, GuiUtil.getSingleRGB(96), false);
            this.chatScrolledToBottom = true;
            return;
        }

        int messageAreaBottomEdge = this.getMessageAreaBottomEdge();
        int capacity = this.getVisibleMessageLines();
        int maxScroll = Math.max(0, totalLines - capacity);

        // Always the `capacity` lines starting at chatScrollPosition, anchored to the bottom edge
        // so the newest visible line sits flush against the input box and no gap appears while scrolling.
        int startLine = Math.min(this.chatScrollPosition, maxScroll);
        int endLine = Math.min(totalLines, startLine + capacity);
        int drawCount = endLine - startLine;
        int y = messageAreaBottomEdge - drawCount * MESSAGE_LINE_HEIGHT;
        this.chatScrolledToBottom = startLine >= maxScroll;

        for (int i = startLine; i < endLine; i++) {
            ChatWindow.RenderedLine line = lines.get(i);
            int color = line.color;
            if (line.messageIndex >= 0) {
                ChatWindow.ChatMessage m = this.chatHistory.get(line.messageIndex);
                color = m.sentByMe ? chatColorMe : chatColorOther;
                if (m.pending) {
                    color = GuiUtil.getSingleRGB(140);
                } else if (m.notAccepted) {
                    color = GuiUtil.getRGB(255, 100, 100);
                }
            }

            if (line.urls) {
                this.drawTextWithUrls(context, line.text, line.x, y, color);
            } else {
                this.drawText(context, line.text, line.x, y, color, false);
            }

            if (line.messageIndex >= 0) {
                int textWidth = this.getTextWidth(line.text);
                this.clickableChatLines.add(new ClickableChatLine(this.x + line.x, this.y + y, textWidth, this.getTextHeight(), line.messageIndex));
            }

            y += MESSAGE_LINE_HEIGHT;
        }
    }

    private void drawTextWithUrls(DrawContext context, String text, int x, int y, int baseColor) {
        Matcher matcher = URL_PATTERN.matcher(text);
        int lastEnd = 0;
        int currentX = x;

        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                String beforeUrl = text.substring(lastEnd, matcher.start());
                this.drawText(context, beforeUrl, currentX, y, baseColor, false);
                currentX += this.getTextWidth(beforeUrl);
            }

            String url = matcher.group();
            int urlWidth = this.getTextWidth(url);
            boolean hovering = this.lastMouseX >= this.x + currentX
                && this.lastMouseX <= this.x + currentX + urlWidth
                && this.lastMouseY >= this.y + y
                && this.lastMouseY <= this.y + y + this.getTextHeight();
            int urlColor = hovering ? GuiUtil.getRGB(100, 200, 255) : GuiUtil.getRGB(85, 170, 255);
            this.drawText(context, url, currentX, y, urlColor, true);
            GuiUtil.drawRect(context, currentX, y + this.getTextHeight() - 1, urlWidth, 1, urlColor);
            this.clickableLinks.add(new ChatWindow.ClickableLink(url, this.x + currentX, this.y + y, urlWidth, this.getTextHeight()));
            currentX += urlWidth;
            lastEnd = matcher.end();
        }

        if (lastEnd < text.length()) {
            String afterUrl = text.substring(lastEnd);
            this.drawText(context, afterUrl, currentX, y, baseColor, false);
        }

        if (lastEnd == 0) {
            this.drawText(context, text, x, y, baseColor, false);
        }
    }

    public boolean shouldDrawBlur() {
        boolean removeHat = this.lastMouseX > this.x + 5
            && this.lastMouseX < this.x + 37
            && this.lastMouseY > this.y + titlebarHeight + 5
            && this.lastMouseY < this.y + titlebarHeight + 37;
        float progress = this.fullSkinAnim.animate(removeHat && this.clicked && !this.dragging && !this.resizing && !this.scrolling ? 1.0F : 0.0F);
        return progress > 0.0F;
    }

    public int getBlurAlpha() {
        boolean removeHat = this.lastMouseX > this.x + 5
            && this.lastMouseX < this.x + 37
            && this.lastMouseY > this.y + titlebarHeight + 5
            && this.lastMouseY < this.y + titlebarHeight + 37;
        float progress = this.fullSkinAnim.animate(removeHat && this.clicked && !this.dragging && !this.resizing && !this.scrolling ? 1.0F : 0.0F);
        return (int) (progress * 128.0F);
    }

    private void drawProfilePic(DrawContext context, int x, int y) {
        boolean removeHat = this.lastMouseX > this.x + x
            && this.lastMouseX < this.x + x + 32
            && this.lastMouseY > this.y + y
            && this.lastMouseY < this.y + y + 32;
        float progress = this.fullSkinAnim.animate(removeHat && this.clicked && !this.dragging && !this.resizing && !this.scrolling ? 1.0F : 0.0F);
        int displaySize = Math.round(32.0F + progress * 224.0F);
        int displayX = Math.round(x - progress * 32.0F);
        int displayY = Math.round(y - progress * 32.0F);
        PlayerListEntry entry = this.mc.getNetworkHandler().getPlayerListEntry(this.liveProfile.uuid);

        if (entry != null) {
            this.liveSkinUtil.captureFromTabList(entry);
            PlayerSkinDrawer.draw(context, entry.getSkinTextures(), displayX, displayY, displaySize, GuiUtil.fade(-1));
        } else if (this.liveSkinUtil.hasCachedSkin()) {
            PlayerSkinDrawer.draw(context, this.liveSkinUtil.getCachedTextures(), displayX, displayY, displaySize, GuiUtil.fade(-1));
        }
    }

    /**
     * Total number of messages persisted for this conversation (one jsonl line per
     * message), read straight from the history files - not just the ~100 lines loaded
     * into memory. Recounts at most every DISK_COUNT_REFRESH_MS or whenever the
     * in-memory history version changes, and also picks up the offline-UUID file
     * (mirrors syncHistoryFromFile).
     */
    private long countMessagesOnDisk() {
        long now = System.currentTimeMillis();
        if (this.totalMessagesOnDisk >= 0
            && this.totalMessagesCountedVersion == this.historyVersion
            && now - this.totalMessagesCountedAtMs < DISK_COUNT_REFRESH_MS) {
            return this.totalMessagesOnDisk;
        }

        long count = countFileLines(LivemessageUtil.MESSAGES_FOLDER.resolve(this.liveProfile.uuid.toString() + ".jsonl").toFile());
        UUID offlineUuid = UUID.nameUUIDFromBytes(
            ("OfflinePlayer:" + this.liveProfile.username.toLowerCase(Locale.ROOT)).getBytes()
        );
        if (!offlineUuid.equals(this.liveProfile.uuid)) {
            count += countFileLines(LivemessageUtil.MESSAGES_FOLDER.resolve(offlineUuid.toString() + ".jsonl").toFile());
        }

        this.totalMessagesOnDisk = count;
        this.totalMessagesCountedVersion = this.historyVersion;
        this.totalMessagesCountedAtMs = now;
        return count;
    }

    private static long countFileLines(File file) {
        if (!file.isFile()) return 0;
        long lines = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            while (reader.readLine() != null) {
                lines++;
            }
        } catch (IOException e) {
            return 0;
        }
        return lines;
    }

    @Override
    public void drawWindow(DrawContext context, int bgColor, int fgColor) {
        boolean online = LivemessageUtil.checkOnlineStatus(this.liveProfile.uuid);
        if (online) {
            this.flushPendingMessages();
        }
        this.title = "[DM] " + this.liveProfile.username;
        int unreads = LivemessageGui.unreadMessages.getOrDefault(this.liveProfile.uuid, 0);
        if (unreads > 0) {
            this.title = this.title + " \u00a7l(" + unreads + ")";
        }

        int chatBoxHeight = this.getChatBoxHeight();
        this.scrollBarHeight = this.getMaxScrollPosition() > 0
            ? (int) MathHelper.clamp(
            Math.floor((double) chatBoxHeight * this.getVisibleMessageLines() / this.getRenderedLines().size()), 10.0, chatBoxHeight / 2.0
        ) : 0;

        // input box stays fully opaque
        int inputBoxTop = this.getInputBoxTop();
        int inputBoxHeight = this.getInputBoxHeight();
        GuiUtil.drawRect(context, 0, titlebarHeight, this.w, this.chatBoxY - 1 - titlebarHeight, bgColor);
        GuiUtil.drawRect(context, 0, this.chatBoxY - 1, 4, chatBoxHeight + 2, bgColor);
        GuiUtil.drawRect(context, this.w - 4, this.chatBoxY - 1, 4, chatBoxHeight + 2, bgColor);
        GuiUtil.drawRect(context, 0, this.chatBoxY + chatBoxHeight + 1, this.w,
            Math.max(0, inputBoxTop - 1 - (this.chatBoxY + chatBoxHeight + 1)), bgColor);
        GuiUtil.drawRect(context, 0, inputBoxTop - 1, 4, inputBoxHeight + 2, bgColor);
        GuiUtil.drawRect(context, this.w - 4, inputBoxTop - 1, 4, inputBoxHeight + 2, bgColor);
        GuiUtil.drawRect(context, 0, inputBoxTop - 1 + inputBoxHeight + 2, this.w,
            Math.max(0, this.h - (inputBoxTop - 1 + inputBoxHeight + 2)), bgColor);

        super.drawWindow(context, bgColor, fgColor);
        GuiUtil.drawRect(context, 3, titlebarHeight + 3, 36, 36, online ? GuiUtil.getRGB(60, 148, 100) : GuiUtil.getSingleRGB(128));
        if (this.lastMouseX > this.x + 40
            && this.lastMouseX < this.x + 40 + this.getTextWidth(this.liveProfile.username) + 4
            && this.lastMouseY > this.y + titlebarHeight + 3
            && this.lastMouseY < this.y + titlebarHeight + 4 + 12) {
            GuiUtil.drawRect(context, 40, titlebarHeight + 3, this.getTextWidth(this.liveProfile.username) + 4, 12, GuiUtil.getSingleRGB(64));
        }

        String displayUsername = this.liveProfile.username;
        int usernameColor = GuiUtil.getSingleRGB(255);
        boolean isFriend = Friends.get().get(this.liveProfile.username) != null;
        EnemyManager enemyManager = EnemyManager.get();
        boolean isEnemy = enemyManager.isEnemy(this.liveProfile.username);
        if (isFriend) {
            displayUsername = displayUsername + " (friend)";
            usernameColor = GuiUtil.getRGB(85, 255, 85);
        } else if (isEnemy) {
            displayUsername = displayUsername + " (enemy)";
            usernameColor = GuiUtil.getRGB(255, 85, 85);
        }

        this.drawText(context, displayUsername, 42, titlebarHeight + 5, usernameColor, false);
        String totalText = " (" + this.countMessagesOnDisk() + " msg total)";
        this.drawText(context, totalText, 42 + this.getTextWidth(displayUsername), titlebarHeight + 5, GuiUtil.getSingleRGB(128), false);
        this.drawText(context, this.liveProfile.uuid.toString(), 42, titlebarHeight + 5 + 11, GuiUtil.getSingleRGB(128), false);
        String onlineStatusText = online ? "online" : "offline";
        this.drawText(context, onlineStatusText, 42, titlebarHeight + 5 + 21, GuiUtil.getSingleRGB(128), false);
        if (WhisperRateLimiter.remainingCooldownMs() > 0 && !this.pendingMessages.isEmpty()) {
            long cooldownRemaining = WhisperRateLimiter.remainingCooldownMs();
            String cooldownText = " | sending in " + String.format("%.1fs", cooldownRemaining / 1000.0)
                + " (" + this.pendingMessages.size() + " queued)";
            this.drawText(context, cooldownText, 42 + this.getTextWidth(onlineStatusText), titlebarHeight + 5 + 21, GuiUtil.getSingleRGB(160), false);
        }

        int chatbg = 36;
        int textbg = 24;
        int innerAlpha = LiveMessage.INSTANCE != null ? LiveMessage.INSTANCE.innerBackgroundAlpha.get() : 255;
        GuiUtil.drawRect(context, 4, this.chatBoxY - 1, this.w - 10 + 2, chatBoxHeight + 2, GuiUtil.withAlpha(GuiUtil.getSingleRGB(64), innerAlpha));
        GuiUtil.drawRect(context, 5, this.chatBoxY, this.w - 10, chatBoxHeight, GuiUtil.withAlpha(GuiUtil.getSingleRGB(chatbg), innerAlpha));
        // The input box is never affected by inner-background-alpha
        // fully opaque so typed text always has a solid backing to read against.
        int inputBorderColor = online ? GuiUtil.getSingleRGB(64) : GuiUtil.getRGB(200, 50, 50);
        int inputBgColor = online ? GuiUtil.getSingleRGB(textbg) : GuiUtil.getRGB(40, 20, 20);
        GuiUtil.drawRect(context, 4, inputBoxTop - 1, this.w - 10 + 2, inputBoxHeight + 2, inputBorderColor);
        GuiUtil.drawRect(context, 5, inputBoxTop, this.w - 10, inputBoxHeight, inputBgColor);
        if (!online) {
            String warningIcon = "\u00a7l!";
            int iconX = 5 + this.w - 10 - this.getTextWidth(warningIcon) - 3;
            int iconY = inputBoxTop + 2;
            this.drawText(context, warningIcon, iconX + 1, iconY, GuiUtil.getRGB(100, 20, 20), false);
            this.drawText(context, warningIcon, iconX, iconY, GuiUtil.getRGB(255, 85, 85), false);
        }

        if (this.getMaxScrollPosition() > 0) {
            int maxScroll = this.getMaxScrollPosition();
            int availableScrollArea = chatBoxHeight - this.scrollBarHeight;
            int scrollY = this.chatBoxY + (maxScroll > 0 ? availableScrollArea * this.chatScrollPosition / maxScroll : 0);
            GuiUtil.drawRect(
                context,
                5 + this.w - 10 - 10,
                scrollY,
                10,
                this.scrollBarHeight,
                this.scrolling
                    ? GuiUtil.getSingleRGB(128)
                    : (
                    this.mouseInRect(5 + this.w - 10 - 10, this.chatBoxY, 10, chatBoxHeight, this.lastMouseX, this.lastMouseY)
                    ? GuiUtil.getSingleRGB(96)
                    : GuiUtil.getSingleRGB(64)
                )
            );
        }

        int otherPlayerColor;
        if (isFriend) {
            otherPlayerColor = GuiUtil.getRGB(85, 255, 85);
        } else if (isEnemy) {
            otherPlayerColor = GuiUtil.getRGB(255, 85, 85);
        } else {
            // fgColor carries the window's background opacity (windowBackgroundAlpha);
            // chat text must stay fully readable, so force its alpha to max - the same
            // opacity friends/enemies already get from the opaque getRGB() colors.
            otherPlayerColor = GuiUtil.withAlpha(fgColor, 255);
        }

        this.drawChatHistory(context, GuiUtil.getSingleRGB(255), otherPlayerColor);
        this.drawProfilePic(context, 5, titlebarHeight + 5);

        if (this.pendingUrl != null) {
            if (System.currentTimeMillis() < this.pendingUrlExpireAt) {
                GuiUtil.drawTooltip(context, "Click link again to open: " + this.pendingUrl, 8, inputBoxTop - 16);
            } else {
                this.pendingUrl = null;
            }
        }

        if (this.copyFeedbackText != null) {
            if (System.currentTimeMillis() < this.copyFeedbackExpireAt) {
                GuiUtil.drawTooltip(context, this.copyFeedbackText, this.copyFeedbackX, this.copyFeedbackY);
            } else {
                this.copyFeedbackText = null;
            }
        }

        // Drawn last so buttons render on top of the chat box background instead of being overlapped by it.
        this.liveButtons.forEach(btn -> btn.draw(context));
        this.liveButtons.forEach(btn -> btn.drawTooltips(context));
    }

    @Override
    public void drawTextFields(DrawContext context) {
        context.getMatrices().translate(this.x, this.y);

        int inputWidth = this.w - 18;
        List<MultilineInputBox.Segment> segs = this.inputBox.wrapSegments(this.fontRenderer, inputWidth);
        int boxTop = this.getInputBoxTop();
        int boxLeft = 8;

        // Renders MAX_VISIBLE_INPUT_LINES wrapped lines starting at the box's
        // scroll position (clamped). The caret-following view can be scrolled up
        // with the mouse wheel so wrapped text past the visible box stays reachable.
        this.inputBox.clampScroll(this.fontRenderer, inputWidth);
        int start = this.inputBox.getScrollIndex();
        int textColor = this.active ? -1 : -8355712;
        boolean hasSelection = this.inputBox.hasSelection();
        int selStart = this.inputBox.getSelectionStart();
        int selEnd = this.inputBox.getSelectionEnd();

        int lineY = boxTop + 2;
        int endLine = Math.min(segs.size(), start + MAX_VISIBLE_INPUT_LINES);
        for (int i = start; i < endLine; i++) {
            MultilineInputBox.Segment seg = segs.get(i);
            int segStart = seg.startOffset;
            int segEnd = segStart + seg.text.length();

            if (hasSelection && selEnd > segStart && selStart <= segEnd) {
                int from = Math.max(selStart, segStart) - segStart;
                int to = Math.min(selEnd, segEnd) - segStart;
                int hx = boxLeft + this.getTextWidth(seg.text.substring(0, from));
                int hw = this.getTextWidth(seg.text.substring(from, to));
                if (selEnd > segEnd) hw += 3; // stub so a selected newline is visible
                if (hw > 0) {
                    GuiUtil.drawRect(context, hx, lineY - 1, hw, INPUT_LINE_HEIGHT, GuiUtil.getRGB(60, 90, 160));
                }
            }

            this.drawText(context, seg.text, boxLeft, lineY, textColor, false);
            lineY += INPUT_LINE_HEIGHT;
        }

        if (this.active && this.inputFocused && this.inputBox.cursorVisible()) {
            int caretLineIndex = this.inputBox.cursorLineIndex(this.fontRenderer, inputWidth);
            if (caretLineIndex >= start && caretLineIndex < endLine) {
                int caretCol = this.inputBox.cursorColumnInLine(this.fontRenderer, inputWidth);
                String lineText = caretLineIndex < segs.size() ? segs.get(caretLineIndex).text : "";
                int clampedCol = Math.min(caretCol, lineText.length());
                int caretX = boxLeft + this.getTextWidth(lineText.substring(0, clampedCol));
                int caretY = boxTop + 2 + (caretLineIndex - start) * INPUT_LINE_HEIGHT;
                context.fill(caretX, caretY, caretX + 1, caretY + 9, GuiUtil.fade(-1));
            }
        }

        context.getMatrices().translate(-this.x, -this.y);
    }

    private static class RenderedLine {
        private final String text;
        private final int x;
        private final int color;
        private final boolean urls;
        private final int messageIndex; // index into chatHistory this line came from; -1 for date separators

        private RenderedLine(String text, int x, int color, boolean urls, int messageIndex) {
            this.text = text;
            this.x = x;
            this.color = color;
            this.urls = urls;
            this.messageIndex = messageIndex;
        }
    }

    public static class ClickableChatLine {
        public int x;
        public int y;
        public int width;
        public int height;
        public int messageIndex;

        ClickableChatLine(int x, int y, int width, int height, int messageIndex) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.messageIndex = messageIndex;
        }

        public boolean contains(int mouseX, int mouseY) {
            return mouseX >= this.x && mouseX <= this.x + this.width && mouseY >= this.y && mouseY <= this.y + this.height;
        }
    }

    public static class ChatMessage {
        public String message;
        public boolean sentByMe;
        public long timestamp;
        public UUID myUUID;
        public boolean pending = false;
        public boolean notAccepted = false;

        ChatMessage(String message, boolean sentByMe, long timestamp) {
            this.message = message;
            this.sentByMe = sentByMe;
            this.timestamp = timestamp;
        }

        ChatMessage(String message, boolean sentByMe, long timestamp, UUID myUUID) {
            this(message, sentByMe, timestamp);
            this.myUUID = myUUID;
        }

        public static ChatMessage create(String message, boolean sentByMe, long timestamp, UUID myUUID, boolean pending) {
            ChatMessage m = new ChatMessage(message, sentByMe, timestamp, myUUID);
            m.pending = pending;
            return m;
        }
    }

    public static class ClickableLink {
        public String url;
        public int x;
        public int y;
        public int width;
        public int height;

        ClickableLink(String url, int x, int y, int width, int height) {
            this.url = url;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        public boolean contains(int mouseX, int mouseY) {
            return mouseX >= this.x && mouseX <= this.x + this.width && mouseY >= this.y && mouseY <= this.y + this.height;
        }
    }
}
