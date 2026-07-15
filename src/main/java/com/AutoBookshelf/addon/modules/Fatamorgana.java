package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import it.unimi.dsi.fastutil.ints.*;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.Target;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public class Fatamorgana extends Module {
    private enum Stage {
        SEARCHING,
        INTERACTING,
        DEPOSITING,
        CLOSING,
        WAITING
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgInteract = settings.createGroup("Interact");
    private final SettingGroup sgDeposit = settings.createGroup("Deposit");
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgDebug = settings.createGroup("Debug");

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Search radius used to find the chest minecart (and barrels, if enabled).")
        .defaultValue(6.0)
        .min(1.0)
        .sliderMax(16.0)
        .build()
    );

    private final Setting<Integer> cycleDelay = sgGeneral.add(new IntSetting.Builder()
        .name("cycle-delay")
        .description("Ticks to wait after closing the container before searching for the next one.")
        .defaultValue(60)
        .min(0)
        .sliderRange(0, 1200)
        .build()
    );

    private final Setting<Boolean> targetBarrels = sgGeneral.add(new BoolSetting.Builder()
        .name("target-barrels")
        .description("Also fill barrels.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> barrelRecheckTicks = sgGeneral.add(new IntSetting.Builder()
        .name("barrel-recheck-ticks")
        .description("How many ticks a barrel stays marked 'completed' before Fatamorgana re-checks it.")
        .defaultValue(10)
        .min(1)
        .sliderRange(1, 300)   // up to 15 seconds
        .visible(targetBarrels::get)
        .build()
    );

    private final Setting<Boolean> requireDepositItems = sgGeneral.add(new BoolSetting.Builder()
        .name("require-deposit-items")
        .description("Stay completely idle in the meantime.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rotate = sgInteract.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotate towards the chest minecart/barrel before interacting with it.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> interactDelay = sgInteract.add(new IntSetting.Builder()
        .name("interact-delay")
        .description("Ticks to wait between interact attempts.")
        .defaultValue(4)
        .min(1)
        .sliderMax(40)
        .build()
    );

    private final Setting<Integer> maxInteractAttempts = sgInteract.add(new IntSetting.Builder()
        .name("max-interact-attempts")
        .description("Give up on this target and search for a new one after this many failed attempts to open it.")
        .defaultValue(15)
        .min(1)
        .sliderMax(60)
        .build()
    );

    private final Setting<Integer> depositRate = sgDeposit.add(new IntSetting.Builder()
        .name("deposit-rate")
        .description("Items moved into the container per tick.")
        .defaultValue(6)
        .min(1)
        .sliderMax(36)
        .build()
    );

    private final Setting<Boolean> autoClose = sgDeposit.add(new BoolSetting.Builder()
        .name("auto-close")
        .description("Close the container automatically once nothing more can be deposited.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<Item>> extraItems = sgDeposit.add(new ItemListSetting.Builder()
        .name("extra-items")
        .description("Extra items (besides all shulker box) to deposit into the container.")
        .defaultValue(new ArrayList<>())
        .build()
    );

    private final Setting<Boolean> resyncAfterDeposit = sgDeposit.add(new BoolSetting.Builder()
        .name("resync-after-deposit")
        .description("Sends an inventory resync packet after each deposit action.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> depositSettleDelay = sgDeposit.add(new IntSetting.Builder()
        .name("deposit-settle-delay")
        .description("Ticks to wait after the container GUI opens before evaluating deposit/close logic, to let the initial inventory sync packet arrive. Prevents the container being closed immediately (before anything is deposited) because slots briefly read as empty.")
        .defaultValue(3)
        .min(0)
        .sliderMax(10)
        .build()
    );

    private final Setting<Boolean> renderTarget = sgRender.add(new BoolSetting.Builder()
        .name("render-target")
        .description("Renders a box around the current target (chest minecart or barrel).")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the target box is rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(renderTarget::get)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .defaultValue(new SettingColor(0, 225, 0, 75))
        .visible(renderTarget::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .defaultValue(new SettingColor(0, 225, 0, 255))
        .visible(renderTarget::get)
        .build()
    );

    private final Setting<Boolean> logContainerState = sgDebug.add(new BoolSetting.Builder()
        .name("log-container-state")
        .description("Logs isContainerMenuOpen() transitions and slot-emptiness info, to diagnose deposit/sync timing issues.")
        .defaultValue(false)
        .build()
    );

    // Mutable state.
    private Stage stage = Stage.SEARCHING;

    /**
     * Entity id of the chest minecart we're currently working with, or -1 if
     * the current target is a barrel (or there is no target). Entity ids are
     * unique per spawned instance.
     */
    private int targetEntityId = -1;

    /**
     * Position of the barrel we're currently working with, or null if the
     * current target is a chest minecart (or there is no target).
     */
    private BlockPos targetBlockPos = null;

    private int ticksInStage = 0;
    private int interactAttempts = 0;
    private int cycleTicks = 0;
    private int depositSettleTicks = 0;

    /**
     * Last value observed from {@link #isContainerMenuOpen()}, used purely to log transitions rather than spamming every tick.
     */
    private boolean lastLoggedContainerOpenState = false;

    private final IntSet completedEntityIds = new IntOpenHashSet();

    private final Object2LongOpenHashMap<BlockPos> completedBarrelPositions = new Object2LongOpenHashMap<>();

    public Fatamorgana() {
        super(Addon.CATEGORY, "Fatamorgana", "Maho x Miho, Miho x Yukari, Maho x Erika.");
    }

    @Override
    public void onActivate() {
        completedEntityIds.clear();
        completedBarrelPositions.clear();
        resetToSearching();
    }

    @Override
    public void onDeactivate() {
        if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
            mc.player.closeContainer();
        }
        resetToSearching();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        if (logContainerState.get()) logContainerStateIfChanged();

        switch (stage) {
            case SEARCHING -> doSearching();
            case INTERACTING -> doInteracting();
            case DEPOSITING -> doDepositing();
            case CLOSING -> doClosing();
            case WAITING -> doWaiting();
        }
    }

    private void logContainerStateIfChanged() {
        boolean open = isContainerMenuOpen();
        if (open == lastLoggedContainerOpenState) return;
        lastLoggedContainerOpenState = open;

        if (open) {
            AbstractContainerMenu handler = mc.player.containerMenu;
            int containerSlots = SlotUtils.indexToId(SlotUtils.MAIN_START);

            int containerFilled = 0;
            for (int i = 0; i < containerSlots; i++) {
                if (handler.getSlot(i).hasItem()) containerFilled++;
            }

            int playerDepositSlots = 0;
            for (int i = containerSlots; i < containerSlots + 4 * 9; i++) {
                Slot slot = handler.getSlot(i);
                if (slot.hasItem() && isDepositItem(slot.getItem())) playerDepositSlots++;
            }

            info("Container opened. container-filled-slots=%d/%d, player-deposit-slots-seen=%d, settle-delay=%d ticks",
                containerFilled, containerSlots, playerDepositSlots, depositSettleDelay.get());
        } else {
            info("Container closed. stage=%s", stage);
        }
    }

    private void doSearching() {
        pruneCompletedTargets();

        // Idle guard: don't bother searching/rotating/opening anything if we
        // couldn't deposit even if we found a target.
        if (requireDepositItems.get() && !hasAnyDepositItem()) return;

        Entity entityTarget = findNearestChestMinecart(range.get());
        if (entityTarget != null) {
            targetEntityId = entityTarget.getId();
            targetBlockPos = null;
            interactAttempts = 0;
            ticksInStage = 0;
            stage = Stage.INTERACTING;
            return;
        }

        if (targetBarrels.get()) {
            BlockPos barrelTarget = findNearestBarrel(range.get());
            if (barrelTarget != null) {
                targetEntityId = -1;
                targetBlockPos = barrelTarget;
                interactAttempts = 0;
                ticksInStage = 0;
                stage = Stage.INTERACTING;
            }
        }
    }

    private void doInteracting() {
        if (targetEntityId != -1) {
            doInteractingEntity();
        } else if (targetBlockPos != null) {
            doInteractingBarrel();
        } else {
            resetToSearching();
        }
    }

    private void doInteractingEntity() {
        Entity target = getTargetEntity();
        if (target == null) {
            // Destroyed before we managed to open it - jump straight to the next one.
            resetToSearching();
            return;
        }

        // Something already got the screen open
        if (isContainerMenuOpen()) {
            depositSettleTicks = 0;
            stage = Stage.DEPOSITING;
            return;
        }

        ticksInStage++;
        if (ticksInStage < interactDelay.get()) return;
        ticksInStage = 0;

        if (++interactAttempts > maxInteractAttempts.get()) {
            resetToSearching();
            return;
        }

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(target), Rotations.getPitch(target, Target.Body), 50, () -> interactEntityTarget(target));
        } else {
            interactEntityTarget(target);
        }
    }

    private void doInteractingBarrel() {
        if (!isBarrelStillPresent(targetBlockPos)) {
            resetToSearching();
            return;
        }

        if (isContainerMenuOpen()) {
            depositSettleTicks = 0;
            stage = Stage.DEPOSITING;
            return;
        }

        ticksInStage++;
        if (ticksInStage < interactDelay.get()) return;
        ticksInStage = 0;

        if (++interactAttempts > maxInteractAttempts.get()) {
            resetToSearching();
            return;
        }

        BlockPos pos = targetBlockPos;
        Vec3 hitPos = Vec3.atCenterOf(pos);
        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(hitPos), Rotations.getPitch(hitPos), 50, () -> interactBarrelTarget(pos));
        } else {
            interactBarrelTarget(pos);
        }
    }

    private void interactEntityTarget(Entity target) {
        if (mc.player == null || mc.gameMode == null || !target.isAlive()) return;
        mc.gameMode.interact(mc.player, target, new EntityHitResult(target), InteractionHand.MAIN_HAND);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    private void interactBarrelTarget(BlockPos pos) {
        if (mc.player == null || mc.gameMode == null || !isBarrelStillPresent(pos)) return;

        BlockHitResult hitResult = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        InteractionResult result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hitResult);
        if (result.consumesAction()) mc.player.swing(InteractionHand.MAIN_HAND);
    }

    private void doDepositing() {
        boolean targetStillValid = targetEntityId != -1
            ? getTargetEntity() != null
            : targetBlockPos != null && isBarrelStillPresent(targetBlockPos);

        if (!targetStillValid || !isContainerMenuOpen()) {
            // Container died mid-transfer, or the screen was force-closed server side.
            stage = Stage.CLOSING;
            return;
        }

        // Settle window: give the server's inventory-contents sync packet
        // time to arrive before trusting slot contents for full/empty
        // decisions. Without this, a freshly opened ScreenHandler can read
        // every slot as empty for a tick or two, which previously caused
        // autoClose to fire before a single item was ever deposited.
        if (depositSettleTicks < depositSettleDelay.get()) {
            depositSettleTicks++;
            return;
        }

        // Completion check: if every container slot is already occupied by a
        // deposit item, there's nothing more we can do here so we mark it and
        // stop bothering with this specific entity/position.
        if (isContainerFullOfDepositItems()) {
            markCurrentTargetCompleted();
            stage = Stage.CLOSING;
            return;
        }

        boolean playerHasDepositItems = false;
        for (int i = SlotUtils.indexToId(SlotUtils.MAIN_START); i < SlotUtils.indexToId(SlotUtils.MAIN_START) + 4 * 9; i++) {
            Slot slot = mc.player.containerMenu.getSlot(i);
            if (slot.hasItem() && isDepositItem(slot.getItem())) {
                playerHasDepositItems = true;
                break;
            }
        }

        if (!playerHasDepositItems) {
            if (autoClose.get()) stage = Stage.CLOSING;
            return;
        }

        boolean containerHasEmptySlots = false;
        for (int i = 0; i < SlotUtils.indexToId(SlotUtils.MAIN_START); i++) {
            if (!mc.player.containerMenu.getSlot(i).hasItem()) {
                containerHasEmptySlots = true;
                break;
            }
        }

        if (!containerHasEmptySlots) {
            if (autoClose.get()) stage = Stage.CLOSING;
            return;
        }

        int moved = 0;
        for (int i = SlotUtils.indexToId(SlotUtils.MAIN_START); i < SlotUtils.indexToId(SlotUtils.MAIN_START) + 4 * 9; i++) {
            if (moved >= depositRate.get()) break;

            Slot slot = mc.player.containerMenu.getSlot(i);
            if (!slot.hasItem() || !isDepositItem(slot.getItem())) continue;

            InvUtils.shiftClick().slotId(i);
            moved++;
        }

        if (moved > 0 && resyncAfterDeposit.get()) sendResyncPacket();
    }

    private void doClosing() {
        if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
            mc.player.closeContainer();
        }
        cycleTicks = 0;
        stage = Stage.WAITING;
    }

    private void doWaiting() {
        boolean stillExists = targetEntityId != -1
            ? getTargetEntity() != null
            : targetBlockPos != null && isBarrelStillPresent(targetBlockPos);

        if (!stillExists) {
            resetToSearching();
            return;
        }

        cycleTicks++;
        if (cycleTicks >= cycleDelay.get()) {
            resetToSearching();
        }
    }

    private void resetToSearching() {
        targetEntityId = -1;
        targetBlockPos = null;
        interactAttempts = 0;
        ticksInStage = 0;
        cycleTicks = 0;
        depositSettleTicks = 0;
        stage = Stage.SEARCHING;
    }

    private void markCurrentTargetCompleted() {
        if (targetEntityId != -1) {
            completedEntityIds.add(targetEntityId);
        } else if (targetBlockPos != null && mc.level != null) {
            completedBarrelPositions.put(targetBlockPos, mc.level.getGameTime() + barrelRecheckTicks.get());
        }
    }

    private boolean isContainerFullOfDepositItems() {
        for (int i = 0; i < SlotUtils.indexToId(SlotUtils.MAIN_START); i++) {
            Slot slot = mc.player.containerMenu.getSlot(i);
            if (!slot.hasItem() || !isDepositItem(slot.getItem())) return false;
        }
        return true;
    }

    private void pruneCompletedTargets() {
        if (!completedEntityIds.isEmpty()) {
            IntIterator it = completedEntityIds.iterator();
            while (it.hasNext()) {
                if (!entityExists(it.nextInt())) it.remove();
            }
        }

        if (!completedBarrelPositions.isEmpty() && mc.level != null) {
            long now = mc.level.getGameTime();
            completedBarrelPositions.object2LongEntrySet().removeIf(entry ->
                mc.level.getBlockState(entry.getKey()).getBlock() != Blocks.BARREL || now >= entry.getLongValue()
            );
        }
    }

    private boolean entityExists(int entityId) {
        if (mc.level == null) return false;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity.getId() == entityId) return entity.isAlive();
        }
        return false;
    }

    private boolean isBarrelStillPresent(BlockPos pos) {
        return mc.level != null && pos != null && mc.level.getBlockState(pos).getBlock() == Blocks.BARREL;
    }

    private Entity getTargetEntity() {
        if (targetEntityId == -1 || mc.level == null) return null;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity.getId() == targetEntityId) {
                return entity.isAlive() && entity instanceof MinecartChest ? entity : null;
            }
        }

        return null;
    }

    private Entity findNearestChestMinecart(double searchRange) {
        if (mc.player == null || mc.level == null) return null;

        List<MinecartChest> nearby = mc.level.getEntitiesOfClass(
            MinecartChest.class,
            mc.player.getBoundingBox().inflate(searchRange),
            entity -> entity.isAlive() && !completedEntityIds.contains(entity.getId())
        );

        Vec3 playerPos = mc.player.position();
        Entity nearest = null;
        double closestDistSq = Double.MAX_VALUE;

        for (MinecartChest entity : nearby) {
            double distSq = entity.position().distanceToSqr(playerPos);
            if (distSq < closestDistSq) {
                closestDistSq = distSq;
                nearest = entity;
            }
        }

        return nearest;
    }

    /**
     * Manual block scan (there's no equivalent of {@code getEntitiesByClass}
     * for blocks) over a cube around the player, filtered down to the actual
     * search radius and to barrels not already marked completed.
     */
    private BlockPos findNearestBarrel(double searchRange) {
        if (mc.player == null || mc.level == null) return null;

        BlockPos playerBlockPos = mc.player.blockPosition();
        int r = (int) Math.ceil(searchRange);
        Vec3 playerPos = mc.player.position();
        double searchRangeSq = searchRange * searchRange;

        BlockPos nearest = null;
        double closestDistSq = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.betweenClosed(playerBlockPos.offset(-r, -r, -r), playerBlockPos.offset(r, r, r))) {
            if (mc.level.getBlockState(pos).getBlock() != Blocks.BARREL) continue;
            if (completedBarrelPositions.containsKey(pos)) continue;

            double distSq = Vec3.atCenterOf(pos).distanceToSqr(playerPos);
            if (distSq > searchRangeSq) continue;

            if (distSq < closestDistSq) {
                closestDistSq = distSq;
                nearest = pos.immutable();
            }
        }

        return nearest;
    }

    private boolean isContainerMenuOpen() {
        if (mc.player == null) return false;
        AbstractContainerMenu handler = mc.player.containerMenu;
        return handler != mc.player.inventoryMenu && handler.getType() == MenuType.GENERIC_9x3;
    }

    private boolean isDepositItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (isShulkerBox(stack)) return true;
        return extraItems.get().contains(stack.getItem());
    }

    private boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock;
    }

    /**
     * Checked before searching at all, so the module doesn't rotate/interact
     * for an empty run when there's genuinely nothing to deposit.
     */
    private boolean hasAnyDepositItem() {
        if (mc.player == null) return false;

        Inventory inventory = mc.player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && isDepositItem(stack)) return true;
        }

        return false;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!renderTarget.get()) return;

        if (targetEntityId != -1) {
            Entity target = getTargetEntity();
            if (target != null) {
                event.renderer.box(target.getBoundingBox(), sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            }
        } else if (targetBlockPos != null && isBarrelStillPresent(targetBlockPos)) {
            event.renderer.box(new AABB(targetBlockPos), sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }

    private void sendResyncPacket() {
        if (mc.player == null || mc.player.connection == null) return;

        AbstractContainerMenu handler = mc.player.containerMenu;
        Int2ObjectMap<HashedStack> modifiedStacks = new Int2ObjectOpenHashMap<>();
        mc.player.connection.send(new ServerboundContainerClickPacket(
            handler.containerId,
            handler.getStateId(),
            (short) -1,
            (byte) 0,
            ContainerInput.CLONE,
            modifiedStacks,
            HashedStack.EMPTY
        ));
    }
}
