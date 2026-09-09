package com.AutoBookshelf.addon.modules.livemessage.notes;

import com.AutoBookshelf.addon.modules.livemessage.gui.GuiUtil;
import com.AutoBookshelf.addon.modules.livemessage.gui.LiveWindow;
import com.AutoBookshelf.addon.modules.livemessage.gui.LivemessageGui;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class NotesWindow extends LiveWindow {
    private static NotesWindow instance;
    private final List<NoteEntry> notes;
    public TextFieldWidget inputField;
    // Header row (titlebarHeight+2 .. titlebarHeight+13) holds the Clear-done/Select-all buttons;
    // the list starts below it so the buttons no longer overlap the top note row.
    private final int listY = titlebarHeight + 18;
    private final int rowHeight = 22;
    private final int footer = 18;
    private int scrollPosition = 0;
    private boolean scrolling = false;
    private final int scrollBarWidth = 8;

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
        this.inputField = new TextFieldWidget(this.mc.textRenderer, 9, this.h - 16, this.w - 18, 12, Text.literal(""));
        this.inputField.setMaxLength(200);
        this.inputField.setDrawsBackground(false);
        this.inputField.setFocused(true);
        this.inputField.setEditableColor(-1);
        this.inputField.setUneditableColor(-8355712);
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
            c.subtext = n.subtext;
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
        this.scrollPosition = MathHelper.clamp(this.scrollPosition, 0, this.getMaxScroll());
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
        NotesUtil.save(this.notes);
        this.scrollPosition = MathHelper.clamp(this.scrollPosition, 0, this.getMaxScroll());
    }

    private void selectAll() {
        this.pushUndo();
        for (NoteEntry n : this.notes) n.checked = true;
        this.sortNotes();
        NotesUtil.save(this.notes);
    }

    private void addNote(String text) {
        if (text == null || text.isBlank()) {
            return;
        }

        String[] parts = text.split("\\|", 2);
        String mainText = parts[0].trim();
        String subtext = parts.length > 1 && !parts[1].trim().isEmpty() ? parts[1].trim() : null;

        this.pushUndo();

        if (this.editingNoteId != null) {
            for (NoteEntry note : this.notes) {
                if (note.id.equals(this.editingNoteId)) {
                    note.text = mainText;
                    note.subtext = subtext;
                    break;
                }
            }
            this.editingNoteId = null;
        } else {
            NoteEntry note = new NoteEntry(mainText);
            note.subtext = subtext;
            this.notes.add(0, note);
        }

        this.sortNotes();
        NotesUtil.save(this.notes);
    }

    private void beginEdit(NoteEntry note) {
        this.editingNoteId = note.id;
        this.inputField.setText(note.subtext != null && !note.subtext.isEmpty() ? note.text + " | " + note.subtext : note.text);
        this.inputField.setFocused(true);
    }

    private void endEdit() {
        this.editingNoteId = null;
        this.inputField.setText("");
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
        return this.h - this.listY - this.footer - 5;
    }

    private int getMaxLines() {
        return Math.max(1, this.getListHeight() / this.rowHeight);
    }

    private int getMaxScroll() {
        return Math.max(0, this.notes.size() - this.getMaxLines());
    }

    private boolean isCtrlDown() {
        long handle = this.mc.getWindow().getHandle();
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
                String text = this.inputField.getText().trim();
                if (!text.isEmpty()) {
                    this.addNote(text);
                }
                this.inputField.setText("");
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
            int row = this.scrollPosition + (mouseY - this.y - this.listY - 2) / this.rowHeight;
            if (row >= 0 && row < this.notes.size()) {
                NoteEntry note = this.notes.get(row);
                boolean checkboxHit = mouseX - this.x >= 8 && mouseX - this.x <= 16;
                boolean deleteHit = mouseX - this.x >= this.w - 15 && mouseX - this.x <= this.w - 7;
                if (checkboxHit) {
                    this.pushUndo();
                    note.checked = !note.checked;
                    this.sortNotes();
                    NotesUtil.save(this.notes);
                } else if (deleteHit) {
                    this.pushUndo();
                    this.notes.remove(row);
                    NotesUtil.save(this.notes);
                    this.scrollPosition = MathHelper.clamp(this.scrollPosition, 0, this.getMaxScroll());
                } else if (note.id.equals(this.editingNoteId)) {
                    this.endEdit();
                } else {
                    this.beginEdit(note);
                }
                return;
            }
        }

        int inputFieldY = this.h - 13 - 2;
        this.inputField.setFocused(this.mouseInRect(5, inputFieldY, this.w - 10, 13, mouseX, mouseY));

        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void drawWindow(DrawContext context, int bgColor, int fgColor) {
        super.drawWindow(context, bgColor, fgColor);

        // Header section for the Clear-done/Select-all buttons, kept visually separate from the list below it.
        GuiUtil.drawRect(context, 4, titlebarHeight, this.w - 10 + 2, 15, GuiUtil.getSingleRGB(40));

        int listHeight = this.getListHeight();
        GuiUtil.drawRect(context, 4, this.listY - 1, this.w - 10 + 2, listHeight + 2, GuiUtil.getSingleRGB(64));
        GuiUtil.drawRect(context, 5, this.listY, this.w - 10, listHeight, GuiUtil.getSingleRGB(24));

        if (this.notes.isEmpty()) {
            this.drawText(context, "No notes yet. Sub text example: meow :3 | the Cat", 10, this.listY + 5, GuiUtil.getSingleRGB(96), false);
        } else {
            int maxLines = this.getMaxLines();
            int drawn = 0;
            for (int i = this.scrollPosition; i < this.notes.size() && drawn < maxLines; i++, drawn++) {
                NoteEntry note = this.notes.get(i);
                int y = this.listY + 2 + drawn * this.rowHeight;

                int boxColor = note.checked ? GuiUtil.getRGB(85, 200, 85) : GuiUtil.getSingleRGB(96);
                GuiUtil.drawRect(context, 8, y, 8, 8, boxColor);
                if (note.checked) {
                    this.drawText(context, "x", 9, y - 1, GuiUtil.getSingleRGB(255), false);
                }

                boolean isEditing = note.id.equals(this.editingNoteId);
                int textColor = isEditing ? GuiUtil.getRGB(120, 180, 255) : note.checked ? GuiUtil.getSingleRGB(120) : GuiUtil.getSingleRGB(255);
                String timeLabel = formatRelativeTime(note.createdAt);
                int timeWidth = this.getTextWidth(timeLabel);
                String clipped = this.fontRenderer.trimToWidth(note.text, this.w - 40 - timeWidth - 6);
                this.drawText(context, clipped, 20, y, textColor, false);
                this.drawText(context, timeLabel, this.w - 20 - timeWidth, y, GuiUtil.getSingleRGB(96), false);

                if (note.subtext != null && !note.subtext.isEmpty()) {
                    String clippedSub = this.fontRenderer.trimToWidth(note.subtext, this.w - 40);
                    this.drawText(context, clippedSub, 20, y + 10, GuiUtil.getSingleRGB(140), false);
                }

                this.drawText(context, "x", this.w - 12, y, GuiUtil.getRGB(255, 100, 100), false);
            }
        }

        GuiUtil.drawRect(context, 4, this.h - 13 - 5 - 1, this.w - 10 + 2, 15, GuiUtil.getSingleRGB(64));
        GuiUtil.drawRect(context, 5, this.h - 13 - 5, this.w - 10, 13, GuiUtil.getSingleRGB(24));

        this.liveButtons.forEach(btn -> btn.draw(context));
        this.liveButtons.forEach(btn -> btn.drawTooltips(context));
    }

    @Override
    public void drawTextFields(DrawContext context) {
        context.getMatrices().translate(this.x, this.y);
        this.inputField.setX(8);
        this.inputField.setY(this.h - 13 - 2);
        this.inputField.setWidth(this.w - 18);
        this.inputField.render(context, this.lastMouseX - this.x, this.lastMouseY - this.y, 0.0F);
        context.getMatrices().translate(-this.x, -this.y);
    }
}
