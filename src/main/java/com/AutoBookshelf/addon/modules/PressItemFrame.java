package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import com.google.gson.*;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

public class PressItemFrame extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgCache = settings.createGroup("Cache");

    private final Setting<String> commandTemplate = sgGeneral.add(new StringSetting.Builder()
        .name("command")
        .description("What command to use, {x} {y} {z} {uuid} {i} as placeholders.")
        .defaultValue("invisframe")
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks between processing each item frame.")
        .defaultValue(20)
        .min(1)
        .max(100)
        .sliderRange(1, 100)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotate player's head to look at the item frame briefly.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyWithItem = sgGeneral.add(new BoolSetting.Builder()
        .name("only-with-item")
        .description("Only process item frames that contain an item.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showInfo = sgGeneral.add(new BoolSetting.Builder()
        .name("show-info")
        .description("Display info when a frame is processed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> persistentCache = sgCache.add(new BoolSetting.Builder()
        .name("persistent-sesh")
        .description("Save processed frames to disk and load it, its not consistent.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> resetOnActivate = sgGeneral.add(new BoolSetting.Builder()
        .name("reset-on-activate")
        .description("Clear processed frames every time the module is turned on.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> undo = sgCache.add(new BoolSetting.Builder()
        .name("undo")
        .description("Remove the most recent processed frames from the cache (auto‑disables).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> undoAmount = sgCache.add(new IntSetting.Builder()
        .name("undo-amount")
        .description("How many of the last processed item frames to undo.")
        .defaultValue(10)
        .min(1)
        .max(50)
        .sliderRange(1, 50)
        .build()
    );

    private final Set<UUID> processedFrames = new HashSet<>();
    private final LinkedList<UUID> recentFrames = new LinkedList<>();   // newest at end
    private int timer;

    // Confirmation state
    private UUID pendingCommandUUID = null;
    private int pendingTicks = 0;

    private File cacheFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public PressItemFrame() {
        super(Addon.CATEGORY, "Press-Frame", "Flatten any nearby item frame because You're an Elite Rank.");
    }

    @Override
    public void onActivate() {
        if (persistentCache.get()) {
            loadCache();
        } else {
            processedFrames.clear();
        }

        if (resetOnActivate.get()) {
            processedFrames.clear();
            recentFrames.clear();
            if (showInfo.get()) info("Processed frames list cleared.");
        }

        timer = 0;
        pendingCommandUUID = null;
    }

    @Override
    public void onDeactivate() {
        if (persistentCache.get()) {
            saveCache();
        }
        processedFrames.clear();
        recentFrames.clear();
        pendingCommandUUID = null;
    }

    private void loadCache() {
        cacheFile = new File(mc.runDirectory, "invisframe_cache.json");
        if (cacheFile.exists()) {
            try {
                String json = new String(Files.readAllBytes(cacheFile.toPath()));
                JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
                for (JsonElement elem : arr) {
                    try {
                        UUID uuid = UUID.fromString(elem.getAsString());
                        processedFrames.add(uuid);
                    } catch (Exception ignored) {}
                }
                if (showInfo.get()) info("Loaded " + processedFrames.size() + " processed frames from cache.");
            } catch (Exception e) {
                error("Failed to load cache: " + e.getMessage());
            }
        }
    }

    private void saveCache() {
        if (cacheFile == null) {
            cacheFile = new File(mc.runDirectory, "invisframe_cache.json");
        }
        try {
            JsonArray arr = new JsonArray();
            for (UUID uuid : processedFrames) {
                arr.add(uuid.toString());
            }
            Files.write(cacheFile.toPath(), gson.toJson(arr).getBytes());
        } catch (Exception e) {
            error("Failed to save cache: " + e.getMessage());
        }
    }

    private boolean canSee(ItemFrameEntity frame) {
        Vec3d eyes = mc.player.getEyePos();
        Vec3d center = frame.getPos().add(0, frame.getHeight() / 2.0, 0);

        RaycastContext context = new RaycastContext(
            eyes, center,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            mc.player
        );

        BlockHitResult hit = mc.world.raycast(context);
        // Visible if the raycast misses all blocks, or hits the block the frame is attached to
        return hit.getType() == HitResult.Type.MISS
            || hit.getBlockPos().equals(frame.getBlockPos());
    }

    @EventHandler
    public void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        if (undo.get()) {
            int count = undoAmount.get();
            int removed = 0;
            while (!recentFrames.isEmpty() && removed < count) {
                UUID uuid = recentFrames.removeLast();   // newest first
                if (processedFrames.remove(uuid)) {
                    removed++;
                }
            }
            if (removed > 0 && persistentCache.get()) {
                saveCache();
            }
            if (showInfo.get()) info("Undone " + removed + " frame(s).");
            undo.set(false);

            return;
        }

        // Pending Timeout
        if (pendingCommandUUID != null) {
            pendingTicks--;
            if (pendingTicks <= 0) {
                pendingCommandUUID = null;
            }
            return;
        }

        if (timer > 0) {
            timer--;
            return;
        }

        double reach = mc.player.getEntityInteractionRange();
        ItemFrameEntity target = null;
        for (ItemFrameEntity frame : mc.world.getEntitiesByClass(
            ItemFrameEntity.class,
            mc.player.getBoundingBox().expand(reach),
            e -> true
        )) {
            if (processedFrames.contains(frame.getUuid())) continue;
            if (frame.isInvisible()) continue;
            if (!PlayerUtils.isWithinReach(frame)) continue;
            if (onlyWithItem.get() && frame.getHeldItemStack().isEmpty()) continue;
            if (!canSee(frame)) continue; // LOSAT

            target = frame;
            break;
        }

        if (target == null) return;

        // Safety: if not rotating, ensure crosshair is on the specific frame
        if (!rotate.get()) {
            HitResult hit = mc.crosshairTarget;
            if (hit == null || hit.getType() != HitResult.Type.ENTITY) return;
            EntityHitResult entityHit = (EntityHitResult) hit;
            if (entityHit.getEntity() != target) return;
        }

        ItemStack held = target.getHeldItemStack();
        String itemName = "";
        if (!held.isEmpty()) {
            itemName = Registries.ITEM.getId(held.getItem()).getPath();
        }

        String rawCmd = commandTemplate.get()
            .replace("{x}", String.valueOf(target.getBlockPos().getX()))
            .replace("{y}", String.valueOf(target.getBlockPos().getY()))
            .replace("{z}", String.valueOf(target.getBlockPos().getZ()))
            .replace("{uuid}", target.getUuid().toString())
            .replace("{i}", itemName);

        if (rawCmd.startsWith("/")) {
            rawCmd = rawCmd.substring(1);
        }

        final String finalCmd = rawCmd;
        final UUID frameUUID = target.getUuid();

        // Send the command and mark as pending
        pendingCommandUUID = frameUUID;
        pendingTicks = 40;  // 2 seconds timeout

        if (rotate.get()) {
            Vec3d center = target.getPos().add(0, target.getHeight() / 2.0, 0);
            Rotations.rotate(Rotations.getYaw(center), Rotations.getPitch(center), () -> {
                mc.player.networkHandler.sendChatCommand(finalCmd);
            });
        } else {
            mc.player.networkHandler.sendChatCommand(finalCmd);
        }

        timer = delay.get();
    }

    // Listen for server chat response, Using ReceiveMessageEvent
    @EventHandler
    private void onMessageReceived(ReceiveMessageEvent event) {
        if (pendingCommandUUID == null) return;
        String message = event.getMessage().getString();

        if (message.contains("Successfully made the item frame invisible.")) {
            processedFrames.add(pendingCommandUUID);
            recentFrames.addLast(pendingCommandUUID);
            while (recentFrames.size() > 50) recentFrames.removeFirst();

            if (persistentCache.get()) saveCache();

            if (showInfo.get()) {
                info("Confirmed invisible, saved frame " + pendingCommandUUID.toString().substring(0, 8) + "...");
            }

            pendingCommandUUID = null;   // clear
        } else if (message.contains("Successfully made the item frame visible again.") ||
            message.contains("You have to look at an item frame to run this command.")) {
            // Toggle or error, discard this attempt, we'll try again next tick
            pendingCommandUUID = null;
        }
    }
}
