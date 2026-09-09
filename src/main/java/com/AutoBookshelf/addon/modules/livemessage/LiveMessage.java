package com.AutoBookshelf.addon.modules.livemessage;

import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.modules.livemessage.gui.ChatWindow;
import com.AutoBookshelf.addon.modules.livemessage.gui.LiveWindow;
import com.AutoBookshelf.addon.modules.livemessage.gui.LivemessageGui;
import com.AutoBookshelf.addon.modules.livemessage.gui.ManeWindow;
import com.AutoBookshelf.addon.modules.livemessage.util.*;
import com.AutoBookshelf.addon.utils.QueueUtil;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.game.SendMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.KeybindSetting.Builder;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.network.PlayerListEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class LiveMessage extends Module {
    public static final Logger LOG = LoggerFactory.getLogger("Livemessage");
    public static LiveMessage INSTANCE;
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgNotifications = this.settings.createGroup("Notifications");
    private final SettingGroup sgPatterns = this.settings.createGroup("Patterns");
    private final SettingGroup sgAntiSpam = this.settings.createGroup("Anti-Spam");
    private final SettingGroup sgHistory = this.settings.createGroup("History");
    public final Setting<Keybind> openGuiKey = this.sgGeneral
        .add(
            new Builder().name("open-gui-key").description("Keybind to open Livemessage GUI.")
                .defaultValue(Keybind.fromKey(85))
                .action(this::openGui)
                .build()
        );
    public final Setting<Keybind> closeWindowKey = this.sgGeneral
        .add(
            new Builder().name("close-window-key").description("Keybind to close the currently focused Livemessage window.")
                .defaultValue(Keybind.none())
                .action(this::closeFocusedWindow)
                .build()
        );
    public final Setting<Boolean> hideMessages = this.sgGeneral
        .add(
            new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                .name("hide-messages")
                .description("Hide DMs from main chat.")
                .defaultValue(true)
                .build()
        );
    public final Setting<Boolean> readOnReply = this.sgGeneral
        .add(
            new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                .name("read-on-reply")
                .description("Mark messages as read when you reply.")
                .defaultValue(true)
                .build()
        );
    public final Setting<Double> guiScale = this.sgGeneral
        .add(
            new meteordevelopment.meteorclient.settings.DoubleSetting.Builder()
                .name("gui-scale")
                .description("GUI scale for Livemessage windows.")
                .defaultValue(1.0)
                .min(0.25)
                .max(8.0)
                .sliderRange(0.25, 4.0)
                .build()
        );
    public final Setting<Boolean> enableBlur = this.sgGeneral
        .add(
            new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                .name("enable-blur")
                .description("Enable blur overlay when profile picture is enlarged.")
                .defaultValue(false)
                .build()
        );
    public final Setting<Boolean> fadeAnimation = this.sgGeneral
        .add(
            new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                .name("fade-animation")
                .description("Smoothly fade the whole GUI in when you open it and out when you close it.")
                .defaultValue(true)
                .build()
        );
    public final Setting<Double> fadeDuration = this.sgGeneral
        .add(
            new meteordevelopment.meteorclient.settings.DoubleSetting.Builder()
                .name("fade-duration")
                .description("How long the GUI fade in/out takes, in seconds.")
                .defaultValue(0.2)
                .min(0.0)
                .sliderRange(0.0, 1.0)
                .visible(this.fadeAnimation::get)
                .build()
        );
    public final Setting<Integer> defaultChatWidth = this.sgGeneral
        .add(
            new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                .name("default-chat-width")
                .description("Default width of chat windows.")
                .defaultValue(400)
                .min(200)
                .max(1000)
                .sliderMin(200)
                .sliderMax(800)
                .build()
        );
    public final Setting<Integer> defaultChatHeight = this.sgGeneral
        .add(
            new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                .name("default-chat-height")
                .description("Default height of chat windows.")
                .defaultValue(300)
                .min(150)
                .max(800)
                .sliderMin(150)
                .sliderMax(600)
                .build()
        );
    public final Setting<Boolean> openOnChatKey = this.sgGeneral
        .add(
            new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                .name("open-on-chat-key")
                .description("Open Livemessage GUI when pressing the chat key.")
                .defaultValue(false)
                .build()
        );
    public final Setting<String> pmCommand = this.sgGeneral
        .add(
            new meteordevelopment.meteorclient.settings.StringSetting.Builder()
                .name("pm-command")
                .description("Server PM command used when sending DMs from the GUI (without /). Examples: msg, w, tell, whisper.")
                .defaultValue("msg")
                .build()
        );
    public final Setting<String> tpaCommand = this.sgGeneral
        .add(
            new meteordevelopment.meteorclient.settings.StringSetting.Builder()
                .name("tpa-command")
                .description("Server command used by the DM window's teleport-request button (without /). Examples: tpa, call, tpr.")
                .defaultValue("tpa")
                .build()
        );
    public final Setting<String> ignoreCommand = this.sgGeneral
        .add(
            new meteordevelopment.meteorclient.settings.StringSetting.Builder()
                .name("ignore-command")
                .description("Server command used by the DM window's ignore button (without /). Examples: ignorehard, ignore, block.")
                .defaultValue("ignore")
                .build()
        );
    public final Setting<Boolean> toastsEnabled = this.sgNotifications
        .add(
            new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                .name("toasts")
                .description("Show toast notifications for new DMs.")
                .defaultValue(true)
                .build()
        );
    public final Setting<Boolean> soundsEnabled = this.sgNotifications
        .add(
            new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                .name("sounds")
                .description("Play notification sounds for new DMs.")
                .defaultValue(true)
                .build()
        );
    public final Setting<Boolean> allowRankPrefix = this.sgPatterns
        .add(
            new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                .name("allow-rank-prefix")
                .description("Ignore rank tags like <Donator> at the start of chat lines when matching formats.")
                .defaultValue(true)
                .onChanged(v -> LivemessageUtil.reloadPatterns())
                .build()
        );
    public final Setting<Boolean> blockAdvertisers = this.sgPatterns
        .add(
            new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                .name("block-advertisers")
                .description("Blocks incoming DMs matching with patterns (not received, no notif), unless the sender is friend.")
                .defaultValue(false)
                .build()
        );
    public final Setting<java.util.List<String>> blockedPatterns = this.sgPatterns
        .add(
            new meteordevelopment.meteorclient.settings.StringListSetting.Builder()
                .name("blocked-dm-patterns")
                .description("Incoming DMs containing any of these (case-insensitive) are blocked when block-advertisers is on.")
                .defaultValue(java.util.List.of(
                    "discord.gg", "discord.com", "gg/", "% off", ".store", ".shop",
                    "cheapest price", "cheap price", "use code", "at checkout", "join now"
                ))
                .visible(this.blockAdvertisers::get)
                .build()
        );
    public final Setting<Boolean> debugCapture = this.sgPatterns
        .add(
            new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                .name("debug-capture")
                .description("Log matched and potential unmatched DM lines to the Meteor console. Also gates non-critical error logging.")
                .defaultValue(false)
                .build()
        );
    public final Setting<String> fromPattern1 = this.sgPatterns
        .add(
            new meteordevelopment.meteorclient.settings.StringSetting.Builder()
                .name("incoming-format-1")
                .description("Incoming DM format as shown in chat. Use 'player' where the username appears. Example: player whispers:")
                .defaultValue("player whispers:")
                .onChanged(v -> LivemessageUtil.reloadPatterns())
                .build()
        );
    public final Setting<String> fromPattern2 = this.sgPatterns
        .add(
            new meteordevelopment.meteorclient.settings.StringSetting.Builder()
                .name("incoming-format-2")
                .description("Incoming DM format. Use 'player' for the username. Example: From player:")
                .defaultValue("From player:")
                .onChanged(v -> LivemessageUtil.reloadPatterns())
                .build()
        );
    public final Setting<String> fromPattern3 = this.sgPatterns
        .add(
            new meteordevelopment.meteorclient.settings.StringSetting.Builder()
                .name("incoming-format-3")
                .description("Incoming DM format. Use 'player' for the username. Example: [player -> me]")
                .defaultValue("[player -> me]")
                .onChanged(v -> LivemessageUtil.reloadPatterns())
                .build()
        );
    public final Setting<String> fromPattern4 = this.sgPatterns
        .add(
            new meteordevelopment.meteorclient.settings.StringSetting.Builder()
                .name("incoming-format-4")
                .description("Incoming DM format. Leave empty to disable. Use 'player' for the username.")
                .defaultValue("player whispers to you:")
                .onChanged(v -> LivemessageUtil.reloadPatterns())
                .build()
        );
    public final Setting<String> toPattern1 = this.sgPatterns
        .add(
            new meteordevelopment.meteorclient.settings.StringSetting.Builder()
                .name("outgoing-format-1")
                .description("Outgoing DM format as shown in chat. Use 'player' where the username appears. Example: You whisper to player:")
                .defaultValue("You whisper to player:")
                .onChanged(v -> LivemessageUtil.reloadPatterns())
                .build()
        );
    public final Setting<String> toPattern2 = this.sgPatterns
        .add(
            new meteordevelopment.meteorclient.settings.StringSetting.Builder()
                .name("outgoing-format-2")
                .description("Outgoing DM format. Use 'player' for the username. Example: To player:")
                .defaultValue("to player:")
                .onChanged(v -> LivemessageUtil.reloadPatterns())
                .build()
        );
    public final Setting<String> toPattern3 = this.sgPatterns
        .add(
            new meteordevelopment.meteorclient.settings.StringSetting.Builder()
                .name("outgoing-format-3")
                .description("Outgoing DM format. Use 'player' for the username. Example: [me -> player]")
                .defaultValue("[me -> player]")
                .onChanged(v -> LivemessageUtil.reloadPatterns())
                .build()
        );
    public final Setting<String> toPattern4 = this.sgPatterns
        .add(
            new meteordevelopment.meteorclient.settings.StringSetting.Builder()
                .name("outgoing-format-4")
                .description("Outgoing DM format. Leave empty to disable. Use 'player' for the username. Example: you whisper to player:")
                .defaultValue("you whisper to player:")
                .onChanged(v -> LivemessageUtil.reloadPatterns())
                .build()
        );
    public final Setting<Integer> whisperCooldown = this.sgAntiSpam
        .add(
            new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                .name("whisper-cooldown")
                .description("Grace period (ms) between outgoing whispers before further sends get queued, to avoid tripping server anti-spam timeouts. 0 disables.")
                .defaultValue(3000)
                .min(0)
                .max(10000)
                .sliderRange(0, 10000)
                .build()
        );
    public final Setting<Boolean> backgroundQueueFlush = this.sgAntiSpam
        .add(
            new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                .name("background-queue-flush")
                .description("Send queued whispers automatically once the recipient is online, even without their chat window open. When off, queued messages only send while that DM window is open.")
                .defaultValue(true)
                .build()
        );
    public final Setting<Integer> maxHistoryLines = this.sgHistory
        .add(
            new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                .name("max-history-lines")
                .description("Maximum lines kept per-conversation history file. Oldest sent/received messages are trimmed first; queued (pending, unsent) messages are never trimmed regardless of this limit.")
                .defaultValue(200)
                .min(20)
                .max(5000)
                .sliderRange(20, 1000)
                .build()
        );

    private int queueScanTicks = 0;

    public LiveMessage() {
        super(Addon.CATEGORY, "livemessage", "Advanced DM management system with GUI.");
        INSTANCE = this;
    }

    public void onActivate() {
        LivemessageUtil.initDirs();
        LivemessageUtil.reloadPatterns();
        LivemessageUtil.trimAllHistories(this.maxHistoryLines.get());
    }

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        if (this.isActive() && event.getMessage() != null) {
            LivemessageMatcher.handle(event.getMessage());
        }
    }

    @EventHandler
    private void onSendMessage(SendMessageEvent event) {
        if (this.isActive() && event.message != null) {
            LivemessageMatcher.handleOutgoingCommand(event.message);
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        com.AutoBookshelf.addon.modules.livemessage.util.LastSeenTracker.onDisconnect();
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if ((Boolean) this.openOnChatKey.get()) {
            if (event.screen instanceof ChatScreen) {
                event.cancel();
                this.mc.setScreen(new LivemessageGui());
            }
        }
    }

    /**
     * Two throttled background jobs, both cheap enough to run from a shared tick handler:
     * 1) Warms LiveSkinUtil's cache for every player currently in the tab list, independent
     * of whether any window is open (in-memory only, negligible cost to runs every tick).
     * 2) Optionally flushes queued whispers for recipients who are online but whose chat
     * window isn't currently open. This one touches disk (QueueUtil.allQueued() reads
     * every contact's message file), so it's throttled to ~once/sec rather than every tick.
     */
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!this.isActive() || this.mc.getNetworkHandler() == null) return;

        java.util.Set<UUID> onlineNow = new java.util.HashSet<>();
        for (PlayerListEntry entry : this.mc.getNetworkHandler().getPlayerList()) {
            UUID uuid = entry.getProfile().id();
            LiveSkinUtil.get(uuid).captureFromTabList(entry);
            if (this.mc.player == null || !uuid.equals(this.mc.player.getUuid())) {
                onlineNow.add(uuid);
            }
        }
        com.AutoBookshelf.addon.modules.livemessage.util.LastSeenTracker.update(onlineNow);

        if (this.backgroundQueueFlush.get() && this.mc.player != null) {
            if (++this.queueScanTicks >= 20) {
                this.queueScanTicks = 0;
                this.flushBackgroundQueue();
            }
        }
    }

    private void flushBackgroundQueue() {
        if (this.mc.player == null || WhisperRateLimiter.isOnCooldown()) return;

        for (Map.Entry<UUID, List<ChatWindow.ChatMessage>> entry : QueueUtil.allQueued().entrySet()) {
            UUID uuid = entry.getKey();
            if (this.isChatWindowOpenFor(uuid)) continue;
            if (!LivemessageUtil.checkOnlineStatus(uuid)) continue;

            LiveProfileCache.LiveProfile profile = LiveProfileCache.getLiveprofileFromUUID(uuid, true);
            String username = profile != null ? profile.username : QueueUtil.usernameFor(uuid);

            ChatWindow.ChatMessage popped = QueueUtil.popOldestPending(uuid);
            if (popped == null) continue;

            WhisperRateLimiter.markSelfInitiated(username, popped.message);
            this.mc.player.networkHandler.sendChatCommand(this.getPmCommand() + " " + username + " " + popped.message);
            WhisperRateLimiter.recordSent();
            // newMessage() is deliberately skipped for self-initiated sends (see LivemessageMatcher),
            // so replicate its readOnReply side effect here to avoid silently dropping it for
            // background-flushed messages specifically.
            if (this.readOnReply.get()) {
                LivemessageGui.unreadMessages.put(uuid, 0);
            }
            ChatWindow.refreshIfOpen(uuid);
            return;
        }
    }

    private boolean isChatWindowOpenFor(UUID uuid) {
        for (LiveWindow window : LivemessageGui.liveWindows) {
            if (window instanceof ChatWindow chatWindow && chatWindow.liveProfile.uuid.equals(uuid)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Routes LOG.error through here so error logging only fires when debug-capture is on.
     */
    public static void logError(String message, Object... args) {
        if (INSTANCE != null && INSTANCE.debugCapture.get()) {
            LOG.error(message, args);
        }
    }

    public String getPmCommand() {
        String command = this.pmCommand.get().trim();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }

        return command.isEmpty() ? "msg" : command;
    }

    public String getTpaCommand() {
        String command = this.tpaCommand.get().trim();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }

        return command.isEmpty() ? "tpa" : command;
    }

    public String getIgnoreCommand() {
        String command = this.ignoreCommand.get().trim();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }

        return command.isEmpty() ? "ignore" : command;
    }

    public void closeFocusedWindow() {
        if (this.mc.currentScreen instanceof LivemessageGui gui) {
            if (LivemessageGui.liveWindows.isEmpty()) return;

            LiveWindow top = LivemessageGui.liveWindows.get(LivemessageGui.liveWindows.size() - 1);
            if (top instanceof ManeWindow || LivemessageGui.liveWindows.size() <= 1) {
                gui.close();
                return;
            }

            LivemessageGui.liveWindows.remove(top);
            LivemessageGui.liveWindows.get(LivemessageGui.liveWindows.size() - 1).activateWindow();
        }
    }

    public void openGui() {
        if (this.mc.currentScreen == null) {
            this.mc.setScreen(new LivemessageGui());
        } else if (this.mc.currentScreen instanceof LivemessageGui gui) {
            boolean anyFieldFocused = false;

            for (LiveWindow window : LivemessageGui.liveWindows) {
                if (window instanceof ChatWindow chatWindow) {
                    if (chatWindow.inputField != null && chatWindow.inputField.isFocused()) {
                        anyFieldFocused = true;
                        break;
                    }
                } else if (window instanceof ManeWindow maneWindow) {
                    if (ManeWindow.searchField != null && ManeWindow.searchField.isFocused()) {
                        anyFieldFocused = true;
                        break;
                    }
                }
            }

            if (!anyFieldFocused) {
                gui.close();
            }
        }
    }
}
