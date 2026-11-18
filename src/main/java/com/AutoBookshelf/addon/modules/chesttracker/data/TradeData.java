// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

/**
 * What a villager or wandering trader offers, attached to its entity record so it is followed,
 * re-homed and judged missing exactly like a donkey. Prices depend on the player's reputation,
 * so the offers are kept per account.
 */
public final class TradeData {
    /**
     * Profession registry id; null for a wandering trader.
     */
    public @Nullable String profession;
    /**
     * 1 (Novice) to 5 (Master); 0 when it has no levels (wandering trader).
     */
    public int level;
    /**
     * Biome type (plains, taiga...), only for the icon/tooltip.
     */
    public @Nullable String villagerType;
    public final Map<UUID, Snapshot> byAccount = new LinkedHashMap<>();

    /**
     * One look at the offers. {@code previous} is what the same account saw the time before,
     * so the screen can mark prices that went up or down and offers that are new or gone.
     */
    public static final class Snapshot {
        public long time;
        public int level;
        public List<Offer> offers = new ArrayList<>();
        public @Nullable List<Offer> previous;
        /**
         * The game version that wrote these offers' items (see {@link ItemData}).
         */
        public int dataVersion = ItemData.CURRENT;
    }

    /**
     * One offer. Items are raw JSON (like container snapshots) so unknown data survives;
     * {@code buy1} is the price as shown to that account (demand and discounts applied),
     * {@code baseCount} the price before them.
     */
    public record Offer(JsonElement buy1, int baseCount, @Nullable JsonElement buy2, JsonElement sell,
                        int uses, int maxUses, int xp) {
        public boolean outOfStock() {
            return uses >= maxUses;
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.add("buy1", buy1);
            o.addProperty("baseCount", baseCount);
            if (buy2 != null) o.add("buy2", buy2);
            o.add("sell", sell);
            o.addProperty("uses", uses);
            o.addProperty("maxUses", maxUses);
            o.addProperty("xp", xp);
            return o;
        }

        Offer upgraded(int fromVersion) {
            return new Offer(ItemData.upgrade(buy1, fromVersion), baseCount,
                buy2 == null ? null : ItemData.upgrade(buy2, fromVersion), ItemData.upgrade(sell, fromVersion),
                uses, maxUses, xp);
        }

        static Offer fromJson(JsonObject o) {
            return new Offer(o.get("buy1"), o.has("baseCount") ? o.get("baseCount").getAsInt() : 0,
                o.has("buy2") ? o.get("buy2") : null, o.get("sell"),
                o.has("uses") ? o.get("uses").getAsInt() : 0, o.has("maxUses") ? o.get("maxUses").getAsInt() : 1,
                o.has("xp") ? o.get("xp").getAsInt() : 0);
        }
    }

    /**
     * This account's offers, else the newest any account saw (prices may differ for this one).
     */
    public @Nullable Snapshot snapshotFor(@Nullable UUID account) {
        Snapshot own = account == null ? null : byAccount.get(account);
        if (own != null) return own;
        Snapshot newest = null;
        for (Snapshot s : byAccount.values()) if (newest == null || s.time > newest.time) newest = s;
        return newest;
    }

    /**
     * Which account a snapshot belongs to.
     */
    public @Nullable UUID accountOf(Snapshot snapshot) {
        for (Map.Entry<UUID, Snapshot> e : byAccount.entrySet()) if (e.getValue() == snapshot) return e.getKey();
        return null;
    }

    /**
     * Brings offers saved by an older version up to this one. Returns how many snapshots were upgraded.
     */
    int upgradeItems() {
        int n = 0;
        for (Snapshot s : byAccount.values()) {
            if (s.dataVersion >= ItemData.CURRENT) continue;
            int from = s.dataVersion;
            s.offers = s.offers.stream().map(offer -> offer.upgraded(from)).collect(Collectors.toCollection(ArrayList::new));
            if (s.previous != null)
                s.previous = s.previous.stream().map(offer -> offer.upgraded(from)).collect(Collectors.toCollection(ArrayList::new));
            s.dataVersion = ItemData.CURRENT;
            n++;
        }
        return n;
    }

    /**
     * Snapshots saved by a newer version than this one; kept exactly as they are.
     */
    long newerSnapshots() {
        return byAccount.values().stream().filter(s -> s.dataVersion > ItemData.CURRENT).count();
    }

    JsonObject toJson() {
        JsonObject o = new JsonObject();
        if (profession != null) o.addProperty("profession", profession);
        o.addProperty("level", level);
        if (villagerType != null) o.addProperty("villagerType", villagerType);
        JsonObject accounts = new JsonObject();
        for (Map.Entry<UUID, Snapshot> e : byAccount.entrySet()) {
            Snapshot s = e.getValue();
            JsonObject so = new JsonObject();
            so.addProperty("time", s.time);
            so.addProperty("level", s.level);
            so.addProperty("dataVersion", s.dataVersion);
            so.add("offers", offersToJson(s.offers));
            if (s.previous != null) so.add("previous", offersToJson(s.previous));
            accounts.add(e.getKey().toString(), so);
        }
        o.add("accounts", accounts);
        return o;
    }

    static TradeData fromJson(JsonObject o) {
        TradeData t = new TradeData();
        t.profession = o.has("profession") ? o.get("profession").getAsString() : null;
        t.level = o.has("level") ? o.get("level").getAsInt() : 0;
        t.villagerType = o.has("villagerType") ? o.get("villagerType").getAsString() : null;
        if (o.has("accounts")) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("accounts").entrySet()) {
                JsonObject so = e.getValue().getAsJsonObject();
                Snapshot s = new Snapshot();
                s.time = so.get("time").getAsLong();
                s.level = so.has("level") ? so.get("level").getAsInt() : 0;
                s.dataVersion = so.has("dataVersion") ? so.get("dataVersion").getAsInt() : ItemData.LEGACY;
                s.offers = offersFromJson(so.getAsJsonArray("offers"));
                if (so.has("previous")) s.previous = offersFromJson(so.getAsJsonArray("previous"));
                t.byAccount.put(UUID.fromString(e.getKey()), s);
            }
        }
        return t;
    }

    private static JsonArray offersToJson(List<Offer> offers) {
        JsonArray a = new JsonArray();
        for (Offer offer : offers) a.add(offer.toJson());
        return a;
    }

    private static List<Offer> offersFromJson(@Nullable JsonArray a) {
        List<Offer> out = new ArrayList<>();
        if (a != null) for (JsonElement e : a) out.add(Offer.fromJson(e.getAsJsonObject()));
        return out;
    }
}
