package com.AutoBookshelf.addon.commands;

import com.AutoBookshelf.addon.utils.BookUtils;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

public class IfpeekCommand extends Command {
    public IfpeekCommand() {
        super("ifpeek", "Shows book information from an item frame.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(ctx -> {
            ItemStack item = getTargetedBookStack();
            if (item == null) return SINGLE_SUCCESS;
            BookUtils.printBookInfo(BookUtils.checkHeldBook(item), this::info);
            return SINGLE_SUCCESS;
        });

        // Search subcommand
        builder.then(literal("search")
            .then(argument("word", StringArgumentType.word())
                .executes(ctx -> {
                    ItemStack item = getTargetedBookStack();
                    if (item == null) return SINGLE_SUCCESS;
                    String searchWord = StringArgumentType.getString(ctx, "word");
                    BookUtils.printSearch(BookUtils.checkHeldBook(item), searchWord, this::info);
                    return SINGLE_SUCCESS;
                })
            )
        );

        // Page subcommand
        builder.then(literal("page")
            .then(argument("number", IntegerArgumentType.integer(1, 100))
                .executes(ctx -> {
                    ItemStack item = getTargetedBookStack();
                    if (item == null) return SINGLE_SUCCESS;
                    int pageNum = IntegerArgumentType.getInteger(ctx, "number");
                    BookUtils.printPage(BookUtils.checkHeldBook(item), pageNum, this::info);
                    return SINGLE_SUCCESS;
                })
            )
        );

        // Stats subcommand
        builder.then(literal("stats")
            .executes(ctx -> {
                ItemStack item = getTargetedBookStack();
                if (item == null) return SINGLE_SUCCESS;
                BookUtils.printStats(BookUtils.checkHeldBook(item), this::info);
                return SINGLE_SUCCESS;
            })
        );

        // GUI subcommand
        builder.then(literal("gui")
            .executes(ctx -> {
                ItemStack item = getTargetedBookStack();
                if (item == null) return SINGLE_SUCCESS;
                BookUtils.openBookGui(item, 1);
                return SINGLE_SUCCESS;
            })
            .then(argument("page", IntegerArgumentType.integer(1, 100))
                .executes(ctx -> {
                    ItemStack item = getTargetedBookStack();
                    if (item == null) return SINGLE_SUCCESS;
                    int pageNum = IntegerArgumentType.getInteger(ctx, "page");
                    BookUtils.openBookGui(item, pageNum);
                    return SINGLE_SUCCESS;
                })
            )
        );
    }

    // Shared targeting + validation for every subcommand — accepts both written and
    // writable (unsigned) books, since BookUtils.checkHeldBook now handles either.
    private ItemStack getTargetedBookStack() {
        if (mc.crosshairTarget == null || mc.crosshairTarget.getType() != HitResult.Type.ENTITY) {
            error("You have to point at an item frame first");
            return null;
        }

        if (!(mc.crosshairTarget instanceof EntityHitResult hitResult) ||
            !(hitResult.getEntity() instanceof ItemFrameEntity itemFrame)) {
            error("You have to point at an item frame first");
            return null;
        }

        ItemStack item = itemFrame.getHeldItemStack();
        if (item.isEmpty()) {
            error("There is no item on the item frame.");
            return null;
        }

        if (!item.isOf(Items.WRITTEN_BOOK) && !item.isOf(Items.WRITABLE_BOOK)) {
            error("This item is not a book!");
            return null;
        }

        return item;
    }
}
