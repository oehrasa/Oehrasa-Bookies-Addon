package com.AutoBookshelf.addon.modules.remote;

import com.google.gson.annotations.SerializedName;

public class BookEntry {
    public String group;   // series/group name, "Overlord"
    public String title;   // display volume and part, "Overlord V1P"
    public String file;    // relative path in the repo, "Overlord/Overlord V1P.txt"
    public String url;     // absolute download URL, (GitHub Pages or raw.githubusercontent.com)

    // Only present when the source pipeline actually knows the real book
    // title/author currently on Gutenberg-sourced entries only, since PDF
    // conversions never had a reliable source for this. Null/absent on
    // older and non-Gutenberg entries, so always go through displayTitle()
    // or hasAuthor()
    @SerializedName("book_title")
    public String bookTitle;
    public String author;
    public String source;   // "gutenberg"
    @SerializedName("gutenberg_id")
    public String gutenbergId;

    // The real title when known, otherwise the old filename-derived `title`.
    public String displayTitle() {
        return (bookTitle != null && !bookTitle.isEmpty()) ? bookTitle : title;
    }

    public boolean hasAuthor() {
        return author != null && !author.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof BookEntry other)) return false;
        return java.util.Objects.equals(file, other.file);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hashCode(file);
    }

    @Override
    public String toString() {
        String base = (group != null && !group.isEmpty() ? group + " / " : "") + displayTitle();
        return hasAuthor() ? base + " (by " + author + ")" : base;
    }
}
