package com.AutoBookshelf.addon.modules.livemessage.gui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain-text box with embedded '\n', pixel-wrapped for rendering. Not backed by
 * TextFieldWidget, Minecraft's widget has no multiline concept, so key/char handling
 * is done directly against the raw keyCode/typedChar ChatWindow already receives.
 * <p>
 * Selection is an anchor offset into the raw text: -1 means no selection, otherwise the
 * selected range is [min(anchor, cursor), max(anchor, cursor)). Movement methods consult
 * the `selecting` flag, which ChatWindow sets from the shift key once per key event.
 * <p>
 * Wrapping is cached: wrapSegments() re-measures text with the font renderer only when
 * the text or the requested width has changed since the last call, since layout getters
 * in ChatWindow call it many times per frame.
 */
public class MultilineInputBox {
    private final StringBuilder text = new StringBuilder();
    private int cursor = 0;
    private int selectionAnchor = -1;
    private boolean selecting = false;
    private int maxTotalLength = 2000;

    private long lastBlinkToggle = System.currentTimeMillis();
    private boolean blinkOn = true;

    // Wrap cache: invalidated whenever the text content changes (modCount) or the
    // requested pixel width differs from the last call.
    private int modCount = 0;
    private List<Segment> cachedSegments = null;
    private List<String> cachedLines = null;
    private int cachedWidth = -1;
    private int cachedMod = -1;

    // View state: index of the first wrapped segment shown in the input box. The
    // host draws at most maxVisibleSegments lines starting here. It defaults to
    // following the caret (further down in this file), and can be wheel-scrolled
    // to reach text that wrapped past the visible box (the top lines are buried
    // otherwise).
    private int scrollIndex = 0;
    private int maxVisibleSegments = 4;

    public String getText() {
        return this.text.toString();
    }

    public int getCursor() {
        return this.cursor;
    }

    public boolean isEmpty() {
        return this.text.length() == 0;
    }

    public void clear() {
        this.text.setLength(0);
        this.cursor = 0;
        this.selectionAnchor = -1;
        this.scrollIndex = 0;
        this.modCount++;
        this.resetBlink();
    }

    public void setMaxTotalLength(int max) {
        this.maxTotalLength = max;
    }

    /**
     * Set once per key event from the shift key; movement methods extend or drop the selection accordingly.
     */
    public void setSelecting(boolean selecting) {
        this.selecting = selecting;
    }

    public boolean hasSelection() {
        return this.selectionAnchor != -1 && this.selectionAnchor != this.cursor;
    }

    public int getSelectionStart() {
        return this.hasSelection() ? Math.min(this.selectionAnchor, this.cursor) : this.cursor;
    }

    public int getSelectionEnd() {
        return this.hasSelection() ? Math.max(this.selectionAnchor, this.cursor) : this.cursor;
    }

    public String getSelectedText() {
        return this.hasSelection() ? this.text.substring(this.getSelectionStart(), this.getSelectionEnd()) : "";
    }

    public void clearSelection() {
        this.selectionAnchor = -1;
    }

    public void selectAll() {
        this.selectionAnchor = 0;
        this.cursor = this.text.length();
        this.resetBlink();
    }

    /**
     * Deletes the selected range if there is one. Returns true if anything was removed.
     */
    public boolean deleteSelection() {
        if (!this.hasSelection()) return false;
        int start = this.getSelectionStart();
        int end = this.getSelectionEnd();
        this.text.delete(start, end);
        this.cursor = start;
        this.selectionAnchor = -1;
        this.modCount++;
        this.resetBlink();
        return true;
    }

    /**
     * Called at the head of every cursor move: extends the selection when shift is down, drops it otherwise.
     */
    private void beginMove() {
        if (this.selecting) {
            if (this.selectionAnchor == -1) this.selectionAnchor = this.cursor;
        } else {
            this.selectionAnchor = -1;
        }
    }

    public void insertChar(char c) {
        this.deleteSelection();
        if (this.text.length() >= this.maxTotalLength) return;
        this.text.insert(this.cursor, c);
        this.cursor++;
        this.modCount++;
        this.resetBlink();
    }

    public void newline() {
        this.insertChar('\n');
    }

    public void backspace() {
        if (this.deleteSelection()) return;
        if (this.cursor > 0) {
            this.text.deleteCharAt(this.cursor - 1);
            this.cursor--;
            this.modCount++;
            this.resetBlink();
        }
    }

    public void delete() {
        if (this.deleteSelection()) return;
        if (this.cursor < this.text.length()) {
            this.text.deleteCharAt(this.cursor);
            this.modCount++;
            this.resetBlink();
        }
    }

    public void insertText(String text) {
        if (text == null || text.isEmpty()) return;
        this.deleteSelection();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // Accept literal newlines from pasted multi-line clipboard content, skip \r
            // (Windows clipboard often carries \r\n; \n alone is enough for our line model)
            if (c == '\r') continue;
            this.insertChar(c);
        }
    }

    public void moveLeft() {
        this.beginMove();
        if (this.cursor > 0) this.cursor--;
        this.resetBlink();
    }

    public void moveRight() {
        this.beginMove();
        if (this.cursor < this.text.length()) this.cursor++;
        this.resetBlink();
    }

    public void moveLeftWord() {
        this.beginMove();
        while (this.cursor > 0 && isWordBreak(this.text.charAt(this.cursor - 1))) this.cursor--;
        while (this.cursor > 0 && !isWordBreak(this.text.charAt(this.cursor - 1))) this.cursor--;
        this.resetBlink();
    }

    public void moveRightWord() {
        this.beginMove();
        int len = this.text.length();
        while (this.cursor < len && isWordBreak(this.text.charAt(this.cursor))) this.cursor++;
        while (this.cursor < len && !isWordBreak(this.text.charAt(this.cursor))) this.cursor++;
        this.resetBlink();
    }

    private static boolean isWordBreak(char c) {
        return c == ' ' || c == '\n';
    }

    public void moveHome() {
        this.beginMove();
        String full = this.text.toString();
        int idx = full.lastIndexOf('\n', Math.max(0, this.cursor - 1));
        this.cursor = idx + 1;
        this.resetBlink();
    }

    public void moveEnd() {
        this.beginMove();
        String full = this.text.toString();
        int idx = full.indexOf('\n', this.cursor);
        this.cursor = idx == -1 ? full.length() : idx;
        this.resetBlink();
    }

    public void moveUp(TextRenderer renderer, int maxWidth) {
        this.beginMove();
        List<Segment> segs = this.wrapSegments(renderer, maxWidth);
        int lineIdx = this.cursorLineIndex(renderer, maxWidth);
        if (lineIdx == 0) {
            this.cursor = 0;
        } else {
            int col = this.cursorColumnInLine(renderer, maxWidth);
            Segment prev = segs.get(lineIdx - 1);
            this.cursor = prev.startOffset + Math.min(col, prev.text.length());
        }
        this.resetBlink();
    }

    public void moveDown(TextRenderer renderer, int maxWidth) {
        this.beginMove();
        List<Segment> segs = this.wrapSegments(renderer, maxWidth);
        int lineIdx = this.cursorLineIndex(renderer, maxWidth);
        if (lineIdx >= segs.size() - 1) {
            this.cursor = this.text.length();
        } else {
            int col = this.cursorColumnInLine(renderer, maxWidth);
            Segment next = segs.get(lineIdx + 1);
            this.cursor = next.startOffset + Math.min(col, next.text.length());
        }
        this.resetBlink();
    }

    /**
     * Places the caret at the character boundary nearest to pixelX on the given wrapped line.
     * pixelX is relative to the left edge of the drawn text, not the window.
     */
    public void setCursorAt(TextRenderer renderer, int maxWidth, int lineIndex, int pixelX, boolean selecting) {
        boolean previous = this.selecting;
        this.selecting = selecting;
        this.beginMove();
        this.selecting = previous;

        List<Segment> segs = this.wrapSegments(renderer, maxWidth);
        Segment seg = segs.get(MathHelper.clamp(lineIndex, 0, segs.size() - 1));
        this.cursor = MathHelper.clamp(seg.startOffset + columnAtPixel(renderer, seg.text, pixelX), 0, this.text.length());
        this.resetBlink();
    }

    private static int columnAtPixel(TextRenderer renderer, String line, int pixelX) {
        if (pixelX <= 0 || line.isEmpty()) return 0;
        for (int i = 1; i <= line.length(); i++) {
            int before = renderer.getWidth(line.substring(0, i - 1));
            int after = renderer.getWidth(line.substring(0, i));
            if (pixelX < (before + after) / 2) return i - 1;
        }
        return line.length();
    }

    /**
     * One wrapped visual segment: the substring to draw, plus its start offset in the raw text.
     */
    public static class Segment {
        public final String text;
        public final int startOffset;

        Segment(String text, int startOffset) {
            this.text = text;
            this.startOffset = startOffset;
        }
    }

    public List<Segment> wrapSegments(TextRenderer renderer, int maxWidth) {
        if (this.cachedSegments != null && this.cachedWidth == maxWidth && this.cachedMod == this.modCount) {
            return this.cachedSegments;
        }

        List<Segment> out = new ArrayList<>();
        String full = this.text.toString();
        String[] rawLines = full.split("\n", -1);
        int offset = 0;

        for (String rawLine : rawLines) {
            if (rawLine.isEmpty()) {
                out.add(new Segment("", offset));
            } else {
                String remaining = rawLine;
                int localOffset = offset;
                while (!remaining.isEmpty()) {
                    String fit = GuiUtil.wrapWordBoundary(renderer, remaining, maxWidth);
                    if (fit.isEmpty()) {
                        fit = remaining.substring(0, 1); // avoid an infinite loop if a single char exceeds maxWidth
                    }
                    out.add(new Segment(fit, localOffset));
                    localOffset += fit.length();
                    if (fit.length() >= remaining.length()) break;
                    remaining = remaining.substring(fit.length());
                }
            }
            offset += rawLine.length() + 1; // account for the '\n' consumed by split
        }

        if (out.isEmpty()) {
            out.add(new Segment("", 0));
        }

        this.cachedSegments = out;
        this.cachedLines = null; // lazily rebuilt by wrappedLines() if asked for
        this.cachedWidth = maxWidth;
        this.cachedMod = this.modCount;
        return out;
    }

    public List<String> wrappedLines(TextRenderer renderer, int maxWidth) {
        List<Segment> segs = this.wrapSegments(renderer, maxWidth);
        // wrapSegments() only just (re)built the cache if it was stale
        if (this.cachedLines != null) {
            return this.cachedLines;
        }
        List<String> lines = new ArrayList<>(segs.size());
        for (Segment s : segs) {
            lines.add(s.text);
        }
        this.cachedLines = lines;
        return lines;
    }

    public int cursorLineIndex(TextRenderer renderer, int maxWidth) {
        List<Segment> segs = this.wrapSegments(renderer, maxWidth);
        int best = 0;
        for (int i = 0; i < segs.size(); i++) {
            Segment s = segs.get(i);
            int segEnd = s.startOffset + s.text.length();
            if (this.cursor >= s.startOffset && this.cursor <= segEnd) {
                return i;
            }
            if (s.startOffset <= this.cursor) best = i;
        }
        return best;
    }

    public int cursorColumnInLine(TextRenderer renderer, int maxWidth) {
        List<Segment> segs = this.wrapSegments(renderer, maxWidth);
        int idx = this.cursorLineIndex(renderer, maxWidth);
        Segment s = segs.get(idx);
        return MathHelper.clamp(this.cursor - s.startOffset, 0, s.text.length());
    }

    public void setMaxVisibleSegments(int maxVisibleSegments) {
        this.maxVisibleSegments = Math.max(1, maxVisibleSegments);
        this.scrollIndex = 0;
    }

    public int getScrollIndex() {
        return this.scrollIndex;
    }

    public int getMaxScrollIndex(TextRenderer renderer, int maxWidth) {
        return Math.max(0, this.wrapSegments(renderer, maxWidth).size() - this.maxVisibleSegments);
    }

    public void clampScroll(TextRenderer renderer, int maxWidth) {
        this.scrollIndex = MathHelper.clamp(this.scrollIndex, 0, this.getMaxScrollIndex(renderer, maxWidth));
    }

    public void scrollBy(int lines, TextRenderer renderer, int maxWidth) {
        this.scrollIndex = MathHelper.clamp(this.scrollIndex + lines, 0, this.getMaxScrollIndex(renderer, maxWidth));
    }

    /**
     * Keeps the caret line inside the visible window, scrolling the view up or
     * down as needed. Called after every edit/caret move so the box follows the
     * caret instead of stranding it on a line the host isn't drawing.
     */
    public void ensureCaretVisible(TextRenderer renderer, int maxWidth) {
        int caretLine = this.cursorLineIndex(renderer, maxWidth);
        int maxScroll = this.getMaxScrollIndex(renderer, maxWidth);
        if (this.scrollIndex > caretLine) {
            this.scrollIndex = caretLine;
        } else if (caretLine >= this.scrollIndex + this.maxVisibleSegments) {
            this.scrollIndex = Math.min(maxScroll, caretLine - this.maxVisibleSegments + 1);
        }
        this.scrollIndex = MathHelper.clamp(this.scrollIndex, 0, maxScroll);
    }

    // caret blink
    public boolean cursorVisible() {
        long now = System.currentTimeMillis();
        if (now - this.lastBlinkToggle > 500L) {
            this.blinkOn = !this.blinkOn;
            this.lastBlinkToggle = now;
        }
        return this.blinkOn;
    }

    public void resetBlink() {
        this.blinkOn = true;
        this.lastBlinkToggle = System.currentTimeMillis();
    }
}
