/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.events.ContainerContentEvent;
import com.AutoBookshelf.addon.events.ContainerSlotEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MinecartPlacer extends Module {
    /**
     * Ticks to wait for the server to consume the item before a placement counts as rejected.
     * The count is the only confirmation, so this window also covers a late inventory update:
     * a server that took the minecart but answered slowly must not retire the rail.
     */
    private static final int VERIFY_TIMEOUT_TICKS = 40;

    /**
     * The hotbar swap confirmation window. The server does not answer a click that landed the way
     * the client predicted: it only sends a correction when its own result differed, so silence
     * means the swap was applied. The window is therefore only there to give a real correction time
     * to arrive, and it is sized from the connection latency: the click has to reach the server and
     * the correction has to come back.
     */
    private static final int SWAP_WINDOW_SLACK_TICKS = 4;
    private static final int SWAP_WINDOW_MIN_TICKS = 2;
    private static final int SWAP_WINDOW_MAX_TICKS = 40;
    /**
     * How often a refused swap is retried before the rail is skipped.
     */
    private static final int SWAP_MAX_ATTEMPTS = 3;

    private boolean placing = false;
    private List<BlockPos> targetRails = new ArrayList<>();
    private int currentIndex = 0;
    private int delayLeft = 0;
    private int placedCount = 0;
    private boolean waitingForMinecarts = false;
    private int waitCounter = 0;
    /**
     * Ticks to wait before the rails are scanned again when none of them was placeable.
     */
    private int railWaitCounter = 0;
    /**
     * The rail wait message already shown for the current situation, null when there is none.
     * The wait expires and the scan runs again, so without this the message would repeat
     * every check-delay.
     */
    private String railWaitMessage = null;
    private int cachedMinecartSlot = -1;
    private int cachedEmptyHotbarSlot = -1;

    /**
     * A rail the server refused: was it occupied when it was tried, and when may it be tried
     * again. An occupied rail frees up again, any other failure is retried after a delay.
     */
    private record FailedRail(boolean occupied, long retryTick) {
    }

    /**
     * Ticks before a refused rail that was not occupied is tried again.
     */
    private static final long FAILED_RAIL_RETRY_TICKS = 200;

    /**
     * Rails the server refused a placement on this run. A refused rail is skipped until the
     * condition that made it fail is gone: an occupied rail once the minecart has left, any
     * other failure once the retry delay is over.
     */
    private final Map<BlockPos, FailedRail> failedRails = new HashMap<>();
    /**
     * Out of reach of every collected rail, waiting for the player to come back.
     */
    private boolean paused = false;
    /**
     * Placement sent but not yet confirmed by the server consuming the item.
     */
    private BlockPos pendingRail = null;
    /**
     * The hotbar slot the minecart was placed from (and its stack count at click time), the evidence used to confirm a placement.
     */
    private int pendingPlacementSlot = -1;
    private int pendingPlacementCount = 0;
    private int pendingBaseline = 0;
    private int pendingTicks = 0;
    /**
     * Whether the rail we are placing on already held a minecart before our click. It is the
     * reason a refused placement is recorded as: an occupied rail frees up again, any other
     * failure does not.
     */
    private boolean pendingRailOccupied = false;
    /**
     * Hotbar swap sent, waiting to settle: the minecart belongs in this hotbar slot.
     */
    private int pendingSwapSlot = -1;
    /**
     * The inventory index the minecart was taken from, so a refused swap can be retried.
     */
    private int pendingSwapSourceSlot = -1;
    /**
     * The menu slot ids of the two swapped slots. The server's corrections use these, and they are
     * not the inventory indices, so they have to be converted.
     */
    private int pendingSwapContainerSlot = -1;
    private int pendingSwapSourceContainerSlot = -1;
    private BlockPos pendingSwapRail = null;
    private int pendingSwapTicks = 0;
    /**
     * Swap attempts for the rail that is being swapped for, so a refusal cannot loop.
     */
    private int pendingSwapAttempts = 0;
    /**
     * Set when the server sends something about either of the swapped slots. The server only sends
     * these when its own result differed from the client's prediction, so this is a correction and
     * not an acknowledgement: the hotbar slot still has to be read, and a correction for a slot
     * that is not one of ours says nothing at all.
     */
    private boolean swapAnswered = false;
    /**
     * Bumped for every run, so a rotation callback from an older run cannot write into a newer one.
     */
    private int runId = 0;
    /**
     * Earliest game time a repeated "hotbar swap failed" message may be printed.
     */
    private long nextSwapFailMsgAt = 0;

    private enum MinecartType {
        MINECART(Items.MINECART, "Minecart", "Minecarts"),
        CHEST_MINECART(Items.CHEST_MINECART, "Chest Minecart", "Chest Minecarts"),
        FURNACE_MINECART(Items.FURNACE_MINECART, "Furnace Minecart", "Furnace Minecarts"),
        TNT_MINECART(Items.TNT_MINECART, "TNT Minecart", "TNT Minecarts"),
        HOPPER_MINECART(Items.HOPPER_MINECART, "Hopper Minecart", "Hopper Minecarts"),
        // Appended last so no existing entry shifts in the settings dropdown.
        ANY(null, "Any Minecart", "Minecarts");

        final Item item;
        final String name;
        final String plural;

        MinecartType(Item item, String name, String plural) {
            this.item = item;
            this.name = name;
            this.plural = plural;
        }

        /**
         * ANY is a null item, so every lookup has to go through this rather than
         * comparing against the entry's own item.
         */
        boolean matches(ItemStack stack) {
            if (stack.isEmpty()) return false;
            Item held = stack.getItem();
            if (item != null) return held == item;
            return held == Items.MINECART || held == Items.CHEST_MINECART
                || held == Items.FURNACE_MINECART || held == Items.TNT_MINECART
                || held == Items.HOPPER_MINECART || held == Items.COMMAND_BLOCK_MINECART;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private enum RailType {
        DETECTOR_RAIL(Blocks.DETECTOR_RAIL, "Detector Rail", "Detector Rails"),
        POWERED_RAIL(Blocks.POWERED_RAIL, "Powered Rail", "Powered Rails"),
        ACTIVATOR_RAIL(Blocks.ACTIVATOR_RAIL, "Activator Rail", "Activator Rails"),
        RAIL(Blocks.RAIL, "Rail", "Rails"),
        // Appended last so no existing entry shifts in the settings dropdown.
        ANY(null, "Any Rail", "Rails");

        final Block block;
        final String name;
        final String plural;

        RailType(Block block, String name, String plural) {
            this.block = block;
            this.name = name;
            this.plural = plural;
        }

        /**
         * ANY is a null block, so use vanilla's own rail predicate rather than a
         * comparison against the entry's own block. BaseRailBlock.isRail covers all
         * four rail blocks (activator rail is an anonymous PoweredRailBlock).
         */
        boolean matches(BlockState state) {
            if (block != null) return state.getBlock() == block;
            return BaseRailBlock.isRail(state);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Print debug messages in chat.")
        .defaultValue(false)
        .build()
    );

    private final Setting<MinecartType> minecartType = sgGeneral.add(new EnumSetting.Builder<MinecartType>()
        .name("minecart-type")
        .description("Type of minecart to place.")
        .defaultValue(MinecartType.MINECART)
        .build()
    );

    private final Setting<RailType> railType = sgGeneral.add(new EnumSetting.Builder<RailType>()
        .name("rail-type")
        .description("Type of rail to place minecarts on.")
        .defaultValue(RailType.DETECTOR_RAIL)
        .build()
    );

    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Delay between placing minecarts in ticks.")
        .defaultValue(10)
        .min(1)
        .max(40)
        .sliderMax(40)
        .build()
    );

    private final Setting<Integer> radius = sgGeneral.add(new IntSetting.Builder()
        .name("radius")
        .description("Radius to search for rails around the player.")
        .defaultValue(5)
        .min(1)
        .max(5)
        .sliderMax(5)
        .build()
    );

    private final Setting<Integer> maxMinecarts = sgGeneral.add(new IntSetting.Builder()
        .name("max-minecarts")
        .description("Maximum number of minecarts to place (0 = Arm the ICBM Silo).")
        .defaultValue(0)
        .min(0)
        .max(100)
        .sliderMax(200)
        .build()
    );

    private final Setting<Boolean> skipOccupied = sgGeneral.add(new BoolSetting.Builder()
        .name("skip-occupied")
        .description("Skip rails that already have a minecart.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> checkDelay = sgGeneral.add(new IntSetting.Builder()
        .name("check-delay")
        .description("Delay between checking for new minecarts when out of stock (ticks).")
        .defaultValue(20)
        .min(5)
        .max(100)
        .sliderMax(100)
        .build()
    );

    // Render settings
    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders the next rail to place.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> highlightColor = sgRender.add(new ColorSetting.Builder()
        .name("highlight-color")
        .description("Color of the highlighted rail.")
        .defaultValue(new SettingColor(255, 255, 0, 100))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Line color of the highlighted rail.")
        .defaultValue(new SettingColor(255, 255, 0, 255))
        .build()
    );


    public MinecartPlacer() {
        super(Addon.CATEGORY2, "Minecart-Placer", "Places any minecarts on any rails in range.");
    }

    @Override
    public void onActivate() {
        startPlacing();
    }

    private void startPlacing() {
        if (mc.player == null || mc.level == null) return;
        runId++;

        // Find all rails in radius
        targetRails.clear();
        BlockPos center = mc.player.blockPosition();
        int rad = radius.get();
        RailType type = railType.get();

        for (int x = -rad; x <= rad; x++) {
            for (int y = -rad; y <= rad; y++) {
                for (int z = -rad; z <= rad; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    if (type.matches(mc.level.getBlockState(pos))) {
                        targetRails.add(pos);
                    }
                }
            }
        }

        if (targetRails.isEmpty()) {
            if (debug.get()) info("§cNo " + type.name + " found within " + rad + " blocks!");
            toggle();
            return;
        }

        // Sort by distance from player
        targetRails.sort((a, b) -> {
            double distA = a.distSqr(center);
            double distB = b.distSqr(center);
            return Double.compare(distA, distB);
        });

        currentIndex = 0;
        placedCount = 0;
        placing = true;
        waitingForMinecarts = false;
        delayLeft = 0;
        waitCounter = 0;
        railWaitCounter = 0;
        railWaitMessage = null;
        cachedMinecartSlot = -1;
        cachedEmptyHotbarSlot = -1;
        paused = false;
        pendingRail = null;
        pendingTicks = 0;
        pendingRailOccupied = false;
        pendingPlacementSlot = -1;
        pendingSwapAttempts = 0;
        clearPendingSwap();
        failedRails.clear();

        if (debug.get()) info("§aFound §f" + targetRails.size() + " §a" + railType.get().plural + ". Placing " + minecartType.get().plural + "...");
    }

    /**
     * The server's answer about a container. Once it arrives the client's inventory is the server's,
     * so a hotbar swap can be checked instead of assumed. The server sends this only when its own
     * result differed from the client's prediction, so it is a correction, not an acknowledgement.
     */
    @EventHandler
    private void onContainerContent(ContainerContentEvent event) {
        if (mc.player == null || pendingSwapSlot < 0) return;
        if (event.containerId == mc.player.inventoryMenu.containerId) swapAnswered = true;
    }

    /**
     * The same, for the single-slot update the server sends when that is all that changed - which
     * is the usual answer, since the full content above only goes out when the client's container
     * state was already out of sync. Only a correction for one of the swapped slots means anything:
     * an update for any other slot says nothing about the swap.
     */
    @EventHandler
    private void onContainerSlot(ContainerSlotEvent event) {
        if (mc.player == null || pendingSwapSlot < 0) return;
        if (event.containerId != mc.player.inventoryMenu.containerId) return;
        if (event.slot == pendingSwapContainerSlot || event.slot == pendingSwapSourceContainerSlot) {
            swapAnswered = true;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null) return;
        if (!placing) return;

        // A placement only counts once the server has consumed the item. The client never
        // predicts a minecart use, so the inventory count is the only trustworthy confirmation;
        // the click itself can be rejected (out of reach, occupied rail, dropped swap).
        if (pendingRail != null && !resolvePending()) return;

        // A hotbar swap is in flight: wait for the server to answer it before anything else.
        // The swap is only settled while the player's own inventory menu is active: another
        // container open means the module is idle anyway, and the placement waits with it.
        if (pendingSwapSlot >= 0) {
            if (mc.player.containerMenu == mc.player.inventoryMenu) settlePendingSwap();
            return;
        }

        // Check if we've reached the limit. This sits in front of the range, pause and container
        // guards on purpose: reaching the limit has to switch the module off wherever the player
        // is standing, not only when the next rail happens to be in reach.
        int max = maxMinecarts.get();
        if (max > 0 && placedCount >= max) {
            if (debug.get()) info("§aPlaced §f" + max + " §aminecarts! Turning off.");
            toggle();
            return;
        }

        // The hotbar swap is a click on the player's own inventory menu, and vanilla drops that
        // outright while any other container screen is open ("Ignoring click in mismatching
        // container"). Wait for it to close instead of clicking with whatever sits in the hotbar.
        if (mc.player.containerMenu != mc.player.inventoryMenu) return;

        // The rails were collected around wherever the player stood at activation, and the
        // fabricated hit result is sent no matter how far away the player is - the server just
        // rejects it, so the module would keep firing placements that never happen. Pause
        // instead of stopping, so walking back into range resumes the same run.
        BlockPos playerPos = mc.player.blockPosition();
        int rad = radius.get();
        boolean anyInRange = false;
        for (BlockPos pos : targetRails) {
            if (inRange(pos, playerPos, rad) && withinReach(pos)) {
                anyInRange = true;
                break;
            }
        }
        if (!anyInRange) {
            if (!paused) {
                paused = true;
                if (debug.get()) info("§eMoved out of range of the " + railType.get().plural + ". Paused until you return.");
            }
            return;
        }
        if (paused) {
            paused = false;
            if (debug.get()) info("§aBack in range. Resuming placement...");
        }

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
                if (debug.get()) info("§aMinecarts found! Resuming placement...");
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

        // The rails are not placeable right now: they are all occupied, or none of them is a
        // rail we can place on any more. This is a wait for the world to change, NOT the
        // minecart wait - the player may well still have minecarts, and the minecart wait
        // resolves by looking for one. Sharing the flag made the two messages alternate
        // forever: "Minecarts found! Resuming placement..." next to "No placeable rail
        // left...", every check-delay, for as long as the rails stayed unusable.
        if (railWaitCounter > 0) {
            railWaitCounter--;
            return;
        }

        // Loop through all rails to find one that needs a minecart
        boolean foundRail = false;
        boolean skippedOccupied = false;
        RailType targetRail = railType.get();
        boolean shouldSkipOccupied = skipOccupied.get();

        for (int i = 0; i < targetRails.size(); i++) {
            int index = (currentIndex + i) % targetRails.size();
            BlockPos railPos = targetRails.get(index);

            // Check if block is still the correct rail type
            if (!targetRail.matches(mc.level.getBlockState(railPos))) {
                continue;
            }

            // Do not place at a rail the player has since moved away from, and never send a
            // click the server will reject for being out of reach.
            if (!inRange(railPos, playerPos, rad) || !withinReach(railPos)) {
                continue;
            }

            // A rail the server already refused a placement on this run, unless the condition
            // that made it fail is gone.
            FailedRail failed = failedRails.get(railPos);
            if (failed != null && !railReady(failed, railPos)) {
                continue;
            }

            // Check if rail already has a minecart
            if (shouldSkipOccupied && hasMinecartOnRail(railPos)) {
                skippedOccupied = true;
                continue;
            }

            // Found a rail that needs a minecart
            foundRail = true;
            currentIndex = index;
            // The rails are placeable again, so a later failure is news again.
            railWaitMessage = null;

            // Find minecart in inventory
            int minecartSlot = findMinecartInInventory();
            if (minecartSlot == -1) {
                if (!waitingForMinecarts) {
                    waitingForMinecarts = true;
                    waitCounter = checkDelay.get();
                    if (debug.get()) info("§eOut of " + minecartType.get().plural + "! Waiting for more...");
                }
                return;
            }

            // Place the minecart
            placeMinecart(railPos, minecartSlot);
            delayLeft = placeDelay.get();
            currentIndex = (currentIndex + 1) % targetRails.size();
            return;
        }

        // Nothing was placeable this scan. Either every reachable rail is occupied, or none of
        // them is a rail we can place on any more (the server refused every one of them, or
        // they are no longer rails of this type). Both are a wait for the world to change, so
        // both use the rail wait and not the minecart wait. The message is shown once per
        // situation: the wait expires, the scan runs again, and a scan that still finds
        // nothing has nothing new to say.
        if (!foundRail) {
            String reason = skippedOccupied
                ? "§eAll rails processed! Waiting for rails to become available..."
                : "§eNo placeable rail left. Waiting for the rails to change...";
            if (!reason.equals(railWaitMessage)) {
                if (debug.get()) info(reason);
                railWaitMessage = reason;
            }
            railWaitCounter = checkDelay.get();
        }
    }

    /**
     * Whether a refused rail may be tried again: an occupied one once the minecart has left it,
     * any other one once the retry delay is over. A refusal that is neither of those is not
     * retried at all, or it would just fail again and hold the loop.
     */
    private boolean railReady(FailedRail failed, BlockPos railPos) {
        if (failed.occupied() && skipOccupied.get()) return !hasMinecartOnRail(railPos);
        return gameTime() >= failed.retryTick();
    }

    /** The world's game time, the clock the retry delay runs on. */
    private long gameTime() {
        return mc.level == null ? 0L : mc.level.getGameTime();
    }

    private boolean hasMinecartOnRail(BlockPos railPos) {
        AABB box = new AABB(railPos.getX(), railPos.getY(), railPos.getZ(),
                          railPos.getX() + 1, railPos.getY() + 1, railPos.getZ() + 1);
        return !mc.level.getEntitiesOfClass(AbstractMinecart.class, box, e -> true).isEmpty();
    }

    /**
     * Rails are collected in the Chebyshev box {@code ±rad} around the activation point, and the
     * same box anchored to the player's current position is what keeps a run from continuing after
     * they walk away. Takes the player position in rather than reading it per rail so the tick loop
     * and the render path each allocate it once.
     */
    private boolean inRange(BlockPos pos, BlockPos playerPos, int rad) {
        return Math.abs(pos.getX() - playerPos.getX()) <= rad
            && Math.abs(pos.getY() - playerPos.getY()) <= rad
            && Math.abs(pos.getZ() - playerPos.getZ()) <= rad;
    }

    /**
     * The server's own reach rule for a use packet
     * ({@code ServerPlayer#isWithinBlockInteractionRange(pos, 1.0)}): eye to block box, with the
     * one-block buffer the packet handler adds. The search box alone allows corner rails ~6.5
     * blocks away, which the server always rejects.
     */
    private boolean withinReach(BlockPos pos) {
        Vec3 eye = mc.player.getEyePosition();
        double dx = Math.max(0.0, Math.max(pos.getX() - eye.x, eye.x - (pos.getX() + 1)));
        double dy = Math.max(0.0, Math.max(pos.getY() - eye.y, eye.y - (pos.getY() + 1)));
        double dz = Math.max(0.0, Math.max(pos.getZ() - eye.z, eye.z - (pos.getZ() + 1)));
        double range = mc.player.blockInteractionRange() + 1.0;
        return dx * dx + dy * dy + dz * dz < range * range;
    }

    private int findMinecartInInventory() {
        MinecartType type = minecartType.get();

        if (cachedMinecartSlot != -1) {
            if (type.matches(mc.player.getInventory().getItem(cachedMinecartSlot))) {
                return cachedMinecartSlot;
            }
            cachedMinecartSlot = -1;
        }

        for (int i = 0; i < 36; i++) {
            if (type.matches(mc.player.getInventory().getItem(i))) {
                cachedMinecartSlot = i;
                return i;
            }
        }
        cachedMinecartSlot = -1;
        return -1;
    }

    /**
     * Minecarts the module could use right now; the server consumes one per accepted placement.
     */
    private int countMinecarts() {
        MinecartType type = minecartType.get();
        int total = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (type.matches(stack)) total += stack.getCount();
        }
        return total;
    }

    /**
     * Settles the placement sent last. A lower minecart count means the server consumed one, so it
     * counts; anything else was a rejected click (out of reach, occupied rail, dropped swap). A
     * rejected rail is retired for the run so one bad rail cannot hold the loop forever.
     *
     * @return true once the pending placement is settled and another may be sent
     */
    private boolean resolvePending() {
        pendingTicks++;

        // The confirmation is tied to the placement slot's stack: a decrease of that stack below
        // its baseline count means the server consumed it (useOn fails without consuming). A
        // global drop (the stack was moved to offhand, into a chest or dropped) is not evidence.
        // The entity on the rail is NOT a confirmation: a minecart can arrive from an adjacent rail.
        boolean consumed = pendingPlacementSlot == -1
            ? countMinecarts() < pendingBaseline
            : mc.player.getInventory().getItem(pendingPlacementSlot).getCount() < pendingPlacementCount;
        if (consumed) {
            placedCount++;
            if (debug.get()) info("§aPlaced " + minecartType.get().name + " on " + railType.get().name + " at §f"
                + pendingRail.getX() + ", " + pendingRail.getY() + ", " + pendingRail.getZ());
            pendingRail = null;
            pendingPlacementSlot = -1;
            return true;
        }

        if (pendingTicks < VERIFY_TIMEOUT_TICKS) return false;

        // The rail is skipped until the condition that made it fail is gone. The message is
        // printed once per rail: a retry that fails again says nothing new.
        FailedRail previous = failedRails.put(pendingRail,
            new FailedRail(pendingRailOccupied, gameTime() + FAILED_RAIL_RETRY_TICKS));
        if (previous == null) {
            if (debug.get()) info("§ePlacement at §f" + pendingRail.getX() + ", " + pendingRail.getY() + ", " + pendingRail.getZ()
                + " §edid not register. Skipping that rail.");
        }
        pendingRail = null;
        pendingPlacementSlot = -1;
        resyncInventory();
        return true;
    }

    /**
     * Drops the cached slots and re-asserts the server's carried slot. A stale cache is what turns
     * a client/server inventory desync into a "ghost" minecart the module keeps trying to place;
     * re-reading live state lets it fall back to waiting for a restock instead.
     */
    private void resyncInventory() {
        cachedMinecartSlot = -1;
        cachedEmptyHotbarSlot = -1;
        if (mc.player == null) return;
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(mc.player.getInventory().getSelectedSlot()));
    }

    /**
     * Places a minecart on a rail. A minecart that is not in the hotbar has to be swapped there
     * first, and that swap is a container click: the server drops it while any other screen is
     * open, and it is client predicted, so the local inventory cannot prove that it landed. The
     * swap therefore goes out from the tick (where the container menu guard still holds), and the
     * placement waits out the swap confirmation window before using the hotbar slot.
     */
    private void placeMinecart(BlockPos railPos, int slot) {
        if (slot >= 9) {
            startHotbarSwap(railPos, slot);
            return;
        }
        placeFromSlot(railPos, slot);
    }

    /**
     * Swaps the minecart from the inventory into an empty hotbar slot. The click is sent against
     * the player's own inventory menu, never a screen that might be open, and the inventory index
     * is converted to a container slot id (raw indexes only coincide with slot ids for the main
     * inventory section).
     */
    private void startHotbarSwap(BlockPos railPos, int slot) {
        if (mc.player == null || mc.gameMode == null) return;
        if (mc.player.containerMenu != mc.player.inventoryMenu) return;
        // The minecart has to still be in the slot the swap takes it from. A retry after a
        // correction can land on a slot that has since changed, and swapping whatever now sits
        // there into the hotbar is how the wrong item gets placed.
        if (!minecartType.get().matches(mc.player.getInventory().getItem(slot))) {
            clearPendingSwap();
            return;
        }

        int tempSlot = cachedEmptyHotbarSlot;
        if (tempSlot == -1 || !mc.player.getInventory().getItem(tempSlot).isEmpty()) {
            tempSlot = -1;
            for (int i = 0; i < 9; i++) {
                if (mc.player.getInventory().getItem(i).isEmpty()) {
                    tempSlot = i;
                    break;
                }
            }
            cachedEmptyHotbarSlot = tempSlot;
        }
        if (tempSlot == -1) tempSlot = 0;

        mc.gameMode.handleContainerInput(
            mc.player.inventoryMenu.containerId,
            SlotUtils.indexToId(slot),
            tempSlot,
            ContainerInput.SWAP,
            mc.player
        );

        // The server only sends a packet when its result differed from the prediction, so the
        // absence of one is the confirmation: the client's hotbar slot is the server's.
        swapAnswered = false;
        pendingSwapSlot = tempSlot;
        pendingSwapSourceSlot = slot;
        pendingSwapContainerSlot = SlotUtils.indexToId(tempSlot);
        pendingSwapSourceContainerSlot = SlotUtils.indexToId(slot);
        pendingSwapRail = railPos;
        pendingSwapTicks = 0;
        pendingSwapAttempts++;
    }

    /**
     * Drops the in-flight swap. The attempt counter is kept: it belongs to the rail, not the swap.
     */
    private void clearPendingSwap() {
        pendingSwapSlot = -1;
        pendingSwapSourceSlot = -1;
        pendingSwapContainerSlot = -1;
        pendingSwapSourceContainerSlot = -1;
        pendingSwapRail = null;
        pendingSwapTicks = 0;
        swapAnswered = false;
    }

    /**
     * Waits out the hotbar swap and only then places. The minecart has to be in that hotbar slot on
     * the server, not just in the client's prediction: otherwise the placement would use whatever
     * the server has in that slot.
     * <p>
     * The server does not acknowledge a click that landed the way the client predicted - it sends a
     * correction only when its own result differed, and nothing at all otherwise. So a packet means
     * "the prediction was wrong", and silence means the swap was applied. The slot is read either
     * way, because once the packet has been applied the client's inventory is the server's.
     */
    private void settlePendingSwap() {
        pendingSwapTicks++;

        int slot = pendingSwapSlot;
        int sourceSlot = pendingSwapSourceSlot;
        BlockPos rail = pendingSwapRail;

        if (swapAnswered) {
            // The server has spoken, so its view of the hotbar is now the client's.
            swapAnswered = false;
            clearPendingSwap();

            if (mc.player == null) return;
            if (!minecartType.get().matches(mc.player.getInventory().getItem(slot))) {
                // The swap was refused. Nothing was placed, so the rail is still usable, and the
                // correction has already put the client's inventory back to the server's state -
                // so a retry starts from there instead of from the prediction. A swap that keeps
                // being refused skips the rail for the retry delay.
                if (pendingSwapAttempts < SWAP_MAX_ATTEMPTS) {
                    resyncInventory();
                    if (mc.level.getGameTime() >= nextSwapFailMsgAt) {
                        nextSwapFailMsgAt = mc.level.getGameTime() + 100;
                        if (debug.get()) info("§eHotbar swap failed. Retrying...");
                    }
                    startHotbarSwap(rail, sourceSlot);
                    return;
                }
                pendingSwapAttempts = 0;
                failedRails.put(rail, new FailedRail(false, gameTime() + FAILED_RAIL_RETRY_TICKS));
                clearPendingSwap();
                if (mc.level.getGameTime() >= nextSwapFailMsgAt) {
                    nextSwapFailMsgAt = mc.level.getGameTime() + 100;
                    if (debug.get()) info("§eHotbar swap failed. Skipping that rail.");
                }
                return;
            }
            pendingSwapAttempts = 0;
            placeAfterSwap(rail, slot);
            return;
        }

        // Silence: the server's result matched the prediction, so the swap landed. The window
        // exists only to give a real correction time to arrive.
        if (pendingSwapTicks < swapWindowTicks()) return;

        clearPendingSwap();
        pendingSwapAttempts = 0;
        placeAfterSwap(rail, slot);
    }

    /**
     * The swap is settled, so the minecart is in the hotbar slot the placement will use. The scan
     * checked the rail before the swap and the swap takes time: the rail can have been replaced and
     * a minecart can have arrived on it, and the rotation callback waits a further tick, so the
     * range and reach are checked again here.
     */
    private void placeAfterSwap(BlockPos rail, int slot) {
        if (mc.player == null) return;
        BlockPos playerPos = mc.player.blockPosition();
        if (!inRange(rail, playerPos, radius.get()) || !withinReach(rail)) {
            if (!paused) {
                paused = true;
                if (debug.get()) info("§eMoved out of range of the " + railType.get().plural + ". Paused until you return.");
            }
            int index = targetRails.indexOf(rail);
            if (index >= 0) currentIndex = index;
            return;
        }
        placeFromSlot(rail, slot);
    }

    /**
     * The confirmation window for a hotbar swap, in ticks: one way to the server and back, plus a
     * server tick of slack, clamped so a missing or broken latency reading cannot stall the module.
     */
    private int swapWindowTicks() {
        int latency = 0;
        if (mc.player != null && mc.getConnection() != null) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
            if (info != null) latency = info.getLatency();
        }
        int ticks = latency / 50 + SWAP_WINDOW_SLACK_TICKS;
        return Math.max(SWAP_WINDOW_MIN_TICKS, Math.min(ticks, SWAP_WINDOW_MAX_TICKS));
    }

    /**
     * Places the minecart that is already in the given hotbar slot. The placement rides the
     * rotation, so the server sees the same look the client aimed with.
     */
    private void placeFromSlot(BlockPos railPos, int slot) {
        // Target the center of the rail block directly
        // This allows placing through walls
        Vec3 targetPos = Vec3.atCenterOf(railPos);

        // Create a hit result pointing directly at the rail
        BlockHitResult hitResult = new BlockHitResult(
            targetPos,
            Direction.UP,
            railPos,
            false
        );

        MinecartType targetType = minecartType.get();
        // The rotation callback runs a tick or more later. If the run ended and a new one
        // started in between, the callback belongs to the old run and must not write its
        // state into the new one.
        final int scheduledRun = runId;

        Rotations.rotate(Rotations.getYaw(targetPos), Rotations.getPitch(targetPos), () -> {
            if (scheduledRun != runId) return;
            // The slot content may have changed while the rotation was pending
            // (packet lag, inventory fiddling). A minecart that is no longer
            // there means nothing to place; leave the rail untouched instead of
            // swapping in whatever now sits in that slot.
            if (!targetType.matches(mc.player.getInventory().getItem(slot))) return;
            // The scan checked the rail before the hotbar swap, and the swap takes time: the
            // rail can have been replaced, and a minecart can have arrived on it. The rotation
            // callback waits a further tick, so check again right before the click.
            if (!railType.get().matches(mc.level.getBlockState(railPos))) return;
            if (skipOccupied.get() && hasMinecartOnRail(railPos)) return;

            // The rotation callback runs a tick or more later, so restore whatever is selected when
            // the click actually goes out, not what was selected when the click was planned.
            int previousSlot = mc.player.getInventory().getSelectedSlot();

            mc.player.getInventory().setSelectedSlot(slot);

            // Place the minecart. The confirmation is tied to the stack this click used: only a
            // decrease of THIS slot below its baseline count means the server consumed it. A global
            // count drop from moving minecarts into the offhand, into a chest or dropping them is
            // not placement evidence.
            pendingBaseline = countMinecarts();
            pendingPlacementSlot = slot;
            pendingPlacementCount = mc.player.getInventory().getItem(slot).getCount();
            // A minecart that was already on the rail cannot confirm our placement: the entity
            // would be there whether or not the click landed.
            pendingRailOccupied = hasMinecartOnRail(railPos);
            mc.gameMode.useItemOn(
                mc.player,
                InteractionHand.MAIN_HAND,
                hitResult
            );
            pendingRail = railPos;
            pendingTicks = 0;

            mc.player.swing(InteractionHand.MAIN_HAND);

            // Restore previous slot
            if (previousSlot != mc.player.getInventory().getSelectedSlot()) {
                mc.player.getInventory().setSelectedSlot(previousSlot);
            }
        });
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get()) return;
        if (mc.player == null || mc.level == null) return;
        if (!placing || paused) return;
        if (targetRails.isEmpty()) return;
        if (waitingForMinecarts) return;

        // Find the next rail to place
        BlockPos playerPos = mc.player.blockPosition();
        int rad = radius.get();
        BlockPos nextRail = null;
        for (int i = 0; i < targetRails.size(); i++) {
            int index = (currentIndex + i) % targetRails.size();
            BlockPos railPos = targetRails.get(index);

            // Check if block is still the correct rail type
            if (!railType.get().matches(mc.level.getBlockState(railPos))) {
                continue;
            }

            // Out-of-range rails are skipped by the placement loop too, so do not highlight one.
            if (!inRange(railPos, playerPos, rad) || !withinReach(railPos)) {
                continue;
            }

            // A rail the server already refused a placement on this run, unless the condition
            // that made it fail is gone.
            FailedRail failed = failedRails.get(railPos);
            if (failed != null && !railReady(failed, railPos)) {
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
        // A rotation callback from this run may still be queued. Bumping the run id makes it
        // stale, so it cannot write its state into a later run.
        runId++;
        placing = false;
        waitingForMinecarts = false;
        railWaitCounter = 0;
        railWaitMessage = null;
        targetRails.clear();
        currentIndex = 0;
        placedCount = 0;
        delayLeft = 0;
        waitCounter = 0;
        cachedMinecartSlot = -1;
        cachedEmptyHotbarSlot = -1;
        paused = false;
        pendingRail = null;
        pendingTicks = 0;
        pendingRailOccupied = false;
        pendingPlacementSlot = -1;
        pendingSwapAttempts = 0;
        clearPendingSwap();
        nextSwapFailMsgAt = 0;
        failedRails.clear();
    }
}
