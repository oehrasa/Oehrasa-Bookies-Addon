package com.AutoBookshelf.addon.modules.livemessage.util;

import com.AutoBookshelf.addon.modules.livemessage.LiveMessage;
import com.AutoBookshelf.addon.modules.livemessage.gui.ChatWindow;
import com.AutoBookshelf.addon.modules.livemessage.gui.LivemessageGui;
import com.AutoBookshelf.addon.utils.QueueUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LivemessageMatcher {
    private static final Pattern PM_COMMAND_PATTERN = Pattern.compile(
        "^/(?:msg|w|whisper|tell|pm|m|t)\\s+(\\S+)\\s+(.+)$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern WHISPERS_DISABLED_PATTERN = Pattern.compile(
        "(?i).*(?:whispers?\\s+disabled|whispering\\s+(?:is|are)\\s+disabled).*"
    );
    private static final Pattern USERNAME_EDGE_MARKERS = Pattern.compile("^[<\\[]+|[>\\]]+$");
    // Directed-at-the-player cues for the rejected-capture dump: without at least one of these an
    // unmatched line is plain public chat, not a failed whisper, and is skipped. Deliberately
    // localization-free (kept the words-as-words plus arrows) so localized/homoglyph whispers are
    // still caught whenever they address the player.
    private static final Pattern DIRECTED_CUES_PATTERN = Pattern.compile("\\b(?:you|me)\\b|->|→|⟶", Pattern.CASE_INSENSITIVE);
    private static String lastHandledLine = null;
    private static long lastHandledTime = 0L;
    private static final long DEDUPE_WINDOW_MS = 50L;

    // Budget guard against user-supplied (regex:) patterns with catastrophic backtracking
    // applied to attacker-controlled chat lines. Java regex can't be aborted mid-match, so
    // when a match blows the budget we drop that pattern from the active lists to stop
    // repeated client freezes. The template-generated patterns are linear and never trigger this.
    private static final long MATCH_BUDGET_NANOS = 10_000_000L;
    private static final long REDOS_WARN_INTERVAL_MS = 10_000L;

    // Consecutive over-budget matches a user pattern must accumulate before it is
    // discarded. A single slow run is usually a GC pause or JIT warmup rather than a
    // genuinely pathological regex, so one overrun must not delete a pattern the user
    // configured deliberately.
    private static final int OVERRUNS_BEFORE_REMOVAL = 3;
    private static final Map<Pattern, Integer> overrunCounts = new HashMap<>();
    private static long lastReDoSWarn = 0L;

    private LivemessageMatcher() {
    }

    private static Matcher safeMatcher(Iterator<Pattern> patternIterator, Pattern pattern, String text) {
        long start = System.nanoTime();
        Matcher matcher = pattern.matcher(text);
        boolean matched = matcher.matches();
        if (System.nanoTime() - start > MATCH_BUDGET_NANOS) {
            long now = System.currentTimeMillis();
            if (now - lastReDoSWarn > REDOS_WARN_INTERVAL_MS) {
                lastReDoSWarn = now;
                LiveMessage.LOG.warn("Livemessage pattern '{}' took longer than {}ms to match; removing it to avoid client freezes. Check your regex patterns.", pattern, MATCH_BUDGET_NANOS / 1_000_000L);
            }
            // Built-in defaults are never removed, and a user-supplied pattern has to
            // overrun several times in a row before it is discarded - one slow run is
            // usually a GC pause or JIT warmup, not a pathological regex.
            boolean removable = patternIterator != null && !LivemessageUtil.DEFAULT_PATTERNS.contains(pattern);
            if (removable) {
                int overruns = overrunCounts.merge(pattern, 1, Integer::sum);
                if (overruns >= OVERRUNS_BEFORE_REMOVAL) {
                    LiveMessage.LOG.warn("Livemessage pattern '{}' overran the match budget {} times in a row; removing it to avoid client freezes. Check your regex patterns.", pattern, overruns);
                    patternIterator.remove();
                    overrunCounts.remove(pattern);
                }
            }
            return null;
        }
        // A run inside the budget clears the streak, so only consecutive overruns count.
        overrunCounts.remove(pattern);
        return matched ? matcher : null;
    }

    public static Match tryMatch(String rawMessage) {
        String text = LivemessageUtil.normalizeChatLine(rawMessage);
        if (text.isEmpty()) {
            return null;
        }

        Iterator<Pattern> fromIterator = LivemessageUtil.FROM_PATTERNS.iterator();
        while (fromIterator.hasNext()) {
            Pattern pattern = fromIterator.next();
            Matcher matcher = safeMatcher(fromIterator, pattern, text);
            if (matcher == null) continue;
            return new Match(cleanUsername(matcher.group(1)), matcher.group(2).trim(), false);
        }

        Iterator<Pattern> toIterator = LivemessageUtil.TO_PATTERNS.iterator();
        while (toIterator.hasNext()) {
            Pattern pattern = toIterator.next();
            Matcher matcher = safeMatcher(toIterator, pattern, text);
            if (matcher == null) continue;
            return new Match(cleanUsername(matcher.group(1)), matcher.group(2).trim(), true);
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

        if (isWhispersDisabledLine(rawMessage)) {
            markHandled(rawMessage);
            handleWhispersDisabled();
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

        if (!match.outgoing() && isUnverifiedSender(match)) {
            markHandled(rawMessage);
            if (isDebugEnabled()) {
                LiveMessage.LOG.info("Dropped unverified/suspicious DM from '{}': {}", match.username(), match.message());
            }
            return false;
        }

        if (!match.outgoing() && isNonFriendBlocked(match)) {
            markHandled(rawMessage);
            if (isDebugEnabled()) {
                LiveMessage.LOG.info("Dropped non-friend DM from '{}': {}", match.username(), match.message());
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

    private static boolean isNonFriendBlocked(Match match) {
        if (LiveMessage.INSTANCE == null || !LiveMessage.INSTANCE.friendsOnlyMessages.get()) {
            return false;
        }
        return meteordevelopment.meteorclient.systems.friends.Friends.get().get(match.username()) == null;
    }

    private static boolean isUnverifiedSender(Match match) {
        // Both sender checks belong to verify-sender-in-tab (its description covers
        // the username check and the tab-list check together). With it off the user
        // has opted out of sender verification, so a legitimate DM from a server
        // that allows nonstandard names must not be dropped as a spoof.
        if (LiveMessage.INSTANCE == null || !LiveMessage.INSTANCE.verifySenderInTab.get()) {
            return false;
        }
        if (!LivemessageUtil.isSafeUsername(match.username())) {
            return true;
        }
        return !LivemessageUtil.isPlayerInTabList(match.username());
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

    private static boolean isWhispersDisabledLine(String rawMessage) {
        if (rawMessage == null) {
            return false;
        }
        return WHISPERS_DISABLED_PATTERN.matcher(LivemessageUtil.normalizeChatLine(rawMessage)).matches();
    }

    private static void handleWhispersDisabled() {
        String username = WhisperRateLimiter.lastSelfSendUser();
        String message = WhisperRateLimiter.lastSelfSendMessage();
        long sentAt = WhisperRateLimiter.lastSelfSendAt();
        if (username == null || message == null) {
            return;
        }
        // A "whispers disabled" line can be typed in public chat by anyone; only honor it if it
        // arrives right after OUR own send, within WhisperRateLimiter's rejection window.
        if (!WhisperRateLimiter.isLastSelfSend(username, message)) {
            return;
        }

        LiveProfileCache.LiveProfile profile = LiveProfileCache.getLiveprofileFromName(username);
        UUID uuid = profile != null ? profile.uuid : null;
        if (uuid == null) {
            return;
        }

        int flagged = QueueUtil.markNotAccepted(uuid, message, sentAt);
        if (isDebugEnabled()) {
            LiveMessage.LOG.info("Whisper rejected ('whispers disabled') for '{}', flagged {} message(s): {}", username, flagged, message);
        }
        ChatWindow.refreshIfOpen(uuid);
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

    private static volatile String cachedPmCommand = null;
    private static volatile Pattern cachedPmPattern = null;

    private static volatile String cachedSelfName = null;
    private static volatile Pattern cachedSelfNamePattern = null;

    private static Pattern selfNamePattern() {
        MinecraftClient mc = MinecraftClient.getInstance();
        String name = (mc != null && mc.getSession() != null) ? mc.getSession().getUsername() : "";
        Pattern pattern = cachedSelfNamePattern;
        if (name.isEmpty()) {
            return null;
        }
        if (pattern == null || !name.equals(cachedSelfName)) {
            pattern = Pattern.compile("\\b" + Pattern.quote(name) + "\\b", Pattern.CASE_INSENSITIVE);
            cachedSelfName = name;
            cachedSelfNamePattern = pattern;
        }
        return pattern;
    }

    private static boolean isWhisperLike(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return false;
        }
        if (DIRECTED_CUES_PATTERN.matcher(normalized).find()) {
            return true;
        }
        Pattern selfName = selfNamePattern();
        return selfName != null && selfName.matcher(normalized).find();
    }

    private static Pattern pmCommandPattern() {
        String pmCommand = (LiveMessage.INSTANCE != null ? LiveMessage.INSTANCE.getPmCommand() : "msg").toLowerCase(Locale.ROOT);
        Pattern pattern = cachedPmPattern;
        if (!pmCommand.equals(cachedPmCommand) || pattern == null) {
            pattern = Pattern.compile("^/?" + Pattern.quote(pmCommand) + "\\s+(\\S+)\\s+(.+)$", Pattern.CASE_INSENSITIVE);
            cachedPmCommand = pmCommand;
            cachedPmPattern = pattern;
        }
        return pattern;
    }

    public static boolean handleOutgoingCommand(String commandLine) {
        if (commandLine == null || commandLine.isBlank()) {
            return false;
        }

        String trimmed = commandLine.trim();
        Matcher customMatcher = pmCommandPattern().matcher(trimmed);
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

    // Rate-limited dump of every whisper-like line that failed every DM pattern,
    private static final long MISS_LOG_INTERVAL_MS = 1000;
    private static long lastMissLogAt = 0L;
    private static int missedInWindow = 0;

    private static void logMiss(String normalized) {
        // Fires only under the dedicated rejected-capture switch, and only for lines that
        // plausibly address the player (mentions us, says "you"/"me", or has an arrow).
        if (LiveMessage.INSTANCE == null
            || !LiveMessage.INSTANCE.debugRejectedCapture.get()
            || !isWhisperLike(normalized)) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastMissLogAt < MISS_LOG_INTERVAL_MS) {
            missedInWindow++;
            return;
        }

        if (missedInWindow > 0) {
            LiveMessage.INSTANCE.info("... and %d more unmatched lines in the previous second", missedInWindow);
        }
        LiveMessage.INSTANCE.info("Unmatched potential DM line: '%s'", normalized);
        lastMissLogAt = now;
        missedInWindow = 0;
    }

    private static boolean isDebugEnabled() {
        return LiveMessage.INSTANCE != null && LiveMessage.INSTANCE.debugCapture.get();
    }

    private static String cleanUsername(String username) {
        if (username == null) {
            return "";
        }

        return USERNAME_EDGE_MARKERS.matcher(username.trim()).replaceAll("");
    }

    public record Match(String username, String message, boolean outgoing) {
    }
}
