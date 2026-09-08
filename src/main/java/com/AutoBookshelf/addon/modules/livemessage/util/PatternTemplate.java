package com.AutoBookshelf.addon.modules.livemessage.util;

import java.util.regex.Pattern;

public final class PatternTemplate {
    private static final String USERNAME_MARKER = "\u0000PLAYER\u0000";
    private static final String USERNAME_REGEX = "([^:\\[\\]\\s]{3,16})";
    private static final String RANK_PREFIX = "(?:<[^>]+> )?";

    private PatternTemplate() {
    }

    public static Pattern compile(String template, boolean allowRankPrefix) {
        String trimmed = template == null ? "" : template.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        if (trimmed.regionMatches(true, 0, "regex:", 0, 6)) {
            return Pattern.compile(trimmed.substring(6).trim());
        }

        return Pattern.compile(toRegex(trimmed, allowRankPrefix), Pattern.CASE_INSENSITIVE);
    }

    private static String toRegex(String template, boolean allowRankPrefix) {
        String withMarker = template.replace("{player}", USERNAME_MARKER);
        withMarker = withMarker.replaceAll("(?i)\\bplayer\\b", USERNAME_MARKER);

        String[] parts = withMarker.split(Pattern.quote(USERNAME_MARKER), -1);
        StringBuilder regex = new StringBuilder("^\\s*");
        if (allowRankPrefix) {
            regex.append(RANK_PREFIX);
        }

        for (int i = 0; i < parts.length; i++) {
            regex.append(Pattern.quote(parts[i]));
            if (i < parts.length - 1) {
                regex.append(USERNAME_REGEX);
            }
        }

        if (template.endsWith(":")) {
            regex.append("\\s*(.*)");
        } else {
            regex.append("\\s+(.*)");
        }

        return regex.toString();
    }
}
