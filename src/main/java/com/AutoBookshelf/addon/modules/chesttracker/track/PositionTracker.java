// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker.track;

import com.AutoBookshelf.addon.modules.chesttracker.Settings;
import com.AutoBookshelf.addon.modules.chesttracker.TrackerLog;
import com.AutoBookshelf.addon.modules.chesttracker.data.*;
import com.AutoBookshelf.addon.modules.chesttracker.gui.Messages;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Follows remembered entities while they are loaded (CLAUDE.md 4.1 step 4), recognises them on
 * sight (step 7), and decides what a disappearance means (step 5). Never deletes a record.
 */
public final class PositionTracker {
    private final BankManager banks;
    private final InteractionTracker clock;
    /**
     * Remembered entities that just vanished near the player, waiting to see if items drop.
     */
    private final List<Vanished> vanished = new ArrayList<>();

    /**
     * {@code dropIds}: item ids that would prove it was broken (its contents, its own item).
     */
    private record Vanished(UUID uuid, Vec3 pos, long tick, Set<Item> dropItems) {
    }

    public PositionTracker(BankManager banks, InteractionTracker clock) {
        this.banks = banks;
        this.clock = clock;
    }

    public void register() {
        ClientEntityEvents.ENTITY_LOAD.register(this::onLoad);
        ClientEntityEvents.ENTITY_UNLOAD.register(this::onUnload);
    }

    /**
     * Refreshes a record from the live entity, re-homing it if it left its home area. Returns
     * true when something worth saving changed (lastSeen alone is not).
     */
    public boolean update(EntityRecord record, Entity entity, ClientLevel world) {
        return update(record, entity, world, false);
    }

    /**
     * @param reappearing true when the entity has just come into view or been opened. Only then
     *                    does a trashed record count as seen again; a trashed entity that never
     *                    left view stays in the trash.
     */
    public boolean update(EntityRecord record, Entity entity, ClientLevel world, boolean reappearing) {
        long now = System.currentTimeMillis();
        String dimension = world.dimension().identifier().toString();
        int chunkX = SectionPos.blockToSectionCoord(entity.getBlockX());
        int chunkZ = SectionPos.blockToSectionCoord(entity.getBlockZ());
        boolean isNew = record.type == null;
        String name = entity.hasCustomName() ? entity.getCustomName().getString() : null;
        // Right after spawning, the custom name hasn't arrived from the server yet; keep the old one.
        if (name == null && reappearing) name = record.name;
        boolean changed = isNew || record.status != RecordStatus.LOADED || !dimension.equals(record.dimension)
            || !Objects.equals(name, record.name)
            || Math.abs(entity.getX() - record.x) + Math.abs(entity.getY() - record.y) + Math.abs(entity.getZ() - record.z) >= 1.0;

        record.type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        record.name = name;
        if (isNew) {
            record.firstSeen = now;
            record.homeChunkX = chunkX;
            record.homeChunkZ = chunkZ;
        } else if (!dimension.equals(record.dimension) || !isHome(record, chunkX, chunkZ)) {
            TrackerLog.LOGGER.info("Re-homed {} {} from chunk {},{} ({}) to {},{} ({})", record.type,
                record.uuid, record.homeChunkX, record.homeChunkZ, record.dimension, chunkX, chunkZ, dimension);
            record.homeChunkX = chunkX;
            record.homeChunkZ = chunkZ;
            record.timesRehomed++;
        }
        if (record.status == RecordStatus.LIKELY_DESTROYED) record.reappeared = true;
        // Traders are trashed to hide them (a wandering trader that moved on), not because they
        // seemed destroyed, so seeing one again doesn't bring it back.
        if (reappearing && record.isTrashed() && !record.isTrader()) {
            // Marked destroyed but seen alive: it wasn't destroyed after all (CLAUDE.md 4.1 step 5).
            record.trashedAt = 0;
            record.reappeared = true;
            changed = true;
            TrackerLog.LOGGER.info("{} {} reappeared, restored from trash", record.type, record.uuid);
            Messages.chat(Component.translatable("chesttracker.reappeared",
                name != null ? "\"" + name + "\"" : entity.getType().getDescription().getString()));
        }
        record.dimension = dimension;
        record.x = entity.getX();
        record.y = entity.getY();
        record.z = entity.getZ();
        record.status = RecordStatus.LOADED;
        record.lastSeen = now;
        return changed;
    }

    private static boolean isHome(EntityRecord record, int chunkX, int chunkZ) {
        int r = homeRadius(record);
        return Math.abs(chunkX - record.homeChunkX) <= r && Math.abs(chunkZ - record.homeChunkZ) <= r;
    }

    private void onLoad(Entity entity, ClientLevel world) {
        MemoryBank bank = banks.bank();
        if (bank == null) return;
        if (entity instanceof ItemEntity) return; // drops are matched in tick(), once their item data arrives
        EntityRecord record = bank.get(entity.getUUID());
        if (record == null) return;
        // Recognised on sight, before opening (CLAUDE.md test 22).
        TrackerLog.LOGGER.info("Recognised {} {}{}", record.type, record.uuid,
            record.name != null ? " \"" + record.name + "\"" : "");
        update(record, entity, world, true);
        bank.markDirty();
    }

    private void onUnload(Entity entity, ClientLevel world) {
        MemoryBank bank = banks.bank();
        if (bank == null) return;
        EntityRecord record = bank.get(entity.getUUID());
        if (record == null) return;
        update(record, entity, world);
        Minecraft client = Minecraft.getInstance();
        if (entity instanceof LivingEntity living && living.isDeadOrDying()) {
            record.status = RecordStatus.LIKELY_DESTROYED;
            TrackerLog.LOGGER.info("{} {} died", record.type, record.uuid);
        } else {
            record.status = RecordStatus.UNLOADED;
            if (nearPortal(world, entity.blockPosition())) {
                // Gone through a portal, not destroyed (CLAUDE.md 4.1d D); matching drops still override.
                record.flags.add(Notes.WENT_THROUGH_PORTAL);
                TrackerLog.LOGGER.info("{} {} went through a portal", record.type, record.uuid);
            }
            if (client.player != null && client.player.distanceToSqr(entity) < Settings.destroyDistance * Settings.destroyDistance) {
                vanished.add(new Vanished(record.uuid, new Vec3(entity.getX(), entity.getY(), entity.getZ()),
                    clock.currentTick(), dropItems(record, entity)));
            }
        }
        bank.markDirty();
    }

    /**
     * Runs every tick; does a full pass every {@link Settings#trackIntervalTicks}.
     */
    public void tick(Minecraft client) {
        MemoryBank bank = banks.bank();
        ClientLevel world = client.level;
        if (bank != null && world != null) matchDrops(world, bank);
        vanished.removeIf(v -> clock.currentTick() - v.tick > Settings.dropWindowTicks);
        if (bank == null || world == null || clock.currentTick() % Settings.trackIntervalTicks != 0) return;

        Set<UUID> present = new HashSet<>();
        for (Entity entity : world.entitiesForRendering()) {
            EntityRecord record = bank.get(entity.getUUID());
            if (record == null) continue;
            present.add(record.uuid);
            if (update(record, entity, world)) bank.markDirty();
            if (checkEntityNotes(record, entity, world)) bank.markDirty();
        }

        // Nothing in another dimension can be loaded right now (e.g. just after going through a portal).
        String here = world.dimension().identifier().toString();
        for (ContainerRecord r : bank.allRecords().toList()) {
            if (r.status == RecordStatus.LOADED && !here.equals(r.dimension) && !(r instanceof BlockRecord b && b.global)) {
                r.status = RecordStatus.UNLOADED;
                bank.markDirty();
            }
        }

        if (clock.currentTick() % (Settings.trackIntervalTicks * 5L) == 0) checkBlocks(world, bank);

        // Home area fully loaded but the entity isn't there: missing (not destroyed).
        String dimension = world.dimension().identifier().toString();
        for (EntityRecord record : bank.all()) {
            if (present.contains(record.uuid) || !dimension.equals(record.dimension)) continue;
            if (record.status != RecordStatus.UNLOADED && record.status != RecordStatus.LOADED) continue;
            if (record.flags.contains(Notes.WENT_THROUGH_PORTAL)) continue; // we know where it went
            // Only trust "not there" within entity tracking range (CLAUDE.md 4.1d C).
            double range = DisplayTracker.isDisplay(record.type) ? Settings.displayMissingCheckRange : Settings.missingCheckRange;
            if (client.player == null || client.player.distanceToSqr(record.x, record.y, record.z) > range * range) continue;
            if (homeAreaLoaded(world, record)) {
                record.status = RecordStatus.MISSING;
                bank.markDirty();
                TrackerLog.LOGGER.info("{} {} missing from its home area", record.type, record.uuid);
            }
        }
    }

    /**
     * Block containers: present while their chunk is loaded and a container is still there;
     * gone (likely destroyed) if the chunk is loaded and it isn't. Checks "is a container", not
     * the exact block id, since copper chests change id as they oxidise.
     */
    private void checkBlocks(ClientLevel world, MemoryBank bank) {
        String dimension = world.dimension().identifier().toString();
        long now = System.currentTimeMillis();
        for (BlockRecord record : bank.allBlocks()) {
            if (record.global || !dimension.equals(record.dimension)) continue;
            BlockPos pos = record.pos;
            if (!world.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
                if (record.status == RecordStatus.LOADED) {
                    record.status = RecordStatus.UNLOADED;
                    bank.markDirty();
                }
                continue;
            }
            BlockState state = world.getBlockState(pos);
            boolean present = world.getBlockEntity(pos) instanceof Container || state.getBlock() instanceof EnderChestBlock
                || state.getBlock() instanceof LecternBlock;
            if (present && checkBlockNotes(record, state, world)) bank.markDirty();
            RecordStatus next = present ? RecordStatus.LOADED : RecordStatus.LIKELY_DESTROYED;
            if (present) record.lastSeen = now;
            if (record.status != next) {
                record.status = next;
                bank.markDirty();
            }
            // Unlike an entity, a block in a loaded chunk is certainly gone: trash it (restorable), and
            // bring it back if the mod trashed it and the container is there again. Gated behind the
            // auto-trash setting (default off), so nothing is silently trashed on its own.
            if (Settings.autoTrashGoneBlocks && !present && !record.isTrashed()) Trash.trashGoneBlock(bank, record);
            else if (present && record.isTrashed() && record.autoTrashed) {
                // A container placed where the old one broke: the old contents dropped, so start as never opened.
                Trash.restore(bank, record);
                record.items.clear();
                record.slotCount = 0;
                record.lastOpened = 0;
                record.flags.clear();
            }
        }
    }

    /**
     * Drops near a vanished entity that match its contents or its own item: it was broken.
     */
    private void matchDrops(ClientLevel world, MemoryBank bank) {
        for (Iterator<Vanished> it = vanished.iterator(); it.hasNext(); ) {
            Vanished v = it.next();
            double r = Settings.dropRadius;
            AABB box = new AABB(v.pos.x - r, v.pos.y - r, v.pos.z - r, v.pos.x + r, v.pos.y + r, v.pos.z + r);
            boolean matched = !world.getEntitiesOfClass(ItemEntity.class, box,
                item -> v.dropItems.contains(item.getItem().getItem())).isEmpty();
            if (!matched) continue;
            EntityRecord record = bank.get(v.uuid);
            if (record != null && record.status != RecordStatus.LOADED) {
                record.status = RecordStatus.LIKELY_DESTROYED;
                record.flags.remove(Notes.WENT_THROUGH_PORTAL); // broken next to a portal, not through it
                bank.markDirty();
                TrackerLog.LOGGER.info("{} {} likely destroyed (its items dropped where it vanished)", record.type, record.uuid);
            }
            it.remove();
        }
    }

    /**
     * What a broken container entity drops: its remembered contents and its own item.
     */
    private static Set<Item> dropItems(EntityRecord record, Entity entity) {
        // Items, not ids: comparing identity avoids one registry id string allocation per
        // candidate per candidate tick; the id strings never existed outside this helper.
        Set<Item> items = new HashSet<>();
        for (ContainerRecord.SlotItem slotItem : record.items) {
            if (slotItem.stack().isJsonObject()) {
                var obj = slotItem.stack().getAsJsonObject();
                if (obj.has("id")) {
                    Identifier id = Identifier.tryParse(obj.get("id").getAsString());
                    if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                        Item item = BuiltInRegistries.ITEM.getValue(id);
                        if (item != Items.AIR) items.add(item);
                    }
                }
            }
        }
        Identifier type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (BuiltInRegistries.ITEM.containsKey(type)) {
            Item item = BuiltInRegistries.ITEM.getValue(type);
            if (item != Items.AIR) items.add(item);
        }
        if (entity instanceof AbstractChestedHorse) items.add(Items.CHEST);
        return items;
    }

    /**
     * A portal within a few blocks. Wider than the entity itself on purpose: a moving entity is
     * drawn a few blocks behind its real position (interpolation), so when it is removed the
     * client may still place it short of the portal it just entered.
     */
    private static boolean nearPortal(ClientLevel world, BlockPos pos) {
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-4, -2, -4), pos.offset(4, 3, 4))) {
            Block b = world.getBlockState(p).getBlock();
            if (b == Blocks.NETHER_PORTAL || b == Blocks.END_PORTAL || b == Blocks.END_GATEWAY) return true;
        }
        return false;
    }

    /**
     * F (chest removed) and G (hints) for a loaded entity. Returns true if anything changed.
     */
    private static boolean checkEntityNotes(EntityRecord record, Entity entity, ClientLevel world) {
        boolean changed = record.flags.remove(Notes.WENT_THROUGH_PORTAL); // it's back
        // A dying animal drops its chest during the death animation: that's a death, not a removed chest.
        if (entity instanceof AbstractChestedHorse donkey && !donkey.hasChest() && !donkey.isDeadOrDying() && record.hasSnapshot()) {
            changed |= record.flags.add(Notes.CHEST_REMOVED); // its contents dropped when the chest came off
        }
        Set<String> hints = new LinkedHashSet<>();
        if (record.trades != null) changed |= checkTraderNotes(record.trades, record, entity, hints);
        if (entity instanceof MinecartHopper) hints.add(Notes.MOVES_ITEMS);
        if (entity instanceof AbstractMinecart && world.getBlockState(entity.blockPosition().below()).getBlock() instanceof HopperBlock) {
            hints.add(Notes.OVER_HOPPER);
        }
        if (!hints.equals(record.hints)) {
            record.hints = hints;
            changed = true;
        }
        return changed;
    }

    /**
     * A villager seen with another profession or a higher level than when its offers were read:
     * the remembered offers are out of date. The stored profession stays until it is opened again.
     */
    private static boolean checkTraderNotes(TradeData trades, EntityRecord record, Entity entity, Set<String> hints) {
        if (entity instanceof WanderingTrader) hints.add(Notes.LEAVES_BY_ITSELF);
        if (trades.byAccount.isEmpty()) return false;
        TradeData live = new TradeData();
        TradeBinder.readVillager(live, entity);
        boolean changed = false;
        if (!java.util.Objects.equals(live.profession, trades.profession)) changed |= record.flags.add(Notes.PROFESSION_CHANGED);
        else if (live.level > trades.level) changed |= record.flags.add(Notes.LEVELED_UP);
        return changed;
    }

    /**
     * E (different container now) and G (hints) for a present block container.
     */
    private static boolean checkBlockNotes(BlockRecord record, BlockState state, ClientLevel world) {
        boolean changed = false;
        String now = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (!family(now).equals(family(record.type))) changed = record.flags.add(Notes.CONTAINER_CHANGED);
        Set<String> hints = new LinkedHashSet<>();
        DisplayTracker.addHints(record, state, hints);
        List<BlockPos> positions = new ArrayList<>();
        positions.add(record.pos);
        positions.addAll(record.otherPositions);
        for (BlockPos p : positions) {
            Block block = world.getBlockState(p).getBlock();
            if (block instanceof AbstractFurnaceBlock || block instanceof BrewingStandBlock) hints.add(Notes.PROCESSES_ITEMS);
            if (block instanceof HopperBlock) hints.add(Notes.MOVES_ITEMS);
            if (world.getBlockState(p.below()).getBlock() instanceof HopperBlock) hints.add(Notes.HOPPER_BELOW);
            for (Direction d : Direction.values()) {
                BlockState n = world.getBlockState(p.relative(d));
                // A hopper facing into this block (its output side points at p) feeds it.
                if (n.getBlock() instanceof HopperBlock && n.getValue(HopperBlock.FACING) == d.getOpposite()) hints.add(Notes.FED_BY_HOPPER);
            }
        }
        if (!hints.equals(record.hints)) {
            record.hints = hints;
            changed = true;
        }
        return changed;
    }

    /**
     * Same container family: copper chests change id as they oxidise or get waxed.
     */
    private static String family(String id) {
        return id.replace("waxed_", "").replace("exposed_", "").replace("weathered_", "").replace("oxidized_", "");
    }

    /**
     * Chunks around the home chunk that count as home. Item frames never move, so theirs is just
     * the chunk they hang in: "missing" as soon as that chunk is loaded without them.
     */
    private static int homeRadius(EntityRecord record) {
        return DisplayTracker.isFixed(record.type) ? 0 : Settings.homeRadiusChunks;
    }

    private static boolean homeAreaLoaded(ClientLevel world, EntityRecord record) {
        int r = homeRadius(record);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (!world.getChunkSource().hasChunk(record.homeChunkX + dx, record.homeChunkZ + dz)) return false;
            }
        }
        return true;
    }
}
