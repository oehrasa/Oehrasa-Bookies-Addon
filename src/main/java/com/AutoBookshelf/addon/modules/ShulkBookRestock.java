package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;

public class ShulkBookRestock extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgItems = settings.createGroup("Items");

    // General settings
    private final Setting<Integer> restockSlot = sgGeneral.add(new IntSetting.Builder()
        .name("restock-slot")
        .description("The hotbar slot to restock (1-9, 0 = auto-detect the slot you just used)")
        .defaultValue(0)
        .min(0)
        .max(9)
        .sliderMax(9)
        .build()
    );

    private final Setting<Boolean> keepOneInInventory = sgGeneral.add(new BoolSetting.Builder()
        .name("keep-one")
        .description("Keep at least one item in inventory (don't restock the last one)")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> restockDelay = sgGeneral.add(new IntSetting.Builder()
        .name("restock-delay")
        .description("Delay in ticks before restocking after using")
        .defaultValue(2)
        .min(0)
        .max(20)
        .build()
    );

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-switch")
        .description("Automatically switch to a hotbar slot that has the item when none in inventory")
        .defaultValue(false)
        .build()
    );

    // Item type settings
    private final Setting<Boolean> restockShulkers = sgItems.add(new BoolSetting.Builder()
        .name("restock-shulkers")
        .description("Restock shulker boxes")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> restockWritableBooks = sgItems.add(new BoolSetting.Builder()
        .name("restock-writable-books")
        .description("Restock writable books (book and quill)")
        .defaultValue(false)
        .build()
    );

    private int timer = 0;
    private int[] previousCounts = new int[9];
    private boolean pendingRestock = false;
    private int slotToRestock = -1;

    public ShulkBookRestock() {
        super(Addon.CATEGORY, "item-restock", "Automatically restocks items in your hotbar when used");
    }

    @Override
    public void onActivate() {
        timer = 0;
        pendingRestock = false;
        slotToRestock = -1;
        // Store initial counts
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (isValidItem(stack)) {
                previousCounts[i] = stack.getCount();
            } else {
                previousCounts[i] = 0;
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        if (pendingRestock && timer > 0) {
            timer--;
            if (timer == 0) {
                performRestock();
                pendingRestock = false;
            }
        }

        // Check for item usage by comparing inventory counts
        checkForItemUsage();

        // Auto switch to a valid item in hotbar if no items in inventory
        if (autoSwitch.get() && countItemsInInventory() == 0) {
            int itemHotbarSlot = findValidItemInHotbar();
            if (itemHotbarSlot != -1 && mc.player.getInventory().selectedSlot != itemHotbarSlot) {
                mc.player.getInventory().selectedSlot = itemHotbarSlot;
            }
        }
    }

    private void checkForItemUsage() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            int currentCount = stack.getCount();
            
            if (isValidItem(stack)) {
                // If a slot decreased by ANY amount, an item was used
                if (currentCount < previousCounts[i]) {
                    slotToRestock = i;
                    pendingRestock = true;
                    timer = restockDelay.get();
                    previousCounts[i] = currentCount;
                } else {
                    previousCounts[i] = currentCount;
                }
            } else {
                // If slot became empty and previously had a valid item
                if (previousCounts[i] > 0) {
                    slotToRestock = i;
                    pendingRestock = true;
                    timer = restockDelay.get();
                }
                previousCounts[i] = 0;
            }
        }
    }

    private void performRestock() {
        if (mc.player == null) return;
        
        int targetSlot = restockSlot.get();
        if (targetSlot == 0) {
            // Auto-detect: use the slot that was just used
            targetSlot = slotToRestock;
        } else {
            targetSlot = targetSlot - 1; // Convert 1-9 to 0-8
        }
        
        if (targetSlot < 0 || targetSlot > 8) return;
        
        // Find a valid item in inventory
        int itemSlot = findValidItemInInventory();
        if (itemSlot == -1) return;
        
        // Move item to the target hotbar slot
        moveToHotbar(itemSlot, targetSlot);
        
        // Update previous count for the target slot
        ItemStack newStack = mc.player.getInventory().getStack(targetSlot);
        if (isValidItem(newStack)) {
            previousCounts[targetSlot] = newStack.getCount();
        }
    }

    private int findValidItemInInventory() {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (isValidItem(stack)) {
                // Check if we should keep one in inventory
                if (keepOneInInventory.get()) {
                    int count = countItemsInInventory();
                    if (count <= 1) continue;
                }
                return i;
            }
        }
        return -1;
    }

    private int findValidItemInHotbar() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (isValidItem(stack)) {
                return i;
            }
        }
        return -1;
    }

    private int countItemsInInventory() {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (isValidItem(stack)) {
                count++;
            }
        }
        return count;
    }

    private boolean isValidItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        Item item = stack.getItem();
        
        // Check for shulkers
        if (restockShulkers.get()) {
            if (item instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock) {
                return true;
            }
        }
        
        // Check for writable books (book and quill)
        if (restockWritableBooks.get()) {
            if (item == Items.WRITABLE_BOOK) {
                return true;
            }
        }
        
        return false;
    }

    private void moveToHotbar(int fromSlot, int toHotbarSlot) {
        if (fromSlot < 9) {
            mc.interactionManager.clickSlot(
                mc.player.currentScreenHandler.syncId,
                fromSlot,
                toHotbarSlot,
                SlotActionType.SWAP,
                mc.player
            );
        } else {
            mc.interactionManager.clickSlot(
                mc.player.currentScreenHandler.syncId,
                fromSlot,
                toHotbarSlot,
                SlotActionType.SWAP,
                mc.player
            );
        }
    }

    @Override
    public String getInfoString() {
        int count = countItemsInInventory();
        if (count > 0) {
            String items = "";
            if (restockShulkers.get()) items += "shulkers ";
            if (restockWritableBooks.get()) items += "books";
            return count + " " + items.trim();
        }
        return null;
    }
}