/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.AttackAircraft;

import meteordevelopment.meteorclient.systems.friends.Friends;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class BoatShotGuard {
    /**
     * How far the boat keeps from a place an end crystal could be put.
     */
    private static final double CRYSTAL_CLEARANCE = 13;
    /**
     * How far from an enemy's eyes a usable crystal base can be.
     */
    private static final double BASE_REACH = 7;

    /**
     * A place an end crystal could be, and how far the boat has to keep from it.
     */
    public record CrystalSource(Vec3 pos, double radius) {
    }

    private final List<CrystalSource> crystals = new ArrayList<>();
    private final Set<BlockPos> reservations = new HashSet<>();

    public void reset() {
        crystals.clear();
        reservations.clear();
    }

    public void reserve(BlockPos pos) {
        reservations.add(pos.immutable());
    }

    public void release(BlockPos pos) {
        reservations.remove(pos);
    }

    public void refresh(ClientLevel world, Player self, AbstractBoat boat, Entity target) {
        refresh(world, self, boat, target, true);
    }

    public void refresh(ClientLevel world, Player self, AbstractBoat boat, Entity target, boolean protectCrystals) {
        crystals.clear();
        if (!protectCrystals) return;
        AABB scan = boat.getBoundingBox().inflate(45);
        if (target != null) scan = scan.minmax(target.getBoundingBox().inflate(14));
        for (Entity entity : world.getEntities(self, scan, e -> e instanceof EndCrystal && !e.isRemoved()))
            crystals.add(new CrystalSource(entity.position(), CRYSTAL_CLEARANCE));

        int scanned = 0;
        for (Player enemy : world.players()) {
            if (enemy == self || enemy.isSpectator() || !enemy.isAlive() || !Friends.get().shouldAttack(enemy)
                || enemy.distanceToSqr(boat) > 45 * 45 && (target == null || enemy.distanceToSqr(target) > 14 * 14)) continue;
            if (++scanned > 12) {
                // The base scan is skipped for the enemies past the cap, but the bases it
                // would have found are all within BASE_REACH of the enemy's eyes. A
                // clearance of CRYSTAL_CLEARANCE + BASE_REACH around the eyes therefore
                // protects exactly the same area as the scan it replaces.
                crystals.add(new CrystalSource(enemy.getEyePosition(), CRYSTAL_CLEARANCE + BASE_REACH));
                continue;
            }
            BlockPos center = enemy.blockPosition();
            for (BlockPos base : BlockPos.betweenClosed(center.offset(-6, -3, -6), center.offset(6, 4, 6))) {
                if (!world.getChunkSource().hasChunk(base.getX() >> 4, base.getZ() >> 4)) continue;
                var state = world.getBlockState(base);
                if (!(state.is(Blocks.OBSIDIAN) || state.is(Blocks.BEDROCK)) || !world.getBlockState(base.above()).isAir()) continue;
                Vec3 source = Vec3.atCenterOf(base).add(0, .5, 0);

                if (enemy.getEyePosition().distanceToSqr(source) <= BASE_REACH * BASE_REACH)
                    crystals.add(new CrystalSource(source, CRYSTAL_CLEARANCE));
            }
        }
    }

    public boolean clear(ClientLevel world, AbstractBoat boat, Vec3 step) {
        return clear(world, boat, step, true);
    }

    public boolean clear(ClientLevel world, AbstractBoat boat, Vec3 step, boolean protectCrystals) {
        return clearAt(world, boat, Vec3.ZERO, step, protectCrystals);
    }

    public boolean clearAt(ClientLevel world, AbstractBoat boat, Vec3 offset, Vec3 step, boolean protectCrystals) {
        if (!Double.isFinite(step.lengthSqr()) || !Double.isFinite(offset.lengthSqr())) return false;
        for (Entity body : boat.getSelfAndPassengers().toList()) {
            AABB current = body.getBoundingBox().move(offset).inflate(.35), path = current.expandTowards(step);
            if (protectCrystals)
                for (CrystalSource crystal : crystals) if (!awayOrOutside(current, step, crystal.pos(), crystal.radius())) return false;
            for (BlockPos pos : reservations) if (!webClear(current, step, new AABB(pos))) return false;
            for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(path.minX, path.minY, path.minZ), BlockPos.containing(path.maxX, path.maxY, path.maxZ))) {
                var state = world.getBlockState(pos);
                if (state.is(Blocks.COBWEB) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                    || state.is(Blocks.CACTUS) || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POWDER_SNOW)) {
                    if (!webClear(current, step, new AABB(pos))) return false;
                }
            }
        }
        return true;
    }

    public static boolean webClear(AABB body, Vec3 step, AABB web) {
        if (!body.expandTowards(step).intersects(web)) return true;

        // The dot product only says which way is out of the web. A body that sits on the
        // web's centre has a zero dot product for a horizontal step, and that must not veto
        // a step that does leave the web - the two tests above already gate the move.
        return body.intersects(web) && !body.move(step).intersects(web)
            && body.getCenter().subtract(web.getCenter()).dot(step) >= 0;
    }

    public static boolean awayOrOutside(AABB body, Vec3 step, Vec3 source, double radius) {
        if (distanceSquared(body.expandTowards(step), source) > radius * radius) return true;
        double before = distanceSquared(body, source), after = distanceSquared(body.move(step), source);
        Vec3 nearest = new Vec3(Math.clamp(source.x, body.minX, body.maxX), Math.clamp(source.y, body.minY, body.maxY), Math.clamp(source.z, body.minZ, body.maxZ));
        return before <= radius * radius && after > before + .001 && nearest.subtract(source).dot(step) >= 0;
    }

    public static double distanceSquared(AABB box, Vec3 p) {
        double x = Math.max(Math.max(box.minX - p.x, p.x - box.maxX), 0), y = Math.max(Math.max(box.minY - p.y, p.y - box.maxY), 0), z = Math.max(Math.max(box.minZ - p.z, p.z - box.maxZ), 0);
        return x * x + y * y + z * z;
    }
}
