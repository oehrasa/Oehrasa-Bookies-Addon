/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.commands;

import com.AutoBookshelf.addon.modules.chesttracker.ChestTrackerModule;
import com.AutoBookshelf.addon.modules.chesttracker.data.BlockRecord;
import com.AutoBookshelf.addon.modules.chesttracker.data.ChestTrackerImport;
import com.AutoBookshelf.addon.modules.chesttracker.data.ContainerRecord;
import com.AutoBookshelf.addon.modules.chesttracker.data.MemoryBank;
import com.AutoBookshelf.addon.modules.chesttracker.search.TallyReport;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Search, inspect, clear, export, tally, save and import the memory bank of the server the player
 * is on.
 */
public class ChestTrackerCommand extends Command {
    public ChestTrackerCommand() {
        super("chesttracker", "Search for items in tracked containers.", "ct", "track");
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder.then(literal("search").then(literal("hand").executes(context -> {
            ChestTrackerModule module = getModule();
            if (module == null) return SINGLE_SUCCESS;

            if (mc.player == null) {
                error("Not in-game.");
                return SINGLE_SUCCESS;
            }

            ItemStack held = mc.player.getMainHandItem();
            if (held.isEmpty()) {
                error("Main hand is empty.");
                return SINGLE_SUCCESS;
            }

            searchAndDisplay(module, held.getItem());
            return SINGLE_SUCCESS;
        })).then(argument("item", StringArgumentType.greedyString()).executes(context -> {
            ChestTrackerModule module = getModule();
            if (module == null) return SINGLE_SUCCESS;

            String query = StringArgumentType.getString(context, "item");
            Item item = findItem(query);
            if (item == null) {
                error("Unknown item: (highlight)%s(default).", query);
                return SINGLE_SUCCESS;
            }

            searchAndDisplay(module, item);
            return SINGLE_SUCCESS;
        })));

        builder.then(literal("nearby").then(argument("radius", IntegerArgumentType.integer(1, 128)).executes(context -> {
            ChestTrackerModule module = getModule();
            if (module == null) return SINGLE_SUCCESS;

            if (mc.player == null || mc.level == null) {
                error("Not in-game.");
                return SINGLE_SUCCESS;
            }

            MemoryBank bank = module.getBank();
            if (bank == null) {
                error("No bank loaded.");
                return SINGLE_SUCCESS;
            }

            int radius = IntegerArgumentType.getInteger(context, "radius");
            // The same records the render highlights: the containers of this dimension that are
            // not trashed and not the global ender chests (an ender chest has no place in the
            // world, so its "location" is nowhere the player can go). A record at the same xyz in
            // another dimension is not nearby either, however close its coordinates look.
            String dim = mc.level.dimension().identifier().toString();
            List<ContainerRecord> all = bank.containers()
                .filter(r -> !r.isTrashed())
                .filter(r -> !(r instanceof BlockRecord b && b.global))
                .filter(r -> dim.equals(r.dimension))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            all.sort((a, b) -> Double.compare(distanceTo(a), distanceTo(b)));

            info("Nearby tracked containers within (highlight)%d(default)m:", radius);

            int found = 0;
            for (ContainerRecord container : all) {
                double distance = distanceTo(container);
                if (distance > radius) continue;

                BlockPos pos = BlockPos.containing(container.x, container.y, container.z);
                info("%s[%d, %d, %d] §e%.0fm §b%d item(s)%s",
                    distance <= module.getRenderDistance() ? "§a" : "§7",
                    pos.getX(), pos.getY(), pos.getZ(),
                    distance, container.items.size(),
                    container.items.isEmpty() ? " §c(empty)" : "");
                found++;
            }

            if (found == 0) info("None found.");
            else info("(highlight)%d(default) found.", found);

            return SINGLE_SUCCESS;
        })));

        builder.then(literal("clear").then(literal("all").executes(context -> {
            ChestTrackerModule module = getModule();
            if (module == null) return SINGLE_SUCCESS;

            MemoryBank bank = module.getBank();
            if (bank == null) {
                error("No bank loaded.");
                return SINGLE_SUCCESS;
            }

            for (ContainerRecord r : bank.allRecords().toList()) bank.remove(r);
            info("Cleared all tracked containers.");
            return SINGLE_SUCCESS;
        })).then(literal("dimension").executes(context -> {
            ChestTrackerModule module = getModule();
            if (module == null) return SINGLE_SUCCESS;

            MemoryBank bank = module.getBank();
            if (bank == null) {
                error("No bank loaded.");
                return SINGLE_SUCCESS;
            }

            String dim = mc.level == null ? "" : mc.level.dimension().identifier().toString();
            for (ContainerRecord r : bank.allRecords().toList()) {
                if (dim.equals(r.dimension)) bank.remove(r);
            }
            info("Cleared tracked containers in the current dimension.");
            return SINGLE_SUCCESS;
        })));

        builder.then(literal("export").executes(context -> {
            ChestTrackerModule module = getModule();
            if (module == null) return SINGLE_SUCCESS;

            MemoryBank bank = module.getBank();
            if (bank == null) {
                error("No bank loaded.");
                return SINGLE_SUCCESS;
            }

            String filename = "export_" + java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".json";
            try {
                java.nio.file.Files.createDirectories(module.getStorage().root().resolve(filename).getParent());
                java.nio.file.Files.writeString(module.getStorage().root().resolve(filename), module.getStorage().snapshot(bank).toString());
                info("Exported to (highlight)%s(default).", filename);
            } catch (Exception e) {
                error("Export failed: (highlight)%s(default).", String.valueOf(e.getMessage()));
            }

            return SINGLE_SUCCESS;
        }));

        builder.then(literal("bank").executes(context -> {
            ChestTrackerModule module = getModule();
            if (module == null) return SINGLE_SUCCESS;
            return bankInfo(module);
        }).then(literal("merge").then(argument("address", StringArgumentType.string()).executes(context -> {
            ChestTrackerModule module = getModule();
            if (module == null) return SINGLE_SUCCESS;
            String address = StringArgumentType.getString(context, "address");
            String error = module.getBankManager().requestMerge(address);
            if (error != null) error(error);
            else info("Merging the bank for (highlight)%s(default) into this one in the background...", address);
            return SINGLE_SUCCESS;
        }))));
        builder.then(literal("list").executes(context -> {
            ChestTrackerModule module = getModule();
            if (module == null) return SINGLE_SUCCESS;
            return listBank(module);
        }));
        builder.then(literal("tally")
            .executes(context -> {
                ChestTrackerModule module = getModule();
                if (module == null) return SINGLE_SUCCESS;
                return tally(module, null);
            })
            .then(argument("item", StringArgumentType.greedyString()).executes(context -> {
                ChestTrackerModule module = getModule();
                if (module == null) return SINGLE_SUCCESS;
                return tally(module, StringArgumentType.getString(context, "item"));
            })));
        builder.then(literal("save").executes(context -> {
            ChestTrackerModule module = getModule();
            if (module == null) return SINGLE_SUCCESS;
            module.getBankManager().saveNow();
            info("Saved.");
            return SINGLE_SUCCESS;
        }));
        LiteralArgumentBuilder<ClientSuggestionProvider> importCmd = literal("import");
        importCmd.executes(context -> listImports(getModule()));
        importCmd.then(literal("all").executes(context -> importAll(getModule())));
        importCmd.then(argument("file", StringArgumentType.string()).executes(context -> importFile(getModule(), StringArgumentType.getString(context, "file"), false)).then(literal("confirm").executes(context -> importFile(getModule(), StringArgumentType.getString(context, "file"), true))));
        builder.then(importCmd);
    }

    private int bankInfo(ChestTrackerModule module) {
        if (module == null) return SINGLE_SUCCESS;
        MemoryBank bank = module.getBank();
        if (bank == null) {
            error("No bank open.");
            return SINGLE_SUCCESS;
        }
        info("Bank (highlight)%s(default) of server (highlight)%s(default): (highlight)%d(default) containers, dirty (highlight)%s(default), loading (highlight)%s(default).",
            bank.key, bank.server, bank.size(), bank.isDirty(), module.getBankManager().isLoading());
        return SINGLE_SUCCESS;
    }

    private int listBank(ChestTrackerModule module) {
        if (module == null) return SINGLE_SUCCESS;
        MemoryBank bank = module.getBank();
        if (bank == null) {
            error("No bank open.");
            return SINGLE_SUCCESS;
        }
        List<ContainerRecord> records = new ArrayList<>(bank.allRecords().toList());
        info("(" + records.size() + " containers in bank " + bank.key + ").");
        for (ContainerRecord r : records) {
            info("(dimension) " + r.dimension + ", type " + r.type + ", at " + r.x + ", " + r.y + ", " + r.z
                + ", items " + r.items.size() + (r.hasSnapshot() ? ", snapshotted" : ", never opened"));
        }
        return SINGLE_SUCCESS;
    }

    private int tally(ChestTrackerModule module, String query) {
        if (module == null) return SINGLE_SUCCESS;
        MemoryBank bank = module.getBank();
        if (bank == null) {
            error("No bank open.");
            return SINGLE_SUCCESS;
        }
        if (query != null) {
            List<Component> lines = TallyReport.lines(query, bank);
            for (Component c : lines) info(c.getString());
        } else {
            List<Component> lines = TallyReport.top(bank, 10);
            for (Component c : lines) info(c.getString());
        }
        return SINGLE_SUCCESS;
    }

    private int listImports(ChestTrackerModule module) {
        if (module == null) return SINGLE_SUCCESS;
        MemoryBank bank = module.getBank();
        if (bank == null) {
            error("No bank open.");
            return SINGLE_SUCCESS;
        }
        Path gameDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
        List<Path> files = ChestTrackerImport.findFiles(gameDir, module.getStorage().root());
        info("Found (highlight)%d(default) Chest Tracker file(s) in (highlight)%s(default).", files.size(), gameDir);
        for (Path p : files) info("- " + p.getFileName() + "  (" + gameDir.relativize(p) + ")");
        return SINGLE_SUCCESS;
    }

    private int importAll(ChestTrackerModule module) {
        if (module == null) return SINGLE_SUCCESS;
        MemoryBank bank = module.getBank();
        if (bank == null) {
            error("No bank open.");
            return SINGLE_SUCCESS;
        }
        Path gameDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
        List<Path> files = ChestTrackerImport.findFiles(gameDir, module.getStorage().root()).stream()
            .filter(p -> namedAfterServer(module, bank, p)).toList();
        if (files.isEmpty()) {
            error("No Chest Tracker files for " + bank.server + "; /chesttracker import lists them");
            return SINGLE_SUCCESS;
        }
        int imported = 0;
        int failed = 0;
        for (Path file : files) {
            int n = importOne(module, bank, file);
            if (n < 0) failed++;
            else imported += n;
        }
        info("Imported into bank (highlight)%s(default): (highlight)%d(default) container(s) from Chest Tracker%s.",
            bank.key, imported, failed > 0 ? " (highlight)" + failed + "(default) file(s) failed)" : "");
        return SINGLE_SUCCESS;
    }

    private int importFile(ChestTrackerModule module, String name, boolean confirmed) {
        if (module == null) return SINGLE_SUCCESS;
        MemoryBank bank = module.getBank();
        if (bank == null) {
            error("No bank open.");
            return SINGLE_SUCCESS;
        }
        Path gameDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
        Path file = findImportFile(ChestTrackerImport.findFiles(gameDir, module.getStorage().root()), gameDir, name);
        if (file == null) {
            error("No Chest Tracker file called (highlight)%s(default); when several files share the name, give the path relative to the game directory.", name);
            return SINGLE_SUCCESS;
        }
        if (!confirmed && !namedAfterServer(module, bank, file)) {
            error("This file is named after another server; add confirm to import it into bank (highlight)%s(default) anyway.", bank.server);
            return SINGLE_SUCCESS;
        }
        int n = importOne(module, bank, file);
        if (n < 0) return SINGLE_SUCCESS;   // the failure was already reported
        info("Imported from Chest Tracker into bank (highlight)%s(default): (highlight)%d(default) container(s).", bank.key, n);
        return SINGLE_SUCCESS;
    }

    /**
     * The file the user named. The list prints the path relative to the game directory, so that
     * path is matched first; a bare file name only matches when it is unambiguous, otherwise the
     * second file with the same name would be unreachable.
     */
    private Path findImportFile(List<Path> files, Path gameDir, String name) {
        String wanted = stripNbt(name);

        // the path relative to the game directory, the way the list prints it
        Path exact = null;
        int exactCount = 0;
        for (Path p : files) {
            if (!stripNbt(gameDir.relativize(p).toString()).equals(wanted)) continue;
            exact = p;
            exactCount++;
        }
        if (exactCount == 1) return exact;

        // a suffix of it, when that is unambiguous
        Path suffix = null;
        int suffixCount = 0;
        for (Path p : files) {
            if (!stripNbt(gameDir.relativize(p).toString()).endsWith("/" + wanted)) continue;
            suffix = p;
            suffixCount++;
        }
        if (suffixCount == 1) return suffix;

        // the file name, when that is unambiguous
        Path byName = null;
        int byNameCount = 0;
        for (Path p : files) {
            if (!stripNbt(p.getFileName().toString()).equals(wanted)) continue;
            byName = p;
            byNameCount++;
        }
        return byNameCount == 1 ? byName : null;
    }

    /**
     * The file name without its .nbt suffix.
     */
    private static String stripNbt(String name) {
        return name.replaceFirst("(?i)\\.nbt$", "");
    }

    /**
     * Imports one file.
     *
     * @return the number of imported containers, or -1 when the import failed. The failure is
     *         reported here, so the callers must not report it as an empty import.
     */
    private int importOne(ChestTrackerModule module, MemoryBank bank, Path file) {
        if (module == null) return -1;
        if (mc.level == null) {
            error("No world open.");
            return -1;
        }
        try {
            ChestTrackerImport.Result result = ChestTrackerImport.read(file, mc.level.registryAccess());
            if (ChestTrackerImport.newerVersion(result)) {
                error("Chest Tracker file is from a newer version; not importing it.");
                return -1;
            }
            ChestTrackerImport.MergeResult merged = ChestTrackerImport.merge(bank, result.records(), bank.currentPlayer);
            info("Imported from Chest Tracker: (highlight)%d(default) new, (highlight)%d(default) updated, (highlight)%d(default) kept as newer, (highlight)%d(default) stacks.",
                merged.added(), merged.replaced(), merged.keptNewer(), result.items());
            return merged.added() + merged.replaced();
        } catch (Exception e) {
            error("Import failed: (highlight)%s(default).", String.valueOf(e.getMessage()));
            return -1;
        }
    }

    /**
     * Whether the file is named after this server <em>exactly</em>. Both the automatic import and
     * the confirmation gate of the explicit one use this and nothing else: stripping the
     * punctuation makes "a.b" and "ab" the same identity, so a loose match can authorise a file
     * that belongs to another server.
     */
    private boolean namedAfterServer(ChestTrackerModule module, MemoryBank bank, Path file) {
        String name = file.getFileName().toString().replaceFirst("(?i)\\.nbt$", "");
        return module.getBankManager().storage().addressesFor(bank.server).stream()
            .anyMatch(a -> a.equalsIgnoreCase(name));
    }

    private ChestTrackerModule getModule() {
        ChestTrackerModule module = Modules.get().get(ChestTrackerModule.class);
        if (module == null || !module.isActive()) {
            error("Chest-Tracker module is not enabled.");
            return null;
        }
        return module;
    }

    private double distanceTo(ContainerRecord container) {
        return Math.sqrt(mc.player.distanceToSqr(container.x, container.y, container.z));
    }

    private void searchAndDisplay(ChestTrackerModule module, Item item) {
        MemoryBank bank = module.getBank();
        if (bank == null) {
            error("No bank loaded.");
            return;
        }
        if (mc.level == null) {
            error("No world open.");
            return;
        }
        String itemId = BuiltInRegistries.ITEM.getKey(item).toString();
        // Count the records the render actually highlights: the containers of this dimension
        // that are not trashed, and not the global ender chests (they have no place in the
        // world, so they are never highlighted). Counting the whole bank instead reported
        // items the player cannot see a box on.
        String currentDim = module.getCurrentDimension();
        int total = 0;
        int containers = 0;
        for (ContainerRecord rec : bank.containers().toList()) {
            if (rec.isTrashed()) continue;
            if (rec instanceof BlockRecord b && b.global) continue;
            if (!currentDim.equals(rec.dimension)) continue;
            int count = 0;
            for (ContainerRecord.SlotItem si : rec.items) {
                // The id and the count are read straight out of the stored JSON: a slot holds
                // the game's own encoding of the stack, so both are top level fields. Decoding
                // every stack of every record with the item codec instead is a client thread
                // stall that grows with the bank, and the search index already exists for the
                // expensive work.
                if (!si.stack().isJsonObject()) continue;
                JsonObject stack = si.stack().getAsJsonObject();
                if (!stack.has("id") || !itemId.equals(stack.get("id").getAsString())) continue;
                count += stack.has("count") ? Math.max(1, stack.get("count").getAsInt()) : 1;
            }
            if (count > 0) {
                total += count;
                containers++;
            }
        }
        String name = item.getName(new ItemStack(item)).getString();
        if (containers == 0) {
            info("No tracked container holds (highlight)%s(default).", name);
            // A zero-result search still has to replace the previous highlights, so the
            // browser stops showing the last item that did match.
            module.searchItem(item);
            return;
        }
        info("(highlight)%s(default) in (highlight)%d(default) container(s).", String.format("%,d", total), containers);
        module.searchItem(item);
    }

    /**
     * Resolves a user-typed item name to an Item. Tries, in order: the string as a
     * namespaced id, the same name in the minecraft namespace, an exact path match in
     * any namespace, then a substring match. Returns null when nothing matches.
     */
    private Item findItem(String query) {
        String normalised = query.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (normalised.isEmpty()) return null;

        Identifier parsed = normalised.contains(":")
            ? Identifier.tryParse(normalised)
            : Identifier.tryParse("minecraft:" + normalised);
        if (parsed != null) {
            Item exact = lookup(parsed);
            if (exact != null) return exact;
        }

        for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
            if (id.getPath().equals(normalised)) return BuiltInRegistries.ITEM.getValue(id);
        }

        for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
            if (id.getPath().contains(normalised)) return BuiltInRegistries.ITEM.getValue(id);
        }

        return null;
    }

    /**
     * ITEM is a defaulted registry, so getValue() silently returns AIR for unknown keys.
     */
    private Item lookup(Identifier id) {
        if (!BuiltInRegistries.ITEM.containsKey(id)) return null;
        Item item = BuiltInRegistries.ITEM.getValue(id);
        return item == null || item == Items.AIR ? null : item;
    }
}
