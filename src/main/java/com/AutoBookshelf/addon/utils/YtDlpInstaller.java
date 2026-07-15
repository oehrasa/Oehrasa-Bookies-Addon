package com.AutoBookshelf.addon.utils;

import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads yt-dlp (and, on Windows, ffmpeg) into the game directory so the
 * module works out of the box without requiring the user to install
 * anything manually first.
 * On macOS/Linux, ffmpeg isn't auto-downloaded (no single static-build URL
 * as reliable as the Windows one)
 */
public final class YtDlpInstaller {

    private YtDlpInstaller() {
    }

    private static final String YTDLP_WIN = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe";
    private static final String YTDLP_MAC = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_macos";
    private static final String YTDLP_NIX = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp";
    private static final String FFMPEG_WIN = "https://github.com/BtbN/FFmpeg-Builds/releases/latest/download/ffmpeg-master-latest-win64-gpl.zip";

    private static final String OS_NAME = System.getProperty("os.name", "").toLowerCase();
    private static final boolean WINDOWS = OS_NAME.contains("win");
    private static final boolean MAC = OS_NAME.contains("mac") || OS_NAME.contains("darwin");

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    public static final CopyOnWriteArrayList<String> logs = new CopyOnWriteArrayList<>();
    private static volatile boolean running = false;
    private static volatile boolean done = false;

    /**
     * <gameDir>/meteor-client/asmr-radio/bin (adjust to wherever it keeps its own data).
     */
    public static Path binDir() {
        return FabricLoader.getInstance().getGameDir()
            .resolve("meteor-client").resolve("asmr-radio").resolve("bin");
    }

    public static Path ytDlpFile() {
        return binDir().resolve(WINDOWS ? "yt-dlp.exe" : "yt-dlp");
    }

    public static Path ffmpegFile() {
        return binDir().resolve(WINDOWS ? "ffmpeg.exe" : "ffmpeg");
    }

    public static boolean ytDlpInstalled() {
        return isCompleteFile(ytDlpFile());
    }

    public static boolean ffmpegInstalled() {
        return isCompleteFile(ffmpegFile());
    }

    private static boolean isCompleteFile(Path p) {
        try {
            return Files.exists(p) && Files.size(p) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Fire-and-forget on a daemon thread. Safe to call from onActivate(). No-op if already running.
     */
    public static void installAsync(Consumer<String> onLog, Runnable onDone) {
        if (running) return;
        logs.clear();
        done = false;
        running = true;

        Thread t = new Thread(() -> {
            try {
                install(msg -> {
                    logs.add(msg);
                    if (onLog != null) onLog.accept(msg);
                });
            } catch (Exception e) {
                String msg = "install failed: " + e.getMessage();
                logs.add(msg);
                if (onLog != null) onLog.accept(msg);
            } finally {
                done = true;
                running = false;
                if (onDone != null) onDone.run();
            }
        });
        t.setDaemon(true);
        t.setName("asmr-radio-installer");
        t.start();
    }

    public static void install(Consumer<String> log) throws IOException {
        Files.createDirectories(binDir());

        if (ytDlpInstalled()) {
            log.accept("yt-dlp already installed");
        } else {
            log.accept("downloading yt-dlp...");
            download(ytDlpUrl(), ytDlpFile());
            if (!WINDOWS) makeExecutable(ytDlpFile());
            log.accept("yt-dlp installed");
        }

        if (ffmpegInstalled()) {
            log.accept("ffmpeg already installed");
        } else if (WINDOWS) {
            log.accept("downloading ffmpeg...");
            installFfmpegWindows();
            log.accept(ffmpegInstalled() ? "ffmpeg installed" : "couldn't find ffmpeg.exe inside the downloaded archive");
        } else if (onPath("ffmpeg")) {
            log.accept("using ffmpeg already on PATH");
        } else {
            log.accept("couldn't fetch ffmpeg automatically, install it yourself (apt/brew install ffmpeg)");
        }

        log.accept("done");
    }

    private static String ytDlpUrl() {
        if (WINDOWS) return YTDLP_WIN;
        if (MAC) return YTDLP_MAC;
        return YTDLP_NIX;
    }

    private static void installFfmpegWindows() throws IOException {
        Path tmpZip = Files.createTempFile(binDir(), "ffmpeg-dl", ".zip");
        Path tmpExe = Files.createTempFile(binDir(), "ffmpeg-extract", ".exe.tmp");
        boolean extracted = false;
        try {
            download(FFMPEG_WIN, tmpZip);
            try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(tmpZip)))) {
                ZipEntry entry = zis.getNextEntry();
                while (entry != null) {
                    String name = entry.getName().replace('\\', '/');
                    if (!entry.isDirectory() && name.endsWith("bin/ffmpeg.exe")) {
                        // Extract fully into a temp file first
                        Files.copy(zis, tmpExe, StandardCopyOption.REPLACE_EXISTING);
                        extracted = true;
                        break;
                    }
                    entry = zis.getNextEntry();
                }
            }
            if (extracted) {
                atomicMoveInto(tmpExe, ffmpegFile());
            }
        } finally {
            Files.deleteIfExists(tmpZip);
            Files.deleteIfExists(tmpExe);
        }
    }

    private static void download(String url, Path target) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Path tmp = Files.createTempFile(dir, target.getFileName().toString(), ".part");
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "AutoBookshelf-Addon")
                .GET()
                .build();
            HttpResponse<InputStream> res = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
            // Body must be opened/closed regardless of status code
            try (InputStream in = res.body()) {
                if (res.statusCode() != 200) throw new IOException("http " + res.statusCode() + " for " + url);
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            if (Files.size(tmp) == 0) throw new IOException("downloaded 0 bytes for " + url);
            atomicMoveInto(tmp, target);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Atomic where the filesystem supports it; falls back to a plain.
     */
    private static void atomicMoveInto(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void makeExecutable(Path p) {
        try {
            Set<PosixFilePermission> perms = new HashSet<>(Files.getPosixFilePermissions(p));
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(p, perms);
        } catch (Exception ignored) {
            // non-POSIX filesystem - fine, nothing to set
        }
    }

    private static boolean onPath(String cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder(cmd, "-version")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            // A plain "waitFor(timeout) && exitValue()==0" leaves the process
            // running forever (leaked) if it hangs past the timeout instead
            // of exiting waitFor(timeout) returning false doesn't kill
            // anything on its own.
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
}
