/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.mixin.accessor;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerboundMovePlayerPacket.class)
public interface PlayerMoveC2SPacketAccessor {
    @Mutable
    @Accessor("x")
    void oehrasa$setX(double x);

    @Mutable
    @Accessor("y")
    void oehrasa$setY(double y);

    @Mutable
    @Accessor("z")
    void oehrasa$setZ(double z);

    @Mutable
    @Accessor("yRot")
    void oehrasa$setYaw(float yaw);

    @Mutable
    @Accessor("xRot")
    void oehrasa$setPitch(float pitch);

    @Mutable
    @Accessor("onGround")
    void oehrasa$setOnGround(boolean onGround);

    @Mutable
    @Accessor("horizontalCollision")
    void oehrasa$setHorizontalCollision(boolean horizontalCollision);
}
