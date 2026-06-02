package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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
        super(Addon.CATEGORY, "Cart-Placer", "Places any minecarts on any rails in range.");
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
        Block targetRail = railType.get().block;

        for (int x = -rad; x <= rad; x++) {
            for (int y = -rad; y <= rad; y++) {
                for (int z = -rad; z <= rad; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    if (mc.level.getBlockState(pos).getBlock() == targetRail) {
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

        info("§aFound §f" + targetRails.size() + " §a" + railType.get().name + "s. Placing " + minecartType.get().name + "s...");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null) return;
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
            if (mc.level.getBlockState(railPos).getBlock() != railType.get().block) {
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
        AABB box = new AABB(railPos.getX(), railPos.getY(), railPos.getZ(),
                          railPos.getX() + 1, railPos.getY() + 1, railPos.getZ() + 1);
        return !mc.level.getEntitiesOfClass(AbstractMinecart.class, box, e -> true).isEmpty();
    }

    private int findMinecartInInventory() {
        Item targetItem = minecartType.get().item;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == targetItem) {
                return i;
            }
        }
        return -1;
    }

    private void placeMinecart(BlockPos railPos, int slot) {
        Vec3 targetPos = Vec3.atCenterOf(railPos);
        BlockHitResult hitResult = new BlockHitResult(targetPos, Direction.UP, railPos, false);
        int previousSlot = mc.player.getInventory().getSelectedSlot();

        // If the minecart is in the main inventory, swap it into a hotbar slot
        if (slot >= 9) {
            int hotbarSlot = -1;
            for (int i = 0; i < 9; i++) {
                if (mc.player.getInventory().getItem(i).isEmpty()) {
                    hotbarSlot = i;
                    break;
                }
            }
            if (hotbarSlot == -1) hotbarSlot = 0;

            int slotId = SlotUtils.indexToId(slot);
            mc.gameMode.handleContainerInput(
                mc.player.inventoryMenu.containerId,
                slotId,
                hotbarSlot,
                ContainerInput.SWAP,
                mc.player
            );
            slot = hotbarSlot;
        }

        mc.player.getInventory().setSelectedSlot(slot);

        Rotations.rotate(Rotations.getYaw(targetPos), Rotations.getPitch(targetPos), () -> {
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hitResult);
            mc.player.swing(InteractionHand.MAIN_HAND);
        });

        mc.player.getInventory().setSelectedSlot(previousSlot);

        info("§aPlaced " + minecartType.get().name + " on " + railType.get().name + " at §f"
            + railPos.getX() + ", " + railPos.getY() + ", " + railPos.getZ());
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
            if (mc.level.getBlockState(railPos).getBlock() != railType.get().block) {
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
