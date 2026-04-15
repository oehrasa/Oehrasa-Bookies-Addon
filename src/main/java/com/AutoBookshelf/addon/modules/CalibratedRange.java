package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.Set;

public class CalibratedRange extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    // Hardcoded sensor detection range (16 blocks - calibrated sculk sensor's natural range)
    private static final double SENSOR_RANGE = 16.0;

    private final Setting<Integer> renderDistance = sgGeneral.add(new IntSetting.Builder()
        .name("render-distance")
        .description("How far away to render spheres (blocks). Higher = more lag.")
        .defaultValue(64)
        .min(16)
        .max(128)
        .sliderRange(16, 128)
        .build()
    );

    private final Setting<Boolean> occlusion = sgRender.add(new BoolSetting.Builder()
        .name("occlusion")
        .description("Only render sphere blocks that are visible (not inside other blocks).")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> sphereColor = sgRender.add(new ColorSetting.Builder()
        .name("sphere-color")
        .description("Color of the sphere outline.")
        .defaultValue(new SettingColor(0, 255, 255, 200))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Color of the sphere lines.")
        .defaultValue(new SettingColor(0, 255, 255, 255))
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the sphere is rendered.")
        .defaultValue(ShapeMode.Lines)
        .build()
    );

    private final Setting<Boolean> showCenterBox = sgRender.add(new BoolSetting.Builder()
        .name("show-center-box")
        .description("Show a box at the sensor location.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> centerColor = sgRender.add(new ColorSetting.Builder()
        .name("center-color")
        .description("Color of the center box.")
        .defaultValue(new SettingColor(255, 255, 255, 150))
        .build()
    );

    private Set<BlockPos> sensors = new HashSet<>();
    private Set<BlockPos> sphereBlocks = new HashSet<>();
    private int tickCounter = 0;

    public CalibratedRange() {
        super(Addon.CATEGORY, "Calibrated-Range", "Shows the detection range of calibrated sculk sensors (16 blocks)");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null) return;
        
        // Only recalculate every 10 ticks (0.5 seconds)
        tickCounter++;
        if (tickCounter < 10) return;
        tickCounter = 0;
        
        // Find all calibrated sculk sensors within render distance
        sensors.clear();
        int playerX = mc.player.getBlockX();
        int playerZ = mc.player.getBlockZ();
        int renderDist = renderDistance.get();
        
        int minY = mc.world.getBottomY();
        int maxY = minY + mc.world.getHeight();
        
        for (int x = playerX - renderDist; x <= playerX + renderDist; x++) {
            for (int z = playerZ - renderDist; z <= playerZ + renderDist; z++) {
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (mc.world.getBlockState(pos).getBlock() == Blocks.CALIBRATED_SCULK_SENSOR) {
                        sensors.add(pos.toImmutable());
                    }
                }
            }
        }
        
        // Generate hollow sphere for each sensor (hardcoded 16 block radius)
        sphereBlocks.clear();
        
        for (BlockPos sensor : sensors) {
            sphereBlocks.addAll(generateHollowSphere(sensor, SENSOR_RANGE));
        }
    }
    
    private Set<BlockPos> generateHollowSphere(BlockPos center, double radius) {
        Set<BlockPos> positions = new HashSet<>();
        int r = (int) Math.ceil(radius);
        double invRadius = 1.0 / (radius + 0.5);
        
        for (int x = 0; x <= r; x++) {
            double xn = x * invRadius;
            double nextXn = (x + 1) * invRadius;
            
            for (int y = 0; y <= r; y++) {
                double yn = y * invRadius;
                double nextYn = (y + 1) * invRadius;
                
                for (int z = 0; z <= r; z++) {
                    double zn = z * invRadius;
                    double nextZn = (z + 1) * invRadius;
                    
                    double distSq = xn * xn + yn * yn + zn * zn;
                    if (distSq > 1) {
                        if (z == 0) {
                            if (y == 0) break;
                            break;
                        }
                        break;
                    }
                    
                    // Check if this voxel is on the surface (hollow)
                    boolean isInterior = (nextXn * nextXn + yn * yn + zn * zn <= 1) &&
                                         (xn * xn + nextYn * nextYn + zn * zn <= 1) &&
                                         (xn * xn + yn * yn + nextZn * nextZn <= 1);
                    
                    if (!isInterior) {
                        // Add all 8 octants
                        positions.add(center.add(x, y, z));
                        if (x != 0) positions.add(center.add(-x, y, z));
                        if (y != 0) positions.add(center.add(x, -y, z));
                        if (z != 0) positions.add(center.add(x, y, -z));
                        if (x != 0 && y != 0) positions.add(center.add(-x, -y, z));
                        if (x != 0 && z != 0) positions.add(center.add(-x, y, -z));
                        if (y != 0 && z != 0) positions.add(center.add(x, -y, -z));
                        if (x != 0 && y != 0 && z != 0) positions.add(center.add(-x, -y, -z));
                    }
                }
            }
        }
        
        return positions;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.world == null) return;
        
        // Render sphere blocks with occlusion
        for (BlockPos pos : sphereBlocks) {
            // Check occlusion: if the block is inside another block (not air), skip rendering
            if (occlusion.get() && !mc.world.getBlockState(pos).isAir()) {
                continue;
            }
            event.renderer.box(pos, sphereColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
        
        // Render center boxes for sensors
        if (showCenterBox.get()) {
            for (BlockPos sensor : sensors) {
                event.renderer.box(sensor, centerColor.get(), centerColor.get(), ShapeMode.Lines, 0);
            }
        }
    }
}