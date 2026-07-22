package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import com.google.gson.*;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.network.Http;
import meteordevelopment.meteorclient.utils.network.MeteorExecutor;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import org.joml.Vector3d;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MobOwner extends Module {
    private static final Color TEXT = new Color(255, 255, 255);
    private static final Color ONLINE_COLOR = new Color(255, 255, 0);
    private static final String UNKNOWN_OWNER_TEXT = "Unknown Owner";

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgCache = settings.createGroup("Cache");
    private final SettingGroup sgDebug = settings.createGroup("Debug");

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("The scale of the text.")
        .defaultValue(1.0)
        .min(0)
        .build()
    );

    private final Setting<Boolean> showUnknown = sgGeneral.add(new BoolSetting.Builder()
        .name("show-unknown")
        .description("Show 'Unknown Owner' when owner cannot be identified.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showUUID = sgGeneral.add(new BoolSetting.Builder()
        .name("show-uuid")
        .description("Show the owner's UUID instead of name.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> showProjectiles = sgGeneral.add(new BoolSetting.Builder()
        .name("show-projectiles")
        .description("Show the owner of other projectiles not just ender pearls.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> highlightOnline = sgGeneral.add(new BoolSetting.Builder()
        .name("highlight-online")
        .description("Show the owner's nametag in yellow while they're online (in the tab list), white when offline.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> persistentCache = sgCache.add(new BoolSetting.Builder()
        .name("persistent-cache")
        .description("Save cache to disk and load on startup.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> debugMode = sgDebug.add(new BoolSetting.Builder()
        .name("debug-mode")
        .description("Show detailed debug information.")
        .defaultValue(true)
        .build()
    );

    private final Vector3d pos = new Vector3d();

    // Caches Owner UUID to Owner Name
    private final Map<UUID, String> ownerNameCache = new HashMap<>();
    private final Map<UUID, UUID> mobToOwner = new HashMap<>();
    // caches whether the owner is currently in the tab list, refreshed once per scan
    private final Map<UUID, Boolean> ownerOnlineCache = new HashMap<>();

    private File cacheFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private int tickCounter = 0;

    public MobOwner() {
        super(Addon.CATEGORY2, "Mob-Owner", "Shows entity owner by saving into cache.");
    }

    @Override
    public void onActivate() {
        if (persistentCache.get()) {
            loadCache();
        }
        if (debugMode.get()) {
            info("§aModule activated. Debug mode ON");
        }
    }

    @Override
    public void onDeactivate() {
        if (persistentCache.get()) {
            saveCache();
        }
        ownerNameCache.clear();
        ownerOnlineCache.clear();
    }

    private void loadCache() {
        try {
            cacheFile = new File(mc.gameDirectory, "mob_owner_cache.json");
            if (cacheFile.exists()) {
                String json = new String(Files.readAllBytes(cacheFile.toPath()));
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();

                if (root.has("ownerNames")) {
                    JsonObject nameMap = root.getAsJsonObject("ownerNames");
                    for (Map.Entry<String, JsonElement> entry : nameMap.entrySet()) {
                        try {
                            UUID ownerUuid = UUID.fromString(entry.getKey());
                            String name = entry.getValue().getAsString();
                            ownerNameCache.put(ownerUuid, name);
                        } catch (Exception ignored) {}
                    }
                }
                info("§aLoaded cache: §f" + ownerNameCache.size() + " §anames");
            }
        } catch (Exception e) {
            error("Failed to load cache: " + e.getMessage());
        }
    }

    private void saveCache() {
        if (cacheFile == null) {
            cacheFile = new File(mc.gameDirectory, "mob_owner_cache.json");
        }
        try {
            JsonObject root = new JsonObject();
            JsonObject nameMap = new JsonObject();
            for (Map.Entry<UUID, String> entry : ownerNameCache.entrySet()) {
                nameMap.addProperty(entry.getKey().toString(), entry.getValue());
            }
            root.add("ownerNames", nameMap);
            Files.write(cacheFile.toPath(), gson.toJson(root).getBytes());
        } catch (Exception e) {
            error("Failed to save cache: " + e.getMessage());
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.level == null) return;

        tickCounter++;
        if (tickCounter < 20) return;   // scan every second
        tickCounter = 0;

        // This snapshot is also reused for the online/offline highlight below.
        Map<UUID, String> tabListNames = new HashMap<>();
        if (mc.getConnection() != null) {
            for (var entry : mc.getConnection().getOnlinePlayers()) {
                UUID id = entry.getProfile().id();
                var displayName = entry.getTabListDisplayName();
                tabListNames.put(id, displayName != null ? displayName.getString() : entry.getProfile().name());
            }
        }

        int newNames = 0;

        for (Entity entity : mc.level.entitiesForRendering()) {
            UUID ownerUuid = getOwnerUuid(entity);
            if (ownerUuid == null) continue;

            // refresh online status every scan so the nametag colour updates as
            // owners join/leave, not just when we first resolve their name.
            ownerOnlineCache.put(ownerUuid, tabListNames.containsKey(ownerUuid));

            if (!ownerNameCache.containsKey(ownerUuid)) {
                // Try to resolve name from tab list snapshot immediately
                String name = tabListNames.get(ownerUuid);
                if (name != null) {
                    ownerNameCache.put(ownerUuid, name);
                    newNames++;
                } else {
                    // Start async Mojang API request
                    MeteorExecutor.execute(() -> {
                        if (!isActive()) return;
                        ProfileResponse res = Http.get("https://sessionserver.mojang.com/session/minecraft/profile/" + ownerUuid.toString().replace("-", ""))
                            .sendJson(ProfileResponse.class);
                        if (isActive()) {
                            if (res == null) ownerNameCache.put(ownerUuid, "Failed to get name");
                            else ownerNameCache.put(ownerUuid, res.name);
                            if (persistentCache.get()) saveCache();
                        }
                    });
                    ownerNameCache.put(ownerUuid, "Retrieving");
                }
            }
        }

        if (newNames > 0 && debugMode.get()) {
            info("§aCached §f" + newNames + " §anew name(s) this scan");
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.level == null) return;

        for (Entity entity : mc.level.entitiesForRendering()) {
            UUID manualUuid = mobToOwner.get(entity.getUUID());
            UUID ownerUuid = manualUuid != null ? manualUuid : getRealOwnerUuid(entity);

            // No owner concept applies to this entity at all (not tameable, not a
            // tracked projectile) -> nothing to show regardless of show-unknown.
            if (ownerUuid == null && manualUuid == null && !isOwnableEntity(entity)) continue;

            boolean unresolved = ownerUuid == null;
            if (unresolved && !showUnknown.get()) continue;

            Utils.set(pos, entity, event.tickDelta);
            pos.add(0, entity.getEyeHeight(entity.getPose()) + 0.75, 0);

            if (NametagUtils.to2D(pos, scale.get())) {
                String name;
                Color color = TEXT;

                if (unresolved) {
                    name = UNKNOWN_OWNER_TEXT;
                } else {
                    name = showUUID.get() ? ownerUuid.toString() : getOwnerName(ownerUuid);
                    if (highlightOnline.get() && Boolean.TRUE.equals(ownerOnlineCache.get(ownerUuid))) {
                        color = ONLINE_COLOR;
                    }
                }

                if (name != null) {
                    renderNametag(name, color);
                }
            }
        }
    }

    private boolean isOwnableEntity(Entity entity) {
        return entity instanceof TamableAnimal || (showProjectiles.get() && entity instanceof Projectile);
    }

    /**
     * Reads the real (non-manual) owner UUID directly from the entity, using the
     * modern API: LazyEntityReference for TameableEntity, and the generic
     * ProjectileEntity owner API for all projectiles.
     */
    private UUID getRealOwnerUuid(Entity entity) {
        if (entity instanceof TamableAnimal tame) {
            var ref = tame.getOwnerReference();
            return ref != null ? ref.getUUID() : null;
        }

        if (showProjectiles.get() && entity instanceof Projectile proj) {
            Entity owner = proj.getOwner();
            return owner != null ? owner.getUUID() : null;
        }
        return null;
    }

    private UUID getOwnerUuid(Entity entity) {
        UUID manualUuid = mobToOwner.get(entity.getUUID());
        if (manualUuid != null) return manualUuid;

        return getRealOwnerUuid(entity);
    }

    private String getOwnerName(UUID ownerUuid) {
        // Check in cache
        String cached = ownerNameCache.get(ownerUuid);
        if (cached != null) return cached;

        // Try from online player
        if (mc.level != null) {
            Player player = mc.level.getPlayerByUUID(ownerUuid);
            if (player != null) {
                String name = player.getName().getString();
                ownerNameCache.put(ownerUuid, name);
                return name;
            }
        }

        // Start an async request
        MeteorExecutor.execute(() -> {
            if (!isActive()) return;
            ProfileResponse res = Http.get("https://sessionserver.mojang.com/session/minecraft/profile/" + ownerUuid.toString().replace("-", ""))
                .sendJson(ProfileResponse.class);
            if (isActive()) {
                if (res == null) ownerNameCache.put(ownerUuid, "Failed to get name");
                else ownerNameCache.put(ownerUuid, res.name);
                if (persistentCache.get()) saveCache();
            }
        });

        ownerNameCache.put(ownerUuid, "Retrieving");
        return "Retrieving";
    }

    private void renderNametag(String name, Color color) {
        TextRenderer text = TextRenderer.get();
        NametagUtils.begin(pos);
        text.beginBig();

        double w = text.getWidth(name);
        double h = text.getHeight();
        double x = -w / 2;
        double y = -h;

        text.render(name, x, y, color);

        text.end();
        NametagUtils.end();
    }

    private static class ProfileResponse {
        public String name;
    }

    /** Called by the AssignOwnerCommand to manually set an owner for an entity */
    public void assignOwner(Entity entity, UUID ownerUuid, String ownerName) {
        mobToOwner.put(entity.getUUID(), ownerUuid);
        ownerNameCache.put(ownerUuid, ownerName);
        if (persistentCache.get()) saveCache();
        if (debugMode.get()) {
            info("Manually assigned " + ownerName + " to " + entity.getType().getDescription().getString());
        }
    }
}
