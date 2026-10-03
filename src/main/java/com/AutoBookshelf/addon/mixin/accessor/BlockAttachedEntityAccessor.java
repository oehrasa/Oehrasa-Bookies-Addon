package com.AutoBookshelf.addon.mixin.accessor;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the block position a hanging entity (item frame, painting, wall sign) is
 * attached to. 1.21.11 had a public getAttachedBlockPos() on ItemFrame; in 26.1.2 the
 * position is only a protected field on BlockAttachedEntity, so reading it needs this.
 */
@Mixin(BlockAttachedEntity.class)
public interface BlockAttachedEntityAccessor {
    @Accessor("pos")
    BlockPos getAttachedBlockPos();
}
