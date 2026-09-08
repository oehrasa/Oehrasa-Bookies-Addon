package com.AutoBookshelf.addon.commands;

import com.AutoBookshelf.addon.commands.arguments.QueueAddTargetArgumentType;
import com.AutoBookshelf.addon.commands.arguments.QueueTargetArgumentType;
import com.AutoBookshelf.addon.modules.livemessage.gui.ChatWindow;
import com.AutoBookshelf.addon.modules.livemessage.util.LiveProfileCache;
import com.AutoBookshelf.addon.utils.QueueUtil;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.command.CommandSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class QueueCommand extends Command {
    public QueueCommand() {
        super("queue", "Manage queued (offline-pending) DM messages.", new String[0]);
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        // .queue del <name> [match text]
        builder.then(literal("del")
            .then(argument("name", QueueTargetArgumentType.create())
                .executes(context -> {
                    UUID uuid = QueueTargetArgumentType.get(context);
                    if (uuid == null) {
                        error("No queued messages found for that name.");
                        return SINGLE_SUCCESS;
                    }

                    int removed = QueueUtil.deleteQueued(uuid, null);
                    info("Cleared (highlight)%d(default) queued message(s) for (highlight)%s(default).", removed, QueueUtil.usernameFor(uuid));
                    return SINGLE_SUCCESS;
                })
                .then(argument("match", StringArgumentType.greedyString())
                    .executes(context -> {
                        UUID uuid = QueueTargetArgumentType.get(context);
                        if (uuid == null) {
                            error("No queued messages found for that name.");
                            return SINGLE_SUCCESS;
                        }

                        String match = context.getArgument("match", String.class);
                        int removed = QueueUtil.deleteQueued(uuid, match);
                        if (removed == 0) {
                            error("No queued message matching '%s' found for (highlight)%s(default).", match, QueueUtil.usernameFor(uuid));
                        } else {
                            info("Removed a queued message for (highlight)%s(default) matching '%s'.", QueueUtil.usernameFor(uuid), match);
                        }
                        return SINGLE_SUCCESS;
                    })
                )
            )
        );

        // .queue info <all|name>
        builder.then(literal("info")
            .then(literal("all").executes(context -> {
                Map<UUID, List<ChatWindow.ChatMessage>> all = QueueUtil.allQueued();
                if (all.isEmpty()) {
                    info("No queued messages.");
                    return SINGLE_SUCCESS;
                }

                info("Queued messages ((highlight)%d(default) players)", all.size());
                for (Map.Entry<UUID, List<ChatWindow.ChatMessage>> entry : all.entrySet()) {
                    info("(highlight)%s(default): %d queued", QueueUtil.usernameFor(entry.getKey()), entry.getValue().size());
                }
                return SINGLE_SUCCESS;
            }))
            .then(argument("name", QueueTargetArgumentType.create())
                .executes(context -> {
                    UUID uuid = QueueTargetArgumentType.get(context);
                    if (uuid == null) {
                        error("No queued messages found for that name.");
                        return SINGLE_SUCCESS;
                    }

                    List<ChatWindow.ChatMessage> msgs = QueueUtil.queuedFor(uuid);
                    info("Queued for (highlight)%s(default) ((highlight)%d(default))", QueueUtil.usernameFor(uuid), msgs.size());
                    for (ChatWindow.ChatMessage m : msgs) {
                        info(" - %s", m.message);
                    }
                    return SINGLE_SUCCESS;
                })
            )
        );

        // .queue add <name> <message...>
        builder.then(literal("add")
            .then(argument("name", QueueAddTargetArgumentType.create())
                .then(argument("message", StringArgumentType.greedyString())
                    .executes(context -> {
                        String name = context.getArgument("name", String.class);
                        String message = context.getArgument("message", String.class);

                        LiveProfileCache.LiveProfile profile = LiveProfileCache.getLiveprofileFromName(name);
                        if (profile == null) {
                            error("Could not resolve a player named (highlight)%s(default).", name);
                            return SINGLE_SUCCESS;
                        }

                        QueueUtil.addQueued(profile.uuid, message);
                        info("Queued a message for (highlight)%s(default).", profile.username);
                        return SINGLE_SUCCESS;
                    })
                )
            )
        );
    }
}
