package com.AutoBookshelf.addon.utils;

import com.AutoBookshelf.addon.modules.livemessage.gui.ChatWindow;
import com.AutoBookshelf.addon.modules.livemessage.util.LivemessageUtil;
import com.google.gson.Gson;
import net.minecraft.client.Minecraft;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads/writes queued (pending=true) DM messages directly against the on-disk .jsonl files
 */
public class QueueUtil {
    private static final Gson GSON = new Gson();

    // One lock per uuid so popOldestPending/deleteQueued/addQueued for the same recipient can
    // never interleave.
    private static final Map<UUID, Object> LOCKS = new ConcurrentHashMap<>();

    private static Object lockFor(UUID uuid) {
        return LOCKS.computeIfAbsent(uuid, k -> new Object());
    }

    private static void writeAtomic(File file, List<String> lines) throws IOException {
        Path target = file.toPath();
        Path tmp = Files.createTempFile(target.getParent(), file.getName(), ".tmp");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                for (String l : lines) {
                    writer.write(l);
                    writer.write("\n");
                }
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) { // ADDED: fallback for filesystems without atomic rename support
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp); // avoid leaking the temp file if move/write fails
        }
    }

    public static Map<UUID, List<ChatWindow.ChatMessage>> allQueued() {
        Map<UUID, List<ChatWindow.ChatMessage>> result = new LinkedHashMap<>();
        File[] files = LivemessageUtil.MESSAGES_FOLDER.toFile().listFiles();
        if (files == null) return result;

        for (File file : files) {
            if (!file.getName().endsWith(".jsonl")) continue;

            UUID uuid;
            try {
                uuid = UUID.fromString(file.getName().substring(0, 36));
            } catch (Exception e) {
                continue;
            }

            List<ChatWindow.ChatMessage> pending = readPending(file);
            if (!pending.isEmpty()) result.put(uuid, pending);
        }

        return result;
    }

    public static List<ChatWindow.ChatMessage> queuedFor(UUID uuid) {
        return readPending(fileFor(uuid));
    }

    private static List<ChatWindow.ChatMessage> readPending(File file) {
        List<ChatWindow.ChatMessage> out = new ArrayList<>();
        if (!file.exists()) return out;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    ChatWindow.ChatMessage msg = GSON.fromJson(line, ChatWindow.ChatMessage.class);
                    if (msg != null && msg.pending) out.add(msg);
                } catch (Exception ignored) {
                }
            }
        } catch (IOException ignored) {
        }

        return out;
    }

    private static File fileFor(UUID uuid) {
        return LivemessageUtil.MESSAGES_FOLDER.resolve(uuid.toString() + ".jsonl").toFile();
    }

    public static String usernameFor(UUID uuid) {
        LivemessageUtil.ChatSettings settings = LivemessageUtil.getChatSettings(uuid);
        return settings.lastName != null ? settings.lastName : uuid.toString();
    }

    /**
     * Appends a new queued (pending) message for the given UUID and resyncs any open ChatWindow for it.
     */
    public static void addQueued(UUID uuid, String message) {
        synchronized (lockFor(uuid)) {
            Minecraft mc = Minecraft.getInstance();
            UUID myUuid = mc.player != null ? mc.player.getUUID() : null;
            ChatWindow.ChatMessage msg = ChatWindow.ChatMessage.create(message, true, System.currentTimeMillis(), myUuid, true);

            try (FileWriter writer = new FileWriter(fileFor(uuid), true)) {
                writer.write(GSON.toJson(msg) + "\n");
            } catch (IOException e) {
                throw new RuntimeException("Failed to queue message for " + uuid, e);
            }
        }

        ChatWindow.refreshIfOpen(uuid);
    }

    public static int deleteQueued(UUID uuid, String matchText) {
        int removed;
        synchronized (lockFor(uuid)) {
            File file = fileFor(uuid);
            if (!file.exists()) return 0;

            List<String> keep = new ArrayList<>();
            removed = 0;
            boolean matchedOnce = false;

            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    ChatWindow.ChatMessage msg;
                    try {
                        msg = GSON.fromJson(line, ChatWindow.ChatMessage.class);
                    } catch (Exception e) {
                        keep.add(line);
                        continue;
                    }

                    boolean isPending = msg != null && msg.pending;
                    boolean matches = isPending && (matchText == null
                        || (!matchedOnce && msg.message != null
                        && msg.message.toLowerCase(Locale.ROOT).contains(matchText.toLowerCase(Locale.ROOT))));

                    if (matches) {
                        removed++;
                        if (matchText != null) matchedOnce = true;
                        continue;
                    }

                    keep.add(line);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to read queue file for " + uuid, e);
            }

            if (removed > 0) {
                try {
                    writeAtomic(file, keep);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to rewrite queue file for " + uuid, e);
                }
            }
        }

        if (removed > 0) {
            ChatWindow.refreshIfOpen(uuid);
        }

        return removed;
    }

    /**
     * Atomically pops the single oldest pending message for uuid: reads the file, flips that
     * one line's pending flag to false, and writes the file back, all while holding this
     * uuid's lock
     */
    public static ChatWindow.ChatMessage popOldestPending(UUID uuid) {
        ChatWindow.ChatMessage popped;
        synchronized (lockFor(uuid)) {
            File file = fileFor(uuid);
            if (!file.exists()) return null;

            List<String> rewritten = new ArrayList<>();
            popped = null;

            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    ChatWindow.ChatMessage parsed;
                    try {
                        parsed = GSON.fromJson(line, ChatWindow.ChatMessage.class);
                    } catch (Exception e) {
                        rewritten.add(line);
                        continue;
                    }

                    if (popped == null && parsed != null && parsed.pending) {
                        popped = parsed;
                        parsed.pending = false;
                        rewritten.add(GSON.toJson(parsed));
                    } else {
                        rewritten.add(line);
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to read queue file for " + uuid, e);
            }

            if (popped == null) return null;

            try {
                writeAtomic(file, rewritten);
            } catch (IOException e) {
                throw new RuntimeException("Failed to rewrite queue file for " + uuid, e);
            }
        }

        return popped;
    }
}
