package com.FileAutoLogin.addon.modules;

import com.FileAutoLogin.addon.Addon;

import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.entity.player.InteractBlockEvent;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;

import net.minecraft.block.*;
import net.minecraft.item.*;
import net.minecraft.util.*;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.world.RaycastContext;
import net.minecraft.state.property.Properties;

import java.util.*;

public class BookshelfFiller extends Module {
    private int delayLeft = 0;
    private BlockPos targetPos = null;

    private BlockPos pos1 = null;
    private BlockPos pos2 = null;
    private boolean selecting = true;
    private boolean allFull = false;
    
    // Track current position
    private int currentRow = 0;
    private int currentCol = 0;
    private int currentSlot = 0; // 0,1,2 for top half
    private boolean fillingBottomHalf = false;
    private List<List<BlockPos>> rows = new ArrayList<>();

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay between actions in ticks")
        .defaultValue(4)
        .min(0)
        .sliderMax(10)
        .build()
    );

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders the selection area")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The side color of the selection box")
        .defaultValue(new SettingColor(0, 255, 255, 30))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The line color of the selection box")
        .defaultValue(new SettingColor(0, 255, 255, 255))
        .build()
    );

    public BookshelfFiller() {
        super(Addon.CATEGORY, "bookshelf-filler", "Fills top half of all bookshelves in a row, then bottom half.");
    }

    @EventHandler
    private void onInteract(InteractBlockEvent event) {
        if (mc.player == null || mc.world == null) return;
        if (event.hand != Hand.MAIN_HAND) return;
        if (!selecting) return;

        BlockHitResult hitResult = event.result;
        if (hitResult == null) return;
        
        BlockPos pos = hitResult.getBlockPos();
        BlockState state = mc.world.getBlockState(pos);

        if (state.getBlock() != Blocks.CHISELED_BOOKSHELF) return;

        if (pos1 == null) {
            pos1 = pos;
            info("§aPos1 set to: §f" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
        } else if (pos2 == null) {
            pos2 = pos;
            selecting = false;
            info("§aPos2 set to: §f" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
            info("§aSelection complete! Filling will now begin.");
            initializeGrid();
        }

        event.cancel();
    }
    
    private void initializeGrid() {
        rows = getSortedRows();
        currentRow = 0;
        currentCol = 0;
        currentSlot = 0;
        fillingBottomHalf = false;
        allFull = false;
        
        if (!rows.isEmpty()) {
            int totalBookshelves = rows.stream().mapToInt(List::size).sum();
            info("§aFound §f" + rows.size() + " §arows with §f" + totalBookshelves + " §abookshelves total");
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        
        if (selecting || pos1 == null || pos2 == null) return;
        if (allFull) return;
        
        if (delayLeft > 0) {
            delayLeft--;
            return;
        }
        
        fillNextSlot();
    }
    
    private void fillNextSlot() {
        if (rows.isEmpty()) {
            allFull = true;
            return;
        }
        
        // Check if we've completed all rows
        if (currentRow >= rows.size()) {
            allFull = true;
            info("§aAll bookshelves are completely full!");
            return;
        }
        
        List<BlockPos> currentRowBlocks = rows.get(currentRow);
        
        // Check if we've completed this row's current half
        if (currentCol >= currentRowBlocks.size()) {
            if (!fillingBottomHalf) {
                // Finished top half of this row, start bottom half
                fillingBottomHalf = true;
                currentCol = 0;
                currentSlot = 0;
                info("§aFinished top half of row " + (currentRow + 1) + ", now filling bottom half...");
                delayLeft = delay.get();
                return;
            } else {
                // Finished bottom half of this row, move to next row
                currentRow++;
                currentCol = 0;
                currentSlot = 0;
                fillingBottomHalf = false;
                delayLeft = delay.get();
                return;
            }
        }
        
        BlockPos pos = currentRowBlocks.get(currentCol);
        BlockState state = mc.world.getBlockState(pos);
        
        // Determine which slot to fill (0-2 for top, 3-5 for bottom)
        int slotToFill = fillingBottomHalf ? currentSlot + 3 : currentSlot;
        
        // Check if this slot is empty
        if (!state.get(ChiseledBookshelfBlock.SLOT_OCCUPIED_PROPERTIES.get(slotToFill))) {
            int bookSlot = findWrittenBook();
            if (bookSlot == -1) {
                // No books, wait and retry
                delayLeft = delay.get();
                return;
            }
            
            if (!canSee(pos)) {
                // Can't see this bookshelf, move to next one
                currentCol++;
                delayLeft = delay.get();
                return;
            }
            
            targetPos = pos;
            Direction facing = state.get(Properties.HORIZONTAL_FACING);
            Vec3d hitVec = getHitVec(pos, facing, slotToFill);
            
            Rotations.rotate(
                Rotations.getYaw(hitVec),
                Rotations.getPitch(hitVec),
                () -> {
                    int previousSlot = mc.player.getInventory().selectedSlot;
                    swapToSlot(bookSlot);
                    
                    mc.interactionManager.interactBlock(
                        mc.player,
                        Hand.MAIN_HAND,
                        new BlockHitResult(hitVec, facing, pos, false)
                    );
                    
                    mc.player.swingHand(Hand.MAIN_HAND);
                    
                    if (bookSlot != previousSlot) {
                        swapToSlot(previousSlot);
                    }
                }
            );
            
            // Move to next slot within this half
            currentSlot++;
            if (currentSlot >= 3) {
                // Finished all 3 slots in this half for this bookshelf
                currentSlot = 0;
                currentCol++;
            }
            
            delayLeft = delay.get();
            return;
        }
        
        // Slot is already filled, move to next slot/bookshelf
        currentSlot++;
        if (currentSlot >= 3) {
            currentSlot = 0;
            currentCol++;
        }
        delayLeft = delay.get();
    }

    private List<List<BlockPos>> getSortedRows() {
        List<BlockPos> all = getSelectedBlocks();
        if (all.isEmpty()) return Collections.emptyList();
        
        // Sort by Y first (top to bottom)
        all.sort((a, b) -> Integer.compare(b.getY(), a.getY()));
        
        // Group by Y level
        Map<Integer, List<BlockPos>> yLevels = new LinkedHashMap<>();
        for (BlockPos pos : all) {
            yLevels.computeIfAbsent(pos.getY(), k -> new ArrayList<>()).add(pos);
        }
        
        List<List<BlockPos>> rows = new ArrayList<>();
        
        for (List<BlockPos> row : yLevels.values()) {
            // Sort each row by X then Z for consistent left-to-right order
            row.sort((a, b) -> {
                if (a.getX() != b.getX()) {
                    return Integer.compare(a.getX(), b.getX());
                }
                return Integer.compare(a.getZ(), b.getZ());
            });
            rows.add(row);
        }
        
        return rows;
    }

    private List<BlockPos> getSelectedBlocks() {
        List<BlockPos> list = new ArrayList<>();
        
        int minX = Math.min(pos1.getX(), pos2.getX());
        int maxX = Math.max(pos1.getX(), pos2.getX());
        int minY = Math.min(pos1.getY(), pos2.getY());
        int maxY = Math.max(pos1.getY(), pos2.getY());
        int minZ = Math.min(pos1.getZ(), pos2.getZ());
        int maxZ = Math.max(pos1.getZ(), pos2.getZ());
        
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (mc.world.getBlockState(pos).getBlock() == Blocks.CHISELED_BOOKSHELF) {
                        list.add(pos);
                    }
                }
            }
        }
        
        return list;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get()) return;
        
        if (pos1 != null && pos2 != null) {
            int minX = Math.min(pos1.getX(), pos2.getX());
            int minY = Math.min(pos1.getY(), pos2.getY());
            int minZ = Math.min(pos1.getZ(), pos2.getZ());
            int maxX = Math.max(pos1.getX(), pos2.getX());
            int maxY = Math.max(pos1.getY(), pos2.getY());
            int maxZ = Math.max(pos1.getZ(), pos2.getZ());
            
            event.renderer.box(
                new Box(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1),
                sideColor.get(),
                lineColor.get(),
                ShapeMode.Both,
                0
            );
        }
        
        if (targetPos != null) {
            event.renderer.box(
                targetPos,
                sideColor.get(),
                lineColor.get(),
                ShapeMode.Both,
                0
            );
        }
    }

    private int findWrittenBook() {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == Items.WRITTEN_BOOK && !stack.isEmpty()) {
                return i;
            }
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
                mc.player.getInventory().selectedSlot,
                SlotActionType.SWAP,
                mc.player
            );
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
    
    public void resetSelection() {
        pos1 = null;
        pos2 = null;
        selecting = true;
        targetPos = null;
        allFull = false;
        fillingBottomHalf = false;
        currentRow = 0;
        currentCol = 0;
        currentSlot = 0;
        rows.clear();
        info("§aSelection reset. Right-click two chiseled bookshelves to set new area.");
    }

    @Override
    public void onActivate() {
        resetSelection();
        info("§aBookshelf Filler activated. Right-click two chiseled bookshelves to set area.");
    }

    @Override
    public void onDeactivate() {
        resetSelection();
    }
}