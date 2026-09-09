package com.AutoBookshelf.addon.modules.livemessage.util;

import com.AutoBookshelf.addon.modules.livemessage.LiveMessage;

import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Tracks a single global "last whisper sent" timestamp so the client leaves a grace
 * period between successive /msg sends, regardless of which ChatWindow (or the
 * vanilla manual chat) sent them. Server anti-spam timeouts generally only grow when
 * messages arrive back-to-back faster than their configured interval (commonly ~3s)
 */
public final class WhisperRateLimiter {
    private static volatile long lastSentAt = 0L;

    private static final Map<String, Deque<SentRecord>> RECENT_SELF_SENDS = new ConcurrentHashMap<>(); // CHANGED
    private static final long SELF_SEND_DEDUPE_MS = 15_000L;
    private static final int MAX_RECORDS_PER_USER = 8; // ADDED — bounds memory; old entries also age out via SELF_SEND_DEDUPE_MS

    private record SentRecord(String message, long at) {
    }

    private WhisperRateLimiter() {
    }

    // Call whenever an outgoing whisper is actually sent
    public static void recordSent() {
        lastSentAt = System.currentTimeMillis();
    }

    public static long remainingCooldownMs() {
        int cooldown = LiveMessage.INSTANCE != null ? LiveMessage.INSTANCE.whisperCooldown.get() : 3000;
        long elapsed = System.currentTimeMillis() - lastSentAt;
        return Math.max(0, cooldown - elapsed);
    }

    public static boolean isOnCooldown() {
        return remainingCooldownMs() > 0;
    }

    /**
     * Call immediately before sending a whisper that's already been explicitly recorded
     * elsewhere (ChatWindow.addMessage, QueueUtil.popOldestPending's pending=false mutation,
     * etc), with the exact recipient and message text that will go out.
     */
    public static void markSelfInitiated(String username, String message) {
        if (username == null || message == null) return;
        Deque<SentRecord> records = RECENT_SELF_SENDS.computeIfAbsent(
            username.toLowerCase(Locale.ROOT), k -> new ConcurrentLinkedDeque<>());
        records.addFirst(new SentRecord(message, System.currentTimeMillis()));
        while (records.size() > MAX_RECORDS_PER_USER) records.pollLast(); // CHANGED: bound the per-user backlog
    }

    /**
     * Checks whether (username, message) matches a self-initiated send recorded recently.
     * Intentionally NOT one-shot / consuming: both the send-detection path
     * (LivemessageMatcher.handleOutgoingCommand) and the echo-detection path
     * (LivemessageMatcher.handle) may see the same logical message, and each needs to
     * independently recognize it rather than racing to consume a single-use flag.
     * Entries age out naturally after SELF_SEND_DEDUPE_MS.
     */
    public static boolean isRecentSelfSend(String username, String message) {
        if (username == null || message == null) return false;
        Deque<SentRecord> records = RECENT_SELF_SENDS.get(username.toLowerCase(Locale.ROOT));
        if (records == null) return false;

        long now = System.currentTimeMillis();
        for (SentRecord record : records) { // CHANGED: scan instead of single-record lookup
            if (record.message().equals(message) && now - record.at() < SELF_SEND_DEDUPE_MS) {
                return true;
            }
        }
        return false;
    }
}
