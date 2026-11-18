/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.chesttracker;

import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.modules.chesttracker.data.BankStorage;
import com.AutoBookshelf.addon.modules.chesttracker.data.BlockRecord;
import com.AutoBookshelf.addon.modules.chesttracker.data.ContainerRecord;
import com.AutoBookshelf.addon.modules.chesttracker.data.MemoryBank;
import com.AutoBookshelf.addon.modules.chesttracker.gui.TrackerScreen;
import com.AutoBookshelf.addon.modules.chesttracker.search.ItemIndex;
import com.AutoBookshelf.addon.modules.chesttracker.search.ItemQuery;
import com.AutoBookshelf.addon.modules.chesttracker.track.*;
import meteordevelopment.meteorclient.events.entity.player.InteractBlockEvent;
import meteordevelopment.meteorclient.events.entity.player.InteractEntityEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.InventoryEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.lwjgl.glfw.GLFW;

import java.util.*;

/**
 * Remembers the contents of containers on the current server. Opening a container binds its
 * screen to a block or entity, snapshots the slots, and tracks it while it is loaded.
 *
 * <p>The module also owns the optional auto-open loop, which clicks untracked containers in
 * range so the binder can snapshot them without the player opening each one.
 */
public class ChestTrackerModule extends Module {
    /**
     * How containers are grouped in the browser list's browse mode.
     */
    public enum GroupMode {Chunk, Nearby}

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAutoOpen = settings.createGroup("Auto-Open");
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgLabels = settings.createGroup("Labels");
    private final SettingGroup sgFilter = settings.createGroup("Filter");
    private final SettingGroup sgDisplay = settings.createGroup("Display");
    private final SettingGroup sgAdvanced = settings.createGroup("Advanced");
    private final SettingGroup sgBank = settings.createGroup("Bank");
    private final SettingGroup sgTrades = settings.createGroup("Trades");
    private final SettingGroup sgSearch = settings.createGroup("Search");

    private final Setting<Keybind> browserKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("browser-keybind")
        .description("Open the container browser.")
        .defaultValue(Keybind.fromKey(GLFW.GLFW_KEY_Y))
        .action(() -> {
            if (mc.gui.screen() == null) mc.gui.setScreen(new TrackerScreen());
        })
        .build()
    );

    private final Setting<Boolean> showDestroyed = sgGeneral.add(new BoolSetting.Builder()
        .name("show-destroyed")
        .description("Show containers that were likely destroyed in the browser list.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoOpenEnabled = sgAutoOpen.add(new BoolSetting.Builder()
        .name("auto-open")
        .description("Automatically open nearby containers so their contents are remembered.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> autoOpenRange = sgAutoOpen.add(new DoubleSetting.Builder()
        .name("auto-open-range")
        .description("Range to search for containers to auto-open.")
        .defaultValue(4.0)
        .min(1.0)
        .max(6.0)
        .sliderRange(1.0, 6.0)
        .decimalPlaces(1)
        .visible(autoOpenEnabled::get)
        .build()
    );

    private final Setting<Integer> autoOpenDelay = sgAutoOpen.add(new IntSetting.Builder()
        .name("auto-open-delay")
        .description("Delay in ticks between opening containers.")
        .defaultValue(5)
        .min(1)
        .max(20)
        .sliderRange(1, 20)
        .visible(autoOpenEnabled::get)
        .build()
    );

    private final Setting<Integer> autoOpenCloseDelay = sgAutoOpen.add(new IntSetting.Builder()
        .name("auto-close-delay")
        .description("Delay in ticks before closing a container after opening it.")
        .defaultValue(5)
        .min(0)
        .max(40)
        .sliderRange(0, 40)
        .visible(autoOpenEnabled::get)
        .build()
    );

    private final Setting<Boolean> renderTracked = sgRender.add(new BoolSetting.Builder()
        .name("render-tracked")
        .description("Render all tracked containers.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> renderSearchResults = sgRender.add(new BoolSetting.Builder()
        .name("render-search-results")
        .description("Render the containers that hold the searched item.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> renderDistance = sgRender.add(new IntSetting.Builder()
        .name("render-distance")
        .description("Maximum render distance.")
        .defaultValue(128)
        .min(8)
        .max(2048)
        .sliderRange(8, 2048)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("Render shape mode.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> trackedColor = sgRender.add(new ColorSetting.Builder()
        .name("tracked-color")
        .description("Color for tracked containers.")
        .defaultValue(new SettingColor(255, 255, 0, 75))
        .build()
    );

    private final Setting<SettingColor> searchColor = sgRender.add(new ColorSetting.Builder()
        .name("search-color")
        .description("Color for search results.")
        .defaultValue(new SettingColor(0, 255, 0, 100))
        .build()
    );

    private final Setting<SettingColor> searchLineColor = sgRender.add(new ColorSetting.Builder()
        .name("search-line-color")
        .description("Line color for search results.")
        .defaultValue(new SettingColor(0, 255, 0, 255))
        .build()
    );

    private final Setting<Boolean> tracer = sgRender.add(new BoolSetting.Builder()
        .name("tracer")
        .description("Draw a tracer from your view to the highlighted container.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> tracerColor = sgRender.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("Color of the highlight tracer.")
        .defaultValue(new SettingColor(255, 215, 0, 255))
        .visible(() -> this.tracer.get())
        .build()
    );

    private final Setting<Integer> highlightDuration = sgRender.add(new IntSetting.Builder()
        .name("highlight-duration")
        .description("Seconds the Find highlight (tracer and box) stays lit after a search.")
        .defaultValue(30)
        .min(1)
        .max(600)
        .sliderRange(5, 120)
        .build()
    );

    private final Setting<Integer> searchHighlightDuration = sgRender.add(new IntSetting.Builder()
        .name("search-duration")
        .description("Seconds the search-result highlight (box and tracer) stays lit after a search.")
        .defaultValue(30)
        .min(1)
        .max(600)
        .sliderRange(5, 120)
        .visible(() -> this.renderSearchResults.get())
        .build()
    );

    private final Setting<Boolean> renderLabels = sgLabels.add(new BoolSetting.Builder()
        .name("render-labels")
        .description("Render the searched item icon on containers that hold it.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> labelScale = sgLabels.add(new DoubleSetting.Builder()
        .name("icon-scale")
        .description("Item icon scale for render labels.")
        .defaultValue(1.0)
        .min(0.5)
        .max(3.0)
        .sliderRange(0.5, 3.0)
        .build()
    );

    private final Setting<Integer> labelMaxDistance = sgLabels.add(new IntSetting.Builder()
        .name("icon-max-distance")
        .description("Max distance for item icons.")
        .defaultValue(64)
        .min(8)
        .max(512)
        .sliderRange(8, 512)
        .build()
    );

    private final Setting<Boolean> trackChests = sgFilter.add(new BoolSetting.Builder()
        .name("track-chests")
        .description("Track chests and trapped chests.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> trackBarrels = sgFilter.add(new BoolSetting.Builder()
        .name("track-barrels")
        .description("Track barrels.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> trackShulkers = sgFilter.add(new BoolSetting.Builder()
        .name("track-shulkers")
        .description("Track shulker boxes.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> trackEnderChests = sgFilter.add(new BoolSetting.Builder()
        .name("track-ender-chests")
        .description("Track ender chests.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> trackHoppers = sgFilter.add(new BoolSetting.Builder()
        .name("track-hoppers")
        .description("Track hoppers.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> trackDispensers = sgFilter.add(new BoolSetting.Builder()
        .name("track-dispensers")
        .description("Track dispensers and droppers.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> trackCopperChests = sgFilter.add(new BoolSetting.Builder()
        .name("track-copper-chests")
        .description("Track copper chests (all weathering stages).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showOnScreen = sgDisplay.add(new BoolSetting.Builder()
        .name("show-on-screen")
        .description("Show a brief on-screen message when containers are tracked or saved.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> onScreenDuration = sgDisplay.add(new IntSetting.Builder()
        .name("on-screen-duration")
        .description("How long (in ticks) the on-screen status message stays visible.")
        .defaultValue(40)
        .min(10)
        .max(200)
        .sliderRange(10, 200)
        .visible(showOnScreen::get)
        .build()
    );

    private final Setting<Boolean> groupNearby = sgDisplay.add(new BoolSetting.Builder()
        .name("group-nearby")
        .description("Group nearby containers into one row in the browser list.")
        .defaultValue(true)
        .build()
    );

    private final Setting<GroupMode> groupMode = sgDisplay.add(new EnumSetting.Builder<GroupMode>()
        .name("group-mode")
        .description("How containers are grouped: by 16-block chunk, or by actual distance.")
        .defaultValue(GroupMode.Chunk)
        .build()
    );

    private final Setting<Boolean> debugMode = sgAdvanced.add(new BoolSetting.Builder()
        .name("debug")
        .description("Show debug messages in chat.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> trackInterval = sgBank.add(new IntSetting.Builder()
        .name("track-interval")
        .description("Ticks between position-tracking passes.")
        .defaultValue(20)
        .min(5)
        .max(200)
        .sliderRange(5, 200)
        .build()
    );

    private final Setting<Integer> saveDebounce = sgBank.add(new IntSetting.Builder()
        .name("save-debounce")
        .description("Ticks of quiet after a change before the bank is written.")
        .defaultValue(100)
        .min(20)
        .max(1200)
        .sliderRange(20, 1200)
        .build()
    );

    private final Setting<Integer> staleDays = sgBank.add(new IntSetting.Builder()
        .name("stale-days")
        .description("Days played without opening a container before it counts as stale.")
        .defaultValue(7)
        .min(1)
        .max(365)
        .sliderRange(1, 60)
        .build()
    );

    private final Setting<Integer> trashDays = sgBank.add(new IntSetting.Builder()
        .name("trash-days")
        .description("Days a destroyed container stays restorable in the trash.")
        .defaultValue(7)
        .min(1)
        .max(365)
        .sliderRange(1, 60)
        .build()
    );

    private final Setting<Boolean> autoTrashGoneBlocks = sgBank.add(new BoolSetting.Builder()
        .name("auto-trash-gone-blocks")
        .description("Move a destroyed block to the trash automatically when gone.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> backupsToKeep = sgBank.add(new IntSetting.Builder()
        .name("backups-to-keep")
        .description("Daily bank backups to keep per server.")
        .defaultValue(7)
        .min(1)
        .max(60)
        .sliderRange(1, 30)
        .build()
    );

    private final Setting<Integer> homeRadius = sgBank.add(new IntSetting.Builder()
        .name("home-radius")
        .description("Radius in chunks treated as an entity's home area.")
        .defaultValue(1)
        .min(0)
        .max(8)
        .sliderRange(0, 4)
        .build()
    );

    private final Setting<Boolean> showSaveStatus = sgBank.add(new BoolSetting.Builder()
        .name("show-save-status")
        .description("Chat notice when the bank is compacted or a save fails.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> trackTrades = sgTrades.add(new BoolSetting.Builder()
        .name("track-trades")
        .description("Remember villager and wandering trader offers.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> tradePreviewTicks = sgTrades.add(new IntSetting.Builder()
        .name("trade-preview-ticks")
        .description("Ticks each page of a villager's offer preview shows.")
        .defaultValue(35)
        .min(10)
        .max(200)
        .sliderRange(10, 120)
        .visible(trackTrades::get)
        .build()
    );

    private final Setting<Double> searchBudgetMs = sgSearch.add(new DoubleSetting.Builder()
        .name("search-budget-ms")
        .description("Time the browser search may take per frame, in milliseconds.")
        .defaultValue(4.0)
        .min(0.5)
        .max(16.0)
        .sliderRange(0.5, 16.0)
        .decimalPlaces(1)
        .build()
    );

    private final TrackerConfig config = new TrackerConfig();
    private BankStorage storage;
    private BankManager banks;
    private InteractionTracker interactions;
    private PositionTracker positions;
    private DisplayTracker displays;
    private ScreenBinder screens;
    private TradeBinder trades;
    private Item currentSearchItem;
    private final Set<String> searchMatches = new HashSet<>();
    private int searchMatchRevision = -1;
    private boolean searchIndexReady = true;

    private boolean autoOpenPending = false;
    private int autoOpenPendingTicks = 0;
    /**
     * The container an auto-open attempt is waiting on, so a timeout can block it.
     */
    private BlockPos autoOpenPendingPos;
    private boolean shouldAutoClose = false;
    private int ticksUntilClose = 0;
    /**
     * The container menu the armed auto-close belongs to; only that screen may be closed.
     */
    private int autoCloseMenuId = -1;
    private int tickCounter = 0;
    private final Map<BlockPos, Integer> blockedContainers = new HashMap<>();
    private static final int BLOCKED_COOLDOWN_TICKS = 100;
    private static final int AUTO_OPEN_TIMEOUT_TICKS = 60;

    private List<ContainerRecord> renderCache = new ArrayList<>();
    private long lastRenderCacheUpdate = 0;
    /**
     * Chunk-keyed index of {@link #renderCache} for O(visible chunks) render iteration.
     */
    private final Map<Long, List<ContainerRecord>> renderChunkIndex = new HashMap<>();
    /**
     * The box of each rendered record, so the render path does not allocate one per record per
     * frame. Keyed by identity: the record's own key is a string that would have to be built
     * for every lookup.
     */
    private final Map<ContainerRecord, AABB> recordBoxes = new IdentityHashMap<>();

    // In-world highlight set by DetailScreen's "Done" button.
    private ContainerRecord highlightedRecord;
    private long highlightedUntil;
    /**
     * Time at which the green search-result highlight stops rendering.
     */
    private long searchHighlightUntil;

    // On-screen status message state (tracked/saved feedback), rendered in onRender2D().
    private String displayText = "";
    private int displayTimer = 0;

    public ChestTrackerModule() {
        super(Addon.CATEGORY, "Chest-Tracker", "Track items in containers.");
    }

    @Override
    public void onActivate() {
        syncConfig();
        if (storage == null) storage = new BankStorage(TrackerServices.root(), config);
        if (banks == null) {
            banks = new BankManager(storage, config);
            interactions = new InteractionTracker();
            positions = new PositionTracker(banks, interactions);
            displays = new DisplayTracker(banks, positions, interactions);
            screens = new ScreenBinder(interactions, banks, positions, this::canBindContainer);
            trades = new TradeBinder(interactions, banks, positions);
            positions.register();
            screens.register();
            trades.register();
        }
        TrackerServices.init(storage.root(), banks, interactions);
        ItemQuery.loadAliases(storage.root());
        resetState();
    }

    @Override
    public void onDeactivate() {
        if (banks != null) banks.close(); // save in background; the final wait happens in onClientStopping
        TrackerServices.clear();
        // The cached boxes hold the records, so they go with the module.
        recordBoxes.clear();
        renderChunkIndex.clear();
        renderCache = new ArrayList<>();
    }

    /**
     * Copies the Meteor settings into the plain snapshot the data and tracking classes read.
     */
    private void syncConfig() {
        config.trackIntervalTicks = trackInterval.get();
        config.saveDebounceTicks = saveDebounce.get();
        config.staleDays = staleDays.get();
        config.trashDays = trashDays.get();
        config.autoTrashGoneBlocks = autoTrashGoneBlocks.get();
        config.showDestroyed = showDestroyed.get();
        config.backupsToKeep = backupsToKeep.get();
        config.homeRadiusChunks = homeRadius.get();
        config.showSaveStatus = showSaveStatus.get();
        config.trackTrades = trackTrades.get();
        config.tradePreviewTicks = tradePreviewTicks.get();
        config.searchBudgetNanos = (long) (searchBudgetMs.get() * 1_000_000L);
        Settings.bindTimeoutTicks = 60;
        Settings.bindTimeoutMaxTicks = 400;
        Settings.homeRadiusChunks = homeRadius.get();
        Settings.destroyDistance = 16.0;
        Settings.dropWindowTicks = 20;
        Settings.trackIntervalTicks = trackInterval.get();
        Settings.saveDebounceTicks = saveDebounce.get();
        Settings.staleDays = staleDays.get();
        Settings.missingCheckRange = 48.0;
        Settings.displayMissingCheckRange = 24.0;
        Settings.dropRadius = 2.5;
        Settings.trashDays = trashDays.get();
        Settings.autoTrashGoneBlocks = autoTrashGoneBlocks.get();
        Settings.showDestroyed = showDestroyed.get();
        Settings.trackTrades = trackTrades.get();
        Settings.tradePreviewTicks = tradePreviewTicks.get();
        Settings.indexBudgetNanos = 1_000_000L;
        Settings.searchBudgetNanos = (long) (searchBudgetMs.get() * 1_000_000L);
        Settings.backupsToKeep = backupsToKeep.get();
        Settings.showSaveStatus = showSaveStatus.get();
    }

    public BankManager getBankManager() {
        return banks;
    }

    public BankStorage getStorage() {
        return storage;
    }

    public MemoryBank getBank() {
        return banks == null ? null : banks.bank();
    }

    public TradeBinder getTrades() {
        return trades;
    }

    public InteractionTracker getInteractions() {
        return interactions;
    }

    /**
     * Called from the client-stopping hook so the last bank write is on disk before exit.
     */
    public void onClientStopping() {
        if (banks != null) banks.closeAndWait();
    }

    private void resetState() {
        currentSearchItem = null;
        searchMatches.clear();
        searchMatchRevision = -1;
        searchIndexReady = true;
        searchHighlightUntil = 0L;
        highlightedRecord = null;
        highlightedUntil = 0L;
        autoOpenPending = false;
        autoOpenPendingTicks = 0;
        autoOpenPendingPos = null;
        shouldAutoClose = false;
        ticksUntilClose = 0;
        tickCounter = 0;
        renderCache = new ArrayList<>();
        renderChunkIndex.clear();
        recordBoxes.clear();
        lastRenderCacheUpdate = 0;
        blockedContainers.clear();
        displayText = "";
        displayTimer = 0;
    }

    private BlockPos getCanonicalChestPos(BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        if (!(block instanceof ChestBlock || block instanceof TrappedChestBlock)) return pos;
        if (!state.hasProperty(ChestBlock.TYPE)) return pos;
        ChestType chestType = state.getValue(ChestBlock.TYPE);
        if (chestType == ChestType.SINGLE) return pos;
        if (!state.hasProperty(ChestBlock.FACING)) return pos;

        Direction facing = state.getValue(ChestBlock.FACING);
        BlockPos other = pos.relative(
            chestType == ChestType.LEFT ? facing.getClockWise() : facing.getCounterClockWise()
        );

        int cmp = Integer.compare(pos.getX(), other.getX());
        if (cmp == 0) cmp = Integer.compare(pos.getY(), other.getY());
        if (cmp == 0) cmp = Integer.compare(pos.getZ(), other.getZ());
        return cmp <= 0 ? pos : other;
    }

    @EventHandler
    private void onInteractBlock(InteractBlockEvent event) {
        if (!isActive() || interactions == null || displays == null) return;
        if (mc.player == null || mc.level == null) return;
        // The result is null when the interaction had no block hit.
        if (event.result == null) return;
        interactions.setBlock(event.result.getBlockPos());
        displays.onInteractBlock(mc.player, event.hand, event.result);
    }

    @EventHandler
    private void onInteractEntity(InteractEntityEvent event) {
        if (!isActive() || interactions == null || displays == null) return;
        if (mc.player == null || mc.level == null) return;
        interactions.set(event.entity);
        displays.onInteractEntity(event.entity);
    }

    @EventHandler
    private void onInventorySync(InventoryEvent event) {
        if (!isActive() || screens == null) return;
        screens.onInventorySync(event.packet.containerId());
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;
        syncConfig();
        if (banks != null) banks.tick(mc);
        if (interactions != null) interactions.tick();
        if (positions != null) positions.tick(mc);
        if (displays != null) displays.tick(mc);
        tickSearchIndex();
        boolean inContainerScreen = isInContainerScreen();

        // Count down the on-screen status message.
        if (displayTimer > 0 && !inContainerScreen) displayTimer--;

        if (!blockedContainers.isEmpty()) {
            Iterator<Map.Entry<BlockPos, Integer>> it = blockedContainers.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<BlockPos, Integer> entry = it.next();
                int ticksRemaining = entry.getValue() - 1;
                if (ticksRemaining <= 0) it.remove();
                else entry.setValue(ticksRemaining);
            }
        }

        // The container we clicked has appeared: start the close countdown. The binder snapshots
        // the contents on the inventory packet and again when the screen closes.
        if (autoOpenPending) {
            if (inContainerScreen) {
                // Only arm the delayed close for the screen that actually appeared for this
                // pending attempt. If a close is already armed (a previous auto-open's screen),
                // treat this as an unrelated manual interaction and cancel the pending attempt
                // instead of stealing its close timer.
                if (shouldAutoClose) {
                    autoOpenPending = false;
                    autoOpenPendingTicks = 0;
                    autoOpenPendingPos = null;
                } else if (screens != null && autoOpenPendingPos != null && screens.currentScreenMatchesBlock(autoOpenPendingPos)) {
                    autoOpenPending = false;
                    autoOpenPendingTicks = 0;
                    autoOpenPendingPos = null;
                    int closeDelay = autoOpenCloseDelay.get();
                    if (closeDelay == 0) mc.player.closeContainer();
                    else {
                        shouldAutoClose = true;
                        ticksUntilClose = closeDelay;
                        // The close belongs to the screen this auto-open opened, so it is only
                        // allowed to close that menu.
                        autoCloseMenuId = mc.player.containerMenu.containerId;
                    }
                } else {
                    // The screen that opened did not belong to the pending auto-open (the player
                    // opened a different container in the meantime): drop the pending attempt and
                    // leave that screen unclaimed instead of recording its menu for delayed closing.
                    autoOpenPending = false;
                    autoOpenPendingTicks = 0;
                    autoOpenPendingPos = null;
                }
            } else if (++autoOpenPendingTicks > AUTO_OPEN_TIMEOUT_TICKS) {
                // No screen appeared: block this container so the scan doesn't retry it
                // immediately on the next pass.
                if (autoOpenPendingPos != null) blockedContainers.put(autoOpenPendingPos, BLOCKED_COOLDOWN_TICKS);
                autoOpenPending = false;
                autoOpenPendingTicks = 0;
                autoOpenPendingPos = null;
            }
        }

        if (shouldAutoClose) {
            if (!inContainerScreen) {
                shouldAutoClose = false;
                ticksUntilClose = 0;
            } else if (ticksUntilClose > 0) {
                ticksUntilClose--;
                if (ticksUntilClose == 0) {
                    shouldAutoClose = false;
                    // The armed close only ever closes the screen the pending auto-open opened;
                    // if a different screen is open now, it is the player's own doing and the
                    // auto-close gives up instead of closing it out from under them.
                    if (mc.player.containerMenu.containerId == autoCloseMenuId) {
                        mc.player.closeContainer();
                        if (debugMode.get()) info("Auto-closed container after delay");
                    }
                }
            }
        }

        if (inContainerScreen) return;
        if (autoOpenPending) return;
        if (!autoOpenEnabled.get()) return;

        if (tickCounter < autoOpenDelay.get()) {
            tickCounter++;
            return;
        }
        tickCounter = 0;

        MemoryBank bank = getBank();
        if (bank == null) return;

        int range = (int) Math.ceil(autoOpenRange.get());
        double maxDistSq = autoOpenRange.get() * autoOpenRange.get();
        BlockPos playerPos = mc.player.blockPosition();
        String currentDim = getCurrentDimension();
        for (int x = -range; x <= range; x++) {
            for (int y = -range; y <= range; y++) {
                for (int z = -range; z <= range; z++) {
                    BlockPos blockPos = playerPos.offset(x, y, z);
                    double distSq = mc.player.distanceToSqr(blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5);
                    if (distSq > maxDistSq) continue;
                    BlockState blockState = mc.level.getBlockState(blockPos);
                    Block block = blockState.getBlock();
                    if (!isTrackableContainer(block)) continue;
                    if (blockedContainers.containsKey(blockPos)) continue;
                    if (bank.getBlockAt(currentDim, blockPos) != null) continue;

                    Vec3 vec = new Vec3(blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5);
                    BlockHitResult hitResult = new BlockHitResult(vec, Direction.UP, blockPos, false);
                    InteractionResult result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hitResult);
                    if (result == InteractionResult.SUCCESS || result == InteractionResult.CONSUME) {
                        blockedContainers.remove(blockPos);
                        autoOpenPending = true;
                        autoOpenPendingTicks = 0;
                        autoOpenPendingPos = blockPos.immutable();
                        mc.player.swing(InteractionHand.MAIN_HAND);
                        if (debugMode.get()) info("Auto-opening container at " + blockPos.toShortString());
                        return;
                    } else if (result == InteractionResult.FAIL) {
                        blockedContainers.put(blockPos.immutable(), BLOCKED_COOLDOWN_TICKS);
                    }
                }
            }
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (banks != null) banks.close();
        renderCache = new ArrayList<>();
        renderChunkIndex.clear();
        lastRenderCacheUpdate = 0;
        highlightedRecord = null;
        highlightedUntil = 0L;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (highlightedRecord != null && (System.currentTimeMillis() > highlightedUntil
            || !getCurrentDimension().equals(highlightedRecord.dimension))) {
            highlightedRecord = null;
        }
        boolean highlightActive = highlightedRecord != null;
        boolean searchHighlightActive = searchHighlightActive();

        // Refresh ahead of the early return below: onRender2D (search labels) also reads
        // renderChunkIndex, so it must stay current even when tracked rendering is off.
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastRenderCacheUpdate > 1000) {
            MemoryBank bank = getBank();
            if (bank == null) {
                renderCache = List.of();
                renderChunkIndex.clear();
                recordBoxes.clear();
            } else {
                String currentDim = getCurrentDimension();
                renderCache = bank.containers()
                    .filter(r -> !r.isTrashed())
                    .filter(r -> !(r instanceof BlockRecord b && b.global))
                    .filter(r -> currentDim.equals(r.dimension))
                    .toList();
                // Rebuild the chunk index so the render path only touches visible chunks.
                renderChunkIndex.clear();
                // The boxes are cached per record and the map holds the records, so it is
                // dropped with the index: a record that left the bank (trashed, deleted,
                // cleared) must not stay reachable through its box.
                recordBoxes.clear();
                for (ContainerRecord r : renderCache) {
                    long key = chunkKey((int) Math.floor(r.x) >> 4, (int) Math.floor(r.z) >> 4);
                    renderChunkIndex.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
                }
            }
            lastRenderCacheUpdate = currentTime;
        }

        if (!renderTracked.get() && !renderSearchResults.get() && !highlightActive) return;

        double maxDist = renderDistance.get();
        double maxDistSq = maxDist * maxDist;
        int chunkRadius = (int) Math.ceil(maxDist / 16.0) + 1;

        int playerChunkX = mc.player.blockPosition().getX() >> 4;
        int playerChunkZ = mc.player.blockPosition().getZ() >> 4;

        // Frustum (best-effort; fall back to chunk-distance only if unavailable).
        net.minecraft.client.renderer.culling.Frustum frustum = null;
        try {
            frustum = mc.gameRenderer.mainCamera().getCullFrustum();
        } catch (Throwable ignored) {
            frustum = null;
        }

        for (Map.Entry<Long, List<ContainerRecord>> entry : renderChunkIndex.entrySet()) {
            int ecx = (int) (entry.getKey() >> 32);
            int ecz = (int) (entry.getKey() & 0xFFFFFFFFL);
            if (Math.abs(ecx - playerChunkX) > chunkRadius || Math.abs(ecz - playerChunkZ) > chunkRadius) continue;
            {
                List<ContainerRecord> chunkContainers = entry.getValue();
                for (ContainerRecord container : chunkContainers) {
                    double distSq = mc.player.distanceToSqr(container.x, container.y, container.z);
                    if (distSq > maxDistSq) continue;

                    // The box of a record, cached: the render path runs every frame and the
                    // cull box was a fresh allocation for every visible container every
                    // frame. The cache is checked against the record's position, so a record
                    // that moved gets a new box instead of a stale one.
                    AABB cbox = recordBox(container);

                    // Frustum cull: skip containers whose box is off-screen.
                    if (frustum != null) {
                        try {
                            if (!frustum.isVisible(cbox)) continue;
                        } catch (Throwable ignored) {
                        }
                    }

                    boolean isSearchResult = searchHighlightActive && searchMatches.contains(container.key());
                    boolean shouldRender = false;
                    SettingColor sideCol = null;
                    SettingColor lineCol = null;

                    if (isSearchResult && renderSearchResults.get()) {
                        shouldRender = true;
                        sideCol = searchColor.get();
                        lineCol = searchLineColor.get();
                    } else if (renderTracked.get()) {
                        shouldRender = true;
                        sideCol = trackedColor.get();
                        lineCol = trackedColor.get();
                    }

                    if (!shouldRender) continue;

                    if (container instanceof BlockRecord block) {
                        event.renderer.box(block.pos, sideCol, lineCol, shapeMode.get(), 0);
                        for (BlockPos other : block.otherPositions) {
                            event.renderer.box(other, sideCol, lineCol, shapeMode.get(), 0);
                        }
                    } else {
                        event.renderer.box(cbox, sideCol, lineCol, shapeMode.get(), 0);
                    }

                    if (isSearchResult && renderSearchResults.get() && tracer.get()) {
                        // Green tracer from the view to the searched container, mirroring the
                        // Find-container tracer. Same endpoint math as the Find highlight.
                        double tracerX;
                        double tracerY;
                        double tracerZ;
                        if (container instanceof BlockRecord block) {
                            Vec3 pos = Vec3.atCenterOf(block.pos);
                            tracerX = pos.x;
                            tracerY = pos.y;
                            tracerZ = pos.z;
                        } else {
                            tracerX = container.x;
                            tracerY = container.y + 0.5;
                            tracerZ = container.z;
                        }
                        event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
                            tracerX, tracerY, tracerZ, searchLineColor.get());
                    }
                }
            }
        }

        // Highlighted container (set by the Find buttons): draw it prominently.
        if (highlightedRecord != null) {
            SettingColor col = new SettingColor(255, 215, 0, 255);
            double centerX = highlightedRecord.x;
            double centerY = highlightedRecord.y + 0.5;
            double centerZ = highlightedRecord.z;
            if (highlightedRecord instanceof BlockRecord block) {
                event.renderer.box(block.pos, col, col, shapeMode.get(), 0);
                for (BlockPos other : block.otherPositions) {
                    event.renderer.box(other, col, col, shapeMode.get(), 0);
                }
                Vec3 pos = Vec3.atCenterOf(block.pos);
                centerX = pos.x;
                centerY = pos.y;
                centerZ = pos.z;
            } else {
                event.renderer.box(
                    new AABB(highlightedRecord.x - 0.5, highlightedRecord.y, highlightedRecord.z - 0.5,
                        highlightedRecord.x + 0.5, highlightedRecord.y + 1.0, highlightedRecord.z + 0.5),
                    col, col, shapeMode.get(), 0);
            }
            if (tracer.get()) {
                event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
                        centerX, centerY, centerZ, tracerColor.get());
            }
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.player == null || mc.level == null) return;

        if (displayTimer > 0 && showOnScreen.get() && !displayText.isEmpty()) {
            GuiGraphicsExtractor statusContext = event.graphics;
            int screenWidth = mc.getWindow().getGuiScaledWidth();
            statusContext.text(mc.font, displayText, screenWidth / 2 - mc.font.width(displayText) / 2, 4, 0xFFFFFFFF, false);
        }

        if (showOnScreen.get() && searchHighlightActive()) {
            long remaining = Math.max(0, (searchHighlightUntil - System.currentTimeMillis() + 999) / 1000L);
            String timer = "§aSearch highlight: " + remaining + "s";
            int screenWidth = mc.getWindow().getGuiScaledWidth();
            event.graphics.text(mc.font, timer, screenWidth / 2 - mc.font.width(timer) / 2, 16, 0xFFFFFFFF, false);
        }

        if (!renderLabels.get()) return;
        if (!searchHighlightActive()) return;

        GuiGraphicsExtractor context = event.graphics;
        double maxDist = labelMaxDistance.get();
        double maxDistSq = maxDist * maxDist;
        Vector3d tempVec = new Vector3d();
        ItemStack icon = new ItemStack(currentSearchItem);

        int chunkRadius = (int) Math.ceil(maxDist / 16.0) + 1;
        int playerChunkX = mc.player.blockPosition().getX() >> 4;
        int playerChunkZ = mc.player.blockPosition().getZ() >> 4;

        for (Map.Entry<Long, List<ContainerRecord>> entry : renderChunkIndex.entrySet()) {
            int ecx = (int) (entry.getKey() >> 32);
            int ecz = (int) (entry.getKey() & 0xFFFFFFFFL);
            if (Math.abs(ecx - playerChunkX) > chunkRadius || Math.abs(ecz - playerChunkZ) > chunkRadius) continue;
            {
                List<ContainerRecord> chunkContainers = entry.getValue();
                for (ContainerRecord container : chunkContainers) {
                    if (!searchMatches.contains(container.key())) continue;
                    double distSq = mc.player.distanceToSqr(container.x, container.y, container.z);
                    if (distSq > maxDistSq) continue;

                    tempVec.set(container.x, container.y, container.z);
                    if (!NametagUtils.to2D(tempVec, 1.0)) continue;

                    // NametagUtils.to2D hands back window coordinates in world units; convert to
                    // GUI-scaled screen pixels before drawing, the same conversion the working
                    // container-panel drawers (ContainerPeek, InventoryTracker) apply.
                    double guiScaleX = (double) mc.getWindow().getGuiScaledWidth() / mc.getWindow().getWidth();
                    double guiScaleY = (double) mc.getWindow().getGuiScaledHeight() / mc.getWindow().getHeight();
                    int screenX = (int) (tempVec.x * guiScaleX);
                    int screenY = (int) (tempVec.y * guiScaleY);
                    int itemSize = (int) (16 * labelScale.get());
                    int renderX = screenX - itemSize / 2;
                    int renderY = screenY - itemSize / 2;

                    var matrices = context.pose();
                    matrices.pushMatrix();
                    if (itemSize != 16) {
                        float scale = itemSize / 16.0f;
                        matrices.translate(renderX + itemSize / 2.0f, renderY + itemSize / 2.0f);
                        matrices.scale(scale, scale);
                        matrices.translate(-8.0f, -8.0f);
                        context.item(icon, 0, 0);
                    } else {
                        context.item(icon, renderX, renderY);
                    }
                    matrices.popMatrix();
                }
            }
        }
    }

    /**
     * Rebuilds the set of containers holding the searched item, a slice per tick so a big bank
     * never stalls a frame. {@link ItemIndex} caches per snapshot, so only changed records decode.
     */
    private void tickSearchIndex() {
        MemoryBank bank = getBank();
        if (bank == null) {
            searchMatches.clear();
            searchMatchRevision = -1;
            searchIndexReady = true;
            return;
        }
        if (currentSearchItem == null) {
            // Warm the search index in the background while the bank is open, so the first
            // search finds candidates immediately instead of full-scanning a cold index.
            // Cached records are skipped, so this costs nothing once everything is indexed.
            searchMatches.clear();
            searchMatchRevision = bank.revision();
            searchIndexReady = true;
            indexWarm(bank);
            return;
        }
        if (searchMatchRevision != bank.revision()) {
            searchMatches.clear();
            searchMatchRevision = bank.revision();
            searchIndexReady = false;
        }
        if (searchIndexReady) return;

        List<ContainerRecord> records = bank.containers().toList();
        ItemIndex index = TrackerServices.index();
        if (index.warm(records.stream(), Settings.indexBudgetNanos) > 0) return;

        // Warming indexed every record, so the inverted item index now answers directly.
        searchMatches.addAll(index.recordsForItem(currentSearchItem));
        searchIndexReady = true;
    }

    private void indexWarm(MemoryBank bank) {
        TrackerServices.index().warm(bank.containers(), Settings.indexBudgetNanos);
    }

    private boolean isTrackableContainer(Block block) {
        // CopperChestBlock extends ChestBlock on 26.2, so this test has to come first or every
        // weathering stage of a copper chest gets folded into the regular-chest filter.
        if (block instanceof CopperChestBlock) return trackCopperChests.get();
        if (block instanceof ChestBlock || block instanceof TrappedChestBlock) return trackChests.get();
        if (block instanceof BarrelBlock) return trackBarrels.get();
        if (block instanceof ShulkerBoxBlock) return trackShulkers.get();
        if (block instanceof EnderChestBlock) return trackEnderChests.get();
        if (block instanceof HopperBlock) return trackHoppers.get();
        if (block instanceof DispenserBlock || block instanceof DropperBlock) return trackDispensers.get();
        return false;
    }

    /**
     * What the binder may record. Wider than the auto-open list on purpose: the ported pipeline
     * can bind furnaces, brewing stands and lecterns too, and only the named families have a
     * setting that can turn them off.
     */
    private boolean canBindContainer(Block block) {
        if (block instanceof CopperChestBlock) return trackCopperChests.get();
        if (block instanceof ChestBlock || block instanceof TrappedChestBlock) return trackChests.get();
        if (block instanceof BarrelBlock) return trackBarrels.get();
        if (block instanceof ShulkerBoxBlock) return trackShulkers.get();
        if (block instanceof EnderChestBlock) return trackEnderChests.get();
        if (block instanceof HopperBlock) return trackHoppers.get();
        if (block instanceof DispenserBlock || block instanceof DropperBlock) return trackDispensers.get();
        return true;
    }

    private boolean isInContainerScreen() {
        if (mc.gui.screen() == null) return false;
        if (mc.player == null) return false;
        return mc.player.containerMenu != mc.player.inventoryMenu;
    }

    public String getCurrentDimension() {
        if (mc.level == null) return "unknown";
        return mc.level.dimension().identifier().toString();
    }

    /**
     * Packs a chunk coordinate pair into a single map key.
     */
    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /**
     * The box of a record, cached so the render path does not allocate one per record per frame.
     * The cached box is checked against the record's position, so a record that moved gets a
     * fresh box instead of a stale one.
     */
    private AABB recordBox(ContainerRecord container) {
        AABB cached = recordBoxes.get(container);
        if (cached != null && sameSpot(cached, container)) return cached;

        AABB box = (container instanceof BlockRecord block)
            ? new AABB(block.pos.getX(), block.pos.getY(), block.pos.getZ(),
            block.pos.getX() + 1, block.pos.getY() + 1, block.pos.getZ() + 1)
            : new AABB(container.x - 0.5, container.y, container.z - 0.5,
            container.x + 0.5, container.y + 1.0, container.z + 0.5);
        recordBoxes.put(container, box);
        return box;
    }

    /**
     * Whether the cached box still sits where the record does.
     */
    private static boolean sameSpot(AABB box, ContainerRecord container) {
        if (container instanceof BlockRecord block) {
            return box.minX == block.pos.getX() && box.minY == block.pos.getY() && box.minZ == block.pos.getZ();
        }
        return box.minX == container.x - 0.5 && box.minY == container.y && box.minZ == container.z - 0.5;
    }

    /**
     * Distance beyond which a tracked container is not rendered; used by ChestTrackerCommand
     * to colour "nearby" output the same way the boxes are coloured in-world.
     */
    public double getRenderDistance() {
        return renderDistance.get();
    }

    /**
     * Whether the browser list shows likely-destroyed containers.
     */
    public boolean showDestroyed() {
        return showDestroyed.get();
    }

    public void setShowDestroyed(boolean value) {
        showDestroyed.set(value);
    }

    /**
     * Highlights the given container in-world for a short time so the player can find it.
     */
    public void highlightContainer(ContainerRecord record) {
        highlightedRecord = record;
        highlightedUntil = System.currentTimeMillis() + highlightDuration.get() * 1000L;
        setDisplayText("§eHighlighted " + (record.name != null ? record.name : record.type));
    }

    /**
     * Whether the green search-result highlight is still lit.
     */
    private boolean searchHighlightActive() {
        return currentSearchItem != null && System.currentTimeMillis() <= searchHighlightUntil;
    }

    /**
     * Whether nearby containers are grouped into one row in the browser list.
     */
    public boolean groupNearby() {
        return groupNearby.get();
    }

    public void setGroupNearby(boolean value) {
        groupNearby.set(value);
    }

    /**
     * How containers are grouped in the browser list's browse mode.
     */
    public GroupMode groupMode() {
        return groupMode.get();
    }

    public void setGroupMode(GroupMode value) {
        groupMode.set(value);
    }

    public void searchItem(Item item) {
        currentSearchItem = item;
        searchMatchRevision = -1;
        searchIndexReady = false;
        searchHighlightUntil = item != null ? System.currentTimeMillis() + searchHighlightDuration.get() * 1000L : 0L;
        tickSearchIndex();
    }

    /**
     * Pushes a short-lived message to the on-screen status line (top-center, next to the item
     * labels). Respects the "show-on-screen" setting so it is a no-op when the player turned the
     * display off.
     */
    private void setDisplayText(String text) {
        if (showOnScreen.get()) {
            this.displayText = text;
            this.displayTimer = onScreenDuration.get();
            if (mc.player != null) mc.player.sendOverlayMessage(Component.literal(text));
        }
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WTable table = theme.table();

        WButton openBrowser = table.add(theme.button("Open Browser (" + browserKey.get() + ")")).expandX().widget();
        openBrowser.action = () -> mc.gui.setScreen(new TrackerScreen());
        table.row();

        WButton searchHeld = table.add(theme.button("Search Held Item")).expandX().widget();
        searchHeld.action = this::searchHeldItem;
        table.row();

        WButton clearSearch = table.add(theme.button("Clear Search")).expandX().widget();
        clearSearch.action = () -> {
            currentSearchItem = null;
            searchHighlightUntil = 0L;
            searchMatches.clear();
            searchIndexReady = true;
            if (debugMode.get()) info("Search cleared");
        };
        table.row();

        WButton saveData = table.add(theme.button("Save Data")).expandX().widget();
        saveData.action = () -> {
            MemoryBank bank = getBank();
            if (bank == null) {
                setDisplayText("§cNo bank loaded");
                return;
            }
            if (banks != null) banks.saveNow();
            setDisplayText("§aSaved " + bank.size() + " containers");
            if (debugMode.get()) info("Manually saved " + bank.size() + " containers");
        };
        table.row();

        WButton clearAll = table.add(theme.button("Clear All Data")).expandX().widget();
        clearAll.action = () -> {
            MemoryBank bank = getBank();
            if (bank == null) return;
            for (ContainerRecord record : bank.allRecords().toList()) bank.remove(record);
            if (debugMode.get()) info("All data cleared");
        };

        return table;
    }

    private void searchHeldItem() {
        if (mc.player == null) return;
        ItemStack held = mc.player.getMainHandItem();
        if (held.isEmpty()) {
            if (debugMode.get()) warning("No item in hand");
            return;
        }
        searchItem(held.getItem());
        if (debugMode.get()) {
            info("Searching for " + held.getHoverName().getString() + " in " + searchMatches.size() + " container(s)");
        }
    }
}
