package com.AutoBookshelf.addon.utils;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;

public class ItemUtil {

    public static ItemStack getItem(PlayerEntity entity, int index) {
        return switch (index) {
            case 0 -> entity.getMainHandStack();
            case 1 -> entity.getEquippedStack(EquipmentSlot.HEAD);
            case 2 -> entity.getEquippedStack(EquipmentSlot.CHEST);
            case 3 -> entity.getEquippedStack(EquipmentSlot.LEGS);
            case 4 -> entity.getEquippedStack(EquipmentSlot.FEET);
            case 5 -> entity.getOffHandStack();
            default -> ItemStack.EMPTY;
        };
    }
}