package com.AutoBookshelf.addon.commands;

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

import java.util.ArrayList;
import java.util.List;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;
import static meteordevelopment.meteorclient.MeteorClient.mc;

public class BookCommand extends Command {

    public BookCommand() {
        super("book", "Shows book information from your held item.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(ctx -> {
            ItemStack item = getHeldBook();

            if (item == null) {
                error("You must hold a book in your hand!");
                return SINGLE_SUCCESS;
            }

            if (item.isOf(Items.WRITTEN_BOOK)) {
                inspectBook(item);
                return SINGLE_SUCCESS;
            }

            if (item.isOf(Items.WRITABLE_BOOK)) {
                info("This is a writable book (book and quill), Not yet signed");
                return SINGLE_SUCCESS;
            }

            error("You are not holding a written book!");
            return SINGLE_SUCCESS;
        });

        builder.then(literal("search")
            .then(argument("word", StringArgumentType.word())
                .executes(ctx -> {
                    String searchWord = StringArgumentType.getString(ctx, "word");
                    searchInBook(searchWord);
                    return SINGLE_SUCCESS;
                })
            )
        );

        builder.then(literal("page")
            .then(argument("number", IntegerArgumentType.integer(1, 100))
                .executes(ctx -> {
                    int pageNum = IntegerArgumentType.getInteger(ctx, "number");
                    viewSpecificPage(pageNum);
                    return SINGLE_SUCCESS;
                })
            )
        );

        builder.then(literal("stats")
            .executes(ctx -> {
                showBookStats();
                return SINGLE_SUCCESS;
            })
        );
    }

    private ItemStack getHeldBook() {
        ItemStack mainHand = mc.player.getMainHandStack();
        if (mainHand.isOf(Items.WRITTEN_BOOK) || mainHand.isOf(Items.WRITABLE_BOOK)) {
            return mainHand;
        }

        ItemStack offHand = mc.player.getOffHandStack();
        if (offHand.isOf(Items.WRITTEN_BOOK) || offHand.isOf(Items.WRITABLE_BOOK)) {
            return offHand;
        }

        return null;
    }

    // Helper to escape % symbols to prevent crashes
    private String escapePercent(String input) {
        if (input == null) return "";
        return input.replace("%", "%%");
    }

    // Helper to add commas without String.format
    private String addCommas(int number) {
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
        info("§7Generation: §f" + generationText);
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

    private void searchInBook(String searchWord) {
        ItemStack book = getHeldBook();
        if (book == null || !book.isOf(Items.WRITTEN_BOOK)) {
            error("You must hold a written book!");
            return;
        }

        WrittenBookContentComponent content = book.get(DataComponentTypes.WRITTEN_BOOK_CONTENT);
        if (content == null) {
            error("This book has no content!");
            return;
        }

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
        ItemStack book = getHeldBook();
        if (book == null || !book.isOf(Items.WRITTEN_BOOK)) {
            error("You must hold a written book!");
            return;
        }

        WrittenBookContentComponent content = book.get(DataComponentTypes.WRITTEN_BOOK_CONTENT);
        if (content == null) {
            error("This book has no content!");
            return;
        }

        List<Text> pages = content.getPages(true);
        if (pageNum < 1 || pageNum > pages.size()) {
            error("Page " + pageNum + " does not exist. Book has " + pages.size() + " pages");
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
        info("§6===================");
    }

    private void showBookStats() {
        ItemStack book = getHeldBook();
        if (book == null || !book.isOf(Items.WRITTEN_BOOK)) {
            error("You must hold a written book!");
            return;
        }

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

        for (Text page : pages) {
            String text = page.getString();
            int length = text.length();

            if (text.trim().isEmpty()) {
                emptyPages++;
            }
            totalChars += length;
            totalWords += text.split("\\s+").length;

            if (length > longestPage) longestPage = length;
            if (length < shortestPage) shortestPage = length;
        }
        if (shortestPage == Integer.MAX_VALUE) shortestPage = 0;

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
        info("§7Total Pages: §f" + pages.size());
        info("§7Empty Pages: §f" + emptyPages);
        info("§7Total Characters: §f" + addCommas(totalChars));
        info("§7Total Words: §f" + addCommas(totalWords));
        info("§7Longest Page: §f" + longestPage + " §7characters");
        info("§7Shortest Page: §f" + shortestPage + " §7characters");
        info("§6=========================");
    }
}
