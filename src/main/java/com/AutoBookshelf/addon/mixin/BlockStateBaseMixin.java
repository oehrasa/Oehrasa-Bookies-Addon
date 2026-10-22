package com.AutoBookshelf.addon.mixin;

import com.AutoBookshelf.addon.modules.HandyPlace;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes skinny blocks (glass panes, iron/copper bars, fences) report a full-cube outline shape
 * while the player holds one and HandyPlace's skinny-full mode is on.
 * <p>
 * Why a mixin is required: HandyPlace's existing interception only rewrites the BlockHitResult
 * *after* the crosshair raycast has already resolved against the real (thin) shape, so it cannot
 * change what the crosshair hits. Verified on 26.2: Entity#pick builds a ClipContext with
 * {@code ClipContext.Block.OUTLINE} and hands it to Level#clip, which resolves the outline shape
 * through these BlockStateBase methods. Faking the shape here is what makes the block behave like
 * a full cube for aiming, which is the same technique easypane-1.21.5 uses, except easypane
 * returns Shapes.empty() to see *through* the block, whereas we return Shapes.block() so the block
 * is aimable across its whole extent.
 * <p>
 * Only the 3-argument {@code getShape} overload is touched. The 2-argument
 * {@code getShape(BlockGetter, BlockPos)} delegates to the 3-argument method with
 * {@code CollisionContext.empty()}, so it is also affected by this injection. The visual shape
 * ({@code getVisualShape}) is not touched. it is used by ClipContext.Block.VISUAL for third-person
 * camera zoom clipping, and faking it there would make the camera collide with full cubes at every
 * skinny block while held. The collision shape is never involved, so entity movement and physics
 * are unchanged.
 */
@Mixin(value = BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin {
    /**
     * Resolved from the target class at merge time; this mixin does not extend it.
     */
    @Shadow
    public abstract BlockState asState();

    @Inject(
        method = "getShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void handyPlace$fullBlockOutline(BlockGetter level, BlockPos pos, CollisionContext ctx,
                                             CallbackInfoReturnable<VoxelShape> cir) {
        // getShape(ctx) is on a much hotter path than just picking - vanilla calls it from
        // propagatesSkylightDown during Blocks.<clinit> and on every light update - so the gate in
        // HandyPlace must stay cheap and must never touch Meteor state that does not exist yet.
        if (shouldOverride()) cir.setReturnValue(Shapes.block());
    }

    /**
     * Never lets this hook throw into vanilla.
     */
    @Unique
    private boolean shouldOverride() {
        try {
            // Gate on the queried block itself: only skinny blocks ever report a full cube.
            // The old gate (treatment active for ANY block) faked the outline shape of every
            // block in the world while a skinny block was held, which broke placement raycasts.
            return HandyPlace.skinnyTreatmentActive() && HandyPlace.isSkinny(this.asState().getBlock());
        } catch (Throwable t) {
            return false;
        }
    }
}
