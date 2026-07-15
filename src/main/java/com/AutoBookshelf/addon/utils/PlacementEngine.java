package com.AutoBookshelf.addon.utils;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 *
 * Handles: candidate generation (cardinal + ring search), closest-first
 * sorting, air-place vs. solid-support placement rules, player-hitbox
 * avoidance, and the "reserve room for a second container" variant used by
 * AutoLoader's double-enderchest mode.
 * <p>
 * Stateless aside from the MinecraftClient reference, callers own their own
 * failedPositions list, settings, etc. and pass them in per call.
 */
public class PlacementEngine {

    /**
     * Real interaction reach, independent of the placement search radius.
     */
    public static final double INTERACTION_REACH_SQ = 5.0 * 5.0;

    private final Minecraft mc;

    public PlacementEngine(Minecraft mc) {
        this.mc = mc;
    }

    public BlockPos findPlacement(int range, boolean airPlace, boolean preferSolidBlock,
                                  List<BlockPos> failedPositions, boolean requireSecondSlot) {
        BlockPos pp = mc.player.blockPosition();
        Direction facing = mc.player.getDirection();
        double rangeSq = (double) range * range;
        Vec3 playerPos = mc.player.position();

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
        // Closest candidates first; ties keep their original (front-biased) order.
        cands.sort(Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(playerPos)));

        if (requireSecondSlot) {
            // A "valid" first spot that's boxed in on its left
            if (airPlace && preferSolidBlock) {
                for (BlockPos pos : cands) {
                    if (Vec3.atCenterOf(pos).distanceToSqr(playerPos) > rangeSq) continue;
                    if (!validSolidPos(pos) || intersectsPlayer(pos) || failedPositions.contains(pos)) continue;
                    if (hasRoomForSecond(pp, pos)) return pos;
                }
            }
            for (BlockPos pos : cands) {
                if (Vec3.atCenterOf(pos).distanceToSqr(playerPos) > rangeSq) continue;

                boolean primaryValid = airPlace
                    ? (spaceAbove(pos) && canPlaceAt(pos, failedPositions))
                    : (validSolidPos(pos) && !intersectsPlayer(pos) && !failedPositions.contains(pos));
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
                if (Vec3.atCenterOf(pos).distanceToSqr(playerPos) > rangeSq) continue;
                // this used to return on validSolidPos(pos) alone, skipping the
                // intersectsPlayer/failedPositions checks every other branch applies.
                // That could hand back a spot inside the player's own hitbox, or one
                // already recorded as a previous failure.
                if (intersectsPlayer(pos) || failedPositions.contains(pos)) continue;
                if (validSolidPos(pos)) return pos;
            }
        }
        for (BlockPos pos : cands) {
            if (Vec3.atCenterOf(pos).distanceToSqr(playerPos) > rangeSq) continue;
            if (airPlace) {
                if (spaceAbove(pos) && canPlaceAt(pos, failedPositions)) return pos;
            } else {
                if (validSolidPos(pos) && !intersectsPlayer(pos) && !failedPositions.contains(pos)) return pos;
            }
        }

        if (!airPlace) {
            for (BlockPos pos : cands) {
                if (Vec3.atCenterOf(pos).distanceToSqr(playerPos) > rangeSq) continue;
                if (pos.getY() != pp.getY()) continue;
                if (canPlaceAt(pos, failedPositions)) return pos;
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
        Vec3 hitPoint = Vec3.atCenterOf(firstPos).add(Vec3.atLowerCornerOf(faceDir.getUnitVec3i()).scale(0.5));
        return mc.player.position().distanceToSqr(hitPoint) <= INTERACTION_REACH_SQ
            && isReplaceableOrAir(pos)
            && isReplaceableOrAir(pos.above());
    }

    public boolean isReplaceableOrAir(BlockPos pos) {
        var state = mc.level.getBlockState(pos);
        return state.isAir() || state.canBeReplaced() || !state.getFluidState().isEmpty();
    }

    public boolean intersectsPlayer(BlockPos pos) {
        AABB playerBox = mc.player.getBoundingBox();
        AABB blockBox = new AABB(pos);
        return playerBox.intersects(blockBox);
    }

    public boolean canPlaceAt(BlockPos pos, List<BlockPos> failedPositions) {
        return isReplaceableOrAir(pos) && !intersectsPlayer(pos) && !failedPositions.contains(pos);
    }

    public boolean spaceAbove(BlockPos pos) {
        return isReplaceableOrAir(pos.above());
    }

    public boolean validSolidPos(BlockPos pos) {
        return isReplaceableOrAir(pos)
            && mc.level.getBlockState(pos.below()).isRedstoneConductor(mc.level, pos.below());
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
