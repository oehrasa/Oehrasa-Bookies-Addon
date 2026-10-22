package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

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
    private int cachedMinecartSlot = -1;
    private int cachedEmptyHotbarSlot = -1;

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
            info("§cNo " + type.name + " found within " + rad + " blocks!");
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
        cachedMinecartSlot = -1;
        cachedEmptyHotbarSlot = -1;

        info("§aFound §f" + targetRails.size() + " §a" + railType.get().plural + ". Placing " + minecartType.get().plural + "...");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null) return;
        if (!placing) return;

        // The rails were collected around wherever the player stood at activation, and the
        // fabricated hit result is sent no matter how far away the player is - the server just
        // rejects it, so the module would keep firing (and counting) placements that never
        // happen. Stop once nothing collected is within the search radius of the player.
        BlockPos playerPos = mc.player.blockPosition();
        int rad = radius.get();
        boolean anyInRange = false;
        for (BlockPos pos : targetRails) {
            if (inRange(pos, playerPos, rad)) {
                anyInRange = true;
                break;
            }
        }
        if (!anyInRange) {
            info("§eMoved out of range of the " + railType.get().plural + ". Stopping.");
            placing = false;
            return;
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
        RailType targetRail = railType.get();
        boolean shouldSkipOccupied = skipOccupied.get();

        for (int i = 0; i < targetRails.size(); i++) {
            int index = (currentIndex + i) % targetRails.size();
            BlockPos railPos = targetRails.get(index);

            // Check if block is still the correct rail type
            if (!targetRail.matches(mc.level.getBlockState(railPos))) {
                continue;
            }

            // Do not place at a rail the player has since moved away from.
            if (!inRange(railPos, playerPos, rad)) {
                continue;
            }

            // Check if rail already has a minecart
            if (shouldSkipOccupied && hasMinecartOnRail(railPos)) {
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
                    info("§eOut of " + minecartType.get().plural + "! Waiting for more...");
                }
                return;
            }

            // Place the minecart
            placeMinecart(railPos, minecartSlot);
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

    private void placeMinecart(BlockPos railPos, int slot) {
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

        int previousSlot = mc.player.getInventory().getSelectedSlot();
        MinecartType targetType = minecartType.get();

        Rotations.rotate(Rotations.getYaw(targetPos), Rotations.getPitch(targetPos), () -> {
            // The slot content may have changed while the rotation was pending
            // (packet lag, inventory fiddling). A minecart that is no longer
            // there means nothing to place; leave the rail untouched instead of
            // swapping in whatever now sits in that slot.
            if (!targetType.matches(mc.player.getInventory().getItem(slot))) return;

            // Switch to minecart slot
            if (slot < 9) {
                mc.player.getInventory().setSelectedSlot(slot);
            } else {
                // Swap to hotbar if needed
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

                // Swap against the player's own inventory container, never a
                // screen that might be open, and convert the inventory index to
                // a container slot id (raw indexes only coincide with slot ids
                // for the main inventory section).
                mc.gameMode.handleContainerInput(
                    mc.player.inventoryMenu.containerId,
                    SlotUtils.indexToId(slot),
                    tempSlot,
                    ContainerInput.SWAP,
                    mc.player
                );
                mc.player.getInventory().setSelectedSlot(tempSlot);
            }

            // Place the minecart
            mc.gameMode.useItemOn(
                mc.player,
                InteractionHand.MAIN_HAND,
                hitResult
            );

            mc.player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);

            // Restore previous slot
            if (previousSlot != mc.player.getInventory().getSelectedSlot()) {
                mc.player.getInventory().setSelectedSlot(previousSlot);
            }

            // Only count and announce a placement that actually went out (the
            // swap/use happens deferred, inside this rotation callback).
            placedCount++;
            info("§aPlaced " + minecartType.get().name + " on " + railType.get().name + " at §f" + railPos.getX() + ", " + railPos.getY() + ", " + railPos.getZ());
        });
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get()) return;
        if (mc.player == null || mc.level == null) return;
        if (!placing) return;
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
            if (!inRange(railPos, playerPos, rad)) {
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
        cachedMinecartSlot = -1;
        cachedEmptyHotbarSlot = -1;
    }
}
