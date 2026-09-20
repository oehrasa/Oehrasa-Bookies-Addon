package com.AutoBookshelf.addon.utils;

import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;

import java.util.*;
import java.util.function.Consumer;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Shared book read/format/display logic for books utilities.
 */
public class BookUtils {

    public enum BookType {WRITTEN, WRITABLE}

    public record BookContent(BookType type, String title, String author, int generation, List<String> pages) {
    }

    /**
     * strips §k before printing.
     */
    public static boolean deobfuscate = true;

    public static BookContent checkHeldBook(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;

        if (stack.is(Items.WRITTEN_BOOK)) {
            WrittenBookContent c = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
            if (c == null) return null;
            List<String> pages = c.pages().stream().map(page -> page.raw()).map(Component::getString).toList();
            return new BookContent(BookType.WRITTEN, c.title().raw(), c.author(), c.generation(), pages);
        }

        if (stack.is(Items.WRITABLE_BOOK)) {
            WritableBookContent c = stack.get(DataComponents.WRITABLE_BOOK_CONTENT);
            if (c == null) return null;
            List<String> pages = c.pages().stream().map(Filterable::raw).toList();
            return new BookContent(BookType.WRITABLE, null, null, -1, pages);
        }

        return null;
    }

    public static int getPageCount(ItemStack item) {
        BookContent content = checkHeldBook(item);
        return content != null ? content.pages().size() : 0;
    }

    public static ItemStack getHeldBook(Player player) {
        ItemStack mainHand = player.getMainHandItem();
        if (checkHeldBook(mainHand) != null) return mainHand;
        ItemStack offHand = player.getOffhandItem();
        if (checkHeldBook(offHand) != null) return offHand;
        return null;
    }

    public static String escapePercent(String input) {
        return input == null ? "" : input.replace("%", "%%");
    }

    public static String stripObfuscation(String text) {
        return deobfuscate ? text.replace("§k", "") : text;
    }

    public static String addCommas(int number) {
        if (number < 1000) return String.valueOf(number);
        StringBuilder result = new StringBuilder();
        String numStr = String.valueOf(number);
        int length = numStr.length();
        for (int i = 0; i < length; i++) {
            if (i > 0 && (length - i) % 3 == 0) result.append(",");
            result.append(numStr.charAt(i));
        }
        return result.toString();
    }

    public static String formatDecimal(double value) {
        double rounded = Math.round(value * 10) / 10.0;
        String str = String.valueOf(rounded);
        return str.endsWith(".0") ? str.substring(0, str.length() - 2) : str;
    }

    public static String getGenerationText(int generation) {
        return switch (generation) {
            case 0 -> "Original";
            case 1 -> "Copy of Original";
            case 2 -> "Copy of Copy";
            case 3 -> "Tattered";
            default -> "Unknown";
        };
    }

    public static String getReadingLevelDescription(int level) {
        if (level <= 5) return "Very Easy";
        if (level <= 8) return "Easy";
        if (level <= 12) return "Medium";
        if (level <= 16) return "Hard";
        return "Very Hard";
    }

    private static final Set<String> STOP_WORDS = Set.of(
        "the", "be", "to", "of", "and", "a", "in", "that", "have", "i",
        "it", "for", "not", "on", "with", "he", "as", "you", "do", "at",
        "this", "but", "his", "by", "from", "they", "we", "say", "her", "she",
        "or", "an", "will", "my", "one", "all", "would", "there", "their", "what",
        "so", "up", "out", "if", "about", "who", "get", "which", "go", "me"
    );

    public static boolean isStopWord(String word) {
        return STOP_WORDS.contains(word);
    }

    /**
     * Counts whitespace-separated words, treating blank text as zero. Java's
     * String.split("\\s+") returns a single-empty-element array for "" and
     * produces a leading empty token for leading whitespace, which would inflate
     * "Words", "Reading Time" and "Reading Level" counts on blank pages.
     */
    private static int countWords(String text) {
        String trimmed = text == null ? "" : text.trim();
        return trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;
    }

    // Chat output.
    public static void printBookInfo(BookContent content, Consumer<String> out) {
        int totalChars = 0, totalWords = 0, emptyPages = 0;
        for (String text : content.pages()) {
            if (text.trim().isEmpty()) emptyPages++;
            totalChars += text.length();
            totalWords += countWords(text);
        }

        out.accept("§6=== Book Info ===");
        if (content.type() == BookType.WRITTEN) {
            out.accept("§7Title: §f" + escapePercent(content.title()));
            String author = content.author();
            out.accept("§7Author: §f" + (author != null && !author.isEmpty() ? escapePercent(author) : "Unknown"));
            out.accept("§7Generation: §f" + getGenerationText(content.generation()));
        } else {
            out.accept("§7Unsigned book (book and quill)");
        }
        out.accept("§7Pages: §f" + content.pages().size() + " §7(§f" + emptyPages + " §7empty)");
        out.accept("§7Characters: §f" + addCommas(totalChars));
        out.accept("§7Words: §f" + addCommas(totalWords));
        out.accept("§6================");

        int pagesToShow = Math.min(3, content.pages().size());
        for (int i = 0; i < pagesToShow; i++) {
            String p = content.pages().get(i).replace("\n", " ").replace("\r", " ");
            p = stripObfuscation(escapePercent(p));
            if (p.length() > 150) p = p.substring(0, 150) + "...";
            out.accept("§7Page " + (i + 1) + ": §f" + p);
        }
        if (content.pages().size() > pagesToShow) {
            out.accept("§7... and §f" + (content.pages().size() - pagesToShow) + " §7more page(s)");
        }
    }

    public static void printSearch(BookContent content, String word, Consumer<String> out) {
        List<Integer> found = new ArrayList<>();
        for (int i = 0; i < content.pages().size(); i++) {
            if (content.pages().get(i).toLowerCase().contains(word.toLowerCase())) found.add(i + 1);
        }
        String escaped = escapePercent(word);
        if (found.isEmpty()) {
            out.accept("§cNo pages found containing: §f" + escaped);
        } else {
            out.accept("§aFound §f" + found.size() + " §apage(s) containing §f'" + escaped + "§f':");
            for (int page : found) out.accept("§7  Page §f" + page);
        }
    }

    public static void printPage(BookContent content, int pageNum, Consumer<String> out) {
        if (pageNum < 1 || pageNum > content.pages().size()) {
            out.accept("§cPage " + pageNum + " doesn't exist, the book has " + content.pages().size() + " pages");
            return;
        }
        String text = stripObfuscation(escapePercent(content.pages().get(pageNum - 1)));
        out.accept("§6=== Page " + pageNum + " of " + content.pages().size() + " ===");
        for (String line : text.split("\n")) {
            if (line.length() > 60) {
                for (int i = 0; i < line.length(); i += 60) {
                    out.accept("§f" + line.substring(i, Math.min(i + 60, line.length())));
                }
            } else {
                out.accept("§f" + line);
            }
        }
        out.accept("§6====================");
    }

    public static void printStats(BookContent content, Consumer<String> out) {
        int totalChars = 0, totalWords = 0, emptyPages = 0, longestPage = 0, shortestPage = Integer.MAX_VALUE;
        Map<String, Integer> freq = new HashMap<>();
        String mostCommon = "";
        int mostCommonCount = 0;

        for (String text : content.pages()) {
            int len = text.length();
            int words = countWords(text);
            if (text.trim().isEmpty()) emptyPages++;
            totalChars += len;
            totalWords += words;
            if (len > longestPage) longestPage = len;
            if (len < shortestPage) shortestPage = len;

            for (String w : text.toLowerCase().replaceAll("[^a-zA-Z0-9\\s]", "").split("\\s+")) {
                if (!w.isEmpty() && !isStopWord(w)) {
                    int c = freq.getOrDefault(w, 0) + 1;
                    freq.put(w, c);
                    if (c > mostCommonCount) {
                        mostCommonCount = c;
                        mostCommon = w;
                    }
                }
            }
        }
        if (shortestPage == Integer.MAX_VALUE) shortestPage = 0;

        int readingTimeMinutes = totalWords / 200;
        int readingTimeSeconds = (totalWords % 200) * 3 / 10;
        String readingTime = readingTimeMinutes > 0 ? readingTimeMinutes + "m " + readingTimeSeconds + "s" : readingTimeSeconds + "s";

        double avgSyllablesPerWord = totalWords > 0 ? (double) totalChars / totalWords / 3.5 : 1.0;
        double readingLevel = Math.max(1, Math.min(20, 0.39 * 15.0 + 11.8 * avgSyllablesPerWord - 15.59));

        out.accept("§6=== Book Statistics ===");
        if (content.type() == BookType.WRITTEN) {
            out.accept("§7Title: §f" + escapePercent(content.title()));
            String author = content.author();
            out.accept("§7Author: §f" + (author != null && !author.isEmpty() ? escapePercent(author) : "Unknown"));
            out.accept("§7Generation: §f" + getGenerationText(content.generation()));
        } else {
            out.accept("§7Unsigned book (book and quill)");
        }
        out.accept("§7Pages: §f" + content.pages().size() + " §7(§f" + emptyPages + " §7empty)");
        out.accept("§7Characters: §f" + addCommas(totalChars));
        out.accept("§7Words: §f" + addCommas(totalWords));
        out.accept("§7Longest Page: §f" + longestPage + " §7chars");
        out.accept("§7Shortest Page: §f" + shortestPage + " §7chars");
        out.accept("§7Reading Time: §f" + readingTime);
        out.accept("§7Reading Level: §f" + formatDecimal(readingLevel) + " §7(" + getReadingLevelDescription((int) readingLevel) + ")");
        if (!mostCommon.isEmpty()) out.accept("§7Most Common Word: §f" + mostCommon + " §7(x§f" + mostCommonCount + "§7)");
        out.accept("§6=========================");
    }

    // GUI
    public static void openBookGui(ItemStack item, int startPage) {
        int targetIndex = Math.max(0, Math.min(startPage - 1, Math.max(0, getPageCount(item) - 1)));
        mc.execute(() -> {
            BookViewScreen screen = new BookViewScreen(BookViewScreen.BookAccess.fromItem(item));
            mc.gui.setScreen(screen);
            if (targetIndex != 0) screen.setPage(targetIndex);
        });
    }

    /**
     * Approximate number of characters that fit on a single book page (~14 lines).
     */
    private static final int MAX_BOOK_PAGE_CHARS = 210;

    /**
     * Builds a written book whose pages are the given paragraph texts reflowed to fit
     * roughly one vanilla page each. The resulting page count therefore shrinks or
     * grows with the amount of text, rather than preserving page boundaries.
     */
    public static ItemStack createWrittenBook(String title, String author, List<String> paragraphs) {
        List<String> pages = reflowPages(paragraphs, MAX_BOOK_PAGE_CHARS);

        List<Filterable<Component>> filteredPages = new ArrayList<>();
        for (String pageContent : pages) {
            filteredPages.add(Filterable.passThrough(Component.literal(pageContent)));
        }

        WrittenBookContent content = new WrittenBookContent(
            Filterable.passThrough(truncateTitle(title)),
            author,
            0,
            filteredPages,
            true
        );

        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, content);
        return book;
    }

    /**
     * Vanilla written-book titles are limited to 32 chars; the packet codec rejects longer ones.
     */
    private static final int MAX_WRITTEN_BOOK_TITLE_CHARS = 32;

    private static String truncateTitle(String title) {
        if (title == null || title.isEmpty()) return "Book";
        int codePoints = title.codePointCount(0, title.length());
        if (codePoints <= MAX_WRITTEN_BOOK_TITLE_CHARS) return title;
        return title.substring(0, title.offsetByCodePoints(0, MAX_WRITTEN_BOOK_TITLE_CHARS));
    }

    private static List<String> reflowPages(List<String> texts, int maxCharsPerPage) {
        List<String> pages = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String text : texts) {
            if (text == null) continue;

            for (String line : text.split("\n", -1)) {
                if (line.isBlank()) {
                    // Blank line = paragraph break; close the current page.
                    if (current.length() > 0) {
                        pages.add(current.toString());
                        current.setLength(0);
                    }
                    continue;
                }

                for (String wrappedLine : wrapLine(line, maxCharsPerPage)) {
                    if (current.length() + wrappedLine.length() + (current.length() > 0 ? 1 : 0) > maxCharsPerPage
                        && current.length() > 0) {
                        pages.add(current.toString());
                        current.setLength(0);
                    }

                    if (current.length() > 0) current.append('\n');
                    current.append(wrappedLine);

                    if (current.length() >= maxCharsPerPage) {
                        pages.add(current.toString());
                        current.setLength(0);
                    }
                }
            }
        }

        if (current.length() > 0) pages.add(current.toString());
        if (pages.isEmpty()) pages.add("");

        return pages;
    }

    /**
     * Splits an overlong line into word-aware chunks of at most {@code maxChars}.
     */
    private static List<String> wrapLine(String line, int maxChars) {
        if (line.length() <= maxChars) return List.of(line);

        List<String> out = new ArrayList<>();
        int start = 0;
        while (start < line.length()) {
            int end = Math.min(line.length(), start + maxChars);
            int space = line.lastIndexOf(' ', end);
            if (space > start) end = space;
            out.add(line.substring(start, end));
            start = end + (space > start ? 1 : 0);
        }
        if (out.isEmpty()) out.add(line);
        return out;
    }
}
