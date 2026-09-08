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
import net.minecraft.component.DataComponentTypes;
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
        .description("Keep at least one of the specific item that ran out (same item/color and custom name) in inventory.")
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

    private final Setting<Boolean> matchColor = sgItems.add(new BoolSetting.Builder()
        .name("match-color")
        .description("Only restock/switch to a shulker box of the same color that was used.")
        .defaultValue(false)
        .visible(restockShulkers::get)
        .build()
    );

    private final Setting<Boolean> matchName = sgItems.add(new BoolSetting.Builder()
        .name("match-name")
        .description("Only restock/switch to an item with the same custom name that was used.")
        .defaultValue(false)
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
    private Item[] previousItems = new Item[9];
    private String[] previousNames = new String[9];
    private boolean pendingRestock = false;
    private int slotToRestock = -1;
    private boolean autoSwitchInProgress = false;
    private int switchCooldown = 0;
    private int lastUsedSlot = -1;
    private boolean wasScreenOpen = false;

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
        wasScreenOpen = false;

        captureBaseline();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.player.getInventory() == null) return;

        if (mc.currentScreen != null) {
            // GUI open: don't track or restock. Remember that it was open so the
            // tick it closes on can resync instead of comparing across the gap.
            wasScreenOpen = true;
            return;
        }

        if (wasScreenOpen) {
            // The screen just closed. Whatever changed while it was open (items
            // moved between slots, sorted, etc.)
            wasScreenOpen = false;
            captureBaseline();
            return;
        }

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

        if (!pendingRestock) {
            int candidate = findSlotNeedingRestock();
            if (candidate != -1) {
                slotToRestock = candidate;
                pendingRestock = true;
                timer = restockDelay.get();
            }
        }
    }

    /**
     * Snapshots all 9 hotbar slots into previousCounts/Items/Names without flagging any usage.
     */
    private void captureBaseline() {
        for (int i = 0; i < 9; i++) {
            try {
                ItemStack stack = mc.player.getInventory().getStack(i);
                if (stack != null && isValidItem(stack)) {
                    previousCounts[i] = stack.getCount();
                    previousItems[i] = stack.getItem();
                    previousNames[i] = stack.get(DataComponentTypes.CUSTOM_NAME) != null
                        ? stack.get(DataComponentTypes.CUSTOM_NAME).getString()
                        : null;
                } else {
                    previousCounts[i] = 0;
                    previousItems[i] = null;
                    previousNames[i] = null;
                }
            } catch (Exception e) {
                previousCounts[i] = 0;
                previousItems[i] = null;
                previousNames[i] = null;
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
                    previousItems[i] = stack.getItem();
                    previousNames[i] = stack.get(DataComponentTypes.CUSTOM_NAME) != null
                        ? stack.get(DataComponentTypes.CUSTOM_NAME).getString()
                        : null;
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

    /**
     * Candidate-selection matching: honors the match-color/match-name toggles.
     */
    private boolean matchesTarget(ItemStack candidate, int targetSlot) {
        if (matchColor.get() && previousItems[targetSlot] != null) {
            if (candidate.getItem() != previousItems[targetSlot]) return false;
        }
        if (matchName.get()) {
            var customName = candidate.get(DataComponentTypes.CUSTOM_NAME);
            String candidateName = customName != null ? customName.getString() : null;
            String targetName = previousNames[targetSlot];
            if (candidateName == null || targetName == null) {
                return candidateName == targetName;
            } else return candidateName.equals(targetName);
        }
        return true;
    }

    /**
     * Exact-variant matching for keep-one: always compares item (colour included, since
     * each shulker colour is a distinct Item) and custom name, regardless of whether
     * match-colour/match-name are enabled. Keep-one should always mean "keep at least
     * one of the specific thing that ran out,"
     */
    private boolean isSameVariant(ItemStack candidate, int targetSlot) {
        if (previousItems[targetSlot] != null && candidate.getItem() != previousItems[targetSlot]) return false;
        var customName = candidate.get(DataComponentTypes.CUSTOM_NAME);
        String candidateName = customName != null ? customName.getString() : null;
        String targetName = previousNames[targetSlot];
        if (candidateName == null && targetName == null) return true;
        if (candidateName == null || targetName == null) return false;
        return candidateName.equals(targetName);
    }

    private void handleAutoSwitch() {
        if (mc.player == null || mc.player.getInventory() == null) return;
        if (lastUsedSlot == -1) return;

        ItemStack usedStack = mc.player.getInventory().getStack(lastUsedSlot);
        boolean isSlotEmpty = usedStack == null || usedStack.isEmpty() || !isValidItem(usedStack);

        if (isSlotEmpty) {
            List<Integer> validSlots = getValidHotbarSlots(lastUsedSlot);
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

    private List<Integer> getValidHotbarSlots(int targetSlot) {
        List<Integer> validSlots = new ArrayList<>();
        if (mc.player == null || mc.player.getInventory() == null) return validSlots;

        for (int i = 0; i < 9; i++) {
            try {
                ItemStack stack = mc.player.getInventory().getStack(i);
                if (stack != null && isValidItem(stack) && !stack.isEmpty() && matchesTarget(stack, targetSlot)) {
                    validSlots.add(i);
                }
            } catch (Exception ignored) {}
        }
        return validSlots;
    }

    /**
     * Auto-detect: only restock the slot that was actually just used up by real
     * gameplay consumption (checkForItemUsage)
     */
    private int findSlotNeedingRestock() {
        if (mc.player == null || mc.player.getInventory() == null) return -1;
        int targetSlot = restockSlot.get();

        if (targetSlot != 0) {
            // Manual slot: always this slot, no tracking required
            int slot = targetSlot - 1;
            ItemStack stack = mc.player.getInventory().getStack(slot);
            boolean empty = stack == null || stack.isEmpty() || !isValidItem(stack);
            return empty ? slot : -1;
        }

        if (lastUsedSlot != -1) {
            ItemStack stack = mc.player.getInventory().getStack(lastUsedSlot);
            boolean empty = stack == null || stack.isEmpty() || !isValidItem(stack);
            if (empty) return lastUsedSlot;
        }
        return -1;
    }

    private void performRestock() {
        if (mc.player == null || mc.player.getInventory() == null || mc.interactionManager == null) return;
        int targetSlot = slotToRestock;
        if (targetSlot < 0 || targetSlot > 8) return;

        ItemStack currentStack = mc.player.getInventory().getStack(targetSlot);
        if (currentStack != null && isValidItem(currentStack) && !currentStack.isEmpty()) return;

        int itemSlot = findValidItemInInventoryExcludingHotbar(targetSlot);
        if (itemSlot == -1) return;

        moveToHotbar(itemSlot, targetSlot);
    }

    private int findValidItemInInventoryExcludingHotbar(int targetSlot) {
        if (mc.player == null || mc.player.getInventory() == null) return -1;
        List<Integer> candidateSlots = new ArrayList<>();

        for (int i = 9; i < 36; i++) {
            ItemStack stack;
            try {
                stack = mc.player.getInventory().getStack(i);
            } catch (Exception ignored) {
                continue;
            }

            if (stack == null || !isValidItem(stack) || stack.isEmpty()) continue;

            try {
                if (matchesTarget(stack, targetSlot)) {
                    candidateSlots.add(i);
                }
            } catch (Exception ignored) {
            }
        }

        if (candidateSlots.isEmpty()) return -1;

        if (keepOneInInventory.get()) {
            int chosen = candidateSlots.get(0);
            ItemStack chosenStack = mc.player.getInventory().getStack(chosen);
            // Reserve check is always exact-variant, independent of match-colour/match-name,
            // so "keep one" always protects one of the specific item that ran out.
            int exactCount = countExactVariant(targetSlot, chosen);
            if (exactCount <= 1) return -1;
        }

        return candidateSlots.get(0);
    }

    /**
     * Counts stacks (hotbar + main inventory, excluding the chosen slot itself) matching the exact variant of targetSlot's former contents.
     */
    private int countExactVariant(int targetSlot, int excludingSlot) {
        if (mc.player == null || mc.player.getInventory() == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            if (i == excludingSlot) continue;
            try {
                ItemStack stack = mc.player.getInventory().getStack(i);
                if (stack != null && isValidItem(stack) && !stack.isEmpty() && isSameVariant(stack, targetSlot)) {
                    count++;
                }
            } catch (Exception ignored) {
            }
        }
        // +1 for the excluded (chosen) stack itself, since it's still in inventory until the move happens.
        return count + 1;
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
