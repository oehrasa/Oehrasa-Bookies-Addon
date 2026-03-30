package com.AutoBookshelf.addon.hud;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class ElytraTime extends HudElement {
    public static final HudElementInfo<ElytraTime> INFO = new HudElementInfo<>(
        Addon.HUD_GROUP, 
        "Elytra Time", 
        "Gives you a rough estimate of the elytra flight time you have left.", 
        ElytraTime::new
    );

    String elytraTimeStringpa = "0h 0m 0s Dura: 0";

    private void calculateElytraTime() {
        // Check if player exists
        if (mc.player == null) {
            elytraTimeStringpa = "0h 0m 0s Dura: 0";
            return;
        }
        
        int totalElytraDurability = 0;

        // Check main inventory
        for (int n = 0; n < mc.player.getInventory().main.size(); n++) {
            ItemStack stack = mc.player.getInventory().getStack(n);
            if (stack.getItem() == Items.ELYTRA) {
                totalElytraDurability += (stack.getMaxDamage() - stack.getDamage() - 1);
            }
        }

        // Check equipped chest slot
        ItemStack chestStack = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (chestStack != null && chestStack.getItem() == Items.ELYTRA) {
            totalElytraDurability += (chestStack.getMaxDamage() - chestStack.getDamage() - 1);
        }

        // Calculate flight time (each durability point = 4 seconds of flight)
        double flightTimeSeconds = totalElytraDurability * 4.0;

        int hours = (int) Math.floor(flightTimeSeconds / 3600);
        int minutes = (int) Math.floor((flightTimeSeconds % 3600) / 60);
        int seconds = (int) flightTimeSeconds % 60;

        elytraTimeStringpa = hours + "h " + minutes + "m " + seconds + "s  Dura: " + totalElytraDurability;
    }

    public ElytraTime() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        calculateElytraTime();

        String displayText = "Efly: " + elytraTimeStringpa;
        setSize(renderer.textWidth(displayText, true), renderer.textHeight(true));
        renderer.text(displayText, x, y, Color.WHITE, true);
    }
}