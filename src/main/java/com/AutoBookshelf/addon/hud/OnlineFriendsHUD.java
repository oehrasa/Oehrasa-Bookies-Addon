package com.AutoBookshelf.addon.hud;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class OnlineFriendsHUD extends HudElement {
    public static final HudElementInfo<OnlineFriendsHUD> INFO = new HudElementInfo<>(
        Addon.HUD_GROUP,
        "online-friends",
        "Displays online friends from your friend list.",
        OnlineFriendsHUD::new
    );

    private static final SettingColor NO_FRIENDS_COLOR = new SettingColor(255, 0, 0);
    private static final SettingColor FRIENDS_COLOR = new SettingColor(0, 255, 0);

    // The tab-list scan happens at most once per second; friend presence can't change faster
    // than that and re-scanning per frame is pure waste.
    private long lastRefreshMs = 0L;
    private static final long REFRESH_INTERVAL_MS = 1000L;
    private final List<String> cachedOnlineFriends = new ArrayList<>();
    private boolean hasCachedFriends = false;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> background = sgGeneral.add(new BoolSetting.Builder()
        .name("background")
        .description("Displays background behind the friend list.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgGeneral.add(new ColorSetting.Builder()
        .name("background-color")
        .description("Color of the background.")
        .defaultValue(new SettingColor(0, 0, 0, 64))
        .build()
    );

    private final Setting<SettingColor> friendColor = sgGeneral.add(new ColorSetting.Builder()
        .name("friend-color")
        .description("Color of friend names.")
        .defaultValue(new SettingColor(173, 216, 230)) // Light blue
        .build()
    );

    public OnlineFriendsHUD() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        if (mc.level == null || mc.getConnection() == null) {
            renderOffline(renderer);
            return;
        }

        List<String> onlineFriends = getOnlineFriends();
        renderFriendsList(renderer, onlineFriends);
    }

    private void renderOffline(HudRenderer renderer) {
        String title = "No Friends Online";
        double width = renderer.textWidth(title, true);
        double height = renderer.textHeight(true);

        setSize(width, height);

        if (background.get()) {
            renderer.quad(x, y, width, height, backgroundColor.get());
        }

        // Red color for "No Friends Online"
        renderer.text(title, x, y, NO_FRIENDS_COLOR, true);
    }

    private void renderFriendsList(HudRenderer renderer, List<String> onlineFriends) {
        String title = onlineFriends.isEmpty() ? "No Friends Online" : "Online Friends";
        double titleWidth = renderer.textWidth(title, true);
        double lineHeight = renderer.textHeight(true);

        // Calculate dimensions
        double maxWidth = titleWidth;
        for (String friend : onlineFriends) {
            double friendWidth = renderer.textWidth(friend, false);
            if (friendWidth > maxWidth) maxWidth = friendWidth;
        }

        double totalHeight = lineHeight;
        if (!onlineFriends.isEmpty()) {
            totalHeight += onlineFriends.size() * lineHeight;
        }

        setSize(maxWidth, totalHeight);

        if (background.get()) {
            renderer.quad(x, y, maxWidth, totalHeight, backgroundColor.get());
        }

        // Title color based on friends status
        SettingColor titleColor = onlineFriends.isEmpty() ? NO_FRIENDS_COLOR : FRIENDS_COLOR;

        // Render title
        renderer.text(title, x, y, titleColor, true);

        // Render friend names in light blue
        double currentY = y + lineHeight;
        for (String friend : onlineFriends) {
            renderer.text(friend, x, currentY, friendColor.get(), false);
            currentY += lineHeight;
        }
    }

    private List<String> getOnlineFriends() {
        long now = System.currentTimeMillis();
        if (hasCachedFriends && now - lastRefreshMs < REFRESH_INTERVAL_MS) {
            return cachedOnlineFriends;
        }
        lastRefreshMs = now;
        hasCachedFriends = true;
        cachedOnlineFriends.clear();

        if (mc.getConnection() == null || mc.player == null) return cachedOnlineFriends;

        // Get our own player name to exclude it
        String ourPlayerName = mc.player.getName().getString();

        for (PlayerInfo player : mc.getConnection().getOnlinePlayers()) {
            String playerName = player.getProfile().name();

            // Skip ourselves and only include friends
            if (!playerName.equals(ourPlayerName) && Friends.get().isFriend(player)) {
                cachedOnlineFriends.add(playerName);
            }
        }

        return cachedOnlineFriends;
    }
}
