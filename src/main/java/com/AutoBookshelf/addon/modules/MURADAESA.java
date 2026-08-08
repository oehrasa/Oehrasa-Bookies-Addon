package com.AutoBookshelf.addon.modules;
//1.21.11 yarn mapping

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;

import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public class MURADAESA extends Module {
    private static final double PLAYER_ESP_LIMIT_BLOCKS = 128.0;
    private static final double PLAYER_ESP_LIMIT_SQ =
        PLAYER_ESP_LIMIT_BLOCKS * PLAYER_ESP_LIMIT_BLOCKS;
    private static final double CONFIRM_PLAYER_RADIUS_BLOCKS = 12.0;
    private static final double CONFIRM_PLAYER_RADIUS_SQ =
        CONFIRM_PLAYER_RADIUS_BLOCKS * CONFIRM_PLAYER_RADIUS_BLOCKS;
    private static final long RECENT_EVENT_WINDOW_MS = 2500L;
    private static final long STATE_CACHE_TTL_MS = 120000L;

    // Burst-suppression: if a resync/anti-xray correction slams a region with
    // many block updates in a short window, that's not a player.
    private static final long BURST_WINDOW_MS = 250L;
    private static final int BURST_THRESHOLD = 12;
    private static final double BURST_RADIUS_BLOCKS = 24.0;
    private static final double BURST_RADIUS_SQ = BURST_RADIUS_BLOCKS * BURST_RADIUS_BLOCKS;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> showBoxes = sgRender.add(new BoolSetting.Builder()
        .name("show-boxes")
        .description("Draw boxes around suspicious far activity spots.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyBeyondPlayerEspRange = sgGeneral.add(new BoolSetting.Builder()
        .name("only-beyond-128")
        .description("Only detect activity outside the typical PlayerESP range.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showTracers = sgRender.add(new BoolSetting.Builder()
        .name("show-tracers")
        .description("Draw tracers to suspicious far activity spots.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> tracerFlash = sgRender.add(new BoolSetting.Builder()
        .name("tracer-flash")
        .description("Make tracers pulse with a smooth fade.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> detectRedstoneInteraction = sgGeneral.add(new BoolSetting.Builder()
        .name("detect-redstone")
        .description("Also score powered/open/triggered/lit toggles as weaker hints.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chatAlerts = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-alerts")
        .description("Show chat alerts when a block-change signal is detected.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> suppressBursts = sgGeneral.add(new BoolSetting.Builder()
        .name("suppress-bursts")
        .description("Ignore dense clusters of block updates (chunk resyncs, anti-xray corrections) instead of scoring them as player activity.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> markerLifetimeSec = sgGeneral.add(new IntSetting.Builder()
        .name("marker-lifetime")
        .description("How long a AESA marker stays on screen, in seconds.")
        .defaultValue(35)
        .min(5)
        .sliderRange(5, 180)
        .build()
    );

    private final Setting<Integer> maxMarkers = sgGeneral.add(new IntSetting.Builder()
        .name("max-markers")
        .description("Maximum number of simultaneous AESA markers.")
        .defaultValue(80)
        .min(10)
        .sliderRange(10, 300)
        .build()
    );

    private final Setting<SettingColor> placeColor = sgRender.add(new ColorSetting.Builder()
        .name("place-color")
        .description("Color for block placements.")
        .defaultValue(new SettingColor(255, 80, 80))
        .build()
    );

    private final Setting<SettingColor> breakColor = sgRender.add(new ColorSetting.Builder()
        .name("break-color")
        .description("Color for block breaks.")
        .defaultValue(new SettingColor(80, 200, 255))
        .build()
    );

    private final Setting<SettingColor> redstoneColor = sgRender.add(new ColorSetting.Builder()
        .name("redstone-color")
        .description("Color for redstone/interaction toggles.")
        .defaultValue(new SettingColor(255, 200, 80))
        .build()
    );

    private final Map<Long, AESAPing> pings = new ConcurrentHashMap<>();
    private final Map<Long, CachedState> knownStates = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<RecentEvent> recentEvents = new ConcurrentLinkedDeque<>();

    public MURADAESA() {
        super(Addon.CATEGORY, "MURAD-AESA",
            "Detects likely player activity outside 128 blocks by scoring world-change packet patterns.");
    }

    @Override
    public void onDeactivate() {
        pings.clear();
        knownStates.clear();
        recentEvents.clear();
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (mc.player == null || mc.world == null)
            return;

        if (mc.isIntegratedServerRunning())
            return;

        Packet<?> packet = event.packet;
        if (packet instanceof BlockUpdateS2CPacket blockUpdate) {
            handleBlockChange(blockUpdate.getPos(), blockUpdate.getState());
            return;
        }

        if (packet instanceof ChunkDeltaUpdateS2CPacket deltaUpdate) {
            deltaUpdate.visitUpdates(this::handleBlockChange);
            return;
        }
    }

    private void handleBlockChange(BlockPos pos, BlockState newState) {
        if (pos == null || newState == null)
            return;

        if (!isInAllowedRange(pos))
            return;

        long now = System.currentTimeMillis();
        long key = pos.asLong();

        BlockState oldState = resolveOldState(pos, key, newState, now);
        if (oldState == null) {
            knownStates.put(key, new CachedState(newState, now));
            return;
        }

        DetectionResult detection = classifyRelevantTransition(oldState, newState);
        knownStates.put(key, new CachedState(newState, now));

        if (detection.score <= 0)
            return;

        if (suppressBursts.get() && isWithinBurst(pos, now))
            return;

        updatePing(pos, detection.kind, detection.oldId, detection.newId);
        recordRecentEvent(pos, now);
    }

    private DetectionResult classifyRelevantTransition(BlockState oldState, BlockState newState) {
        if (oldState == null || newState == null || oldState == newState)
            return DetectionResult.NONE;

        boolean oldAir = oldState.isAir();
        boolean newAir = newState.isAir();
        boolean oldFluid = !oldState.getFluidState().isEmpty();
        boolean newFluid = !newState.getFluidState().isEmpty();
        String oldId = getBlockId(oldState);
        String newId = getBlockId(newState);
        if (isLikelyNaturalTransition(oldId, newId))
            return DetectionResult.NONE;

        if (isFluidOrFireState(oldState) || isFluidOrFireState(newState))
            return DetectionResult.NONE;

        if (oldAir && !newAir && !newFluid)
            return new DetectionResult(4.0, "PLACE", oldId, newId);

        if (!oldAir && !oldFluid && newAir)
            return new DetectionResult(4.0, "BREAK", oldId, newId);

        if (!detectRedstoneInteraction.get())
            return DetectionResult.NONE;

        if (oldState.getBlock() == newState.getBlock()) {
            if (hasInteractiveFlip(oldState, newState))
                return new DetectionResult(1.7, "REDSTONE", oldId, newId);
            return DetectionResult.NONE;
        }

        return DetectionResult.NONE;
    }

    private boolean isLikelyNaturalTransition(String oldId, String newId) {
        return isLikelyNaturalBlock(oldId) || isLikelyNaturalBlock(newId);
    }

    private boolean isLikelyNaturalBlock(String blockId) {
        if (blockId == null)
            return false;

        if ("minecraft:air".equals(blockId))
            return false;

        return blockId.contains("vine") || blockId.contains("amethyst_bud")
            || blockId.contains("mushroom") || blockId.contains("short_grass")
            || blockId.contains("tall_grass") || blockId.contains("fern")
            || blockId.contains("lichen") || blockId.contains("moss")
            || blockId.contains("seagrass") || blockId.contains("kelp")
            || blockId.contains("sugar_cane") || blockId.contains("cactus")
            || blockId.contains("bamboo") || blockId.contains("dripleaf")
            || blockId.contains("dripstone") || blockId.contains("cocoa")
            || blockId.contains("nether_wart") || blockId.contains("crop")
            || blockId.contains("sweet_berry_bush")
            || blockId.contains("leaves") || blockId.contains("snow")
            || blockId.contains("ice") || blockId.contains("sand")
            || blockId.contains("gravel") || blockId.contains("concrete_powder");
    }

    private boolean hasInteractiveFlip(BlockState oldState, BlockState newState) {
        try {
            for (Property<?> property : oldState.getProperties()) {
                String name = property.getName();
                if (!name.equals("open") && !name.equals("powered")
                    && !name.equals("triggered") && !name.equals("lit"))
                    continue;

                Comparable<?> oldVal = oldState.get(property);
                Comparable<?> newVal = newState.get(property);
                if (oldVal != null && newVal != null && !oldVal.equals(newVal))
                    return true;
            }
        } catch (Exception ignored) {
        }

        return false;
    }

    private String getBlockId(BlockState state) {
        if (state == null)
            return "minecraft:air";

        return Registries.BLOCK.getId(state.getBlock()).toString();
    }

    private boolean isFireId(String blockId) {
        if (blockId == null)
            return false;

        return "minecraft:fire".equals(blockId) || "minecraft:soul_fire".equals(blockId);
    }

    private boolean isFluidOrFireState(BlockState state) {
        if (state == null)
            return true;

        if (!state.getFluidState().isEmpty())
            return true;

        return isFireId(getBlockId(state));
    }

    private boolean isChunkLoaded(BlockPos pos) {
        if (mc.world == null)
            return false;

        return mc.world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private void recordRecentEvent(BlockPos pos, long now) {
        recentEvents.addLast(new RecentEvent(pos.toImmutable(), now));
        pruneRecentEvents(now);
    }

    private void pruneRecentEvents(long now) {
        while (!recentEvents.isEmpty()) {
            RecentEvent first = recentEvents.peekFirst();
            if (first == null || now - first.timeMs <= RECENT_EVENT_WINDOW_MS)
                break;
            recentEvents.pollFirst();
        }
    }

    private boolean isWithinBurst(BlockPos pos, long now) {
        Vec3d center = Vec3d.ofCenter(pos);
        int nearbyRecent = 0;

        for (RecentEvent event : recentEvents) {
            if (now - event.timeMs > BURST_WINDOW_MS)
                continue;

            if (Vec3d.ofCenter(event.pos).squaredDistanceTo(center) <= BURST_RADIUS_SQ)
                nearbyRecent++;

            if (nearbyRecent >= BURST_THRESHOLD)
                return true;
        }

        return false;
    }

    private void updatePing(BlockPos pos, String kind, String oldId, String newId) {
        long key = pos.asLong();
        long now = System.currentTimeMillis();
        AESAPing ping = pings.get(key);
        if (ping == null) {
            ping = new AESAPing(pos.toImmutable());
            pings.put(key, ping);
        }

        ping.lastUpdateMs = now;
        ping.hits++;
        ping.lastKind = kind;
        ping.lastOldId = oldId;
        ping.lastNewId = newId;

        if (chatAlerts.get() && now - ping.lastAlertMs >= 300L) {
            Vec3d center = Vec3d.ofCenter(ping.pos);
            int distanceBlocks = (int) Math.round(Math.sqrt(
                center.squaredDistanceTo(mc.player.getX(), mc.player.getY(), mc.player.getZ())));
            String rangeSuffix = onlyBeyondPlayerEspRange.get()
                ? String.format(" (%db away, outside %.0fb).", distanceBlocks, PLAYER_ESP_LIMIT_BLOCKS)
                : String.format(" (%db away).", distanceBlocks);
            info(String.format(
                "%s %s -> %s at %d, %d, %d%s", ping.lastKind,
                ping.lastOldId, ping.lastNewId, ping.pos.getX(),
                ping.pos.getY(), ping.pos.getZ(), rangeSuffix));
            ping.lastAlertMs = now;
        }

        trimToMarkerLimit();
    }

    private void trimToMarkerLimit() {
        int max = maxMarkers.get();
        if (pings.size() <= max)
            return;

        ArrayList<AESAPing> sorted = new ArrayList<>(pings.values());
        sorted.sort(Comparator.comparingLong(p -> p.lastUpdateMs));
        int toRemove = pings.size() - max;
        for (int i = 0; i < toRemove && i < sorted.size(); i++)
            pings.remove(sorted.get(i).pos.asLong());
    }

    private BlockState resolveOldState(BlockPos pos, long key, BlockState newState, long now) {
        CachedState cached = knownStates.get(key);
        if (cached != null && now - cached.timeMs <= STATE_CACHE_TTL_MS) {
            if (cached.state != newState)
                return cached.state;
        }

        if (!isChunkLoaded(pos))
            return null;

        return mc.world.getBlockState(pos);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        long now = System.currentTimeMillis();

        long lifetimeMs = markerLifetimeSec.get() * 1000L;
        pings.entrySet().removeIf(entry -> {
            AESAPing ping = entry.getValue();
            return now - ping.lastUpdateMs > lifetimeMs || shouldRevokePing(ping);
        });

        pruneRecentEvents(now);

        knownStates.entrySet().removeIf(e -> now - e.getValue().timeMs > STATE_CACHE_TTL_MS);
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || pings.isEmpty())
            return;

        List<AESAPing> sorted = new ArrayList<>(pings.values()).stream()
            .filter(p -> isInAllowedRange(p.pos))
            .sorted(Comparator.comparingLong((AESAPing p) -> p.lastUpdateMs).reversed())
            .limit(maxMarkers.get())
            .toList();

        for (AESAPing ping : sorted) {
            SettingColor color = getColorForKind(ping.lastKind);
            if (tracerFlash.get())
                color = flashColor(color);
            Box box = new Box(ping.pos);

            if (showBoxes.get()) {
                event.renderer.box(box, color, color, ShapeMode.Lines, 0);
            }

            if (showTracers.get()) {
                Vec3d eyes = mc.player.getCameraPosVec(event.tickDelta);
                Vec3d center = box.getCenter();
                event.renderer.line(eyes.x, eyes.y, eyes.z,
                    center.x, center.y, center.z, color);
            }
        }
    }

    private SettingColor flashColor(SettingColor color) {
        double t = (System.currentTimeMillis() % 1000L) / 1000.0;
        double pulse = 0.5 + 0.5 * Math.sin(t * Math.PI * 2);
        int alpha = (int) (color.a * (0.4 + 0.6 * pulse));
        return new SettingColor(color.r, color.g, color.b, alpha);
    }

    private SettingColor getColorForKind(String kind) {
        return switch (kind) {
            case "PLACE" -> placeColor.get();
            case "BREAK" -> breakColor.get();
            case "REDSTONE" -> redstoneColor.get();
            default -> placeColor.get();
        };
    }

    private boolean isBeyondPlayerEspRange(BlockPos pos) {
        if (mc.player == null || pos == null)
            return false;

        Vec3d center = Vec3d.ofCenter(pos);
        return mc.player.getEntityPos().squaredDistanceTo(center) > PLAYER_ESP_LIMIT_SQ;
    }

    private boolean isInAllowedRange(BlockPos pos) {
        if (pos == null || mc.player == null)
            return false;

        if (!onlyBeyondPlayerEspRange.get())
            return true;

        return isBeyondPlayerEspRange(pos);
    }

    private boolean shouldRevokePing(AESAPing ping) {
        if (ping == null || ping.pos == null || mc.player == null || mc.world == null)
            return false;

        if (!onlyBeyondPlayerEspRange.get())
            return false;

        if (isBeyondPlayerEspRange(ping.pos))
            return false;

        return !hasTrackedPlayerNear(ping.pos);
    }

    private boolean hasTrackedPlayerNear(BlockPos pos) {
        Vec3d center = Vec3d.ofCenter(pos);
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == null || player == mc.player || player.isRemoved())
                continue;

            if (player.getEntityPos().squaredDistanceTo(center) <= CONFIRM_PLAYER_RADIUS_SQ)
                return true;
        }

        return false;
    }

    private static final class AESAPing {
        private final BlockPos pos;
        private long lastUpdateMs = System.currentTimeMillis();
        private long lastAlertMs;
        private int hits;
        private String lastKind = "UNKNOWN";
        private String lastOldId = "unknown";
        private String lastNewId = "unknown";

        private AESAPing(BlockPos pos) {
            this.pos = pos;
        }
    }

    private record DetectionResult(double score, String kind, String oldId, String newId) {
        private static final DetectionResult NONE = new DetectionResult(0, "", "", "");
    }

    private record CachedState(BlockState state, long timeMs) {
    }

    private record RecentEvent(BlockPos pos, long timeMs) {
    }
}
