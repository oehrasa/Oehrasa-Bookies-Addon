package com.AutoBookshelf.addon.interfaces;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;

public interface IClientPlayerInteractionManager {
    void setBlockBreakingCooldown(int cooldown);

    float getCurrentBreakingProgress();

    void clickSlot(int syncId, int slotId, int data, ContainerInput actionType, Player player);
}
