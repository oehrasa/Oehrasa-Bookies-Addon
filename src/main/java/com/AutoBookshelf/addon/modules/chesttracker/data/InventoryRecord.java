// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker.data;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * "On <player>": the player's own inventory (armour and offhand included), built fresh for each
 * search so carried items, and shulkers and bundles in the inventory, are found too. Never stored.
 */
public final class InventoryRecord extends ContainerRecord {
    public static final String KEY = "on_me";

    private InventoryRecord() {
    }

    @Override
    public String key() {
        return KEY;
    }

    public static @Nullable InventoryRecord current() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return null;
        var ops = client.level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        InventoryRecord r = new InventoryRecord();
        r.type = "minecraft:player_head"; // for the icon
        r.name = client.player.getName().getString(); // shown as "On <name>"
        r.dimension = client.level.dimension().identifier().toString();
        r.x = client.player.getX();
        r.y = client.player.getY();
        r.z = client.player.getZ();
        long now = System.currentTimeMillis();
        r.lastSeen = now;
        // A new value each time, so the search cache never reuses an old inventory.
        r.lastOpened = System.nanoTime();
        Inventory inventory = client.player.getInventory();
        r.slotCount = inventory.getContainerSize();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            JsonElement json = ItemStack.CODEC.encodeStart(ops, stack).result().orElse(null);
            if (json != null) r.items.add(new SlotItem(i, json));
        }
        return r;
    }
}
