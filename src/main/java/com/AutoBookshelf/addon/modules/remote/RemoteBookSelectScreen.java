/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.remote;

import com.AutoBookshelf.addon.utils.BookPages;
import com.AutoBookshelf.addon.utils.BookUtils;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.utils.Utils;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import com.AutoBookshelf.addon.utils.ScreenUtil;

// Lets the player search/browse the remote manifest
// and pick one or more books to queue for import.
public class RemoteBookSelectScreen extends WindowScreen {
    private static final int FILE_LABEL_MAX_CHARS = 48;
    private static final String ELLIPSIS = "…";
    // Leading spaces on the author line
    private static final String AUTHOR_INDENT = "    ";
    private static final int PREVIEW_CACHE_SIZE = 3;

    private final List<BookEntry> allEntries;
    private final Consumer<List<BookEntry>> onConfirm;
    // Pages per book as the Import module is configured to sign with.
    private final int pagesPerBook;
    // Minimum width (pixels) requested for the screen's rows. Driven by the
    // Remote Library > screen-width setting on BookImporter.
    private final int minWidth;
    private final Map<BookEntry, WCheckbox> checkboxes = new LinkedHashMap<>();
    private final List<BookEntry> visible = new ArrayList<>();

    // LRU cache for built preview part books (keyed by file URL).
    private final LinkedHashMap<String, List<ItemStack>> previewCache = new LinkedHashMap<>(PREVIEW_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<ItemStack>> eldest) {
            return size() > PREVIEW_CACHE_SIZE;
        }
    };

    private WVerticalList listContainer;
    private WTextBox search;
    private meteordevelopment.meteorclient.gui.widgets.WLabel countLabel;
    // Set the moment the import button runs, so the onClosed callback can tell a
    // confirmed import apart from an actual cancel and only fire onCancel for the latter.
    private boolean confirmed = false;
    private boolean fetchingPreview = false;

    public RemoteBookSelectScreen(List<BookEntry> entries, Consumer<List<BookEntry>> onConfirm, Runnable onCancel, int pagesPerBook) {
        this(entries, onConfirm, onCancel, pagesPerBook, 320);
    }

    public RemoteBookSelectScreen(List<BookEntry> entries, Consumer<List<BookEntry>> onConfirm, Runnable onCancel, int pagesPerBook, int minWidth) {
        super(GuiThemes.get(), "Select Books to Import");
        this.allEntries = entries;
        this.onConfirm = onConfirm;
        this.pagesPerBook = pagesPerBook;
        this.minWidth = minWidth;
        onClosed(() -> {
            if (!confirmed) onCancel.run();
        });
    }

    @Override
    public void initWidgets() {
        WHorizontalList searchRow = add(theme.horizontalList()).expandX().widget();

        search = searchRow.add(theme.textBox("", "Search books...")).expandX().minWidth(minWidth).widget();
        search.setFocused(true);
        search.action = () -> refreshList(search.get());

        countLabel = searchRow.add(theme.label(countText(allEntries.size()))).widget();

        WHorizontalList actions = add(theme.horizontalList()).expandX().minWidth(minWidth).widget();
        WButton selectAllBtn = actions.add(theme.button("Select All Visible")).expandX().widget();
        selectAllBtn.action = () -> {
            List<BookEntry> targets = new ArrayList<>();
            for (BookEntry entry : visible) {
                WCheckbox cb = checkboxes.get(entry);
                if (cb != null) targets.add(entry);
            }
            if (targets.isEmpty()) return;

            // Toggle: if any visible book is unchecked, tick everything (fills the rest)
            // if every visible book is already checked, clear all.
            boolean anyUnchecked = false;
            for (BookEntry entry : targets) {
                if (!checkboxes.get(entry).checked) {
                    anyUnchecked = true;
                    break;
                }
            }
            boolean selectAll = anyUnchecked;
            for (BookEntry entry : targets) {
                checkboxes.get(entry).checked = selectAll;
            }
        };

        WButton importBtn = actions.add(theme.button("Import Selected")).expandX().widget();
        importBtn.action = () -> {
            List<BookEntry> selected = new ArrayList<>();
            for (var e : checkboxes.entrySet()) {
                if (e.getValue().checked) selected.add(e.getKey());
            }
            if (!selected.isEmpty()) {
                confirmed = true;
                onConfirm.accept(selected);
                onClose();
            }
        };

        WButton cancelBtn = actions.add(theme.button("Cancel")).expandX().widget();
        cancelBtn.action = this::onClose;

        add(theme.horizontalSeparator()).expandX();
        listContainer = add(theme.verticalList()).expandX().minWidth(minWidth).widget();
        refreshList("");
    }

    private void refreshList(String filterRaw) {
        listContainer.clear();
        visible.clear(); // reset tracked-visible set for this filter pass
        String filter = filterRaw == null ? "" : filterRaw.toLowerCase(Locale.ROOT);

        List<BookEntry> matched = new ArrayList<>();
        for (BookEntry entry : allEntries) {
            // Matches on the filename-derived group/title and the real
            // book_title/author when the manifest has them. An entry with
            // no metadata sidecar just has null bookTitle/author, which
            // contributes nothing to the haystack.
            String haystack = String.join(" ",
                nullToEmpty(entry.group),
                nullToEmpty(entry.title),
                nullToEmpty(entry.bookTitle),
                nullToEmpty(entry.author)
            ).toLowerCase(Locale.ROOT);
            if (!filter.isEmpty() && !haystack.contains(filter)) continue;
            matched.add(entry);
        }

        if (countLabel != null) {
            countLabel.set(countText(matched.size(), allEntries.size()));
        }
        if (matched.isEmpty()) {
            listContainer.add(theme.label("No matches.")).expandX().minWidth(minWidth).pad(10);
            return;
        }

        // Each book renders as its own titled block, Meteor's HomesScreen-ish shape:
        // title, author, then a row with checkbox, file name, and a Preview button.
        for (int i = 0; i < matched.size(); i++) {
            BookEntry entry = matched.get(i);
            visible.add(entry); // this entry passed the filter, so it's currently rendered

            listContainer.add(theme.label(
                "(" + (i + 1) + ") " + entry.displayTitle(),
                false,
                labelMaxWidth() / theme.scale(1))).expandX();

            if (entry.hasAuthor()) {
                listContainer.add(theme.label(fit(AUTHOR_INDENT + "by " + entry.author, labelMaxWidth()))).expandX();
            }

            var fileRow = listContainer.add(theme.horizontalList()).expandX().widget();
            WCheckbox checkbox = checkboxes.computeIfAbsent(entry, e -> theme.checkbox(false));
            fileRow.add(checkbox);
            fileRow.add(theme.label(truncate(fileNameOf(entry), FILE_LABEL_MAX_CHARS))).expandX();

            // Per-row Preview button, independent of the checkbox.
            WButton previewBtn = fileRow.add(theme.button("Preview")).widget();
            previewBtn.action = () -> openPreview(entry);

            if (i < matched.size() - 1) {
                listContainer.add(theme.horizontalSeparator()).expandX();
            }
        }
    }

    private static String countText(int n) {
        return n + (n == 1 ? " book" : " books");
    }

    private static String countText(int showing, int total) {
        return total + (total == 1 ? " book" : " books")
            + (showing == total ? "" : " (" + showing + " shown)");
    }

    private void openPreview(BookEntry entry) {
        if (fetchingPreview) return;
        fetchingPreview = true;

        // Check cache first.
        List<ItemStack> cached = previewCache.get(entry.url);
        if (cached != null) {
            fetchingPreview = false;
            showPreview(cached, entry);
            return;
        }

        CompletableFuture
            .supplyAsync(() -> {
                try {
                    return RemoteLibrary.fetchLines(entry.url);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            })
            .whenComplete((lines, throwable) -> Minecraft.getInstance().execute(() -> {
                fetchingPreview = false;
                if (throwable != null) {
                    return;
                }
                List<ItemStack> books = buildPreviewParts(entry, lines);
                previewCache.put(entry.url, books);
                showPreview(books, entry);
            }));
    }

    /**
     * Builds the same multi-part written books the import would sign, so the
     * preview is byte-identical to the real thing.
     */
    private List<ItemStack> buildPreviewParts(BookEntry entry, List<String> lines) {
        List<String> pages = BookPages.convertLinesToPages(lines);
        int parts = (int) Math.ceil((double) pages.size() / pagesPerBook);
        if (parts < 1) parts = 1;
        String baseTitle = BookPages.truncateBaseTitle(entry.title != null ? entry.title : entry.file);
        String author = entry.hasAuthor() ? entry.author : "Unknown";
        List<ItemStack> books = new ArrayList<>(parts);
        for (int i = 0; i < parts; i++) {
            List<String> slice = pages.subList(i * pagesPerBook, Math.min(pages.size(), (i + 1) * pagesPerBook));
            String title = BookPages.partTitle(baseTitle, i + 1, parts);
            books.add(BookUtils.createWrittenBookFromPages(title, author, slice));
        }
        return books;
    }

    private void showPreview(List<ItemStack> partBooks, BookEntry entry) {
        ScreenUtil.set(new PreviewBookScreen(partBooks, 0, this, entry));
    }

    private static String fileNameOf(BookEntry entry) {
        String file = entry.file;
        if (file == null) return "?";
        int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        return slash >= 0 ? file.substring(slash + 1) : file;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    // Row text budget. The window sizes to its content, so a budget based on the
    // whole screen never binds: cap it to a paragraph width and only shrink
    // further on tiny windows. Title rows word-wrap against this via the multi
    // label; author rows ellipsize against it via fit(). This also stops a long
    // title from stretching the window to fill the screen.
    private double labelMaxWidth() {
        // Use the configured minimum width as the upper cap for row contents so the
        // window actually grows with the setting instead of being capped by a
        // hardcoded 360 px above.
        return Math.min(Utils.getWindowWidth() - theme.scale(48), theme.scale(minWidth));
    }

    // Character cap for the checkbox row, which is short by design.
    private static String truncate(String text, int maxChars) {
        if (text == null) return "";
        return text.length() <= maxChars ? text : text.substring(0, maxChars - 1) + ELLIPSIS;
    }

    // Width-aware ellipsis: trims only when the rendered text would overflow
    // maxWidthPx, using the theme's actual measured glyph width. Unlike the old
    // character-count cap, narrow glyphs (i, l, spaces) no longer get cut before
    // they run out of room.
    private String fit(String text, double maxWidthPx) {
        if (text == null || text.isEmpty()) return "";
        if (theme.textWidth(text) <= maxWidthPx) return text;

        double ellWidth = theme.textWidth(ELLIPSIS);
        int lo = 0, hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (theme.textWidth(text.substring(0, mid)) + ellWidth <= maxWidthPx) lo = mid;
            else hi = mid - 1;
        }
        return text.substring(0, lo) + ELLIPSIS;
    }
}
