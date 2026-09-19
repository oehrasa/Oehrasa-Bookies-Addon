package com.AutoBookshelf.addon.commands;

import com.AutoBookshelf.addon.modules.AutoLogin;
import com.AutoBookshelf.addon.utils.BookUtils;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
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
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class ShelfCommand extends Command {

    private ItemStack currentBook = null;

    public ShelfCommand() {
        super("shelf", "Extracts a book from a chiseled bookshelf slot, reads it, and puts it back.");
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
