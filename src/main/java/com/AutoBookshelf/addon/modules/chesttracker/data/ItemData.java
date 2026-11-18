// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker.data;

import com.AutoBookshelf.addon.modules.chesttracker.TrackerLog;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Saved items across Minecraft versions. Items are kept as the game's own JSON, which changes
 * between versions, so every saved item carries the data version of the game that wrote it:
 * older items are upgraded with the game's own data fixers when a bank is loaded, and newer
 * items are never rewritten (they show by type and count if this version cannot read them).
 */
public final class ItemData {
    private ItemData() {
    }

    /**
     * This game's data version.
     */
    public static final int CURRENT = SharedConstants.getCurrentVersion().dataVersion().version();
    /**
     * Items saved before data versions were stored: the first builds were for 1.21.11.
     */
    public static final int LEGACY = 4671;

    /**
     * An item saved by an older version, brought up to this one; unchanged if the fixers fail.
     */
    public static JsonElement upgrade(JsonElement stack, int fromVersion) {
        return upgrade(JsonOps.INSTANCE, stack, fromVersion);
    }

    /**
     * The same for an item in any format (NBT from a Chest Tracker file, JSON from a bank).
     */
    public static <T> T upgrade(DynamicOps<T> ops, T stack, int fromVersion) {
        if (fromVersion <= 0 || fromVersion >= CURRENT) return stack;
        try {
            return DataFixers.getDataFixer()
                .update(References.ITEM_STACK, new Dynamic<>(ops, stack), fromVersion, CURRENT)
                .getValue();
        } catch (RuntimeException e) {
            TrackerLog.LOGGER.warn("Could not upgrade a saved item from data version {}: {}", fromVersion, e.toString());
            return stack;
        }
    }

    /**
     * The item, or, when this version cannot read it (an item from a newer version), at least its
     * type and count so it still shows and can be searched. Empty only if even the type is unknown.
     */
    public static ItemStack decode(DynamicOps<JsonElement> ops, JsonElement json) {
        ItemStack stack = ItemStack.CODEC.parse(ops, json).result().orElse(ItemStack.EMPTY);
        if (!stack.isEmpty() || !(json instanceof JsonObject o) || !o.has("id")) return stack;
        Identifier id = Identifier.tryParse(o.get("id").getAsString());
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return ItemStack.EMPTY;
        Item item = BuiltInRegistries.ITEM.getValue(id);
        int count = o.has("count") ? Math.max(1, o.get("count").getAsInt()) : 1;
        return new ItemStack(item, Math.min(count, item.getDefaultMaxStackSize()));
    }
}
