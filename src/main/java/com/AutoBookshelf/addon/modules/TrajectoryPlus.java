package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.*;
import net.minecraft.entity.projectile.thrown.*;
import net.minecraft.item.*;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

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

    // Shared simulation result record
    private record SimResult(List<Vec3d> path, Entity hitEntity) {
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

        SimResult result = simulatePath(pos, vel, null, getDrag(stack.getItem()), getGravity(stack.getItem()), 100, event, boxColor.get());

        if (renderTrail.get()) {
            SettingColor color = result.hitEntity() != null ? entityHighlightColor.get() : trailColor.get();
            renderPath(event, result.path(), color);
        }

        if (result.hitEntity() != null && renderEntityHighlight.get()) {
            event.renderer.box(result.hitEntity().getBoundingBox(), entityHighlightColor.get(), entityHighlightColor.get(), entityShapeMode.get(), 0);
        }
    }

    private void trackExistingProjectiles(Render3DEvent event) {
        int maxTrail = trailLength.get();

        for (Entity entity : mc.world.getEntities()) {
            if (!isProjectile(entity)) continue;

            UUID id = entity.getUuid();
            Vec3d currentPos = entity.getPos();

            List<Vec3d> trail = projectileTrails.computeIfAbsent(id, k -> new ArrayList<>());
            trail.add(currentPos);

            if (trail.size() > maxTrail) {
                trail.subList(0, trail.size() - maxTrail).clear();
            }

            predictProjectilePath(event, entity);

            if (renderTrail.get()) {
                renderPath(event, trail, existingProjectileColor.get());
            }

            if (renderBox.get()) {
                event.renderer.box(entity.getBoundingBox(), existingProjectileColor.get(), existingProjectileColor.get(), boxShapeMode.get(), 0);
            }
        }

        projectileTrails.keySet().removeIf(id -> {
            for (Entity e : mc.world.getEntities()) {
                if (e.getUuid().equals(id)) return false;
            }
            return true;
        });
    }

    private void predictProjectilePath(Render3DEvent event, Entity projectile) {
        SimResult result = simulatePath(
            projectile.getPos(), projectile.getVelocity(), projectile,
            0.99, getProjectileGravity(projectile), 60,
            event, existingProjectileColor.get()
        );

        if (renderTrail.get()) {
            SettingColor color = result.hitEntity() != null ? entityHighlightColor.get() : existingProjectileColor.get();
            renderPath(event, result.path(), color);
        }

        if (result.hitEntity() != null && renderEntityHighlight.get()) {
            event.renderer.box(result.hitEntity().getBoundingBox(), entityHighlightColor.get(), entityHighlightColor.get(), entityShapeMode.get(), 0);
        }
    }

    // Shared simulation logic extracted from both predict methods
    private SimResult simulatePath(Vec3d startPos, Vec3d startVel, Entity ignoreEntity, double drag, double gravity, int maxTicks, Render3DEvent event, SettingColor boxCol) {
        List<Vec3d> path = new ArrayList<>();
        Vec3d currentPos = startPos;
        Vec3d currentVel = startVel;
        path.add(currentPos);

        for (int i = 0; i < maxTicks; i++) {
            Vec3d nextPos = currentPos.add(currentVel);

            BlockHitResult blockHit = mc.world.raycast(new RaycastContext(
                currentPos, nextPos, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player
            ));

            EntityHitResult entityHit = findEntityHit(currentPos, nextPos, ignoreEntity);

            if (entityHit != null) {
                path.add(entityHit.getPos());
                return new SimResult(path, entityHit.getEntity());
            } else if (blockHit.getType() != HitResult.Type.MISS) {
                path.add(blockHit.getPos());
                if (renderBox.get() && event != null) {
                    event.renderer.box(blockHit.getBlockPos(), boxCol, boxCol, boxShapeMode.get(), 0);
                }
                break;
            }

            currentPos = nextPos;
            path.add(currentPos);
            currentVel = currentVel.multiply(drag).subtract(0, gravity, 0);
        }

        return new SimResult(path, null);
    }

    // Shared render helper to avoid duplicating the line loop
    private void renderPath(Render3DEvent event, List<Vec3d> path, SettingColor color) {
        for (int i = 0; i < path.size() - 1; i++) {
            Vec3d a = path.get(i), b = path.get(i + 1);
            event.renderer.line(a.x, a.y, a.z, b.x, b.y, b.z, color);
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

    private EntityHitResult findEntityHit(Vec3d start, Vec3d end, Entity ignoreEntity) {
        for (Entity entity : mc.world.getEntities()) {
            if (entity == mc.player || entity == ignoreEntity) continue;
            if (!(entity instanceof LivingEntity)) continue;

            Box box = entity.getBoundingBox().expand(0.3);
            var hit = box.raycast(start, end);
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
