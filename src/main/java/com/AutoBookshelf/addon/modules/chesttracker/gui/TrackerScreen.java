// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker.gui;

import com.AutoBookshelf.addon.modules.chesttracker.ChestTrackerModule;
import com.AutoBookshelf.addon.modules.chesttracker.Settings;
import com.AutoBookshelf.addon.modules.chesttracker.TrackerLog;
import com.AutoBookshelf.addon.modules.chesttracker.TrackerServices;
import com.AutoBookshelf.addon.modules.chesttracker.data.*;
import com.AutoBookshelf.addon.modules.chesttracker.search.*;
import com.AutoBookshelf.addon.modules.chesttracker.track.Trash;
import com.mojang.blaze3d.platform.InputConstants;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Stream;

/**
 * Search screen over everything remembered. Empty search lists containers; typing lists matching
 * items (nested ones included). Vanilla widgets only, rows tall enough to tap (PojavLauncher).
 */
public final class TrackerScreen extends Screen {
    private static final int ROW_HEIGHT = 46; // four lines: what / which container / where+status / notes
    private static final int MAX_ROWS = 500;

    private EditBox search;
    private @Nullable String pendingTooltip;
    /**
     * Keys of selected containers (Ctrl/Shift-click, or taps in Select mode).
     */
    private final Set<String> selection = new LinkedHashSet<>();
    private boolean selectMode;
    private int lastToggled = -1;
    // Normal bar / bulk bar buttons; shown and hidden in place so the list keeps its scroll.
    private Button viewButton, destroyedButton, selectButton, doneButton;
    private Button groupButton;
    private Button backButton;
    /**
     * In the trash: one button per kind, the open one greyed out.
     */
    private final List<Button> kindButtons = new ArrayList<>();
    private Button bulkPrimary, bulkDelete, selectAllButton, cancelButton;
    private int tooltipX, tooltipY;
    private ResultList list;
    private boolean showDestroyed = true;
    private boolean groupNearby = true;
    private ChestTrackerModule.GroupMode groupMode = ChestTrackerModule.GroupMode.Chunk;
    private Cluster viewingCluster;
    /**
     * The results shown are close matches, not exact ones.
     */
    private boolean fuzzy;
    /**
     * How many of the searched item the memory holds, when the search is an item ("totems").
     */
    private @Nullable Component tallyHint;
    /**
     * Containers the current search hasn't looked at yet.
     */
    private int indexing;
    /**
     * The search being worked through, a few milliseconds per frame; null when finished.
     */
    private @Nullable SearchJob job;
    private boolean waitingForBank;
    /**
     * false = remembered containers, true = the trash.
     */
    private boolean trashView;
    /**
     * Which part of the trash is open. Kept apart so broken blocks don't bury entities.
     */
    private TrashKind trashKind = TrashKind.ENTITIES;
    /**
     * The grid: every item the memory holds at a glance, one slot per item type with its total.
     */
    private boolean gridView;
    private Button gridButton;
    private List<GridCell> grid = List.of();
    private int gridScroll, gridMaxScroll, gridBottom, gridRevision = -1;
    /**
     * The list's place to go back to once the rebuilt list is long enough (-1: none).
     */
    private double pendingScroll = -1;
    private @Nullable Row pendingSelected;
    /**
     * The next rebuild shows a different list (the trash, another part of it): start at the top.
     */
    private boolean freshList;
    private int gridPressed = -1;
    private boolean gridDragged;
    /**
     * The hovered cell's tooltip, worked out once per cell rather than every frame.
     */
    private int tooltipCell = -1;
    private List<net.minecraft.util.FormattedCharSequence> cellTooltip = List.of();
    private static final int CELL = 20;

    /**
     * A s of text trimmed to a pixel budget. Keyed by the exact (text, budget) pair; the
     * same row runs this on every visible line every frame, so the cache is worth its entry
     * cost and self-cleans at 256 entries.
     */
    private record TrimKey(String text, int maxWidth) {
    }

    private static final Map<TrimKey, String> TRIM_CACHE = new java.util.LinkedHashMap<>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<TrimKey, String> eldest) {
            return size() > 256;
        }
    };

    /**
     * entity icon per {@link ContainerRecord#type}; covers getInstance() mutations and keeps
     * the value live across DetailScreen and row icons without rebuilding an ItemStack.
     */
    private static final Map<String, ItemStack> ENTITY_ICON_CACHE = new HashMap<>();

    /**
     * Translated row text cached per record fingerprint; avoids dozens of translatable +
     * string concat allocations per visible row per frame.
     */
    public record RowText(String title, List<Note> notes, @Nullable String explain) {
    }

    // LRU eviction at 256 entries is comfortably larger than any visible page.
    private static final Map<String, RowText> ROW_TEXT_CACHE = new java.util.LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, RowText> eldest) {
            return size() > 256;
        }
    };

    private static String fingerprint(ContainerRecord r) {
        // Bank revision busts the cache on every bank write: any status/flag/item change
        // lands here, including ones the individual fields below don't name.
        // The minute bucket expires time-displaying strings ("seen_ago", "never_opened")
        // at minute resolution, which keeps those accurate without re-invalidating every frame.
        long minute = System.currentTimeMillis() / 60000;
        int rev = TrackerServices.banks() == null ? -1 : (TrackerServices.banks().bank() == null ? -1 : TrackerServices.banks().bank().revision());
        StringBuilder sb = new StringBuilder(64);
        sb.append(minute).append('|').append(r.key()).append('|').append(r.lastOpened).append('|').append(r.status).append('|')
            .append(r.trashedAt).append('|').append(r.hasSnapshot()).append('|').append(r.dimension)
            .append('|').append(r.name).append('|').append(rev)
            .append(r instanceof EntityRecord e && e.reappeared ? "|R" : "|");
        for (String flag : r.flags) sb.append("|F").append(flag);
        for (String hint : r.hints) sb.append("|H").append(hint);
        return sb.toString();
    }

    /**
     * Cached {@link #title} + {@link #notes} for the rows; built once per record fingerprint.
     */
    public static RowText rowText(ContainerRecord r) {
        String fp = fingerprint(r);
        RowText cached = ROW_TEXT_CACHE.get(fp);
        if (cached != null) return cached;
        RowText built = new RowText(title(r), notes(r), statusExplain(r));
        if (ROW_TEXT_CACHE.size() > 256) ROW_TEXT_CACHE.clear();
        ROW_TEXT_CACHE.put(fp, built);
        return built;
    }

    /**
     * {@code shown}: the total, plus what may be gone while "Destroyed: shown".
     */
    private record GridCell(String id, ItemStack stack, long shown) {
    }

    /**
     * The trash's three views: storage entities, villagers and traders, and blocks.
     */
    public enum TrashKind {
        ENTITIES("chesttracker.trash.entities"),
        TRADES("chesttracker.trash.trades"),
        BLOCKS("chesttracker.trash.blocks");

        final String key;

        TrashKind(String key) {
            this.key = key;
        }

        Stream<? extends ContainerRecord> trashed(MemoryBank bank) {
            Stream<? extends ContainerRecord> all = this == TRADES ? bank.traders()
                : bank.containers().filter(r -> (r instanceof BlockRecord) == (this == BLOCKS));
            return all.filter(ContainerRecord::isTrashed);
        }
    }

    /**
     * The open row menu (right-click or the row's "⋮"), empty when closed.
     */
    private final List<Button> menu = new ArrayList<>();
    private int menuX, menuY, menuW, menuH;

    public TrackerScreen() {
        super(Component.translatable("chesttracker.title"));
    }

    /**
     * Opens straight into one part of the trash (the Trades screen's Trash button).
     */
    public static TrackerScreen trash(TrashKind kind) {
        TrackerScreen screen = new TrackerScreen();
        screen.trashView = true;
        screen.trashKind = kind;
        return screen;
    }

    @Override
    protected void init() {
        int fieldWidth = Math.min(300, width - 32);
        String previous = search != null ? search.getValue() : "";
        search = new EditBox(font, (width - fieldWidth) / 2, 24, fieldWidth, 20,
            Component.translatable("chesttracker.search"));
        search.setHint(Component.translatable("chesttracker.search"));
        search.setValue(previous);
        search.setResponder(q -> {
            // A new search starts at its top.
            pendingScroll = -1;
            pendingSelected = null;
            refresh();
        });
        addRenderableWidget(search);

        // Coming back from a detail view (or a resize) rebuilds the list: keep its place and the
        // container that was picked, so checking 400 chests one by one doesn't start over each time.
        if (list != null && !freshList) {
            pendingScroll = list.scrollAmount();
            ResultList.Entry picked = list.getSelected();
            pendingSelected = picked == null ? null : picked.row;
        }
        freshList = false;
        list = new ResultList(minecraft, width, height - 30 - LIST_Y, LIST_Y);
        addRenderableWidget(list);
        for (Button tab : ScreenTabs.create(minecraft, width, false)) addRenderableWidget(tab);

        // Bottom bar: views and filters, or bulk actions while something is selected.
        menu.clear();
        // "Trash (n)" in the list, "<- Back to list" in the trash, which has a button per kind.
        viewButton = addRenderableWidget(Button.builder(trashLabel(), b -> {
            trashView = !trashView;
            clearSelection();
            freshList = true;
            rebuildWidgets();
        }).build());
        kindButtons.clear();
        for (TrashKind kind : TrashKind.values()) {
            Button button = addRenderableWidget(Button.builder(kindLabel(kind), b -> {
                trashKind = kind;
                clearSelection();
                freshList = true;
                rebuildWidgets();
            }).build());
            button.active = kind != trashKind;
            kindButtons.add(button);
        }
        gridButton = addRenderableWidget(Button.builder(gridLabel(), b -> {
            gridView = !gridView;
            gridScroll = 0;
            b.setMessage(gridLabel());
            refresh();
        }).build());
        destroyedButton = addRenderableWidget(Button.builder(destroyedLabel(), b -> {
            setShowDestroyed(!showDestroyed);
            b.setMessage(destroyedLabel());
            updateBar();
            refresh();
        }).build());
        groupButton = addRenderableWidget(Button.builder(groupLabel(), b -> {
            setGroupNearby(!currentGroupNearby());
            b.setMessage(groupLabel());
            updateBar();
            refresh();
        }).build());
        backButton = addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> {
            viewingCluster = null;
            refresh();
            updateBar();
        }).build());
        selectButton = addRenderableWidget(Button.builder(Component.translatable("chesttracker.select"), b -> {
            selectMode = true;
            updateBar();
        }).build());
        doneButton = addRenderableWidget(Button.builder(Component.translatable("chesttracker.find"), b -> locateCurrentSelection()).build());
        bulkPrimary = addRenderableWidget(Button.builder(Component.empty(), b -> bulkPrimary()).build());
        bulkDelete = addRenderableWidget(Button.builder(Component.empty(), b -> bulkDelete()).build());
        selectAllButton = addRenderableWidget(Button.builder(Component.translatable("chesttracker.select_all"), b -> {
            for (ResultList.Entry e : list.children()) {
                if (e.row != null && !(e.row.record() instanceof InventoryRecord)) selection.add(e.row.record().key());
            }
            selectMode = true;
            updateBar();
        }).build());
        cancelButton = addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> {
            clearSelection();
            updateBar();
        }).build());
        updateBar();

        setInitialFocus(search);
        refresh();
    }

    /**
     * "Trash (3)" in the list, "<- Back to list" in the trash: a place to go, not a mode.
     */
    private Component trashLabel() {
        if (trashView) return Component.translatable("chesttracker.trash_back");
        return Component.translatable("chesttracker.trash_open", totalTrashed());
    }

    /**
     * Everything in the trash, all three kinds.
     */
    public static long totalTrashed() {
        MemoryBank bank = TrackerServices.banks().bank();
        if (bank == null) return 0;
        long n = 0;
        for (TrashKind kind : TrashKind.values()) n += kind.trashed(bank).count();
        return n;
    }

    /**
     * "Entities (3)", "Trades (1)", "Blocks (9)".
     */
    private static Component kindLabel(TrashKind kind) {
        MemoryBank bank = TrackerServices.banks().bank();
        return Component.translatable(kind.key, bank == null ? 0 : kind.trashed(bank).count());
    }

    /**
     * Persisted "show destroyed" pref, kept in sync with the module setting.
     */
    private void setShowDestroyed(boolean value) {
        showDestroyed = value;
        ChestTrackerModule module = Modules.get().get(ChestTrackerModule.class);
        if (module != null) module.setShowDestroyed(value);
    }

    private boolean currentShowDestroyed() {
        ChestTrackerModule module = Modules.get().get(ChestTrackerModule.class);
        return module == null ? showDestroyed : module.showDestroyed();
    }

    private void setGroupNearby(boolean value) {
        groupNearby = value;
        ChestTrackerModule module = Modules.get().get(ChestTrackerModule.class);
        if (module != null) module.setGroupNearby(value);
    }

    private boolean currentGroupNearby() {
        ChestTrackerModule module = Modules.get().get(ChestTrackerModule.class);
        return module == null ? groupNearby : module.groupNearby();
    }

    private void setGroupMode(ChestTrackerModule.GroupMode value) {
        groupMode = value;
        ChestTrackerModule module = Modules.get().get(ChestTrackerModule.class);
        if (module != null) module.setGroupMode(value);
    }

    private ChestTrackerModule.GroupMode currentGroupMode() {
        ChestTrackerModule module = Modules.get().get(ChestTrackerModule.class);
        return module == null ? groupMode : module.groupMode();
    }

    private Component destroyedLabel() {
        return Component.translatable(currentShowDestroyed() ? "chesttracker.destroyed_shown"
            : "chesttracker.destroyed_hidden");
    }

    private Component groupLabel() {
        return Component.translatable(currentGroupNearby() ? "chesttracker.grouped" : "chesttracker.un_grouped");
    }

    /**
     * Names the view the button goes to: "Grid" in the list, "List" in the grid.
     */
    private Component gridLabel() {
        return Component.translatable(gridView ? "chesttracker.grid.to_list" : "chesttracker.grid.to_grid");
    }

    private boolean showingGrid() {
        return gridView && !trashView;
    }

    /**
     * Lays out whichever bottom bar applies: views and filters, or bulk actions.
     */
    private void updateBar() {
        boolean bulk = selectMode || !selection.isEmpty();
        List<Button> shown = new ArrayList<>();
        for (Button b : List.of(viewButton, gridButton, destroyedButton, groupButton, backButton, selectButton, doneButton,
            bulkPrimary, bulkDelete, selectAllButton, cancelButton)) {
            b.visible = false;
        }
        for (Button b : kindButtons) b.visible = false;
        if (bulk) {
            int n = selection.size();
            bulkPrimary.setMessage(Component.translatable(trashView ? "chesttracker.bulk.restore"
                : "chesttracker.bulk.mark", n));
            bulkPrimary.active = n > 0;
            shown.add(bulkPrimary);
            if (trashView) {
                bulkDelete.setMessage(Component.translatable("chesttracker.bulk.delete", n));
                bulkDelete.active = n > 0;
                shown.add(bulkDelete);
            }
            shown.add(selectAllButton);
            shown.add(cancelButton);
        } else {
            if (viewingCluster != null) {
                shown.add(backButton);
            } else {
                shown.add(viewButton);
                if (trashView) {
                    shown.addAll(kindButtons);
                } else {
                    shown.add(gridButton);
                    shown.add(destroyedButton);
                    shown.add(groupButton);
                }
            }
            if (!showingGrid()) shown.add(selectButton);
            shown.add(doneButton);
        }
        for (Button b : shown) b.visible = true;
        // The list ends above the buttons, which take two rows on a narrow screen
        int top = ButtonBar.layout(shown, font, width, height, 120);
        list.updateSizeAndPosition(width, top - 4 - LIST_Y, LIST_Y);
        list.visible = !showingGrid();
        gridBottom = top - 4;
    }

    private void clearSelection() {
        selection.clear();
        selectMode = false;
        lastToggled = -1;
    }

    private List<ContainerRecord> selectedRecords() {
        MemoryBank bank = TrackerServices.banks().bank();
        List<ContainerRecord> out = new ArrayList<>();
        if (bank == null) return out;
        for (String key : selection) {
            ContainerRecord r = bank.getByKey(key);
            if (r != null) out.add(r);
        }
        return out;
    }

    /**
     * Mark destroyed (one undoable action) or, in the trash view, restore the selection.
     */
    private void bulkPrimary() {
        MemoryBank bank = TrackerServices.banks().bank();
        List<ContainerRecord> records = selectedRecords();
        if (bank == null || records.isEmpty()) return;
        if (trashView) {
            for (ContainerRecord r : records) Trash.restore(bank, r);
        } else {
            Trash.trashArea(bank, records);
            Messages.chat(Component.translatable("chesttracker.area.done", records.size()));
        }
        clearSelection();
        refresh();
        updateBar();
    }

    private void bulkDelete() {
        MemoryBank bank = TrackerServices.banks().bank();
        List<ContainerRecord> records = selectedRecords();
        if (bank == null || records.isEmpty()) return;
        minecraft.setScreenAndShow(new ConfirmScreen(yes -> {
            if (yes) {
                for (ContainerRecord r : records) Trash.deletePermanently(bank, r);
                clearSelection();
            }
            minecraft.setScreenAndShow(this);
        }, Component.translatable("chesttracker.bulk.delete_confirm", records.size()),
            Component.translatable("chesttracker.bulk.delete_body")));
    }

    /**
     * Opens the action menu for a container at the given screen position.
     */
    private void openMenu(ContainerRecord record, double x, double y) {
        closeMenu();
        if (record instanceof InventoryRecord) return; // nothing to mark destroyed
        List<Button.Builder> items = new ArrayList<>();
        if (trashView) {
            items.add(Button.builder(Component.translatable("chesttracker.restore"), b -> act(() -> {
                MemoryBank bank = TrackerServices.banks().bank();
                if (bank != null) Trash.restore(bank, record);
            })));
            items.add(Button.builder(Component.translatable("chesttracker.delete"), b -> {
                closeMenu();
                confirmDelete(record);
            }));
        } else {
            boolean hasExplanations = statusExplain(record) != null || notes(record).stream().anyMatch(n -> n.explain() != null);
            if (hasExplanations) {
                items.add(Button.builder(Component.translatable("chesttracker.show_warnings"), b -> {
                    closeMenu();
                    minecraft.setScreenAndShow(new DetailScreen(record, this, ""));
                }));
            }
            items.add(Button.builder(Component.translatable("chesttracker.find"), b -> {
                closeMenu();
                locate(record);
            }));
            items.add(Button.builder(markLabel(record), b -> act(() -> {
                MemoryBank bank = TrackerServices.banks().bank();
                if (bank != null) Trash.trash(bank, record);
            })));
        }
        menuW = 120;
        menuH = items.size() * 20;
        menuX = (int) Math.min(x, width - menuW - 4);
        menuY = (int) Math.min(y, height - menuH - 4);
        for (int i = 0; i < items.size(); i++) {
            menu.add(addRenderableWidget(items.get(i).bounds(menuX, menuY + i * 20, menuW, 20).build()));
        }
    }

    /**
     * Highlights the nearest container holding {@code item} (same ranking as the list) and closes.
     */
    private void locateNearest(Item item) {
        MemoryBank bank = TrackerServices.banks().bank();
        if (bank == null || minecraft.player == null || minecraft.level == null) return;
        ContainerRecord best = null;
        double bestDist = Double.MAX_VALUE;
        for (String key : TrackerServices.index().recordsForItem(item)) {
            ContainerRecord record = bank.getByKey(key);
            if (record == null || record.isTrashed()) continue;
            double dist = sortDistance(record);
            if (dist < bestDist) {
                bestDist = dist;
                best = record;
            }
        }
        if (best != null) locate(best);
    }

    /**
     * Highlights the container in the world for 30 seconds and closes the screen (like Detail's Done).
     */
    private void locate(ContainerRecord record) {
        ChestTrackerModule module = Modules.get().get(ChestTrackerModule.class);
        if (module != null) module.highlightContainer(record);
        minecraft.setScreenAndShow(null);
    }

    /**
     * Highlights the container the row selection points at (or, with no selection, the first row of
     * the list, which is sorted nearest-first). If neither is a real container, nothing happens and
     * the player keeps the screen.
     */
    private void locateCurrentSelection() {
        ResultList.Entry selected = list.getSelected();
        ContainerRecord record = selected != null && selected.row != null ? selected.row.record() : null;
        if (record == null || record instanceof InventoryRecord) {
            record = null;
            for (ResultList.Entry entry : list.children()) {
                if (entry.row == null || entry.row.record() instanceof InventoryRecord) continue;
                record = entry.row.record();
                break;
            }
        }
        if (record == null) {
            Messages.actionBar(Component.translatable("chesttracker.find_none"));
            return;
        }
        locate(record);
    }

    private void act(Runnable action) {
        closeMenu();
        action.run();
        refresh();
    }

    private void closeMenu() {
        for (Button b : menu) removeWidget(b);
        menu.clear();
    }

    private boolean insideMenu(double x, double y) {
        return x >= menuX && x < menuX + menuW && y >= menuY && y < menuY + menuH;
    }

    /**
     * While a search is still waiting for containers to be indexed, try again every tick.
     */
    @Override
    public void tick() {
        super.tick();
        // The bank just finished loading while the screen was open. (A search in progress
        // carries on by itself in render, a few milliseconds per frame.)
        if (waitingForBank && TrackerServices.banks().bank() != null) refreshKeepingScroll();
        waitingForBank = TrackerServices.banks().bank() == null;
        // The grid follows the memory while it is open (a container opened, an entity moved).
        MemoryBank bank = TrackerServices.banks().bank();
        if (showingGrid() && bank != null && bank.revision() != gridRevision) refreshGrid();
    }

    private void refreshKeepingScroll() {
        double scroll = list.scrollAmount();
        refresh();
        list.setScrollAmount(scroll);
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        input = CloseKey.asEscape(this, minecraft, input);
        if (input.key() == InputConstants.KEY_ESCAPE) {
            // Esc closes an open menu, then clears the search, and only then closes the screen.
            if (!menu.isEmpty()) {
                closeMenu();
                return true;
            }
            if (selectMode || !selection.isEmpty()) {
                clearSelection();
                updateBar();
                return true;
            }
            if (!search.getValue().isEmpty()) {
                search.setValue("");
                return true;
            }
        }
        // H locates the selected row in the world (like middle-click), but only when the search
        // field isn't focused, so typing "h" in a query never triggers it.
        if (input.key() == InputConstants.KEY_H && !search.isFocused() && !showingGrid()) {
            ResultList.Entry selected = list.getSelected();
            if (selected != null && selected.row != null && !(selected.row.record() instanceof InventoryRecord)) {
                locate(selected.row.record());
                return true;
            }
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (!menu.isEmpty()) {
            if (insideMenu(click.x(), click.y())) return super.mouseClicked(click, doubled);
            closeMenu(); // a click anywhere else just closes the menu
            return true;
        }
        if (showingGrid() && click.button() == InputConstants.MOUSE_BUTTON_LEFT && gridCellAt(click.x(), click.y()) >= 0) {
            // Acted on at release, so a drag (scrolling on a touch screen) doesn't open anything.
            gridPressed = gridCellAt(click.x(), click.y());
            gridDragged = false;
            return true;
        }
        // Middle-click a grid cell: highlight the nearest container holding that item.
        if (showingGrid() && click.button() == InputConstants.MOUSE_BUTTON_MIDDLE && gridCellAt(click.x(), click.y()) >= 0) {
            locateNearest(grid.get(gridCellAt(click.x(), click.y())).stack().getItem());
            return true;
        }
        if (click.button() == InputConstants.MOUSE_BUTTON_RIGHT && !showingGrid()) {
            ResultList.Entry entry = list.entryAt(click.x(), click.y());
            if (entry != null && entry.row != null) {
                list.setSelected(entry);
                openMenu(entry.row.record(), click.x(), click.y());
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    /**
     * A tap on a grid cell (pressed and released on it, no drag) lists where that item is.
     */
    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        if (gridPressed >= 0) {
            int pressed = gridPressed;
            gridPressed = -1;
            if (!gridDragged && showingGrid() && gridCellAt(click.x(), click.y()) == pressed) {
                String name = grid.get(pressed).stack().getHoverName().getString();
                gridView = false;
                gridButton.setMessage(gridLabel());
                search.setValue(name); // searches the list for it
                updateBar();
                Messages.actionBar(Component.translatable("chesttracker.grid.tap_confirm", name));
            }
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (showingGrid()) {
            gridScroll = Math.max(0, Math.min(gridMaxScroll, gridScroll - (int) Math.round(vertical * CELL)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    /**
     * Dragging the grid scrolls it, for touch screens without a wheel.
     */
    @Override
    public boolean mouseDragged(MouseButtonEvent click, double offsetX, double offsetY) {
        if (showingGrid() && click.button() == InputConstants.MOUSE_BUTTON_LEFT && click.y() >= LIST_Y && click.y() < gridBottom) {
            gridDragged |= Math.abs(offsetY) > 0.5;
            gridScroll = Math.max(0, Math.min(gridMaxScroll, gridScroll - (int) Math.round(offsetY)));
            return true;
        }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    private void confirmDelete(ContainerRecord record) {
        MemoryBank bank = TrackerServices.banks().bank();
        if (bank == null || record == null) return;
        minecraft.setScreenAndShow(new ConfirmScreen(yes -> {
            if (yes) Trash.deletePermanently(bank, record);
            minecraft.setScreenAndShow(this);
        }, Component.translatable("chesttracker.delete_confirm"),
            Component.translatable("chesttracker.delete_confirm_body", label(record))));
    }

    /**
     * Starts a new search and shows whatever it finds within this frame's budget; the rest
     * follows over the next frames (see {@link SearchJob}), so the nearest results appear at once
     * and typing never waits for a big base to be searched to the end.
     */
    private void refresh() {
        if (viewButton != null) {
            viewButton.setMessage(trashLabel());
            for (TrashKind kind : TrashKind.values()) kindButtons.get(kind.ordinal()).setMessage(kindLabel(kind));
            updateBar(); // "Trash (n)" grows with n
        }
        String query = search.getValue().trim().toLowerCase(Locale.ROOT);
        if (query.startsWith("@")) query = query.substring(1); // @head:Notch until full filters (4.6)
        fuzzy = false;
        MemoryBank bank = TrackerServices.banks().bank();
        tallyHint = bank == null || trashView || query.isEmpty() ? null : TallyReport.hint(query, bank);
        if (showingGrid()) {
            // The grid needs no search through containers: it comes from the tally.
            job = null;
            indexing = 0;
            list.setRows(List.of());
            refreshGrid();
            return;
        }
        Set<Item> named = namedItems(query);
        // Candidate records from the inverted index (word -> records, item -> records), so the
        // search only verifies likely records instead of walking the whole bank per keystroke.
        // Empty when nothing is indexed yet: the job then runs its full scan and warms the index.
        Set<String> candidates = null;
        if (!query.isEmpty()) {
            ItemIndex index = TrackerServices.index();
            Set<String> cand = new HashSet<>();
            for (String word : Fuzzy.words(query)) {
                if (word.isEmpty()) continue;
                cand.addAll(index.recordsForToken(word));
                if (word.length() >= 3) cand.addAll(index.recordsForTokenPrefix(word));
            }
            // Mid-token substrings ("mond") miss the token indexes: trigram candidates cover them.
            if (query.length() >= 3) cand.addAll(index.recordsForTrigrams(query));
            for (Item item : named) cand.addAll(index.recordsForItem(item));
            if (!cand.isEmpty()) candidates = cand;
        }
        job = new SearchJob(query, records(), named, candidates);
        list.setRows(List.of());
        continueSearch();
    }

    /**
     * The items a search names by alias or exact name ("totems", "gapples"), which count as exact
     * matches although their text doesn't contain the words typed.
     */
    private static Set<Item> namedItems(String query) {
        if (query.isEmpty()) return Set.of();
        ItemQuery.Result result = ItemQuery.resolve(query);
        if (result.kind() != ItemQuery.Kind.ALIAS && result.kind() != ItemQuery.Kind.EXACT) return Set.of();
        Set<Item> items = new HashSet<>();
        for (String id : result.ids()) {
            Identifier parsed = Identifier.tryParse(id);
            if (parsed != null) BuiltInRegistries.ITEM.getOptional(parsed).ifPresent(items::add);
        }
        return items;
    }

    /**
     * One cell per item type the memory holds, most first, narrowed to the search: item names, and
     * players' words like "gapples" or "totems" (see {@link ItemQuery}).
     */
    private void refreshGrid() {
        MemoryBank bank = TrackerServices.banks().bank();
        tooltipCell = -1;
        if (bank == null) {
            grid = List.of();
            gridRevision = -1;
            return;
        }
        String query = search.getValue().trim().toLowerCase(Locale.ROOT);
        Set<Item> named = namedItems(query);
        List<GridCell> cells = new ArrayList<>();
        Tally.of(bank).items.forEach((id, count) -> {
            long shown = count.total + (currentShowDestroyed() ? count.maybeGone : 0);
            if (shown <= 0) return;
            Identifier parsed = Identifier.tryParse(id);
            Item item = parsed == null ? null : BuiltInRegistries.ITEM.getOptional(parsed).orElse(null);
            if (item == null || item == Items.AIR) return;
            ItemStack stack = new ItemStack(item);
            if (!query.isEmpty() && !named.contains(item)
                && !stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(query)) return;
            cells.add(new GridCell(id, stack, shown));
        });
        cells.sort(Comparator.comparingLong(GridCell::shown).reversed()
            .thenComparing(c -> c.stack().getHoverName().getString()));
        grid = cells;
        gridRevision = bank.revision();
    }

    private int gridColumns() {
        return Math.max(1, Math.min(24, (width - 24) / CELL));
    }

    private int gridCellAt(double mx, double my) {
        if (my < LIST_Y || my >= gridBottom) return -1;
        int cols = gridColumns();
        int left = (width - cols * CELL) / 2;
        if (mx < left || mx >= left + cols * CELL) return -1;
        int i = (int) ((my - LIST_Y + gridScroll) / CELL) * cols + (int) ((mx - left) / CELL);
        return i < grid.size() ? i : -1;
    }

    /**
     * 3456 stays as it is; bigger numbers are shortened so they fit under the slot: 12k, 1.2M.
     */
    private static String shortCount(long n) {
        if (n < 10_000) return Long.toString(n);
        if (n < 1_000_000) return n / 1000 + "k";
        return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
    }

    private void renderGrid(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        int cols = gridColumns();
        int left = (width - cols * CELL) / 2;
        int rows = (grid.size() + cols - 1) / cols;
        gridMaxScroll = Math.max(0, rows * CELL - (gridBottom - LIST_Y));
        gridScroll = Math.max(0, Math.min(gridMaxScroll, gridScroll));
        int hovered = menu.isEmpty() ? gridCellAt(mouseX, mouseY) : -1;
        context.enableScissor(0, LIST_Y, width, gridBottom);
        for (int i = 0; i < grid.size(); i++) {
            int x = left + (i % cols) * CELL + 1;
            int y = LIST_Y + (i / cols) * CELL - gridScroll + 1;
            if (y + CELL < LIST_Y || y > gridBottom) continue;
            GridCell cell = grid.get(i);
            context.fill(x, y, x + 18, y + 18, i == hovered ? 0x60FFFFFF : 0x28FFFFFF);
            context.item(cell.stack(), x + 1, y + 1);
            context.itemDecorations(font, cell.stack(), x + 1, y + 1, shortCount(cell.shown()));
        }
        context.disableScissor();
        if (gridMaxScroll > 0) {
            int view = gridBottom - LIST_Y;
            int barH = Math.max(12, view * view / (view + gridMaxScroll));
            int barY = LIST_Y + (view - barH) * gridScroll / gridMaxScroll;
            context.fill(width - 5, LIST_Y, width - 2, gridBottom, 0x40FFFFFF);
            context.fill(width - 5, barY, width - 2, barY + barH, 0xC0FFFFFF);
        }
        if (hovered < 0) return;
        if (hovered != tooltipCell) {
            MemoryBank bank = TrackerServices.banks().bank();
            List<net.minecraft.util.FormattedCharSequence> lines = new ArrayList<>();
            GridCell cell = grid.get(hovered);
            lines.add(cell.stack().getHoverName().getVisualOrderText());
            if (bank != null) lines.addAll(font.split(TallyReport.line(cell.id(), bank), 220));
            lines.add(Component.translatable("chesttracker.grid.tap").getVisualOrderText());
            cellTooltip = lines;
            tooltipCell = hovered;
        }
        context.setTooltipForNextFrame(cellTooltip, mouseX, mouseY);
    }

    /**
     * Runs the current search for one frame's budget and puts what it found in the list.
     */
    private void continueSearch() {
        if (job == null) return;
        long start = System.nanoTime();
        job.run(start + Settings.searchBudgetNanos);
        if (!job.exact.isEmpty()) {
            if (fuzzy) {
                // The first exact match: close matches shown so far make way for it.
                list.setRows(job.exact);
                fuzzy = false;
            } else if (job.exact.size() > list.children().size()) {
                list.addRows(job.exact.subList(list.children().size(), job.exact.size()));
            }
        } else if (job.close.size() > list.children().size()) {
            list.addRows(job.close.subList(list.children().size(), job.close.size()));
            fuzzy = true;
        }
        indexing = job.done ? 0 : job.remaining();
        restorePlace(job.done);
        if (job.done) {
            job = null;
            applyGrouping();
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        if (ms > 16) TrackerLog.LOGGER.warn("Tracker search step took {} ms (longer than a frame)", ms);
    }

    /**
     * After the list was rebuilt: the picked row is selected again as soon as it's back, and the
     * scroll put back once the search has refilled the list far enough (or has finished).
     */
    private void restorePlace(boolean searchDone) {
        if (pendingSelected != null) {
            for (ResultList.Entry e : list.children()) {
                if (e.row == null) continue;
                // The same container, and the same item in it (rows are rebuilt, so not by identity).
                if (e.row.record() == pendingSelected.record() && (pendingSelected.item() == null
                    || e.row.item() != null && e.row.item().stack() == pendingSelected.item().stack()
                    && e.row.item().path().equals(pendingSelected.item().path()))) {
                    list.setSelected(e);
                    pendingSelected = null;
                    break;
                }
            }
        }
        if (pendingScroll >= 0 && (searchDone || list.maxScrollAmount() >= pendingScroll)) {
            list.setScrollAmount(pendingScroll);
            pendingScroll = -1;
        }
        if (searchDone) pendingSelected = null;
    }

    /**
     * "close matches" next to the search field, when the results are fuzzy ones.
     */
    static void drawFuzzyNote(GuiGraphicsExtractor context, net.minecraft.client.gui.Font tr, EditBox field) {
        String note = Component.translatable("chesttracker.close_matches").getString();
        context.text(tr, note, field.getX() + field.getWidth() - tr.width(note) - 6,
            field.getY() + 6, 0xFFE8B04A);
    }

    /**
     * What the list is searching through: "On me" first, then containers nearest first.
     */
    private List<ContainerRecord> records() {
        List<ContainerRecord> records = new ArrayList<>();
        MemoryBank bank = TrackerServices.banks().bank();
        if (bank == null) return records;
        if (viewingCluster != null) {
            records.addAll(viewingCluster.members());
            records.sort(Comparator.comparingDouble(TrackerScreen::sortDistance));
            return records;
        }
        // Your own inventory first, so "it was on me all along" is the first thing you see.
        InventoryRecord onMe = trashView ? null : InventoryRecord.current();
        if (onMe != null) records.add(onMe);
        Stream<? extends ContainerRecord> shown = trashView ? trashKind.trashed(bank)
            : bank.containers().filter(r -> !r.isTrashed() && (currentShowDestroyed() || r.status != RecordStatus.LIKELY_DESTROYED));
        records.addAll(shown
            .sorted(Comparator.comparingDouble(TrackerScreen::sortDistance))
            .toList());
        return records;
    }

    /**
     * Collapses nearby containers into cluster rows when {@code group-nearby} is on and the list is
     * in browse mode (no query). Cluster rows are display-only; singletons stay as normal rows.
     * Called once per refresh, after the search job has finished producing its rows.
     */
    private void applyGrouping() {
        if (viewingCluster != null) return;
        if (!currentGroupNearby() || trashView) return;
        String q = search.getValue().trim();
        if (!q.isEmpty()) return;
        if (list.children().isEmpty()) return;
        for (ResultList.Entry e : list.children()) if (e.row == null) return; // already grouped
        List<Row> flat = new ArrayList<>();
        for (ResultList.Entry e : list.children()) if (e.row != null) flat.add(e.row);
        if (flat.size() < 2) return;

        // Bucket records into groups.
        Map<String, List<ContainerRecord>> buckets = new LinkedHashMap<>();
        for (Row r : flat) {
            ContainerRecord rec = r.record();
            if (rec instanceof InventoryRecord) continue;
            String key = bucketKey(rec, currentGroupMode());
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(rec);
        }

        // Nearby mode: split each bucket into sub-clusters by Manhattan distance <= 16.
        Map<ContainerRecord, Cluster> recordToCluster = new HashMap<>();
        List<Cluster> clusters = new ArrayList<>();
        for (List<ContainerRecord> bucket : buckets.values()) {
            if (bucket.size() < 2) continue;
            // union-find within the bucket
            int n = bucket.size();
            int[] parent = new int[n];
            for (int i = 0; i < n; i++) parent[i] = i;
            java.util.function.IntUnaryOperator find = new java.util.function.IntUnaryOperator() {
                public int applyAsInt(int x) {
                    while (parent[x] != x) {
                        parent[x] = parent[parent[x]];
                        x = parent[x];
                    }
                    return x;
                }
            };
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    ContainerRecord a = bucket.get(i), b = bucket.get(j);
                    if (currentGroupMode() == ChestTrackerModule.GroupMode.Nearby
                        && manhattan(a, b) > 16) continue;
                    int ra = find.applyAsInt(i), rb = find.applyAsInt(j);
                    if (ra != rb) parent[ra] = rb;
                }
            }
            Map<Integer, List<ContainerRecord>> groups = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) groups.computeIfAbsent(find.applyAsInt(i), k -> new ArrayList<>()).add(bucket.get(i));
            for (List<ContainerRecord> g : groups.values()) {
                if (g.size() < 2) continue;
                ContainerRecord rep = g.get(0);
                String label = Component.translatable("chesttracker.cluster", g.size(),
                    (int) rep.x, (int) rep.y, (int) rep.z).getString();
                Cluster cluster = new Cluster(label, g);
                clusters.add(cluster);
                for (ContainerRecord r : g) recordToCluster.put(r, cluster);
            }
        }

        // Build the display list in the original (distance) order, collapsing clustered records.
        Set<ContainerRecord> consumed = new HashSet<>();
        List<ResultList.Entry> entries = new ArrayList<>();
        for (Row r : flat) {
            if (consumed.contains(r.record())) continue;
            Cluster c = recordToCluster.get(r.record());
            if (c != null) {
                entries.add(list.new Entry(c));
                consumed.addAll(c.members());
            } else {
                entries.add(list.new Entry(r));
            }
        }
        list.replaceEntries(entries);
    }

    private static String bucketKey(ContainerRecord r, ChestTrackerModule.GroupMode mode) {
        if (mode == ChestTrackerModule.GroupMode.Nearby) return r.dimension;
        return r.dimension + "@" + (r.x >= 0 ? (int) (r.x / 16) : (int) Math.floor(r.x / 16))
            + "," + (r.y >= 0 ? (int) (r.y / 16) : (int) Math.floor(r.y / 16))
            + "," + (r.z >= 0 ? (int) (r.z / 16) : (int) Math.floor(r.z / 16));
    }

    private static int manhattan(ContainerRecord a, ContainerRecord b) {
        return (int) (Math.abs(a.x - b.x) + Math.abs(a.y - b.y) + Math.abs(a.z - b.z));
    }

    /**
     * One search, worked through container by container (nearest first) and item by item until
     * the time budget runs out, then carried on next frame. Results come in order, so they're
     * only ever added at the bottom of the list. Until an exact match turns up, close matches
     * ("diamnd" -> diamond) are collected on the way, so a typo shows results straight away
     * instead of after a second pass over everything.
     */
    private static final class SearchJob {
        final String query;
        final String[] queryWords;
        final Set<Item> namedItems;
        final List<ContainerRecord> records;
        final List<Row> exact = new ArrayList<>();
        final List<Row> close = new ArrayList<>();
        int next;
        /**
         * Where to carry on inside records.get(next) (a big container can take several frames).
         */
        int nextItem;
        boolean done;
        /**
         * Inverted-index candidates for a first exact-only pass; null = go straight to the full scan.
         */
        final Set<String> candidateKeys;
        boolean candidatePassDone;
        /**
         * Records the candidate pass already reported, so the full scan does not duplicate them.
         */
        final Set<String> matchedKeys = new HashSet<>();

        SearchJob(String query, List<ContainerRecord> records, Set<Item> namedItems, Set<String> candidateKeys) {
            this.query = query;
            this.namedItems = namedItems;
            this.queryWords = Fuzzy.words(query);
            this.records = records;
            this.candidateKeys = candidateKeys;
        }

        /**
         * Book pages already searched, by identity: copies of one book share their text, so a
         * wall of the same 100-page book is read once per search, not once per copy.
         */
        final java.util.IdentityHashMap<String, Boolean> books = new java.util.IdentityHashMap<>();

        boolean bookMatches(String text) {
            if (text.isEmpty()) return false;
            return books.computeIfAbsent(text, t -> t.contains(query));
        }

        /**
         * Containers not looked at yet.
         */
        int remaining() {
            return records.size() - next;
        }

        void run(long deadline) {
            // First pass over inverted-index candidates only, exact matches only: usually a
            // handful of records, so results appear in the first frame. If that finds nothing
            // (substring, book-text or fuzzy-only matches), fall through to the full scan below.
            if (!candidatePassDone && candidateKeys != null) {
                while (next < records.size() && exact.size() < MAX_ROWS) {
                    if (System.nanoTime() >= deadline) return;
                    ContainerRecord record = records.get(next);
                    if (!candidateKeys.contains(record.key()) && !(record instanceof InventoryRecord)) {
                        next++;
                        nextItem = 0;
                        continue;
                    }
                    if (!scanRecord(record, deadline, false)) return;
                    next++;
                    nextItem = 0;
                }
                if (next >= records.size()) {
                    // The candidate pass only looked at the records the inverted index knows
                    // about. A record whose items are not indexed yet can still hold a match, and
                    // only the full scan finds it, because it indexes the records it touches on
                    // demand. So the search may only end here when the index is warm for every
                    // record; otherwise the full scan carries on and skips what was reported.
                    if (!exact.isEmpty() && indexWarm()) {
                        done = true;
                        return;
                    }
                    for (Row row : exact) matchedKeys.add(row.record().key());
                    candidatePassDone = true;
                    next = 0;
                    nextItem = 0;
                } else {
                    return;
                }
            }
            while (next < records.size() && exact.size() < MAX_ROWS) {
                if (System.nanoTime() >= deadline) return;
                ContainerRecord record = records.get(next);
                // The candidate pass already reported this record: scanning it again would
                // duplicate its rows.
                if (matchedKeys.contains(record.key())) {
                    next++;
                    nextItem = 0;
                    continue;
                }
                if (!scanRecord(record, deadline, true)) return;
                next++;
                nextItem = 0;
            }
            done = true;
        }

        /**
         * True when every record has a current index entry, so no match can hide in an unindexed one.
         */
        boolean indexWarm() {
            ItemIndex index = TrackerServices.index();
            for (ContainerRecord record : records) {
                if (index.cachedItems(record) == null) return false;
            }
            return true;
        }

        /**
         * Scans one record's items; false when the deadline hit mid-record (carry on next frame).
         */
        private boolean scanRecord(ContainerRecord record, long deadline, boolean collectClose) {
            if (query.isEmpty()) {
                // Top-level stacks from the record itself: opening the screen indexes nothing.
                exact.add(new Row(record, null, record.items.size()));
                return true;
            }
            ItemIndex index = TrackerServices.index();
            // Not indexed yet: index it slot by slot within the budget; carry on next frame.
            List<ItemIndex.IndexedItem> items = record instanceof InventoryRecord
                ? index.items(record) : index.itemsWithin(record, deadline);
            if (items == null) return false;
            for (; nextItem < items.size(); nextItem++) {
                if ((nextItem & 31) == 31 && System.nanoTime() >= deadline) return false;
                ItemIndex.IndexedItem item = items.get(nextItem);
                if (item.searchText().contains(query) || bookMatches(item.bookText())
                    || namedItems.contains(item.stack().getItem())) {
                    exact.add(new Row(record, item, 0));
                } else if (collectClose && exact.isEmpty() && close.size() < MAX_ROWS
                    && Fuzzy.matches(item.searchText(), item.words(), queryWords)) {
                    close.add(new Row(record, item, 0));
                }
            }
            return true;
        }
    }

    /**
     * Same dimension first, nearest first; other dimensions after.
     */
    public static double sortDistance(ContainerRecord r) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return 0;
        String here = Dimensions.current();
        if (here != null && r.dimension.equals(here)) return client.player.distanceToSqr(r.x, r.y, r.z);
        // Another dimension: by its converted position, so a nether stash "under" you ranks high.
        double converted = Dimensions.convertedDistance(r);
        return Double.isNaN(converted) ? 1e15 : converted * converted;
    }

    private void showTooltip(@Nullable String text, int x, int y) {
        if (text == null || !menu.isEmpty()) return;
        pendingTooltip = text;
        tooltipX = x;
        tooltipY = y;
    }

    /**
     * Wrapped explanation tooltip, readable width.
     */
    static void drawExplanation(GuiGraphicsExtractor context, net.minecraft.client.gui.Font tr, String text, int x, int y) {
        List<net.minecraft.util.FormattedCharSequence> lines = new ArrayList<>();
        for (String para : text.split("\n")) {
            if (para.isEmpty()) lines.add(net.minecraft.util.FormattedCharSequence.EMPTY);
            else lines.addAll(tr.split(Component.literal(para), 220));
        }
        context.setTooltipForNextFrame(lines, x, y);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        pendingTooltip = null;
        continueSearch(); // a search still in progress adds this frame's results before drawing
        super.extractRenderState(context, mouseX, mouseY, delta);
        if (showingGrid()) renderGrid(context, mouseX, mouseY);
        if (pendingTooltip != null) drawExplanation(context, font, pendingTooltip, tooltipX, tooltipY);
        ScreenTabs.drawUnderline(context, width, false);
        if (fuzzy) drawFuzzyNote(context, font, search);
        // Hint line under the search field: the trash, the selection, or how to select.
        Component hint;
        int hintColor = 0xFFB0B0B0;
        if (trashView) {
            hint = Component.translatable("chesttracker.hint.trash", Settings.trashDays);
            hintColor = 0xFFE8B04A;
        } else if (indexing > 0) {
            hint = Component.translatable("chesttracker.hint.indexing", indexing);
            hintColor = 0xFFE8B04A;
        } else if (selectMode || !selection.isEmpty()) {
            hint = Component.translatable("chesttracker.hint.selected", selection.size());
            hintColor = 0xFF8FC8FF;
        } else if (tallyHint != null) {
            hint = tallyHint;
            hintColor = 0xFFFFE08A;
        } else if (showingGrid()) {
            hint = Component.translatable("chesttracker.grid.hint", grid.size());
        } else {
            hint = Component.translatable("chesttracker.hint.select");
        }
        context.centeredText(font, hint, width / 2, 48, hintColor);
        if (TrackerServices.banks().bank() == null) {
            context.centeredText(font, Component.translatable(TrackerServices.banks().isLoading()
                    ? "chesttracker.loading" : "chesttracker.no_bank"),
                width / 2, height / 2, 0xFFAAAAAA);
        } else if (showingGrid() ? grid.isEmpty() : list.children().isEmpty() && job == null) {
            context.centeredText(font, Component.translatable("chesttracker.no_results"),
                width / 2, height / 2, 0xFFAAAAAA);
        }
    }

    /**
     * Darker than the vanilla backdrop, so grey text stays readable over a bright sky.
     */
    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // In-game UI: the world stratum renders underneath and stays visible behind, dimmed only by
        // BACKDROP. No vanilla menu texture or blur stacked on top (it was what made transitions flash black).
        context.fill(0, 0, width, height, TrackerScreen.BACKDROP);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * Either a container (item == null) or one matching item inside a container.
     */
    private record Row(ContainerRecord record, ItemIndex.IndexedItem item, int itemCount) {
    }

    /**
     * A collapsed group of nearby containers in the browse list; render-only (no open/select).
     */
    private record Cluster(String label, List<ContainerRecord> members) {
    }

    private final class ResultList extends ObjectSelectionList<ResultList.Entry> {
        ResultList(Minecraft client, int width, int height, int y) {
            super(client, width, height, y, ROW_HEIGHT);
        }

        com.AutoBookshelf.addon.modules.chesttracker.gui.TrackerScreen.ResultList.Entry entryAt(double x, double y) {
            return getEntryAtPosition(x, y);
        }

        void setRows(List<Row> rows) {
            replaceEntries(rows.stream().map(com.AutoBookshelf.addon.modules.chesttracker.gui.TrackerScreen.ResultList.Entry::new).toList());
            setScrollAmount(0);
        }

        /**
         * Adds rows at the bottom, keeping the scroll and selection (a search still in progress).
         */
        void addRows(List<Row> rows) {
            for (Row row : rows) addEntry(new com.AutoBookshelf.addon.modules.chesttracker.gui.TrackerScreen.ResultList.Entry(row));
        }

        @Override
        public int getRowWidth() {
            return Math.min(520, width - 30);
        }

        final class Entry extends ObjectSelectionList.Entry<com.AutoBookshelf.addon.modules.chesttracker.gui.TrackerScreen.ResultList.Entry> {
            private final Row row;
            private final Cluster cluster;
            private final ItemStack icon;
            private final ItemStack containerIcon;

            Entry(Row row) {
                this.row = row;
                this.cluster = null;
                this.containerIcon = entityIcon(row.record().type);
                this.icon = row.item() != null ? row.item().stack() : containerIcon;
            }

            Entry(Cluster cluster) {
                this.row = null;
                this.cluster = cluster;
                this.containerIcon = null;
                this.icon = null;
            }

            @Override
            public void extractContent(GuiGraphicsExtractor context, int mouseX, int mouseY, boolean hovered, float delta) {
                int x = getContentX();
                int y = getContentY();
                if (cluster != null) {
                    context.text(font, cluster.label(), x + 2, y + 14, 0xFFB0B0B0);
                    context.text(font, "\u203A", x + getRowWidth() - 18, y + 14, 0xFF888888);
                    context.text(font, "\u22EE", x + getRowWidth() - 10, y + 17, 0xFF888888);
                    return;
                }
                ContainerRecord r = row.record();
                if (selection.contains(r.key())) {
                    context.fill(x - 2, y - 2, x + getRowWidth() - 2, y + ROW_HEIGHT - 4, 0x553A78C8);
                    context.text(font, "\u2714", x - 12, y + 17, 0xFF8FC8FF);
                }
                // Icons: the item found, with the container it is in underneath.
                context.item(icon, x + 2, y + 3);
                if (row.item() != null) {
                    context.itemDecorations(font, icon, x + 2, y + 3);
                    context.item(containerIcon, x + 2, y + 23);
                }

                int textX = x + 24;
                int maxWidth = getContentWidthSafe() - 42;
                context.text(font, "\u22EE", x + getRowWidth() - 10, y + 17,
                    hovered ? 0xFFFFFFFF : 0xFF888888);
                RowText rowText = rowText(r);
                int lineY = y + 3;
                if (row.item() == null) {
                    // Container row: which container / where and status / notes.
                    String count = r.hasSnapshot()
                        ? Component.translatable("chesttracker.stacks", row.itemCount()).getString()
                        : Component.translatable("chesttracker.never_opened").getString();
                    context.text(font, trim(rowText.title() + " · " + count, maxWidth), textX, lineY, 0xFFFFFFFF);
                } else {
                    // Item row: the item / which container (and inside what) / where and status / notes.
                    context.text(font, trim(itemName(row.item().stack()), maxWidth),
                        textX, lineY, 0xFFFFFFFF);
                    lineY += 11;
                    String path = String.join(" > ", row.item().path());
                    context.text(font, trim(rowText.title() + (path.isEmpty() ? "" : " > " + path), maxWidth),
                        textX, lineY, 0xFFDDDDDD);
                }
                lineY += 11;
                String whereLine = trim(where(r), maxWidth);
                context.text(font, whereLine, textX, lineY, statusColor(r.status));
                if (hovered && mouseY >= lineY - 1 && mouseY < lineY + 10 && mouseX >= textX
                    && mouseX < textX + font.width(whereLine)) {
                    showTooltip(statusExplain(r), mouseX, mouseY);
                }
                lineY += 11;
                // Notes: warnings (amber), hints (blue), "last checked" (grey), left to right.
                int nx = textX;
                for (Note note : rowText.notes()) {
                    String text = (nx == textX ? "" : " · ") + note.text();
                    int room = textX + maxWidth - nx;
                    if (room <= 0) break;
                    String shown = trim(text, room);
                    context.text(font, shown, nx, lineY, note.color());
                    int w = font.width(shown);
                    if (hovered && mouseY >= lineY - 1 && mouseY < lineY + 10 && mouseX >= nx && mouseX < nx + w) {
                        showTooltip(note.explain(), mouseX, mouseY);
                    }
                    nx += w;
                }

                if (hovered && mouseX >= x + 2 && mouseX < x + 18 && mouseY >= y + 3 && mouseY < y + 19
                    && row.item() != null) {
                    context.setTooltipForNextFrame(font, icon, mouseX, mouseY);
                }
            }

            private int getContentWidthSafe() {
                return getRowWidth();
            }

            private String trim(String s, int maxWidth) {
                // Bounded memo for the measure+substring pair the rows run on every visible
                // line per frame; the width work is deterministic on the string contents.
                TrimKey key = new TrimKey(s, maxWidth);
                String cached = TRIM_CACHE.get(key);
                if (cached != null) return cached;
                if (font.width(s) <= maxWidth) {
                    TRIM_CACHE.put(key, s);
                    return s;
                }
                String clipped = font.plainSubstrByWidth(s, Math.max(0, maxWidth - font.width("..."))) + "...";
                TRIM_CACHE.put(key, clipped);
                return clipped;
            }

            @Override
            public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
                ResultList.this.setSelected(this);
                if (cluster != null) {
                    viewingCluster = cluster;
                    TrackerScreen.this.refresh();
                    return true;
                }
                boolean ctrl = (click.modifiers() & InputConstants.MOD_CONTROL) != 0;
                boolean shift = (click.modifiers() & InputConstants.MOD_SHIFT) != 0;
                boolean selecting = selectMode || !selection.isEmpty() || ctrl || shift;
                // Middle-click locates the container in the world (same as Detail's Done);
                // a single left-click opens Detail instantly, so double-click can't be used here.
                if (click.button() == InputConstants.MOUSE_BUTTON_MIDDLE && !selecting && !(row.record() instanceof InventoryRecord)) {
                    TrackerScreen.this.locate(row.record());
                    return true;
                }
                // The "⋮" at the end of the row opens the menu with a plain tap (touch screens);
                // while selecting, a click toggles the row; otherwise it opens the detail view.
                if (click.x() >= getContentX() + getRowWidth() - 16) {
                    openMenu(row.record(), click.x(), click.y());
                } else if (selecting && row.record() instanceof InventoryRecord) {
                    return true; // can't be selected; opening it here would leave the selection behind
                } else if (selecting) {
                    int index = ResultList.this.children().indexOf(this);
                    if (shift && lastToggled >= 0) {
                        int from = Math.min(lastToggled, index), to = Math.max(lastToggled, index);
                        for (int i = from; i <= to; i++) {
                            ResultList.Entry e = ResultList.this.children().get(i);
                            if (e.row == null) continue;
                            ContainerRecord r = e.row.record();
                            if (!(r instanceof InventoryRecord)) selection.add(r.key());
                        }
                    } else if (!selection.remove(row.record().key())) {
                        selection.add(row.record().key());
                    }
                    lastToggled = index;
                    updateBar();
                } else {
                    String q = search.getValue().trim().toLowerCase(Locale.ROOT);
                    if (row.record() instanceof EntityRecord trader && trader.isTrader()) {
                        minecraft.setScreenAndShow(new TradeDetailScreen(trader, TrackerScreen.this, ""));
                    } else {
                        minecraft.setScreenAndShow(new DetailScreen(row.record(), TrackerScreen.this, q.startsWith("@") ? q.substring(1) : q));
                    }
                }
                return true;
            }

            @Override
            public Component getNarration() {
                if (cluster != null) return Component.literal(cluster.label());
                return Component.literal(label(row.record()));
            }
        }
    }

    /**
     * Display name of an item, using a written book's title when it has one.
     */
    public static String itemName(ItemStack stack) {
        WrittenBookContent written = stack.get(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT);
        if (written != null) {
            String t = written.title().get(true);
            if (t != null && !t.isBlank()) return t;
        }
        return stack.getHoverName().getString();
    }

    /**
     * The container as a player would name it: "Chest at 210, 100, 213", "\"Pack\" (Donkey)".
     */
    public static String title(ContainerRecord r) {
        String label = label(r);
        // In another dimension the place line already shows "Nether (x, y, z)".
        if (r instanceof BlockRecord b && !b.global && r.dimension.equals(Dimensions.current())) {
            return Component.translatable("chesttracker.at", label, b.pos.getX(), b.pos.getY(), b.pos.getZ()).getString();
        }
        return label;
    }

    public static String label(ContainerRecord r) {
        if (r instanceof InventoryRecord) return Component.translatable("chesttracker.on_me", r.name).getString();
        String type = r instanceof BlockRecord
            ? BuiltInRegistries.BLOCK.getValue(Identifier.parse(r.type)).getName().getString()
            : Component.translatable(BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(r.type)).getDescriptionId()).getString();
        if (r instanceof BlockRecord block && block.global && block.ownerName != null) return type + " (" + block.ownerName + ")";
        return r.name != null ? "\"" + r.name + "\" (" + type + ")" : type;
    }

    public static String where(ContainerRecord r) {
        Minecraft client = Minecraft.getInstance();
        List<String> parts = new ArrayList<>();
        if (r instanceof InventoryRecord) return Component.translatable("chesttracker.on_me_where").getString();
        if (r instanceof BlockRecord block && block.global) {
            // Ender chest: no place or status. Another account's says which account to switch to.
            MemoryBank bank = TrackerServices.banks().bank();
            if (bank != null && block.owner != null && !block.owner.equals(bank.currentPlayer)) {
                parts.add(Component.translatable("chesttracker.other_account",
                    block.ownerName != null ? block.ownerName : block.owner.toString()).getString());
            }
        } else {
            parts.add(place(r, false));
            String status = Component.translatable("chesttracker.status." + statusId(r)).getString();
            boolean bad = r.status == RecordStatus.MISSING || r.status == RecordStatus.LIKELY_DESTROYED;
            parts.add(bad ? "\u26A0 " + status : status);
        }
        parts.add(Component.translatable("chesttracker.seen_ago", age(r.lastSeen)).getString());
        if (r.isTrashed()) parts.add(Component.translatable("chesttracker.in_trash", age(r.trashedAt)).getString());
        return String.join(" · ", parts);
    }

    /**
     * Where a container is, from where the player stands: "12m (x, y, z)" in this dimension;
     * "Nether (x, y, z) ≈ x, z here - 1,240m" in a dimension that converts (CLAUDE.md 4.1e);
     * "End (x, y, z)" otherwise. {@code withCoords} adds coordinates for entities too.
     */
    public static String place(ContainerRecord r, boolean withCoords) {
        Minecraft client = Minecraft.getInstance();
        String coords = r instanceof BlockRecord b
            ? b.pos.getX() + ", " + b.pos.getY() + ", " + b.pos.getZ()
            : Math.round(Math.floor(r.x)) + ", " + Math.round(Math.floor(r.y)) + ", " + Math.round(Math.floor(r.z));
        boolean showCoords = withCoords;
        if (client.player != null && r.dimension.equals(Dimensions.current())) {
            String d = Math.round(Math.sqrt(client.player.distanceToSqr(r.x, r.y, r.z))) + "m";
            return showCoords ? d + " (" + coords + ")" : d;
        }
        String there = Dimensions.displayName(r.dimension) + " (" + coords + ")";
        double[] here = Dimensions.inCurrent(r);
        if (here == null) return there;
        return Component.translatable("chesttracker.converted", there, Math.round(Math.floor(here[0])),
            Math.round(Math.floor(here[1])), String.format(Locale.ROOT, "%,d", Math.round(Dimensions.convertedDistance(r)))).getString();
    }

    /**
     * {@code explain}: what the note means, shown on hover / "Show warnings"; null if none.
     */
    public record Note(String text, int color, @Nullable String explain) {
    }

    /**
     * An explanation from the language file, or null when its entry is empty or absent. Empty
     * entries (e.g. "unloaded") are placeholders a resource pack can fill in.
     */
    static @Nullable String explain(String id, Object... args) {
        String key = "chesttracker.explain." + id;
        String text = Component.translatable(key, args).getString();
        return text.equals(key) || text.isBlank() ? null : text; // empty entry = no tooltip (yet)
    }

    /**
     * The status as shown: a mob (donkey, llama, villager...) that is likely destroyed "died";
     * minecarts and boats stay "likely destroyed". A gone block is certain (its chunk is loaded):
     * "destroyed", or "picked up" for a shulker box.
     */
    public static String statusId(ContainerRecord r) {
        if (r.status == RecordStatus.LIKELY_DESTROYED && r instanceof EntityRecord e && isMob(e)) return "died";
        if (r.status == RecordStatus.LIKELY_DESTROYED && r instanceof BlockRecord) {
            // A broken shulker box keeps its contents and drops as an item: picked up, not destroyed.
            return r.type.endsWith("shulker_box") ? "picked_up" : "destroyed";
        }
        return r.status.name().toLowerCase(Locale.ROOT);
    }

    /**
     * "Mark dead" for a mob, "Mark destroyed" for everything else.
     */
    public static Component markLabel(ContainerRecord r) {
        return Component.translatable(r instanceof EntityRecord e && isMob(e) ? "chesttracker.mark_dead"
            : "chesttracker.mark_destroyed");
    }

    /**
     * Copies "x y z" (vanilla) or "x z" (Baritone goals) and says so in the action bar.
     */
    public static void copyCoords(Minecraft client, ContainerRecord r, boolean withY) {
        long x, y, z;
        if (r instanceof BlockRecord b && !b.global) {
            x = b.pos.getX();
            y = b.pos.getY();
            z = b.pos.getZ();
        } else {
            x = Math.round(Math.floor(r.x));
            y = Math.round(Math.floor(r.y));
            z = Math.round(Math.floor(r.z));
        }
        String coords = withY ? x + " " + y + " " + z : x + " " + z;
        client.keyboardHandler.setClipboard(coords);
        Messages.actionBar(Component.translatable("chesttracker.detail.copied", coords,
            Dimensions.displayName(r.dimension)));
    }

    /**
     * Backdrop drawn over the vanilla one by every tracker screen.
     */
    static final int BACKDROP = 0x90000000;
    /**
     * Where the list starts: below the search field and its hint line.
     */
    static final int LIST_Y = 60;

    /**
     * Animals and villagers, as opposed to minecarts and boats (which are MISC, like villagers).
     */
    private static boolean isMob(EntityRecord e) {
        return e.isTrader() || BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(e.type)).getCategory() != net.minecraft.world.entity.MobCategory.MISC;
    }

    /**
     * Explanation of the status line (and the trash, if it is in there), or null.
     */
    public static @Nullable String statusExplain(ContainerRecord r) {
        if (r instanceof InventoryRecord || (r instanceof BlockRecord b && b.global)) return null;
        String status = explain("status." + statusId(r));
        String trash = r.isTrashed() ? explain("in_trash", Settings.trashDays) : null;
        if (status == null) return trash;
        return trash == null ? status : status + "\n\n" + trash;
    }

    /**
     * Warning flags (amber), hints (blue) and "last checked" (grey) for a container (CLAUDE.md
     * 4.1d), already translated. Shown, never acted on: the player decides what to do.
     */
    public static List<Note> notes(ContainerRecord r) {
        List<Note> out = new ArrayList<>();
        for (String flag : r.flags) out.add(new Note("\u26A0 " + note("warn", flag), 0xFFE8B04A, explain(flag)));
        for (String hint : r.hints) out.add(new Note(note("hint", hint), 0xFF8FB8E8, explain(hint)));
        if (r instanceof EntityRecord e && e.reappeared) {
            out.add(new Note(Component.translatable("chesttracker.note.reappeared").getString(), 0xFF9A9A9A, explain("reappeared")));
        }
        MemoryBank bank = TrackerServices.banks().bank();
        if (bank != null && bank.isStale(r, Settings.staleDays)) {
            int days = bank.playDaysSince(r.lastOpened);
            out.add(new Note(Component.translatable("chesttracker.warn.stale", days).getString(), 0xFF9A9A9A,
                explain("stale", days)));
        }
        return out;
    }

    /**
     * The same notes as plain text, for chat.
     */
    public static List<String> warnings(ContainerRecord r) {
        return notes(r).stream().map(Note::text).toList();
    }

    private static String note(String kind, String id) {
        String key = "chesttracker." + kind + "." + id;
        String text = Component.translatable(key).getString();
        return text.equals(key) ? id : text; // unknown note from a newer version: show its id
    }

    static int statusColor(RecordStatus status) {
        return switch (status) {
            case LOADED -> 0xFF8FE08F;
            case UNLOADED -> 0xFFAAAAAA;
            case MISSING -> 0xFFE0C060;
            case LIKELY_DESTROYED -> 0xFFE07070;
        };
    }

    /**
     * The item with the same id (chest, barrel, chest_minecart, oak_chest_boat), else the spawn egg, else a chest.
     */
    public static ItemStack entityIcon(String type) {
        ItemStack cached = ENTITY_ICON_CACHE.get(type);
        if (cached != null) return cached;
        Identifier id = Identifier.parse(type);
        Item item = BuiltInRegistries.ITEM.getValue(id);
        if (item == Items.AIR)
            item = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath(id.getNamespace(), id.getPath() + "_spawn_egg"));
        if (item == Items.AIR) item = Items.CHEST;
        ItemStack built = new ItemStack(item);
        if (ENTITY_ICON_CACHE.size() > 512) ENTITY_ICON_CACHE.clear();
        ENTITY_ICON_CACHE.put(type, built);
        return built;
    }

    public static String age(long millis) {
        long s = Math.max(0, (System.currentTimeMillis() - millis) / 1000);
        if (s < 60) return s + "s";
        if (s < 3600) return s / 60 + "m";
        if (s < 86400) return s / 3600 + "h";
        return s / 86400 + "d";
    }
}
