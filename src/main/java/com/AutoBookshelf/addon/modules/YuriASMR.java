package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.utils.AsmrAudioEngine;
import com.AutoBookshelf.addon.utils.YtDlpInstaller;
import com.AutoBookshelf.addon.utils.YtDlpUtils;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;

import java.util.ArrayList;
import java.util.List;

public class YuriASMR extends Module {

    public YuriASMR() {
        super(Addon.CATEGORY2, "Yuri-Asmr",
            "Streams a random ASMR search result through yt-dlp + ffmpeg with integrated HUD.");
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgQueries = settings.createGroup("Queries");
    private final SettingGroup sgBinaries = settings.createGroup("Binaries");

    public final Setting<Boolean> asmr = sgQueries.add(new BoolSetting.Builder()
        .name("asmr")
        .description("Include plain \"asmr\" in the search rotation.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> yuriAsmr = sgQueries.add(new BoolSetting.Builder()
        .name("yuri-asmr")
        .description("Include \"yuri asmr\" in the search rotation.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> girlfriendAsmr = sgQueries.add(new BoolSetting.Builder()
        .name("girlfriend-asmr")
        .description("Include \"girlfriend asmr\" in the search rotation.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> mommyAsmr = sgQueries.add(new BoolSetting.Builder()
        .name("mommy-asmr")
        .description("Include \"mommy asmr\" in the search rotation.")
        .defaultValue(true)
        .build()
    );

    public final Setting<String> extraQuery = sgQueries.add(new StringSetting.Builder()
        .name("extra-query")
        .description("Optional extra search term added to the rotation. Leave blank to disable.")
        .defaultValue("")
        .build()
    );

    public final Setting<Integer> searchPoolSize = sgGeneral.add(new IntSetting.Builder()
        .name("search-pool-size")
        .description("How many top search results to randomly pick from per query, for variety.")
        .defaultValue(15)
        .range(1, 30)
        .sliderMin(1).sliderMax(30)
        .build()
    );

    public final Setting<Integer> volume = sgGeneral.add(new IntSetting.Builder()
        .name("volume")
        .description("Playback volume percentage.")
        .defaultValue(60)
        .range(0, 100)
        .sliderMin(0).sliderMax(100)
        .build()
    );

    public final Setting<Boolean> autoNext = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-next")
        .description("Automatically start a new random track when the current one finishes.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Keybind> nextTrackKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("next-track-key")
        .description("Keybind to skip to a new random track. Unbound by default.")
        .defaultValue(Keybind.none())
        .build()
    );

    public final Setting<String> ytDlpPath = sgBinaries.add(new StringSetting.Builder()
        .name("yt-dlp-path")
        .description("Path to the yt-dlp executable. Leave blank to use \"yt-dlp\" on PATH.")
        .defaultValue("")
        .build()
    );

    public final Setting<String> ffmpegPath = sgBinaries.add(new StringSetting.Builder()
        .name("ffmpeg-path")
        .description("Path to the ffmpeg executable. Leave blank to use \"ffmpeg\" on PATH.")
        .defaultValue("")
        .build()
    );

    // Minimum time between skip() calls. Rapid-fire skipping fires a burst
    // of back-to-back yt-dlp requests at YouTube from the same IP, which is
    // exactly the pattern that gets flagged and starts returning
    // "HTTP Error 403: Forbidden". This debounce keeps the skip key
    // responsive without hammering the endpoint.
    private static final long SKIP_DEBOUNCE_MS = 800;
    private long lastSkipMs = 0L;

    @Override
    public void onActivate() {
        applyBinaryPaths();
        Thread check = new Thread(() -> {
            boolean available = YtDlpUtils.binariesAvailable(3, 400);
            if (available) {
                AsmrAudioEngine.setVolume(volume.get() / 100f);
                playRandom();
                return;
            }

            info("yt-dlp/ffmpeg not found, downloading...");
            YtDlpInstaller.installAsync(
                this::info,
                () -> {
                    applyBinaryPaths();
                    if (YtDlpUtils.binariesAvailable(3, 400)) {
                        AsmrAudioEngine.setVolume(volume.get() / 100f);
                        playRandom();
                    } else {
                        error("couldn't set up yt-dlp/ffmpeg. Install ffmpeg (apt/brew install ffmpeg) or point the settings at existing binaries.");
                        toggle();
                    }
                }
            );
        });
        check.setDaemon(true);
        check.setName("asmr-radio-check");
        check.start();
    }

    /**
     * Explicit settings paths win; otherwise fall back to whatever the installer downloaded, then bare PATH lookups.
     */
    private void applyBinaryPaths() {
        YtDlpUtils.setYtDlpPath(!ytDlpPath.get().isBlank()
            ? ytDlpPath.get()
            : (YtDlpInstaller.ytDlpInstalled() ? YtDlpInstaller.ytDlpFile().toString() : ""));

        YtDlpUtils.setFfmpegPath(!ffmpegPath.get().isBlank()
            ? ffmpegPath.get()
            : (YtDlpInstaller.ffmpegInstalled() ? YtDlpInstaller.ffmpegFile().toString() : ""));
    }

    @Override
    public void onDeactivate() {
        AsmrAudioEngine.stop();
    }

    private boolean nextKeyWasDown = false;

    @EventHandler
    private void onTick(TickEvent.Post event) {
        AsmrAudioEngine.setVolume(volume.get() / 100f);
        if (autoNext.get() && AsmrAudioEngine.consumeFinished()) {
            playRandom();
        }

        boolean keyDown = nextTrackKey.get().isPressed();
        if (keyDown && !nextKeyWasDown) skip();
        nextKeyWasDown = keyDown;
    }

    private void skip() {
        if (!isActive()) return;
        long now = System.currentTimeMillis();
        if (now - lastSkipMs < SKIP_DEBOUNCE_MS) return;
        lastSkipMs = now;
        playRandom();
    }

    private void playRandom() {
        // Each entry pairs the search query with the keyword that must
        // actually appear in a candidate's title for it to count as
        // matching that toggle. Plain "asmr" and the free-text extra query
        // aren't given a required keyword since any result is acceptable
        // for those.
        List<String[]> pool = new ArrayList<>(); // {query, requiredKeywordOrNull}
        if (asmr.get()) pool.add(new String[]{"asmr", null});
        if (yuriAsmr.get()) pool.add(new String[]{"yuri asmr", "yuri"});
        if (girlfriendAsmr.get()) pool.add(new String[]{"girlfriend asmr", "girlfriend"});
        if (mommyAsmr.get()) pool.add(new String[]{"mommy asmr", "mommy"});
        if (!extraQuery.get().isBlank()) pool.add(new String[]{extraQuery.get(), null});

        if (pool.isEmpty()) {
            error("no queries enabled, enable at least one in settings.");
            toggle();
            return;
        }

        String[] choice = pool.get((int) (Math.random() * pool.size()));
        AsmrAudioEngine.play(choice[0], searchPoolSize.get(), choice[1]);
    }
}
