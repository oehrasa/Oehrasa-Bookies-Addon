/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.mixin;

import com.AutoBookshelf.addon.modules.livemessage.LiveMessage;
import com.AutoBookshelf.addon.modules.livemessage.gui.LivemessageGui;
import meteordevelopment.meteorclient.systems.modules.movement.GUIMove;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Treats the Livemessage GUI like the other locked screens in Meteor's GUIMove
 * (ChatScreen, sign screens, anvil, command block, structure block): while it is open
 * and the addon's restriction says the move must not apply, Meteor's GUIMove sees
 * {@code skip() == true} and leaves the movement and attack keys alone. The rest of
 * Meteor's own predicates are untouched.
 */
@Mixin(GUIMove.class)
public abstract class GUIMoveMixin {
    @Inject(method = "skip", at = @At("HEAD"), cancellable = true)
    private void forceSkipForLivemessageGui(CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = Minecraft.getInstance();
        LiveMessage message = LiveMessage.INSTANCE;
        if (message == null || mc.gui == null) return;
        if (!(mc.gui.screen() instanceof LivemessageGui)) return;
        if (message.guiMoveSkip()) cir.setReturnValue(true);
    }
}
