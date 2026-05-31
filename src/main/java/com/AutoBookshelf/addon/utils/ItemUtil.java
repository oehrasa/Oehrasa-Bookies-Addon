package com.AutoBookshelf.addon.utils;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public class ItemUtil {

    public static ItemStack getItem(Player entity, int index) {
        return switch (index) {
            case 0 -> entity.getMainHandItem();
            case 1 -> entity.getItemBySlot(EquipmentSlot.HEAD);
            case 2 -> entity.getItemBySlot(EquipmentSlot.CHEST);
            case 3 -> entity.getItemBySlot(EquipmentSlot.LEGS);
            case 4 -> entity.getItemBySlot(EquipmentSlot.FEET);
            case 5 -> entity.getOffhandItem();
            default -> ItemStack.EMPTY;
        };
    }
}