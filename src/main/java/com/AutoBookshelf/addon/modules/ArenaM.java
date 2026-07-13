package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.utils.ProjectilePhysics;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.ProjectileEntityAccessor;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.*;
import net.minecraft.entity.projectile.thrown.*;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Arena M “Active Protection System”.
 * Every tick the module scans for hostile projectiles heading towards the player,
 * predicts their real flight path (real gravity/drag applied via ProjectilePhysics,
 * the same physics utility TrajectoryPlus uses and, if one is considered dangerous enough,
 * rotates towards a calculated intercept point
 * and throws a wind charge to try and neutralize it.
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
        .name("throwables")
        .description("Intercept snowballs, eggs, ender pearls and experience bottles.")
        .defaultValue(true)
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
        .name("intercept-non-target")
        .description("Also consider incoming threat that aren't actually on course to hit you (inacurate like Pantsir).")
        .defaultValue(false)
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
        .defaultValue(false)
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
    private static final int MAX_DELAY = 10;             // max ticks for rotation before throwing
    private static final int MAX_LEAD = 30;              // max ticks the wind charge is simulated for
    private static final int DETECTION_TICKS = MAX_DELAY + MAX_LEAD; // full simulation horizon
    private static final double MIN_THREAT_SPEED_SQ = 0.0025; // below this, treat as landed/stuck

    private enum Stage {
        IDLE,       // scanning for threats
        AIMING,     // rotation in progress, waiting for callback
        COOLDOWN    // waiting after a shot
    }

    private record Threat(Entity entity, Vec3d[] path, int impactTick, double closestDistance) {
    }

    private record Solution(int delay, Vec3d direction, int ticksToImpact) {
    }

    private Stage stage = Stage.IDLE;
    private int cooldownTimer = 0;
    private Threat lastTarget;
    private Solution lastSolution;

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
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (stage == Stage.COOLDOWN) {
            if (cooldownTimer > 0) {
                cooldownTimer--;
                return;
            }
            stage = Stage.IDLE;
        }

        if (stage == Stage.AIMING) return;

        Vec3d eyePos = mc.player.getEyePos();

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
        if (mc.player.getItemCooldownManager().isCoolingDown(Items.WIND_CHARGE.getDefaultStack())) return;

        lastTarget = target;
        lastSolution = solution;

        // 3. Execute: rotate, then throw via callback
        float[] rot = toYawPitch(solution.direction());
        stage = Stage.AIMING;

        Rotations.rotate(rot[0], rot[1], rotationPriority.get(), () -> {
            if (mc.player != null) throwWindCharge();
            cooldownTimer = cooldownTicks.get();
            stage = Stage.COOLDOWN;
        });
    }

    private Threat findMostDangerousThreat(Vec3d eyePos) {
        List<Threat> threats = new ArrayList<>();
        double rangeSq = maxRange.get() * (double) maxRange.get();

        for (Entity entity : mc.world.getEntities()) {
            if (!isThreat(entity)) continue;
            if (!interceptOwnProjectiles.get() && isOwnedByPlayer(entity)) continue;

            // Filter out projectiles that are already stuck/landed. This mainly matters
            // with intercept-non-target on, since that setting bypasses the
            // moving-towards-you check below, which would otherwise catch these.
            if (ignoreLanded.get() && entity.getVelocity().lengthSquared() < MIN_THREAT_SPEED_SQ) continue;

            Vec3d pos = entity.getPos();
            if (pos.squaredDistanceTo(eyePos) > rangeSq) continue;

            if (!interceptNonTargeting.get()) {
                Vec3d fromPlayer = pos.subtract(eyePos);
                if (entity.getVelocity().dotProduct(fromPlayer) >= 0) continue;
            }

            Vec3d[] path = simulateThreatPath(entity);
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

    private Vec3d[] simulateThreatPath(Entity entity) {
        ProjectilePhysics.Result result = ProjectilePhysics.simulate(
            entity, entity.getPos(), entity.getVelocity(), DETECTION_TICKS
        );

        List<Vec3d> simulated = result.path();
        Vec3d[] path = new Vec3d[DETECTION_TICKS + 1];
        for (int i = 0; i < simulated.size() && i < path.length; i++) {
            path[i] = simulated.get(i);
        }
        return path;
    }

    private boolean isThreat(Entity entity) {
        if (entity instanceof ArrowEntity || entity instanceof SpectralArrowEntity || entity instanceof TridentEntity)
            return interceptArrows.get();
        if (entity instanceof FireballEntity || entity instanceof SmallFireballEntity
            || entity instanceof DragonFireballEntity || entity instanceof WitherSkullEntity)
            return interceptFireballs.get();
        if (entity instanceof SnowballEntity || entity instanceof EggEntity
            || entity instanceof EnderPearlEntity || entity instanceof ExperienceBottleEntity)
            return interceptThrowables.get();
        if (entity instanceof PotionEntity)
            return interceptPotions.get();
        if (entity instanceof WindChargeEntity)
            return interceptWindCharges.get();
        return false;
    }

    private boolean isOwnedByPlayer(Entity entity) {
        if (!(entity instanceof ProjectileEntity)) return false;
        UUID owner = ((ProjectileEntityAccessor) entity).getOwnerUuid();
        return owner != null && owner.equals(mc.player.getUuid());
    }

    // Interception solver
    // Unified lead/impact-tick variable: the direction we aim in is derived from
    // path[delay+tau], and that same tau is what we test for collision, so the
    // returned solution's direction always corresponds to the tick the collision
    // was actually found at. (Previously "lead" picked the aim point and an
    // independent "t" tested collision, so a fixed ray aimed far ahead could clip
    // the threat's curved path at some unrelated earlier/later tick the source
    // of the early/late "desync" throws.)
    private Solution solveIntercept(Threat target, Vec3d eyePos) {
        Box baseBox = target.entity().getBoundingBox();
        Vec3d basePos = target.entity().getPos();

        for (int delay = 0; delay <= MAX_DELAY; delay++) {
            if (target.path()[delay] == null) continue;

            for (int tau = 1; tau <= MAX_LEAD; tau++) {
                int aimIndex = delay + tau;
                if (aimIndex >= target.path().length || target.path()[aimIndex] == null) break; // path ends here; larger tau can't help either

                Vec3d aimPoint = target.path()[aimIndex];
                Vec3d direction = aimPoint.subtract(eyePos).normalize();
                Vec3d windPos = eyePos.add(direction.multiply(WIND_SPEED * tau));

                Vec3d threatPrev = target.path()[aimIndex - 1] != null ? target.path()[aimIndex - 1] : aimPoint;
                Vec3d windPrev = eyePos.add(direction.multiply(WIND_SPEED * (tau - 1)));

                Box threatBoxPrev = baseBox.offset(threatPrev.subtract(basePos));
                Box threatBoxCurr = baseBox.offset(aimPoint.subtract(basePos));

                Box windBoxPrev = windBoxAt(windPrev);
                Box windBoxCurr = windBoxAt(windPos);

                if (union(threatBoxPrev, threatBoxCurr).intersects(union(windBoxPrev, windBoxCurr))) {
                    return new Solution(delay, direction, tau);
                }
            }
        }
        return null;
    }

    private Box windBoxAt(Vec3d pos) {
        return new Box(
            pos.x - WIND_HALF_SIZE, pos.y - WIND_HALF_SIZE, pos.z - WIND_HALF_SIZE,
            pos.x + WIND_HALF_SIZE, pos.y + WIND_HALF_SIZE, pos.z + WIND_HALF_SIZE
        );
    }

    private Box union(Box a, Box b) {
        return new Box(
            Math.min(a.minX, b.minX), Math.min(a.minY, b.minY), Math.min(a.minZ, b.minZ),
            Math.max(a.maxX, b.maxX), Math.max(a.maxY, b.maxY), Math.max(a.maxZ, b.maxZ)
        );
    }

    private boolean pathIntersectsSelf(Vec3d eyePos, Vec3d direction) {
        Box selfBox = mc.player.getBoundingBox().expand(0.1);
        for (int t = 1; t <= 3; t++) {
            Vec3d windPos = eyePos.add(direction.multiply(WIND_SPEED * t));
            Box windBox = windBoxAt(windPos);
            if (windBox.intersects(selfBox)) return true;
        }
        return false;
    }

    private float[] toYawPitch(Vec3d direction) {
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

        int selectedSlot = mc.player.getInventory().selectedSlot;
        int itemSlot = windCharge.slot();

        if (quickSwap.get()) {
            InvUtils.quickSwap().fromId(selectedSlot).to(itemSlot);
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            InvUtils.quickSwap().fromId(selectedSlot).to(itemSlot);
        } else {
            InvUtils.swap(itemSlot, false);
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            InvUtils.swapBack();
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!debugRender.get() || lastTarget == null || lastSolution == null || mc.player == null) return;

        Vec3d[] path = lastTarget.path();
        for (int i = 0; i < path.length - 1; i++) {
            if (path[i] == null || path[i + 1] == null) break;
            Vec3d a = path[i], b = path[i + 1];
            event.renderer.line(a.x, a.y, a.z, b.x, b.y, b.z, threatColor.get());
        }

        Vec3d eyePos = mc.player.getEyePos();
        Vec3d prev = eyePos;
        for (int t = 1; t <= lastSolution.ticksToImpact(); t++) {
            Vec3d p = eyePos.add(lastSolution.direction().multiply(WIND_SPEED * t));
            event.renderer.line(prev.x, prev.y, prev.z, p.x, p.y, p.z, interceptColor.get());
            prev = p;
        }

        Vec3d intercept = eyePos.add(lastSolution.direction().multiply(WIND_SPEED * lastSolution.ticksToImpact()));
        Box interceptBox = windBoxAt(intercept);
        event.renderer.box(interceptBox, interceptColor.get(), interceptColor.get(), shapeMode.get(), 0);
    }
}
