package com.AutoBookshelf.addon.mixin.accessor;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.prediction.PredictiveAction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes MultiPlayerGameMode#startPrediction, which is private in 26.1.2. It is the
 * 26.1.2 replacement for 1.21.11's sendSequencedPacket: the sequence number used to be
 * handed out by an Invoker on the interaction manager, and now only this private
 * prediction helper can mint one. Meteor's own MultiPlayerGameModeMixin also shadows it
 * public at runtime, but that shadow is not on our compile classpath, so an Invoker here
 * is what lets the addon call it (and is what InstantRebreak does upstream).
 */
@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeAccessor {
    @Invoker("startPrediction")
    void invokeStartPrediction(ClientLevel level, PredictiveAction action);
}
