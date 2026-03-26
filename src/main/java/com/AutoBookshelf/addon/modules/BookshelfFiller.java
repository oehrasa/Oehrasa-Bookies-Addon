package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;

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
import net.minecraft.registry.Registries;

import java.util.*;

public class BookshelfFiller extends Module {
    private int delayLeft = 0;
    private BlockPos targetPos = null;
    private int retryCount = 0;
    private int stuckCounter = 0;
    private BlockPos lastPos = null;
    private int lastSlot = -1;
    private boolean isFilling = false;

    private BlockPos pos1 = null;
    private BlockPos pos2 = null;
    private boolean selecting = true;
    private boolean allFull = false;
    
    // Selection tool state
    private boolean wandModeActive = false;
    private boolean pendingReset = false;
    
    // Track current position
    private int currentRow = 0;
    private int currentCol = 0;
    private int currentSlot = 0;
    private boolean fillingBottomHalf = false;
    private List<List<BlockPos>> rows = new ArrayList<>();

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSelection = settings.createGroup("Selection");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay between actions in ticks")
        .defaultValue(10)
        .min(0)
        .sliderMax(30)
        .build()
    );

    private final Setting<Integer> maxRetries = sgGeneral.add(new IntSetting.Builder()
        .name("max-retries")
        .description("Maximum retries for failed placements")
        .defaultValue(3)
        .min(1)
        .sliderMax(5)
        .build()
    );

    private final Setting<Item> selectionToolSetting = sgSelection.add(new ItemSetting.Builder()
        .name("selection-tool")
        .description("Which item to use for making selections")
        .defaultValue(Items.NETHERITE_AXE)
        .build()
    );

    private final Setting<Boolean> requireToolInHand = sgSelection.add(new BoolSetting.Builder()
        .name("require-tool-in-hand")
        .description("Require the selection tool to be held in hand to make selections")
        .defaultValue(true)
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

    private final Setting<SettingColor> pos1Color = sgRender.add(new ColorSetting.Builder()
        .name("pos1-color")
        .description("The color of the first position marker")
        .defaultValue(new SettingColor(0, 255, 0, 255))
        .build()
    );

    private final Setting<SettingColor> pos2Color = sgRender.add(new ColorSetting.Builder()
        .name("pos2-color")
        .description("The color of the second position marker")
        .defaultValue(new SettingColor(255, 0, 0, 255))
        .build()
    );

    public BookshelfFiller() {
        super(Addon.CATEGORY, "bookshelf-filler", "oeh Yuri doujin bookshelf restocker");
    }

    @EventHandler
    private void onInteract(InteractBlockEvent event) {
        if (mc.player == null || mc.world == null) return;
        if (event.hand != Hand.MAIN_HAND) return;
        if (!selecting) return;

        // Check if we're using selection tool mode
        if (selectionToolSetting.get() != null && requireToolInHand.get()) {
            ItemStack mainHand = mc.player.getMainHandStack();
            Item expectedTool = selectionToolSetting.get();
            
            if (mainHand.isEmpty() || mainHand.getItem() != expectedTool) {
                return; // Not holding the correct tool
            }
        }

        BlockHitResult hitResult = event.result;
        if (hitResult == null) return;
        
        BlockPos pos = hitResult.getBlockPos();
        BlockState state = mc.world.getBlockState(pos);

        if (state.getBlock() != Blocks.CHISELED_BOOKSHELF) return;

        if (pos1 == null) {
            pos1 = pos;
            info("§aPos1 set to: §f" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + 
                 " §7(using " + selectionToolSetting.get().getName().getString() + ")");
        } else if (pos2 == null) {
            pos2 = pos;
            selecting = false;
            wandModeActive = false;
            info("§aPos2 set to: §f" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + 
                 " §7(using " + selectionToolSetting.get().getName().getString() + ")");
            info("§aSelection complete! Filling will now begin");
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
        retryCount = 0;
        stuckCounter = 0;
        lastPos = null;
        lastSlot = -1;
        isFilling = true;
        
        if (!rows.isEmpty()) {
            int totalBookshelves = rows.stream().mapToInt(List::size).sum();
            info("§aFound §f" + rows.size() + " §arows with §f" + totalBookshelves + " §abookshelves total");
            info("§7Filling from pos1 to pos2: left to right, top to bottom");
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        
        // Handle pending reset
        if (pendingReset) {
            pendingReset = false;
            performReset();
        }
        
        // Handle wand mode activation/deactivation
        if (selecting && selectionToolSetting.get() != null && requireToolInHand.get()) {
            ItemStack mainHand = mc.player.getMainHandStack();
            Item expectedTool = selectionToolSetting.get();
            boolean hasTool = !mainHand.isEmpty() && mainHand.getItem() == expectedTool;
            
            if (hasTool && !wandModeActive) {
                wandModeActive = true;
                info("§7Selection tool equipped: §f" + expectedTool.getName().getString());
            } else if (!hasTool && wandModeActive) {
                wandModeActive = false;
                info("§7Selection tool unequipped");
            }
        }
        
        if (selecting || pos1 == null || pos2 == null) return;
        if (allFull) {
            if (isFilling) {
                isFilling = false;
            }
            return;
        }
        
        if (delayLeft > 0) {
            delayLeft--;
            return;
        }
        
        fillNextSlot();
    }
    
    private void fillNextSlot() {
        if (rows.isEmpty()) {
            allFull = true;
            isFilling = false;
            return;
        }
        
        if (currentRow >= rows.size()) {
            allFull = true;
            isFilling = false;
            info("§aAll bookshelves are completely full!");
            return;
        }
        
        List<BlockPos> currentRowBlocks = rows.get(currentRow);
        
        if (currentCol >= currentRowBlocks.size()) {
            if (!fillingBottomHalf) {
                fillingBottomHalf = true;
                currentCol = 0;
                currentSlot = 0;
                retryCount = 0;
                stuckCounter = 0;
                info("§aFinished top half of row " + (currentRow + 1) + ", now filling bottom half...");
                delayLeft = delay.get();
                return;
            } else {
                currentRow++;
                currentCol = 0;
                currentSlot = 0;
                fillingBottomHalf = false;
                retryCount = 0;
                stuckCounter = 0;
                delayLeft = delay.get();
                return;
            }
        }
        
        BlockPos pos = currentRowBlocks.get(currentCol);
        BlockState state = mc.world.getBlockState(pos);
        
        if (state.getBlock() != Blocks.CHISELED_BOOKSHELF) {
            info("§cWarning: Block at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + " is no longer a bookshelf! Skipping.");
            currentCol++;
            retryCount = 0;
            stuckCounter = 0;
            delayLeft = delay.get();
            return;
        }
        
        int slotToFill = fillingBottomHalf ? currentSlot + 3 : currentSlot;
        
        if (lastPos != null && lastPos.equals(pos) && lastSlot == slotToFill) {
            stuckCounter++;
            if (stuckCounter >= maxRetries.get()) {
                info("§cStuck on slot " + slotToFill + " at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ". Skipping.");
                currentSlot++;
                if (currentSlot >= 3) {
                    currentSlot = 0;
                    currentCol++;
                }
                stuckCounter = 0;
                retryCount = 0;
                lastPos = null;
                lastSlot = -1;
                delayLeft = delay.get();
                return;
            }
        } else {
            stuckCounter = 0;
        }
        
        boolean isSlotEmpty = !state.get(ChiseledBookshelfBlock.SLOT_OCCUPIED_PROPERTIES.get(slotToFill));
        
        if (isSlotEmpty) {
            int bookSlot = findWrittenBook();
            if (bookSlot == -1) {
                delayLeft = delay.get();
                return;
            }
            
            if (!canSee(pos)) {
                retryCount++;
                if (retryCount >= maxRetries.get()) {
                    info("§cCannot see bookshelf at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ". Skipping.");
                    currentCol++;
                    retryCount = 0;
                    stuckCounter = 0;
                    lastPos = null;
                    lastSlot = -1;
                }
                delayLeft = delay.get();
                return;
            }
            
            targetPos = pos;
            Direction facing = state.get(Properties.HORIZONTAL_FACING);
            Vec3d hitVec = getHitVec(pos, facing, slotToFill);
            
            lastPos = pos;
            lastSlot = slotToFill;
            
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
            
            retryCount = 0;
            
            currentSlot++;
            if (currentSlot >= 3) {
                currentSlot = 0;
                currentCol++;
            }
            
            lastPos = null;
            lastSlot = -1;
            stuckCounter = 0;
            
            delayLeft = delay.get();
            return;
        } else {
            currentSlot++;
            if (currentSlot >= 3) {
                currentSlot = 0;
                currentCol++;
            }
            retryCount = 0;
            stuckCounter = 0;
            lastPos = null;
            lastSlot = -1;
            delayLeft = delay.get();
            return;
        }
    }

    private List<List<BlockPos>> getSortedRows() {
        List<BlockPos> all = getSelectedBlocks();
        if (all.isEmpty()) return Collections.emptyList();
        
        // Get the direction from pos1 to pos2
        int minX = Math.min(pos1.getX(), pos2.getX());
        int maxX = Math.max(pos1.getX(), pos2.getX());
        int minY = Math.min(pos1.getY(), pos2.getY());
        int maxY = Math.max(pos1.getY(), pos2.getY());
        int minZ = Math.min(pos1.getZ(), pos2.getZ());
        int maxZ = Math.max(pos1.getZ(), pos2.getZ());
        
        // Determine if we're going positive or negative direction
        boolean increasingX = pos2.getX() >= pos1.getX();
        boolean increasingZ = pos2.getZ() >= pos1.getZ();
        boolean increasingY = pos2.getY() >= pos1.getY();
        
        // Sort by Y (top to bottom based on pos1 to pos2 direction)
        all.sort((a, b) -> {
            if (increasingY) {
                return Integer.compare(a.getY(), b.getY());
            } else {
                return Integer.compare(b.getY(), a.getY());
            }
        });
        
        // Group by Y level
        Map<Integer, List<BlockPos>> yLevels = new LinkedHashMap<>();
        for (BlockPos pos : all) {
            yLevels.computeIfAbsent(pos.getY(), k -> new ArrayList<>()).add(pos);
        }
        
        List<List<BlockPos>> rows = new ArrayList<>();
        
        for (List<BlockPos> row : yLevels.values()) {
            // Sort each row left to right based on pos1 to pos2 direction
            row.sort((a, b) -> {
                // Determine primary axis (X or Z) based on which has larger range
                int xRange = Math.abs(pos2.getX() - pos1.getX());
                int zRange = Math.abs(pos2.getZ() - pos1.getZ());
                
                if (xRange >= zRange) {
                    // Sort by X first
                    if (increasingX) {
                        int compare = Integer.compare(a.getX(), b.getX());
                        if (compare != 0) return compare;
                        // Then sort by Z if X is same
                        if (increasingZ) {
                            return Integer.compare(a.getZ(), b.getZ());
                        } else {
                            return Integer.compare(b.getZ(), a.getZ());
                        }
                    } else {
                        int compare = Integer.compare(b.getX(), a.getX());
                        if (compare != 0) return compare;
                        if (increasingZ) {
                            return Integer.compare(a.getZ(), b.getZ());
                        } else {
                            return Integer.compare(b.getZ(), a.getZ());
                        }
                    }
                } else {
                    // Sort by Z first
                    if (increasingZ) {
                        int compare = Integer.compare(a.getZ(), b.getZ());
                        if (compare != 0) return compare;
                        if (increasingX) {
                            return Integer.compare(a.getX(), b.getX());
                        } else {
                            return Integer.compare(b.getX(), a.getX());
                        }
                    } else {
                        int compare = Integer.compare(b.getZ(), a.getZ());
                        if (compare != 0) return compare;
                        if (increasingX) {
                            return Integer.compare(a.getX(), b.getX());
                        } else {
                            return Integer.compare(b.getX(), a.getX());
                        }
                    }
                }
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
        
        // Render pos1 and pos2 markers
        if (pos1 != null) {
            event.renderer.box(
                pos1,
                pos1Color.get(),
                pos1Color.get(),
                ShapeMode.Both,
                0
            );
        }
        
        if (pos2 != null) {
            event.renderer.box(
                pos2,
                pos2Color.get(),
                pos2Color.get(),
                ShapeMode.Both,
                0
            );
        }
        
        // Render selection area
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
        if (isFilling) {
            pendingReset = true;
        } else {
            performReset();
        }
    }
    
    private void performReset() {
        pos1 = null;
        pos2 = null;
        selecting = true;
        targetPos = null;
        allFull = false;
        fillingBottomHalf = false;
        currentRow = 0;
        currentCol = 0;
        currentSlot = 0;
        retryCount = 0;
        stuckCounter = 0;
        lastPos = null;
        lastSlot = -1;
        isFilling = false;
        rows.clear();
        info("§aSelection reset");
    }
    
    public void setPos1(BlockPos pos) {
        pos1 = pos;
        info("§aPos1 set to: §f" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
    }
    
    public void setPos2(BlockPos pos) {
        pos2 = pos;
        info("§aPos2 set to: §f" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
    }

    @Override
    public void onActivate() {
        resetSelection();
        String toolName = selectionToolSetting.get().getName().getString();
        info("§aBookshelf Filler activated.");
        if (requireToolInHand.get()) {
            info("§7Hold a §f" + toolName + " §7and right-click two chiseled bookshelves corner to set area.");
        } else {
            info("§7Right-click two chiseled bookshelves corner to set area");
        }
    }

    @Override
    public void onDeactivate() {
        resetSelection();
    }
}