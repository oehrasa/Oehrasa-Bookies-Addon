package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import com.google.gson.*;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.text.Text;
import org.joml.Vector3d;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class CrackMobOwner extends Module {
    private static final Color BACKGROUND = new Color(0, 0, 0, 75);
    private static final Color TEXT = new Color(255, 255, 255);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgCache = settings.createGroup("Cache");

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("The scale of the text.")
        .defaultValue(1.0)
        .min(0)
        .build()
    );

    private final Setting<Boolean> showUnknown = sgGeneral.add(new BoolSetting.Builder()
        .name("show-unknown")
        .description("Show 'Unknown Owner' when owner cannot be identified")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> persistentCache = sgCache.add(new BoolSetting.Builder()
        .name("persistent-cache")
        .description("Save cache to disk and load on startup")
        .defaultValue(true)
        .build()
    );

    private final Vector3d pos = new Vector3d();
    
    // Store mob UUID -> Owner UUID mapping (permanent)
    private final Map<UUID, UUID> mobToOwner = new HashMap<>();
    // Store Owner UUID -> Name mapping (dynamic, updates when player online)
    private final Map<UUID, String> ownerNameCache = new HashMap<>();
    
    private File cacheFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public CrackMobOwner() {
        super(Addon.CATEGORY, "CrackMobOwner", "Shows entity owner (works with cracked accounts)");
    }

    @Override
    public void onActivate() {
        if (persistentCache.get()) {
            loadCache();
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
                
                int loadedMobs = 0;
                int loadedNames = 0;
                
                // Load mob -> owner mappings
                if (root.has("mobToOwner")) {
                    JsonObject mobMap = root.getAsJsonObject("mobToOwner");
                    for (Map.Entry<String, JsonElement> entry : mobMap.entrySet()) {
                        try {
                            UUID mobUuid = UUID.fromString(entry.getKey());
                            UUID ownerUuid = UUID.fromString(entry.getValue().getAsString());
                            mobToOwner.put(mobUuid, ownerUuid);
                            loadedMobs++;
                        } catch (Exception e) {
                            // Skip invalid entries
                        }
                    }
                }
                
                // Load owner name cache (no expiration)
                if (root.has("ownerNames")) {
                    JsonObject nameMap = root.getAsJsonObject("ownerNames");
                    for (Map.Entry<String, JsonElement> entry : nameMap.entrySet()) {
                        try {
                            UUID ownerUuid = UUID.fromString(entry.getKey());
                            String name = entry.getValue().getAsString();
                            ownerNameCache.put(ownerUuid, name);
                            loadedNames++;
                        } catch (Exception e) {
                            // Skip invalid entries
                        }
                    }
                }
                
                info("§aLoaded §f" + loadedMobs + " §amob mappings and §f" + loadedNames + " §aowner names");
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
            
            // Save mob -> owner mappings
            JsonObject mobMap = new JsonObject();
            for (Map.Entry<UUID, UUID> entry : mobToOwner.entrySet()) {
                mobMap.addProperty(entry.getKey().toString(), entry.getValue().toString());
            }
            root.add("mobToOwner", mobMap);
            
            // Save owner name cache (no expiration, all saved)
            JsonObject nameMap = new JsonObject();
            for (Map.Entry<UUID, String> entry : ownerNameCache.entrySet()) {
                nameMap.addProperty(entry.getKey().toString(), entry.getValue());
            }
            root.add("ownerNames", nameMap);
            
            Files.write(cacheFile.toPath(), gson.toJson(root).getBytes());
            info("§aSaved §f" + mobToOwner.size() + " §amob mappings and §f" + ownerNameCache.size() + " §aowner names");
        } catch (Exception e) {
            error("Failed to save cache: " + e.getMessage());
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.world == null || mc.getNetworkHandler() == null) return;
        
        for (Entity entity : mc.world.getEntities()) {
            LivingEntity owner = null;
            UUID mobUuid = entity.getUuid();

            // Check for tamed animals
            if (entity instanceof TameableEntity tameable) {
                owner = tameable.getOwner();
            }
            // Check for ender pearls
            else if (entity instanceof EnderPearlEntity pearl) {
                if (pearl.getOwner() instanceof LivingEntity livingOwner) {
                    owner = livingOwner;
                }
            }
            else continue;

            if (owner != null) {
                UUID ownerUuid = owner.getUuid();
                
                // Store mob -> owner mapping for future (even if owner offline)
                if (!mobToOwner.containsKey(mobUuid)) {
                    mobToOwner.put(mobUuid, ownerUuid);
                    if (persistentCache.get()) {
                        saveCache();
                    }
                }
                
                Utils.set(pos, entity, event.tickDelta);
                pos.add(0, entity.getEyeHeight(entity.getPose()) + 0.75, 0);

                if (NametagUtils.to2D(pos, scale.get())) {
                    String ownerName = getOwnerName(owner, ownerUuid);
                    if (ownerName != null && !ownerName.isEmpty()) {
                        renderNametag(ownerName);
                    }
                }
            } else {
                // If no owner entity, check if we have cached owner for this mob
                UUID cachedOwnerUuid = mobToOwner.get(mobUuid);
                if (cachedOwnerUuid != null) {
                    Utils.set(pos, entity, event.tickDelta);
                    pos.add(0, entity.getEyeHeight(entity.getPose()) + 0.75, 0);
                    
                    if (NametagUtils.to2D(pos, scale.get())) {
                        String ownerName = getCachedOwnerName(cachedOwnerUuid);
                        if (ownerName != null && !ownerName.isEmpty()) {
                            renderNametag(ownerName);
                        }
                    }
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

    private String getOwnerName(LivingEntity owner, UUID ownerUuid) {
        // Check if owner is a player entity (online and in render distance)
        if (owner instanceof PlayerEntity playerEntity) {
            String name = playerEntity.getGameProfile().getName();
            // Update cache with fresh name
            ownerNameCache.put(ownerUuid, name);
            return name;
        }

        // Owner is not a player entity (offline or not in render distance)
        return getCachedOwnerName(ownerUuid);
    }
    
    private String getCachedOwnerName(UUID ownerUuid) {
        // Check cache first
        String cached = ownerNameCache.get(ownerUuid);
        if (cached != null) {
            return cached;
        }

        // Scan tab list - this catches players who are online but not in render distance
        String tabListName = findNameInTabList(ownerUuid);
        if (tabListName != null) {
            // Found in tab list, update cache
            ownerNameCache.put(ownerUuid, tabListName);
            return tabListName;
        }

        // No name found anywhere
        return showUnknown.get() ? "Unknown Owner" : null;
    }
    
    private String findNameInTabList(UUID uuid) {
        if (mc.getNetworkHandler() == null) return null;
        
        // Scan all players in the tab list
        for (PlayerListEntry entry : mc.getNetworkHandler().getPlayerList()) {
            if (entry.getProfile().getId().equals(uuid)) {
                // Found matching UUID in tab list
                Text displayName = entry.getDisplayName();
                if (displayName != null) {
                    return displayName.getString();
                }
                return entry.getProfile().getName();
            }
        }
        return null;
    }
    
    // Public methods for manual management
    public void addManualMapping(UUID mobUuid, UUID ownerUuid, String ownerName) {
        mobToOwner.put(mobUuid, ownerUuid);
        ownerNameCache.put(ownerUuid, ownerName);
        info("§aManually mapped mob to owner: §f" + ownerName);
        if (persistentCache.get()) {
            saveCache();
        }
    }
    
    public void updateOwnerName(UUID ownerUuid, String newName) {
        ownerNameCache.put(ownerUuid, newName);
        info("§aUpdated owner name to: §f" + newName);
        if (persistentCache.get()) {
            saveCache();
        }
    }
    
    public void clearCache() {
        mobToOwner.clear();
        ownerNameCache.clear();
        info("§aAll caches cleared");
        if (persistentCache.get()) {
            saveCache();
        }
    }
    
    public int getMobCount() {
        return mobToOwner.size();
    }
    
    public int getNameCount() {
        return ownerNameCache.size();
    }
}