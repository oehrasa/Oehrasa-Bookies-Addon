package com.AutoBookshelf.addon.utils;

import net.minecraft.util.Mth;


public class FadeAnimator {
    private float alpha;
    private long lastNanos;

    public void update(boolean targetVisible, double durationSeconds, boolean enabled) {
        long now = System.nanoTime();
        float dt = this.lastNanos == 0L ? 0.0F : (float) (now - this.lastNanos) / 1.0E9F;
        this.lastNanos = now;
        dt = Mth.clamp(dt, 0.0F, 0.1F);
        if (!enabled) {
            this.alpha = targetVisible ? 1.0F : 0.0F;
        } else {
            float target = targetVisible ? 1.0F : 0.0F;
            float step = durationSeconds <= 0.0 ? 1.0F : (float) (dt / durationSeconds);
            if (this.alpha < target) {
                this.alpha = Math.min(target, this.alpha + step);
            } else if (this.alpha > target) {
                this.alpha = Math.max(target, this.alpha - step);
            }
        }
    }

    public float alpha() {
        return this.alpha;
    }

    public boolean rendering() {
        return this.alpha > 0.001F;
    }

    public int apply(int argb) {
        int a = argb >>> 24 & 0xFF;
        if (a == 0) {
            a = 255;
        }

        a = Mth.clamp(Math.round(a * this.alpha), 0, 255);
        return a << 24 | argb & 16777215;
    }
}
