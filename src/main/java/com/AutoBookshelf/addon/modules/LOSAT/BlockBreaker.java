/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.LOSAT;

import com.AutoBookshelf.addon.utils.EntityUtil;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.core.BlockPos;
//? if >=26.3 {
/*import net.minecraft.world.item.component.SwingAnimation;
*///?} else {
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
//?}
import net.minecraft.world.InteractionHand;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class BlockBreaker {
    private static boolean breaking;
    private static boolean breakingThisTick;

    private BlockBreaker() {
    }

    @EventHandler(priority = EventPriority.HIGHEST + 100)
    private static void onTickPre(TickEvent.Pre event) {
        breakingThisTick = false;
    }

    @EventHandler(priority = EventPriority.LOWEST - 100)
    private static void onTickPost(TickEvent.Post event) {
        if (!breakingThisTick && breaking) {
            breaking = false;
            if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        }
    }

    public static boolean breakBlock(BlockPos blockPos, boolean swing) {
        if (!BlockUtils.canBreak(blockPos, mc.level.getBlockState(blockPos))) return false;

        BlockPos pos = blockPos instanceof BlockPos.MutableBlockPos ? blockPos.immutable() : blockPos;

        if (mc.gameMode.isDestroying())
            mc.gameMode.continueDestroyBlock(pos, BlockUtils.getDirection(blockPos));
        else mc.gameMode.startDestroyBlock(pos, BlockUtils.getDirection(blockPos));

//? if >=26.3 {
        /*if (swing) mc.player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
*///?} else {
        if (swing) EntityUtil.swing(mc.player, InteractionHand.MAIN_HAND);
//?}
//? if >=26.3 {
        /*else mc.player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
*///?} else {
        else mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
//?}

        breaking = true;
        breakingThisTick = true;

        return true;
    }
}
