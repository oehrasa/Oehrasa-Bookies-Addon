package com.AutoBookshelf.addon.modules;
// V4

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.simulator.ProjectileEntitySimulator;
import meteordevelopment.meteorclient.utils.entity.simulator.SimulationStep;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
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

/// Arena M "Active Protection System".
/// Every tick the module scans for hostile projectiles heading towards the player,
/// predicts their real flight path via Meteor's own ProjectileEntitySimulator
/// (same simulator Trajectories.java uses, real block/entity collision, piercing,
/// deflection, and no-gravity handling included)
/// if one is considered dangerous
/// enough, rotates towards a calculated intercept point and throws a wind charge to
/// neutralize it.
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
        .description("Ignore projectiles that are already stuck/landed (near-zero velocity). Mainly matters with intercept-non-target on,")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> prioritySoonest = sgThreats.add(new BoolSetting.Builder()
        .name("priority-soonest")
        .description("Tiebreak equally close threats by which arrives soonest instead of raw distance.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> quickSwap = sgGeneral.add(new BoolSetting.Builder()
        .name("quick-swap")
        .description("Swaps to the wind charge by simulating hotbar key presses instead of inventory clicks. May get flagged by anticheats.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> instantThrow = sgGeneral.add(new BoolSetting.Builder()
        .name("instant-throw")
        .description("Skip the smooth rotation and throw immediately at the calculated angle. Faster reaction but anticheat-sensitive.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logStats = sgGeneral.add(new BoolSetting.Builder()
        .name("log-stats")
        .description("Logs interception accuracy to chat after every resolved throw (hit or miss), split by quick-swap vs normal mode.")
        .defaultValue(true)
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
        .description("Color of the box drawn where the thrown wind charge's real hitbox is confirmed to touch the threat's real hitbox.")
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
    private static final double SOONEST_WINDOW = 1.5;    // threats within this distance band are tiebroken by arrival time
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

    private static final int MAX_AIM_TICKS = 100;   // safety net if the rotate callback never fires

    private Stage stage = Stage.IDLE;
    private int cooldownTimer = 0;
    private int aimTicks = 0;
    private Threat lastTarget;
    private Solution lastSolution;
    private Threat aimingTarget;
    private boolean pendingShotCancelled;
    private int preSlot = -1;
    private int preSwapSlot = -1;   // charge's slot when pre-slotting via quick-swap; -1 = plain select-swap
    private net.minecraft.world.item.Item expectedPreSwapItem = null; // item preSlotWindCharge left in preSwapSlot, for safe reversal

    private final Set<Integer> preThrowChargeIds = new HashSet<>();
    private boolean awaitingChargeSpawn = false;
    private int spawnSearchTimer = 0;
    private Entity trackedCharge;
    private Entity trackedThreatEntity;
    private int trackTimer = 0;
    private AABB confirmedHitBox;
    private int confirmedHitTimer = 0;

    // Reused instead of allocating a fresh simulator per threat every tick.
    // set(entity) fully re-initializes per call.
    private final ProjectileEntitySimulator threatSimulator = new ProjectileEntitySimulator();

    // Latency estimates, split by throw mode since quick-swap and normal
    // swap almost certainly have different real spawn delays.
    private double avgLatencyNormal = 1.0;    // seeded conservatively until measured
    private double avgLatencyQuickSwap = 1.0;
    private boolean latencySeeded = false;
    private int minLatencyNormal = Integer.MAX_VALUE, maxLatencyNormal = Integer.MIN_VALUE;
    private int minLatencyQuickSwap = Integer.MAX_VALUE, maxLatencyQuickSwap = Integer.MIN_VALUE;
    private boolean trackedUsedQuickSwap;
    private int ticksWaitedForSpawn = 0;

    // Accuracy stats. Deliberately not reset in resetState()
    private int attemptsNormal = 0, hitsNormal = 0;
    private int attemptsQuickSwap = 0, hitsQuickSwap = 0;

    private final Set<Integer> neutralizedThreatIds = new HashSet<>();
    private int trackedThreatEntityId;
    private boolean trackedCountsForStats;

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
        restorePreSlot();
        stage = Stage.IDLE;
        cooldownTimer = 0;
        aimTicks = 0;
        lastTarget = null;
        lastSolution = null;
        aimingTarget = null;
        pendingShotCancelled = false;
        preSlot = -1;
        preSwapSlot = -1;
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

        Vec3 eyePos = mc.player.getEyePosition();

        if (stage == Stage.AIMING) {
            if (cooldownTimer > 0) cooldownTimer--;
            tickAiming(eyePos);
            return;
        }

        if (stage == Stage.COOLDOWN) {
            if (cooldownTimer > 0) cooldownTimer--;
            if (cooldownTimer > 0) return;
            stage = Stage.IDLE;
        }

        // 1. Detection
        Threat target = findMostDangerousThreat(eyePos);
        if (target == null) return;

        // 2.-3. Solve, then execute (or abandon through the aiming loop).
        beginAim(target, eyePos);
    }

    /**
     * Called every tick while a smooth rotation is in flight. A2: if the pending
     * threat disappeared or can no longer be intercepted, drop the shot (the
     * rotate callback will notice pendingShotCancelled and not throw).
     */
    private void tickAiming(Vec3 eyePos) {
        // Safety net: if another module superseded our rotation, the callback may
        // never fire. Don't sit in AIMING forever with the charge still slotted.
        if (++aimTicks > MAX_AIM_TICKS) {
            abortAim();
            return;
        }

        if (aimingTarget == null || aimingTarget.entity().isRemoved()) {
            abortAim();
            return;
        }

        Threat fresh = simulateThreat(aimingTarget.entity(), eyePos);
        if (fresh == null || solveIntercept(fresh, eyePos) == null) {
            abortAim();
        }
    }

    /**
     * Drop a pending aim and return to IDLE, restoring the pre-slotted slot.
     */
    private void abortAim() {
        pendingShotCancelled = true;
        aimingTarget = null;
        cooldownTimer = 0;
        stage = Stage.IDLE;
        restorePreSlot();
    }

    private void beginAim(Threat target, Vec3 eyePos) {
        Solution solution = solveIntercept(target, eyePos);
        if (solution == null || pathIntersectsSelf(eyePos, solution.direction())) return;

        if (!findWindCharge().found()) return;
        if (mc.player.getCooldowns().isOnCooldown(Items.WIND_CHARGE.getDefaultInstance())) return;

        lastTarget = target;
        lastSolution = solution;
        aimingTarget = target;
        pendingShotCancelled = false;
        aimTicks = 0;

        // R1: get the wind charge in hand now so the throw, not the swap, is on the
        // critical path after rotation completes.
        preSlotWindCharge();

        float[] rot = toYawPitch(solution.direction());
        stage = Stage.AIMING;

        if (instantThrow.get()) {
            instantAimThrow(rot[0], rot[1], target, solution);
            return;
        }

        Rotations.rotate(rot[0], rot[1], rotationPriority.get(), () -> {
            // Abandoned mid-rotation (tickAiming), a newer aim took over, or the
            // module was turned off.
            if (pendingShotCancelled || aimingTarget != target || mc.player == null || mc.level == null) return;

            if (cooldownTimer > 0
                || mc.player.getCooldowns().isOnCooldown(Items.WIND_CHARGE.getDefaultInstance())) {
                abortAim();
                return;
            }

            // A1: re-solve with the freshest threat path at throw time so the aim is
            // current even after a long rotation.
            Vec3 eye = mc.player.getEyePosition();
            Threat fresh = simulateThreat(target.entity(), eye);
            Solution s = fresh == null ? null : solveIntercept(fresh, eye);
            if (fresh == null || s == null || pathIntersectsSelf(eye, s.direction())) {
                lastTarget = null;
                lastSolution = null;
                abortAim();
                return;
            }

            lastSolution = s;
            beginChargeTracking(target.entity());
            throwWindCharge();
            cooldownTimer = cooldownTicks.get();
            stage = Stage.COOLDOWN;
        });
    }

    /**
     * R3: aim and throw in the same tick, skipping the smooth rotation entirely.
     */
    private void instantAimThrow(float yaw, float pitch, Threat target, Solution solution) {
        float prevYaw = mc.player.getYRot();
        float prevPitch = mc.player.getXRot();

        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.connection.send(new ServerboundMovePlayerPacket.Rot(
            yaw, pitch, mc.player.onGround(), mc.player.horizontalCollision));

        lastSolution = solution;
        beginChargeTracking(target.entity());
        try {
            throwWindCharge();
        } finally {
            // Put the camera back and tell the server, otherwise vanilla thinks the
            // rotation never changed and won't send a corrective look packet.
            mc.player.setYRot(prevYaw);
            mc.player.setXRot(prevPitch);
            mc.player.connection.send(new ServerboundMovePlayerPacket.Rot(
                prevYaw, prevPitch, mc.player.onGround(), mc.player.horizontalCollision));
        }
        cooldownTimer = cooldownTicks.get();
        stage = Stage.COOLDOWN;
    }

    /**
     * Swap the hotbar wind charge into the main hand ahead of the throw.
     */
    private boolean preSlotWindCharge() {
        FindItemResult windCharge = findWindCharge();
        if (!windCharge.found()) return false;
        int selected = mc.player.getInventory().getSelectedSlot();
        if (selected == windCharge.slot()) return false;

        // If a previous pre-slot was never undone, clean it up before arming again
        // so the earlier swap (stack exchange with quick-swap) gets reversed.
        restorePreSlot();
        preSlot = selected;
        if (quickSwap.get()) {
            // InvUtils.quickSwap physically exchanges stacks; remember the target so
            // restorePreSlot can apply the same swap again to reverse it. Also record
            // what item this leaves in preSwapSlot, so restorePreSlot can detect if
            // something else touched that slot before we get back to it.
            preSwapSlot = windCharge.slot();
            expectedPreSwapItem = mc.player.getInventory().getItem(selected).getItem();
            InvUtils.quickSwap().fromId(selected).to(windCharge.slot());
        } else {
            preSwapSlot = -1;
            expectedPreSwapItem = null;
            InvUtils.swap(windCharge.slot(), false);
        }
        return true;
    }

    private void restorePreSlot() {
        if (mc.player != null && preSlot >= 0 && preSlot <= 8) {
            if (preSwapSlot >= 0) {
                net.minecraft.world.item.Item currentInSwapSlot = mc.player.getInventory().getItem(preSwapSlot).getItem();
                if (currentInSwapSlot == expectedPreSwapItem) {
                    // preSwapSlot still holds what we put there, safe to reverse.
                    InvUtils.quickSwap().fromId(preSlot).to(preSwapSlot);
                } else {
                    // Something else changed that slot while we were aiming/throwing.
                    // Reversing blindly would move an unrelated item into the wrong
                    // slot, so don't touch inventory contents, just restore selection.
                    InvUtils.swap(preSlot, false);
                }
            } else {
                InvUtils.swap(preSlot, false);
            }
        }
        preSlot = -1;
        preSwapSlot = -1;
        expectedPreSwapItem = null;
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

            Threat threat = simulateThreat(entity, eyePos);
            if (threat == null) continue;

            if (!interceptNonTargeting.get() && threat.closestDistance() > SAFETY_MARGIN) continue;

            threats.add(threat);
        }

        if (threats.isEmpty()) return null;

        if (prioritySoonest.get()) {
            // A3: only threats whose closest point is within SOONEST_WINDOW of the
            // closest overall count as tied danger; among those, pick the soonest.
            // (A raw band-comparator is non-transitive, so pick via min instead of sort.)
            double minDist = threats.stream().mapToDouble(Threat::closestDistance).min().orElse(0);
            return threats.stream()
                .filter(t -> t.closestDistance() - minDist <= SOONEST_WINDOW)
                .min(Comparator.comparingInt(Threat::impactTick)
                    .thenComparingDouble(Threat::closestDistance))
                .orElse(null);
        } else {
            threats.sort(Comparator
                .comparingDouble(Threat::closestDistance)
                .thenComparingInt(Threat::impactTick));
            return threats.get(0);
        }
    }

    private Threat simulateThreat(Entity entity, Vec3 eyePos) {
        Vec3[] path = simulateThreatPath(entity);
        if (path == null) return null;

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
        return new Threat(entity, path, closestTick, closest);
    }

    private Vec3[] simulateThreatPath(Entity entity) {
        if (!threatSimulator.set(entity)) return null;

        Vec3[] path = new Vec3[DETECTION_TICKS + 1];
        path[0] = new Vec3(threatSimulator.pos.x, threatSimulator.pos.y, threatSimulator.pos.z);

        for (int i = 1; i <= DETECTION_TICKS; i++) {
            SimulationStep step = threatSimulator.tick();
            path[i] = new Vec3(threatSimulator.pos.x, threatSimulator.pos.y, threatSimulator.pos.z);
            if (step.shouldStop) break;
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
        seedLatencyFromPing();
        double avg = quickSwap.get() ? avgLatencyQuickSwap : avgLatencyNormal;
        return (int) Math.round(Math.max(0, Math.min(avg, MAX_LEAD - 1)));
    }

    /**
     * A4: seed the flight clock from the measured ping instead of a blind 1.0 tick.
     */
    private void seedLatencyFromPing() {
        if (latencySeeded) return;
        latencySeeded = true;
        int ping = 0;
        if (mc.getConnection() != null && mc.player != null) {
            PlayerInfo entry = mc.getConnection().getPlayerInfo(mc.player.getUUID());
            if (entry != null) ping = entry.getLatency();
        }
        // Seed as a full round trip (a spawn must come back to this client), 50 ms per tick.
        double seed = Math.min(10.0, Math.max(1.0, ping / 50.0));
        avgLatencyNormal = seed;
        avgLatencyQuickSwap = seed;
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
        if (!windCharge.found()) {
            // The charge vanished mid-aim: undo any pre-slot so the hotbar is ours again.
            restorePreSlot();
            return;
        }

        int selectedSlot = mc.player.getInventory().getSelectedSlot();
        int itemSlot = windCharge.slot();

        // A wind charge already in the main hand (from preSlotWindCharge, or a
        // second stack elsewhere causing findInHotbar to return a different slot
        // than the one we pre-slotted) is still the pre-slotted case
        boolean mainHandIsCharge = mc.player.getMainHandItem().getItem() == Items.WIND_CHARGE;
        if (itemSlot == selectedSlot || (preSlot >= 0 && mainHandIsCharge)) {
            // Pre-slotted (R1): no swap needed, just sling it and restore the old slot.
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            restorePreSlot();
            return;
        }

        if (quickSwap.get()) {
            InvUtils.quickSwap().fromId(selectedSlot).to(itemSlot);
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            InvUtils.quickSwap().fromId(selectedSlot).to(itemSlot);
        } else {
            InvUtils.swap(itemSlot, true);
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
        trackedThreatEntityId = threatEntity.getId();
        trackedCountsForStats = !neutralizedThreatIds.contains(trackedThreatEntityId);
        trackedUsedQuickSwap = quickSwap.get();
        awaitingChargeSpawn = true;
        spawnSearchTimer = CHARGE_SPAWN_SEARCH_TICKS;
        ticksWaitedForSpawn = 0;
        trackedCharge = null;

        if (trackedCountsForStats) {
            if (trackedUsedQuickSwap) attemptsQuickSwap++;
            else attemptsNormal++;
        }
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

                // EMA update: nudge the mode-specific running latency estimate towards this real sample.
                if (trackedUsedQuickSwap) {
                    avgLatencyQuickSwap += (ticksWaitedForSpawn - avgLatencyQuickSwap) * LATENCY_SMOOTHING_ALPHA;
                    minLatencyQuickSwap = Math.min(minLatencyQuickSwap, ticksWaitedForSpawn);
                    maxLatencyQuickSwap = Math.max(maxLatencyQuickSwap, ticksWaitedForSpawn);
                } else {
                    avgLatencyNormal += (ticksWaitedForSpawn - avgLatencyNormal) * LATENCY_SMOOTHING_ALPHA;
                    minLatencyNormal = Math.min(minLatencyNormal, ticksWaitedForSpawn);
                    maxLatencyNormal = Math.max(maxLatencyNormal, ticksWaitedForSpawn);
                }
                break;
            }

            // Gave up without finding it; charge likely never spawned (throw failed).
            // Don't feed a non-sample into the average, but it still counts against accuracy.
            if (spawnSearchTimer <= 0) {
                awaitingChargeSpawn = false;
                logResolution(false);
                trackedThreatEntity = null;
            }
            preThrowChargeIds.clear();
            return;
        }

        if (trackedCharge == null || trackedThreatEntity == null) return;

        if (trackedCharge.isRemoved() || trackedThreatEntity.isRemoved() || --trackTimer <= 0) {
            logResolution(false);
            trackedCharge = null;
            trackedThreatEntity = null;
            return;
        }

        if (trackedCharge.getBoundingBox().intersects(trackedThreatEntity.getBoundingBox())) {
            confirmedHitBox = trackedCharge.getBoundingBox();
            confirmedHitTimer = CONFIRMED_HIT_DISPLAY_TICKS;
            logResolution(true);
            trackedCharge = null;
            trackedThreatEntity = null;
        }
    }

    private void logResolution(boolean hit) {
        if (hit) neutralizedThreatIds.add(trackedThreatEntityId);

        // Mop-up throw against an already-neutralized target
        // just not a data point about prediction accuracy. Don't log or count it.
        if (!trackedCountsForStats) return;

        if (hit) {
            if (trackedUsedQuickSwap) hitsQuickSwap++;
            else hitsNormal++;
        }

        if (!logStats.get()) return;

        ChatUtils.info(String.format(
            "[Arena-M] %s (%s) Normal: %d/%d (%.0f pct) lat avg=%.2f [%d-%d] | QuickSwap: %d/%d (%.0f pct) lat avg=%.2f [%d-%d]",
            hit ? "HIT" : "MISS",
            trackedUsedQuickSwap ? "quick-swap" : "normal",
            hitsNormal, attemptsNormal, pct(hitsNormal, attemptsNormal), avgLatencyNormal,
            safeMin(minLatencyNormal), safeMax(maxLatencyNormal),
            hitsQuickSwap, attemptsQuickSwap, pct(hitsQuickSwap, attemptsQuickSwap), avgLatencyQuickSwap,
            safeMin(minLatencyQuickSwap), safeMax(maxLatencyQuickSwap)
        ));
    }

    private double pct(int hits, int attempts) {
        return attempts == 0 ? 0.0 : (100.0 * hits / attempts);
    }

    private int safeMin(int v) {
        return v == Integer.MAX_VALUE ? 0 : v;
    }

    private int safeMax(int v) {
        return v == Integer.MIN_VALUE ? 0 : v;
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
