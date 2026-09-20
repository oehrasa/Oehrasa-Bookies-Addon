package com.AutoBookshelf.addon.modules.remote;

import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;

import java.util.*;
import java.util.function.Consumer;

// Lets the player search/browse the remote manifest
// and pick one or more books to queue for import.
public class RemoteBookSelectScreen extends WindowScreen {
    private static final int TITLE_LABEL_MAX_CHARS = 60;
    private static final int FILE_LABEL_MAX_CHARS = 48;
    private static final int AUTHOR_LABEL_MAX_CHARS = 32;
    // Leading spaces on the author line
    private static final String AUTHOR_INDENT = "    ";

    private final List<BookEntry> allEntries;
    private final Consumer<List<BookEntry>> onConfirm;
    private final Map<BookEntry, WCheckbox> checkboxes = new LinkedHashMap<>();
    private final List<BookEntry> visible = new ArrayList<>();

    private WVerticalList listContainer;
    private WTextBox search;
    // Set the moment the import button runs, so the onClosed callback can tell a
    // confirmed import apart from an actual cancel and only fire onCancel for the latter.
    private boolean confirmed = false;

    public RemoteBookSelectScreen(List<BookEntry> entries, Consumer<List<BookEntry>> onConfirm, Runnable onCancel) {
        super(GuiThemes.get(), "Select Books to Import");
        this.allEntries = entries;
        this.onConfirm = onConfirm;
        onClosed(() -> {
            if (!confirmed) onCancel.run();
        });
    }

    @Override
    public void initWidgets() {
        search = add(theme.textBox("")).minWidth(320).expandX().widget();
        search.setFocused(true);
        search.action = () -> refreshList(search.get());

        var header = add(theme.horizontalList()).expandX().widget();

        WButton selectAllBtn = header.add(theme.button("Select All Visible")).widget();
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

        WButton importBtn = header.add(theme.button("Import Selected")).widget();
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

        WButton cancelBtn = header.add(theme.button("Cancel")).widget();
        cancelBtn.action = this::onClose;

        listContainer = add(theme.verticalList()).expandX().widget();
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

        if (matched.isEmpty()) {
            listContainer.add(theme.label("No matches."));
            return;
        }

        // Each book renders as its own titled block
        // Layout is: title, separator strip, then a row with the checkbox
        // sitting on the .txt filename, and the author at the bottom.
        for (int i = 0; i < matched.size(); i++) {
            BookEntry entry = matched.get(i);
            visible.add(entry); // this entry passed the filter, so it's currently rendered

            listContainer.add(theme.label("(" + (i + 1) + ") " + truncate(entry.displayTitle(), TITLE_LABEL_MAX_CHARS))).expandX();
            listContainer.add(theme.horizontalSeparator()).expandX();

            var fileRow = listContainer.add(theme.horizontalList()).expandX().widget();
            WCheckbox checkbox = checkboxes.computeIfAbsent(entry, e -> theme.checkbox(false));
            fileRow.add(checkbox);
            fileRow.add(theme.label(truncate(fileNameOf(entry), FILE_LABEL_MAX_CHARS)));

            if (entry.hasAuthor()) {
                listContainer.add(theme.label(AUTHOR_INDENT + "by " + truncate(entry.author, AUTHOR_LABEL_MAX_CHARS))).expandX();
            }

            if (i < matched.size() - 1) {
                listContainer.add(theme.horizontalSeparator()).expandX().padTop(12).padBottom(12);
            }
        }
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

    // Caps displayed text length
    private static String truncate(String text, int maxChars) {
        if (text == null) return "";
        return text.length() <= maxChars ? text : text.substring(0, maxChars - 1) + "…";
    }
}