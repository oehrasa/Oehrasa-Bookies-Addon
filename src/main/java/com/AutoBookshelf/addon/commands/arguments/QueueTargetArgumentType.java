package com.AutoBookshelf.addon.commands.arguments;

import com.AutoBookshelf.addon.utils.QueueUtil;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static net.minecraft.commands.SharedSuggestionProvider.suggest;

public class QueueTargetArgumentType implements ArgumentType<String> {
    private static final QueueTargetArgumentType INSTANCE = new QueueTargetArgumentType();
    private static final Collection<String> EXAMPLES = List.of("seasnail8169");

    public static QueueTargetArgumentType create() {
        return INSTANCE;
    }

    /**
     * Resolves the typed name back to the UUID it matched, or null if that name has no queued messages.
     */
    public static UUID get(CommandContext<?> context) {
        String name = context.getArgument("name", String.class);
        for (UUID uuid : QueueUtil.allQueued().keySet()) {
            if (QueueUtil.usernameFor(uuid).equalsIgnoreCase(name)) {
                return uuid;
            }
        }
        return null;
    }

    private QueueTargetArgumentType() {
    }

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        return reader.readString();
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        return suggest(QueueUtil.allQueued().keySet().stream().map(QueueUtil::usernameFor), builder);
    }

    @Override
    public Collection<String> getExamples() {
        return EXAMPLES;
    }
}
