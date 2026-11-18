/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.remote;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * The remote library preview: one part of a previewed book shown as a read-only
 * written book. Part navigation is a single row of three buttons at the top-left:
 * previous / position readout / next, with wrap-around. Back returns to the selection
 * screen. The layout stays fixed regardless of how many parts the import would
 * produce, so it never overflows the screen edge.
 *
 * <p>A two-line title header above the book body shows the manifest entry's title
 * (truncated to fit the window) and its author, so the preview is anchored in the
 * same metadata the user picked in the selection list.
 */
public class PreviewBookScreen extends BookViewScreen {
    private final List<ItemStack> partBooks;
    private final int currentPart;
    private final RemoteBookSelectScreen parent;
    private final BookEntry entry;

    public PreviewBookScreen(List<ItemStack> partBooks, int currentPart, RemoteBookSelectScreen parent, BookEntry entry) {
        super(BookViewScreen.BookAccess.fromItem(partBooks.get(currentPart)));
        this.partBooks = partBooks;
        this.currentPart = currentPart;
        this.parent = parent;
        this.entry = entry;
    }

    @Override
    public Component getNarrationMessage() {
        Component base = super.getNarrationMessage();
        return Component.literal("Part " + (currentPart + 1) + " of " + partBooks.size() + " / ").append(base);
    }

    @Override
    protected void init() {
        super.init();
        int y = 48;
        int prev = (currentPart - 1 + partBooks.size()) % partBooks.size();
        int next = (currentPart + 1) % partBooks.size();
        addRenderableWidget(Button.builder(Component.literal("<"),
                b -> minecraft.gui.setScreen(new PreviewBookScreen(partBooks, prev, parent, entry)))
            .pos(4, y).size(28, 16).build());
        Button labelButton = Button.builder(Component.literal("< Part " + (currentPart + 1) + " / " + partBooks.size()),
            b -> {
            }).pos(36, y).size(92, 16).build();
        labelButton.active = false;
        addRenderableWidget(labelButton);
        addRenderableWidget(Button.builder(Component.literal(">"),
                b -> minecraft.gui.setScreen(new PreviewBookScreen(partBooks, next, parent, entry)))
            .pos(132, y).size(28, 16).build());
        addRenderableWidget(Button.builder(Component.literal("Back"),
                b -> minecraft.gui.setScreen(parent))
            .pos(4, y + 20).size(120, 16).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        // Manifest block directly under the vanilla Done button. Anchored to the
        // controls-top of the book layout so the position follows the window, not a
        // hardcoded screen coordinate, and so this region never touches the page body
        // or the page-turn buttons above it.
        int xBase = (this.width - 192) / 2;       // matches vanilla backgroundLeft()
        int y = menuControlsTop() + 24;           // just below the Done button
        String title = entry.displayTitle();
        String author = entry.hasAuthor() ? "by " + entry.author : null;
        String headline = "Part " + (currentPart + 1) + " of " + partBooks.size();
        y = drawWrapped(graphics, title, xBase, y, 192, 0xFFFFFFFF, true);
        y += 2;
        if (author != null) {
            y = drawWrapped(graphics, author, xBase, y, 192, 0xFFE8E8E8, true);
            y += 2;
        }
        y = drawWrapped(graphics, headline, xBase, y, 192, 0xFFFFFFFF, true);
    }

    /**
     * Draws text word-wrapped to {@code maxWidth}, centre-anchored at {@code xBase + maxWidth/2}.
     * Returns the next y position after the lines that were drawn.
     */
    private int drawWrapped(GuiGraphicsExtractor graphics, String text, int xBase, int y, int maxWidth, int color, boolean shadow) {
        int centerX = xBase + maxWidth / 2;
        java.util.List<net.minecraft.util.FormattedCharSequence> lines = font.split(net.minecraft.network.chat.FormattedText.of(text), maxWidth);
        for (net.minecraft.util.FormattedCharSequence line : lines) {
            int w = font.width(line);
            graphics.text(font, line, centerX - w / 2, y, color, shadow);
            y += font.lineHeight + 1;
        }
        return y;
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
