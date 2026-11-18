// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.mixin.accessor;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.AbstractMountInventoryMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The client builds mount screens around the real entity, so horse/donkey/llama screens name their owner.
 */
@Mixin(AbstractMountInventoryMenu.class)
public interface MountScreenHandlerAccessor {
    @Accessor("mount")
    LivingEntity entitytracker$getMount();
}
