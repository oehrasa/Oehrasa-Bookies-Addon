package com.AutoBookshelf.addon.commands;

import com.AutoBookshelf.addon.modules.AutoLogin;
import com.AutoBookshelf.addon.utils.BookUtils;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChiseledBookshelfBlock;
import net.minecraft.command.CommandSource;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.state.property.Properties;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;

public class ShelfCommand extends Command {

    private static class ShelfEntry {
        final String title;
        final String author;

        ShelfEntry(String title, String author) {
            this.title = title;
            this.author = author;
        }
    }

    // world -> "x,y,z" -> slot -> entry read from that slot this session.
    private final Map<String, Map<String, Map<Integer, ShelfEntry>>> hoverCache = new HashMap<>();

    private ItemStack currentBook = null;

    public ShelfCommand() {
        super("shelf", "Extracts a book from a chiseled bookshelf slot, reads it, and puts it back.");
        MeteorClient.EVENT_BUS.subscribe(this);
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
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

            autoLogin.extractAndReturn(pos, slot,
                () -> {
                    ItemStack book = mc.player.getMainHandStack();
                    if (book.isEmpty() || !book.isOf(Items.WRITTEN_BOOK)) {
                        error("Failed to get book!");
                        return;
                    }
                    currentBook = book.copy();
                    BookUtils.BookContent content = BookUtils.checkHeldBook(currentBook);
                    if (content != null) {
                        recordHoverEntry(pos, slot, content.title(), content.author());
                    }
                    BookUtils.printBookInfo(BookUtils.checkHeldBook(currentBook), this::info);
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

        builder.then(literal("gui")
            .executes(ctx -> {
                if (currentBook == null) {
                    error("No book loaded. Use .shelf first to extract a book from a bookshelf.");
                    return SINGLE_SUCCESS;
                }
                BookUtils.openBookGui(currentBook, 1);
                return SINGLE_SUCCESS;
            })
            .then(argument("page", IntegerArgumentType.integer(1, 100))
                .executes(ctx -> {
                    if (currentBook == null) {
                        error("No book loaded. Use .shelf first to extract a book from a bookshelf.");
                        return SINGLE_SUCCESS;
                    }
                    int pageNum = IntegerArgumentType.getInteger(ctx, "page");
                    BookUtils.openBookGui(currentBook, pageNum);
                    return SINGLE_SUCCESS;
                })
            )
        );

        builder.then(literal("search")
            .then(argument("word", StringArgumentType.word())
                .executes(ctx -> {
                    if (currentBook == null) {
                        error("No book loaded. Use .shelf first to extract a book from a bookshelf.");
                        return SINGLE_SUCCESS;
                    }
                    String searchWord = StringArgumentType.getString(ctx, "word");
                    BookUtils.printSearch(BookUtils.checkHeldBook(currentBook), searchWord, this::info);
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
                    BookUtils.printPage(BookUtils.checkHeldBook(currentBook), pageNum, this::info);
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
                BookUtils.printStats(BookUtils.checkHeldBook(currentBook), this::info);
                return SINGLE_SUCCESS;
            })
        );

        // Writes the extracted book to <gameDir>/AutoBookshelf/<title>_<timestamp>.txt, same
        // format as .booktranslate export. Exports the copy already in currentBook, so the book
        // goes back on the shelf exactly as before, this never re-extracts.
        builder.then(literal("export")
            .executes(ctx -> {
                return runExport(1, -1);
            })
            .then(argument("page", IntegerArgumentType.integer(1, 100))
                .executes(ctx -> runExport(
                    IntegerArgumentType.getInteger(ctx, "page"),
                    IntegerArgumentType.getInteger(ctx, "page")))
                .then(argument("endPage", IntegerArgumentType.integer(1, 100))
                    .executes(ctx -> runExport(
                        IntegerArgumentType.getInteger(ctx, "page"),
                        IntegerArgumentType.getInteger(ctx, "endPage")))
                )
            )
        );

        // Translation: identical shape to .booktranslate, reading the extracted copy in
        // currentBook rather than the hand.
        addTranslation(builder, BookUtils.TranslateOutput.CHAT, "lang");
        addTranslation(builder, BookUtils.TranslateOutput.GUI, "gui-translate");
    }

    private void addTranslation(LiteralArgumentBuilder<CommandSource> builder,
                                BookUtils.TranslateOutput output, String name) {
        builder.then(literal(name)
            .executes(ctx -> runTranslate("en", 1, -1, output))
            .then(argument("language", StringArgumentType.word())
                .suggests(BookUtils::suggestLanguages)
                .executes(ctx -> runTranslate(StringArgumentType.getString(ctx, "language"), 1, -1, output))
                .then(argument("page", IntegerArgumentType.integer(1, 100))
                    .executes(ctx -> {
                        int page = IntegerArgumentType.getInteger(ctx, "page");
                        return runTranslate(StringArgumentType.getString(ctx, "language"), page, page, output);
                    })
                    .then(argument("endPage", IntegerArgumentType.integer(1, 100))
                        .executes(ctx -> {
                            int start = IntegerArgumentType.getInteger(ctx, "page");
                            int end = IntegerArgumentType.getInteger(ctx, "endPage");
                            return runTranslate(StringArgumentType.getString(ctx, "language"), start, end, output);
                        })
                    )
                )
            )
        );
    }

    /**
     * Translates the extracted book. Requires {@code currentBook}, like every other subcommand
     * here, and never re-extracts, the book goes back on the shelf as it was.
     */
    private int runTranslate(String targetLang, int startPage, int endPage, BookUtils.TranslateOutput output) {
        if (currentBook == null) {
            error("No book loaded. Use .shelf first to extract a book from a bookshelf");
            return SINGLE_SUCCESS;
        }
        BookUtils.translate(currentBook, targetLang, startPage, endPage, output, this::info, this::error);
        return SINGLE_SUCCESS;
    }

    private int runExport(int startPage, int endPage) {
        if (currentBook == null) {
            error("No book loaded. Use .shelf first to extract a book from a bookshelf");
            return SINGLE_SUCCESS;
        }
        BookUtils.exportBook(currentBook, startPage, endPage, this::info, this::error);
        return SINGLE_SUCCESS;
    }

    private void recordHoverEntry(BlockPos pos, int slot, String title, String author) {
        if (title == null || title.isEmpty()) return;
        String key = pos.getX() + "," + pos.getY() + "," + pos.getZ();
        hoverCache.computeIfAbsent(getWorldName(), k -> new HashMap<>())
            .computeIfAbsent(key, k -> new HashMap<>())
            .put(slot, new ShelfEntry(title, author));
    }

    private String getWorldName() {
        return mc.world != null ? mc.world.getRegistryKey().getValue().toString() : "unknown";
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.world == null || mc.player == null) return;
        if (!(mc.crosshairTarget instanceof BlockHitResult hit)) return;

        // Only keep the current world's reads in memory.
        String world = getWorldName();
        if (hoverCache.size() > 1) {
            hoverCache.keySet().removeIf(k -> !k.equals(world));
        }
        Map<String, Map<Integer, ShelfEntry>> worldCache = hoverCache.get(world);
        if (worldCache == null || worldCache.isEmpty()) return;

        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.world.getBlockState(pos);
        if (state.getBlock() != Blocks.CHISELED_BOOKSHELF) return;

        String key = pos.getX() + "," + pos.getY() + "," + pos.getZ();
        Map<Integer, ShelfEntry> shelf = worldCache.get(key);
        if (shelf == null) return;

        int slot = BookUtils.getSlotFromHit(hit);
        if (slot == -1) return;

        // Don't keep painting a title over a slot that no longer holds a book.
        if (!state.get(ChiseledBookshelfBlock.SLOT_OCCUPIED_PROPERTIES.get(slot))) {
            shelf.remove(slot);
            return;
        }

        ShelfEntry entry = shelf.get(slot);
        if (entry == null) return;

        BookUtils.renderSlotHover(event, pos, state.get(Properties.HORIZONTAL_FACING), slot,
            entry.title, entry.author, 1.0, new Color(0xFFFFFF), new Color(0xAAAAAA));
    }

    private int getSlotFromHit(BlockHitResult hit) {
        return BookUtils.getSlotFromHit(hit);
    }
}
