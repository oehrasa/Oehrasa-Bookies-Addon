package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public class MinecartPlacer extends Module {
    private boolean placing = false;
    private List<BlockPos> targetRails = new ArrayList<>();
    private int currentIndex = 0;
    private int delayLeft = 0;
    private int placedCount = 0;
    private boolean waitingForMinecarts = false;
    private int waitCounter = 0;
    
    private enum MinecartType {
        MINECART(Items.MINECART, "Minecart"),
        CHEST_MINECART(Items.CHEST_MINECART, "Chest Minecart"),
        FURNACE_MINECART(Items.FURNACE_MINECART, "Furnace Minecart"),
        TNT_MINECART(Items.TNT_MINECART, "TNT Minecart"),
        HOPPER_MINECART(Items.HOPPER_MINECART, "Hopper Minecart");
        
        final Item item;
        final String name;
        
        MinecartType(Item item, String name) {
            this.item = item;
            this.name = name;
        }
        
        @Override
        public String toString() {
            return name;
        }
    }
    
    private enum RailType {
        DETECTOR_RAIL(Blocks.DETECTOR_RAIL, "Detector Rail"),
        POWERED_RAIL(Blocks.POWERED_RAIL, "Powered Rail"),
        ACTIVATOR_RAIL(Blocks.ACTIVATOR_RAIL, "Activator Rail"),
        RAIL(Blocks.RAIL, "Rail");
        
        final Block block;
        final String name;
        
        RailType(Block block, String name) {
            this.block = block;
            this.name = name;
        }
        
        @Override
        public String toString() {
            return name;
        }
    }
    
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");
    
    private final Setting<MinecartType> minecartType = sgGeneral.add(new EnumSetting.Builder<MinecartType>()
        .name("minecart-type")
        .description("Type of minecart to place")
        .defaultValue(MinecartType.MINECART)
        .build()
    );
    
    private final Setting<RailType> railType = sgGeneral.add(new EnumSetting.Builder<RailType>()
        .name("rail-type")
        .description("Type of rail to place minecarts on")
        .defaultValue(RailType.DETECTOR_RAIL)
        .build()
    );
    
    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Delay between placing minecarts in ticks")
        .defaultValue(10)
        .min(1)
        .max(40)
        .sliderMax(40)
        .build()
    );
    
    private final Setting<Integer> radius = sgGeneral.add(new IntSetting.Builder()
        .name("radius")
        .description("Radius to search for rails around the player")
        .defaultValue(10)
        .min(1)
        .max(50)
        .sliderMax(50)
        .build()
    );
    
    private final Setting<Integer> maxMinecarts = sgGeneral.add(new IntSetting.Builder()
        .name("max-minecarts")
        .description("Maximum number of minecarts to place (0 = unlimited)")
        .defaultValue(0)
        .min(0)
        .max(100)
        .sliderMax(100)
        .build()
    );
    
    private final Setting<Boolean> skipOccupied = sgGeneral.add(new BoolSetting.Builder()
        .name("skip-occupied")
        .description("Skip rails that already have a minecart")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Integer> checkDelay = sgGeneral.add(new IntSetting.Builder()
        .name("check-delay")
        .description("Delay between checking for new minecarts when out of stock (ticks)")
        .defaultValue(20)
        .min(5)
        .max(100)
        .sliderMax(100)
        .build()
    );
    
    // Render settings
    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders the next rail to place")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<SettingColor> highlightColor = sgRender.add(new ColorSetting.Builder()
        .name("highlight-color")
        .description("Color of the highlighted rail")
        .defaultValue(new SettingColor(255, 255, 0, 100))
        .build()
    );
    
    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Line color of the highlighted rail")
        .defaultValue(new SettingColor(255, 255, 0, 255))
        .build()
    );
    
    public MinecartPlacer() {
        super(Addon.CATEGORY, "Minecart-Placer", "Places any minecarts on any rails in range");
    }
    
    @Override
    public void onActivate() {
        startPlacing();
    }
    
    private void startPlacing() {
        if (mc.player == null || mc.world == null) return;
        
        // Find all rails in radius
        targetRails.clear();
        BlockPos center = mc.player.getBlockPos();
        int rad = radius.get();
        Block targetRail = railType.get().block;
        
        for (int x = -rad; x <= rad; x++) {
            for (int y = -rad; y <= rad; y++) {
                for (int z = -rad; z <= rad; z++) {
                    BlockPos pos = center.add(x, y, z);
                    if (mc.world.getBlockState(pos).getBlock() == targetRail) {
                        targetRails.add(pos);
                    }
                }
            }
        }
        
        if (targetRails.isEmpty()) {
            info("§cNo " + railType.get().name + " found within " + rad + " blocks!");
            toggle();
            return;
        }
        
        // Sort by distance from player
        targetRails.sort((a, b) -> {
            double distA = a.getSquaredDistance(center);
            double distB = b.getSquaredDistance(center);
            return Double.compare(distA, distB);
        });
        
        currentIndex = 0;
        placedCount = 0;
        placing = true;
        waitingForMinecarts = false;
        delayLeft = 0;
        waitCounter = 0;
        
        info("§aFound §f" + targetRails.size() + " §a" + railType.get().name + "s. Placing " + minecartType.get().name + "s...");
    }
    
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        if (!placing) return;
        
        // Handle waiting for minecarts
        if (waitingForMinecarts) {
            if (waitCounter > 0) {
                waitCounter--;
                return;
            }
            
            // Check if we have minecarts now
            int minecartSlot = findMinecartInInventory();
            if (minecartSlot != -1) {
                waitingForMinecarts = false;
                info("§aMinecarts found! Resuming placement...");
                delayLeft = 0;
            } else {
                waitCounter = checkDelay.get();
                return;
            }
        }
        
        if (delayLeft > 0) {
            delayLeft--;
            return;
        }
        
        // Check if we've reached the limit
        int max = maxMinecarts.get();
        if (max > 0 && placedCount >= max) {
            info("§aPlaced §f" + max + " §aminecarts! Stopping.");
            placing = false;
            return;
        }
        
        // Loop through all rails to find one that needs a minecart
        boolean foundRail = false;
        
        for (int i = 0; i < targetRails.size(); i++) {
            int index = (currentIndex + i) % targetRails.size();
            BlockPos railPos = targetRails.get(index);
            
            // Check if block is still the correct rail type
            if (mc.world.getBlockState(railPos).getBlock() != railType.get().block) {
                continue;
            }
            
            // Check if rail already has a minecart
            if (skipOccupied.get() && hasMinecartOnRail(railPos)) {
                continue;
            }
            
            // Found a rail that needs a minecart
            foundRail = true;
            currentIndex = index;
            
            // Find minecart in inventory
            int minecartSlot = findMinecartInInventory();
            if (minecartSlot == -1) {
                if (!waitingForMinecarts) {
                    waitingForMinecarts = true;
                    waitCounter = checkDelay.get();
                    info("§eOut of " + minecartType.get().name + "s! Waiting for more...");
                }
                return;
            }
            
            // Place the minecart
            placeMinecart(railPos, minecartSlot);
            placedCount++;
            delayLeft = placeDelay.get();
            currentIndex = (currentIndex + 1) % targetRails.size();
            return;
        }
        
        // If no rails need minecarts, reset index and wait a bit before rechecking
        if (!foundRail) {
            if (!waitingForMinecarts) {
                info("§eAll rails processed! Waiting for rails to become available...");
                waitingForMinecarts = true;
                waitCounter = checkDelay.get();
            }
        }
    }
    
    private boolean hasMinecartOnRail(BlockPos railPos) {
        Box box = new Box(railPos.getX(), railPos.getY(), railPos.getZ(), 
                          railPos.getX() + 1, railPos.getY() + 1, railPos.getZ() + 1);
        return !mc.world.getEntitiesByClass(AbstractMinecartEntity.class, box, e -> true).isEmpty();
    }
    
    private int findMinecartInInventory() {
        Item targetItem = minecartType.get().item;
        
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() == targetItem) {
                return i;
            }
        }
        return -1;
    }
    
    private void placeMinecart(BlockPos railPos, int slot) {
        // Target the center of the rail block directly
        // This allows placing through walls
        Vec3d targetPos = Vec3d.ofCenter(railPos);
        
        // Create a hit result pointing directly at the rail
        BlockHitResult hitResult = new BlockHitResult(
            targetPos,
            Direction.UP,
            railPos,
            false
        );
        
        int previousSlot = mc.player.getInventory().selectedSlot;
        
        Rotations.rotate(Rotations.getYaw(targetPos), Rotations.getPitch(targetPos), () -> {
            // Switch to minecart slot
            if (slot < 9) {
                mc.player.getInventory().selectedSlot = slot;
            } else {
                // Swap to hotbar if needed
                int tempSlot = -1;
                for (int i = 0; i < 9; i++) {
                    if (mc.player.getInventory().getStack(i).isEmpty()) {
                        tempSlot = i;
                        break;
                    }
                }
                if (tempSlot == -1) tempSlot = 0;
                
                mc.interactionManager.clickSlot(
                    mc.player.currentScreenHandler.syncId,
                    slot,
                    tempSlot,
                    SlotActionType.SWAP,
                    mc.player
                );
                mc.player.getInventory().selectedSlot = tempSlot;
            }
            
            // Place the minecart
            mc.interactionManager.interactBlock(
                mc.player,
                Hand.MAIN_HAND,
                hitResult
            );
            
            mc.player.swingHand(Hand.MAIN_HAND);
            
            // Restore previous slot
            if (previousSlot != mc.player.getInventory().selectedSlot) {
                mc.player.getInventory().selectedSlot = previousSlot;
            }
        });
        
        info("§aPlaced " + minecartType.get().name + " on " + railType.get().name + " at §f" + railPos.getX() + ", " + railPos.getY() + ", " + railPos.getZ());
    }
    
    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get()) return;
        if (!placing) return;
        if (targetRails.isEmpty()) return;
        if (waitingForMinecarts) return;
        
        // Find the next rail to place
        BlockPos nextRail = null;
        for (int i = 0; i < targetRails.size(); i++) {
            int index = (currentIndex + i) % targetRails.size();
            BlockPos railPos = targetRails.get(index);
            
            // Check if block is still the correct rail type
            if (mc.world.getBlockState(railPos).getBlock() != railType.get().block) {
                continue;
            }
            
            // Check if rail already has a minecart
            if (skipOccupied.get() && hasMinecartOnRail(railPos)) {
                continue;
            }
            
            nextRail = railPos;
            break;
        }
        
        if (nextRail != null) {
            event.renderer.box(nextRail, highlightColor.get(), lineColor.get(), ShapeMode.Both, 0);
        }
    }
    
    @Override
    public void onDeactivate() {
        placing = false;
        waitingForMinecarts = false;
        targetRails.clear();
        currentIndex = 0;
        placedCount = 0;
        delayLeft = 0;
        waitCounter = 0;
    }
}