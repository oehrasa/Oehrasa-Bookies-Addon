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
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

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
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
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
    }

    private void recordHoverEntry(BlockPos pos, int slot, String title, String author) {
        if (title == null || title.isEmpty()) return;
        String key = pos.getX() + "," + pos.getY() + "," + pos.getZ();
        hoverCache.computeIfAbsent(getWorldName(), k -> new HashMap<>())
            .computeIfAbsent(key, k -> new HashMap<>())
            .put(slot, new ShelfEntry(title, author));
    }

    private String getWorldName() {
        return mc.level != null ? mc.level.dimension().identifier().toString() : "unknown";
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.level == null || mc.player == null) return;
        if (!(mc.hitResult instanceof BlockHitResult hit)) return;

        // Only keep the current world's reads in memory.
        String world = getWorldName();
        if (hoverCache.size() > 1) {
            hoverCache.keySet().removeIf(k -> !k.equals(world));
        }
        Map<String, Map<Integer, ShelfEntry>> worldCache = hoverCache.get(world);
        if (worldCache == null || worldCache.isEmpty()) return;

        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (state.getBlock() != Blocks.CHISELED_BOOKSHELF) return;

        String key = pos.getX() + "," + pos.getY() + "," + pos.getZ();
        Map<Integer, ShelfEntry> shelf = worldCache.get(key);
        if (shelf == null) return;

        int slot = BookUtils.getSlotFromHit(hit);
        if (slot == -1) return;

        // Don't keep painting a title over a slot that no longer holds a book.
        if (!state.getValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(slot))) {
            shelf.remove(slot);
            return;
        }

        ShelfEntry entry = shelf.get(slot);
        if (entry == null) return;

        BookUtils.renderSlotHover(event, pos, state.getValue(BlockStateProperties.HORIZONTAL_FACING), slot,
            entry.title, entry.author, 1.0, new Color(0xFFFFFF), new Color(0xAAAAAA));
    }

    private int getSlotFromHit(BlockHitResult hit) {
        return BookUtils.getSlotFromHit(hit);
    }
}
