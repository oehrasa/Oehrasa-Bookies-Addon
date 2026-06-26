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
    private int height, offset;
    private Vec2f clicked;

    private record DisplayEntry(ItemStack stack, int slot) {
    }

    public InventoryInfo() {
        super(Addon.CATEGORY, "Inventory-Info", "prigozhinplugg");
    }
    //TODO
    // Make proper component display.
    // Add profile target, litematica Material list feature.
    // Searchbar.
    // Whisper/info panel.

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!(mc.currentScreen instanceof HandledScreen<?>) || mc.player.age % 4 != 0) return;
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
            int count = 0, x = baseX, startY = y, maxX = baseX + slotSize;
            for (ItemStack stack : shulkerInfo.stacks()) {
                if (shulkerInfo.type() == Type.COMPACT && stack.isEmpty()) break;
                if (count > 0 && count % columns == 0) {
                    x = baseX;
                    y += slotSize;
                }
                drawScaledItem(event, stack, x + 2, y, scale);
                x += slotSize;
                count++;
                if (x > maxX) maxX = x;
            }
            y += slotSize;
            if (clicked != null
                && clicked.x >= baseX && clicked.x <= maxX
                && clicked.y >= startY && clicked.y <= y) {
                mc.interactionManager.clickSlot(
                    mc.player.currentScreenHandler.syncId,
                    shulkerInfo.slot(), 0, SlotActionType.PICKUP, mc.player);
                setClicked(null);
            }
            // use int ARGB constants instead of Color.hashCode()
            event.drawContext.fill(baseX, startY, maxX, y, COLOR_BACKGROUND);
            event.drawContext.fill(baseX, startY - 1, maxX, startY, shulkerInfo.color());
            y += 2;
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
            ItemStack display = template.copy();
            display.setCount(total);
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
        int maxX = baseX + slotSize;

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

            if (drawX + slotSize > maxX) maxX = drawX + slotSize;
        }

        int y = baseY + ((entries.size() + columns - 1) / columns) * slotSize;

        // use int ARGB constants instead of Color.hashCode()
        event.drawContext.fill(baseX, startY, maxX, y, COLOR_BACKGROUND);
        event.drawContext.fill(baseX, startY - 1, maxX, startY, COLOR_SEPARATOR);
        height = y - offset;
        setClicked(null);
    }

    /**
     * Draws a scaled item icon and its overlay (durability bar, count text).
     */
    private void drawScaledItem(ScreenRenderEvent event, ItemStack stack, int px, int py, float scale) {
        String countText = stack.getCount() > 999 ? formatCount(stack.getCount()) : null;

        var context = event.drawContext;
        var matrices = context.getMatrices();

        // 1. Draw the item icon at the requested scale.
        matrices.push();
        matrices.translate(px, py, 0);
        matrices.scale(scale, scale, 1);
        context.drawItem(stack, 0, 0);
        matrices.pop();

        // 2. Draw the overlay (durability bar, count text) without the scale
        //    matrix active. drawStackOverlay() uses hardcoded pixel geometry
        //    that must render at 1:1 screen pixels relative to (px, py).
        matrices.push();
        matrices.translate(px, py, 0);
        context.drawStackOverlay(mc.textRenderer, stack, 0, 0, countText);
        matrices.pop();
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
