// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker.gui;

import com.AutoBookshelf.addon.modules.chesttracker.TrackerServices;
import com.AutoBookshelf.addon.modules.chesttracker.data.BlockRecord;
import com.AutoBookshelf.addon.modules.chesttracker.data.ContainerRecord;
import com.AutoBookshelf.addon.modules.chesttracker.data.MemoryBank;
import com.AutoBookshelf.addon.modules.chesttracker.track.Trash;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Confirm list for "mark everything in this area destroyed": blocks and entities in separate
 * groups, everything ticked, tap a row to untick it. Nothing changes until Confirm.
 */
public final class AreaConfirmScreen extends Screen {
    private final List<ContainerRecord> blocks = new ArrayList<>();
    private final List<ContainerRecord> entities = new ArrayList<>();
    private final Set<ContainerRecord> unticked = new HashSet<>();
    private final Trash.Area area;
    private Button confirm;

    public AreaConfirmScreen(List<ContainerRecord> candidates, Trash.Area area) {
        super(Component.translatable("chesttracker.area.title"));
        this.area = area;
        for (ContainerRecord r : candidates) (r instanceof BlockRecord ? blocks : entities).add(r);
    }

    @Override
    protected void init() {
        CheckList list = new CheckList(minecraft, width, height - 84, 40);
        list.add(Component.translatable("chesttracker.area.blocks", blocks.size()), blocks);
        list.add(Component.translatable("chesttracker.area.entities", entities.size()), entities);
        addRenderableWidget(list);

        confirm = addRenderableWidget(Button.builder(Component.empty(), b -> apply())
            .bounds(width / 2 - 154, height - 28, 150, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
            .bounds(width / 2 + 4, height - 28, 150, 20).build());
        updateConfirm();
    }

    private List<ContainerRecord> ticked() {
        List<ContainerRecord> out = new ArrayList<>();
        for (ContainerRecord r : blocks) if (!unticked.contains(r)) out.add(r);
        for (ContainerRecord r : entities) if (!unticked.contains(r)) out.add(r);
        return out;
    }

    private void updateConfirm() {
        int n = ticked().size();
        confirm.setMessage(Component.translatable("chesttracker.area.confirm", n));
        confirm.active = n > 0;
    }

    private void apply() {
        MemoryBank bank = TrackerServices.banks().bank();
        if (bank != null) {
            List<ContainerRecord> chosen = ticked();
            Trash.trashArea(bank, chosen);
            Messages.chat(Component.translatable("chesttracker.area.done", chosen.size()));
        }
        onClose();
    }

    /**
     * In-game UI: world shows through, dimmed only by the BACKDROP fill so grey text stays readable.
     */
    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, TrackerScreen.BACKDROP);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        context.centeredText(font, title, width / 2, 8, 0xFFFFFFFF);
        context.centeredText(font, Component.translatable("chesttracker.area.size",
            area.maxX() - area.minX() + 1, area.maxY() - area.minY() + 1, area.maxZ() - area.minZ() + 1,
            area.minX(), area.minY(), area.minZ(), area.maxX(), area.maxY(), area.maxZ()), width / 2, 22, 0xFFAAAAAA);
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        return super.keyPressed(CloseKey.asEscape(this, minecraft, input));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private final class CheckList extends ObjectSelectionList<CheckList.Entry> {
        CheckList(Minecraft client, int width, int height, int y) {
            super(client, width, height, y, 26);
        }

        void add(Component header, List<ContainerRecord> records) {
            addEntry(new com.AutoBookshelf.addon.modules.chesttracker.gui.AreaConfirmScreen.CheckList.Entry(header, null));
            for (ContainerRecord r : records)
                addEntry(new com.AutoBookshelf.addon.modules.chesttracker.gui.AreaConfirmScreen.CheckList.Entry(null, r));
        }

        @Override
        public int getRowWidth() {
            return Math.min(360, width - 24);
        }

        final class Entry extends ObjectSelectionList.Entry<com.AutoBookshelf.addon.modules.chesttracker.gui.AreaConfirmScreen.CheckList.Entry> {
            private final Component header;
            private final ContainerRecord record;
            private final ItemStack icon;

            Entry(Component header, ContainerRecord record) {
                this.header = header;
                this.record = record;
                this.icon = record == null ? ItemStack.EMPTY : TrackerScreen.entityIcon(record.type);
            }

            @Override
            public void extractContent(GuiGraphicsExtractor context, int mouseX, int mouseY, boolean hovered, float delta) {
                int x = getContentX();
                int y = getContentY();
                if (record == null) {
                    context.text(font, header, x + 2, y + 9, 0xFFFFD060);
                    return;
                }
                boolean on = !unticked.contains(record);
                context.text(font, on ? "[x]" : "[ ]", x + 2, y + 9, on ? 0xFFFFFFFF : 0xFF888888);
                context.item(icon, x + 22, y + 3);
                context.text(font, TrackerScreen.label(record), x + 42, y + 3, on ? 0xFFFFFFFF : 0xFF888888);
                context.text(font, TrackerScreen.where(record), x + 42, y + 14,
                    on ? TrackerScreen.statusColor(record.status) : 0xFF666666);
            }

            @Override
            public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
                if (record == null) return false;
                if (!unticked.remove(record)) unticked.add(record);
                updateConfirm();
                return true;
            }

            @Override
            public Component getNarration() {
                return record == null ? header : Component.literal(TrackerScreen.label(record));
            }
        }
    }
}
