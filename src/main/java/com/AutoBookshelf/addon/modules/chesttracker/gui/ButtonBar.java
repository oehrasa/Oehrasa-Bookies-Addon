// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.List;

/**
 * The row of buttons along the bottom of a screen. Buttons share one width when that fits;
 * otherwise each gets what its label needs, and if even that doesn't fit, they wrap onto more
 * rows. A label that doesn't fit its button scrolls sideways and reads clipped, which this
 * avoids at every GUI scale (the scaled screen is never narrower than 320).
 */
final class ButtonBar {
    static final int HEIGHT = 20;
    private static final int GAP = 4;
    /**
     * Space under the bottom row.
     */
    private static final int BOTTOM = 6;
    /**
     * Around the label: vanilla scrolls it once it's closer than 2 to either edge.
     */
    private static final int PADDING = 8;

    private ButtonBar() {
    }

    /**
     * Places the buttons in order, left to right and top to bottom, each at most {@code maxWidth}
     * unless its label needs more. Returns the y of the top row, where the content above should end.
     */
    static int layout(List<? extends AbstractWidget> buttons, Font font, int screenWidth, int screenHeight, int maxWidth) {
        int n = buttons.size();
        if (n == 0) return screenHeight - BOTTOM - HEIGHT;
        int room = screenWidth - 16;
        int[] need = new int[n];
        for (int i = 0; i < n; i++) need[i] = font.width(buttons.get(i).getMessage()) + PADDING;

        for (int rows = 1; rows <= n; rows++) {
            int perRow = (n + rows - 1) / rows;
            int[] widths = new int[n];
            boolean fits = true;
            for (int start = 0; start < n && fits; start += perRow) {
                fits = rowWidths(need, start, Math.min(n, start + perRow), screenWidth, room, maxWidth, widths)
                    || rows == n; // one per row: a label wider than the screen has to scroll
            }
            if (!fits) continue;
            int usedRows = (n + perRow - 1) / perRow;
            int top = screenHeight - BOTTOM - HEIGHT - (usedRows - 1) * (HEIGHT + GAP);
            for (int start = 0, row = 0; start < n; start += perRow, row++) {
                int end = Math.min(n, start + perRow);
                int total = GAP * (end - start - 1);
                for (int i = start; i < end; i++) total += widths[i];
                int x = (screenWidth - total) / 2;
                for (int i = start; i < end; i++) {
                    buttons.get(i).setRectangle(widths[i], HEIGHT, x, top + row * (HEIGHT + GAP));
                    x += widths[i] + GAP;
                }
            }
            return top;
        }
        throw new AssertionError("one button per row always fits");
    }

    /**
     * Widths for buttons [start, end) on one row; false if they don't fit even at their labels' width.
     */
    private static boolean rowWidths(int[] need, int start, int end, int screenWidth, int room, int maxWidth, int[] widths) {
        int count = end - start;
        int gaps = GAP * (count - 1);
        int widest = 0, sum = 0;
        for (int i = start; i < end; i++) {
            widest = Math.max(widest, need[i]);
            sum += need[i];
        }
        int equal = Math.max(widest, Math.min(maxWidth, (screenWidth - 40) / count));
        if (equal * count + gaps <= room) {
            for (int i = start; i < end; i++) widths[i] = equal;
            return true;
        }
        if (sum + gaps > room) {
            for (int i = start; i < end; i++) widths[i] = Math.min(need[i], room);
            return false;
        }
        // Each as wide as its label, with what's left shared out
        int extra = (room - sum - gaps) / count;
        for (int i = start; i < end; i++) widths[i] = need[i] + extra;
        return true;
    }
}
