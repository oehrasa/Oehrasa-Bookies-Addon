package com.AutoBookshelf.addon.utils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.*;
import net.minecraft.entity.projectile.thrown.*;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.List;

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
        if (entity instanceof ArrowEntity || entity instanceof SpectralArrowEntity || entity instanceof TridentEntity)
            return TickOrder.LEGACY_POS_DRAG_ACCEL;
        return TickOrder.ACCEL_DRAG_POS;
    }

    public static double getGravity(Entity entity) {
        if (entity instanceof ArrowEntity) return 0.05;
        if (entity instanceof SpectralArrowEntity) return 0.05;
        if (entity instanceof TridentEntity) return 0.05;
        if (entity instanceof SnowballEntity) return 0.03;
        if (entity instanceof EggEntity) return 0.03;
        if (entity instanceof EnderPearlEntity) return 0.03;
        if (entity instanceof ExperienceBottleEntity) return 0.07; // was 0.03 — confirmed distinct from other thrown items
        if (entity instanceof PotionEntity) return 0.05;
        if (entity instanceof FireballEntity) return 0.0;
        if (entity instanceof SmallFireballEntity) return 0.0;
        if (entity instanceof DragonFireballEntity) return 0.0;
        if (entity instanceof WitherSkullEntity) return 0.0;
        if (entity instanceof ShulkerBulletEntity) return 0.0;
        if (entity instanceof WindChargeEntity) return 0.0;
        return 0.03;
    }

    public static double getDrag(Entity entity) {
        // Wind charges have no drag at all — they coast at constant speed.
        if (entity instanceof WindChargeEntity) return 1.0;
        // Fireballs/wither skulls/dragon fireballs decay faster than arrows/thrown items.
        if (entity instanceof FireballEntity || entity instanceof SmallFireballEntity
            || entity instanceof DragonFireballEntity || entity instanceof WitherSkullEntity) return 0.95;
        // Arrows, tridents, and all thrown items (snowball/egg/pearl/potion/xp bottle) share 0.99.
        return 0.99;
    }

    public record Result(List<Vec3d> path, boolean truncatedByBlockHit) {
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
    public static Result simulate(Entity source, Vec3d startPos, Vec3d startVel, int maxTicks) {
        MinecraftClient mc = MinecraftClient.getInstance();
        double gravity = getGravity(source);
        double drag = getDrag(source);
        boolean legacyOrder = getTickOrder(source) == TickOrder.LEGACY_POS_DRAG_ACCEL;

        List<Vec3d> path = new ArrayList<>(maxTicks + 1);
        Vec3d currentPos = startPos;
        Vec3d currentVel = startVel;
        path.add(currentPos);

        for (int i = 0; i < maxTicks; i++) {
            Vec3d nextPos;
            Vec3d nextVel;

            if (legacyOrder) {
                // pre-1.21.2: position moves with current velocity, then drag/gravity apply for next tick
                nextPos = currentPos.add(currentVel);
                nextVel = currentVel.multiply(drag).subtract(0, gravity, 0);
            } else {
                // 1.21.2+: drag/gravity apply first, position moves with the updated velocity
                nextVel = currentVel.subtract(0, gravity, 0).multiply(drag);
                nextPos = currentPos.add(nextVel);
            }

            BlockHitResult blockHit = mc.world.raycast(new RaycastContext(
                currentPos, nextPos, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player
            ));

            if (blockHit.getType() != HitResult.Type.MISS) {
                path.add(blockHit.getPos());
                return new Result(path, true);
            }

            currentPos = nextPos;
            currentVel = nextVel;
            path.add(currentPos);
        }

        return new Result(path, false);
    }
}
