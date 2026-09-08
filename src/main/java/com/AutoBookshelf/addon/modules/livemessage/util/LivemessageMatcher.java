package com.AutoBookshelf.addon.modules.livemessage.util;

import com.AutoBookshelf.addon.modules.livemessage.LiveMessage;
import com.AutoBookshelf.addon.modules.livemessage.gui.LivemessageGui;
import net.minecraft.text.Text;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LivemessageMatcher {
    private static final Pattern PM_COMMAND_PATTERN = Pattern.compile(
        "^/(?:msg|w|whisper|tell|pm|m|t)\\s+(\\S+)\\s+(.+)$",
        Pattern.CASE_INSENSITIVE
    );
    private static String lastHandledLine = null;
    private static long lastHandledTime = 0L;
    private static final long DEDUPE_WINDOW_MS = 50L;

    private LivemessageMatcher() {
    }

    public static Match tryMatch(String rawMessage) {
        String text = LivemessageUtil.normalizeChatLine(rawMessage);
        if (text.isEmpty()) {
            return null;
        }

        for (Pattern pattern : LivemessageUtil.FROM_PATTERNS) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.matches()) {
                return new Match(cleanUsername(matcher.group(1)), matcher.group(2).trim(), false);
            }
        }

        for (Pattern pattern : LivemessageUtil.TO_PATTERNS) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.matches()) {
                return new Match(cleanUsername(matcher.group(1)), matcher.group(2).trim(), true);
            }
        }

        logMiss(text);
        return null;
    }

    public static boolean handle(Text message) {
        if (message == null) {
            return false;
        }

        return handle(message.getString());
    }

    public static boolean handle(String rawMessage) {
        if (isDuplicateHandle(rawMessage)) {
            return false;
        }

        Match match = tryMatch(rawMessage);
        if (match == null) {
            return false;
        }

        if (match.outgoing() && WhisperRateLimiter.isRecentSelfSend(match.username(), match.message())) {
            markHandled(rawMessage);
            if (isDebugEnabled()) {
                LiveMessage.LOG.info("Suppressed self-echo DM to '{}': {}", match.username(), match.message());
            }
            return false;
        }

        if (!match.outgoing() && isBlockedAdvertiser(match)) {
            markHandled(rawMessage);
            if (isDebugEnabled()) {
                LiveMessage.LOG.info("Blocked advertiser DM from '{}': {}", match.username(), match.message());
            }
            return false;
        }

        markHandled(rawMessage);
        logHit(match, rawMessage);
        return LivemessageGui.newMessage(match.username(), match.message(), match.outgoing());
    }

    private static boolean isBlockedAdvertiser(Match match) {
        if (LiveMessage.INSTANCE == null || !LiveMessage.INSTANCE.blockAdvertisers.get()) {
            return false;
        }

        String lower = match.message().toLowerCase(Locale.ROOT);
        boolean matched = false;
        for (String pattern : LiveMessage.INSTANCE.blockedPatterns.get()) {
            if (!pattern.isBlank() && lower.contains(pattern.toLowerCase(Locale.ROOT))) {
                matched = true;
                break;
            }
        }
        if (!matched) return false;

        return meteordevelopment.meteorclient.systems.friends.Friends.get().get(match.username()) == null;
    }

    private static boolean isDuplicateHandle(String rawMessage) {
        long now = System.currentTimeMillis();
        return rawMessage != null
            && rawMessage.equals(lastHandledLine)
            && now - lastHandledTime < DEDUPE_WINDOW_MS;
    }

    private static void markHandled(String rawMessage) {
        lastHandledLine = rawMessage;
        lastHandledTime = System.currentTimeMillis();
    }

    public static boolean handleOutgoingCommand(String commandLine) {
        if (commandLine == null || commandLine.isBlank()) {
            return false;
        }

        String trimmed = commandLine.trim();
        String pmCommand = LiveMessage.INSTANCE != null ? LiveMessage.INSTANCE.getPmCommand().toLowerCase(Locale.ROOT) : "msg";
        Pattern customPattern = Pattern.compile(
            "^/?" + Pattern.quote(pmCommand) + "\\s+(\\S+)\\s+(.+)$",
            Pattern.CASE_INSENSITIVE
        );
        Matcher customMatcher = customPattern.matcher(trimmed);
        if (customMatcher.matches()) {
            String username = customMatcher.group(1).trim();
            String message = customMatcher.group(2).trim();
            // Skip re-recording if this send(msg) came from our own code (ChatWindow's immediate
            // send/flush, or LiveMessage's background flush) as those paths already explicitly
            // saved the message themselves. Checked by content+recipient rather than a one-shot
            // flag so the later server echo (handled in handle() above) can independently make
            // the same determination.
            boolean selfInitiated = WhisperRateLimiter.isRecentSelfSend(username, message);
            if (!selfInitiated) {
                WhisperRateLimiter.recordSent();
                logHit(new Match(username, message, true), trimmed);
                LivemessageGui.newMessage(username, message, true);
            }
            return true;
        }

        Matcher fallbackMatcher = PM_COMMAND_PATTERN.matcher(trimmed.startsWith("/") ? trimmed : "/" + trimmed);
        if (fallbackMatcher.matches()) {
            String username = fallbackMatcher.group(1).trim();
            String message = fallbackMatcher.group(2).trim();
            boolean selfInitiated = WhisperRateLimiter.isRecentSelfSend(username, message);
            if (!selfInitiated) {
                WhisperRateLimiter.recordSent();
                logHit(new Match(username, message, true), trimmed);
                LivemessageGui.newMessage(username, message, true);
            }
            return true;
        }

        return false;
    }

    private static void logHit(Match match, String rawMessage) {
        if (isDebugEnabled()) {
            LiveMessage.LOG.info(
                "Captured {} DM from '{}': {} (raw: {})",
                match.outgoing() ? "outgoing" : "incoming",
                match.username(),
                match.message(),
                rawMessage
            );
        }
    }

    private static void logMiss(String normalized) {
        if (!isDebugEnabled()) {
            return;
        }

        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.contains("whisper")
            || lower.contains("from ")
            || lower.contains(" to ")
            || lower.contains("->")
            || lower.contains("msg")) {
            LiveMessage.LOG.info("Unmatched potential DM line: '{}'", normalized);
        }
    }

    private static boolean isDebugEnabled() {
        return LiveMessage.INSTANCE != null && LiveMessage.INSTANCE.debugCapture.get();
    }

    private static String cleanUsername(String username) {
        if (username == null) {
            return "";
        }

        return username.trim().replaceAll("^[<\\[]+|[>\\]]+$", "");
    }

    public record Match(String username, String message, boolean outgoing) {
    }
}
