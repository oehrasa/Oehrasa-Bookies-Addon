package com.AutoBookshelf.addon.utils;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class AsmrAudioEngine {

    public enum State {IDLE, RESOLVING, PLAYING, FINISHED, ERROR}

    private static final AudioFormat PCM_FORMAT = new AudioFormat(44100f, 16, 2, true, false);

    private static volatile Thread worker;
    private static volatile Process ytProc;
    private static volatile Process ffProc;
    private static volatile SourceDataLine line;
    private static final AtomicBoolean stopFlag = new AtomicBoolean(false);

    // Bumped every time play()/stop() starts a new "generation" of playback.
    // Any in-flight worker thread from a previous generation checks this
    // before touching shared state or tearing down processes.
    private static final AtomicLong generation = new AtomicLong(0);

    private static volatile boolean paused = false;
    private static volatile String title = "";
    private static volatile State state = State.IDLE;
    private static volatile String statusMessage = "idle";
    private static volatile float configuredVolume = 0.6f;

    // Set true when a track ends naturally; a tick handler should poll+clear
    // this via consumeFinished() to drive "auto play next" behaviour.
    private static volatile boolean finishedFlag = false;

    private AsmrAudioEngine() {
    }

    public static boolean isPlaying() {
        Thread t = worker;
        return t != null && t.isAlive();
    }

    public static String title() {
        return title;
    }

    public static State state() {
        return state;
    }

    public static String statusMessage() {
        return statusMessage;
    }

    public static boolean paused() {
        return paused;
    }

    public static boolean consumeFinished() {
        if (finishedFlag) {
            finishedFlag = false;
            return true;
        }
        return false;
    }

    /**
     * Back-compat: no required keyword, any result from the pool is acceptable.
     */
    public static void play(String query, int poolSize) {
        play(query, poolSize, null);
    }

    public static void play(String query, int poolSize, String requiredKeyword) {
        stop();
        long myGen = generation.get(); // capture the generation stop() just advanced to
        stopFlag.set(false);
        paused = false;
        title = "";
        state = State.RESOLVING;
        statusMessage = "searching \"" + query + "\"...";

        Thread t = new Thread(() -> run(query, poolSize, requiredKeyword, myGen));
        t.setDaemon(true);
        t.setName("asmr-radio-player");
        worker = t;
        t.start();
    }

    public static void pause() {
        SourceDataLine l = line;
        if (l != null) {
            try {
                l.stop();
            } catch (Exception ignored) {
            }
        }
        paused = true;
        statusMessage = "paused";
    }

    public static void resume() {
        SourceDataLine l = line;
        if (l != null) {
            try {
                l.start();
            } catch (Exception ignored) {
            }
        }
        paused = false;
        statusMessage = "playing: " + title;
    }

    public static void togglePause() {
        if (paused) resume();
        else pause();
    }

    /**
     * vol in [0,1]. Applies live if a track is currently streaming, and is remembered for the next track.
     */
    public static void setVolume(float vol) {
        configuredVolume = Math.max(0f, Math.min(1f, vol));
        SourceDataLine l = line;
        if (l != null) applyGain(l, configuredVolume);
    }

    public static void stop() {
        // Invalidate any in-flight worker *before* touching anything else,
        // so a thread that's mid-stream sees the new generation as soon as
        // it next checks and stops mutating shared state / tearing down
        // whatever the next generation has already set up.
        generation.incrementAndGet();
        stopFlag.set(true);
        paused = false;
        YtDlpUtils.cancelResolve();
        destroyProcs();

        SourceDataLine l = line;
        if (l != null) {
            // Each call gets its own try/catch: previously these were
            // chained in a single try block, so if l.stop() threw,
            // flush()/close() were skipped entirely, leaving the line open
            // with buffered audio still playing out.
            try {
                l.stop();
            } catch (Exception ignored) {
            }
            try {
                l.flush();
            } catch (Exception ignored) {
            }
            try {
                l.close();
            } catch (Exception ignored) {
            }
        }
        line = null;

        Thread t = worker;
        if (t != null) t.interrupt();
        worker = null;

        title = "";
        state = State.IDLE;
        statusMessage = "stopped";
    }

    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 1200;

    private static void run(String query, int poolSize, String requiredKeyword, long myGen) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (myGen != generation.get()) return; // superseded, stop retrying

            YtDlpUtils.Track track;
            try {
                track = YtDlpUtils.resolveSearch(query, poolSize, requiredKeyword);
            } catch (Exception e) {
                if (myGen != generation.get()) return;
                if (attempt < MAX_ATTEMPTS) {
                    statusMessage = "retrying after resolve error (" + attempt + "/" + MAX_ATTEMPTS + ")...";
                    if (!sleepUnlessSuperseded(myGen)) return;
                    continue;
                }
                if (!stopFlag.get()) {
                    state = State.ERROR;
                    statusMessage = "yt-dlp not working";
                }
                return;
            }

            if (myGen != generation.get()) return; // superseded while resolving, drop silently

            if (track == null) {
                if (attempt < MAX_ATTEMPTS) {
                    statusMessage = "nothing found, retrying (" + attempt + "/" + MAX_ATTEMPTS + ")...";
                    if (!sleepUnlessSuperseded(myGen)) return;
                    continue;
                }
                if (myGen == generation.get()) {
                    state = State.ERROR;
                    statusMessage = "found nothing for \"" + query + "\"";
                }
                return;
            }

            if (myGen != generation.get()) return; // superseded between resolve and here

            title = track.title();
            state = State.PLAYING;
            statusMessage = "playing: " + title;

            boolean played = stream(track.url(), myGen);
            if (played || myGen != generation.get()) return; // success, or superseded mid-stream

            // stream() failed to produce any audio (e.g. the 403 -> ffmpeg
            // "invalid data" case) - retry with a fresh resolve rather than
            // leaving the user on a permanently broken track.
            if (attempt < MAX_ATTEMPTS) {
                statusMessage = "stream failed, retrying (" + attempt + "/" + MAX_ATTEMPTS + ")...";
                if (!sleepUnlessSuperseded(myGen)) return;
            }
        }
    }

    /**
     * Sleeps for RETRY_DELAY_MS, bailing out early (returns false) if superseded or stopped mid-sleep.
     */
    private static boolean sleepUnlessSuperseded(long myGen) {
        long deadline = System.currentTimeMillis() + RETRY_DELAY_MS;
        while (System.currentTimeMillis() < deadline) {
            if (myGen != generation.get() || stopFlag.get()) return false;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return myGen == generation.get() && !stopFlag.get();
    }

    /**
     * Returns true iff audio actually played (bytesPlayed > 0), so callers know whether a retry is warranted.
     */
    private static boolean stream(String url, long myGen) {
        long bytesPlayed = 0L;
        boolean failedToStart = false;
        Process localYt = null;
        Process localFf = null;
        try {
            List<Process> procs;
            try {
                procs = ProcessBuilder.startPipeline(List.of(
                    YtDlpUtils.ytDlpAudioStreamProcess(url),
                    YtDlpUtils.ffmpegAudioToPcmProcess()
                ));
            } catch (Exception e) {
                failedToStart = true;
                throw e;
            }
            localYt = procs.get(0);
            localFf = procs.get(1);

            if (myGen != generation.get()) return true; // superseded before we even publish these procs; not a failure

            ytProc = localYt;
            ffProc = localFf;

            SourceDataLine out = AudioSystem.getSourceDataLine(PCM_FORMAT);
            out.open(PCM_FORMAT);
            applyGain(out, configuredVolume);
            out.start();

            if (myGen != generation.get()) {
                try {
                    out.stop();
                    out.close();
                } catch (Exception ignored) {
                }
                return true;
            }

            line = out;
            if (paused) {
                try {
                    out.stop();
                } catch (Exception ignored) {
                }
            }

            InputStream src = localFf.getInputStream();
            byte[] buf = new byte[8192];
            while (!stopFlag.get() && myGen == generation.get()) {
                int n = src.read(buf);
                if (n < 0) break;
                if (n > 0) {
                    out.write(buf, 0, n);
                    bytesPlayed += n;
                }
            }
            if (!stopFlag.get() && myGen == generation.get()) out.drain();
            try {
                out.stop();
                out.close();
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
        } finally {
            // Only ever tear down the processes *this* invocation created -
            // never whatever the static ytProc/ffProc fields currently point
            // to, which may by now belong to a newer generation.
            if (localYt != null) localYt.destroyForcibly();
            if (localFf != null) localFf.destroyForcibly();

            if (myGen == generation.get()) {
                line = null;
                if (!stopFlag.get() && bytesPlayed > 0L) {
                    state = State.FINISHED;
                    statusMessage = "finished: " + title;
                    finishedFlag = true;
                }
                // failedToStart / bytesPlayed == 0 cases are left for the
                // caller (run()) to interpret as "not played", so it can
                // decide whether to retry instead of immediately declaring
                // a hard error.
            }
            // else: this generation was superseded
        }
        return bytesPlayed > 0L;
    }

    private static void applyGain(SourceDataLine l, float vol) {
        if (!l.isControlSupported(FloatControl.Type.MASTER_GAIN)) return;
        FloatControl c = (FloatControl) l.getControl(FloatControl.Type.MASTER_GAIN);
        float v = Math.max(0f, Math.min(1f, vol));
        float gain = v <= 0.0001f
            ? c.getMinimum()
            : (float) Math.max(c.getMinimum(), Math.min(c.getMaximum(), 20.0 * Math.log10(v)));
        c.setValue(gain);
    }

    private static void destroyProcs() {
        Process ff = ffProc, yt = ytProc;
        if (ff != null) ff.destroyForcibly();
        if (yt != null) yt.destroyForcibly();
        ffProc = null;
        ytProc = null;
    }
}
