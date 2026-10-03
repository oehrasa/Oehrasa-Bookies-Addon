package com.AutoBookshelf.addon.modules.livemessage.notes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class NoteEntry {
    public String id;
    public String text;
    // Sub texts below the main line; each renders as one or more wrapped rows.
    public List<String> subtexts = new ArrayList<>();
    public boolean checked;
    public long createdAt;
    // Legacy single-sub line kept only so old JSON files migrate to subtexts on load.
    public String subtext;

    public NoteEntry() {
    }

    public NoteEntry(String text) {
        this.id = UUID.randomUUID().toString();
        this.text = text;
        this.checked = false;
        this.createdAt = System.currentTimeMillis();
    }
}