package com.AutoBookshelf.addon.modules.BlockRadiusUtils;

import com.AutoBookshelf.addon.utils.DistanceUtil;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PotentSulfurBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.PotentSulfurBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.PotentSulfurState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.AutoBookshelf.addon.modules.BlockRadiusUtils.BlockRadiusShared.ShapeMode;
import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Potent sulfur geyser reach plus the eruption countdown.
 * <p>
 * Countdown semantics (verified by decompiling PotentSulfurBlockEntity): the server
 * decrements waitingCountdown once per second and flips DORMANT &lt;-&gt; ERUPTING via
 * Level.setBlock(flags 3) when it hits 0. The class never calls markUpdated/setChanged,
 * so the client only ever sees a snapshot from the last block-entity data sync
 * (placement or chunk load; resetCountdown() seeds -1). The text is therefore a
 * locally-ticked estimate of the last synced value: adopted when the value changes, and
 * re-seeded from the previous snapshot after a phase flip. This stays correct whether the
 * server syncs once (placement) or every cycle (chunk reloads).
 */
public class SulfurGeyserRenderer {
    private final BlockRadiusShared shared;
    private final Setting<ShapeMode> shapeMode;

    private final Setting<Boolean> showSulfurGeysers;
    private final Setting<Boolean> sulfurCountdown;
    private final Setting<Double> sulfurTextScale;
    private final Setting<SettingColor> dormantSideColor;
    private final Setting<SettingColor> dormantLineColor;
    private final Setting<SettingColor> eruptingSideColor;
    private final Setting<SettingColor> eruptingLineColor;
    private final Setting<SettingColor> continuousSideColor;
    private final Setting<SettingColor> continuousLineColor;

    // Geyser launch reach: a potent sulfur geyser shoots 5 blocks upward per water level
    // above it (water column height 1-4 -> reach 5, 10, 15, 20 blocks).
    private static final int GEYSER_REACH_PER_WATER_LEVEL = 5;

    private static class GeyserData {
        final BlockPos pos;               // potent sulfur block position
        PotentSulfurState state;          // current phase: DORMANT / ERUPTING / CONTINUOUS
        PotentSulfurState prevState;      // previously scanned phase (flip detection)
        int waterHeight;                  // consecutive water blocks above (1..4)
        int lastObservedCountdown = Integer.MIN_VALUE; // raw waitingCountdown last read from the BE
        int countdownSeconds = -1;        // adopted sync snapshot; -1 = unknown
        int seedCountdown = -1;           // last adopted snapshot, reused to restart after a flip
        long countdownSyncedTick;         // game tick the snapshot was adopted at
        // Measured from the phase flips we DO see (they arrive as ordinary block updates), so the
        // countdown can reflect this geyser's real period instead of a guessed constant.
        long dormantStartTick = -1;
        int measuredDormantSeconds = -1;

        GeyserData(BlockPos pos) {
            this.pos = pos;
        }
    }

    private final Map<Long, GeyserData> geysers = new HashMap<>();

    public SulfurGeyserRenderer(SettingGroup sgSulfur, BlockRadiusShared shared,
                                Setting<ShapeMode> shapeMode) {
        this.shared = shared;
        this.shapeMode = shapeMode;

        showSulfurGeysers = sgSulfur.add(new BoolSetting.Builder()
            .name("show-sulfur-geysers")
            .description("Renders the geyser reach of potent sulfur blocks: 5 blocks per water level above (1-4), colored by phase.")
            .defaultValue(true)
            .build()
        );

        // ticked down locally; hidden after a phase flip since the server only syncs the value on placement or chunk load.
        sulfurCountdown = sgSulfur.add(new BoolSetting.Builder()
            .name("sulfur-countdown")
            .description("Shows the seconds until the next eruption, Uses the block entity's synced countdown as a snapshot.")
            .defaultValue(true)
            .visible(showSulfurGeysers::get)
            .build()
        );

        sulfurTextScale = sgSulfur.add(new DoubleSetting.Builder()
            .name("sulfur-text-scale")
            .description("How big the sulfur countdown text should be.")
            .defaultValue(1.25)
            .min(1)
            .sliderMax(4)
            .visible(() -> sulfurCountdown.get() && showSulfurGeysers.get())
            .build()
        );

        dormantSideColor = sgSulfur.add(new ColorSetting.Builder()
            .name("sulfur-dormant-side-color")
            .description("Side color of the geyser column while the potent sulfur is dormant (counting down).")
            .defaultValue(new SettingColor(255, 170, 0, 40))
            .visible(() -> shapeMode.get().sides() && showSulfurGeysers.get())
            .build()
        );

        dormantLineColor = sgSulfur.add(new ColorSetting.Builder()
            .name("sulfur-dormant-line-color")
            .description("Line color of the geyser column while the potent sulfur is dormant (counting down).")
            .defaultValue(new SettingColor(255, 170, 0, 255))
            .visible(() -> shapeMode.get().lines() && showSulfurGeysers.get())
            .build()
        );

        eruptingSideColor = sgSulfur.add(new ColorSetting.Builder()
            .name("sulfur-erupting-side-color")
            .description("Side color of the geyser column while the potent sulfur is erupting.")
            .defaultValue(new SettingColor(255, 85, 85, 45))
            .visible(() -> shapeMode.get().sides() && showSulfurGeysers.get())
            .build()
        );

        eruptingLineColor = sgSulfur.add(new ColorSetting.Builder()
            .name("sulfur-erupting-line-color")
            .description("Line color of the geyser column while the potent sulfur is erupting.")
            .defaultValue(new SettingColor(255, 85, 85, 255))
            .visible(() -> shapeMode.get().lines() && showSulfurGeysers.get())
            .build()
        );

        continuousSideColor = sgSulfur.add(new ColorSetting.Builder()
            .name("sulfur-continuous-side-color")
            .description("Side color of the geyser column for a continuously erupting potent sulfur (lava underneath).")
            .defaultValue(new SettingColor(255, 140, 0, 45))
            .visible(() -> shapeMode.get().sides() && showSulfurGeysers.get())
            .build()
        );

        continuousLineColor = sgSulfur.add(new ColorSetting.Builder()
            .name("sulfur-continuous-line-color")
            .description("Line color of the geyser column for a continuously erupting potent sulfur (lava underneath).")
            .defaultValue(new SettingColor(255, 140, 0, 255))
            .visible(() -> shapeMode.get().lines() && showSulfurGeysers.get())
            .build()
        );
    }

    /**
     * Low end of vanilla's dormant reseed, from the SERVER_WAITING_COUNTDOWN_TICKER bytecode:
     * {@code waitingCountdown = 10 + (dy - 1) * rng.nextIntBetweenInclusive(15, 30)} where
     * {@code dy} is the water column height. Used only when no snapshot was ever synced, so the
     * countdown reads a little under the true value rather than not rendering at all.
     */
    private static int minimumSeed(int waterHeight) {
        return 10 + Math.max(0, waterHeight - 1) * 15;
    }

    /**
     * Picks the seed for a locally-derived countdown chapter, best source first:
     * a real synced snapshot, then the dormant period measured from observed phase flips, then
     * the low end of vanilla's formula.
     * <p>
     * The measured period is the important one: the flips arrive as ordinary block updates, so
     * timing the DORMANT -> ERUPTING edge gives this geyser's actual dormant length (which scales
     * with the water column) instead of the constant 10s the formula fallback produced for every
     * 1-deep column. Time the ERUPTING -> DORMANT edge instead and you measure the whole cycle,
     * dormant plus eruption, which then over-runs every subsequent label.
     */
    private int estimatedSeed(GeyserData data, int waterHeight) {
        if (data.measuredDormantSeconds > 0) return data.measuredDormantSeconds;
        if (data.seedCountdown > 0) return data.seedCountdown;
        return minimumSeed(waterHeight);
    }

    /**
     * Seeds a local countdown chapter for a freshly synced snapshot.
     * <p>
     * The server reseeds on every flip from a value derived only from the water column height, so
     * we reuse the observed seed as the estimate for later chapters. The displayed number drifts
     * from the true one (vanilla redraws it from rng each cycle), but it keeps counting instead of
     * freezing at zero, which is the difference between a useful fuse and a dead label.
     */
    private void startCountdownChapter(GeyserData data, int seed, long nowTick, boolean fromSync) {
        // Only a REAL synced snapshot may become the reusable seed. Storing a fallback here would
        // poison estimatedSeed() forever: it checks seedCountdown first, so a synthetic 10 would be
        // re-read on every cycle and the measured period below would never be reached.
        if (fromSync) data.seedCountdown = seed;
        data.countdownSeconds = seed;
        data.countdownSyncedTick = nowTick;
    }

    public void scan() {
        if (!showSulfurGeysers.get()) {
            geysers.clear();
            return;
        }

        Set<Long> seen = new HashSet<>();
        long nowTick = mc.level.getGameTime();

        for (BlockEntity be : Utils.blockEntities()) {
            if (!(be instanceof PotentSulfurBlockEntity potent)) continue;

            BlockPos pos = be.getBlockPos();
            BlockState bs = mc.level.getBlockState(pos);
            // The block entity can outlive its block (broken/replaced between the scan and this
            // read), so confirm the STATE property is present before reading it.
            if (!bs.hasProperty(PotentSulfurBlock.STATE)) continue;
            PotentSulfurState state = bs.getValue(PotentSulfurBlock.STATE);
            if (state != PotentSulfurState.DORMANT
                && state != PotentSulfurState.ERUPTING
                && state != PotentSulfurState.CONTINUOUS) continue;

            int waterHeight = waterHeightAbove(pos);
            if (waterHeight <= 0) continue;

            long key = pos.asLong();
            seen.add(key);

            GeyserData data = geysers.get(key);
            if (data == null) {
                data = new GeyserData(pos);
                geysers.put(key, data);
            }

            boolean phaseChanged = data.prevState != null && data.prevState != state;
            if (state == PotentSulfurState.DORMANT && data.prevState == PotentSulfurState.ERUPTING) {
                // Flip INTO dormant: start (or restart) the clock. Nothing is measured here.
                data.dormantStartTick = nowTick;
            } else if (state == PotentSulfurState.ERUPTING
                && data.prevState == PotentSulfurState.DORMANT
                && data.dormantStartTick > 0) {
                // Flip OUT of dormant: the elapsed stretch is the dormant phase and nothing else.
                //
                // This must be measured on the DORMANT -> ERUPTING edge, not on the next
                // ERUPTING -> DORMANT edge. Both flips are synced as ordinary block updates, but
                // measuring dormantStart-at-ERUPTING again at ERUPTING-to-DORMANT times the whole
                // cycle (dormant + eruption), and estimatedSeed() feeds that straight back in as
                // the next countdown - so every derived label ran long by the eruption duration and
                // hit 0 only after the geyser had already erupted.
                int measured = (int) ((nowTick - data.dormantStartTick) / 20);
                if (measured > 0) data.measuredDormantSeconds = measured;
                data.dormantStartTick = -1;
            }
            data.prevState = state;
            data.state = state;
            data.waterHeight = waterHeight;

            int fresh = potent.waitingCountdown;
            boolean freshSync = fresh >= 0 && fresh != data.lastObservedCountdown;
            data.lastObservedCountdown = fresh;

            if (state != PotentSulfurState.DORMANT) {
                // Erupting/continuous blocks have no pending countdown to display.
                data.countdownSeconds = -1;
            } else if (freshSync) {
                // A genuinely new value arrived (chunk load, re-join): adopt it as ground truth.
                startCountdownChapter(data, fresh, nowTick, true);
            } else if (data.countdownSeconds < 0) {
                // No snapshot has ever been adopted for this block, so the label would otherwise
                // never appear at all. PotentSulfurBlockEntity only writes "countdown" into the BE
                // tag and never calls markUpdated/setChanged (verified on 26.2), so a client
                // that loaded the chunk after the placement sync can sit on -1 indefinitely - the
                // ctor seeds -1 and resetCountdown() re-seeds -1.
                startCountdownChapter(data, estimatedSeed(data, waterHeight), nowTick, false);
            } else if (phaseChanged) {
                // The server just flipped back to DORMANT and reseeded waitingCountdown locally,
                // but never re-syncs it, so waitingCountdown here is still the pre-flip value.
                // Restart from a locally-derived estimate rather than blanking: blanking made the
                // label disappear permanently after the first eruption, since no further sync can
                // ever arrive while the chunk stays loaded.
                startCountdownChapter(data, estimatedSeed(data, waterHeight), nowTick, false);
            }
            // else: keep the existing chapter and keep ticking it down locally.
        }

        geysers.keySet().removeIf(key -> !seen.contains(key));
    }

    public void clear() {
        geysers.clear();
    }

    /**
     * Counts consecutive water blocks directly above the potent sulfur (the water column
     * that drives the geyser), capped at the vanilla maximum of 4.
     */
    private int waterHeightAbove(BlockPos sulfurPos) {
        int count = 0;
        int y = sulfurPos.getY() + 1;
        while (count < PotentSulfurBlock.ALLOWED_WATER_BLOCKS_ABOVE) {
            shared.scanPos.set(sulfurPos.getX(), y, sulfurPos.getZ());
            if (mc.level.getBlockState(shared.scanPos).getBlock() != Blocks.WATER) break;
            count++;
            y++;
        }
        return count;
    }

    /**
     * Sulfur geyser reach: a column from the water surface up to (5 * water level) blocks.
     */
    public void render(Render3DEvent event, Vec3 cam, double maxDistSq) {
        if (!showSulfurGeysers.get()) return;

        for (GeyserData g : geysers.values()) {
            double x = g.pos.getX();
            double z = g.pos.getZ();
            // waterHeightAbove() counts blocks at pos.y+1 .. pos.y+waterHeight, and a block
            // at y spans [y, y+1], so the surface (top face of the highest water block) is
            // pos.y + waterHeight + 1 - not pos.y + waterHeight, which is that block's bottom.
            double yBot = g.pos.getY() + 1 + g.waterHeight;   // water surface
            double yTop = yBot + (double) g.waterHeight * GEYSER_REACH_PER_WATER_LEVEL;

            if (DistanceUtil.distanceSqToBox(cam, x, yBot, z, x + 1.0, yTop, z + 1.0) > maxDistSq) continue;

            SettingColor side, line;
            if (g.state == PotentSulfurState.ERUPTING) {
                side = eruptingSideColor.get();
                line = eruptingLineColor.get();
            } else if (g.state == PotentSulfurState.CONTINUOUS) {
                side = continuousSideColor.get();
                line = continuousLineColor.get();
            } else {
                side = dormantSideColor.get();
                line = dormantLineColor.get();
            }

            shared.renderBox(event.renderer, x, yBot, z, x + 1.0, yTop, z + 1.0, side, line);
        }
    }

    public void render2D(Render2DEvent event, Vec3 cam, double maxDistSq) {
        if (!showSulfurGeysers.get() || !sulfurCountdown.get()) return;

        for (GeyserData g : geysers.values()) {
            if (g.state != PotentSulfurState.DORMANT || g.countdownSeconds < 0) continue;

            // Position text on the sulfur block, slightly above
            double cx = g.pos.getX() + 0.5;
            double cy = g.pos.getY() + 1.2;   // above sulfur block
            double cz = g.pos.getZ() + 0.5;

            if (cam.distanceToSqr(cx, cy, cz) > maxDistSq) continue;

            // Locally-ticked estimate of the sync snapshot
            long elapsed = (mc.level.getGameTime() - g.countdownSyncedTick) / 20;
            long remain = Math.max(0L, g.countdownSeconds - elapsed);

            Vector3d joml = new Vector3d(cx, cy, cz);
            if (!NametagUtils.to2D(joml, sulfurTextScale.get())) continue;
            NametagUtils.begin(joml, event.graphics);
            TextRenderer.get().begin(event.graphics, 1, false, true);

            String text = remain + "s";
            SettingColor color = dormantLineColor.get();
            double w = TextRenderer.get().getWidth(text) / 2;
            double h = TextRenderer.get().getHeight() / 2;
            TextRenderer.get().render(text, -w, -h, color, true);

            TextRenderer.get().end();
            NametagUtils.end(event.graphics);
        }
    }
}
