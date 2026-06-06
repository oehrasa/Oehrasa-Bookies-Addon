package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.events.ScreenRenderEvent;
import com.AutoBookshelf.addon.utils.ShulkerInfo;
import com.AutoBookshelf.addon.utils.Type;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.Queue;

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
        .defaultValue(true)
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
    private final Queue<Runnable> renderQueue = new ArrayDeque<>();
    private int height, offset;
    private Vec2f clicked;

    // For combined click support
    private record DisplayEntry(ItemStack stack, int slot) {
    }

    public InventoryInfo() {
        super(Addon.CATEGORY, "inventory-info", "prigozhinplugg");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!(mc.currentScreen instanceof HandledScreen<?>) || mc.player.age % 4 != 0) return;
        refresh((HandledScreen<?>) mc.currentScreen);
    }

    @EventHandler
    private void onRenderScreen(ScreenRenderEvent event) {
        if (info.isEmpty()) return;

        int baseX = 2 + panelXOffset.get();
        int baseY = 3 + offset + panelYOffset.get();

        if (combineShulkers.get()) {
            renderCombinedGrid(event, baseX, baseY);
        } else {
            renderPerShulkerGrid(event, baseX, baseY);
        }
    }

    private void renderPerShulkerGrid(ScreenRenderEvent event, int baseX, int baseY) {
        int y = baseY;
        boolean isCompact = compact.get();
        int slotSize = isCompact ? compactSlotSize.get() : 20;
        int columns = isCompact ? compactColumns.get() : 9;
        float scale = (isCompact ? slotSize / 16.0f : 1.0f) * iconScale.get().floatValue();

        for (ShulkerInfo shulkerInfo : info) {
            int count = 0, x = baseX, startY = y, maxX = baseX + slotSize;
            for (ItemStack stack : shulkerInfo.stacks()) {
                if (shulkerInfo.type() == Type.COMPACT && stack.isEmpty()) break;
                if (count > 0 && count % columns == 0) {
                    x = baseX;
                    y += slotSize;
                }
                int drawX = x, drawY = y;
                renderQueue.add(() -> drawScaledItem(event, stack, drawX + 2, drawY, scale));
                x += slotSize;
                count++;
                if (x > maxX) maxX = x;
            }
            y += slotSize;
            if (clicked != null && clicked.x >= baseX && clicked.x <= maxX && clicked.y >= startY && clicked.y <= y) {
                renderQueue.add(() -> mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, shulkerInfo.slot(), 0, SlotActionType.PICKUP, mc.player));
                setClicked(null);
            }
            event.drawContext.fill(baseX, startY, maxX, y, BACKGROUND.hashCode());
            event.drawContext.fill(baseX, startY - 1, maxX, startY, shulkerInfo.color());
            y += 2;
        }
        flushAndUpdateHeight(y);
    }

    private void renderCombinedGrid(ScreenRenderEvent event, int baseX, int baseY) {
        // Count items and remember first shulker slot
        Map<Item, Integer> combined = new HashMap<>();
        Map<Item, Integer> itemToSlot = new HashMap<>();
        for (ShulkerInfo shulkerInfo : info) {
            for (ItemStack stack : shulkerInfo.stacks()) {
                if (stack.isEmpty()) continue;
                Item item = stack.getItem();
                combined.merge(item, stack.getCount(), Integer::sum);
                itemToSlot.putIfAbsent(item, shulkerInfo.slot());
            }
        }

        // Build display list
        List<DisplayEntry> entries = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : combined.entrySet()) {
            Item item = e.getKey();
            int total = e.getValue();
            int max = item.getMaxCount();
            int slot = itemToSlot.get(item);
            while (total > 0) {
                int size = Math.min(total, max);
                entries.add(new DisplayEntry(new ItemStack(item, size), slot));
                total -= size;
            }
        }
        entries.sort(Comparator.comparingInt((DisplayEntry e) -> -e.stack().getCount()).thenComparing(e -> e.stack().getName().getString()));

        boolean isCompact = compact.get();
        int slotSize = isCompact ? compactSlotSize.get() : 20;
        int columns = isCompact ? compactColumns.get() : 9;
        float scale = isCompact ? slotSize / 16.0f : 1.0f;

        int y = baseY;
        int maxX = baseX + slotSize;
        int startY = y;

        for (int i = 0; i < entries.size(); i++) {
            if (i > 0 && i % columns == 0) {
                y += slotSize;
            }
            int col = i % columns;
            int row = i / columns;
            int drawX = baseX + col * slotSize;
            int drawY = baseY + row * slotSize;
            DisplayEntry entry = entries.get(i);

            renderQueue.add(() -> drawScaledItem(event, entry.stack(), drawX + 2, drawY, scale));

            if (clicked != null
                && clicked.x >= drawX && clicked.x <= drawX + slotSize
                && clicked.y >= drawY && clicked.y <= drawY + slotSize) {
                renderQueue.add(() -> mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, entry.slot(), 0, SlotActionType.PICKUP, mc.player));
                setClicked(null);
            }

            if (drawX + slotSize > maxX) maxX = drawX + slotSize;
        }
        y = baseY + ((entries.size() + columns - 1) / columns) * slotSize;

        event.drawContext.fill(baseX, startY, maxX, y, BACKGROUND.hashCode());
        event.drawContext.fill(baseX, startY - 1, maxX, startY, new Color(255, 255, 255, 100).hashCode());
        flushAndUpdateHeight(y);
    }

    private void drawScaledItem(ScreenRenderEvent event, ItemStack stack, int px, int py, float scale) {
        String countText = stack.getCount() > 999 ? formatCount(stack.getCount()) : null;
        RenderUtils.drawItem(event.drawContext, stack, px, py, scale, true, countText);
    }

    private void flushAndUpdateHeight(int bottomY) {
        while (!renderQueue.isEmpty()) renderQueue.poll().run();
        height = bottomY - offset;
        setClicked(null);
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
}
