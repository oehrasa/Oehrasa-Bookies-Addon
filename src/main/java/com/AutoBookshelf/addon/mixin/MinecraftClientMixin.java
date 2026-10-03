package com.AutoBookshelf.addon.mixin;

import com.AutoBookshelf.addon.modules.ForceAccess;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.WallBannerBlock;
import net.minecraft.block.WallSignBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.LockableContainerBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {

    @Shadow
    public HitResult crosshairTarget;
    @Shadow
    public ClientPlayerEntity player;
    @Shadow
    public ClientWorld world;

    @Inject(method = "doItemUse", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerEntity;getStackInHand(Lnet/minecraft/util/Hand;)Lnet/minecraft/item/ItemStack;"))
    public void switchCrosshairTarget(CallbackInfo ci) {
        ForceAccess module = Modules.get().get(ForceAccess.class);
        if (module == null || !module.isActive()) return;
        if (!module.isAutoHidden() || !module.isBypassInteractiveNearContainer()) return;
        if (crosshairTarget == null) return;
        if (player == null || world == null) return;

        if (crosshairTarget.getType() == HitResult.Type.ENTITY) {
            EntityHitResult entityHit = (EntityHitResult) crosshairTarget;
            if (entityHit.getEntity() instanceof ItemFrameEntity frame) {
                BlockPos attachedPos = frame.getAttachedBlockPos().offset(frame.getHorizontalFacing().getOpposite());
                if (!player.isSneaking() && isContainerAt(attachedPos)) {
                    this.crosshairTarget = new BlockHitResult(crosshairTarget.getPos(), frame.getHorizontalFacing(), attachedPos, false);
                }
            }
        } else if (crosshairTarget.getType() == HitResult.Type.BLOCK) {
            BlockHitResult blockHit = (BlockHitResult) crosshairTarget;
            BlockPos blockPos = blockHit.getBlockPos();
            BlockState state = world.getBlockState(blockPos);
            Block block = state.getBlock();

            if (block instanceof WallSignBlock sign) {
                BlockPos attachedPos = blockPos.offset(state.get(sign.FACING).getOpposite());
                if (isContainerAt(attachedPos) && !player.isSneaking()) {
                    this.crosshairTarget = new BlockHitResult(crosshairTarget.getPos(), blockHit.getSide(), attachedPos, false);
                }
            } else if (block instanceof WallBannerBlock banner) {
                BlockPos attachedPos = blockPos.offset(state.get(banner.FACING).getOpposite());
                if (isContainerAt(attachedPos) && !player.isSneaking()) {
                    this.crosshairTarget = new BlockHitResult(crosshairTarget.getPos(), blockHit.getSide(), attachedPos, false);
                }
            }
        }
    }

    private boolean isContainerAt(BlockPos pos) {
        if (world == null) return false;
        BlockEntity entity = world.getBlockEntity(pos);
        return entity instanceof LockableContainerBlockEntity;
    }
}
