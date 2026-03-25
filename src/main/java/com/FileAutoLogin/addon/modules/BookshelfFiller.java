package com.FileAutoLogin.addon.modules;

import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;

import net.minecraft.block.*;
import net.minecraft.item.*;
import net.minecraft.util.*;
import net.minecraft.util.hit.*;
import net.minecraft.util.math.*;

public class BookshelfFiller extends Module {
    private int delayLeft = 0;
    private BlockPos targetPos = null;

    // Settings
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("How far to search for bookshelves.")
        .defaultValue(4)
        .min(1)
        .sliderMax(6)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks between placements.")
        .defaultValue(4)
        .min(0)
        .sliderMax(10)
        .build()
    );

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Render target bookshelf.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .defaultValue(new SettingColor(0, 255, 255, 30))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .defaultValue(new SettingColor(0, 255, 255, 255))
        .build()
    );

    public BookshelfFiller(Category category) {
        super(category, "bookshelf-filler", "Fills chiseled bookshelves with written books.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        targetPos = null;

        if (delayLeft > 0) {
            delayLeft--;
            return;
        }

        BlockPos playerPos = mc.player.getBlockPos();
        int r = range.get();

        for (BlockPos pos : BlockPos.iterateOutwards(playerPos, r, r, r)) {
            BlockState state = mc.world.getBlockState(pos);

            if (state.getBlock() != Blocks.CHISELED_BOOKSHELF) continue;

            int emptySlot = getEmptySlot(state);
            if (emptySlot == -1) continue;

            int bookSlot = findWrittenBook();
            if (bookSlot == -1) return;

            if (!canSee(pos)) continue;

            targetPos = pos;

            Direction facing = state.get(ChiseledBookshelfBlock.FACING);
            Vec3d hitVec = getHitVec(pos, facing, emptySlot);

            Rotations.rotate(
                Rotations.getYaw(hitVec),
                Rotations.getPitch(hitVec),
                () -> {
                    swapToSlot(bookSlot);

                    mc.interactionManager.interactBlock(
                        mc.player,
                        Hand.MAIN_HAND,
                        new BlockHitResult(hitVec, facing, pos, false)
                    );

                    mc.player.swingHand(Hand.MAIN_HAND);
                }
            );

            delayLeft = delay.get();
            return;
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || targetPos == null) return;

        event.renderer.box(
            targetPos,
            sideColor.get(),
            lineColor.get(),
            ShapeMode.Both,
            0
        );
    }

    // =========================
    // Helpers
    // =========================

    private int getEmptySlot(BlockState state) {
        for (int i = 0; i < 6; i++) {
            if (!state.get(ChiseledBookshelfBlock.SLOT_OCCUPIED_PROPERTIES.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private int findWrittenBook() {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == Items.WRITTEN_BOOK) return i;
        }
        return -1;
    }

    private void swapToSlot(int slot) {
        if (slot < 9) {
            mc.player.getInventory().selectedSlot = slot;
        } else {
            mc.interactionManager.clickSlot(
                mc.player.currentScreenHandler.syncId,
                slot,
                0,
                SlotActionType.SWAP,
                mc.player
            );
            mc.player.getInventory().selectedSlot = 0;
        }
    }

    private boolean canSee(BlockPos pos) {
        Vec3d eyes = mc.player.getEyePos();
        Vec3d target = Vec3d.ofCenter(pos);

        BlockHitResult result = mc.world.raycast(new RaycastContext(
            eyes,
            target,
            RaycastContext.ShapeType.OUTLINE,
            RaycastContext.FluidHandling.NONE,
            mc.player
        ));

        return result.getBlockPos().equals(pos);
    }

    private Vec3d getHitVec(BlockPos pos, Direction facing, int slot) {
        double x = 0, y = 0;

        switch (slot) {
            case 0 -> { x = -0.25; y = 0.25; }
            case 1 -> { x = 0.0;  y = 0.25; }
            case 2 -> { x = 0.25; y = 0.25; }
            case 3 -> { x = -0.25; y = -0.25; }
            case 4 -> { x = 0.0;  y = -0.25; }
            case 5 -> { x = 0.25; y = -0.25; }
        }

        Vec3d center = Vec3d.ofCenter(pos);

        return switch (facing) {
            case NORTH -> center.add(-x, y, -0.5);
            case SOUTH -> center.add(x, y, 0.5);
            case WEST  -> center.add(-0.5, y, x);
            case EAST  -> center.add(0.5, y, -x);
            default -> center;
        };
    }
}