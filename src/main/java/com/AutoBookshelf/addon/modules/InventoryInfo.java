package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.events.ScreenRenderEvent;
import com.AutoBookshelf.addon.utils.ShulkerInfo;
import com.AutoBookshelf.addon.utils.Type;
import meteordevelopment.meteorclient.events.meteor.MouseScrollEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.render.MapRenderState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.map.MapState;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;
import org.lwjgl.glfw.GLFW;

import java.util.*;

public class InventoryInfo extends Module {
    private static final int COLOR_BACKGROUND = 0x4B000000;
    private static final int COLOR_SEPARATOR = 0x64FFFFFF;
    private static final int REFRESH_INTERVAL = 4;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgCustom = settings.createGroup("Customization");

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

    public final Setting<Boolean> tooltips = sgGeneral.add(new BoolSetting.Builder()
        .name("tooltips")
        .description("Show the item tooltip for the entry under the cursor.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> bothSides = sgGeneral.add(new BoolSetting.Builder()
        .name("both-sides")
        .description("Once previews fill up the left side of the screen, continues them on the right.")
        .defaultValue(false)
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

    public final Setting<Boolean> searchBar = sgGeneral.add(new BoolSetting.Builder()
        .name("search-bar")
        .description("Show a search bar above the panel to filter displayed items by name.")
        .defaultValue(false)
        .build()
    );

    public final Setting<SearchBarSide> searchBarSide = sgGeneral.add(new EnumSetting.Builder<SearchBarSide>()
        .name("search-bar-side")
        .description("Which side the search bar sits on. Right hugs the overflow column when both-sides is on.")
        .defaultValue(SearchBarSide.Left)
        .visible(() -> searchBar.get())
        .build()
    );

    public final Setting<Boolean> inventoryOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("inventory-only")
        .description("Only show the panel while your own inventory screen is open.")
        .defaultValue(false)
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

    public final Setting<Boolean> showMapIdInTooltip = sgGeneral.add(new BoolSetting.Builder()
        .name("show-map-id-tooltip")
        .description("Prepend the map ID to the tooltip of filled maps.")
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

    private final List<ShulkerInfo> info = new ArrayList<>();
    private int height, offset;
    private Vec2f clicked;
    private ItemStack hoveredTooltip;
    private DrawContext lastGraphics;

    // Cached render inputs, rebuilt on the throttled refresh: per-shulker
    // visible slices and the merged combined-grid entries. This keeps per-frame
    // rendering from rebuilding arraylists, rescanning stacks or ItemStack.copy()ing
    // while the search bar is idle; the search filter is applied live on top.
    private List<List<ItemStack>> shulkerVisibleCache = null;
    private List<DisplayEntry> combinedCache = null;

    // Combined grid filtered by the current search query, plus the query it was
    // built from. Rebuilt only when the query changes rather than every frame.
    private List<DisplayEntry> combinedFilteredCache = null;
    private String combinedFilteredKey = null;
    private int refreshTickCounter = 0;

    // The screen the cached data belongs to, so a newly opened container refreshes
    // immediately and a closed one invalidates the cache instead of lingering.
    private Screen lastScreen = null;

    // search bar state
    private final StringBuilder searchQuery = new StringBuilder();
    private boolean searchFocused = false;

    // Rows scrolled out of view above the panel, and the total that can be scrolled
    // away. Recomputed each render, since it changes with the screen size, the
    // search filter and the icon scale.
    private int scrollOffset = 0;
    private int maxScroll = 0;
    private static final int SCROLL_STEP = 18;

    // Measured per-shulker grids, invalidated whenever the slot data, the search
    // filter or the layout settings change. The two values below are the columns and
    // slot size it was measured with, since those can change mid-screen.
    private List<ShulkerGrid> measuredGridsCache = null;
    private int measuredGridsColumns = -1;
    private int measuredGridsSlotSize = -1;

    // Panel rectangle in scaled-screen coordinates, recorded during the render pass
    // so the wheel handler can tell whether the cursor is actually over the panel.
    private int panelLeft, panelRight, panelTop, panelBottom;
    private int lastMouseX, lastMouseY;

    // Memo for cachedScrollLimit, plus the signature it was computed from.
    private int cachedScrollLimit = 0;
    private String scrollLimitKey = null;

    private record DisplayEntry(ItemStack stack, int slot) {
    }

    /**
     * Which screen edge the search bar is anchored to.
     */
    private enum SearchBarSide {
        Left,
        Right
    }

    /**
     * One shulker's visible contents plus the cell geometry needed to place it.
     */
    private record ShulkerGrid(ShulkerInfo info, List<ItemStack> stacks, int rows, int width) {
    }

    /**
     * A {@link ShulkerGrid} that the placement pass has assigned screen coordinates.
     */
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
        // Leaving a container: drop the tracked screen and the slot data, so the
        // next render cannot draw a previous container's contents.
        if (!(mc.currentScreen instanceof HandledScreen<?>)) {
            if (lastScreen != null) {
                lastScreen = null;
                scrollOffset = 0;
                maxScroll = 0;
                info.clear();
                shulkerVisibleCache = null;
                combinedCache = null;
                measuredGridsCache = null;
            }
            return;
        }

        if (inventoryOnly.get() && !(mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.InventoryScreen)) {
            info.clear();
            // The measured grids would otherwise be rebuilt against the empty list and
            // cached, leaving the panel blank once this setting is turned back off.
            measuredGridsCache = null;
            return;
        }

        // A newly opened screen shares the counter with the previous one, so the
        // first tick can land anywhere in the interval and show a blank panel for
        // up to REFRESH_INTERVAL ticks. Refresh straight away when the screen
        // changed, then fall back to the interval while it stays the same.
        if (mc.currentScreen != lastScreen) {
            lastScreen = mc.currentScreen;
            refreshTickCounter = 0;
            refresh((HandledScreen<?>) mc.currentScreen);
            return;
        }
        if (refreshTickCounter++ % REFRESH_INTERVAL != 0) return;
        refresh((HandledScreen<?>) mc.currentScreen);
    }

    /**
     * Scrolls the preview flow. Applies whether or not both-sides is on: a single
     * column overflows past the screen bottom just as surely as two do, so the
     * one-offset-for-every-column choice only decides that both columns move
     * together.
     */
    @EventHandler
    private void onMouseScroll(MouseScrollEvent event) {
        if (info.isEmpty()) return;
        if (!(mc.currentScreen instanceof HandledScreen<?>)) return;

        // Only claim the wheel over the panel itself. Without this the cursor could be
        // anywhere and scrolling there would still drive this panel, and cancelling it
        // unconditionally would starve every other listener of the event.
        if (!isOverPanel(lastMouseX, lastMouseY)) return;

        // Positive value is scrolling up, which moves the content up.
        int delta = (int) Math.signum(event.value) * SCROLL_STEP;
        scrollOffset = MathHelper.clamp(scrollOffset - delta, 0, maxScroll);
        event.cancel();
    }

    private boolean isOverPanel(int mouseX, int mouseY) {
        return mouseX >= panelLeft && mouseX <= panelRight
            && mouseY >= panelTop && mouseY <= panelBottom;
    }

    @EventHandler
    private void onRenderScreen(ScreenRenderEvent event) {
        // The inventory screen posts both HandledScreen#render and its own
        // extractRenderState every frame with the same DrawContext, which would
        // draw the whole panel twice. A per-frame identity check on the context
        // dedupes it; a tick/time-based check cannot, because both posts land
        // inside one tick.
        if (event.drawContext == lastGraphics) return;
        lastGraphics = event.drawContext;

        int screenWidth = mc.getWindow().getScaledWidth();
        int screenHeight = mc.getWindow().getScaledHeight();
        hoveredTooltip = null;
        // ScreenRenderEvent carries the cursor in scaled coordinates, the same space
        // isHovering and the panel rectangle use. MouseScrollEvent has no position of
        // its own, so the wheel handler reads the last one recorded here.
        lastMouseX = event.mouseX;
        lastMouseY = event.mouseY;
        event.drawContext.enableScissor(0, 0, screenWidth, screenHeight);

        if (info.isEmpty()) {
            event.drawContext.disableScissor();
            return;
        }

        int baseX = 2 + panelXOffset.get();
        int baseY = 3 + offset + panelYOffset.get();

        // Seed the hit rectangle here and let the layout pass widen it. The search bar
        // sits above the grids but belongs to the panel, so the top is taken before
        // baseY advances past it.
        panelLeft = baseX;
        panelRight = baseX;
        panelTop = baseY;
        panelBottom = baseY;

        if (searchBar.get()) {
            // Anchoring to the right mirrors the overflow column it belongs with, so a
            // search bar never sits on the opposite edge from the panel it filters.
            int barWidth = searchBarWidth();
            int barX = searchBarSide.get() == SearchBarSide.Right
                ? screenWidth - barWidth - 2
                : baseX;

            // The bar counts as part of the panel even when it is anchored to the edge
            // opposite the grids. Without this the right-hand bar sits entirely outside
            // the hit rectangle and scrolling with the cursor over it does nothing,
            // while the same spot over a left-hand bar scrolls fine.
            panelLeft = Math.min(panelLeft, barX);
            panelRight = Math.max(panelRight, barX + barWidth);

            baseY = renderSearchBar(event, barX, baseY);
        }

        if (combineShulkers.get()) {
            renderCombinedGrid(event, baseX, baseY);
        } else {
            renderPerShulkerGrid(event, baseX, baseY);
        }

        // The single pop for the panel scissor pushed at the top of this method.
        // Releasing it here is what lets the tooltip draw outside the panel: the panel
        // and per-grid clips bound drawing to the region the grids occupy, so leaving
        // one active trims the tooltip against the search bar when the two are
        // anchored to opposite edges of the screen. Popping here rather than inside
        // the tooltip block keeps the stack balanced whether or not one is drawn -
        // pop() throws "Scissor stack underflow" when it has nothing to pop.
        event.drawContext.disableScissor();

        if (tooltips.get() && hoveredTooltip != null) {
            List<Text> tooltip = buildTooltip(hoveredTooltip);
            if (tooltip != null) {
                event.drawContext.drawTooltip(mc.textRenderer, tooltip, event.mouseX, event.mouseY);
            }
        }
    }

    /**
     * Builds the tooltip for the stack currently under the cursor, adding the
     * filled-map ID line when enabled. Returns null when there is nothing to show.
     */
    private List<Text> buildTooltip(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        List<Text> tooltip = new ArrayList<>(Screen.getTooltipFromItem(mc, stack));
        if (showMapIdInTooltip.get()) {
            MapIdComponent mapId = stack.get(DataComponentTypes.MAP_ID);
            if (mapId != null) {
                String idText = "#" + mapId.id();
                boolean alreadyPresent = tooltip.stream()
                    .anyMatch(line -> line.getString().contains(idText));
                if (!alreadyPresent) tooltip.addFirst(Text.literal(idText));
            }
        }
        return tooltip;
    }

    private void renderPerShulkerGrid(ScreenRenderEvent event, int baseX, int baseY) {
        boolean isCompact = compact.get();
        int slotSize = isCompact ? compactSlotSize.get() : 20;
        int columns = isCompact ? compactColumns.get() : 9;
        float scale = (isCompact ? slotSize / 16.0f : 1.0f) * iconScale.get().floatValue();
        int gap = spacing.get();

        // Measure every grid first so the placement pass below can wrap onto a
        // second column before the screen height runs out. Cached, because the
        // measurement depends only on the refreshed slot data, the search filter and
        // the layout settings, none of which change while a screen stays open.
        List<ShulkerGrid> grids = measuredGrids(columns, slotSize);
        if (grids.isEmpty()) {
            height = baseY - offset;
            setClicked(null);
            return;
        }

        boolean both = bothSides.get();
        int screenWidth = mc.getWindow().getScaledWidth();
        int screenHeight = mc.getWindow().getScaledHeight();

        List<PlacedGrid> placed = new ArrayList<>();
        int x = baseX;
        int y = baseY;
        int columnWidth = 0;
        int maxBottom = baseY;

        // Scrolling shifts the whole flow, so skip the leading grids that sit entirely
        // above the viewport before placing anything. Both columns share one offset:
        // they are two views of the same inventory, so they stay on the same entry.
        //
        // Columns are capped at what the screen can actually show, and everything past
        // the cap is reached by scrolling instead. Without the cap a third column would
        // be drawn off the right edge, which is the unreachable content this replaces.
        int viewportHeight = screenHeight - baseY - 2;
        int maxColumns = both ? 2 : 1;

        // Budget the limit by running the same wrap-and-cap rules as the placement pass
        // below, and record how far the flow had to shift for the last grid to appear.
        // Deriving it from total flow height instead under-counts, because columns stop
        // at grid boundaries and waste the remainder of the viewport
        maxScroll = cachedScrollLimit(grids, gap, viewportHeight, maxColumns, slotSize);
        scrollOffset = MathHelper.clamp(scrollOffset, 0, maxScroll);

        int consumed = 0;
        int firstVisibleConsumed = 0;
        List<ShulkerGrid> visibleGrids = new ArrayList<>();
        consumed = 0;
        for (ShulkerGrid grid : grids) {
            int blockHeight = grid.rows() * slotSize + gap;
            if (consumed + blockHeight > scrollOffset) {
                if (visibleGrids.isEmpty()) firstVisibleConsumed = consumed;
                visibleGrids.add(grid);
            }
            consumed += blockHeight;
        }
        grids = visibleGrids;

        // Every column's width is known before placement, so the overflow column can be
        // anchored to the right edge of the screen rather than appended beside the
        // first one, the HUD's right-aligned list behaviour, so the previews stay
        // pinned to opposite edges on a wide screen instead of hugging the left.
        int widestColumn = 0;
        for (ShulkerGrid grid : grids) {
            widestColumn = Math.max(widestColumn, grid.width());
        }
        int rightColumnX = screenWidth - widestColumn - 2;

        // Only the first overflow goes to the right edge; anything beyond that has to
        // advance past the column it just finished. Re-deriving x from baseX every time
        // would drop a third column back onto the second one's x, overlapping the
        // previews and their hover/click regions.
        // The first surviving grid is usually only part scrolled off, so push the flow
        // down by the remainder. Without this the content snaps a whole grid per tick.
        y = baseY - (scrollOffset - firstVisibleConsumed);

        int columnCount = 1;
        int maxRight = baseX;
        for (ShulkerGrid grid : grids) {
            int gridHeight = grid.rows() * slotSize;

            if (y + gridHeight > screenHeight - 2 && y > baseY) {
                // Out of columns: stop here. The rest is reachable by scrolling, and
                // drawing it would run off the right edge of the screen.
                if (columnCount >= maxColumns) break;

                columnCount++;
                // Only the first overflow goes to the right edge. There is no second
                // overflow past the cap, so there is no third column to misplace.
                x = columnCount == 2 ? Math.max(baseX + columnWidth + gap, rightColumnX)
                    : x + columnWidth + gap;
                columnWidth = 0;
                y = baseY;
            }

            placed.add(new PlacedGrid(grid, x, y));
            columnWidth = Math.max(columnWidth, grid.width());
            maxBottom = Math.max(maxBottom, y + gridHeight);
            maxRight = Math.max(maxRight, x + grid.width());
            y += gridHeight + gap;
        }
        // A part-scrolled grid starts above baseY. Clip it at the panel's top edge so it
        // cannot paint over the search bar sitting there, and keep its click region
        // from claiming clicks up there too, otherwise a hidden row would steal the
        // search bar's own click.
        int clipTop = baseY - 1;

        for (PlacedGrid p : placed) {
            ShulkerGrid grid = p.grid();

            int startY = p.y();
            int endY = startY + grid.rows() * slotSize;
            int maxX = p.x() + grid.width();

            if (endY <= clipTop || startY >= screenHeight - 2) continue;

            event.drawContext.enableScissor(0, clipTop, screenWidth, screenHeight);

            // Clamp to the visible band so the colour header and background of a
            // half-scrolled grid do not bleed upwards past the panel edge.
            int drawStart = Math.max(startY, clipTop + 1);
            event.drawContext.fill(p.x(), drawStart, maxX, endY, COLOR_BACKGROUND);
            event.drawContext.fill(p.x(), startY - 1, maxX, Math.max(startY, clipTop + 1), grid.info().color());

            int count = 0, drawX = p.x();
            int drawY = startY;
            for (ItemStack stack : grid.stacks()) {
                if (count > 0 && count % columns == 0) {
                    drawX = p.x();
                    drawY += slotSize;
                }

                drawScaledItem(event, stack, drawX, drawY, slotSize, scale);
                // A row scrolled up under the search bar is not clickable - the click
                // test below clamps to clipTop, so it must not raise a tooltip for a
                // slot the player cannot see either.
                if (drawY + slotSize > clipTop && isHovering(drawX, drawY, slotSize, event)) {
                    hoveredTooltip = stack;
                }

                drawX += slotSize;
                count++;
            }

            // Pop the per-grid clip pushed above. enableScissor cannot do this: it
            // intersects the new rect with the current one and pushes a second frame
            // rather than popping, so every grid would leak a frame onto the stack.
            event.drawContext.disableScissor();

            if (clicked != null
                && clicked.x >= p.x() && clicked.x <= maxX
                && clicked.y >= Math.max(startY, clipTop) && clicked.y <= endY) {
                mc.interactionManager.clickSlot(
                    mc.player.currentScreenHandler.syncId,
                    grid.info().slot(), 0, SlotActionType.PICKUP, mc.player);
                setClicked(null);
            }
        }

        height = maxBottom - offset;
        panelRight = Math.max(panelRight, maxRight);
        panelBottom = Math.max(panelBottom, maxBottom);
        setClicked(null); // consume any unmatched click once, after checking every shulker
    }

    private void renderCombinedGrid(ScreenRenderEvent event, int baseX, int baseY) {
        List<DisplayEntry> base = combinedCache;
        if (base == null) {
            base = buildCombinedBase();
            combinedCache = base;
        }

        List<DisplayEntry> entries = base;
        if (searchFilterActive()) {
            // Cached on the query: filtering every merged entry on every frame is the
            // most expensive thing this mode can do, and the result cannot change
            // until the query or the slot data does.
            if (combinedFilteredCache == null || !searchQuery.toString().equals(combinedFilteredKey)) {
                List<DisplayEntry> filtered = new ArrayList<>();
                for (DisplayEntry entry : base) {
                    if (matchesSearch(entry.stack())) filtered.add(entry);
                }
                combinedFilteredCache = filtered;
                combinedFilteredKey = searchQuery.toString();
            }
            entries = combinedFilteredCache;
        } else {
            combinedFilteredCache = null;
            combinedFilteredKey = null;
        }

        boolean isCompact = compact.get();
        int slotSize = isCompact ? compactSlotSize.get() : 20;
        int columns = isCompact ? compactColumns.get() : 9;
        float scale = (isCompact ? slotSize / 16.0f : 1.0f) * iconScale.get().floatValue();

        int startY = baseY;
        int rows = entries.isEmpty() ? 0 : (entries.size() + columns - 1) / columns;
        int cols = Math.min(entries.size(), columns);
        int maxX = baseX + (rows > 1 ? columns : cols) * slotSize;
        int y = baseY + rows * slotSize;

        // Draw background first so icons render on top of it.
        if (!entries.isEmpty()) {
            event.drawContext.fill(baseX, startY, maxX, y, COLOR_BACKGROUND);
            event.drawContext.fill(baseX, startY - 1, maxX, startY, COLOR_SEPARATOR);
        }

        for (int i = 0; i < entries.size(); i++) {
            int col = i % columns;
            int row = i / columns;
            int drawX = baseX + col * slotSize;
            int drawY = baseY + row * slotSize;

            DisplayEntry entry = entries.get(i);
            drawScaledItem(event, entry.stack(), drawX, drawY, slotSize, scale);
            if (isHovering(drawX, drawY, slotSize, event)) hoveredTooltip = entry.stack();

            if (clicked != null
                && clicked.x >= drawX && clicked.x <= drawX + slotSize
                && clicked.y >= drawY && clicked.y <= drawY + slotSize) {
                mc.interactionManager.clickSlot(
                    mc.player.currentScreenHandler.syncId,
                    entry.slot(), 0, SlotActionType.PICKUP, mc.player);
                setClicked(null);
            }
        }

        height = y - offset;
        panelRight = Math.max(panelRight, maxX);
        panelBottom = Math.max(panelBottom, y);
        setClicked(null);
    }

    /**
     * Per-shulker grid measurements, rebuilt only when the underlying data or the
     * layout settings change. Held in a mutable list that the placement pass narrows
     * with subList, so the render pass must not reorder or mutate it.
     *
     * <p>Keyed on the columns and slot size it was measured with rather than assuming
     * they cannot move: both are read fresh every frame, and compact, compact-columns
     * and compact-slot-size can all be changed while a container is open, which would
     * otherwise leave the rows and widths describing the old layout.
     */
    private List<ShulkerGrid> measuredGrids(int columns, int slotSize) {
        if (measuredGridsCache != null
            && measuredGridsColumns == columns && measuredGridsSlotSize == slotSize) {
            return measuredGridsCache;
        }

        List<ShulkerGrid> grids = new ArrayList<>();
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

    /**
     * The search is linear in the flow height times the shulker count, hundreds of thousands of iterations for a
     * full container, and none of those inputs change between frames, so recomputing
     * it every render was the single most expensive thing in this module.
     */
    private int cachedScrollLimit(List<ShulkerGrid> grids, int gap, int viewportHeight, int maxColumns, int slotSize) {
        // Height signature: the grid heights plus the settings that place them. Cheap to
        // build, and it changes only on refresh, search, resize or a settings change.
        StringBuilder key = new StringBuilder();
        for (ShulkerGrid grid : grids) {
            // Delimiter between rows and width: without it (1,18) and (11,8) both
            // build "118," and two different layouts share one memo entry.
            key.append(grid.rows()).append(':').append(grid.width()).append(',');
        }
        key.append('|').append(gap).append('|').append(viewportHeight)
            .append('|').append(maxColumns).append('|').append(slotSize);

        String signature = key.toString();
        if (signature.equals(scrollLimitKey)) return cachedScrollLimit;

        cachedScrollLimit = scrollLimitFor(grids, gap, viewportHeight, maxColumns, slotSize);
        scrollLimitKey = signature;
        return cachedScrollLimit;
    }

    /**
     * Furthest the flow can scroll: the smallest offset at which the last grid
     * becomes placeable under the same grid-boundary wrapping and column cap the
     * placement pass uses. A flow that already fits reports 0.
     * <p>
     * The scan is linear rather than a binary search on purpose:
     */
    private int scrollLimitFor(List<ShulkerGrid> grids, int gap, int viewportHeight, int maxColumns, int slotSize) {
        if (grids.isEmpty()) return 0;

        int total = 0;
        int[] heights = new int[grids.size()];
        for (int i = 0; i < grids.size(); i++) {
            heights[i] = grids.get(i).rows() * slotSize;
            total += heights[i] + gap;
        }
        total -= gap;

        for (int limit = 0; limit <= total; limit++) {
            int consumed = 0;
            int firstVisible = 0;
            boolean anyVisible = false;
            for (int height : heights) {
                if (consumed + height + gap > limit) {
                    if (!anyVisible) firstVisible = consumed;
                    anyVisible = true;
                }
                consumed += height + gap;
            }
            if (!anyVisible) continue;

            // Place the surviving grids exactly as the render pass does.
            int y = -(limit - firstVisible);
            int columns = 1;
            boolean lastPlaced = false;
            consumed = 0;
            for (int i = 0; i < heights.length; i++) {
                int height = heights[i];
                if (consumed + height + gap <= limit) {
                    consumed += height + gap;
                    continue;
                }
                consumed += height + gap;

                if (y + height > viewportHeight && y > 0) {
                    if (columns >= maxColumns) break;
                    columns++;
                    y = 0;
                }
                // Whether the final grid got placed, rather than how many did. Grids
                // scrolled off above the limit are skipped without being counted, so
                // comparing a placed tally against the grid count can never be
                // satisfied once anything scrolls away, and the limit then runs to the
                // full flow height, where the render pass finds no visible grid at all
                // and the panel goes blank.
                if (i == heights.length - 1) lastPlaced = true;
                y += height + gap;
            }
            if (lastPlaced) return limit;
        }
        return total;
    }

    /**
     * Width of the search bar, matching the grid it sits above so the two line up.
     */
    private int searchBarWidth() {
        boolean isCompact = compact.get();
        int slotSize = isCompact ? compactSlotSize.get() : 20;
        int columns = isCompact ? compactColumns.get() : 9;
        return columns * slotSize;
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

        var context = event.drawContext;
        int borderColor = searchFocused ? 0xFFFFFFFF : COLOR_SEPARATOR;

        // Background
        context.fill(baseX, baseY, baseX + barWidth, baseY + barHeight, COLOR_BACKGROUND);

        // Border (manual)
        context.fill(baseX, baseY, baseX + barWidth, baseY + 1, borderColor);                     // top
        context.fill(baseX, baseY + barHeight - 1, baseX + barWidth, baseY + barHeight, borderColor); // bottom
        context.fill(baseX, baseY, baseX + 1, baseY + barHeight, borderColor);                     // left
        context.fill(baseX + barWidth - 1, baseY, baseX + barWidth, baseY + barHeight, borderColor); // right

        String text = searchQuery.length() > 0 ? searchQuery.toString() : "Search...";
        context.drawText(mc.textRenderer, text, baseX + 3, baseY + 2,
            searchQuery.length() > 0 ? 0xFFFFFFFF : 0x80FFFFFF, false);

        return baseY + barHeight + 2;
    }

    private boolean matchesSearch(ItemStack stack) {
        if (!searchBar.get() || searchQuery.length() == 0) return true;
        return stack.getName().getString().toLowerCase().contains(searchQuery.toString().toLowerCase());
    }

    /**
     * Draws a scaled item icon and its overlay (durability bar, count text).
     */
    private boolean isHovering(int x, int y, int size, ScreenRenderEvent event) {
        return event.mouseX >= x && event.mouseX < x + size && event.mouseY >= y && event.mouseY < y + size;
    }

    private void drawScaledItem(ScreenRenderEvent event, ItemStack stack, int cellX, int cellY, int cellSize, float scale) {
        // Center the scaled icon inside its cell: at a reduced scale a 16px icon
        // drawn from the cell's top-left corner would bleed into the neighbouring
        // cell in the compact grid.
        float itemPixelSize = 16.0f * scale;
        int px = cellX + (int) ((cellSize - itemPixelSize) / 2);
        int py = cellY + (int) ((cellSize - itemPixelSize) / 2);
        String countText = stack.getCount() > 999 ? formatCount(stack.getCount()) : null;

        var context = event.drawContext;
        var matrices = context.getMatrices();

        // 1. Draw the item icon at the requested scale.
        matrices.pushMatrix();
        matrices.translate(px, py);
        matrices.scale(scale, scale);

        // Special case: draw the rendered map texture instead of the item model.
        // context.drawItem() renders through the generic item pipeline, which never
        // fetches the actual map texture, so a filled map would show a blank item.
        boolean drewMap = false;
        if (renderMapFill.get() && stack.isOf(Items.FILLED_MAP)) {
            MapIdComponent mapId = stack.get(DataComponentTypes.MAP_ID);
            if (mapId != null && mc.world != null) {
                MapState mapState = FilledMapItem.getMapState(mapId, mc.world);
                if (mapState != null) {
                    // map() draws at 128x128 map-pixels; 100% = 0.125 fills the slot.
                    float mapScale = mapFillSize.get() / 800.0f;
                    matrices.scale(mapScale, mapScale);
                    MapRenderState renderState = new MapRenderState();
                    mc.getMapRenderer().update(mapId, mapState, renderState);
                    context.drawMap(renderState);
                    drewMap = true;
                }
            }
        }

        if (!drewMap) context.drawItem(stack, 0, 0);
        matrices.popMatrix();

        // 2. Draw the overlay (durability bar, count text) without the scale
        //    matrix active. drawStackOverlay() uses hardcoded pixel geometry
        //    that must render at 1:1 screen pixels relative to (px, py).
        matrices.pushMatrix();
        matrices.translate(px, py);
        context.drawStackOverlay(mc.textRenderer, stack, 0, 0, countText);
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
            .thenComparing(e -> e.stack().getName().getString()));
        return entries;
    }

    private void rebuildRenderCaches() {
        combinedCache = null;
        combinedFilteredCache = null;
        combinedFilteredKey = null;
        // The measured grids are derived from the visible slices below, so they have to
        // go when this runs, and equally when the search filter makes the slices live.
        // The scroll limit is memoised off those grids, so it goes with them.
        measuredGridsCache = null;
        scrollLimitKey = null;
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

    private void refresh(HandledScreen<?> screen) {
        info.clear();
        for (Slot slot : screen.getScreenHandler().slots) {
            ShulkerInfo shulkerInfo = ShulkerInfo.create(slot.getStack(), slot.id);
            if (shulkerInfo == null) continue;
            info.add(shulkerInfo);
        }
        rebuildRenderCaches();
    }

    public int getOffset() {
        return offset;
    }

    public void setOffset(int offset) {
        this.offset = MathHelper.clamp(offset, -Math.max(height - mc.getWindow().getScaledHeight(), 0), 0);
    }

    public void setClicked(Vec2f clicked) {
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

    /**
     * The query changed outside the refresh cycle, so anything derived from the
     * filtered slices has to be rebuilt on the next render.
     */
    private void invalidateSearchCaches() {
        measuredGridsCache = null;
        scrollLimitKey = null;
        combinedFilteredCache = null;
        combinedFilteredKey = null;
    }

    public boolean isSearchFocused() {
        return searchFocused;
    }
}
