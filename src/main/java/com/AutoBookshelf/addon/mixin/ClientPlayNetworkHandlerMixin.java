/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.mixin;

import com.AutoBookshelf.addon.events.ContainerContentEvent;
import com.AutoBookshelf.addon.events.ContainerSlotEvent;
import com.AutoBookshelf.addon.events.PlayerPositionLookEvent;
import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPlayNetworkHandlerMixin {
    @Inject(method = "handleMovePlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V", shift = At.Shift.AFTER))
    private void quiettee$onPlayerPositionLookHead(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if ((Object) this != client.getConnection()) return;
        MeteorClient.EVENT_BUS.post(new PlayerPositionLookEvent.Before(packet));
    }

    @Inject(method = "handleMovePlayer", at = @At("RETURN"))
    private void quiettee$onPlayerPositionLookReturn(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if ((Object) this != client.getConnection()) return;
        MeteorClient.EVENT_BUS.post(new PlayerPositionLookEvent.After(packet));
    }

    /**
     * The server answers a container click with the content of that container. Once the client has
     * applied it, the client's view is the server's and not a local prediction, which is what
     * makes a container swap verifiable.
     */
    @Inject(method = "handleContainerContent", at = @At("TAIL"))
    private void quiettee$onContainerContent(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if ((Object) this != client.getConnection()) return;
        MeteorClient.EVENT_BUS.post(ContainerContentEvent.get(packet.containerId()));
    }

    /**
     * The server also answers a click with a single-slot update when that is all that changed, and
     * that is the *usual* answer: the full content above is only sent when the client's container
     * state was already out of sync. The client applies this packet to the same slot, so it makes
     * the swap verifiable too.
     */
    @Inject(method = "handleContainerSetSlot", at = @At("TAIL"))
    private void quiettee$onContainerSetSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if ((Object) this != client.getConnection()) return;
        MeteorClient.EVENT_BUS.post(ContainerSlotEvent.get(
            packet.getContainerId(), packet.getSlot(), packet.getItem()));
    }
}
