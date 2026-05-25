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
        "Shows a countdown bar for teleports and cooldowns.",
        TeleportTimer::new
    );

    public enum Rank {
        Prime(300, 420),
        Elite(90, 120),
        APEX(15, 30);

        public final int homeCooldownSec, tpaCooldownSec;
        Rank(int home, int tpa) {
            this.homeCooldownSec = home;
            this.tpaCooldownSec = tpa;
        }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<SettingColor> startColor = sgGeneral.add(new ColorSetting.Builder()
        .name("start-color")
        .defaultValue(new SettingColor(0, 255, 0, 200))
        .build()
    );
    private final Setting<SettingColor> midColor = sgGeneral.add(new ColorSetting.Builder()
        .name("mid-color")
        .defaultValue(new SettingColor(255, 255, 0, 200))
        .build()
    );
    private final Setting<SettingColor> endColor = sgGeneral.add(new ColorSetting.Builder()
        .name("end-color")
        .defaultValue(new SettingColor(255, 0, 0, 200))
        .build()
    );
    private final Setting<Double> barHeight = sgGeneral.add(new DoubleSetting.Builder()
        .name("bar-height")
        .defaultValue(10)
        .min(4)
        .sliderRange(4, 30)
        .build()
    );

    private final Setting<Boolean> showCooldown = sgGeneral.add(new BoolSetting.Builder()
        .name("show-cooldown")
        .description("Display cooldown timer after teleport.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Rank> rank = sgGeneral.add(new EnumSetting.Builder<Rank>()
        .name("rank")
        .description("Your rank (used to calculate full cooldown).")
        .defaultValue(Rank.Elite)
        .build()
    );

    // internal state
    private int ticksRemaining = 0;
    private int totalTicks = 0;
    private String label = "";
    private boolean isCooldown = false;

    // patterns
    private static final Pattern TELEPORT_WARMUP = Pattern.compile("Teleporting.*?\\bin\\s+(\\d+)\\s*seconds?");
    private static final Pattern TELEPORT_SUCCESS = Pattern.compile("Teleporting to:");
    private static final Pattern COOLDOWN_MSG = Pattern.compile("You have to wait (?:(\\d+)m\\s*)?(\\d+)s\\s+to teleport again");

    public TeleportTimer() {
        super(INFO);
        MeteorClient.EVENT_BUS.subscribe(this);
    }

    @Override public void remove() {
        super.remove();
        MeteorClient.EVENT_BUS.unsubscribe(this);
    }

    @EventHandler
    private void onMessageReceive(ReceiveMessageEvent event) {
        if (mc.player == null || mc.world == null) return;
        String message = event.getMessage().getString();

        // 1. Teleport warmup (countdown)
        Matcher matcher = TELEPORT_WARMUP.matcher(message);
        if (matcher.find()) {
            int seconds = Integer.parseInt(matcher.group(1));
            ticksRemaining = seconds * 20;
            totalTicks = ticksRemaining;
            label = "Teleport";
            isCooldown = false;
            return;
        }

        // 2. Teleport success → start cooldown automatically
        if (showCooldown.get() && TELEPORT_SUCCESS.matcher(message).find()) {
            int fullSec = rank.get().homeCooldownSec;
            ticksRemaining = fullSec * 20;
            totalTicks = fullSec * 20;
            label = "Home CD";
            isCooldown = true;
            return;
        }

        // 3. Manual cooldown message (fallback)
        if (showCooldown.get()) {
            matcher = COOLDOWN_MSG.matcher(message);
            if (matcher.find()) {
                // Only handle /home cooldown (skip /tpa ones that contain shop link)
                if (message.contains("Lower your cooldown")) return;

                String minStr = matcher.group(1);
                String secStr = matcher.group(2);
                int minutes = (minStr != null) ? Integer.parseInt(minStr) : 0;
                int seconds = Integer.parseInt(secStr);
                int remaining = minutes * 60 + seconds;

                int fullSec = rank.get().homeCooldownSec;
                ticksRemaining = remaining * 20;
                totalTicks = fullSec * 20;
                label = "Home CD";
                isCooldown = true;
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (ticksRemaining > 0) ticksRemaining--;
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

        // Label above the bar
        renderer.text(label, x, y - renderer.textHeight(false, getScale()) - 1, colour, false, getScale());

        // Background bar
        renderer.quad(x, y, getWidth(), barHeight.get(), new SettingColor(0, 0, 0, 100));

        // Filled bar
        double filledWidth = getWidth() * fraction;
        renderer.quad(x, y, filledWidth, barHeight.get(), colour);

        // Time text below the bar
        String text = formatTime(ticksRemaining);
        double textX = x + getWidth() / 2.0 - renderer.textWidth(text, false, getScale()) / 2.0;
        double textY = y + barHeight.get() + 2;
        renderer.text(text, textX, textY, colour, false, getScale());
    }

    @Override
    public void tick(HudRenderer renderer) {
        setSize(200, barHeight.get() + renderer.textHeight(false, getScale()) * 2 + 6);
    }

    private double getScale() { return 1.0; }

    private Color lerpColor(SettingColor a, SettingColor b, double t) {
        int r = (int) Math.round(a.r + (b.r - a.r) * t);
        int g = (int) Math.round(a.g + (b.g - a.g) * t);
        int bl = (int) Math.round(a.b + (b.b - a.b) * t);
        int al = (int) Math.round(a.a + (b.a - a.a) * t);
        return new Color(r, g, bl, al);
    }

    private String formatTime(int ticks) {
        int totalSec = ticks / 20;
        if (totalSec >= 60) {
            return String.format("%dm %ds", totalSec / 60, totalSec % 60);
        }
        return String.format("%ds", totalSec);
    }
}
