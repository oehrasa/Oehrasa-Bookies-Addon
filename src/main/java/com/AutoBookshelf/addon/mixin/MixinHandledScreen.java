package com.AutoBookshelf.addon.mixin;

import com.AutoBookshelf.addon.events.ScreenRenderEvent;
import com.AutoBookshelf.addon.modules.InventoryInfo;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HandledScreen.class)
public abstract class MixinHandledScreen extends Screen {
    protected MixinHandledScreen(Text title) {
        super(title);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void onRenderTail(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        MeteorClient.EVENT_BUS.post(ScreenRenderEvent.get(context, MinecraftClient.getInstance().getRenderTickCounter().getTickDelta(true)));
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void click(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (button != 0) return;
        InventoryInfo m = Modules.get().get(InventoryInfo.class);
        if (m == null || !m.isActive()) return;
        m.setClicked(new Vec2f((float) mouseX, (float) mouseY));
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"))
    private void onMouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount, CallbackInfoReturnable<Boolean> cir) {
        InventoryInfo m = Modules.get().get(InventoryInfo.class);
        if (m != null && m.isActive() && verticalAmount != 0) {
            m.setOffset((int) (m.getOffset() + Math.ceil(verticalAmount) * 18));
        }
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        InventoryInfo m = Modules.get().get(InventoryInfo.class);
        if (m != null && m.isActive()) m.onSearchCharTyped(chr);
        return super.charTyped(chr, modifiers);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"))
    private void onKeyPressed(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        InventoryInfo m = Modules.get().get(InventoryInfo.class);
        if (m == null || !m.isActive()) return;
        m.onSearchKeyPressed(keyCode);
    }
}
