package com.AutoBookshelf.addon.modules.livemessage.gui;

import com.AutoBookshelf.addon.modules.livemessage.util.LivemessageUtil;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.io.File;
import java.io.FileReader;
import java.util.UUID;

public class GuiUtil {

    public static int hslColor(float h, float s, float l) {
        float r;
        float g;
        float b;
        if (s == 0.0F) {
            b = l;
            g = l;
            r = l;
        } else {
            float q = l < 0.5 ? l * (1.0F + s) : l + s - l * s;
            float p = 2.0F * l - q;
            r = hue2rgb(p, q, h + 0.33333334F);
            g = hue2rgb(p, q, h);
            b = hue2rgb(p, q, h - 0.33333334F);
        }

        return getRGB(Math.round(r * 255.0F), Math.round(g * 255.0F), Math.round(b * 255.0F));
    }

    private static float hue2rgb(float p, float q, float h) {
        if (h < 0.0F) {
            h++;
        }

        if (h > 1.0F) {
            h--;
        }

        if (6.0F * h < 1.0F) {
            return p + (q - p) * 6.0F * h;
        } else if (2.0F * h < 1.0F) {
            return q;
        } else {
            return 3.0F * h < 2.0F ? p + (q - p) * 6.0F * (0.6666667F - h) : p;
        }
    }

    public static int getRGB(int r, int g, int b) {
        return getRGBA(r, g, b, 255);
    }

    public static int getSingleRGB(int color) {
        return getRGBA(color, color, color, 255);
    }

    public static int getRGBA(int r, int g, int b, int a) {
        return a << 24 | r << 16 | g << 8 | b;
    }

    public static int getWindowColor(UUID uuid) {
        try {
            File settingsFile = LivemessageUtil.LIVEMESSAGE_FOLDER.resolve("mainwindow.json").toFile();
            if (settingsFile.exists()) {
                Gson gson = new Gson();
                JsonObject json = gson.fromJson(new FileReader(settingsFile), JsonObject.class);
                if (json.has("customColor")) {
                    int mainWindowColor = json.get("customColor").getAsInt();
                    if (mainWindowColor > 0) {
                        return mainWindowColor;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        float uuidvalue1 = Integer.parseInt(uuid.toString().substring(0, 2), 16) / 255.0F;
        float uuidvalue2 = Integer.parseInt(uuid.toString().substring(2, 4), 16) / 255.0F;
        return hslColor(uuidvalue1, 0.6F + uuidvalue2 * 0.15F, 0.5F) | 0xFF000000;
    }

    public static double easeOutQuint(double t) {
        return 1.0 - Math.pow(1.0 - t, 5.0);
    }

    public static int fade(int argb) {
        float f = LivemessageGui.currentFadeAlpha;
        if (f >= 0.999F) {
            return argb;
        }

        int a = argb >>> 24 & 0xFF;
        if (a == 0) {
            a = 255;
        }

        a = Math.max(0, Math.min(255, Math.round(a * f)));
        return a << 24 | argb & 16777215;
    }

    public static void drawRect(DrawContext context, int x, int y, int w, int h, int color) {
        context.fill(x, y, x + w, y + h, fade(color));
    }

    public static void drawTooltip(DrawContext context, String text, int x, int y) {
        MinecraftClient mc = MinecraftClient.getInstance();
        int width = mc.textRenderer.getWidth(text) + 8;
        int height = 12;
        context.fill(x, y, x + width, y + height, fade(getRGBA(0, 0, 0, 200)));
        context.fill(x, y, x + width, y + 1, fade(getRGBA(80, 80, 255, 255)));
        context.fill(x, y + height - 1, x + width, y + height, fade(getRGBA(80, 80, 255, 255)));
        context.fill(x, y, x + 1, y + height, fade(getRGBA(80, 80, 255, 255)));
        context.fill(x + width - 1, y, x + width, y + height, fade(getRGBA(80, 80, 255, 255)));
        context.drawText(mc.textRenderer, text, x + 4, y + 2, fade(getSingleRGB(255)), false);
    }

    public static class QuintAnimation {
        public long startTime = 0L;
        public float startValue = 0.0F;
        public float currentValue = 0.0F;
        public int animationLength = 1000;

        public float animate(float val) {
            if (val != this.currentValue) {
                this.startValue = this.getState();
                this.currentValue = val;
                this.startTime = System.currentTimeMillis();
            }

            return this.getState();
        }

        public float getState() {
            if (System.currentTimeMillis() - this.startTime > this.animationLength) {
                return this.currentValue;
            }

            double progress = (double) (System.currentTimeMillis() - this.startTime) / this.animationLength;
            return (float) (this.startValue - GuiUtil.easeOutQuint(progress) * (this.startValue - this.currentValue));
        }

        public QuintAnimation(int len, float initialValue) {
            this.animationLength = len;
            this.startValue = initialValue;
            this.currentValue = initialValue;
        }
    }
}
