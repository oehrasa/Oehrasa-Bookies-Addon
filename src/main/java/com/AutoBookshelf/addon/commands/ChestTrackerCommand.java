package com.AutoBookshelf.addon.commands;

import com.AutoBookshelf.addon.modules.chesttracker.ChestTrackerDataV2;
import com.AutoBookshelf.addon.modules.chesttracker.ChestTrackerModule;
import com.AutoBookshelf.addon.modules.chesttracker.TrackedContainer;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Locale;

/**
 * Ported from BepHax's {@code bep.hax.commands.ChestTrackerCommand}.
 *
 * <p>Differences from the reference, all forced by our fork:
 * <ul>
 *   <li>{@code getSharedData()} -> {@link ChestTrackerModule#getData()}; we have no
 *       {@code ChestTrackerDataManager} singleton (single consumer).</li>
 *   <li>Meteor formatting ({@code (highlight)>/<default>}) instead of raw § codes.</li>
 *   <li>{@code findItem} tries an exact path match before the registry-order substring
 *       fallback, so "iron" no longer resolves to whatever item happens to be iterated
 *       first, and it accepts an explicit {@code namespace:path}.</li>
 * </ul>
 *
 * <p>Note this command deliberately does <b>not</b> leak coordinates to anyone else -
 * everything it prints goes to the local chat buffer only.
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

        builder.then(literal("clear").then(literal("all").executes(context -> {
            ChestTrackerModule module = getModule();
            if (module == null) return SINGLE_SUCCESS;

            module.getData().clearAll();
            info("Cleared all tracked containers.");
            return SINGLE_SUCCESS;
        })).then(literal("dimension").executes(context -> {
            ChestTrackerModule module = getModule();
            if (module == null) return SINGLE_SUCCESS;

            module.getData().clearCurrentDimension();
            info("Cleared tracked containers in the current dimension.");
            return SINGLE_SUCCESS;
        })));

        builder.then(literal("export").executes(context -> {
            ChestTrackerModule module = getModule();
            if (module == null) return SINGLE_SUCCESS;

            String filename = "export_" + java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".json";
            try {
                module.getData().exportData(filename);
                info("Exported to (highlight)%s(default).", filename);
            } catch (Exception e) {
                error("Export failed: (highlight)%s(default).", String.valueOf(e.getMessage()));
            }

            return SINGLE_SUCCESS;
        }));

        builder.then(literal("nearby").then(argument("radius", IntegerArgumentType.integer(1, 128)).executes(context -> {
            ChestTrackerModule module = getModule();
            if (module == null) return SINGLE_SUCCESS;

            if (mc.player == null) {
                error("Not in-game.");
                return SINGLE_SUCCESS;
            }

            int radius = IntegerArgumentType.getInteger(context, "radius");
            List<TrackedContainer> all = module.getData().getAllContainers();
            double renderDistance = module.getRenderDistance();

            // Collected then sorted by distance: the underlying map iteration order is a
            // hash order, which would print the list in a meaningless order.
            all.sort((a, b) -> Double.compare(distanceTo(a), distanceTo(b)));

            info("Nearby tracked containers within (highlight)%d(default)m:", radius);

            int found = 0;
            for (TrackedContainer container : all) {
                double distance = distanceTo(container);
                if (distance > radius) continue;

                BlockPos pos = container.getPosition();
                String color = distance <= renderDistance ? "§a" : "§7";
                info("%s[%d, %d, %d] §e%.0fm §b%d item(s)%s",
                    color, pos.getX(), pos.getY(), pos.getZ(),
                    distance, container.getItems().size(),
                    container.isEmpty() ? " §c(empty)" : "");
                found++;
            }

            if (found == 0) info("None found.");
            else info("(highlight)%d(default) found.", found);

            return SINGLE_SUCCESS;
        })));
    }

    /**
     * Returns the module if it is loaded and enabled, otherwise reports and returns null.
     */
    private ChestTrackerModule getModule() {
        ChestTrackerModule module = Modules.get().get(ChestTrackerModule.class);
        if (module == null || !module.isActive()) {
            error("Chest-Tracker module is not enabled.");
            return null;
        }
        return module;
    }

    private double distanceTo(TrackedContainer container) {
        BlockPos pos = container.getPosition();
        return Math.sqrt(mc.player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
    }

    private void searchAndDisplay(ChestTrackerModule module, Item item) {
        ChestTrackerDataV2 data = module.getData();
        List<TrackedContainer> results = data.searchItem(item);
        String name = item.getName(new ItemStack(item)).getString();

        if (results.isEmpty()) {
            info("No tracked container holds (highlight)%s(default).", name);
            return;
        }

        String itemId = BuiltInRegistries.ITEM.getKey(item).toString();
        double renderDistance = module.getRenderDistance();
        double renderDistanceSq = renderDistance * renderDistance;

        int total = 0;
        int inRenderDistance = 0;
        for (TrackedContainer container : results) {
            total += container.getItemCount(itemId);

            BlockPos pos = container.getPosition();
            if (mc.player != null
                && mc.player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= renderDistanceSq) {
                inRenderDistance++;
            }
        }

        info("(highlight)%s(default) in (highlight)%d(default) container(s).",
            String.format("%,d", total), results.size());
        if (inRenderDistance < results.size()) {
            info("Within render distance: (highlight)%d(default), outside: (highlight)%d(default).",
                inRenderDistance, results.size() - inRenderDistance);
        }

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
