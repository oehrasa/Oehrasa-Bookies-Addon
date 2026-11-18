// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A block container (chest, barrel, furnace...), keyed by dimension + position. A double chest
 * is one record at one half's position with the other half in {@link #otherPositions}. An
 * ender chest is a {@link #global} record (the same everywhere), one per account.
 */
public final class BlockRecord extends ContainerRecord {
    public static final String ENDER_CHEST_KEY = "ender_chest";

    public final boolean global;
    /**
     * For the ender chest: whose it is. Each account has its own; all are searchable.
     */
    public @Nullable UUID owner;
    public @Nullable String ownerName;
    public BlockPos pos;
    public List<BlockPos> otherPositions = new ArrayList<>();

    public BlockRecord(boolean global) {
        this.global = global;
    }

    public static String keyFor(String dimension, BlockPos pos) {
        return dimension + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    public static String enderKey(UUID owner) {
        return ENDER_CHEST_KEY + ":" + owner;
    }

    @Override
    public String key() {
        if (global) return owner == null ? ENDER_CHEST_KEY : enderKey(owner);
        return keyFor(dimension, pos);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        writeCommon(o);
        if (global) o.addProperty("global", true);
        if (owner != null) o.addProperty("owner", owner.toString());
        if (ownerName != null) o.addProperty("ownerName", ownerName);
        JsonArray block = new JsonArray();
        block.add(pos.getX());
        block.add(pos.getY());
        block.add(pos.getZ());
        o.add("block", block);
        if (!otherPositions.isEmpty()) {
            JsonArray others = new JsonArray();
            for (BlockPos p : otherPositions) {
                JsonArray a = new JsonArray();
                a.add(p.getX());
                a.add(p.getY());
                a.add(p.getZ());
                others.add(a);
            }
            o.add("otherPositions", others);
        }
        return o;
    }

    public static BlockRecord fromJson(JsonObject o) {
        BlockRecord r = new BlockRecord(o.has("global") && o.get("global").getAsBoolean());
        r.readCommon(o);
        if (o.has("owner")) r.owner = UUID.fromString(o.get("owner").getAsString());
        if (o.has("ownerName")) r.ownerName = o.get("ownerName").getAsString();
        r.pos = readPos(o.getAsJsonArray("block"));
        if (o.has("otherPositions")) {
            for (JsonElement e : o.getAsJsonArray("otherPositions")) r.otherPositions.add(readPos(e.getAsJsonArray()));
        }
        return r;
    }

    private static BlockPos readPos(JsonArray a) {
        return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
    }
}
