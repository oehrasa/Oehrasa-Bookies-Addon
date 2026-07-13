package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import it.unimi.dsi.fastutil.ints.*;
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
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.vehicle.ChestMinecartEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Repeatedly targets a chest minecart (and, optionally, barrel blocks),
 * right-clicks it open, shift-clicks every shulker box (plus any configured
 * extra items) out of the player's inventory into it, closes the GUI, waits,
 * then looks for the next one - built for a farm setup where the machine
 * constantly destroys and respawns a chest minecart (or has a fixed barrel)
 * in roughly the same spot.
 * <p>
 * Fixed against the AutoBookshelf project's actual base class (plain
 * {@link Module}, {@code Addon.CATEGORY}) instead of the unrelated reference
 * package used before, and against the confirmed-correct 1.21.11 Yarn API
 * shown in AxolotlTools:
 * <ul>
 *   <li>{@code Rotations.getYaw(entity)} / {@code Rotations.getPitch(entity, Target.Body)}
 *       take the {@link Entity} directly rather than a manually computed
 *       {@code Vec3d}.</li>
 *   <li>Entity position is read via {@code getEntityPos()}, not {@code getPos()}.</li>
 *   <li>{@code mc.world.getEntitiesByClass(Class, Box, predicate)} is used for
 *       the nearby search instead of manually filtering {@code getEntities()}.</li>
 *   <li>{@code ClickSlotC2SPacket}'s slot-id parameter is a {@code short} on
 *       this branch, so the resync packet's {@code -1} needs an explicit cast.</li>
 * </ul>
 * <p>
 * New for barrel + completion-tracking support - flagging these as
 * assumptions since they're not independently confirmed the way the
 * rotation/position APIs above are:
 * <ul>
 *   <li>{@code Rotations.getYaw(Vec3d)} / {@code Rotations.getPitch(Vec3d)}
 *       are assumed to exist as position-based overloads alongside the
 *       entity-based ones, for aiming at the barrel's block center.</li>
 *   <li>{@code BlockHitResult(Vec3d, Direction, BlockPos, boolean)} is assumed
 *       to be the constructor shape; the side passed ({@code Direction.UP})
 *       is arbitrary since opening a barrel doesn't care which face was hit.</li>
 *   <li>{@code mc.interactionManager.interactBlock(player, hand, hitResult)}
 *       returning an {@code ActionResult} with {@code isAccepted()} is
 *       assumed rather than confirmed against decompiled source.</li>
 *   <li>{@code PlayerInventory.size()} / {@code getStack(int)} are used
 *       instead of the {@code main} field directly, since the field name is
 *       more mapping-sensitive than the index-based accessors.</li>
 * </ul>
 * Still worth a quick double check: {@code ChestMinecartEntity}'s exact class
 * name/package, since I don't have that one independently confirmed the way
 * the rotation/position APIs now are.
 */
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
        .description("Seconds to wait after closing the container before searching for the next one.")
        .defaultValue(3)
        .min(0)
        .sliderMax(60)
        .build()
    );

    private final Setting<Boolean> targetBarrels = sgGeneral.add(new BoolSetting.Builder()
        .name("target-barrels")
        .description("Also fill barrels.")
        .defaultValue(false)
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
        .sliderMax(20)
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
        .sliderMax(20)
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
        .description("Extra items (besides all shulker box colors) to deposit into the container.")
        .defaultValue(new ArrayList<>())
        .build()
    );

    private final Setting<Boolean> resyncAfterDeposit = sgDeposit.add(new BoolSetting.Builder()
        .name("resync-after-deposit")
        .description("Sends an inventory resync packet after each deposit action.")
        .defaultValue(true)
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

    private final IntSet completedEntityIds = new IntOpenHashSet();

    /**
     * Same idea as {@link #completedEntityIds}, but for barrel positions.
     * Positions are pruned once the block there is no longer a barrel, so a
     * freshly placed barrel at the same coordinates is treated as new.
     */
    private final Set<BlockPos> completedBarrelPositions = new HashSet<>();

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
        if (mc.player != null && mc.player.currentScreenHandler != mc.player.playerScreenHandler) {
            mc.player.closeHandledScreen();
        }
        resetToSearching();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        switch (stage) {
            case SEARCHING -> doSearching();
            case INTERACTING -> doInteracting();
            case DEPOSITING -> doDepositing();
            case CLOSING -> doClosing();
            case WAITING -> doWaiting();
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
        Vec3d hitPos = Vec3d.ofCenter(pos);
        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(hitPos), Rotations.getPitch(hitPos), 50, () -> interactBarrelTarget(pos));
        } else {
            interactBarrelTarget(pos);
        }
    }

    private void interactEntityTarget(Entity target) {
        if (mc.player == null || mc.interactionManager == null || !target.isAlive()) return;
        mc.interactionManager.interactEntity(mc.player, target, Hand.MAIN_HAND);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private void interactBarrelTarget(BlockPos pos) {
        if (mc.player == null || mc.interactionManager == null || !isBarrelStillPresent(pos)) return;

        BlockHitResult hitResult = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
        ActionResult result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
        if (result.isAccepted()) mc.player.swingHand(Hand.MAIN_HAND);
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
            Slot slot = mc.player.currentScreenHandler.getSlot(i);
            if (slot.hasStack() && isDepositItem(slot.getStack())) {
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
            if (!mc.player.currentScreenHandler.getSlot(i).hasStack()) {
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

            Slot slot = mc.player.currentScreenHandler.getSlot(i);
            if (!slot.hasStack() || !isDepositItem(slot.getStack())) continue;

            InvUtils.shiftClick().slotId(i);
            moved++;
        }

        if (moved > 0 && resyncAfterDeposit.get()) sendResyncPacket();
    }

    private void doClosing() {
        if (mc.player != null && mc.player.currentScreenHandler != mc.player.playerScreenHandler) {
            mc.player.closeHandledScreen();
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
        if (cycleTicks >= cycleDelay.get() * 20) {
            resetToSearching();
        }
    }

    private void resetToSearching() {
        targetEntityId = -1;
        targetBlockPos = null;
        interactAttempts = 0;
        ticksInStage = 0;
        cycleTicks = 0;
        stage = Stage.SEARCHING;
    }

    private void markCurrentTargetCompleted() {
        if (targetEntityId != -1) {
            completedEntityIds.add(targetEntityId);
        } else if (targetBlockPos != null) {
            completedBarrelPositions.add(targetBlockPos);
        }
    }

    private boolean isContainerFullOfDepositItems() {
        for (int i = 0; i < SlotUtils.indexToId(SlotUtils.MAIN_START); i++) {
            Slot slot = mc.player.currentScreenHandler.getSlot(i);
            if (!slot.hasStack() || !isDepositItem(slot.getStack())) return false;
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

        if (!completedBarrelPositions.isEmpty() && mc.world != null) {
            completedBarrelPositions.removeIf(pos -> mc.world.getBlockState(pos).getBlock() != Blocks.BARREL);
        }
    }

    private boolean entityExists(int entityId) {
        if (mc.world == null) return false;
        for (Entity entity : mc.world.getEntities()) {
            if (entity.getId() == entityId) return entity.isAlive();
        }
        return false;
    }

    private boolean isBarrelStillPresent(BlockPos pos) {
        return mc.world != null && pos != null && mc.world.getBlockState(pos).getBlock() == Blocks.BARREL;
    }

    private Entity getTargetEntity() {
        if (targetEntityId == -1 || mc.world == null) return null;

        for (Entity entity : mc.world.getEntities()) {
            if (entity.getId() == targetEntityId) {
                return entity.isAlive() && entity instanceof ChestMinecartEntity ? entity : null;
            }
        }

        return null;
    }

    private Entity findNearestChestMinecart(double searchRange) {
        if (mc.player == null || mc.world == null) return null;

        List<ChestMinecartEntity> nearby = mc.world.getEntitiesByClass(
            ChestMinecartEntity.class,
            mc.player.getBoundingBox().expand(searchRange),
            entity -> entity.isAlive() && !completedEntityIds.contains(entity.getId())
        );

        Vec3d playerPos = mc.player.getEntityPos();
        Entity nearest = null;
        double closestDistSq = Double.MAX_VALUE;

        for (ChestMinecartEntity entity : nearby) {
            double distSq = entity.getEntityPos().squaredDistanceTo(playerPos);
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
        if (mc.player == null || mc.world == null) return null;

        BlockPos playerBlockPos = mc.player.getBlockPos();
        int r = (int) Math.ceil(searchRange);
        Vec3d playerPos = mc.player.getEntityPos();
        double searchRangeSq = searchRange * searchRange;

        BlockPos nearest = null;
        double closestDistSq = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.iterate(playerBlockPos.add(-r, -r, -r), playerBlockPos.add(r, r, r))) {
            if (mc.world.getBlockState(pos).getBlock() != Blocks.BARREL) continue;
            if (completedBarrelPositions.contains(pos)) continue;

            double distSq = Vec3d.ofCenter(pos).squaredDistanceTo(playerPos);
            if (distSq > searchRangeSq) continue;

            if (distSq < closestDistSq) {
                closestDistSq = distSq;
                nearest = pos.toImmutable();
            }
        }

        return nearest;
    }

    private boolean isContainerMenuOpen() {
        if (mc.player == null) return false;
        ScreenHandler handler = mc.player.currentScreenHandler;
        return handler != mc.player.playerScreenHandler && handler.getType() == ScreenHandlerType.GENERIC_9X3;
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

        PlayerInventory inventory = mc.player.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
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
            event.renderer.box(new Box(targetBlockPos), sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }

    private void sendResyncPacket() {
        if (mc.player == null || mc.player.networkHandler == null) return;

        ScreenHandler handler = mc.player.currentScreenHandler;
        Int2ObjectMap<ItemStackHash> modifiedStacks = new Int2ObjectOpenHashMap<>();
        mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
            handler.syncId,
            handler.getRevision(),
            (short) -1,
            (byte) 0,
            SlotActionType.CLONE,
            modifiedStacks,
            ItemStackHash.EMPTY
        ));
    }
}
