// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker.gui;

import com.AutoBookshelf.addon.modules.chesttracker.TrackerServices;
import com.AutoBookshelf.addon.modules.chesttracker.data.EntityRecord;
import com.AutoBookshelf.addon.modules.chesttracker.data.MemoryBank;
import com.AutoBookshelf.addon.modules.chesttracker.data.TradeData;
import com.AutoBookshelf.addon.modules.chesttracker.track.Trash;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Every offer of one villager or wandering trader, as last seen (CLAUDE.md 4.11).
 */
public final class TradeDetailScreen extends Screen {
    private static final int ROW_HEIGHT = 22;

    private final EntityRecord record;
    private final Screen parent;
    private final String query;
    private final List<String> header = new ArrayList<>();
    private final List<Integer> headerColors = new ArrayList<>();
    private @Nullable ItemStack hoveredStack;

    public TradeDetailScreen(EntityRecord record, Screen parent, String query) {
        super(Component.literal(TradeView.label(record)));
        this.record = record;
        this.parent = parent;
        this.query = query;
    }

    @Override
    protected void init() {
        MemoryBank bank = TrackerServices.banks().bank();
        TradeData.Snapshot s = record.trades == null ? null : record.trades.snapshotFor(bank == null ? null : bank.currentPlayer);
        header.clear();
        headerColors.clear();
        line(TrackerScreen.place(record, true) + " · " + statusText(), TrackerScreen.statusColor(record.status));
        if (s != null) {
            String when = Component.translatable("chesttracker.trades.seen", TrackerScreen.age(s.time)).getString();
            String other = TradeView.otherAccount(record.trades, s);
            line(other != null ? when + " · " + other : when, 0xFFAAAAAA);
            int gone = TradeView.gone(s);
            if (gone > 0) line(Component.translatable("chesttracker.trades.gone", gone).getString(), 0xFFE8B04A);
        }
        for (TrackerScreen.Note note : TrackerScreen.notes(record)) line(note.text(), note.color());
        String explain = TrackerScreen.explain("trades.changes");
        if (explain != null) line(explain, 0xFFB0B0B0);

        List<Button> buttons = List.of(
            Button.builder(Component.translatable("chesttracker.detail.back"), b -> onClose()).build(),
            Button.builder(Component.translatable("chesttracker.detail.copy_xyz"),
                b -> TrackerScreen.copyCoords(minecraft, record, true)).build(),
            Button.builder(Component.translatable("chesttracker.detail.copy_xz"),
                    b -> TrackerScreen.copyCoords(minecraft, record, false))
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("chesttracker.detail.copy_xz_tip")))
                .build(),
            Button.builder(record.isTrashed()
                    ? Component.translatable("chesttracker.restore") : TrackerScreen.markLabel(record), b -> toggleTrash())
                .build(),
            Button.builder(CommonComponents.GUI_DONE, b -> minecraft.setScreenAndShow(null)).build());
        int buttonsTop = ButtonBar.layout(buttons, font, width, height, 100);

        int listY = 22 + header.size() * 11 + 6;
        OfferList list = new OfferList(minecraft, width, buttonsTop - 6 - listY, listY);
        for (TradeView.ShownOffer o : TradeView.offers(record, s)) list.add(o);
        addRenderableWidget(list);
        for (Button b : buttons) addRenderableWidget(b);
    }

    /**
     * Header text, wrapped to the screen width.
     */
    private void line(String text, int color) {
        for (var part : font.split(Component.literal(text), Math.min(420, width - 40))) {
            StringBuilder sb = new StringBuilder();
            part.accept((index, style, codePoint) -> {
                sb.appendCodePoint(codePoint);
                return true;
            });
            header.add(sb.toString());
            headerColors.add(color);
        }
    }

    private String statusText() {
        return Component.translatable("chesttracker.status." + TrackerScreen.statusId(record)).getString();
    }

    private void toggleTrash() {
        MemoryBank bank = TrackerServices.banks().bank();
        if (bank == null) return;
        if (record.isTrashed()) Trash.restore(bank, record);
        else Trash.trash(bank, record);
        rebuildWidgets();
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }

    /**
     * Darker than the vanilla backdrop, so grey text stays readable over a bright sky.
     */
    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // In-game UI: the world stratum renders underneath and stays visible behind, dimmed only by
        // BACKDROP. No vanilla menu texture or blur stacked on top.
        context.fill(0, 0, width, height, TrackerScreen.BACKDROP);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        hoveredStack = null;
        super.extractRenderState(context, mouseX, mouseY, delta);
        context.centeredText(font, title, width / 2, 8, 0xFFFFFFFF);
        for (int i = 0; i < header.size(); i++) {
            context.centeredText(font, header.get(i), width / 2, 22 + i * 11, headerColors.get(i));
        }
        if (hoveredStack != null) context.setTooltipForNextFrame(font, hoveredStack, mouseX, mouseY);
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        return super.keyPressed(CloseKey.asEscape(this, minecraft, input));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private final class OfferList extends ContainerObjectSelectionList<OfferList.Entry> {
        OfferList(Minecraft client, int width, int height, int y) {
            super(client, width, height, y, ROW_HEIGHT);
        }

        void add(TradeView.ShownOffer offer) {
            addEntry(new com.AutoBookshelf.addon.modules.chesttracker.gui.TradeDetailScreen.OfferList.Entry(offer));
        }

        @Override
        public int getRowWidth() {
            return Math.min(480, width - 30);
        }

        final class Entry extends ContainerObjectSelectionList.Entry<com.AutoBookshelf.addon.modules.chesttracker.gui.TradeDetailScreen.OfferList.Entry> {
            private final TradeView.ShownOffer offer;
            private final boolean match;

            Entry(TradeView.ShownOffer offer) {
                this.offer = offer;
                this.match = !query.isEmpty() && (offer.searchText().contains(query)
                    || com.AutoBookshelf.addon.modules.chesttracker.search.Fuzzy.matches(offer.searchText(), query));
            }

            @Override
            public void extractContent(GuiGraphicsExtractor context, int mouseX, int mouseY, boolean hovered, float delta) {
                int x = getContentX(), y = getContentY();
                if (match) context.fill(x - 2, y - 1, x + getRowWidth() - 2, y + ROW_HEIGHT - 3, 0x553A78C8);
                boolean out = offer.offer().outOfStock();
                item(context, offer.buy1(), x + 2, y + 1, mouseX, mouseY);
                if (!offer.buy2().isEmpty()) item(context, offer.buy2(), x + 22, y + 1, mouseX, mouseY);
                TradeView.tradeArrow(context, x + 42, y + 1, out);
                item(context, offer.sell(), x + 56, y + 1, mouseX, mouseY);

                int textX = x + 80;
                int room = getRowWidth() - 84;
                List<String> parts = new ArrayList<>();
                parts.add(TradeView.describe(offer.sell()));
                String stock = out ? Component.translatable("chesttracker.trades.out_of_stock").getString()
                    : Component.translatable("chesttracker.trades.uses", offer.offer().maxUses() - offer.offer().uses(),
                    offer.offer().maxUses()).getString();
                String main = String.join(" · ", parts);
                int w = draw(context, main, textX, y + 1, room, 0xFFFFFFFF);
                int nx = textX;
                nx += draw(context, stock, nx, y + 11, room, out ? 0xFFE07070 : 0xFFAAAAAA);
                int base = offer.offer().baseCount();
                if (base > 0 && offer.buy1().getCount() < base) {
                    nx += draw(context, " · " + Component.translatable("chesttracker.trades.discount", base).getString(),
                        nx, y + 11, textX + room - nx, 0xFF8FE08F);
                } else if (base > 0 && offer.buy1().getCount() > base) {
                    nx += draw(context, " · " + Component.translatable("chesttracker.trades.demand", base).getString(),
                        nx, y + 11, textX + room - nx, 0xFFE8B04A);
                }
                if (offer.change() != null) draw(context, " · " + offer.change(), nx, y + 11, textX + room - nx, offer.changeColor());
            }

            private void item(GuiGraphicsExtractor context, ItemStack stack, int x, int y, int mouseX, int mouseY) {
                context.item(stack, x, y);
                context.itemDecorations(font, stack, x, y);
                if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16 && !stack.isEmpty()) hoveredStack = stack;
            }

            private int draw(GuiGraphicsExtractor context, String s, int x, int y, int maxWidth, int color) {
                if (maxWidth <= 0) return 0;
                if (font.width(s) > maxWidth) {
                    s = font.plainSubstrByWidth(s, Math.max(0, maxWidth - font.width("..."))) + "...";
                }
                context.text(font, s, x, y, color);
                return font.width(s);
            }

            @Override
            public List<? extends GuiEventListener> children() {
                return List.of();
            }

            @Override
            public List<? extends NarratableEntry> narratables() {
                return List.of();
            }
        }
    }
}
