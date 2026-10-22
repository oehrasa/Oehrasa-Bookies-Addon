package com.AutoBookshelf.addon.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
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

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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

    /**
     * Whether two written books are the same book for duplicate-avoidance purposes.
     *
     * <p>Identity is title, author and page text. Three things it is deliberately not:
     *
     * <ul>
     *   <li><b>Not a content hash.</b> The comparison set is at most the six slots of one
     *       bookshelf, so a hash costs a full pass over up to 32 KB per page
     *       ({@code PAGE_LENGTH = 32767}) and then still has to do the string compare. Comparing
     *       directly with an early exit is strictly cheaper, has no collision risk, and needs no
     *       cache to invalidate.
     *   <li><b>Not the map id.</b> Written books have no map id at all - {@code MAP_ID} is read
     *       only by the filled-map side (MapartNamer, MapViewer, GetPreview, InventoryInfo). Two
     *       copies are distinct items with byte-identical content, which is exactly the case this
     *       has to catch, and there is no id to fall back on.
     *   <li><b>Not {@code generation}.</b> It only distinguishes copies made by crafting
     *       ({@code craftCopy()} bumps it, {@code MAX_GENERATION = 3}), so two books created
     *       separately are both generation 0 and a generation-keyed check would miss the common
     *       case.
     * </ul>
     *
     * <p>Takes {@link WrittenBookContent} rather than {@link ItemStack} so callers can hold the
     * contents of a shelf without holding live stack references into a block entity that may be
     * replaced or unloaded. {@code WrittenBookContent} is a record, so the reference is a safe
     * immutable snapshot; {@link #readWrittenContent} produces one from a stack.
     */
    public static boolean isSameBook(WrittenBookContent a, WrittenBookContent b) {
        if (a == null || b == null) return false;

        // Title and author first: these almost always differ, so the page walk is the rare path.
        // Page *count* is a record field, so it costs nothing to reject on before materialising.
        if (!Objects.equals(a.title().get(true), b.title().get(true))) return false;
        if (!Objects.equals(a.author(), b.author())) return false;
        if (a.pages().size() != b.pages().size()) return false;

        List<String> pa = a.getPages(true).stream().map(Component::getString).toList();
        List<String> pb = b.getPages(true).stream().map(Component::getString).toList();

        for (int i = 0; i < pa.size(); i++) {
            if (!Objects.equals(pa.get(i), pb.get(i))) return false;
        }
        return true;
    }

    /**
     * The written-book content of {@code stack}, or null when it is not a readable written book.
     */
    public static WrittenBookContent readWrittenContent(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getItem() != Items.WRITTEN_BOOK) return null;
        return stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
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
     * Subdirectory under the game directory that exports are written to.
     */
    private static final String EXPORT_DIR = "AutoBookshelf";

    /**
     * Upper bound on the sanitised title that forms the filename stem. The rest of the name is a
     * fixed-width timestamp, so this is what keeps a path under the usual per-component limit.
     */
    private static final int MAX_EXPORT_NAME_CHARS = 50;

    /**
     * Writes {@code pages} to a timestamped {@code .txt} under {@code <gameDir>/AutoBookshelf}
     * and reports the result through {@code info}/{@code error}.
     *
     * <p>{@code startPage} is the 1-based book page that {@code pages.get(0)} came from, so a
     * range export keeps the book's real page numbers rather than renumbering from 1.
     */
    public static void exportPages(BookContent content, List<String> pages, int startPage,
                                   Consumer<String> info, Consumer<String> error) {
        if (content == null || pages == null || pages.isEmpty()) {
            error.accept("This book has no content to export!");
            return;
        }

        StringBuilder fileContent = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            fileContent.append("===== Page ").append(startPage + i).append(" =====\n\n");
            fileContent.append(pages.get(i).trim()).append("\n\n");
        }

        String rawTitle = content.type() == BookType.WRITTEN ? content.title() : "book_and_quill";
        String stem = sanitizeExportName(rawTitle == null || rawTitle.isBlank() ? "book" : rawTitle);
        String timestamp = LocalDateTime.now().format(EXPORT_TIMESTAMP);

        Path outDir = mc.gameDirectory.toPath().resolve(EXPORT_DIR);
        try {
            Files.createDirectories(outDir);
            Path outFile = writeUnique(outDir, stem, timestamp, fileContent.toString());
            if (outFile == null) {
                error.accept("Failed to write file: too many exports share that timestamp.");
                return;
            }
            info.accept("§aExported " + pages.size() + " page(s) to §f" + outFile.toAbsolutePath());
        } catch (Exception e) {
            error.accept("Failed to write file: " + e.getMessage());
        }
    }

    private static final DateTimeFormatter EXPORT_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    /**
     * Clamps a requested page range to the book and writes it out.
     *
     * <p>{@code endPage < 0} means "to the last page". A reversed range is swapped rather than
     * refused, because {@code .book export 9 3} reads as an obvious slip.
     *
     * <p>The ordering of the three steps below is load-bearing, and getting it wrong is a crash
     * rather than a wrong answer. Resolving {@code -1} first stops an explicit out-of-range start
     * being silently swapped into a valid-looking range. Swapping <em>before</em> clamping is
     * what actually matters: clamp first and {@code end} is pulled down to the last page while
     * {@code start} stays high, so the swap hands back an {@code end} that is past the last page
     * again and {@code subList} throws {@code IndexOutOfBoundsException} - i.e. {@code export 9 3}
     * on a 5-page book takes the game down.
     */
    public static void exportBook(ItemStack book, int startPage, int endPage,
                                  Consumer<String> info, Consumer<String> error) {
        BookContent content = checkHeldBook(book);
        if (content == null || content.pages().isEmpty()) {
            error.accept("This book has no content!");
            return;
        }

        int total = content.pages().size();
        int start = startPage;
        int end = (endPage < 0) ? total : endPage;

        if (endPage >= 0 && start > end) {
            int swap = start;
            start = end;
            end = swap;
        }

        start = Math.max(1, start);
        end = Math.min(end, total);

        if (start > end) {
            error.accept("Page " + start + " is out of range; this book has " + total + " page(s).");
            return;
        }

        exportPages(content, content.pages().subList(start - 1, end), start, info, error);
    }

    /**
     * Writes to {@code <stem>_<timestamp>.txt}, adding {@code -2}, {@code -3}, ... until a name
     * is free. Returns null if 1000 names are somehow all taken.
     *
     * <p>The timestamp has one-second resolution, so two exports in the same second resolve to the
     * same file. That used to cost the earlier export: {@code writeString}'s default options
     * include {@code TRUNCATE_EXISTING}, so the second export silently overwrote the first, with
     * no error anywhere. It is only noticed when the earlier export turns out to be the one you
     * wanted.
     *
     * <p>The {@code CREATE_NEW} is what actually fixes it. Probing with {@code exists()} and then
     * writing is a check-then-act race, and it is the write that destroys data - so the
     * uniqueness test has to live in the same atomic operation that creates the file, rather than
     * being a separate step that merely looks equivalent.
     */
    private static Path writeUnique(Path dir, String stem, String timestamp, String content) throws IOException {
        for (int n = 1; n < 1000; n++) {
            Path candidate = dir.resolve(stem + "_" + timestamp + (n == 1 ? "" : "-" + n) + ".txt");
            try {
                Files.writeString(candidate, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return candidate;
            } catch (FileAlreadyExistsException ignored) {
                // Name taken by a previous export in the same second; try the next suffix.
            }
        }
        return null;
    }

    /**
     * Turns a book title into a filename stem.
     *
     * <p>This is the one place in export that genuinely needs sanitising, and the reason is
     * worth being precise about: the title is untrusted text that reaches a <em>filesystem path</em>,
     * which is exactly the case where sanitising applies. Page text does not, and never should -
     * see the note on {@code sanitizeForChat}.
     *
     * <p>Separators and the characters Windows rejects are replaced rather than removed, so a
     * title stays readable. Control characters (including a newline) are dropped outright: they
     * survive the separator replace and would produce a filename the shell mangles.
     */
    private static String sanitizeExportName(String title) {
        String cleaned = title.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("[\\p{Cc}&&[^\\t]]", "");
        cleaned = cleaned.strip();
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) cleaned = "book";
        return cleaned.length() > MAX_EXPORT_NAME_CHARS ? cleaned.substring(0, MAX_EXPORT_NAME_CHARS) : cleaned;
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

    // Translation
    // Shared by .booktranslate, .shelf and .ifpeek.
    //
    // It also must not be duplicated per command. The request pool and the global throttle
    // below are static, so three copies would give each command its own pool and its own
    // gap enforcement, and three books' pages would hit the free endpoint at three times
    // the intended rate, which is what produces the 429s the backoff below exists for.

    // Bounded pool: a few pages can translate concurrently
    // without unboundedly hammering the free endpoint
    // the way a fully-parallel commonPool() dispatch could.
    private static final int TRANSLATE_POOL_SIZE = 2;

    /**
     * Daemon worker threads, deliberately. {@code newFixedThreadPool} hands back NON-daemon
     * threads that sit idle waiting for tasks forever, which keeps the JVM alive: verified by
     * running a pool on the same JDK and watching {@code main()} return without the process ever
     * exiting. A client-side mod has no business holding the process open, and this one is the
     * worst case for it, since a page being translated can sit in the retry loop for up to
     * {@code MAX_TRANSLATE_ATTEMPTS} with a backoff capped at 30s.
     */
    private static final ExecutorService TRANSLATE_POOL = Executors.newFixedThreadPool(
        TRANSLATE_POOL_SIZE, runnable -> {
            Thread thread = new Thread(runnable, "BookTranslate");
            thread.setDaemon(true);
            return thread;
        });

    private static final int MAX_TRANSLATE_ATTEMPTS = 8;

    // Global request throttle: no two page requests may actually start closer
    // than this apart (millis + random jitter). Even a small pool will 429 the
    // free endpoint if every page fires back-to-back.
    private static final long REQUEST_MIN_INTERVAL_MILLIS = 250;
    private static final long REQUEST_INTERVAL_JITTER = 250;

    // Cap for the exponential retry backoff; Google's free endpoint can stay
    // rate-limited for a while, so let retries keep trying without growing unbounded.
    private static final long REQUEST_MAX_RETRY_DELAY_MILLIS = 30_000;

    private static final Random RANDOM = new Random();
    private static final AtomicLong LAST_REQUEST_START_NANOS = new AtomicLong();

    // Codes Google Translate accepts for the `tl` parameter (ISO 639-1 alpha-2).
    private static final List<Language> LANGUAGES = List.of(
        new Language("af", "Afrikaans"),
        new Language("sq", "Albanian"),
        new Language("am", "Amharic"),
        new Language("ar", "Arabic"),
        new Language("hy", "Armenian"),
        new Language("az", "Azerbaijani"),
        new Language("eu", "Basque"),
        new Language("be", "Belarusian"),
        new Language("bn", "Bengali"),
        new Language("bs", "Bosnian"),
        new Language("bg", "Bulgarian"),
        new Language("ca", "Catalan"),
        new Language("ceb", "Cebuano"),
        new Language("ny", "Chichewa"),
        new Language("zh-CN", "Chinese (Simplified)"),
        new Language("zh-TW", "Chinese (Traditional)"),
        new Language("co", "Corsican"),
        new Language("hr", "Croatian"),
        new Language("cs", "Czech"),
        new Language("da", "Danish"),
        new Language("nl", "Dutch"),
        new Language("en", "English"),
        new Language("eo", "Esperanto"),
        new Language("et", "Estonian"),
        new Language("tl", "Filipino"),
        new Language("fi", "Finnish"),
        new Language("fr", "French"),
        new Language("fy", "Frisian"),
        new Language("gl", "Galician"),
        new Language("ka", "Georgian"),
        new Language("de", "German"),
        new Language("el", "Greek"),
        new Language("gu", "Gujarati"),
        new Language("ht", "Haitian Creole"),
        new Language("ha", "Hausa"),
        new Language("haw", "Hawaiian"),
        new Language("he", "Hebrew"),
        new Language("hi", "Hindi"),
        new Language("hmn", "Hmong"),
        new Language("hu", "Hungarian"),
        new Language("is", "Icelandic"),
        new Language("ig", "Igbo"),
        new Language("id", "Indonesian"),
        new Language("ga", "Irish"),
        new Language("it", "Italian"),
        new Language("ja", "Japanese"),
        new Language("jw", "Javanese"),
        new Language("kn", "Kannada"),
        new Language("kk", "Kazakh"),
        new Language("km", "Khmer"),
        new Language("rw", "Kinyarwanda"),
        new Language("ko", "Korean"),
        new Language("ku", "Kurdish"),
        new Language("ky", "Kyrgyz"),
        new Language("lo", "Lao"),
        new Language("la", "Latin"),
        new Language("lv", "Latvian"),
        new Language("lt", "Lithuanian"),
        new Language("lb", "Luxembourgish"),
        new Language("mk", "Macedonian"),
        new Language("mg", "Malagasy"),
        new Language("ms", "Malay"),
        new Language("ml", "Malayalam"),
        new Language("mt", "Maltese"),
        new Language("mi", "Maori"),
        new Language("mr", "Marathi"),
        new Language("mn", "Mongolian"),
        new Language("my", "Myanmar (Burmese)"),
        new Language("ne", "Nepali"),
        new Language("no", "Norwegian"),
        new Language("or", "Odia"),
        new Language("ps", "Pashto"),
        new Language("fa", "Persian"),
        new Language("pl", "Polish"),
        new Language("pt", "Portuguese"),
        new Language("pa", "Punjabi"),
        new Language("ro", "Romanian"),
        new Language("ru", "Russian"),
        new Language("sm", "Samoan"),
        new Language("gd", "Scots Gaelic"),
        new Language("sr", "Serbian"),
        new Language("st", "Sesotho"),
        new Language("sn", "Shona"),
        new Language("sd", "Sindhi"),
        new Language("si", "Sinhala"),
        new Language("sk", "Slovak"),
        new Language("sl", "Slovenian"),
        new Language("so", "Somali"),
        new Language("es", "Spanish"),
        new Language("su", "Sundanese"),
        new Language("sw", "Swahili"),
        new Language("sv", "Swedish"),
        new Language("tg", "Tajik"),
        new Language("ta", "Tamil"),
        new Language("tt", "Tatar"),
        new Language("te", "Telugu"),
        new Language("th", "Thai"),
        new Language("tr", "Turkish"),
        new Language("tk", "Turkmen"),
        new Language("uk", "Ukrainian"),
        new Language("ur", "Urdu"),
        new Language("ug", "Uyghur"),
        new Language("uz", "Uzbek"),
        new Language("vi", "Vietnamese"),
        new Language("cy", "Welsh"),
        new Language("xh", "Xhosa"),
        new Language("yi", "Yiddish"),
        new Language("yo", "Yoruba"),
        new Language("zu", "Zulu")
    );

    private record Language(String code, String name) {
    }

    public static String getLanguageName(String code) {
        for (Language language : LANGUAGES) {
            if (language.code().equals(code)) return language.name();
        }
        return code;
    }

    public static CompletableFuture<Suggestions> suggestLanguages(CommandContext<ClientSuggestionProvider> context, SuggestionsBuilder builder) {
        for (Language language : LANGUAGES) {
            builder.suggest(language.code(), new LiteralMessage(language.name()));
        }
        return builder.buildFuture();
    }

    /**
     * Where a finished translation goes.
     */
    public enum TranslateOutput {CHAT, EXPORT, GUI}

    /**
     * Translates {@code book}'s pages {@code startPage..endPage} into {@code targetLang} and
     * renders the result as {@code output}. {@code endPage < 0} means "to the last page".
     *
     * <p>Fully asynchronous; progress and completion are reported through the supplied consumers.
     */
    public static void translate(ItemStack book, String targetLang, int startPage, int endPage,
                                 TranslateOutput output, Consumer<String> info, Consumer<String> error) {
        BookUtils.BookContent content = BookUtils.checkHeldBook(book);
        if (content == null || content.pages().isEmpty()) {
            error.accept("This book has no content!");
            return;
        }

        List<String> allPages = content.pages();
        int totalPages = allPages.size();

        // A reversed explicit range is swapped, not refused: `.shelf lang de 9 3` reads as an
        // obvious slip. This used to live in each command's brigadier layer (three copies in
        // BookTranslateCommand, none in Shelf/Ifpeek), which made `.booktranslate lang de 9 3`
        // work while `.shelf lang de 9 3` said "Invalid page range" for the identical request.
        // One owner, one behaviour.
        int start = Math.max(1, startPage);
        int end = (endPage < 0) ? totalPages : endPage;
        if (endPage >= 0 && start > end) {
            int swap = start;
            start = end;
            end = swap;
        }
        end = Math.min(end, totalPages);

        // Still invalid after normalisation: an explicit start past the last page. A negative
        // `endPage` can never land here because it was resolved to totalPages above.
        if (start > end) {
            error.accept("Invalid page range.");
            return;
        }

        List<String> pagesToTranslate = allPages.subList(start - 1, end);
        String rangeInfo = (start == end) ? "page " + start : "pages " + start + "-" + end;
        info.accept("§6Translating " + rangeInfo + " of " + totalPages + " to " + getLanguageName(targetLang) + " (" + targetLang + ")...");

        AtomicInteger completed = new AtomicInteger();
        int totalToTranslate = pagesToTranslate.size();
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (String page : pagesToTranslate) {
            CompletableFuture<String> future = page.isBlank() ? CompletableFuture.completedFuture("") : translateTextAsync(targetLang, page);
            future.whenComplete((result, err) -> {
                int done = completed.incrementAndGet();
                if (done % 30 == 0 || done == totalToTranslate) {
                    mc.executeIfPossible(() -> info.accept("§6Translating progress: " + done + "/" + totalToTranslate + " pages translated..."));
                }
            });
            futures.add(future);
        }

        int finalStart = start;
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenAccept(v -> {
                List<String> translated = new ArrayList<>();
                for (int i = 0; i < futures.size(); i++) {
                    String t = futures.get(i).join();
                    if (t == null) {
                        int failedPage = finalStart + i;
                        mc.executeIfPossible(() -> error.accept("Translation failed on page " + failedPage + ". Check the log for the HTTP error."));
                        return;
                    }
                    translated.add(t);
                }

                mc.executeIfPossible(() -> {
                    switch (output) {
                        case EXPORT -> BookUtils.exportPages(content, translated, finalStart, info, error);
                        case GUI -> openTranslatedGui(content, translated, info, error);
                        default -> printTranslation(rangeInfo, finalStart, translated, info);
                    }
                });
            })
            .exceptionally(e -> {
                mc.executeIfPossible(() -> error.accept("Translation error: " + e.getMessage()));
                return null;
            });
    }

    private static void printTranslation(String rangeInfo, int startPage, List<String> translatedPages, Consumer<String> info) {
        // Translated pages are remote text, so they go through the same sanitizing sink the other
        // chat printers use before any of them reaches chat.
        Consumer<String> sink = chatSink(info);
        sink.accept("§6<Translated Book (" + rangeInfo + ")>");
        for (int i = 0; i < translatedPages.size(); i++) {
            int actualPage = startPage + i;
            sink.accept("§7--- Page " + actualPage + " ---");
            String text = translatedPages.get(i);
            for (String line : text.split("\n")) {
                if (line.length() > MAX_TRANSLATION_LINE_CHARS) {
                    int from = 0;
                    while (from < line.length()) {
                        int to = Math.min(from + MAX_TRANSLATION_LINE_CHARS, line.length());
                        // Never split a surrogate pair. Each chunk becomes its own chat line, so a
                        // boundary landing between a high and low surrogate leaves a lone half at
                        // the end of one line and the start of the next, and BOTH render as U+FFFD.
                        // Step back one char and resume from the adjusted boundary.
                        if (to < line.length() && Character.isHighSurrogate(line.charAt(to - 1))) to--;
                        if (to <= from) to = Math.min(from + MAX_TRANSLATION_LINE_CHARS, line.length());
                        sink.accept("§f" + BookUtils.escapePercent(line.substring(from, to)));
                        from = to;
                    }
                } else {
                    sink.accept("§f" + BookUtils.escapePercent(line));
                }
            }
        }
        sink.accept("§6========================");
    }

    /**
     * Chat lines wider than this are wrapped; see {@link #printTranslation}.
     */
    private static final int MAX_TRANSLATION_LINE_CHARS = 120;

    private static void openTranslatedGui(BookUtils.BookContent content, List<String> translatedPages,
                                          Consumer<String> info, Consumer<String> error) {
        if (translatedPages.isEmpty()) {
            error.accept("Translation produced no content.");
            return;
        }

        String title = content.type() == BookUtils.BookType.WRITTEN && content.title() != null
            ? content.title() : "Translated";
        String author = content.author() != null ? content.author()
            : mc.player.getName().getString();

        ItemStack translatedBook = BookUtils.createWrittenBook(title, author, translatedPages);
        int pageCount = BookUtils.getPageCount(translatedBook);
        BookUtils.openBookGui(translatedBook, 1);
        info.accept("§aOpened translated book in GUI. §7" + pageCount + " page(s)");
    }

    private static CompletableFuture<String> translateTextAsync(String targetLang, String textToTranslate) {
        return CompletableFuture.supplyAsync(() -> {
            int attempts = 0;
            while (true) {
                try {
                    String urlString = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl="
                        + URLEncoder.encode(targetLang, StandardCharsets.UTF_8)
                        + "&dt=t&q=" + URLEncoder.encode(textToTranslate, StandardCharsets.UTF_8);

                    String raw;
                    while (true) {
                        // Recreated on every attempt: HttpURLConnection is single-use,
                        // so retrying on the same object just reads the cached status.
                        throttleRequestStart();
                        HttpURLConnection conn = null;
                        try {
                            conn = (HttpURLConnection) new URI(urlString).toURL().openConnection();
                            conn.setConnectTimeout(5000);
                            conn.setReadTimeout(8000);
                            conn.setRequestProperty("User-Agent", "Mozilla/5.0");

                            int status = conn.getResponseCode();
                            if (status >= 200 && status < 300) {
                                try (InputStream stream = conn.getInputStream()) {
                                    raw = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                                }
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
                            }

                            // Transient failures (rate limit, 5xx) get an exponential,
                            // jittered backoff; hard 4xx fail fast.
                            if ((status == 429 || status >= 500) && attempts < MAX_TRANSLATE_ATTEMPTS - 1) {
                                attempts++;
                                long retryDelay = Math.min(REQUEST_MAX_RETRY_DELAY_MILLIS, 1200L * (1L << (attempts - 1))) + RANDOM.nextLong(1500);
                                sleep(retryDelay);
                                continue;
                            }
                            // Any non-2xx we're not retrying further is a real failure.
                            // Log the body for diagnostics but return null so the caller's
                            // `t == null` check reports it as a failed page instead of
                            // silently treating the error body as translated text.
                            String errorBody = readErrorBody(conn);
                            System.err.println("BookTranslate: HTTP " + status + " for page translation: " + errorBody);
                            return null;
                        } catch (IOException e) {
                            // Transient connection-level failures (connect/read timeout,
                            // connect reset, DNS) are retried through the same exponential,
                            // jittered backoff; pacing and attempt budget are untouched.
                            if (attempts < MAX_TRANSLATE_ATTEMPTS - 1) {
                                attempts++;
                                long retryDelay = Math.min(REQUEST_MAX_RETRY_DELAY_MILLIS, 1200L * (1L << (attempts - 1))) + RANDOM.nextLong(1500);
                                sleep(retryDelay);
                                continue;
                            }
                            e.printStackTrace();
                            return null;
                        } finally {
                            if (conn != null) conn.disconnect();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    return null;
                }
            }
        }, TRANSLATE_POOL);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Enforces a global minimum gap between consecutive request *starts* (with
    // jitter), so an entire book can't burst the free endpoint at once even
    // though pages translate concurrently. Blocks the worker until a slot opens.
    private static void throttleRequestStart() {
        while (true) {
            long now = System.nanoTime();
            long last = LAST_REQUEST_START_NANOS.get();
            if (last == 0) {
                if (LAST_REQUEST_START_NANOS.compareAndSet(0, now)) return;
                continue;
            }
            long intervalMillis = REQUEST_MIN_INTERVAL_MILLIS + RANDOM.nextLong(REQUEST_INTERVAL_JITTER + 1);
            long gapMillis = intervalMillis - (now - last) / 1_000_000L;
            if (gapMillis <= 0) {
                if (LAST_REQUEST_START_NANOS.compareAndSet(last, now)) return;
                continue;
            }
            sleep(gapMillis);
        }
    }

    private static String readErrorBody(HttpURLConnection conn) {
        try (InputStream errorStream = conn.getErrorStream()) {
            if (errorStream == null) return "";
            byte[] bytes = errorStream.readAllBytes();
            String body = new String(bytes, StandardCharsets.UTF_8);
            return body.length() > 300 ? body.substring(0, 300) : body;
        } catch (Exception ignored) {
            return "";
        }
    }
}
