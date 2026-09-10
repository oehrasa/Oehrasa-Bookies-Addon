package com.AutoBookshelf.addon.modules.livemessage.util;

import com.AutoBookshelf.addon.modules.livemessage.LiveMessage;
import com.AutoBookshelf.addon.modules.livemessage.gui.ChatWindow;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.settings.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

public class LivemessageUtil {
    public static final Path LIVEMESSAGE_FOLDER = MeteorClient.FOLDER.toPath().resolve("livemessage");
    public static final Path MESSAGES_FOLDER = LIVEMESSAGE_FOLDER.resolve("messages");
    public static final Path SETTINGS_FOLDER = LIVEMESSAGE_FOLDER.resolve("settings");
    public static final Path PATTERNS_FOLDER = LIVEMESSAGE_FOLDER.resolve("patterns");
    public static final List<Pattern> FROM_PATTERNS = new ArrayList<>();
    public static final List<Pattern> TO_PATTERNS = new ArrayList<>();
    private static final Pattern TIMESTAMP_PREFIX = Pattern.compile("^<\\d{1,2}:\\d{2}>\\s*");

    private static final String[] DEFAULT_INCOMING = new String[]{
        "player whispers:",
        "From player:",
        "[player -> me]",
        "player whispers to you:"
    };
    private static final String[] DEFAULT_OUTGOING = new String[]{
        "You whisper to player:",
        "you whisper to player:",
        "to player:",
        "[me -> player]"
    };

    public static void initDirs() {
        try {
            Files.createDirectories(LIVEMESSAGE_FOLDER);
            Files.createDirectories(MESSAGES_FOLDER);
            Files.createDirectories(SETTINGS_FOLDER);
            Files.createDirectories(PATTERNS_FOLDER);
            File toPatterns = PATTERNS_FOLDER.resolve("toPatterns.txt").toFile();
            if (!toPatterns.exists()) {
                toPatterns.createNewFile();
            }

            File fromPatterns = PATTERNS_FOLDER.resolve("fromPatterns.txt").toFile();
            if (!fromPatterns.exists()) {
                fromPatterns.createNewFile();
            }
        } catch (IOException e) {
            LiveMessage.logError("Failed to initialize Livemessage directories", e);
        }
    }

    public static void initFolders() {
        initDirs();
        reloadPatterns();
    }

    public static void reloadPatterns() {
        FROM_PATTERNS.clear();
        TO_PATTERNS.clear();
        boolean allowRankPrefix = LiveMessage.INSTANCE == null || LiveMessage.INSTANCE.allowRankPrefix.get();
        int fromModule = loadModulePatterns(true, allowRankPrefix);
        int toModule = loadModulePatterns(false, allowRankPrefix);
        int fromFile = loadFilePatterns(PATTERNS_FOLDER.resolve("fromPatterns.txt").toFile(), FROM_PATTERNS, allowRankPrefix);
        int toFile = loadFilePatterns(PATTERNS_FOLDER.resolve("toPatterns.txt").toFile(), TO_PATTERNS, allowRankPrefix);
        loadDefaults(DEFAULT_INCOMING, FROM_PATTERNS, allowRankPrefix);
        loadDefaults(DEFAULT_OUTGOING, TO_PATTERNS, allowRankPrefix);

        LiveMessage.LOG.info(
            "Loaded {} incoming and {} outgoing DM formats (module: {}/{}, files: {}/{})",
            FROM_PATTERNS.size(),
            TO_PATTERNS.size(),
            fromModule,
            toModule,
            fromFile,
            toFile
        );
    }

    private static void loadDefaults(String[] templates, List<Pattern> target, boolean allowRankPrefix) {
        for (String template : templates) {
            addPattern(target, template, allowRankPrefix, "default");
        }
    }

    private static int loadModulePatterns(boolean incoming, boolean allowRankPrefix) {
        if (LiveMessage.INSTANCE == null) {
            return 0;
        }

        int count = 0;
        List<Setting<String>> settings = incoming
            ? List.of(
            LiveMessage.INSTANCE.fromPattern1,
            LiveMessage.INSTANCE.fromPattern2,
            LiveMessage.INSTANCE.fromPattern3,
            LiveMessage.INSTANCE.fromPattern4
        )
            : List.of(
            LiveMessage.INSTANCE.toPattern1,
            LiveMessage.INSTANCE.toPattern2,
            LiveMessage.INSTANCE.toPattern3,
            LiveMessage.INSTANCE.toPattern4
        );

        List<Pattern> target = incoming ? FROM_PATTERNS : TO_PATTERNS;

        for (Setting<String> setting : settings) {
            if (addPattern(target, setting.get(), allowRankPrefix, "module")) {
                count++;
            }
        }

        return count;
    }

    private static int loadFilePatterns(File file, List<Pattern> target, boolean allowRankPrefix) {
        int count = 0;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            int lineNum = 0;
            String line;

            while ((line = reader.readLine()) != null) {
                lineNum++;
                if (addPattern(target, line, allowRankPrefix, "file line " + lineNum)) {
                    count++;
                }
            }
        } catch (IOException e) {
            LiveMessage.logError("Failed to load patterns file: {}", file.getName(), e);
        }

        return count;
    }

    private static boolean addPattern(List<Pattern> target, String template, boolean allowRankPrefix, String source) {
        try {
            Pattern pattern = PatternTemplate.compile(template, allowRankPrefix);
            if (pattern != null) {
                target.add(pattern);
                return true;
            }
        } catch (Exception e) {
            LiveMessage.logError("Invalid {} pattern '{}': {}", source, template, e.getMessage());
        }

        return false;
    }

    public static LivemessageUtil.ChatSettings getChatSettings(UUID uuid) {
        try (JsonReader reader = new JsonReader(new FileReader(SETTINGS_FOLDER.resolve(uuid.toString() + ".json").toFile()))) {
            ChatSettings settings = new Gson().fromJson(reader, ChatSettings.class);
            return settings != null ? settings : new LivemessageUtil.ChatSettings();
        } catch (Exception e) {
            return new LivemessageUtil.ChatSettings();
        }
    }

    public static void saveChatSettings(UUID uuid, LivemessageUtil.ChatSettings chatSettings) {
        try (Writer writer = new FileWriter(SETTINGS_FOLDER.resolve(uuid.toString() + ".json").toFile())) {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            gson.toJson(chatSettings, writer);
        } catch (IOException e) {
            LiveMessage.logError("Failed to save chat settings for UUID: {}", uuid, e);
        }
    }

    public static String stripChatDecorations(String text) {
        if (text == null) {
            return "";
        }

        String stripped = text.replaceAll("§[0-9a-fk-or]", "");
        stripped = TIMESTAMP_PREFIX.matcher(stripped).replaceFirst("");
        return stripped;
    }

    public static String normalizeChatLine(String text) {
        String stripped = stripChatDecorations(text);
        while (TIMESTAMP_PREFIX.matcher(stripped).lookingAt()) {
            stripped = TIMESTAMP_PREFIX.matcher(stripped).replaceFirst("");
        }

        return stripped.trim();
    }

    public static boolean checkOnlineStatus(UUID uuid) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) {
            return false;
        }

        PlayerInfo entry = mc.getConnection().getPlayerInfo(uuid);
        return entry != null;
    }

    /**
     * Trims a conversation's on-disk history to at most maxLines lines. Oldest sent/received
     * (non-pending) lines are dropped first; all pending (queued, unsent) lines are always kept
     * regardless of the limit.
     */
    public static void trimHistory(UUID uuid, int maxLines) {
        File file = MESSAGES_FOLDER.resolve(uuid.toString() + ".jsonl").toFile();
        if (!file.exists()) {
            return;
        }

        Gson gson = new Gson();
        List<String> lines = new ArrayList<>();
        List<Boolean> pendingFlags = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
                boolean pending = false;
                try {
                    ChatWindow.ChatMessage msg = gson.fromJson(line, ChatWindow.ChatMessage.class);
                    pending = msg != null && msg.pending;
                } catch (Exception ignored) {
                }
                pendingFlags.add(pending);
            }
        } catch (IOException e) {
            LiveMessage.logError("Failed to read history while trimming for UUID: {}", uuid, e);
            return;
        }

        int pendingCount = 0;
        for (boolean p : pendingFlags) {
            if (p) pendingCount++;
        }

        int trimmableTotal = lines.size() - pendingCount;
        int remainingBudget = Math.max(0, maxLines - pendingCount);
        int dropCount = Math.max(0, trimmableTotal - remainingBudget);

        if (dropCount <= 0) {
            return; // already within the cap once pending lines are accounted for
        }

        List<String> kept = new ArrayList<>(lines.size());
        int trimmableSeen = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (pendingFlags.get(i)) {
                kept.add(lines.get(i));
                continue;
            }
            trimmableSeen++;
            if (trimmableSeen <= dropCount) {
                continue; // drop this non-pending line
            }
            kept.add(lines.get(i));
        }

        try (FileWriter writer = new FileWriter(file, false)) {
            for (String l : kept) {
                writer.write(l + "\n");
            }
        } catch (IOException e) {
            LiveMessage.logError("Failed to rewrite trimmed history for UUID: {}", uuid, e);
        }
    }

    /**
     * Runs trimHistory() across every conversation file in messages_folder
     */
    public static void trimAllHistories(int maxLines) {
        File[] files = MESSAGES_FOLDER.toFile().listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (!file.getName().endsWith(".jsonl")) continue;
            try {
                UUID uuid = UUID.fromString(file.getName().substring(0, 36));
                trimHistory(uuid, maxLines);
            } catch (Exception ignored) {
            }
        }
    }

    public static class ChatSettings {
        public int customColor = 0;
        public String lastName;
    }
}
