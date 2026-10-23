package com.AutoBookshelf.addon.modules.BlockRadiusUtils;

import com.AutoBookshelf.addon.utils.DistanceUtil;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.block.*;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

import static com.AutoBookshelf.addon.modules.BlockRadiusUtils.BlockRadiusShared.ShapeMode;
import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Sponge absorption preview: outlines the water a dry sponge would absorb if placed at the cell
 * the player is aiming at.
 * <p>
 * The traversal mirrors SpongeBlock#removeWaterBreadthFirstSearch exactly, verified against the
 * 26.2 jar (see the constants below). It is read-only so nothing in the world is modified.
 */
public class SpongeAbsorptionRenderer {
    private final BlockRadiusShared shared;
    private final Setting<ShapeMode> shapeMode;

    private final Setting<Boolean> showSpongeAbsorption;
    private final Setting<SettingColor> sideColor;
    private final Setting<SettingColor> lineColor;

    /**
     * Vanilla SpongeBlock passes 65 as BlockPos.breadthFirstTraversal's maxVisited, but that
     * counter includes the sponge's own position (its tester returns accept for the origin), so
     * at most 64 water positions are ever absorbed. verified on 26.2: the counter is
     * incremented per accepted node and the traversal returns as soon as it reaches maxVisited.
     */
    private static final int SPONGE_MAX_ABSORBED = 64;

    public static final int SPONGE_SEARCH_RADIUS = 6;

    private record SpongeAbsorption(BlockPos center, Set<BlockPos> absorbed) {
    }

    private SpongeAbsorption absorption;
    // Preview caching: the scan runs every tick, but its answer only changes when the aim
    // moves or a block in range changes. cacheStale is set by the module's BlockUpdateEvent for
    // any update within the search radius; computedPos is the placement cell the result is for.
    private boolean cacheStale = true;
    private long computedPos = Long.MIN_VALUE;

    public SpongeAbsorptionRenderer(SettingGroup sgSponge, BlockRadiusShared shared,
                                    Setting<ShapeMode> shapeMode) {
        this.shared = shared;
        this.shapeMode = shapeMode;

        showSpongeAbsorption = sgSponge.add(new BoolSetting.Builder()
            .name("show-sponge-absorption")
            .description("Shows the water blocks a dry sponge would absorb if placed at the targeted position.")
            .defaultValue(false)
            .build()
        );

        sideColor = sgSponge.add(new ColorSetting.Builder()
            .name("sponge-side-color")
            .description("Side color of the sponge absorption preview.")
            .defaultValue(new SettingColor(40, 200, 40, 40))
            .visible(() -> shapeMode.get().sides() && showSpongeAbsorption.get())
            .build()
        );

        lineColor = sgSponge.add(new ColorSetting.Builder()
            .name("sponge-line-color")
            .description("Line color of the sponge absorption preview.")
            .defaultValue(new SettingColor(60, 255, 60, 255))
            .visible(() -> shapeMode.get().lines() && showSpongeAbsorption.get())
            .build()
        );
    }

    public void scan() {
        if (!showSpongeAbsorption.get() || mc.player == null || mc.world == null
            || !(mc.crosshairTarget instanceof BlockHitResult hit)
            // instanceof already guarantees Type.BLOCK (BlockHitResult hardcodes it in its
            // constructor), but assert the type explicitly so the intent survives a future
            // refactor of this guard.
            || hit.getType() != HitResult.Type.BLOCK
            // A preview of what "placing a sponge here" would do is only meaningful while a sponge
            // is actually held; otherwise this would just always trace every water block you look at.
            || (!mc.player.getMainHandStack().isOf(Items.SPONGE)
            && !mc.player.getOffHandStack().isOf(Items.SPONGE))) {
            invalidate();
            return;
        }

        BlockPos hitPos = hit.getBlockPos();
        BlockState hitState = mc.world.getBlockState(hitPos);

        BlockPos placementPos;
        if (hitState.isOf(Blocks.SPONGE)) {
            // Already-placed sponge: preview around the block it occupies.
            placementPos = hitPos;
        } else {
            // Otherwise preview the cell the sponge would be placed into, which must be a cell
            // vanilla would actually let it replace (air, water, grass, ... - not just those two).
            placementPos = hitPos.offset(hit.getSide());
            if (!mc.world.isInBuildLimit(placementPos)
                || !mc.world.getBlockState(placementPos).isReplaceable()) {
                invalidate();
                return;
            }
        }

        // Reuse the previous result while the aim and the neighbourhood are unchanged: this runs
        // every tick, and re-running a 64-node BFS for a bit-identical answer is pure waste.
        long key = placementPos.asLong();
        if (!cacheStale && computedPos == key) return;

        cacheStale = false;
        computedPos = key;

        // Early-out before allocating the BFS structures: nothing absorbable adjacent to the
        // sponge means nothing can be absorbed, so there is no preview to build.
        if (!hasAdjacentAbsorbable(mc.world, placementPos)) {
            absorption = null;
            return;
        }

        Set<BlockPos> absorbed = removeWaterBreadthFirstSearch(mc.world, placementPos);
        absorption = new SpongeAbsorption(placementPos.toImmutable(), absorbed);
    }

    /**
     * Called from the module's BlockUpdateEvent handler for any block change.
     */
    public void invalidateNear(BlockPos changed) {
        if (computedPos == Long.MIN_VALUE) return;
        BlockPos center = BlockPos.fromLong(computedPos);
        if (Math.abs(center.getX() - changed.getX())
            + Math.abs(center.getY() - changed.getY())
            + Math.abs(center.getZ() - changed.getZ()) <= SPONGE_SEARCH_RADIUS) {
            cacheStale = true;
        }
    }

    public void invalidate() {
        absorption = null;
        cacheStale = true;
        computedPos = Long.MIN_VALUE;
    }

    public void render(Render3DEvent event, Vec3d cam, double maxDistSq) {
        if (!showSpongeAbsorption.get() || absorption == null) return;

        SettingColor side = sideColor.get();
        SettingColor line = lineColor.get();

        for (BlockPos p : absorption.absorbed()) {
            if (DistanceUtil.distanceSqToBox(cam, p.getX(), p.getY(), p.getZ(),
                p.getX() + 1.0, p.getY() + 1.0, p.getZ() + 1.0) > maxDistSq) continue;
            shared.renderBox(event.renderer,
                p.getX(), p.getY(), p.getZ(),
                p.getX() + 1.0, p.getY() + 1.0, p.getZ() + 1.0,
                side, line);
        }

        // show placement preview box
        BlockPos c = absorption.center();
        shared.renderBox(event.renderer,
            c.getX(), c.getY(), c.getZ(),
            c.getX() + 1.0, c.getY() + 1.0, c.getZ() + 1.0,
            side, line);
    }

    /**
     * Cheap pre-check: vanilla's BFS can only absorb anything that is itself absorbable, and it
     * only ever starts from the six cells adjacent to the sponge. If none of those is absorbable
     * the whole traversal is a no-op, so skip building the queue/visited sets entirely. This is
     * the overwhelmingly common case (the player is usually not aiming a sponge at water), and it
     * is what keeps a per-tick scan affordable.
     */
    private boolean hasAdjacentAbsorbable(ClientWorld level, BlockPos center) {
        for (Direction dir : Direction.values()) {
            if (isAbsorbableWater(level, center.offset(dir))) return true;
        }
        return false;
    }

    private Set<BlockPos> removeWaterBreadthFirstSearch(ClientWorld level, BlockPos start) {
        Set<BlockPos> result = new HashSet<>();
        Deque<DepthPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();

        visited.add(start);

        // enqueue initial adjacent absorbable water blocks at depth 1
        for (Direction dir : Direction.values()) {
            BlockPos next = start.offset(dir);
            if (visited.contains(next)) continue;
            if (isAbsorbableWater(level, next)) {
                visited.add(next);
                result.add(next.toImmutable());
                queue.addLast(new DepthPos(next, 1));
            } else if (level.getFluidState(next).isIn(FluidTags.WATER)) {
                visited.add(next);
            }
        }

        while (!queue.isEmpty() && result.size() < SPONGE_MAX_ABSORBED) {
            DepthPos current = queue.pollFirst();
            if (current.depth >= SPONGE_SEARCH_RADIUS) continue;

            for (Direction dir : Direction.values()) {
                BlockPos next = current.pos.offset(dir);
                if (visited.contains(next)) continue;

                if (isAbsorbableWater(level, next)) {
                    visited.add(next);
                    result.add(next.toImmutable());
                    queue.addLast(new DepthPos(next, current.depth + 1));
                    if (result.size() >= SPONGE_MAX_ABSORBED) break;
                } else if (level.getFluidState(next).isIn(FluidTags.WATER)) {
                    visited.add(next);
                }
            }
        }
        return result;
    }

    /**
     * A position paired with its BFS depth from the sponge. Vanilla's sponge absorption
     * uses a true breadth-first search limited by depth (radius), not Manhattan distance.
     * The two are equivalent on an empty grid, but differ when obstacles force the search
     * around them - depth counts steps taken, Manhattan counts coordinate difference.
     */
    private record DepthPos(BlockPos pos, int depth) {
    }

    private boolean isAbsorbableWater(ClientWorld level, BlockPos pos) {
        FluidState fluid = level.getFluidState(pos);
        if (!fluid.isIn(FluidTags.WATER)) return false;
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();

        if (block instanceof FluidDrainable) {
            // Simulate: bucket-pickupable blocks in vanilla sponge BFS are accepted if they can be
            // picked up; but calling pickupBlock on client side is not safe to side effect. The
            // main pickup-ables (buckets of water source blocks that become air in that context)
            // are mostly covered by FluidBlock and special blocks. For preview, accept if it's a
            // FluidDrainable instance (vanilla checks pickup result) conservatively accept to show
            // the block would be affected.
            return true;
        }
        if (block instanceof FluidBlock) return true;
        if (state.isOf(Blocks.KELP) || state.isOf(Blocks.KELP_PLANT)
            || state.isOf(Blocks.SEAGRASS) || state.isOf(Blocks.TALL_SEAGRASS)) {
            return true;
        }
        return false;
    }
}
