/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.utils.ScreenUtil;
import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.events.ScreenRenderEvent;
import com.AutoBookshelf.addon.mixin.accessor.AbstractContainerScreenAccessor;
import com.AutoBookshelf.addon.modules.MapartNamer.CoordPosition;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.maps.MapId;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sorts filled map art into its grid frame inside an open container (chest, double chest, ...).
 *
 * <p>The hovered named map anchors the group; if no map is hovered, the first parseable map does.
 * A manual sort started while hovering an empty storage slot uses that slot as the frame anchor.
 * The frame size comes from the group's coordinate span when that span accounts for every map;
 * otherwise map-width/map-height are used.
 */
public class MapartSort extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Print debug messages in chat.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> mapWidth = sgGeneral.add(new IntSetting.Builder()
        .name("map-width")
        .description("Columns (X) of the map art, used when auto-size cannot confirm the frame. The frame is laid out on the 9-wide container grid, so 9 is the most that fits.")
        .defaultValue(4)
        .min(1)
        .max(9)
        .sliderRange(1, 9)
        .build()
    );

    private final Setting<Integer> mapHeight = sgGeneral.add(new IntSetting.Builder()
        .name("map-height")
        .description("Rows (Y) of the map art, used when auto-size cannot confirm the frame. At most 10 rows fit: a double chest plus your inventory and hotbar.")
        .defaultValue(5)
        .min(1)
        .max(10)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Boolean> autoSize = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-size")
        .description("Derive the frame size from the maps' coordinates; map-width/map-height are the fallback.")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> coordinateFormat = sgGeneral.add(new StringSetting.Builder()
        .name("coordinate-format")
        .description("Coordinates in the map name ({x}/{y}). MapartNamer writes the names this module parses, so its template is used while it is active.")
        .defaultValue("[{x}, {y}]")
        .build()
    );

    private final Setting<CoordPosition> coordPosition = sgGeneral.add(new EnumSetting.Builder<CoordPosition>()
        .name("coord-position")
        .description("Where the coordinate sits in the map name: prefix (before the name) or suffix (after). MapartNamer's position is used while it is active.")
        .defaultValue(CoordPosition.Suffix)
        .build()
    );

    private final Setting<Boolean> useLetteredNames = sgGeneral.add(new BoolSetting.Builder()
        .name("use-lettered-names")
        .description("Accept lettered positions (A1, AA 12, the TL/MM/BR compass grid, single letters) in addition to numeric coordinates.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder()
        .name("action-delay")
        .description("Ticks between individual slot clicks.")
        .defaultValue(1)
        .min(1)
        .max(6)
        .sliderRange(1, 6)
        .build()
    );

    private final Setting<Keybind> sortKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("sort-key")
        .description("Sort the map art frame in the opened GUI by using this button.")
        .defaultValue(Keybind.none())
        .action(() -> {
            if (ScreenUtil.current() instanceof AbstractContainerScreen) startSort(true);
        })
        .build()
    );

    private final Setting<Boolean> autoSort = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-sort")
        .description("Sort automatically shortly after opening a container with named filled maps.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> autoSortDelay = sgGeneral.add(new IntSetting.Builder()
        .name("auto-sort-delay")
        .description("Ticks to wait after opening a container before automatic sorting.")
        .defaultValue(20)
        .min(1)
        .max(100)
        .sliderRange(1, 60)
        .visible(autoSort::get)
        .build()
    );

    private final Setting<SortArea> sortArea = sgGeneral.add(new EnumSetting.Builder<SortArea>()
        .name("sort-area")
        .description("Which GUI slots the frame may use: container, your inventory, both, or the first map index as the anchor.")
        .defaultValue(SortArea.Both)
        .build()
    );

    private final Setting<Integer> autoSortMinCompleteness = sgGeneral.add(new IntSetting.Builder()
        .name("auto-sort-min-completeness")
        .description("Auto-sort only once the art is this complete: percentage of the frame present.")
        .defaultValue(100)
        .min(0)
        .max(100)
        .sliderRange(0, 100)
        .visible(autoSort::get)
        .build()
    );

    private final Setting<Boolean> showFramePreview = sgGeneral.add(new BoolSetting.Builder()
        .name("show-frame-preview")
        .description("Highlight the frame the sort would use while the GUI is open.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> allowHotbarSpares = sgGeneral.add(new BoolSetting.Builder()
        .name("allow-hotbar-spares")
        .description("Allow hotbar slots to be used as spares and as part of the grid frame.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> frameWrap = sgGeneral.add(new BoolSetting.Builder()
        .name("frame-wrap")
        .description("Split a frame too tall for its section into side-by-side pages inside the section.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> frameWrapSpill = sgGeneral.add(new BoolSetting.Builder()
        .name("frame-wrap-spill")
        .description("Let a frame that cannot split into pages continue into the storage rows below, even outside the sort area.")
        .defaultValue(false)
        .visible(frameWrap::get)
        .build()
    );

    private final Setting<Boolean> strictHoveredGroup = sgGeneral.add(new BoolSetting.Builder()
        .name("strict-hovered-group")
        .description("Only sort the hovered map's group; never fall back to the largest group.")
        .defaultValue(false)
        .build()
    );

    private final Setting<ArtShape> requireArtShape = sgGeneral.add(new EnumSetting.Builder<ArtShape>()
        .name("require-art-shape")
        .description("Sort 2D and compass groups only when their maps fill the frame's rectangle exactly; unrelated names that share a base name are skipped.")
        .defaultValue(ArtShape.Full)
        .visible(autoSize::get)
        .build()
    );


    public MapartSort() {
        super(Addon.CATEGORY2, "Mapart-Sort", "Sorts filled maparts into its grid frame inside container/inventory.");
    }

    private enum State {IDLE, SORTING}

    /**
     * Whether a 2D or compass group must fill its frame exactly before it is sorted.
     */
    public enum ArtShape {Full, Off}

    private static final int STEP_PICKUP_SOURCE = 0;
    private static final int STEP_PLACE_TARGET = 1;
    private static final int STEP_VERIFY_TARGET = 2;

    private static final int PLAN_FAILED = 0;
    private static final int PLAN_STRICT = 1;
    private static final int PLAN_PAGED = 2;
    private static final int PLAN_WRAPPED = 3;

    private State state = State.IDLE;
    private final List<Move> moves = new ArrayList<>();
    private final Map<Integer, Integer> expectedByTarget = new HashMap<>();
    private final List<String> displacedReport = new ArrayList<>();
    private AbstractContainerMenu menu;
    private int moveIndex;
    private int moveStep = -1;
    private int moveTimer;
    private int currentSource = -1;
    private int currentTarget = -1;
    private int verifyAttempts;
    private GuiGraphicsExtractor lastHintGraphics;
    // Preview cache: recomputing the frame every frame is a grouping + origin scan over the
    // whole GUI; the stamp (item identity per slot) tells us when nothing changed.
    private AbstractContainerMenu previewMenu;
    private int previewHovered = -2;
    private long previewStamp;

    // Hint-path memo for the (N maps) suffix: recomputed only when the slot stamp changes.
    private long hintMapCountStamp = Long.MIN_VALUE;
    private int hintMapCountCached = 0;
    private long previewSettings;
    private List<PreviewFrame> previewFrames = List.of();

    /**
     * One previewed frame: its cells' fill colors and its per-group outline color.
     */
    private record PreviewFrame(int origin, int width, int height, int outline, Map<Integer, Integer> cells) {
    }

    private boolean prevSortKeyPressed;
    private AbstractContainerMenu autoSortMenu;
    private AbstractContainerMenu lastAutoSortMenu;
    private int autoSortTicks;

    private record Move(int source, int target, Integer mapId) {
    }

    @Override
    public void onActivate() {
        reset();
        autoSortMenu = null;
        lastAutoSortMenu = null;
        autoSortTicks = 0;
        lastHintGraphics = null;
        prevSortKeyPressed = false;
    }

    @Override
    public void onDeactivate() {
        reset();
        autoSortMenu = null;
        lastAutoSortMenu = null;
        autoSortTicks = 0;
        lastHintGraphics = null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;

        if (state == State.IDLE) {
            boolean now = sortKey.get().isPressed();
            boolean pressed = now && !prevSortKeyPressed;
            prevSortKeyPressed = now;
            // The keybind action handles the release edge in a GUI; this poll is a fallback for a
            // press that starts before the GUI is fully up, so either edge can start the sort.
            if (pressed && ScreenUtil.current() instanceof AbstractContainerScreen) startSort(true);
            updateAutoSort();
            return;
        }

        if (!(ScreenUtil.current() instanceof AbstractContainerScreen) || mc.player.containerMenu != menu) {
            error("The container closed before the sort finished.");
            reset();
            return;
        }

        // Pressing the sort key again cancels after the current move (cursor is empty between moves).
        boolean now = sortKey.get().isPressed();
        boolean pressed = now && !prevSortKeyPressed;
        prevSortKeyPressed = now;
        if (pressed) {
            if (debug.get()) info("Sort cancelled after " + moveIndex + " move(s).");
            reset();
            return;
        }

        if (moveIndex >= moves.size()) {
            finishSort();
            return;
        }

        if (moveTimer > 0) {
            moveTimer--;
            return;
        }

        if (moveStep < 0) {
            Move move = moves.get(moveIndex);
            currentSource = move.source();
            currentTarget = move.target();
            verifyAttempts = 0;
            moveStep = STEP_PICKUP_SOURCE;
        }

        if (moveStep == STEP_PICKUP_SOURCE) {
            if (currentSource < 0 || currentSource >= menu.slots.size()
                || currentTarget < 0 || currentTarget >= menu.slots.size()) {
                error("Slot index out of range during sort. Stopping.");
                reset();
                return;
            }
            if (!menu.getCarried().isEmpty()) {
                error("Cursor is not empty during sort. Stopping.");
                reset();
                return;
            }
            if (menu.slots.get(currentSource).getItem().isEmpty()) {
                // The source is already empty: an earlier group emptied it before this move ran,
                // so the intent of the move is already satisfied. Skip it instead of stopping.
                moveIndex++;
                moveStep = -1;
                moveTimer = actionDelay.get();
                if (moveIndex >= moves.size()) finishSort();
                return;
            }
            clickSlot(currentSource);
            moveStep = STEP_PLACE_TARGET;
            moveTimer = actionDelay.get();
            return;
        }

        if (moveStep == STEP_PLACE_TARGET) {
            if (menu.getCarried().isEmpty()) {
                error("Did not pick up the item from slot " + currentSource + ". Stopping.");
                reset();
                return;
            }
            clickSlot(currentTarget);
            moveStep = STEP_VERIFY_TARGET;
            moveTimer = Math.max(actionDelay.get(), 2);
            return;
        }

        if (moveStep == STEP_VERIFY_TARGET) {
            if (!verifyMove()) return;
            moveIndex++;
            moveStep = -1;
            moveTimer = actionDelay.get();
            if (moveIndex >= moves.size()) finishSort();
        }
    }

    private boolean verifyMove() {
        if (currentTarget < 0 || currentTarget >= menu.slots.size()) {
            error("Slot index out of range during sort. Stopping.");
            reset();
            return false;
        }

        Move move = moves.get(moveIndex);
        Integer expected = move.mapId();
        if (expected != null) {
            Integer got = mapIdOf(menu.slots.get(currentTarget).getItem());
            if (!expected.equals(got)) {
                Integer carried = mapIdOf(menu.getCarried());
                if (expected.equals(carried) && verifyAttempts < 2) {
                    clickSlot(currentTarget);
                    verifyAttempts++;
                    moveTimer = 2;
                    return false;
                }
                error("A map did not land in its slot; the container may have changed. Stopping.");
                reset();
                return false;
            }
        }

        if (!menu.getCarried().isEmpty()) {
            error("Cursor is not empty after a move. Stopping.");
            reset();
            return false;
        }
        return true;
    }

    public boolean handleSortKey(KeyEvent input) {
        if (!isActive() || state != State.IDLE) return false;
        if (!sortKey.get().matches(input)) return false;
        if (!(ScreenUtil.current() instanceof AbstractContainerScreen)) return false;
        startSort(true);
        return true;
    }

    @EventHandler
    private void onRenderHint(ScreenRenderEvent event) {
        if (!isActive() || state != State.IDLE) return;
        if (!(ScreenUtil.current() instanceof AbstractContainerScreen screen)) return;
        if (!sortKey.get().isSet()) return;
        if (event.graphics == lastHintGraphics) return;

        lastHintGraphics = event.graphics;
        renderPreview(screen, event.graphics);

        Slot hovered = ((AbstractContainerScreenAccessor) screen).getHoveredSlot();
        if (hovered == null || hovered.getItem().getItem() != Items.FILLED_MAP) return;

        Set<Integer> hintSlots = sortArea.get() == SortArea.FollowAnchor
            ? allStorageSlots(screen.getMenu(), screen.getMenu().slots)
            : sortableSlotIndices(screen.getMenu(), screen.getMenu().slots);
        int mapCount = hintMapCount(screen.getMenu().slots, hintSlots);
        String text = "[" + sortKey.get() + "] Sort map art" + (mapCount >= 2 ? " (" + mapCount + " maps)" : "");
        int textWidth = mc.font.width(text);
        int x = Math.min(event.mouseX + 16, Math.max(0, mc.getWindow().getGuiScaledWidth() - textWidth - 6));
        int y = event.mouseY + 18;
        event.graphics.fill(x - 3, y - 2, x + textWidth + 3, y + mc.font.lineHeight + 2, 0xCC000000);
        event.graphics.text(mc.font, text, x, y, 0xFFFFFFFF, false);
    }

    /**
     * Highlights the frame the sort would use: at the hovered empty sortable slot when there is
     * one, otherwise at the best origin. Shares member/frame-size resolution with the sort
     * itself, so the preview can't disagree with where maps would land.
     */
    private void renderPreview(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics) {
        if (!showFramePreview.get()) return;
        AbstractContainerMenu container = screen.getMenu();
        List<Slot> slots = container.slots;
        Set<Integer> memberSlots = sortArea.get() == SortArea.FollowAnchor
            ? allStorageSlots(container, slots) : sortableSlotIndices(container, slots);
        if (memberSlots.isEmpty()) return;

        Slot hovered = ((AbstractContainerScreenAccessor) screen).getHoveredSlot();
        int hoveredIndex = -1;
        if (hovered != null) {
            for (int i = 0; i < slots.size(); i++) {
                if (slots.get(i) == hovered) {
                    hoveredIndex = i;
                    break;
                }
            }
        }

        long stamp = previewStamp(slots, memberSlots);
        long settings = previewSettingsStamp();
        if (container != previewMenu || hoveredIndex != previewHovered || stamp != previewStamp
            || settings != previewSettings) {
            previewMenu = container;
            previewHovered = hoveredIndex;
            previewStamp = stamp;
            previewSettings = settings;
            previewFrames = computePreviewFrames(container, slots, memberSlots, hoveredIndex);
        }
        if (previewFrames.isEmpty()) return;

        int rowWidth = AbstractContainerMenu.SLOTS_PER_ROW;
        int left = ((AbstractContainerScreenAccessor) screen).getLeftPos();
        int top = ((AbstractContainerScreenAccessor) screen).getTopPos();
        for (PreviewFrame frame : previewFrames) {
            for (int row = 0; row < frame.height(); row++) {
                for (int col = 0; col < frame.width(); col++) {
                    int idx = frame.origin() + row * rowWidth + col;
                    if (idx < 0 || idx >= slots.size()) continue;
                    Slot slot = slots.get(idx);
                    int x1 = left + slot.x - 1;
                    int y1 = top + slot.y - 1;
                    int x2 = left + slot.x + 17;
                    int y2 = top + slot.y + 17;
                    Integer color = frame.cells().get(idx);
                    if (color != null) {
                        graphics.fill(x1, y1, x2, y2, color);
                    }
                    // Per-group outline: top/bottom edges on the outer rows, sides on outer cols.
                    if (row == 0) graphics.fill(x1, y1, x2, y1 + 1, frame.outline());
                    if (row == frame.height() - 1) graphics.fill(x1, y2 - 1, x2, y2, frame.outline());
                    if (col == 0) graphics.fill(x1, y1, x1 + 1, y2, frame.outline());
                    if (col == frame.width() - 1) graphics.fill(x2 - 1, y1, x2, y2, frame.outline());
                }
            }
        }
    }

    /**
     * Every group's preview frame, in sort order with exclusive claims. A frame too tall for its
     * section is previewed as one outlined rectangle per page, side by side. Cell colors: green =
     * already correct, yellow = will move here, red = occupied by a non-map item to displace,
     * cyan = empty slot to fill. Outline hue differs per art (derived from its base name).
     */
    private List<PreviewFrame> computePreviewFrames(AbstractContainerMenu container, List<Slot> slots,
                                                    Set<Integer> memberSlots, int hoveredIndex) {
        List<NameEntry> entries = collectNames(slots, memberSlots);
        if (entries.isEmpty()) return List.of();
        AllGroups selection = resolveAllGroups(entries, hoveredIndex);
        Set<Integer> sortable = frameSortable(container, slots, memberSlots, selection.anchorSlot());
        Set<Integer> wrapChain = frameWrap.get() && frameWrapSpill.get() ? rowChain(container, slots) : Set.of();
        Integer requestedOrigin = hoveredIndex >= 0 && sortable.contains(hoveredIndex)
            && slots.get(hoveredIndex).getItem().isEmpty() ? hoveredIndex : null;
        List<PreviewFrame> out = new ArrayList<>();
        Set<Integer> claimed = new HashSet<>();
        boolean first = true;
        for (NamedGroup group : sortableGroups(selection)) {
            List<Member> members = group.members();
            if (members.size() < 2) continue;
            FrameSize size = frameSize(members);
            int minRow = Integer.MAX_VALUE, minCol = Integer.MAX_VALUE;
            for (Member member : members) {
                minRow = Math.min(minRow, member.row);
                minCol = Math.min(minCol, member.col);
            }
            int width = size.width();
            int height = size.height();
            int rowWidth = AbstractContainerMenu.SLOTS_PER_ROW;
            OrderedOffsets single = orderedOffsets(members, width, height, minRow, minCol,
                size.linear(), height);
            OrderedOffsets layout = single;
            Set<Integer> effective = new HashSet<>(sortable);
            effective.removeAll(claimed);
            Integer originRequest = first ? requestedOrigin : null;
            OriginResult best = originRequest != null
                ? evaluateOrigin(slots, width, height, single, rowWidth, effective, originRequest)
                : findBestOrigin(slots, width, height, single, rowWidth, effective);
            if (frameWrap.get() && best.origin < 0) {
                // Mirror the sort's page split so the preview never promises a frame the sort
                // would refuse (or hide one it would place).
                int sectionRows = sectionRows(sortable);
                if (sectionRows >= 1 && sectionRows < height) {
                    OrderedOffsets paged = orderedOffsets(members, width, height, minRow, minCol,
                        size.linear(), sectionRows);
                    best = originRequest != null
                        ? evaluateOrigin(slots, width, height, paged, rowWidth, effective, originRequest)
                        : findBestOrigin(slots, width, height, paged, rowWidth, effective);
                    if (best.origin >= 0) layout = paged;
                }
            }
            if (frameWrap.get() && frameWrapSpill.get() && best.origin < 0) {
                Set<Integer> wrapped = new HashSet<>(wrapChain);
                wrapped.removeAll(claimed);
                best = originRequest != null
                    ? evaluateOrigin(slots, width, height, single, rowWidth,
                    wrapped, sortable, wrapped, Set.of(), originRequest)
                    : findBestOrigin(slots, width, height, single, rowWidth,
                    wrapped, sortable, wrapped, Set.of());
            }
            first = false;
            if (best.origin < 0) continue;
            Map<Integer, Integer> cells = new HashMap<>();
            for (int i = 0; i < layout.ordered().size(); i++) {
                Member member = layout.ordered().get(i);
                int target = best.origin + layout.offsets().get(i);
                if (target < 0 || target >= slots.size()) continue;
                cells.put(target, member.slot == target ? 0x6633FF66 : 0x66FFCC33);
            }
            for (int idx : layoutCells(layout, width, height, best.origin)) {
                if (idx < 0 || idx >= slots.size()) continue;
                claimed.add(idx);
                if (cells.containsKey(idx)) continue;
                cells.put(idx, slots.get(idx).getItem().isEmpty() ? 0x6633CCFF : 0x66FF4444);
            }
            float hue = (Math.abs(group.base().hashCode()) % 360) / 360.0f;
            int outline = (0xCC << 24) | (java.awt.Color.HSBtoRGB(hue, 0.7f, 1.0f) & 0x00FFFFFF);
            for (int page = 0; page < layout.pages(); page++) {
                int rows = Math.min(layout.pageHeight(), height - page * layout.pageHeight());
                out.add(new PreviewFrame(best.origin + page * width, width, rows, outline, cells));
            }
        }
        return out;
    }

    /**
     * Cheap per-slot identity stamp: item, count and map id. Detects any content change.
     */
    private static long previewStamp(List<Slot> slots, Set<Integer> sortable) {
        long stamp = 0;
        for (int i : sortable) {
            ItemStack stack = slots.get(i).getItem();
            stamp = stamp * 31 + i;
            stamp = stamp * 31 + stack.getItem().hashCode();
            stamp = stamp * 31 + stack.getCount();
            Integer mapId = mapIdOf(stack);
            stamp = stamp * 31 + (mapId == null ? 0 : mapId);
        }
        return stamp;
    }

    /**
     * Settings the frame depends on; a change must rebuild the preview while the GUI stays open.
     */
    private long previewSettingsStamp() {
        long stamp = sortArea.get().ordinal();
        stamp = stamp * 31 + (allowHotbarSpares.get() ? 1 : 0);
        stamp = stamp * 31 + (frameWrap.get() ? 1 : 0);
        stamp = stamp * 31 + (frameWrapSpill.get() ? 1 : 0);
        stamp = stamp * 31 + (autoSize.get() ? 1 : 0);
        stamp = stamp * 31 + requireArtShape.get().ordinal();
        stamp = stamp * 31 + mapWidth.get();
        stamp = stamp * 31 + mapHeight.get();
        stamp = stamp * 31 + (strictHoveredGroup.get() ? 1 : 0);
        stamp = stamp * 31 + coordinateFormat.get().hashCode();
        stamp = stamp * 31 + coordPosition.get().ordinal();
        stamp = stamp * 31 + (useLetteredNames.get() ? 1 : 0);
        return stamp;
    }

    private void updateAutoSort() {
        if (!autoSort.get()
            || !(ScreenUtil.current() instanceof AbstractContainerScreen screen)
            || screen.getMenu() instanceof InventoryMenu) {
            autoSortMenu = null;
            autoSortTicks = 0;
            lastAutoSortMenu = null;
            return;
        }

        AbstractContainerMenu current = screen.getMenu();
        if (current == lastAutoSortMenu) return;
        if (current != autoSortMenu) {
            autoSortMenu = current;
            autoSortTicks = autoSortDelay.get();
            return;
        }
        if (autoSortTicks <= 0) return;
        autoSortTicks--;
        if (autoSortTicks == 0) {
            // Wait until the anchor art is complete enough; checked again next tick.
            if (!autoSortReady(screen)) return;
            lastAutoSortMenu = current;
            autoSortMenu = null;
            startSort(false);
        }
    }

    /**
     * Whether the anchor art meets the completeness threshold: present maps as a percent of
     * its frame area. At 100 it waits for a full frame; lower values sort a nearly-complete art.
     */
    private boolean autoSortReady(AbstractContainerScreen<?> screen) {
        int threshold = autoSortMinCompleteness.get();
        if (threshold <= 0) return true;
        AbstractContainerMenu container = screen.getMenu();
        List<Slot> slots = container.slots;
        Set<Integer> memberSlots = sortArea.get() == SortArea.FollowAnchor
            ? allStorageSlots(container, slots) : sortableSlotIndices(container, slots);
        List<NameEntry> entries = collectNames(slots, memberSlots);
        if (entries.isEmpty()) return true; // nothing to sort; let startSort report it
        AllGroups selection = resolveAllGroups(entries, -1);
        for (NamedGroup group : selection.groups()) {
            if (group.members().size() < 2) continue;
            if (invalidArtShape(group.members()) != null) continue;
            FrameSize size = frameSize(group.members());
            double completeness = (double) group.members().size() / (size.width() * size.height());
            return completeness * 100 >= threshold;
        }
        return true;
    }

    private void startSort(boolean anchoredByHover) {
        if (mc.player == null || mc.gameMode == null) return;
        if (state != State.IDLE) return;
        if (!isActive()) {
            if (debug.get()) info("Enable Mapart-Sort first.");
            return;
        }
        if (!(ScreenUtil.current() instanceof AbstractContainerScreen screen)) {
            if (debug.get()) info("Open an inventory or container GUI first.");
            return;
        }

        Slot hovered = ((AbstractContainerScreenAccessor) screen).getHoveredSlot();

        AbstractContainerMenu container = screen.getMenu();
        List<Slot> slots = container.slots;
        // FollowAnchor collects maps from both sections; the frame section is derived below.
        Set<Integer> memberSlots = sortArea.get() == SortArea.FollowAnchor
            ? allStorageSlots(container, slots) : sortableSlotIndices(container, slots);
        if (memberSlots.isEmpty()) {
            if (container instanceof InventoryMenu && sortArea.get() == SortArea.ContainerOnly) {
                if (debug.get()) info("sort-area is container-only, but this GUI has no container section;"
                    + " use sort-area = Both or InventoryOnly.");
            } else {
                if (debug.get()) info("Mapart Sort only supports chest, shulker, ender chest and player inventory GUIs.");
            }
            return;
        }
        if (!container.getCarried().isEmpty()) {
            error("Empty your cursor before sorting.");
            return;
        }

        List<NameEntry> entries = collectNames(slots, memberSlots);
        if (entries.isEmpty()) {
            if (debug.get()) info("No named filled maps in the open GUI to sort.");
            return;
        }

        int hoveredIndex = -1;
        Integer requestedOrigin = null;
        if (hovered != null) {
            for (int i = 0; i < slots.size(); i++) {
                if (slots.get(i) == hovered) {
                    hoveredIndex = i;
                    break;
                }
            }
        }
        AllGroups selection = resolveAllGroups(entries, hoveredIndex);
        // The frame section: for FollowAnchor it follows the anchor map's section.
        Set<Integer> sortable = frameSortable(container, slots, memberSlots, selection.anchorSlot());
        // Rows a too-tall frame may wrap into: everything storage, hotbar row only when allowed.
        Set<Integer> wrapChain = frameWrap.get() && frameWrapSpill.get() ? rowChain(container, slots) : Set.of();
        if (anchoredByHover
            && hoveredIndex >= 0
            && sortable.contains(hoveredIndex)
            && slots.get(hoveredIndex).getItem().isEmpty()) {
            requestedOrigin = hoveredIndex;
        }
        if (strictHoveredGroup.get() && selection.hoveredGroupSize() >= 0 && selection.hoveredGroupSize() < 2) {
            if (debug.get()) info("Hovered map's group has only " + selection.hoveredGroupSize() + " map(s); strict-hovered-group is on.");
            return;
        }
        if (selection.duplicateCoords() > 0) {
            if (debug.get()) info("Skipped " + selection.duplicateCoords() + " map(s) with duplicate coordinates.");
        }
        List<NamedGroup> groups = sortableGroups(selection);
        int shapeSkipped = reportUnsortableGroups(selection, groups);
        if (groups.isEmpty()) {
            if (shapeSkipped > 0) {
                if (debug.get()) info("No map art with a complete frame was found; nothing to sort.");
            } else {
                if (debug.get()) info("Could not read any map position from the related names.");
            }
            return;
        }

        StringBuilder parsed = new StringBuilder("Parsed coords: ");
        int shown = 0;
        for (NamedGroup group : groups) {
            for (Member m : group.members()) {
                if (shown >= 8) break;
                parsed.append("[").append(m.col).append(",").append(m.row).append("] ");
                shown++;
            }
            if (groups.size() > 1) parsed.append(" | ");
            if (shown >= 8) break;
        }
        if (shown >= 8) parsed.append("...");
        if (debug.get()) info(parsed.toString());

        if (!planAllGroups(container, groups, slots, sortable, wrapChain, requestedOrigin)) return;
        if (moves.isEmpty()) {
            if (debug.get()) info("Map art frame already sorted.");
            return;
        }

        menu = container;
        state = State.SORTING;
        moveIndex = 0;
        moveStep = -1;
        moveTimer = 0;
        currentSource = -1;
        currentTarget = -1;
        verifyAttempts = 0;
        if (debug.get()) info("Sorting " + moves.size() + " map(s) into the frame.");
    }

    /**
     * One art: its masked base name and its deduped members.
     */
    private record NamedGroup(String base, List<Member> members) {
    }

    private record AllGroups(List<NamedGroup> groups, int duplicateCoords, int hoveredGroupSize, int anchorSlot) {
    }

    /**
     * Groups the named maps by masked base name (coordinate cut out).
     */
    private Map<String, List<Member>> groupMembers(List<NameEntry> entries) {
        Map<String, List<Member>> groups = new LinkedHashMap<>();
        for (NameEntry entry : entries) {
            Coord coord = extractCoord(entry.name);
            if (coord == null) continue;
            String base = baseNameOf(entry.name, coord.start(), coord.end());
            groups.computeIfAbsent(base, k -> new ArrayList<>())
                .add(new Member(entry.slot, entry.stack, coord.col(), coord.row(), coord.kind()));
        }
        for (List<Member> members : groups.values()) {
            collapseCompass(members);
        }
        return groups;
    }

    /**
     * Dedupes by map id, then by coordinate, so a duplicate cannot corrupt the span.
     */
    private List<Member> dedupeMembers(List<Member> members, int[] duplicateCoords) {
        List<Member> deduped = new ArrayList<>();
        Set<Integer> usedMapIds = new HashSet<>();
        Set<String> usedCoords = new HashSet<>();
        for (Member member : members) {
            Integer mapId = mapIdOf(member.stack);
            if (mapId != null && !usedMapIds.add(mapId)) continue;
            if (!usedCoords.add(member.col + "," + member.row)) {
                duplicateCoords[0]++;
                continue;
            }
            deduped.add(member);
        }
        return deduped;
    }

    /**
     * All arts in the GUI, deduped, ordered anchor group first (hovered map, else first parseable
     * map) then largest first. Shared by the sort itself and the frame preview.
     */
    private AllGroups resolveAllGroups(List<NameEntry> entries, int hoveredIndex) {
        Map<String, List<Member>> groups = groupMembers(entries);
        if (groups.isEmpty()) return new AllGroups(List.of(), 0, -1, -1);
        NameEntry hoveredEntry = null;
        NameEntry anchorEntry = null;
        for (NameEntry entry : entries) {
            if (extractCoord(entry.name) == null) continue;
            if (anchorEntry == null) anchorEntry = entry;
            if (entry.slot == hoveredIndex) {
                hoveredEntry = entry;
                break;
            }
        }
        if (hoveredEntry != null) anchorEntry = hoveredEntry;
        int hoveredGroupSize = -1;
        if (hoveredEntry != null) {
            Coord hc = extractCoord(hoveredEntry.name);
            List<Member> g = groups.get(baseNameOf(hoveredEntry.name, hc.start(), hc.end()));
            hoveredGroupSize = g == null ? 0 : g.size();
        }
        String anchorBase = null;
        int anchorSlot = -1;
        if (anchorEntry != null) {
            Coord ac = extractCoord(anchorEntry.name);
            anchorBase = baseNameOf(anchorEntry.name, ac.start(), ac.end());
            anchorSlot = anchorEntry.slot;
        }
        int[] duplicateCoords = {0};
        List<NamedGroup> out = new ArrayList<>();
        for (Map.Entry<String, List<Member>> e : groups.entrySet()) {
            out.add(new NamedGroup(e.getKey(), dedupeMembers(e.getValue(), duplicateCoords)));
        }
        String anchor = anchorBase;
        out.sort((a, b) -> {
            boolean aAnchor = a.base().equals(anchor);
            boolean bAnchor = b.base().equals(anchor);
            if (aAnchor != bAnchor) return aAnchor ? -1 : 1;
            return Integer.compare(b.members().size(), a.members().size());
        });
        return new AllGroups(out, duplicateCoords[0], hoveredGroupSize, anchorSlot);
    }

    private record FrameSize(int width, int height, boolean linear, String note) {
    }

    /**
     * The frame size from the members' coordinate span, or map-width/map-height as fallback.
     */
    private FrameSize frameSize(List<Member> members) {
        int minRow = Integer.MAX_VALUE, minCol = Integer.MAX_VALUE;
        int maxRow = Integer.MIN_VALUE, maxCol = Integer.MIN_VALUE;
        for (Member member : members) {
            minRow = Math.min(minRow, member.row);
            maxRow = Math.max(maxRow, member.row);
            minCol = Math.min(minCol, member.col);
            maxCol = Math.max(maxCol, member.col);
        }
        int spanWidth = maxCol - minCol + 1;
        int spanHeight = maxRow - minRow + 1;
        if (!autoSize.get()) return new FrameSize(mapWidth.get(), mapHeight.get(), false, null);
        // A group of pure 1D indices ("Map 1".."Map 16", "Map A".."Map Z") wraps into map-width
        // rows; the single-line span branch below would demand a width the menu cannot have.
        if (!members.isEmpty() && members.stream().allMatch(m -> m.kind == CoordKind.LINEAR)) {
            int width = Math.max(1, mapWidth.get());
            int height = (members.size() + width - 1) / width;
            return new FrameSize(width, height, true,
                "Auto-size: linear single-line coordinates; wrapping " + members.size() + " map(s) into " + width + "-wide rows.");
        }
        if (spanWidth * spanHeight == members.size()) {
            return new FrameSize(spanWidth, spanHeight, false, null);
        }
        Set<String> distinctCoords = new HashSet<>();
        for (Member member : members) distinctCoords.add(member.col + "," + member.row);
        if (spanWidth * spanHeight > members.size() && distinctCoords.size() == members.size()) {
            return new FrameSize(spanWidth, spanHeight, false,
                "Auto-size: span " + spanWidth + "x" + spanHeight + " covers " + members.size() + " of "
                    + (spanWidth * spanHeight) + " slots; using span.");
        }
        if (spanHeight == 1 && members.size() > spanWidth) {
            int width = Math.max(1, mapWidth.get());
            int height = (members.size() + width - 1) / width;
            return new FrameSize(width, height, true,
                "Auto-size could not read 2D coordinates (span " + spanWidth + "x" + spanHeight + "); "
                    + "laying out " + members.size() + " map(s) as " + width + "x" + height + " using map-width.");
        }
        return new FrameSize(mapWidth.get(), mapHeight.get(), false,
            "Auto-size expected " + (spanWidth * spanHeight) + " maps for a " + spanWidth + "x" + spanHeight
                + " frame but found " + members.size() + "; using map-width/map-height.");
    }

    /**
     * A frame's slot layout: member-to-offset mapping plus the page split. {@code pageHeight} is
     * the number of art rows per page, so {@code pageHeight >= height} means a single frame.
     * A page k starts {@code width} columns to the right of page k-1, on the same row band.
     */
    private record OrderedOffsets(List<Member> ordered, List<Integer> offsets,
                                  int pageHeight, int pages, int maxOffset) {
    }

    private OrderedOffsets orderedOffsets(List<Member> members, int width, int height,
                                          int minRow, int minCol, boolean linear, int pageHeight) {
        int perPage = Math.max(1, pageHeight);
        int rowWidth = AbstractContainerMenu.SLOTS_PER_ROW;
        List<Member> ordered = new ArrayList<>(members);
        if (linear) ordered.sort(Comparator.comparingInt((Member m) -> m.col).thenComparingInt(m -> m.slot));
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            Member member = ordered.get(i);
            int row = linear ? i / Math.max(1, width) : member.row - minRow;
            int col = linear ? i % Math.max(1, width) : member.col - minCol;
            int page = row / perPage;
            offsets.add(page * width + (row % perPage) * rowWidth + col);
        }
        int pages = Math.max(1, (height + perPage - 1) / perPage);
        int lastRows = height - (pages - 1) * perPage;
        int maxOffset = (pages - 1) * width + (lastRows - 1) * rowWidth + (width - 1);
        return new OrderedOffsets(ordered, offsets, perPage, pages, maxOffset);
    }

    /**
     * Every slot the layout occupies at {@code origin}: all pages, last page only its real rows.
     */
    private Set<Integer> layoutCells(OrderedOffsets oo, int width, int height, int origin) {
        int rowWidth = AbstractContainerMenu.SLOTS_PER_ROW;
        Set<Integer> cells = new HashSet<>();
        for (int page = 0; page < oo.pages(); page++) {
            int rows = Math.min(oo.pageHeight(), height - page * oo.pageHeight());
            for (int row = 0; row < rows; row++) {
                for (int col = 0; col < width; col++) {
                    cells.add(origin + page * width + row * rowWidth + col);
                }
            }
        }
        return cells;
    }

    /**
     * Distinct menu rows in a section; sections are contiguous full rows, so this is its height.
     */
    private static int sectionRows(Set<Integer> section) {
        Set<Integer> rows = new HashSet<>();
        for (int slot : section) rows.add(slot / AbstractContainerMenu.SLOTS_PER_ROW);
        return rows.size();
    }

    /**
     * Plans every group in order (anchor group first). Frames claim slots exclusively, so two
     * arts can never overlap, and spares are shared across groups so a spare is never planned
     * into twice. Group identity is the masked base name, so maps from different arts that share
     * coordinates can never mix.
     */
    private boolean planAllGroups(AbstractContainerMenu container, List<NamedGroup> groups,
                                  List<Slot> slots, Set<Integer> sortable, Set<Integer> wrapChain, Integer requestedOrigin) {
        moves.clear();
        expectedByTarget.clear();
        displacedReport.clear();
        Set<Integer> claimed = new HashSet<>();
        Set<Integer> usedSpares = new HashSet<>();
        // The slots the groups planned so far will empty. A later group must plan against that
        // state, not against the container as it is now: a slot an earlier group vacates is free
        // when the later group's moves run, and planning a displacement from it would abort the
        // sort with "source slot is empty".
        Set<Integer> vacated = new HashSet<>();
        boolean anyFrame = false;
        for (int gi = 0; gi < groups.size(); gi++) {
            NamedGroup group = groups.get(gi);
            List<Member> members = group.members();
            if (members.size() < 2) continue;
            Integer originRequest = gi == 0 ? requestedOrigin : null;
            // A group that can't be planned must not leave half-planned moves behind.
            int movesBefore = moves.size();
            int reportBefore = displacedReport.size();
            Set<Integer> expectedBefore = new HashSet<>(expectedByTarget.keySet());
            Set<Integer> claimedBefore = new HashSet<>(claimed);
            Set<Integer> sparesBefore = new HashSet<>(usedSpares);
            Set<Integer> vacatedBefore = new HashSet<>(vacated);
            if (planOneGroup(container, members, slots, sortable, wrapChain, claimed, usedSpares, vacated, originRequest)
                == PLAN_FAILED) {
                while (moves.size() > movesBefore) moves.remove(moves.size() - 1);
                while (displacedReport.size() > reportBefore) displacedReport.remove(displacedReport.size() - 1);
                expectedByTarget.keySet().retainAll(expectedBefore);
                claimed.clear();
                claimed.addAll(claimedBefore);
                usedSpares.clear();
                usedSpares.addAll(sparesBefore);
                vacated.clear();
                vacated.addAll(vacatedBefore);
                continue;
            }
            anyFrame = true;
        }
        return anyFrame;
    }

    /**
     * Empty slots for displaced/cycle moves, frame-section spares first.
     */
    private List<Integer> orderedSpares(List<Slot> slots, Set<Integer> sparePool, Set<Integer> claimed,
                                        Set<Integer> frameSlots, Set<Integer> usedSpares, Set<Integer> vacated, int origin) {
        boolean framePlayer = slots.get(origin).container instanceof Inventory;
        List<Integer> out = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < slots.size(); i++) {
                if (!sparePool.contains(i) || claimed.contains(i) || frameSlots.contains(i) || usedSpares.contains(i)) continue;
                boolean playerSlot = slots.get(i).container instanceof Inventory;
                if (playerSlot != framePlayer && pass == 0) continue;
                if (playerSlot == framePlayer && pass == 1) continue;
                // A slot an earlier group empties is free when this group's moves run.
                if ((slots.get(i).getItem().isEmpty() || vacated.contains(i)) && spareAllowed(slots.get(i))) out.add(i);
            }
        }
        return out;
    }

    /**
     * Explains why no frame could be placed: the limit, then the ways out (spill, wider area).
     */
    private void reportNoFrame(AbstractContainerMenu container, List<Slot> slots, int width, int height,
                               Integer requestedOrigin, Set<Integer> section) {
        if (requestedOrigin != null) {
            if (debug.get()) info("Hovered anchor slot " + requestedOrigin + " cannot hold a " + width + "x" + height
                + " frame. Hover another empty storage slot or use auto-sort.");
            return;
        }
        StringBuilder msg = new StringBuilder("Map art frame " + width + "x" + height
            + " does not fit sort-area " + areaLabel() + " (" + sectionRows(section) + " rows).");
        if (!frameWrap.get()) {
            msg.append(" Turn on frame-wrap to split it into pages.");
        } else if (!frameWrapSpill.get()) {
            msg.append(" frame-wrap-spill can continue it below the section.");
        }
        if (sortArea.get() != SortArea.Both
            && height <= sectionRows(allStorageSlots(container, slots))) {
            msg.append(" Or use sort-area = Both.");
        }
        if (debug.get()) info(msg.toString());
    }

    /**
     * Lower-case name of the current sort area, for messages.
     */
    private String areaLabel() {
        return switch (sortArea.get()) {
            case ContainerOnly -> "container only";
            case InventoryOnly -> "inventory only";
            case FollowAnchor -> "anchor's section";
            case Both -> "both";
        };
    }

    private int planOneGroup(AbstractContainerMenu container, List<Member> members, List<Slot> slots,
                             Set<Integer> sortable, Set<Integer> wrapChain, Set<Integer> claimed, Set<Integer> usedSpares,
                             Set<Integer> vacated, Integer requestedOrigin) {
        int minRow = Integer.MAX_VALUE;
        int minCol = Integer.MAX_VALUE;
        for (Member member : members) {
            minRow = Math.min(minRow, member.row);
            minCol = Math.min(minCol, member.col);
        }

        FrameSize size = frameSize(members);
        int width = size.width();
        int height = size.height();
        if (debug.get() && (size.note() != null)) info(size.note());

        int rowWidth = AbstractContainerMenu.SLOTS_PER_ROW;
        OrderedOffsets single = orderedOffsets(members, width, height, minRow, minCol, size.linear(), height);
        OrderedOffsets layout = single;

        Set<Integer> effective = new HashSet<>(sortable);
        effective.removeAll(claimed);
        OriginResult best = requestedOrigin != null
            ? evaluateOrigin(slots, width, height, single, rowWidth, effective, vacated, requestedOrigin)
            : findBestOrigin(slots, width, height, single, rowWidth, effective, vacated);
        Set<Integer> sparePool = effective;
        int planResult = PLAN_STRICT;
        if (frameWrap.get() && best.origin < 0) {
            // Too tall for its section: split the art into pages side by side on the same rows,
            // like pages of a book. The first page still has to start inside the section.
            int sectionRows = sectionRows(sortable);
            if (sectionRows >= 1 && sectionRows < height) {
                OrderedOffsets paged = orderedOffsets(members, width, height, minRow, minCol,
                    size.linear(), sectionRows);
                best = requestedOrigin != null
                    ? evaluateOrigin(slots, width, height, paged, rowWidth, effective, vacated, requestedOrigin)
                    : findBestOrigin(slots, width, height, paged, rowWidth, effective, vacated);
                if (best.origin >= 0) {
                    layout = paged;
                    planResult = PLAN_PAGED;
                }
            }
        }
        if (frameWrap.get() && frameWrapSpill.get() && best.origin < 0) {
            // Last resort: the pages do not fit side by side, so let the lower rows continue into
            // the storage rows below, like text wrapping.
            Set<Integer> wrapped = new HashSet<>(wrapChain);
            wrapped.removeAll(claimed);
            best = requestedOrigin != null
                ? evaluateOrigin(slots, width, height, single, rowWidth,
                wrapped, sortable, wrapped, vacated, requestedOrigin)
                : findBestOrigin(slots, width, height, single, rowWidth,
                wrapped, sortable, wrapped, vacated);
            if (best.origin >= 0) {
                sparePool = wrapped;
                layout = single;
                planResult = PLAN_WRAPPED;
            }
        }
        if (best.origin < 0) {
            reportNoFrame(container, slots, width, height, requestedOrigin, sortable);
            return PLAN_FAILED;
        }
        int originSlot = best.origin;
        int displaced = best.displaced;
        int spareCount = best.spares;
        if (debug.get()) info("Frame " + width + "x" + height
            + (planResult == PLAN_PAGED ? " as " + layout.pages() + " pages side by side" : "")
            + " at slot " + originSlot + " (" + members.size() + " map(s), "
            + spareCount + " spare(s), " + displaced + " displaced, " + best.correct + " already correct"
            + (planResult == PLAN_WRAPPED ? "; lower rows wrap below the section" : "") + ").");

        Set<Integer> frameSlots = layoutCells(layout, width, height, originSlot);
        List<Member> ordered = layout.ordered();
        List<Integer> offsets = layout.offsets();
        claimed.addAll(frameSlots);
        Map<Integer, Integer> targetBySource = new HashMap<>();
        Map<Integer, Integer> sourceByTarget = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            Member member = ordered.get(i);
            int target = originSlot + offsets.get(i);
            if (target < 0 || target >= slots.size()) continue;
            if (targetBySource.containsKey(member.slot) || sourceByTarget.containsKey(target)) continue;
            targetBySource.put(member.slot, target);
            sourceByTarget.put(target, member.slot);
            expectedByTarget.put(target, mapIdOf(member.stack));
        }

        List<Integer> spares = orderedSpares(slots, sparePool, claimed, frameSlots, usedSpares, vacated, originSlot);
        int spareCursor = 0;

        List<Integer> targets = new ArrayList<>(sourceByTarget.keySet());
        Collections.sort(targets);
        for (int target : targets) {
            int source = sourceByTarget.get(target);
            if (source == target) continue;
            if (targetBySource.containsKey(target)) continue;
            // A slot an earlier group empties is free when this group's moves run, so there is
            // nothing to displace out of it.
            if (vacated.contains(target)) continue;
            if (slots.get(target).getItem().isEmpty()) continue;
            if (spareCursor >= spares.size()) {
                error("No spare slot to move a displaced item into.");
                return PLAN_FAILED;
            }
            int spare = spares.get(spareCursor++);
            usedSpares.add(spare);
            displacedReport.add("slot " + target + " -> slot " + spare);
            moves.add(new Move(target, spare, null));
        }

        Set<Integer> visited = new HashSet<>();
        List<Integer> starts = new ArrayList<>(targetBySource.keySet());
        Collections.sort(starts);
        Integer cycleSpare = null;
        for (int startSlot : starts) {
            if (visited.contains(startSlot)) continue;
            int startTarget = targetBySource.get(startSlot);
            if (startTarget == startSlot) {
                visited.add(startSlot);
                continue;
            }

            List<Integer> chain = new ArrayList<>();
            int cur = startSlot;
            boolean cycle = false;
            while (true) {
                chain.add(cur);
                visited.add(cur);
                int target = targetBySource.get(cur);
                if (!targetBySource.containsKey(target)) break;
                if (target == startSlot) {
                    cycle = true;
                    break;
                }
                if (visited.contains(target)) break;
                cur = target;
            }

            if (cycle) {
                if (cycleSpare == null) {
                    if (spareCursor >= spares.size()) {
                        error("No spare slot to rotate the frame.");
                        return PLAN_FAILED;
                    }
                    cycleSpare = spares.get(spareCursor++);
                    usedSpares.add(cycleSpare);
                }
                moves.add(new Move(startSlot, cycleSpare, null));
                for (int i = chain.size() - 1; i >= 1; i--) {
                    int source = chain.get(i);
                    int target = targetBySource.get(source);
                    moves.add(new Move(source, target, expectedByTarget.get(target)));
                }
                moves.add(new Move(cycleSpare, startTarget, expectedByTarget.get(startTarget)));
            } else {
                for (int i = chain.size() - 1; i >= 0; i--) {
                    int source = chain.get(i);
                    int target = targetBySource.get(source);
                    moves.add(new Move(source, target, expectedByTarget.get(target)));
                }
            }
        }

        // The slots this group's maps came from are free once its moves have run, unless the
        // group cycled a map into them (then another map takes their place).
        for (Member member : members) {
            if (!sourceByTarget.containsValue(member.slot)) vacated.add(member.slot);
        }
        return planResult;
    }

    private void finishSort() {
        int wrong = 0;
        for (Map.Entry<Integer, Integer> entry : expectedByTarget.entrySet()) {
            Integer want = entry.getValue();
            if (want == null) continue;
            int slot = entry.getKey();
            if (slot < 0 || slot >= mc.player.containerMenu.slots.size()) {
                wrong++;
                continue;
            }
            Integer got = mapIdOf(mc.player.containerMenu.slots.get(slot).getItem());
            if (!want.equals(got)) wrong++;
        }
        if (debug.get() && (wrong == 0)) info("Map art frame sorted.");
        else error(wrong + " map(s) are not in place.");
        if (!displacedReport.isEmpty()) {
            if (debug.get()) info("Displaced items: " + String.join(", ", displacedReport));
        }
        reset();
    }

    private void reset() {
        state = State.IDLE;
        moves.clear();
        expectedByTarget.clear();
        displacedReport.clear();
        menu = null;
        moveIndex = 0;
        moveStep = -1;
        moveTimer = 0;
        currentSource = -1;
        currentTarget = -1;
        verifyAttempts = 0;
        prevSortKeyPressed = sortKey.get().isPressed();
    }

    private void clickSlot(int slot) {
        mc.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.PICKUP, mc.player);
    }

    private List<NameEntry> collectNames(List<Slot> slots, Set<Integer> sortable) {
        List<NameEntry> entries = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            if (!sortable.contains(i)) continue;
            ItemStack stack = slots.get(i).getItem();
            if (stack.getItem() != Items.FILLED_MAP) continue;
            Component custom = stack.get(DataComponents.CUSTOM_NAME);
            if (custom == null) continue;
            String name = custom.getString();
            if (name == null || name.isBlank()) continue;
            // Keep the original name: the artist stripper treats quoted titles as artist text and
            // can eat the closing part of bracketed coordinates, collapsing every row to y=0.
            entries.add(new NameEntry(i, stack, name));
        }
        return entries;
    }

    /**
     * Memoised size of {@link #collectNames} keyed on the preview stamp; the hint runs every
     * frame while a container is open, and the list of named maps only changes when the slot
     * contents change.
     */
    private int hintMapCount(List<Slot> slots, Set<Integer> sortable) {
        long stamp = previewStamp(slots, sortable);
        if (stamp == hintMapCountStamp) {
            return hintMapCountCached;
        }
        hintMapCountStamp = stamp;
        hintMapCountCached = collectNames(slots, sortable).size();
        return hintMapCountCached;
    }

    /**
     * Coordinate flavour: a 2D grid (numeric or lettered axes), a 1D line, or the TL/MM/BR compass.
     */
    private enum CoordKind {GRID2D, LINEAR, COMPASS}

    private record Coord(int col, int row, int start, int end, CoordKind kind) {
    }

    private record OriginResult(int origin, int correct, int displaced, int spares, boolean needsCycle) {
    }

    /**
     * The coordinate format to parse against. Prefers MapartNamer's settings when that module is
     * active and has a non-blank format, so the two modules cannot silently drift apart and break
     * parsing.
     */
    private String effectiveCoordinateFormat() {
        MapartNamer namer = Modules.get().get(MapartNamer.class);
        if (namer != null && namer.isActive()) {
            String f = namer.coordinateFormat.get();
            if (f != null && !f.isBlank()) return f;
        }
        return coordinateFormat.get();
    }

    /**
     * The coordinate position to assume. Prefers MapartNamer's settings when that module is active.
     */
    private CoordPosition effectiveCoordPosition() {
        MapartNamer namer = Modules.get().get(MapartNamer.class);
        if (namer != null && namer.isActive()) return namer.coordPosition.get();
        return coordPosition.get();
    }

    /**
     * Normalises a map name for coordinate parsing: Unicode compatibility decomposition, combining
     * marks stripped, uppercased, the compass words folded to single letters, the FIRST/SECOND
     * ordinals folded to digits, a "by &lt;artist&gt;" attribution removed (one pass, either side of
     * the coordinate), and a dash between two digits treated as a separator. Both
     * {@link #extractCoord} and {@link #baseNameOf} run on this string, so spans stay consistent.
     */
    private static final Pattern WORD_T = Pattern.compile("\\b(?:TOP|UP)\\b");
    private static final Pattern WORD_B = Pattern.compile("\\b(?:BOTTOM|DOWN)\\b");
    private static final Pattern WORD_L = Pattern.compile("\\bLEFT\\b");
    private static final Pattern WORD_R = Pattern.compile("\\bRIGHT\\b");
    private static final Pattern WORD_M = Pattern.compile("\\bMIDDLE\\b");
    private static final Pattern WORD_0 = Pattern.compile("\\b(?:FIRST|ONE)\\b");
    private static final Pattern WORD_1 = Pattern.compile("\\b(?:SECOND|TWO)\\b");
    private static final Pattern DIGIT_HYPHEN = Pattern.compile("(?<=\\d)-(?=\\d)");
    private static final Pattern BY_ATTRIBUTION = Pattern.compile(
        "(?i)(.*?)\\s*\\bby\\b\\s*[\\p{L}\\p{N}_'-]+(?:\\s*(?:,|,?\\s*&|,?\\s*and)\\s*[\\p{L}\\p{N}_'-]+)*(.*)$");
    private static final Pattern COORDISH_GATE = Pattern.compile(
        "\\d|\\b[TMB][LMR]\\b|[A-Za-z]\\d|\\d[A-Za-z]");

    private static String normName(String name) {
        String n = Normalizer.normalize(name, Normalizer.Form.NFKD)
            .replaceAll("\\p{M}", "")
            .toUpperCase(Locale.ROOT);
        n = n.replace("┌", "TL").replace("┐", "TR").replace("└", "BL").replace("┘", "BR");
        n = WORD_T.matcher(n).replaceAll("T");
        n = WORD_B.matcher(n).replaceAll("B");
        n = WORD_L.matcher(n).replaceAll("L");
        n = WORD_R.matcher(n).replaceAll("R");
        n = WORD_M.matcher(n).replaceAll("M");
        // A dash not glued to a digit is a word separator ("Top-Left" -> "T-L" -> "T L").
        n = n.replaceAll("-(?!\\d)", " ");
        // "Top Left" folds to T and L separated by a space; glue them so the compass can match.
        n = n.replaceAll("\\b([TMB])[,\\s]+([LMR])\\b", "$1$2");
        n = WORD_0.matcher(n).replaceAll("0");
        n = WORD_1.matcher(n).replaceAll("1");
        n = stripByAttribution(n);
        n = DIGIT_HYPHEN.matcher(n).replaceAll(" ");
        return n.replaceAll("\\s+", " ").trim();
    }

    /**
     * Removes one "by &lt;name list&gt;" attribution (comma, &amp; or "and" separators, either
     * side of the coordinate), but only when the remainder still looks like it carries a
     * coordinate; otherwise the original is kept. Applied once at most.
     */
    private static String stripByAttribution(String n) {
        while (true) {
            Matcher m = BY_ATTRIBUTION.matcher(n);
            if (!m.matches()) return n;
            String remainder = m.group(1) + m.group(2);
            if (remainder.isBlank()) return n;
            if (!COORDISH_GATE.matcher(remainder).find()) return n;
            n = remainder;
        }
    }

    /**
     * Base-26 decoder: A=0 … Z=25, AA=26, AAA=702.
     */
    private static int letterValue(String token) {
        int value = 0;
        for (int i = 0; i < token.length(); i++) {
            char c = Character.toUpperCase(token.charAt(i));
            if (c < 'A' || c > 'Z') return -1;
            value = value * 26 + (c - 'A' + 1);
        }
        return value - 1;
    }

    private static int parseIntSafe(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * One token of a normalised name: digits ("-3", "12") or a letter run ("AA", "Maiku").
     */
    private record Token(String text, int start, int end, boolean digits) {
    }

    private static List<Token> tokenize(String n) {
        List<Token> out = new ArrayList<>();
        Matcher m = Pattern.compile("-?\\d+|[A-Za-z]+").matcher(n);
        while (m.find()) {
            String token = m.group();
            char first = token.charAt(0);
            out.add(new Token(token, m.start(), m.end(), Character.isDigit(first) || first == '-'));
        }
        return out;
    }

    private static final Set<String> NOT_AN_INDEX = Set.of("BY", "AND", "OR", "THE");

    /**
     * The short letter token nearest the digit token (length <= 2, attribution words skipped).
     * Solves "My Map AA 12", where the index is not adjacent but closest. Ties prefer the
     * earlier token, so the answer is deterministic.
     */
    private static Token nearestShortLetter(List<Token> letters, Token d) {
        Token best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Token l : letters) {
            if (l.text().length() > 2 || NOT_AN_INDEX.contains(l.text())) continue;
            int dist = Math.abs(l.start() - d.start());
            if (dist < bestDist || (dist == bestDist && best != null && l.start() < best.start())) {
                best = l;
                bestDist = dist;
            }
        }
        return best;
    }

    /**
     * The first or last short letter token (length <= 2, attribution words skipped), following
     * coord-position. Solves lone lettered indices behind a base name ("My Map A").
     */
    private static Token edgeShortLetter(List<Token> letters, boolean suffix) {
        Token best = null;
        for (Token l : letters) {
            if (l.text().length() > 2 || NOT_AN_INDEX.contains(l.text())) continue;
            if (best == null || (suffix ? l.start() > best.start() : l.start() < best.start())) {
                best = l;
            }
        }
        return best;
    }

    // "A1"-style pair: first token is x, second is y. A digit paired with a letter counts value-1
    // and letters decode base-26, matching the pre-existing unspaced "A1" behaviour.
    private static int pairedValue(Token t) {
        if (t.digits()) return parseIntSafe(t.text()) - 1;
        return letterValue(t.text());
    }

    // Standalone compass token, e.g. "Art TL". T/L=0, M=1, B/R=2 on each axis; a middle-free
    // axis is collapsed per group by {@link #collapseCompass} so a 2x2 art spans exactly 2x2.
    private static final Pattern COMPASS = Pattern.compile("(?:^|[^A-Za-z0-9])([TMB][LMR])(?=$|[^A-Za-z0-9])");

    private Coord extractCompass(String n) {
        Matcher m = COMPASS.matcher(n);
        if (!m.find()) return null;
        String token = m.group(1);
        int start = m.start(1), end = m.end(1);
        int row = token.charAt(0) == 'M' ? 1 : token.charAt(0) == 'B' ? 2 : 0;
        int col = token.charAt(1) == 'M' ? 1 : token.charAt(1) == 'R' ? 2 : 0;
        return new Coord(col, row, start, end, CoordKind.COMPASS);
    }

    /**
     * Collapses a compass axis whose members never take the middle value ({0, 2} -> {0, 1}), so a
     * 2x2 compass art spans two rows and two columns instead of a 3x3 art with gaps. Applied per
     * group; numeric members are left untouched.
     */
    private static void collapseCompass(List<Member> members) {
        boolean[] seenRow = new boolean[3], seenCol = new boolean[3];
        boolean anyCompass = false;
        for (Member member : members) {
            if (member.kind != CoordKind.COMPASS) continue;
            anyCompass = true;
            seenRow[member.row] = true;
            seenCol[member.col] = true;
        }
        if (!anyCompass) return;
        boolean rowCollapse = !seenRow[1];
        boolean colCollapse = !seenCol[1];
        if (!rowCollapse && !colCollapse) return;
        for (int i = 0; i < members.size(); i++) {
            Member member = members.get(i);
            if (member.kind != CoordKind.COMPASS) continue;
            int row = rowCollapse && member.row == 2 ? 1 : member.row;
            int col = colCollapse && member.col == 2 ? 1 : member.col;
            members.set(i, new Member(member.slot, member.stack, col, row, member.kind));
        }
    }

    /**
     * Extracts the coordinate from a map name. Tries the configured coordinate format first, then
     * falls back to a generic digit/lettered scan. Returns null when no coordinate is found.
     */
    private Coord extractCoord(String name) {
        if (name == null) return null;
        String n = normName(name);
        if (n.isEmpty()) return null;
        Coord scheme = extractSchemeCoord(n);
        if (scheme != null) return scheme;
        if (useLetteredNames.get()) {
            Coord compass = extractCompass(n);
            if (compass != null) return compass;
        }

        List<Token> tokens = tokenize(n);
        List<Token> digits = new ArrayList<>(), letters = new ArrayList<>();
        for (Token token : tokens) (token.digits() ? digits : letters).add(token);

        if (digits.size() >= 2) {
            Token a, b;
            // coord-position decides which end of the name the coordinate pair lives in, so a
            // base name containing digits cannot eat the coordinate.
            if (effectiveCoordPosition() == CoordPosition.Suffix) {
                a = digits.get(digits.size() - 2);
                b = digits.get(digits.size() - 1);
            } else {
                a = digits.get(0);
                b = digits.get(1);
            }
            return new Coord(parseIntSafe(a.text()), parseIntSafe(b.text()), a.start(), b.end(), CoordKind.GRID2D);
        }
        if (digits.size() == 1) {
            Token d = digits.get(0);
            if (useLetteredNames.get()) {
                // A lettered axis adjacent to the digit, or a short lone letter/word token next to
                // it, makes the pair a lettered 2D coordinate ("A1", "AA12", "A 12", "AA 12").
                Token axis = null;
                for (Token l : letters) {
                    if (l.end() == d.start() || l.start() == d.end()) {
                        axis = l;
                        break;
                    }
                }
                if (axis == null) {
                    axis = nearestShortLetter(letters, d);
                }
                if (axis != null) {
                    Token first = axis.start() < d.start() ? axis : d;
                    Token second = first == axis ? d : axis;
                    return new Coord(pairedValue(first), pairedValue(second),
                        Math.min(axis.start(), d.start()), Math.max(axis.end(), d.end()), CoordKind.GRID2D);
                }
            }
            return new Coord(parseIntSafe(d.text()), 0, d.start(), d.end(), CoordKind.LINEAR);
        }
        if (useLetteredNames.get() && digits.isEmpty()) {
            Token l = edgeShortLetter(letters, effectiveCoordPosition() == CoordPosition.Suffix);
            if (l != null) {
                return new Coord(letterValue(l.text()), 0, l.start(), l.end(), CoordKind.LINEAR);
            }
        }
        return null;
    }

    private Coord extractSchemeCoord(String n) {
        String format = effectiveCoordinateFormat();
        if (format == null || format.isBlank()) return null;
        StringBuilder regex = new StringBuilder();
        int xGroup = -1, yGroup = -1, groupCount = 0, i = 0;
        while (i < format.length()) {
            if (format.startsWith("{x}", i)) {
                regex.append("([0-9]+|[A-Za-z]+)");
                xGroup = ++groupCount;
                i += 3;
            } else if (format.startsWith("{y}", i)) {
                regex.append("([0-9]+|[A-Za-z]+)");
                yGroup = ++groupCount;
                i += 3;
            } else {
                int next = format.indexOf('{', i);
                if (next < 0) next = format.length();
                regex.append(Pattern.quote(format.substring(i, next)));
                i = next;
            }
        }
        if (xGroup < 0 || yGroup < 0) return null;
        Matcher matcher = Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE).matcher(n);
        if (!matcher.find()) return null;
        Integer x = tokenValue(matcher.group(xGroup));
        Integer y = tokenValue(matcher.group(yGroup));
        if (x == null || y == null) return null;
        return new Coord(x, y, matcher.start(), matcher.end(), CoordKind.GRID2D);
    }

    private static String baseNameOf(String name, int start, int end) {
        String n = normName(name);
        String base = n.substring(0, Math.min(start, n.length())) + " " + n.substring(Math.min(end, n.length()));
        base = base.replaceAll("[^\\p{L}\\p{N}]+", " ").trim().toLowerCase(Locale.ROOT);
        return base.isEmpty() ? n.trim().toLowerCase(Locale.ROOT) : base;
    }

    /**
     * Groups worth sorting: only the hovered one under strict mode, else every group of 2+ that
     * is one real art. The frame preview iterates this same list, so the outline can never
     * promise a frame the sort would refuse, or hide one it would place.
     */
    private List<NamedGroup> sortableGroups(AllGroups selection) {
        List<NamedGroup> groups = selection.groups();
        if (strictHoveredGroup.get() && selection.hoveredGroupSize() >= 0) {
            if (groups.isEmpty()) return List.of();
            NamedGroup hovered = groups.get(0);
            return invalidArtShape(hovered.members()) == null ? List.of(hovered) : List.of();
        }
        List<NamedGroup> out = new ArrayList<>();
        for (NamedGroup group : groups) {
            if (group.members().size() >= 2 && invalidArtShape(group.members()) == null) out.add(group);
        }
        return out;
    }

    /**
     * Why these members cannot be one art frame, or null when they can.
     * <p>
     * A 2D or compass name carries its own position, so a real art fills its bounding rectangle
     * exactly: a hole means unrelated names that merely share a base name, and laying a frame
     * for them rearranges items into an art that does not exist. Linear indices are exempt on
     * purpose: a single index is a rank, not a position, so "Map 1, 2, 4, 7" stays the documented
     * partial-art fallback and is wrapped into map-width rows instead.
     * <p>
     * Only consulted while auto-size is on; auto-size = off means the user stated the shape.
     */
    private String invalidArtShape(List<Member> members) {
        if (!autoSize.get() || requireArtShape.get() == ArtShape.Off || members.isEmpty()) return null;
        CoordKind kind = members.get(0).kind;
        for (Member member : members) {
            if (member.kind != kind) {
                return "it mixes " + kindLabel(kind) + " and " + kindLabel(member.kind) + " coordinates";
            }
        }
        if (kind == CoordKind.LINEAR) return null;
        int minCol = Integer.MAX_VALUE, maxCol = Integer.MIN_VALUE;
        int minRow = Integer.MAX_VALUE, maxRow = Integer.MIN_VALUE;
        for (Member member : members) {
            minCol = Math.min(minCol, member.col);
            maxCol = Math.max(maxCol, member.col);
            minRow = Math.min(minRow, member.row);
            maxRow = Math.max(maxRow, member.row);
        }
        long spanWidth = (long) maxCol - minCol + 1;
        long spanHeight = (long) maxRow - minRow + 1;
        long limit = members.size();
        // Both dimensions are compared against the member count first, so the product below
        // cannot overflow on a name carrying an extreme coordinate.
        if (spanWidth <= limit && spanHeight <= limit && spanWidth * spanHeight == limit) return null;
        return "its coordinates span " + spanWidth + "x" + spanHeight + " (" + (spanWidth * spanHeight)
            + " slots) but only " + members.size() + " map(s) fill it";
    }

    private static String kindLabel(CoordKind kind) {
        return switch (kind) {
            case GRID2D -> "numbered 2D";
            case COMPASS -> "compass";
            case LINEAR -> "single-index";
        };
    }

    /**
     * Says why a group of two or more maps was left out of the sort. Returns how many groups were
     * skipped for their shape, so the caller can tell "no positions at all" apart from
     * "positions that do not form a frame".
     */
    private int reportUnsortableGroups(AllGroups selection, List<NamedGroup> kept) {
        int skipped = 0;
        for (NamedGroup group : selection.groups()) {
            if (kept.contains(group) || group.members().size() < 2) continue;
            String reason = invalidArtShape(group.members());
            if (reason == null) continue;
            skipped++;
            if (debug.get()) info("Skipped " + group.members().size() + " map(s) named \"" + group.base() + "\": "
                + reason + ".");
        }
        return skipped;
    }

    /**
     * The section the frame and spares may use. For FollowAnchor it follows the anchor map's
     * section (maps themselves were already collected from both); otherwise {@code memberSlots}.
     * The hotbar row is dropped when hotbar use is disabled, so the frame obeys the same gate as
     * the spares and the wrap chain.
     */
    private Set<Integer> frameSortable(AbstractContainerMenu menu, List<Slot> slots,
                                       Set<Integer> memberSlots, int anchorSlot) {
        Set<Integer> frame;
        if (sortArea.get() != SortArea.FollowAnchor || anchorSlot < 0 || anchorSlot >= slots.size()) {
            frame = new HashSet<>(memberSlots);
        } else {
            boolean anchorPlayer = slots.get(anchorSlot).container instanceof Inventory;
            frame = sectionSlots(menu, slots, anchorPlayer);
        }
        if (!allowHotbarSpares.get()) frame.removeIf(i -> isHotbarSlot(slots.get(i)));
        return frame;
    }

    /**
     * Storage slots of one section (container or player inventory) in the given GUI.
     */
    private Set<Integer> sectionSlots(AbstractContainerMenu menu, List<Slot> slots, boolean playerSection) {
        Set<Integer> valid = new HashSet<>();
        for (int i : allStorageSlots(menu, slots)) {
            if ((slots.get(i).container instanceof Inventory) == playerSection) valid.add(i);
        }
        return valid;
    }

    /**
     * Every storage slot of the GUI (container and player inventory sections, both).
     */
    private Set<Integer> allStorageSlots(AbstractContainerMenu menu, List<Slot> slots) {
        Set<Integer> valid = new HashSet<>();
        if (menu instanceof InventoryMenu) {
            for (int i = InventoryMenu.INV_SLOT_START; i < InventoryMenu.USE_ROW_SLOT_END; i++) {
                if (i < slots.size()) valid.add(i);
            }
            return valid;
        }
        if (menu instanceof ChestMenu || menu instanceof ShulkerBoxMenu) {
            for (int i = 0; i < slots.size(); i++) valid.add(i);
        }
        return valid;
    }

    /**
     * A spare is an empty sortable slot outside the frame; hotbar slots are excluded when disabled.
     */
    private boolean spareAllowed(Slot slot) {
        if (allowHotbarSpares.get()) return true;
        return !isHotbarSlot(slot);
    }

    /**
     * The player's hotbar row (menu slots 36-44 in the inventory GUI, 54-62 below a container).
     */
    private static boolean isHotbarSlot(Slot slot) {
        return slot.container instanceof Inventory && slot.getContainerSlot() < 9;
    }

    /**
     * Storage rows a frame may spill into when it is too tall for its own section. The hotbar row
     * is part of the chain only when hotbar use is allowed, the same gate as the spare rule.
     */
    private Set<Integer> rowChain(AbstractContainerMenu menu, List<Slot> slots) {
        Set<Integer> chain = allStorageSlots(menu, slots);
        if (!allowHotbarSpares.get()) chain.removeIf(i -> isHotbarSlot(slots.get(i)));
        return chain;
    }

    private Set<Integer> sortableSlotIndices(AbstractContainerMenu menu, List<Slot> slots) {
        SortArea area = sortArea.get();
        if (menu instanceof InventoryMenu) {
            if (area == SortArea.ContainerOnly) return Set.of(); // no container slots in this GUI
            // Player inventory main inventory (9-35) and hotbar (36-44). This deliberately excludes
            // the result slot (0), the 2x2 crafting grid (1-4), armor (5-8) and offhand (45).
            return allStorageSlots(menu, slots);
        }
        // A container GUI also carries the player's own inventory below the container; the
        // sort area decides whether the frame, the maps and the spares may use both sections
        // or only one of them. FollowAnchor's frame section is derived from the anchor instead.
        if (area == SortArea.ContainerOnly) return sectionSlots(menu, slots, false);
        if (area == SortArea.InventoryOnly) return sectionSlots(menu, slots, true);
        return allStorageSlots(menu, slots);
    }

    private OriginResult evaluateOrigin(List<Slot> slots, int width, int height, OrderedOffsets oo,
                                        int rowWidth, Set<Integer> sortable, int origin) {
        return evaluateOrigin(slots, width, height, oo, rowWidth, sortable, sortable, sortable, Set.of(), origin);
    }

    /**
     * Same scan, with the slots an earlier group empties counted as free.
     */
    private OriginResult evaluateOrigin(List<Slot> slots, int width, int height, OrderedOffsets oo,
                                        int rowWidth, Set<Integer> sortable, Set<Integer> vacated, int origin) {
        return evaluateOrigin(slots, width, height, oo, rowWidth, sortable, sortable, sortable, vacated, origin);
    }

    /**
     * Scores one candidate origin. {@code frameSlots} is every slot the art may occupy (its
     * section, or that section plus the rows below when the frame spills), {@code firstRowSlots} is
     * where the first row must start, and {@code spareSlots} is where displaced items may go.
     * {@code vacated} holds the slots an earlier group empties: they are free when this group's
     * moves run, so they count as free frame slots and as usable spares.
     */
    private OriginResult evaluateOrigin(List<Slot> slots, int width, int height, OrderedOffsets oo,
                                        int rowWidth, Set<Integer> frameSlots, Set<Integer> firstRowSlots,
                                        Set<Integer> spareSlots, Set<Integer> vacated, int origin) {
        int total = slots.size();
        if (origin < 0 || origin + oo.maxOffset() >= total) return new OriginResult(-1, 0, 0, 0, false);
        if (origin % rowWidth + oo.pages() * width > rowWidth) return new OriginResult(-1, 0, 0, 0, false);
        if (!firstRowSlots.contains(origin)) return new OriginResult(-1, 0, 0, 0, false);

        Set<Integer> frame = layoutCells(oo, width, height, origin);
        if (!frameSlots.containsAll(frame)) return new OriginResult(-1, 0, 0, 0, false);

        Set<Integer> memberSources = new HashSet<>();
        for (Member m : oo.ordered()) memberSources.add(m.slot);
        int spares = 0;
        for (int slot : spareSlots) {
            if (!frame.contains(slot) && (slots.get(slot).getItem().isEmpty() || vacated.contains(slot))
                && spareAllowed(slots.get(slot))) {
                spares++;
            }
        }
        int correct = 0, displaced = 0;
        boolean needsCycle = false;
        for (int idx = 0; idx < oo.ordered().size(); idx++) {
            Member member = oo.ordered().get(idx);
            int target = origin + oo.offsets().get(idx);
            // With auto-size off the configured frame can be smaller than the art's span, so a
            // member offset may leave the frame; reject rather than read past the slot list.
            if (target < 0 || target >= total) return new OriginResult(-1, 0, 0, 0, false);
            // A slot an earlier group empties is free when this group's moves run.
            if (vacated.contains(target)) continue;
            ItemStack occ = slots.get(target).getItem();
            if (occ.isEmpty()) continue;
            if (member.slot == target) {
                correct++;
                continue;
            }
            Integer want = mapIdOf(member.stack);
            Integer got = mapIdOf(occ);
            if (want != null && want.equals(got)) {
                correct++;
                continue;
            }
            if (memberSources.contains(target)) {
                needsCycle = true;
                continue;
            }
            displaced++;
        }
        return new OriginResult(origin, correct, displaced, spares, needsCycle);
    }

    /**
     * Scans every legal frame origin in the container and picks the best one: feasible (enough
     * spares for displaced items), most already-correct maps, fewest displaced, earliest on ties.
     */
    private OriginResult findBestOrigin(List<Slot> slots, int width, int height, OrderedOffsets oo,
                                        int rowWidth, Set<Integer> sortable) {
        return findBestOrigin(slots, width, height, oo, rowWidth, sortable, sortable, sortable, Set.of());
    }

    /**
     * Same scan, with the slots an earlier group empties counted as free.
     */
    private OriginResult findBestOrigin(List<Slot> slots, int width, int height, OrderedOffsets oo,
                                        int rowWidth, Set<Integer> sortable, Set<Integer> vacated) {
        return findBestOrigin(slots, width, height, oo, rowWidth, sortable, sortable, sortable, vacated);
    }

    /**
     * Same scan, but with the frame, its first row and its spares allowed on different slot sets.
     */
    private OriginResult findBestOrigin(List<Slot> slots, int width, int height, OrderedOffsets oo,
                                        int rowWidth, Set<Integer> frameSlots, Set<Integer> firstRowSlots, Set<Integer> spareSlots) {
        return findBestOrigin(slots, width, height, oo, rowWidth, frameSlots, firstRowSlots, spareSlots, Set.of());
    }

    /**
     * Same scan, with the slots an earlier group empties counted as free.
     */
    private OriginResult findBestOrigin(List<Slot> slots, int width, int height, OrderedOffsets oo,
                                        int rowWidth, Set<Integer> frameSlots, Set<Integer> firstRowSlots, Set<Integer> spareSlots,
                                        Set<Integer> vacated) {
        int total = slots.size();
        int maxOffset = oo.maxOffset();
        if (maxOffset >= total) return new OriginResult(-1, 0, 0, 0, false);
        OriginResult best = new OriginResult(-1, 0, 0, 0, false);
        int bestScore = Integer.MIN_VALUE;
        for (int origin = 0; origin + maxOffset < total; origin++) {
            OriginResult candidate = evaluateOrigin(slots, width, height, oo, rowWidth,
                frameSlots, firstRowSlots, spareSlots, vacated, origin);
            if (candidate.origin < 0) continue;
            boolean feasible = candidate.spares >= candidate.displaced
                && (!candidate.needsCycle || candidate.spares >= 1);
            int score = (feasible ? 1_000_000 : 0) + candidate.correct * 100 - candidate.displaced * 10 - origin;
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private static final class NameEntry {
        final int slot;
        final ItemStack stack;
        final String name;

        NameEntry(int slot, ItemStack stack, String name) {
            this.slot = slot;
            this.stack = stack;
            this.name = name;
        }
    }

    private static final class Member {
        final int slot;
        final ItemStack stack;
        final int col;
        final int row;
        final CoordKind kind;

        Member(int slot, ItemStack stack, int col, int row, CoordKind kind) {
            this.slot = slot;
            this.stack = stack;
            this.col = col;
            this.row = row;
            this.kind = kind;
        }
    }

    private Integer tokenValue(String token) {
        if (token == null || token.isEmpty()) return null;
        char first = token.charAt(0);
        if (Character.isDigit(first)) {
            try {
                return Integer.parseInt(token);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (useLetteredNames.get()) {
            int value = letterValue(token);
            return value < 0 ? null : value;
        }
        return null;
    }

    private static Integer mapIdOf(ItemStack stack) {
        if (stack.getItem() != Items.FILLED_MAP) return null;
        MapId id = stack.get(DataComponents.MAP_ID);
        return id == null ? null : id.id();
    }


    private enum SortArea {ContainerOnly, InventoryOnly, Both, FollowAnchor}
}
