// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Saves each distinct item stack once. Storage rooms repeat the same stacks over and over (64
 * obsidian, full shulkers of gapples, a box of identical pickaxes): on a real bank, about six of
 * every seven stacks were exact copies of another. On save every stack goes once into a "stacks"
 * table and the slots point to it by number; on load every copy gets the same shared stack back.
 * Only the file is deduplicated: each copy keeps its own container, slot and count.
 */
final class StackStore {
    static final String TABLE = "stacks";
    private static final String REF = "$s";
    /**
     * Where items sit in the bank: container slots, and a trade offer's prices and goods.
     */
    private static final Set<String> KEYS = Set.of("stack", "buy1", "buy2", "sell");

    private StackStore() {
    }

    /**
     * The bank JSON with every stack replaced by its number in the table. Like {@link BookStore},
     * only the objects on the way to a stack are copied; the live bank's data is never changed.
     */
    static JsonObject pack(JsonObject root) {
        // Structural JSON keys avoid converting every stack to a full string only to deduplicate it.
        // Gson's JsonObject hashCode/equals are structural and keep that large temporary string
        // allocation out of the save path; this matters on banks with hundreds of thousands of stacks.
        Map<JsonElement, Integer> index = new HashMap<>();
        JsonArray table = new JsonArray();
        JsonObject packed = (JsonObject) pack(root, index, table);
        if (!table.isEmpty()) packed.add(TABLE, table);
        return packed;
    }

    private static JsonElement pack(JsonElement e, Map<JsonElement, Integer> index, JsonArray table) {
        if (e instanceof JsonObject o) {
            JsonObject copy = null;
            for (Map.Entry<String, JsonElement> entry : o.entrySet()) {
                JsonElement value = entry.getValue();
                JsonElement next = KEYS.contains(entry.getKey()) && value instanceof JsonObject stack
                    ? ref(stack, index, table) : pack(value, index, table);
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
                JsonElement next = pack(value, index, table);
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

    private static JsonObject ref(JsonObject stack, Map<JsonElement, Integer> index, JsonArray table) {
        int n = index.computeIfAbsent(stack, k -> {
            table.add(stack);
            return table.size() - 1;
        });
        JsonObject ref = new JsonObject();
        ref.addProperty(REF, n);
        return ref;
    }

    /**
     * Puts the stacks back in place, in a freshly read bank file. Copies share one object.
     */
    static void unpack(JsonObject root) {
        if (!(root.remove(TABLE) instanceof JsonArray table)) return;
        unpack(root, table);
    }

    private static void unpack(JsonElement e, JsonArray table) {
        if (e instanceof JsonObject o) {
            for (Map.Entry<String, JsonElement> entry : o.entrySet()) {
                JsonElement value = entry.getValue();
                if (KEYS.contains(entry.getKey()) && value instanceof JsonObject ref && ref.get(REF) instanceof JsonPrimitive n) {
                    int i = n.getAsInt();
                    // A number outside the table (hand-edited file) leaves the pointer, which reads
                    // as an unknown item: that one slot is lost, nothing else.
                    if (i >= 0 && i < table.size()) entry.setValue(table.get(i));
                } else {
                    unpack(value, table);
                }
            }
        } else if (e instanceof JsonArray a) {
            for (JsonElement value : a) unpack(value, table);
        }
    }
}
