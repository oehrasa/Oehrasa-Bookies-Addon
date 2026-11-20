/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.commands;

import com.AutoBookshelf.addon.utils.BookUtils;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
//? if >=26.1 {
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
//?} else {
/*import net.minecraft.commands.SharedSuggestionProvider;
 *///?}
import net.minecraft.world.item.ItemStack;

public class BookCommand extends Command {

    public BookCommand() {
        super("book", "Shows book information from your held item.");
    }

    @Override
        //? if >=26.1 {
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        //?} else {
        /*public void build(LiteralArgumentBuilder<SharedSuggestionProvider> builder) {
         *///?}
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

        // Writes the held book to <gameDir>/AutoBookshelf/<title>_<timestamp>.txt, same format as
        // .booktranslate export.
        builder.then(literal("export")
            .executes(ctx -> {
                ItemStack item = requireHeldBook();
                if (item == null) return SINGLE_SUCCESS;
                BookUtils.exportBook(item, 1, -1, this::info, this::error);
                return SINGLE_SUCCESS;
            })
            .then(argument("page", IntegerArgumentType.integer(1, 100))
                .executes(ctx -> {
                    ItemStack item = requireHeldBook();
                    if (item == null) return SINGLE_SUCCESS;
                    int pageNum = IntegerArgumentType.getInteger(ctx, "page");
                    BookUtils.exportBook(item, pageNum, pageNum, this::info, this::error);
                    return SINGLE_SUCCESS;
                })
                .then(argument("endPage", IntegerArgumentType.integer(1, 100))
                    .executes(ctx -> {
                        ItemStack item = requireHeldBook();
                        if (item == null) return SINGLE_SUCCESS;
                        int start = IntegerArgumentType.getInteger(ctx, "page");
                        int end = IntegerArgumentType.getInteger(ctx, "endPage");
                        BookUtils.exportBook(item, start, end, this::info, this::error);
                        return SINGLE_SUCCESS;
                    })
                )
            )
        );
    }

    private ItemStack requireHeldBook() {
        ItemStack item = BookUtils.getHeldBook(mc.player);
        if (item == null) error("You must hold a written or writable book!");
        return item;
    }
}
