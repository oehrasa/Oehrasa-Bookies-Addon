package com.AutoBookshelf.addon.utils;

import com.AutoBookshelf.addon.modules.InventoryTracker;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class InventoryTrackerScreen extends Screen {

    private static final int SLOT_SIZE = 18;
    private static final int PAD = 4;
    private static final int TITLE_H = 10;

    private static final EquipmentSlot[] EQUIPMENT_ORDER = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
        EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND
    };

    private final Player player;
    private final InventoryTracker.TrackedInventory tracked;

    private int panelX, panelY, panelW, panelH;
    private int equipY, itemsY;

    private ItemStack hoveredStack = null;
    private boolean hoveredIsEquip = false;

    public InventoryTrackerScreen(Player player, InventoryTracker.TrackedInventory tracked) {
        super(Component.literal(player.getName().getString() + "'s Inventory"));
        this.player = player;
        this.tracked = tracked;
    }

    @Override
    protected void init() {
        int itemRows = Math.max(1, (tracked.items.size() + 8) / 9);

        panelW = 9 * SLOT_SIZE + PAD * 2;
        panelH = PAD + TITLE_H + PAD
            + SLOT_SIZE
            + PAD
            + itemRows * SLOT_SIZE
            + PAD;

        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
        equipY = panelY + PAD + TITLE_H + PAD;
        itemsY = equipY + SLOT_SIZE + PAD;
    }

    @Override
    public void tick() {
        if (mc.level == null) onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);

        hoveredStack = null;
        hoveredIsEquip = false;

        drawPanel(graphics);
        drawEquipmentRow(graphics, mouseX, mouseY);
        drawSeparator(graphics);
        drawTrackedGrid(graphics, mouseX, mouseY);

        if (hoveredStack != null && !hoveredStack.isEmpty()) {
            drawHoverTooltip(graphics, mouseX, mouseY);
        }
    }

    // panel background & title
    private void drawPanel(GuiGraphicsExtractor context) {
        context.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xC0101010);
        context.fill(panelX, panelY, panelX + panelW, panelY + 1, 0xFF888888);
        context.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, 0xFF888888);
        context.fill(panelX, panelY, panelX + 1, panelY + panelH, 0xFF888888);
        context.fill(panelX + panelW - 1, panelY, panelX + panelW, panelY + panelH, 0xFF888888);

        context.text(mc.font,
            player.getName().getString() + "'s Inventory",
            panelX + PAD, panelY + PAD, 0xFFFFFFFF);
    }

    private void drawEquipmentRow(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        for (int i = 0; i < EQUIPMENT_ORDER.length; i++) {
            int x = panelX + PAD + i * SLOT_SIZE;
            drawSlot(context, player.getItemBySlot(EQUIPMENT_ORDER[i]),
                x, equipY, mouseX, mouseY, true);
        }
    }

    private void drawSeparator(GuiGraphicsExtractor context) {
        int y = equipY + SLOT_SIZE + PAD / 2;
        context.fill(panelX + PAD, y, panelX + panelW - PAD, y + 1, 0xFF555555);
    }

    private void drawTrackedGrid(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        List<ItemStack> items = tracked.items;
        for (int i = 0; i < items.size(); i++) {
            int x = panelX + PAD + (i % 9) * SLOT_SIZE;
            int y = itemsY + (i / 9) * SLOT_SIZE;
            drawSlot(context, items.get(i), x, y, mouseX, mouseY, false);
        }
    }

    // slot rendering
    private void drawSlot(GuiGraphicsExtractor context, ItemStack stack,
                          int x, int y, int mouseX, int mouseY, boolean isEquip) {
        drawSlotBackground(context, x, y);

        if (!stack.isEmpty()) {
            context.item(stack, x + 1, y + 1);
            context.itemDecorations(mc.font, stack, x + 1, y + 1, null);
        }

        if (isMouseOver(mouseX, mouseY, x, y)) {
            context.fill(x, y, x + SLOT_SIZE, y + SLOT_SIZE, 0x80FFFFFF);
            if (!stack.isEmpty()) {
                hoveredStack = stack;
                hoveredIsEquip = isEquip;
            }
        }
    }

    private void drawSlotBackground(GuiGraphicsExtractor context, int x, int y) {
        context.fill(x, y, x + SLOT_SIZE, y + SLOT_SIZE, 0xFF373737);
        context.fill(x, y, x + SLOT_SIZE - 1, y + 1, 0xFF8B8B8B);
        context.fill(x, y, x + 1, y + SLOT_SIZE - 1, 0xFF8B8B8B);
        context.fill(x + SLOT_SIZE - 1, y + 1, x + SLOT_SIZE, y + SLOT_SIZE, 0xFF565656);
        context.fill(x + 1, y + SLOT_SIZE - 1, x + SLOT_SIZE, y + SLOT_SIZE, 0xFF565656);
    }

    private void drawHoverTooltip(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        // 1.21.11: getTooltipFromItem returns List<Text>; drawTooltip also takes List<Text>
        List<Component> tooltip = new ArrayList<>(Screen.getTooltipFromItem(mc, hoveredStack));

        // Append "Last Seen" only for tracked items, not live equipment slots.
        // timeMap is identity-keyed: hoveredStack IS the same reference stored in
        // items/timeMap via update(), so getLong resolves correctly.
        if (!hoveredIsEquip) {
            long ts = tracked.timeMap.getLong(hoveredStack);
            if (ts != 0L) {
                tooltip.add(
                    Component.literal("Last Seen: " + formatElapsed(System.currentTimeMillis() - ts))
                        .withStyle(ChatFormatting.GRAY)
                );
            }
        }

        context.setComponentTooltipForNextFrame(mc.font, tooltip, mouseX, mouseY);
    }

    private boolean isMouseOver(int mouseX, int mouseY, int x, int y) {
        return mouseX >= x && mouseX < x + SLOT_SIZE
            && mouseY >= y && mouseY < y + SLOT_SIZE;
    }

    private String formatElapsed(long ms) {
        long total = ms / 1000;
        long minutes = (total % 3600) / 60;
        long seconds = total % 60;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (mc.options.keyInventory.matches(input)) {
            onClose();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
