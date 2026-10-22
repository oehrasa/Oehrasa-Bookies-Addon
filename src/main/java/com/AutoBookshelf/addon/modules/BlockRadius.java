package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.modules.BlockRadiusUtils.*;
import com.AutoBookshelf.addon.modules.BlockRadiusUtils.BlockRadiusShared.ShapeMode;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.phys.Vec3;

/**
 * Renders the range of powered beacons, lightning rods, active conduits, linked creakings,
 * Warden ESP, splash/lingering potion areas, and sponge absorption preview.
 * <p>
 * Each feature owns its settings, scan state and render path in its own class under
 * {@code com.AutoBookshelf.addon.modules.BlockRadiusUtils}; this module is the wiring that owns
 * the shared throttled tick and the render dispatch.
 */
public class BlockRadius extends Module {

    private final Setting<Integer> updateInterval;

    private final BlockRadiusShared shared;

    private final BeaconRenderer beacons;
    private final LightningRodRenderer lightningRods;
    private final ConduitRenderer conduits;
    private final CreakingRenderer creakings;
    private final WardenEsp wardens;
    private final SpongeAbsorptionRenderer sponge;
    private final SplashPotionAoE potions;

    private int tickCounter = 0;

    public BlockRadius() {
        super(Addon.CATEGORY, "Block-Radius",
            "Renders the range of potions, beacons, lightning rods, conduits, linked creakings, Wardens, and sponges.");

        // SettingGroup handles are constructor locals on purpose, not fields: createGroup registers
        // the group into settings immediately and the container owns it.
        SettingGroup sgGeneral = settings.getDefaultGroup();
        updateInterval = sgGeneral.add(new IntSetting.Builder()
            .name("update-interval")
            .description("How often (in ticks) blocks/wardens/creakings are rescanned. Raise for better performance.")
            .defaultValue(20)
            .range(2, 200)
            .build()
        );

        Setting<Integer> searchRadius = sgGeneral.add(new IntSetting.Builder()
            .name("search-radius")
            .description("Horizontal search radius in chunks.")
            .defaultValue(6)
            .range(1, 16)
            .build()
        );

        Setting<Double> maxRenderDistance = sgGeneral.add(new DoubleSetting.Builder()
            .name("max-render-distance")
            .description("Boxes farther than this from the camera are skipped.")
            .defaultValue(256.0)
            .range(16, 1024)
            .build()
        );

        SettingGroup sgRender = settings.createGroup("Render");
        Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
            .name("shape-mode")
            .description("How the shapes are rendered.")
            .defaultValue(ShapeMode.Both)
            .build()
        );

        shared = new BlockRadiusShared(shapeMode, maxRenderDistance, searchRadius);

        beacons = new BeaconRenderer(sgGeneral, sgRender, shared, shapeMode);
        lightningRods = new LightningRodRenderer(sgGeneral, sgRender, shared, shapeMode);
        conduits = new ConduitRenderer(sgGeneral, sgRender, shared, shapeMode);
        SettingGroup sgCreaking = settings.createGroup("Creaking");
        creakings = new CreakingRenderer(sgCreaking, shared);
        SettingGroup sgWarden = settings.createGroup("Warden");
        wardens = new WardenEsp(sgWarden, shared);
        SettingGroup sgSponge = settings.createGroup("Sponge Absorption");
        sponge = new SpongeAbsorptionRenderer(sgSponge, shared, shapeMode);
        // Appended last so no existing tab shifts position in the GUI.
        SettingGroup sgPotions = settings.createGroup("Potion AoE");
        potions = new SplashPotionAoE(sgPotions, shared);
    }

    @Override
    public void onActivate() {
        // Force an immediate scan on the first tick instead of waiting a full interval.
        tickCounter = updateInterval.get() - 1;
        // Block updates are only delivered to active modules, so anything that changed while this
        // module was off was never invalidated. Drop the cached beacon shells (and the rod cache,
        // same reasoning) rather than trusting them on the first scan after re-activation.
        beacons.clear();
        lightningRods.clear();
        sponge.invalidate();
        wardens.clear();
        potions.clear();
    }

    @Override
    public void onDeactivate() {
        beacons.clear();
        lightningRods.clear();
        conduits.clear();
        creakings.clear();
        wardens.clear();
        sponge.invalidate();
        potions.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null) return;

        // Crosshair-driven preview: has to follow the view every tick, so it stays above the
        // updateInterval gate. Its search is bounded (Manhattan 6, 64 results), so per-tick is cheap.
        sponge.scan();

        // Same reasoning, stronger: a thrown potion detonates within ~1.5s, so a throttled scan
        // would miss most bursts outright. Detection is one pass over entitiesForRendering().
        potions.scan();

        if (++tickCounter < updateInterval.get()) return;
        tickCounter = 0;

        beacons.scan();
        lightningRods.scan();
        conduits.scan();
        creakings.scan();
        wardens.scan();
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        // Keep the lightning-rod chunk cache fresh; only rod block changes touch it.
        if (lightningRods.isRodUpdate(event.newState.getBlock(), event.oldState.getBlock())) {
            lightningRods.invalidateChunkAt(event.pos);
        }

        // Invalidate the sponge preview when anything inside its search radius changes; flow
        // updates and kelp growth both land here, and either can add or remove a water cell.
        sponge.invalidateNear(event.pos);

        // A block placed or broken anywhere inside a beacon's box or the one-block ring its
        // exposure test reads -> changes the box's solid-voxel shell; drop the cached one.
        beacons.invalidateAt(event.pos);
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        // A full chunk resend (on re-join or data refresh) doesn't raise a
        // BlockUpdateEvent, so drop the cached rod list of that chunk to avoid
        // keeping stale entries until the chunk next leaves the search radius.
        int chunkX = event.chunk().getPos().x();
        int chunkZ = event.chunk().getPos().z();
        lightningRods.invalidateChunk(chunkX, chunkZ);

        // Same for a cached beacon shell overlapping the resent chunk.
        beacons.invalidateChunk(chunkX, chunkZ);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.level == null) return;

        Vec3 cam = mc.gameRenderer.getMainCamera().position();
        double maxDistSq = shared.maxDistSq();

        beacons.render(event, cam, maxDistSq);
        lightningRods.render(event, cam, maxDistSq);
        conduits.render(event, cam, maxDistSq);
        creakings.render(event, cam, maxDistSq);
        wardens.render(event);
        sponge.render(event, cam, maxDistSq);
        potions.render(event, cam, maxDistSq);
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.player == null || mc.level == null) return;

        Vec3 cam = mc.gameRenderer.getMainCamera().position();
        potions.render2D(event, cam, shared.maxDistSq());
    }
}
