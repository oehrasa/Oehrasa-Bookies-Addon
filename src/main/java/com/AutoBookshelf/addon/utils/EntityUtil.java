/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.utils;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;

/**
 * Cross-version swing helper. 26.3 removed the one- and two-argument {@code swing} overloads in
 * favour of {@code swing(hand, SwingAnimation, boolean)}; on older nodes the same default animation
 * plays via the one-argument form.
 */
public final class EntityUtil {
    private EntityUtil() {
    }

    public static void swing(LivingEntity entity, InteractionHand hand) {
//? if >=26.3 {
        /*entity.swing(hand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
*///?} else {
        entity.swing(hand);
//?}
    }
}
