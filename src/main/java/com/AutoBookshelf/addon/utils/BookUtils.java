package com.AutoBookshelf.addon.utils;

import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

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

    /**
     * Upper bound on a single sanitized chat line, to bound layout cost.
     */
    private static final int MAX_CHAT_LINE_CHARS = 1000;

    /**
     * Upper bound on a slot-hover nametag, which is a single on-screen line.
     */
    private static final int MAX_NAMETAG_CHARS = 48;

    public static BookContent checkHeldBook(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;

        if (stack.is(Items.WRITTEN_BOOK)) {
            WrittenBookContent c = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
            if (c == null) return null;
            List<String> pages = c.getPages(true).stream().map(Component::getString).toList();
            return new BookContent(BookType.WRITTEN, c.title().get(true), c.author(), c.generation(), pages);
        }

        if (stack.is(Items.WRITABLE_BOOK)) {
            WritableBookContent c = stack.get(DataComponents.WRITABLE_BOOK_CONTENT);
            if (c == null) return null;
            List<String> pages = c.getPages(true).toList();
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
        if (isBook(mainHand)) return mainHand;
        ItemStack offHand = player.getOffhandItem();
        if (isBook(offHand)) return offHand;
        return null;
    }

    /**
     * Cheap validity check (type + content component present) that avoids decoding the
     * page list; getHeldBook uses this so the heavy pages build happens exactly once,
     * in the caller's own checkHeldBook() on the returned stack.
     */
    private static boolean isBook(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return (stack.is(Items.WRITTEN_BOOK) && stack.get(DataComponents.WRITTEN_BOOK_CONTENT) != null)
            || (stack.is(Items.WRITABLE_BOOK) && stack.get(DataComponents.WRITABLE_BOOK_CONTENT) != null);
    }

    /**
     * Makes untrusted book text safe to hand to the chat renderer.
     *
     * <p>Book contents come from the server and from anvil NBT, so a title, author
     * or page can contain anything: ISO control characters, zero-width and bidi
     * override characters, and unpaired surrogates from malformed UTF-16. The 26.1.2
     * chat pipeline reshapes text with a bidirectional shaper that walks code
     * points, and those characters can stall that walk and freeze the client
     * instead of merely rendering oddly.
     *
     * <p>Newlines/tabs become spaces, everything unsafe is dropped, and the result
     * is length-capped so a pathological page cannot blow up the layout cost.
     */
    public static String sanitizeForChat(String input) {
        if (input == null || input.isEmpty()) return "";
        if (input.length() > MAX_CHAT_LINE_CHARS) input = input.substring(0, MAX_CHAT_LINE_CHARS);

        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); ) {
            int cp = input.codePointAt(i);
            i += Character.charCount(cp);

            if (cp == '\n' || cp == '\r' || cp == '\t') {
                sb.append(' ');
                continue;
            }
            // A surrogate pair was already combined above, so anything left in this
            // range is an unpaired surrogate: drop it.
            if (cp >= 0xD800 && cp <= 0xDFFF) continue;
            if (cp == 0x7F || Character.isISOControl(cp)) continue;
            if (isUnsafeLayoutChar(cp)) continue;

            sb.appendCodePoint(cp);
        }
        return sb.toString().trim();
    }

    /**
     * Zero-width, bidirectional and other invisible formatting characters. They
     * carry no meaning in a chat message and are the usual cause of the chat
     * shaper looping or laying text out over an unbounded width.
     */
    /**
     * Caps untrusted book text to a nametag-sized run of whole code points. The cut
     * avoids splitting a surrogate pair, which would reintroduce the unpaired
     * surrogate that {@link #sanitizeForChat(String)} just removed.
     */
    private static String truncateForNametag(String text) {
        if (text.length() <= MAX_NAMETAG_CHARS) return text;
        int end = MAX_NAMETAG_CHARS;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + "...";
    }

    private static boolean isUnsafeLayoutChar(int cp) {
        return cp == 0x00AD            // soft hyphen
            || cp == 0x200B || cp == 0x200C || cp == 0x200D   // zero width space / non-joiner / joiner
            || cp == 0x200E || cp == 0x200F                   // LTR / RTL mark
            || (cp >= 0x202A && cp <= 0x202E)                  // bidi embedding / override
            || (cp >= 0x2060 && cp <= 0x2064)                  // word joiner + invisible operators
            || (cp >= 0x2066 && cp <= 0x2069)                  // bidi isolates
            || cp == 0xFEFF;                                  // zero width no-break space
    }

    /**
     * Wraps a chat consumer so every line these printers emit is sanitized first.
     * Used as a single choke point so no untrusted book character can reach chat.
     */
    private static Consumer<String> chatSink(Consumer<String> out) {
        return line -> out.accept(sanitizeForChat(line));
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
        Consumer<String> sink = chatSink(out);
        int totalChars = 0, totalWords = 0, emptyPages = 0;
        for (String text : content.pages()) {
            if (text.trim().isEmpty()) emptyPages++;
            totalChars += text.length();
            totalWords += countWords(text);
        }

        sink.accept("§6=== Book Info ===");
        if (content.type() == BookType.WRITTEN) {
            sink.accept("§7Title: §f" + escapePercent(content.title()));
            String author = content.author();
            sink.accept("§7Author: §f" + (author != null && !author.isEmpty() ? escapePercent(author) : "Unknown"));
            sink.accept("§7Generation: §f" + getGenerationText(content.generation()));
        } else {
            sink.accept("§7Unsigned book (book and quill)");
        }
        sink.accept("§7Pages: §f" + content.pages().size() + " §7(§f" + emptyPages + " §7empty)");
        sink.accept("§7Characters: §f" + addCommas(totalChars));
        sink.accept("§7Words: §f" + addCommas(totalWords));
        sink.accept("§6================");

        int pagesToShow = Math.min(3, content.pages().size());
        for (int i = 0; i < pagesToShow; i++) {
            String p = content.pages().get(i).replace("\n", " ").replace("\r", " ");
            p = stripObfuscation(escapePercent(p));
            if (p.length() > 150) p = p.substring(0, 150) + "...";
            sink.accept("§7Page " + (i + 1) + ": §f" + p);
        }
        if (content.pages().size() > pagesToShow) {
            sink.accept("§7... and §f" + (content.pages().size() - pagesToShow) + " §7more page(s)");
        }
    }

    public static void printSearch(BookContent content, String word, Consumer<String> out) {
        Consumer<String> sink = chatSink(out);
        List<Integer> found = new ArrayList<>();
        for (int i = 0; i < content.pages().size(); i++) {
            if (content.pages().get(i).toLowerCase().contains(word.toLowerCase())) found.add(i + 1);
        }
        String escaped = escapePercent(word);
        if (found.isEmpty()) {
            sink.accept("§cNo pages found containing: §f" + escaped);
        } else {
            sink.accept("§aFound §f" + found.size() + " §apage(s) containing §f'" + escaped + "§f':");
            for (int page : found) sink.accept("§7  Page §f" + page);
        }
    }

    public static void printPage(BookContent content, int pageNum, Consumer<String> out) {
        Consumer<String> sink = chatSink(out);
        if (pageNum < 1 || pageNum > content.pages().size()) {
            sink.accept("§cPage " + pageNum + " doesn't exist, the book has " + content.pages().size() + " pages");
            return;
        }
        String text = stripObfuscation(escapePercent(content.pages().get(pageNum - 1)));
        sink.accept("§6=== Page " + pageNum + " of " + content.pages().size() + " ===");
        for (String line : text.split("\n")) {
            if (line.length() > 60) {
                for (int i = 0; i < line.length(); i += 60) {
                    sink.accept("§f" + line.substring(i, Math.min(i + 60, line.length())));
                }
            } else {
                sink.accept("§f" + line);
            }
        }
        sink.accept("§6====================");
    }

    public static void printStats(BookContent content, Consumer<String> out) {
        Consumer<String> sink = chatSink(out);
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

        sink.accept("§6=== Book Statistics ===");
        if (content.type() == BookType.WRITTEN) {
            sink.accept("§7Title: §f" + escapePercent(content.title()));
            String author = content.author();
            sink.accept("§7Author: §f" + (author != null && !author.isEmpty() ? escapePercent(author) : "Unknown"));
            sink.accept("§7Generation: §f" + getGenerationText(content.generation()));
        } else {
            sink.accept("§7Unsigned book (book and quill)");
        }
        sink.accept("§7Pages: §f" + content.pages().size() + " §7(§f" + emptyPages + " §7empty)");
        sink.accept("§7Characters: §f" + addCommas(totalChars));
        sink.accept("§7Words: §f" + addCommas(totalWords));
        sink.accept("§7Longest Page: §f" + longestPage + " §7chars");
        sink.accept("§7Shortest Page: §f" + shortestPage + " §7chars");
        sink.accept("§7Reading Time: §f" + readingTime);
        sink.accept("§7Reading Level: §f" + formatDecimal(readingLevel) + " §7(" + getReadingLevelDescription((int) readingLevel) + ")");
        if (!mostCommon.isEmpty()) sink.accept("§7Most Common Word: §f" + mostCommon + " §7(x§f" + mostCommonCount + "§7)");
        sink.accept("§6=========================");
    }

    // GUI
    public static void openBookGui(ItemStack item, int startPage) {
        int targetIndex = Math.max(0, Math.min(startPage - 1, Math.max(0, getPageCount(item) - 1)));
        mc.execute(() -> {
            BookViewScreen screen = new BookViewScreen(BookViewScreen.BookAccess.fromItem(item));
            mc.setScreen(screen);
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
        // The packet codec counts UTF-16 chars, not code points, so a 32-emoji title would
        // measure 64 and be rejected. Truncate on length(), then step back off a split pair.
        if (title.length() <= MAX_WRITTEN_BOOK_TITLE_CHARS) return title;
        int end = MAX_WRITTEN_BOOK_TITLE_CHARS;
        if (Character.isHighSurrogate(title.charAt(end - 1))) end--;
        return title.substring(0, end);
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
            int remaining = line.length() - start;
            // The rest already fits: take it whole rather than looking for a break that
            // isn't needed, which would split a fitting final chunk into two lines.
            if (remaining <= maxChars) {
                out.add(line.substring(start));
                break;
            }
            int end = start + maxChars;
            int space = line.lastIndexOf(' ', end);
            if (space > start) end = space;
            out.add(line.substring(start, end));
            start = end + (space > start ? 1 : 0);
        }
        if (out.isEmpty()) out.add(line);
        return out;
    }

    /**
     * Maps a hit on a chiseled bookshelf face to a slot index (0-5). Returns -1 if the
     * hit isn't on a chiseled bookshelf or the facing has no front face. This mirrors the
     * shelf geometry used by BookshelfFiller's placement, so hovered slots and placed
     * slots always agree.
     */
    public static int getSlotFromHit(BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (state.getBlock() != Blocks.CHISELED_BOOKSHELF) return -1;

        Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        // Only the front face has slots; hits on the sides, back, top or bottom are not a shelf slot.
        if (hit.getDirection() != facing) return -1;
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

    /**
     * Hit point in front of the given shelf slot, used both for clicking a slot
     * (extract/place) and for anchoring hover text above it.
     */
    public static Vec3 getSlotHitVec(BlockPos pos, Direction facing, int slot) {
        double x = 0, y = 0;

        switch (slot) {
            case 0 -> {
                x = -0.25;
                y = 0.25;
            }
            case 1 -> {
                x = 0.0;
                y = 0.25;
            }
            case 2 -> {
                x = 0.25;
                y = 0.25;
            }
            case 3 -> {
                x = -0.25;
                y = -0.25;
            }
            case 4 -> {
                x = 0.0;
                y = -0.25;
            }
            case 5 -> {
                x = 0.25;
                y = -0.25;
            }
        }

        Vec3 center = Vec3.atCenterOf(pos);

        return switch (facing) {
            case NORTH -> center.add(-x, y, -0.5);
            case SOUTH -> center.add(x, y, 0.5);
            case WEST -> center.add(-0.5, y, x);
            case EAST -> center.add(0.5, y, -x);
            default -> center;
        };
    }

    /**
     * Renders the given book title (+ author) as a nametag anchored above a shelf
     * slot. Shared by BookshelfFiller's registered-slot hover and ShelfCommand's
     * read-slot hover; returns true if the text was actually drawn on screen.
     */
    public static boolean renderSlotHover(Render2DEvent event, BlockPos pos, Direction facing, int slot,
                                          String title, String author, double scale, Color titleColor, Color authorColor) {
        if (title == null || title.isEmpty()) return false;
        if (mc.level == null || mc.player == null) return false;

        // Book titles and authors are untrusted: they arrive from the server or from
        // anvil NBT. The chat printers already route through sanitizeForChat(), but the
        // nametag goes straight into the text renderer, and 26.1.2 reshapes text with
        // a code-point bidi walk that stalls on control / bidi / unpaired-surrogate
        // characters. Since this runs on every frame the shelf is in view, an
        // unsanitized title freezes the client instead of merely rendering oddly.
        String safeTitle = truncateForNametag(sanitizeForChat(title));
        if (safeTitle.isEmpty()) return false;
        String safeAuthor = author == null ? "" : truncateForNametag(sanitizeForChat(author));

        Vec3 anchor = getSlotHitVec(pos, facing, slot).add(0, 0.35, 0);
        Vector3d vec3 = new Vector3d(anchor.x, anchor.y, anchor.z);
        if (!NametagUtils.to2D(vec3, scale)) return false;

        NametagUtils.begin(vec3, event.graphics);
        TextRenderer.get().begin(1, false, true);

        double lineHeight = TextRenderer.get().getHeight();
        double titleWidth = TextRenderer.get().getWidth(safeTitle);

        if (!safeAuthor.isEmpty()) {
            String authorText = "by " + safeAuthor;
            double authorWidth = TextRenderer.get().getWidth(authorText);
            TextRenderer.get().render(authorText, -authorWidth / 2, 1, authorColor, true);
            TextRenderer.get().render(safeTitle, -titleWidth / 2, -lineHeight - 1, titleColor, true);
        } else {
            TextRenderer.get().render(safeTitle, -titleWidth / 2, -lineHeight / 2, titleColor, true);
        }

        TextRenderer.get().end();
        NametagUtils.end(event.graphics);

        return true;
    }
}
