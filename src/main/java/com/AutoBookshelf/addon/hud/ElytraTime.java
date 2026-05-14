package com.AutoBookshelf.addon.hud;

import com.AutoBookshelf.addon.Addon;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Identifier;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class ElytraTime extends HudElement {
    public static final HudElementInfo<ElytraTime> INFO = new HudElementInfo<>(
        Addon.HUD_GROUP,
        "Elytra-Time",
        "Gives you a rough estimate of the elytra flight time you have left.",
        ElytraTime::new
    );

    private String displayText = "Efly: 0h 0m 0s Dura: 0";

    public ElytraTime() {
        super(INFO);
    }

    /**
     * Reads the Unbreaking level directly from the item's enchantments.
     * Falls back to 0 if the item has no enchantments or no Unbreaking.
     */
    private int getUnbreakingLevel(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        // Get the item's enchantments
        ItemEnchantmentsComponent ench = stack.getOrDefault(
            DataComponentTypes.ENCHANTMENTS,
            ItemEnchantmentsComponent.DEFAULT
        );
        // Match by Identifier
        Identifier unbreakingId = Enchantments.UNBREAKING.getValue();
        return ench.getEnchantmentEntries().stream()
            .filter(entry -> entry.getKey().getKey()
                .map(key -> key.getValue().equals(unbreakingId))
                .orElse(false))
            .map(Object2IntMap.Entry::getIntValue)
            .findFirst()
            .orElse(0);
    }

    private int getUnbreakingMultiplier(ItemStack elytra) {
        return getUnbreakingLevel(elytra) + 1;
    }

    private int getTimeRemaining(ItemStack elytra) {
        if (elytra == null || elytra.isEmpty()) return 0;
        int multiplier = getUnbreakingMultiplier(elytra);
        // Durability points times Unbreaking multiplier 1 = effective flight seconds
        return (elytra.getMaxDamage() - elytra.getDamage()) * multiplier - 1;
    }

    private int totalRawDurability = 0;
    private int unbreakingLevel = 0;

    private int getTotalElytraTime() {
        if (mc.player == null) return 0;

        int totalTime = 0;
        totalRawDurability = 0;
        unbreakingLevel = 0;

        // Check main inventory
        for (int n = 0; n < mc.player.getInventory().main.size(); n++) {
            ItemStack stack = mc.player.getInventory().getStack(n);
            if (stack.getItem() == Items.ELYTRA) {
                totalTime += getTimeRemaining(stack);
                totalRawDurability += (stack.getMaxDamage() - stack.getDamage() - 1);
                unbreakingLevel = Math.max(unbreakingLevel, getUnbreakingLevel(stack));
            }
        }

        // Check equipped chest slot
        ItemStack chestStack = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (chestStack != null && chestStack.getItem() == Items.ELYTRA) {
            totalTime += getTimeRemaining(chestStack);
            totalRawDurability += (chestStack.getMaxDamage() - chestStack.getDamage() - 1);
            unbreakingLevel = Math.max(unbreakingLevel, getUnbreakingLevel(chestStack));
        }

        // totalTime is already in effective seconds
        return totalTime;
    }

    private void calculateElytraTime() {
        if (mc.player == null) {
            displayText = "Efly: 0h 0m 0s Dura: 0";
            return;
        }

        int totalSeconds = getTotalElytraTime();
        if (totalSeconds < 0) totalSeconds = 0;

        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;

        String unbreakingDisplay = "";
        if (unbreakingLevel > 0) {
            unbreakingDisplay = String.format(" Unb%d", unbreakingLevel);
        }

        displayText = String.format("Efly: %dh %dm %ds Dura: %d%s",
            hours, minutes, seconds, totalRawDurability, unbreakingDisplay);
    }

    @Override
    public void render(HudRenderer renderer) {
        calculateElytraTime();
        setSize(renderer.textWidth(displayText, true), renderer.textHeight(true));
        renderer.text(displayText, x, y, Color.WHITE, true);
    }
}
