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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
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
        .description("Allow lofted trajectories that can hit targets behind obstructions or beyond flat range, instead of direct shots.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> angleStep = sgArtillery.add(new DoubleSetting.Builder()
        .name("angle-step")
        .description("Base degree increment used when scanning candidate pitch angles. Used as reference for adaptive stepping.")
        .defaultValue(1.0)
        .range(0.1, 5.0)
        .sliderRange(0.1, 5.0)
        .build()
    );

    private final Setting<Boolean> adaptiveAngleStep = sgArtillery.add(new BoolSetting.Builder()
        .name("adaptive-angle-step")
        .description("Automatically scale angle-step by distance: smaller steps at long range for precision, larger at close range for speed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> refinePitch = sgArtillery.add(new BoolSetting.Builder()
        .name("refine-pitch")
        .description("After the angle sweep, refine the winning pitch with a continuous golden-section search across the two neighboring grid points.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hitboxTargeting = sgArtillery.add(new BoolSetting.Builder()
        .name("hitbox-targeting")
        .description("Aim at the target's full hitbox instead of its center point.")
        .defaultValue(false)
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
        .defaultValue(0.5)
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
        .description("Automatically draws and releases the bow the instant the current charge produces a valid, confirmed solution.")
        .defaultValue(true)
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
        .description("Multiplier applied over the analytic minimum speed required to reach the target.")
        .defaultValue(1.15)
        .range(1.0, 1.5)
        .sliderRange(1.0, 1.5)
        .build()
    );

    private final SettingGroup sgSelfCorrect = settings.createGroup("Self-Correction");

    private final Setting<Boolean> selfCorrect = sgSelfCorrect.add(new BoolSetting.Builder()
        .name("self-correct")
        .description("Tracks real fired arrows and nudges future aim based on observed bias. Only corrects a consistent offset.")
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

    private final Setting<Boolean> adaptiveLearningRate = sgSelfCorrect.add(new BoolSetting.Builder()
        .name("adaptive-learning-rate")
        .description("Automatically reduce learning-rate when shot variance is high (noisy/unlucky shots). Prevents overcorrection from lucky/unlucky shots.")
        .visible(selfCorrect::get)
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> maxErrorVariance = sgSelfCorrect.add(new DoubleSetting.Builder()
        .name("max-error-variance")
        .description("Maximum expected error variance (degrees^2). When observed variance approaches this, learning-rate scales toward zero. Higher = more tolerant.")
        .visible(() -> selfCorrect.get() && adaptiveLearningRate.get())
        .defaultValue(4.0)
        .range(0.5, 20.0)
        .sliderRange(0.5, 20.0)
        .build()
    );

    private final SettingGroup sgBracketing = settings.createGroup("Bracketing");

    private final Setting<Boolean> bracketing = sgBracketing.add(new BoolSetting.Builder()
        .name("bracketing")
        .description("Between shots at the same target, walk the aim further in the direction of the last correction as long as each shot is landing closer than before.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> bracketRate = sgBracketing.add(new DoubleSetting.Builder()
        .name("bracket-rate")
        .description("How aggressively the trim walks toward the correction while shots keep improving. Higher than the global learning rate on purpose.")
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
        .range(0.5, 15.0)
        .sliderRange(0.5, 15.0)
        .build()
    );

    private final Setting<Integer> noSolutionAbortTicks = sgArtillery.add(new IntSetting.Builder()
        .name("no-solution-abort-ticks")
        .description("If NO charge level up to max-charge-ticks can reach the target for this many ticks, cancel the draw instead of releasing it to prevent wasting arrows.")
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

    // Adaptive learning rate: track the spread of shot errors around their
    // running mean to scale the learning rate down when shots are noisy (high
    // variance = lucky/unlucky shots, not real bias). Variance of a constantly
    // deflected series stays low (the mean tracks the deflection) even when the
    // errors are large, so the learning rate stays high to converge the bias out.
    private double yawErrorMean = 0;
    private double pitchErrorMean = 0;
    private double yawErrorVariance = 0;
    private double pitchErrorVariance = 0;
    private static final double VARIANCE_SMOOTHING = 0.9; // EMA factor for variance

    // True when auto-fire is the one holding the use key down. Prevents
    // the no-bow guard from cancelling a manual eat/drink/other item use.
    private boolean autoFireHoldsUseKey = false;

    // Blocked-target safety: once a target is confirmed unreachable at every
    // achievable charge level (no arc clears the obstruction, or a wall
    // directly in front), stop drawing and stop re-searching every tick
    private int consecutiveNoSolutionTicks = 0;
    private Integer noSolutionTargetId = null;
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
    private static final int REFINE_ITERATIONS = 12;

    // Bracketing: a faster, per-engagement trim layered on top of the slow
    // global bias. Walked further in the corrective direction while shots keep
    // improving; dropped entirely the instant one doesn't.
    private double bracketTrimYaw = 0;
    private double bracketTrimPitch = 0;
    private double lastShotErrorMagnitude = Double.MAX_VALUE;
    private Integer lastShotTargetId = null;

    // The constants below match the vanilla implementation of PersistentProjectileEntity.tick()
    // > each in-flight tick, in this exact order,
    //   1. position += velocity,
    //   2. if not in water: velocity *= 0.99 (air drag; the water branch instead multiplies by
    //      getDragInWater() == 0.6 BEFORE the move, hence water shots are not modeled here),
    //   3. velocity.y -= 0.05 (getGravity()).
    // simulateArc() must stay in step with that order, since it is the only place trajectories
    // are solved. Don't "optimize" the recurrence order as Meteor's simulator applies drag/gravity
    // in a differently-ordered recurrence that does NOT match vanilla. The same gravity constant
    // also drives the analytic (drag-free) closed-form solver used to pick charge/pitch before
    // simulation ever runs; drag is treated there as a small correction to be refined out locally,
    // not modelled in closed form.
    private static final double DRAG = 0.99;
    private static final double GRAVITY = 0.05;

    public PzH2000() {
        super(Addon.CATEGORY2, "PzH-2000", "With a bow and this, It can fire shells at a high velocity aided by a laser rangefinder");
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
        // Release the auto-fire key before clearing the flag, otherwise the
        // release check sees false and the key stays pressed.
        if (mc.player != null && autoFireHoldsUseKey) {
            mc.options.keyUse.setDown(false);
        }
        autoFireHoldsUseKey = false;
        consecutiveNoSolutionTicks = 0;
        noSolutionTargetId = null;
        blockedTargetId = null;
        blockedRecheckCooldown = 0;
        bracketTrimYaw = 0;
        bracketTrimPitch = 0;
        yawErrorMean = 0;
        pitchErrorMean = 0;
        yawErrorVariance = 0;
        pitchErrorVariance = 0;
        lastShotErrorMagnitude = Double.MAX_VALUE;
        lastShotTargetId = null;
        // Note: yawBias/pitchBias intentionally not reset, they represent a
        // learned correction for a consistent aiming offset, not per-session state.
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        // target is only recomputed once past these guards, so each early exit has to
        // clear it. Otherwise a target from a previous frame survives into onTick,
        // which only re-checks itemInHand(): holding a bow with no arrows left, or
        // dying outright, would keep aiming at and holding use on a stale target.
        if (!PlayerUtils.isAlive() || !itemInHand()) {
            target = null;
            return;
        }
        if (!mc.player.getAbilities().instabuild && !InvUtils.find(itemStack -> itemStack.getItem() instanceof ArrowItem).found()) {
            target = null;
            return;
        }

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

        // Guard: auto-fire keeps the use key pressed while drawing. If the bow is no
        // longer held (slot switched away) the module must release it immediately,
        if (!itemInHand()) {
            if (autoFireHoldsUseKey) {
                if (mc.options.keyUse.isDown()) mc.options.keyUse.setDown(false);
                // Do noy call stopUsingItem here
                autoFireHoldsUseKey = false;
            }
        }

        if (target == null || !itemInHand()) {
            if (autoFireHoldsUseKey && isUsingNow && itemInHand()) {
                mc.options.keyUse.setDown(false);
                mc.gameMode.releaseUsingItem(mc.player);
                autoFireHoldsUseKey = false;
            }
            // A shot fired at a target that has since died, walked out of range, or while we
            // swapped away from the bow still needs its outcome recorded, otherwise the
            // self-correction silently skips every engagement that ends before the arrow lands
            if (selfCorrect.get() && pendingTargetRef != null) processPendingShot();
            wasUsingItemLastTick = false;
            return;
        }

        // Bows fire the arrow from the living entity's eye position minus 0.1
        // (the vanilla Arrow spawn offset), not the eye line itself.
        Vec3 shooterPos = new Vec3(mc.player.getX(), mc.player.getEyeY() - 0.1, mc.player.getZ());
        Vec3 targetPos = target.position().add(0, target.getBbHeight() / 2.0, 0);
        AABB targetBox = hitboxTargeting.get() ? target.getBoundingBox() : null;

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

        // If auto-fire was holding the key but is now disabled, release it
        // so the synthetic press doesn't persist.
        if (autoFireHoldsUseKey && !autoFire.get()) {
            mc.options.keyUse.setDown(false);
            autoFireHoldsUseKey = false;
        }

        boolean wantsToDraw = autoFire.get() || manualHold;

        if (!wantsToDraw) {
            cachedSolutionFound = false;
            if (selfCorrect.get() && pendingTargetRef != null) processPendingShot();
            return;
        }

        if (autoFire.get() && !isUsingNow) {
            mc.options.keyUse.setDown(true);
            autoFireHoldsUseKey = true;
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

                    double previewSpeed = 3.0 * pullProgress(ticks);
                    List<Vec3> previewPath = new ArrayList<>();
                    if (findBallisticPitch(shooterPos, targetPos, yaw, previewSpeed, horizontalDist, heightDiff, previewPath, artilleryMode.get(), targetBox) != null) {
                        feasible = true;
                        if (!cachedSolutionFound) cachedPath = previewPath;
                    }
                }
                if (!feasible && maxChargeTicks.get() != lastTicks) {
                    double previewSpeed = 3.0 * pullProgress(maxChargeTicks.get());
                    List<Vec3> previewPath = new ArrayList<>();
                    if (findBallisticPitch(shooterPos, targetPos, yaw, previewSpeed, horizontalDist, heightDiff, previewPath, artilleryMode.get(), targetBox) != null) {
                        feasible = true;
                        if (!cachedSolutionFound) cachedPath = previewPath;
                    }
                }
            }
            // Only cache when the sweep actually ran. A tick skipped by onCooldown/losOk has no
            // result to remember, and caching its `false` would hide a real solution for
            // TARGET_FEASIBILITY_CACHE_TICKS once the target becomes checkable again.
            if (!onCooldown && losOk) {
                feasibilityCacheTargetId = target.getId();
                feasibilityCacheTicksLeft = TARGET_FEASIBILITY_CACHE_TICKS;
                feasibleCached = feasible;
            }
        }

        // Live aim: solved for the ACTUAL current draw (mc.player.getTicksUsingItem()),
        int currentUseTicks = isUsingNow ? mc.player.getTicksUsingItem() : 0;
        double currentSpeed = 3.0 * pullProgress(currentUseTicks);
        double requiredSpeed = computeMinimumSpeed(horizontalDist, heightDiff) * powerMargin.get();

        Float currentPitch = null;
        List<Vec3> currentPath = new ArrayList<>();
        if (!onCooldown && losOk && currentUseTicks >= minChargeTicks.get()) {
            currentPitch = findBallisticPitch(shooterPos, targetPos, yaw, currentSpeed, horizontalDist, heightDiff, currentPath, artilleryMode.get(), targetBox);
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
            noSolutionTargetId = null;
            blockedTargetId = null;
            blockedRecheckCooldown = 0;
        } else if (currentUseTicks >= minChargeTicks.get()) {
            // Accumulate against the target we are currently failing on, not blockedTargetId:
            // that one is only set once the threshold trips, so using it here would reset the
            // counter every tick and the abort would never fire.
            boolean sameNoSolutionTarget = noSolutionTargetId != null && noSolutionTargetId == target.getId();
            consecutiveNoSolutionTicks = sameNoSolutionTarget ? consecutiveNoSolutionTicks + 1 : 1;
            noSolutionTargetId = target.getId();
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
            autoFireHoldsUseKey = false;
        }

        if (selfCorrect.get() && pendingTargetRef != null) {
            processPendingShot();
        }
    }

    private void abortDraw() {
        if (mc.player == null) return;
        mc.options.keyUse.setDown(false);
        autoFireHoldsUseKey = false;
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
            double speed = 3.0 * pullProgress(ticks);
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
                    && arrow.getOwner() == mc.player
                    // Reject an older arrow of ours that is still in flight: only an
                    // arrow shot from where we are shooting, and no older than the
                    // ticks we have waited, belongs to this pending shot. Adopting a
                    // stale one would feed its miss into the learned aim bias.
                    && arrow.tickCount <= pendingWaitTicks + 1
                    && (pendingShooterPos == null || arrow.position().distanceToSqr(pendingShooterPos) <= 16.0D)) {
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
     * cancelling out any consistent offset, such as the wiki default
     * rightward drift caused by the bow's model not being exactly on the eye
     * line.
     * This cannot reduce Minecraft's inherent per-shot Gaussian velocity
     * randomness (bows have inaccuracy=1); that's independent noise each shot
     * with nothing consistent to learn from, this only removes bias, not
     * variance.
     * <p>
     * An arrow that never got near the target region -> a wall stop well short,
     * or a drop that fell out early is not recorded, otherwise a single
     * obstructed shot would turn into a giant degree error, slam the bias
     * against its cap, and push every later shot hard to the side.
     */
    private void recordShotOutcome(Vec3 actualFinalPos) {
        if (pendingTargetPos == null || pendingShooterPos == null) return;

        Vec3 toTarget = pendingTargetPos.subtract(pendingShooterPos);
        double dist = toTarget.length();
        if (dist < 1e-3) return;

        Vec3 toActual = actualFinalPos.subtract(pendingShooterPos);
        Vec3 forward = toTarget.normalize();
        // Ignore shots that fell short of the target region (walls, premature
        // ground hits): only progress TOWARD the target past ~85% of the way
        // counts as a real outcome to learn from. A lofted arrow hitting an
        // overhead obstruction near the shooter can have a large toActual.length()
        // but near-zero forward progress; we check the projection instead.
        double progress = toActual.dot(forward);
        if (progress < dist * 0.85) return;

        Vec3 worldUp = new Vec3(0, 1, 0);
        Vec3 right = forward.cross(worldUp).normalize();
        Vec3 up = right.cross(forward).normalize();

        Vec3 error = toActual.subtract(forward.scale(toActual.dot(forward)));
        double lateralError = error.dot(right);
        double verticalError = error.dot(up);

        double yawErrorDeg = Math.toDegrees(Math.atan2(lateralError, dist));
        double pitchErrorDeg = Math.toDegrees(Math.atan2(verticalError, dist));

        // Adaptive learning rate: reduce when error variance is high (noisy shots)
        double yawLR = learningRate.get();
        double pitchLR = learningRate.get();
        if (adaptiveLearningRate.get()) {
            // Variance of the error spread around its EMA mean, not the raw
            // mean-square error: a constant bias raises E[y], so E[y^2]-E[y]^2
            // stays small and the learning rate stays high to absorb it.
            yawErrorMean = VARIANCE_SMOOTHING * yawErrorMean + (1.0 - VARIANCE_SMOOTHING) * yawErrorDeg;
            pitchErrorMean = VARIANCE_SMOOTHING * pitchErrorMean + (1.0 - VARIANCE_SMOOTHING) * pitchErrorDeg;
            yawErrorVariance = VARIANCE_SMOOTHING * yawErrorVariance + (1.0 - VARIANCE_SMOOTHING) * yawErrorDeg * yawErrorDeg;
            pitchErrorVariance = VARIANCE_SMOOTHING * pitchErrorVariance + (1.0 - VARIANCE_SMOOTHING) * pitchErrorDeg * pitchErrorDeg;

            double maxVar = maxErrorVariance.get();
            double yawSpread = Math.max(0.0, yawErrorVariance - yawErrorMean * yawErrorMean);
            double pitchSpread = Math.max(0.0, pitchErrorVariance - pitchErrorMean * pitchErrorMean);
            double yawScale = 1.0 - Math.min(1.0, yawSpread / maxVar);
            double pitchScale = 1.0 - Math.min(1.0, pitchSpread / maxVar);
            yawLR *= Math.max(0.1, yawScale); // floor at 10% to avoid stall
            pitchLR *= Math.max(0.1, pitchScale);
        }

        // Arrow landed right of intended -> aim further left next time, hence "-=".
        yawBias -= yawLR * yawErrorDeg;
        pitchBias += pitchLR * pitchErrorDeg;

        double cap = maxCorrection.get();
        yawBias = Math.max(-cap, Math.min(cap, yawBias));
        pitchBias = Math.max(-cap, Math.min(cap, pitchBias));

        if (bracketing.get()) {
            int thisTargetId = pendingTargetRef != null ? pendingTargetRef.getId() : -1;
            double errorMagnitude = Math.hypot(yawErrorDeg, pitchErrorDeg);

            boolean sameEngagement = lastShotTargetId != null && lastShotTargetId == thisTargetId;
            boolean improved = sameEngagement && errorMagnitude < lastShotErrorMagnitude;

            if (improved) {
                // Walking in successfully, keep nudging further the same way,
                // faster than the slow global bias, same sign convention as above.
                bracketTrimYaw -= bracketRate.get() * yawErrorDeg;
                bracketTrimPitch += bracketRate.get() * pitchErrorDeg;

                double trimCap = maxBracketTrim.get();
                bracketTrimYaw = Math.max(-trimCap, Math.min(trimCap, bracketTrimYaw));
                bracketTrimPitch = Math.max(-trimCap, Math.min(trimCap, bracketTrimPitch));
            } else {
                // Regressed (or this is a new engagement) a single "improvement"
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

    /**
     * Bow draw strength for a number of ticks drawn. BowItem#getPullProgress was removed in
     * 26.1.2, so this replicates its 1.21.11 body exactly: f = ticks/20, (f*f + f*2)/3,
     * clamped to a maximum of 1.
     */
    private static float pullProgress(int ticks) {
        float f = ticks / 20.0F;
        float g = (f * f + f * 2.0F) / 3.0F;
        return g > 1.0F ? 1.0F : g;
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
        // Crossbow deliberately excluded: the charge-speed model (BowItem.getPullProgress)
        // and the release logic (stopUsingItem) both assume a bow.
        return InvUtils.testInMainHand(Items.BOW);
    }

    /**
     * allowLoft selects the angle search range: Artillery Mode on enables steep
     * angles (-85..85) so shots can arc over walls or drop onto distant targets,
     * while off restricts to shallow angles (-15..45). The solver first tries a
     * fast analytic path, solving the drag-free ballistic equation for the exact
     * speed, which gives two candidates (a fast low arc and a lofted high arc)
     * then refines each with a narrow local simulation since drag pulls the real
     * impact point short of the ideal prediction. If neither corrected candidate
     * lands within tolerance (example: both are obstructed or at the edge of reach),
     * a full angle sweep runs as a fallback safety net.
     */
    private Float findBallisticPitch(Vec3 shooterPos, Vec3 targetPos, double yaw, double speed, double d, double dy, List<Vec3> outPath, boolean allowLoft, AABB targetBox) {
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

                // Prefer the flatter/faster low arc first which is shorter flight time,
                // less drag exposure, matches the existing "shortest ticks wins"
                // philosophy below.
                refineAround(pitchLow, minPitch, maxPitch, shooterPos, yaw, speed, targetPos, targetDist3D, best, targetBox);
                if (!(best.found && best.ticks <= 1)) {
                    refineAround(pitchHigh, minPitch, maxPitch, shooterPos, yaw, speed, targetPos, targetDist3D, best, targetBox);
                }
            }
        }

        if (best.found) {
            if (refinePitch.get())
                refinePitchAround(best.pitch, pitchStep(d), minPitch, maxPitch, shooterPos, yaw, speed, targetPos, targetDist3D, best, targetBox);
            outPath.addAll(best.path);
            return (float) best.pitch;
        }

        // Slow path fallback: full brute-force sweep, same as before.
        bruteForceScan(minPitch, maxPitch, shooterPos, yaw, speed, targetPos, targetDist3D, best, targetBox);

        if (best.found) {
            if (refinePitch.get())
                refinePitchAround(best.pitch, pitchStep(d), minPitch, maxPitch, shooterPos, yaw, speed, targetPos, targetDist3D, best, targetBox);
            outPath.addAll(best.path);
            return (float) best.pitch;
        }
        return null;
    }

    /**
     * Closes the sub-grid gap left by the discrete angle sweep. The sweep can
     * only land a shot within ~range·tan(step/2) of the true arc, which shows
     * up as consistent near-hits/overshoots; a golden-section search over the
     * two neighbor cells walks the miss to (near) zero with a modest number of
     * extra simulations. The winner replaces best only if it actually improves
     * on the sampled candidate.
     */
    private void refinePitchAround(double centerPitch, double step, double minPitch, double maxPitch, Vec3 shooterPos, double yaw, double speed, Vec3 targetPos, double targetDist3D, Best best, AABB targetBox) {
        double lo = Math.max(minPitch, centerPitch - step);
        double hi = Math.min(maxPitch, centerPitch + step);
        if (hi - lo < 1e-4) return;

        final double invPhi = (Math.sqrt(5.0) - 1.0) / 2.0;
        List<Vec3> scratch = new ArrayList<>();

        double x1 = hi - invPhi * (hi - lo);
        double x2 = lo + invPhi * (hi - lo);
        ArcResult r1 = evaluatePitch(x1, shooterPos, yaw, speed, targetPos, targetDist3D, scratch, targetBox);
        ArcResult r2 = evaluatePitch(x2, shooterPos, yaw, speed, targetPos, targetDist3D, scratch, targetBox);
        double f1 = r1 == null ? Double.MAX_VALUE : r1.missDistance();
        double f2 = r2 == null ? Double.MAX_VALUE : r2.missDistance();

        for (int i = 0; i < REFINE_ITERATIONS; i++) {
            if (f1 < f2) {
                hi = x2;
                x2 = x1;
                f2 = f1;
                x1 = hi - invPhi * (hi - lo);
                ArcResult r = evaluatePitch(x1, shooterPos, yaw, speed, targetPos, targetDist3D, scratch, targetBox);
                f1 = r == null ? Double.MAX_VALUE : r.missDistance();
            } else {
                lo = x1;
                x1 = x2;
                f1 = f2;
                x2 = lo + invPhi * (hi - lo);
                ArcResult r = evaluatePitch(x2, shooterPos, yaw, speed, targetPos, targetDist3D, scratch, targetBox);
                f2 = r == null ? Double.MAX_VALUE : r.missDistance();
            }
        }

        double pitch = f1 <= f2 ? x1 : x2;
        double miss = Math.min(f1, f2);
        if (miss >= best.miss) return;

        List<Vec3> path = new ArrayList<>();
        ArcResult result = evaluatePitch(pitch, shooterPos, yaw, speed, targetPos, targetDist3D, path, targetBox);
        if (result == null || result.missDistance() > hitTolerance.get()) return;

        best.found = true;
        best.pitch = pitch;
        best.miss = result.missDistance();
        best.ticks = result.ticksToClosestApproach();
        best.path = path;
    }

    /**
     * Mutable best-candidate accumulator shared across the analytic and brute-force passes.
     * Accuracy-first: among candidates that cleared hitTolerance, the one with the smallest
     * simulated miss wins. Flight time only breaks a tie when two arcs miss by virtually the
     * same amount (CLOSENESS_EPSILON), because the bow's per-shot random inaccuracy compounds
     * into more drift on longer flights. Pitched candidates at angleStep granularity (default 1.0)
     * routinely differ in miss by 0.5+ blocks within the refine window, so preferring the
     * fastest arc outright systematically aims up to a full block off-center.
     */
    private static final class Best {
        private static final double CLOSENESS_EPSILON = 0.05;

        boolean found;
        double pitch;
        int ticks = Integer.MAX_VALUE;
        double miss = Double.MAX_VALUE;
        List<Vec3> path;

        void consider(double candidatePitch, ArcResult result, List<Vec3> path) {
            double candidateMiss = result.missDistance();
            int candidateTicks = result.ticksToClosestApproach();
            if (!found
                || candidateMiss < miss - CLOSENESS_EPSILON
                || (Math.abs(candidateMiss - miss) <= CLOSENESS_EPSILON && candidateTicks < ticks)) {
                found = true;
                pitch = candidatePitch;
                ticks = candidateTicks;
                miss = candidateMiss;
                this.path = path;
            }
        }
    }

    /**
     * Simulates a narrow window of angles (±4°, in angleStep increments) around an analytic guess.
     */
    private void refineAround(double centerPitch, double minPitch, double maxPitch, Vec3 shooterPos, double yaw, double speed, Vec3 targetPos, double targetDist3D, Best best, AABB targetBox) {
        double window = 4.0;
        double from = Math.max(minPitch, centerPitch - window);
        double to = Math.min(maxPitch, centerPitch + window);
        scanRange(from, to, shooterPos, yaw, speed, targetPos, targetDist3D, best, targetBox);
    }

    private void bruteForceScan(double minPitch, double maxPitch, Vec3 shooterPos, double yaw, double speed, Vec3 targetPos, double targetDist3D, Best best, AABB targetBox) {
        scanRange(minPitch, maxPitch, shooterPos, yaw, speed, targetPos, targetDist3D, best, targetBox);
    }

    // Among all candidates that land within tolerance, prefer the most accurate
    // one (smallest simulated miss); only fall back to the shortest flight time
    // as a tie-break, since bows have inaccuracy=1 per the wiki (random Gaussian
    // noise added to launch velocity) and that noise compounds into more
    // positional drift the longer the arrow is in flight.
    private void scanRange(double minPitch, double maxPitch, Vec3 shooterPos, double yaw, double speed, Vec3 targetPos, double targetDist3D, Best best, AABB targetBox) {
        double horizontalDist = DistanceUtil.distanceXZ(shooterPos.x, shooterPos.z, targetPos.x, targetPos.z);
        double step = pitchStep(horizontalDist);

        for (double pitchDeg = minPitch; pitchDeg <= maxPitch; pitchDeg += step) {
            List<Vec3> path = new ArrayList<>();
            ArcResult result = evaluatePitch(pitchDeg, shooterPos, yaw, speed, targetPos, targetDist3D, path, targetBox);
            if (result == null || result.missDistance() > hitTolerance.get()) continue;

            best.consider(pitchDeg, result, path);
        }
    }

    /**
     * Adaptive sweep step: at 50 blocks reference distance and above, step =
     * angleStep. Closer = larger step (faster), farther = smaller (precision).
     */
    private double pitchStep(double horizontalDist) {
        double step = angleStep.get();
        if (adaptiveAngleStep.get() && horizontalDist > 1.0) {
            step = Math.max(0.25, Math.min(2.0, angleStep.get() * (50.0 / horizontalDist)));
        }
        return step;
    }

    /**
     * Simulates one candidate pitch angle; pathOut may be null to skip building
     * the path (probes), null result means the arc is blocked before the target.
     */
    private ArcResult evaluatePitch(double pitchDeg, Vec3 shooterPos, double yaw, double speed, Vec3 targetPos, double targetDist3D, List<Vec3> pathOut, AABB targetBox) {
        double pitchRad = Math.toRadians(pitchDeg);
        double yawRad = Math.toRadians(yaw);
        double horizontalSpeed = speed * Math.cos(pitchRad);
        Vec3 velocity = new Vec3(
            -Math.sin(yawRad) * horizontalSpeed,
            -speed * Math.sin(pitchRad),
            Math.cos(yawRad) * horizontalSpeed
        );
        return simulateArc(shooterPos, velocity, targetPos, targetDist3D, pathOut, targetBox);
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
     * the target.
     * a horizontal-only check would wrongly treat that as "reaching"
     * the target. Comparing true 3D distance correctly recognizes the wall is
     * physically closer to the shooter than the target is, and rejects the shot.
     */
    private ArcResult simulateArc(Vec3 startPos, Vec3 startVel, Vec3 targetPos, double targetDist3D, List<Vec3> outPath, AABB targetBox) {
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
                double missAtBlock = distanceToTarget(blockHit.getLocation(), targetPos, targetBox);
                if (distToBlock3D < targetDist3D - hitTolerance.get()) return null;
                boolean blockIsBest = missAtBlock < bestMiss;
                return new ArcResult(Math.min(bestMiss, missAtBlock), blockIsBest ? i : bestMissTick);
            }

            double miss = distanceToTarget(next, targetPos, targetBox);
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

    /**
     * Distance from a point to the aim target: the center point when hitbox
     * targeting is off, or 0 inside the entity's bounding box (the point has
     * reached the box) and otherwise the distance to the box surface.
     */
    private double distanceToTarget(Vec3 point, Vec3 targetPos, AABB targetBox) {
        if (targetBox == null) return DistanceUtil.distance(point, targetPos);

        double dx = Math.max(0.0, Math.max(targetBox.minX - point.x, point.x - targetBox.maxX));
        double dy = Math.max(0.0, Math.max(targetBox.minY - point.y, point.y - targetBox.maxY));
        double dz = Math.max(0.0, Math.max(targetBox.minZ - point.z, point.z - targetBox.maxZ));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Override
    public String getInfoString() {
        return EntityUtils.getName(target);
    }
}
