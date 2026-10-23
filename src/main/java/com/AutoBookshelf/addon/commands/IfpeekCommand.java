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

        // Writes the item frame's book to <gameDir>/AutoBookshelf/<title>_<timestamp>.txt, same
        // format as .booktranslate export. The optional range mirrors that command's page args.
        builder.then(literal("export")
            .executes(ctx -> {
                ItemStack item = getTargetedBookStack();
                if (item == null) return SINGLE_SUCCESS;
                BookUtils.exportBook(item, 1, -1, this::info, this::error);
                return SINGLE_SUCCESS;
            })
            .then(argument("page", IntegerArgumentType.integer(1, 100))
                .executes(ctx -> {
                    ItemStack item = getTargetedBookStack();
                    if (item == null) return SINGLE_SUCCESS;
                    int pageNum = IntegerArgumentType.getInteger(ctx, "page");
                    BookUtils.exportBook(item, pageNum, pageNum, this::info, this::error);
                    return SINGLE_SUCCESS;
                })
                .then(argument("endPage", IntegerArgumentType.integer(1, 100))
                    .executes(ctx -> {
                        ItemStack item = getTargetedBookStack();
                        if (item == null) return SINGLE_SUCCESS;
                        int start = IntegerArgumentType.getInteger(ctx, "page");
                        int end = IntegerArgumentType.getInteger(ctx, "endPage");
                        BookUtils.exportBook(item, start, end, this::info, this::error);
                        return SINGLE_SUCCESS;
                    })
                )
            )
        );

        // Translation: identical shape to .booktranslate, reading the item frame's book. Shares
        // the engine so the request throttle stays global, see BookUtils for why that
        // must not be duplicated per command.
        addTranslation(builder, BookUtils.TranslateOutput.CHAT, "lang");
        // NOT "gui": this command already has a literal("gui") that opens the frame's book, and
        // brigadier MERGES two same-named literals with the later registration winning on a bare
        // invocation so `.ifpeek gui` would have translated instead of opened. See ShelfCommand.
        addTranslation(builder, BookUtils.TranslateOutput.GUI, "gui-translate");
    }

    /**
     * Builds one translation subcommand: bare, {@code <language>}, {@code <language> <page>} and
     * {@code <language> <page> <endPage>}. Shared by the chat and GUI variants, which differ
     * only in the output.
     */
    private void addTranslation(LiteralArgumentBuilder<CommandSource> builder,
                                BookUtils.TranslateOutput output, String name) {
        builder.then(literal(name)
            .executes(ctx -> runTranslate("en", 1, -1, output))
            .then(argument("language", StringArgumentType.word())
                .suggests(BookUtils::suggestLanguages)
                .executes(ctx -> runTranslate(StringArgumentType.getString(ctx, "language"), 1, -1, output))
                .then(argument("page", IntegerArgumentType.integer(1, 100))
                    .executes(ctx -> {
                        int page = IntegerArgumentType.getInteger(ctx, "page");
                        return runTranslate(StringArgumentType.getString(ctx, "language"), page, page, output);
                    })
                    .then(argument("endPage", IntegerArgumentType.integer(1, 100))
                        .executes(ctx -> {
                            int start = IntegerArgumentType.getInteger(ctx, "page");
                            int end = IntegerArgumentType.getInteger(ctx, "endPage");
                            return runTranslate(StringArgumentType.getString(ctx, "language"), start, end, output);
                        })
                    )
                )
            )
        );
    }

    private int runTranslate(String targetLang, int startPage, int endPage, BookUtils.TranslateOutput output) {
        ItemStack item = getTargetedBookStack();
        if (item == null) return SINGLE_SUCCESS;
        BookUtils.translate(item, targetLang, startPage, endPage, output, this::info, this::error);
        return SINGLE_SUCCESS;
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
