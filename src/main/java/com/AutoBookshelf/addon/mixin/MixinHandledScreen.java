package com.AutoBookshelf.addon.mixin;

import com.AutoBookshelf.addon.events.ScreenRenderEvent;
import com.AutoBookshelf.addon.modules.InventoryInfo;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.joml.Vector2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
public abstract class MixinHandledScreen extends Screen {
    protected MixinHandledScreen(Component title) {
        super(title);
    }

    @Inject(method = "extractContents", at = @At("TAIL"))       // render() was renamed
    private void onRenderTail(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        MeteorClient.EVENT_BUS.post(ScreenRenderEvent.get(context, delta));
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void onClick(MouseButtonEvent event, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
        if (event.button() != 0) return;
        InventoryInfo m = Modules.get().get(InventoryInfo.class);
        if (m == null || !m.isActive()) return;
        m.setClicked(new Vector2f((float) event.x(), (float) event.y()));
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"))
    private void onMouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount, CallbackInfoReturnable<Boolean> cir) {
        InventoryInfo m = Modules.get().get(InventoryInfo.class);
        if (m != null && m.isActive()) {
            double amount = Math.abs(verticalAmount) > 0.0 ? verticalAmount : horizontalAmount;
            if (amount != 0) {
                m.setOffset((int) (m.getOffset() + Math.ceil(amount) * 18));
            }
        }
    }

    @Override
    public boolean charTyped(CharacterEvent input) {
        InventoryInfo m = Modules.get().get(InventoryInfo.class);
        if (m != null && m.isActive()) m.onSearchCharTyped((char) input.codepoint());
        return super.charTyped(input);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void onKeyPressed(KeyEvent input, CallbackInfoReturnable<Boolean> cir) {
        InventoryInfo m = Modules.get().get(InventoryInfo.class);
        if (m == null || !m.isActive()) return;

        boolean wasFocused = m.isSearchFocused();
        m.onSearchKeyPressed(input.key());

        if (wasFocused) {
            // Search bar had focus when this key was pressed
            cir.setReturnValue(true);
            cir.cancel();
        }
    }
}
