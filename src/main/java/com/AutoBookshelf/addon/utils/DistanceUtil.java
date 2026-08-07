package com.AutoBookshelf.addon.utils;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

public class DistanceUtil {

    // 3D squared distance
    public static double distanceSq(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        return dx * dx + dy * dy + dz * dz;
    }

    // 3D distance
    public static double distance(double x1, double y1, double z1, double x2, double y2, double z2) {
        return Math.sqrt(distanceSq(x1, y1, z1, x2, y2, z2));
    }

    // 2D (xz) squared distance
    public static double distanceSqXZ(double x1, double z1, double x2, double z2) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        return dx * dx + dz * dz;
    }

    // 2D (xz) distance
    public static double distanceXZ(double x1, double z1, double x2, double z2) {
        return Math.sqrt(distanceSqXZ(x1, z1, x2, z2));
    }

    // Vec3 overloads
    public static double distanceSq(Vec3 a, Vec3 b) {
        return distanceSq(a.x, a.y, a.z, b.x, b.y, b.z);
    }

    public static double distance(Vec3 a, Vec3 b) {
        return Math.sqrt(distanceSq(a, b));
    }

    // Vec3 + raw doubles overload, to avoid allocating a Vec3 at call sites that
    // only have loose x/y/z (e.g. world-space label positions).
    public static double distanceSq(Vec3 a, double x, double y, double z) {
        return distanceSq(a.x, a.y, a.z, x, y, z);
    }

    public static double distance(Vec3 a, double x, double y, double z) {
        return Math.sqrt(distanceSq(a, x, y, z));
    }

    // Vec2 overloads (Vec2's fields are x/y, not x/z, so this is a flat 2D calc, not xz)
    public static double distanceSq(Vec2 a, Vec2 b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        return dx * dx + dy * dy;
    }

    public static double distance(Vec2 a, Vec2 b) {
        return Math.sqrt(distanceSq(a, b));
    }

    // Entity overloads, with a flag for whether to include Y (3D) or not (xz only)
    public static double distanceSq(Entity a, Entity b, boolean includeY) {
        if (includeY) {
            return distanceSq(a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ());
        } else {
            return distanceSqXZ(a.getX(), a.getZ(), b.getX(), b.getZ());
        }
    }

    public static double distance(Entity a, Entity b, boolean includeY) {
        return Math.sqrt(distanceSq(a, b, includeY));
    }

    // Squared distance from a point to the nearest point on/in an axis-aligned box
    // (0 if the point is inside). Used for camera-distance culling of ESP boxes.
    public static double distanceSqToBox(double px, double py, double pz,
                                         double minX, double minY, double minZ,
                                         double maxX, double maxY, double maxZ) {
        double dx = clamp(px, minX, maxX) - px;
        double dy = clamp(py, minY, maxY) - py;
        double dz = clamp(pz, minZ, maxZ) - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    public static double distanceSqToBox(Vec3 p, double minX, double minY, double minZ,
                                         double maxX, double maxY, double maxZ) {
        return distanceSqToBox(p.x, p.y, p.z, minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
