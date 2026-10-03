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
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
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
    private static final String RETRIEVING_TEXT = "Retrieving";
    private static final String FAILED_NAME_TEXT = "Failed to get name";
    private static final int SCAN_INTERVAL = 20;

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
        .description("Show the owner for any player-fired projectile, not just ender pearls.")
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
    // Ender pearls are transient entities with a fresh UUID per throw, so their
    // manual claims are keyed by the thrower's UUID and re-applied to any pearl
    // that player throws (also survives rejoins, unlike an entity-scoped key).
    private final Map<UUID, UUID> pearlOwnerByThrower = new HashMap<>();
    // caches whether the owner is currently in the tab list, refreshed once per scan
    private final Map<UUID, Boolean> ownerOnlineCache = new HashMap<>();
    // entity UUID -> owner UUID, rebuilt each scan and reused on render ticks so
    // the per-frame 2D loop doesn't re-resolve every tamed entity / projectile.
    private final Map<UUID, UUID> entityOwnerCache = new HashMap<>();

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
        entityOwnerCache.clear();
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

                if (root.has("mobOwners")) {
                    JsonObject mobMap = root.getAsJsonObject("mobOwners");
                    for (Map.Entry<String, JsonElement> entry : mobMap.entrySet()) {
                        try {
                            mobToOwner.put(UUID.fromString(entry.getKey()), UUID.fromString(entry.getValue().getAsString()));
                        } catch (Exception ignored) {
                        }
                    }
                }

                if (root.has("pearlOwners")) {
                    JsonObject pearlMap = root.getAsJsonObject("pearlOwners");
                    for (Map.Entry<String, JsonElement> entry : pearlMap.entrySet()) {
                        try {
                            pearlOwnerByThrower.put(UUID.fromString(entry.getKey()), UUID.fromString(entry.getValue().getAsString()));
                        } catch (Exception ignored) {
                        }
                    }
                }
                info("§aLoaded cache: §f" + ownerNameCache.size() + " §anames, §f"
                    + (mobToOwner.size() + pearlOwnerByThrower.size()) + " §aassignments");
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
            // Transient/failed labels are never persisted: they would outlive the session
            // and mask names that later resolve live from the tab list.
            for (Map.Entry<UUID, String> entry : ownerNameCache.entrySet()) {
                if (isTransientLabel(entry.getValue())) continue;
                nameMap.addProperty(entry.getKey().toString(), entry.getValue());
            }
            root.add("ownerNames", nameMap);

            JsonObject mobMap = new JsonObject();
            for (Map.Entry<UUID, UUID> entry : mobToOwner.entrySet()) {
                mobMap.addProperty(entry.getKey().toString(), entry.getValue().toString());
            }
            root.add("mobOwners", mobMap);

            JsonObject pearlMap = new JsonObject();
            for (Map.Entry<UUID, UUID> entry : pearlOwnerByThrower.entrySet()) {
                pearlMap.addProperty(entry.getKey().toString(), entry.getValue().toString());
            }
            root.add("pearlOwners", pearlMap);

            Files.write(cacheFile.toPath(), gson.toJson(root).getBytes());
        } catch (Exception e) {
            error("Failed to save cache: " + e.getMessage());
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.level == null) return;

        tickCounter++;
        if (tickCounter < SCAN_INTERVAL) return;   // scan every second
        tickCounter = 0;

        entityOwnerCache.clear();
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

            entityOwnerCache.put(entity.getUUID(), ownerUuid);
            // refresh online status every scan so the nametag colour updates as
            // owners join/leave, not just when we first resolve their name.
            ownerOnlineCache.put(ownerUuid, tabListNames.containsKey(ownerUuid));

            // A missing or previously failed/busy entry is re-checked against the tab
            // list, so a name learned live on a cracked server isn't masked by an old
            // failed lookup.
            String cached = ownerNameCache.get(ownerUuid);
            String name = tabListNames.get(ownerUuid);
            if (name != null && (cached == null || isTransientLabel(cached))) {
                ownerNameCache.put(ownerUuid, name);
                if (cached == null) newNames++;
                continue;
            }
            if (cached != null) continue;

            // Only real Mojang (v4) UUIDs can be resolved remotely; offline/cracked
            // UUIDs (v3) would both fail and poison the cache with a permanent error.
            if (ownerUuid.version() != 4) continue;

            MeteorExecutor.execute(() -> {
                if (!isActive()) return;
                ProfileResponse res = Http.get("https://sessionserver.mojang.com/session/minecraft/profile/" + ownerUuid.toString().replace("-", ""))
                    .sendJson(ProfileResponse.class);
                if (mc == null) return;
                // Cache maps are only touched on the render thread, so the disk
                // snapshot never races a tick or command that is mutating them.
                mc.execute(() -> {
                    if (!isActive()) return;
                    if (res == null) ownerNameCache.put(ownerUuid, FAILED_NAME_TEXT);
                    else ownerNameCache.put(ownerUuid, res.name);
                    if (persistentCache.get()) saveCache();
                });
            });
            ownerNameCache.put(ownerUuid, RETRIEVING_TEXT);
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
            UUID cachedUuid = manualUuid != null ? manualUuid : entityOwnerCache.get(entity.getUUID());
            // Fallback resolves real owners and pearl-by-thrower claims the frame a
            // scan hasn't cached yet.
            UUID ownerUuid = cachedUuid != null ? cachedUuid : getOwnerUuid(entity);

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
                }

                // An unresolvable owner (a cracked player with no cache entry and
                // not currently online) falls back to the same Unknown Owner treatment.
                if (UNKNOWN_OWNER_TEXT.equals(name) && !showUnknown.get()) continue;

                if (name != null) {
// Only true names get the online highlight; unknown ones stay plain.
                    if (highlightOnline.get()
                        && !UNKNOWN_OWNER_TEXT.equals(name)
                        && Boolean.TRUE.equals(ownerOnlineCache.get(ownerUuid))) {
                        color = ONLINE_COLOR;
                    }
                    renderNametag(event.graphics, name, color);
                }
            }
        }
    }

    private boolean isOwnableEntity(Entity entity) {
        if (entity instanceof TamableAnimal) return true;
        // Only player-fired projectiles have a resolvable owner; mob-shot ones
        // (skeleton arrows, shulker bullets, ...) carry the mob as owner and are skipped.
        if (showProjectiles.get() && entity instanceof Projectile proj) {
            return proj.getOwner() instanceof Player;
        }
        return false;
    }

    /**
     * Reads the real (non-manual) owner UUID directly from the entity. Tameables use
     * the modern LazyEntityReference API. Projectiles are attributed only when the
     * owner is a player, so a mob's projectile is never mistaken for a player's.
     */
    private UUID getRealOwnerUuid(Entity entity) {
        if (entity instanceof TamableAnimal tame) {
            var ref = tame.getOwnerReference();
            return ref != null ? ref.getUUID() : null;
        }

        if (showProjectiles.get() && entity instanceof Projectile proj) {
            Entity owner = proj.getOwner();
            return owner instanceof Player ? owner.getUUID() : null;
        }
        return null;
    }

    private UUID getOwnerUuid(Entity entity) {
        UUID manualUuid = mobToOwner.get(entity.getUUID());
        if (manualUuid != null) return manualUuid;

        // A thrown pearl has a new UUID every throw, so a persisted pearl claim is
        // looked up by the thrower instead of the entity id.
        if (entity instanceof ThrownEnderpearl pearl && pearl.getOwner() instanceof Player thrower) {
            UUID claimedUuid = pearlOwnerByThrower.get(thrower.getUUID());
            if (claimedUuid != null) return claimedUuid;
        }

        return getRealOwnerUuid(entity);
    }

    private static boolean isTransientLabel(String name) {
        return RETRIEVING_TEXT.equals(name) || FAILED_NAME_TEXT.equals(name);
    }

    private String getOwnerName(UUID ownerUuid) {
        // Live sources outrank the cache: on a cracked server the world/tab list is the
        // only source of truth, so a stale failure label must never hide a name that's
        // resolvable right now.
        String liveName = null;
        if (mc.getConnection() != null) {
            var entry = mc.getConnection().getPlayerInfo(ownerUuid);
            if (entry != null) {
                var displayName = entry.getTabListDisplayName();
                liveName = displayName != null ? displayName.getString() : entry.getProfile().name();
            }
        }
        if (liveName == null && mc.level != null) {
            if (mc.getConnection() != null) {
                PlayerInfo info = mc.getConnection().getPlayerInfo(ownerUuid);
                if (info != null) liveName = info.getProfile().name();
            }
        }
        if (liveName != null) {
            ownerNameCache.put(ownerUuid, liveName);
            return liveName;
        }

        String cached = ownerNameCache.get(ownerUuid);
        if (cached != null) return cached;

        // An offline-style (cracked) UUID can never be resolved remotely; leave it as
        // Unknown so the tag renders like any other unresolvable owner instead of
        // firing a doomed request or caching a permanent failure.
        if (ownerUuid.version() != 4) return UNKNOWN_OWNER_TEXT;

        MeteorExecutor.execute(() -> {
            if (!isActive()) return;
            ProfileResponse res = Http.get("https://sessionserver.mojang.com/session/minecraft/profile/" + ownerUuid.toString().replace("-", ""))
                .sendJson(ProfileResponse.class);
            if (mc == null) return;
            // Same single-threaded rule as the scan path: mutate cache + save on the
            // render thread only.
            mc.execute(() -> {
                if (!isActive()) return;
                if (res == null) ownerNameCache.put(ownerUuid, FAILED_NAME_TEXT);
                else ownerNameCache.put(ownerUuid, res.name);
                if (persistentCache.get()) saveCache();
            });
        });

        ownerNameCache.put(ownerUuid, RETRIEVING_TEXT);
        return RETRIEVING_TEXT;
    }

    private void renderNametag(GuiGraphicsExtractor graphics, String name, Color color) {
        TextRenderer text = TextRenderer.get();
        NametagUtils.begin(pos);
        text.beginBig(graphics);

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
        // Pearls can't be keyed by entity UUID (a new one every throw), so the claim
        // follows the thrower and reapplies to every pearl they throw, across sessions.
        if (entity instanceof ThrownEnderpearl pearl && pearl.getOwner() instanceof Player thrower) {
            pearlOwnerByThrower.put(thrower.getUUID(), ownerUuid);
        } else {
            mobToOwner.put(entity.getUUID(), ownerUuid);
        }
        ownerNameCache.put(ownerUuid, ownerName);
        if (persistentCache.get()) saveCache();
        if (debugMode.get()) {
            info("Manually assigned " + ownerName + " to " + entity.getType().getDescription().getString());
        }
    }

    /**
     * Removes the manual owner of an entity; mirrors assignOwner's keying (per-entity for
     * tameables, per-thrower for pearls). Returns whether a claim existed.
     */
    public boolean clearAssignedOwner(Entity entity) {
        boolean removed;
        if (entity instanceof ThrownEnderpearl pearl && pearl.getOwner() instanceof Player thrower) {
            removed = pearlOwnerByThrower.remove(thrower.getUUID()) != null;
        } else {
            removed = mobToOwner.remove(entity.getUUID()) != null;
        }
        if (removed) {
            entityOwnerCache.remove(entity.getUUID());
            if (persistentCache.get()) saveCache();
        }
        return removed;
    }

    /**
     * Wipes every manual assignment (mobs + pearls); used by .assowner clearAll.
     */
    public int clearAllAssignedOwners() {
        int removed = mobToOwner.size() + pearlOwnerByThrower.size();
        mobToOwner.clear();
        pearlOwnerByThrower.clear();
        if (removed > 0 && persistentCache.get()) saveCache();
        return removed;
    }
}
