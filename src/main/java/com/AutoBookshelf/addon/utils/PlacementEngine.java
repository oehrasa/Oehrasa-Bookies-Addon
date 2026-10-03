package com.AutoBookshelf.addon.utils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 *
 * Handles: candidate generation (cardinal + ring search), closest-first
 * sorting, air-place vs. solid-support placement rules, player-hitbox
 * avoidance, and the "reserve room for a second container" variant used by
 * AutoLoader's double-enderchest mode.
 * <p>
 * Stateless aside from the Minecraft reference and a reusable HashSet that
 * mirrors the caller's failedPositions for the duration of one findPlacement call;
 * callers still own their own lists, settings, etc. and pass them in per call.
 */
public class PlacementEngine {
    /**
     * Reusable mirror of the caller's failed-positions list so per-candidate
     * membership checks stay O(1). Refilled at the start of every findPlacement
     * call; never mutated outside one call.
     */
    private final HashSet<BlockPos> failedPositionsSet = new HashSet<>();

    public BlockPos findPlacement(int range, boolean airPlace, boolean preferSolidBlock,
                                  List<BlockPos> failedPositions, boolean requireSecondSlot) {
        var player = mc.player;
        BlockPos pp = player.blockPosition();
        Direction facing = player.getDirection();
        double rangeSq = (double) range * range;
        Vec3 playerPos = player.position();
        AABB playerAABB = player.getBoundingBox();

        failedPositionsSet.clear();
        failedPositionsSet.addAll(failedPositions);

        List<BlockPos> cands = new ArrayList<>();
        if (requireSecondSlot) {
            cands.add(pp.relative(facing));
            cands.add(pp.relative(facing.getClockWise()));
            cands.add(pp.relative(facing.getCounterClockWise()));
            cands.add(pp.relative(facing.getOpposite()));
            for (int d = 1; d <= range; d++) {
                for (int x = -d; x <= d; x++) {
                    for (int z = -d; z <= d; z++) {
                        if (Math.abs(x) == d || Math.abs(z) == d) {
                            cands.add(pp.offset(x, 0, z));
                        }
                    }
                }
            }
        } else {
            cands.add(pp.relative(facing));
            cands.add(pp.relative(facing.getClockWise()));
            cands.add(pp.relative(facing.getCounterClockWise()));
            cands.add(pp.relative(facing.getOpposite()));
            cands.add(pp.above());
            cands.add(pp.below());
            for (int d = 1; d <= range; d++) {
                for (int x = -d; x <= d; x++) {
                    for (int z = -d; z <= d; z++) {
                        if (Math.abs(x) == d || Math.abs(z) == d) {
                            for (int y = -1; y <= 1; y++) cands.add(pp.offset(x, y, z));
                        }
                    }
                }
            }
        }

        cands.removeIf(pos -> !isWithinWorldHeight(pos));
        // Closest candidates first; ties keep their original (front-biased) order.
        cands.sort(Comparator.comparingDouble(pos -> centerDistSq(pos, playerPos)));

        if (requireSecondSlot) {
            // A "valid" first spot that's boxed in on its left
            if (airPlace && preferSolidBlock) {
                for (BlockPos pos : cands) {
                    if (centerDistSq(pos, playerPos) > rangeSq) continue;
                    if (!validSolidPos(pos) || intersectsPlayer(pos, playerAABB) || failedPositionsSet.contains(pos)) continue;
                    if (hasRoomForSecond(pp, pos)) return pos;
                }
            }
            for (BlockPos pos : cands) {
                if (centerDistSq(pos, playerPos) > rangeSq) continue;

                boolean primaryValid = airPlace
                    ? (spaceAbove(pos) && canPlaceAt(pos, playerAABB))
                    : (validSolidPos(pos) && !intersectsPlayer(pos, playerAABB) && !failedPositionsSet.contains(pos));
                if (!primaryValid) continue;
                if (hasRoomForSecond(pp, pos)) return pos;
            }
            // No spot has room for a second container; fall through to the
            // normal single-spot search below so the caller still gets a spot
            // for the first container (their own logic decides what to do
            // about the second one).
        }

        if (airPlace && preferSolidBlock) {
            for (BlockPos pos : cands) {
                if (centerDistSq(pos, playerPos) > rangeSq) continue;
                // this used to return on validSolidPos(pos) alone, skipping the
                // intersectsPlayer/failedPositions checks every other branch applies.
                // That could hand back a spot inside the player's own hitbox, or one
                // already recorded as a previous failure.
                if (intersectsPlayer(pos, playerAABB) || failedPositionsSet.contains(pos)) continue;
                if (validSolidPos(pos)) return pos;
            }
        }
        for (BlockPos pos : cands) {
            if (centerDistSq(pos, playerPos) > rangeSq) continue;
            if (airPlace) {
                if (spaceAbove(pos) && canPlaceAt(pos, playerAABB)) return pos;
            } else {
                if (validSolidPos(pos) && !intersectsPlayer(pos, playerAABB) && !failedPositionsSet.contains(pos)) return pos;
            }
        }

        return null;
    }

    /**
     * Convenience overload for callers that never need the double-slot mode.
     */
    public BlockPos findPlacement(int range, boolean airPlace, boolean preferSolidBlock,
                                  List<BlockPos> failedPositions) {
        return findPlacement(range, airPlace, preferSolidBlock, failedPositions, false);
    }

    public boolean hasRoomForSecond(BlockPos from, BlockPos pos) {
        Direction approach = horizontalDirectionBetween(from, pos);
        if (approach == null) approach = mc.player.getDirection();
        BlockPos second = pos.relative(approach.getCounterClockWise());
        return isReplaceableOrAir(second) && isReplaceableOrAir(second.above());
    }

    public boolean isValidSecondPos(BlockPos firstPos, BlockPos pos, Direction faceDir) {
        Vec3 hitPoint = Vec3.atCenterOf(firstPos).add(faceDir.getStepX() * 0.5, faceDir.getStepY() * 0.5, faceDir.getStepZ() * 0.5);
        return mc.player.position().distanceToSqr(hitPoint) <= 25.0
            && isReplaceableOrAir(pos)
            && isReplaceableOrAir(pos.above());
    }

    public boolean isReplaceableOrAir(BlockPos pos) {
        var state = mc.level.getBlockState(pos);
        return state.isAir() || state.canBeReplaced() || !state.getFluidState().isEmpty();
    }

    public boolean intersectsPlayer(BlockPos pos) {
        AABB playerAABB = mc.player.getBoundingBox();
        AABB blockAABB = new AABB(pos);
        return playerAABB.intersects(blockAABB);
    }

    private boolean intersectsPlayer(BlockPos pos, AABB playerAABB) {
        double x = pos.getX(), y = pos.getY(), z = pos.getZ();
        return playerAABB.minX < x + 1.0 && playerAABB.maxX > x
            && playerAABB.minY < y + 1.0 && playerAABB.maxY > y
            && playerAABB.minZ < z + 1.0 && playerAABB.maxZ > z;
    }

    private boolean canPlaceAt(BlockPos pos, AABB playerAABB) {
        return isReplaceableOrAir(pos) && !intersectsPlayer(pos, playerAABB) && !failedPositionsSet.contains(pos);
    }

    private static double centerDistSq(BlockPos pos, Vec3 playerPos) {
        double dx = (pos.getX() + 0.5) - playerPos.x;
        double dy = (pos.getY() + 0.5) - playerPos.y;
        double dz = (pos.getZ() + 0.5) - playerPos.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private boolean isWithinWorldHeight(BlockPos pos) {
        return !mc.level.isOutsideBuildHeight(pos);
    }

    public boolean canPlaceAt(BlockPos pos, List<BlockPos> failedPositions) {
        return isReplaceableOrAir(pos) && !intersectsPlayer(pos) && !failedPositions.contains(pos);
    }

    public boolean spaceAbove(BlockPos pos) {
        return isReplaceableOrAir(pos.above());
    }

    public boolean validSolidPos(BlockPos pos) {
        return isReplaceableOrAir(pos)
            && isReplaceableOrAir(pos.above())
            && mc.level.getBlockState(pos.below()).canOcclude();
    }

    public Direction horizontalDirectionBetween(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (dx == 0 && dz == 0) return null;
        return Math.abs(dx) >= Math.abs(dz)
            ? (dx > 0 ? Direction.EAST : Direction.WEST)
            : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
    }
}
