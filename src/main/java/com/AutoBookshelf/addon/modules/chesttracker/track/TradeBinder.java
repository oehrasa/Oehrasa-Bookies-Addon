// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker.track;

import com.AutoBookshelf.addon.modules.chesttracker.Settings;
import com.AutoBookshelf.addon.modules.chesttracker.TrackerLog;
import com.AutoBookshelf.addon.modules.chesttracker.data.EntityRecord;
import com.AutoBookshelf.addon.modules.chesttracker.data.ItemData;
import com.AutoBookshelf.addon.modules.chesttracker.data.MemoryBank;
import com.AutoBookshelf.addon.modules.chesttracker.data.TradeData;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerDataHolder;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Binds a merchant screen to the villager or wandering trader the player just clicked and keeps
 * its offers (CLAUDE.md 4.11). Same rules as containers: no matching click, no record.
 */
public final class TradeBinder {
    private final InteractionTracker interactions;
    private final BankManager banks;
    private final PositionTracker positions;
    private final Map<Screen, Boolean> seen = new WeakHashMap<>();

    private static final class Bound {
        final MerchantMenu handler;
        final EntityRecord record;
        final UUID account;
        /**
         * What this account saw before this opening, for the change marks.
         */
        final @Nullable List<TradeData.Offer> previous;
        /**
         * Set once the offers have arrived; closing before that keeps the old ones.
         */
        boolean synced;

        Bound(MerchantMenu handler, EntityRecord record, UUID account, @Nullable List<TradeData.Offer> previous) {
            this.handler = handler;
            this.record = record;
            this.account = account;
            this.previous = previous;
        }
    }

    private @Nullable Bound current;

    public TradeBinder(InteractionTracker interactions, BankManager banks, PositionTracker positions) {
        this.interactions = interactions;
        this.banks = banks;
        this.positions = positions;
    }

    public void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof MerchantScreen merchantScreen) || seen.containsKey(screen)) return;
            seen.put(screen, Boolean.TRUE);
            MemoryBank bank = banks.bank();
            ClientLevel world = client.level;
            if (!Settings.trackTrades || bank == null || world == null || bank.currentPlayer == null) return;
            MerchantMenu handler = merchantScreen.getMenu();
            Entity merchant = find(world, screen.getTitle());
            TrackerLog.LOGGER.debug("Merchant screen {} opened, bound to {}", handler.containerId,
                merchant == null ? null : merchant.getUUID());
            if (merchant == null) return;

            EntityRecord record = bank.getOrCreate(merchant.getUUID());
            if (record.trades == null) record.trades = new TradeData();
            positions.update(record, merchant, world, true);
            readVillager(record.trades, merchant);
            TradeData.Snapshot old = record.trades.byAccount.get(bank.currentPlayer);
            // Offers saved by a newer version can't be compared (or stored next to this version's): no change marks then.
            boolean comparable = old != null && old.dataVersion == ItemData.CURRENT;
            Bound bound = new Bound(handler, record, bank.currentPlayer, comparable ? old.offers : null);
            current = bound;
            bank.markDirty();
            ScreenEvents.remove(screen).register(closed -> {
                // Uses go up while trading; the client's copy of the offers follows them.
                if (bound.synced) snapshot(bound, handler.getOffers());
                if (current == bound) current = null;
            });
        });
    }

    /**
     * The offers arrived (right after the screen opened, and again after a restock or level-up).
     */
    public void onOffers(int syncId, List<MerchantOffer> offers) {
        Bound bound = current;
        if (bound == null || bound.handler.containerId != syncId) return;
        bound.synced = true;
        snapshot(bound, offers);
    }

    /**
     * The oldest fresh click on a merchant whose name is the screen title, else the only clicked
     * merchant. Several clicked and none named like the screen: not recorded.
     */
    private @Nullable Entity find(ClientLevel world, Component title) {
        InteractionTracker.Pending only = null;
        int candidates = 0;
        for (InteractionTracker.Pending p : interactions.fresh()) {
            if (!p.isEntity()) continue;
            Entity entity = world.getEntity(p.networkId());
            if (!(entity instanceof AbstractVillager) || entity.isRemoved() || !entity.getUUID().equals(p.uuid())) continue;
            if (title.getString().equals(entity.getDisplayName().getString())) {
                interactions.consume(p);
                return entity;
            }
            candidates++;
            only = p;
        }
        if (candidates == 1) {
            interactions.consume(only);
            return world.getEntity(only.networkId());
        }
        return null;
    }

    /**
     * Profession and level as the client sees them; wandering traders have neither.
     */
    static void readVillager(TradeData trades, Entity entity) {
        if (entity instanceof VillagerDataHolder container) {
            VillagerData data = container.getVillagerData();
            trades.profession = data.profession().unwrapKey().map(k -> k.identifier().toString()).orElse(null);
            trades.villagerType = data.type().unwrapKey().map(k -> k.identifier().toString()).orElse(null);
            trades.level = data.level();
        } else {
            trades.profession = null;
            trades.villagerType = null;
            trades.level = 0;
        }
    }

    private void snapshot(Bound bound, List<MerchantOffer> offers) {
        Minecraft client = Minecraft.getInstance();
        MemoryBank bank = banks.bank();
        if (bank == null || client.level == null) return;
        RegistryOps<JsonElement> ops = client.level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        List<TradeData.Offer> out = new ArrayList<>();
        for (MerchantOffer offer : offers) {
            JsonElement buy1 = encode(ops, offer.getCostA());
            JsonElement sell = encode(ops, offer.getResult());
            if (buy1 == null || sell == null) continue;
            ItemStack second = offer.getCostB();
            out.add(new TradeData.Offer(buy1, offer.getBaseCostA().getCount(),
                second.isEmpty() ? null : encode(ops, second), sell,
                offer.getUses(), offer.getMaxUses(), offer.getXp()));
        }
        TradeData trades = bound.record.trades;
        TradeData.Snapshot s = new TradeData.Snapshot();
        s.time = System.currentTimeMillis();
        s.level = trades.level;
        s.offers = out;
        s.previous = bound.previous;
        trades.byAccount.put(bound.account, s);
        bound.record.lastOpened = s.time;
        bound.record.flags.clear(); // a fresh look settles "levelled up", "profession changed"
        bank.markDirty();
        TrackerLog.LOGGER.debug("Offers of {} {}: {}", bound.record.type, bound.record.key(), out.size());
    }

    private static @Nullable JsonElement encode(RegistryOps<JsonElement> ops, ItemStack stack) {
        if (stack.isEmpty()) return null;
        return ItemStack.CODEC.encodeStart(ops, stack).result().orElse(null);
    }
}
