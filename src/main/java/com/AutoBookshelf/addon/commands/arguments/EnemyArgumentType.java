package com.AutoBookshelf.addon.commands.arguments;

import com.AutoBookshelf.addon.utils.Enemy;
import com.AutoBookshelf.addon.utils.EnemyManager;
import com.google.common.collect.Streams;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static net.minecraft.commands.SharedSuggestionProvider.suggest;

public class EnemyArgumentType implements ArgumentType<String> {
    private static final EnemyArgumentType INSTANCE = new EnemyArgumentType();
    private static final Collection<String> EXAMPLES = List.of("seasnail8169", "MineGame159");

    public static EnemyArgumentType create() {
        return INSTANCE;
    }

    public static Enemy get(CommandContext<?> context) {
        return EnemyManager.get().get(context.getArgument("enemy", String.class));
    }

    private EnemyArgumentType() {
    }

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        return reader.readString();
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        return suggest(Streams.stream(EnemyManager.get()).map(Enemy::getName), builder);
    }

    @Override
    public Collection<String> getExamples() {
        return EXAMPLES;
    }
}
