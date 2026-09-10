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
    private static final int GROUP_LABEL_MAX_CHARS = 50;
    private static final int AUTHOR_LABEL_MAX_CHARS = 32;
    // Leading spaces on the author line
    private static final String AUTHOR_INDENT = "    ";

    private final List<BookEntry> allEntries;
    private final Consumer<List<BookEntry>> onConfirm;
    private final Map<BookEntry, WCheckbox> checkboxes = new LinkedHashMap<>();
    private final List<BookEntry> visible = new ArrayList<>();

    private WVerticalList listContainer;
    private WTextBox search;

    public RemoteBookSelectScreen(List<BookEntry> entries, Consumer<List<BookEntry>> onConfirm) {
        super(GuiThemes.get(), "Select Books to Import");
        this.allEntries = entries;
        this.onConfirm = onConfirm;
    }

    @Override
    public void initWidgets() {
        search = add(theme.textBox("")).minWidth(320).expandX().widget();
        search.setFocused(true);
        search.action = () -> refreshList(search.get());

        listContainer = add(theme.verticalList()).expandX().widget();
        refreshList("");

        var footer = add(theme.horizontalList()).expandX().widget();

        WButton selectAllBtn = footer.add(theme.button("Select All Visible")).widget();
        selectAllBtn.action = () -> {
            for (BookEntry entry : visible) {
                WCheckbox cb = checkboxes.get(entry);
                if (cb != null) cb.checked = true;
            }
        };

        WButton importBtn = footer.add(theme.button("Import Selected")).widget();
        importBtn.action = () -> {
            List<BookEntry> selected = new ArrayList<>();
            for (var e : checkboxes.entrySet()) {
                if (e.getValue().checked) selected.add(e.getKey());
            }
            if (!selected.isEmpty()) {
                onConfirm.accept(selected);
                onClose();
            }
        };

        WButton cancelBtn = footer.add(theme.button("Cancel")).widget();
        cancelBtn.action = this::onClose;
    }

    private void refreshList(String filterRaw) {
        listContainer.clear();
        visible.clear(); // reset tracked-visible set for this filter pass
        String filter = filterRaw == null ? "" : filterRaw.toLowerCase(Locale.ROOT);

        Map<String, List<BookEntry>> byGroup = new LinkedHashMap<>();
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
            visible.add(entry); // this entry passed the filter, so it's currently rendered
            byGroup.computeIfAbsent(entry.group == null || entry.group.isEmpty() ? "Ungrouped" : entry.group,
                g -> new ArrayList<>()).add(entry);
        }

        if (byGroup.isEmpty()) {
            listContainer.add(theme.label("No matches."));
            return;
        }

        for (var groupEntry : byGroup.entrySet()) {
            List<BookEntry> books = groupEntry.getValue();

            String groupLabel = truncate(groupEntry.getKey(), GROUP_LABEL_MAX_CHARS)
                + " (" + books.size() + (books.size() == 1 ? " book)" : " books)");
            // horizontalSeparator(text) draws divider lines with the group name inline
            listContainer.add(theme.horizontalSeparator(groupLabel)).expandX();

            for (int i = 0; i < books.size(); i++) {
                BookEntry entry = books.get(i);
                var row = listContainer.add(theme.horizontalList()).expandX().widget();

                WCheckbox checkbox = checkboxes.computeIfAbsent(entry, e -> theme.checkbox(false));
                row.add(checkbox);

                // Title and author stack as two separate lines next to the
                // checkbox, instead of being crammed onto one line with
                // "by Author" appended right after the title separated by
                // only a single space.
                WVerticalList textColumn = row.add(theme.verticalList()).expandX().widget();
                textColumn.add(theme.label(truncate(entry.displayTitle(), TITLE_LABEL_MAX_CHARS))).expandX();
                if (entry.hasAuthor()) {
                    textColumn.add(theme.label(AUTHOR_INDENT + "by " + truncate(entry.author, AUTHOR_LABEL_MAX_CHARS))).expandX();
                }

                if (i < books.size() - 1) {
                    listContainer.add(theme.horizontalSeparator()).expandX();
                }
            }
        }
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
