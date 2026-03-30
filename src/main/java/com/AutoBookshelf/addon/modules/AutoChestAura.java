package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.OpenScreenS2CPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AutoChestAura extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Maximum distance to open containers.")
        .defaultValue(5.0)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<List<BlockEntityType<?>>> blocks = sgGeneral.add(new StorageBlockListSetting.Builder()
        .name("container-types")
        .description("Select which containers to open.")
        .defaultValue(BlockEntityType.CHEST, BlockEntityType.BARREL, BlockEntityType.SHULKER_BOX)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("open-delay")
        .description("Cooldown before opening the next container (ticks)")
        .defaultValue(3)
        .min(1)
        .sliderMax(20)
        .build()
    );
    
    private final Setting<Integer> waitTime = sgGeneral.add(new IntSetting.Builder()
        .name("wait-time")
        .description("How long to wait after receiving packet before closing (ticks)")
        .defaultValue(2)
        .min(1)
        .sliderMax(20)
        .build()
    );

    private final Setting<Integer> timeout = sgGeneral.add(new IntSetting.Builder()
        .name("force-timeout")
        .description("Force close after this many ticks if container gets stuck")
        .defaultValue(15)
        .min(5)
        .sliderMax(60)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotate towards container when opening")
        .defaultValue(true)
        .build()
    );

    private final Map<BlockPos, Long> openedBlocks = new HashMap<>();
    private final PacketListener packetListener = new PacketListener();

    private int timer = 0;
    private int packetTimer = 0;
    private int stuckTimer = 0;
    
    private boolean isPending = false;

    public AutoChestAura() {
        super(Addon.CATEGORY, "Auto-ChestAura", "High-speed automatic container opener");
    }

    @Override
    public void onActivate() {
        timer = 0;
        packetTimer = 0;
        stuckTimer = 0;
        isPending = false;
        openedBlocks.clear();
        MeteorClient.EVENT_BUS.subscribe(packetListener);
    }

    @Override
    public void onDeactivate() {
        MeteorClient.EVENT_BUS.unsubscribe(packetListener);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.world == null || mc.player == null) return;

        // --- 1. Stuck detection and state management ---
        
        if (isPending) {
            stuckTimer++;
            
            // If player manually closed screen or screen auto-closed due to distance
            if (mc.currentScreen == null && stuckTimer > 5) {
                resetState();
                return;
            }

            // Countdown to close
            if (packetTimer > 0) {
                packetTimer--;
                if (packetTimer <= 0) {
                    forceClose();
                    return;
                }
            }

            // Force timeout protection
            if (stuckTimer >= timeout.get()) {
                forceClose();
                return;
            }
            
            return;
        }

        // --- 2. Find new container ---

        if (timer > 0) {
            timer--;
            return;
        }

        // Only operate when no screen is open
        if (mc.currentScreen != null) return;

        for (BlockEntity block : Utils.blockEntities()) {
            if (!blocks.get().contains(block.getType())) continue;
            
            if (mc.player.getEyePos().distanceTo(Vec3d.ofCenter(block.getPos())) >= range.get()) continue;

            BlockPos pos = block.getPos();
            if (openedBlocks.containsKey(pos)) continue;

            // --- 3. Execute opening ---
            
            Runnable click = () -> mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, 
                new BlockHitResult(new Vec3d(pos.getX(), pos.getY(), pos.getZ()), Direction.UP, pos, false));
            
            if (rotate.get()) {
                Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), click);
            } else {
                click.run();
            }

            // Mark double chests
            markOpened(block, pos);

            // Enter waiting state
            isPending = true;
            stuckTimer = 0;
            packetTimer = 0;
            timer = delay.get();
            
            break;
        }
    }

    private void markOpened(BlockEntity block, BlockPos pos) {
        openedBlocks.put(pos, System.currentTimeMillis());
        
        BlockState state = block.getCachedState();
        if (state.contains(ChestBlock.CHEST_TYPE)) {
            Direction direction = state.get(ChestBlock.FACING);
            switch (state.get(ChestBlock.CHEST_TYPE)) {
                case LEFT -> openedBlocks.put(pos.offset(direction.rotateYClockwise()), System.currentTimeMillis());
                case RIGHT -> openedBlocks.put(pos.offset(direction.rotateYCounterclockwise()), System.currentTimeMillis());
            }
        }
    }

    private void forceClose() {
        if (mc.player != null) {
            mc.player.closeHandledScreen();
            if (mc.player.currentScreenHandler != null) {
                mc.getNetworkHandler().sendPacket(new CloseHandledScreenC2SPacket(mc.player.currentScreenHandler.syncId));
            }
        }
        resetState();
    }

    private void resetState() {
        isPending = false;
        stuckTimer = 0;
        packetTimer = 0;
    }

    private class PacketListener {
        @EventHandler(priority = EventPriority.HIGH)
        private void onPacket(PacketEvent.Receive event) {
            if (!isPending) return;

            // Listen for InventoryS2CPacket (container contents)
            if (event.packet instanceof InventoryS2CPacket packet) {
                ScreenHandler handler = mc.player.currentScreenHandler;
                if (handler != null && packet.getSyncId() == handler.syncId) {
                    packetTimer = waitTime.get();
                }
            }
            // Fallback: sometimes container is empty or laggy, only OpenScreen packet is sent
            else if (event.packet instanceof OpenScreenS2CPacket packet) {
                if (packetTimer == 0) { 
                    packetTimer = timeout.get() - 5; 
                }
            }
        }
    }
}