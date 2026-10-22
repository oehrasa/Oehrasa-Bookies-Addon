package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.utils.HotbarSwap;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * B36 - places and ignites TNT in a radius around the player (airplace supported). TNT and flint
 * and steel are pulled from the inventory into the hotbar when missing.
 */
public class B36 extends Module {
    // Setting groups
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPlacement = settings.createGroup("Placement");
    private final SettingGroup sgInventory = settings.createGroup("Inventory");

    // General settings
    private final Setting<Double> radius = sgGeneral.add(new DoubleSetting.Builder()
        .name("radius")
        .description("The radius to place and light TNT")
        .defaultValue(4)
        .min(1)
        .sliderMax(6)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay between placing TNT in ticks")
        .defaultValue(6)
        .min(0)
        .sliderMax(20)
        .build()
    );

    // Placement settings
    private final Setting<Boolean> airPlace = sgPlacement.add(new BoolSetting.Builder()
        .name("air-place")
        .description("Use packet-based airplace for TNT placement")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyAirPlace = sgPlacement.add(new BoolSetting.Builder()
        .name("only-air-place")
        .description("Only place TNT on air blocks")
        .defaultValue(true)
        .visible(() -> !airPlace.get())
        .build()
    );

    // Inventory settings
    private final Setting<Boolean> autoMoveToHotbar = sgInventory.add(new BoolSetting.Builder()
        .name("auto-move-to-hotbar")
        .description("Automatically moves TNT and flint and steel from the inventory to the hotbar if not found there")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoDisable = sgInventory.add(new BoolSetting.Builder()
        .name("auto-disable")
        .description("Automatically disables when no TNT is found in hotbar or inventory.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> maxAttempts = sgInventory.add(new IntSetting.Builder()
        .name("max-attempts")
        .description("Maximum number of placement attempts before disabling")
        .defaultValue(100)
        .min(10)
        .sliderMax(200)
        .visible(() -> autoDisable.get())
        .build()
    );

    // Module state variables
    private int ticksWaited = 0;
    private int originalSlot = -1;
    private int placementAttempts = 0;
    private BlockPos lastPos = null;

    public B36() {
        super(Addon.CATEGORY, "B36-Peacemaker", "Automatically places and lights TNT around you, Created this to make peace. Named after Convair B-36 Peacemaker");
    }

    @Override
    public void onActivate() {
        originalSlot = mc.player.getInventory().getSelectedSlot();
        ticksWaited = 0;
        placementAttempts = 0;
        lastPos = null;
    }

    @Override
    public void onDeactivate() {
        if (originalSlot != -1) {
            mc.player.getInventory().setSelectedSlot(originalSlot);
            originalSlot = -1;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        // Handle delay between operations
        if (ticksWaited < delay.get()) {
            ticksWaited++;
            return;
        }
        ticksWaited = 0;

        // Save original slot if not saved yet
        if (originalSlot == -1) {
            originalSlot = mc.player.getInventory().getSelectedSlot();
        }

        // Find required items and handle inventory management
        if (!prepareInventory()) return;

        // Place and light TNT
        boolean placed = placeTnt();

        // Update placement attempts counter
        if (placed) {
            placementAttempts = 0;
        } else if (autoDisable.get() && placementAttempts >= maxAttempts.get()) {
            toggle();
        }
    }

    /**
     * Prepares inventory by finding TNT and flint and steel.
     * Pulls either in from the main inventory when it is only there.
     * @return true if preparation successful, false otherwise
     */
    private boolean prepareInventory() {
        // Get TNT slot or try to move TNT to hotbar
        int tntSlot = findTntInHotbar();
        if (tntSlot == -1 && autoMoveToHotbar.get()) {
            tntSlot = HotbarSwap.ensureInHotbar(stack -> stack.is(Items.TNT), 0);
        }
        if (tntSlot == -1) {
            if (autoDisable.get()) {
                toggle();
            }
            return false;
        }

        // Search for flint and steel in hotbar, pulling it in from the inventory if needed. The two
        // items have to sit in the hotbar together (placeTnt swaps between them), so the flint pull
        // uses a fallback slot that is never the TNT's, otherwise a full hotbar would let it
        // clobber the stack that was just provisioned.
        int flintAndSteelSlot = findFlintAndSteelInHotbar();
        if (flintAndSteelSlot == -1 && autoMoveToHotbar.get()) {
            flintAndSteelSlot = HotbarSwap.ensureInHotbar(stack -> stack.is(Items.FLINT_AND_STEEL), tntSlot == 0 ? 1 : 0);
        }
        if (flintAndSteelSlot == -1) {
            if (autoDisable.get()) {
                toggle();
            }
            return false;
        }

        return true;
    }

    /**
     * Places and lights TNT in a radius around the player.
     * @return true if TNT was placed, false otherwise
     */
    private boolean placeTnt() {
        // Increment placement attempts
        placementAttempts++;

        // Get TNT and flint and steel slots
        int tntSlot = findTntInHotbar();
        int flintAndSteelSlot = findFlintAndSteelInHotbar();
        if (tntSlot == -1 || flintAndSteelSlot == -1) return false;

        // Remember current slot to restore if no placement happens
        int previousSlot = mc.player.getInventory().getSelectedSlot();

        // Force switch to TNT slot and notify the server
        mc.player.getInventory().setSelectedSlot(tntSlot);
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(tntSlot));

        // Get player position and calculate placement area
        BlockPos playerPos = mc.player.blockPosition();
        double r = radius.get();
        boolean placed = false;

        // Iterate through blocks in radius
        for (double x = -r; x <= r; x++) {
            for (double z = -r; z <= r; z++) {
                // Check if block is within the circle
                if (x * x + z * z > r * r) continue;

                BlockPos blockPos = playerPos.offset((int) x, 0, (int) z);

                // Skip if this is the same position as last time
                if (blockPos.equals(lastPos)) continue;

                // Check if we can place TNT here (for non-airplace mode)
                if (!airPlace.get() && !canPlace(blockPos)) continue;

                // Save this position as the last position
                lastPos = blockPos;

                try {
                    // Double-check we're using TNT
                    if (mc.player.getMainHandItem().getItem() != Items.TNT) {
                        // Try forcing the slot again
                        mc.player.getInventory().setSelectedSlot(tntSlot);
                        mc.player.connection.send(new ServerboundSetCarriedItemPacket(tntSlot));

                        // Skip if still not holding TNT
                        if (mc.player.getMainHandItem().getItem() != Items.TNT) {
                            continue;
                        }
                    }

                    // Place TNT using appropriate method
                    if (airPlace.get()) {
                        airPlaceTnt(blockPos);
                    } else {
                        placeBlock(blockPos);
                    }

                    // Switch to flint and steel and notify the server
                    mc.player.getInventory().setSelectedSlot(flintAndSteelSlot);
                    mc.player.connection.send(new ServerboundSetCarriedItemPacket(flintAndSteelSlot));

                    // Verify we have flint and steel in hand
                    if (mc.player.getMainHandItem().getItem() != Items.FLINT_AND_STEEL) {
                        continue;
                    } else {
                        // Light TNT
                        useFlintAndSteel(blockPos);
                        mc.player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
                    }

                    placed = true;
                } catch (Exception e) {
                    // Silently handle exceptions
                } finally {
                    // Always return to original slot
                    mc.player.getInventory().setSelectedSlot(originalSlot);
                    mc.player.connection.send(new ServerboundSetCarriedItemPacket(originalSlot));
                }

                break;
            }
            if (placed) break;
        }

        // If we didn't place anything but changed slots, restore previous slot
        if (!placed && mc.player.getInventory().getSelectedSlot() != previousSlot) {
            mc.player.getInventory().setSelectedSlot(previousSlot);
            mc.player.connection.send(new ServerboundSetCarriedItemPacket(previousSlot));
        }

        return placed;
    }

    /**
     * Places TNT at the specified position using airplace packets.
     * @param pos The position to place at
     */
    private void airPlaceTnt(BlockPos pos) {
        BlockHitResult bhr = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);

        // Get current revision for accurate packet
        int currentRevision = mc.player.containerMenu.getStateId();

        // Send the 3 packet sequence for airplace
        mc.player.connection.send(new ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));

        mc.player.connection.send(new ServerboundUseItemOnPacket(
            InteractionHand.OFF_HAND, bhr, currentRevision));

        // Swap back
        mc.player.connection.send(new ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));

        // Visual feedback
        mc.player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
    }

    /**
     * Checks if a block can be placed at the specified position.
     * @param pos The position to check
     * @return true if placement is possible, false otherwise
     */
    private boolean canPlace(BlockPos pos) {
        if (onlyAirPlace.get()) {
            return mc.level.getBlockState(pos).isAir();
        } else {
            return mc.level.getBlockState(pos).canBeReplaced();
        }
    }

    /**
     * Places a block at the specified position using normal interaction.
     * @param pos The position to place at
     */
    private void placeBlock(BlockPos pos) {
        Vec3 hitPos = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        BlockHitResult hit = new BlockHitResult(hitPos, Direction.UP, pos, false);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        mc.player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
    }

    /**
     * Uses flint and steel at the specified position.
     * @param pos The position to use flint and steel
     */
    private void useFlintAndSteel(BlockPos pos) {
        Vec3 hitPos = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        BlockHitResult hit = new BlockHitResult(hitPos, Direction.UP, pos, false);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
    }

    /**
     * Finds TNT in the player's hotbar.
     * @return The slot index (0-8) or -1 if not found
     */
    private int findTntInHotbar() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).getItem() == Items.TNT) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Finds flint and steel in the player's hotbar.
     * @return The slot index (0-8) or -1 if not found
     */
    private int findFlintAndSteelInHotbar() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).getItem() == Items.FLINT_AND_STEEL) {
                return i;
            }
        }
        return -1;
    }
}
