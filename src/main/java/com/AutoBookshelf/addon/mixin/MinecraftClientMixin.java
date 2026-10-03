package com.AutoBookshelf.addon.mixin;

import com.AutoBookshelf.addon.mixin.accessor.BlockAttachedEntityAccessor;
import com.AutoBookshelf.addon.modules.ForceAccess;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftClientMixin {

    @Shadow
    public HitResult hitResult;
    @Shadow
    public LocalPlayer player;
    @Shadow
    public ClientLevel level;

    // startUseItem is 26.1.2's doItemUse. Injected at HEAD rather than at the hand-stack read
    // (what 1.21.11 anchored on): hitResult is only consumed further down, so swapping it here
    // is equivalent and does not couple the mixin to an internal INVOKE owner that moves
    // whenever the item-use path is refactored. This mixin has defaultRequire: 1, so a
    // mistargeted INVOKE would be a hard client crash.
    @Inject(method = "startUseItem", at = @At("HEAD"))
    public void switchCrosshairTarget(CallbackInfo ci) {
        ForceAccess module = Modules.get().get(ForceAccess.class);
        if (module == null || !module.isActive()) return;
        if (!module.isAutoHidden() || !module.isBypassInteractiveNearContainer()) return;
        if (hitResult == null) return;
        if (player == null || level == null) return;

        if (hitResult.getType() == HitResult.Type.ENTITY) {
            EntityHitResult entityHit = (EntityHitResult) hitResult;
            if (entityHit.getEntity() instanceof ItemFrame frame) {
                BlockPos attachedPos = ((BlockAttachedEntityAccessor) frame).getAttachedBlockPos()
                    .relative(frame.getDirection().getOpposite());
                if (!player.isShiftKeyDown() && isContainerAt(attachedPos)) {
                    this.hitResult = new BlockHitResult(hitResult.getLocation(), frame.getDirection(), attachedPos, false);
                }
            }
        } else if (hitResult.getType() == HitResult.Type.BLOCK) {
            BlockHitResult blockHit = (BlockHitResult) hitResult;
            BlockPos blockPos = blockHit.getBlockPos();
            BlockState state = level.getBlockState(blockPos);
            Block block = state.getBlock();

            if (block instanceof WallSignBlock sign) {
                BlockPos attachedPos = blockPos.relative(state.getValue(sign.FACING).getOpposite());
                if (isContainerAt(attachedPos) && !player.isShiftKeyDown()) {
                    this.hitResult = new BlockHitResult(hitResult.getLocation(), blockHit.getDirection(), attachedPos, false);
                }
            } else if (block instanceof WallBannerBlock banner) {
                BlockPos attachedPos = blockPos.relative(state.getValue(banner.FACING).getOpposite());
                if (isContainerAt(attachedPos) && !player.isShiftKeyDown()) {
                    this.hitResult = new BlockHitResult(hitResult.getLocation(), blockHit.getDirection(), attachedPos, false);
                }
            }
        }
    }

    private boolean isContainerAt(BlockPos pos) {
        if (level == null) return false;
        BlockEntity entity = level.getBlockEntity(pos);
        return entity instanceof ChestBlockEntity || entity instanceof EnderChestBlockEntity;
    }
}
