package com.AutoBookshelf.addon.modules;
//26.2 mojmap
import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.events.ScreenRenderEvent;
import com.AutoBookshelf.addon.utils.ShulkerInfo;
import com.AutoBookshelf.addon.utils.Type;
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
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
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
    /**
     * Pixels one wheel notch moves the panel. Read by MixinHandledScreen.
     */
    public static final int SCROLL_STEP = 18;
    private static final int REFRESH_INTERVAL = 2; // ticks between rebuilding caches from container

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

    public final Setting<Boolean> searchBar = sgGeneral.add(new BoolSetting.Builder()
        .name("search-bar")
        .description("Show a search bar above the panel to filter displayed items by name.")
        .defaultValue(false)
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

    public final Setting<SearchBarSide> searchBarSide = sgGeneral.add(new EnumSetting.Builder<SearchBarSide>()
        .name("search-bar-side")
        .description("Which side the search bar sits on. Right hugs the overflow column when both-sides is on.")
        .defaultValue(SearchBarSide.Left)
        .visible(() -> searchBar.get())
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

    /**
     * How far the flow can actually scroll, recomputed every render from the content
     * that was placed. This one and not {@link #height} is what {@link #setOffset} clamps
     * against: {@code height} is the panel's own extent, which the placement pass keeps
     * inside the screen, so clamping against it pins the offset at zero and kills the
     * wheel entirely.
     */
    private int scrollOverflow;

    /**
     * Last known cursor position, for the panel hit test in {@link #setOffset}.
     */
    private int lastMouseX = -1, lastMouseY = -1;

    /**
     * The container the refresh cycle last ran against, to catch a swap.
     */
    private AbstractContainerScreen<?> lastScreen;
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

    private List<ShulkerGrid> measuredGridsCache = null;
    private int measuredGridsColumns = -1;
    private int measuredGridsSlotSize = -1;

    // Memo for cachedScrollLimit, plus the signature it was computed from.
    private int cachedScrollLimit = 0;
    private String scrollLimitKey = null;

    private record DisplayEntry(ItemStack stack, int slot) {
    }

    private record ShulkerGrid(ShulkerInfo info, List<ItemStack> stacks, int rows, int width) {
    }

    private record PlacedGrid(ShulkerGrid grid, int x, int y) {
    }

    // The panel's hit rectangle, so the wheel handler can tell whether the cursor is
    // actually over the panel.
    private int panelLeft, panelRight, panelTop, panelBottom;

    /**
     * Which screen edge the search bar is anchored to.
     */
    private enum SearchBarSide {
        Left,
        Right
    }

    public InventoryInfo() {
        super(Addon.CATEGORY, "Inventory-Info", "prigozhinplugg");
    }
    //TODO
    // Make proper component display.
    // Add profile target, litematica Material list feature.

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?>)) {
            info.clear();
            shulkerVisibleCache = null;
            combinedCache = null;
            return;
        }
        if (inventoryOnly.get() && !(mc.gui.screen() instanceof InventoryScreen)) {
            info.clear();
            return;
        }
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) mc.gui.screen();

        // A new container has to refresh on its first tick. Carried over, the counter's
        // phase can land anywhere within REFRESH_INTERVAL and the panel shows nothing
        // until it happens to fire.
        if (screen != lastScreen) {
            lastScreen = screen;
            refreshTickCounter = 0;
            refresh(screen);
            return;
        }

        if (refreshTickCounter++ % REFRESH_INTERVAL != 0) return;
        refresh(screen);
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
        lastMouseX = event.mouseX;
        lastMouseY = event.mouseY;
        hoveredTooltip = null;
        event.graphics.enableScissor(0, 0, screenWidth, screenHeight);

        if (info.isEmpty()) {
            event.graphics.disableScissor();
            return;
        }

        int baseX = 2 + panelXOffset.get();
        // Deliberately scroll-free. The placement pass applies the scroll itself, and
        // folding it in here as well cancelled the two out, so the wheel moved nothing.
        int baseY = 3 + panelYOffset.get();

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

        if (tooltips.get() && hoveredTooltip != null) {
            List<Component> tooltip = new ArrayList<>(Screen.getTooltipFromItem(mc, hoveredTooltip));
            if (showMapIdInTooltip.get()) {
                var mapId = hoveredTooltip.get(net.minecraft.core.component.DataComponents.MAP_ID);
                if (mapId != null) {
                    String idText = "#" + mapId.id();
                    boolean alreadyPresent = tooltip.stream()
                        .anyMatch(line -> line.getString().contains(idText));
                    if (!alreadyPresent) tooltip.addFirst(Component.literal(idText));
                }
            }
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
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        List<PlacedGrid> placed = new ArrayList<>();
        int x = baseX;
        int y = baseY;
        int columnWidth = 0;
        int maxBottom = baseY;

        // When the overflow column is on, it has to be pinned to the right edge of the
        // screen rather than appended beside the first one, the HUD's right-aligned
        // list behaviour, so the previews stay on opposite edges of a wide screen
        // instead of hugging the left.
        int widestColumn = 0;
        for (ShulkerGrid grid : grids) {
            widestColumn = Math.max(widestColumn, grid.width());
        }
        int rightColumnX = screenWidth - widestColumn - 2;

        int maxColumns = both ? 2 : 1;

        int viewportHeight = screenHeight - baseY - 2;
        int scrollLimit = cachedScrollLimit(grids, gap, viewportHeight, maxColumns, slotSize);
        int scrollOffset = Mth.clamp(offset, -scrollLimit, 0);
        scrollOverflow = scrollLimit;

        // Drop the grids that sit entirely above the viewport before placing anything.
        // This is not just an optimisation: firstVisible below compensates for the grids
        // already scrolled off, so leaving them in the flow double-counts that offset and
        // every grid is drawn short of where it should be.
        List<ShulkerGrid> visible = new ArrayList<>(grids.size());
        int consumed = 0;
        int firstVisible = 0;
        for (ShulkerGrid grid : grids) {
            int gh = grid.rows() * slotSize;
            if (consumed + gh + gap > -scrollOffset) {
                if (visible.isEmpty()) firstVisible = consumed;
                visible.add(grid);
            }
            consumed += gh + gap;
        }

        // Only reachable if the limit is ever computed short of the real end of the flow.
        // Hold the last grid at the top rather than rendering an empty panel.
        if (visible.isEmpty() && !grids.isEmpty()) {
            ShulkerGrid last = grids.get(grids.size() - 1);
            visible = new ArrayList<>(grids.subList(grids.size() - 1, grids.size()));
            firstVisible = consumed - (last.rows() * slotSize + gap);
        }

        // The first surviving grid is usually only part scrolled off, so push the flow
        // down by the remainder. Without this the content snaps a whole grid per tick.
        y = baseY + scrollOffset + firstVisible;

        int columnCount = 1;
        int maxRight = baseX;
        for (ShulkerGrid grid : visible) {
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

        // Widen, never assign. The seed and the search bar's extents were recorded
        // before this pass; assigning here threw them away again, which left a bar
        // anchored to the far edge outside the hit rectangle entirely.
        int minPlacedY = baseY;
        for (PlacedGrid p : placed) minPlacedY = Math.min(minPlacedY, p.y());
        panelLeft = Math.min(panelLeft, baseX);
        panelRight = Math.max(panelRight, maxRight);
        panelTop = Math.min(panelTop, minPlacedY);
        panelBottom = Math.max(panelBottom, maxBottom);

        // A row scrolled up under the search bar must not paint over it, raise a
        // tooltip for a slot the user cannot see, or swallow the bar's own click.
        int clipTop = baseY - 1;
        for (PlacedGrid p : placed) {
            ShulkerGrid grid = p.grid();

            int startY = p.y();
            int endY = startY + grid.rows() * slotSize;
            int maxX = p.x() + grid.width();

            if (endY <= clipTop || startY >= screenHeight - 2) continue;

            event.graphics.enableScissor(0, clipTop, screenWidth, screenHeight);

            // Clamp to the visible band so the colour header and background of a
            // half-scrolled grid do not bleed upwards past the panel edge.
            int drawStart = Math.max(startY, clipTop + 1);
            drawBackground(event, p.x(), drawStart, maxX, endY);
            event.graphics.fill(p.x(), startY - 1, maxX, drawStart, grid.info().color());

            int count = 0, drawX = p.x();
            int drawY = startY;
            for (ItemStack stack : grid.stacks()) {
                if (count > 0 && count % columns == 0) {
                    drawX = p.x();
                    drawY += slotSize;
                    if (drawY >= screenHeight - 2) break;
                }

                drawScaledItem(event, stack, drawX, drawY, slotSize, scale);
                if (drawY + slotSize > clipTop && isHovering(drawX, drawY, slotSize, event)) {
                    hoveredTooltip = stack;
                }

                drawX += slotSize;
                count++;
            }

            if (clicked != null
                && clicked.x >= p.x() && clicked.x <= maxX
                && clicked.y >= Math.max(startY, clipTop) && clicked.y <= endY) {
                mc.gameMode.handleContainerInput(
                    mc.player.containerMenu.containerId,
                    grid.info().slot(), 0, ContainerInput.PICKUP, mc.player);
                setClicked(null);
            }

            // enableScissor pushes and disableScissor pops a Deque, so this pair is
            // balanced and the caller's screen-wide clip is still in place afterwards.
            // Re-pushing it here would leak a level every frame.
            event.graphics.disableScissor();
        }

        height = maxBottom;
        setClicked(null);
    }

    private void renderCombinedGrid(ScreenRenderEvent event, int baseX, int baseY) {
        List<DisplayEntry> base = combinedCache;
        if (base == null) {
            base = buildCombinedBase();
            combinedCache = base;
        }

        List<DisplayEntry> entries = base;
        if (searchFilterActive()) {
            entries = new ArrayList<>();
            for (DisplayEntry entry : base) {
                if (matchesSearch(entry.stack())) entries.add(entry);
            }
        }

        boolean isCompact = compact.get();
        int slotSize = isCompact ? compactSlotSize.get() : 20;
        int columns = isCompact ? compactColumns.get() : 9;
        float scale = (isCompact ? slotSize / 16.0f : 1.0f) * iconScale.get().floatValue();

        int startY = baseY + offset;
        int rows = entries.isEmpty() ? 0 : (entries.size() + columns - 1) / columns;
        int cols = Math.min(entries.size(), columns);
        int maxX = baseX + (rows > 1 ? columns : cols) * slotSize;
        int y = baseY + rows * slotSize;

        // Scrolled bottom edge. The background has to span exactly the rows being drawn, so it
        // is derived from startY; using the unscrolled y gave a plate whose height no longer
        // matched the icons sitting on it.
        int scrolledEnd = startY + rows * slotSize;

        // Draw background first so icons render on top of it.
        if (!entries.isEmpty()) {
            drawBackground(event, baseX, startY, maxX, scrolledEnd);
            event.graphics.fill(baseX, startY - 1, maxX, startY, COLOR_SEPARATOR);
        }

        for (int i = 0; i < entries.size(); i++) {
            int col = i % columns;
            int row = i / columns;
            int drawX = baseX + col * slotSize;
            // From startY, not baseY: the background above is drawn from the scrolled origin,
            // so using the unscrolled one desyncs the icons from their own backing plate (and
            // puts hover/click hit-testing on a different row than the one being drawn).
            int drawY = startY + row * slotSize;

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

        // The panel rectangle drives isOverPanel, so the wheel only reaches this grid when
        // the bounds actually cover it. They were only ever grown in renderPerShulkerGrid,
        // which left combined mode with a zero-height rect at baseY.
        panelLeft = Math.min(panelLeft, baseX);
        panelRight = Math.max(panelRight, maxX);
        panelTop = Math.min(panelTop, startY);
        panelBottom = Math.max(panelBottom, scrolledEnd);

        // From the unscrolled extent: scrollOverflow is how much content there is to scroll
        // through, independent of where the viewport currently sits.
        height = y;
        scrollOverflow = Math.max(y - mc.getWindow().getGuiScaledHeight(), 0);
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
        // Both of these are derived from the slices rebuilt below, so they have to go
        // when this runs. The measured grids memoise on (columns, slotSize), neither of
        // which changes when the container does, so a stale cache keeps the previous
        // container's ShulkerInfo records, old contents AND a stale slot() index, which
        // makes a click send handleContainerInput for the wrong slot.
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

    private void refresh(AbstractContainerScreen<?> screen) {
        info.clear();
        for (Slot slot : screen.getMenu().slots) {
            ShulkerInfo shulkerInfo = ShulkerInfo.create(slot.getItem(), slot.index);
            if (shulkerInfo == null) continue;
            info.add(shulkerInfo);
        }
        rebuildRenderCaches();
    }

    public int getOffset() {
        return offset;
    }

    /**
     * Whether a cursor position lies inside the panel's hit rectangle. The wheel is
     * handled before the next render, so this reads the rectangle and the cursor
     * position as of the previous frame.
     */
    public boolean isOverPanel(double x, double y) {
        return x >= panelLeft && x <= panelRight && y >= panelTop && y <= panelBottom;
    }

    public void setOffset(int offset) {
        // Only while the cursor is over the panel. Unconditionally, the wheel drove this
        // module from anywhere on screen including over the container's own slots and
        // every other scrollable widget on it.
        if (!isOverPanel(lastMouseX, lastMouseY)) return;
        this.offset = Mth.clamp(offset, -Math.max(scrollOverflow, 0), 0);
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
        } else if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            // The screen mixin cancels any key pressed while the bar has focus, so
            // without handling it here Enter was swallowed and ESC was the only way out.
            searchFocused = false;
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
    }

    public boolean isSearchFocused() {
        return searchFocused;
    }


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
}
