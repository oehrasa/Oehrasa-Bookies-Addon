package com.AutoBookshelf.addon.utils;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.arrow.SpectralArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.hurtingprojectile.DragonFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import net.minecraft.world.entity.projectile.throwableitemprojectile.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Single source of truth for ballistic prediction constants and integration.
 * <ul>
 *   <li>Arrows / spectral arrows / tridents kept the OLD order:
 *       {@code pos += vel; vel = vel*drag - (0,gravity,0)} (position moves with
 *       the current velocity, drag/gravity apply to velocity for the next tick).</li>
 *   <li>Everything else we track (thrown items, potions, experience bottles,
 *       fireballs/wither skulls/dragon fireballs, wind charges) uses the NEW
 *       order: {@code vel = vel - (0,gravity,0); vel = vel*drag; pos += vel}
 *       (acceleration and drag apply first, position moves with the updated
 *       velocity, same tick).</li>
 * </ul>
 */
public final class ProjectilePhysics {

    private ProjectilePhysics() {
    }

    /**
     * Which tick-update order a given projectile family uses.
     */
    public enum TickOrder {
        /**
         * pos += vel; vel = vel*drag - gravity  (pre-1.21.2 order, arrows/tridents only)
         */
        LEGACY_POS_DRAG_ACCEL,
        /**
         * vel = vel - gravity; vel *= drag; pos += vel  (1.21.2+ order, everything else)
         */
        ACCEL_DRAG_POS
    }

    public static TickOrder getTickOrder(Entity entity) {
        if (entity instanceof Arrow || entity instanceof SpectralArrow || entity instanceof ThrownTrident)
            return TickOrder.LEGACY_POS_DRAG_ACCEL;
        return TickOrder.ACCEL_DRAG_POS;
    }

    public static double getGravity(Entity entity) {
        if (entity instanceof Arrow) return 0.05;
        if (entity instanceof SpectralArrow) return 0.05;
        if (entity instanceof ThrownTrident) return 0.05;
        if (entity instanceof Snowball) return 0.03;
        if (entity instanceof ThrownEgg) return 0.03;
        if (entity instanceof ThrownEnderpearl) return 0.03;
        if (entity instanceof ThrownExperienceBottle) return 0.07; // was 0.03 — confirmed distinct from other thrown items
        if (entity instanceof AbstractThrownPotion) return 0.05;
        if (entity instanceof LargeFireball) return 0.0;
        if (entity instanceof SmallFireball) return 0.0;
        if (entity instanceof DragonFireball) return 0.0;
        if (entity instanceof WitherSkull) return 0.0;
        if (entity instanceof ShulkerBullet) return 0.0;
        if (entity instanceof WindCharge) return 0.0;
        return 0.03;
    }

    public static double getDrag(Entity entity) {
        // Wind charges have no drag at all — they coast at constant speed.
        if (entity instanceof WindCharge) return 1.0;
        // Fireballs/wither skulls/dragon fireballs decay faster than arrows/thrown items.
        if (entity instanceof LargeFireball || entity instanceof SmallFireball
            || entity instanceof DragonFireball || entity instanceof WitherSkull) return 0.95;
        // Arrows, tridents, and all thrown items (snowball/egg/pearl/potion/xp bottle) share 0.99.
        return 0.99;
    }

    public record Result(List<Vec3> path, boolean truncatedByBlockHit) {
    }

    /**
     * Ballistic integration for threat prediction (ArenaM).
     * Only checks block collisions - no entity-hit truncation, since a threat
     * path shouldn't stop just because it grazes some other entity first.
     *
     * @param source   the actual world entity, used to look up gravity, drag,
     *                 and tick order for its specific type
     * @param startPos simulation start position (usually {@code source.getPos()})
     * @param startVel simulation start velocity (usually {@code source.getVelocity()})
     * @param maxTicks how many ticks to simulate forward
     */
    public static Result simulate(Entity source, Vec3 startPos, Vec3 startVel, int maxTicks) {
        double gravity = getGravity(source);
        double drag = getDrag(source);
        boolean legacyOrder = getTickOrder(source) == TickOrder.LEGACY_POS_DRAG_ACCEL;

        List<Vec3> path = new ArrayList<>(maxTicks + 1);
        Vec3 currentPos = startPos;
        Vec3 currentVel = startVel;
        path.add(currentPos);

        for (int i = 0; i < maxTicks; i++) {
            Vec3 nextPos;
            Vec3 nextVel;

            if (legacyOrder) {
                // pre-1.21.2: position moves with current velocity, then drag/gravity apply for next tick
                nextPos = currentPos.add(currentVel);
                nextVel = currentVel.scale(drag).subtract(0, gravity, 0);
            } else {
                // 1.21.2+: drag/gravity apply first, position moves with the updated velocity
                nextVel = currentVel.subtract(0, gravity, 0).scale(drag);
                nextPos = currentPos.add(nextVel);
            }

            BlockHitResult blockHit = mc.level.clip(new ClipContext(
                currentPos, nextPos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player
            ));

            if (blockHit.getType() != HitResult.Type.MISS) {
                path.add(blockHit.getLocation());
                return new Result(path, true);
            }

            currentPos = nextPos;
            currentVel = nextVel;
            path.add(currentPos);
        }

        return new Result(path, false);
    }
}
