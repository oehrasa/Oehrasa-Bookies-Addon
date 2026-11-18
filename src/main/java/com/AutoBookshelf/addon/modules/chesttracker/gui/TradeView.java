// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker.gui;

import com.AutoBookshelf.addon.modules.chesttracker.TrackerServices;
import com.AutoBookshelf.addon.modules.chesttracker.data.EntityRecord;
import com.AutoBookshelf.addon.modules.chesttracker.data.ItemData;
import com.AutoBookshelf.addon.modules.chesttracker.data.MemoryBank;
import com.AutoBookshelf.addon.modules.chesttracker.data.TradeData;
import com.AutoBookshelf.addon.modules.chesttracker.search.ItemIndex;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Shared text and item helpers for the Trades screens (CLAUDE.md 4.11).
 */
final class TradeView {
    private TradeView() {
    }

    /**
     * An offer decoded for display, with what changed since the account's previous look.
     */
    record ShownOffer(TradeData.Offer offer, ItemStack buy1, ItemStack buy2, ItemStack sell,
                      @Nullable String change, int changeColor, String searchText) {
    }

    /**
     * "Librarian (Expert)", "\"Bob\" (Librarian, Expert)", "Wandering Trader".
     */
    static String label(EntityRecord r) {
        TradeData t = r.trades;
        String kind = t == null || t.profession == null ? TrackerScreen.label(withoutName(r)) : profession(t.profession);
        String level = t != null && t.profession != null && t.level > 0
            ? Component.translatable("merchant.level." + t.level).getString() : null;
        if (r.name != null) return "\"" + r.name + "\" (" + kind + (level != null ? ", " + level : "") + ")";
        return level != null ? kind + " (" + level + ")" : kind;
    }

    private static EntityRecord withoutName(EntityRecord r) {
        EntityRecord copy = new EntityRecord(r.uuid);
        copy.type = r.type;
        return copy;
    }

    /**
     * The profession's own display name from the registry (modded ones too), else its id.
     */
    static String profession(String id) {
        Minecraft client = Minecraft.getInstance();
        Identifier key = Identifier.tryParse(id);
        if (client.level != null && key != null) {
            VillagerProfession p = client.level.registryAccess().lookupOrThrow(Registries.VILLAGER_PROFESSION).getValue(key);
            if (p != null) return p.name().getString();
        }
        return key == null ? id : key.getPath();
    }

    /**
     * "on account Alt" when the shown offers are another account's (prices depend on the player).
     */
    static @Nullable String otherAccount(TradeData trades, TradeData.Snapshot s) {
        MemoryBank bank = TrackerServices.banks().bank();
        UUID owner = trades.accountOf(s);
        if (bank == null || owner == null || owner.equals(bank.currentPlayer)) return null;
        String name = bank.accounts.get(owner);
        return Component.translatable("chesttracker.other_account", name != null ? name : owner.toString().substring(0, 8)).getString();
    }

    /**
     * Decoded offers per snapshot; a new look at the villager is a new snapshot object.
     */
    private static final Map<TradeData.Snapshot, List<ShownOffer>> CACHE = new WeakHashMap<>();

    /**
     * Decoding and tooltips are slow; cached so typing in the search never re-decodes a trading hall.
     */
    static List<ShownOffer> offers(EntityRecord r, @Nullable TradeData.Snapshot s) {
        if (s == null) return List.of();
        return CACHE.computeIfAbsent(s, snapshot -> decodeOffers(r, snapshot));
    }

    private static List<ShownOffer> decodeOffers(EntityRecord r, TradeData.Snapshot s) {
        List<ShownOffer> out = new ArrayList<>();
        for (TradeData.Offer o : s.offers) {
            ItemStack buy1 = decode(o.buy1()), buy2 = decode(o.buy2()), sell = decode(o.sell());
            String change = null;
            int color = 0xFF9A9A9A;
            if (s.previous != null) {
                TradeData.Offer before = s.previous.stream().filter(p -> same(p, o)).findFirst().orElse(null);
                int was = before == null ? 0 : decode(before.buy1()).getCount();
                if (before == null) {
                    change = Component.translatable("chesttracker.trades.new").getString();
                    color = 0xFF8FE08F;
                } else if (buy1.getCount() > was) {
                    change = Component.translatable("chesttracker.trades.price_up", was).getString();
                    color = 0xFFE8B04A;
                } else if (buy1.getCount() < was) {
                    change = Component.translatable("chesttracker.trades.price_down", was).getString();
                    color = 0xFF8FE08F;
                }
            }
            String text = ItemIndex.searchText(sell) + "\n" + ItemIndex.searchText(buy1)
                + (buy2.isEmpty() ? "" : "\n" + ItemIndex.searchText(buy2));
            out.add(new ShownOffer(o, buy1, buy2, sell, change, color, text));
        }
        return out;
    }

    /**
     * Offers the account saw last time that are no longer there.
     */
    static int gone(@Nullable TradeData.Snapshot s) {
        if (s == null || s.previous == null) return 0;
        int n = 0;
        for (TradeData.Offer p : s.previous) if (s.offers.stream().noneMatch(o -> same(p, o))) n++;
        return n;
    }

    /**
     * Same offer (only the price may differ): same goods sold, paid with the same items.
     */
    private static boolean same(TradeData.Offer a, TradeData.Offer b) {
        return a.sell().equals(b.sell()) && Objects.equals(itemId(a.buy1()), itemId(b.buy1()))
            && Objects.equals(itemId(a.buy2()), itemId(b.buy2()));
    }

    private static @Nullable String itemId(@Nullable JsonElement json) {
        return json != null && json.isJsonObject() && json.getAsJsonObject().has("id") ? json.getAsJsonObject().get("id").getAsString() : null;
    }

    static ItemStack decode(@Nullable JsonElement json) {
        Minecraft client = Minecraft.getInstance();
        if (json == null || client.level == null) return ItemStack.EMPTY;
        return ItemData.decode(client.level.registryAccess().createSerializationContext(JsonOps.INSTANCE), json);
    }

    /**
     * "Enchanted Book (Mending)", "Iron Axe (Efficiency II)" rather than just the item name.
     */
    static String describe(ItemStack stack) {
        String name = stack.getHoverName().getString();
        // Books keep theirs as stored enchantments; tools and armour as enchantments.
        ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
        if (stored == null || stored.isEmpty()) stored = stack.get(DataComponents.ENCHANTMENTS);
        if (stored == null || stored.isEmpty()) return name;
        List<String> enchants = new ArrayList<>();
        for (Object2IntMap.Entry<Holder<Enchantment>> e : stored.entrySet()) {
            enchants.add(Enchantment.getFullname(e.getKey(), e.getIntValue()).getString());
        }
        return name + " (" + String.join(", ", enchants) + ")";
    }

    private static final Identifier TRADE_ARROW = Identifier.withDefaultNamespace("container/villager/trade_arrow");
    private static final Identifier TRADE_ARROW_OUT_OF_STOCK = Identifier.withDefaultNamespace("container/villager/trade_arrow_out_of_stock");

    /**
     * The arrow between price and goods, as in the villager's own trade list: a red X when the
     * offer is out of stock. {@code y} is the top of the 16px item row.
     */
    static void tradeArrow(GuiGraphicsExtractor context, int x, int y, boolean outOfStock) {
        context.blitSprite(RenderPipelines.GUI_TEXTURED, outOfStock ? TRADE_ARROW_OUT_OF_STOCK : TRADE_ARROW,
            x, y + 4, 10, 9);
    }

    /**
     * "12 Emerald + 1 Book"
     */
    static String price(ShownOffer o) {
        String s = o.buy1().getCount() + " " + o.buy1().getHoverName().getString();
        if (!o.buy2().isEmpty()) s += " + " + o.buy2().getCount() + " " + o.buy2().getHoverName().getString();
        return s;
    }

    static String sold(ShownOffer o) {
        return (o.sell().getCount() > 1 ? o.sell().getCount() + " " : "") + describe(o.sell());
    }

    static String query(String raw) {
        String q = raw.trim().toLowerCase(Locale.ROOT);
        return q.startsWith("@") ? q.substring(1) : q;
    }
}
