package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.utils.ProjectilePhysics;
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
import net.minecraft.world.entity.projectile.throwableitemprojectile.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    private final Setting<Boolean> renderTrailAhead = sgTrail.add(new BoolSetting.Builder()
        .name("render-trail-ahead")
        .description("Render the predicted trail ahead of a projectile as predicted path.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> renderTrailBehind = sgTrail.add(new BoolSetting.Builder()
        .name("render-trail-behind")
        .description("Render trail behind an existing projectile.")
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

    private final Setting<Boolean> ignoreLanded = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-landed")
        .description("Ignore existing projectiles that are already stuck/landed (near-zero velocity).")
        .defaultValue(true)
        .visible(renderExistingProjectiles::get)
        .build()
    );

    // Store projectile trails
    private final ConcurrentHashMap<UUID, List<Vec3>> projectileTrails = new ConcurrentHashMap<>();

    // Just reusing threshold from ArenaM
    private static final double MIN_THREAT_SPEED_SQ = 0.0025;

    // Shared simulation result record
    private record SimResult(List<Vec3> path, Entity hitEntity) {
    }

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

        SimResult result = simulatePath(pos, vel, null, getDrag(stack.getItem()), getGravity(stack.getItem()), 100, event, boxColor.get());

        // Only "ahead" applies here
        if (renderTrailAhead.get()) {
            SettingColor color = result.hitEntity() != null ? entityHighlightColor.get() : trailColor.get();
            renderPath(event, result.path(), color);
        }

        if (result.hitEntity() != null && renderEntityHighlight.get()) {
            event.renderer.box(result.hitEntity().getBoundingBox(), entityHighlightColor.get(), entityHighlightColor.get(), entityShapeMode.get(), 0);
        }
    }

    private void trackExistingProjectiles(Render3DEvent event) {
        int maxTrail = trailLength.get();

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!isProjectile(entity)) continue;
            // Skip stuck/landed projectiles entirely
            if (ignoreLanded.get() && entity.getDeltaMovement().lengthSqr() < MIN_THREAT_SPEED_SQ) continue;

            UUID id = entity.getUUID();
            Vec3 currentPos = entity.position();

            List<Vec3> trail = projectileTrails.computeIfAbsent(id, k -> new ArrayList<>());
            trail.add(currentPos);

            if (trail.size() > maxTrail) {
                trail.subList(0, trail.size() - maxTrail).clear();
            }

            predictProjectilePath(event, entity);

            // "Behind" the breadcrumb trail.
            if (renderTrailBehind.get()) {
                renderPath(event, trail, existingProjectileColor.get());
            }

            if (renderBox.get()) {
                event.renderer.box(entity.getBoundingBox(), existingProjectileColor.get(), existingProjectileColor.get(), boxShapeMode.get(), 0);
            }
        }

        projectileTrails.keySet().removeIf(id -> {
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e.getUUID().equals(id)) return false;
            }
            return true;
        });
    }

    private void predictProjectilePath(Render3DEvent event, Entity projectile) {
        SimResult result = simulatePath(
            projectile.position(), projectile.getDeltaMovement(), projectile,
            ProjectilePhysics.getDrag(projectile), ProjectilePhysics.getGravity(projectile), 60,
            event, existingProjectileColor.get()
        );

        // "Ahead" which is  the predicted future path, rendered in trackExistingProjectiles().
        if (renderTrailAhead.get()) {
            SettingColor color = result.hitEntity() != null ? entityHighlightColor.get() : existingProjectileColor.get();
            renderPath(event, result.path(), color);
        }

        if (result.hitEntity() != null && renderEntityHighlight.get()) {
            event.renderer.box(result.hitEntity().getBoundingBox(), entityHighlightColor.get(), entityHighlightColor.get(), entityShapeMode.get(), 0);
        }
    }

    // Shared simulation logic extracted from both predict methods
    private SimResult simulatePath(Vec3 startPos, Vec3 startVel, Entity ignoreEntity, double drag, double gravity, int maxTicks, Render3DEvent event, SettingColor boxCol) {
        List<Vec3> path = new ArrayList<>();
        Vec3 currentPos = startPos;
        Vec3 currentVel = startVel;
        path.add(currentPos);

        for (int i = 0; i < maxTicks; i++) {
            Vec3 nextPos = currentPos.add(currentVel);

            BlockHitResult blockHit = mc.level.clip(new ClipContext(
                currentPos, nextPos, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player
            ));

            EntityHitResult entityHit = findEntityHit(currentPos, nextPos, ignoreEntity);

            if (entityHit != null) {
                path.add(entityHit.getLocation());
                return new SimResult(path, entityHit.getEntity());
            } else if (blockHit.getType() != HitResult.Type.MISS) {
                path.add(blockHit.getLocation());
                if (renderBox.get() && event != null) {
                    event.renderer.box(blockHit.getBlockPos(), boxCol, boxCol, boxShapeMode.get(), 0);
                }
                break;
            }

            currentPos = nextPos;
            path.add(currentPos);
            currentVel = currentVel.scale(drag).subtract(0, gravity, 0);
        }

        return new SimResult(path, null);
    }

    // Shared render helper to avoid duplicating the line loop
    private void renderPath(Render3DEvent event, List<Vec3> path, SettingColor color) {
        for (int i = 0; i < path.size() - 1; i++) {
            Vec3 a = path.get(i), b = path.get(i + 1);
            event.renderer.line(a.x, a.y, a.z, b.x, b.y, b.z, color);
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

    private EntityHitResult findEntityHit(Vec3 start, Vec3 end, Entity ignoreEntity) {
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player || entity == ignoreEntity) continue;
            if (!(entity instanceof LivingEntity)) continue;

            AABB box = entity.getBoundingBox().inflate(0.3);
            var hit = box.clip(start, end);
            if (hit.isPresent()) {
                return new EntityHitResult(entity, hit.get());
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
        double x = entity.xOld + (entity.getX() - entity.xOld) * delta;
        double y = (entity.yOld + (entity.getY() - entity.yOld) * delta) + entity.getEyeHeight(entity.getPose());
        double z = entity.zOld + (entity.getZ() - entity.zOld) * delta;
        return new Vec3(x, y, z);
    }
}
