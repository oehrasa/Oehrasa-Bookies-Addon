// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker.search;

import java.util.regex.Pattern;

/**
 * Typo-tolerant matching, used only when a search finds nothing exactly ("wheet" -> wheat).
 * Every word of the query must appear in the text, or be within a small edit distance of one of
 * its words (or of a word's start, so "diamon" still finds diamond_sword).
 */
public final class Fuzzy {
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    private Fuzzy() {
    }

    /**
     * The words of a lowercase text, for {@link #matches(String, String[], String[])}.
     */
    public static String[] words(String text) {
        return NON_WORD.split(text);
    }

    /**
     * Both arguments lowercase. Splits both every call: fine for a few dozen trade offers.
     */
    public static boolean matches(String text, String query) {
        return matches(text, words(text), words(query));
    }

    /**
     * The fast form for big searches: the item's words are split once when it's indexed and the
     * query's once per search, so a pass over thousands of items allocates almost nothing.
     */
    public static boolean matches(String text, String[] words, String[] queryWords) {
        for (String q : queryWords) {
            if (q.isEmpty() || text.contains(q)) continue;
            int max = q.length() >= 7 ? 2 : q.length() >= 4 ? 1 : 0;
            if (max == 0 || !nearWord(words, q, max)) return false;
        }
        return true;
    }

    private static boolean nearWord(String[] words, String q, int max) {
        for (String w : words) {
            if (w.length() < q.length() - max) continue; // too short to be within max edits
            if (Math.abs(w.length() - q.length()) <= max && distance(w, w.length(), q, max) <= max) return true;
            if (w.length() > q.length() && distance(w, q.length(), q, max) <= max) return true;
        }
        return false;
    }

    /**
     * Edit distance between the first {@code aLen} characters of a and b, counting a swap of two
     * neighbouring letters as one edit; stops early once every path is past max.
     */
    static int distance(String a, int aLen, String b, int max) {
        int[][] d = new int[aLen + 1][b.length() + 1];
        for (int i = 0; i <= aLen; i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= aLen; i++) {
            int rowMin = Integer.MAX_VALUE;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int v = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    v = Math.min(v, d[i - 2][j - 2] + 1);
                }
                d[i][j] = v;
                rowMin = Math.min(rowMin, v);
            }
            if (rowMin > max) return max + 1;
        }
        return d[aLen][b.length()];
    }
}
