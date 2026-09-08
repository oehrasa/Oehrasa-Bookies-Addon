package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.entity.simulator.ProjectileEntitySimulator;
import meteordevelopment.meteorclient.utils.entity.simulator.SimulationStep;
import meteordevelopment.meteorclient.utils.misc.Pool;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.*;
import net.minecraft.item.*;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.RaycastContext;
import org.joml.Vector3d;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TrajectoryPlus extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTrail = settings.createGroup("Trail");
    private final SettingGroup sgBox = settings.createGroup("Box");
    private final SettingGroup sgEntity = settings.createGroup("Entity Highlight");

    private final Setting<List<Item>> items = sgGeneral.add(new ItemListSetting.Builder()
        .name("items")
        .description("Items to display trajectories for.")
        .defaultValue(getDefaultItems())
        .filter(this::itemFilter)
        .build()
    );

    private final Setting<Boolean> otherPlayers = sgGeneral.add(new BoolSetting.Builder()
        .name("other-players")
        .description("Calculates trajectories for other players.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> firedProjectiles = sgGeneral.add(new BoolSetting.Builder()
        .name("fired-projectiles")
        .description("Calculates trajectories for already fired projectiles.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> ignoreWitherSkulls = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-wither-skulls")
        .description("Whether to ignore fired wither skulls.")
        .defaultValue(false)
        .visible(firedProjectiles::get)
        .build()
    );

    private final Setting<Boolean> ignoreLanded = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-landed")
        .description("Ignore already fired projectiles that are stuck/landed (near-zero velocity).")
        .defaultValue(true)
        .visible(firedProjectiles::get)
        .build()
    );

    private final Setting<Boolean> accurate = sgGeneral.add(new BoolSetting.Builder()
        .name("accurate")
        .description("Whether or not to calculate more accurate.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Integer> simulationSteps = sgGeneral.add(new IntSetting.Builder()
        .name("simulation-steps")
        .description("How many steps to simulate projectiles. Zero for no limit.")
        .defaultValue(500)
        .sliderMax(5000)
        .build()
    );

    private final Setting<Boolean> renderTrailAhead = sgTrail.add(new BoolSetting.Builder()
        .name("render-trail-ahead")
        .description("Renders the predicted path ahead of a projectile.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> renderTrailBehind = sgTrail.add(new BoolSetting.Builder()
        .name("render-trail-behind")
        .description("Renders a breadcrumb trail of where an already fired projectile has actually been.")
        .defaultValue(false)
        .visible(firedProjectiles::get)
        .build()
    );

    private final Setting<Integer> trailLength = sgTrail.add(new IntSetting.Builder()
        .name("trail-length")
        .description("How many points to keep in the breadcrumb trail.")
        .defaultValue(20)
        .min(5)
        .max(100)
        .visible(() -> firedProjectiles.get() && renderTrailBehind.get())
        .build()
    );

    private final Setting<Integer> ignoreFirstTicks = sgTrail.add(new IntSetting.Builder()
        .name("ignore-rendering-first-ticks")
        .description("Ignores rendering the first given ticks, to make the rest of the path more visible.")
        .defaultValue(3)
        .min(0)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgTrail.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Predicted path color for your own (and other players') aimed projectile.")
        .defaultValue(new SettingColor(255, 150, 0))
        .build()
    );

    private final Setting<SettingColor> existingProjectileColor = sgTrail.add(new ColorSetting.Builder()
        .name("existing-projectile-color")
        .description("Color used for already fired projectiles - both their predicted path and breadcrumb trail.")
        .defaultValue(new SettingColor(0, 200, 200, 150))
        .visible(firedProjectiles::get)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgBox.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the block-hit quad and position boxes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgBox.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The fill color of the hit quad.")
        .defaultValue(new SettingColor(255, 150, 0, 35))
        .build()
    );

    private final Setting<Boolean> renderFrameBox = sgBox.add(new BoolSetting.Builder()
        .name("render-frame-box")
        .description("Also renders a full block-sized frame box around the predicted hit position, in addition to the hit quad.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> frameBoxColor = sgBox.add(new ColorSetting.Builder()
        .name("frame-box-color")
        .description("Color of the frame box.")
        .defaultValue(new SettingColor(255, 255, 255, 200))
        .visible(renderFrameBox::get)
        .build()
    );

    private final Setting<Boolean> renderPositionBox = sgBox.add(new BoolSetting.Builder()
        .name("render-position-boxes")
        .description("Renders the actual position the projectile will be at each tick along its trajectory.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> positionBoxSize = sgBox.add(new DoubleSetting.Builder()
        .name("position-box-size")
        .description("The size of the box drawn at the simulated positions.")
        .defaultValue(0.02)
        .sliderRange(0.01, 0.1)
        .visible(renderPositionBox::get)
        .build()
    );

    private final Setting<SettingColor> positionSideColor = sgBox.add(new ColorSetting.Builder()
        .name("position-side-color")
        .description("The side color of the position boxes.")
        .defaultValue(new SettingColor(255, 150, 0, 35))
        .visible(renderPositionBox::get)
        .build()
    );

    private final Setting<SettingColor> positionLineColor = sgBox.add(new ColorSetting.Builder()
        .name("position-line-color")
        .description("The line color of the position boxes.")
        .defaultValue(new SettingColor(255, 150, 0))
        .visible(renderPositionBox::get)
        .build()
    );

    private final Setting<Boolean> renderEntityHighlight = sgEntity.add(new BoolSetting.Builder()
        .name("render-entity-highlight")
        .description("Highlights entities that the predicted path will hit, using a distinct color.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> entityHighlightColor = sgEntity.add(new ColorSetting.Builder()
        .name("entity-highlight-color")
        .description("Color used for entities the path will hit.")
        .defaultValue(new SettingColor(255, 0, 0, 90))
        .visible(renderEntityHighlight::get)
        .build()
    );

    private final Setting<ShapeMode> entityShapeMode = sgEntity.add(new EnumSetting.Builder<ShapeMode>()
        .name("entity-shape-mode")
        .description("How the entity highlight box is rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(renderEntityHighlight::get)
        .build()
    );

    private final ProjectileEntitySimulator simulator = new ProjectileEntitySimulator();

    private final Pool<Vector3d> vec3s = new Pool<>(Vector3d::new);
    private final List<Path> paths = new ArrayList<>();

    // Breadcrumb ("behind") trails for already-fired projectiles, keyed by entity UUID.
    private final Map<UUID, List<Vector3d>> firedTrails = new ConcurrentHashMap<>();

    private static final double MULTISHOT_OFFSET = Math.toRadians(10); // accurate-ish offset of crossbow multishot in radians (10°)
    private static final double MIN_THREAT_SPEED_SQ = 0.0025;

    private static final ProjectileEntitySimulator.MotionData FIREBALL_MOTION =
        new ProjectileEntitySimulator.MotionData(0f, 0, 0.0, 0.95f, 0.95f, null);

    public TrajectoryPlus() {
        super(Addon.CATEGORY2, "trajectory-plus", "Predicts the trajectory of throwable items and already fired projectiles.");
    }

    @Override
    public void onDeactivate() {
        firedTrails.clear();
    }

    private boolean itemFilter(Item item) {
        return item instanceof RangedWeaponItem || item instanceof FishingRodItem || item instanceof TridentItem ||
            item instanceof SnowballItem || item instanceof EggItem || item instanceof EnderPearlItem ||
            item instanceof ExperienceBottleItem || item instanceof ThrowablePotionItem || item instanceof WindChargeItem;
    }

    private List<Item> getDefaultItems() {
        List<Item> items = new ArrayList<>();

        for (Item item : Registries.ITEM) {
            if (itemFilter(item)) items.add(item);
        }

        return items;
    }

    private Path getEmptyPath() {
        for (Path path : paths) {
            if (path.points.isEmpty()) return path;
        }

        Path path = new Path();
        paths.add(path);
        return path;
    }

    private void calculatePath(PlayerEntity player, float tickDelta) {
        for (Path path : paths) path.clear();

        ItemStack itemStack = player.getMainHandStack();
        if (!items.get().contains(itemStack.getItem())) {
            itemStack = player.getOffHandStack();
            if (!items.get().contains(itemStack.getItem())) return;
        }

        if (!simulator.set(player, itemStack, 0, accurate.get(), tickDelta)) return;
        Path p = getEmptyPath().calculate();
        if (player == mc.player) p.ignoreFirstTicks();

        if (itemStack.getItem() instanceof CrossbowItem && Utils.hasEnchantment(itemStack, Enchantments.MULTISHOT)) {
            if (!simulator.set(player, itemStack, MULTISHOT_OFFSET, accurate.get(), tickDelta)) return; // left multishot arrow
            p = getEmptyPath().calculate();
            if (player == mc.player) p.ignoreFirstTicks();

            if (!simulator.set(player, itemStack, -MULTISHOT_OFFSET, accurate.get(), tickDelta)) return; // right multishot arrow
            p = getEmptyPath().calculate();
            if (player == mc.player) p.ignoreFirstTicks();
        }
    }

    private void calculateFiredPath(Entity entity, double tickDelta) {
        for (Path path : paths) path.clear();

        if (!primeSimulator(entity)) return;
        getEmptyPath().setStart(entity, tickDelta).calculate();
    }

    // Fireballs/small fireballs/dragon fireballs/wither skulls are all ProjectileEntity subtypes the
    // simulator already knows how to collide-check
    private boolean primeSimulator(Entity entity) {
        if (entity instanceof FireballEntity || entity instanceof SmallFireballEntity
            || entity instanceof DragonFireballEntity || entity instanceof WitherSkullEntity) {
            simulator.set((ProjectileEntity) entity, FIREBALL_MOTION);
            return true;
        }

        return simulator.set(entity);
    }

    private void updateBreadcrumb(Entity entity) {
        List<Vector3d> trail = firedTrails.computeIfAbsent(entity.getUuid(), k -> new ArrayList<>());
        trail.add(new Vector3d(entity.getX(), entity.getY(), entity.getZ()));

        int maxTrail = trailLength.get();
        while (trail.size() > maxTrail) trail.remove(0);
    }

    private void renderBreadcrumb(Render3DEvent event, Entity entity) {
        List<Vector3d> trail = firedTrails.get(entity.getUuid());
        if (trail == null || trail.size() < 2) return;

        for (int i = 0; i < trail.size() - 1; i++) {
            Vector3d a = trail.get(i), b = trail.get(i + 1);
            event.renderer.line(a.x, a.y, a.z, b.x, b.y, b.z, existingProjectileColor.get());
        }
    }

    private static final double SHULKER_GRAVITY = 0.0;
    private static final double SHULKER_DRAG = 0.99;

    private void renderShulkerBulletPrediction(Render3DEvent event, Entity shulkerBullet, SettingColor color) {
        int maxTicks = simulationSteps.get() > 0 ? simulationSteps.get() : 500;

        List<Vec3d> points = new ArrayList<>();
        Vec3d currentPos = shulkerBullet.getEntityPos();
        Vec3d currentVel = shulkerBullet.getVelocity();
        points.add(currentPos);

        BlockPos hitBlockPos = null;
        Entity hitEntity = null;

        for (int i = 0; i < maxTicks; i++) {
            currentVel = currentVel.subtract(0, SHULKER_GRAVITY, 0).multiply(SHULKER_DRAG);
            Vec3d nextPos = currentPos.add(currentVel);

            EntityHitResult entityHit = findEntityHit(currentPos, nextPos, shulkerBullet);
            if (entityHit != null) {
                points.add(entityHit.getPos());
                hitEntity = entityHit.getEntity();
                break;
            }

            BlockHitResult blockHit = mc.world.raycast(new RaycastContext(
                currentPos, nextPos, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player
            ));

            if (blockHit.getType() != HitResult.Type.MISS) {
                points.add(blockHit.getPos());
                hitBlockPos = blockHit.getBlockPos();
                break;
            }

            currentPos = nextPos;
            points.add(currentPos);
        }

        SettingColor pathColor = (hitEntity != null && renderEntityHighlight.get()) ? entityHighlightColor.get() : color;

        if (renderTrailAhead.get()) {
            for (int i = 0; i < points.size() - 1; i++) {
                Vec3d a = points.get(i), b = points.get(i + 1);
                event.renderer.line(a.x, a.y, a.z, b.x, b.y, b.z, pathColor);
            }
        }

        if (hitBlockPos != null && renderFrameBox.get()) {
            event.renderer.box(hitBlockPos, frameBoxColor.get(), frameBoxColor.get(), shapeMode.get(), 0);
        }

        if (hitEntity != null && renderEntityHighlight.get()) {
            Box box = hitEntity.getBoundingBox();
            event.renderer.box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
                entityHighlightColor.get(), entityHighlightColor.get(), entityShapeMode.get(), 0);
        }
    }

    private EntityHitResult findEntityHit(Vec3d start, Vec3d end, Entity ignoreEntity) {
        for (Entity e : mc.world.getEntities()) {
            if (e == mc.player || e == ignoreEntity) continue;
            if (!(e instanceof LivingEntity)) continue;

            Box box = e.getBoundingBox().expand(0.3);
            var hit = box.raycast(start, end);
            if (hit.isPresent()) return new EntityHitResult(e, hit.get());
        }

        return null;
    }

    private void pruneBreadcrumbs() {
        if (firedTrails.isEmpty()) return;

        Set<UUID> alive = new HashSet<>();
        for (Entity e : mc.world.getEntities()) alive.add(e.getUuid());
        firedTrails.keySet().removeIf(id -> !alive.contains(id));
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;

        float tickDelta = mc.world.getTickManager().isFrozen() ? 1 : event.tickDelta;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (!otherPlayers.get() && player != mc.player) continue;

            calculatePath(player, tickDelta);
            for (Path path : paths) path.render(event, lineColor.get());
        }

        if (firedProjectiles.get()) {
            for (Entity entity : mc.world.getEntities()) {
                boolean isShulkerBullet = entity instanceof ShulkerBulletEntity;
                if (!(entity instanceof ProjectileEntity) && !isShulkerBullet) continue;
                if (ignoreWitherSkulls.get() && entity instanceof WitherSkullEntity) continue;
                if (entity instanceof TridentEntity trident && trident.noClip) continue; // when it's returning via loyalty
                if (ignoreLanded.get() && entity.getVelocity().lengthSquared() < MIN_THREAT_SPEED_SQ) continue;

                updateBreadcrumb(entity);
                if (renderTrailBehind.get()) renderBreadcrumb(event, entity);

                if (isShulkerBullet) {
                    renderShulkerBulletPrediction(event, entity, existingProjectileColor.get());
                } else {
                    calculateFiredPath(entity, tickDelta);
                    for (Path path : paths) path.render(event, existingProjectileColor.get());
                }
            }

            pruneBreadcrumbs();
        }
    }

    private class Path {
        private final List<Vector3d> points = new ArrayList<>();

        private boolean hitQuad, hitQuadHorizontal;
        private double hitQuadX1, hitQuadY1, hitQuadZ1, hitQuadX2, hitQuadY2, hitQuadZ2;
        private BlockPos hitBlockPos;

        private final List<Entity> collidingEntities = new ArrayList<>();
        public Vector3d lastPoint;
        private int start;

        public void clear() {
            vec3s.freeAll(points);
            points.clear();

            hitQuad = false;
            hitBlockPos = null;
            collidingEntities.clear();
            lastPoint = null;
            start = 0;
        }

        public Path calculate() {
            addPoint();

            for (int i = 0; i < (simulationSteps.get() > 0 ? simulationSteps.get() : Integer.MAX_VALUE); i++) {
                SimulationStep result = simulator.tick();

                processHitResults(result);
                if (result.shouldStop) break;

                addPoint();
            }

            return this;
        }

        public Path setStart(Entity entity, double tickDelta) {
            lastPoint = new Vector3d(
                MathHelper.lerp(tickDelta, entity.lastRenderX, entity.getX()),
                MathHelper.lerp(tickDelta, entity.lastRenderY, entity.getY()),
                MathHelper.lerp(tickDelta, entity.lastRenderZ, entity.getZ())
            );

            return this;
        }

        private void addPoint() {
            points.add(vec3s.get().set(simulator.pos));
        }

        private void processHitResults(SimulationStep step) {
            for (int i = 0; i < step.hitResults.length; i++) {
                HitResult result = step.hitResults[i];
                if (result.getType() == HitResult.Type.BLOCK) {
                    BlockHitResult r = (BlockHitResult) result;

                    hitQuad = true;
                    hitBlockPos = r.getBlockPos();
                    hitQuadX1 = r.getPos().x;
                    hitQuadY1 = r.getPos().y;
                    hitQuadZ1 = r.getPos().z;
                    hitQuadX2 = r.getPos().x;
                    hitQuadY2 = r.getPos().y;
                    hitQuadZ2 = r.getPos().z;

                    if (r.getSide() == Direction.UP || r.getSide() == Direction.DOWN) {
                        hitQuadHorizontal = true;
                        hitQuadX1 -= 0.25;
                        hitQuadZ1 -= 0.25;
                        hitQuadX2 += 0.25;
                        hitQuadZ2 += 0.25;
                    } else if (r.getSide() == Direction.NORTH || r.getSide() == Direction.SOUTH) {
                        hitQuadHorizontal = false;
                        hitQuadX1 -= 0.25;
                        hitQuadY1 -= 0.25;
                        hitQuadX2 += 0.25;
                        hitQuadY2 += 0.25;
                    } else {
                        hitQuadHorizontal = false;
                        hitQuadZ1 -= 0.25;
                        hitQuadY1 -= 0.25;
                        hitQuadZ2 += 0.25;
                        hitQuadY2 += 0.25;
                    }

                    points.add(Utils.set(vec3s.get(), result.getPos()));
                } else if (result.getType() == HitResult.Type.ENTITY) {
                    Entity entity = ((EntityHitResult) result).getEntity();
                    collidingEntities.add(entity);

                    if (step.shouldStop && i == step.hitResults.length - 1) {
                        points.add(Utils.set(vec3s.get(), result.getPos()));
                    }
                }
            }
        }

        public void ignoreFirstTicks() {
            start = points.size() <= TrajectoryPlus.this.ignoreFirstTicks.get() ? 0 : TrajectoryPlus.this.ignoreFirstTicks.get();
        }

        public void render(Render3DEvent event, SettingColor color) {
            // Render "ahead" trail
            if (renderTrailAhead.get()) {
                for (int i = start; i < points.size(); i++) {
                    Vector3d point = points.get(i);

                    if (lastPoint != null) {
                        event.renderer.line(lastPoint.x, lastPoint.y, lastPoint.z, point.x, point.y, point.z, color);
                        if (renderPositionBox.get()) {
                            event.renderer.box(
                                point.x - positionBoxSize.get(), point.y - positionBoxSize.get(), point.z - positionBoxSize.get(),
                                point.x + positionBoxSize.get(), point.y + positionBoxSize.get(), point.z + positionBoxSize.get(),
                                positionSideColor.get(), positionLineColor.get(), shapeMode.get(), 0
                            );
                        }
                    }

                    lastPoint = point;
                }
            }

            // Render hit quad + optional frame box
            if (hitQuad) {
                if (hitQuadHorizontal)
                    event.renderer.sideHorizontal(hitQuadX1, hitQuadY1, hitQuadZ1, hitQuadX1 + 0.5, hitQuadZ1 + 0.5, sideColor.get(), color, shapeMode.get());
                else
                    event.renderer.sideVertical(hitQuadX1, hitQuadY1, hitQuadZ1, hitQuadX2, hitQuadY2, hitQuadZ2, sideColor.get(), color, shapeMode.get());

                if (renderFrameBox.get() && hitBlockPos != null) {
                    event.renderer.box(hitBlockPos, frameBoxColor.get(), frameBoxColor.get(), shapeMode.get(), 0);
                }
            }

            // Render highlighted colliding entities
            SettingColor entityColor = renderEntityHighlight.get() ? entityHighlightColor.get() : color;
            ShapeMode entityMode = renderEntityHighlight.get() ? entityShapeMode.get() : shapeMode.get();

            for (Entity collidingEntity : collidingEntities) {
                double x = (collidingEntity.getX() - collidingEntity.lastX) * event.tickDelta;
                double y = (collidingEntity.getY() - collidingEntity.lastY) * event.tickDelta;
                double z = (collidingEntity.getZ() - collidingEntity.lastZ) * event.tickDelta;

                Box box = collidingEntity.getBoundingBox();
                event.renderer.box(x + box.minX, y + box.minY, z + box.minZ, x + box.maxX, y + box.maxY, z + box.maxZ, entityColor, entityColor, entityMode, 0);
            }
        }
    }
}
