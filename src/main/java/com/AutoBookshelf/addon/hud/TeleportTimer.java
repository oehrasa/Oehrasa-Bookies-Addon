package com.AutoBookshelf.addon.hud;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class TeleportTimer extends HudElement {
    public static final HudElementInfo<TeleportTimer> INFO = new HudElementInfo<>(
        Addon.HUD_GROUP,
        "Teleport-Timer",
        "Shows a countdown bar on pending teleportation.",
        TeleportTimer::new
    );

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<SettingColor> startColor = sgGeneral.add(new ColorSetting.Builder()
        .name("start-color")
        .description("Colour at the beginning of the countdown.")
        .defaultValue(new SettingColor(0, 255, 0, 200))
        .build()
    );

    private final Setting<SettingColor> midColor = sgGeneral.add(new ColorSetting.Builder()
        .name("mid-color")
        .description("Colour in the middle of the countdown.")
        .defaultValue(new SettingColor(255, 255, 0, 200))
        .build()
    );

    private final Setting<SettingColor> endColor = sgGeneral.add(new ColorSetting.Builder()
        .name("end-color")
        .description("Colour at the end of the countdown.")
        .defaultValue(new SettingColor(255, 0, 0, 200))
        .build()
    );

    private final Setting<Double> barHeight = sgGeneral.add(new DoubleSetting.Builder()
        .name("bar-height")
        .description("Height of the countdown bar.")
        .defaultValue(10)
        .min(4)
        .sliderRange(4, 30)
        .build()
    );

    private int ticksRemaining = 0;
    private int totalTicks = 0;
    private static final Pattern SECONDS_PATTERN = Pattern.compile("Teleporting.*?\\b(\\d+)\\s*seconds?");

    public TeleportTimer() {
        super(INFO);
        MeteorClient.EVENT_BUS.subscribe(this);
    }

    @Override
    public void remove() {
        super.remove();
        MeteorClient.EVENT_BUS.unsubscribe(this);
    }

    @EventHandler
    private void onMessageReceive(ReceiveMessageEvent event) {
        if (mc.player == null || mc.world == null) return;
        String message = event.getMessage().getString();

        Matcher matcher = SECONDS_PATTERN.matcher(message);
        if (matcher.find()) {
            int seconds = Integer.parseInt(matcher.group(1));
            ticksRemaining = seconds * 20;
            totalTicks = ticksRemaining;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (ticksRemaining > 0) {
            ticksRemaining--;
        }
    }

    @Override
    public void render(HudRenderer renderer) {
        if (ticksRemaining <= 0 || totalTicks == 0) return;

        double fraction = (double) ticksRemaining / totalTicks;
        Color colour;

        if (fraction > 0.5) {
            double t = (fraction - 0.5) * 2.0;
            colour = lerpColor(midColor.get(), startColor.get(), t);
        } else {
            double t = fraction * 2.0;
            colour = lerpColor(endColor.get(), midColor.get(), t);
        }

        // Background
        renderer.quad(x, y, getWidth(), barHeight.get(), new SettingColor(0, 0, 0, 100));

        // Filled bar
        double filledWidth = getWidth() * fraction;
        renderer.quad(x, y, filledWidth, barHeight.get(), colour);

        // Time text
        String text = String.format("%.1fs", ticksRemaining / 20.0);
        double textX = x + getWidth() / 2.0 - renderer.textWidth(text, false, getScale()) / 2.0;
        double textY = y + barHeight.get() + 2;
        renderer.text(text, textX, textY, colour, false, getScale());
    }

    @Override
    public void tick(HudRenderer renderer) {
        setSize(200, barHeight.get() + renderer.textHeight(false, getScale()) + 4);
    }

    private double getScale() { return 1.0; }

    private Color lerpColor(SettingColor a, SettingColor b, double t) {
        int r = (int) Math.round(a.r + (b.r - a.r) * t);
        int g = (int) Math.round(a.g + (b.g - a.g) * t);
        int bl = (int) Math.round(a.b + (b.b - a.b) * t);
        int alpha = (int) Math.round(a.a + (b.a - a.a) * t);
        return new Color(r, g, bl, alpha);
    }
}
