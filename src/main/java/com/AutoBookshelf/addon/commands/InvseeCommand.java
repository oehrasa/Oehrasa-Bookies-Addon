package com.AutoBookshelf.addon.commands;

import com.AutoBookshelf.addon.modules.InventoryTracker;
import com.AutoBookshelf.addon.utils.InventoryTrackerScreen;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.player.PlayerEntity;

public class InvseeCommand extends Command {

    public InvseeCommand() {
        super("invsee", "View the tracked inventory of another player.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        // .invsee list that show all currently tracked players
        builder.then(literal("list")
            .executes(ctx -> {
                InventoryTracker mod = requireTracker();
                if (mod == null) return SINGLE_SUCCESS;

                if (mod.playerMap.isEmpty()) {
                    info("No players tracked yet. Players must equip or swap items while in range.");
                    return SINGLE_SUCCESS;
                }

                info("Tracked players (" + mod.playerMap.size() + "):");
                for (PlayerEntity p : mod.playerMap.values()) {
                    info("  §f" + p.getName().getString());
                }
                return SINGLE_SUCCESS;
            })
        );

        // .invsee <name> to open the inventory screen for a tracked player
        builder.then(argument("name", StringArgumentType.word())
            .suggests((ctx, sb) -> {
                InventoryTracker mod = Modules.get().get(InventoryTracker.class);
                // Suggest live world players
                if (mc.world != null) {
                    mc.world.getPlayers().stream()
                        .filter(p -> p != mc.player)
                        .map(p -> p.getName().getString())
                        .forEach(sb::suggest);
                }
                // Also suggest saved (out-of-range) players
                if (mod != null) {
                    mod.playerMap.values().stream()
                        .map(p -> p.getName().getString())
                        .forEach(sb::suggest);
                }
                return sb.buildFuture();
            })
            .executes(ctx -> {
                String name = StringArgumentType.getString(ctx, "name");
                InventoryTracker mod = requireTracker();
                if (mod == null) return SINGLE_SUCCESS;

                // Live world players take priority; saved playerMap is the fallback
                // for players who have since left render distance.
                PlayerEntity target = findPlayer(mod, name);
                if (target == null) {
                    error("Player '" + name + "' not found in world or saved data.");
                    return SINGLE_SUCCESS;
                }

                InventoryTracker.TrackedInventory tracked = mod.getTracked(target.getUuid());
                if (tracked == null || tracked.items.isEmpty()) {
                    error("No tracked data for '" + name + "' yet, and they need to equip or swap items while in render distance.");
                    return SINGLE_SUCCESS;
                }

                PlayerEntity finalTarget = target;
                mc.execute(() -> mc.setScreen(new InventoryTrackerScreen(finalTarget, tracked)));
                return SINGLE_SUCCESS;
            })
        );
    }

    /**
     * Returns the active tracker module.
     */
    private InventoryTracker requireTracker() {
        InventoryTracker mod = Modules.get().get(InventoryTracker.class);
        if (mod == null || !mod.isActive()) {
            error("Inventory-Tracker module must be enabled.");
            return null;
        }
        return mod;
    }

    private PlayerEntity findPlayer(InventoryTracker mod, String name) {
        if (mc.world != null) {
            for (PlayerEntity p : mc.world.getPlayers()) {
                if (p.getName().getString().equalsIgnoreCase(name)) return p;
            }
        }
        for (PlayerEntity p : mod.playerMap.values()) {
            if (p.getName().getString().equalsIgnoreCase(name)) return p;
        }
        return null;
    }
}
