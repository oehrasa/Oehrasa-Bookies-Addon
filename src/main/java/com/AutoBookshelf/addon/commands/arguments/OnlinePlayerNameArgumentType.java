package com.AutoBookshelf.addon.commands.arguments;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.client.MinecraftClient;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static net.minecraft.command.CommandSource.suggestMatching;

/**
 * Accepts any player name (cracked/offline included) but suggests the names of
 * currently online players. Mirrors EnemyArgumentType.
 */
public class OnlinePlayerNameArgumentType implements ArgumentType<String> {
    private static final OnlinePlayerNameArgumentType INSTANCE = new OnlinePlayerNameArgumentType();
    private static final Collection<String> EXAMPLES = List.of("seasnail8169", "MineGame159");

    public static OnlinePlayerNameArgumentType create() {
        return INSTANCE;
    }

    public static String get(CommandContext<?> context, String name) {
        return context.getArgument(name, String.class);
    }

    private OnlinePlayerNameArgumentType() {
    }

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        return reader.readString();
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        var handler = MinecraftClient.getInstance().getNetworkHandler();
        if (handler == null) return builder.buildFuture();
        return suggestMatching(
            handler.getPlayerList().stream().map(entry -> entry.getProfile().name()),
            builder
        );
    }

    @Override
    public Collection<String> getExamples() {
        return EXAMPLES;
    }
}