package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.CalibratedSculkSensorBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.entity.CalibratedSculkSensorBlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CalibratedRange extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgColors = settings.createGroup("Colors");

    private final Setting<Integer> renderDistance = sgGeneral.add(new IntSetting.Builder()
        .name("render-distance")
        .description("Maximum distance to render sensors from the player")
        .defaultValue(64)
        .min(16)
        .max(128)
        .sliderMax(128)
        .build()
    );

    private final Setting<Double> opacity = sgGeneral.add(new DoubleSetting.Builder()
        .name("opacity")
        .description("Opacity of the sphere render")
        .defaultValue(0.5)
        .min(0.0)
        .max(1.0)
        .sliderMax(1.0)
        .build()
    );

    private final Setting<Boolean> showLabel = sgGeneral.add(new BoolSetting.Builder()
        .name("show-label")
        .description("Show frequency label above the sensor")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> cacheTimeout = sgGeneral.add(new IntSetting.Builder()
        .name("cache-timeout")
        .description("How many seconds to cache sphere positions before recalculating")
        .defaultValue(5)
        .min(1)
        .max(30)
        .sliderMax(30)
        .build()
    );

    // Color settings for each frequency (1-15)
    private final Setting<SettingColor> frequency1Color = sgColors.add(new ColorSetting.Builder().name("frequency-1-color").description("Step / Swim / Flap").defaultValue(new SettingColor(0, 0, 255)).build());
    private final Setting<SettingColor> frequency2Color = sgColors.add(new ColorSetting.Builder().name("frequency-2-color").description("Projectile / Hit Ground / Splash").defaultValue(new SettingColor(0, 100, 255)).build());
    private final Setting<SettingColor> frequency3Color = sgColors.add(new ColorSetting.Builder().name("frequency-3-color").description("Item Interact / Shoot / Instrument").defaultValue(new SettingColor(0, 150, 255)).build());
    private final Setting<SettingColor> frequency4Color = sgColors.add(new ColorSetting.Builder().name("frequency-4-color").description("Entity Action / Elytra / Unequip").defaultValue(new SettingColor(0, 200, 255)).build());
    private final Setting<SettingColor> frequency5Color = sgColors.add(new ColorSetting.Builder().name("frequency-5-color").description("Dismount / Equip").defaultValue(new SettingColor(0, 255, 255)).build());
    private final Setting<SettingColor> frequency6Color = sgColors.add(new ColorSetting.Builder().name("frequency-6-color").description("Mount / Interact / Shear").defaultValue(new SettingColor(0, 255, 200)).build());
    private final Setting<SettingColor> frequency7Color = sgColors.add(new ColorSetting.Builder().name("frequency-7-color").description("Damage").defaultValue(new SettingColor(0, 255, 150)).build());
    private final Setting<SettingColor> frequency8Color = sgColors.add(new ColorSetting.Builder().name("frequency-8-color").description("Drink / Eat").defaultValue(new SettingColor(0, 255, 100)).build());
    private final Setting<SettingColor> frequency9Color = sgColors.add(new ColorSetting.Builder().name("frequency-9-color").description("Container Close / Deactivate").defaultValue(new SettingColor(0, 255, 50)).build());
    private final Setting<SettingColor> frequency10Color = sgColors.add(new ColorSetting.Builder().name("frequency-10-color").description("Container Open / Activate").defaultValue(new SettingColor(0, 255, 0)).build());
    private final Setting<SettingColor> frequency11Color = sgColors.add(new ColorSetting.Builder().name("frequency-11-color").description("Block Change").defaultValue(new SettingColor(100, 255, 0)).build());
    private final Setting<SettingColor> frequency12Color = sgColors.add(new ColorSetting.Builder().name("frequency-12-color").description("Block Destroy").defaultValue(new SettingColor(150, 255, 0)).build());
    private final Setting<SettingColor> frequency13Color = sgColors.add(new ColorSetting.Builder().name("frequency-13-color").description("Block Place").defaultValue(new SettingColor(200, 255, 0)).build());
    private final Setting<SettingColor> frequency14Color = sgColors.add(new ColorSetting.Builder().name("frequency-14-color").description("Entity Place / Teleport").defaultValue(new SettingColor(255, 200, 0)).build());
    private final Setting<SettingColor> frequency15Color = sgColors.add(new ColorSetting.Builder().name("frequency-15-color").description("Die / Explode").defaultValue(new SettingColor(255, 0, 0)).build());

    private static class SphereCache {
        final Set<BlockPos> positions;
        final long timestamp;
        final int frequency;
        
        SphereCache(Set<BlockPos> positions, int frequency) {
            this.positions = positions;
            this.frequency = frequency;
            this.timestamp = System.currentTimeMillis();
        }
        
        boolean isValid(int timeoutSeconds, int currentFrequency) {
            return frequency == currentFrequency && 
                   System.currentTimeMillis() - timestamp < timeoutSeconds * 1000L;
        }
    }
    
    private final Map<BlockPos, SphereCache> sphereCache = new ConcurrentHashMap<>();

    public CalibratedRange() {
        super(Addon.CATEGORY, "CalibratedRange", "kill your fps");
    }
    
    @Override
    public void onDeactivate() {
        sphereCache.clear();
        super.onDeactivate();
    }
    
    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!isActive()) return;
        if (mc.player == null || mc.world == null) return;
        
        Vec3d cameraPos = mc.player.getPos();
        int renderDistSq = renderDistance.get() * renderDistance.get();
        
        List<BlockPos> sensors = getSensorsInRange(cameraPos, renderDistSq);
        
        for (BlockPos pos : sensors) {
            int frequency = getSensorFrequency(pos);
            if (frequency == 0) continue; // Skip if no frequency detected
            
            Color color = getColorForFrequency(frequency);
            Color renderColor = new Color(color.r, color.g, color.b, (int)(opacity.get() * 255));
            
            SphereCache cache = sphereCache.get(pos);
            if (cache == null || !cache.isValid(cacheTimeout.get(), frequency)) {
                Set<BlockPos> positions = calculateHollowSphere(pos, 16.0);
                sphereCache.put(pos, new SphereCache(positions, frequency));
                cache = sphereCache.get(pos);
            }
            
            if (cache == null) continue;
            
            // Render each block in the hollow sphere
            for (BlockPos blockPos : cache.positions) {
                if (isBlockVisible(blockPos, pos)) {
                    event.renderer.box(blockPos, renderColor, renderColor, ShapeMode.Sides, 0);
                }
            }
            
            if (showLabel.get()) {
                renderLabel(event, pos, frequency, color);
            }
        }
        
        cleanupCache();
    }
    
    private List<BlockPos> getSensorsInRange(Vec3d cameraPos, int renderDistSq) {
        List<BlockPos> sensors = new ArrayList<>();
        int radius = (int) Math.sqrt(renderDistSq);
        
        for (int x = (int)cameraPos.x - radius; x <= (int)cameraPos.x + radius; x++) {
            for (int z = (int)cameraPos.z - radius; z <= (int)cameraPos.z + radius; z++) {
                if (!mc.world.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) continue;
                
                for (int y = mc.world.getBottomY(); y <= mc.world.getHeight(); y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    double dx = cameraPos.x - (x + 0.5);
                    double dy = cameraPos.y - (y + 0.5);
                    double dz = cameraPos.z - (z + 0.5);
                    
                    if (dx * dx + dy * dy + dz * dz <= renderDistSq) {
                        if (mc.world.getBlockState(pos).getBlock() instanceof CalibratedSculkSensorBlock) {
                            sensors.add(pos);
                        }
                    }
                }
            }
        }
        return sensors;
    }
    
    private int getSensorFrequency(BlockPos pos) {
        BlockEntity blockEntity = mc.world.getBlockEntity(pos);
        
        if (blockEntity instanceof CalibratedSculkSensorBlockEntity sensor) {
            // Method 1: Read last_vibration_frequency from NBT (most reliable)
            // Need to pass RegistryWrapper.WrapperLookup
            RegistryWrapper.WrapperLookup registries = mc.world.getRegistryManager();
            NbtCompound nbt = sensor.createNbt(registries);
            if (nbt.contains("last_vibration_frequency")) {
                return nbt.getInt("last_vibration_frequency");
            }
            
            // Method 2: Read from listener NBT (for real-time events)
            if (nbt.contains("listener")) {
                NbtCompound listener = nbt.getCompound("listener");
                if (listener.contains("event")) {
                    NbtCompound event = listener.getCompound("event");
                    if (event.contains("game_event")) {
                        String gameEvent = event.getString("game_event");
                        return extractFrequencyFromGameEvent(gameEvent);
                    }
                }
            }
        }
        
        // Fallback: Try to get from block state (redstone power)
        BlockState state = mc.world.getBlockState(pos);
        if (state.getBlock() instanceof CalibratedSculkSensorBlock && state.contains(CalibratedSculkSensorBlock.POWER)) {
            int power = state.get(CalibratedSculkSensorBlock.POWER);
            // Map power (distance-based) to approximate frequency
            if (power > 0) {
                return Math.min(15, Math.max(1, power));
            }
        }
        
        return 0;
    }
    
    private int extractFrequencyFromGameEvent(String gameEvent) {
        // Check for resonate events first (calibrated sensor specific)
        if (gameEvent.contains("resonate_")) {
            String[] parts = gameEvent.split("_");
            try {
                return Integer.parseInt(parts[parts.length - 1]);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        
        // Map regular game events to frequencies
        return switch (gameEvent) {
            // Frequency 1
            case "minecraft:step", "minecraft:swim", "minecraft:flap" -> 1;
            // Frequency 2
            case "minecraft:projectile_land", "minecraft:hit_ground", "minecraft:splash" -> 2;
            // Frequency 3
            case "minecraft:item_interact_finish", "minecraft:projectile_shoot", "minecraft:instrument_play" -> 3;
            // Frequency 4
            case "minecraft:entity_action", "minecraft:elytra_glide", "minecraft:unequip" -> 4;
            // Frequency 5
            case "minecraft:entity_dismount", "minecraft:equip" -> 5;
            // Frequency 6
            case "minecraft:entity_mount", "minecraft:entity_interact", "minecraft:shear" -> 6;
            // Frequency 7
            case "minecraft:entity_damage" -> 7;
            // Frequency 8
            case "minecraft:drink", "minecraft:eat" -> 8;
            // Frequency 9
            case "minecraft:container_close", "minecraft:block_close", "minecraft:block_deactivate", "minecraft:block_detach" -> 9;
            // Frequency 10
            case "minecraft:container_open", "minecraft:block_open", "minecraft:block_activate", "minecraft:block_attach", "minecraft:prime_fuse", "minecraft:note_block_play" -> 10;
            // Frequency 11
            case "minecraft:block_change" -> 11;
            // Frequency 12
            case "minecraft:block_destroy", "minecraft:fluid_pickup" -> 12;
            // Frequency 13
            case "minecraft:block_place", "minecraft:fluid_place" -> 13;
            // Frequency 14
            case "minecraft:entity_place", "minecraft:lightning_strike", "minecraft:teleport" -> 14;
            // Frequency 15
            case "minecraft:entity_die", "minecraft:explode" -> 15;
            default -> 0;
        };
    }
    
    private Color getColorForFrequency(int frequency) {
        return switch (frequency) {
            case 1 -> frequency1Color.get();
            case 2 -> frequency2Color.get();
            case 3 -> frequency3Color.get();
            case 4 -> frequency4Color.get();
            case 5 -> frequency5Color.get();
            case 6 -> frequency6Color.get();
            case 7 -> frequency7Color.get();
            case 8 -> frequency8Color.get();
            case 9 -> frequency9Color.get();
            case 10 -> frequency10Color.get();
            case 11 -> frequency11Color.get();
            case 12 -> frequency12Color.get();
            case 13 -> frequency13Color.get();
            case 14 -> frequency14Color.get();
            case 15 -> frequency15Color.get();
            default -> new Color(255, 255, 255);
        };
    }
    
    // Calculate hollow sphere (only shell, 1 block thick)
    private Set<BlockPos> calculateHollowSphere(BlockPos center, double radius) {
        Set<BlockPos> positions = new HashSet<>();
        int radiusInt = (int) Math.ceil(radius);
        
        for (int x = -radiusInt; x <= radiusInt; x++) {
            for (int y = -radiusInt; y <= radiusInt; y++) {
                for (int z = -radiusInt; z <= radiusInt; z++) {
                    double dist = Math.sqrt(x*x + y*y + z*z);
                    // Only include blocks at the surface (within 0.5 block thickness)
                    if (Math.abs(dist - radius) <= 0.6) {
                        BlockPos pos = new BlockPos(center.getX() + x, center.getY() + y, center.getZ() + z);
                        positions.add(pos);
                    }
                }
            }
        }
        
        return positions;
    }
    
    private boolean isBlockVisible(BlockPos target, BlockPos center) {
        // Don't render the center block itself
        if (target.equals(center)) return false;
        
        // Check if the target block is solid (if solid, don't render)
        BlockState targetState = mc.world.getBlockState(target);
        if (targetState.isFullCube(mc.world, target) && !targetState.isAir()) {
            return false;
        }
        
        // Raycast from center to target to check for solid blocks in between
        Vec3d start = new Vec3d(center.getX() + 0.5, center.getY() + 0.5, center.getZ() + 0.5);
        Vec3d end = new Vec3d(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        Vec3d dir = end.subtract(start).normalize();
        double distance = start.distanceTo(end);
        
        for (double t = 0.1; t < distance; t += 0.2) {
            Vec3d check = start.add(dir.multiply(t));
            BlockPos checkPos = new BlockPos((int) Math.floor(check.x), (int) Math.floor(check.y), (int) Math.floor(check.z));
            
            if (checkPos.equals(center) || checkPos.equals(target)) continue;
            
            BlockState checkState = mc.world.getBlockState(checkPos);
            if (checkState.isFullCube(mc.world, checkPos) && !checkState.isAir()) {
                return false;
            }
        }
        
        return true;
    }
    
    private void renderLabel(Render3DEvent event, BlockPos pos, int frequency, Color color) {
        try {
            double x = pos.getX() + 0.5 - event.offsetX;
            double y = pos.getY() + 1.8 - event.offsetY;
            double z = pos.getZ() + 0.5 - event.offsetZ;
            
            String frequencyName = getFrequencyName(frequency);
            String text = String.format("%d: %s", frequency, frequencyName);
            
            event.matrices.push();
            event.matrices.translate(x, y, z);
            
            Vec3d cameraPos = mc.gameRenderer.getCamera().getPos();
            Vec3d pos3d = new Vec3d(pos.getX() + 0.5, pos.getY() + 1.8, pos.getZ() + 0.5);
            Vec3d direction = pos3d.subtract(cameraPos).normalize();
            
            float yaw = (float) Math.toDegrees(Math.atan2(direction.z, direction.x)) - 90;
            float pitch = (float) Math.toDegrees(Math.asin(-direction.y));
            
            event.matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw));
            event.matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(pitch));
            event.matrices.scale(0.025f, 0.025f, 0.025f);
            
            TextRenderer textRenderer = TextRenderer.get();
            textRenderer.begin(1.0, true, true);
            double textWidth = textRenderer.getWidth(text);
            textRenderer.render(text, -textWidth / 2, 0, color, true);
            textRenderer.end();
            
            event.matrices.pop();
        } catch (Exception e) {
            // Fallback - do nothing
        }
    }
    
    private String getFrequencyName(int frequency) {
        return switch (frequency) {
            case 1 -> "Step / Swim / Flap";
            case 2 -> "Projectile / Hit Ground / Splash";
            case 3 -> "Item Interact / Shoot / Instrument";
            case 4 -> "Entity Action / Elytra / Unequip";
            case 5 -> "Dismount / Equip";
            case 6 -> "Mount / Interact / Shear";
            case 7 -> "Damage";
            case 8 -> "Drink / Eat";
            case 9 -> "Container Close / Deactivate";
            case 10 -> "Container Open / Activate";
            case 11 -> "Block Change";
            case 12 -> "Block Destroy";
            case 13 -> "Block Place";
            case 14 -> "Entity Place / Teleport";
            case 15 -> "Die / Explode";
            default -> "Unknown";
        };
    }
    
    private void cleanupCache() {
        int timeout = cacheTimeout.get();
        sphereCache.entrySet().removeIf(entry -> {
            if (mc.world == null) return true;
            BlockPos pos = entry.getKey();
            if (!(mc.world.getBlockState(pos).getBlock() instanceof CalibratedSculkSensorBlock)) return true;
            int currentFrequency = getSensorFrequency(pos);
            return !entry.getValue().isValid(timeout, currentFrequency);
        });
    }
}