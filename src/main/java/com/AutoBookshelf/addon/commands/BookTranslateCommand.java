package com.AutoBookshelf.addon.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.command.CommandSource;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class BookTranslateCommand extends Command {

    public BookTranslateCommand() {
        super("booktranslate", "Translates the held written book into another language.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        // 1. Display in chat (default)
        builder.executes(ctx -> {
            displayTranslation("en", 1, -1);
            return SINGLE_SUCCESS;
        });
        builder.then(argument("language", StringArgumentType.word())
            .executes(ctx -> {
                String lang = StringArgumentType.getString(ctx, "language");
                displayTranslation(lang, 1, -1);
                return SINGLE_SUCCESS;
            })
            .then(argument("page", IntegerArgumentType.integer(1))
                .executes(ctx -> {
                    String lang = StringArgumentType.getString(ctx, "language");
                    int page = IntegerArgumentType.getInteger(ctx, "page");
                    displayTranslation(lang, page, page);
                    return SINGLE_SUCCESS;
                })
                .then(argument("endPage", IntegerArgumentType.integer(1))
                    .executes(ctx -> {
                        String lang = StringArgumentType.getString(ctx, "language");
                        int start = IntegerArgumentType.getInteger(ctx, "page");
                        int end = IntegerArgumentType.getInteger(ctx, "endPage");
                        if (start > end) { int temp = start; start = end; end = temp; }
                        displayTranslation(lang, start, end);
                        return SINGLE_SUCCESS;
                    })
                )
            )
        );

        // 2. Export to .txt
        builder.then(literal("export")
            .executes(ctx -> {
                exportTranslation("en", 1, -1, null);
                return SINGLE_SUCCESS;
            })
            .then(argument("language", StringArgumentType.word())
                .executes(ctx -> {
                    String lang = StringArgumentType.getString(ctx, "language");
                    exportTranslation(lang, 1, -1, null);
                    return SINGLE_SUCCESS;
                })
                .then(argument("page", IntegerArgumentType.integer(1))
                    .executes(ctx -> {
                        String lang = StringArgumentType.getString(ctx, "language");
                        int page = IntegerArgumentType.getInteger(ctx, "page");
                        exportTranslation(lang, page, page, null);
                        return SINGLE_SUCCESS;
                    })
                    .then(argument("endPage", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            String lang = StringArgumentType.getString(ctx, "language");
                            int start = IntegerArgumentType.getInteger(ctx, "page");
                            int end = IntegerArgumentType.getInteger(ctx, "endPage");
                            if (start > end) { int temp = start; start = end; end = temp; }
                            exportTranslation(lang, start, end, null);
                            return SINGLE_SUCCESS;
                        })
                    )
                )
            )
        );
    }

    private ItemStack getHeldBook() {
        if (mc.player == null || mc.player.getInventory() == null) return null;
        ItemStack mainHand = mc.player.getMainHandStack();
        if (mainHand != null && mainHand.isOf(Items.WRITTEN_BOOK)) return mainHand;
        ItemStack offHand = mc.player.getOffHandStack();
        if (offHand != null && offHand.isOf(Items.WRITTEN_BOOK)) return offHand;
        return null;
    }

    private CompletableFuture<String> translateTextAsync(String targetLang, String textToTranslate) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String urlString = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl="
                    + URLEncoder.encode(targetLang, StandardCharsets.UTF_8)
                    + "&dt=t&q=" + URLEncoder.encode(textToTranslate, StandardCharsets.UTF_8);
                InputStream stream = new URI(urlString).toURL().openStream();
                String raw = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                stream.close();

                JsonArray root = JsonParser.parseString(raw).getAsJsonArray();
                StringBuilder result = new StringBuilder();

                if (root.size() > 0 && root.get(0).isJsonArray()) {
                    JsonArray segments = root.get(0).getAsJsonArray();
                    for (JsonElement el : segments) {
                        if (el.isJsonArray()) {
                            JsonArray segment = el.getAsJsonArray();
                            if (!segment.isEmpty()) {
                                String translatedSegment = segment.get(0).getAsString();
                                if (translatedSegment != null && !translatedSegment.isEmpty()) {
                                    result.append(translatedSegment);
                                }
                            }
                        }
                    }
                }
                return result.toString();
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        });
    }

    private void displayTranslation(String targetLang, int startPage, int endPage) {
        ItemStack book = getHeldBook();
        if (book == null) {
            error("You must hold a written book!");
            return;
        }

        WrittenBookContentComponent content = book.get(DataComponentTypes.WRITTEN_BOOK_CONTENT);
        if (content == null) {
            error("This book has no content!");
            return;
        }

        List<Text> allPages = content.getPages(true);
        if (allPages.isEmpty()) {
            error("The book is empty.");
            return;
        }

        int totalPages = allPages.size();
        if (endPage == -1) endPage = totalPages;
        if (startPage < 1) startPage = 1;
        if (endPage > totalPages) endPage = totalPages;
        if (startPage > endPage) {
            error("Invalid page range.");
            return;
        }

        List<Text> pagesToTranslate = new ArrayList<>();
        for (int i = startPage - 1; i < endPage; i++) pagesToTranslate.add(allPages.get(i));

        final String rangeInfo = (startPage == endPage)
            ? "page " + startPage
            : "pages " + startPage + "-" + endPage;
        info("§6Translating " + rangeInfo + " of " + totalPages + " to " + targetLang + "...");

        StringBuilder fullText = new StringBuilder();
        for (int i = 0; i < pagesToTranslate.size(); i++) {
            if (i > 0) fullText.append("\n§§§PAGEBREAK§§§\n");
            fullText.append(pagesToTranslate.get(i).getString());
        }

        int finalStartPage = startPage;
        translateTextAsync(targetLang, fullText.toString()).thenAccept(translated -> mc.executeSync(() -> {
            if (translated == null) {
                error("Translation failed (API may be overloaded).");
                return;
            }

            String[] pageArray = translated.split("\\n?§§§PAGEBREAK§§§\\n?");
            info("§6<Translated Book (" + rangeInfo + ")>");
            for (int i = 0; i < pageArray.length; i++) {
                int actualPage = finalStartPage + i;
                info("§7--- Page " + actualPage + " ---");
                for (String line : pageArray[i].split("\n")) {
                    if (line.length() > 120) {
                        for (int j = 0; j < line.length(); j += 120) {
                            info("§f" + line.substring(j, Math.min(j + 120, line.length())));
                        }
                    } else {
                        info("§f" + line);
                    }
                }
            }
            info("§6===========================");
        }));
    }

    private void exportTranslation(String targetLang, int startPage, int endPage, String customFilename) {
        ItemStack book = getHeldBook();
        if (book == null) {
            error("You must hold a written book!");
            return;
        }

        WrittenBookContentComponent content = book.get(DataComponentTypes.WRITTEN_BOOK_CONTENT);
        if (content == null) {
            error("This book has no content!");
            return;
        }

        String rawTitle = content.title().raw();
        String safeTitle = rawTitle.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (safeTitle.length() > 50) safeTitle = safeTitle.substring(0, 50);

        List<Text> allPages = content.getPages(true);
        if (allPages.isEmpty()) {
            error("The book is empty.");
            return;
        }

        int totalPages = allPages.size();
        if (endPage == -1) endPage = totalPages;
        if (startPage < 1) startPage = 1;
        if (endPage > totalPages) endPage = totalPages;
        if (startPage > endPage) {
            error("Invalid page range.");
            return;
        }

        List<Text> pagesToTranslate = new ArrayList<>();
        for (int i = startPage - 1; i < endPage; i++) pagesToTranslate.add(allPages.get(i));

        final String rangeInfo = (startPage == endPage)
            ? "page " + startPage
            : "pages " + startPage + "-" + endPage;
        info("§6Translating and exporting " + rangeInfo + " of " + totalPages + " to " + targetLang + "...");

        StringBuilder fullText = new StringBuilder();
        for (int i = 0; i < pagesToTranslate.size(); i++) {
            if (i > 0) fullText.append("\n§§§PAGEBREAK§§§\n");
            fullText.append(pagesToTranslate.get(i).getString());
        }

        int finalStartPage = startPage;
        String finalSafeTitle = safeTitle;

        translateTextAsync(targetLang, fullText.toString()).thenAccept(translated -> mc.executeSync(() -> {
            if (translated == null) {
                error("Translation failed. (API may be overloaded)");
                return;
            }

            // 2. Replace PAGEBREAK markers with page numbers
            String[] pageArray = translated.split("\\n?§§§PAGEBREAK§§§\\n?");
            StringBuilder fileContent = new StringBuilder();
            for (int i = 0; i < pageArray.length; i++) {
                int pageNum = finalStartPage + i;
                fileContent.append("===== Page ").append(pageNum).append(" =====\n\n");
                fileContent.append(pageArray[i].trim()).append("\n\n");
            }

            // 3. Build output filename with timestamp
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd_HH-mm-ss"));
            String fileName = finalSafeTitle + "_" + timestamp + ".txt";

            // 4. Create output directory and write file
            Path outDir = mc.runDirectory.toPath().resolve("AutoBookshelf");
            try {
                Files.createDirectories(outDir);
                Path outFile = outDir.resolve(fileName);
                Files.writeString(outFile, fileContent.toString(), StandardCharsets.UTF_8);
                info("§aExported translation to §f" + outFile.toAbsolutePath());
            } catch (Exception e) {
                error("Failed to write file: " + e.getMessage());
            }
        }));
    }
}
