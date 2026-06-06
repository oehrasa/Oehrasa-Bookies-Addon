package com.AutoBookshelf.addon.mixin;

import com.AutoBookshelf.addon.events.ScreenRenderEvent;
import com.AutoBookshelf.addon.modules.InventoryInfo;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.joml.Vector2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HandledScreen.class)
public abstract class MixinHandledScreen {

    @Inject(method = "render", at = @At("TAIL"))
    private void onRenderTail(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        MeteorClient.EVENT_BUS.post(ScreenRenderEvent.get(context, delta));
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void onClick(Click click, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
        if (click.button() != 0) return;
        InventoryInfo m = Modules.get().get(InventoryInfo.class);
        if (m == null || !m.isActive()) return;
        m.setClicked(new Vector2f((float) click.x(), (float) click.y()));
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"))
    private void onMouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount, CallbackInfoReturnable<Boolean> cir) {
        InventoryInfo m = Modules.get().get(InventoryInfo.class);
        if (m != null && m.isActive() && verticalAmount != 0) {
            m.setOffset((int) (m.getOffset() + Math.ceil(verticalAmount) * 18));
        }
    }
}
