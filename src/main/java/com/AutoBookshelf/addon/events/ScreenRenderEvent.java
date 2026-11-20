/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only AND MIT AND Apache-2.0
 * Portions adapted from PathSeeker by FaxHack (https://github.com/FaxHack/PathSeeker),
 * a Meteor port of shulker-view by cattyn (https://github.com/cattyngmd/shulker-view).
 * See third-party/licenses and THIRD_PARTY_NOTICES.md.
 */
package com.AutoBookshelf.addon.events;

import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public class ScreenRenderEvent {
    private static final ScreenRenderEvent INSTANCE = new ScreenRenderEvent();

    //? if >=26.1 {
    public GuiGraphicsExtractor graphics;
    //?} else {
    /*public GuiGraphicsExtractor drawContext; // matches the 1.21.x Render2DEvent field name
     *///?}
    public double frameTime;
    public float tickDelta;
    public int mouseX;
    public int mouseY;

    public static ScreenRenderEvent get(GuiGraphicsExtractor graphics, float tickDelta, int mouseX, int mouseY) {
        //? if >=26.1 {
        INSTANCE.graphics = graphics;
        //?} else {
        /*INSTANCE.drawContext = graphics;
         *///?}
        INSTANCE.frameTime = Utils.frameTime;
        INSTANCE.tickDelta = tickDelta;
        INSTANCE.mouseX = mouseX;
        INSTANCE.mouseY = mouseY;
        return INSTANCE;
    }
}
