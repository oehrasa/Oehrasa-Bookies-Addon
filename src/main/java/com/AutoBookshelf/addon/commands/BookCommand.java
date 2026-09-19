package com.AutoBookshelf.addon.commands;

import com.AutoBookshelf.addon.utils.BookUtils;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.command.CommandSource;
import net.minecraft.item.ItemStack;

public class BookCommand extends Command {

    public BookCommand() {
        super("book", "Shows book information from your held item.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(ctx -> {
            ItemStack item = requireHeldBook();
            if (item == null) return SINGLE_SUCCESS;
            BookUtils.printBookInfo(BookUtils.checkHeldBook(item), this::info);
            return SINGLE_SUCCESS;
        });

        builder.then(literal("search")
            .then(argument("word", StringArgumentType.word())
                .executes(ctx -> {
                    ItemStack item = requireHeldBook();
                    if (item == null) return SINGLE_SUCCESS;
                    BookUtils.printSearch(BookUtils.checkHeldBook(item), StringArgumentType.getString(ctx, "word"), this::info);
                    return SINGLE_SUCCESS;
                })
            )
        );

        builder.then(literal("page")
            .then(argument("number", IntegerArgumentType.integer(1, 100))
                .executes(ctx -> {
                    ItemStack item = requireHeldBook();
                    if (item == null) return SINGLE_SUCCESS;
                    BookUtils.printPage(BookUtils.checkHeldBook(item), IntegerArgumentType.getInteger(ctx, "number"), this::info);
                    return SINGLE_SUCCESS;
                })
            )
        );

        builder.then(literal("stats")
            .executes(ctx -> {
                ItemStack item = requireHeldBook();
                if (item == null) return SINGLE_SUCCESS;
                BookUtils.printStats(BookUtils.checkHeldBook(item), this::info);
                return SINGLE_SUCCESS;
            })
        );
    }

    private ItemStack requireHeldBook() {
        ItemStack item = BookUtils.getHeldBook(mc.player);
        if (item == null) error("You must hold a written or writable book!");
        return item;
    }
}
