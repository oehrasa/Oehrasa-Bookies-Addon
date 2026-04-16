package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * B52 Module - Places and ignites TNT around the player.
 * Supports airplace mode for placing TNT in the air.
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
        .description("Automatically moves TNT from inventory to hotbar if none is found")
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
        super(Addon.CATEGORY, "B36", "Automatically places and lights TNT around you, Created this to make peace. Named after Convair B-36 Peacemaker");
    }

    @Override
    public void onActivate() {
        originalSlot = mc.player.getInventory().selectedSlot;
        ticksWaited = 0;
        placementAttempts = 0;
        lastPos = null;
    }

    @Override
    public void onDeactivate() {
        if (originalSlot != -1) {
            mc.player.getInventory().selectedSlot = originalSlot;
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
            originalSlot = mc.player.getInventory().selectedSlot;
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
     * Moves TNT from inventory to hotbar if needed.
     * @return true if preparation successful, false otherwise
     */
    private boolean prepareInventory() {
        // Get TNT slot or try to move TNT to hotbar
        int tntSlot = findTntInHotbar();
        if (tntSlot == -1) {
            if (autoMoveToHotbar.get()) {
                boolean moved = moveTntToHotbar();
                if (moved) {
                    tntSlot = findTntInHotbar(); // Get the new slot
                } else {
                    if (autoDisable.get()) {
                        toggle();
                    }
                    return false;
                }
            } else {
                if (autoDisable.get()) {
                    toggle();
                }
                return false;
            }
        }

        // Search for flint and steel in hotbar
        int flintAndSteelSlot = findFlintAndSteelInHotbar();
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
        int previousSlot = mc.player.getInventory().selectedSlot;

        // Force switch to TNT slot and notify the server
        mc.player.getInventory().selectedSlot = tntSlot;
        mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(tntSlot));

        // Get player position and calculate placement area
        BlockPos playerPos = mc.player.getBlockPos();
        double r = radius.get();
        boolean placed = false;

        // Iterate through blocks in radius
        for (double x = -r; x <= r; x++) {
            for (double z = -r; z <= r; z++) {
                // Check if block is within the circle
                if (x * x + z * z > r * r) continue;

                BlockPos blockPos = playerPos.add((int) x, 0, (int) z);

                // Skip if this is the same position as last time
                if (blockPos.equals(lastPos)) continue;

                // Check if we can place TNT here (for non-airplace mode)
                if (!airPlace.get() && !canPlace(blockPos)) continue;

                // Save this position as the last position
                lastPos = blockPos;

                try {
                    // Double-check we're using TNT
                    if (mc.player.getMainHandStack().getItem() != Items.TNT) {
                        // Try forcing the slot again
                        mc.player.getInventory().selectedSlot = tntSlot;
                        mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(tntSlot));

                        // Skip if still not holding TNT
                        if (mc.player.getMainHandStack().getItem() != Items.TNT) {
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
                    mc.player.getInventory().selectedSlot = flintAndSteelSlot;
                    mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(flintAndSteelSlot));

                    // Verify we have flint and steel in hand
                    if (mc.player.getMainHandStack().getItem() != Items.FLINT_AND_STEEL) {
                        continue;
                    } else {
                        // Light TNT
                        useFlintAndSteel(blockPos);
                        mc.player.swingHand(Hand.MAIN_HAND);
                    }

                    placed = true;
                } catch (Exception e) {
                    // Silently handle exceptions
                } finally {
                    // Always return to original slot
                    mc.player.getInventory().selectedSlot = originalSlot;
                    mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(originalSlot));
                }

                break;
            }
            if (placed) break;
        }

        // If we didn't place anything but changed slots, restore previous slot
        if (!placed && mc.player.getInventory().selectedSlot != previousSlot) {
            mc.player.getInventory().selectedSlot = previousSlot;
            mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(previousSlot));
        }

        return placed;
    }

    /**
     * Places TNT at the specified position using airplace packets.
     * @param pos The position to place at
     */
    private void airPlaceTnt(BlockPos pos) {
        BlockHitResult bhr = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);

        // Get current revision for accurate packet
        int currentRevision = mc.player.currentScreenHandler.getRevision();

        // Send the 3-packet sequence for airplace
        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));

        mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(
            Hand.OFF_HAND, bhr, currentRevision));

        // Swap back
        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));

        // Visual feedback
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    /**
     * Verifies that the player is holding the specified item in the specified slot.
     * @param slot The hotbar slot to check
     * @param expectedItem The item expected in that slot
     * @return true if holding the correct item, false otherwise
     */
    private boolean verifyHoldingItem(int slot, net.minecraft.item.Item expectedItem) {
        // Force the client to update the selected slot
        mc.player.getInventory().selectedSlot = slot;

        // Give the client a small moment to register the change
        mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));

        // Verify we have the expected item in hand
        return mc.player.getMainHandStack().getItem() == expectedItem;
    }

    /**
     * Checks if a block can be placed at the specified position.
     * @param pos The position to check
     * @return true if placement is possible, false otherwise
     */
    private boolean canPlace(BlockPos pos) {
        if (onlyAirPlace.get()) {
            return mc.world.getBlockState(pos).isAir();
        } else {
            return mc.world.getBlockState(pos).isReplaceable();
        }
    }

    /**
     * Places a block at the specified position using normal interaction.
     * @param pos The position to place at
     */
    private void placeBlock(BlockPos pos) {
        Vec3d hitPos = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        BlockHitResult hit = new BlockHitResult(hitPos, Direction.UP, pos, false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    /**
     * Uses flint and steel at the specified position.
     * @param pos The position to use flint and steel
     */
    private void useFlintAndSteel(BlockPos pos) {
        Vec3d hitPos = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        BlockHitResult hit = new BlockHitResult(hitPos, Direction.UP, pos, false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
    }

    /**
     * Finds TNT in the player's hotbar.
     * @return The slot index (0-8) or -1 if not found
     */
    private int findTntInHotbar() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.TNT) {
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
            if (mc.player.getInventory().getStack(i).getItem() == Items.FLINT_AND_STEEL) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Moves TNT from the main inventory to the hotbar if possible.
     * @return true if TNT was moved successfully, false otherwise
     */
    private boolean moveTntToHotbar() {
        // First, find TNT in the main inventory (slots 9-35)
        int tntInvSlot = -1;
        for (int i = 9; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.TNT) {
                tntInvSlot = i;
                break;
            }
        }

        if (tntInvSlot == -1) return false; // No TNT found in inventory

        // Find an empty or replaceable slot in hotbar
        int targetHotbarSlot = -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) {
                targetHotbarSlot = i;
                break;
            }
        }

        // If no empty slot, use slot 0 as a fallback
        if (targetHotbarSlot == -1) targetHotbarSlot = 0;

        // Swap the items - this is a direct inventory operation
        mc.interactionManager.clickSlot(
            mc.player.currentScreenHandler.syncId,
            tntInvSlot,          // Source slot
            targetHotbarSlot,    // Target slot
            SlotActionType.SWAP, // Action type - swap the items
            mc.player            // Player
        );

        // Return true to indicate success
        return true;
    }
}