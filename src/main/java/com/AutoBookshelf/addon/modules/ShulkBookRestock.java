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

    private final Setting<Integer> restockDelay = sgGeneral.add(new IntSetting.Builder()
        .name("restock-delay")
        .description("Delay in ticks between restock operations.")
        .defaultValue(2)
        .min(0)
        .max(20)
        .build()
    );

    private final Setting<Boolean> pauseOnUse = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-on-use")
        .description("Delay restock while actively using an item.")
        .defaultValue(true)
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

    // internal state
    private int delayTicks = 0;
    private final List<Integer> pendingShiftClicks = new ArrayList<>();

    public ShulkBookRestock() {
        super(Addon.CATEGORY, "SBB-Restock", "Automatically restocks shulkers and books in your hotbar using shift-click.");
    }

    @Override
    public void onActivate() {
        delayTicks = 0;
        pendingShiftClicks.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.player.getInventory() == null) return;
        if (mc.currentScreen != null) return;
        if (pauseOnUse.get() && mc.player.isUsingItem()) return;

        // Process any pending shift-clicks first
        if (!pendingShiftClicks.isEmpty()) {
            if (delayTicks <= 0) {
                int slot = pendingShiftClicks.remove(0);
                sendShiftClick(slot);
                delayTicks = restockDelay.get();
            } else {
                delayTicks--;
            }
            return;
        }

        // Check if any hotbar slot is empty or needs more items
        if (needsRestock()) {
            // Find up to 5 valid stacks in inventory and queue them for shift-click
            findSourceSlots();
            if (!pendingShiftClicks.isEmpty()) {
                delayTicks = 0; // start immediately
            }
        }
    }

    private boolean needsRestock() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack == null || stack.isEmpty()) return true; // empty slot needs restock
            // Optionally, if stack count is very low (1), we still restock if we have more
            if (isValidItem(stack) && stack.getCount() <= 1) {
                // check if there's a larger stack in inventory
                for (int j = 9; j < 36; j++) {
                    ItemStack invStack = mc.player.getInventory().getStack(j);
                    if (invStack != null && canMerge(stack, invStack) && invStack.getCount() > 1) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void findSourceSlots() {
        pendingShiftClicks.clear();
        // Collect up to 5 slots from inventory that contain valid items
        for (int i = 9; i < 36 && pendingShiftClicks.size() < 5; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack == null || stack.isEmpty()) continue;
            if (!isValidItem(stack)) continue;
            pendingShiftClicks.add(i);
        }
    }

    private void sendShiftClick(int slot) {
        // Shift-click the stack from inventory slot into the hotbar (fills empty slots automatically)
        mc.interactionManager.clickSlot(
            mc.player.playerScreenHandler.syncId,
            slot,
            0,
            SlotActionType.QUICK_MOVE,
            mc.player
        );
    }

    private boolean isValidItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Item item = stack.getItem();
        if (restockShulkers.get() && item instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock)
            return true;
        return restockWritableBooks.get() && item == Items.WRITABLE_BOOK;
    }

    private boolean canMerge(ItemStack a, ItemStack b) {
        return ItemStack.areItemsAndComponentsEqual(a, b);
    }

    @Override
    public void onDeactivate() {
        pendingShiftClicks.clear();
    }
}
