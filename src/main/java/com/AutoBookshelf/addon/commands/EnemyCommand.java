package com.AutoBookshelf.addon.commands;

import com.AutoBookshelf.addon.commands.arguments.EnemyArgumentType;
import com.AutoBookshelf.addon.utils.Enemy;
import com.AutoBookshelf.addon.utils.EnemyManager;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.commands.arguments.PlayerListEntryArgumentType;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;

public class EnemyCommand extends Command {
    public EnemyCommand() {
        super("enemy", "Manage friends marked as enemies", new String[0]);
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder.then(literal("add")
            .then(argument("player", PlayerListEntryArgumentType.create())
                .executes(context -> {
                    GameProfile profile = PlayerListEntryArgumentType.get(context).getProfile();
                    Enemy enemy = new Enemy(profile.name(), profile.id());

                    if (EnemyManager.get().add(enemy)) {
                        info("Marked (highlight)%s(default) as an enemy.", enemy.getName());
                    } else {
                        error("Already marked (highlight)%s(default) as an enemy.", profile.name());
                    }

                    return SINGLE_SUCCESS;
                })
            )
            .then(argument("name", StringArgumentType.word())
                .executes(context -> {
                    String name = context.getArgument("name", String.class);

                    if (EnemyManager.get().add(name)) {
                        info("Marked (highlight)%s(default) as an enemy.", name);
                    } else {
                        error("Already marked (highlight)%s(default) as an enemy, or invalid name.", name);
                    }

                    return SINGLE_SUCCESS;
                })
            )
        );

        builder.then(literal("remove")
            .then(argument("enemy", EnemyArgumentType.create())
                .executes(context -> {
                    Enemy enemy = EnemyArgumentType.get(context);
                    if (enemy == null) {
                        error("Not marked as an enemy.");
                        return SINGLE_SUCCESS;
                    }

                    if (EnemyManager.get().remove(enemy)) {
                        info("Removed (highlight)%s(default) from enemies.", enemy.getName());
                    } else {
                        error("Failed to remove (highlight)%s(default) from enemies.", enemy.getName());
                    }

                    return SINGLE_SUCCESS;
                })
            )
        );

        builder.then(literal("list").executes(context -> {
            if (EnemyManager.get().isEmpty()) {
                info("No enemies marked.");
                return SINGLE_SUCCESS;
            }

            info("--- Enemies ((highlight)%s(default)) ---", EnemyManager.get().count());
            for (String name : EnemyManager.get().getEnemyNames()) {
                info("(highlight)%s", name);
            }

            return SINGLE_SUCCESS;
        }));

        builder.then(literal("clear").executes(context -> {
            int count = EnemyManager.get().count();
            if (count == 0) {
                info("No enemies to clear.");
                return SINGLE_SUCCESS;
            }

            EnemyManager.get().clear();
            info("Cleared (highlight)%d(default) enemies.", count);
            return SINGLE_SUCCESS;
        }));
    }
}
