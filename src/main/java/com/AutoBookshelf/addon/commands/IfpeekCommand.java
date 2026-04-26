package com.AutoBookshelf.addon.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.command.CommandSource;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;
import static meteordevelopment.meteorclient.MeteorClient.mc;

public class IfpeekCommand extends Command {

    public IfpeekCommand() {
        super("ifpeek", "Shows book information from an item frame.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(ctx -> {
            if (mc.crosshairTarget == null || mc.crosshairTarget.getType() != HitResult.Type.ENTITY) {
                error("You have to point at an item frame first");
                return SINGLE_SUCCESS;
            }

            if (!(mc.crosshairTarget instanceof EntityHitResult hitResult)) {
                error("You have to point at an item frame first");
                return SINGLE_SUCCESS;
            }

            if (!(hitResult.getEntity() instanceof ItemFrameEntity itemFrame)) {
                error("You have to point at an item frame first");
                return SINGLE_SUCCESS;
            }

            ItemStack item = itemFrame.getHeldItemStack();
            if (item.isEmpty()) {
                error("There is no item on the item frame.");
                return SINGLE_SUCCESS;
            }

            if (item.isOf(Items.WRITTEN_BOOK)) {
                inspectBook(item);
                return SINGLE_SUCCESS;
            }

            if (item.isOf(Items.WRITABLE_BOOK)) {
                info("This is a writable book (book and quill) Not yet signed");
                return SINGLE_SUCCESS;
            }

            error("This item is not a written book!");
            return SINGLE_SUCCESS;
        });

        // Search subcommand
        builder.then(literal("search")
            .then(argument("word", StringArgumentType.word())
                .executes(ctx -> {
                    String searchWord = StringArgumentType.getString(ctx, "word");
                    searchInBook(searchWord);
                    return SINGLE_SUCCESS;
                })
            )
        );

        // Page subcommand
        builder.then(literal("page")
            .then(argument("number", IntegerArgumentType.integer(1, 100))
                .executes(ctx -> {
                    int pageNum = IntegerArgumentType.getInteger(ctx, "number");
                    viewSpecificPage(pageNum);
                    return SINGLE_SUCCESS;
                })
            )
        );

        // Stats subcommand
        builder.then(literal("stats")
            .executes(ctx -> {
                showBookStats();
                return SINGLE_SUCCESS;
            })
        );
    }

    // Helper to escape % symbols in strings to prevent format crashes
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

        // Show first 3 pages
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

    private void searchInBook(String searchWord) {
        WrittenBookContentComponent content = getBookFromItemFrame();
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

    private void viewSpecificPage(int pageNum) {
        WrittenBookContentComponent content = getBookFromItemFrame();
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

    private void showBookStats() {
        WrittenBookContentComponent content = getBookFromItemFrame();
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

        // Reading time: 200 words per minute
        int readingTimeMinutes = totalWords / 200;
        int readingTimeSeconds = (totalWords % 200) * 3 / 10;
        String readingTime = readingTimeMinutes > 0 ?
            readingTimeMinutes + "m " + readingTimeSeconds + "s" :
            readingTimeSeconds + "s";

        // Reading level using Flesch-Kincaid simplified
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

    private WrittenBookContentComponent getBookFromItemFrame() {
        if (mc.crosshairTarget instanceof EntityHitResult hitResult &&
            hitResult.getEntity() instanceof ItemFrameEntity itemFrame) {
            ItemStack item = itemFrame.getHeldItemStack();
            if (item.isOf(Items.WRITTEN_BOOK)) {
                return item.get(DataComponentTypes.WRITTEN_BOOK_CONTENT);
            }
        }
        return null;
    }
}
