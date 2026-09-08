package com.AutoBookshelf.addon.modules.livemessage.gui;

import com.AutoBookshelf.addon.modules.livemessage.LiveMessage;
import com.AutoBookshelf.addon.modules.livemessage.notes.NotesWindow;
import com.AutoBookshelf.addon.modules.livemessage.util.LastSeenTracker;
import com.AutoBookshelf.addon.modules.livemessage.util.LiveProfileCache;
import com.AutoBookshelf.addon.modules.livemessage.util.LiveSkinUtil;
import com.AutoBookshelf.addon.modules.livemessage.util.LivemessageUtil;
import com.AutoBookshelf.addon.utils.EnemyManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import meteordevelopment.meteorclient.systems.friends.Friends;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.*;
import java.util.Map.Entry;

import static com.AutoBookshelf.addon.modules.livemessage.LiveMessage.logError;

public class ManeWindow extends LiveWindow {
    LiveProfileCache.LiveProfile liveProfile;
    LiveSkinUtil liveSkinUtil;
    GuiUtil.QuintAnimation hatFade = new GuiUtil.QuintAnimation(300, 1.0F);
    GuiUtil.QuintAnimation fullSkinAnim = new GuiUtil.QuintAnimation(600, 0.0F);
    final int scrollBarWidth = 10;
    int scrollBarHeight = 50;
    static int listScrollPosition = 0;
    boolean scrolling = false;
    public static TextFieldWidget searchField;
    public static List<ManeWindow.BuddyListEntry> buddyListEntries = new ArrayList<>();
    final int buddyListX = 5;
    final int buddyListY = titlebarHeight + 44;
    final int footer = 13;
    private static int mainWindowColor = 0;
    private static final Formatting[] MINECRAFT_COLORS = new Formatting[]{
        Formatting.BLACK,
        Formatting.DARK_BLUE,
        Formatting.DARK_GREEN,
        Formatting.DARK_AQUA,
        Formatting.DARK_RED,
        Formatting.DARK_PURPLE,
        Formatting.GOLD,
        Formatting.GRAY,
        Formatting.DARK_GRAY,
        Formatting.BLUE,
        Formatting.GREEN,
        Formatting.AQUA,
        Formatting.RED,
        Formatting.LIGHT_PURPLE,
        Formatting.YELLOW,
        Formatting.WHITE
    };
    private static final Identifier NOTE_ICON = Identifier.of("livemessage", "note.png");
    private static int lastBuddyListSize = 0;

    // Unfiltered, sorted category lists rebuilt on a throttle
    private static List<BuddyListEntry> cachedRecentList = new ArrayList<>();
    private static List<BuddyListEntry> cachedNearbyList = new ArrayList<>();
    private static List<BuddyListEntry> cachedFriendsList = new ArrayList<>();
    private static List<BuddyListEntry> cachedNeutralsList = new ArrayList<>();
    private static List<BuddyListEntry> cachedEnemiesList = new ArrayList<>();
    private static List<BuddyListEntry> cachedOfflineList = new ArrayList<>();
    private static long lastSourceRebuild = 0L;
    private static final long SOURCE_REBUILD_INTERVAL_MS = 250L;

    ManeWindow() {
        this.liveProfile = new LiveProfileCache.LiveProfile();
        this.liveProfile.username = this.mc.player.getName().getString();
        this.liveProfile.uuid = this.mc.player.getUuid();
        this.liveSkinUtil = LiveSkinUtil.get(this.liveProfile.uuid);
        this.closeButton = false;
        this.loadMainWindowColor();
        searchField = new TextFieldWidget(this.mc.textRenderer, 9, this.h - 16, this.w - 18, 12, Text.literal(""));
        searchField.setMaxLength(16);
        searchField.setDrawsBackground(false);
        searchField.setFocused(true);
        searchField.setText("");
        searchField.setEditableColor(-1);
        searchField.setUneditableColor(-8355712);
        this.initButtons();
    }

    private void loadMainWindowColor() {
        try {
            File settingsFile = LivemessageUtil.LIVEMESSAGE_FOLDER.resolve("mainwindow.json").toFile();
            if (settingsFile.exists()) {
                Gson gson = new Gson();
                JsonObject json = (JsonObject) gson.fromJson(new FileReader(settingsFile), JsonObject.class);
                if (json.has("customColor")) {
                    mainWindowColor = json.get("customColor").getAsInt();
                    this.primaryColor = mainWindowColor > 0 ? mainWindowColor : GuiUtil.getWindowColor(this.mc.player.getUuid());
                }
            }
        } catch (Exception var4) {
        }
    }

    private void saveMainWindowColor() {
        try {
            File settingsFile = LivemessageUtil.LIVEMESSAGE_FOLDER.resolve("mainwindow.json").toFile();
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            JsonObject json = new JsonObject();
            json.addProperty("customColor", mainWindowColor);
            FileWriter writer = new FileWriter(settingsFile);
            gson.toJson(json, writer);
            writer.close();
        } catch (Exception e) {
            logError("Failed to save main window color", e);
        }
    }

    public void toggleMainWindowColor() {
        int currentIndex = -1;
        if (mainWindowColor == 0) {
            currentIndex = -1;
        } else {
            for (int i = 0; i < MINECRAFT_COLORS.length; i++) {
                if (MINECRAFT_COLORS[i].isColor() && MINECRAFT_COLORS[i].getColorValue() != null) {
                    int colorValue = MINECRAFT_COLORS[i].getColorValue() | 0xFF000000;
                    if (mainWindowColor == colorValue) {
                        currentIndex = i;
                        break;
                    }
                }
            }
        }

        currentIndex = (currentIndex + 1) % (MINECRAFT_COLORS.length + 1);
        if (currentIndex == MINECRAFT_COLORS.length) {
            mainWindowColor = 0;
            this.primaryColor = GuiUtil.getWindowColor(this.mc.player.getUuid());
            LiveMessage.LOG.info("Main window color reset to default (0x{})", Integer.toHexString(this.primaryColor).toUpperCase());
        } else if (MINECRAFT_COLORS[currentIndex].isColor() && MINECRAFT_COLORS[currentIndex].getColorValue() != null) {
            mainWindowColor = MINECRAFT_COLORS[currentIndex].getColorValue() | 0xFF000000;
            this.primaryColor = mainWindowColor;
            LiveMessage.LOG.info("Main window color changed to: 0x{} (index {})", Integer.toHexString(mainWindowColor).toUpperCase(), currentIndex);
        }

        this.saveMainWindowColor();
        this.updateButtonStates();
        this.refreshAllChatWindowColors();
    }

    private void refreshAllChatWindowColors() {
        for (LiveWindow window : LivemessageGui.liveWindows) {
            if (window instanceof ChatWindow chatWindow) {
                chatWindow.reloadWindowColor();
            }
        }
    }

    public void initButtons() {
        this.liveButtons.add(new LiveWindow.LiveButton(0, 14, titlebarHeight + 3 + 13, 11, 11, true, 2, "Custom color", () -> this.toggleMainWindowColor()));
        this.liveButtons.add(new LiveWindow.LiveButton(1, 14, titlebarHeight + 3 + 26, 11, 11, true, NOTE_ICON, "Open Notes", () -> this.openNotesWindow()));
    }

    private void openNotesWindow() {
        for (LiveWindow window : LivemessageGui.liveWindows) {
            if (window instanceof NotesWindow) {
                window.activateWindow();
                LivemessageGui.liveWindows.remove(window);
                LivemessageGui.liveWindows.add(window);
                this.deactivateWindow();
                return;
            }
        }

        this.deactivateWindow();
        NotesWindow notesWindow = NotesWindow.getOrCreate();
        LivemessageGui.liveWindows.add(notesWindow);
    }

    private void updateButtonStates() {
        for (LiveWindow.LiveButton btn : this.liveButtons) {
            if (btn.id == 0) {
                btn.iconActive = mainWindowColor > 0;
            }
        }
    }

    public boolean shouldDrawBlur() {
        boolean removeHat = this.lastMouseX > this.x + 5
            && this.lastMouseX < this.x + 37
            && this.lastMouseY > this.y + titlebarHeight + 5
            && this.lastMouseY < this.y + titlebarHeight + 37;
        float progress = this.fullSkinAnim.animate(removeHat && this.clicked && !this.dragging && !this.resizing && !this.scrolling ? 1.0F : 0.0F);
        return progress > 0.0F;
    }

    public int getBlurAlpha() {
        boolean removeHat = this.lastMouseX > this.x + 5
            && this.lastMouseX < this.x + 37
            && this.lastMouseY > this.y + titlebarHeight + 5
            && this.lastMouseY < this.y + titlebarHeight + 37;
        float progress = this.fullSkinAnim.animate(removeHat && this.clicked && !this.dragging && !this.resizing && !this.scrolling ? 1.0F : 0.0F);
        return (int) (progress * 128.0F);
    }

    private void drawProfilePic(DrawContext context, int x, int y, UUID uuid) {
        boolean removeHat = this.lastMouseX > this.x + x
            && this.lastMouseX < this.x + x + 32
            && this.lastMouseY > this.y + y
            && this.lastMouseY < this.y + y + 32;
        float progress = this.fullSkinAnim.animate(removeHat && this.clicked && !this.dragging && !this.resizing && !this.scrolling ? 1.0F : 0.0F);
        int displaySize = Math.round(32.0F + progress * 224.0F);
        int displayX = Math.round(x - progress * 32.0F);
        int displayY = Math.round(y - progress * 32.0F);
        PlayerListEntry entry = this.mc.getNetworkHandler().getPlayerListEntry(uuid);
        if (entry != null) {
            PlayerSkinDrawer.draw(context, entry.getSkinTextures(), displayX, displayY, displaySize, GuiUtil.fade(-1));
        }
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) {
        int maxVisibleLines = (this.h - (this.buddyListY + 13 + 15)) / 12;
        int maxScroll = Math.max(0, buddyListEntries.size() - maxVisibleLines);
        if (keyCode == 266) {
            listScrollPosition = Math.max(0, listScrollPosition - 10);
        } else if (keyCode == 267) {
            listScrollPosition = Math.min(maxScroll, listScrollPosition + 10);
        } else {
            if (keyCode != 0 && this.lastKeyInput != null) {
                searchField.keyPressed(this.lastKeyInput);
            }

            if (typedChar != 0 && this.lastCharInput != null) {
                searchField.charTyped(this.lastCharInput);
            }
        }

        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int state) {
        this.scrolling = false;
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    public void mouseWheel(int mWheelState) {
        int maxVisibleLines = (this.h - (this.buddyListY + 13 + 15)) / 12;
        int maxScroll = Math.max(0, buddyListEntries.size() - maxVisibleLines);
        boolean shift = GLFW.glfwGetKey(this.mc.getWindow().getHandle(), 340) == 1;
        int scrollAmount = shift ? 5 : 1;
        if (mWheelState < 0) {
            listScrollPosition = Math.min(maxScroll, listScrollPosition + scrollAmount);
        } else {
            listScrollPosition = Math.max(0, listScrollPosition - scrollAmount);
        }

        super.mouseWheel(mWheelState);
    }

    @Override
    public void handleMouseDrag(double mouseX, double mouseY) {
        if (this.scrolling && buddyListEntries.size() > 1) {
            int maxVisibleLines = (this.h - (this.buddyListY + 13 + 15)) / 12;
            int maxScroll = Math.max(0, buddyListEntries.size() - maxVisibleLines);
            int availableScrollArea = this.h - (this.buddyListY + 10 + 13) - this.scrollBarHeight;
            int relativeMouseY = (int) mouseY - (this.dragY + this.buddyListY + this.y);
            listScrollPosition = (int) MathHelper.clamp((float) (relativeMouseY * maxScroll) / availableScrollArea, 0.0F, maxScroll);
        } else {
            super.handleMouseDrag(mouseX, mouseY);
        }
    }

    @Override
    public void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (this.scrolling && buddyListEntries.size() > 1) {
            int maxVisibleLines = (this.h - (this.buddyListY + 13 + 15)) / 12;
            int maxScroll = Math.max(0, buddyListEntries.size() - maxVisibleLines);
            int availableScrollArea = this.h - (this.buddyListY + 10 + 13) - this.scrollBarHeight;
            int relativeMouseY = mouseY - (this.dragY + this.buddyListY + this.y);
            listScrollPosition = (int) MathHelper.clamp((float) (relativeMouseY * maxScroll) / availableScrollArea, 0.0F, maxScroll);
        }

        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        boolean buttonClicked = false;

        for (LiveWindow.LiveButton btn : this.liveButtons) {
            if (btn.isMouseOver()) {
                LiveMessage.LOG.info("ManeWindow button {} clicked at ({}, {}) - btn pos: ({}, {})", new Object[]{btn.id, mouseX, mouseY, btn.gx(), btn.by});
                btn.action.run();
                buttonClicked = true;
                break;
            }
        }

        if (!buttonClicked) {
            int searchFieldY = this.h - 13 - 5;
            if (this.mouseInRect(5, searchFieldY, this.w - 10, 13, mouseX, mouseY)) {
                searchField.setFocused(true);
            } else {
                searchField.setFocused(false);
            }

            int maxVisibleLines = (this.h - (this.buddyListY + 13 + 15)) / 12;
            int totalEntries = buddyListEntries.size();
            int maxScroll = Math.max(0, totalEntries - maxVisibleLines);
            int availableScrollArea = this.h - (this.buddyListY + 10 + 13) - this.scrollBarHeight;
            boolean needsScrollbar = totalEntries > maxVisibleLines;
            int listClickWidth = needsScrollbar ? this.w - 10 - 10 : this.w - 10;
            if (needsScrollbar && maxScroll > 0) {
                int scrollY = this.buddyListY + availableScrollArea * listScrollPosition / maxScroll;
                int scrollBarX = 5 + this.w - 10 - 10;
                if (this.mouseInRect(scrollBarX, scrollY, 10, this.scrollBarHeight, mouseX, mouseY)) {
                    this.scrolling = true;
                    this.dragY = mouseY - (this.y + scrollY);
                }
            }

            if (!this.scrolling && this.mouseInRect(5, this.buddyListY, listClickWidth, this.h - (this.buddyListY + 10 + 13), mouseX, mouseY)) {
                int i = (int) Math.floor((mouseY - this.buddyListY - this.y - 3) / 12.0F) + listScrollPosition;
                if (i < buddyListEntries.size() && i >= 0) {
                    ManeWindow.BuddyListEntry buddyListEntry = buddyListEntries.get(i);
                    if (buddyListEntry.uuid != null) {
                        LivemessageGui.openChatWindow(buddyListEntry.uuid);
                    }
                }
            }

            super.mouseClicked(mouseX, mouseY, mouseButton);
        }
    }

    private static boolean rightMode(int mode, UUID uuid) {
        switch (mode) {
            case 0:
                if (LivemessageGui.unreadMessages.getOrDefault(uuid, 0) == 0) {
                    return false;
                }
                break;
            case 1:
                if (LivemessageGui.unreadMessages.getOrDefault(uuid, 0) > 0 || !LivemessageUtil.checkOnlineStatus(uuid)) {
                    return false;
                }
                break;
            case 2:
                if (LivemessageGui.unreadMessages.getOrDefault(uuid, 0) > 0 || LivemessageUtil.checkOnlineStatus(uuid)) {
                    return false;
                }
        }

        return true;
    }

    private static boolean searchFilter(String username) {
        try {
            String searchText = searchField.getText().trim().toLowerCase(Locale.ROOT);
            return searchText.isEmpty() ? false : !username.toLowerCase(Locale.ROOT).contains(searchText);
        } catch (Exception e) {
            return false;
        }
    }

    private static void rebuildBuddyListSource() {
        long now = System.currentTimeMillis();
        if (now - lastSourceRebuild < SOURCE_REBUILD_INTERVAL_MS) {
            return;
        }
        lastSourceRebuild = now;

        Friends friends = Friends.get();
        EnemyManager enemyManager = EnemyManager.get();
        Map<UUID, String> onlinePlayers = new HashMap<>();
        if (MinecraftClient.getInstance().getNetworkHandler() != null) {
            for (PlayerListEntry entry : MinecraftClient.getInstance().getNetworkHandler().getPlayerList()) {
                GameProfile gameProfile = entry.getProfile();
                UUID uuid = gameProfile.id();
                if (!uuid.equals(MinecraftClient.getInstance().player.getUuid())) {
                    onlinePlayers.put(uuid, gameProfile.name());
                }
            }
        }

        Set<UUID> nearbyPlayerUUIDs = new HashSet<>();
        if (MinecraftClient.getInstance().world != null) {
            for (PlayerEntity player : MinecraftClient.getInstance().world.getPlayers()) {
                if (player != MinecraftClient.getInstance().player) {
                    nearbyPlayerUUIDs.add(player.getUuid());
                }
            }
        }

        Map<UUID, String> allPlayers = new HashMap<>(onlinePlayers);

        for (UUID uuid : LivemessageGui.chats) {
            if (!allPlayers.containsKey(uuid)) {
                LiveProfileCache.LiveProfile profile = LiveProfileCache.getLiveprofileFromUUID(uuid, true);
                if (profile != null) {
                    allPlayers.put(uuid, profile.username);
                }
            }
        }

        Set<UUID> shownUUIDs = new HashSet<>();
        List<ManeWindow.BuddyListEntry> recentList = new ArrayList<>();

        for (UUID uuid : LivemessageGui.recentChats) {
            LiveProfileCache.LiveProfile profile = LiveProfileCache.getLiveprofileFromUUID(uuid, true);
            if (profile != null) {
                boolean online = onlinePlayers.containsKey(uuid);
                recentList.add(new ManeWindow.BuddyListEntry(uuid, profile.username, online));
                shownUUIDs.add(uuid);
            }
        }

        List<ManeWindow.BuddyListEntry> nearbyList = new ArrayList<>();

        for (UUID uuid : nearbyPlayerUUIDs) {
            String username = onlinePlayers.get(uuid);
            if (username != null && !shownUUIDs.contains(uuid)) {
                nearbyList.add(new ManeWindow.BuddyListEntry(uuid, username, true));
                shownUUIDs.add(uuid);
            }
        }

        nearbyList.sort(Comparator.comparing(entryx -> entryx.username.toLowerCase(Locale.ROOT)));

        List<ManeWindow.BuddyListEntry> friendsList = new ArrayList<>();
        List<ManeWindow.BuddyListEntry> enemiesList = new ArrayList<>();
        List<ManeWindow.BuddyListEntry> neutralsList = new ArrayList<>();
        List<ManeWindow.BuddyListEntry> offlineList = new ArrayList<>();

        for (Entry<UUID, String> entry : allPlayers.entrySet()) {
            UUID uuid = entry.getKey();
            String username = entry.getValue();
            if (!shownUUIDs.contains(uuid)) {
                boolean online = onlinePlayers.containsKey(uuid);
                boolean isFriend = friends.get(username) != null;
                boolean isEnemy = enemyManager.isEnemy(username);
                if (!online) {
                    offlineList.add(new ManeWindow.BuddyListEntry(uuid, username, false));
                } else if (isFriend) {
                    friendsList.add(new ManeWindow.BuddyListEntry(uuid, username, true));
                } else if (isEnemy) {
                    enemiesList.add(new ManeWindow.BuddyListEntry(uuid, username, true));
                } else {
                    neutralsList.add(new ManeWindow.BuddyListEntry(uuid, username, true));
                }
            }
        }

        Comparator<ManeWindow.BuddyListEntry> alphabeticalComparator = Comparator.comparing(entryx -> entryx.username.toLowerCase(Locale.ROOT));
        friendsList.sort(alphabeticalComparator);
        neutralsList.sort(alphabeticalComparator);
        enemiesList.sort(alphabeticalComparator);
        offlineList.sort(alphabeticalComparator);

        cachedRecentList = recentList;
        cachedNearbyList = nearbyList;
        cachedFriendsList = friendsList;
        cachedNeutralsList = neutralsList;
        cachedEnemiesList = enemiesList;
        cachedOfflineList = offlineList;
    }

    public static void generateBuddylist() {
        rebuildBuddyListSource();

        buddyListEntries.clear();
        addFilteredSection("Recent", cachedRecentList);
        addFilteredSection("Nearby Players", cachedNearbyList);
        addFilteredSection("Friends", cachedFriendsList);
        addFilteredSection("All Players", cachedNeutralsList);
        addFilteredSection("Enemies", cachedEnemiesList);
        addFilteredSection("All Offline", cachedOfflineList);

        if (buddyListEntries.isEmpty()) {
            buddyListEntries.add(new ManeWindow.BuddyListEntry("No players found"));
        }

        if (Math.abs(buddyListEntries.size() - lastBuddyListSize) > 3) {
            listScrollPosition = 0;
        }

        lastBuddyListSize = buddyListEntries.size();
    }

    private static void addFilteredSection(String header, List<ManeWindow.BuddyListEntry> source) {
        List<ManeWindow.BuddyListEntry> filtered = new ArrayList<>(source.size());
        for (ManeWindow.BuddyListEntry entry : source) {
            if (!searchFilter(entry.username)) {
                filtered.add(entry);
            }
        }

        if (!filtered.isEmpty()) {
            buddyListEntries.add(new ManeWindow.BuddyListEntry(header));
            buddyListEntries.addAll(filtered);
        }
    }

    private String hoverTooltip = null;
    private int hoverTooltipX = 0;
    private int hoverTooltipY = 0;

    public void drawBuddylist(DrawContext context, int availableWidth) {
        int lineHeight = 0;
        Friends friends = Friends.get();
        EnemyManager enemyManager = EnemyManager.get();
        int maxVisibleLines = (this.h - (this.buddyListY + 13 + 15)) / 12;
        int maxScroll = Math.max(0, buddyListEntries.size() - maxVisibleLines);
        listScrollPosition = MathHelper.clamp(listScrollPosition, 0, maxScroll);

        this.hoverTooltip = null;

        for (int i = listScrollPosition; i < buddyListEntries.size() && lineHeight < maxVisibleLines; i++) {
            ManeWindow.BuddyListEntry buddyListEntry = buddyListEntries.get(i);
            int yPos = this.buddyListY + 5 + 12 * lineHeight;
            if (buddyListEntry.uuid != null) {
                PlayerListEntry tabEntry = this.mc.getNetworkHandler() != null ? this.mc.getNetworkHandler().getPlayerListEntry(buddyListEntry.uuid) : null;
                LiveSkinUtil buddySkin = LiveSkinUtil.get(buddyListEntry.uuid);
                if (tabEntry != null) {
                    buddySkin.captureFromTabList(tabEntry);
                    PlayerSkinDrawer.draw(context, tabEntry.getSkinTextures(), 10, yPos - 1, 10);
                } else if (buddySkin.hasCachedSkin()) {
                    PlayerSkinDrawer.draw(context, buddySkin.getCachedTextures(), 10, yPos - 1, 10);
                }
            }

            String buddyText = (buddyListEntry.uuid == null ? "\u00a7l" : "     ") + buddyListEntry.username;
            int textColor;
            if (buddyListEntry.uuid != null) {
                boolean isFriend = friends.get(buddyListEntry.username) != null;
                boolean isEnemy = enemyManager.isEnemy(buddyListEntry.username);
                if (isFriend) {
                    textColor = buddyListEntry.online ? GuiUtil.getRGB(85, 255, 85) : GuiUtil.getRGB(42, 128, 42);
                } else if (isEnemy) {
                    textColor = buddyListEntry.online ? GuiUtil.getRGB(255, 85, 85) : GuiUtil.getRGB(128, 42, 42);
                } else {
                    textColor = GuiUtil.getSingleRGB(buddyListEntry.online ? 255 : 128);
                }
            } else {
                textColor = GuiUtil.getSingleRGB(255);
            }

            int maxTextWidth = availableWidth - 10;
            String clippedText = this.fontRenderer.trimToWidth(buddyText, maxTextWidth);
            this.drawText(context, clippedText, 10, yPos, textColor, false);
            if (buddyListEntry.uuid != null) {
                int unreads = LivemessageGui.unreadMessages.getOrDefault(buddyListEntry.uuid, 0);
                if (unreads > 0) {
                    String unreadString = "(" + unreads + ")";
                    int unreadX = 10 + this.getTextWidth(clippedText + " ");
                    if (unreadX + this.getTextWidth(unreadString) < 5 + availableWidth - 5) {
                        this.drawText(context, unreadString, unreadX, yPos, GuiUtil.getRGB(255, 255, 0), false);
                    }
                }
            }

            if (buddyListEntry.uuid != null && !buddyListEntry.online
                && this.mouseInRect(5, yPos - 4, availableWidth, 12, this.lastMouseX, this.lastMouseY)) {
                this.hoverTooltip = "Last seen: " + LastSeenTracker.formatLastSeen(buddyListEntry.uuid);
                this.hoverTooltipX = this.lastMouseX - this.x + 10;
                this.hoverTooltipY = this.lastMouseY - this.y - 12;
            }

            lineHeight++;
        }
    }

    @Override
    public void drawWindow(DrawContext context, int bgColor, int fgColor) {
        this.w = 150;
        this.title = "Livemessage";
        super.drawWindow(context, bgColor, fgColor);
        this.updateButtonStates();
        GuiUtil.drawRect(context, 4, this.buddyListY - 1, this.w - 10 + 2, this.h - (this.buddyListY + 10 + 13) + 2, GuiUtil.getRGB(64, 64, 64));
        GuiUtil.drawRect(context, 5, this.buddyListY, this.w - 10, this.h - (this.buddyListY + 10 + 13), GuiUtil.getRGB(36, 36, 36));
        this.liveButtons.forEach(btn -> btn.draw(context));
        generateBuddylist();
        int maxVisibleLines = (this.h - (this.buddyListY + 13 + 15)) / 12;
        int totalEntries = buddyListEntries.size();
        boolean needsScrollbar = totalEntries > maxVisibleLines;
        if (needsScrollbar) {
            int availableHeight = this.h - (this.buddyListY + 10 + 13);
            this.scrollBarHeight = Math.max(20, (int) ((float) maxVisibleLines / totalEntries * availableHeight));
        } else {
            this.scrollBarHeight = 0;
        }

        int listWidth = needsScrollbar ? this.w - 10 - 10 : this.w - 10;
        if (this.active && this.mouseInRect(5, this.buddyListY, listWidth, this.h - (this.buddyListY + 10 + 13), this.lastMouseX, this.lastMouseY)) {
            int i = (int) Math.floor((this.lastMouseY - this.buddyListY - this.y - 3) / 12.0F) + listScrollPosition;
            if (i < buddyListEntries.size() && i >= 0 && i - listScrollPosition < maxVisibleLines) {
                ManeWindow.BuddyListEntry buddyListEntry = buddyListEntries.get(i);
                if (buddyListEntry.uuid != null) {
                    GuiUtil.drawRect(context, 5, this.buddyListY + (i - listScrollPosition) * 12 + 3, listWidth, 12, GuiUtil.getRGB(64, 64, 64));
                }
            }
        }

        this.drawBuddylist(context, listWidth);
        if (needsScrollbar && totalEntries > 1) {
            int availableScrollArea = this.h - (this.buddyListY + 10 + 13) - this.scrollBarHeight;
            int maxScroll = totalEntries - maxVisibleLines;
            int scrollY = this.buddyListY + availableScrollArea * listScrollPosition / Math.max(1, maxScroll);
            GuiUtil.drawRect(
                context,
                5 + this.w - 10 - 10,
                scrollY,
                10,
                this.scrollBarHeight,
                this.scrolling
                    ? GuiUtil.getSingleRGB(128)
                    : (
                    this.mouseInRect(5 + this.w - 10 - 10, this.buddyListY, 10, this.h - (this.buddyListY + 10 + 13), this.lastMouseX, this.lastMouseY)
                    ? GuiUtil.getSingleRGB(96)
                    : GuiUtil.getSingleRGB(64)
                )
            );
        }

        this.drawText(context, this.liveProfile.username, 42, titlebarHeight + 5, GuiUtil.getSingleRGB(255), false);
        this.drawText(context, "online", 42, titlebarHeight + 5 + 11, GuiUtil.getSingleRGB(128), false);
        GuiUtil.drawRect(context, 3, titlebarHeight + 3, 36, 36, GuiUtil.getRGB(60, 148, 100));
        this.drawProfilePic(context, 5, titlebarHeight + 5, this.liveProfile.uuid);
        GuiUtil.drawRect(context, 4, this.h - 13 - 5 - 1, this.w - 10 + 2, 15, GuiUtil.getSingleRGB(64));
        GuiUtil.drawRect(context, 5, this.h - 13 - 5, this.w - 10, 13, GuiUtil.getSingleRGB(24));
        if (searchField.getText().trim().length() == 0) {
            this.drawText(context, "Search...", 8, this.h - 13 - 2, GuiUtil.getSingleRGB(64), false);
        }

        this.liveButtons.forEach(btn -> btn.drawTooltips(context));

        // Drawn last so it renders on top of the scrollbar thumb instead of being on top of it.
        if (this.hoverTooltip != null) {
            GuiUtil.drawTooltip(context, this.hoverTooltip, this.hoverTooltipX, this.hoverTooltipY);
        }
    }

    @Override
    public void drawTextFields(DrawContext context) {
        context.getMatrices().translate(this.x, this.y);
        searchField.setEditableColor(this.active ? -1 : -8355712);
        searchField.setX(8);
        searchField.setY(this.h - 13 - 2);
        searchField.setWidth(this.w - 18);
        searchField.render(context, this.lastMouseX - this.x, this.lastMouseY - this.y, 0.0F);
        context.getMatrices().translate(-this.x, -this.y);
    }

    public static class BuddyListEntry {
        UUID uuid = null;
        String username;
        boolean online;

        BuddyListEntry(UUID uuid, String username, boolean online) {
            this.uuid = uuid;
            this.username = username;
            this.online = online;
        }

        BuddyListEntry(String username) {
            this.username = username;
            this.online = true;
        }
    }
}
