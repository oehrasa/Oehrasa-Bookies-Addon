/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.utils;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.FormattedText;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared page-fitting math used by both the import pipeline and the remote preview,
 * so the preview renders exactly what the importer will sign into books: same font
 * metrics, same word/char splitting, same per-page and per-book limits.
 *
 * <p>All methods are static and expect to run on the render thread (the font lookup
 * uses the live font). Kept dependency-free of Meteor's APIs so any screen or task can
 * call it.
 */
public final class BookPages {
    public static final int MAX_PAGE_CHARS = 1024;
    public static final int MAX_PAGE_WIDTH = 114;
    public static final int MAX_PAGE_HEIGHT = 128;
    public static final String LINE_SEPARATOR = "\n";

    private BookPages() {
    }

    /**
     * Converts a list of raw file lines into the same page list the importer produces.
     */
    public static List<String> convertLinesToPages(List<String> lines) {
        List<String> pages = new ArrayList<>();
        StringBuilder currentPage = new StringBuilder();
        for (String line : lines) {
            processLine(line, currentPage, pages);
        }
        if (!currentPage.isEmpty()) {
            pages.add(currentPage.toString());
        }
        return pages;
    }

    private static void processLine(String line, StringBuilder currentPage, List<String> pages) {
        boolean lineFitsOnCurrentPage = fitsOnPage(currentPage + line + LINE_SEPARATOR);
        if (lineFitsOnCurrentPage) {
            addLineToPage(line, currentPage);
            return;
        }
        boolean lineFitsOnNewPage = fitsOnPage(line);
        if (lineFitsOnNewPage) {
            startNewPage(currentPage, pages);
            addLineToPage(line, currentPage);
            return;
        }
        processLineLongerThanPage(line, currentPage, pages);
    }

    private static void addLineToPage(String line, StringBuilder currentPage) {
        currentPage.append(line).append(LINE_SEPARATOR);
    }

    private static void startNewPage(StringBuilder currentPage, List<String> pages) {
        if (currentPage.length() > 0) {     // only add if not empty
            pages.add(currentPage.toString());
        }
        currentPage.setLength(0);
    }

    private static void processLineLongerThanPage(String line, StringBuilder currentPage, List<String> pages) {
        String[] words = line.split(" ");
        StringBuilder linePartForCurrentPage = new StringBuilder();
        for (String word : words) {
            boolean pageHasRoomForWord = fitsOnPage(currentPage.toString() + linePartForCurrentPage.toString() + word + " ");
            if (pageHasRoomForWord) {
                linePartForCurrentPage.append(word).append(" ");
                continue;
            }
            boolean pageHasContents = !currentPage.isEmpty() || !linePartForCurrentPage.isEmpty();
            if (pageHasContents) {
                currentPage.append(linePartForCurrentPage);
                startNewPage(currentPage, pages);
                linePartForCurrentPage.setLength(0);
            }
            boolean blankPageCanHoldWord = fitsOnPage(word);
            if (blankPageCanHoldWord) {
                linePartForCurrentPage.append(word).append(" ");
                continue;
            }
            splitLongWordAcrossPages(word, currentPage, pages);
        }
        String leftover = linePartForCurrentPage.toString();
        if (!leftover.isEmpty() || !currentPage.isEmpty()) {
            if (!fitsOnPage(currentPage.toString() + leftover + LINE_SEPARATOR)) {
                startNewPage(currentPage, pages);
            }
            addLineToPage(leftover, currentPage);
        }
    }

    private static void splitLongWordAcrossPages(String word, StringBuilder currentPage, List<String> pages) {
        char[] chars = word.toCharArray();
        for (char c : chars) {
            if (!fitsOnPage(currentPage.toString() + c)) {
                startNewPage(currentPage, pages);
            }
            currentPage.append(c);
        }
        if (fitsOnPage(currentPage + " ")) {
            currentPage.append(" ");
        } else {
            startNewPage(currentPage, pages);
        }
    }

    private static boolean fitsOnPage(String text) {
        return text.length() < MAX_PAGE_CHARS
            && Minecraft.getInstance().font.wordWrapHeight(FormattedText.of(text), MAX_PAGE_WIDTH) <= MAX_PAGE_HEIGHT;
    }

    /**
     * Truncates the base title to the length the importer uses when signing.
     */
    public static String truncateBaseTitle(String baseTitle) {
        if (baseTitle == null) return "";
        if (baseTitle.length() <= 32) return baseTitle;
        return baseTitle.substring(0, 32);
    }

    /**
     * Title of a specific part of a multi-book import, matching the import's naming.
     */
    public static String partTitle(String baseTitle, int part, int totalParts) {
        if (totalParts <= 1) return baseTitle;
        int digits = Math.max(2, String.valueOf(totalParts).length());
        return baseTitle + "-" + String.format("%0" + digits + "d", part);
    }
}
