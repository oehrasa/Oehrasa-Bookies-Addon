package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
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

    // General settings
    private final Setting<Integer> restockSlot = sgGeneral.add(new IntSetting.Builder()
        .name("restock-slot")
        .description("The hotbar slot to restock when all are empty (1-9, 0 = auto-detect)")
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
        .description("Delay in ticks before restocking after hotbar is empty")
        .defaultValue(2)
        .min(0)
        .max(20)
        .build()
    );

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-switch")
        .description("Automatically switch to another hotbar slot when current runs out")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> switchDelay = sgGeneral.add(new IntSetting.Builder()
        .name("switch-delay")
        .description("Delay in ticks before auto-switching")
        .defaultValue(1)
        .min(0)
        .max(10)
        .visible(autoSwitch::get)
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
    private boolean autoSwitchInProgress = false;
    private int switchCooldown = 0;
    private int lastUsedSlot = -1;

    public ShulkBookRestock() {
        super(Addon.CATEGORY, "SBB-restock", "Automatically restocks shulkers and books in your hotbar when used");
    }

    @Override
    public void onActivate() {
        // Safety check
        if (mc.player == null || mc.player.getInventory() == null) {
            error("Player not loaded, module may not work correctly");
            return;
        }
        
        timer = 0;
        pendingRestock = false;
        slotToRestock = -1;
        autoSwitchInProgress = false;
        switchCooldown = 0;
        lastUsedSlot = -1;
        
        // Store initial counts
        for (int i = 0; i < 9; i++) {
            try {
                ItemStack stack = mc.player.getInventory().getStack(i);
                if (stack != null && isValidItem(stack)) {
                    previousCounts[i] = stack.getCount();
                } else {
                    previousCounts[i] = 0;
                }
            } catch (Exception e) {
                previousCounts[i] = 0;
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        // Safety check
        if (mc.player == null || mc.player.getInventory() == null) return;

        // Handle switch cooldown
        if (switchCooldown > 0) {
            switchCooldown--;
        }

        // Handle restock delay
        if (pendingRestock && timer > 0) {
            timer--;
            if (timer == 0) {
                performRestock();
                pendingRestock = false;
            }
        }

        // Check for item usage - this will detect when a shulker/book was used
        checkForItemUsage();
        
        // Auto switch logic - if an item was used and its slot is now empty, switch
        if (autoSwitch.get() && !autoSwitchInProgress && switchCooldown == 0 && lastUsedSlot != -1) {
            handleAutoSwitch();
        }
        
        // Check if hotbar is completely empty of valid items - then restock
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
        
        // Reset lastUsedSlot at the start of check
        lastUsedSlot = -1;
        
        for (int i = 0; i < 9; i++) {
            try {
                ItemStack stack = mc.player.getInventory().getStack(i);
                int currentCount = (stack == null || stack.isEmpty()) ? 0 : stack.getCount();
                
                if (isValidItem(stack)) {
                    // Check if this slot's count decreased (item was used)
                    if (currentCount < previousCounts[i]) {
                        lastUsedSlot = i;
                        // Don't break - we want to track the last used slot
                    }
                    previousCounts[i] = currentCount;
                } else {
                    // If slot became empty and previously had a valid item
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
        
        // Check if the slot that was just used is now empty
        if (lastUsedSlot != -1) {
            ItemStack usedStack = mc.player.getInventory().getStack(lastUsedSlot);
            boolean isSlotEmpty = usedStack == null || usedStack.isEmpty() || !isValidItem(usedStack);
            
            if (isSlotEmpty) {
                // Find other valid slots in hotbar
                List<Integer> validSlots = getValidHotbarSlots();
                
                // Remove the used slot from valid slots
                validSlots.remove(Integer.valueOf(lastUsedSlot));
                
                if (!validSlots.isEmpty()) {
                    // Switch to the next valid slot
                    int nextSlot = validSlots.get(0);
                    mc.player.getInventory().selectedSlot = nextSlot;
                    autoSwitchInProgress = true;
                    switchCooldown = switchDelay.get();
                }
            }
        }
        
        // Reset after processing
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
            if (stack != null && isValidItem(stack) && !stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private int findBestSlotToRestock() {
        if (mc.player == null || mc.player.getInventory() == null) return -1;
        
        int targetSlot = restockSlot.get();
        if (targetSlot == 0) {
            // Auto-detect: FIRST try the last used slot
            if (lastUsedSlot != -1 && lastUsedSlot < 9) {
                ItemStack stack = mc.player.getInventory().getStack(lastUsedSlot);
                if (stack == null || stack.isEmpty() || !isValidItem(stack)) {
                    return lastUsedSlot; // Restock the slot that was just used
                }
            }
            
            // If last used slot is taken, find first empty slot
            for (int i = 0; i < 9; i++) {
                ItemStack stack = mc.player.getInventory().getStack(i);
                if (stack == null || stack.isEmpty() || !isValidItem(stack)) {
                    return i;
                }
            }
            return -1;
        } else {
            // Use specified slot (convert 1-9 to 0-8)
            return targetSlot - 1;
        }
    }

    private void performRestock() {
        if (mc.player == null || mc.player.getInventory() == null || mc.interactionManager == null) return;
        
        int targetSlot = slotToRestock;
        if (targetSlot < 0 || targetSlot > 8) return;
        
        // Check if the slot is already occupied with a valid item
        ItemStack currentStack = mc.player.getInventory().getStack(targetSlot);
        if (currentStack != null && isValidItem(currentStack) && !currentStack.isEmpty()) {
            return;
        }
        
        // Find a valid item in inventory (excluding hotbar)
        int itemSlot = findValidItemInInventoryExcludingHotbar();
        if (itemSlot == -1) return;
        
        // Move item to the target hotbar slot
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
            } catch (Exception e) {
                // Skip this slot on error
            }
        }
        
        // Check if we should keep one in inventory
        if (keepOneInInventory.get()) {
            int hotbarCount = countValidItemsInHotbar();
            if (totalCount <= 1 && hotbarCount > 0) {
                return -1;
            }
        }
        
        if (!candidateSlots.isEmpty()) {
            return candidateSlots.get(0);
        }
        
        return -1;
    }

    private int countValidItemsInHotbar() {
        if (mc.player == null || mc.player.getInventory() == null) return 0;
        
        int count = 0;
        for (int i = 0; i < 9; i++) {
            try {
                ItemStack stack = mc.player.getInventory().getStack(i);
                if (stack != null && isValidItem(stack) && !stack.isEmpty()) {
                    count++;
                }
            } catch (Exception e) {
                // Skip on error
            }
        }
        return count;
    }

    private boolean isValidItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
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
        if (fromSlot < 0 || toHotbarSlot < 0) return;
        if (mc.player == null || mc.interactionManager == null) return;
        
        try {
            mc.interactionManager.clickSlot(
                mc.player.currentScreenHandler.syncId,
                fromSlot,
                toHotbarSlot,
                SlotActionType.SWAP,
                mc.player
            );
        } catch (Exception e) {
            error("Failed to move item: " + e.getMessage());
        }
    }

    @Override
    public String getInfoString() {
        if (mc.player == null || mc.player.getInventory() == null) {
            return null;
        }
        
        int shulkersInInv = 0;
        int booksInInv = 0;
        
        try {
            for (int i = 9; i < 36; i++) {
                ItemStack stack = mc.player.getInventory().getStack(i);
                if (stack != null && !stack.isEmpty()) {
                    Item item = stack.getItem();
                    if (restockShulkers.get() && item instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock) {
                        shulkersInInv += stack.getCount();
                    } else if (restockWritableBooks.get() && item == Items.WRITABLE_BOOK) {
                        booksInInv += stack.getCount();
                    }
                }
            }
        } catch (Exception e) {
            return null;
        }
        
        List<String> items = new ArrayList<>();
        if (restockShulkers.get() && shulkersInInv > 0) items.add(shulkersInInv + " shulkers");
        if (restockWritableBooks.get() && booksInInv > 0) items.add(booksInInv + " books");
        
        return items.isEmpty() ? null : String.join(", ", items);
    }
}