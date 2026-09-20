package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.utils.DistanceUtil;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PzH2000 extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgArtillery = settings.createGroup("Artillery");

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("The maximum range the entity can be to aim at it.")
        .defaultValue(20)
        .range(0, 100)
        .sliderMax(100)
        .build()
    );

    private final Setting<Set<EntityType<?>>> entities = sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("entities")
        .description("Entities to attack.")
        .onlyAttackable()
        .build()
    );

    private final Setting<SortPriority> priority = sgGeneral.add(new EnumSetting.Builder<SortPriority>()
        .name("priority")
        .description("What type of entities to target.")
        .defaultValue(SortPriority.LowestHealth)
        .build()
    );

    private final Setting<Boolean> babies = sgGeneral.add(new BoolSetting.Builder()
        .name("babies")
        .description("Whether or not to attack baby variants of the entity.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> nametagged = sgGeneral.add(new BoolSetting.Builder()
        .name("nametagged")
        .description("Whether or not to attack mobs with a name tag.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> pauseOnCombat = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-on-combat")
        .description("Freezes Baritone temporarily until you released the bow.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> artilleryMode = sgArtillery.add(new BoolSetting.Builder()
        .name("artillery-mode")
        .description("Allow lofted trajectories that can hit targets behind obstructions or beyond flat range, instead of only shallow direct shots.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> angleStep = sgArtillery.add(new DoubleSetting.Builder()
        .name("angle-step")
        .description("Degree increment used when scanning candidate pitch angles, both for the local refinement window and the brute-force fallback.")
        .defaultValue(1.0)
        .range(0.1, 5.0)
        .sliderRange(0.1, 5.0)
        .build()
    );

    private final Setting<Integer> simulationTicks = sgArtillery.add(new IntSetting.Builder()
        .name("simulation-ticks")
        .description("Maximum ticks to simulate per candidate arc.")
        .defaultValue(100)
        .min(20)
        .sliderRange(20, 200)
        .build()
    );

    private final Setting<Double> hitTolerance = sgArtillery.add(new DoubleSetting.Builder()
        .name("hit-tolerance")
        .description("Maximum acceptable miss distance (blocks) for a candidate arc to be considered a hit.")
        .defaultValue(1.0)
        .range(0.2, 3.0)
        .sliderRange(0.2, 3.0)
        .build()
    );

    private final Setting<Boolean> renderPrediction = sgArtillery.add(new BoolSetting.Builder()
        .name("render-prediction")
        .description("Draws the computed arc and target highlight so you can see what's being aimed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> predictionColor = sgArtillery.add(new ColorSetting.Builder()
        .name("prediction-color")
        .description("Color of the predicted arc line and target highlight.")
        .visible(renderPrediction::get)
        .defaultValue(new SettingColor(255, 165, 0, 220))
        .build()
    );

    private final Setting<Boolean> debugNoSolution = sgArtillery.add(new BoolSetting.Builder()
        .name("debug-no-solution")
        .description("Print a chat message when no arc solution can be found.")
        .defaultValue(true)
        .build()
    );

    private final SettingGroup sgAutoFire = settings.createGroup("Auto Fire");

    private final Setting<Boolean> autoFire = sgAutoFire.add(new BoolSetting.Builder()
        .name("auto-fire")
        .description("Automatically draws and releases the bow the instant the CURRENT charge produces a valid, confirmed solution, instead of requiring you to hold right-click.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> minChargeTicks = sgAutoFire.add(new IntSetting.Builder()
        .name("min-charge-ticks")
        .description("Minimum draw time before a shot can be released, even for very close targets.")
        .visible(autoFire::get)
        .defaultValue(3)
        .min(0)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Integer> maxChargeTicks = sgAutoFire.add(new IntSetting.Builder()
        .name("max-charge-ticks")
        .description("Maximum draw time before releasing anyway, even if no confirmed solution was found at that charge.")
        .visible(autoFire::get)
        .defaultValue(20)
        .min(5)
        .sliderRange(5, 20)
        .build()
    );

    private final Setting<Double> powerMargin = sgAutoFire.add(new DoubleSetting.Builder()
        .name("power-margin")
        .description("Multiplier applied over the analytic minimum speed required to reach the target. 1.0 is a fragile knife-edge lofted shot with only one possible angle; going above 1.0 unlocks a flatter, faster, more predictable direct-arc option and gives the drag-correction step room to work. The shot is only accepted once the CURRENT draw's speed clears this margin (or max charge is reached) — higher = flatter/faster shots, but costs more charge time.")
        .defaultValue(1.15)
        .range(1.0, 1.5)
        .sliderRange(1.0, 1.5)
        .build()
    );

    private final SettingGroup sgSelfCorrect = settings.createGroup("Self-Correction");

    private final Setting<Boolean> selfCorrect = sgSelfCorrect.add(new BoolSetting.Builder()
        .name("self-correct")
        .description("Tracks real fired arrows and nudges future aim based on observed bias. Only corrects a consistent offset (e.g. the wiki-documented rightward drift) — cannot reduce Minecraft's inherent per-shot randomness.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> learningRate = sgSelfCorrect.add(new DoubleSetting.Builder()
        .name("learning-rate")
        .description("How strongly each observed shot nudges the running correction. Lower is slower but more stable.")
        .visible(selfCorrect::get)
        .defaultValue(0.15)
        .range(0.01, 1.0)
        .sliderRange(0.01, 1.0)
        .build()
    );

    private final Setting<Double> maxCorrection = sgSelfCorrect.add(new DoubleSetting.Builder()
        .name("max-correction")
        .description("Cap on the accumulated correction, in degrees, so a run of unlucky shots can't run away the aim.")
        .visible(selfCorrect::get)
        .defaultValue(5.0)
        .range(0.5, 15.0)
        .sliderRange(0.5, 15.0)
        .build()
    );

    private final SettingGroup sgBracketing = settings.createGroup("Bracketing");

    private final Setting<Boolean> bracketing = sgBracketing.add(new BoolSetting.Builder()
        .name("bracketing")
        .description("Between shots at the same target, walk the aim further in the direction of the last correction as long as each shot is landing closer than the one before — and drop the walked-in trim the instant a shot lands worse, going back to a fresh calculation. This is on top of, and faster-adapting than, the slow global bias above. Since bow shots have inherent random spread, an 'improvement' isn't always a real signal — that's why the trim is discarded on any regression rather than trusted indefinitely.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> bracketRate = sgBracketing.add(new DoubleSetting.Builder()
        .name("bracket-rate")
        .description("How aggressively the trim walks toward the correction while shots keep improving. Higher than the global learning rate on purpose — meant to converge fast within a single engagement, not accumulate slowly across many.")
        .visible(bracketing::get)
        .defaultValue(0.5)
        .range(0.05, 1.0)
        .sliderRange(0.05, 1.0)
        .build()
    );

    private final Setting<Double> maxBracketTrim = sgBracketing.add(new DoubleSetting.Builder()
        .name("max-bracket-trim")
        .description("Cap on the walked-in trim, in degrees, separate from the global max-correction cap.")
        .visible(bracketing::get)
        .defaultValue(3.0)
        .range(0.5, 10.0)
        .sliderRange(0.5, 10.0)
        .build()
    );

    private final Setting<Integer> noSolutionAbortTicks = sgArtillery.add(new IntSetting.Builder()
        .name("no-solution-abort-ticks")
        .description("If NO charge level up to max-charge-ticks can reach the target for this many consecutive ticks, cancel the draw instead of releasing it blind. Prevents wasting arrows on geometrically impossible shots, e.g. a wall directly in front that no angle can clear. This does not trigger just because the current charge isn't high enough yet — that's normal mid-draw.")
        .defaultValue(3)
        .min(1)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Integer> blockedRecheckTicks = sgArtillery.add(new IntSetting.Builder()
        .name("blocked-recheck-ticks")
        .description("Once a target is confirmed unreachable, wait this many ticks before spending another full search on it, instead of re-running the failed search every single tick.")
        .defaultValue(20)
        .min(5)
        .sliderRange(5, 100)
        .build()
    );

    private boolean wasPathing;
    private Entity target;

    private Float cachedYaw;
    private Float cachedPitch;
    private List<Vec3> cachedPath = new ArrayList<>();
    private boolean cachedSolutionFound;

    // Self-correction: track real fired arrows to measure actual vs. predicted
    // landing point, and accumulate a small running bias correction from it.
    private final Set<Integer> knownArrowIds = new HashSet<>();
    private boolean wasUsingItemLastTick = false;
    private Entity pendingTargetRef;
    private Vec3 pendingTargetPos;
    private Vec3 pendingShooterPos;
    private Entity trackedArrow;
    private int pendingWaitTicks;
    private double yawBias = 0;
    private double pitchBias = 0;

    // Blocked-target safety: once a target is confirmed unreachable at every
    // achievable charge level (no arc clears the obstruction, e.g. a wall
    // directly in front), stop drawing and stop re-searching every tick
    // instead of eventually releasing blind.
    private int consecutiveNoSolutionTicks = 0;
    private Integer blockedTargetId = null;
    private int blockedRecheckCooldown = 0;

    // Feasibility cache: the per-charge sweep below is the most expensive work in
    // the tick, so its result is reused for a short window instead of re-running
    // it for every target every tick. Guarded by the same conditions (LOS + not on
    // cooldown) that the live sweep uses, so a changed LOS or a blocked-target
    // cooldown bypasses the cache and forces a fresh solve.
    private int feasibilityCacheTargetId = -1;
    private int feasibilityCacheTicksLeft = 0;
    private boolean feasibleCached = false;
    private static final int FEASIBILITY_COARSE_SAMPLES = 10;
    private static final int TARGET_FEASIBILITY_CACHE_TICKS = 20;

    // Bracketing: a faster, per-engagement trim layered on top of the slow
    // global bias. Walked further in the corrective direction while shots keep
    // improving; dropped entirely the instant one doesn't.
    private double bracketTrimYaw = 0;
    private double bracketTrimPitch = 0;
    private double lastShotErrorMagnitude = Double.MAX_VALUE;
    private Integer lastShotTargetId = null;

    // Bow/arrow physics constants, confirmed against the Minecraft wiki's Arrow
    // page: velocity is multiplied by 0.99 each tick (air drag), and 0.05 is
    // subtracted from the y-velocity each tick (gravity). These must exactly
    // match what simulateArc() uses below, since that's the only place
    // trajectories are solved. The same GRAVITY constant also drives the
    // analytic (drag-free) closed-form solver used to pick charge/pitch before
    // simulation ever runs, drag is treated there as a small correction to be
    // refined out locally, not modeled in closed form.
    private static final double DRAG = 0.99;
    private static final double GRAVITY = 0.05;

    public PzH2000() {
        super(Addon.CATEGORY2, "PzH-2000", "It can fire shells at a high velocity aided by a laser rangefinder");
    }

    @Override
    public void onDeactivate() {
        target = null;
        wasPathing = false;
        cachedYaw = null;
        cachedPitch = null;
        cachedPath = new ArrayList<>();
        cachedSolutionFound = false;
        pendingTargetRef = null;
        trackedArrow = null;
        wasUsingItemLastTick = false;
        consecutiveNoSolutionTicks = 0;
        blockedTargetId = null;
        blockedRecheckCooldown = 0;
        bracketTrimYaw = 0;
        bracketTrimPitch = 0;
        lastShotErrorMagnitude = Double.MAX_VALUE;
        lastShotTargetId = null;
        // Note: yawBias/pitchBias intentionally not reset, they represent a
        // learned correction for a consistent aiming offset, not per-session state.
        if (mc.player != null) mc.options.keyUse.setDown(false);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!PlayerUtils.isAlive() || !itemInHand()) return;
        if (!mc.player.getAbilities().instabuild && !InvUtils.find(itemStack -> itemStack.getItem() instanceof ArrowItem).found()) return;

        target = TargetUtils.get(entity -> {
            if (entity == mc.player || entity == mc.getCameraEntity()) return false;
            if ((entity instanceof LivingEntity && ((LivingEntity) entity).isDeadOrDying()) || !entity.isAlive()) return false;
            if (!PlayerUtils.isWithin(entity, range.get())) return false;
            if (!entities.get().contains(entity.getType())) return false;
            if (!nametagged.get() && entity.hasCustomName()) return false;
            if (!artilleryMode.get() && !PlayerUtils.canSeeEntity(entity)) return false;
            if (entity instanceof Player) {
                if (((Player) entity).isCreative()) return false;
                if (!Friends.get().shouldAttack((Player) entity)) return false;
            }
            return !(entity instanceof Animal) || babies.get() || !((Animal) entity).isBaby();
        }, priority.get());

        if (target == null) {
            if (wasPathing) {
                PathManagers.get().resume();
                wasPathing = false;
            }
            return;
        }

        boolean drawing = mc.options.keyUse.isDown() || (autoFire.get() && mc.player.isUsingItem());
        if (drawing && itemInHand()) {
            if (pauseOnCombat.get() && PathManagers.get().isPathing() && !wasPathing) {
                PathManagers.get().pause();
                wasPathing = true;
            }
            applyRotation();
            if (renderPrediction.get()) renderPrediction(event);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null) return;

        boolean isUsingNow = mc.player.isUsingItem();

        if (target == null || !itemInHand()) {
            if (autoFire.get() && isUsingNow && itemInHand()) {
                mc.options.keyUse.setDown(false);
                mc.gameMode.releaseUsingItem(mc.player);
            }
            wasUsingItemLastTick = false;
            return;
        }

        Vec3 shooterPos = new Vec3(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ());
        Vec3 targetPos = target.position().add(0, target.getBbHeight() / 2.0, 0);

        // Release/pending-shot detection runs first and reads cachedSolutionFound as it stood
        // at the end of the previous tick, exactly what the arrow that just left the bow
        // (whether auto-fired or manually released) was actually aimed with. This has to happen
        // before anything below overwrites cachedSolutionFound for the new tick, and before any
        // early return, or a purely-manual release (which drops mc.options.keyUse.isDown()
        // the same tick isUsingItem() goes false) would never be observed.
        if (wasUsingItemLastTick && !isUsingNow && cachedSolutionFound) {
            pendingTargetRef = target;
            pendingTargetPos = targetPos;
            pendingShooterPos = shooterPos;
            trackedArrow = null;
            pendingWaitTicks = 0;
        }
        wasUsingItemLastTick = isUsingNow;

        boolean manualHold = mc.options.keyUse.isDown();
        boolean wantsToDraw = autoFire.get() || manualHold;

        if (!wantsToDraw) {
            cachedSolutionFound = false;
            if (selfCorrect.get() && pendingTargetRef != null) processPendingShot();
            return;
        }

        if (autoFire.get() && !isUsingNow) {
            mc.options.keyUse.setDown(true);
        }

        double relativeX = targetPos.x - shooterPos.x;
        double relativeZ = targetPos.z - shooterPos.z;
        double yaw = Math.toDegrees(Math.atan2(-relativeX, relativeZ));
        boolean losClear = hasLineOfSight(shooterPos, targetPos);
        boolean losOk = artilleryMode.get() || losClear;

        double horizontalDist = DistanceUtil.distanceXZ(shooterPos.x, shooterPos.z, targetPos.x, targetPos.z);
        double heightDiff = targetPos.y - shooterPos.y;

        boolean sameBlockedTarget = blockedTargetId != null && blockedTargetId == target.getId();
        boolean onCooldown = sameBlockedTarget && blockedRecheckCooldown > 0;

        // Feasibility lookahead: is there ANY charge level up to max-charge-ticks that can hit
        // this target at all? This exists purely to tell "genuinely unreachable" (a wall
        // blocking ahead) apart from "just needs more charge" for the abort logic below, and to give
        // a live preview of the incoming arc while still drawing. It is NEVER used to decide what
        // to actually aim or fire at, that's the live solve further down.
        boolean feasible;
        if (feasibilityCacheTargetId == target.getId()
            && feasibilityCacheTicksLeft > 0
            && !onCooldown
            && losOk) {
            feasible = feasibleCached;
            feasibilityCacheTicksLeft--;
        } else {
            feasible = false;
            if (!onCooldown && losOk) {
                int startTicks = clampTicks(ticksForSpeed(computeMinimumSpeed(horizontalDist, heightDiff) * powerMargin.get()));
                // Sample a coarse grid of charge levels (at most 10) instead of every single
                // tick, then always cover the maximum charge explicitly so max-charge shots
                // are never missed by grid rounding.
                int span = maxChargeTicks.get() - startTicks + 1;
                int samples = Math.min(FEASIBILITY_COARSE_SAMPLES, span);
                int lastTicks = -1;
                for (int s = 0; s < samples && !feasible; s++) {
                    int ticks = startTicks + (int) Math.round((span - 1) * (double) s / (samples - 1));
                    if (ticks == lastTicks) continue;
                    lastTicks = ticks;

                    double previewSpeed = 3.0 * BowItem.getPowerForTime(ticks);
                    List<Vec3> previewPath = new ArrayList<>();
                    if (findBallisticPitch(shooterPos, targetPos, yaw, previewSpeed, horizontalDist, heightDiff, previewPath, artilleryMode.get()) != null) {
                        feasible = true;
                        if (!cachedSolutionFound) cachedPath = previewPath;
                    }
                }
                if (!feasible && maxChargeTicks.get() != lastTicks) {
                    double previewSpeed = 3.0 * BowItem.getPowerForTime(maxChargeTicks.get());
                    List<Vec3> previewPath = new ArrayList<>();
                    if (findBallisticPitch(shooterPos, targetPos, yaw, previewSpeed, horizontalDist, heightDiff, previewPath, artilleryMode.get()) != null) {
                        feasible = true;
                        if (!cachedSolutionFound) cachedPath = previewPath;
                    }
                }
            }
            feasibilityCacheTargetId = target.getId();
            feasibilityCacheTicksLeft = TARGET_FEASIBILITY_CACHE_TICKS;
            feasibleCached = feasible;
        }

        // Live aim: solved for the ACTUAL current draw (mc.player.getTicksUsingItem()),
        int currentUseTicks = isUsingNow ? mc.player.getTicksUsingItem() : 0;
        double currentSpeed = 3.0 * BowItem.getPowerForTime(currentUseTicks);
        double requiredSpeed = computeMinimumSpeed(horizontalDist, heightDiff) * powerMargin.get();

        Float currentPitch = null;
        List<Vec3> currentPath = new ArrayList<>();
        if (!onCooldown && losOk && currentUseTicks >= minChargeTicks.get()) {
            currentPitch = findBallisticPitch(shooterPos, targetPos, yaw, currentSpeed, horizontalDist, heightDiff, currentPath, artilleryMode.get());
        }

        // Don't accept the bare knife-edge minimum-speed solution the instant it appears,
        // wait for the margin (or max charge) so there's a flatter, more robust arc to refine
        // the drag correction around. See powerMargin's description.
        boolean marginSatisfied = currentSpeed >= requiredSpeed || currentUseTicks >= maxChargeTicks.get();
        boolean ready = currentPitch != null && marginSatisfied;

        cachedYaw = (float) yaw;
        if (currentPitch != null) {
            cachedPitch = currentPitch;
            cachedPath = currentPath;
        } else if (!feasible) {
            // No live solution and nothing achievable is coming either
            cachedPitch = (float) Rotations.getPitch(target);
        }
        // else: keep showing the feasibility preview set above while charge builds toward it.
        cachedSolutionFound = ready;

        if (onCooldown) {
            blockedRecheckCooldown--;
        } else if (feasible) {
            consecutiveNoSolutionTicks = 0;
            blockedTargetId = null;
            blockedRecheckCooldown = 0;
        } else if (currentUseTicks >= minChargeTicks.get()) {
            consecutiveNoSolutionTicks = sameBlockedTarget ? consecutiveNoSolutionTicks + 1 : 1;
            if (consecutiveNoSolutionTicks >= noSolutionAbortTicks.get()) {
                if (debugNoSolution.get() && blockedTargetId == null) {
                    info("No shot solution found! target appears unreachable, aborting draw.");
                }
                blockedTargetId = target.getId();
                blockedRecheckCooldown = blockedRecheckTicks.get();
                abortDraw();
            }
        }

        if (autoFire.get() && isUsingNow && ready) {
            mc.gameMode.releaseUsingItem(mc.player);
            mc.options.keyUse.setDown(false);
        }

        if (selfCorrect.get() && pendingTargetRef != null) {
            processPendingShot();
        }
    }

    private void abortDraw() {
        if (mc.player == null) return;
        mc.options.keyUse.setDown(false);
        if (!mc.player.isUsingItem()) return;

        int bowSlot = mc.player.getInventory().getSelectedSlot();
        int dummySlot = (bowSlot + 1) % 9;
        InvUtils.swap(dummySlot, false);
        InvUtils.swap(bowSlot, false);
    }

    private int clampTicks(int ticks) {
        return Math.max(minChargeTicks.get(), Math.min(maxChargeTicks.get(), ticks));
    }

    /**
     * Minimum launch speed (blocks/tick, drag ignored) needed to reach a point
     * at horizontal distance d and height difference dy under constant gravity
     * g — the classic "safety parabola" envelope formula:
     * v_min = sqrt(g * (dy + sqrt(d^2 + dy^2)))
     * At exactly v_min there is only one possible launch angle (the two ballistic
     * roots collapse to one), which is why we never target v_min directly —
     * see powerMargin's description.
     */
    private double computeMinimumSpeed(double d, double dy) {
        return Math.sqrt(GRAVITY * (dy + Math.sqrt(d * d + dy * dy)));
    }

    /**
     * Inverts the bow's charge curve to find the cheapest charge (in ticks,
     * clamped to [minChargeTicks, maxChargeTicks]) whose launch speed meets
     * requiredSpeed. Pure arithmetic over ~20 candidates — no simulation —
     * used only to seed the feasibility lookahead's starting point.
     */
    private int ticksForSpeed(double requiredSpeed) {
        for (int ticks = minChargeTicks.get(); ticks <= maxChargeTicks.get(); ticks++) {
            double speed = 3.0 * BowItem.getPowerForTime(ticks);
            if (speed >= requiredSpeed) return ticks;
        }
        return maxChargeTicks.get();
    }

    /**
     * Processes a real fired arrow to learn a running aim bias correction. See
     * recordShotOutcome for what it can and can't correct.
     */
    private void processPendingShot() {
        pendingWaitTicks++;

        if (trackedArrow == null) {
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e instanceof Arrow arrow
                    && !knownArrowIds.contains(arrow.getId())
                    && arrow.getOwner() == mc.player) {
                    knownArrowIds.add(arrow.getId());
                    trackedArrow = arrow;
                    break;
                }
            }
            if (trackedArrow == null && pendingWaitTicks > 5) {
                pendingTargetRef = null; // never actually fired an arrow, give up
            }
            return;
        }

        if (trackedArrow.isRemoved() || pendingWaitTicks > simulationTicks.get() + 10) {
            recordShotOutcome(trackedArrow.position());
            trackedArrow = null;
            pendingTargetRef = null;
        }
    }

    /**
     * Compares the tracked arrow's actual final position against where we
     * predicted the target to be, and nudges the running yaw/pitch bias toward
     * canceling out any consistent offset — such as the wiki-documented default
     * rightward drift caused by the bow's model not being exactly on the eye
     * line. This cannot reduce Minecraft's inherent per-shot Gaussian velocity
     * randomness (bows have inaccuracy=1); that's independent noise each shot
     * with nothing consistent to learn from — this only removes bias, not
     * variance.
     */
    private void recordShotOutcome(Vec3 actualFinalPos) {
        if (pendingTargetPos == null || pendingShooterPos == null) return;

        Vec3 toTarget = pendingTargetPos.subtract(pendingShooterPos);
        Vec3 toActual = actualFinalPos.subtract(pendingShooterPos);
        double dist = toTarget.length();
        if (dist < 1e-3) return;

        Vec3 forward = toTarget.normalize();
        Vec3 worldUp = new Vec3(0, 1, 0);
        Vec3 right = forward.cross(worldUp).normalize();
        Vec3 up = right.cross(forward).normalize();

        Vec3 error = toActual.subtract(forward.scale(toActual.dot(forward)));
        double lateralError = error.dot(right);
        double verticalError = error.dot(up);

        double yawErrorDeg = Math.toDegrees(Math.atan2(lateralError, dist));
        double pitchErrorDeg = Math.toDegrees(Math.atan2(verticalError, dist));

        // Arrow landed right of intended -> aim further left next time, hence "-=".
        yawBias -= learningRate.get() * yawErrorDeg;
        pitchBias += learningRate.get() * pitchErrorDeg;

        double cap = maxCorrection.get();
        yawBias = Math.max(-cap, Math.min(cap, yawBias));
        pitchBias = Math.max(-cap, Math.min(cap, pitchBias));

        if (bracketing.get()) {
            int thisTargetId = pendingTargetRef != null ? pendingTargetRef.getId() : -1;
            double errorMagnitude = Math.hypot(yawErrorDeg, pitchErrorDeg);

            boolean sameEngagement = lastShotTargetId != null && lastShotTargetId == thisTargetId;
            boolean improved = sameEngagement && errorMagnitude < lastShotErrorMagnitude;

            if (improved) {
                // Walking in successfully — keep nudging further the same way,
                // faster than the slow global bias, same sign convention as above.
                bracketTrimYaw -= bracketRate.get() * yawErrorDeg;
                bracketTrimPitch += bracketRate.get() * pitchErrorDeg;

                double trimCap = maxBracketTrim.get();
                bracketTrimYaw = Math.max(-trimCap, Math.min(trimCap, bracketTrimYaw));
                bracketTrimPitch = Math.max(-trimCap, Math.min(trimCap, bracketTrimPitch));
            } else {
                // Regressed (or this is a new engagement) — a single "improvement"
                // can just be the bow's own random spread, not a real trend, so
                // don't keep compounding on unconfirmed signal. Drop the walked-in
                // trim and let the next shot start from a clean analytic solve.
                bracketTrimYaw = 0;
                bracketTrimPitch = 0;
            }

            lastShotErrorMagnitude = errorMagnitude;
            lastShotTargetId = thisTargetId;
        }
    }

    private boolean hasLineOfSight(Vec3 from, Vec3 to) {
        BlockHitResult hit = mc.level.clip(new ClipContext(
            from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player
        ));
        return hit.getType() == HitResult.Type.MISS;
    }

    private void applyRotation() {
        if (cachedYaw == null || cachedPitch == null) return;
        double yawCorrection = (selfCorrect.get() ? yawBias : 0.0) + (bracketing.get() ? bracketTrimYaw : 0.0);
        double pitchCorrection = (selfCorrect.get() ? pitchBias : 0.0) + (bracketing.get() ? bracketTrimPitch : 0.0);
        float appliedYaw = (float) (cachedYaw + yawCorrection);
        float appliedPitch = (float) (cachedPitch + pitchCorrection);
        Rotations.rotate(appliedYaw, appliedPitch, -10, true, null);
    }

    private void renderPrediction(Render3DEvent event) {
        if (target == null) return;

        if (cachedSolutionFound && cachedPath.size() > 1) {
            for (int i = 0; i < cachedPath.size() - 1; i++) {
                Vec3 a = cachedPath.get(i), b = cachedPath.get(i + 1);
                event.renderer.line(a.x, a.y, a.z, b.x, b.y, b.z, predictionColor.get());
            }
        }

        event.renderer.box(target.getBoundingBox(), predictionColor.get(), predictionColor.get(), ShapeMode.Both, 0);
    }

    private boolean itemInHand() {
        // Crossbow deliberately excluded: the charge-speed model (BowItem.getPowerForTime)
        // and the release logic (stopUsingItem) both assume a bow.
        return InvUtils.testInMainHand(Items.BOW);
    }

    /**
     * allowLoft controls the angle range considered:
     * - true (Artillery Mode on): steep angles too (-85..85), so a shot can
     * loft over a wall or drop onto a target beyond flat range.
     * - false (Artillery Mode off): restricted to shallow angles (-15..45).
     * <p>
     * Fast path: solve the drag-free closed-form ballistic equation for this
     * exact speed, which — for any speed above the analytic minimum — yields
     * two candidate angles (a flatter/faster "low" arc and a lofted "high"
     * arc). Drag means the real, simulated landing point will fall a little
     * short of the no-drag prediction, so each analytic candidate is corrected
     * with a narrow local simulated search (a handful of angleStep increments
     * either side) rather than trusting the closed form outright.
     * <p>
     * Slow path: if neither analytic candidate (after local correction) lands
     * within tolerance — e.g. both are obstructed, or the geometry is right at
     * the edge of what this speed can reach — fall back to the original full
     * angle sweep as a robustness net.
     */
    private Float findBallisticPitch(Vec3 shooterPos, Vec3 targetPos, double yaw, double speed, double d, double dy, List<Vec3> outPath, boolean allowLoft) {
        double targetDist3D = DistanceUtil.distance(shooterPos, targetPos);

        double minPitch = allowLoft ? -85.0 : -15.0;
        double maxPitch = allowLoft ? 85.0 : 45.0;

        Best best = new Best();

        if (d > 1e-3) {
            double v2 = speed * speed;
            double disc = v2 * v2 - GRAVITY * (GRAVITY * d * d + 2 * dy * v2);

            if (disc >= 0) {
                double sqrtDisc = Math.sqrt(disc);
                double tanLow = (v2 - sqrtDisc) / (GRAVITY * d);
                double tanHigh = (v2 + sqrtDisc) / (GRAVITY * d);

                double pitchLow = -Math.toDegrees(Math.atan(tanLow));
                double pitchHigh = -Math.toDegrees(Math.atan(tanHigh));

                // Prefer the flatter/faster low arc first — shorter flight time,
                // less drag exposure, matches the existing "shortest ticks wins"
                // philosophy below.
                refineAround(pitchLow, minPitch, maxPitch, shooterPos, yaw, speed, targetPos, targetDist3D, best);
                if (!(best.found && best.ticks <= 1)) {
                    refineAround(pitchHigh, minPitch, maxPitch, shooterPos, yaw, speed, targetPos, targetDist3D, best);
                }
            }
        }

        if (best.found) {
            outPath.addAll(best.path);
            return (float) best.pitch;
        }

        // Slow path fallback: full brute-force sweep, same as before.
        bruteForceScan(minPitch, maxPitch, shooterPos, yaw, speed, targetPos, targetDist3D, best);

        if (best.found) {
            outPath.addAll(best.path);
            return (float) best.pitch;
        }
        return null;
    }

    /**
     * Mutable best-candidate accumulator shared across the analytic and brute-force passes.
     */
    private static final class Best {
        boolean found;
        double pitch;
        int ticks = Integer.MAX_VALUE;
        List<Vec3> path;

        void consider(double candidatePitch, ArcResult result, List<Vec3> path) {
            if (result.ticksToClosestApproach() < ticks) {
                found = true;
                pitch = candidatePitch;
                ticks = result.ticksToClosestApproach();
                this.path = path;
            }
        }
    }

    /**
     * Simulates a narrow window of angles (±4°, in angleStep increments) around an analytic guess.
     */
    private void refineAround(double centerPitch, double minPitch, double maxPitch, Vec3 shooterPos, double yaw, double speed, Vec3 targetPos, double targetDist3D, Best best) {
        double window = 4.0;
        double from = Math.max(minPitch, centerPitch - window);
        double to = Math.min(maxPitch, centerPitch + window);
        scanRange(from, to, shooterPos, yaw, speed, targetPos, targetDist3D, best);
    }

    private void bruteForceScan(double minPitch, double maxPitch, Vec3 shooterPos, double yaw, double speed, Vec3 targetPos, double targetDist3D, Best best) {
        scanRange(minPitch, maxPitch, shooterPos, yaw, speed, targetPos, targetDist3D, best);
    }

    // Among all candidates that land within tolerance, prefer the one with the
    // shortest flight time — not just the smallest miss distance. Bows have
    // inaccuracy=1 per the wiki (random Gaussian noise added to launch
    // velocity), and that noise compounds into more positional drift the
    // longer the arrow is in flight. A flatter, faster shot that still hits
    // is inherently less exposed to that randomness than a slower, longer
    // lofted one with a marginally smaller simulated miss.
    private void scanRange(double minPitch, double maxPitch, Vec3 shooterPos, double yaw, double speed, Vec3 targetPos, double targetDist3D, Best best) {
        double yawRad = Math.toRadians(yaw);
        double dirX = -Math.sin(yawRad);
        double dirZ = Math.cos(yawRad);

        for (double pitchDeg = minPitch; pitchDeg <= maxPitch; pitchDeg += angleStep.get()) {
            double pitchRad = Math.toRadians(pitchDeg);
            double horizontalSpeed = speed * Math.cos(pitchRad);
            Vec3 velocity = new Vec3(
                dirX * horizontalSpeed,
                -speed * Math.sin(pitchRad),
                dirZ * horizontalSpeed
            );

            List<Vec3> path = new ArrayList<>();
            ArcResult result = simulateArc(shooterPos, velocity, targetPos, targetDist3D, path);
            if (result == null || result.missDistance() > hitTolerance.get()) continue;

            best.consider(pitchDeg, result, path);
        }
    }

    private record ArcResult(double missDistance, int ticksToClosestApproach) {
    }

    /**
     * Simulates one candidate arc tick-by-tick using the wiki's recurrence
     * relation and reports the closest approach to the target. Returns null if
     * the path hits a block before reaching the target's actual distance, since
     * that arc isn't actually deliverable.
     * <p>
     * Distance comparisons here use full 3D distance from the shooter, not just
     * horizontal (X/Z) distance. A flat shot aimed straight at a target standing
     * behind a wall travels in a near-straight line toward the target's X/Z
     * coordinates, so it hits the wall at almost the same horizontal position as
     * the target — a horizontal-only check would wrongly treat that as "reaching"
     * the target. Comparing true 3D distance correctly recognizes the wall is
     * physically closer to the shooter than the target is, and rejects the shot.
     */
    private ArcResult simulateArc(Vec3 startPos, Vec3 startVel, Vec3 targetPos, double targetDist3D, List<Vec3> outPath) {
        Vec3 pos = startPos;
        Vec3 vel = startVel;
        double bestMiss = Double.MAX_VALUE;
        int bestMissTick = 0;
        boolean passedTargetDistance = false;
        outPath.add(pos);

        for (int i = 0; i < simulationTicks.get(); i++) {
            Vec3 next = pos.add(vel);

            BlockHitResult blockHit = mc.level.clip(new ClipContext(
                pos, next, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player
            ));

            double distSoFar3D = DistanceUtil.distance(next, startPos);

            if (blockHit.getType() != HitResult.Type.MISS) {
                outPath.add(blockHit.getLocation());
                double distToBlock3D = DistanceUtil.distance(blockHit.getLocation(), startPos);
                double missAtBlock = DistanceUtil.distance(blockHit.getLocation(), targetPos);
                if (distToBlock3D < targetDist3D - hitTolerance.get()) return null;
                boolean blockIsBest = missAtBlock < bestMiss;
                return new ArcResult(Math.min(bestMiss, missAtBlock), blockIsBest ? i : bestMissTick);
            }

            double miss = DistanceUtil.distance(next, targetPos);
            if (miss < bestMiss) {
                bestMiss = miss;
                bestMissTick = i;
            }

            outPath.add(next);
            if (distSoFar3D >= targetDist3D) passedTargetDistance = true;
            if (passedTargetDistance && miss > bestMiss + 0.5) break;

            pos = next;
            vel = vel.scale(DRAG).subtract(0, GRAVITY, 0);
        }

        return new ArcResult(bestMiss, bestMissTick);
    }

    @Override
    public String getInfoString() {
        return EntityUtils.getName(target);
    }
}
