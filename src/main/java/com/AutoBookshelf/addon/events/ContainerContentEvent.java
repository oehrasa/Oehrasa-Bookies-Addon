/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.events;

/**
 * Posted when the server sends the content of a container. The client applies it, so after this
 * event the client's view of that container is the server's, not a local prediction.
 */
public class ContainerContentEvent {
    public final int containerId;

    private ContainerContentEvent(int containerId) {
        this.containerId = containerId;
    }

    public static ContainerContentEvent get(int containerId) {
        return new ContainerContentEvent(containerId);
    }
}
