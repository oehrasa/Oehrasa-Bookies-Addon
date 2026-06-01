package com.AutoBookshelf.addon.mixin;

import com.AutoBookshelf.addon.interfaces.IClientPlayerInteractionManager;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = MultiPlayerGameMode.class, priority = 1002)
public abstract class ClientPlayerInteractionManagerMixin implements IClientPlayerInteractionManager {
    @Shadow
    public abstract void handleContainerInput(int syncId, int slotId, int data, ContainerInput input, Player player);

    @Override
    public void clickSlot(int syncId, int slotId, int data, ContainerInput actionType, Player player) {
        handleContainerInput(syncId, slotId, data, actionType, player);
    }
}
