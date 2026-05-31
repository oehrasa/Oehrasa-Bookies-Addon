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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChestAura extends Module {
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
        .description("Cooldown before opening the next container in ticks.")
        .defaultValue(3)
        .min(1)
        .sliderMax(20)
        .build()
    );

    private final Setting<Integer> waitTime = sgGeneral.add(new IntSetting.Builder()
        .name("wait-time")
        .description("How long to wait after receiving packet before closing in ticks.")
        .defaultValue(2)
        .min(1)
        .sliderMax(20)
        .build()
    );

    private final Setting<Integer> timeout = sgGeneral.add(new IntSetting.Builder()
        .name("force-timeout")
        .description("Force close after this many ticks if container gets stuck.")
        .defaultValue(15)
        .min(5)
        .sliderMax(60)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotate towards container when opening.")
        .defaultValue(true)
        .build()
    );

    private final Map<BlockPos, Long> openedBlocks = new HashMap<>();
    private final PacketListener packetListener = new PacketListener();

    private int timer = 0;
    private int packetTimer = 0;
    private int stuckTimer = 0;

    private boolean isPending = false;

    public ChestAura() {
        super(Addon.CATEGORY, "Chest-Aura", "High-speed automatic container opener.");
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
        if (mc.level == null || mc.player == null) return;

        // --- 1. Stuck detection and state management ---

        if (isPending) {
            stuckTimer++;

            // If player manually closed screen or screen auto-closed due to distance
            if (mc.screen == null && stuckTimer > 5) {
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
        if (mc.screen != null) return;

        for (BlockEntity block : Utils.blockEntities()) {
            if (!blocks.get().contains(block.getType())) continue;

            if (mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(block.getBlockPos())) >= range.get()) continue;

            BlockPos pos = block.getBlockPos();
            if (openedBlocks.containsKey(pos)) continue;

            // --- 3. Execute opening ---

            Runnable click = () -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(new Vec3(pos.getX(), pos.getY(), pos.getZ()), Direction.UP, pos, false));

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

        BlockState state = block.getBlockState();
        if (state.hasProperty(ChestBlock.TYPE)) {
            Direction direction = state.getValue(ChestBlock.FACING);
            switch (state.getValue(ChestBlock.TYPE)) {
                case LEFT -> openedBlocks.put(pos.relative(direction.getClockWise()), System.currentTimeMillis());
                case RIGHT -> openedBlocks.put(pos.relative(direction.getCounterClockWise()), System.currentTimeMillis());
            }
        }
    }

    private void forceClose() {
        if (mc.player != null) {
            mc.player.closeContainer();
            if (mc.player.containerMenu != null) {
                mc.getConnection().send(new ServerboundContainerClosePacket(mc.player.containerMenu.containerId));
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
            if (event.packet instanceof ClientboundContainerSetContentPacket packet) {
                AbstractContainerMenu handler = mc.player.containerMenu;
                if (handler != null && packet.containerId() == handler.containerId) {
                    packetTimer = waitTime.get();
                }
            }
            // Fallback: sometimes container is empty or laggy, only OpenScreen packet is sent
            else if (event.packet instanceof ClientboundOpenScreenPacket packet) {
                if (packetTimer == 0) {
                    packetTimer = timeout.get() - 5;
                }
            }
        }
    }
}
