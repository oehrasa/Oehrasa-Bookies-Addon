package com.AutoBookshelf.addon.modules; // Adjust package to your addon

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayList;
import java.util.List;

public class
ThrowEmptyShulkers extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFilter  = settings.createGroup("Filter");
    private final SettingGroup sgThrow   = settings.createGroup("Throw Direction");

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay in ticks between each shulker throw.")
        .defaultValue(5)
        .min(1)
        .max(100)
        .sliderRange(1, 100)
        .build()
    );

    private final Setting<Boolean> hotbarOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("hotbar-only")
        .description("Only throw shulkers from the hotbar (slots 0-8).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> emptyOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("empty-only")
        .description("Only throw shulkers that are completely empty. Ignores all filter settings when enabled.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> throwAtOnce = sgGeneral.add(new BoolSetting.Builder()
        .name("throw-at-once")
        .description("Collects all matching shulkers, rotates once, throws them all, then restores rotation.")
        .defaultValue(false)
        .build()
    );

    // Rotation mode (silent = client‑side only, normal = sends packets)
    private final Setting<RotationMode> rotationMode = sgThrow.add(new EnumSetting.Builder<RotationMode>()
        .name("rotation-mode")
        .description("Normal: sends rotation packets (server sees you turn). Silent: client‑side only (no packets).")
        .defaultValue(RotationMode.Silent)
        .build()
    );

    // Disable rotation entirely
    private final Setting<Boolean> enableRotation = sgThrow.add(new BoolSetting.Builder()
        .name("enable-rotation")
        .description("Completely disable any rotation (yaw/pitch changes) – throws without moving your camera.")
        .defaultValue(true)
        .build()
    );

    // Throw direction offsets
    private final Setting<Double> yaw = sgThrow.add(new DoubleSetting.Builder()
        .name("yaw-offset")
        .description("Rotates your yaw by this amount before throwing. 180 = behind you, 0 = forward.")
        .defaultValue(0)
        .min(-180)
        .max(180)
        .sliderMin(-180)
        .sliderMax(180)
        .build()
    );

    private final Setting<Double> pitch = sgThrow.add(new DoubleSetting.Builder()
        .name("pitch-offset")
        .description("Rotates your pitch by this amount before throwing. Negative = upward, positive = downward.")
        .defaultValue(0)
        .min(-90)
        .max(90)
        .sliderMin(-90)
        .sliderMax(90)
        .build()
    );

    // Filter settings
    private enum FilterMode { WHITELIST, BLACKLIST }
    private final Setting<FilterMode> filterMode = sgFilter.add(new EnumSetting.Builder<FilterMode>()
        .name("filter-mode")
        .description("Whitelist: skip shulkers containing listed items. Blacklist: throw shulkers containing listed items.")
        .defaultValue(FilterMode.BLACKLIST)
        .build()
    );

    private final Setting<List<Item>> filterItems = sgFilter.add(new ItemListSetting.Builder()
        .name("filter-items")
        .description("Items to check inside shulkers. Behaviour depends on filter-mode.")
        .defaultValue()
        .build()
    );

    // State machines
    private enum ThrowState { IDLE, ROTATING, THROWING, RESTORING }
    private enum BatchState { IDLE, ROTATING, THROWING_BATCH, RESTORING }
    private enum RotationMode { Normal, Silent }

    private ThrowState throwState = ThrowState.IDLE;
    private BatchState batchState = BatchState.IDLE;

    private int tickTimer = 0;
    private float savedYaw = 0;
    private float savedPitch = 0;
    private int pendingSlot = -1;
    private final List<Integer> pendingSlots = new ArrayList<>();
    private int batchIndex = 0;

    public ThrowEmptyShulkers() {
        super(Addon.CATEGORY, "Throw-Shulkers", "Automatically throws shulker boxes based on their contents.");
    }

    @Override
    public void onDeactivate() {
        throwState = ThrowState.IDLE;
        batchState = BatchState.IDLE;
        tickTimer = 0;
        pendingSlot = -1;
        pendingSlots.clear();
        batchIndex = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.interactionManager == null) return;

        if (throwAtOnce.get()) {
            tickBatchMode();
        } else {
            tickSingleMode();
        }
    }

    // Rotation helper using Rotations.rotate
    private void applyRotation(float yaw, float pitch) {
        if (!enableRotation.get()) return; // no rotation at all
        boolean silent = (rotationMode.get() == RotationMode.Silent);
        Rotations.rotate(yaw, pitch, 50, silent, null);
    }

    private void tickSingleMode() {
        switch (throwState) {
            case ROTATING -> {
                savedYaw = mc.player.getYaw();
                savedPitch = mc.player.getPitch();
                applyRotation(savedYaw + yaw.get().floatValue(), savedPitch + pitch.get().floatValue());
                throwState = ThrowState.THROWING;
            }
            case THROWING -> {
                executeDrop(pendingSlot);
                throwState = enableRotation.get() ? ThrowState.RESTORING : ThrowState.IDLE;
                if (!enableRotation.get()) tickTimer = delay.get();
            }
            case RESTORING -> {
                applyRotation(savedYaw, savedPitch);
                throwState = ThrowState.IDLE;
                tickTimer = delay.get();
            }
            case IDLE -> {
                if (tickTimer > 0) { tickTimer--; return; }
                int endSlot = hotbarOnly.get() ? 9 : mc.player.getInventory().size();
                for (int i = 0; i < endSlot; i++) {
                    if (shouldThrow(mc.player.getInventory().getStack(i))) {
                        boolean needsRotation = enableRotation.get() && (yaw.get() != 0 || pitch.get() != 0);
                        if (needsRotation) {
                            pendingSlot = i;
                            throwState = ThrowState.ROTATING;
                        } else {
                            executeDrop(i);
                            tickTimer = delay.get();
                        }
                        return;
                    }
                }
            }
        }
    }

    private void tickBatchMode() {
        switch (batchState) {
            case ROTATING -> {
                savedYaw = mc.player.getYaw();
                savedPitch = mc.player.getPitch();
                applyRotation(savedYaw + yaw.get().floatValue(), savedPitch + pitch.get().floatValue());
                batchIndex = 0;
                batchState = BatchState.THROWING_BATCH;
            }
            case THROWING_BATCH -> {
                if (tickTimer > 0) { tickTimer--; return; }
                if (batchIndex < pendingSlots.size()) {
                    int slot = pendingSlots.get(batchIndex);
                    if (shouldThrow(mc.player.getInventory().getStack(slot))) {
                        executeDrop(slot);
                    }
                    batchIndex++;
                    tickTimer = delay.get();
                } else {
                    batchState = enableRotation.get() ? BatchState.RESTORING : BatchState.IDLE;
                    if (!enableRotation.get()) {
                        pendingSlots.clear();
                        batchIndex = 0;
                        tickTimer = delay.get();
                    }
                }
            }
            case RESTORING -> {
                applyRotation(savedYaw, savedPitch);
                pendingSlots.clear();
                batchIndex = 0;
                batchState = BatchState.IDLE;
                tickTimer = delay.get();
            }
            case IDLE -> {
                if (tickTimer > 0) { tickTimer--; return; }
                pendingSlots.clear();
                int endSlot = hotbarOnly.get() ? 9 : mc.player.getInventory().size();
                for (int i = 0; i < endSlot; i++) {
                    if (shouldThrow(mc.player.getInventory().getStack(i))) {
                        pendingSlots.add(i);
                    }
                }
                if (pendingSlots.isEmpty()) return;
                boolean needsRotation = enableRotation.get() && (yaw.get() != 0 || pitch.get() != 0);
                if (needsRotation) {
                    batchState = BatchState.ROTATING;
                } else {
                    batchIndex = 0;
                    batchState = BatchState.THROWING_BATCH;
                }
            }
        }
    }

    // Filter
    private boolean shouldThrow(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (!(stack.getItem() instanceof BlockItem blockItem)) return false;
        if (!(blockItem.getBlock() instanceof ShulkerBoxBlock)) return false;

        if (emptyOnly.get()) return isShulkerEmpty(stack);

        List<Item> items = filterItems.get();
        if (items.isEmpty()) return true;

        boolean containsFilteredItem = shulkerContainsAny(stack, items);
        return switch (filterMode.get()) {
            case WHITELIST -> !containsFilteredItem;
            case BLACKLIST -> containsFilteredItem;
        };
    }

    private boolean isShulkerEmpty(ItemStack stack) {
        ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
        if (container == null) return true;
        for (ItemStack stored : container.iterateNonEmpty()) {
            if (!stored.isEmpty()) return false;
        }
        return true;
    }

    private boolean shulkerContainsAny(ItemStack shulker, List<Item> items) {
        ContainerComponent container = shulker.get(DataComponentTypes.CONTAINER);
        if (container == null) return false;
        for (ItemStack stored : container.iterateNonEmpty()) {
            if (!stored.isEmpty() && items.contains(stored.getItem())) return true;
        }
        return false;
    }

    private void executeDrop(int invSlot) {
        if (mc.player == null || mc.player.currentScreenHandler == null) return;
        int networkSlot = (invSlot < 9) ? 36 + invSlot : invSlot;
        mc.interactionManager.clickSlot(
            mc.player.currentScreenHandler.syncId,
            networkSlot,
            1,
            SlotActionType.THROW,
            mc.player
        );
    }
}
