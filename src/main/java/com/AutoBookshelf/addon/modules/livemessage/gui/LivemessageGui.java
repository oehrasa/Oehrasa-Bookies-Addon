package com.AutoBookshelf.addon.modules.livemessage.gui;

import com.AutoBookshelf.addon.modules.livemessage.LiveMessage;
import com.AutoBookshelf.addon.modules.livemessage.notes.NotesWindow;
import com.AutoBookshelf.addon.modules.livemessage.util.LiveProfileCache;
import com.AutoBookshelf.addon.modules.livemessage.util.LivemessageUtil;
import com.AutoBookshelf.addon.utils.FadeAnimator;
import com.google.gson.Gson;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.AutoBookshelf.addon.modules.livemessage.LiveMessage.logError;

public class LivemessageGui extends Screen {
    public static boolean buddiesLoaded = false;
    public static List<UUID> chats = new CopyOnWriteArrayList<>();
    public static List<UUID> recentChats = new CopyOnWriteArrayList<>();
    public static Map<UUID, Integer> unreadMessages = new ConcurrentHashMap<>();
    public static List<LiveWindow> liveWindows = new CopyOnWriteArrayList<>();
    private static LiveWindow lastActiveChatWindow = null;
    private static final Map<UUID, LivemessageGui.RecentLogEntry> recentLogs = new ConcurrentHashMap<>();
    private static final long DUPLICATE_WINDOW_MS = 3000L;
    public static double sclOrig = 1.0;
    public static double scl = 1.0;
    public static int screenHeight = 600;
    public static int screenWidth = 800;
    private LiveWindow activeWindow = null;
    private boolean initialized = false;
    public static float currentFadeAlpha = 1.0F;
    private final FadeAnimator fade = new FadeAnimator();
    private boolean closing = false;

    public LivemessageGui() {
        super(Component.literal("Livemessage"));
        if (this.minecraft != null) {
            this.setScl();
        }
    }

    public static void handleBtn(int action) {
        switch (action) {
            case 0:
                liveWindows.get(liveWindows.size() - 1).deactivateWindow();
                liveWindows.add(new ManeWindow());
        }
    }

    public static void loadBuddies() {
        chats.clear();
        buddiesLoaded = true;
        File folder = LivemessageUtil.MESSAGES_FOLDER.toFile();
        File[] listOfFiles = folder.listFiles();
        if (listOfFiles != null) {
            for (File file : listOfFiles) {
                if (file.isFile() && file.getName().endsWith(".jsonl") && file.getName().length() >= 36) {
                    try {
                        chats.add(UUID.fromString(file.getName().substring(0, 36)));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }

        Collections.sort(chats);
    }

    public static void markAllAsRead() {
        unreadMessages.clear();
    }

    public void setScl() {
        double guiScale = (Double) LiveMessage.INSTANCE.guiScale.get();
        scl = 1.0 / guiScale;
        screenHeight = (int) (this.minecraft.getWindow().getGuiScaledHeight() / guiScale);
        screenWidth = (int) (this.minecraft.getWindow().getGuiScaledWidth() / guiScale);
    }

    protected void init() {
        this.setScl();
        loadBuddies();
        boolean hasManeWindow = false;
        for (LiveWindow window : liveWindows) {
            if (window instanceof ManeWindow) {
                hasManeWindow = true;
                break;
            }
        }
        if (!hasManeWindow) {
            // Insert at the base of the stack rather than on top, so it doesn't steal focus
            // from whatever chat/notes window the user still had open.
            liveWindows.add(0, new ManeWindow());
        }

        this.restoreLastActiveChatWindow();
    }

    public void removed() {
        this.activeWindow = null;
        this.saveLastActiveChatWindow();

        for (LiveWindow window : liveWindows) {
            if (window instanceof ChatWindow chatWindow) {
                if (chatWindow.inputField != null) {
                    chatWindow.inputField.setFocused(false);
                }
            } else if (window instanceof ManeWindow) {
                if (ManeWindow.searchField != null) {
                    ManeWindow.searchField.setFocused(false);
                }
            } else if (window instanceof NotesWindow notesWindow && notesWindow.inputField != null) {
                notesWindow.inputField.setFocused(false);
            }
        }

        super.removed();
    }

    private void saveLastActiveChatWindow() {
        if (!liveWindows.isEmpty()) {
            LiveWindow topWindow = liveWindows.get(liveWindows.size() - 1);
            if (topWindow instanceof ChatWindow || topWindow instanceof NotesWindow) {
                lastActiveChatWindow = topWindow;
            }
        }
    }

    private void restoreLastActiveChatWindow() {
        if (lastActiveChatWindow != null && liveWindows.contains(lastActiveChatWindow)) {
            liveWindows.get(liveWindows.size() - 1).deactivateWindow();
            liveWindows.remove(lastActiveChatWindow);
            liveWindows.add(lastActiveChatWindow);
            lastActiveChatWindow.activateWindow();
            if (lastActiveChatWindow instanceof ChatWindow chatWindow) {
                if (chatWindow.inputField != null) {
                    chatWindow.inputField.setFocused(true);
                }
            } else if (lastActiveChatWindow instanceof NotesWindow notesWindow && notesWindow.inputField != null) {
                notesWindow.inputField.setFocused(true);
            }
        }
    }

    public static void openChatWindow(UUID uuid) {
        if (uuid != null) {
            liveWindows.get(liveWindows.size() - 1).deactivateWindow();

            for (LiveWindow liveWindow : liveWindows) {
                if (liveWindow instanceof ChatWindow chatWindow && chatWindow.liveProfile.uuid.equals(uuid)) {
                    chatWindow.activateWindow();
                    liveWindows.removeIf(it -> it == chatWindow);
                    liveWindows.add(chatWindow);
                    return;
                }
            }

            addChatWindow(new ChatWindow(uuid));
        }
    }

    private static void addChatWindow(ChatWindow chatWindow) {
        if (chatWindow.valid) {
            liveWindows.add(chatWindow);
        } else {
            liveWindows.get(liveWindows.size() - 1).activateWindow();
        }
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        liveWindows.get(liveWindows.size() - 1).mouseWheel((int) verticalAmount);
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        if (this.activeWindow != null) {
            int virtualX = (int) (click.x() / (Double) LiveMessage.INSTANCE.guiScale.get());
            int virtualY = (int) (click.y() / (Double) LiveMessage.INSTANCE.guiScale.get());
            this.activeWindow.handleMouseDrag(virtualX, virtualY);
        }

        return super.mouseDragged(click, deltaX, deltaY);
    }

    public void mouseMoved(double mouseX, double mouseY) {
        if (!liveWindows.isEmpty()) {
            int virtualX = (int) (mouseX / (Double) LiveMessage.INSTANCE.guiScale.get());
            int virtualY = (int) (mouseY / (Double) LiveMessage.INSTANCE.guiScale.get());
            liveWindows.get(liveWindows.size() - 1).mouseMove(virtualX, virtualY);
        }

        super.mouseMoved(mouseX, mouseY);
    }

    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (!liveWindows.isEmpty()) {
            double guiScale = (Double) LiveMessage.INSTANCE.guiScale.get();
            int virtualX = (int) (click.x() / guiScale);
            int virtualY = (int) (click.y() / guiScale);
            int button = click.button();
            LiveWindow clickedWindow = null;

            for (int i = liveWindows.size() - 1; i >= 0; i--) {
                LiveWindow liveWindow = liveWindows.get(i);
                if (liveWindow.mouseInWindow(virtualX, virtualY)) {
                    clickedWindow = liveWindow;
                    if (i != liveWindows.size() - 1) {
                        liveWindows.get(liveWindows.size() - 1).deactivateWindow();
                        liveWindow.activateWindow();
                        liveWindows.remove(i);
                        liveWindows.add(liveWindow);
                    }
                    break;
                }
            }

            if (clickedWindow != null) {
                this.activeWindow = clickedWindow;
                this.activeWindow.mouseClicked(virtualX, virtualY, button);
            }
        }

        return super.mouseClicked(click, doubled);
    }

    public boolean mouseReleased(MouseButtonEvent click) {
        if (this.activeWindow != null) {
            int virtualX = (int) (click.x() / (Double) LiveMessage.INSTANCE.guiScale.get());
            int virtualY = (int) (click.y() / (Double) LiveMessage.INSTANCE.guiScale.get());
            this.activeWindow.mouseReleased(virtualX, virtualY, click.button());
            this.activeWindow = null;
        }

        return super.mouseReleased(click);
    }

    public boolean keyPressed(KeyEvent input) {
        if (!liveWindows.isEmpty()) {
            LiveWindow activeWindow = liveWindows.get(liveWindows.size() - 1);
            activeWindow.handleKeyInput(input);
            activeWindow.keyTyped('\u0000', input.key());
        }

        if (this.isAnyTextFieldFocused()) {
            return input.key() == 256 ? super.keyPressed(input) : true;
        } else {
            return super.keyPressed(input);
        }
    }

    public boolean charTyped(CharacterEvent input) {
        if (!liveWindows.isEmpty()) {
            LiveWindow activeWindow = liveWindows.get(liveWindows.size() - 1);
            activeWindow.handleCharInput(input);
            activeWindow.keyTyped((char) input.codepoint(), 0);
        }

        return this.isAnyTextFieldFocused() ? true : super.charTyped(input);
    }

    public static boolean newMessage(String username, String message, boolean sentByMe) {
        LiveProfileCache.LiveProfile profile = LiveProfileCache.getLiveprofileFromName(username);
        if (profile == null) {
            return false;
        }

        UUID uuid = profile.uuid;
        boolean doHide = false;
        if (uuid != null) {
            boolean duplicate = isRecentDuplicate(uuid, message, sentByMe);
            if (!duplicate) {
                try {
                    Gson gson = new Gson();
                    FileWriter fw = new FileWriter(LivemessageUtil.MESSAGES_FOLDER.resolve(uuid.toString() + ".jsonl").toFile(), true);
                    BufferedWriter bw = new BufferedWriter(fw);
                    bw.write(gson.toJson(new ChatWindow.ChatMessage(message, sentByMe, System.currentTimeMillis(), Minecraft.getInstance().player.getUUID())));
                    bw.newLine();
                    bw.close();
                } catch (Exception e) {
                    logError("Failed to write message to history file for UUID: {}", uuid, e);
                }

                if (!chats.contains(uuid)) {
                    chats.add(uuid);
                    Collections.sort(chats);
                }

                recentChats.remove(uuid);
                recentChats.add(0, uuid);
                if (recentChats.size() > 10) {
                    recentChats.remove(recentChats.size() - 1);
                }

                if (!sentByMe) {
                    unreadMessages.put(uuid, unreadMessages.getOrDefault(uuid, 0) + 1);
                    if ((Boolean) LiveMessage.INSTANCE.toastsEnabled.get()) {
                        Minecraft mc = Minecraft.getInstance();
                        ToastManager toastManager = mc.getToastManager();
                        toastManager.addToast(new SystemToast(SystemToastId.NARRATOR_TOGGLE, Component.literal("DM from " + username), Component.literal(message)));
                    }

                    if ((Boolean) LiveMessage.INSTANCE.soundsEnabled.get()) {
                        Minecraft.getInstance().player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F, 1.0F);
                    }
                } else if ((Boolean) LiveMessage.INSTANCE.readOnReply.get()) {
                    unreadMessages.put(uuid, 0);
                }

                recordRecentLog(uuid, message, sentByMe);
            }

            if ((Boolean) LiveMessage.INSTANCE.hideMessages.get()) {
                doHide = true;
            }
        }

        long timestamp = System.currentTimeMillis();
        for (LiveWindow liveWindow : liveWindows) {
            if (liveWindow instanceof ChatWindow chatWindow
                && ((uuid != null && uuid.equals(chatWindow.liveProfile.uuid))
                || username.equalsIgnoreCase(chatWindow.liveProfile.username))) {
                chatWindow.appendMessageIfNew(message, sentByMe, timestamp);
                break;
            }
        }
        return doHide;
    }

    public static void recordRecentLog(UUID uuid, String message, boolean sentByMe) {
        recentLogs.put(uuid, new LivemessageGui.RecentLogEntry(message, sentByMe, System.currentTimeMillis()));
    }

    private static boolean isRecentDuplicate(UUID uuid, String message, boolean sentByMe) {
        LivemessageGui.RecentLogEntry recent = recentLogs.get(uuid);
        return recent != null
            && recent.sentByMe == sentByMe
            && recent.message.equals(message)
            && System.currentTimeMillis() - recent.timestamp < DUPLICATE_WINDOW_MS;
    }

    private static class RecentLogEntry {
        private final String message;
        private final boolean sentByMe;
        private final long timestamp;

        private RecentLogEntry(String message, boolean sentByMe, long timestamp) {
            this.message = message;
            this.sentByMe = sentByMe;
            this.timestamp = timestamp;
        }
    }

    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        if (LiveMessage.INSTANCE != null && (Boolean) LiveMessage.INSTANCE.enableBlur.get()) {
            super.extractBackground(context, mouseX, mouseY, delta);
        }
    }

    public void onClose() {
        boolean fadeEnabled = LiveMessage.INSTANCE != null && (Boolean) LiveMessage.INSTANCE.fadeAnimation.get();
        if (fadeEnabled && !this.closing && currentFadeAlpha > 0.001F) {
            this.closing = true;
        } else {
            super.onClose();
        }
    }

    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        boolean fadeEnabled = LiveMessage.INSTANCE != null && (Boolean) LiveMessage.INSTANCE.fadeAnimation.get();
        double fadeDur = LiveMessage.INSTANCE != null ? (Double) LiveMessage.INSTANCE.fadeDuration.get() : 0.2;
        this.fade.update(!this.closing, fadeDur, fadeEnabled);
        currentFadeAlpha = fadeEnabled ? this.fade.alpha() : 1.0F;
        if (!this.closing || fadeEnabled && !(this.fade.alpha() <= 0.001F)) {
            float reverseGuiScale = (float) (1.0 / scl);
            if (LiveMessage.INSTANCE != null && (Boolean) LiveMessage.INSTANCE.enableBlur.get()) {
                boolean shouldDrawBlur = false;
                int blurAlpha = 0;

                for (LiveWindow liveWindow : liveWindows) {
                    if (liveWindow instanceof ChatWindow chatWindow) {
                        if (chatWindow.shouldDrawBlur()) {
                            shouldDrawBlur = true;
                            blurAlpha = chatWindow.getBlurAlpha();
                            break;
                        }
                    } else if (liveWindow instanceof ManeWindow maneWindow && maneWindow.shouldDrawBlur()) {
                        shouldDrawBlur = true;
                        blurAlpha = maneWindow.getBlurAlpha();
                        break;
                    }
                }

                if (shouldDrawBlur) {
                    context.fill(0, 0, screenWidth, screenHeight, GuiUtil.fade(GuiUtil.getRGBA(0, 0, 0, blurAlpha)));
                }
            }

            context.pose().scale(reverseGuiScale, reverseGuiScale);

            for (LiveWindow liveWindow : liveWindows) {
                liveWindow.preDrawWindow(context);
            }

            for (LiveWindow liveWindow : liveWindows) {
                liveWindow.drawTextFields(context);
            }

            context.pose().scale((float) scl, (float) scl);
        } else {
            this.closing = false;
            currentFadeAlpha = 1.0F;
            super.onClose();
        }
    }

    public boolean isPauseScreen() {
        return false;
    }

    public GuiEventListener getFocused() {
        EditBox focused = this.getFocusedTextField();
        return (GuiEventListener) (focused != null ? focused : super.getFocused());
    }

    private EditBox getFocusedTextField() {
        for (LiveWindow window : liveWindows) {
            if (window instanceof ChatWindow chatWindow) {
                if (chatWindow.inputField != null && chatWindow.inputField.isFocused()) {
                    return chatWindow.inputField;
                }
            } else if (window instanceof ManeWindow) {
                if (ManeWindow.searchField != null && ManeWindow.searchField.isFocused()) {
                    return ManeWindow.searchField;
                }
            } else if (window instanceof NotesWindow notesWindow && notesWindow.inputField != null && notesWindow.inputField.isFocused()) {
                return notesWindow.inputField;
            }
        }

        return null;
    }

    public boolean isAnyTextFieldFocused() {
        return this.getFocusedTextField() != null;
    }
}
