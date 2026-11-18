// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps big book contents out of the bank file's item data: a book and quill can hold 100 pages
 * of 1024 characters, so a storage room of copies of one book would otherwise repeat the same
 * ~100 KB per copy. On save each book's content goes once into a "books" table, keyed by its
 * hash, and the items point to it; on load every copy gets the same shared content back.
 */
final class BookStore {
    static final String TABLE = "books";
    private static final String REF = "$book";
    private static final String[] COMPONENTS = {"minecraft:written_book_content", "minecraft:writable_book_content"};
    /**
     * Small books stay inline: a pointer and its table entry would cost more than they save.
     */
    private static final int MIN_LENGTH = 512;

    private BookStore() {
    }

    /**
     * The bank JSON with book contents moved into a table. Item data is shared with the live bank
     * and never changed in place, so only the objects on the way to a book are copied.
     */
    static JsonObject pack(JsonObject root) {
        Map<String, JsonElement> books = new LinkedHashMap<>();
        JsonObject packed = (JsonObject) pack(root, books);
        if (!books.isEmpty()) {
            JsonObject table = new JsonObject();
            books.forEach(table::add);
            packed.add(TABLE, table);
        }
        return packed;
    }

    /**
     * Shallow copies only: everything that didn't change is the same object as before.
     */
    private static JsonElement pack(JsonElement e, Map<String, JsonElement> books) {
        if (e instanceof JsonObject o) {
            JsonObject copy = null;
            for (Map.Entry<String, JsonElement> entry : o.entrySet()) {
                JsonElement value = entry.getValue();
                JsonElement next = isBook(entry.getKey()) ? packBook(value, books) : pack(value, books);
                if (next != value && copy == null) {
                    copy = new JsonObject();
                    for (Map.Entry<String, JsonElement> before : o.entrySet()) {
                        if (before.getKey().equals(entry.getKey())) break;
                        copy.add(before.getKey(), before.getValue());
                    }
                }
                if (copy != null) copy.add(entry.getKey(), next);
            }
            return copy != null ? copy : o;
        }
        if (e instanceof JsonArray a) {
            JsonArray copy = null;
            for (int i = 0; i < a.size(); i++) {
                JsonElement value = a.get(i);
                JsonElement next = pack(value, books);
                if (next != value && copy == null) {
                    copy = new JsonArray(a.size());
                    for (int j = 0; j < i; j++) copy.add(a.get(j));
                }
                if (copy != null) copy.add(next);
            }
            return copy != null ? copy : a;
        }
        return e;
    }

    private static JsonElement packBook(JsonElement content, Map<String, JsonElement> books) {
        if (!content.isJsonObject()) return content;
        String json = content.toString();
        if (json.length() < MIN_LENGTH) return content;
        String hash = sha1(json);
        books.putIfAbsent(hash, content);
        JsonObject ref = new JsonObject();
        ref.addProperty(REF, hash);
        return ref;
    }

    /**
     * Puts the book contents back in place, in a freshly read bank file. Copies share one object.
     */
    static void unpack(JsonObject root) {
        if (!(root.remove(TABLE) instanceof JsonObject table)) return;
        unpack(root, table);
    }

    private static void unpack(JsonElement e, JsonObject table) {
        if (e instanceof JsonObject o) {
            for (Map.Entry<String, JsonElement> entry : o.entrySet()) {
                JsonElement value = entry.getValue();
                if (isBook(entry.getKey()) && value instanceof JsonObject ref && ref.get(REF) instanceof JsonPrimitive hash) {
                    JsonElement content = table.get(hash.getAsString());
                    // A missing entry (hand-edited file) leaves the pointer, which the item codec
                    // then rejects: that one book can't be shown, but nothing else is lost.
                    if (content != null) entry.setValue(content);
                } else {
                    unpack(value, table);
                }
            }
        } else if (e instanceof JsonArray a) {
            for (JsonElement value : a) unpack(value, table);
        }
    }

    private static boolean isBook(String key) {
        for (String c : COMPONENTS) if (c.equals(key)) return true;
        return false;
    }

    private static String sha1(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
