package com.AutoBookshelf.addon.commands.arguments;

import com.AutoBookshelf.addon.modules.livemessage.gui.LivemessageGui;
import com.AutoBookshelf.addon.utils.QueueUtil;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static net.minecraft.command.CommandSource.suggestMatching;

/**
 * Suggests names from every player you have DM history with (LivemessageGui chats), for .queue add. Deliberately different
 * from QueueTargetArgumentType (which only suggests names that already have something queued):
 * adds whole point is targeting someone who usually hasn't been queued for yet
 * Free text is still accepted for anyone not in the list (brand-new player); this only
 * changes what's suggested; it doesn't restrict what's allowed.
 */
public class QueueAddTargetArgumentType implements ArgumentType<String> {
    private static final QueueAddTargetArgumentType INSTANCE = new QueueAddTargetArgumentType();
    private static final Collection<String> EXAMPLES = List.of("seasnail8169");

    public static QueueAddTargetArgumentType create() {
        return INSTANCE;
    }

    private QueueAddTargetArgumentType() {
    }

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        return reader.readString();
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        return suggestMatching(LivemessageGui.chats.stream().map(QueueUtil::usernameFor), builder);
    }

    @Override
    public Collection<String> getExamples() {
        return EXAMPLES;
    }
}
