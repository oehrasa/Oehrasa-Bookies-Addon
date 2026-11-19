// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker.gui;

import com.AutoBookshelf.addon.modules.chesttracker.ChestTrackerModule;
import com.AutoBookshelf.addon.modules.chesttracker.TrackerServices;
import com.AutoBookshelf.addon.modules.chesttracker.data.*;
import com.AutoBookshelf.addon.modules.chesttracker.search.ItemIndex;
import com.AutoBookshelf.addon.modules.chesttracker.track.Trash;
import com.mojang.serialization.JsonOps;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import org.jetbrains.annotations.Nullable;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.*;

/**
 * One container in detail (CLAUDE.md 4.1e): where it is (converted from another dimension when
 * possible), its status and notes, and its last snapshot as an inventory grid. Tap a shulker or
 * bundle to look inside it, or a book to read it; items matching the search are outlined.
 * Vanilla widgets, 18px slots.
 */
public final class DetailScreen extends Screen {
    private static final int SLOT = 18;
    private static final int COLUMNS = 9;
    /**
     * The animal's picture on a donkey, mule or llama: three slots high, as in the game's own screen.
     */
    private static final int PICTURE = 3 * SLOT;

    private final ContainerRecord record;
    private final Screen parent;
    private final String query;
    /**
     * Shulkers/bundles opened inside the container, innermost last.
     */
    private final Deque<Level> path = new ArrayDeque<>();
    private Level top;
    private int selected = -1;
    private int gridX, gridY;
    /**
     * Breadcrumb parts on screen: {x0, x1, depth}; depth 0 = the container itself.
     */
    private final List<int[]> crumbs = new ArrayList<>();
    private int crumbY;
    /**
     * Everything above the buttons scrolls when it doesn't fit (big GUI scale, long notes, a
     * double chest): mouse wheel, or drag on a touch screen. Content is clipped at viewBottom.
     */
    private int scroll, maxScroll, viewBottom;

    private record Level(String name, List<ItemStack> slots) {
    }

    /**
     * Search matches by stack, worked out once rather than every frame: matching reads a book's
     * pages, which for a shelf of 100-page books is far too slow to repeat per frame.
     */
    private final Map<ItemStack, Boolean> matches = new IdentityHashMap<>();

    public DetailScreen(ContainerRecord record, Screen parent, String query) {
        super(Component.literal(TrackerScreen.label(record)));
        this.record = record;
        this.parent = parent;
        this.query = query;
    }

    @Override
    protected void init() {
        if (top == null) top = new Level(TrackerScreen.label(record), decodeSlots());
        List<Button> buttons = new ArrayList<>();
        buttons.add(Button.builder(Component.translatable("chesttracker.detail.back"), b -> back()).build());
        if (!(record instanceof InventoryRecord)) {
            buttons.add(Button.builder(Component.translatable("chesttracker.detail.copy_xyz"),
                    b -> minecraft.setScreenAndShow(new ConfirmScreen(yes -> {
                        if (yes) TrackerScreen.copyCoords(minecraft, record, true);
                        minecraft.setScreenAndShow(this);
                    }, Component.translatable("chesttracker.detail.copy_confirm"),
                        Component.translatable("chesttracker.detail.copy_confirm_body", TrackerScreen.where(record)))))
                .build());
            buttons.add(Button.builder(Component.translatable("chesttracker.detail.copy_xz"),
                    b -> minecraft.setScreenAndShow(new ConfirmScreen(yes -> {
                        if (yes) TrackerScreen.copyCoords(minecraft, record, false);
                        minecraft.setScreenAndShow(this);
                    }, Component.translatable("chesttracker.detail.copy_confirm"),
                        Component.translatable("chesttracker.detail.copy_confirm_body", TrackerScreen.where(record)))))
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("chesttracker.detail.copy_xz_tip")))
                .build());
            buttons.add(Button.builder(record.isTrashed()
                    ? Component.translatable("chesttracker.restore") : TrackerScreen.markLabel(record), b -> toggleTrash())
                .build());
        }
        buttons.add(Button.builder(Component.translatable("chesttracker.find"), b -> {
                ChestTrackerModule m = Modules.get().get(ChestTrackerModule.class);
                if (m != null) m.highlightContainer(record);
                minecraft.setScreenAndShow(null);
            }).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("chesttracker.find_tip")))
            .build());
        viewBottom = ButtonBar.layout(buttons, font, width, height, 100) - 4;
        for (Button b : buttons) addRenderableWidget(b);
    }

    private List<ItemStack> decodeSlots() {
        int size = Math.max(record.slotCount, 1);
        List<ItemStack> slots = new ArrayList<>();
        for (int i = 0; i < size; i++) slots.add(ItemStack.EMPTY);
        if (minecraft == null || minecraft.level == null) return slots;
        var ops = minecraft.level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        for (ContainerRecord.SlotItem item : record.items) {
            ItemStack stack = ItemData.decode(ops, item.stack());
            while (slots.size() <= item.slot()) slots.add(ItemStack.EMPTY);
            slots.set(item.slot(), stack);
        }
        return slots;
    }

    private Level current() {
        return path.isEmpty() ? top : path.peekLast();
    }

    /**
     * The slots of a shulker or bundle, or null if the stack holds nothing to look into.
     */
    private static List<ItemStack> inside(ItemStack stack) {
        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        if (container != null) {
            NonNullList<ItemStack> slots = NonNullList.withSize(27, ItemStack.EMPTY);
            container.copyInto(slots);
            return slots;
        }
        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null && !bundle.isEmpty()) {
//? if >=26.3 {
            /*return new ArrayList<>(bundle.itemCopies().toList());
*///?} else {
            return new ArrayList<>(bundle.itemCopyStream().toList());
//?}
        }
        return null;
    }

    private void back() {
        if (!path.isEmpty()) {
            path.removeLast();
            selected = -1;
        } else {
            onClose();
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }

    private void toggleTrash() {
        MemoryBank bank = TrackerServices.banks().bank();
        if (bank == null) return;
        if (record.isTrashed()) Trash.restore(bank, record);
        else Trash.trash(bank, record);
        rebuildWidgets();
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        input = CloseKey.asEscape(this, minecraft, input);
        if (input.key() == InputConstants.KEY_ESCAPE && !path.isEmpty()) {
            path.removeLast(); // like a file explorer: Esc goes up one folder first
            selected = -1;
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (maxScroll == 0) return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
        scroll = Math.clamp(scroll - (int) Math.round(vertical * 16), 0, maxScroll);
        return true;
    }

    /**
     * Dragging the content scrolls it, for touch screens (PojavLauncher) without a wheel.
     */
    @Override
    public boolean mouseDragged(MouseButtonEvent click, double offsetX, double offsetY) {
        if (maxScroll > 0 && click.button() == 0 && click.y() < viewBottom) {
            scroll = Math.clamp(scroll - (int) Math.round(offsetY), 0, maxScroll);
            return true;
        }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (click.y() < viewBottom && click.y() >= crumbY - 2 && click.y() < crumbY + 10) {
            for (int[] c : crumbs) {
                if (click.x() >= c[0] && click.x() < c[1]) {
                    while (path.size() > c[2]) path.removeLast();
                    selected = -1;
                    return true;
                }
            }
        }
        int slot = slotAt(click.x(), click.y());
        if (slot >= 0 && (click.button() == 1 || click.button() == 2)) {
            // Secondary click (right or middle) on an item: search the world for nearby containers
            // holding it (the same search the grid tap runs), then close so the matches are visible.
            // Empty slots do nothing; left click keeps the content behaviour below.
            ItemStack stack = current().slots().get(slot);
            if (!stack.isEmpty()) {
                ChestTrackerModule m = Modules.get().get(ChestTrackerModule.class);
                if (m != null) {
                    m.searchItem(stack.getItem());
                    Messages.actionBar(Component.translatable("chesttracker.grid.tap_confirm",
                        stack.getHoverName().getString()));
                }
                minecraft.setScreenAndShow(null);
            }
            return true;
        }
        if (slot >= 0 && click.button() == 0) {
            ItemStack stack = current().slots().get(slot);
            List<ItemStack> inner = stack.isEmpty() ? null : inside(stack);
            BookViewScreen.BookAccess book = isBook(stack) ? BookViewScreen.BookAccess.fromItem(stack) : null;
            if (inner != null) {
                path.addLast(new Level(stack.getHoverName().getString(), inner)); // step inside
                selected = -1;
            } else if (book != null) {
                minecraft.setScreenAndShow(new Reader(book, this));
            } else {
                selected = slot == selected ? -1 : slot; // tap to pin the tooltip (touch screens)
            }
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    private int slotAt(double mx, double my) {
        if (my >= viewBottom) return -1; // scrolled under the buttons
        List<ItemStack> slots = current().slots();
        for (int i = 0; i < slots.size(); i++) {
            if (!shown(i, slots.get(i))) continue;
            int sx = gridX + slotX(i);
            int sy = gridY + slotY(i);
            if (mx >= sx && mx < sx + SLOT && my >= sy && my < sy + SLOT) return i;
        }
        return -1;
    }

    /**
     * A donkey, mule or llama, not inside a shulker: laid out like the game's own screen, with the
     * saddle (or a llama's carpet) on the left, a picture of the animal, then the chest. Slots are
     * saddle, body armour, then the chest, as the game's screen lists them.
     */
    private boolean mountLayout() {
        String type = record.type == null ? "" : record.type;
        return path.isEmpty() && record instanceof EntityRecord && current().slots().size() > 2
            && (type.endsWith("donkey") || type.endsWith("mule") || type.endsWith("llama"));
    }

    private int chestColumns() {
        return Math.max(1, (current().slots().size() - 2) / 3);
    }

    /**
     * Llamas wear a carpet and take no saddle; donkeys and mules the other way round.
     */
    private boolean shown(int i, ItemStack stack) {
        if (!mountLayout() || i >= 2 || !stack.isEmpty()) return true;
        boolean llama = record.type.endsWith("llama");
        return i == 0 ? !llama : llama;
    }

    private int slotX(int i) {
        if (!mountLayout()) return (i % COLUMNS) * SLOT;
        return i < 2 ? 0 : SLOT + PICTURE + 4 + ((i - 2) % chestColumns()) * SLOT;
    }

    private int slotY(int i) {
        if (!mountLayout()) return (i / COLUMNS) * SLOT;
        return i < 2 ? i * SLOT : ((i - 2) / chestColumns()) * SLOT;
    }

    private int gridWidth() {
        if (mountLayout()) return SLOT + PICTURE + 4 + chestColumns() * SLOT;
        return Math.min(COLUMNS, Math.max(1, current().slots().size())) * SLOT;
    }

    private int gridHeight() {
        int size = current().slots().size();
        if (mountLayout()) {
            int cols = chestColumns();
            return Math.max(PICTURE, (size - 2 + cols - 1) / cols * SLOT);
        }
        return (size + COLUMNS - 1) / COLUMNS * SLOT;
    }

    /**
     * The animal itself when it's loaded (it follows the mouse, as in its own screen), else its icon.
     */
    private void drawMount(GuiGraphicsExtractor context, int x, int y, int mouseX, int mouseY) {
        context.fill(x, y, x + PICTURE, y + PICTURE, 0xFF373737);
        context.fill(x + 1, y + 1, x + PICTURE - 1, y + PICTURE - 1, 0xFF000000);
        LivingEntity entity = loadedMount();
        if (entity != null) {
            InventoryScreen.extractEntityInInventoryFollowsMouse(context, x + 1, y + 1, x + PICTURE - 1, y + PICTURE - 1,
                17, 0.25f, mouseX, mouseY, entity);
        } else {
            context.item(TrackerScreen.entityIcon(record.type), x + PICTURE / 2 - 8, y + PICTURE / 2 - 8);
        }
    }

    private @Nullable LivingEntity loadedMount() {
        if (minecraft.level == null || !(record instanceof EntityRecord e)) return null;
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity instanceof LivingEntity living && !entity.isRemoved() && entity.getUUID().equals(e.uuid)) return living;
        }
        return null;
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
        super.extractRenderState(context, mouseX, mouseY, delta);
        int cx = width / 2;
        int y = 8 - scroll;
        context.enableScissor(0, 0, width, viewBottom);
        context.item(TrackerScreen.entityIcon(record.type), cx - font.width(title) / 2 - 20, y - 4);
        context.centeredText(font, title, cx, y, 0xFFFFFFFF);
        y += 14;
        for (String line : infoLines()) {
            context.centeredText(font, line, cx, y, 0xFFCCCCCC);
            y += 11;
        }
        y = explanation(context, TrackerScreen.rowText(record).explain(), cx, y);
        List<TrackerScreen.Note> notes = TrackerScreen.rowText(record).notes();
        for (TrackerScreen.Note note : notes) {
            context.centeredText(font, note.text(), cx, y, note.color());
            y += 11;
            y = explanation(context, note.explain(), cx, y);
        }

        // Breadcrumb (each part clickable, like a file explorer path), then the grid.
        y += 4;
        List<String> parts = new ArrayList<>();
        parts.add(Component.translatable("chesttracker.detail.contents").getString());
        for (Level l : path) parts.add(l.name());
        String sep = " > ";
        int total = 0;
        for (int i = 0; i < parts.size(); i++) total += font.width(parts.get(i)) + (i > 0 ? font.width(sep) : 0);
        int px = cx - total / 2;
        crumbs.clear();
        crumbY = y;
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                context.text(font, sep, px, y, 0xFF888888);
                px += font.width(sep);
            }
            int w = font.width(parts.get(i));
            boolean last = i == parts.size() - 1;
            boolean over = !last && mouseX >= px && mouseX < px + w && mouseY >= y - 2 && mouseY < y + 10;
            context.text(font, parts.get(i), px, y, last ? 0xFFFFD060 : over ? 0xFFFFFFFF : 0xFFC8A040);
            if (!last) crumbs.add(new int[]{px, px + w, i});
            px += w;
        }
        y += 12;

        List<ItemStack> slots = current().slots();
        gridX = cx - gridWidth() / 2;
        gridY = y;
        int hovered = slotAt(mouseX, mouseY);
        if (mountLayout()) drawMount(context, gridX + SLOT + 2, gridY, mouseX, mouseY);
        for (int i = 0; i < slots.size(); i++) {
            ItemStack stack = slots.get(i);
            if (!shown(i, stack)) continue;
            int sx = gridX + slotX(i);
            int sy = gridY + slotY(i);
            boolean match = !query.isEmpty() && !stack.isEmpty()
                && matches.computeIfAbsent(stack, st -> ItemIndex.matchesDeep(st, query, 0));
            context.fill(sx, sy, sx + SLOT, sy + SLOT, match ? 0xFFE8B04A : 0xFF373737); // border
            context.fill(sx + 1, sy + 1, sx + SLOT - 1, sy + SLOT - 1, i == hovered || i == selected ? 0xFF9A9A9A : 0xFF8B8B8B);
            if (!stack.isEmpty()) {
                context.item(stack, sx + 1, sy + 1);
                context.itemDecorations(font, stack, sx + 1, sy + 1);
            }
        }
        List<String> roles = path.isEmpty() ? slotRoles() : List.of();
        if (roles.isEmpty() && slots.stream().anyMatch(DetailScreen::isBook)) {
            roles = List.of(Component.translatable("chesttracker.detail.read_hint").getString());
        }
        if (!roles.isEmpty()) {
            context.centeredText(font, String.join(" \u00B7 ", roles), cx,
                gridY + gridHeight() + 3, 0xFFAAAAAA);
        }
        if (!record.hasSnapshot()) {
            context.centeredText(font, Component.translatable("chesttracker.never_opened"),
                cx, gridY + SLOT + 4, 0xFFAAAAAA);
        }
        context.disableScissor();
        int contentBottom = gridY + gridHeight() + (roles.isEmpty() ? 0 : 14) + 4;
        maxScroll = Math.max(0, contentBottom + scroll - viewBottom);
        if (scroll > maxScroll) scroll = maxScroll; // content got shorter (stepped out of a shulker)
        if (maxScroll > 0) {
            // Thin scrollbar on the right, so it's clear there's more below.
            int barH = Math.max(12, viewBottom * viewBottom / (viewBottom + maxScroll));
            int barY = (viewBottom - barH) * scroll / maxScroll;
            context.fill(width - 5, 0, width - 2, viewBottom, 0x40FFFFFF);
            context.fill(width - 5, barY, width - 2, barY + barH, 0xC0FFFFFF);
        }
        if (hovered >= 0 && !slots.get(hovered).isEmpty()) {
            context.setTooltipForNextFrame(font, slots.get(hovered), mouseX, mouseY);
        } else if (selected >= 0 && selected < slots.size() && !slots.get(selected).isEmpty()) {
            // A tapped (pinned) tooltip stays next to its slot instead of following the mouse.
            context.setTooltipForNextFrame(font, slots.get(selected),
                gridX + slotX(selected) + SLOT, gridY + slotY(selected));
        }
    }

    /**
     * What each slot is for, left to right, for containers whose slots differ.
     */
    private List<String> slotRoles() {
        String type = record.type == null ? "" : record.type;
        if (type.endsWith("furnace") || type.endsWith("smoker")) {
            return List.of(t("input"), t("fuel"), t("output"));
        }
        if (type.endsWith("brewing_stand")) {
            return List.of(t("bottle"), t("bottle"), t("bottle"), t("ingredient"), t("fuel"));
        }
        if (type.endsWith("armor_stand")) {
            return List.of(t("head"), t("chest"), t("legs"), t("feet"), t("main_hand"), t("off_hand"));
        }
        return List.of();
    }

    private static String t(String role) {
        return Component.translatable("chesttracker.slot." + role).getString();
    }

    /**
     * Draws an explanation wrapped and dimmed under the line it explains; returns the next y.
     */
    private int explanation(GuiGraphicsExtractor context, String text, int cx, int y) {
        if (text == null) return y;
        for (String para : text.split("\n")) {
            if (para.isEmpty()) continue;
            for (var line : font.split(Component.literal(para), Math.min(320, width - 40))) {
                context.centeredText(font, line, cx, y, 0xFFB0B0B0);
                y += 10;
            }
        }
        return y + 2;
    }

    /**
     * Info text cached per record fingerprint. Refreshed once a second so the minute age
     * label keeps ticking; everything else renders instantly from the cache in between.
     */
    private List<String> infoLinesCache;
    private String infoLinesFingerprint = "";
    private long infoLinesCacheTime = 0;

    private List<String> infoLines() {
        // Bank revision busts the cache on every bank write: status/flag/item changes land here,
        // including ones the individual fields below don't name. The minute bucket expires
        // "opened Nm ago" / "never opened" strings at minute resolution.
        long minute = System.currentTimeMillis() / 60000;
        int rev = TrackerServices.banks() == null ? -1 : (TrackerServices.banks().bank() == null ? -1 : TrackerServices.banks().bank().revision());
        String fp = minute + "|" + record.key() + "|" + record.lastOpened + "|" + record.hasSnapshot() + "|"
            + record.status + "|" + record.trashedAt + "|" + rev + "|"
            + (record instanceof EntityRecord e ? e.homeChunkX + "," + e.homeChunkZ : "");
        long now = System.currentTimeMillis();
        if (infoLinesCache != null && infoLinesFingerprint.equals(fp) && now - infoLinesCacheTime < 1000L) {
            return infoLinesCache;
        }
        List<String> lines = new ArrayList<>();
        if (record instanceof InventoryRecord) {
            lines.add(Component.translatable("chesttracker.on_me_where").getString());
            infoLinesFingerprint = fp;
            infoLinesCacheTime = now;
            infoLinesCache = lines;
            return lines;
        }
        if (record instanceof BlockRecord b && b.global) {
            lines.add(TrackerScreen.where(record));
        } else {
            lines.add(TrackerScreen.place(record, true));
            String status = Component.translatable("chesttracker.status." + TrackerScreen.statusId(record)).getString();
            boolean bad = record.status == RecordStatus.MISSING || record.status == RecordStatus.LIKELY_DESTROYED;
            lines.add((bad ? "⚠ " : "") + status + " · "
                + Component.translatable("chesttracker.seen_ago", TrackerScreen.age(record.lastSeen)).getString());
        }
        lines.add(record.hasSnapshot()
            ? Component.translatable("chesttracker.detail.opened", TrackerScreen.age(record.lastOpened)).getString()
            : Component.translatable("chesttracker.never_opened").getString());
        if (record instanceof EntityRecord e) {
            lines.add(Component.translatable("chesttracker.detail.home", e.homeChunkX, e.homeChunkZ).getString());
        }
        if (record.isTrashed()) {
            lines.add(Component.translatable("chesttracker.in_trash", TrackerScreen.age(record.trashedAt)).getString());
        }
        infoLinesFingerprint = fp;
        infoLinesCacheTime = now;
        infoLinesCache = lines;
        return lines;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static boolean isBook(ItemStack stack) {
        return stack.has(DataComponents.WRITTEN_BOOK_CONTENT) || stack.has(DataComponents.WRITABLE_BOOK_CONTENT);
    }

    /**
     * The vanilla book view, read-only, going back to the detail view when closed.
     */
    private static final class Reader extends BookViewScreen {
        private final Screen parent;

        Reader(BookViewScreen.BookAccess contents, Screen parent) {
            super(contents);
            this.parent = parent;
        }

        @Override
        public void onClose() {
            minecraft.setScreenAndShow(parent);
        }
    }
}
