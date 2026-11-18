// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker.track;

import com.AutoBookshelf.addon.mixin.accessor.MountScreenHandlerAccessor;
import com.AutoBookshelf.addon.modules.chesttracker.TrackerLog;
import com.AutoBookshelf.addon.modules.chesttracker.data.*;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/**
 * Binds a container screen to the entity or block it belongs to (CLAUDE.md 4.1 step 2) and
 * snapshots its contents (step 3). Screens that can't be bound with confidence are ignored, which
 * also keeps server menus and plugin GUIs out of memory (4.10).
 */
public final class ScreenBinder {
    private final InteractionTracker interactions;
    private final BankManager banks;
    private final PositionTracker positions;
    private final Predicate<Block> trackable;
    /**
     * Screens already looked at, so re-inits (window resize) don't bind twice.
     */
    private final Map<Screen, Boolean> seen = new WeakHashMap<>();

    public ScreenBinder(InteractionTracker interactions, BankManager banks, PositionTracker positions, Predicate<Block> trackable) {
        this.interactions = interactions;
        this.banks = banks;
        this.positions = positions;
        this.trackable = trackable == null ? b -> true : trackable;
    }

    /**
     * The open screen and the record it fills. {@code networkId} is -1 for blocks.
     */
    private static final class Bound {
        final AbstractContainerMenu handler;
        final ContainerRecord record;
        final int networkId;
        /**
         * Set once the server has sent the full contents; before that the slots are just empty.
         */
        boolean synced;

        Bound(AbstractContainerMenu handler, ContainerRecord record, int networkId) {
            this.handler = handler;
            this.record = record;
            this.networkId = networkId;
        }
    }

    private @Nullable Bound current;

    public void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            // Lectern screens aren't AbstractContainerScreens, but have a menu with the book in it.
            if (!(screen instanceof MenuAccess<?> handled) || seen.containsKey(screen)) return;
            seen.put(screen, Boolean.TRUE);
            MemoryBank bank = banks.bank();
            ClientLevel world = client.level;
            if (bank == null || world == null) return;
            AbstractContainerMenu handler = handled.getMenu();
            Bound bound = bind(client, world, bank, handler, screen.getTitle());
            TrackerLog.LOGGER.debug("Container screen {} ({}) opened, bound to {}", handler.containerId,
                screen.getClass().getSimpleName(), bound == null ? null : bound.record.key());
            if (bound == null) return;
            bank.markDirty();
            current = bound;
            ScreenEvents.remove(screen).register(closed -> {
                // Closed before the contents arrived (lag): the slots are empty placeholders, so
                // keep the previous snapshot rather than overwriting it with nothing.
                if (bound.synced) {
                    snapshot(client, bound);
                } else {
                    TrackerLog.LOGGER.info("Screen for {} closed before its contents arrived; kept the previous snapshot",
                        bound.record.key());
                }
                if (current == bound) current = null;
            });
        });
    }

    /**
     * Called after the server sends a container's full contents. Snapshotting here as well as on
     * close means a lost close event (lag, another mod swapping screens) can't drop a snapshot.
     */
    public void onInventorySync(int syncId) {
        Bound bound = current;
        if (bound == null || bound.handler.containerId != syncId) return;
        bound.synced = true;
        snapshot(Minecraft.getInstance(), bound);
    }

    /**
     * Whether the screen the tracker currently holds as bound is a container at the given block
     * position (the clicked half of a double chest counts as the bound half). The auto-open flow
     * uses this to confirm that an opened screen really belongs to its pending target before the
     * delayed close claims it.
     */
    public boolean currentScreenMatchesBlock(BlockPos pos) {
        Bound bound = this.current;
        if (bound == null || !(bound.record instanceof BlockRecord block)) {
            return false;
        }
        return block.pos.equals(pos) || (block.otherPositions != null && block.otherPositions.contains(pos));
    }

    private @Nullable Bound bind(Minecraft client, ClientLevel world, MemoryBank bank, AbstractContainerMenu handler, Component title) {
        // The player's own inventory has 5 crafting slots, the same as a hopper minecart.
        if (handler instanceof InventoryMenu) return null;
        // Merchant screens have 3 slots, like a furnace clicked just before; TradeBinder handles them.
        if (handler instanceof MerchantMenu) return null;

        // Horses, donkeys, mules, llamas: the handler holds the entity itself.
        if (handler instanceof AbstractMountInventoryMenu) {
            Entity mount = ((MountScreenHandlerAccessor) handler).entitytracker$getMount();
            if (!(mount instanceof AbstractChestedHorse donkey && donkey.hasChest())) return null;
            interactions.consumeEntity(mount.getUUID());
            return bindEntity(world, bank, handler, mount);
        }

        // Generic screens don't say whose they are. Match recent clicks in order (the server
        // answers them in order); an entity must also match the screen title. No click, no record.
        int containerSlots = containerSlotCount(handler);
        List<InteractionTracker.Pending> fresh = interactions.fresh();
        InteractionTracker.Pending onlySlotMatch = null;
        int slotMatches = 0;
        for (InteractionTracker.Pending p : fresh) {
            if (p.isEntity()) {
                Entity entity = world.getEntity(p.networkId());
                if (!matches(entity, containerSlots) || !entity.getUUID().equals(p.uuid())) continue;
                slotMatches++;
                onlySlotMatch = p;
                if (title.getString().equals(entity.getDisplayName().getString())) {
                    interactions.consume(p);
                    return bindEntity(world, bank, handler, entity);
                }
            } else {
                Bound bound = bindBlock(world, bank, handler, p.pos(), containerSlots, title);
                if (bound != null) {
                    interactions.consume(p);
                    return bound;
                }
            }
        }
        // No title matched (a server or ViaVersion may show a different title): only safe when a
        // single clicked entity fits the screen at all.
        if (slotMatches == 1) {
            Entity entity = world.getEntity(onlySlotMatch.networkId());
            TrackerLog.LOGGER.info("Screen title \"{}\" didn't match {}; bound it as the only candidate",
                title.getString(), entity.getDisplayName().getString());
            interactions.consume(onlySlotMatch);
            return bindEntity(world, bank, handler, entity);
        }
        if (slotMatches > 1) {
            TrackerLog.LOGGER.info("Screen \"{}\" fits {} clicked entities and matches none by name; not recorded",
                title.getString(), slotMatches);
        }
        return null;
    }

    private Bound bindEntity(ClientLevel world, MemoryBank bank, AbstractContainerMenu handler, Entity entity) {
        EntityRecord record = bank.getOrCreate(entity.getUUID());
        // Record type, name and position now, while the entity is certainly loaded.
        positions.update(record, entity, world, true);
        return new Bound(handler, record, entity.getId());
    }

    private @Nullable Bound bindBlock(ClientLevel world, MemoryBank bank, AbstractContainerMenu handler, BlockPos pos,
                                      int containerSlots, Component title) {
        BlockState state = world.getBlockState(pos);
        if (!trackable.test(state.getBlock())) return null;
        String dimension = world.dimension().identifier().toString();
        long now = System.currentTimeMillis();

        if (state.getBlock() instanceof EnderChestBlock) {
            if (containerSlots != 27) return null;
            if (bank.currentPlayer == null) return null;
            BlockRecord record = bank.getEnderChest(bank.currentPlayer);
            if (record == null) {
                record = new BlockRecord(true);
                record.owner = bank.currentPlayer;
                record.firstSeen = now;
            }
            record.ownerName = bank.accounts.get(bank.currentPlayer);
            fill(record, state, dimension, pos, null, title, now);
            bank.putBlock(record);
            return new Bound(handler, record, -1);
        }

        if (DisplayTracker.hasNoScreen(state.getBlock())) return null;
        BlockPos other = null;
        int expected;
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            other = pos.relative(ChestBlock.getConnectedDirection(state));
            expected = 54;
        } else if (state.getBlock() instanceof LecternBlock) {
            expected = 1; // the book
        } else if (world.getBlockEntity(pos) instanceof Container inventory) {
            expected = inventory.getContainerSize();
        } else {
            return null;
        }
        if (containerSlots != expected) return null;

        // A double chest is stored at its lower corner so either half finds the same record.
        BlockPos primary = other == null || pos.asLong() < other.asLong() ? pos : other;
        BlockPos second = other == null ? null : (primary.equals(pos) ? other : pos);
        BlockRecord existing = bank.getBlockAt(dimension, primary);
        BlockRecord existingOther = second == null ? null : bank.getBlockAt(dimension, second);
        BlockRecord record;
        if (existing != null && existing.pos.equals(primary)
            && existing.otherPositions.equals(second == null ? List.of() : List.of(second))) {
            record = existing;
        } else {
            // New, or the chest changed shape (single <-> double): start a record for the new shape.
            record = new BlockRecord(false);
            record.firstSeen = existing != null ? existing.firstSeen : now;
            record.dimension = dimension;
            record.pos = primary;
            if (second != null) record.otherPositions.add(second);
            if (existing != null) bank.replaceBlock(existing, record);
            if (existingOther != null && existingOther != existing) bank.replaceBlock(existingOther, record);
        }
        fill(record, state, dimension, primary, second, title, now);
        bank.putBlock(record);
        return new Bound(handler, record, -1);
    }

    private static void fill(BlockRecord record, BlockState state, String dimension, BlockPos pos, @Nullable BlockPos second,
                             Component title, long now) {
        record.type = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        record.dimension = dimension;
        record.pos = pos;
        record.x = pos.getX() + 0.5 + (second == null ? 0 : (second.getX() - pos.getX()) / 2.0);
        record.y = pos.getY() + 0.5;
        record.z = pos.getZ() + 0.5 + (second == null ? 0 : (second.getZ() - pos.getZ()) / 2.0);
        // Default titles ("Chest", "Large Chest") are translatable; a renamed container's title is not.
        record.name = title.getContents() instanceof TranslatableContents ? null : title.getString();
        record.status = RecordStatus.LOADED;
        record.lastSeen = now;
    }

    private static boolean matches(@Nullable Entity entity, int containerSlots) {
        return entity instanceof Container inventory && !entity.isRemoved() && inventory.getContainerSize() == containerSlots;
    }

    private static int containerSlotCount(AbstractContainerMenu handler) {
        int count = 0;
        for (Slot slot : handler.slots) if (isStored(slot)) count++;
        return count;
    }

    /**
     * A slot of the container itself: not the player's inventory, and not a computed result (the crafter's output).
     */
    private static boolean isStored(Slot slot) {
        return !(slot.container instanceof Inventory) && !(slot.container instanceof ResultContainer);
    }

    private void snapshot(Minecraft client, Bound bound) {
        MemoryBank bank = banks.bank();
        ClientLevel world = client.level;
        if (bank == null || world == null) return;
        var ops = world.registryAccess().createSerializationContext(JsonOps.INSTANCE);

        List<ContainerRecord.SlotItem> items = new ArrayList<>();
        int containerSlots = 0;
        for (Slot slot : bound.handler.slots) {
            if (!isStored(slot)) continue;
            int index = containerSlots++;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            var encoded = ItemStack.CODEC.encodeStart(ops, stack);
            JsonElement json = encoded.result().orElse(null);
            if (json == null) {
                TrackerLog.LOGGER.warn("Could not encode {} in slot {}: {}", stack, index,
                    encoded.error().map(Object::toString).orElse("?"));
                continue;
            }
            items.add(new ContainerRecord.SlotItem(index, json));
        }

        ContainerRecord record = bound.record;
        if (record instanceof EntityRecord entityRecord) {
            Entity entity = world.getEntity(bound.networkId);
            if (entity != null && entity.getUUID().equals(entityRecord.uuid)) positions.update(entityRecord, entity, world);
        }
        record.items = items;
        record.slotCount = containerSlots;
        record.flags.clear(); // a fresh look settles "chest removed", "container changed", portal
        record.lastOpened = System.currentTimeMillis();
        bank.markDirty();
        TrackerLog.LOGGER.debug("Snapshot of {} {}: {} stacks in {} slots", record.type, record.key(),
            items.size(), containerSlots);
    }
}
