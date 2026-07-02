package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.events.ScreenRenderEvent;
import com.AutoBookshelf.addon.utils.ShulkerInfo;
import com.AutoBookshelf.addon.utils.Type;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;
import org.lwjgl.glfw.GLFW;

import java.util.*;

public class InventoryInfo extends Module {
    private static final int COLOR_BACKGROUND = 0x4B000000;
    private static final int COLOR_SEPARATOR = 0x64FFFFFF;

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

    private final List<ShulkerInfo> info = new ArrayList<>();
    private int height, offset;
    private Vec2f clicked;

    // search bar state
    private final StringBuilder searchQuery = new StringBuilder();
    private boolean searchFocused = false;

    private record DisplayEntry(ItemStack stack, int slot) {
    }

    public InventoryInfo() {
        super(Addon.CATEGORY, "Inventory-Info", "prigozhinplugg");
    }
    //TODO
    // Make proper component display.
    // Add profile target, litematica Material list feature.
    // Whisper/info panel.

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!(mc.currentScreen instanceof HandledScreen<?>) || mc.player.age % 4 != 0) return;
        if (inventoryOnly.get() && !(mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.InventoryScreen)) {
            info.clear();
            return;
        }
        refresh((HandledScreen<?>) mc.currentScreen);
    }

    @EventHandler
    private void onRenderScreen(ScreenRenderEvent event) {
        int screenWidth = mc.getWindow().getScaledWidth();
        int screenHeight = mc.getWindow().getScaledHeight();
        event.drawContext.enableScissor(0, 0, screenWidth, screenHeight);

        if (info.isEmpty()) {
            event.drawContext.disableScissor();
            return;
        }

        int baseX = 2 + panelXOffset.get();
        int baseY = 3 + offset + panelYOffset.get();

        if (searchBar.get()) baseY = renderSearchBar(event, baseX, baseY);

        if (combineShulkers.get()) {
            renderCombinedGrid(event, baseX, baseY);
        } else {
            renderPerShulkerGrid(event, baseX, baseY);
        }

        event.drawContext.disableScissor();
    }

    private void renderPerShulkerGrid(ScreenRenderEvent event, int baseX, int baseY) {
        int y = baseY;
        boolean isCompact = compact.get();
        int slotSize = isCompact ? compactSlotSize.get() : 20;
        int columns = isCompact ? compactColumns.get() : 9;
        float scale = (isCompact ? slotSize / 16.0f : 1.0f) * iconScale.get().floatValue();

        for (ShulkerInfo shulkerInfo : info) {
            List<ItemStack> visible = new ArrayList<>();
            for (ItemStack stack : shulkerInfo.stacks()) {
                if (shulkerInfo.type() == Type.COMPACT && stack.isEmpty()) break;
                if (!matchesSearch(stack)) continue;
                visible.add(stack);
            }

            if (visible.isEmpty()) {
                continue; // just skip, don't touch clicked state
            }

            int startY = y;
            int rows = (visible.size() + columns - 1) / columns;
            int cols = Math.min(visible.size(), columns);
            int maxX = baseX + (rows > 1 ? columns : cols) * slotSize;
            int endY = startY + rows * slotSize;

            event.drawContext.fill(baseX, startY, maxX, endY, COLOR_BACKGROUND);
            event.drawContext.fill(baseX, startY - 1, maxX, startY, shulkerInfo.color());

            int count = 0, x = baseX;
            int drawY = startY;
            for (ItemStack stack : visible) {
                if (count > 0 && count % columns == 0) {
                    x = baseX;
                    drawY += slotSize;
                }
                drawScaledItem(event, stack, x + 2, drawY, scale);
                x += slotSize;
                count++;
            }
            y = endY;

            if (clicked != null
                && clicked.x >= baseX && clicked.x <= maxX
                && clicked.y >= startY && clicked.y <= y) {
                mc.interactionManager.clickSlot(
                    mc.player.currentScreenHandler.syncId,
                    shulkerInfo.slot(), 0, SlotActionType.PICKUP, mc.player);
                setClicked(null);
            }
            y += 2;
        }
        height = y - offset;
        setClicked(null); // consume any unmatched click once, after checking every shulker
    }

    private void renderCombinedGrid(ScreenRenderEvent event, int baseX, int baseY) {
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
            if (!matchesSearch(display)) continue; // search filter
            entries.add(new DisplayEntry(display, slot));
        }
        entries.sort(Comparator
            .comparingInt((DisplayEntry e) -> -e.stack().getCount())
            .thenComparing(e -> e.stack().getName().getString()));

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
            drawScaledItem(event, entry.stack(), drawX + 2, drawY, scale);

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
        setClicked(null);
    }

    private int renderSearchBar(ScreenRenderEvent event, int baseX, int baseY) {
        boolean isCompact = compact.get();
        int slotSize = isCompact ? compactSlotSize.get() : 20;
        int columns = isCompact ? compactColumns.get() : 9;
        int barWidth = columns * slotSize;
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
    private void drawScaledItem(ScreenRenderEvent event, ItemStack stack, int px, int py, float scale) {
        String countText = stack.getCount() > 999 ? formatCount(stack.getCount()) : null;

        var context = event.drawContext;
        var matrices = context.getMatrices();

        // 1. Draw the item icon at the requested scale.
        matrices.pushMatrix();
        matrices.translate(px, py);
        matrices.scale(scale, scale);
        context.drawItem(stack, 0, 0);
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

    private void refresh(HandledScreen<?> screen) {
        info.clear();
        for (Slot slot : screen.getScreenHandler().slots) {
            ShulkerInfo shulkerInfo = ShulkerInfo.create(slot.getStack(), slot.id);
            if (shulkerInfo == null) continue;
            info.add(shulkerInfo);
        }
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
        if (chr >= 32 && searchQuery.length() < 32) searchQuery.append(chr);
    }

    public void onSearchKeyPressed(int keyCode) {
        if (!searchFocused) return;
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !searchQuery.isEmpty()) {
            searchQuery.deleteCharAt(searchQuery.length() - 1);
        } else if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            searchFocused = false;
        }
    }
}
