package com.AutoBookshelf.addon.events;

import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.client.gui.DrawContext;

public class ScreenRenderEvent {
    private static final ScreenRenderEvent INSTANCE = new ScreenRenderEvent();

    public DrawContext drawContext;
    public double frameTime;
    public float tickDelta;
    public int mouseX;
    public int mouseY;

    public static ScreenRenderEvent get(DrawContext drawContext, float tickDelta, int mouseX, int mouseY) {
        INSTANCE.drawContext = drawContext;
        INSTANCE.frameTime = Utils.frameTime;
        INSTANCE.tickDelta = tickDelta;
        INSTANCE.mouseX = mouseX;
        INSTANCE.mouseY = mouseY;
        return INSTANCE;
    }
}
