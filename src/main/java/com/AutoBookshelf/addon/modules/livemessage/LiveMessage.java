/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.livemessage;

import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.modules.livemessage.gui.ChatWindow;
import com.AutoBookshelf.addon.modules.livemessage.gui.LiveWindow;
import com.AutoBookshelf.addon.modules.livemessage.gui.LivemessageGui;
import com.AutoBookshelf.addon.modules.livemessage.gui.ManeWindow;
import com.AutoBookshelf.addon.modules.livemessage.util.*;
import com.AutoBookshelf.addon.utils.QueueUtil;
import com.mojang.blaze3d.platform.InputConstants;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.game.SendMessageEvent;
import meteordevelopment.meteorclient.events.meteor.KeyInputEvent;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.KeybindSetting.Builder;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.util.Mth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class LiveMessage extends Module {
    public static final Logger LOG = LoggerFactory.getLogger("Livemessage");
    private final Set<UUID> warnedUnsafeRecipients = new HashSet<>();
    public static volatile LiveMessage INSTANCE;
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
    public final Setting<Integer> windowBackgroundAlpha = this.sgGeneral
        .add(
            new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                .name("window-background-alpha")
                .description("Opacity of the ManeWindow and ChatWindow background and title bar.")
                .defaultValue(120)
                .min(0)
                .max(255)
                .sliderRange(40, 255)
                .build()
        );
    public final Setting<Integer> innerBackgroundAlpha = this.sgGeneral
        .add(
            new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                .name("inner-background-alpha")
                .description("Opacity of the inner chat list, input box, buddy list and search bar backgrounds inside the windows.")
                .defaultValue(125)
                .min(0)
                .max(255)
                .sliderRange(20, 255)
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
    public final Setting<Boolean> autoFocus = this.sgGeneral
        .add(
            new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                .name("auto-focus")
                .description("Automatically focus the text field of the active Livemessage window when the GUI opens or this is enabled.")
                .defaultValue(true)
                .onChanged(aBoolean -> {
                    if (Boolean.TRUE.equals(aBoolean)) {
                        LivemessageGui.applyAutoFocus();
                    } else {
                        LivemessageGui.clearAllTextFieldFocus();
                    }
                })
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
                    "gg/", "% off", ".store", ".shop",
                    "cheapest price", "cheap price", "use code", "at checkout", "join now"
                ))
                .visible(this.blockAdvertisers::get)
                .build()
        );
    /**
     * Blocks spoofed 'From <player>:' lines in public chat from creating unread counts, toasts, files, or reply targets.
     */
    public final Setting<Boolean> debugRejectedCapture = this.sgPatterns
        .add(
            new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                .name("debug-rejected-capture")
                .description("Show whisper-like chat lines (directed at you) that match no DM format in the in-game chat, so they never reach the DM system.")
                .defaultValue(false)
                .build()
        );
    public final Setting<Boolean> debugCapture = this.sgPatterns
        .add(
            new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                .name("debug-capture")
                .description("Log matched/suppressed DM lines to the Meteor console. Also gates non-critical error logging.")
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
    public final Setting<Boolean> friendsOnlyMessages = this.sgAntiSpam
        .add(
            new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                .name("friends-only")
                .description("Only receive DMs from players on your friends list. Messages from non-friends are silently dropped.")
                .defaultValue(false)
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

    // GUI move: keep walking while the Livemessage GUI is open.
    private final SettingGroup sgGuiMove = this.settings.createGroup("GUI Move");
    public final Setting<Boolean> guiMoveMovement = this.sgGuiMove
        .add(
            new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                .name("movement")
                .description("Keep moving with WASD while the Livemessage GUI is open.")
                .defaultValue(false)
                .onChanged(aBoolean -> {
                    if (Boolean.TRUE.equals(aBoolean)) return;
                    if (mc == null || mc.options == null) return;
                    this.releaseForwardedBind(mc.options.keyUp);
                    this.releaseForwardedBind(mc.options.keyDown);
                    this.releaseForwardedBind(mc.options.keyLeft);
                    this.releaseForwardedBind(mc.options.keyRight);
                })
                .build()
        );
    public final Setting<Boolean> guiMoveJump = this.sgGuiMove
        .add(
            new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                .name("jump")
                .description("Allows you to jump while the Livemessage GUI is open.")
                .defaultValue(false)
                .onChanged(aBoolean -> {
                    if (isActive() && !Boolean.TRUE.equals(aBoolean)) this.releaseForwardedBind(mc.options.keyJump);
                })
                .build()
        );
    public final Setting<Boolean> guiMoveSneak = this.sgGuiMove
        .add(
            new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                .name("sneak")
                .description("Allows you to sneak while the Livemessage GUI is open.")
                .defaultValue(false)
                .onChanged(aBoolean -> {
                    if (isActive() && !Boolean.TRUE.equals(aBoolean)) this.releaseForwardedBind(mc.options.keyShift);
                })
                .build()
        );
    public final Setting<Boolean> guiMoveSprint = this.sgGuiMove
        .add(
            new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                .name("sprint")
                .description("Allows you to sprint while the Livemessage GUI is open.")
                .defaultValue(false)
                .onChanged(aBoolean -> {
                    if (isActive() && !Boolean.TRUE.equals(aBoolean)) this.releaseForwardedBind(mc.options.keySprint);
                })
                .build()
        );
    public final Setting<Boolean> guiMoveArrowsRotate = this.sgGuiMove
        .add(
            new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                .name("arrows-rotate")
                .description("Allows you to use your arrow keys to rotate while the Livemessage GUI is open.")
                .defaultValue(false)
                .build()
        );
    public final Setting<Double> guiMoveRotateSpeed = this.sgGuiMove
        .add(
            new meteordevelopment.meteorclient.settings.DoubleSetting.Builder()
                .name("rotate-speed")
                .description("Rotation speed while the Livemessage GUI is open.")
                .defaultValue(4)
                .min(0)
                .build()
        );

    private int queueScanTicks = 0;
    private boolean openGuiOnChatKey = false;
    // Movement keys this module forwarded a press for. Ownership is recorded at forward time so
    // a later release can be cleaned up even when the skip state did not change between press and
    // release; unpressGuiMove forces a release without dropping ownership so re-assertion can
    // resume it on the next non-skip tick; clearForwardedMovementKeys() drops ownership (called
    // on deactivation).
    private final Set<KeyMapping> forwardedMovementKeys = new HashSet<>();
    // GUI-session rotation state. The base is captured once per GUI opening; the LOWEST-priority
    // rotation handler advances it per frame, so holding an arrow key never restarts the
    // rotation from the tick values; and no stale value can leak between sessions.
    private float guiMoveAccumYaw = 0;
    private float guiMoveAccumPitch = 0;
    private boolean guiMoveAccumValid = false;

    public LiveMessage() {
        super(Addon.CATEGORY2, "Livemessage", "Advanced DM management system with GUI.");
        INSTANCE = this;
    }

    public void onActivate() {
        LivemessageUtil.initDirs();
        LivemessageUtil.reloadPatterns();
        LivemessageUtil.trimAllHistories(this.maxHistoryLines.get());
    }

    @Override
    public void onDeactivate() {
        this.openGuiOnChatKey = false;
        this.guiMoveAccumValid = false;
        this.clearForwardedMovementKeys();
    }

    // --- GUI move -------------------------------------------------------------
    // The Livemessage GUI is a screen, so it swallows the movement keys. Passing them into the
    // key mappings keeps the player walking while it is open. The caret always wins: a DM that is
    // being typed in is never disturbed.

    @EventHandler
    private void onGuiMoveKey(KeyInputEvent event) {
        if (this.guiMoveSkip()) return;
        this.passGuiMove(mc.options.keyUp, event.key(), event.action);
        this.passGuiMove(mc.options.keyDown, event.key(), event.action);
        this.passGuiMove(mc.options.keyLeft, event.key(), event.action);
        this.passGuiMove(mc.options.keyRight, event.key(), event.action);
        if (this.guiMoveJump.get()) this.passGuiMove(mc.options.keyJump, event.key(), event.action);
        if (this.guiMoveSneak.get()) this.passGuiMove(mc.options.keyShift, event.key(), event.action);
        if (this.guiMoveSprint.get()) this.passGuiMove(mc.options.keySprint, event.key(), event.action);
    }

    @EventHandler
    private void onGuiMoveMouse(MouseClickEvent event) {
        if (this.guiMoveSkip()) return;
        // The mouse buttons are only a source for the movement keys a mouse button is bound
        // to. The attack key is deliberately not passed: a click inside the GUI must not
        // make the player attack.
        this.passGuiMove(mc.options.keyUp, event.button(), event.action);
        this.passGuiMove(mc.options.keyDown, event.button(), event.action);
        this.passGuiMove(mc.options.keyLeft, event.button(), event.action);
        this.passGuiMove(mc.options.keyRight, event.button(), event.action);
        if (this.guiMoveJump.get()) this.passGuiMove(mc.options.keyJump, event.button(), event.action);
        if (this.guiMoveSneak.get()) this.passGuiMove(mc.options.keyShift, event.button(), event.action);
        if (this.guiMoveSprint.get()) this.passGuiMove(mc.options.keySprint, event.button(), event.action);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onGuiMoveRender(Render3DEvent event) {
        // LOWEST so this runs after any other module's rotation this frame. The Livemessage
        // GUI owns the rotation here: keep a per-session accumulator and advance it by the
        // frame delta, or leave it alone when rotation is disabled. Frame deltas accumulate
        // across frames instead of restarting from the tick base.
        if (mc.player == null) return;
        if (mc.gui == null || !(mc.gui.screen() instanceof LivemessageGui)) {
            // GUI gone: the next opening re-initializes the session's base.
            this.guiMoveAccumValid = false;
            return;
        }

        final boolean active = !this.guiMoveSkip() && this.guiMoveArrowsRotate.get();
        if (!active) {
            // Rotation is inactive: the GUI does not own the player's view, so anything stale
            // in the session accumulator must not leak back in when arrow rotation re-engages.
            this.guiMoveAccumValid = false;
            return;
        }

        if (!this.guiMoveAccumValid) {
            this.guiMoveAccumYaw = mc.player.getYRot();
            this.guiMoveAccumPitch = mc.player.getXRot();
            this.guiMoveAccumValid = true;
        }

        float rotationDelta = (float) Math.min(this.guiMoveRotateSpeed.get() * event.frameTime * 20f, 100);
        float yaw = this.guiMoveAccumYaw;
        float pitch = this.guiMoveAccumPitch;

        if (Input.isKeyPressed(InputConstants.KEY_LEFT)) yaw -= rotationDelta;
        if (Input.isKeyPressed(InputConstants.KEY_RIGHT)) yaw += rotationDelta;
        if (Input.isKeyPressed(InputConstants.KEY_UP)) pitch -= rotationDelta;
        if (Input.isKeyPressed(InputConstants.KEY_DOWN)) pitch += rotationDelta;

        this.guiMoveAccumYaw = yaw;
        this.guiMoveAccumPitch = Mth.clamp(pitch, -90, 90);
        mc.player.setYRot(yaw);
        mc.player.setXRot(this.guiMoveAccumPitch);
    }

    private void passGuiMove(KeyMapping bind, int key, KeyAction action) {
        if (Input.getKey(bind) != key) return;
        if (action == KeyAction.Press) {
            bind.setDown(true);
            this.forwardedMovementKeys.add(bind);
        }
        if (action == KeyAction.Release) {
            bind.setDown(false);
            this.forwardedMovementKeys.remove(bind);
        }
    }

    /**
     * True when the GUI move must not act: the setting is off, another screen is open, or a caret is in use.
     */
    public boolean guiMoveSkip() {
        if (!this.isActive() || !this.guiMoveMovement.get()) return true;
        if (mc.gui == null || !(mc.gui.screen() instanceof LivemessageGui gui)) return true;
        // A text field has the caret: the keys belong to it, not to the player.
        return gui.isAnyTextFieldFocused();
    }

    /**
     * Releases the movement keys this module forwarded, and only those.
     */
    private void unpressGuiMove() {
        if (mc == null || mc.options == null) return;
        for (KeyMapping bind : this.forwardedMovementKeys) {
            bind.setDown(false);
        }
        // Ownership is kept so the module can re-assert held state when the GUI re-engages;
        // clearForwardedMovementKeys() drops the ownership, called from onDeactivate.
    }

    /**
     * Dropped bits of ownership and forced release; called on deactivation.
     */
    private void clearForwardedMovementKeys() {
        if (mc == null || mc.options == null) return;
        for (KeyMapping bind : this.forwardedMovementKeys) {
            bind.setDown(false);
        }
        this.forwardedMovementKeys.clear();
    }

    /**
     * Re-assert the physical held state on every owned bind; restores movement when a
     * text field loses focus while a key is still physically held.
     */
    private void repressOwnedMovementKeys() {
        if (mc == null || mc.options == null || this.forwardedMovementKeys.isEmpty()) return;
        for (KeyMapping bind : this.forwardedMovementKeys) {
            int code = Input.getKey(bind);
            bind.setDown(code != -1 && Input.isKeyPressed(code));
        }
    }

    /**
     * Releases one bind when this module forwarded it (and prunes the ownership set).
     */
    private void releaseForwardedBind(KeyMapping bind) {
        if (bind == null) return;
        bind.setDown(false);
        this.forwardedMovementKeys.remove(bind);
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
        LiveSkinUtil.forceSave();
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if ((Boolean) this.openOnChatKey.get()) {
            if (event.screen instanceof ChatScreen) {
                event.cancel();
                this.openGuiOnChatKey = true;
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
        if (this.openGuiOnChatKey) {
            this.openGuiOnChatKey = false;
            this.mc.gui.setScreen(new LivemessageGui());
        }

        // A key that was held while the GUI move was active has to be released when it stops
        // applying, or the player keeps walking after the GUI closed. When the GUI move is
        // active we re-assert the physical held state on every bind we own, so a key held
        // through a focus change continues to move the player when focus returns.
        boolean skipped = this.guiMoveSkip();
        if (skipped) this.unpressGuiMove();
        else this.repressOwnedMovementKeys();

        if (this.mc.gui != null && this.mc.player != null && this.mc.gui.screen() instanceof LivemessageGui) {
            // GUI-session rotation base: captured once per opening, never stale.
            if (!this.guiMoveAccumValid) {
                this.guiMoveAccumYaw = this.mc.player.getYRot();
                this.guiMoveAccumPitch = this.mc.player.getXRot();
                this.guiMoveAccumValid = true;
            }
        } else {
            // GUI not open: the session's base is stale, the next opening re-initializes it.
            this.guiMoveAccumValid = false;
        }

        if (!this.isActive() || this.mc.getConnection() == null) return;

        java.util.Set<UUID> onlineNow = new java.util.HashSet<>();
        for (PlayerInfo entry : this.mc.getConnection().getOnlinePlayers()) {
            UUID uuid = entry.getProfile().id();
            LiveSkinUtil.get(uuid).captureFromTabList(entry);
            if (this.mc.player == null || !uuid.equals(this.mc.player.getUUID())) {
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

            // Never interpolate an unvalidated username into sendCommand(): it
            // would let a crafted profile hijack the command (e.g. inject a
            // second command via a leading '/'). Warn once per recipient.
            if (!LivemessageUtil.isSafeUsername(username)) {
                if (warnedUnsafeRecipients.add(uuid)) {
                    LOG.warn("Skipping queued message for {}: unsafe username", uuid);
                }
                continue;
            }

            ChatWindow.ChatMessage popped = QueueUtil.popOldestPending(uuid);
            if (popped == null) continue;

            WhisperRateLimiter.markSelfInitiated(username, popped.message);
            this.mc.player.connection.sendCommand(this.getPmCommand() + " " + username + " " + popped.message);
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

    /**
     * Validates a server command name. The name is interpolated into a command, so it must not
     * contain whitespace or a slash - a space would add an argument and a separator would start a
     * second command. Digits, underscores, dots, hyphens and a namespace are legitimate.
     */
    private static String validCommand(String raw, String fallback, String setting) {
        String command = raw == null ? "" : raw.trim();
        if (command.startsWith("/")) command = command.substring(1);
        if (command.isEmpty() || !command.matches("^[a-zA-Z0-9_.:-]+$")) {
            if (!invalidCommands.contains(setting)) {
                invalidCommands.add(setting);
                LOG.warn("Invalid {} '{}', falling back to '{}'.", setting, raw, fallback);
            }
            return fallback;
        }
        invalidCommands.remove(setting);
        return command;
    }

    private static final Set<String> invalidCommands = ConcurrentHashMap.newKeySet();

    public String getPmCommand() {
        return validCommand(this.pmCommand.get(), "msg", "pm-command");
    }

    public String getTpaCommand() {
        return validCommand(this.tpaCommand.get(), "tpa", "tpa-command");
    }

    public String getIgnoreCommand() {
        return validCommand(this.ignoreCommand.get(), "ignore", "ignore-command");
    }

    public void closeFocusedWindow() {
        if (this.mc.gui.screen() instanceof LivemessageGui gui) {
            if (LivemessageGui.liveWindows.isEmpty()) return;

            LiveWindow top = LivemessageGui.liveWindows.get(LivemessageGui.liveWindows.size() - 1);
            if (top instanceof ManeWindow || LivemessageGui.liveWindows.size() <= 1) {
                gui.onClose();
                return;
            }

            LivemessageGui.liveWindows.remove(top);
            LivemessageGui.liveWindows.get(LivemessageGui.liveWindows.size() - 1).activateWindow();
        }
    }

    public void openGui() {
        if (this.mc.gui.screen() == null) {
            this.mc.gui.setScreen(new LivemessageGui());
        } else if (this.mc.gui.screen() instanceof LivemessageGui gui) {
            boolean anyFieldFocused = false;

            for (LiveWindow window : LivemessageGui.liveWindows) {
                if (window instanceof ChatWindow chatWindow) {
                    if (chatWindow.inputFocused) {
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
                gui.onClose();
            }
        }
    }
}
