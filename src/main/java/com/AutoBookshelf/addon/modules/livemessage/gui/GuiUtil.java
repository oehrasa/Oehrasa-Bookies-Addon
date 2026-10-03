package com.AutoBookshelf.addon.modules.livemessage.gui;

import com.AutoBookshelf.addon.modules.livemessage.util.LivemessageUtil;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.language.FormattedBidiReorder;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.text.BreakIterator;
import java.util.*;

public class GuiUtil {

    public static int hslColor(float h, float s, float l) {
        float r;
        float g;
        float b;
        if (s == 0.0F) {
            b = l;
            g = l;
            r = l;
        } else {
            float q = l < 0.5 ? l * (1.0F + s) : l + s - l * s;
            float p = 2.0F * l - q;
            r = hue2rgb(p, q, h + 0.33333334F);
            g = hue2rgb(p, q, h);
            b = hue2rgb(p, q, h - 0.33333334F);
        }

        return getRGB(Math.round(r * 255.0F), Math.round(g * 255.0F), Math.round(b * 255.0F));
    }

    private static float hue2rgb(float p, float q, float h) {
        if (h < 0.0F) {
            h++;
        }

        if (h > 1.0F) {
            h--;
        }

        if (6.0F * h < 1.0F) {
            return p + (q - p) * 6.0F * h;
        } else if (2.0F * h < 1.0F) {
            return q;
        } else {
            return 3.0F * h < 2.0F ? p + (q - p) * 6.0F * (0.6666667F - h) : p;
        }
    }

    public static int getRGB(int r, int g, int b) {
        return getRGBA(r, g, b, 255);
    }

    public static int getSingleRGB(int color) {
        return getRGBA(color, color, color, 255);
    }

    public static int getRGBA(int r, int g, int b, int a) {
        return a << 24 | r << 16 | g << 8 | b;
    }

    /**
     * Replaces the alpha channel of an ARGB color, keeping the RGB unchanged.
     */
    public static int withAlpha(int argb, int alpha) {
        return (alpha << 24) | (argb & 0xFFFFFF);
    }

    public static int getWindowColor(UUID uuid) {
        try {
            File settingsFile = LivemessageUtil.LIVEMESSAGE_FOLDER.resolve("mainwindow.json").toFile();
            if (settingsFile.exists()) {
                Gson gson = new Gson();
                try (FileReader reader = new FileReader(settingsFile)) {
                    JsonObject json = gson.fromJson(reader, JsonObject.class);
                    if (json != null && json.has("customColor")) {
                        int mainWindowColor = json.get("customColor").getAsInt();
                        if (mainWindowColor > 0) {
                            return mainWindowColor;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        float uuidvalue1 = Integer.parseInt(uuid.toString().substring(0, 2), 16) / 255.0F;
        float uuidvalue2 = Integer.parseInt(uuid.toString().substring(2, 4), 16) / 255.0F;
        return hslColor(uuidvalue1, 0.6F + uuidvalue2 * 0.15F, 0.5F) | 0xFF000000;
    }

    public static double easeOutQuint(double t) {
        return 1.0 - Math.pow(1.0 - t, 5.0);
    }

    public static int fade(int argb) {
        float f = LivemessageGui.currentFadeAlpha;
        if (f >= 0.999F) {
            return argb;
        }

        int a = argb >>> 24 & 0xFF;
        if (a == 0) {
            a = 255;
        }

        a = Math.max(0, Math.min(255, Math.round(a * f)));
        return a << 24 | argb & 16777215;
    }

    public static void drawRect(GuiGraphicsExtractor context, int x, int y, int w, int h, int color) {
        context.fill(x, y, x + w, y + h, fade(color));
    }

    /**
     * Trims a line to fit within {@code maxWidth} without splitting a word. Breaks at the last
     * space that fits; a single word wider than the line is broken mid-word. Cuts are
     * grapheme-safe so combining marks, surrogate pairs and ZWJ emoji are never split; for
     * plain BMP Latin this produces exactly the same string as a code-unit trim.
     */
    // Zero/near-zero-width runs (ZWSP, format marks) never trip a pixel-based trim, so a single
    // sender-supplied message could stay one giant RenderedLine and make the per-unit layout
    // measuring quadratic. Cap lines on grapheme count as well; normal text stays untouched.
    private static final int MAX_LINE_GRAPHEMES = 256;

    public static String wrapWordBoundary(Font renderer, String text, int maxWidth) {
        if (text.isEmpty()) {
            return text;
        }
        text = capGraphemeCount(text, MAX_LINE_GRAPHEMES);
        if (renderer.width(text) <= maxWidth) {
            return text;
        }

        int fitLen = renderer.plainSubstrByWidth(text, maxWidth).length();
        if (fitLen >= text.length()) {
            return text;
        }

        int fitGraphemeEnd = snapToGraphemeBoundary(text, fitLen);
        if (fitGraphemeEnd >= text.length()) {
            return text;
        }
        if (fitGraphemeEnd == 0) {
            // One grapheme alone may exceed maxWidth; still emit it so callers cannot loop forever.
            return text.substring(0, graphemeEnd(text, 0));
        }

        if (text.charAt(fitGraphemeEnd - 1) == ' ') {
            return text.substring(0, fitGraphemeEnd);
        }
        if (text.charAt(fitGraphemeEnd) == ' ') {
            return text.substring(0, fitGraphemeEnd + 1);
        }

        int lastSpace = text.lastIndexOf(' ', fitGraphemeEnd - 1);
        if (lastSpace > 0) {
            return text.substring(0, lastSpace + 1);
        }

        return text.substring(0, fitGraphemeEnd); // single long word, mid-word break is unavoidable
    }

    private static String capGraphemeCount(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        BreakIterator iterator = BreakIterator.getCharacterInstance();
        iterator.setText(text);
        int end = iterator.first();
        for (int count = 0; count < max; count++) {
            int next = iterator.next();
            if (next == BreakIterator.DONE) {
                return text;
            }
            end = next;
        }
        return text.substring(0, end);
    }

    private static int snapToGraphemeBoundary(String text, int index) {
        if (index <= 0 || index >= text.length()) {
            return index;
        }
        BreakIterator iterator = BreakIterator.getCharacterInstance();
        iterator.setText(text);
        int end = iterator.following(index - 1);
        return end == BreakIterator.DONE ? text.length() : end;
    }

    private static int graphemeEnd(String text, int from) {
        BreakIterator iterator = BreakIterator.getCharacterInstance();
        iterator.setText(text);
        int end = iterator.following(from);
        if (end == BreakIterator.DONE) {
            end = text.length();
        }
        return Math.max(end, from + 1);
    }

    public static int utf8ByteLength(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * True when the line's base direction is right-to-left, determined by its first strong
     * directional character (UAX#9 P2/P3): the first L forces LTR, the first R/AL forces RTL,
     * and a line with no strong character defaults to LTR. This matches the base direction
     * FormattedBidiReorder/bidirectionalShaping() derive, so caret math and the drawn order
     * stay consistent even for mixed-direction lines.
     */
    public static boolean isRtlLine(String text) {
        for (int i = 0; i < text.length(); i++) {
            byte direction = Character.getDirectionality(text.charAt(i));
            if (direction == Character.DIRECTIONALITY_LEFT_TO_RIGHT) {
                return false;
            }
            if (direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                || direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
                return true;
            }
        }
        return false;
    }

    /**
     * X offset (from the line's left edge) at which the caret at the given logical
     * {@code column} should be drawn. RTL lines anchor the logical start at the right edge.
     */
    public static int caretX(Font renderer, String line, int column, boolean rtl) {
        int col = Math.max(0, Math.min(column, line.length()));
        if (rtl) {
            return renderer.width(line) - renderer.width(line.substring(0, col));
        }
        return renderer.width(line.substring(0, col));
    }

    /**
     * Maps a pixel column (relative to the line's left edge, in [0, width]) to the logical
     * caret index nearest that pixel, honoring the line's base direction and never landing
     * inside a grapheme cluster.
     */
    public static int columnAtPixel(Font renderer, String line, int pixelX, boolean rtl) {
        if (pixelX <= 0 || line.isEmpty()) {
            return 0;
        }
        int width = renderer.width(line);
        double x = rtl ? width - pixelX : pixelX;

        BreakIterator iterator = BreakIterator.getCharacterInstance();
        iterator.setText(line);
        int clusterStart = iterator.first();
        double acc = 0;
        while (true) {
            int clusterEnd = iterator.next();
            if (clusterEnd == BreakIterator.DONE) {
                return line.length();
            }
            double clusterWidth = renderer.width(line.substring(clusterStart, clusterEnd));
            if (x < acc + clusterWidth / 2.0) {
                return clusterStart;
            }
            acc += clusterWidth;
            clusterStart = clusterEnd;
        }
    }

    /**
     * Visual x-extent occupied by the logical character range [start, end) of a line after
     * Minecraft's own bidi reorder (FormattedBidiReorder - the same code that renders the
     * line). Pixel positions come from measuring the reordered string's prefixes, so the span
     * lands exactly where the glyphs are drawn. A bidi-interleaved range returns its outer
     * bounding box instead of several disjoint rects.
     */
    public static int[] visualSpanForRange(Font renderer, String line, int start, int end) {
        if (line.isEmpty()) {
            return new int[]{0, 0};
        }

        VisualLayout layout = visualLayout(renderer, line);
        int[] units = layout.unitToLogical;
        if (units.length == 0) {
            return new int[]{renderer.width(line.substring(0, start)), renderer.width(line.substring(0, end))};
        }

        int left = Integer.MAX_VALUE;
        int right = -1;
        for (int i = 0; i < units.length; i++) {
            if (units[i] >= start && units[i] < end) {
                left = Math.min(left, layout.cumWidths[i]);
                right = Math.max(right, layout.cumWidths[i + 1]);
            }
        }
        if (left == Integer.MAX_VALUE) {
            return new int[]{renderer.width(line.substring(0, start)), renderer.width(line.substring(0, end))};
        }
        return new int[]{left, right};
    }

    private static final int VISUAL_LAYOUT_CACHE_MAX = 128;
    private static final Map<String, VisualLayout> visualLayoutCache =
        new LinkedHashMap<String, VisualLayout>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, VisualLayout> eldest) {
                return size() > VISUAL_LAYOUT_CACHE_MAX;
            }
        };

    // Visual-order layout of one line: how the glyphs are drawn left-to-right, plus the exact
    // pixel offset of every visual boundary. Widths come from measuring the reordered string
    // itself (the same order FormattedBidiReorder draws), so RTL blocks span exactly as drawn.
    private static final class VisualLayout {
        final int[] unitToLogical; // visual position -> logical code-unit index of that glyph
        final int[] cumWidths;     // cumWidths[i] = x-offset (pixels) of visual boundary i

        VisualLayout(int[] unitToLogical, int[] cumWidths) {
            this.unitToLogical = unitToLogical;
            this.cumWidths = cumWidths;
        }
    }

    private static VisualLayout visualLayout(Font renderer, String line) {
        VisualLayout cached = visualLayoutCache.get(line);
        if (cached != null) {
            return cached;
        }

        FormattedCharSequence reordered = FormattedBidiReorder.reorder(Component.literal(line), isRtlLine(line));
        List<Integer> visualToLogical = new ArrayList<>();
        StringBuilder visual = new StringBuilder();
        // char offset just past each code point, so substring() below never
        // splits a surrogate pair or treats a code-point index as a char index
        List<Integer> visualCharEnds = new ArrayList<>();
        reordered.accept((index, style, codePoint) -> {
            visualToLogical.add(index);
            visual.appendCodePoint(codePoint);
            visualCharEnds.add(visual.length());
            return true;
        });

        // The reorder codepoints are the SHAPED glyphs Minecraft actually draws (joining ligatures
        // like lam-alef collapse several logical units into one visual glyph), so the string we
        // measure must come from those codepoints, never from re-picking logical chars.
        VisualLayout layout;
        int n = visualToLogical.size();
        if (n == 0) {
            layout = new VisualLayout(new int[0], new int[]{0});
        } else {
            int[] unitToLogical = new int[n];
            for (int i = 0; i < n; i++) {
                unitToLogical[i] = visualToLogical.get(i);
            }
            int[] cumWidths = new int[n + 1];
            cumWidths[0] = 0;
            for (int i = 0; i < n; i++) {
                cumWidths[i + 1] = renderer.width(visual.substring(0, visualCharEnds.get(i)));
            }
            layout = new VisualLayout(unitToLogical, cumWidths);
        }

        visualLayoutCache.put(line, layout);
        return layout;
    }

    public static void clearVisualLayoutCache() {
        visualLayoutCache.clear();
        mirrorCache.clear();
    }

    private static final int MIRROR_CACHE_MAX = 128;
    private static final Map<String, String> mirrorCache =
        new LinkedHashMap<String, String>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > MIRROR_CACHE_MAX;
            }
        };

    // Returns the string to actually draw/measure: Minecraft's own shaped + reordered form
    // (Font#bidirectionalShaping), exactly as it would render on an RTL-locale client. Pure-LTR
    // text passes through unchanged (a provable no-op for Latin output), and an already-RTL
    // client language is left alone so MC's own mirroring is never doubled.
    public static String bidiDisplayText(Font renderer, String text) {
        if (text == null || text.isEmpty() || renderer == null) {
            return text;
        }
        if (renderer.isBidirectional() || !isRtlLine(text)) {
            return text;
        }
        String cached = mirrorCache.get(text);
        if (cached != null) {
            return cached;
        }
        String mirrored = renderer.bidirectionalShaping(text);
        mirrorCache.put(text, mirrored);
        return mirrored;
    }

    public static void drawTooltip(GuiGraphicsExtractor context, String text, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        int width = mc.font.width(text) + 8;
        int height = 12;
        context.fill(x, y, x + width, y + height, fade(getRGBA(0, 0, 0, 200)));
        context.fill(x, y, x + width, y + 1, fade(getRGBA(80, 80, 255, 255)));
        context.fill(x, y + height - 1, x + width, y + height, fade(getRGBA(80, 80, 255, 255)));
        context.fill(x, y, x + 1, y + height, fade(getRGBA(80, 80, 255, 255)));
        context.fill(x + width - 1, y, x + width, y + height, fade(getRGBA(80, 80, 255, 255)));
        context.text(mc.font, text, x + 4, y + 2, fade(getSingleRGB(255)), false);
    }

    public static class QuintAnimation {
        public long startTime = 0L;
        public float startValue = 0.0F;
        public float currentValue = 0.0F;
        public int animationLength = 1000;

        public float animate(float val) {
            if (val != this.currentValue) {
                this.startValue = this.getState();
                this.currentValue = val;
                this.startTime = System.currentTimeMillis();
            }

            return this.getState();
        }

        public float getState() {
            if (System.currentTimeMillis() - this.startTime > this.animationLength) {
                return this.currentValue;
            }

            double progress = (double) (System.currentTimeMillis() - this.startTime) / this.animationLength;
            return (float) (this.startValue - GuiUtil.easeOutQuint(progress) * (this.startValue - this.currentValue));
        }

        public QuintAnimation(int len, float initialValue) {
            this.animationLength = len;
            this.startValue = initialValue;
            this.currentValue = initialValue;
        }
    }
}
