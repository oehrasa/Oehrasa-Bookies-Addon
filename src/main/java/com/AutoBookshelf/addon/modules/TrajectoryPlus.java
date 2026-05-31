package com.AutoBookshelf.addon.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.arrow.SpectralArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.hurtingprojectile.DragonFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownExperienceBottle;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.EggItem;
import net.minecraft.world.item.EnderpearlItem;
import net.minecraft.world.item.ExperienceBottleItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.SnowballItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.WindChargeItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
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
    private final ConcurrentHashMap<UUID, List<Vec3>> projectileTrails = new ConcurrentHashMap<>();

    public TrajectoryPlus() {
        super(Addon.CATEGORY2, "Trajectory-Plus", "Smooth projectile prediction and tracking.");
    }

    @Override
    public void onDeactivate() {
        projectileTrails.clear();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.level == null) return;

        // Always predict player's own projectile
        predictPlayerProjectile(event);

        // Always track existing projectiles
        if (renderExistingProjectiles.get()) {
            trackExistingProjectiles(event);
        }
    }

    private void predictPlayerProjectile(Render3DEvent event) {
        ItemStack stack = mc.player.getMainHandItem();
        if (!isValidItem(stack.getItem())) {
            stack = mc.player.getOffhandItem();
            if (!isValidItem(stack.getItem())) return;
        }

        float delta = (updateMode.get() == Mode.Frame) ? event.tickDelta : 1.0f;

        Vec3 pos = getInterpolatedPos(mc.player, delta);
        Vec3 vel = getInitialVelocity(stack.getItem(), delta);

        List<Vec3> path = new ArrayList<>();
        path.add(pos);

        boolean hitEntity = false;
        Entity hitEntityObj = null;
        Vec3 currentPos = pos;
        Vec3 currentVel = vel;

        for (int i = 0; i < 100; i++) {
            Vec3 nextPos = currentPos.add(currentVel);

            BlockHitResult blockHit = mc.level.clip(new ClipContext(
                currentPos, nextPos, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player
            ));

            EntityHitResult entityHit = findEntityHit(currentPos, nextPos);

            if (entityHit != null) {
                path.add(entityHit.getLocation());
                hitEntityObj = entityHit.getEntity();
                hitEntity = true;
                break;
            } else if (blockHit.getType() != HitResult.Type.MISS) {
                path.add(blockHit.getLocation());
                if (renderBox.get()) {
                    event.renderer.box(blockHit.getBlockPos(), boxColor.get(), boxColor.get(), boxShapeMode.get(), 0);
                }
                break;
            }

            currentPos = nextPos;
            path.add(currentPos);

            double drag = getDrag(stack.getItem());
            double gravity = getGravity(stack.getItem());
            currentVel = currentVel.scale(drag).subtract(0, gravity, 0);
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
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (isProjectile(entity)) {
                UUID id = entity.getUUID();
                Vec3 currentPos = entity.position();

                List<Vec3> trail = projectileTrails.computeIfAbsent(id, k -> new ArrayList<>());
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
            Entity e = mc.level.getEntity(id.hashCode());
            return e == null;
        });
    }

    private void predictProjectilePath(Render3DEvent event, Entity projectile) {
        Vec3 pos = projectile.getPosition(event.tickDelta);
        Vec3 vel = projectile.getDeltaMovement();

        List<Vec3> path = new ArrayList<>();
        path.add(pos);

        boolean hitEntity = false;
        Entity hitEntityObj = null;
        Vec3 currentPos = pos;
        Vec3 currentVel = vel;

        for (int i = 0; i < 60; i++) {
            Vec3 nextPos = currentPos.add(currentVel);

            BlockHitResult blockHit = mc.level.clip(new ClipContext(
                currentPos, nextPos, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player
            ));

            EntityHitResult entityHit = findEntityHit(currentPos, nextPos, projectile);

            if (entityHit != null) {
                path.add(entityHit.getLocation());
                hitEntityObj = entityHit.getEntity();
                hitEntity = true;
                break;
            } else if (blockHit.getType() != HitResult.Type.MISS) {
                path.add(blockHit.getLocation());
                if (renderBox.get()) {
                    event.renderer.box(blockHit.getBlockPos(), existingProjectileColor.get(), existingProjectileColor.get(), boxShapeMode.get(), 0);
                }
                break;
            }

            currentPos = nextPos;
            path.add(currentPos);

            double drag = 0.99;
            double gravity = getProjectileGravity(projectile);
            currentVel = currentVel.scale(drag).subtract(0, gravity, 0);
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
        return entity instanceof Arrow ||
               entity instanceof SpectralArrow ||
               entity instanceof ThrownTrident ||
               entity instanceof LargeFireball ||
               entity instanceof SmallFireball ||
               entity instanceof DragonFireball ||
               entity instanceof WitherSkull ||
               entity instanceof ShulkerBullet ||
               entity instanceof Snowball ||
               entity instanceof ThrownEgg ||
               entity instanceof ThrownEnderpearl ||
               entity instanceof ThrownExperienceBottle ||
               entity instanceof AbstractThrownPotion ||
               entity instanceof WindCharge;
    }

    private double getProjectileGravity(Entity projectile) {
        if (projectile instanceof Arrow) return 0.05;
        if (projectile instanceof SpectralArrow) return 0.05;
        if (projectile instanceof ThrownTrident) return 0.05;
        if (projectile instanceof Snowball) return 0.03;
        if (projectile instanceof ThrownEgg) return 0.03;
        if (projectile instanceof ThrownEnderpearl) return 0.03;
        if (projectile instanceof ThrownExperienceBottle) return 0.03;
        if (projectile instanceof AbstractThrownPotion) return 0.05;
        if (projectile instanceof LargeFireball) return 0.0;
        if (projectile instanceof SmallFireball) return 0.0;
        if (projectile instanceof DragonFireball) return 0.0;
        if (projectile instanceof WitherSkull) return 0.0;
        if (projectile instanceof ShulkerBullet) return 0.0;
        if (projectile instanceof WindCharge) return 0.0;
        return 0.03;
    }

    private EntityHitResult findEntityHit(Vec3 start, Vec3 end) {
        return findEntityHit(start, end, null);
    }

    private EntityHitResult findEntityHit(Vec3 start, Vec3 end, Entity ignoreEntity) {
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player || entity == ignoreEntity) continue;
            if (!(entity instanceof LivingEntity)) continue;

            AABB box = entity.getBoundingBox().inflate(0.3);
            if (box.clip(start, end).isPresent()) {
                return new EntityHitResult(entity, box.clip(start, end).get());
            }
        }
        return null;
    }

    private boolean isValidItem(Item item) {
        return item instanceof BowItem ||
               item instanceof CrossbowItem ||
               item instanceof TridentItem ||
               item instanceof EnderpearlItem ||
               item instanceof EggItem ||
               item instanceof SnowballItem ||
               item instanceof ExperienceBottleItem ||
               item instanceof WindChargeItem ||
               item instanceof PotionItem;
    }

    private Vec3 getInitialVelocity(Item item, float delta) {
        Vec3 look = mc.player.getViewVector(delta);
        double mult;
        if (item instanceof BowItem || item instanceof CrossbowItem) {
            int useTime = mc.player.getTicksUsingItem();
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
        return look.scale(mult);
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

    private Vec3 getInterpolatedPos(Entity entity, float delta) {
        Vec3 pos = entity.getPosition(delta);           // interpolated foot position
        return new Vec3(pos.x, pos.y + entity.getEyeHeight(entity.getPose()), pos.z);  // eye position
    }
}
