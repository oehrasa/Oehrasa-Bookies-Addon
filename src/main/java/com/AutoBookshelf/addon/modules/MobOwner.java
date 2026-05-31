package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import com.google.gson.*;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.network.Http;
import meteordevelopment.meteorclient.utils.network.MeteorExecutor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import org.joml.Vector3d;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MobOwner extends Module {
    private static final Color BACKGROUND = new Color(0, 0, 0, 75);
    private static final Color TEXT = new Color(255, 255, 255);

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

    private File cacheFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private int tickCounter = 0;

    public MobOwner() {
        super(Addon.CATEGORY, "Mob-Owner", "Shows entity owner by saving into cache.");
    }

    @Override
    public void onActivate() {
        if (persistentCache.get()) {
            loadCache();
        }
        if (debugMode.get()) {
            info("§aModule activated - Debug mode ON");
        }
    }

    @Override
    public void onDeactivate() {
        if (persistentCache.get()) {
            saveCache();
        }
        ownerNameCache.clear();
    }

    private void loadCache() {
        try {
            cacheFile = new File(mc.gameDirectory, "cracked_mob_owner_cache.json");
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
            cacheFile = new File(mc.gameDirectory, "cracked_mob_owner_cache.json");
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

        int newNames = 0;

        for (Entity entity : mc.level.entitiesForRendering()) {
            UUID ownerUuid = getOwnerUuid(entity);
            if (ownerUuid == null) continue;

            if (!ownerNameCache.containsKey(ownerUuid)) {
                // Try to resolve name from tab list immediately
                String name = findNameInTabList(ownerUuid);
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
            UUID ownerUuid = getOwnerUuid(entity);
            if (ownerUuid == null) continue;

            Utils.set(pos, entity, event.tickDelta);
            pos.add(0, entity.getEyeHeight(entity.getPose()) + 0.75, 0);

            if (NametagUtils.to2D(pos, scale.get())) {
                String name = getOwnerName(ownerUuid);
                if (name != null) {
                    renderNametag(name);
                }
            }
        }
    }

    /**
     * Extracts the owner's UUID from an entity using the modern API.
     * Uses LazyEntityReference for TameableEntity, direct getOwner() for EnderPearlEntity.
     */
    private UUID getOwnerUuid(Entity entity) {
        // 1) If a manual mapping was added by the command, use that
        UUID manualUuid = mobToOwner.get(entity.getUUID());
        if (manualUuid != null) return manualUuid;

        // 2) Otherwise, read the real owner from the entity
        if (entity instanceof TamableAnimal tame) {
            var ref = tame.getOwnerReference();
            return ref != null ? ref.getUUID() : null;
        }
        if (entity instanceof ThrownEnderpearl pearl) {
            Entity owner = pearl.getOwner();
            return owner != null ? owner.getUUID() : null;
        }
        return null;
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

    private void renderNametag(String name) {
        TextRenderer text = TextRenderer.get();
        NametagUtils.begin(pos);
        text.beginBig();

        double w = text.getWidth(name);
        double h = text.getHeight();
        double x = -w / 2;
        double y = -h;

        text.render(name, x, y, TEXT);

        text.end();
        NametagUtils.end();
    }

    private String findNameInTabList(UUID uuid) {
        if (mc.getConnection() == null) return null;
        for (var entry : mc.getConnection().getOnlinePlayers()) {
            if (entry.getProfile().id().equals(uuid)) {
                var displayName = entry.getTabListDisplayName();
                if (displayName != null) return displayName.getString();
                return entry.getProfile().name();
            }
        }
        return null;
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

    /** Expose the cache for the command (optional)*/
    public void addOwnerName(UUID ownerUuid, String name) {
        ownerNameCache.put(ownerUuid, name);
        if (persistentCache.get()) saveCache();
    }
}
