package com.AutoBookshelf.addon.modules;
// V2

import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.utils.ProjectilePhysics;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.arrow.SpectralArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.hurtingprojectile.DragonFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import net.minecraft.world.entity.projectile.throwableitemprojectile.*;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Arena M "Active Protection System".
 * Every tick the module scans for hostile projectiles heading towards the player,
 * predicts their real flight path (real gravity/drag applied via ProjectilePhysics,
 * the same physics utility TrajectoryPlus uses and, if one is considered dangerous enough,
 * rotates towards a calculated intercept point and throws a wind charge to neutralize it)
 */
public class ArenaM extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgThreats = settings.createGroup("Threats");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> maxRange = sgGeneral.add(new IntSetting.Builder()
        .name("max-range")
        .description("Maximum distance (in blocks) a threat is considered from.")
        .defaultValue(60)
        .min(1)
        .sliderMax(100)
        .build()
    );

    private final Setting<Integer> cooldownTicks = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown")
        .description("Ticks to wait after throwing a wind charge before another can be thrown.")
        .defaultValue(10)
        .min(0)
        .sliderMax(40)
        .build()
    );

    private final Setting<Integer> rotationPriority = sgGeneral.add(new IntSetting.Builder()
        .name("rotation-priority")
        .description("Priority used when rotating to aim at the intercept point.")
        .defaultValue(-100)
        .min(-1000)
        .sliderMax(1000)
        .build()
    );

    private final Setting<Boolean> interceptArrows = sgThreats.add(new BoolSetting.Builder()
        .name("arrows")
        .description("Intercept arrows, spectral arrows and tridents.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> interceptFireballs = sgThreats.add(new BoolSetting.Builder()
        .name("fireballs")
        .description("Intercept fireballs, small fireballs, dragon fireballs and wither skulls.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> interceptThrowables = sgThreats.add(new BoolSetting.Builder()
        .name("throwable")
        .description("Intercept snowballs, eggs, ender pearls and experience bottles.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> interceptPotions = sgThreats.add(new BoolSetting.Builder()
        .name("potions")
        .description("Intercept thrown potions.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> interceptWindCharges = sgThreats.add(new BoolSetting.Builder()
        .name("wind-charges")
        .description("Intercept wind charges thrown by other players.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> interceptOwnProjectiles = sgThreats.add(new BoolSetting.Builder()
        .name("intercept-our-own")
        .description("Also treat projectiles you fired/threw yourself as valid targets. Off by default since shooting down your own shots is rarely useful.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> interceptNonTargeting = sgThreats.add(new BoolSetting.Builder()
        .name("pantsir-mode")
        .description("Also consider incoming threat that aren't actually on course to hit you (inaccurate like the real Pantsir).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> ignoreLanded = sgThreats.add(new BoolSetting.Builder()
        .name("ignore-landed")
        .description("Ignore projectiles that are already stuck/landed (near-zero velocity). Mainly matters with intercept-non-target on, since that setting skips the normal moving-towards-you filter that would otherwise exclude them.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> quickSwap = sgGeneral.add(new BoolSetting.Builder()
        .name("quick-swap")
        .description("Swaps to the wind charge by simulating hotbar key presses instead of inventory clicks. May get flagged by anticheats.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> debugRender = sgRender.add(new BoolSetting.Builder()
        .name("debug-render")
        .description("Renders the predicted threat path and the calculated intercept path.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> threatColor = sgRender.add(new ColorSetting.Builder()
        .name("threat-color")
        .description("Color of the predicted threat path.")
        .defaultValue(new SettingColor(255, 60, 60, 69))
        .visible(debugRender::get)
        .build()
    );

    private final Setting<SettingColor> interceptColor = sgRender.add(new ColorSetting.Builder()
        .name("intercept-color")
        .description("Color of the calculated wind charge path.")
        .defaultValue(new SettingColor(60, 200, 255, 75))
        .visible(debugRender::get)
        .build()
    );

    private final Setting<SettingColor> confirmedHitColor = sgRender.add(new ColorSetting.Builder()
        .name("confirmed-hit-color")
        .description("Color of the box drawn where the thrown wind charge's real hitbox is confirmed to touch the threat's real hitbox. Unlike threat-color/intercept-color (which are drawn from the prediction), this only shows up on an actual confirmed collision, so it's the ground truth to compare the prediction against.")
        .defaultValue(new SettingColor(80, 255, 80, 130))
        .visible(debugRender::get)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the intercept point box is rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(debugRender::get)
        .build()
    );

    // Physical constants for the interceptor
    private static final double WIND_SPEED = 1.5;        // blocks/tick
    private static final double WIND_HALF_SIZE = 0.25;
    private static final double SAFETY_MARGIN = 1.5;     // threats that miss by more than this are ignored
    private static final int MAX_LEAD = 30;              // max ticks the wind charge is simulated for
    private static final int DETECTION_TICKS = MAX_LEAD; // full simulation horizon
    private static final double MIN_THREAT_SPEED_SQ = 0.0025; // below this, treat as landed/stuck

    // Real-hit tracking constants. These only drive updateChargeTracking(), which
    // is a debug-render concern, not the detection/solver hot path.
    private static final int CHARGE_SPAWN_SEARCH_TICKS = 5;
    private static final int CHARGE_TRACK_TIMEOUT_TICKS = MAX_LEAD + 10;
    private static final int CONFIRMED_HIT_DISPLAY_TICKS = 20;
    // How fast the learned spawn-latency average adapts to new samples (EMA alpha).
    private static final double LATENCY_SMOOTHING_ALPHA = 0.3;

    private enum Stage {
        IDLE,       // scanning for threats
        AIMING,     // rotation in progress, waiting for callback
        COOLDOWN    // waiting after a shot
    }

    private record Threat(Entity entity, Vec3[] path, int impactTick, double closestDistance) {
    }

    private record Solution(Vec3 direction, int ticksToImpact) {
    }

    private Stage stage = Stage.IDLE;
    private int cooldownTimer = 0;
    private Threat lastTarget;
    private Solution lastSolution;

    private final Set<Integer> preThrowChargeIds = new HashSet<>();
    private boolean awaitingChargeSpawn = false;
    private int spawnSearchTimer = 0;
    private Entity trackedCharge;
    private Entity trackedThreatEntity;
    private int trackTimer = 0;
    private AABB confirmedHitBox;
    private int confirmedHitTimer = 0;

    private double averageSpawnLatencyTicks = 1.0; // seeded conservatively until measured
    private int ticksWaitedForSpawn = 0;

    public ArenaM() {
        super(Addon.CATEGORY, "Arena-M", "Throws wind charges to intercept incoming projectiles mid-air.");
    }

    @Override
    public void onActivate() {
        resetState();
    }

    @Override
    public void onDeactivate() {
        resetState();
    }

    private void resetState() {
        stage = Stage.IDLE;
        cooldownTimer = 0;
        lastTarget = null;
        lastSolution = null;
        awaitingChargeSpawn = false;
        spawnSearchTimer = 0;
        trackedCharge = null;
        trackedThreatEntity = null;
        trackTimer = 0;
        confirmedHitBox = null;
        confirmedHitTimer = 0;
        preThrowChargeIds.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        // Cheap no-op when nothing was just thrown (see updateChargeTracking()).
        updateChargeTracking();

        if (stage == Stage.COOLDOWN) {
            if (cooldownTimer > 0) {
                cooldownTimer--;
                return;
            }
            stage = Stage.IDLE;
        }

        if (stage == Stage.AIMING) return;

        Vec3 eyePos = mc.player.getEyePosition();

        // 1. Detection
        Threat target = findMostDangerousThreat(eyePos);
        if (target == null) return;

        // 2. Interception solution
        Solution solution = solveIntercept(target, eyePos);
        if (solution == null) return;

        // Safety: abort if the shot would clip our own hitbox
        if (pathIntersectsSelf(eyePos, solution.direction())) return;

        // Ensure we have a wind charge
        if (!findWindCharge().found()) return;

        // Item cooldown check
        if (mc.player.getCooldowns().isOnCooldown(Items.WIND_CHARGE.getDefaultInstance())) return;

        lastTarget = target;
        lastSolution = solution;

        // 3. Execute: rotate, then throw via callback
        float[] rot = toYawPitch(solution.direction());
        stage = Stage.AIMING;

        Rotations.rotate(rot[0], rot[1], rotationPriority.get(), () -> {
            if (mc.player != null) {
                beginChargeTracking(target.entity());
                throwWindCharge();
            }
            cooldownTimer = cooldownTicks.get();
            stage = Stage.COOLDOWN;
        });
    }

    private Threat findMostDangerousThreat(Vec3 eyePos) {
        List<Threat> threats = new ArrayList<>();
        double rangeSq = maxRange.get() * (double) maxRange.get();

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!isThreat(entity)) continue;
            if (!interceptOwnProjectiles.get() && isOwnedByPlayer(entity)) continue;

            // Filter out projectiles that are already stuck/landed. This mainly matters
            // with intercept-non-target on, since that setting bypasses the
            // moving-towards-you check below, which would otherwise catch these.
            if (ignoreLanded.get() && entity.getDeltaMovement().lengthSqr() < MIN_THREAT_SPEED_SQ) continue;

            Vec3 pos = entity.position();
            if (pos.distanceToSqr(eyePos) > rangeSq) continue;

            if (!interceptNonTargeting.get()) {
                Vec3 fromPlayer = pos.subtract(eyePos);
                if (entity.getDeltaMovement().dot(fromPlayer) >= 0) continue;
            }

            Vec3[] path = simulateThreatPath(entity);
            if (path == null) continue;

            double closest = Double.MAX_VALUE;
            int closestTick = -1;
            for (int i = 0; i < path.length; i++) {
                if (path[i] == null) continue;
                double d = path[i].distanceTo(eyePos);
                if (d < closest) {
                    closest = d;
                    closestTick = i;
                }
            }

            if (!interceptNonTargeting.get() && closest > SAFETY_MARGIN) continue;

            threats.add(new Threat(entity, path, closestTick, closest));
        }

        if (threats.isEmpty()) return null;

        // Sort by actual danger first (how close it comes to hitting you), and use
        // approach-time only as a tiebreaker between similarly dangerous threats.
        // Sorting by impactTick alone breaks down once intercept-non-target is on,
        // since a projectile flying away from you can have its closest point occur
        // at tick 0 (its current position) an early tick number that has nothing
        // to do with actual danger, letting it wrongly outrank a real incoming threat.
        threats.sort(Comparator
            .comparingDouble(Threat::closestDistance)
            .thenComparingInt(Threat::impactTick));
        return threats.get(0);
    }

    private Vec3[] simulateThreatPath(Entity entity) {
        ProjectilePhysics.Result result = ProjectilePhysics.simulate(
            entity, entity.position(), entity.getDeltaMovement(), DETECTION_TICKS
        );

        List<Vec3> simulated = result.path();
        Vec3[] path = new Vec3[DETECTION_TICKS + 1];
        for (int i = 0; i < simulated.size() && i < path.length; i++) {
            path[i] = simulated.get(i);
        }
        return path;
    }

    private boolean isThreat(Entity entity) {
        if (entity instanceof Arrow || entity instanceof SpectralArrow || entity instanceof ThrownTrident)
            return interceptArrows.get();
        if (entity instanceof LargeFireball || entity instanceof SmallFireball
            || entity instanceof DragonFireball || entity instanceof WitherSkull)
            return interceptFireballs.get();
        if (entity instanceof Snowball || entity instanceof ThrownEgg
            || entity instanceof ThrownEnderpearl || entity instanceof ThrownExperienceBottle)
            return interceptThrowables.get();
        if (entity instanceof AbstractThrownPotion)
            return interceptPotions.get();
        if (entity instanceof WindCharge)
            return interceptWindCharges.get();
        return false;
    }

    private boolean isOwnedByPlayer(Entity entity) {
        if (!(entity instanceof Projectile projectile)) return false;
        Entity owner = projectile.getOwner();
        return owner != null && owner.getUUID().equals(mc.player.getUUID());
    }

    private Solution solveIntercept(Threat target, Vec3 eyePos) {
        AABB baseBox = target.entity().getBoundingBox();
        Vec3 basePos = target.entity().position();
        int latencyTicks = getEffectiveLatencyTicks();

        for (int tau = 1; tau <= MAX_LEAD; tau++) {
            if (tau >= target.path().length || target.path()[tau] == null) break; // path ends here; larger tau can't help either

            int windTicks = tau - latencyTicks;
            if (windTicks <= 0) continue; // charge hasn't actually left the barrel yet at this real-time tick

            Vec3 aimPoint = target.path()[tau];
            Vec3 direction = aimPoint.subtract(eyePos).normalize();
            Vec3 windPos = eyePos.add(direction.scale(WIND_SPEED * windTicks));

            Vec3 threatPrev = target.path()[tau - 1] != null ? target.path()[tau - 1] : aimPoint;
            Vec3 windPrev = eyePos.add(direction.scale(WIND_SPEED * (windTicks - 1)));

            AABB threatBoxPrev = baseBox.move(threatPrev.subtract(basePos));
            AABB threatBoxCurr = baseBox.move(aimPoint.subtract(basePos));

            AABB windBoxPrev = windBoxAt(windPrev);
            AABB windBoxCurr = windBoxAt(windPos);

            if (union(threatBoxPrev, threatBoxCurr).intersects(union(windBoxPrev, windBoxCurr))) {
                // Solution.ticksToImpact() means "how long the real charge actually
                // flies", which is windTicks, not tau
                return new Solution(direction, windTicks);
            }
        }
        return null;
    }

    private int getEffectiveLatencyTicks() {
        return (int) Math.round(Math.max(0, Math.min(averageSpawnLatencyTicks, MAX_LEAD - 1)));
    }

    private AABB windBoxAt(Vec3 pos) {
        return new AABB(
            pos.x - WIND_HALF_SIZE, pos.y - WIND_HALF_SIZE, pos.z - WIND_HALF_SIZE,
            pos.x + WIND_HALF_SIZE, pos.y + WIND_HALF_SIZE, pos.z + WIND_HALF_SIZE
        );
    }

    private AABB union(AABB a, AABB b) {
        return new AABB(
            Math.min(a.minX, b.minX), Math.min(a.minY, b.minY), Math.min(a.minZ, b.minZ),
            Math.max(a.maxX, b.maxX), Math.max(a.maxY, b.maxY), Math.max(a.maxZ, b.maxZ)
        );
    }

    private boolean pathIntersectsSelf(Vec3 eyePos, Vec3 direction) {
        AABB selfBox = mc.player.getBoundingBox().inflate(0.1);
        for (int t = 1; t <= 3; t++) {
            Vec3 windPos = eyePos.add(direction.scale(WIND_SPEED * t));
            AABB windBox = windBoxAt(windPos);
            if (windBox.intersects(selfBox)) return true;
        }
        return false;
    }

    private float[] toYawPitch(Vec3 direction) {
        double horizontalDist = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        float yaw = (float) (Math.toDegrees(Math.atan2(direction.z, direction.x)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(direction.y, horizontalDist));
        return new float[]{yaw, pitch};
    }

    // Inventory & throwing
    private FindItemResult findWindCharge() {
        return InvUtils.findInHotbar(Items.WIND_CHARGE);
    }

    private void throwWindCharge() {
        FindItemResult windCharge = findWindCharge();
        if (!windCharge.found()) return;

        int selectedSlot = mc.player.getInventory().getSelectedSlot();
        int itemSlot = windCharge.slot();

        if (quickSwap.get()) {
            InvUtils.quickSwap().fromId(selectedSlot).to(itemSlot);
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            InvUtils.quickSwap().fromId(selectedSlot).to(itemSlot);
        } else {
            InvUtils.swap(itemSlot, false);
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            InvUtils.swapBack();
        }
    }

    private void beginChargeTracking(Entity threatEntity) {
        preThrowChargeIds.clear();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof WindCharge) preThrowChargeIds.add(e.getId());
        }
        trackedThreatEntity = threatEntity;
        awaitingChargeSpawn = true;
        spawnSearchTimer = CHARGE_SPAWN_SEARCH_TICKS;
        ticksWaitedForSpawn = 0;
        trackedCharge = null;
    }

    private void updateChargeTracking() {
        if (confirmedHitBox != null && --confirmedHitTimer <= 0) {
            confirmedHitBox = null;
        }

        if (awaitingChargeSpawn) {
            spawnSearchTimer--;
            ticksWaitedForSpawn++;
            for (Entity e : mc.level.entitiesForRendering()) {
                if (!(e instanceof WindCharge)) continue;
                if (preThrowChargeIds.contains(e.getId())) continue;
                if (!isOwnedByPlayer(e)) continue;
                trackedCharge = e;
                awaitingChargeSpawn = false;
                trackTimer = CHARGE_TRACK_TIMEOUT_TICKS;
                // EMA update: nudge the running latency estimate towards this real sample.
                averageSpawnLatencyTicks += (ticksWaitedForSpawn - averageSpawnLatencyTicks) * LATENCY_SMOOTHING_ALPHA;
                break;
            }
            // Gave up without finding it; charge likely never spawned (e.g. throw failed).
            // Don't feed a non-sample into the average.
            if (spawnSearchTimer <= 0) awaitingChargeSpawn = false;
            preThrowChargeIds.clear();
            return;
        }

        if (trackedCharge == null || trackedThreatEntity == null) return;

        if (trackedCharge.isRemoved() || trackedThreatEntity.isRemoved() || --trackTimer <= 0) {
            trackedCharge = null;
            trackedThreatEntity = null;
            return;
        }

        if (trackedCharge.getBoundingBox().intersects(trackedThreatEntity.getBoundingBox())) {
            confirmedHitBox = trackedCharge.getBoundingBox();
            confirmedHitTimer = CONFIRMED_HIT_DISPLAY_TICKS;
            trackedCharge = null;
            trackedThreatEntity = null;
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!debugRender.get() || mc.player == null) return;

        if (confirmedHitBox != null) {
            event.renderer.box(confirmedHitBox, confirmedHitColor.get(), confirmedHitColor.get(), shapeMode.get(), 0);
        }

        if (lastTarget == null || lastSolution == null) return;

        Vec3[] path = lastTarget.path();
        for (int i = 0; i < path.length - 1; i++) {
            if (path[i] == null || path[i + 1] == null) break;
            Vec3 a = path[i], b = path[i + 1];
            event.renderer.line(a.x, a.y, a.z, b.x, b.y, b.z, threatColor.get());
        }

        Vec3 eyePos = mc.player.getEyePosition();
        Vec3 prev = eyePos;
        for (int t = 1; t <= lastSolution.ticksToImpact(); t++) {
            Vec3 p = eyePos.add(lastSolution.direction().scale(WIND_SPEED * t));
            event.renderer.line(prev.x, prev.y, prev.z, p.x, p.y, p.z, interceptColor.get());
            prev = p;
        }

        Vec3 intercept = eyePos.add(lastSolution.direction().scale(WIND_SPEED * lastSolution.ticksToImpact()));
        AABB interceptBox = windBoxAt(intercept);
        event.renderer.box(interceptBox, interceptColor.get(), interceptColor.get(), shapeMode.get(), 0);
    }
}
