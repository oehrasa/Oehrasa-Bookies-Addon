package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.events.ScreenRenderEvent;
import com.AutoBookshelf.addon.utils.ShulkerInfo;
import com.AutoBookshelf.addon.utils.Type;
import meteordevelopment.meteorclient.events.meteor.MouseScrollEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.themes.meteor.MeteorGuiTheme;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector2f;
import org.lwjgl.glfw.GLFW;

import java.util.*;

public class InventoryInfo extends Module {
    private static final int COLOR_SEPARATOR = 0x64FFFFFF;
    private static final int REFRESH_INTERVAL = 4;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgCustom = settings.createGroup("Customization");
    private final SettingGroup sgBackground = settings.createGroup("Background");

    public final Setting<Boolean> compact = sgGeneral.add(new BoolSetting.Builder()
        .name("Compact")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> combineShulkers = sgGeneral.add(new BoolSetting.Builder()
        .name("combine-shulkers")
        .description("Merge all shulker contents into a single combined grid (more compact).")
        .defaultValue(false)
        .build()
    );

    public enum SearchBarSide {
        Top,
        Bottom
    }

    public final Setting<Boolean> searchBar = sgGeneral.add(new BoolSetting.Builder()
        .name("search-bar")
        .description("Show a search bar above the panel to filter displayed items by name.")
        .defaultValue(false)
        .build()
    );

    public final Setting<SearchBarSide> searchBarSide = sgGeneral.add(new EnumSetting.Builder<SearchBarSide>()
        .name("search-bar-side")
        .description("Where to place the search bar relative to the panel.")
        .defaultValue(SearchBarSide.Top)
        .visible(searchBar::get)
        .build()
    );

    public final Setting<Boolean> inventoryOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("inventory-only")
        .description("Only show the panel while your own inventory screen is open.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> tooltips = sgGeneral.add(new BoolSetting.Builder()
        .name("tooltips")
        .description("Shows the normal item tooltip when hovering an item inside a preview.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> bothSides = sgGeneral.add(new BoolSetting.Builder()
        .name("both-sides")
        .description("Once previews fill up the left side of the screen, continues them on the right.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> showMapIdInTooltip = sgGeneral.add(new BoolSetting.Builder()
        .name("show-map-id-tooltip")
        .description("Append map ID to the tooltip of filled maps.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> renderMapFill = sgGeneral.add(new BoolSetting.Builder()
        .name("render-map-fill")
        .description("Draw the actual rendered map texture for filled maps instead of the item model.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Integer> mapFillSize = sgGeneral.add(new IntSetting.Builder()
        .name("map-fill-size")
        .description("Scale of the rendered filled map, as a percentage. 100% (max) fills the whole slot; lower scales it down.")
        .defaultValue(100)
        .min(10)
        .max(100)
        .sliderRange(10, 100)
        .visible(renderMapFill::get)
        .build()
    );

    public final Setting<Integer> spacing = sgGeneral.add(new IntSetting.Builder()
        .name("spacing")
        .description("Empty space in pixels between the previews.")
        .defaultValue(4)
        .min(0)
        .sliderMax(30)
        .build()
    );

    public final Setting<Integer> panelXOffset = sgCustom.add(new IntSetting.Builder()
        .name("x-offset")
        .defaultValue(0)
        .min(-100)
        .max(100)
        .sliderRange(-100, 100)
        .build()
    );
    public final Setting<Integer> panelYOffset = sgCustom.add(new IntSetting.Builder()
        .name("y-offset")
        .defaultValue(0)
        .min(-100)
        .max(100)
        .sliderRange(-100, 100)
        .build()
    );
    public final Setting<Double> iconScale = sgCustom.add(new DoubleSetting.Builder()
        .name("icon-scale")
        .defaultValue(1.0)
        .min(0.5)
        .max(2.0)
        .sliderRange(0.5, 2.0)
        .decimalPlaces(1)
        .build()
    );

    public final Setting<Integer> compactSlotSize = sgGeneral.add(new IntSetting.Builder()
        .name("compact-slot-size")
        .defaultValue(14)
        .min(8)
        .max(20)
        .sliderRange(8, 20)
        .visible(compact::get)
        .build()
    );
    public final Setting<Integer> compactColumns = sgGeneral.add(new IntSetting.Builder()
        .name("compact-columns")
        .defaultValue(12)
        .min(6)
        .max(16)
        .sliderRange(6, 16)
        .visible(compact::get)
        .build()
    );

    public final Setting<SettingColor> backgroundColor = sgBackground.add(new ColorSetting.Builder()
        .name("background-color")
        .description("Color of the preview background.")
        .defaultValue(new SettingColor(16, 16, 20, 200))
        .build()
    );

    public final Setting<Boolean> backgroundColorUseTheme = sgBackground.add(new BoolSetting.Builder()
        .name("background-use-theme")
        .description("Use the current Meteor theme accent color.")
        .defaultValue(false)
        .build()
    );

    private final List<ShulkerInfo> info = new ArrayList<>();
    private int height, offset;
    private Vector2f clicked;
    private ItemStack hoveredTooltip;
    private GuiGraphicsExtractor lastGraphics;

    // Cached render inputs, rebuilt on the throttled refresh: per-shulker
    // visible slices and the merged combined-grid entries. This keeps per-frame
    // rendering from rebuilding arraylists, rescanning stacks or ItemStack.copy()ing
    // while the search bar is idle; the search filter is applied live on top.
    private List<List<ItemStack>> shulkerVisibleCache = null;
    private List<DisplayEntry> combinedCache = null;
    private int refreshTickCounter = 0;

    // search bar state
    private final StringBuilder searchQuery = new StringBuilder();
    private boolean searchFocused = false;

    // Scrolling
    private double scrollOffset = 0.0;
    private double maxScroll = 0.0;
    private static final double SCROLL_STEP = 20.0;

    // Cached grid measurements to avoid recomputing scroll limits per frame
    private List<ShulkerGrid> measuredGridsCache = null;
    private int measuredGridsColumns = -1;
    private int measuredGridsSlotSize = -1;

    // Panel rect tracking for mouse scroll hit test
    private int panelLeft = 0, panelRight = 0, panelTop = 0, panelBottom = 0;
    private int lastMouseX = -1, lastMouseY = -1;

    // Cached scroll limit for a given layout key
    private double cachedScrollLimit = -1.0;
    private long scrollLimitKey = 0;

    // Cached combined filtered results for current query to avoid filtering lists every frame
    private List<DisplayEntry> combinedFilteredCache = null;
    private long combinedFilteredKey = 0;

    // Track last inventory screen to invalidate caches on screen change
    private Screen lastScreen = null;

    private record DisplayEntry(ItemStack stack, int slot) {
    }

    private record ShulkerGrid(ShulkerInfo info, List<ItemStack> stacks, int rows, int width) {
    }

    private record PlacedGrid(ShulkerGrid grid, int x, int y) {
    }

    public InventoryInfo() {
        super(Addon.CATEGORY, "Inventory-Info", "prigozhinplugg");
    }
    //TODO
    // Make proper component display.
    // Add profile target, litematica Material list feature.

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!(mc.screen instanceof AbstractContainerScreen<?>)) {
            info.clear();
            shulkerVisibleCache = null;
            combinedCache = null;
            return;
        }
        if (inventoryOnly.get() && !(mc.screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen)) {
            info.clear();
            return;
        }
        if (refreshTickCounter++ % REFRESH_INTERVAL != 0) return;
        refresh((AbstractContainerScreen<?>) mc.screen);
    }

    @EventHandler
    private void onSearchCharTypedEvent(meteordevelopment.meteorclient.events.meteor.CharTypedEvent event) {
        if (!searchBar.get()) return;
        if (!searchFocused) return;
        char chr = event.c;
        if (chr >= 32 && searchQuery.length() < 32) {
            searchQuery.append(chr);
            invalidateSearchCaches();
            event.cancel();
        }
    }

    @EventHandler
    private void onSearchKeyPressedEvent(meteordevelopment.meteorclient.events.meteor.KeyInputEvent event) {
        if (!searchBar.get()) return;
        if (!searchFocused) return;
        if (event.action != meteordevelopment.meteorclient.utils.misc.input.KeyAction.Press) return;
        int keyCode = event.key();
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !searchQuery.isEmpty()) {
            searchQuery.deleteCharAt(searchQuery.length() - 1);
            invalidateSearchCaches();
            event.cancel();
        } else if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            searchFocused = false;
            event.cancel();
        } else if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            searchFocused = false;
            event.cancel();
        }
    }

    @EventHandler
    private void onRenderScreen(ScreenRenderEvent event) {
        // The inventory screen posts ScreenRenderEvent twice per frame (once from
        // the AbstractContainerScreen.extractContents tail, once from the
        // InventoryScreen.extractRenderState tail), both with the same
        // per-frame GuiGraphicsExtractor. Only render once per frame.
        // NOTE: Utils.frameTime is updated per tick, not per frame
        if (event.graphics == lastGraphics) return;
        lastGraphics = event.graphics;

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        hoveredTooltip = null;
        event.graphics.enableScissor(0, 0, screenWidth, screenHeight);

        if (info.isEmpty()) {
            event.graphics.disableScissor();
            return;
        }

        int baseX = 2 + panelXOffset.get();
        int baseY = 3 + offset + panelYOffset.get();

        lastMouseX = event.mouseX;
        lastMouseY = event.mouseY;

        if (mc.screen != lastScreen) {
            lastScreen = mc.screen;
            invalidateSearchCaches();
        }

        panelLeft = panelRight = panelTop = panelBottom = 0;

        int afterSearchY = baseY;
        if (searchBar.get() && searchBarSide.get() == SearchBarSide.Top) {
            afterSearchY = renderSearchBar(event, baseX, baseY);
        }

        if (combineShulkers.get()) {
            renderCombinedGrid(event, baseX, afterSearchY);
        } else {
            renderPerShulkerGrid(event, baseX, afterSearchY);
        }

        if (searchBar.get() && searchBarSide.get() == SearchBarSide.Bottom) {
            renderSearchBar(event, baseX, afterSearchY);
        }

        if (tooltips.get() && hoveredTooltip != null) {
            List<Component> tooltip = buildTooltip(hoveredTooltip);
            event.graphics.setComponentTooltipForNextFrame(mc.font, tooltip, event.mouseX, event.mouseY);
        }

        event.graphics.disableScissor();
    }

    private void renderPerShulkerGrid(ScreenRenderEvent event, int baseX, int baseY) {
        boolean isCompact = compact.get();
        int slotSize = isCompact ? compactSlotSize.get() : 20;
        int columns = isCompact ? compactColumns.get() : 9;
        float scale = (isCompact ? slotSize / 16.0f : 1.0f) * iconScale.get().floatValue();
        int gap = spacing.get();

        List<ShulkerGrid> grids = measuredGrids(columns, slotSize);
        if (grids.isEmpty()) {
            panelLeft = panelRight = panelTop = panelBottom = 0;
            height = 0;
            setClicked(null);
            return;
        }

        boolean both = bothSides.get();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int viewportHeight = screenHeight - (baseY + 4);

        // Compute scroll limit and apply offset
        double limit = cachedScrollLimit(grids, gap, viewportHeight, both ? columns : 1, slotSize);
        if (limit < 0.0) limit = 0.0;
        maxScroll = limit;
        scrollOffset = Mth.clamp(scrollOffset, 0.0, maxScroll);
        int scrollY = (int) -scrollOffset;

        List<PlacedGrid> placed = new ArrayList<>();
        int x = baseX;
        int y = baseY + scrollY;
        int columnWidth = 0;
        int maxBottom = baseY;
        int minTop = baseY;

        for (ShulkerGrid grid : grids) {
            int gridHeight = grid.rows() * slotSize;

            if (both && y != baseY + scrollY && y + gridHeight > baseY + viewportHeight) {
                x = x + columnWidth + gap;
                columnWidth = 0;
                y = baseY + scrollY;
            }

            placed.add(new PlacedGrid(grid, x, y));
            columnWidth = Math.max(columnWidth, grid.width());
            maxBottom = Math.max(maxBottom, y + gridHeight);
            minTop = Math.min(minTop, y);
            y += gridHeight + gap;
        }

        int left = baseX;
        int right = baseX;
        for (PlacedGrid p : placed) {
            if (p.x() < left) left = p.x();
            if (p.x() + p.grid().width() > right) right = p.x() + p.grid().width();
        }

        panelLeft = left;
        panelRight = right;
        panelTop = minTop;
        panelBottom = Math.max(maxBottom, baseY + viewportHeight);

        for (PlacedGrid p : placed) {
            ShulkerGrid grid = p.grid();

            int startY = p.y();
            int endY = startY + grid.rows() * slotSize;
            if (endY < baseY) continue;
            if (startY > baseY + viewportHeight) continue;
            int maxX = p.x() + grid.width();
            if (maxX < baseX) continue;
            if (p.x() > baseX + (both ? 3000 : grid.width())) continue;

            drawBackground(event, p.x(), startY, maxX, endY);
            event.graphics.fill(p.x(), startY - 1, maxX, startY, grid.info().color());

            int count = 0, drawX = p.x();
            int drawY = startY;
            for (ItemStack stack : grid.stacks()) {
                if (count > 0 && count % columns == 0) {
                    drawX = p.x();
                    drawY += slotSize;
                }
                if (drawY + slotSize < baseY) {
                    drawX += slotSize;
                    count++;
                    continue;
                }
                if (drawY > baseY + viewportHeight) {
                    break;
                }

                drawScaledItem(event, stack, drawX, drawY, slotSize, scale);
                if (isHovering(drawX, drawY, slotSize, event)) hoveredTooltip = stack;

                drawX += slotSize;
                count++;
            }

            if (clicked != null
                && clicked.x >= p.x() && clicked.x <= maxX
                && clicked.y >= startY && clicked.y <= endY) {
                mc.gameMode.handleContainerInput(
                    mc.player.containerMenu.containerId,
                    grid.info().slot(), 0, ContainerInput.PICKUP, mc.player);
                setClicked(null);
            }
        }

        height = Math.max(maxBottom - offset, baseY - offset);
        setClicked(null);
    }

    private void renderCombinedGrid(ScreenRenderEvent event, int baseX, int baseY) {
        List<DisplayEntry> base = combinedCache;
        if (base == null) {
            base = buildCombinedBase();
            combinedCache = base;
        }

        List<DisplayEntry> entries;
        if (searchFilterActive()) {
            long key = 0;
            String q = searchQuery.toString().toLowerCase();
            for (int i = 0; i < q.length(); i++) key = key * 131542391L + (long) q.charAt(i);
            key = key * 1000000009L + (long) base.size();
            if (combinedFilteredCache != null && combinedFilteredKey == key) {
                entries = combinedFilteredCache;
            } else {
                entries = new ArrayList<>();
                for (DisplayEntry entry : base) {
                    if (matchesSearch(entry.stack())) entries.add(entry);
                }
                combinedFilteredCache = entries;
                combinedFilteredKey = key;
            }
        } else {
            entries = base;
        }

        boolean isCompact = compact.get();
        int slotSize = isCompact ? compactSlotSize.get() : 20;
        int columns = isCompact ? compactColumns.get() : 9;
        float scale = (isCompact ? slotSize / 16.0f : 1.0f) * iconScale.get().floatValue();

        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int viewportHeight = screenHeight - (baseY + 4);

        int startY = baseY + (int) -scrollOffset;
        int rows = entries.isEmpty() ? 0 : (entries.size() + columns - 1) / columns;
        int cols = Math.min(entries.size(), columns);
        int maxX = baseX + (rows > 1 ? columns : cols) * slotSize;
        int y = startY + rows * slotSize;

        double limit = entries.isEmpty() ? 0.0 : (double) (y - (baseY + viewportHeight));
        if (limit < 0.0) limit = 0.0;
        maxScroll = limit;
        scrollOffset = Mth.clamp(scrollOffset, 0.0, maxScroll);
        startY = baseY + (int) -scrollOffset;
        y = startY + rows * slotSize;

        panelLeft = baseX;
        panelRight = maxX;
        panelTop = baseY;
        panelBottom = Math.max(y, baseY + viewportHeight);

        if (!entries.isEmpty()) {
            drawBackground(event, baseX, startY, maxX, y);
            event.graphics.fill(baseX, startY - 1, maxX, startY, COLOR_SEPARATOR);
        }

        for (int i = 0; i < entries.size(); i++) {
            int col = i % columns;
            int row = i / columns;
            int drawX = baseX + col * slotSize;
            int drawY = startY + row * slotSize;
            if (drawY + slotSize < baseY) continue;
            if (drawY > baseY + viewportHeight) continue;

            DisplayEntry entry = entries.get(i);
            drawScaledItem(event, entry.stack(), drawX, drawY, slotSize, scale);
            if (isHovering(drawX, drawY, slotSize, event)) hoveredTooltip = entry.stack();

            if (clicked != null
                && clicked.x >= drawX && clicked.x <= drawX + slotSize
                && clicked.y >= drawY && clicked.y <= drawY + slotSize) {
                mc.gameMode.handleContainerInput(
                    mc.player.containerMenu.containerId,
                    entry.slot(), 0, ContainerInput.PICKUP, mc.player);
                setClicked(null);
            }
        }

        height = Math.max(y - offset, baseY - offset);
        setClicked(null);
    }

    private int renderSearchBar(ScreenRenderEvent event, int baseX, int baseY) {
        int barWidth = searchBarWidth();
        int barHeight = 12;

        if (clicked != null && clicked.x >= baseX && clicked.x <= baseX + barWidth
            && clicked.y >= baseY && clicked.y <= baseY + barHeight) {
            searchFocused = true;
            setClicked(null);
        } else if (clicked != null) {
            searchFocused = false;
        }

        var context = event.graphics;
        int borderColor = searchFocused ? 0xFFFFFFFF : COLOR_SEPARATOR;

        // Background
        drawBackground(event, baseX, baseY, baseX + barWidth, baseY + barHeight);

        // Border (manual)
        context.fill(baseX, baseY, baseX + barWidth, baseY + 1, borderColor);                     // top
        context.fill(baseX, baseY + barHeight - 1, baseX + barWidth, baseY + barHeight, borderColor); // bottom
        context.fill(baseX, baseY, baseX + 1, baseY + barHeight, borderColor);                     // left
        context.fill(baseX + barWidth - 1, baseY, baseX + barWidth, baseY + barHeight, borderColor); // right

        String text = searchQuery.length() > 0 ? searchQuery.toString() : "Search...";
        context.text(mc.font, text, baseX + 3, baseY + 2,
            searchQuery.length() > 0 ? 0xFFFFFFFF : 0x80FFFFFF, false);

        return baseY + barHeight + 2;
    }

    private boolean matchesSearch(ItemStack stack) {
        if (!searchBar.get() || searchQuery.length() == 0) return true;
        return stack.getHoverName().getString().toLowerCase().contains(searchQuery.toString().toLowerCase());
    }

    /**
     * Draws a scaled item icon and its overlay (durability bar, count text),
     * centered within a cell so compact grids don't bleed into the neighbor.
     */
    private void drawScaledItem(ScreenRenderEvent event, ItemStack stack, int cellX, int cellY, int cellSize, float scale) {
        float itemPixelSize = 16.0f * scale;
        int drawX = cellX + (int) ((cellSize - itemPixelSize) / 2);
        int drawY = cellY + (int) ((cellSize - itemPixelSize) / 2);
        String countText = stack.getCount() > 999 ? formatCount(stack.getCount()) : null;

        var context = event.graphics;
        var matrices = context.pose();

        // 1. Draw the item/icon at the requested scale.
        matrices.pushMatrix();
        matrices.translate(drawX, drawY);
        matrices.scale(scale, scale);

        // Special case: draw rendered map texture for filled maps
        if (renderMapFill.get() && stack.is(net.minecraft.world.item.Items.FILLED_MAP)) {
            var mapId = stack.get(net.minecraft.core.component.DataComponents.MAP_ID);
            if (mapId != null && mc.level != null) {
                var mapState = net.minecraft.world.item.MapItem.getSavedData(mapId, mc.level);
                if (mapState != null) {
                    var renderState = new net.minecraft.client.renderer.state.MapRenderState();
                    mc.getMapRenderer().extractRenderState(mapId, mapState, renderState);
                    // map() renders at 128x128 map-pixels; default 100% = 0.125 fills the whole slot
                    float mapScale = mapFillSize.get() / 800.0f;
                    matrices.scale(mapScale, mapScale);
                    context.map(renderState);
                    matrices.popMatrix();
                    // Overlays without scale
                    matrices.pushMatrix();
                    matrices.translate(drawX, drawY);
                    context.itemDecorations(mc.font, stack, 0, 0, countText);
                    matrices.popMatrix();
                    return;
                }
            }
        }

        context.item(stack, 0, 0);
        matrices.popMatrix();

        // 2. Draw the overlay (durability bar, count text) without the scale
        //    matrix active. drawStackOverlay() uses hardcoded pixel geometry
        //    that must render at 1:1 screen pixels relative to (drawX, drawY).
        matrices.pushMatrix();
        matrices.translate(drawX, drawY);
        context.itemDecorations(mc.font, stack, 0, 0, countText);
        matrices.popMatrix();
    }

    private String formatCount(int count) {
        if (count >= 1000) {
            double d = count / 1000.0;
            if (d == (int) d) return (int) d + "k";
            return String.format("%.1fk", d);
        }
        return String.valueOf(count);
    }

    private void drawBackground(ScreenRenderEvent event, int x, int startY, int maxX, int endY) {
        SettingColor color = backgroundColorUseTheme.get()
            && GuiThemes.get() instanceof MeteorGuiTheme theme ? theme.accentColor.get() : backgroundColor.get();

        if (color.a == 0) return;
        event.graphics.fill(x, startY, maxX, endY, color.getPacked());
    }

    private boolean isHovering(int x, int y, int size, ScreenRenderEvent event) {
        return event.mouseX >= x && event.mouseX < x + size && event.mouseY >= y && event.mouseY < y + size;
    }

    private boolean searchFilterActive() {
        return searchBar.get() && searchQuery.length() > 0;
    }

    /**
     * Returns the visible item list for one shulker grid. With the search bar
     * idle this is served from the refresh-cached slice; while a query is active
     * the filter is applied live so typed results update immediately.
     */
    private List<ItemStack> visibleStacks(int index, ShulkerInfo shulkerInfo) {
        if (searchFilterActive()) {
            List<ItemStack> visible = new ArrayList<>();
            for (ItemStack stack : shulkerInfo.stacks()) {
                if (shulkerInfo.type() == Type.COMPACT && stack.isEmpty()) break;
                if (!matchesSearch(stack)) continue;
                visible.add(stack);
            }
            return visible;
        }

        if (shulkerVisibleCache != null) return shulkerVisibleCache.get(index);

        List<ItemStack> visible = new ArrayList<>();
        for (ItemStack stack : shulkerInfo.stacks()) {
            if (shulkerInfo.type() == Type.COMPACT && stack.isEmpty()) break;
            visible.add(stack);
        }
        return visible;
    }

    /**
     * Merged view of all shulker contents for the combined grid, built once per
     * info refresh so rendering doesn't copy stacks or rescan every frame. The
     * search filter (when active) is applied on top at render time.
     */
    private List<DisplayEntry> buildCombinedBase() {
        Map<Item, Integer> combined = new HashMap<>();
        Map<Item, Integer> itemToSlot = new HashMap<>();
        Map<Item, ItemStack> itemToStack = new HashMap<>();

        for (ShulkerInfo shulkerInfo : info) {
            for (ItemStack stack : shulkerInfo.stacks()) {
                if (stack.isEmpty()) continue;
                Item item = stack.getItem();
                combined.merge(item, stack.getCount(), Integer::sum);
                itemToSlot.putIfAbsent(item, shulkerInfo.slot());
                itemToStack.putIfAbsent(item, stack.copy());
            }
        }

        List<DisplayEntry> entries = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : combined.entrySet()) {
            Item item = e.getKey();
            int total = e.getValue();
            int slot = itemToSlot.get(item);
            ItemStack template = itemToStack.get(item);
            ItemStack display = template.copy();
            display.setCount(total);
            entries.add(new DisplayEntry(display, slot));
        }
        entries.sort(Comparator
            .comparingInt((DisplayEntry e) -> -e.stack().getCount())
            .thenComparing(e -> e.stack().getHoverName().getString()));
        return entries;
    }

    private void rebuildRenderCaches() {
        combinedCache = null;
        if (searchFilterActive()) {
            shulkerVisibleCache = null;
            return;
        }

        List<List<ItemStack>> cache = new ArrayList<>(info.size());
        for (ShulkerInfo shulkerInfo : info) {
            List<ItemStack> visible = new ArrayList<>();
            for (ItemStack stack : shulkerInfo.stacks()) {
                if (shulkerInfo.type() == Type.COMPACT && stack.isEmpty()) break;
                visible.add(stack);
            }
            cache.add(visible);
        }
        shulkerVisibleCache = cache;
    }

    private void refresh(AbstractContainerScreen<?> screen) {
        info.clear();
        for (Slot slot : screen.getMenu().slots) {
            ShulkerInfo shulkerInfo = ShulkerInfo.create(slot.getItem(), slot.index);
            if (shulkerInfo == null) continue;
            info.add(shulkerInfo);
        }
        invalidateSearchCaches();
        rebuildRenderCaches();
    }

    public int getOffset() {
        return offset;
    }

    public void setOffset(int offset) {
        this.offset = Mth.clamp(offset, -Math.max(height - mc.getWindow().getGuiScaledHeight(), 0), 0);
    }

    public void setClicked(Vector2f clicked) {
        this.clicked = clicked;
    }

    public void onSearchCharTyped(char chr) {
        if (!searchFocused) return;
        if (chr >= 32 && searchQuery.length() < 32) {
            searchQuery.append(chr);
            invalidateSearchCaches();
        }
    }

    public void onSearchKeyPressed(int keyCode) {
        if (!searchFocused) return;
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !searchQuery.isEmpty()) {
            searchQuery.deleteCharAt(searchQuery.length() - 1);
            invalidateSearchCaches();
        } else if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            searchFocused = false;
        }
    }

    public boolean isSearchFocused() {
        return searchFocused;
    }

    @EventHandler
    private void onMouseScroll(MouseScrollEvent event) {
        if (info.isEmpty()) return;
        if (!(mc.screen instanceof AbstractContainerScreen<?>)) return;

        // Only claim the wheel over the panel itself. Without this the cursor could be
        // anywhere and scrolling there would still drive this panel, and cancelling it
        // unconditionally would starve every other listener of the event.
        // ScreenRenderEvent carries the cursor in scaled coordinates, the same space
        // isOverPanel and the panel rectangle use. MouseScrollEvent has no position of
        // its own, so the wheel handler reads the last one recorded here.
        if (!isOverPanel(lastMouseX, lastMouseY)) return;

        // Positive value is scrolling up, which moves the content up.
        double delta = Math.signum(event.value) * SCROLL_STEP;
        scrollOffset = Mth.clamp(scrollOffset - delta, 0.0, maxScroll);
        event.cancel();
    }

    private boolean isOverPanel(int mouseX, int mouseY) {
        return mouseX >= panelLeft && mouseX <= panelRight && mouseY >= panelTop && mouseY <= panelBottom;
    }

    private List<Component> buildTooltip(ItemStack stack) {
        List<Component> tooltip = new ArrayList<>(Screen.getTooltipFromItem(mc, stack));
        if (showMapIdInTooltip.get()) {
            var mapId = stack.get(net.minecraft.core.component.DataComponents.MAP_ID);
            if (mapId != null) {
                String idText = "#" + mapId.id();
                boolean alreadyPresent = tooltip.stream().anyMatch(line -> line.getString().contains(idText));
                if (!alreadyPresent) {
                    if (tooltip.isEmpty()) {
                        tooltip.add(Component.literal(idText));
                    } else {
                        tooltip.addFirst(Component.literal(idText));
                    }
                }
            }
        }
        return tooltip;
    }

    private List<ShulkerGrid> measuredGrids(int columns, int slotSize) {
        if (measuredGridsCache != null
            && measuredGridsColumns == columns
            && measuredGridsSlotSize == slotSize) {
            return measuredGridsCache;
        }

        List<ShulkerGrid> grids = new ArrayList<>(info.size());
        for (int i = 0; i < info.size(); i++) {
            ShulkerInfo shulkerInfo = info.get(i);
            List<ItemStack> visible = visibleStacks(i, shulkerInfo);
            if (visible.isEmpty()) continue;

            int rows = (visible.size() + columns - 1) / columns;
            int cols = Math.min(visible.size(), columns);
            int width = (rows > 1 ? columns : cols) * slotSize;
            grids.add(new ShulkerGrid(shulkerInfo, visible, rows, width));
        }

        measuredGridsCache = grids;
        measuredGridsColumns = columns;
        measuredGridsSlotSize = slotSize;
        return grids;
    }

    private double cachedScrollLimit(List<ShulkerGrid> grids, int gap, int viewportHeight, int maxColumns, int slotSize) {
        // Build key: grid count, widths/rows, layout params, bothSides, panel offsets
        long key = (long) grids.size();
        key = key * 131542391L + (long) gap;
        key = key * 1000000009L + (long) viewportHeight;
        key = key * 17L + (long) maxColumns;
        key = key * 19L + (long) slotSize;
        key = key * 2L + (bothSides.get() ? 1L : 0L);
        key = key * 2000000000L + (long) (panelXOffset.get() * 131542391L);
        key = key * 2000000000L + (long) (panelYOffset.get() * 1000000009L);

        for (int i = 0; i < Math.min(grids.size(), 16); i++) {
            ShulkerGrid g = grids.get(i);
            key = key * 131542391L + (long) g.width();
            key = key * 1000000009L + (long) g.rows();
        }

        if (cachedScrollLimit >= 0.0 && scrollLimitKey == key) {
            return cachedScrollLimit;
        }

        double limit = scrollLimitFor(grids, gap, viewportHeight, maxColumns, slotSize);
        cachedScrollLimit = limit;
        scrollLimitKey = key;
        return limit;
    }

    private double scrollLimitFor(List<ShulkerGrid> grids, int gap, int viewportHeight, int maxColumns, int slotSize) {
        if (grids.isEmpty()) return 0.0;
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int available = viewportHeight >= 0 ? viewportHeight : (screenHeight - 4);
        int totalHeight = 0;
        for (ShulkerGrid g : grids) {
            totalHeight += g.rows() * slotSize + gap;
        }
        if (totalHeight <= gap) totalHeight = 0;
        else totalHeight -= gap;
        double limit = totalHeight - (double) available;
        if (limit < 0.0) limit = 0.0;
        return limit;
    }

    private int searchBarWidth() {
        boolean isCompact = compact.get();
        int slotSize = isCompact ? compactSlotSize.get() : 20;
        int columns = isCompact ? compactColumns.get() : 9;
        return columns * slotSize;
    }

    private void invalidateSearchCaches() {
        measuredGridsCache = null;
        measuredGridsColumns = -1;
        measuredGridsSlotSize = -1;
        cachedScrollLimit = -1.0;
        scrollLimitKey = 0;
        combinedFilteredCache = null;
        combinedFilteredKey = 0;
        shulkerVisibleCache = null;
    }
}
