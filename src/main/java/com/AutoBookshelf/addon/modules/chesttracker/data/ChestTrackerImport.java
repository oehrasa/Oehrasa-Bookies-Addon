// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker.data;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * Reads a Chest Tracker (2.x) memory bank, a gzipped NBT file with one compound per dimension:
 * {@code memories} maps "x,y,z" to a container with its block id, items, other positions (the
 * second half of a double chest) and timestamps. Ender chests sit under
 * {@code chesttracker:ender_chest}. The file is only ever read, never changed.
 * <p>
 * Chest Tracker doesn't keep slot numbers, only the stacks in order, so imported items fill the
 * first slots and the record is flagged {@link Notes#IMPORTED} until it is opened again.
 */
public final class ChestTrackerImport {
    private static final String ENDER_DIMENSION = "chesttracker:ender_chest";

    private ChestTrackerImport() {
    }

    public record Result(List<BlockRecord> records, int items, int rawItems, int skipped, int dataVersion) {
    }

    public record MergeResult(int added, int replaced, int keptNewer) {
    }

    /**
     * Chest Tracker files the player can pick: the import folder, then Chest Tracker's own folder.
     */
    public static List<Path> findFiles(Path gameDir, Path root) {
        List<Path> out = new ArrayList<>();
        List<Path> dirs = new ArrayList<>();
        dirs.add(importDir(root));
        dirs.add(gameDir.resolve("chesttracker"));
        Path instances = gameDir.getParent() != null ? gameDir.getParent().getParent() : null;
        if (instances != null && Files.isDirectory(instances)) {
            try (Stream<Path> s = Files.list(instances)) {
                s.forEach(inst -> {
                    Path r = inst.resolve("minecraft/meteor-client/ChestTracker");
                    if (Files.isDirectory(r) && !r.equals(root)) {
                        dirs.add(r.resolve("import"));
                        dirs.add(r.resolve("banks"));
                    }
                    Path r2 = inst.resolve("minecraft/chesttracker");
                    if (Files.isDirectory(r2) && !r2.equals(root)) dirs.add(r2);
                });
            } catch (IOException ignored) {
            }
        }
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir) || dir.equals(root)) continue;
            try (Stream<Path> files = Files.walk(dir, 4)) {
                files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".nbt"))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(out::add);
            } catch (IOException ignored) {
                // An unreadable folder just offers nothing.
            }
        }
        // de-dupe
        LinkedHashSet<Path> dedup = new LinkedHashSet<>(out);
        return new ArrayList<>(dedup);
    }

    public static Path importDir(Path root) {
        return root.resolve("import");
    }

    /**
     * Reads the file and turns it into block records. Safe off the game thread: the registries
     * are only read. Ender chests come back as global records without an owner.
     */
    public static Result read(Path file, HolderLookup.Provider registries) throws IOException {
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        var nbtOps = registries.createSerializationContext(NbtOps.INSTANCE);
        var jsonOps = registries.createSerializationContext(JsonOps.INSTANCE);
        List<BlockRecord> records = new ArrayList<>();
        int items = 0, rawItems = 0, skipped = 0, dataVersion = 0;
        for (String dimension : root.keySet()) {
            CompoundTag dim = root.getCompoundOrEmpty(dimension);
            int dimVersion = dim.getIntOr("MinecraftDataVersion", 0);
            dataVersion = Math.max(dataVersion, dimVersion);
            CompoundTag memories = dim.getCompoundOrEmpty("memories");
            for (String posKey : memories.keySet()) {
                CompoundTag memory = memories.getCompoundOrEmpty(posKey);
                BlockPos pos = parsePos(posKey);
                String type = memory.getStringOr("container", "");
                if (pos == null || type.isEmpty()) {
                    skipped++;
                    continue;
                }
                boolean ender = dimension.equals(ENDER_DIMENSION);
                BlockRecord r = new BlockRecord(ender);
                r.type = type;
                r.dimension = ender ? "minecraft:overworld" : dimension;
                List<BlockPos> all = new ArrayList<>();
                all.add(pos);
                for (Tag e : memory.getListOrEmpty("otherPositions")) {
                    BlockPos other = e.asString().map(ChestTrackerImport::parsePos).orElse(null);
                    if (other != null) all.add(other);
                }
                // Same convention as a live double chest: stored at the lower corner.
                all.sort(Comparator.comparingLong(BlockPos::asLong));
                r.pos = all.getFirst();
                r.otherPositions.addAll(all.subList(1, all.size()));
                BlockPos second = r.otherPositions.isEmpty() ? null : r.otherPositions.getFirst();
                r.x = r.pos.getX() + 0.5 + (second == null ? 0 : (second.getX() - r.pos.getX()) / 2.0);
                r.y = r.pos.getY() + 0.5;
                r.z = r.pos.getZ() + 0.5 + (second == null ? 0 : (second.getZ() - r.pos.getZ()) / 2.0);
                r.name = memory.getString("name").filter(s -> !s.isBlank()).orElse(null);
                r.status = RecordStatus.UNLOADED;
                long when = parseTime(memory.getStringOr("realTimestamp", ""));
                r.firstSeen = when;
                r.lastSeen = when;
                r.lastOpened = when;

                ListTag stacks = memory.getListOrEmpty("items");
                int slot = 0;
                for (Tag e : stacks) {
                    // Chest Tracker writes empty slots as {} (seen in ender chests): keep the slot free.
                    if (e instanceof CompoundTag c && c.isEmpty()) {
                        slot++;
                        continue;
                    }
                    // An older Chest Tracker file's items are upgraded first, as the game upgrades worlds.
                    Tag upgraded = ItemData.upgrade(NbtOps.INSTANCE, e, dimVersion);
                    JsonElement json = ItemStack.CODEC.parse(nbtOps, upgraded).result()
                        .flatMap(stack -> ItemStack.CODEC.encodeStart(jsonOps, stack).result())
                        .orElse(null);
                    if (json == null) {
                        // Unknown item (a mod that isn't installed, or a newer version): keep its raw data with
                        // the version that wrote it, like a bank from a newer version.
                        json = NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, upgraded);
                        rawItems++;
                        int version = dimVersion > ItemData.CURRENT ? dimVersion : ItemData.CURRENT;
                        r.items.add(new ContainerRecord.SlotItem(slot++, json, version));
                    } else {
                        r.items.add(new ContainerRecord.SlotItem(slot++, json));
                    }
                    items++;
                }
                r.slotCount = Math.max(slots(type, !r.otherPositions.isEmpty()), r.items.size());
                r.flags.add(Notes.IMPORTED);
                records.add(r);
            }
        }
        return new Result(records, items, rawItems, skipped, dataVersion);
    }

    /**
     * True when the file was written by a newer Minecraft version (items may not read right). Older
     * files are fine: their items were upgraded on reading.
     */
    public static boolean newerVersion(Result result) {
        return result.dataVersion() > ItemData.CURRENT;
    }

    /**
     * Adds the records to the bank. Whatever was opened more recently wins, so containers the
     * mod already tracks keep their newer contents and importing the same file twice changes
     * nothing. Ender chests go to {@code owner}, the account playing now.
     */
    public static MergeResult merge(MemoryBank bank, List<BlockRecord> records, @Nullable UUID owner) {
        int added = 0, replaced = 0, keptNewer = 0;
        for (BlockRecord r : records) {
            if (r.global) {
                if (owner == null) continue;
                r.owner = owner;
                r.ownerName = bank.accounts.get(owner);
                BlockRecord existing = bank.getEnderChest(owner);
                if (existing != null && existing.lastOpened >= r.lastOpened) {
                    keptNewer++;
                    continue;
                }
                if (existing != null) {
                    r.firstSeen = Math.min(r.firstSeen, existing.firstSeen);
                    replaced++;
                } else {
                    added++;
                }
                bank.putBlock(r);
                continue;
            }
            List<BlockRecord> existing = new ArrayList<>();
            addIfAbsent(existing, bank.getBlockAt(r.dimension, r.pos));
            for (BlockPos p : r.otherPositions) addIfAbsent(existing, bank.getBlockAt(r.dimension, p));
            if (existing.stream().anyMatch(old -> old.lastOpened >= r.lastOpened)) {
                keptNewer++;
                continue;
            }
            if (existing.isEmpty()) {
                bank.putBlock(r);
                added++;
            } else {
                // Possibly another shape (single vs double chest): the import replaces every record it covers.
                for (BlockRecord old : existing) {
                    r.firstSeen = Math.min(r.firstSeen, old.firstSeen);
                    bank.replaceBlock(old, r);
                }
                replaced++;
            }
        }
        if (added + replaced > 0) bank.markDirty();
        return new MergeResult(added, replaced, keptNewer);
    }

    private static void addIfAbsent(List<BlockRecord> list, @Nullable BlockRecord r) {
        if (r != null && !list.contains(r)) list.add(r);
    }

    /**
     * Container slots for a block id; only a guess at the size, since the file doesn't say.
     */
    private static int slots(String type, boolean doubleChest) {
        String path = type.substring(type.indexOf(':') + 1);
        if (doubleChest) return 54;
        if (path.endsWith("furnace") || path.equals("smoker")) return 3;
        if (path.equals("hopper") || path.equals("brewing_stand")) return 5;
        if (path.equals("dispenser") || path.equals("dropper") || path.equals("crafter")) return 9;
        return 27;
    }

    private static @Nullable BlockPos parsePos(String s) {
        String[] parts = s.split(",");
        if (parts.length != 3) return null;
        try {
            return new BlockPos(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
                Integer.parseInt(parts[2].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long parseTime(String s) {
        try {
            return Instant.parse(s).toEpochMilli();
        } catch (Exception e) {
            // Unknown time: treat it as very old, so anything the mod saw itself wins.
            return 1;
        }
    }
}
