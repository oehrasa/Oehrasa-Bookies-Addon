/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.events;

import net.minecraft.world.item.ItemStack;

/**
 * Posted when the server sends the content of one slot of a container. The client applies it, so
 * after this event the client's view of that slot is the server's, not a local prediction.
 * <p>
 * The server sends one of these only when its own slot differs from what the client reported in
 * its click packet, so this event is a <em>correction</em>: it means the client's prediction was
 * wrong, not that the click was applied.
 */
public class ContainerSlotEvent {
    public final int containerId;
    public final int slot;
    public final ItemStack stack;

    private ContainerSlotEvent(int containerId, int slot, ItemStack stack) {
        this.containerId = containerId;
        this.slot = slot;
        this.stack = stack;
    }

    public static ContainerSlotEvent get(int containerId, int slot, ItemStack stack) {
        return new ContainerSlotEvent(containerId, slot, stack);
    }
}
