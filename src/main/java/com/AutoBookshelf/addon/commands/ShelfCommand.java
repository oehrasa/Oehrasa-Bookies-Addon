package com.AutoBookshelf.addon.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChiseledBookshelfBlock;
import net.minecraft.command.CommandSource;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

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
    private boolean isExtracting = false;

    public ShelfCommand() {
        super("shelf", "Extracts a book from a chiseled bookshelf slot, reads it, and puts it back");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(ctx -> {
            if (isExtracting) {
                error("Already processing a book! Please wait...");
                return SINGLE_SUCCESS;
            }

            if (mc.crosshairTarget == null || mc.crosshairTarget.getType() != HitResult.Type.BLOCK) {
                error("You must point at a chiseled bookshelf!");
                return SINGLE_SUCCESS;
            }

            BlockHitResult hit = (BlockHitResult) mc.crosshairTarget;
            BlockPos pos = hit.getBlockPos();
            BlockState state = mc.world.getBlockState(pos);

            if (state.getBlock() != Blocks.CHISELED_BOOKSHELF) {
                error("You must point at a chiseled bookshelf!");
                return SINGLE_SUCCESS;
            }

            int slot = getSlotFromHit(hit);
            if (slot == -1) {
                error("Point at a specific slot in the bookshelf!");
                return SINGLE_SUCCESS;
            }

            boolean occupied = state.get(ChiseledBookshelfBlock.SLOT_OCCUPIED_PROPERTIES.get(slot));
            if (!occupied) {
                error("Slot " + (slot + 1) + " is empty!");
                return SINGLE_SUCCESS;
            }

            AutoLogin autoLogin = Modules.get().get(AutoLogin.class);
            if (autoLogin == null) {
                error("AutoLogin module is not loaded!");
                return SINGLE_SUCCESS;
            }

            if (!autoLogin.isActive()) {
                error("AutoLogin module must be enabled!");
                return SINGLE_SUCCESS;
            }

            isExtracting = true;

            autoLogin.extractAndReturn(pos, slot,
                () -> {
                    ItemStack book = mc.player.getMainHandStack();
                    if (book.isEmpty() || !book.isOf(Items.WRITTEN_BOOK)) {
                        error("Failed to get book!");
                        isExtracting = false;
                        return;
                    }
                    currentBook = book.copy();
                    inspectBook(currentBook);
                },
                () -> {
                    isExtracting = false;
                }
            );

            return SINGLE_SUCCESS;
        });

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

    // Helper to escape % symbols
    private String escapePercent(String input) {
        if (input == null) return "";
        return input.replace("%", "%%");
    }

    // Helper to add commas without String.format
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

    // Helper to format decimal without String.format
    private String formatDecimal(double value) {
        double rounded = Math.round(value * 10) / 10.0;
        String str = String.valueOf(rounded);
        if (str.endsWith(".0")) {
            str = str.substring(0, str.length() - 2);
        }
        return str;
    }

    private void inspectBook(ItemStack book) {
        WrittenBookContentComponent content = book.get(DataComponentTypes.WRITTEN_BOOK_CONTENT);

        if (content == null) {
            error("This book has no content!");
            return;
        }

        String title = escapePercent(content.title().raw());
        String author = escapePercent(content.author());
        int generation = content.generation();
        List<Text> pages = content.getPages(true);

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

        for (Text page : pages) {
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
            info("§7First " + pagesToShow + " page(s):");
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
        WrittenBookContentComponent content = book.get(DataComponentTypes.WRITTEN_BOOK_CONTENT);
        if (content == null) return;

        List<Text> pages = content.getPages(true);
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
        WrittenBookContentComponent content = book.get(DataComponentTypes.WRITTEN_BOOK_CONTENT);
        if (content == null) return;

        List<Text> pages = content.getPages(true);
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
        WrittenBookContentComponent content = book.get(DataComponentTypes.WRITTEN_BOOK_CONTENT);
        if (content == null) {
            error("This book has no content!");
            return;
        }

        List<Text> pages = content.getPages(true);

        int totalChars = 0;
        int totalWords = 0;
        int emptyPages = 0;
        int longestPage = 0;
        int shortestPage = Integer.MAX_VALUE;

        Map<String, Integer> wordFrequency = new HashMap<>();
        String mostCommonWord = "";
        int mostCommonWordCount = 0;

        for (Text page : pages) {
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
        info("§7Characters: §f" + addCommas(totalChars));      // FIXED: No String.format
        info("§7Words: §f" + addCommas(totalWords));          // FIXED: No String.format
        info("§7Longest Page: §f" + longestPage + " §7chars");
        info("§7Shortest Page: §f" + shortestPage + " §7chars");
        info("§7Reading Time: §f" + readingTime);
        info("§7Reading Level: §f" + formatDecimal(readingLevel) + " §7(" + getReadingLevelDescription((int) readingLevel) + ")"); // FIXED: No String.format
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
        BlockState state = mc.world.getBlockState(pos);
        if (state.getBlock() != Blocks.CHISELED_BOOKSHELF) return -1;

        Direction facing = state.get(Properties.HORIZONTAL_FACING);
        Vec3d hitPos = hit.getPos();
        Vec3d relative = hitPos.subtract(pos.getX(), pos.getY(), pos.getZ());

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
