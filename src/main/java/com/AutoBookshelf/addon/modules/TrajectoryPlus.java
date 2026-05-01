package com.AutoBookshelf.addon.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.*;
import net.minecraft.entity.projectile.thrown.EggEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.entity.projectile.thrown.ExperienceBottleEntity;
import net.minecraft.entity.projectile.thrown.SnowballEntity;
import net.minecraft.entity.projectile.thrown.PotionEntity;
import net.minecraft.entity.projectile.WindChargeEntity;
import net.minecraft.item.*;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

import com.AutoBookshelf.addon.Addon;

public class TrajectoryPlus extends Module {
    public enum Mode {
        Tick,
        Frame
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTrail = settings.createGroup("Trail");
    private final SettingGroup sgBox = settings.createGroup("Box");
    private final SettingGroup sgEntity = settings.createGroup("Entity Highlight");

    private final Setting<Mode> updateMode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Frame mode is smoother; Tick mode is more 'classic'.")
        .defaultValue(Mode.Frame)
        .build()
    );

    // Trail settings
    private final Setting<SettingColor> trailColor = sgTrail.add(new ColorSetting.Builder()
        .name("trail-color")
        .description("Color of the projectile trail lines.")
        .defaultValue(new SettingColor(0, 255, 255, 200))
        .build()
    );

    private final Setting<Integer> trailLength = sgTrail.add(new IntSetting.Builder()
        .name("trail-length")
        .description("How many points to keep in the projectile trail.")
        .defaultValue(20)
        .min(5)
        .max(100)
        .build()
    );

    private final Setting<Boolean> renderTrail = sgTrail.add(new BoolSetting.Builder()
        .name("render-trail")
        .description("Render the projectile trail.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> boxColor = sgBox.add(new ColorSetting.Builder()
        .name("box-color")
        .description("Color of the prediction box when hitting blocks.")
        .defaultValue(new SettingColor(255, 255, 255, 200))
        .build()
    );

    private final Setting<ShapeMode> boxShapeMode = sgBox.add(new EnumSetting.Builder<ShapeMode>()
        .name("box-shape-mode")
        .description("How the prediction box is rendered.")
        .defaultValue(ShapeMode.Lines)
        .build()
    );

    private final Setting<Boolean> renderBox = sgBox.add(new BoolSetting.Builder()
        .name("render-box")
        .description("Render the prediction box for block hits.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> entityHighlightColor = sgEntity.add(new ColorSetting.Builder()
        .name("entity-highlight-color")
        .description("Color when the path hits an entity.")
        .defaultValue(new SettingColor(255, 0, 0, 200))
        .build()
    );

    private final Setting<ShapeMode> entityShapeMode = sgEntity.add(new EnumSetting.Builder<ShapeMode>()
        .name("entity-shape-mode")
        .description("How the entity highlight is rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<Boolean> renderEntityHighlight = sgEntity.add(new BoolSetting.Builder()
        .name("render-entity-highlight")
        .description("Render highlight on entities that will be hit.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> existingProjectileColor = sgGeneral.add(new ColorSetting.Builder()
        .name("existing-projectile-color")
        .description("Color for existing projectile trails and boxes.")
        .defaultValue(new SettingColor(0, 200, 200, 150))
        .build()
    );

    private final Setting<Boolean> renderExistingProjectiles = sgGeneral.add(new BoolSetting.Builder()
        .name("render-existing-projectiles")
        .description("Render prediction for existing projectiles.")
        .defaultValue(true)
        .build()
    );

    // Store projectile trails
    private final ConcurrentHashMap<UUID, List<Vec3d>> projectileTrails = new ConcurrentHashMap<>();

    public TrajectoryPlus() {
        super(Addon.CATEGORY, "Trajectory-Plus", "Smooth projectile prediction and tracking.");
    }

    @Override
    public void onDeactivate() {
        projectileTrails.clear();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;

        // Always predict player's own projectile
        predictPlayerProjectile(event);

        // Always track existing projectiles
        if (renderExistingProjectiles.get()) {
            trackExistingProjectiles(event);
        }
    }

    private void predictPlayerProjectile(Render3DEvent event) {
        ItemStack stack = mc.player.getMainHandStack();
        if (!isValidItem(stack.getItem())) {
            stack = mc.player.getOffHandStack();
            if (!isValidItem(stack.getItem())) return;
        }

        float delta = (updateMode.get() == Mode.Frame) ? event.tickDelta : 1.0f;

        Vec3d pos = getInterpolatedPos(mc.player, delta);
        Vec3d vel = getInitialVelocity(stack.getItem(), delta);

        List<Vec3d> path = new ArrayList<>();
        path.add(pos);

        boolean hitEntity = false;
        Entity hitEntityObj = null;
        Vec3d currentPos = pos;
        Vec3d currentVel = vel;

        for (int i = 0; i < 100; i++) {
            Vec3d nextPos = currentPos.add(currentVel);

            BlockHitResult blockHit = mc.world.raycast(new RaycastContext(
                currentPos, nextPos, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player
            ));

            EntityHitResult entityHit = findEntityHit(currentPos, nextPos);

            if (entityHit != null) {
                path.add(entityHit.getPos());
                hitEntityObj = entityHit.getEntity();
                hitEntity = true;
                break;
            } else if (blockHit.getType() != HitResult.Type.MISS) {
                path.add(blockHit.getPos());
                if (renderBox.get()) {
                    event.renderer.box(blockHit.getBlockPos(), boxColor.get(), boxColor.get(), boxShapeMode.get(), 0);
                }
                break;
            }

            currentPos = nextPos;
            path.add(currentPos);

            double drag = getDrag(stack.getItem());
            double gravity = getGravity(stack.getItem());
            currentVel = currentVel.multiply(drag).subtract(0, gravity, 0);
        }

        // Render trail
        if (renderTrail.get()) {
            SettingColor finalColor = hitEntity ? entityHighlightColor.get() : trailColor.get();
            for (int i = 0; i < path.size() - 1; i++) {
                event.renderer.line(path.get(i).x, path.get(i).y, path.get(i).z,
                                   path.get(i+1).x, path.get(i+1).y, path.get(i+1).z,
                                   finalColor);
            }
        }

        // Render entity highlight
        if (hitEntity && renderEntityHighlight.get() && hitEntityObj != null) {
            event.renderer.box(hitEntityObj.getBoundingBox(), entityHighlightColor.get(), entityHighlightColor.get(), entityShapeMode.get(), 0);
        }
    }

    private void trackExistingProjectiles(Render3DEvent event) {
        for (Entity entity : mc.world.getEntities()) {
            if (isProjectile(entity)) {
                UUID id = entity.getUuid();
                Vec3d currentPos = entity.getPos();

                List<Vec3d> trail = projectileTrails.computeIfAbsent(id, k -> new ArrayList<>());
                trail.add(currentPos);

                while (trail.size() > trailLength.get()) {
                    trail.remove(0);
                }

                predictProjectilePath(event, entity);

                // Render trail for existing projectiles
                if (renderTrail.get()) {
                    for (int i = 0; i < trail.size() - 1; i++) {
                        event.renderer.line(trail.get(i).x, trail.get(i).y, trail.get(i).z,
                                           trail.get(i+1).x, trail.get(i+1).y, trail.get(i+1).z,
                                           existingProjectileColor.get());
                    }
                }

                // Render box for existing projectiles
                if (renderBox.get()) {
                    event.renderer.box(entity.getBoundingBox(), existingProjectileColor.get(), existingProjectileColor.get(), boxShapeMode.get(), 0);
                }
            }
        }

        projectileTrails.keySet().removeIf(id -> {
            Entity e = mc.world.getEntityById(id.hashCode());
            return e == null;
        });
    }

    private void predictProjectilePath(Render3DEvent event, Entity projectile) {
        Vec3d pos = projectile.getPos();
        Vec3d vel = projectile.getVelocity();

        List<Vec3d> path = new ArrayList<>();
        path.add(pos);

        boolean hitEntity = false;
        Entity hitEntityObj = null;
        Vec3d currentPos = pos;
        Vec3d currentVel = vel;

        for (int i = 0; i < 60; i++) {
            Vec3d nextPos = currentPos.add(currentVel);

            BlockHitResult blockHit = mc.world.raycast(new RaycastContext(
                currentPos, nextPos, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player
            ));

            EntityHitResult entityHit = findEntityHit(currentPos, nextPos, projectile);

            if (entityHit != null) {
                path.add(entityHit.getPos());
                hitEntityObj = entityHit.getEntity();
                hitEntity = true;
                break;
            } else if (blockHit.getType() != HitResult.Type.MISS) {
                path.add(blockHit.getPos());
                if (renderBox.get()) {
                    event.renderer.box(blockHit.getBlockPos(), existingProjectileColor.get(), existingProjectileColor.get(), boxShapeMode.get(), 0);
                }
                break;
            }

            currentPos = nextPos;
            path.add(currentPos);

            double drag = 0.99;
            double gravity = getProjectileGravity(projectile);
            currentVel = currentVel.multiply(drag).subtract(0, gravity, 0);
        }

        // Render prediction trail for existing projectile
        if (renderTrail.get()) {
            SettingColor finalColor = hitEntity ? entityHighlightColor.get() : existingProjectileColor.get();
            for (int i = 0; i < path.size() - 1; i++) {
                event.renderer.line(path.get(i).x, path.get(i).y, path.get(i).z,
                                   path.get(i+1).x, path.get(i+1).y, path.get(i+1).z,
                                   finalColor);
            }
        }

        // Render entity highlight for existing projectile prediction
        if (hitEntity && renderEntityHighlight.get() && hitEntityObj != null) {
            event.renderer.box(hitEntityObj.getBoundingBox(), entityHighlightColor.get(), entityHighlightColor.get(), entityShapeMode.get(), 0);
        }
    }

    private boolean isProjectile(Entity entity) {
        return entity instanceof ArrowEntity ||
               entity instanceof SpectralArrowEntity ||
               entity instanceof TridentEntity ||
               entity instanceof FireballEntity ||
               entity instanceof SmallFireballEntity ||
               entity instanceof DragonFireballEntity ||
               entity instanceof WitherSkullEntity ||
               entity instanceof ShulkerBulletEntity ||
               entity instanceof SnowballEntity ||
               entity instanceof EggEntity ||
               entity instanceof EnderPearlEntity ||
               entity instanceof ExperienceBottleEntity ||
               entity instanceof PotionEntity ||
               entity instanceof WindChargeEntity;
    }

    private double getProjectileGravity(Entity projectile) {
        if (projectile instanceof ArrowEntity) return 0.05;
        if (projectile instanceof SpectralArrowEntity) return 0.05;
        if (projectile instanceof TridentEntity) return 0.05;
        if (projectile instanceof SnowballEntity) return 0.03;
        if (projectile instanceof EggEntity) return 0.03;
        if (projectile instanceof EnderPearlEntity) return 0.03;
        if (projectile instanceof ExperienceBottleEntity) return 0.03;
        if (projectile instanceof PotionEntity) return 0.05;
        if (projectile instanceof FireballEntity) return 0.0;
        if (projectile instanceof SmallFireballEntity) return 0.0;
        if (projectile instanceof DragonFireballEntity) return 0.0;
        if (projectile instanceof WitherSkullEntity) return 0.0;
        if (projectile instanceof ShulkerBulletEntity) return 0.0;
        if (projectile instanceof WindChargeEntity) return 0.0;
        return 0.03;
    }

    private EntityHitResult findEntityHit(Vec3d start, Vec3d end) {
        return findEntityHit(start, end, null);
    }

    private EntityHitResult findEntityHit(Vec3d start, Vec3d end, Entity ignoreEntity) {
        for (Entity entity : mc.world.getEntities()) {
            if (entity == mc.player || entity == ignoreEntity) continue;
            if (!(entity instanceof LivingEntity)) continue;

            Box box = entity.getBoundingBox().expand(0.3);
            if (box.raycast(start, end).isPresent()) {
                return new EntityHitResult(entity, box.raycast(start, end).get());
            }
        }
        return null;
    }

    private boolean isValidItem(Item item) {
        return item instanceof BowItem ||
               item instanceof CrossbowItem ||
               item instanceof TridentItem ||
               item instanceof EnderPearlItem ||
               item instanceof EggItem ||
               item instanceof SnowballItem ||
               item instanceof ExperienceBottleItem ||
               item instanceof WindChargeItem ||
               item instanceof PotionItem;
    }

    private Vec3d getInitialVelocity(Item item, float delta) {
        Vec3d look = mc.player.getRotationVec(delta);
        double mult;
        if (item instanceof BowItem || item instanceof CrossbowItem) {
            int useTime = mc.player.getItemUseTime();
            float pullTime = Math.min(useTime, 20) / 20.0f;
            float power = (pullTime * pullTime + pullTime * 2.0f) / 3.0f;
            if (power > 1.0f) power = 1.0f;
            mult = 3.0 * power;
        } else if (item instanceof TridentItem) {
            mult = 2.5;
        } else if (item instanceof WindChargeItem) {
            mult = 1.5;
        } else {
            mult = 1.5;
        }
        return look.multiply(mult);
    }

    private double getGravity(Item item) {
        if (item instanceof BowItem || item instanceof CrossbowItem) return 0.05;
        if (item instanceof TridentItem) return 0.05;
        if (item instanceof WindChargeItem) return 0.0;
        if (item instanceof PotionItem) return 0.05;
        return 0.03;
    }

    private double getDrag(Item item) {
        return 0.99;
    }

    private Vec3d getInterpolatedPos(Entity entity, float delta) {
        double x = entity.prevX + (entity.getX() - entity.prevX) * delta;
        double y = (entity.prevY + (entity.getY() - entity.prevY) * delta) + entity.getEyeHeight(entity.getPose());
        double z = entity.prevZ + (entity.getZ() - entity.prevZ) * delta;
        return new Vec3d(x, y, z);
    }
}
