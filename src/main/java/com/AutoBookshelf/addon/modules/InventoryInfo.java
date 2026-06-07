package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.events.ScreenRenderEvent;
import com.AutoBookshelf.addon.utils.ShulkerInfo;
import com.AutoBookshelf.addon.utils.Type;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector2f;

import java.awt.*;
import java.util.*;
import java.util.List;

public class InventoryInfo extends Module {
    private static final Color BACKGROUND = new Color(0, 0, 0, 75);
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

    // Customization
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

    // Compact grid settings
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
    private Vector2f clicked;

    private record DisplayEntry(ItemStack stack, int slot) {
    }

    public InventoryInfo() {
        super(Addon.CATEGORY, "inventory-info", "prigozhinplugg");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen) || mc.player.tickCount % 4 != 0) return;
        refresh(screen);
    }

    @EventHandler
    private void onRenderScreen(ScreenRenderEvent event) {
        if (mc.screen instanceof AbstractContainerScreen<?> screen) {
            refresh(screen);
        }

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        event.graphics.enableScissor(0, 0, screenWidth, screenHeight);

        if (info.isEmpty()) {
            event.graphics.disableScissor();
            return;
        }

        int baseX = 2 + panelXOffset.get();
        int baseY = 3 + offset + panelYOffset.get();

        if (combineShulkers.get()) {
            renderCombinedGrid(event, baseX, baseY);
        } else {
            renderPerShulkerGrid(event, baseX, baseY);
        }

        event.graphics.disableScissor();
    }

    private void renderPerShulkerGrid(ScreenRenderEvent event, int baseX, int baseY) {
        int y = baseY;
        boolean isCompact = compact.get();
        int slotSize = isCompact ? compactSlotSize.get() : 20;
        int columns = isCompact ? compactColumns.get() : 9;
        float scale = (isCompact ? slotSize / 16.0f : 1.0f) * iconScale.get().floatValue();

        for (ShulkerInfo shulkerInfo : info) {
            int count = 0, x = baseX, startY = y;
            int maxX = baseX;

            int nonEmpty = 0;
            for (ItemStack s : shulkerInfo.stacks()) {
                if (!s.isEmpty()) nonEmpty++;
                else if (shulkerInfo.type() == Type.COMPACT) break;
            }
            if (nonEmpty == 0) continue;

            int rows = (nonEmpty + columns - 1) / columns;
            int bottomY = y + rows * slotSize;

            int lastRowCount = nonEmpty % columns;
            if (lastRowCount == 0) lastRowCount = columns;
            maxX = baseX + lastRowCount * slotSize;
            if (rows > 1) {
                maxX = Math.max(maxX, baseX + columns * slotSize);
            }

            event.graphics.fill(baseX, startY, maxX, bottomY, BACKGROUND.hashCode());
            event.graphics.fill(baseX, startY - 1, maxX, startY, shulkerInfo.color());

            for (ItemStack stack : shulkerInfo.stacks()) {
                if (shulkerInfo.type() == Type.COMPACT && stack.isEmpty()) break;
                if (count > 0 && count % columns == 0) {
                    x = baseX;
                    y += slotSize;
                }
                int drawX = x, drawY = y;
                drawScaledItem(event, stack, drawX, drawY, slotSize, scale);
                x += slotSize;
                count++;
            }

            if (clicked != null && clicked.x >= baseX && clicked.x <= maxX && clicked.y >= startY && clicked.y <= bottomY) {
                mc.gameMode.handleContainerInput(
                    mc.player.containerMenu.containerId,
                    shulkerInfo.slot(),
                    0,
                    ContainerInput.PICKUP,
                    mc.player
                );
                setClicked(null);
            }

            y = bottomY + 2;
        }

        height = y - offset;
        setClicked(null);
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
            ItemStack displayStack = template.copy();
            displayStack.setCount(total);
            entries.add(new DisplayEntry(displayStack, slot));
        }
        entries.sort(Comparator.comparingInt((DisplayEntry e) -> -e.stack().getCount())
            .thenComparing(e -> e.stack().getHoverName().getString()));

        boolean isCompact = compact.get();
        int slotSize = isCompact ? compactSlotSize.get() : 20;
        int columns = isCompact ? compactColumns.get() : 9;
        float scale = (isCompact ? slotSize / 16.0f : 1.0f) * iconScale.get().floatValue();

        int y = baseY;
        int startY = y;
        int totalRows = (entries.size() + columns - 1) / columns;
        int totalHeight = totalRows * slotSize;
        int maxX = baseX + columns * slotSize;

        event.graphics.fill(baseX, startY, maxX, startY + totalHeight, BACKGROUND.hashCode());
        event.graphics.fill(baseX, startY - 1, maxX, startY, new Color(255, 255, 255, 100).hashCode());

        for (int i = 0; i < entries.size(); i++) {
            int col = i % columns;
            int row = i / columns;
            int drawX = baseX + col * slotSize;
            int drawY = baseY + row * slotSize;
            DisplayEntry entry = entries.get(i);

            drawScaledItem(event, entry.stack(), drawX, drawY, slotSize, scale);

            if (clicked != null
                && clicked.x >= drawX && clicked.x <= drawX + slotSize
                && clicked.y >= drawY && clicked.y <= drawY + slotSize) {
                mc.gameMode.handleContainerInput(
                    mc.player.containerMenu.containerId,
                    entry.slot(),
                    0,
                    ContainerInput.PICKUP,
                    mc.player
                );
                setClicked(null);
            }
        }

        height = (baseY + totalHeight) - offset;
        setClicked(null);
    }

    private void drawScaledItem(ScreenRenderEvent event, ItemStack stack, int cellX, int cellY, int cellSize, float scale) {
        float itemPixelSize = 16.0f * scale;
        int drawX = cellX + (int) ((cellSize - itemPixelSize) / 2);
        int drawY = cellY + (int) ((cellSize - itemPixelSize) / 2);

        var pose = event.graphics.pose();   // returns Matrix3x2fStack
        pose.pushMatrix();
        pose.translate(drawX, drawY);   // only x, y
        pose.scale(scale, scale);   // only x, y
        event.graphics.item(stack, 0, 0);   // renderItem to item
        event.graphics.itemDecorations(mc.font, stack, 0, 0, null); // renderItemDecorations to itemDecorations
        pose.popMatrix();
    }

    private String formatCount(int count) {
        if (count >= 1000) {
            double d = count / 1000.0;
            if (d == (int) d) return (int) d + "k";
            return String.format("%.1fk", d);
        }
        return String.valueOf(count);
    }

    private void refresh(AbstractContainerScreen<?> screen) {
        info.clear();
        for (Slot slot : screen.getMenu().slots) {
            ShulkerInfo shulkerInfo = ShulkerInfo.create(slot.getItem(), slot.index);
            if (shulkerInfo == null) continue;
            info.add(shulkerInfo);
        }
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
}
