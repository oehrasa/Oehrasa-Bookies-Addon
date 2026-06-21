package com.AutoBookshelf.addon.utils;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public class JoinPayload implements CustomPayload {
    public static final Identifier ID = Identifier.of("anarchymod", "join");

    public static final PacketCodec<PacketByteBuf, JoinPayload> CODEC =
        PacketCodec.of(
            (payload, buf) -> {
            },
            buf -> new JoinPayload()
        );

    public static final Id<JoinPayload> TYPE = new Id<>(ID);

    @Override
    public Id<? extends CustomPayload> getId() {
        return TYPE;
    }
}
