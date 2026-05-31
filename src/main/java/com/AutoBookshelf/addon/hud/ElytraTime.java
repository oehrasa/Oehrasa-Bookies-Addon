package com.AutoBookshelf.addon.hud;

import com.AutoBookshelf.addon.Addon;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

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
        ItemEnchantments ench = stack.getOrDefault(
            DataComponents.ENCHANTMENTS,
            ItemEnchantments.EMPTY
        );
        Identifier unbreakingId = Enchantments.UNBREAKING.identifier();
        return ench.entrySet().stream()
            .filter(entry -> entry.getKey().unwrapKey()
                .map(key -> key.identifier().equals(unbreakingId))
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
        return (elytra.getMaxDamage() - elytra.getDamageValue()) * multiplier - 1;
    }

    private int totalRawDurability = 0;
    private int unbreakingLevel = 0;

    private int getTotalElytraTime() {
        if (mc.player == null) return 0;

        int totalTime = 0;
        totalRawDurability = 0;
        unbreakingLevel = 0;

        for (int n = 0; n < mc.player.getInventory().getNonEquipmentItems().size(); n++) {
            ItemStack stack = mc.player.getInventory().getNonEquipmentItems().get(n);
            if (stack.getItem() == Items.ELYTRA) {
                totalTime += getTimeRemaining(stack);
                totalRawDurability += (stack.getMaxDamage() - stack.getDamageValue() - 1);
                unbreakingLevel = Math.max(unbreakingLevel, getUnbreakingLevel(stack));
            }
        }

        // Check equipped chest slot
        ItemStack chestStack = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (chestStack != null && chestStack.getItem() == Items.ELYTRA) {
            totalTime += getTimeRemaining(chestStack);
            totalRawDurability += (chestStack.getMaxDamage() - chestStack.getDamageValue() - 1);
            unbreakingLevel = Math.max(unbreakingLevel, getUnbreakingLevel(chestStack));
        }

        // totalTime already holds effective flight seconds
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
