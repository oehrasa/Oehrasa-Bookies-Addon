package com.AutoBookshelf.addon.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BeaconBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import com.AutoBookshelf.addon.Addon;

public class BeaconRange extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> cullOverlapping = sgGeneral.add(new BoolSetting.Builder()
        .name("cull-overlapping")
        .description("Cull overlapping boxes.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The side color of the rendering.")
        .defaultValue(new SettingColor(0, 255, 255, 60))
        .visible(() -> shapeMode.get().sides())
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The line color of the rendering.")
        .defaultValue(new SettingColor(0, 255, 255, 255))
        .visible(() -> shapeMode.get().lines())
        .build()
    );

    private final java.util.List<BeaconBox> beaconBoxes = new java.util.ArrayList<>();

    private static class BeaconBox {
        double minX, minY, minZ, maxX, maxY, maxZ;
        int level;
        
        BeaconBox(double x1, double y1, double z1, double x2, double y2, double z2, int level) {
            this.minX = Math.min(x1, x2);
            this.minY = Math.min(y1, y2);
            this.minZ = Math.min(z1, z2);
            this.maxX = Math.max(x1, x2);
            this.maxY = Math.max(y1, y2);
            this.maxZ = Math.max(z1, z2);
            this.level = level;
        }
        
        boolean intersects(BeaconBox other) {
            return this.minX < other.maxX && this.maxX > other.minX &&
                   this.minY < other.maxY && this.maxY > other.minY &&
                   this.minZ < other.maxZ && this.maxZ > other.minZ;
        }
    }

    public BeaconRange() {
        super(Addon.CATEGORY, "beacon-range", "Renders the range of powered beacons.");
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;
        
        beaconBoxes.clear();
        
        // Find all beacons
        for (BlockEntity blockEntity : Utils.blockEntities()) {
            if (!(blockEntity instanceof BeaconBlockEntity)) continue;
            
            BlockPos pos = blockEntity.getPos();
            int level = getBeaconLevel(pos);
            if (level < 1) continue;
            
            int range = 10 + (level * 10);
            
            double x1 = pos.getX() - range;
            double y1 = pos.getY() - range;
            double z1 = pos.getZ() - range;
            double x2 = pos.getX() + range;
            double y2 = pos.getY() + range;
            double z2 = pos.getZ() + range;
            
            beaconBoxes.add(new BeaconBox(x1, y1, z1, x2, y2, z2, level));
        }
        
        // Render each beacon box
        for (int i = 0; i < beaconBoxes.size(); i++) {
            BeaconBox box = beaconBoxes.get(i);
            
            // Check if this box should be culled by a higher-level beacon
            boolean shouldRender = true;
            if (cullOverlapping.get()) {
                for (int j = 0; j < beaconBoxes.size(); j++) {
                    if (i == j) continue;
                    BeaconBox other = beaconBoxes.get(j);
                    // If another beacon has higher level and fully contains this one, don't render
                    if (other.level > box.level && 
                        other.minX <= box.minX && other.maxX >= box.maxX &&
                        other.minY <= box.minY && other.maxY >= box.maxY &&
                        other.minZ <= box.minZ && other.maxZ >= box.maxZ) {
                        shouldRender = false;
                        break;
                    }
                }
            }
            
            if (shouldRender) {
                renderBox(event.renderer, box);
            }
        }
    }
    
    private void renderBox(Renderer3D renderer, BeaconBox box) {
        if (shapeMode.get().lines()) {
            renderer.boxLines(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, lineColor.get(), 0);
        }
        if (shapeMode.get().sides()) {
            int origAlpha = sideColor.get().a;
            sideColor.get().a(sideColor.get().a);
            renderer.boxSides(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, sideColor.get(), 0);
            sideColor.get().a(origAlpha);
        }
    }
    
    private int getBeaconLevel(BlockPos beaconPos) {
        int level = 0;
        
        for (int y = 1; y <= 4; y++) {
            int layerY = beaconPos.getY() - y;
            if (layerY < mc.world.getBottomY()) break;
            
            boolean validLayer = true;
            
            for (int x = -y; x <= y; x++) {
                for (int z = -y; z <= y; z++) {
                    BlockPos checkPos = beaconPos.add(x, -y, z);
                    if (!isValidBeaconBase(mc.world.getBlockState(checkPos).getBlock())) {
                        validLayer = false;
                        break;
                    }
                }
                if (!validLayer) break;
            }
            
            if (validLayer) {
                level++;
            } else {
                break;
            }
        }
        
        return level;
    }
    
    private boolean isValidBeaconBase(net.minecraft.block.Block block) {
        return block == Blocks.IRON_BLOCK ||
               block == Blocks.GOLD_BLOCK ||
               block == Blocks.EMERALD_BLOCK ||
               block == Blocks.DIAMOND_BLOCK ||
               block == Blocks.NETHERITE_BLOCK;
    }
    
    public enum ShapeMode {
        Lines,
        Sides,
        Both;
        
        public boolean lines() {
            return this == Lines || this == Both;
        }
        
        public boolean sides() {
            return this == Sides || this == Both;
        }
    }
}