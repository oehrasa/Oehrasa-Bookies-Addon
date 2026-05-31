package com.AutoBookshelf.addon.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import com.AutoBookshelf.addon.modules.AutoLogin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;
import static meteordevelopment.meteorclient.MeteorClient.mc;

public class ShelfCommand extends Command {

    private ItemStack currentBook = null;

    public ShelfCommand() {
        super("shelf", "Extracts a book from a chiseled bookshelf slot, reads it, and puts it back.");
    }

    @Override
    public void build(LiteralArgumentBuilder<SharedSuggestionProvider> builder) {
        builder.executes(ctx -> {
            AutoLogin autoLogin = Modules.get().get(AutoLogin.class);
            if (autoLogin == null) {
                error("AutoLogin module is not loaded!");
                return SINGLE_SUCCESS;
            }
            if (!autoLogin.isActive()) {
                error("AutoLogin module must be enabled!");
                return SINGLE_SUCCESS;
            }
            if (autoLogin.isProcessingBook) {
                error("Already processing a book! Please wait...");
                return SINGLE_SUCCESS;
            }

            if (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.BLOCK) {
                error("You must point at a chiseled bookshelf!");
                return SINGLE_SUCCESS;
            }

            BlockHitResult hit = (BlockHitResult) mc.hitResult;
            BlockPos pos = hit.getBlockPos();
            BlockState state = mc.level.getBlockState(pos);

            if (state.getBlock() != Blocks.CHISELED_BOOKSHELF) {
                error("You must point at a chiseled bookshelf!");
                return SINGLE_SUCCESS;
            }

            int slot = getSlotFromHit(hit);
            if (slot == -1) {
                error("Point at a specific slot in the bookshelf!");
                return SINGLE_SUCCESS;
            }

            boolean occupied = state.getValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(slot));
            if (!occupied) {
                error("Slot " + (slot + 1) + " is empty!");
                return SINGLE_SUCCESS;
            }

            autoLogin.extractAndReturn(pos, slot,
                () -> {
                    ItemStack book = mc.player.getMainHandItem();
                    if (book.isEmpty() || !book.is(Items.WRITTEN_BOOK)) {
                        error("Failed to get book!");
                        return;
                    }
                    currentBook = book.copy();
                    inspectBook(currentBook);
                },
                () -> {}
            );

            return SINGLE_SUCCESS;
        });

        builder.then(literal("cancel")
            .executes(ctx -> {
                AutoLogin autoLogin = Modules.get().get(AutoLogin.class);
                if (autoLogin == null) {
                    error("AutoLogin module is not loaded!");
                    return SINGLE_SUCCESS;
                }
                autoLogin.cancelBookExtraction();
                info("Book extraction forcibly cancelled.");
                return SINGLE_SUCCESS;
            })
        );

        builder.then(literal("search")
            .then(argument("word", StringArgumentType.word())
                .executes(ctx -> {
                    if (currentBook == null) {
                        error("No book loaded. Use .shelf first to extract a book from a bookshelf.");
                        return SINGLE_SUCCESS;
                    }
                    String searchWord = StringArgumentType.getString(ctx, "word");
                    searchInBook(currentBook, searchWord);
                    return SINGLE_SUCCESS;
                })
            )
        );

        builder.then(literal("page")
            .then(argument("number", IntegerArgumentType.integer(1, 100))
                .executes(ctx -> {
                    if (currentBook == null) {
                        error("No book loaded. Use .shelf first to extract a book from a bookshelf");
                        return SINGLE_SUCCESS;
                    }
                    int pageNum = IntegerArgumentType.getInteger(ctx, "number");
                    viewSpecificPage(currentBook, pageNum);
                    return SINGLE_SUCCESS;
                })
            )
        );

        builder.then(literal("stats")
            .executes(ctx -> {
                if (currentBook == null) {
                    error("No book loaded. Use .shelf first to extract a book from a bookshelf");
                    return SINGLE_SUCCESS;
                }
                showBookStats(currentBook);
                return SINGLE_SUCCESS;
            })
        );
    }

    private String escapePercent(String input) {
        if (input == null) return "";
        return input.replace("%", "%%");
    }

    private String addCommas(int number) {
        if (number < 1000) return String.valueOf(number);
        StringBuilder result = new StringBuilder();
        String numStr = String.valueOf(number);
        int length = numStr.length();
        for (int i = 0; i < length; i++) {
            if (i > 0 && (length - i) % 3 == 0) {
                result.append(",");
            }
            result.append(numStr.charAt(i));
        }
        return result.toString();
    }

    private String formatDecimal(double value) {
        double rounded = Math.round(value * 10) / 10.0;
        String str = String.valueOf(rounded);
        if (str.endsWith(".0")) {
            str = str.substring(0, str.length() - 2);
        }
        return str;
    }

    private void inspectBook(ItemStack book) {
        WrittenBookContent content = book.get(DataComponents.WRITTEN_BOOK_CONTENT);

        if (content == null) {
            error("This book has no content!");
            return;
        }

        String title = escapePercent(content.title().raw());
        String author = escapePercent(content.author());
        int generation = content.generation();
        List<Component> pages = content.getPages(true);

        String generationText = switch (generation) {
            case 0 -> "Original";
            case 1 -> "Copy of Original";
            case 2 -> "Copy of Copy";
            case 3 -> "Tattered";
            default -> "Unknown";
        };

        int totalChars = 0;
        int totalWords = 0;
        int emptyPages = 0;

        for (Component page : pages) {
            String text = page.getString();
            if (text.trim().isEmpty()) {
                emptyPages++;
            }
            totalChars += text.length();
            totalWords += text.split("\\s+").length;
        }

        info("§6=== Book Info ===");
        info("§7Title: §f" + title);
        info("§7Author: §f" + (author != null && !author.isEmpty() ? author : "Unknown"));
        info("§7Generated: §f" + generationText);
        info("§7Pages: §f" + pages.size() + " §7(§f" + emptyPages + " §7empty)");
        info("§7Characters: §f" + addCommas(totalChars));
        info("§7Words: §f" + addCommas(totalWords));
        info("§6================");

        int pagesToShow = Math.min(3, pages.size());
        if (pagesToShow > 0) {
            info("§7First " + pagesToShow + " page:");
            for (int i = 0; i < pagesToShow; i++) {
                String pageContent = pages.get(i).getString();
                if (pageContent.length() > 150) {
                    pageContent = pageContent.substring(0, 150) + "...";
                }
                pageContent = pageContent.replace("\n", " ").replace("\r", " ");
                pageContent = escapePercent(pageContent);
                info("§7Page " + (i + 1) + ": §f" + pageContent);
            }
        }

        if (pages.size() > pagesToShow) {
            info("§7... and §f" + (pages.size() - pagesToShow) + " §7more page(s)");
        }
    }

    private void searchInBook(ItemStack book, String searchWord) {
        WrittenBookContent content = book.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (content == null) return;

        List<Component> pages = content.getPages(true);
        List<Integer> foundPages = new ArrayList<>();
        String escapedSearchWord = escapePercent(searchWord);

        for (int i = 0; i < pages.size(); i++) {
            String pageText = pages.get(i).getString().toLowerCase();
            if (pageText.contains(searchWord.toLowerCase())) {
                foundPages.add(i + 1);
            }
        }

        if (foundPages.isEmpty()) {
            info("§cNo pages found containing: §f" + escapedSearchWord);
        } else {
            info("§aFound §f" + foundPages.size() + " §apage(s) containing §f'" + escapedSearchWord + "§f':");
            for (int page : foundPages) {
                info("§7  Page §f" + page);
            }
        }
    }

    private void viewSpecificPage(ItemStack book, int pageNum) {
        WrittenBookContent content = book.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (content == null) return;

        List<Component> pages = content.getPages(true);
        if (pageNum < 1 || pageNum > pages.size()) {
            error("Page " + pageNum + " doesn't exist, The Book has " + pages.size() + " pages");
            return;
        }

        String pageContent = escapePercent(pages.get(pageNum - 1).getString());
        info("§6=== Page " + pageNum + " of " + pages.size() + " ===");

        String[] lines = pageContent.split("\n");
        for (String line : lines) {
            if (line.length() > 60) {
                for (int i = 0; i < line.length(); i += 60) {
                    int end = Math.min(i + 60, line.length());
                    info("§f" + line.substring(i, end));
                }
            } else {
                info("§f" + line);
            }
        }
        info("§6====================");
    }

    private void showBookStats(ItemStack book) {
        WrittenBookContent content = book.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (content == null) {
            error("This book has no content!");
            return;
        }

        List<Component> pages = content.getPages(true);

        int totalChars = 0;
        int totalWords = 0;
        int emptyPages = 0;
        int longestPage = 0;
        int shortestPage = Integer.MAX_VALUE;

        Map<String, Integer> wordFrequency = new HashMap<>();
        String mostCommonWord = "";
        int mostCommonWordCount = 0;

        for (Component page : pages) {
            String text = page.getString();
            int length = text.length();
            int words = text.split("\\s+").length;

            if (text.trim().isEmpty()) {
                emptyPages++;
            }
            totalChars += length;
            totalWords += words;

            if (length > longestPage) longestPage = length;
            if (length < shortestPage) shortestPage = length;

            String[] wordsArray = text.toLowerCase().replaceAll("[^a-zA-Z0-9\\s]", "").split("\\s+");
            for (String word : wordsArray) {
                if (word.length() > 0 && !isStopWord(word)) {
                    int count = wordFrequency.getOrDefault(word, 0) + 1;
                    wordFrequency.put(word, count);
                    if (count > mostCommonWordCount) {
                        mostCommonWordCount = count;
                        mostCommonWord = word;
                    }
                }
            }
        }
        if (shortestPage == Integer.MAX_VALUE) shortestPage = 0;

        int readingTimeMinutes = totalWords / 200;
        int readingTimeSeconds = (totalWords % 200) * 3 / 10;
        String readingTime = readingTimeMinutes > 0 ?
            readingTimeMinutes + "m " + readingTimeSeconds + "s" :
            readingTimeSeconds + "s";

        double avgWordsPerSentence = 15.0;
        double avgSyllablesPerWord = totalWords > 0 ? (double) totalChars / totalWords / 3.5 : 1.0;
        double readingLevel = 0.39 * avgWordsPerSentence + 11.8 * avgSyllablesPerWord - 15.59;
        readingLevel = Math.max(1, Math.min(20, readingLevel));

        String title = escapePercent(content.title().raw());
        String author = escapePercent(content.author());
        int generation = content.generation();

        String generationText = switch (generation) {
            case 0 -> "Original";
            case 1 -> "Copy of Original";
            case 2 -> "Copy of Copy";
            case 3 -> "Tattered";
            default -> "Unknown";
        };

        info("§6=== Book Statistics ===");
        info("§7Title: §f" + title);
        info("§7Author: §f" + (author != null && !author.isEmpty() ? author : "Unknown"));
        info("§7Generation: §f" + generationText);
        info("§7Pages: §f" + pages.size() + " §7(§f" + emptyPages + " §7empty)");
        info("§7Characters: §f" + addCommas(totalChars));
        info("§7Words: §f" + addCommas(totalWords));
        info("§7Longest Page: §f" + longestPage + " §7chars");
        info("§7Shortest Page: §f" + shortestPage + " §7chars");
        info("§7Reading Time: §f" + readingTime);
        info("§7Reading Level: §f" + formatDecimal(readingLevel) + " §7(" + getReadingLevelDescription((int) readingLevel) + ")");
        if (!mostCommonWord.isEmpty()) {
            info("§7Most Common Word: §f" + mostCommonWord + " §7(x§f" + mostCommonWordCount + "§7)");
        }
        info("§6=========================");
    }

    private boolean isStopWord(String word) {
        return Set.of(
            "the", "be", "to", "of", "and", "a", "in", "that", "have", "i",
            "it", "for", "not", "on", "with", "he", "as", "you", "do", "at",
            "this", "but", "his", "by", "from", "they", "we", "say", "her", "she",
            "or", "an", "will", "my", "one", "all", "would", "there", "their", "what",
            "so", "up", "out", "if", "about", "who", "get", "which", "go", "me"
        ).contains(word);
    }

    private String getReadingLevelDescription(int level) {
        if (level <= 5) return "Very Easy";
        if (level <= 8) return "Easy";
        if (level <= 12) return "Medium";
        if (level <= 16) return "Hard";
        return "Very Hard";
    }

    private int getSlotFromHit(BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (state.getBlock() != Blocks.CHISELED_BOOKSHELF) return -1;

        Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        Vec3 hitPos = hit.getLocation();
        Vec3 relative = hitPos.subtract(pos.getX(), pos.getY(), pos.getZ());

        double u, v;
        switch (facing) {
            case NORTH -> {
                u = 1 - relative.x;
                v = relative.y;
            }
            case SOUTH -> {
                u = relative.x;
                v = relative.y;
            }
            case WEST -> {
                u = relative.z;
                v = relative.y;
            }
            case EAST -> {
                u = 1 - relative.z;
                v = relative.y;
            }
            default -> {
                return -1;
            }
        }

        u = Math.max(0, Math.min(1, u));
        v = Math.max(0, Math.min(1, v));

        int col;
        if (u < 0.375) col = 0;
        else if (u < 0.6875) col = 1;
        else col = 2;

        int row = v >= 0.5 ? 0 : 1;

        return col + row * 3;
    }
}
