package com.AutoBookshelf.addon.hud;

import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class ElytraTime extends HudElement {
    public static final HudElementInfo<ElytraTime> INFO = new HudElementInfo<>(
        Addon.HUD_GROUP,
        "Elytra-Time",
        "Gives you a rough estimate of the elytra flight time you have left.",
        ElytraTime::new
    );

    private String displayText = "Efly: 0h 0m 0s Dura: 0";
    private RegistryEntry<net.minecraft.enchantment.Enchantment> unbreakingEntry = null;

    public ElytraTime() {
        super(INFO);
    }

    private RegistryEntry<net.minecraft.enchantment.Enchantment> getUnbreakingEntry() {
        if (unbreakingEntry == null && mc.world != null) {
            var registry = mc.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
            unbreakingEntry = registry.getEntry(Enchantments.UNBREAKING.getValue()).orElse(null);
        }
        return unbreakingEntry;
    }

    private int getUnbreakingMultiplier(ItemStack elytra) {
        if (elytra == null || elytra.isEmpty()) return 1;

        RegistryEntry<net.minecraft.enchantment.Enchantment> entry = getUnbreakingEntry();
        if (entry == null) return 1;

        // Unbreaking level + 1 is the multiplier
        return EnchantmentHelper.getLevel(entry, elytra) + 1;
    }

    private int getTimeRemaining(ItemStack elytra) {
        if (elytra == null || elytra.isEmpty()) return 0;

        int multiplier = getUnbreakingMultiplier(elytra);
        // (maxDamage - currentDamage) * multiplier - 1
        return (elytra.getMaxDamage() - elytra.getDamage()) * multiplier - 1;
    }

    private int getTotalElytraTime() {
        if (mc.player == null) return 0;

        int totalTime = 0;
        int totalRawDurability = 0;
        int unbreakingLevel = 0;

        // Check main inventory
        for (int n = 0; n < mc.player.getInventory().getMainStacks().size(); n++) {
            ItemStack stack = mc.player.getInventory().getStack(n);
            if (stack.getItem() == Items.ELYTRA) {
                totalTime += getTimeRemaining(stack);
                totalRawDurability += (stack.getMaxDamage() - stack.getDamage() - 1);
                unbreakingLevel = Math.max(unbreakingLevel, EnchantmentHelper.getLevel(getUnbreakingEntry(), stack));
            }
        }

        // Check equipped chest slot
        ItemStack chestStack = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (chestStack != null && chestStack.getItem() == Items.ELYTRA) {
            totalTime += getTimeRemaining(chestStack);
            totalRawDurability += (chestStack.getMaxDamage() - chestStack.getDamage() - 1);
            unbreakingLevel = Math.max(unbreakingLevel, EnchantmentHelper.getLevel(getUnbreakingEntry(), chestStack));
        }

        // Each durability point = 4 seconds of flight
        int totalSeconds = totalTime * 4;

        // Store raw durability for display
        this.totalRawDurability = totalRawDurability;
        this.unbreakingLevel = unbreakingLevel;

        return totalSeconds;
    }

    private int totalRawDurability = 0;
    private int unbreakingLevel = 0;

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

        displayText = String.format("Efly: %dh %dm %ds Dura: %d%s", hours, minutes, seconds, totalRawDurability, unbreakingDisplay);
    }

    @Override
    public void render(HudRenderer renderer) {
        calculateElytraTime();
        setSize(renderer.textWidth(displayText, true), renderer.textHeight(true));
        renderer.text(displayText, x, y, Color.WHITE, true);
    }
}
