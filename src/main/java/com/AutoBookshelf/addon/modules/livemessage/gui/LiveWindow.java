package com.AutoBookshelf.addon.modules.livemessage.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class LiveWindow {
    private static final Identifier ICONS_TEXTURE = Identifier.fromNamespaceAndPath("livemessage", "icons.png");
    public static int titlebarHeight = 17;
    public int x;
    public int y;
    public int w = 400;
    public int h = 250;
    public int minw = 100;
    public int maxw = 9999;
    public int minh = 100;
    public int maxh = 9999;
    public int lastMouseX = 0;
    public int lastMouseY = 0;
    public String title = "Sample text";
    public boolean active = true;
    public int dragX;
    public int dragY;
    public boolean clicked = false;
    public boolean dragging = false;
    public boolean resizing = false;
    public boolean closeButton = true;
    public int primaryColor = 0;
    public Font fontRenderer;
    boolean animateIn = true;
    long animateInStart;
    protected Minecraft mc;
    protected List<LiveWindow.LiveButton> liveButtons = new ArrayList<>();
    protected KeyEvent lastKeyInput;
    protected CharacterEvent lastCharInput;

    public LiveWindow() {
        this.mc = Minecraft.getInstance();
        this.x = (int) (Math.random() * (LivemessageGui.screenWidth - this.w));
        this.y = (int) (Math.random() * (LivemessageGui.screenHeight - this.h));
        this.fontRenderer = this.mc.font;
        this.primaryColor = this.mc.player != null
            ? GuiUtil.getWindowColor(this.mc.player.getUUID())
            : GuiUtil.getSingleRGB(128);
        if (LivemessageGui.liveWindows.size() > 0) {
            LivemessageGui.liveWindows.get(LivemessageGui.liveWindows.size() - 1).deactivateWindow();
        }

        this.animateInStart = System.currentTimeMillis();
    }

    protected void drawText(GuiGraphicsExtractor context, String text, int x, int y, int color, boolean shadow) {
        context.text(this.fontRenderer, text, x, y, GuiUtil.fade(color), shadow);
    }

    protected int getTextWidth(String text) {
        return this.fontRenderer.width(text);
    }

    protected int getTextHeight() {
        return 9;
    }

    public void mouseReleased(int mouseX, int mouseY, int state) {
        this.dragging = false;
        this.resizing = false;
        this.clicked = false;
    }

    public void mouseMove(int mouseX, int mouseY) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
    }

    public boolean mouseInRect(int x, int y, int w, int h, int mouseX, int mouseY) {
        return mouseX > this.x + x && mouseX < this.x + x + w && mouseY > this.y + y && mouseY < this.y + y + h;
    }

    public void keyTyped(char typedChar, int keyCode) {
        if (keyCode == 258 && LivemessageGui.liveWindows.size() > 1) {
            if (GLFW.glfwGetKey(this.mc.getWindow().handle(), 340) == 1) {
                LiveWindow tempWindow = LivemessageGui.liveWindows.get(0);
                LivemessageGui.liveWindows.remove(0);
                tempWindow.activateWindow();
                LivemessageGui.liveWindows.add(tempWindow);
            } else {
                LivemessageGui.liveWindows.removeIf(it -> it == this);
                LivemessageGui.liveWindows.add(0, this);
                LivemessageGui.liveWindows.get(LivemessageGui.liveWindows.size() - 1).activateWindow();
            }

            this.deactivateWindow();
        }
    }

    public void handleKeyInput(KeyEvent input) {
        this.lastKeyInput = input;
    }

    public void handleCharInput(CharacterEvent input) {
        this.lastCharInput = input;
    }

    public void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        this.clicked = true;
        if (this.closeButton
            && mouseX > this.x + this.w - 13
            && mouseX < this.x + this.w - 2
            && mouseY > this.y + 3
            && mouseY < this.y + 14
            && LivemessageGui.liveWindows.size() > 1) {
            LivemessageGui.liveWindows.removeIf(it -> it == this);
            if (!LivemessageGui.liveWindows.isEmpty()) {
                LivemessageGui.liveWindows.get(LivemessageGui.liveWindows.size() - 1).activateWindow();
            }
        }

        if (mouseX > this.x && mouseX < this.x + this.w && mouseY > this.y && mouseY < this.y + 20) {
            this.dragging = true;
            this.resizing = false;
            this.dragX = mouseX - this.x;
            this.dragY = mouseY - this.y;
        } else if (mouseX > this.x + this.w - 7 && mouseX < this.x + this.w + 3 && mouseY > this.y + this.h - 7 && mouseY < this.y + this.h + 3) {
            this.dragging = false;
            this.resizing = true;
            this.dragX = mouseX - this.x - this.w;
            this.dragY = mouseY - this.y - this.h;
        }
    }

    public boolean mouseInWindow(int mouseX, int mouseY) {
        return mouseX > this.x && mouseX < this.x + this.w && mouseY > this.y && mouseY < this.y + this.h;
    }

    public void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
    }

    public void handleMouseDrag(double mouseX, double mouseY) {
        if (this.dragging || this.resizing) {
            if (this.dragging) {
                this.x = Math.max(0, (int) mouseX - this.dragX);
                this.y = Math.max(0, (int) mouseY - this.dragY);
            } else if (this.resizing) {
                this.w = Mth.clamp((int) mouseX - this.dragX - this.x, this.minw, this.maxw);
                this.h = Mth.clamp((int) mouseY - this.dragY - this.y, this.minh, this.maxh);
            }
        }
    }

    public void mouseWheel(int mWheelState) {
    }

    public void activateWindow() {
        this.active = true;
    }

    public void deactivateWindow() {
        this.active = false;
        this.mouseReleased(this.lastMouseX, this.lastMouseY, 0);
    }

    public void preDrawWindow(GuiGraphicsExtractor context) {
        if (this.fontRenderer == null) {
            this.fontRenderer = this.mc.font;
        }

        if (this.x + this.w > LivemessageGui.screenWidth) {
            this.x = LivemessageGui.screenWidth - this.w;
        }

        if (this.y + this.h > LivemessageGui.screenHeight) {
            this.y = LivemessageGui.screenHeight - this.h;
        }

        if (this.x < 0) {
            this.x = 0;
        }

        if (this.y < 0) {
            this.y = 0;
        }

        if (this.x + this.w > LivemessageGui.screenWidth) {
            this.w = Math.max(LivemessageGui.screenWidth, this.minw);
        }

        if (this.y + this.h > LivemessageGui.screenHeight) {
            this.h = Math.max(LivemessageGui.screenHeight, this.minh);
        }

        context.pose().translate(this.x, this.y);
        int bgColor = GuiUtil.getRGB(32, 32, 32);
        int fgColor = this.active ? this.primaryColor : GuiUtil.getRGB(128, 128, 128);
        this.drawWindow(context, bgColor, fgColor);
        context.pose().translate(-this.x, -this.y);
    }

    public void drawTextFields(GuiGraphicsExtractor context) {
    }

    public void drawWindow(GuiGraphicsExtractor context, int bgColor, int fgColor) {
        GuiUtil.drawRect(context, 0, 0, this.w, this.h, bgColor);
        this.drawRectOutline(context, 0, 0, this.w, this.h, fgColor);
        GuiUtil.drawRect(context, 0, 0, this.w, titlebarHeight, fgColor);
        this.drawText(context, this.title, 5, 5, 16777215, false);
        if (this.closeButton) {
            GuiUtil.drawRect(context, this.w - 13, 3, 11, 11, bgColor);
            int closeX = this.w - 13;
            int closeY = 3;
            int xColor = GuiUtil.fade(GuiUtil.getRGB(255, 64, 64));
            context.fill(closeX + 3, closeY + 3, closeX + 4, closeY + 9, xColor);
            context.fill(closeX + 4, closeY + 4, closeX + 5, closeY + 8, xColor);
            context.fill(closeX + 7, closeY + 3, closeX + 8, closeY + 9, xColor);
            context.fill(closeX + 6, closeY + 4, closeX + 7, closeY + 8, xColor);
        }

        this.drawRectHalf(context, this.w - 6, this.h - 6, 6, 6, false, fgColor);
    }

    public void drawRectHalf(GuiGraphicsExtractor context, int x, int y, int w, int h, boolean top, int color) {
        color = GuiUtil.fade(color);
        if (top) {
            context.fill(x, y, x + w, y + h / 2, color);
        } else {
            context.fill(x + w / 2, y + h / 2, x + w, y + h, color);
        }
    }

    public void drawRectOutline(GuiGraphicsExtractor context, int x, int y, int w, int h, int color) {
        color = GuiUtil.fade(color);
        context.fill(x, y, x + w, y + 1, color);
        context.fill(x, y + h - 1, x + w, y + h, color);
        context.fill(x, y, x + 1, y + h, color);
        context.fill(x + w - 1, y, x + w, y + h, color);
    }

    public class LiveButton {
        public int id;
        public int bx;
        public int by;
        public int bw;
        public int bh;
        public boolean negativeX;
        public String btnText;
        public String tooltipText;
        public int iconIndex = -1;
        public boolean iconActive = false;
        public int iconColor = -1;
        public int idleColor = GuiUtil.getSingleRGB(64);
        public int hoverColor = GuiUtil.getSingleRGB(96);
        public int textColor = GuiUtil.getSingleRGB(255);
        public Runnable action;
        public Identifier customTexture = null;
        public int customTextureWidth = 9;
        public int customTextureHeight = 9;
        public boolean visible = true;

        public LiveButton(int id, int x, int y, int w, int h, boolean negativeX, String btnText, String tooltipText, Runnable action) {
            this.id = id;
            this.bx = x;
            this.by = y;
            this.bw = w;
            this.bh = h;
            this.btnText = btnText;
            this.tooltipText = tooltipText;
            this.action = action;
            this.negativeX = negativeX;
        }

        public LiveButton(int id, int x, int y, int w, int h, boolean negativeX, int iconIndex, String tooltipText, Runnable action) {
            this(id, x, y, w, h, negativeX, "", tooltipText, action);
            this.iconIndex = iconIndex;
        }

        public LiveButton(int id, int x, int y, int w, int h, boolean negativeX, Identifier texture, String tooltipText, Runnable action) {
            this(id, x, y, w, h, negativeX, "", tooltipText, action);
            this.customTexture = texture;
            this.iconIndex = 0;
        }

        public int gx() {
            return this.negativeX ? LiveWindow.this.w - this.bx : this.bx;
        }

        public boolean isMouseOver() {
            return this.visible && LiveWindow.this.mouseInRect(this.gx(), this.by, this.bw, this.bh, LiveWindow.this.lastMouseX, LiveWindow.this.lastMouseY);
        }

        public void draw(GuiGraphicsExtractor context) {
            if (!this.visible) {
                int bgColor = GuiUtil.getSingleRGB(32);
                GuiUtil.drawRect(context, this.gx(), this.by, this.bw, this.bh, bgColor);
                int borderColor = GuiUtil.fade(GuiUtil.getSingleRGB(48));
                context.fill(this.gx(), this.by, this.gx() + this.bw, this.by + 1, borderColor);
                context.fill(this.gx(), this.by + this.bh - 1, this.gx() + this.bw, this.by + this.bh, borderColor);
                context.fill(this.gx(), this.by, this.gx() + 1, this.by + this.bh, borderColor);
                context.fill(this.gx() + this.bw - 1, this.by, this.gx() + this.bw, this.by + this.bh, borderColor);
                if (this.iconIndex >= 0) {
                    int iconX = this.gx() + 1;
                    int iconY = this.by + 1;
                    int grayTint = GuiUtil.fade(-12566464);
                    if (this.customTexture != null) {
                        context.blit(
                            RenderPipelines.GUI_TEXTURED,
                            this.customTexture,
                            iconX,
                            iconY,
                            0.0F,
                            0.0F,
                            this.customTextureWidth,
                            this.customTextureHeight,
                            this.customTextureWidth,
                            this.customTextureHeight,
                            grayTint
                        );
                    } else {
                        int texU = this.iconIndex * 9;
                        context.blit(RenderPipelines.GUI_TEXTURED, LiveWindow.ICONS_TEXTURE, iconX, iconY, texU, 0.0F, 9, 9, 45, 9, grayTint);
                    }
                }
            } else {
                int bgColor = this.isMouseOver() ? GuiUtil.getSingleRGB(96) : GuiUtil.getSingleRGB(64);
                GuiUtil.drawRect(context, this.gx(), this.by, this.bw, this.bh, bgColor);
                int borderColor = GuiUtil.fade(this.isMouseOver() ? GuiUtil.getSingleRGB(192) : GuiUtil.getSingleRGB(96));
                context.fill(this.gx(), this.by, this.gx() + this.bw, this.by + 1, borderColor);
                context.fill(this.gx(), this.by + this.bh - 1, this.gx() + this.bw, this.by + this.bh, borderColor);
                context.fill(this.gx(), this.by, this.gx() + 1, this.by + this.bh, borderColor);
                context.fill(this.gx() + this.bw - 1, this.by, this.gx() + this.bw, this.by + this.bh, borderColor);
                if (this.iconIndex >= 0) {
                    int iconX = this.gx() + 1;
                    int iconY = this.by + 1;
                    if (this.customTexture != null) {
                        context.blit(
                            RenderPipelines.GUI_TEXTURED,
                            this.customTexture,
                            iconX,
                            iconY,
                            0.0F,
                            0.0F,
                            this.customTextureWidth,
                            this.customTextureHeight,
                            this.customTextureWidth,
                            this.customTextureHeight,
                            GuiUtil.fade(-1)
                        );
                        if (this.iconActive) {
                            int color = this.iconColor != -1 ? this.iconColor : LiveWindow.this.primaryColor;
                            int argb = GuiUtil.fade(0xFF000000 | color & 16777215);
                            context.blit(
                                RenderPipelines.GUI_TEXTURED,
                                this.customTexture,
                                iconX,
                                iconY,
                                0.0F,
                                0.0F,
                                this.customTextureWidth,
                                this.customTextureHeight,
                                this.customTextureWidth,
                                this.customTextureHeight,
                                argb
                            );
                        }
                    } else {
                        int texU = this.iconIndex * 9;
                        context.blit(RenderPipelines.GUI_TEXTURED, LiveWindow.ICONS_TEXTURE, iconX, iconY, texU, 0.0F, 9, 9, 45, 9, GuiUtil.fade(-1));
                        if (this.iconActive) {
                            int color = this.iconColor != -1 ? this.iconColor : LiveWindow.this.primaryColor;
                            int argb = GuiUtil.fade(0xFF000000 | color & 16777215);
                            context.blit(RenderPipelines.GUI_TEXTURED, LiveWindow.ICONS_TEXTURE, iconX, iconY, texU, 0.0F, 9, 9, 45, 9, argb);
                        }
                    }
                } else if (!this.btnText.isEmpty()) {
                    LiveWindow.this.drawText(
                        context, this.btnText, this.gx() + this.bw / 2 - LiveWindow.this.getTextWidth(this.btnText) / 2, this.by + 2, this.textColor, false
                    );
                }
            }
        }

        public void drawTooltips(GuiGraphicsExtractor context) {
            if (this.visible && !this.tooltipText.isEmpty() && this.isMouseOver() && LiveWindow.this.active) {
                this.drawTooltip(context, this.tooltipText);
            }
        }

        private void drawTooltip(GuiGraphicsExtractor context, String text) {
            if (LiveWindow.this.active) {
                int textWidth = LiveWindow.this.getTextWidth(text);
                int textHeight = LiveWindow.this.getTextHeight();
                int padding = 3;
                int tooltipWidth = textWidth + padding * 2;
                int tooltipHeight = textHeight + padding * 2;
                int x = LiveWindow.this.lastMouseX - LiveWindow.this.x + 10;
                int y = LiveWindow.this.lastMouseY - LiveWindow.this.y - tooltipHeight - 5;
                if (x + tooltipWidth > LiveWindow.this.w) {
                    x = LiveWindow.this.w - tooltipWidth;
                }

                if (x < 0) {
                    x = 0;
                }

                if (y < 0) {
                    y = LiveWindow.this.lastMouseY - LiveWindow.this.y + 15;
                }

                GuiUtil.drawRect(context, x - 1, y - 1, tooltipWidth + 2, tooltipHeight + 2, GuiUtil.getSingleRGB(255));
                GuiUtil.drawRect(context, x, y, tooltipWidth, tooltipHeight, GuiUtil.getRGB(32, 32, 32));
                LiveWindow.this.drawText(context, text, x + padding, y + padding, GuiUtil.getSingleRGB(255), false);
            }
        }
    }
}
