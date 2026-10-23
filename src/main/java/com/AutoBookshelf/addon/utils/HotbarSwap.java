package com.AutoBookshelf.addon.utils;

import meteordevelopment.meteorclient.utils.player.SlotUtils;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;

import java.util.function.Predicate;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Hotbar provisioning shared by the modules that need a specific item in hand.
 * <p>
 * Follows MinecartPlacer's swap handling: click the player's own inventoryMenu (so an open screen
 * makes the container id mismatch and the click is ignored), convert the index through
 * SlotUtils.indexToId, and prefer an empty hotbar slot.
 */
public final class HotbarSwap {
    private HotbarSwap() {
    }

    /**
     * Hotbar index (0-8) holding a matching stack, or -1.
     */
    public static int findInHotbar(Predicate<ItemStack> matches) {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (matches.test(mc.player.getInventory().getStack(i))) return i;
        }
        return -1;
    }

    /**
     * Main-inventory index (9-35) holding a matching stack, or -1.
     */
    public static int findInMainInventory(Predicate<ItemStack> matches) {
        if (mc.player == null) return -1;
        for (int i = 9; i < 36; i++) {
            if (matches.test(mc.player.getInventory().getStack(i))) return i;
        }
        return -1;
    }

    /**
     * First empty hotbar index (0-8), or -1 when every hotbar slot is occupied.
     */
    public static int findEmptyHotbarSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) return i;
        }
        return -1;
    }

    /**
     * Exchanges the stack at inventory index {@code invIndex} (0-35) with hotbar slot
     * {@code hotbarSlot} (0-8) using a container SWAP against the player's own inventory menu.
     * A screen being open makes the menu id mismatch and the click is ignored that is the point:
     * never swap into whatever container happens to be open.
     */
    public static void swapIntoHotbar(int invIndex, int hotbarSlot) {
        if (mc.player == null || mc.interactionManager == null) return;
        mc.interactionManager.clickSlot(
            mc.player.playerScreenHandler.syncId,
            SlotUtils.indexToId(invIndex),
            hotbarSlot,
            SlotActionType.SWAP,
            mc.player);
    }

    /**
     * Returns a hotbar slot holding a matching stack, pulling one in from the main inventory when it
     * is only there. An empty hotbar slot is preferred; when the hotbar is full
     * {@code preferredHotbarSlot} (0-8, or -1) is reused so a repeated pull replaces the item the
     * caller last put there instead of clobbering slot 0 every time. Returns -1 if the item is
     * nowhere, or if the swap did not take effect (a screen is open).
     */
    public static int ensureInHotbar(Predicate<ItemStack> matches, int preferredHotbarSlot) {
        if (mc.player == null) return -1;

        int hotbar = findInHotbar(matches);
        if (hotbar != -1) return hotbar;

        int invIndex = findInMainInventory(matches);
        if (invIndex == -1) return -1;

        boolean hasPreferred = preferredHotbarSlot >= 0 && preferredHotbarSlot < 9;
        int dest = -1;
        if (hasPreferred && mc.player.getInventory().getStack(preferredHotbarSlot).isEmpty()) {
            dest = preferredHotbarSlot;
        }
        if (dest == -1) dest = findEmptyHotbarSlot();
        if (dest == -1) dest = hasPreferred ? preferredHotbarSlot : 0;

        swapIntoHotbar(invIndex, dest);

        // The client applies its own container clicks, so the swap is visible immediately; if it
        // is not (mismatched container id, rejected click) report failure rather than hand back a
        // slot that still holds the old item.
        return matches.test(mc.player.getInventory().getStack(dest)) ? dest : -1;
    }
}
