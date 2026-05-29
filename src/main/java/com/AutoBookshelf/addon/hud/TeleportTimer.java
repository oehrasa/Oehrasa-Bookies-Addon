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
        NoRank(600, 600),
        Prime(300, 420),
        Elite(90, 120),
        APEX(15, 30);

        public final int homeCooldownSec, tpaCooldownSec;
        Rank(int home, int tpa) {
            this.homeCooldownSec = home;
            this.tpaCooldownSec = tpa;
        }
    }

    public enum HomeCooldown {
        Zero_Vote(600),
        Vote_21(300),
        Vote_49(90),
        Vote_210(15);

        public final int seconds;
        HomeCooldown(int seconds) { this.seconds = seconds; }
        @Override public String toString() { return seconds + "s"; }
    }

    public enum TpaCooldown {
        Zero_Vote(600),
        Vote_21(420),
        Vote_49(120),
        Vote_210(30);

        public final int seconds;
        TpaCooldown(int seconds) { this.seconds = seconds; }
        @Override public String toString() { return seconds + "s"; }
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
        .description("Your rank to calculate full cooldown.")
        .defaultValue(Rank.Elite)
        .build()
    );

    private final Setting<HomeCooldown> customHomeCd = sgGeneral.add(new EnumSetting.Builder<HomeCooldown>()
        .name("home-cooldown")
        .description("Home cooldown time when rank is NoRank if you have vote?")
        .defaultValue(HomeCooldown.Zero_Vote).visible(() -> rank.get() == Rank.NoRank)
        .build());

    private final Setting<TpaCooldown> customTpaCd = sgGeneral.add(new EnumSetting.Builder<TpaCooldown>()
        .name("tpa-cooldown")
        .description("TPA cooldown time when rank is NoRank if you have vote?")
        .defaultValue(TpaCooldown.Zero_Vote).visible(() -> rank.get() == Rank.NoRank)
        .build());

    // Warmup
    private int warmupTicksRemaining = 0;
    private int warmupTotalTicks = 0;
    private String warmupLabel = "";

    // Home cooldown
    private int homeTicksRemaining = 0;
    private int homeTotalTicks = 0;
    private String homeLabel = "";

    // Tpa cooldown
    private int tpaTicksRemaining = 0;
    private int tpaTotalTicks = 0;
    private String tpaLabel = "";

    private boolean lastTeleportWasHome = true;   // used for manual reminders
    private boolean pendingTpa = false;           // Tpa accept seen
    private String tpaTarget = "";

    // Pattern
    private static final Pattern TELEPORT_WARMUP   = Pattern.compile("Teleporting.*?\\bin\\s+(\\d+)\\s*seconds?");
    private static final Pattern COOLDOWN_MSG      = Pattern.compile("You have to wait (?:(\\d+)m\\s*)?(\\d+)s\\s+to teleport again");
    private static final Pattern TELEPORT_CANCEL   = Pattern.compile("Successfully cancelled your pending teleport request to:\\s*(\\S+)");
    private static final Pattern TPA_ACCEPT = Pattern.compile("Your request sent to (\\S+) was accepted!");
    private static final Pattern HOME_ARRIVAL = Pattern.compile("Teleporting to:");
    private static final Pattern TPA_ARRIVAL  = Pattern.compile("Teleported to");

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

        // Cancel
        if (TELEPORT_CANCEL.matcher(message).find()) {
            warmupTicksRemaining = 0;
            warmupTotalTicks = 0;
            warmupLabel = "";
            return;
        }

        // TPA accepted, store the target player name
        Matcher tpaMatcher = TPA_ACCEPT.matcher(message);
        if (tpaMatcher.find()) {
            pendingTpa = true;
            tpaTarget = tpaMatcher.group(1);   // "onrc-chan"
            return;
        }

        // 1. Warmup
        Matcher matcher = TELEPORT_WARMUP.matcher(message);
        if (matcher.find()) {
            int seconds = Integer.parseInt(matcher.group(1));
            warmupTicksRemaining = seconds * 20;
            warmupTotalTicks = seconds * 20;

            String destination = "";
            if (message.contains(" to ")) {
                int start = message.indexOf(" to ") + 4;
                int end = message.length();
                int inIdx = message.indexOf(" in ", start);
                if (inIdx != -1) end = inIdx;
                int dotIdx = message.indexOf('.', start);
                if (dotIdx != -1 && dotIdx < end) end = dotIdx;
                destination = message.substring(start, end).trim();
            }
            if (pendingTpa && !tpaTarget.isEmpty()) {
                warmupLabel = "Teleporting to " + tpaTarget;
            } else {
                warmupLabel = destination.isEmpty() ? "Teleporting" : "Teleporting to " + destination;
            }

            lastTeleportWasHome = !pendingTpa;
            pendingTpa = false;
            return;
        }

        // 2. Arrival
        if (showCooldown.get()) {
            if (HOME_ARRIVAL.matcher(message).find()) {
                // Home arrival
                lastTeleportWasHome = true;
                warmupTicksRemaining = 0;

                int fullSec = (rank.get() == Rank.NoRank)
                    ? customHomeCd.get().seconds
                    : rank.get().homeCooldownSec;
                homeTicksRemaining = fullSec * 20;
                homeTotalTicks = fullSec * 20;
                homeLabel = "Home Cooldown";
                return;
            } else if (TPA_ARRIVAL.matcher(message).find()) {
                // TPA arrival, it starts on manual reminder
                lastTeleportWasHome = false;
                warmupTicksRemaining = 0;
                return;
            }
        }

        // 3. Manual cooldown reminder
        if (showCooldown.get()) {
            matcher = COOLDOWN_MSG.matcher(message);
            if (matcher.find()) {
                String minStr = matcher.group(1);
                String secStr = matcher.group(2);
                int minutes = (minStr != null) ? Integer.parseInt(minStr) : 0;
                int seconds = Integer.parseInt(secStr);
                int remaining = minutes * 60 + seconds;

                int fullSec;
                if (rank.get() == Rank.NoRank) {
                    fullSec = lastTeleportWasHome ? customHomeCd.get().seconds : customTpaCd.get().seconds;
                } else {
                    fullSec = lastTeleportWasHome ? rank.get().homeCooldownSec : rank.get().tpaCooldownSec;
                }

                // Update the timer that matches lastTeleportWasHome
                if (lastTeleportWasHome) {
                    homeTicksRemaining = remaining * 20;
                    homeTotalTicks = fullSec * 20;
                    homeLabel = "Home Cooldown";
                } else {
                    tpaTicksRemaining = remaining * 20;
                    tpaTotalTicks = fullSec * 20;
                    tpaLabel = "TPA Cooldown";
                }
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (warmupTicksRemaining > 0) warmupTicksRemaining--;
        if (homeTicksRemaining > 0) homeTicksRemaining--;
        if (tpaTicksRemaining > 0) tpaTicksRemaining--;
    }

    @Override
    public void render(HudRenderer renderer) {
        double barH = barHeight.get();
        double scale = getScale();
        int visibleBars = 0;
        if (warmupTicksRemaining > 0) visibleBars++;
        if (homeTicksRemaining > 0) visibleBars++;
        if (tpaTicksRemaining > 0) visibleBars++;
        if (visibleBars == 0) return;

        // Layout: each bar = barH + 2*textHeight + 6   label above, timer below
        double lineH = renderer.textHeight(false, scale);
        double barGap = 2;
        double rowHeight = lineH + 2 + barH + 2 + lineH + barGap;
        double totalHeight = rowHeight * visibleBars;
        setSize(200, totalHeight);

        double y = this.y;
        // Warmup bar
        if (warmupTicksRemaining > 0 && warmupTotalTicks > 0) {
            drawBar(renderer, y, warmupTicksRemaining, warmupTotalTicks, warmupLabel, barH, scale);
            y += rowHeight;
        }
        // Home cooldown
        if (homeTicksRemaining > 0 && homeTotalTicks > 0) {
            drawBar(renderer, y, homeTicksRemaining, homeTotalTicks, homeLabel, barH, scale);
            y += rowHeight;
        }
        // Tpa cooldown
        if (tpaTicksRemaining > 0 && tpaTotalTicks > 0) {
            drawBar(renderer, y, tpaTicksRemaining, tpaTotalTicks, tpaLabel, barH, scale);
        }
    }

    private void drawBar(HudRenderer renderer, double y, int ticks, int total, String label, double barH, double scale) {
        double fraction = (double) ticks / total;
        Color colour = fraction > 0.5
            ? lerpColor(midColor.get(), startColor.get(), (fraction - 0.5) * 2.0)
            : lerpColor(endColor.get(), midColor.get(), fraction * 2.0);

        // Label
        renderer.text(label, x, y, colour, false, scale);
        double textH = renderer.textHeight(false, scale);
        // Background bar
        renderer.quad(x, y + textH + 2, getWidth(), barH, new SettingColor(0, 0, 0, 100));
        // Filled bar
        double filledW = getWidth() * fraction;
        renderer.quad(x, y + textH + 2, filledW, barH, colour);
        // Timer text below
        String timeText = formatTime(ticks);
        double textX = x + getWidth() / 2.0 - renderer.textWidth(timeText, false, scale) / 2.0;
        double textY = y + textH + 2 + barH + 2;
        renderer.text(timeText, textX, textY, colour, false, scale);
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
        if (totalSec >= 60) return String.format("%dm %ds", totalSec / 60, totalSec % 60);
        return String.format("%ds", totalSec);
    }
}
