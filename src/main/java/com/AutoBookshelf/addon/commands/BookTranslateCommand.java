package com.AutoBookshelf.addon.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
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
    } // Credits to Akgezen for the command idea

    @Override
    public void build(LiteralArgumentBuilder<SharedSuggestionProvider> builder) {
        // 1. Display in chat as the default
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
        ItemStack mainHand = mc.player.getMainHandItem();
        if (mainHand != null && mainHand.is(Items.WRITTEN_BOOK)) return mainHand;
        ItemStack offHand = mc.player.getOffhandItem();
        if (offHand != null && offHand.is(Items.WRITTEN_BOOK)) return offHand;
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

                if (!root.isEmpty() && root.get(0).isJsonArray()) {
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

        WrittenBookContent content = book.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (content == null) {
            error("This book has no content!");
            return;
        }

        List<Component> allPages = content.getPages(true);
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

        List<Component> pagesToTranslate = new ArrayList<>();
        for (int i = startPage - 1; i < endPage; i++) pagesToTranslate.add(allPages.get(i));

        final String rangeInfo = (startPage == endPage)
            ? "page " + startPage
            : "pages " + startPage + "-" + endPage;
        info("§6Translating " + rangeInfo + " of " + totalPages + " to " + targetLang + "...");

        // Translate each page individually – avoids HTTP 400 from oversized requests
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (Component page : pagesToTranslate) {
            futures.add(translateTextAsync(targetLang, page.getString()));
        }

        int finalStartPage = startPage;
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenAccept(v -> {
                List<String> translatedPages = new ArrayList<>();
                for (CompletableFuture<String> future : futures) {
                    String translated = future.join(); // safe because all futures are done
                    if (translated == null) {
                        mc.executeIfPossible(() -> error("Translation failed for one or more pages."));
                        return;
                    }
                    translatedPages.add(translated);
                }

                mc.executeIfPossible(() -> {
                    info("§6<Translated Book (" + rangeInfo + ")>");
                    for (int i = 0; i < translatedPages.size(); i++) {
                        int actualPage = finalStartPage + i;
                        info("§7--- Page " + actualPage + " ---");
                        for (String line : translatedPages.get(i).split("\n")) {
                            if (line.length() > 120) {
                                for (int j = 0; j < line.length(); j += 120) {
                                    info("§f" + line.substring(j, Math.min(j + 120, line.length())));
                                }
                            } else {
                                info("§f" + line);
                            }
                        }
                    }
                    info("§6========================");
                });
            })
            .exceptionally(e -> {
                mc.executeIfPossible(() -> error("Translation error: " + e.getMessage()));
                return null;
            });
    }

    private void exportTranslation(String targetLang, int startPage, int endPage, String customFilename) {
        ItemStack book = getHeldBook();
        if (book == null) {
            error("You must hold a written book!");
            return;
        }

        WrittenBookContent content = book.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (content == null) {
            error("This book has no content!");
            return;
        }

        String rawTitle = content.title().raw();
        String safeTitle = rawTitle.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (safeTitle.length() > 50) safeTitle = safeTitle.substring(0, 50);

        List<Component> allPages = content.getPages(true);
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

        List<Component> pagesToTranslate = new ArrayList<>();
        for (int i = startPage - 1; i < endPage; i++) pagesToTranslate.add(allPages.get(i));

        final String rangeInfo = (startPage == endPage)
            ? "page " + startPage
            : "pages " + startPage + "-" + endPage;
        info("§6Translating and exporting " + rangeInfo + " of " + totalPages + " to " + targetLang + "...");

        // Translate each page individually, avoids HTTP 400 from oversized requests.
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (Component page : pagesToTranslate) {
            futures.add(translateTextAsync(targetLang, page.getString()));
        }

        int finalStartPage = startPage;
        String finalSafeTitle = safeTitle;

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenAccept(v -> {
                List<String> translatedPages = new ArrayList<>();
                for (CompletableFuture<String> future : futures) {
                    String translated = future.join();
                    if (translated == null) {
                        mc.executeIfPossible(() -> error("Translation failed for one or more pages."));
                        return;
                    }
                    translatedPages.add(translated);
                }

                // Build file content with page numbers
                StringBuilder fileContent = new StringBuilder();
                for (int i = 0; i < translatedPages.size(); i++) {
                    int pageNum = finalStartPage + i;
                    fileContent.append("===== Page ").append(pageNum).append(" =====\n\n");
                    fileContent.append(translatedPages.get(i).trim()).append("\n\n");
                }

                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
                String fileName = finalSafeTitle + "_" + timestamp + ".txt";
                Path outDir = mc.gameDirectory.toPath().resolve("AutoBookshelf");

                try {
                    Files.createDirectories(outDir);
                    Path outFile = outDir.resolve(fileName);
                    Files.writeString(outFile, fileContent.toString(), StandardCharsets.UTF_8);
                    mc.executeIfPossible(() -> info("§aExported translation to §f" + outFile.toAbsolutePath()));
                } catch (Exception e) {
                    mc.executeIfPossible(() -> error("Failed to write file: " + e.getMessage()));
                }
            })
            .exceptionally(e -> {
                mc.executeIfPossible(() -> error("Export error: " + e.getMessage()));
                return null;
            });
    }
}
