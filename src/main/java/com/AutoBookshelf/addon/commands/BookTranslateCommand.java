package com.AutoBookshelf.addon.commands;

import com.AutoBookshelf.addon.utils.BookUtils;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.command.CommandSource;
import net.minecraft.item.ItemStack;

public class BookTranslateCommand extends Command {

    public BookTranslateCommand() {
        super("booktranslate", "Translates the held written or writable book into another language.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        // 1. Display in chat (default)
        builder.executes(ctx -> {
            runTranslation("en", 1, -1, BookUtils.TranslateOutput.CHAT);
            return SINGLE_SUCCESS;
        });
        builder.then(literal("lang")
            .then(argument("language", StringArgumentType.word())
                .suggests(BookUtils::suggestLanguages)
                .executes(ctx -> {
                    String lang = StringArgumentType.getString(ctx, "language");
                    runTranslation(lang, 1, -1, BookUtils.TranslateOutput.CHAT);
                    return SINGLE_SUCCESS;
                })
                .then(argument("page", IntegerArgumentType.integer(1))
                    .executes(ctx -> {
                        String lang = StringArgumentType.getString(ctx, "language");
                        int page = IntegerArgumentType.getInteger(ctx, "page");
                        runTranslation(lang, page, page, BookUtils.TranslateOutput.CHAT);
                        return SINGLE_SUCCESS;
                    })
                    .then(argument("endPage", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            String lang = StringArgumentType.getString(ctx, "language");
                            int start = IntegerArgumentType.getInteger(ctx, "page");
                            int end = IntegerArgumentType.getInteger(ctx, "endPage");
                            runTranslation(lang, start, end, BookUtils.TranslateOutput.CHAT);
                            return SINGLE_SUCCESS;
                        })
                    )
                )
            )
        );

        // 2. Export to .txt
        builder.then(literal("export")
            .executes(ctx -> {
                runTranslation("en", 1, -1, BookUtils.TranslateOutput.EXPORT);
                return SINGLE_SUCCESS;
            })
            .then(argument("language", StringArgumentType.word())
                .suggests(BookUtils::suggestLanguages)
                .executes(ctx -> {
                    String lang = StringArgumentType.getString(ctx, "language");
                    runTranslation(lang, 1, -1, BookUtils.TranslateOutput.EXPORT);
                    return SINGLE_SUCCESS;
                })
                .then(argument("page", IntegerArgumentType.integer(1))
                    .executes(ctx -> {
                        String lang = StringArgumentType.getString(ctx, "language");
                        int page = IntegerArgumentType.getInteger(ctx, "page");
                        runTranslation(lang, page, page, BookUtils.TranslateOutput.EXPORT);
                        return SINGLE_SUCCESS;
                    })
                    .then(argument("endPage", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            String lang = StringArgumentType.getString(ctx, "language");
                            int start = IntegerArgumentType.getInteger(ctx, "page");
                            int end = IntegerArgumentType.getInteger(ctx, "endPage");
                            runTranslation(lang, start, end, BookUtils.TranslateOutput.EXPORT);
                            return SINGLE_SUCCESS;
                        })
                    )
                )
            )
        );

        // 3. Open in book GUI (reflowed into new page count)
        builder.then(literal("gui")
            .executes(ctx -> {
                runTranslation("en", 1, -1, BookUtils.TranslateOutput.GUI);
                return SINGLE_SUCCESS;
            })
            .then(argument("language", StringArgumentType.word())
                .suggests(BookUtils::suggestLanguages)
                .executes(ctx -> {
                    String lang = StringArgumentType.getString(ctx, "language");
                    runTranslation(lang, 1, -1, BookUtils.TranslateOutput.GUI);
                    return SINGLE_SUCCESS;
                })
                .then(argument("page", IntegerArgumentType.integer(1))
                    .executes(ctx -> {
                        String lang = StringArgumentType.getString(ctx, "language");
                        int page = IntegerArgumentType.getInteger(ctx, "page");
                        runTranslation(lang, page, page, BookUtils.TranslateOutput.GUI);
                        return SINGLE_SUCCESS;
                    })
                    .then(argument("endPage", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            String lang = StringArgumentType.getString(ctx, "language");
                            int start = IntegerArgumentType.getInteger(ctx, "page");
                            int end = IntegerArgumentType.getInteger(ctx, "endPage");
                            runTranslation(lang, start, end, BookUtils.TranslateOutput.GUI);
                            return SINGLE_SUCCESS;
                        })
                    )
                )
            )
        );
    }

    private void runTranslation(String targetLang, int startPage, int endPage, BookUtils.TranslateOutput output) {
        ItemStack book = BookUtils.getHeldBook(mc.player);
        if (book == null) {
            error("You must hold a written or writable book!");
            return;
        }
        BookUtils.translate(book, targetLang, startPage, endPage, output, this::info, this::error);
    }
}
