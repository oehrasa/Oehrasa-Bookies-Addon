package com.AutoBookshelf.addon.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class YtDlpUtils {

    private YtDlpUtils() {
    }

    private static volatile String ytDlpPath = "yt-dlp";
    private static volatile String ffmpegPath = "ffmpeg";

    // Routes around the YouTube web-client signature/PO-token path that is
    // currently the main source of spurious "HTTP Error 403: Forbidden"
    // failures. The android client uses a simpler, more stable playback
    // path. If YouTube ever locks this one down too, this is the first
    // thing to try changing (example: to "ios") or removing if yt-dlp itself
    // has since fixed the underlying issue upstream.
    private static final String[] EXTRACTOR_ARGS = {"--extractor-args", "youtube:player_client=android"};

    public static void setYtDlpPath(String path) {
        ytDlpPath = (path == null || path.isBlank()) ? "yt-dlp" : path;
    }

    public static void setFfmpegPath(String path) {
        ffmpegPath = (path == null || path.isBlank()) ? "ffmpeg" : path;
    }

    public static boolean binariesAvailable(int attempts, long retryDelayMs) {
        // Try the current paths (which may be overridden to the downloaded locations)
        for (int i = 0; i < Math.max(1, attempts); i++) {
            if (existsAndExecutable(ytDlpPath) && existsAndExecutable(ffmpegPath)) {
                return true;
            }
            if (i < attempts - 1) {
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        // Fallback: try the system PATH (for "ffmpeg" / "yt-dlp" if not set)
        String[] ytCmds = {ytDlpPath, "yt-dlp", "yt-dlp.exe"};
        String[] ffCmds = {ffmpegPath, "ffmpeg", "ffmpeg.exe"};
        for (int i = 0; i < Math.max(1, attempts); i++) {
            for (String yt : ytCmds) {
                for (String ff : ffCmds) {
                    if (onPath(yt, "--version") && onPath(ff, "-version")) {
                        setYtDlpPath(yt);
                        setFfmpegPath(ff);
                        return true;
                    }
                }
            }
            if (i < attempts - 1) {
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException e) {
                    return false;
                }
            }
        }
        return false;
    }

    private static boolean existsAndExecutable(String path) {
        try {
            Path p = Path.of(path);
            return Files.exists(p) && Files.size(p) > 0 && (System.getProperty("os.name").toLowerCase().contains("win") || Files.isExecutable(p));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean onPath(String cmd, String flag) {
        Process p = null;
        try {
            p = new ProcessBuilder(cmd, flag)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (p != null) p.destroyForcibly();
            return false;
        } catch (Exception e) {
            if (p != null) p.destroyForcibly();
            return false;
        }
    }

    public record Track(String title, String url) {
    }

    /**
     * Back-compat convenience: no keyword filter, just a random pick among the pool.
     */
    public static Track resolveSearch(String query, int poolSize) throws IOException, InterruptedException {
        return resolveSearch(query, poolSize, null);
    }

    public static Track resolveSearch(String query, int poolSize, String requiredKeyword) throws IOException, InterruptedException {
        int pool = Math.max(1, poolSize);
        String input = "ytsearch" + pool + ":" + query;
        List<Track> candidates = resolveAll(input);
        if (candidates.isEmpty()) return null;

        if (requiredKeyword != null && !requiredKeyword.isBlank()) {
            String kw = requiredKeyword.toLowerCase();
            List<Track> filtered = new ArrayList<>();
            for (Track t : candidates) {
                if (t.title().toLowerCase().contains(kw)) filtered.add(t);
            }
            if (!filtered.isEmpty()) candidates = filtered;
            // else: nothing in this batch matched the keyword - fall back
            // to the unfiltered candidates rather than returning null.
        }

        return candidates.get((int) (Math.random() * candidates.size()));
    }

    /**
     * Currently in-flight yt-dlp metadata-resolve process, if any, so it can be force-cancelled externally (see cancelResolve()).
     */
    private static volatile Process resolveProc;

    public static void cancelResolve() {
        Process p = resolveProc;
        if (p != null) p.destroyForcibly();
    }

    /**
     * Fetches (title, url) pairs for every entry in a ytsearchN: input, using flat-playlist listing so it stays fast even for larger pool sizes.
     */
    private static List<Track> resolveAll(String input) throws IOException, InterruptedException {
        List<String> args = new ArrayList<>();
        args.add(ytDlpPath);
        args.add("-q");
        args.add("--no-warnings");
        args.add("--flat-playlist");
        for (String a : EXTRACTOR_ARGS) args.add(a);
        args.add("--print");
        args.add("%(title)s");
        args.add("--print");
        args.add("%(webpage_url)s");

        args.add("--");
        args.add(input);

        Process proc = new ProcessBuilder(args)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
        resolveProc = proc;

        Thread watchdog = new Thread(() -> {
            try {
                if (!proc.waitFor(20, TimeUnit.SECONDS)) {
                    proc.destroyForcibly();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        watchdog.setDaemon(true);
        watchdog.setName("yt-dlp-resolve-watchdog");
        watchdog.start();

        List<String> lines = new ArrayList<>();
        try {
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) lines.add(line);
            }
            proc.waitFor();
        } finally {
            watchdog.interrupt();
            resolveProc = null;
        }

        List<Track> out = new ArrayList<>();
        for (int i = 0; i + 1 < lines.size(); i += 2) {
            String title = lines.get(i);
            String url = lines.get(i + 1);
            if (title != null && !title.isBlank() && url != null && !url.isBlank()) {
                out.add(new Track(title, url));
            }
        }
        return out;
    }

    public static ProcessBuilder ytDlpAudioStreamProcess(String url) {
        List<String> args = new ArrayList<>();
        args.add(ytDlpPath);
        args.add("-q");
        args.add("--no-warnings");
        args.add("--no-progress");
        for (String a : EXTRACTOR_ARGS) args.add(a);
        args.add("-f");
        args.add("bestaudio/best");
        args.add("-o");
        args.add("-");
        args.add("--");
        args.add(url);

        return new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.INHERIT);
    }

    @Deprecated
    public static ProcessBuilder ytDlpMp3StreamProcess(String url) {
        return ytDlpAudioStreamProcess(url);
    }

    /**
     * ffmpeg process that decodes the piped audio stream into raw PCM
     * (s16le, 44.1kHz stereo) Java Sound can't decode compressed audio
     * itself, so this hop is what makes the audio playable via
     * javax.sound.sampled.
     * <p>
     * No input "-f" is forced here: the upstream yt-dlp process now streams
     * whatever raw container YouTube actually serves (not guaranteed to be
     * mp3), so ffmpeg is left to auto-probe pipe:0 from its content rather
     * than being told to assume mp3, which would fail to decode anything
     * that isn't literally an mp3 bitstream.
     * <p>
     * stderr is inherited (not discarded) so decode failures are visible
     * instead of manifesting only as "bytesPlayed == 0" with no diagnostic.
     */
    public static ProcessBuilder ffmpegAudioToPcmProcess() {
        return new ProcessBuilder(
            ffmpegPath, "-hide_banner", "-loglevel", "error",
            "-i", "pipe:0",
            "-vn", "-f", "s16le", "-ar", "44100", "-ac", "2", "pipe:1"
        ).redirectError(ProcessBuilder.Redirect.INHERIT);
    }

    @Deprecated
    public static ProcessBuilder ffmpegMp3ToPcmProcess() {
        return ffmpegAudioToPcmProcess();
    }
}
