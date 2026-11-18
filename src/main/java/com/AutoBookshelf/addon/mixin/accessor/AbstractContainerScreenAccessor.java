/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.mixin.accessor;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the slot the cursor is hovering in a container screen (e.g. for MapartSort's anchor).
 */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
    @Accessor("hoveredSlot")
    Slot getHoveredSlot();

    @Accessor("leftPos")
    int getLeftPos();

    @Accessor("topPos")
    int getTopPos();
}
