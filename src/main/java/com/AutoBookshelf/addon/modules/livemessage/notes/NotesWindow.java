package com.AutoBookshelf.addon.modules.livemessage.notes;

import com.AutoBookshelf.addon.modules.livemessage.gui.GuiUtil;
import com.AutoBookshelf.addon.modules.livemessage.gui.LiveWindow;
import com.AutoBookshelf.addon.modules.livemessage.gui.LivemessageGui;
import com.AutoBookshelf.addon.modules.livemessage.util.LivemessageUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.*;

public class NotesWindow extends LiveWindow {
    private static NotesWindow instance;
    private final List<NoteEntry> notes;
    public EditBox inputField;
    // Header row (titlebarHeight+2 .. titlebarHeight+13) holds the Clear-done/Select-all buttons;
    // the list starts below it so the buttons no longer overlap the top note row.
    private final int listY = titlebarHeight + 18;
    private static final int LINE_SPACING = 10;
    // Long enough that editing a note with many sub texts never truncates content;
    // the field scrolls horizontally for anything beyond its visible width.
    private static final int MAX_INPUT_LENGTH = 2048;
    private int scrollPosition = 0;
    private boolean scrolling = false;
    private final int scrollBarWidth = 8;

    // Wrapped sub lines per note id, so row heights and the per-frame draw don't
    // re-measure glyph widths for every note on every scroll/click/render. Cleared
    // whenever a note's content changes or the window width changes.
    private final Map<String, List<String>> wrappedLinesCache = new HashMap<>();
    private int cachedWrapWidth = -1;

    private String editingNoteId = null;

    private static final int MAX_HISTORY = 50;
    private final Deque<List<NoteEntry>> undoStack = new ArrayDeque<>();
    private final Deque<List<NoteEntry>> redoStack = new ArrayDeque<>();
    private boolean ctrlWasDown = false;

    public NotesWindow() {
        instance = this;
        this.title = "Notes";
        this.w = 300;
        this.h = 300;
        this.minw = 200;
        this.minh = 150;
        // Fixed corner spawn instead of LiveWindow's random position, so this
        // never lands on top of an already-open chat window.
        this.x = Math.max(0, LivemessageGui.screenWidth - this.w - 20);
        this.y = 20;
        this.notes = NotesUtil.load();
        this.sortNotes();
        this.inputField = new EditBox(this.mc.font, 9, this.h - 16, this.w - 18, 12, Component.literal(""));
        this.inputField.setMaxLength(MAX_INPUT_LENGTH);
        this.inputField.setBordered(false);
        this.inputField.setFocused(true);
        this.inputField.setTextColor(-1);
        this.inputField.setTextColorUneditable(-8355712);
        this.initButtons();
    }

    public static NotesWindow getOrCreate() {
        boolean isNew = instance == null || !LivemessageGui.liveWindows.contains(instance);
        if (isNew) {
            instance = new NotesWindow();
        }
        return instance;
    }

    public static NotesWindow getInstance() {
        return instance;
    }

    private void initButtons() {
        this.liveButtons.add(new LiveWindow.LiveButton(0, 70, titlebarHeight + 2, 60, 11, true, "Clear done", "Remove completed notes", this::clearCompleted));
        this.liveButtons.add(new LiveWindow.LiveButton(1, 135, titlebarHeight + 2, 55, 11, true, "Select all", "Mark every note as done", this::selectAll));
    }

    // ============================== Undo / redo ==============================

    private List<NoteEntry> copyNotes() {
        List<NoteEntry> copy = new ArrayList<>(this.notes.size());
        for (NoteEntry n : this.notes) {
            NoteEntry c = new NoteEntry();
            c.id = n.id;
            c.text = n.text;
            c.subtexts = new ArrayList<>(n.subtexts);
            c.checked = n.checked;
            c.createdAt = n.createdAt;
            copy.add(c);
        }
        return copy;
    }

    // Call before any mutation that should be undoable. Snapshots current state,
    // then invalidates the redo stack since we're branching off into a new change.
    private void pushUndo() {
        this.undoStack.push(this.copyNotes());
        if (this.undoStack.size() > MAX_HISTORY) {
            this.undoStack.removeLast();
        }
        this.redoStack.clear();
    }

    private void applySnapshot(List<NoteEntry> snapshot) {
        this.notes.clear();
        this.notes.addAll(snapshot);
        this.invalidateRowHeights();
        this.scrollPosition = Mth.clamp(this.scrollPosition, 0, this.getMaxScroll());
        NotesUtil.save(this.notes);
    }

    private void undo() {
        if (this.undoStack.isEmpty()) return;
        this.redoStack.push(this.copyNotes());
        this.applySnapshot(this.undoStack.pop());
    }

    private void redo() {
        if (this.redoStack.isEmpty()) return;
        this.undoStack.push(this.copyNotes());
        this.applySnapshot(this.redoStack.pop());
    }

    // ============================== Note operations ==============================

    private void clearCompleted() {
        this.pushUndo();
        this.notes.removeIf(n -> n.checked);
        this.invalidateRowHeights();
        NotesUtil.save(this.notes);
        this.scrollPosition = Mth.clamp(this.scrollPosition, 0, this.getMaxScroll());
    }

    private void selectAll() {
        this.pushUndo();
        for (NoteEntry n : this.notes) n.checked = true;
        this.sortNotes();
        NotesUtil.save(this.notes);
    }

    private void addNote(String text) {
        // Drop chat decorations/§ and invisible bidi or zero-width control chars
        // before storing, so hidden formatting never survives inside a note.
        String cleaned = LivemessageUtil.stripChatDecorations(text).trim();
        if (cleaned.isBlank()) {
            return;
        }

        // ' | ' (space-pipe-space) separates the main text from sub texts that grow
        // below it. Splitting on the spaced separator keeps any standalone '|' inside
        // a note intact, so "a|b" survives an edit round-trip instead of re-splitting.
        String[] parts = cleaned.split(" \\| ", -1);
        String mainText = parts[0].trim();
        if (mainText.isBlank()) {
            return;
        }

        List<String> subs = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            String sub = parts[i].trim();
            if (!sub.isEmpty()) subs.add(sub);
        }

        this.pushUndo();

        if (this.editingNoteId != null) {
            for (NoteEntry note : this.notes) {
                if (note.id.equals(this.editingNoteId)) {
                    note.text = mainText;
                    note.subtexts = subs;
                    break;
                }
            }
            this.editingNoteId = null;
        } else {
            NoteEntry note = new NoteEntry(mainText);
            note.subtexts = subs;
            this.notes.add(0, note);
        }

        this.sortNotes();
        this.invalidateRowHeights();
        NotesUtil.save(this.notes);
    }

    private void beginEdit(NoteEntry note) {
        this.editingNoteId = note.id;
        StringBuilder sb = new StringBuilder(note.text);
        for (String sub : note.subtexts) {
            sb.append(" | ").append(sub);
        }
        this.inputField.setValue(sb.toString());
        this.inputField.setFocused(true);
    }

    private void endEdit() {
        this.editingNoteId = null;
        this.inputField.setValue("");
    }

    // Unchecked notes first (newest first within each group), checked notes sink to the bottom.
    private void sortNotes() {
        this.notes.sort((a, b) -> {
            if (a.checked != b.checked) {
                return a.checked ? 1 : -1;
            }
            return Long.compare(b.createdAt, a.createdAt);
        });
    }

    private static String formatRelativeTime(long createdAt) {
        long seconds = (System.currentTimeMillis() - createdAt) / 1000;
        if (seconds < 60) return "now";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h";
        return (hours / 24) + "d";
    }

    private int getListHeight() {
        return this.h - this.listY - 18 - 5;
    }

    /**
     * Wraps one sub text into rows that fit the list width, so a long sub text
     * grows downward instead of being cut off on a single line.
     */
    private List<String> wrapSubLines(String sub) {
        List<String> lines = new ArrayList<>();
        String rest = LivemessageUtil.stripChatDecorations(sub);
        int maxWidth = this.w - 52;
        while (!rest.isEmpty()) {
            String piece = GuiUtil.wrapWordBoundary(this.fontRenderer, rest, maxWidth);
            lines.add(piece.strip());
            if (piece.length() >= rest.length()) break;
            rest = rest.substring(piece.length());
        }
        return lines;
    }

    /**
     * The wrapped sub rows of a note, memoized per note id so scroll math and the
     * draw loop don't re-measure glyph widths on every event. Invalidated when the
     * window width or any note's text changes.
     */
    private List<String> wrappedLines(NoteEntry note) {
        if (this.cachedWrapWidth != this.w) {
            this.wrappedLinesCache.clear();
            this.cachedWrapWidth = this.w;
        }
        return this.wrappedLinesCache.computeIfAbsent(note.id, id -> {
            List<String> lines = new ArrayList<>();
            for (String sub : note.subtexts) {
                if (sub != null && !sub.isBlank()) lines.addAll(this.wrapSubLines(sub));
            }
            return lines;
        });
    }

    private void invalidateRowHeights() {
        this.wrappedLinesCache.clear();
    }

    private int noteLineCount(NoteEntry note) {
        return 1 + this.wrappedLines(note).size();
    }

    private int rowHeight(NoteEntry note) {
        return 12 + LINE_SPACING * this.noteLineCount(note);
    }

    private int getMaxScroll() {
        // Count from the bottom how many rows fit in the list area. A row taller
        // than the leftover space only counts as reachable when it is the sole
        // (bottom-most) visible row, otherwise it would hide notes below it.
        int remaining = this.getListHeight();
        int visible = 0;
        for (int i = this.notes.size() - 1; i >= 0; i--) {
            int h = this.rowHeight(this.notes.get(i));
            if (h > remaining) {
                if (visible == 0) visible++;
                break;
            }
            remaining -= h;
            visible++;
        }
        return Math.max(0, this.notes.size() - visible);
    }

    private boolean isCtrlDown() {
        long handle = this.mc.getWindow().handle();
        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_CONTROL) == 1 || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_CONTROL) == 1;
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) {
        // Ctrl+Z / Ctrl+Y undo-redo.
        if (isCtrlDown() && (keyCode == GLFW.GLFW_KEY_Z || keyCode == GLFW.GLFW_KEY_Y)) {
            if (keyCode == GLFW.GLFW_KEY_Z) this.undo();
            else this.redo();
            super.keyTyped(typedChar, keyCode);
            return;
        }

        if (keyCode == 257 || keyCode == 335) {
            if (this.inputField.isFocused()) {
                String text = this.inputField.getValue().trim();
                if (!text.isEmpty()) {
                    this.addNote(text);
                }
                this.inputField.setValue("");
            }
        } else if (keyCode == 266) {
            this.scrollPosition = Math.max(0, this.scrollPosition - 5);
        } else if (keyCode == 267) {
            this.scrollPosition = Math.min(this.getMaxScroll(), this.scrollPosition + 5);
        } else {
            if (keyCode != 0 && this.lastKeyInput != null) {
                this.inputField.keyPressed(this.lastKeyInput);
            }
            if (typedChar != 0 && this.lastCharInput != null) {
                this.inputField.charTyped(this.lastCharInput);
            }
        }

        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void mouseWheel(int mWheelState) {
        int maxScroll = this.getMaxScroll();
        if (mWheelState < 0) {
            this.scrollPosition = Math.min(maxScroll, this.scrollPosition + 3);
        } else {
            this.scrollPosition = Math.max(0, this.scrollPosition - 3);
        }
        super.mouseWheel(mWheelState);
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int state) {
        this.scrolling = false;
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;

        for (LiveWindow.LiveButton btn : this.liveButtons) {
            if (btn.isMouseOver()) {
                btn.action.run();
                return;
            }
        }

        int listHeight = this.getListHeight();
        if (this.mouseInRect(5, this.listY, this.w - 10, listHeight, mouseX, mouseY)) {
            // Rows grow with their sub texts, so walk the rows accumulating height.
            int y = this.listY + 2;
            for (int i = this.scrollPosition; i < this.notes.size(); i++) {
                NoteEntry note = this.notes.get(i);
                int rowH = this.rowHeight(note);
                if (mouseY - this.y >= y && mouseY - this.y < y + rowH) {
                    boolean checkboxHit = mouseX - this.x >= 8 && mouseX - this.x <= 16;
                    boolean deleteHit = mouseX - this.x >= this.w - 15 && mouseX - this.x <= this.w - 7;
                    if (checkboxHit) {
                        this.pushUndo();
                        note.checked = !note.checked;
                        this.sortNotes();
                        NotesUtil.save(this.notes);
                    } else if (deleteHit) {
                        this.pushUndo();
                        this.notes.remove(i);
                        this.invalidateRowHeights();
                        NotesUtil.save(this.notes);
                        this.scrollPosition = Mth.clamp(this.scrollPosition, 0, this.getMaxScroll());
                    } else if (note.id.equals(this.editingNoteId)) {
                        this.endEdit();
                    } else {
                        this.beginEdit(note);
                    }
                    return;
                }
                y += rowH;
                if (y >= this.listY + listHeight) break;
            }
        }

        int inputFieldY = this.h - 13 - 2;
        this.inputField.setFocused(this.mouseInRect(5, inputFieldY, this.w - 10, 13, mouseX, mouseY));

        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void drawWindow(GuiGraphicsExtractor context, int bgColor, int fgColor) {
        // Base drawWindow only draws the frame, so NotesWindow fills its own
        // (fully opaque) body before the frame elements go on top.
        GuiUtil.drawRect(context, 0, 0, this.w, this.h, bgColor);
        super.drawWindow(context, bgColor, fgColor);

        // Header section for the Clear-done/Select-all buttons, kept visually separate from the list below it.
        GuiUtil.drawRect(context, 4, titlebarHeight, this.w - 10 + 2, 15, GuiUtil.getSingleRGB(40));

        int listHeight = this.getListHeight();
        GuiUtil.drawRect(context, 4, this.listY - 1, this.w - 10 + 2, listHeight + 2, GuiUtil.getSingleRGB(64));
        GuiUtil.drawRect(context, 5, this.listY, this.w - 10, listHeight, GuiUtil.getSingleRGB(24));

        if (this.notes.isEmpty()) {
            this.drawText(context, "No notes yet. Format: main | sub 1 | sub 2 ...", 10, this.listY + 5, GuiUtil.getSingleRGB(96), false);
        } else {
            int listBottom = this.listY + listHeight;
            int y = this.listY + 2;
            for (int i = this.scrollPosition; i < this.notes.size(); i++) {
                NoteEntry note = this.notes.get(i);

                List<String> subLines = this.wrappedLines(note);
                int rowH = 12 + LINE_SPACING * (1 + subLines.size());
                // Skip the row entirely once its main text line itself no longer
                // fits
                if (y + 12 > listBottom) break;

                int boxColor = note.checked ? GuiUtil.getRGB(85, 200, 85) : GuiUtil.getSingleRGB(96);
                GuiUtil.drawRect(context, 8, y, 8, 8, boxColor);
                if (note.checked) {
                    this.drawText(context, "x", 9, y - 1, GuiUtil.getSingleRGB(255), false);
                }

                boolean isEditing = note.id.equals(this.editingNoteId);
                int textColor = isEditing ? GuiUtil.getRGB(120, 180, 255) : note.checked ? GuiUtil.getSingleRGB(120) : GuiUtil.getSingleRGB(255);
                String timeLabel = formatRelativeTime(note.createdAt);
                int timeWidth = this.getTextWidth(timeLabel);
                String clipped = this.fontRenderer.plainSubstrByWidth(LivemessageUtil.stripChatDecorations(note.text), this.w - 40 - timeWidth - 6);
                this.drawText(context, clipped, 20, y, textColor, false);
                this.drawText(context, timeLabel, this.w - 20 - timeWidth, y, GuiUtil.getSingleRGB(96), false);

                int lineY = y + LINE_SPACING;
                for (String subLine : subLines) {
                    // Stop drawing sub lines once they'd cross the list's bottom
                    // edge
                    if (lineY + LINE_SPACING > listBottom) break;
                    this.drawText(context, subLine, 20, lineY, GuiUtil.getSingleRGB(140), false);
                    lineY += LINE_SPACING;
                }

                this.drawText(context, "x", this.w - 12, y, GuiUtil.getRGB(255, 100, 100), false);
                y += rowH;
            }
        }

        GuiUtil.drawRect(context, 4, this.h - 13 - 5 - 1, this.w - 10 + 2, 15, GuiUtil.getSingleRGB(64));
        GuiUtil.drawRect(context, 5, this.h - 13 - 5, this.w - 10, 13, GuiUtil.getSingleRGB(24));

        this.liveButtons.forEach(btn -> btn.draw(context));
        this.liveButtons.forEach(btn -> btn.drawTooltips(context));
    }

    @Override
    public void drawTextFields(GuiGraphicsExtractor context) {
        context.pose().translate(this.x, this.y);
        this.inputField.setX(8);
        this.inputField.setY(this.h - 13 - 2);
        this.inputField.setWidth(this.w - 18);
        this.inputField.extractWidgetRenderState(context, this.lastMouseX - this.x, this.lastMouseY - this.y, 0.0F);
        context.pose().translate(-this.x, -this.y);
    }
}
