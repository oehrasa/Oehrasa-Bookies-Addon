package com.AutoBookshelf.addon.modules.livemessage.notes;

import java.util.UUID;

public class NoteEntry {
    public String id;
    public String text;
    public String subtext;
    public boolean checked;
    public long createdAt;

    public NoteEntry() {
    }

    public NoteEntry(String text) {
        this.id = UUID.randomUUID().toString();
        this.text = text;
        this.checked = false;
        this.createdAt = System.currentTimeMillis();
    }
}
