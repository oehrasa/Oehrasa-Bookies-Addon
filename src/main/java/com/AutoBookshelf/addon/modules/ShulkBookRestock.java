package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayList;
import java.util.List;

public class ShulkBookRestock extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgItems = settings.createGroup("Items");

    private final Setting<Integer> restockSlot = sgGeneral.add(new IntSetting.Builder()
        .name("restock-slot")
        .description("The hotbar slot to restock when all are empty (0 = auto-detect).")
        .defaultValue(0)
        .min(0)
        .max(9)
        .sliderMax(9)
        .build()
    );

    private final Setting<Boolean> keepOneInInventory = sgGeneral.add(new BoolSetting.Builder()
        .name("keep-one")
        .description("Keep at least one item in inventory.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> restockDelay = sgGeneral.add(new IntSetting.Builder()
        .name("restock-delay")
        .description("Delay in ticks before restocking after hotbar is empty.")
        .defaultValue(50)
        .min(0)
        .max(100)
        .sliderRange(1, 100)
        .build()
    );

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-switch")
        .description("Automatically switch to another hotbar slot when current runs out.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> switchDelay = sgGeneral.add(new IntSetting.Builder()
        .name("switch-delay")
        .description("Delay in ticks before auto-switching.")
        .defaultValue(50)
        .min(0)
        .max(100)
        .sliderRange(1, 100)
        .visible(autoSwitch::get)
        .build()
    );

    private final Setting<Boolean> restockShulkers = sgItems.add(new BoolSetting.Builder()
        .name("restock-shulkers")
        .description("Restock shulker boxes.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> restockWritableBooks = sgItems.add(new BoolSetting.Builder()
        .name("restock-writable-books")
        .description("Restock writable books (book and quill).")
        .defaultValue(false)
        .build()
    );

    private int timer = 0;
    private int[] previousCounts = new int[9];
    private boolean pendingRestock = false;
    private int slotToRestock = -1;
    private boolean autoSwitchInProgress = false;
    private int switchCooldown = 0;
    private int lastUsedSlot = -1;

    public ShulkBookRestock() {
        super(Addon.CATEGORY2, "SBB-Restock", "Automatically restocks shulkers and books in your hotbar when used");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.player.getInventory() == null) {
            error("Player not loaded");
            return;
        }

        timer = 0;
        pendingRestock = false;
        slotToRestock = -1;
        autoSwitchInProgress = false;
        switchCooldown = 0;
        lastUsedSlot = -1;

        for (int i = 0; i < 9; i++) {
            try {
                ItemStack stack = mc.player.getInventory().getStack(i);
                previousCounts[i] = (stack != null && isValidItem(stack)) ? stack.getCount() : 0;
            } catch (Exception e) {
                previousCounts[i] = 0;
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.player.getInventory() == null) return;
        if (mc.currentScreen != null) return;   // never restock while a GUI is open

        if (switchCooldown > 0) switchCooldown--;
        if (pendingRestock && timer > 0) {
            timer--;
            if (timer == 0) {
                performRestock();
                pendingRestock = false;
            }
        }

        checkForItemUsage();

        if (autoSwitch.get() && !autoSwitchInProgress && switchCooldown == 0 && lastUsedSlot != -1) {
            handleAutoSwitch();
        }

        if (!pendingRestock && isHotbarEmpty()) {
            slotToRestock = findBestSlotToRestock();
            if (slotToRestock != -1) {
                pendingRestock = true;
                timer = restockDelay.get();
            }
        }
    }

    private void checkForItemUsage() {
        if (mc.player == null || mc.player.getInventory() == null) return;
        lastUsedSlot = -1;

        for (int i = 0; i < 9; i++) {
            try {
                ItemStack stack = mc.player.getInventory().getStack(i);
                int currentCount = (stack == null || stack.isEmpty()) ? 0 : stack.getCount();

                if (isValidItem(stack)) {
                    if (currentCount < previousCounts[i]) {
                        lastUsedSlot = i;
                    }
                    previousCounts[i] = currentCount;
                } else {
                    if (previousCounts[i] > 0) {
                        lastUsedSlot = i;
                    }
                    previousCounts[i] = 0;
                }
            } catch (Exception e) {
                previousCounts[i] = 0;
            }
        }
    }

    private void handleAutoSwitch() {
        if (mc.player == null || mc.player.getInventory() == null) return;
        if (lastUsedSlot == -1) return;

        ItemStack usedStack = mc.player.getInventory().getStack(lastUsedSlot);
        boolean isSlotEmpty = usedStack == null || usedStack.isEmpty() || !isValidItem(usedStack);

        if (isSlotEmpty) {
            List<Integer> validSlots = getValidHotbarSlots();
            validSlots.remove(Integer.valueOf(lastUsedSlot));

            if (!validSlots.isEmpty()) {
                int nextSlot = validSlots.get(0);
                mc.player.getInventory().setSelectedSlot(nextSlot);
                autoSwitchInProgress = true;
                switchCooldown = switchDelay.get();
            }
        }
        autoSwitchInProgress = false;
    }

    private List<Integer> getValidHotbarSlots() {
        List<Integer> validSlots = new ArrayList<>();
        if (mc.player == null || mc.player.getInventory() == null) return validSlots;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack != null && isValidItem(stack) && !stack.isEmpty()) {
                validSlots.add(i);
            }
        }
        return validSlots;
    }

    private boolean isHotbarEmpty() {
        if (mc.player == null || mc.player.getInventory() == null) return true;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack != null && isValidItem(stack) && !stack.isEmpty()) return false;
        }
        return true;
    }

    private int findBestSlotToRestock() {
        if (mc.player == null || mc.player.getInventory() == null) return -1;
        int targetSlot = restockSlot.get();

        if (targetSlot == 0) {
            if (lastUsedSlot != -1 && lastUsedSlot < 9) {
                ItemStack stack = mc.player.getInventory().getStack(lastUsedSlot);
                if (stack == null || stack.isEmpty() || !isValidItem(stack)) return lastUsedSlot;
            }
            for (int i = 0; i < 9; i++) {
                ItemStack stack = mc.player.getInventory().getStack(i);
                if (stack == null || stack.isEmpty() || !isValidItem(stack)) return i;
            }
            return -1;
        } else {
            return targetSlot - 1;
        }
    }

    private void performRestock() {
        if (mc.player == null || mc.player.getInventory() == null || mc.interactionManager == null) return;
        int targetSlot = slotToRestock;
        if (targetSlot < 0 || targetSlot > 8) return;

        ItemStack currentStack = mc.player.getInventory().getStack(targetSlot);
        if (currentStack != null && isValidItem(currentStack) && !currentStack.isEmpty()) return;

        int itemSlot = findValidItemInInventoryExcludingHotbar();
        if (itemSlot == -1) return;

        moveToHotbar(itemSlot, targetSlot);
    }

    private int findValidItemInInventoryExcludingHotbar() {
        if (mc.player == null || mc.player.getInventory() == null) return -1;
        int totalCount = 0;
        List<Integer> candidateSlots = new ArrayList<>();

        for (int i = 9; i < 36; i++) {
            try {
                ItemStack stack = mc.player.getInventory().getStack(i);
                if (stack != null && isValidItem(stack) && !stack.isEmpty()) {
                    totalCount += stack.getCount();
                    candidateSlots.add(i);
                }
            } catch (Exception ignored) {}
        }

        if (keepOneInInventory.get()) {
            int hotbarCount = countValidItemsInHotbar();
            if (totalCount <= 1 && hotbarCount > 0) return -1;
        }

        return candidateSlots.isEmpty() ? -1 : candidateSlots.get(0);
    }

    private int countValidItemsInHotbar() {
        if (mc.player == null || mc.player.getInventory() == null) return 0;
        int count = 0;
        for (int i = 0; i < 9; i++) {
            try {
                ItemStack stack = mc.player.getInventory().getStack(i);
                if (stack != null && isValidItem(stack) && !stack.isEmpty()) count++;
            } catch (Exception ignored) {}
        }
        return count;
    }

    private boolean isValidItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Item item = stack.getItem();

        if (restockShulkers.get() && item instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock) {
            return true;
        }
        return restockWritableBooks.get() && item == Items.WRITABLE_BOOK;
    }

    private void moveToHotbar(int fromSlot, int toHotbarSlot) {
        if (fromSlot < 0 || toHotbarSlot < 0 || mc.player == null || mc.interactionManager == null) return;

        mc.interactionManager.clickSlot(
            mc.player.playerScreenHandler.syncId,
            fromSlot,
            toHotbarSlot,
            SlotActionType.SWAP,
            mc.player
        );
    }

    @Override
    public void onDeactivate() {
        pendingRestock = false;
    }
}
