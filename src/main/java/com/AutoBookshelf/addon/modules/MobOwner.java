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
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import org.joml.Vector3d;

import java.io.File;
import java.nio.charset.StandardCharsets;
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

    // Store mob UUID -> Owner UUID mapping
    private final Map<UUID, UUID> mobToOwner = new HashMap<>();
    // Store Owner UUID -> Name mapping
    private final Map<UUID, String> ownerNameCache = new HashMap<>();

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
        mobToOwner.clear();
        ownerNameCache.clear();
    }

    private void loadCache() {
        try {
            cacheFile = new File(mc.runDirectory, "cracked_mob_owner_cache.json");
            if (cacheFile.exists()) {
                String json = new String(Files.readAllBytes(cacheFile.toPath()));
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();

                if (root.has("mobToOwner")) {
                    JsonObject mobMap = root.getAsJsonObject("mobToOwner");
                    for (Map.Entry<String, JsonElement> entry : mobMap.entrySet()) {
                        try {
                            UUID mobUuid = UUID.fromString(entry.getKey());
                            UUID ownerUuid = UUID.fromString(entry.getValue().getAsString());
                            mobToOwner.put(mobUuid, ownerUuid);
                        } catch (Exception ignored) {}
                    }
                }

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

                info("§aLoaded cache: §f" + mobToOwner.size() + " §amobs, §f" + ownerNameCache.size() + " §anames");
            }
        } catch (Exception e) {
            error("Failed to load cache: " + e.getMessage());
        }
    }

    private void saveCache() {
        if (cacheFile == null) {
            cacheFile = new File(mc.runDirectory, "cracked_mob_owner_cache.json");
        }

        try {
            JsonObject root = new JsonObject();

            JsonObject mobMap = new JsonObject();
            for (Map.Entry<UUID, UUID> entry : mobToOwner.entrySet()) {
                mobMap.addProperty(entry.getKey().toString(), entry.getValue().toString());
            }
            root.add("mobToOwner", mobMap);

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

    private UUID getOwnerUuidFromEntity(Entity entity) {
        // 1. Tamed animals – always a valid UUID
        if (entity instanceof TameableEntity tame) {
            return tame.getOwnerUuid();
        }

        // 2. Ender pearls / projectiles – owner may be UUID or player name
        if (entity instanceof EnderPearlEntity) {
            NbtCompound nbt = new NbtCompound();
            entity.writeNbt(nbt);
            if (nbt.containsUuid("Owner")) {
                return nbt.getUuid("Owner");
            }
            if (nbt.contains("Owner", 8)) {
                return parseOwnerTag(nbt.getString("Owner"));
            }
        }

        // 3. General fallback for any entity
        NbtCompound nbt = new NbtCompound();
        entity.writeNbt(nbt);
        if (nbt.containsUuid("Owner")) return nbt.getUuid("Owner");
        if (nbt.containsUuid("owner")) return nbt.getUuid("owner");
        if (nbt.contains("Owner", 8)) return parseOwnerTag(nbt.getString("Owner"));
        if (nbt.contains("owner", 8)) return parseOwnerTag(nbt.getString("owner"));

        if (debugMode.get()) {
            info("§cNo owner UUID for §f" + entity.getType().getName().getString());
        }
        return null;
    }

    private UUID parseOwnerTag(String tag) {
        if (tag.isEmpty()) return null;
        try {
            return UUID.fromString(tag);
        } catch (IllegalArgumentException e) {
            // The tag contains a player name generate an offline UUID
            return UUID.nameUUIDFromBytes(("OfflinePlayer:" + tag).getBytes(StandardCharsets.UTF_8));
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null) return;

        tickCounter++;
        if (tickCounter < 20) return; // Scan every second
        tickCounter = 0;

        int newMobs = 0;

        for (Entity entity : mc.world.getEntities()) {
            boolean isTameable = entity instanceof TameableEntity;
            boolean isPearl = entity instanceof EnderPearlEntity;

            if (!isTameable && !isPearl) continue;

            UUID mobUuid = entity.getUuid();

            // Skip if already cached
            if (mobToOwner.containsKey(mobUuid)) continue;

            // Get owner UUID
            UUID ownerUuid = getOwnerUuidFromEntity(entity);

            if (ownerUuid != null) {
                mobToOwner.put(mobUuid, ownerUuid);
                newMobs++;

                if (debugMode.get()) {
                    String entityName = entity.getType().getName().getString();
                    info("§aCached: §f" + entityName +
                        " §f" + ownerUuid.toString().substring(0, 8) + "...");

                    // Try to get name from tab list
                    String name = findNameInTabList(ownerUuid);
                    if (name != null) {
                        ownerNameCache.put(ownerUuid, name);
                        info("§aName: §f" + name);
                    }
                }

                if (persistentCache.get()) {
                    saveCache();
                }
            }
        }

        if (newMobs > 0 && debugMode.get()) {
            info("§aFound §f" + newMobs + " §anew mob(s) this scan");
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.world == null) return;

        for (Entity entity : mc.world.getEntities()) {
            boolean isTameable = entity instanceof TameableEntity;
            boolean isPearl = entity instanceof EnderPearlEntity;

            if (!isTameable && !isPearl) continue;

            UUID mobUuid = entity.getUuid();
            UUID ownerUuid = mobToOwner.get(mobUuid);

            // Try to get on the fly if not cached
            if (ownerUuid == null) {
                ownerUuid = getOwnerUuidFromEntity(entity);
                if (ownerUuid != null) {
                    mobToOwner.put(mobUuid, ownerUuid);
                }
            }

            if (ownerUuid != null) {
                Utils.set(pos, entity, event.tickDelta);
                pos.add(0, entity.getEyeHeight(entity.getPose()) + 0.75, 0);

                if (NametagUtils.to2D(pos, scale.get())) {
                    String displayText;

                    if (showUUID.get()) {
                        displayText = ownerUuid.toString();
                    } else {
                        String name = getOwnerName(ownerUuid);
                        displayText = (name != null) ? name :
                            (showUnknown.get() ? ownerUuid.toString().substring(0, 8) + "..." : null);
                    }

                    if (displayText != null) {
                        renderNametag(displayText);
                    }
                }
            } else if (showUnknown.get() && debugMode.get()) {
                // Debug: show that entity has no owner
                Utils.set(pos, entity, event.tickDelta);
                pos.add(0, entity.getEyeHeight(entity.getPose()) + 0.75, 0);
                if (NametagUtils.to2D(pos, scale.get())) {
                    renderNametag("§cNo Owner Data");
                }
            }
        }
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

    private String getOwnerName(UUID uuid) {
        // 1. Player is currently online
        PlayerEntity player = mc.world.getPlayerByUuid(uuid);
        if (player != null) {
            String name = player.getName().getString();
            ownerNameCache.put(uuid, name);   // cache for later
            return name;
        }

        // 2. Check local cache
        String name = ownerNameCache.get(uuid);
        if (name != null) return name;

        // 3. Try to resolve via tab list
        name = findNameInTabList(uuid);
        if (name != null) {
            ownerNameCache.put(uuid, name);
            if (persistentCache.get()) saveCache();
            return name;
        }

        // 4. If still unknown, start a Mojang API request
        MeteorExecutor.execute(() -> {
            if (!isActive()) return;
            ProfileResponse res = Http.get("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid.toString().replace("-", ""))
                .sendJson(ProfileResponse.class);
            if (isActive()) {
                String result;
                if (res == null) result = "Failed to get name";
                else result = res.name;
                ownerNameCache.put(uuid, result);
                if (persistentCache.get()) saveCache();
            }
        });

        // Store temporary placeholder while the HTTP request is in flight
        ownerNameCache.put(uuid, "Retrieving");
        return "Retrieving";
    }

    private static class ProfileResponse {
        public String name;
    }

    private String findNameInTabList(UUID uuid) {
        if (mc.getNetworkHandler() == null) return null;

        for (PlayerListEntry entry : mc.getNetworkHandler().getPlayerList()) {
            if (entry.getProfile().getId().equals(uuid)) {
                Text displayName = entry.getDisplayName();
                if (displayName != null) {
                    return displayName.getString();
                }
                return entry.getProfile().getName();
            }
        }
        return null;
    }

    public void showStatus() {
        info("§7=== CrackMobOwner Status ===");
        info("§aMobs mapped: §f" + mobToOwner.size());
        info("§aNames cached: §f" + ownerNameCache.size());
        info("§7Cache file: §f" + (cacheFile != null ? cacheFile.getPath() : "Not initialized"));
    }

    public void clearCache() {
        mobToOwner.clear();
        ownerNameCache.clear();
        info("§aAll caches cleared");
        if (persistentCache.get()) {
            saveCache();
        }
    }
}
