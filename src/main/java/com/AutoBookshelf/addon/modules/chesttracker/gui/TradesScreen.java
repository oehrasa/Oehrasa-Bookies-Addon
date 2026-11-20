// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker.gui;

import com.AutoBookshelf.addon.modules.chesttracker.Settings;
import com.AutoBookshelf.addon.modules.chesttracker.TrackerServices;
import com.AutoBookshelf.addon.modules.chesttracker.data.EntityRecord;
import com.AutoBookshelf.addon.modules.chesttracker.data.MemoryBank;
import com.AutoBookshelf.addon.modules.chesttracker.data.TradeData;
import com.AutoBookshelf.addon.modules.chesttracker.search.Fuzzy;
import com.AutoBookshelf.addon.modules.chesttracker.track.Trash;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The shops the player has seen (CLAUDE.md 4.11), kept apart from the storage search so offers
 * never mix into inventory results. Empty search lists villagers; typing lists matching offers.
 */
public final class TradesScreen extends Screen {
    private static final int ROW_HEIGHT = 46;
    private static final int MAX_ROWS = 500;
    /**
     * Width of the "price -> goods" icons at the end of an offer row.
     */
    private static final int STRIP_WIDTH = 72;

    private EditBox search;
    private ResultList list;
    /**
     * The results shown are close matches, not exact ones.
     */
    private boolean fuzzy;
    /**
     * Wandering traders come and go; this hides them all at once. Kept while the game runs.
     */
    private static boolean showWandering = true;
    /**
     * The open row menu (right-click or the row's "⋮"), empty when closed.
     */
    private final List<Button> menu = new ArrayList<>();
    private List<Button> bar = List.of();
    private int menuX, menuY, menuW, menuH;

    private final String initialQuery;

    public TradesScreen() {
        this("");
    }

    /**
     * Opens already searching for {@code query} (/offers mending).
     */
    public TradesScreen(String query) {
        super(Component.translatable("chesttracker.trades.title"));
        this.initialQuery = query;
    }

    /**
     * The bottom buttons, and the list ending above them (they take two rows on a narrow screen).
     */
    private void layoutBar() {
        int top = ButtonBar.layout(bar, font, width, height, 120);
        list.updateSizeAndPosition(width, top - 4 - TrackerScreen.LIST_Y, TrackerScreen.LIST_Y);
    }

    @Override
    protected void init() {
        int fieldWidth = Math.min(300, width - 32);
        String previous = search != null ? search.getValue() : initialQuery;
        search = new EditBox(font, (width - fieldWidth) / 2, 24, fieldWidth, 20,
            Component.translatable("chesttracker.trades.search"));
        search.setHint(Component.translatable("chesttracker.trades.search"));
        search.setValue(previous);
        search.setResponder(q -> refresh());
        addRenderableWidget(search);

        // Coming back from a villager's details (or a resize) rebuilds the list: keep its place.
        double keepScroll = list != null ? list.scrollAmount() : 0;
        list = new ResultList(minecraft, width, height - 30 - TrackerScreen.LIST_Y, TrackerScreen.LIST_Y);
        addRenderableWidget(list);
        for (Button tab : ScreenTabs.create(minecraft, width, true)) addRenderableWidget(tab);

        bar = List.of(
            // One trash for everything: opens on its Trades part.
            Button.builder(trashLabel(), b -> minecraft.setScreenAndShow(TrackerScreen.trash(TrackerScreen.TrashKind.TRADES))).build(),
            Button.builder(wanderingLabel(), b -> {
                showWandering = !showWandering;
                b.setMessage(wanderingLabel());
                layoutBar();
                refresh();
            }).build(),
            Button.builder(CommonComponents.GUI_DONE, b -> onClose()).build());
        menu.clear();
        for (Button b : bar) addRenderableWidget(b);
        layoutBar();
        setInitialFocus(search);
        refresh();
        list.setScrollAmount(keepScroll);
    }

    private void refresh() {
        String query = TradeView.query(search.getValue());
        fuzzy = false;
        List<Row> rows = rows(query, false);
        if (rows.isEmpty() && !query.isEmpty()) {
            // Nothing exact: try close matches ("wheet" -> wheat) and say so.
            rows = rows(query, true);
            fuzzy = !rows.isEmpty();
        }
        list.setRows(rows);
    }

    private static boolean matches(String text, String query, boolean fuzzy) {
        return fuzzy ? Fuzzy.matches(text, query) : text.contains(query);
    }

    private List<Row> rows(String query, boolean fuzzy) {
        List<Row> rows = new ArrayList<>();
        MemoryBank bank = TrackerServices.banks().bank();
        if (bank != null) {
            List<EntityRecord> traders = bank.traders()
                .filter(r -> !r.isTrashed())
                .filter(r -> showWandering || !isWandering(r))
                .sorted(Comparator.comparingDouble(TrackerScreen::sortDistance))
                .toList();
            for (EntityRecord r : traders) {
                TradeData.Snapshot s = r.trades.snapshotFor(bank.currentPlayer);
                List<TradeView.ShownOffer> offers = TradeView.offers(r, s);
                if (query.isEmpty()) {
                    rows.add(new Row(r, s, offers, null));
                } else {
                    boolean any = false;
                    for (TradeView.ShownOffer o : offers) {
                        if (matches(o.searchText(), query, fuzzy)) {
                            rows.add(new Row(r, s, offers, o));
                            any = true;
                        }
                    }
                    // "librarian", "Bob": the villager itself
                    if (!any && matches(TradeView.label(r).toLowerCase(Locale.ROOT), query, fuzzy)) rows.add(new Row(r, s, offers, null));
                }
                if (rows.size() >= MAX_ROWS) break;
            }
        }
        return rows;
    }

    /**
     * "Trash (3)": everything in the trash, the same count as on the storage screen.
     */
    private static Component trashLabel() {
        return Component.translatable("chesttracker.trash_open", TrackerScreen.totalTrashed());
    }

    private static Component wanderingLabel() {
        return Component.translatable(showWandering ? "chesttracker.trades.wandering_shown"
            : "chesttracker.trades.wandering_hidden");
    }

    private static boolean isWandering(EntityRecord r) {
        return "minecraft:wandering_trader".equals(r.type);
    }

    /**
     * Row menu: mark destroyed (to the trash, e.g. a wandering trader that left). Restoring is in the trash.
     */
    private void openMenu(EntityRecord record, double x, double y) {
        closeMenu();
        Button.Builder item = Button.builder(TrackerScreen.markLabel(record), b -> {
            closeMenu();
            MemoryBank bank = TrackerServices.banks().bank();
            if (bank == null) return;
            Trash.trash(bank, record);
            rebuildWidgets(); // also updates the trash count
        });
        menuW = 120;
        menuH = 20;
        menuX = (int) Math.min(x, width - menuW - 4);
        menuY = (int) Math.min(y, height - menuH - 4);
        menu.add(addRenderableWidget(item.bounds(menuX, menuY, menuW, menuH).build()));
    }

    private void closeMenu() {
        for (Button b : menu) removeWidget(b);
        menu.clear();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (!menu.isEmpty()) {
            boolean inside = click.x() >= menuX && click.x() < menuX + menuW && click.y() >= menuY && click.y() < menuY + menuH;
            if (inside) return super.mouseClicked(click, doubled);
            closeMenu(); // a click anywhere else just closes the menu
            return true;
        }
        if (click.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            ResultList.Entry entry = list.entryAt(click.x(), click.y());
            if (entry != null) {
                openMenu(entry.row.record(), click.x(), click.y());
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    /**
     * For commands outside this package.
     */
    public static String label(EntityRecord r) {
        return TradeView.label(r);
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        input = CloseKey.asEscape(this, minecraft, input);
        if (input.key() == InputConstants.KEY_ESCAPE && !menu.isEmpty()) {
            closeMenu();
            return true;
        }
        if (input.key() == InputConstants.KEY_ESCAPE && !search.getValue().isEmpty()) {
            search.setValue("");
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        ScreenTabs.drawUnderline(context, width, true);
        if (fuzzy) TrackerScreen.drawFuzzyNote(context, font, search);
        context.centeredText(font, Component.translatable("chesttracker.hint.trades"), width / 2, 48, 0xFFB0B0B0);
        if (TrackerServices.banks().bank() == null) {
            context.centeredText(font, Component.translatable(TrackerServices.banks().isLoading()
                    ? "chesttracker.loading" : "chesttracker.no_bank"),
                width / 2, height / 2, 0xFFAAAAAA);
        } else if (list.children().isEmpty()) {
            context.centeredText(font, Component.translatable(search.getValue().isBlank()
                ? "chesttracker.trades.none_yet" : "chesttracker.no_results"), width / 2, height / 2, 0xFFAAAAAA);
        }
    }

    /**
     * In-game UI: world shows through, dimmed only by the BACKDROP fill so grey text stays readable over a bright sky.
     */
    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // In-game UI: the world stratum renders underneath and stays visible behind, dimmed only by
        // BACKDROP. No vanilla menu texture or blur stacked on top.
        context.fill(0, 0, width, height, TrackerScreen.BACKDROP);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * A villager (offer == null) or one of its offers that matched the search.
     */
    private record Row(EntityRecord record, @Nullable TradeData.Snapshot snapshot, List<TradeView.ShownOffer> offers,
                       @Nullable TradeView.ShownOffer offer) {
    }

    private final class ResultList extends ObjectSelectionList<ResultList.Entry> {
        ResultList(Minecraft client, int width, int height, int y) {
            super(client, width, height, y, ROW_HEIGHT);
        }

        com.AutoBookshelf.addon.modules.chesttracker.gui.TradesScreen.ResultList.Entry entryAt(double x, double y) {
            return getEntryAtPosition(x, y);
        }

        void setRows(List<Row> rows) {
            replaceEntries(rows.stream().map(com.AutoBookshelf.addon.modules.chesttracker.gui.TradesScreen.ResultList.Entry::new).toList());
            setScrollAmount(0);
        }

        @Override
        public int getRowWidth() {
            return Math.min(520, width - 30);
        }

        final class Entry extends ObjectSelectionList.Entry<com.AutoBookshelf.addon.modules.chesttracker.gui.TradesScreen.ResultList.Entry> {
            private final Row row;
            private final ItemStack traderIcon;

            Entry(Row row) {
                this.row = row;
                this.traderIcon = TrackerScreen.entityIcon(row.record().type);
            }

            @Override
            public void extractContent(GuiGraphicsExtractor context, int mouseX, int mouseY, boolean hovered, float delta) {
                int x = getContentX(), y = getContentY();
                int textX = x + 24, maxWidth = getRowWidth() - 30;
                EntityRecord r = row.record();
                TradeView.ShownOffer o = row.offer();
                ItemStack hoveredStack = null;
                if (o != null) {
                    // The trade as in the villager's own screen: price (and second price) -> goods.
                    int sx = x + getRowWidth() - 16 - STRIP_WIDTH;
                    hoveredStack = strip(context, o, sx, y + 13, mouseX, mouseY, hoveredStack);
                    maxWidth = sx - 8 - textX;
                }
                context.text(font, "⋮", x + getRowWidth() - 10, y + 17,
                    hovered ? 0xFFFFFFFF : 0xFF888888);
                int lineY = y + 3;
                List<TrackerScreen.Note> notes = new ArrayList<>();
                if (o != null) {
                    // Offer row: what it sells and for how much / who sells it / where / notes.
                    context.item(o.sell(), x + 2, y + 3);
                    context.itemDecorations(font, o.sell(), x + 2, y + 3);
                    context.item(traderIcon, x + 2, y + 23);
                    draw(context, TradeView.sold(o) + "  ←  " + TradeView.price(o), textX, lineY, maxWidth, 0xFFFFFFFF);
                    lineY += 11;
                    draw(context, TradeView.label(r), textX, lineY, maxWidth, 0xFFDDDDDD);
                    if (o.offer().outOfStock()) {
                        notes.add(new TrackerScreen.Note(Component.translatable("chesttracker.trades.out_of_stock").getString(),
                            0xFFE07070, null));
                    }
                    if (o.change() != null) notes.add(new TrackerScreen.Note(o.change(), o.changeColor(), null));
                } else {
                    // Villager row: who / what it sells / where / notes, with a preview of its offers
                    // (price -> goods, two at a time) at the end of the row. With more than two
                    // they move up one step every Settings.tradePreviewTicks, like a ticker: 1-2, 2-3,
                    // 3-1, ... Both slots stay filled and every offer moves the same way (up).
                    context.item(traderIcon, x + 2, y + 3);
                    int n = row.offers().size();
                    int stripsX = x + getRowWidth() - 16 - STRIP_WIDTH;
                    int step = n <= 2 ? 0
                        : (int) (TrackerServices.interactions().currentTick() / Math.max(1, Settings.tradePreviewTicks) % n);
                    for (int k = 0; k < Math.min(n, 2); k++) {
                        TradeView.ShownOffer offer = row.offers().get((step + k) % n);
                        hoveredStack = strip(context, offer, stripsX, y + 3 + k * 20, mouseX, mouseY, hoveredStack);
                    }
                    if (n > 0) maxWidth = stripsX - 8 - textX;
                    String count = row.snapshot() == null ? Component.translatable("chesttracker.never_opened").getString()
                        : Component.translatable(row.offers().size() == 1 ? "chesttracker.trades.offer"
                                                 : "chesttracker.trades.offers", row.offers().size()).getString();
                    draw(context, TradeView.label(r) + " · " + count, textX, lineY, maxWidth, 0xFFFFFFFF);
                    lineY += 11;
                    List<String> sold = row.offers().stream().map(TradeView::sold).toList();
                    draw(context, String.join(", ", sold), textX, lineY, maxWidth, 0xFFDDDDDD);
                    int gone = TradeView.gone(row.snapshot());
                    if (gone > 0) notes.add(new TrackerScreen.Note(Component.translatable("chesttracker.trades.gone", gone).getString(),
                        0xFFE8B04A, null));
                }
                lineY += 11;
                draw(context, TrackerScreen.where(r), textX, lineY, maxWidth, TrackerScreen.statusColor(r.status));
                lineY += 11;
                if (row.snapshot() != null) {
                    String other = TradeView.otherAccount(r.trades, row.snapshot());
                    if (other != null) notes.add(new TrackerScreen.Note(other, 0xFF8FB8E8, null));
                }
                notes.addAll(TrackerScreen.notes(r));
                int nx = textX;
                for (TrackerScreen.Note note : notes) {
                    int room = textX + maxWidth - nx;
                    if (room <= 0) break;
                    nx += draw(context, (nx == textX ? "" : " · ") + note.text(), nx, lineY, room, note.color());
                }
                if (hovered && o != null && mouseX >= x + 2 && mouseX < x + 18 && mouseY >= y + 3 && mouseY < y + 19) {
                    hoveredStack = o.sell();
                }
                if (hovered && hoveredStack != null) context.setTooltipForNextFrame(font, hoveredStack, mouseX, mouseY);
            }

            /**
             * price (+ second price) -> goods, as in the villager's own screen; returns the hovered stack.
             */
            private ItemStack strip(GuiGraphicsExtractor context, TradeView.ShownOffer o, int sx, int sy, int mouseX, int mouseY,
                                    ItemStack hoveredSoFar) {
                // Each offer on its own faint box, like the trade buttons in the villager's screen.
                context.fill(sx - 3, sy - 1, sx + STRIP_WIDTH - 1, sy + 17, 0x30FFFFFF);
                ItemStack h = stripItem(context, o.buy1(), sx, sy, mouseX, mouseY, hoveredSoFar);
                if (!o.buy2().isEmpty()) h = stripItem(context, o.buy2(), sx + 20, sy, mouseX, mouseY, h);
                TradeView.tradeArrow(context, sx + 40, sy, o.offer().outOfStock());
                return stripItem(context, o.sell(), sx + 54, sy, mouseX, mouseY, h);
            }

            /**
             * One icon of the trade strip, with its count; returns the stack if the mouse is on it.
             */
            private ItemStack stripItem(GuiGraphicsExtractor context, ItemStack stack, int x, int y, int mouseX, int mouseY,
                                        ItemStack hoveredSoFar) {
                context.item(stack, x, y);
                context.itemDecorations(font, stack, x, y);
                return mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16 ? stack : hoveredSoFar;
            }

            /**
             * Draws text cut to fit; returns its width.
             */
            private int draw(GuiGraphicsExtractor context, String s, int x, int y, int maxWidth, int color) {
                if (font.width(s) > maxWidth) {
                    s = font.plainSubstrByWidth(s, Math.max(0, maxWidth - font.width("..."))) + "...";
                }
                context.text(font, s, x, y, color);
                return font.width(s);
            }

            @Override
            public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
                ResultList.this.setSelected(this);
                // The "⋮" at the end of the row opens the menu with a plain tap (touch screens).
                if (click.x() >= getContentX() + getRowWidth() - 16) {
                    openMenu(row.record(), click.x(), click.y());
                    return true;
                }
                minecraft.setScreenAndShow(new TradeDetailScreen(row.record(), TradesScreen.this, TradeView.query(search.getValue())));
                return true;
            }

            @Override
            public Component getNarration() {
                return Component.literal(TradeView.label(row.record()));
            }
        }
    }
}
