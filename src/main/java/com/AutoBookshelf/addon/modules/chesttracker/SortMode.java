package com.AutoBookshelf.addon.modules.chesttracker;

public enum SortMode {
    COUNT_DESC("Count ↓"),
    COUNT_ASC("Count ↑"),
    NAME_ASC("Name A-Z"),
    NAME_DESC("Name Z-A"),
    DISTANCE("Distance");

    private final String displayName;

    SortMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public SortMode next() {
        SortMode[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }
}
