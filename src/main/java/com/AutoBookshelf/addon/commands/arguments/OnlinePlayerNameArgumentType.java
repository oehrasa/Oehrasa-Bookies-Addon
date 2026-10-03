package com.AutoBookshelf.addon.commands.arguments;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

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

    // 26.1.2 dropped CommandSource#suggestMatching, so the names are offered through the
    // builder directly; same prefix-matching behaviour, no registry lookup needed.
    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) return builder.buildFuture();
        String remaining = builder.getRemainingLowerCase();
        connection.getOnlinePlayers().stream()
            .map(entry -> entry.getProfile().name())
            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(remaining))
            .forEach(builder::suggest);
        return builder.buildFuture();
    }

    @Override
    public Collection<String> getExamples() {
        return EXAMPLES;
    }
}
