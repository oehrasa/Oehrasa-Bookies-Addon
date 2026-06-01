package com.AutoBookshelf.addon.modules.chesttracker;

import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.stream.Collectors;

public class ChestTrackerScreen extends Screen {
    private final ChestTrackerModule module;
    private final ChestTrackerDataV2 data;
    private EditBox searchField;
    private String searchQuery = "";
    private List<ItemEntry> allItems = new ArrayList<>();
    private List<ItemEntry> filteredItems = new ArrayList<>();
    private int scrollOffset = 0;
    private int maxScroll = 0;
    private static final int ITEM_SIZE = 18;
    private static final int ITEMS_PER_ROW = 16;
    private static final int PADDING = 10;
    private static final int TOP_PADDING = 70;
    private static final int BOTTOM_PADDING = 35;
    private static final int SCROLLBAR_WIDTH = 8;
    private static final int MAX_PANEL_HEIGHT = 600;
    private static final int MIN_VISIBLE_ROWS = 5;
    private Button clearSearchButton;
    private Button sortButton;
    private SortMode currentSortMode = SortMode.COUNT_DESC;
    private boolean isDraggingScrollbar = false;
    private int scrollbarDragStartY = 0;
    private int scrollbarDragStartOffset = 0;
    private int cachedStartX;
    private int cachedStartY;
    private int cachedMaxY;
    private int cachedTotalRows;
    private int cachedVisibleHeight;
    private Map<Item, Double> distanceCache = new HashMap<>();

    public ChestTrackerScreen(ChestTrackerModule module) {
        super(Component.literal("Chest Tracker"));
        this.module = module;
        this.data = module.getData();
    }

    @Override
    protected void init() {
        super.init();
        searchField = new EditBox(
            this.font,
            this.width / 2 - 110,
            20,
            200,
            20,
            Component.literal("Search items...")
        );
        searchField.setMaxLength(50);
        searchField.setHint(Component.literal("Search items..."));
        searchField.setResponder(this::onSearchChanged);
        this.addWidget(searchField);

        clearSearchButton = Button.builder(
                Component.literal("§cx"),
                button -> {
                    searchField.setValue("");
                    this.searchQuery = "";
                    filterItems();
                }
            )
            .bounds(this.width / 2 + 95, 20, 20, 20)
            .build();
        this.addRenderableWidget(clearSearchButton);

        sortButton = Button.builder(
                Component.literal("Sort: " + currentSortMode.getDisplayName()),
                button -> {
                    currentSortMode = currentSortMode.next();
                    button.setMessage(Component.literal("Sort: " + currentSortMode.getDisplayName()));
                    sortItems();
                    filterItems();
                }
            )
            .bounds(this.width / 2 - 220, 20, 100, 20)
            .build();
        this.addRenderableWidget(sortButton);

        loadItems();
        filterItems();
    }

    private void loadItems() {
        allItems = new ArrayList<>();
        Map<String, Integer> itemCounts = new HashMap<>();
        String currentDim = getCurrentDimension();
        for (TrackedContainer container : data.getAllContainers(currentDim)) {
            for (Map.Entry<String, Integer> entry : container.getItems().entrySet()) {
                itemCounts.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
            Identifier id = Identifier.tryParse(entry.getKey());
            if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                Item item = BuiltInRegistries.ITEM.getValue(id);
                allItems.add(new ItemEntry(item, entry.getValue()));
            }
        }
        sortItems();
    }

    private void sortItems() {
        switch (currentSortMode) {
            case COUNT_DESC -> allItems.sort((a, b) -> Integer.compare(b.count, a.count));
            case COUNT_ASC  -> allItems.sort((a, b) -> Integer.compare(a.count, b.count));
            case NAME_ASC   -> allItems.sort((a, b) -> a.item.getName(a.item.getDefaultInstance()).getString().compareToIgnoreCase(b.item.getName(b.item.getDefaultInstance()).getString()));
            case NAME_DESC  -> allItems.sort((a, b) -> b.item.getName(b.item.getDefaultInstance()).getString().compareToIgnoreCase(a.item.getName(a.item.getDefaultInstance()).getString()));
            case DISTANCE -> {
                if (minecraft.player == null) break;
                Vec3 playerPos = minecraft.player.position();
                List<TrackedContainer> containers = data.getAllContainers(getCurrentDimension());

                distanceCache.clear();
                for (ItemEntry entry : allItems) {
                    double closest = Double.MAX_VALUE;
                    for (TrackedContainer c : containers) {
                        if (c.containsItem(entry.item)) {
                            BlockPos pos = c.getPosition();
                            double dist = playerPos.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                            if (dist < closest) closest = dist;
                        }
                    }
                    distanceCache.put(entry.item, Math.sqrt(closest));
                }

                allItems.sort((a, b) -> Double.compare(
                    distanceCache.getOrDefault(a.item, Double.MAX_VALUE),
                    distanceCache.getOrDefault(b.item, Double.MAX_VALUE)
                ));
            }
        }
    }

    private void filterItems() {
        if (allItems == null) allItems = new ArrayList<>();
        if (searchQuery.isEmpty()) {
            filteredItems = new ArrayList<>(allItems);
        } else {
            String query = searchQuery.toLowerCase();
            filteredItems = allItems.stream()
                .filter(entry -> entry.item.getName(entry.item.getDefaultInstance()).getString().toLowerCase().contains(query))
                .collect(Collectors.toList());
        }
        int rows = (int) Math.ceil(filteredItems.size() / (double) ITEMS_PER_ROW);
        int contentHeight = Math.max(rows * ITEM_SIZE, MIN_VISIBLE_ROWS * ITEM_SIZE);
        int maxPanelHeight = Math.min(MAX_PANEL_HEIGHT, this.height - TOP_PADDING - BOTTOM_PADDING);
        int actualPanelHeight = Math.min(contentHeight, maxPanelHeight);
        int visibleRows = actualPanelHeight / ITEM_SIZE;
        maxScroll = Math.max(0, rows - visibleRows);
        scrollOffset = Math.min(scrollOffset, maxScroll);
    }

    private void onSearchChanged(String query) {
        this.searchQuery = query;
        filterItems();
    }

    private void updateCachedBounds() {
        cachedStartX = this.width / 2 - (ITEMS_PER_ROW * ITEM_SIZE) / 2;
        cachedStartY = TOP_PADDING;
        cachedTotalRows = (int) Math.ceil(filteredItems.size() / (double) ITEMS_PER_ROW);
        int contentHeight = Math.max(cachedTotalRows * ITEM_SIZE, MIN_VISIBLE_ROWS * ITEM_SIZE);
        int maxPanelHeight = Math.min(MAX_PANEL_HEIGHT, this.height - TOP_PADDING - BOTTOM_PADDING);
        cachedVisibleHeight = Math.min(contentHeight, maxPanelHeight);
        cachedMaxY = TOP_PADDING + cachedVisibleHeight;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        updateCachedBounds();
        context.fill(0, 0, this.width, this.height, 0xF0000000);
        int panelWidth = (ITEMS_PER_ROW * ITEM_SIZE) + 20;
        int panelX = this.width / 2 - panelWidth / 2;
        int panelY = TOP_PADDING - 5;
        int totalRows = (int) Math.ceil(filteredItems.size() / (double) ITEMS_PER_ROW);
        int contentHeight = Math.max(totalRows * ITEM_SIZE, MIN_VISIBLE_ROWS * ITEM_SIZE);
        int maxPanelHeight = Math.min(MAX_PANEL_HEIGHT, this.height - TOP_PADDING - BOTTOM_PADDING);
        int panelContentHeight = Math.min(contentHeight, maxPanelHeight);
        int panelBottom = panelY + panelContentHeight + 15;
        context.fill(panelX, panelY, panelX + panelWidth, panelBottom, 0xFF1A1A1A);
        context.fill(panelX, panelY, panelX + panelWidth, panelY + 2, 0xFF555555);
        context.fill(panelX, panelY, panelX + 2, panelBottom, 0xFF555555);
        context.fill(panelX + panelWidth - 2, panelY, panelX + panelWidth, panelBottom, 0xFF2A2A2A);
        context.fill(panelX, panelBottom - 2, panelX + panelWidth, panelBottom, 0xFF2A2A2A);
        String currentDim = getCurrentDimension();
        String dimName = currentDim.contains("overworld") ? "Overworld" :
            currentDim.contains("nether") ? "Nether" :
            currentDim.contains("end") ? "End" : currentDim;
        context.centeredText(
            this.font,
            Component.literal("§l§eChest Tracker §r§7- " + dimName),
            this.width / 2,
            8,
            0xFFFFFF
        );
        searchField.extractRenderState(context, mouseX, mouseY, delta);         // renamed method
        clearSearchButton.visible = !searchQuery.isEmpty();
        clearSearchButton.active = !searchQuery.isEmpty();
        renderItemGrid(context, mouseX, mouseY);
        renderScrollbar(context, mouseX, mouseY);
        super.extractRenderState(context, mouseX, mouseY, delta);               // renamed super call
        renderTooltip(context, mouseX, mouseY);
    }

    // renderBackground is no longer a separate override; it's handled inside extractRenderState if needed.
    // We remove the empty override entirely.

    private void renderItemGrid(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        // ... (unchanged, using context.item and context.text/fill as before) ...
        // (Same code as previously, but I'll include the full method for completeness)
        int index = scrollOffset * ITEMS_PER_ROW;
        int maxIndex = filteredItems.size();
        int panelWidth = (ITEMS_PER_ROW * ITEM_SIZE) + 20;
        int panelX = this.width / 2 - panelWidth / 2;
        context.enableScissor(panelX + 10, TOP_PADDING, panelX + panelWidth - 10, cachedMaxY);
        int visibleRows = (cachedVisibleHeight / ITEM_SIZE) + 2;
        int maxRow = Math.min(visibleRows, cachedTotalRows - scrollOffset);
        for (int row = 0; row < maxRow; row++) {
            for (int col = 0; col < ITEMS_PER_ROW; col++) {
                if (index >= maxIndex) break;
                ItemEntry entry = filteredItems.get(index);
                int x = cachedStartX + col * ITEM_SIZE;
                int y = cachedStartY + row * ITEM_SIZE;
                if (y + ITEM_SIZE <= TOP_PADDING || y >= cachedMaxY) { index++; continue; }
                boolean hovered = mouseX >= x && mouseX < x + ITEM_SIZE &&
                    mouseY >= y && mouseY < y + ITEM_SIZE;
                context.fill(x, y, x + ITEM_SIZE, y + ITEM_SIZE, 0xFF3A3A3A);
                if (hovered) {
                    context.fill(x, y, x + ITEM_SIZE, y + 1, 0xFF00FF00);
                    context.fill(x, y, x + 1, y + ITEM_SIZE, 0xFF00FF00);
                    context.fill(x + ITEM_SIZE - 1, y, x + ITEM_SIZE, y + ITEM_SIZE, 0xFF00FF00);
                    context.fill(x, y + ITEM_SIZE - 1, x + ITEM_SIZE, y + ITEM_SIZE, 0xFF00FF00);
                } else {
                    context.fill(x, y, x + ITEM_SIZE, y + 1, 0xFF555555);
                    context.fill(x, y, x + 1, y + ITEM_SIZE, 0xFF555555);
                    context.fill(x + ITEM_SIZE - 1, y, x + ITEM_SIZE, y + ITEM_SIZE, 0xFF2A2A2A);
                    context.fill(x, y + ITEM_SIZE - 1, x + ITEM_SIZE, y + ITEM_SIZE, 0xFF2A2A2A);
                }
                context.item(new ItemStack(entry.item), x + 1, y + 1);
                index++;
            }
            if (index >= maxIndex) break;
        }
        context.disableScissor();
        String itemCountText;
        if (searchQuery.isEmpty()) {
            itemCountText = String.format("§e%d §7unique items tracked", filteredItems.size());
        } else {
            itemCountText = String.format("§e%d §7items found (filtered from §e%d§7 total)", filteredItems.size(), allItems.size());
        }
        int countTextWidth = this.font.width(itemCountText);
        int countX = this.width / 2 - countTextWidth / 2;
        int countY = 52;
        context.fill(countX - 4, countY - 2, countX + countTextWidth + 4, countY + 10, 0xDD000000);
        context.text(this.font, itemCountText, countX, countY, 0xFFFFAA00, false);
    }

    private void renderScrollbar(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        // (unchanged)
        if (maxScroll <= 0) return;
        int panelWidth = (ITEMS_PER_ROW * ITEM_SIZE) + 20;
        int scrollbarX = this.width / 2 + panelWidth / 2 + 5;
        int scrollbarY = TOP_PADDING;
        int scrollbarHeight = cachedVisibleHeight;
        context.fill(scrollbarX, scrollbarY, scrollbarX + SCROLLBAR_WIDTH, scrollbarY + scrollbarHeight, 0xFF2A2A2A);
        int visibleRows = scrollbarHeight / ITEM_SIZE;
        int thumbHeight = Math.max(20, (int)((double)visibleRows / cachedTotalRows * scrollbarHeight));
        int scrollableHeight = scrollbarHeight - thumbHeight;
        int thumbY = scrollbarY + (maxScroll > 0 ? (int)((double)scrollOffset / maxScroll * scrollableHeight) : 0);
        boolean hovered = mouseX >= scrollbarX && mouseX <= scrollbarX + SCROLLBAR_WIDTH &&
            mouseY >= thumbY && mouseY <= thumbY + thumbHeight;
        int thumbColor = isDraggingScrollbar ? 0xFF00FF00 : (hovered ? 0xFF00CC00 : 0xFF008800);
        context.fill(scrollbarX + 1, thumbY, scrollbarX + SCROLLBAR_WIDTH - 1, thumbY + thumbHeight, thumbColor);
        context.fill(scrollbarX + 1, thumbY, scrollbarX + SCROLLBAR_WIDTH - 1, thumbY + 1, 0xFF00FF00);
        context.fill(scrollbarX + 1, thumbY + thumbHeight - 1, scrollbarX + SCROLLBAR_WIDTH - 1, thumbY + thumbHeight, 0xFF005500);
    }

    private void renderTooltip(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        // (unchanged except getName fix already done)
        int index = scrollOffset * ITEMS_PER_ROW;
        int maxIndex = filteredItems.size();
        int visibleRows = (cachedVisibleHeight / ITEM_SIZE) + 2;
        int maxRow = Math.min(visibleRows, cachedTotalRows - scrollOffset);
        for (int row = 0; row < maxRow; row++) {
            for (int col = 0; col < ITEMS_PER_ROW; col++) {
                if (index >= maxIndex) return;
                int x = cachedStartX + col * ITEM_SIZE;
                int y = cachedStartY + row * ITEM_SIZE;
                if (y + ITEM_SIZE <= TOP_PADDING || y >= cachedMaxY) { index++; continue; }
                if (mouseX >= x && mouseX < x + ITEM_SIZE && mouseY >= y && mouseY < y + ITEM_SIZE) {
                    ItemEntry entry = filteredItems.get(index);
                    List<TrackedContainer> containers = data.searchItem(entry.item);
                    int withinRange = 0;
                    double renderDist = module.getRenderDistance();
                    if (minecraft != null && minecraft.player != null) {
                        for (TrackedContainer container : containers) {
                            BlockPos pos = container.getPosition();
                            double distSq = minecraft.player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                            if (distSq <= renderDist * renderDist) withinRange++;
                        }
                    }
                    List<Component> tooltip = new ArrayList<>();
                    tooltip.add(Component.literal("§f§l" + entry.item.getName(entry.item.getDefaultInstance()).getString()));
                    tooltip.add(Component.literal(""));
                    tooltip.add(Component.literal("§7Total Amount: §a" + formatCountFull(entry.count)));
                    tooltip.add(Component.literal("§7Found in: §e" + containers.size() + " §7container(s)"));
                    if (withinRange > 0 && withinRange < containers.size()) {
                        tooltip.add(Component.literal("§7Will highlight: §e" + withinRange + " §7nearby"));
                        tooltip.add(Component.literal("§8(Increase render distance for more)"));
                    } else if (withinRange == 0) {
                        tooltip.add(Component.literal("§cAll containers are far away!"));
                        tooltip.add(Component.literal("§8(Increase render distance in settings)"));
                    }
                    tooltip.add(Component.literal(""));
                    tooltip.add(Component.literal("§e§l» Click to Highlight All Within Range «"));
                    context.setComponentTooltipForNextFrame(this.font, tooltip, mouseX, mouseY);
                    return;
                }
                index++;
            }
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {   // MouseButtonEvent import added
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();

        // ... rest of mouseClicked unchanged (scrollbar logic, item click) ...
        // (Include the exact same logic as before, but using MouseButtonEvent)
        if (maxScroll > 0 && button == 0) {
            int panelWidth = (ITEMS_PER_ROW * ITEM_SIZE) + 20;
            int scrollbarX = this.width / 2 + panelWidth / 2 + 5;
            int scrollbarY = TOP_PADDING;
            int totalRows = (int) Math.ceil(filteredItems.size() / (double) ITEMS_PER_ROW);
            int contentHeight = Math.max(totalRows * ITEM_SIZE, MIN_VISIBLE_ROWS * ITEM_SIZE);
            int maxPanelHeight = Math.min(MAX_PANEL_HEIGHT, this.height - TOP_PADDING - BOTTOM_PADDING);
            int scrollbarHeight = Math.min(contentHeight, maxPanelHeight);
            if (mouseX >= scrollbarX && mouseX <= scrollbarX + SCROLLBAR_WIDTH &&
                mouseY >= scrollbarY && mouseY <= scrollbarY + scrollbarHeight) {
                int visibleRows = scrollbarHeight / ITEM_SIZE;
                int thumbHeight = Math.max(20, (int)((double)visibleRows / totalRows * scrollbarHeight));
                int scrollableHeight = scrollbarHeight - thumbHeight;
                int thumbY = scrollbarY + (maxScroll > 0 ? (int)((double)scrollOffset / maxScroll * scrollableHeight) : 0);
                if (mouseY >= thumbY && mouseY <= thumbY + thumbHeight) {
                    isDraggingScrollbar = true;
                    scrollbarDragStartY = (int)mouseY;
                    scrollbarDragStartOffset = scrollOffset;
                    return true;
                } else {
                    double clickRatio = (mouseY - scrollbarY) / (double)scrollableHeight;
                    scrollOffset = (int)(clickRatio * maxScroll);
                    scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset));
                    return true;
                }
            }
        }

        int index = scrollOffset * ITEMS_PER_ROW;
        int maxIndex = filteredItems.size();
        int visibleRows = (cachedVisibleHeight / ITEM_SIZE) + 2;
        int maxRow = Math.min(visibleRows, cachedTotalRows - scrollOffset);
        for (int row = 0; row < maxRow; row++) {
            for (int col = 0; col < ITEMS_PER_ROW; col++) {
                if (index >= maxIndex) break;
                int x = cachedStartX + col * ITEM_SIZE;
                int y = cachedStartY + row * ITEM_SIZE;
                if (y + ITEM_SIZE <= TOP_PADDING || y >= cachedMaxY) { index++; continue; }
                if (mouseX >= x && mouseX < x + ITEM_SIZE && mouseY >= y && mouseY < y + ITEM_SIZE) {
                    ItemEntry entry = filteredItems.get(index);
                    onItemClicked(entry);
                    return true;
                }
                index++;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    private void onItemClicked(ItemEntry entry) {
        List<TrackedContainer> results = data.searchItem(entry.item);
        module.searchItem(entry.item);
        int withinRange = 0;
        if (minecraft != null && minecraft.player != null) {
            double renderDist = module.getRenderDistance();
            for (TrackedContainer container : results) {
                BlockPos pos = container.getPosition();
                double distSq = minecraft.player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                if (distSq <= renderDist * renderDist) withinRange++;
            }
        }
        if (minecraft != null && minecraft.player != null) {
            String msg = withinRange < results.size()
                ? String.format("§aLit: §e%d§7/§f%d §7(%d far)", withinRange, results.size(), results.size() - withinRange)
                : String.format("§aLit: §e%d §7boxes", results.size());
            minecraft.player.sendSystemMessage(Component.literal(msg));
        }
        this.onClose();
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        double mouseX = click.x();
        double mouseY = click.y();
        if (isDraggingScrollbar && maxScroll > 0) {
            int totalRows = (int) Math.ceil(filteredItems.size() / (double) ITEMS_PER_ROW);
            int contentHeight = Math.max(totalRows * ITEM_SIZE, MIN_VISIBLE_ROWS * ITEM_SIZE);
            int maxPanelHeight = Math.min(MAX_PANEL_HEIGHT, this.height - TOP_PADDING - BOTTOM_PADDING);
            int scrollbarHeight = Math.min(contentHeight, maxPanelHeight);
            int visibleRows = scrollbarHeight / ITEM_SIZE;
            int thumbHeight = Math.max(20, (int)((double)visibleRows / totalRows * scrollbarHeight));
            int scrollableHeight = scrollbarHeight - thumbHeight;
            int dragDelta = (int)mouseY - scrollbarDragStartY;
            double scrollRatio = (double)dragDelta / scrollableHeight;
            int newOffset = scrollbarDragStartOffset + (int)(scrollRatio * maxScroll);
            scrollOffset = Math.max(0, Math.min(maxScroll, newOffset));
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        int button = click.button();
        if (isDraggingScrollbar && button == 0) {
            isDraggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (verticalAmount > 0) scrollOffset = Math.max(0, scrollOffset - 1);
        else if (verticalAmount < 0) scrollOffset = Math.min(maxScroll, scrollOffset + 1);
        return true;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private String formatCountFull(int count) { return String.format("%,d", count); }

    private String getCurrentDimension() {
        if (minecraft == null || minecraft.level == null) return "unknown";
        return minecraft.level.dimension().identifier().toString();
    }

    private static class ItemEntry {
        final Item item;
        final int count;
        ItemEntry(Item item, int count) { this.item = item; this.count = count; }
    }

    private enum SortMode {
        COUNT_DESC("Count ↓"),
        COUNT_ASC("Count ↑"),
        NAME_ASC("Name A-Z"),
        NAME_DESC("Name Z-A"),
        DISTANCE("Distance");
        private final String displayName;
        SortMode(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
        public SortMode next() {
            SortMode[] values = values();
            return values[(this.ordinal() + 1) % values.length];
        }
    }
}
