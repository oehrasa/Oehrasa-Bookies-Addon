/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.utils;

import net.minecraft.core.BlockPos;

/**
 * Cross-version wrapper for the manhattan-bounded cell iterable: 26.3 renamed
 * {@code BlockPos.withinManhattan} to {@code withinBoxByManhattanDistance}.
 */
public final class BlockPosUtil {
    private BlockPosUtil() {
    }

    public static Iterable<BlockPos> withinManhattan(BlockPos pos, int x, int y, int z) {
//? if >=26.3 {
        /*return BlockPos.withinBoxByManhattanDistance(pos, x, y, z);
*///?} else {
        return BlockPos.withinManhattan(pos, x, y, z);
//?}
    }
}
