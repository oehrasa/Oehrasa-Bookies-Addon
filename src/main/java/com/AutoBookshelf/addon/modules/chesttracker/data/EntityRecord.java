// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * An entity container (donkey, chest minecart, chest boat...). Identity is the UUID; position is
 * only "where it should be".
 */
public final class EntityRecord extends ContainerRecord {
    public final UUID uuid;
    public int homeChunkX, homeChunkZ;
    /**
     * Set when a likely-destroyed entity was seen alive again.
     */
    public boolean reappeared;
    public int timesRehomed;
    /**
     * Set for villagers and wandering traders; null for containers.
     */
    public @Nullable TradeData trades;

    public EntityRecord(UUID uuid) {
        this.uuid = uuid;
    }

    /**
     * A villager or wandering trader: shown in the Trades screen, not in storage.
     */
    public boolean isTrader() {
        return trades != null;
    }

    @Override
    public String key() {
        return uuid.toString();
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        writeCommon(o);
        JsonArray home = new JsonArray();
        home.add(homeChunkX);
        home.add(homeChunkZ);
        o.add("homeChunk", home);
        if (reappeared) o.addProperty("reappeared", true);
        if (timesRehomed != 0) o.addProperty("timesRehomed", timesRehomed);
        if (trades != null) o.add("trades", trades.toJson());
        return o;
    }

    public static EntityRecord fromJson(UUID uuid, JsonObject o) {
        EntityRecord r = new EntityRecord(uuid);
        r.readCommon(o);
        JsonArray home = o.getAsJsonArray("homeChunk");
        r.homeChunkX = home.get(0).getAsInt();
        r.homeChunkZ = home.get(1).getAsInt();
        r.reappeared = o.has("reappeared") && o.get("reappeared").getAsBoolean();
        r.timesRehomed = o.has("timesRehomed") ? o.get("timesRehomed").getAsInt() : 0;
        if (o.has("trades")) r.trades = TradeData.fromJson(o.getAsJsonObject("trades"));
        return r;
    }
}
