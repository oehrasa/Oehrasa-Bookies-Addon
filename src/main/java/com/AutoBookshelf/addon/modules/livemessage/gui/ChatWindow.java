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
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatWindow extends LiveWindow {
    boolean valid;
    public LiveProfileCache.LiveProfile liveProfile;
    String msgString;
    int scrollBarHeight = 50;
    int chatScrollPosition = 0;
    boolean scrolling = false;
    public boolean chatScrolledToBottom = true;
    public EditBox inputField;
    public LivemessageUtil.ChatSettings chatSettings;
    final int chatBoxY = titlebarHeight + 44;
    final int chatBoxX = 5;
    private static final int CHAT_INNER_PADDING = 5;
    private static final int CHAT_INPUT_RESERVE = 23;
    private static final int MESSAGE_LINE_HEIGHT = 12;
    List<ChatWindow.ChatMessage> chatHistory = new ArrayList<>();
    List<ChatWindow.ChatMessage> pendingMessages = new ArrayList<>();
    List<ChatWindow.ClickableLink> clickableLinks = new ArrayList<>();
    List<ChatWindow.ClickableChatLine> clickableChatLines = new ArrayList<>();
    private String pendingUrl = null;
    private long pendingUrlExpireAt = 0L;
    private String copyFeedbackText = null;
    private long copyFeedbackExpireAt = 0L;
    private int copyFeedbackX = 0;
    private int copyFeedbackY = 0;
    // Set right before any sendChatCommand()
    private long suppressEchoUntil = 0L;
    LiveSkinUtil liveSkinUtil;
    GuiUtil.QuintAnimation fullSkinAnim = new GuiUtil.QuintAnimation(600, 0.0F);
    private static final Pattern URL_PATTERN = Pattern.compile("(https?://[^\\s]+|www\\.[^\\s]+)", 2);
    private static final ChatFormatting[] MINECRAFT_COLORS = new ChatFormatting[]{
        ChatFormatting.BLACK,
        ChatFormatting.DARK_BLUE,
        ChatFormatting.DARK_GREEN,
        ChatFormatting.DARK_AQUA,
        ChatFormatting.DARK_RED,
        ChatFormatting.DARK_PURPLE,
        ChatFormatting.GOLD,
        ChatFormatting.GRAY,
        ChatFormatting.DARK_GRAY,
        ChatFormatting.BLUE,
        ChatFormatting.GREEN,
        ChatFormatting.AQUA,
        ChatFormatting.RED,
        ChatFormatting.LIGHT_PURPLE,
        ChatFormatting.YELLOW,
        ChatFormatting.WHITE
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
            this.inputField = new EditBox(this.mc.font, 9, this.h - 16, this.w - 18, 12, Component.literal(""));
            this.inputField.setMaxLength(256 - this.msgString.length());
            this.inputField.setBordered(false);
            this.inputField.setFocused(true);
            this.inputField.setValue("");
            this.inputField.setTextColor(-1);
            this.inputField.setTextColorUneditable(-8355712);
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
            this.mc.player.connection.sendCommand(LiveMessage.INSTANCE.getIgnoreCommand() + " " + this.liveProfile.username);
        }

        LivemessageGui.liveWindows.remove(this);
        if (!LivemessageGui.liveWindows.isEmpty()) {
            LivemessageGui.liveWindows.get(LivemessageGui.liveWindows.size() - 1).activateWindow();
        }
    }

    private void requestTeleport() {
        if (this.mc.player != null) {
            this.mc.player.connection.sendCommand(LiveMessage.INSTANCE.getTpaCommand() + " " + this.liveProfile.username);
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
                if (MINECRAFT_COLORS[i].isColor() && MINECRAFT_COLORS[i].getColor() != null) {
                    int colorValue = MINECRAFT_COLORS[i].getColor() | 0xFF000000;
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
        } else if (MINECRAFT_COLORS[currentIndex].isColor() && MINECRAFT_COLORS[currentIndex].getColor() != null) {
            this.chatSettings.customColor = MINECRAFT_COLORS[currentIndex].getColor() | 0xFF000000;
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

        Gson gson = new Gson();
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
                ChatWindow.ChatMessage parsed = gson.fromJson(allLines.get(i), ChatWindow.ChatMessage.class);
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
    }

    public void saveChatMessage(ChatWindow.ChatMessage message) {
        Gson gson = new Gson();

        try (FileWriter writer = new FileWriter(LivemessageUtil.MESSAGES_FOLDER.resolve(this.liveProfile.uuid.toString() + ".jsonl").toFile(), true)) {
            writer.write(gson.toJson(message) + "\n");
        } catch (IOException e) {
            LiveMessage.logError("Failed to save chat message to history file for UUID: {}", this.liveProfile.uuid, e);
        }

        if (LiveMessage.INSTANCE != null) {
            LivemessageUtil.trimHistory(this.liveProfile.uuid, LiveMessage.INSTANCE.maxHistoryLines.get());
        }
    }

    public void addMessage(String message, boolean sentByMe) {
        ChatWindow.ChatMessage chatMessage = new ChatWindow.ChatMessage(message, sentByMe, System.currentTimeMillis(), this.mc.player.getUUID());
        boolean wasAtBottom = this.isAtBottom();
        this.chatHistory.add(chatMessage);
        this.saveChatMessage(chatMessage);
        LivemessageGui.recordRecentLog(this.liveProfile.uuid, message, sentByMe);
        this.clampScrollPosition();
        if (wasAtBottom) {
            this.scrollToBottom();
        }

        if (!sentByMe && !this.active) {
            int unreads = LivemessageGui.unreadMessages.getOrDefault(this.liveProfile.uuid, 0);
            LivemessageGui.unreadMessages.put(this.liveProfile.uuid, unreads + 1);
        }
    }

    private void queueMessage(String message) {
        ChatWindow.ChatMessage queued = new ChatWindow.ChatMessage(message, true, System.currentTimeMillis(), this.mc.player.getUUID());
        queued.pending = true;

        boolean wasAtBottom = this.isAtBottom();
        this.chatHistory.add(queued);
        this.saveChatMessage(queued);
        this.pendingMessages.add(queued);
        LivemessageGui.recordRecentLog(this.liveProfile.uuid, message, true);
        this.clampScrollPosition();
        if (wasAtBottom) {
            this.scrollToBottom();
        }
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
        this.mc.player.connection.sendCommand(LiveMessage.INSTANCE.getPmCommand() + " " + this.liveProfile.username + " " + popped.message);
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
        this.clampScrollPosition();
        if (wasAtBottom) {
            this.scrollToBottom();
        }
    }

    private boolean containsMessage(String message, boolean sentByMe, long timestamp) {
        for (ChatWindow.ChatMessage existing : this.chatHistory) {
            if (existing.sentByMe == sentByMe
                && existing.message.equals(message)
                && Math.abs(existing.timestamp - timestamp) < 5000L) {
                return true;
            }
        }

        return false;
    }

    public void clampScrollPosition() {
        if (this.chatHistory.isEmpty()) {
            this.chatScrollPosition = 0;
            return;
        }

        if (this.chatScrollPosition >= this.chatHistory.size()) {
            this.scrollToBottom();
            return;
        }

        int maxScroll = this.getMaxScrollPosition();
        if (this.chatScrollPosition > maxScroll) {
            this.chatScrollPosition = maxScroll;
        }
    }

    public void syncHistoryFromFile() {
        this.loadMessagesFromFile(this.liveProfile.uuid);
        UUID offlineUuid = UUID.nameUUIDFromBytes(
            ("OfflinePlayer:" + this.liveProfile.username.toLowerCase(Locale.ROOT)).getBytes()
        );
        if (!offlineUuid.equals(this.liveProfile.uuid)) {
            this.loadMessagesFromFile(offlineUuid);
        }

        this.chatHistory.sort((a, b) -> Long.compare(a.timestamp, b.timestamp));
        this.clampScrollPosition();
    }

    private void loadMessagesFromFile(UUID uuid) {
        Gson gson = new Gson();

        try (BufferedReader reader = new BufferedReader(
            new FileReader(LivemessageUtil.MESSAGES_FOLDER.resolve(uuid.toString() + ".jsonl").toFile())
        )) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    ChatWindow.ChatMessage loaded = gson.fromJson(line, ChatWindow.ChatMessage.class);
                    if (loaded != null && loaded.message != null && !this.containsMessage(loaded.message, loaded.sentByMe, loaded.timestamp)) {
                        this.chatHistory.add(loaded);
                        if (loaded.pending) {
                            this.pendingMessages.add(loaded);
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

    private int getChatBoxHeight() {
        return this.h - (this.chatBoxY + CHAT_INPUT_RESERVE);
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
        return Math.max(0, this.chatHistory.size() - this.getVisibleMessageLines());
    }

    public boolean isAtBottom() {
        return this.chatScrollPosition >= this.getMaxScrollPosition();
    }

    public void scrollToBottom() {
        this.chatScrollPosition = this.getMaxScrollPosition();
        this.chatScrolledToBottom = true;
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) {
        this.markAsRead();
        if (keyCode == 257 || keyCode == 335) {
            String s = this.inputField.getValue().trim();
            if (!s.isEmpty() && this.mc.player != null) {
                if (LivemessageUtil.checkOnlineStatus(this.liveProfile.uuid) && !WhisperRateLimiter.isOnCooldown()) {
                    this.suppressEchoUntil = System.currentTimeMillis() + 3000L;
                    WhisperRateLimiter.markSelfInitiated(this.liveProfile.username, s);
                    this.mc.player.connection.sendCommand(LiveMessage.INSTANCE.getPmCommand() + " " + this.liveProfile.username + " " + s);
                    WhisperRateLimiter.recordSent();
                    this.addMessage(s, true);
                } else {
                    this.queueMessage(s);
                }

                this.inputField.setValue("");
            }
        } else if (keyCode == 266) {
            this.chatScrollPosition = Mth.clamp(this.chatScrollPosition - 10, 0, this.getMaxScrollPosition());
            this.chatScrolledToBottom = this.isAtBottom();
        } else if (keyCode == 267) {
            this.chatScrollPosition = Mth.clamp(this.chatScrollPosition + 10, 0, this.getMaxScrollPosition());
            this.chatScrolledToBottom = this.isAtBottom();
        } else {
            if (keyCode != 0 && this.lastKeyInput != null) {
                this.inputField.keyPressed(this.lastKeyInput);
            }

            if (typedChar != 0 && this.lastCharInput != null) {
                this.inputField.charTyped(this.lastCharInput);
            }
        }

        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void mouseWheel(int mWheelState) {
        this.markAsRead();
        boolean shift = GLFW.glfwGetKey(this.mc.getWindow().handle(), 340) == 1;
        int scrollAmount = shift ? 10 : 1;
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
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    public void handleMouseDrag(double mouseX, double mouseY) {
        if (this.scrolling && this.chatHistory.size() > this.getVisibleMessageLines()) {
            int totalPixels = this.h - (this.chatBoxY + 10 + 13 + this.scrollBarHeight);
            int maxScroll = this.getMaxScrollPosition();
            int relativeMouseY = (int) mouseY - (this.dragY + this.chatBoxY + this.y);
            this.chatScrollPosition = (int) Mth.clamp((float) (relativeMouseY * maxScroll) / totalPixels, 0.0F, maxScroll);
            this.chatScrolledToBottom = this.isAtBottom();
        } else {
            super.handleMouseDrag(mouseX, mouseY);
        }
    }

    @Override
    public void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (this.scrolling && this.chatHistory.size() > this.getVisibleMessageLines()) {
            int totalPixels = this.h - (this.chatBoxY + 10 + 13 + this.scrollBarHeight);
            int maxScroll = this.getMaxScrollPosition();
            int relativeMouseY = mouseY - (this.dragY + this.chatBoxY + this.y);
            this.chatScrollPosition = (int) Mth.clamp((float) (relativeMouseY * maxScroll) / totalPixels, 0.0F, maxScroll);
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
            int inputFieldY = this.h - 13 - 2;
            if (this.mouseInRect(5, inputFieldY, this.w - 10, 13, mouseX, mouseY)) {
                this.inputField.setFocused(true);
            } else {
                this.inputField.setFocused(false);
            }

            if (this.chatHistory.size() > this.getVisibleMessageLines()
                && this.mouseInRect(5 + this.w - 10 - 10, this.chatBoxY, 10, this.h - (this.chatBoxY + 10 + 13), mouseX, mouseY)) {
                this.scrolling = true;
                int maxScroll = this.getMaxScrollPosition();
                if (maxScroll > 0) {
                    int availableScrollArea = this.h - (this.chatBoxY + 10 + 13) - this.scrollBarHeight;
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
        if (this.mc.keyboardHandler != null) {
            this.mc.keyboardHandler.setClipboard(text);
        }
        this.copyFeedbackText = "Copied!";
        this.copyFeedbackExpireAt = System.currentTimeMillis() + 1200L;
    }

    private void openUrl(String url) {
        try {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }

            Util.getPlatform().openUri(url);
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
                chatWindow.loadChatHistory();
            }
        }
    }

    private List<ChatWindow.RenderedLine> buildRenderedLines(int startMessageIndex, int chatBoxX, int chatColorMe, int chatColorOther) {
        List<ChatWindow.RenderedLine> lines = new ArrayList<>();
        if (startMessageIndex >= this.chatHistory.size()) {
            return lines;
        }

        DateFormat dateFormat = new SimpleDateFormat("MMMM dd, yyyy");
        DateFormat timeFormat = new SimpleDateFormat("<HH:mm> ");
        String lastDay = dateFormat.format(new Date(System.currentTimeMillis()));

        for (int i = startMessageIndex; i < this.chatHistory.size(); i++) {
            ChatWindow.ChatMessage chatMessage = this.chatHistory.get(i);
            boolean isTrimmed = false;
            String message = LivemessageUtil.stripChatDecorations(chatMessage.message);
            Date timestamp = new Date(chatMessage.timestamp);
            int baseColor = chatMessage.sentByMe ? chatColorMe : chatColorOther;

            if (chatMessage.pending) {
                message = message + " (pending)";
                baseColor = GuiUtil.getSingleRGB(140);
            }

            while (true) {
                String thisDay = dateFormat.format(timestamp);
                if (!thisDay.equals(lastDay)) {
                    lastDay = thisDay;
                    lines.add(new ChatWindow.RenderedLine(thisDay, chatBoxX + 4, GuiUtil.getSingleRGB(64), false, -1));
                } else {
                    if (!isTrimmed) {
                        message = timeFormat.format(timestamp) + message;
                    }

                    int trimIndent = isTrimmed ? this.getTextWidth("<00:00> ") : 0;
                    int maxWidth = this.w - (chatBoxX * 2 + 8 + trimIndent + 10 - 5);
                    String trimmed = this.fontRenderer.plainSubstrByWidth(message, maxWidth);
                    lines.add(new ChatWindow.RenderedLine(trimmed, chatBoxX + 4 + trimIndent, baseColor, true, i));
                    if (message.equals(trimmed)) {
                        break;
                    }

                    message = message.substring(trimmed.length());
                    isTrimmed = true;
                }
            }
        }

        return lines;
    }

    private void drawChatHistory(GuiGraphicsExtractor context, int chatBoxX, int chatBoxY, int chatColorMe, int chatColorOther) {
        this.clickableLinks.clear();
        this.clickableChatLines.clear();
        this.clampScrollPosition();

        if (this.chatHistory.isEmpty()) {
            int placeholderY = this.getMessageAreaBottomEdge() - MESSAGE_LINE_HEIGHT;
            this.drawText(context, "You're chatting with " + this.liveProfile.username, chatBoxX + 4, placeholderY, GuiUtil.getSingleRGB(96), false);
            this.chatScrolledToBottom = true;
            return;
        }

        int messageAreaTop = this.getMessageAreaTop();
        int messageAreaBottomEdge = this.getMessageAreaBottomEdge();
        int capacity = this.getVisibleMessageLines();
        List<ChatWindow.RenderedLine> lines = this.buildRenderedLines(this.chatScrollPosition, chatBoxX, chatColorMe, chatColorOther);
        int drawCount = Math.min(lines.size(), capacity);
        int startIndex = this.isAtBottom() ? Math.max(0, lines.size() - drawCount) : 0;
        int y = this.isAtBottom()
            ? messageAreaBottomEdge - drawCount * MESSAGE_LINE_HEIGHT
            : messageAreaTop;
        this.chatScrolledToBottom = this.isAtBottom() && lines.size() <= capacity;

        for (int i = startIndex; i < startIndex + drawCount && i < lines.size(); i++) {
            if (y + MESSAGE_LINE_HEIGHT > messageAreaBottomEdge) {
                this.chatScrolledToBottom = false;
                break;
            }

            ChatWindow.RenderedLine line = lines.get(i);
            if (line.urls) {
                this.drawTextWithUrls(context, line.text, line.x, y, line.color);
            } else {
                this.drawText(context, line.text, line.x, y, line.color, false);
            }

            if (line.messageIndex >= 0) {
                this.clickableChatLines.add(new ClickableChatLine(this.x + 5, this.y + y, this.w - 20, MESSAGE_LINE_HEIGHT, line.messageIndex));
            }

            y += MESSAGE_LINE_HEIGHT;
        }
    }

    private void drawTextWithUrls(GuiGraphicsExtractor context, String text, int x, int y, int baseColor) {
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

    private void drawProfilePic(GuiGraphicsExtractor context, int x, int y) {
        boolean removeHat = this.lastMouseX > this.x + x
            && this.lastMouseX < this.x + x + 32
            && this.lastMouseY > this.y + y
            && this.lastMouseY < this.y + y + 32;
        float progress = this.fullSkinAnim.animate(removeHat && this.clicked && !this.dragging && !this.resizing && !this.scrolling ? 1.0F : 0.0F);
        int displaySize = Math.round(32.0F + progress * 224.0F);
        int displayX = Math.round(x - progress * 32.0F);
        int displayY = Math.round(y - progress * 32.0F);
        PlayerInfo entry = this.mc.getConnection().getPlayerInfo(this.liveProfile.uuid);

        if (entry != null) {
            this.liveSkinUtil.captureFromTabList(entry);
            PlayerFaceExtractor.extractRenderState(context, entry.getSkin(), displayX, displayY, displaySize, GuiUtil.fade(-1));
        } else if (this.liveSkinUtil.hasCachedSkin()) {
            PlayerFaceExtractor.extractRenderState(context, this.liveSkinUtil.getCachedTextures(), displayX, displayY, displaySize, GuiUtil.fade(-1));
        }
    }

    @Override
    public void drawWindow(GuiGraphicsExtractor context, int bgColor, int fgColor) {
        boolean online = LivemessageUtil.checkOnlineStatus(this.liveProfile.uuid);
        if (online) {
            this.flushPendingMessages();
        }
        this.title = "[DM] " + this.liveProfile.username;
        int unreads = LivemessageGui.unreadMessages.getOrDefault(this.liveProfile.uuid, 0);
        if (unreads > 0) {
            this.title = this.title + " \u00a7l(" + unreads + ")";
        }

        this.scrollBarHeight = this.chatHistory.size() < 2
            ? 0
            : (int) Mth.clamp(
            Math.floor((this.h - (this.chatBoxY + 10 + 13)) / Math.max((this.chatHistory.size() - 1) / 10, 1)), 10.0, (this.h - (this.chatBoxY + 10 + 13)) / 2
        );
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
        GuiUtil.drawRect(context, 4, this.chatBoxY - 1, this.w - 10 + 2, this.h - (this.chatBoxY + 10 + 13) + 2, GuiUtil.getSingleRGB(64));
        GuiUtil.drawRect(context, 5, this.chatBoxY, this.w - 10, this.h - (this.chatBoxY + 10 + 13), GuiUtil.getSingleRGB(chatbg));
        int inputBorderColor = online ? GuiUtil.getSingleRGB(64) : GuiUtil.getRGB(200, 50, 50);
        int inputBgColor = online ? GuiUtil.getSingleRGB(textbg) : GuiUtil.getRGB(40, 20, 20);
        GuiUtil.drawRect(context, 4, this.chatBoxY - 1 + this.h - (this.chatBoxY + 5 + 13), this.w - 10 + 2, 15, inputBorderColor);
        GuiUtil.drawRect(context, 5, this.chatBoxY + this.h - (this.chatBoxY + 5 + 13), this.w - 10, 13, inputBgColor);
        if (!online) {
            String warningIcon = "\u00a7l!";
            int iconX = 5 + this.w - 10 - this.getTextWidth(warningIcon) - 3;
            int iconY = this.chatBoxY + this.h - (this.chatBoxY + 5 + 13) + 2;
            this.drawText(context, warningIcon, iconX + 1, iconY, GuiUtil.getRGB(100, 20, 20), false);
            this.drawText(context, warningIcon, iconX, iconY, GuiUtil.getRGB(255, 85, 85), false);
        }

        if (this.chatHistory.size() > this.getVisibleMessageLines()) {
            int maxScroll = this.getMaxScrollPosition();
            int availableScrollArea = this.h - (this.chatBoxY + 10 + 13) - this.scrollBarHeight;
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
                    this.mouseInRect(5 + this.w - 10 - 10, this.chatBoxY, 10, this.h - (this.chatBoxY + 10 + 13), this.lastMouseX, this.lastMouseY)
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
            otherPlayerColor = fgColor;
        }

        this.drawChatHistory(context, 5, this.chatBoxY, GuiUtil.getSingleRGB(255), otherPlayerColor);
        this.drawProfilePic(context, 5, titlebarHeight + 5);

        if (this.pendingUrl != null) {
            if (System.currentTimeMillis() < this.pendingUrlExpireAt) {
                GuiUtil.drawTooltip(context, "Click link again to open: " + this.pendingUrl, 8, this.h - 13 - 2 - 16);
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
    public void drawTextFields(GuiGraphicsExtractor context) {
        context.pose().translate(this.x, this.y);
        this.inputField.setTextColor(this.active ? -1 : -8355712);
        this.inputField.setX(8);
        this.inputField.setY(this.h - 13 - 2);
        this.inputField.setWidth(this.w - 18);
        this.inputField.extractWidgetRenderState(context, this.lastMouseX - this.x, this.lastMouseY - this.y, 0.0F);
        context.pose().translate(-this.x, -this.y);
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
