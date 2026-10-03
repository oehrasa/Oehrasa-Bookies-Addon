package com.AutoBookshelf.addon.events;

import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public class ScreenRenderEvent {
    private static final ScreenRenderEvent INSTANCE = new ScreenRenderEvent();

    public GuiGraphicsExtractor graphics;   // was DrawContext / GuiGraphics
    public double frameTime;
    public float tickDelta;
    public int mouseX;
    public int mouseY;

    public static ScreenRenderEvent get(GuiGraphicsExtractor graphics, float tickDelta, int mouseX, int mouseY) {
        INSTANCE.graphics = graphics;
        INSTANCE.frameTime = Utils.frameTime;
        INSTANCE.tickDelta = tickDelta;
        INSTANCE.mouseX = mouseX;
        INSTANCE.mouseY = mouseY;
        return INSTANCE;
    }
}
