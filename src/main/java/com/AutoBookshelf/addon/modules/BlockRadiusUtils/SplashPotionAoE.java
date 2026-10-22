package com.AutoBookshelf.addon.modules.BlockRadiusUtils;

import com.AutoBookshelf.addon.utils.DistanceUtil;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.ARGB;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownLingeringPotion;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.*;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Splash / lingering potion area-of-effect preview, and which entities in that area the potion
 * actually beats.
 *
 * <p>Everything here is from vanilla rather than approximating it.
 *
 * <p><b>Splash.</b> Decompiled from 26.2 {@code ThrownSplashPotion.onHitAsPotion}:
 * <pre>
 *   AABB potionBox = getBoundingBox().move(hitLocation.subtract(position())).inflate(4.0, 2.0, 4.0);
 *   for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, potionBox))
 *       if (e.isAffectedByPotions())
 *           if (e.getBoundingBox().inflate(margin).distanceToSqr(potionBox) &lt;= 16.0) apply();
 * </pre>
 * with {@code SPLASH_RANGE = 4.0} / {@code SPLASH_RANGE_SQ = 16.0} and
 * {@code margin = ProjectileUtil.computeMargin(potion)} = {@code clamp((tickCount - 2) / 20, 0, 0.3)}.
 * That helper is just a function of the projectile's age, not of any entity, so it can be computed
 * from a captured tick count instead of holding the projectile alive past detonation.
 *
 * <p>Passing the distance test is <b>not</b> the same as being hit. The rest of the loop:
 * <pre>
 *   double d = 1.0 - Math.sqrt(distSq) / 4.0;
 *   int dur = mapDuration(i -&gt; (int) (d * i * durationScale + 0.5));
 *   MobEffectInstance inst = new MobEffectInstance(holder, dur, potion.getAmplifier(), ...);
 *   if (!inst.endsWithin(20)) e.addEffect(inst, source);
 * </pre>
 * Falloff is on <b>duration only</b> (amplifier passed through untouched); below 20 ticks the
 * instance is <b>discarded outright</b> (a rim mob passes the distance test and receives nothing).
 * That discarded case renders in the "weak" colour. {@code d} multiplies into the same product as
 * {@code durationScale}, so a scaled potion crosses the 20-tick line closer in; the scale must
 * therefore be part of the comparison fed to {@code MobEffectInstance.update}.
 *
 * <p>The AoE is an asymmetric <b>BOX</b> (4 horizontal, 2 vertical) and the narrow phase is
 * box-to-box squared distance, so a large mob is caught with its centre well outside the radius.
 * {@code position().distanceToSqr(e) <= 16} disagrees on exactly the entities that matter, and a
 * point-to-box approximation such as {@code DistanceUtil.distanceSqToBox} is a different quantity
 * that would shift the 20-tick cutoff. Draw the box or the preview lies.
 *
 * <p><b>Lingering</b> is a different model: the throw becomes an {@link AreaEffectCloud} that
 * re-applies for up to {@code DEFAULT_LINGERING_DURATION} (600) ticks, rate-limited per victim by
 * an internal {@code victims} map on {@code TIME_BETWEEN_APPLICATIONS} (5) ticks. It is
 * re-evaluated live every tick rather than snapshotted.
 *
 * <p>A cloud applies <b>two</b> tests, neither subsuming the other. Broad phase:
 * {@code getEntitiesOfClass(LivingEntity.class, this.getBoundingBox())}, where the cloud overrides
 * {@code getDimensions} to {@code EntityDimensions.scalable(radius * 2, 0.5)} - a box 2r wide but
 * only half a block tall, feet-anchored. That is the entire vertical constraint. Narrow phase:
 * {@code dx*dx + dz*dz <= r*r} on feet, flat and horizontal. Dropping (1) includes mobs on another
 * floor; (1) alone is too permissive horizontally.
 *
 * <p>There is <b>no distance falloff at all</b> on that path in 26.2: every entity passing both
 * tests receives an identical {@code new MobEffectInstance(existing)} copy, same amplifier and
 * duration. Centre and rim are indistinguishable, so a cloud never has a "discarded" outcome.
 *
 * <p>{@code Hit} is {@code (entityId, wins)}: {@code Hit.amplifier} was a write-only per-potion
 * constant, not per-entity information, and is gone.
 */
public class SplashPotionAoE {
    private final BlockRadiusShared shared;

    // feature
    private final Setting<Boolean> showAoe;
    private final Setting<PotionMode> potionMode;
    private final Setting<Integer> minAmplifier;

    // shape
    private final Setting<Boolean> showAoeShape;
    private final Setting<Boolean> showHitEntities;
    private final Setting<Boolean> highlightDirectHit;
    private final Setting<Boolean> tintByPotionColor;

    // colours
    private final Setting<SettingColor> aoeColor;
    private final Setting<SettingColor> hitColorFull;
    private final Setting<SettingColor> hitColorWeak;
    private final Setting<SettingColor> directColor;

    // labels
    private final Setting<Boolean> showEffectName;
    private final Setting<Double> effectLabelScale;

    // lifetime
    private final Setting<Integer> burstTtlTicks;

    public enum PotionMode {
        Splash,
        Lingering,
        Both
    }

    public SplashPotionAoE(SettingGroup sgAoe, BlockRadiusShared shared) {
        this.shared = shared;

        showAoe = sgAoe.add(new BoolSetting.Builder()
            .name("show-potion-aoe")
            .description("Show the area of effect of thrown splash/lingering potions.")
            .defaultValue(true)
            .build()
        );

        potionMode = sgAoe.add(new EnumSetting.Builder<PotionMode>()
            .name("potion-mode")
            .description("Which potion projectiles to track.")
            .defaultValue(PotionMode.Both)
            .build()
        );

        minAmplifier = sgAoe.add(new IntSetting.Builder()
            .name("min-amplifier")
            .description("Ignore potions weaker than this amplifier level (0-255).")
            .defaultValue(0)
            .range(0, 255)
            .build()
        );

        showAoeShape = sgAoe.add(new BoolSetting.Builder()
            .name("show-aoe-shape")
            .description("Draw the potion's true area of effect.")
            .defaultValue(true)
            .visible(showAoe::get)
            .build()
        );

        showHitEntities = sgAoe.add(new BoolSetting.Builder()
            .name("show-hit-entities")
            .description("Outline the entities the potion actually hits, coloured by whether the effect would win.")
            .defaultValue(true)
            .visible(showAoe::get)
            .build()
        );

        highlightDirectHit = sgAoe.add(new BoolSetting.Builder()
            .name("highlight-direct-hit")
            .description("Use a separate colour for the entity at the burst centre, i.e. the one the projectile struck.")
            .defaultValue(true)
            .visible(showAoe::get)
            .build()
        );

        tintByPotionColor = sgAoe.add(new BoolSetting.Builder()
            .name("tint-by-potion-color")
            .description("Tint the AoE shape and label with the potion's own colour instead of the configured one.")
            .defaultValue(true)
            .visible(showAoe::get)
            .build()
        );

        aoeColor = sgAoe.add(new ColorSetting.Builder()
            .name("aoe-color")
            .description("Colour of the area-of-effect shape.")
            .defaultValue(new SettingColor(180, 120, 255, 200))
            .visible(() -> showAoe.get() && !tintByPotionColor.get())
            .build()
        );

        hitColorFull = sgAoe.add(new ColorSetting.Builder()
            .name("hit-color-full")
            .description("Entity colour when the potion overwrites the entity's current effect (higher amplifier, or equal amplifier with a longer duration).")
            .defaultValue(new SettingColor(80, 255, 120, 180))
            .visible(showAoe::get)
            .build()
        );

        hitColorWeak = sgAoe.add(new ColorSetting.Builder()
            .name("hit-color-weak")
            .description("Entity colour when the potion does nothing to the entity: its existing effect is stronger, or the distance falloff discarded the effect outright.")
            .defaultValue(new SettingColor(255, 90, 90, 180))
            .visible(showAoe::get)
            .build()
        );

        directColor = sgAoe.add(new ColorSetting.Builder()
            .name("direct-color")
            .description("Colour for the entity the projectile struck directly.")
            .defaultValue(new SettingColor(255, 230, 90, 200))
            .visible(() -> showAoe.get() && highlightDirectHit.get())
            .build()
        );

        showEffectName = sgAoe.add(new BoolSetting.Builder()
            .name("show-effect-name")
            .description("Label the area with the potion's name.")
            .defaultValue(true)
            .visible(showAoe::get)
            .build()
        );

        effectLabelScale = sgAoe.add(new DoubleSetting.Builder()
            .name("effect-label-scale")
            .description("Scale of the potion name label.")
            .defaultValue(1.0)
            .range(0.25, 4.0)
            .visible(() -> showAoe.get() && showEffectName.get())
            .build()
        );

        burstTtlTicks = sgAoe.add(new IntSetting.Builder()
            .name("burst-ttl-ticks")
            .description("How long a splash burst (or a lingering cloud) stays visible.")
            .defaultValue(40)
            .range(1, 700)
            .visible(showAoe::get)
            .build()
        );
    }

    /**
     * Projectile position + age, captured while the entity is alive.
     */
    private record Tracked(Vec3 pos, int tickCount, ItemStack stack) {
    }

    /**
     * A splash burst. Snapshot, not re-evaluated: the burst is instantaneous, and re-reading
     * entity effects a tick later would show the <i>result</i> of the application rather than what
     * the potion was up against. Lingering clouds are re-evaluated live instead.
     */
    private static final class Burst {
        final AABB area;
        final Vec3 center;
        final String label;
        final int potionColor;
        final int directEntityId;
        final long expiryTick;
        final List<Hit> hits;

        Burst(AABB area, Vec3 center, String label, int potionColor, int directEntityId,
              long expiryTick, List<Hit> hits) {
            this.area = area;
            this.center = center;
            this.label = label;
            this.potionColor = potionColor;
            this.directEntityId = directEntityId;
            this.expiryTick = expiryTick;
            this.hits = hits;
        }
    }

    /**
     * One affected entity, resolved at burst time.
     *
     * @param wins whether the potion's effect would overwrite the entity's current one, i.e. the
     *             {@code MobEffectInstance.update} verdict. False covers both "the entity's effect
     *             is stronger" and "the distance falloff discarded the effect entirely": either way
     *             the potion does nothing to this entity.
     */
    private record Hit(int entityId, boolean wins) {
    }

    /**
     * A lingering cloud; its hit list is recomputed every scan because the cloud keeps applying.
     */
    private record CloudView(AreaEffectCloud cloud, List<MobEffectInstance> effects,
                             String label, int potionColor, float durationScale) {
    }

    /**
     * A detonating lingering throw still waiting for its cloud entity to appear.
     */
    private record PendingCloud(Vec3 at, ItemStack stack, long giveUpTick) {
    }

    private final Map<Integer, Tracked> trackedSplash = new HashMap<>();
    private final Map<Integer, Tracked> trackedLingering = new HashMap<>();

    /**
     * Cloud entity id -> the potion stack of the throw that created it.
     *
     * <p>{@code AreaEffectCloud.potionContents} is a private field that is <b>not</b> a synced
     * {@code EntityDataAccessor} (only radius / waiting / particle are), and the synced particle
     * carries just a colour, so the client genuinely cannot ask a cloud which potion made it.
     * The throw we were already tracking can, so each cloud is bound to its own throw by matching
     * the spawn point at detonation. Binding at adoption (rather than "most recent throw wins")
     * is what keeps two simultaneous lingering potions from stealing each other's effects.
     */
    private final Map<Integer, PotionInfo> cloudInfo = new HashMap<>();

    private record PotionInfo(List<MobEffectInstance> effects, String label, int potionColor,
                              float durationScale) {
    }

    private final List<PendingCloud> pendingClouds = new ArrayList<>();

    private final List<Burst> bursts = new ArrayList<>();
    private final List<CloudView> clouds = new ArrayList<>();

    /**
     * Entity id of the directly-struck target from the last {@link #resolveHits} call.
     */
    private int directHitId = -1;

    /**
     * entity id -> (effect -> {amplifier, duration}) sampled on the most recent tick a potion
     * was still in flight.
     *
     * <p>This exists because of a one-tick race that makes the naive comparison useless. The
     * server applies the splash effect on the same tick it detonates and immediately syncs the
     * new {@code MobEffectInstance} to us - so by the time the client sees the projectile leave
     * the entity list, {@code entity.getEffect(...)} already returns the potion's <i>own result</i>.
     * Comparing that against the potion compares the effect with itself, so the verdict is
     * vacuous (with the corrected {@code effectWins}, a self-comparison reads as "the potion
     * lost"). Reading live state
     * answers "has it been applied", which is the opposite of the question we want.
     *
     * <p>Sampling <i>before</i> the burst gives the state the server actually evaluated against.
     */
    private final Map<Integer, Map<MobEffect, int[]>> preBurst = new HashMap<>();

    /**
     * Reused across scans and across both potions' hit resolution, so the tick path does not allocate.
     */
    private final List<MobEffectInstance> effectScratch = new ArrayList<>();
    private final List<Hit> hitScratch = new ArrayList<>();
    private final List<AreaEffectCloud> cloudScratch = new ArrayList<>();

    /**
     * Translucent fill variants, keyed by the base colour they were derived from.
     */
    private final Map<SettingColor, SettingColor> fillCache = new HashMap<>();
    /**
     * Last potion tint handed out, so a stable potion colour costs no allocation per frame.
     */
    private SettingColor potionTintCache;

    /**
     * Alpha for the AoE shape's filled sides; the outline keeps the setting's own alpha.
     */
    private static final int AOE_FILL_ALPHA = 70;
    /**
     * Alpha for a highlighted entity's filled sides.
     */
    private static final int HIT_FILL_ALPHA = 60;

    /**
     * How long a detonated lingering throw waits for its cloud entity to appear.
     */
    private static final int CLOUD_ADOPT_TIMEOUT_TICKS = 20;
    /**
     * Max distance between a detonation point and the cloud it spawned, squared.
     */
    private static final double CLOUD_ADOPT_RADIUS_SQ = 64.0;
    /**
     * {@code AreaEffectCloud.getDimensions} returns {@code EntityDimensions.scalable(radius*2, 0.5f)},
     * so the cloud's own box - the one its broad phase is built from - is half a block tall.
     */
    private static final double CLOUD_HEIGHT = 0.5;

    public void clear() {
        bursts.clear();
        clouds.clear();
        trackedSplash.clear();
        trackedLingering.clear();
        cloudInfo.clear();
        pendingClouds.clear();
    }

    /**
     * Runs every tick, deliberately outside {@code BlockRadius}'s update-interval gate (same
     * reasoning as the sponge preview): a thrown potion lives ~1.5s and detonates between
     * throttled scans, so a 20-tick cadence would miss most bursts outright.
     *
     * <p>Cost is bounded: one pass over {@code entitiesForRendering()} plus one narrow-phase
     * entity query per active burst/cloud, and both are gated behind a distance check.
     */
    public void scan() {
        if (mc.level == null) return;

        long now = mc.level.getGameTime();
        bursts.removeIf(b -> b.expiryTick <= now);

        if (!showAoe.get()) {
            // Keep tracking state consistent while hidden, so re-enabling does not emit a burst
            // for every potion that vanished during the time the module was off.
            trackedSplash.clear();
            trackedLingering.clear();
            clouds.clear();
            cloudInfo.clear();
            pendingClouds.clear();
            return;
        }

        PotionMode mode = potionMode.get();

        // -- splash: disappearance == detonation -----------------------------------------
        if (mode == PotionMode.Splash || mode == PotionMode.Both) {
            detectBursts(trackedSplash, true);
        } else {
            trackedSplash.clear();
        }

        // -- lingering: track the throw, then follow the cloud it becomes ------------------
        if (mode == PotionMode.Lingering || mode == PotionMode.Both) {
            detectBursts(trackedLingering, false);
            rebuildClouds(now);
        } else {
            trackedLingering.clear();
            clouds.clear();
            cloudInfo.clear();
            pendingClouds.clear();
        }
    }

    /**
     * Shared body for both projectile types: anything that vanishes from the entity list
     * detonated where we last saw it. Keeping the last known position (rather than the vanishing
     * one) is the whole trick - by the time the entity is gone the client has no position left.
     *
     * <p>Order is load-bearing: burst detection runs <b>first</b>, while {@link #preBurst} still
     * holds the previous tick's effect state, and only then is the snapshot refreshed. Refreshing
     * first would overwrite it with the already-applied effect and reintroduce the race.
     */
    private void detectBursts(Map<Integer, Tracked> tracked, boolean splash) {
        Iterator<Map.Entry<Integer, Tracked>> it = tracked.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Tracked> entry = it.next();
            if (findEntity(entry.getKey()) != null) continue;
            it.remove();

            Tracked t = entry.getValue();
            if (t.stack() == null || t.stack().isEmpty()) continue;

            if (splash) registerBurst(t);
                // A lingering throw does not burst: it becomes a cloud entity. Queue the adoption so
                // the next scan can bind that specific stack to the cloud it spawned.
            else pendingClouds.add(new PendingCloud(t.pos(), t.stack(),
                mc.level.getGameTime() + CLOUD_ADOPT_TIMEOUT_TICKS));
        }

        for (Entity e : mc.level.entitiesForRendering()) {
            if (e.isRemoved()) continue;
            if (splash ? e instanceof ThrownSplashPotion : e instanceof ThrownLingeringPotion) {
                tracked.put(e.getId(), new Tracked(e.position(), e.tickCount, getItemOf(e)));
            }
        }

        if (splash) refreshPreBurst(tracked);
    }

    /**
     * Samples the effect state of everything a still-in-flight potion could reach, so the burst
     * can be resolved against what the server saw rather than what it produced. Bounded by the
     * splash box itself, so the cost is a single entity walk.
     */
    private void refreshPreBurst(Map<Integer, Tracked> tracked) {
        preBurst.clear();
        if (tracked.isEmpty()) return;

        for (Tracked t : tracked.values()) {
            List<MobEffectInstance> effects = effectsOf(t.stack(), effectScratch);
            if (effects.isEmpty()) continue;

            AABB area = splashBox(t.pos());
            float margin = marginFor(t.tickCount());

            for (Entity e : mc.level.entitiesForRendering()) {
                if (!(e instanceof LivingEntity le)) continue;
                if (le.isRemoved() || !le.isAlive()) continue;
                if (!le.isAffectedByPotions()) continue;
                if (le.getBoundingBox().inflate(margin).distanceToSqr(area) > 16.0) continue;

                Map<MobEffect, int[]> byEffect =
                    preBurst.computeIfAbsent(le.getId(), k -> new HashMap<>());
                for (MobEffectInstance potion : effects) {
                    MobEffectInstance cur = le.getEffect(potion.getEffect());
                    byEffect.putIfAbsent(potion.getEffect().value(),
                        new int[]{cur == null ? -1 : cur.getAmplifier(),
                            cur == null ? -1 : cur.getDuration()});
                }
            }
        }
    }

    /**
     * The exact box vanilla builds: the projectile's 0.25 bbox inflated by (4, 2, 4).
     */
    private static AABB splashBox(Vec3 at) {
        return new AABB(at.x - 0.25, at.y - 0.25, at.z - 0.25,
            at.x + 0.25, at.y + 0.25, at.z + 0.25).inflate(4.0, 2.0, 4.0);
    }

    private static ItemStack getItemOf(Entity e) {
        if (e instanceof ThrownSplashPotion p) return p.getItem();
        if (e instanceof ThrownLingeringPotion p) return p.getItem();
        return ItemStack.EMPTY;
    }

    private void registerBurst(Tracked t) {
        List<MobEffectInstance> effects = effectsOf(t.stack(), effectScratch);
        if (effects.isEmpty()) return;
        if (!passesAmplifierFilter(effects)) return;

        // potionBox = bbox.move(hitLocation - position()).inflate(4, 2, 4). The burst point is the
        // last position we saw, so the box is the projectile's own 0.25 bbox grown by that inflate.
        AABB area = new AABB(t.pos().x - 0.25, t.pos().y - 0.25, t.pos().z - 0.25,
            t.pos().x + 0.25, t.pos().y + 0.25, t.pos().z + 0.25)
            .inflate(4.0, 2.0, 4.0);

        float margin = marginFor(t.tickCount());

        resolveHits(splashBox(t.pos()), effects, margin, hitScratch, t.pos(), true,
            durationScaleOf(t.stack()));
        int direct = directHitId;

        bursts.add(new Burst(area, t.pos(), labelOf(t.stack()), colorOf(effects),
            direct, mc.level.getGameTime() + burstTtlTicks.get(), new ArrayList<>(hitScratch)));
    }

    /**
     * {@code ProjectileUtil.computeMargin}: {@code clamp((tickCount - 2) / 20, 0, 0.3)}.
     */
    private static float marginFor(int tickCount) {
        return Math.max(0.0f, Math.min(0.3f, (tickCount - 2) / 20.0f));
    }

    /**
     * The stack's {@code POTION_DURATION_SCALE}, defaulting to 1. Vanilla multiplies it into
     * the same product as the splash falloff, so a scaled potion starts failing the 20-tick
     * discard closer to the centre than an unscaled one.
     */
    private static float durationScaleOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 1.0f;
        return stack.getOrDefault(DataComponents.POTION_DURATION_SCALE, 1.0f);
    }

    /**
     * The splash narrow phase, exactly as vanilla runs it: box-to-box squared distance against
     * the inflated potion box, gated on {@code isAffectedByPotions()}, then the distance
     * falloff and the 20-tick discard.
     *
     * <p>Also records {@link #directHitId} - the caught entity nearest the burst centre, i.e. the
     * one the projectile struck. It is derived from the entities that actually received an effect
     * rather than from a separate "closest entity" walk: the nearest entity to the impact point is
     * not necessarily a target at all (it may be immune, or out of the 4-block range), and
     * highlighting one of those would point the direct-hit colour at a mob the potion never
     * touched. Rim entities that fall under the 20-tick cutoff are excluded for the same reason.
     *
     * <p>{@code useSnapshot} selects the {@link #preBurst} sample instead of live effect state.
     * Splash must use it (see that field); lingering must not, because a cloud keeps re-applying
     * and the live state genuinely is what the next application will be compared against.
     *
     * <p>{@code durationScale} is the stack's {@code POTION_DURATION_SCALE}. Vanilla folds it
     * into the same product as the falloff, so a scaled-down potion crosses the 20-tick cutoff
     * closer in than an unscaled one.
     */
    private void resolveHits(AABB area, List<MobEffectInstance> effects, float margin,
                             List<Hit> out, Vec3 center, boolean useSnapshot,
                             float durationScale) {
        out.clear();
        directHitId = -1;
        double nearestSq = Double.MAX_VALUE;

        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity le)) continue;
            if (le.isRemoved() || !le.isAlive()) continue;
            if (!le.isAffectedByPotions()) continue;

            AABB box = le.getBoundingBox().inflate(margin);
            double distSq = box.distanceToSqr(area);
            if (distSq > 16.0) continue;

            // Vanilla: `double d = 1.0 - Math.sqrt(distSq) / 4.0`. This is a DURATION falloff
            // only - the amplifier is passed through untouched, so everyone inside the box gets
            // the same strength and only the duration degrades with distance. Hence `Hit` no
            // longer carries a per-entity amplifier: it was a constant wearing a per-entity hat.
            double falloff = 1.0 - Math.sqrt(distSq) / 4.0;

            // Vanilla: `mapDuration(i -> (int) (d * i * durationScale + 0.5))`, then
            // `if (!instance.endsWithin(20)) addEffect(...)`. A mob at the rim therefore gets
            // NOTHING at all - it passes the distance test and is then silently dropped. The
            // arithmetic is inlined rather than routed through mapDuration so no lambda is
            // allocated per entity per effect; the expression is character-for-character the
            // same one the server evaluates.
            boolean applies = false;
            boolean wins = false;
            Map<MobEffect, int[]> snap = useSnapshot ? preBurst.get(le.getId()) : null;
            for (MobEffectInstance potion : effects) {
                // Instantaneous effects (instant health/damage) do NOT go through the duration path
                // at all: vanilla branches to `applyInstantaneousEffect(level, ...)` with no
                // `endsWithin(20)` discard, so they are the one case the falloff must never drop.
                // Treating them like a normal effect is why a healing/harming splash reported every
                // target as "no effect": their duration is 1, so `scaled` was always <= 20. An
                // infinite-duration effect is likewise never discarded (`endsWithin` returns false).
                if (potion.getEffect().value().isInstantenous() || potion.isInfiniteDuration()) {
                    applies = true;
                    if (winsAgainst(potion, potion.getDuration(), snap, useSnapshot, le)) {
                        wins = true;
                        break;
                    }
                    continue;
                }
                int scaled = (int) (falloff * potion.getDuration() * durationScale + 0.5);
                // Per-effect, exactly like the server: a turtle master's two effects are judged
                // independently, and it only takes one of them surviving.
                // `endsWithin(20)` includes 20 (bytecode: `duration <= ticks`), so the boundary is
                // `scaled <= 20`, not `<`.
                if (scaled <= 20) continue;
                applies = true;
                if (winsAgainst(potion, scaled, snap, useSnapshot, le)) {
                    wins = true;
                    break;
                }
            }
            if (!applies) {
                // Inside the box but the falloff leaves nothing the server will apply. Still worth
                // showing - the potion provably cannot touch this entity - but it is NOT a direct
                // hit: the projectile never affected it, so it must not take the direct-hit
                // highlight away from an entity the potion actually reached.
                out.add(new Hit(le.getId(), false));
                continue;
            }

            double d = le.position().distanceToSqr(center);
            if (d < nearestSq) {
                nearestSq = d;
                directHitId = le.getId();
            }

            out.add(new Hit(le.getId(), wins));
        }
    }

    /**
     * @param scaledDuration the duration <em>after</em> the distance falloff. Vanilla compares
     *                       the scaled instance against the entity's current effect, so feeding
     *                       the unscaled potion duration here would let a rim hit - whose real
     *                       duration is a fraction of a second, be reported as beating an effect
     *                       it could not actually replace.
     */
    private static boolean winsAgainst(MobEffectInstance potion, int scaledDuration,
                                       Map<MobEffect, int[]> snap, boolean useSnapshot,
                                       LivingEntity target) {
        if (useSnapshot && snap != null) {
            int[] rec = snap.get(potion.getEffect().value());
            // No sample for this entity means it was outside the sampled set entirely, i.e. the
            // entity only came into range on the detonating tick. Fall back to live state, which
            // is the best available answer for a genuinely new target - still comparing against
            // the SCALED duration, since that is what the server will compare.
            if (rec == null) {
                MobEffectInstance cur = target.getEffect(potion.getEffect());
                return cur == null || effectWins(cur.getAmplifier(), cur.getDuration(),
                    potion.getAmplifier(), scaledDuration);
            }
            return rec[0] < 0 || effectWins(rec[0], rec[1], potion.getAmplifier(), scaledDuration);
        }
        MobEffectInstance current = target.getEffect(potion.getEffect());
        if (current == null) return true;
        return effectWins(current.getAmplifier(), current.getDuration(),
            potion.getAmplifier(), scaledDuration);
    }

    private static boolean effectWins(int currentAmp, int currentDur, int inAmp, int inDur) {
        // MobEffectInstance.update, verbatim: a stronger incoming effect wins outright; a weaker
        // one never does (vanilla only files it away as a hiddenEffect and leaves the current one
        // active); an equal-amplifier effect wins only when the current effect is shorter
        // (`!current.isInfiniteDuration() && (currentDur < inDur || incoming is infinite)`).
        if (inAmp > currentAmp) return true;
        if (inAmp < currentAmp) return false;
        if (currentDur == MobEffectInstance.INFINITE_DURATION) return false;
        return inDur == MobEffectInstance.INFINITE_DURATION || inDur > currentDur;
    }

    private boolean passesAmplifierFilter(List<MobEffectInstance> effects) {
        int min = minAmplifier.get();
        if (min <= 0) return true;
        for (MobEffectInstance e : effects) if (e.getAmplifier() >= min) return true;
        return false;
    }

    /**
     * Lingering cloud views, rebuilt every tick: the radius animates and targets move.
     */
    private void rebuildClouds(long now) {
        clouds.clear();
        List<AreaEffectCloud> live = findClouds();

        // Bind any newly-spawned clouds to the stack of the throw that made them. Matching by
        // nearest spawn point is what keeps two simultaneous lingering potions distinct.
        if (!pendingClouds.isEmpty()) {
            for (AreaEffectCloud cloud : live) {
                if (cloudInfo.containsKey(cloud.getId())) continue;

                int best = -1;
                double bestDist = CLOUD_ADOPT_RADIUS_SQ;
                for (int i = 0; i < pendingClouds.size(); i++) {
                    double d = cloud.position().distanceToSqr(pendingClouds.get(i).at());
                    if (d < bestDist) {
                        bestDist = d;
                        best = i;
                    }
                }
                if (best < 0) continue;

                PotionInfo info = describe(pendingClouds.get(best).stack());
                cloudInfo.put(cloud.getId(), info);
                pendingClouds.remove(best);
            }
            pendingClouds.removeIf(p -> p.giveUpTick() <= now);
        }

        // Drop bindings for clouds that are gone, so the map does not grow without bound.
        if (!cloudInfo.isEmpty()) {
            Iterator<Map.Entry<Integer, PotionInfo>> it = cloudInfo.entrySet().iterator();
            while (it.hasNext()) {
                if (findEntity(it.next().getKey()) == null) it.remove();
            }
        }

        for (AreaEffectCloud cloud : live) {
            if (cloud.getRadius() <= 0) continue;
            PotionInfo info = cloudInfo.get(cloud.getId());
            if (info == null) continue;   // not ours / never bound
            // The splash path drops a filtered potion outright in `registerBurst`, so a cloud made
            // by one must not be drawn or hit-tested either. Gated here rather than at binding:
            // binding must stay "nearest throw wins", because a cloud left unbound is re-matched
            // every tick and would eventually claim the next throw's entry.
            if (!passesAmplifierFilter(info.effects())) continue;
            clouds.add(new CloudView(cloud, info.effects(), info.label(), info.potionColor(),
                info.durationScale()));
        }
    }

    private PotionInfo describe(ItemStack stack) {
        List<MobEffectInstance> effects = effectsOf(stack, effectScratch);
        return new PotionInfo(new ArrayList<>(effects), labelOf(stack), colorOf(effects),
            durationScaleOf(stack));
    }

    /**
     * Single walk over the entity list per scan, collected once and reused.
     */
    private List<AreaEffectCloud> findClouds() {
        cloudScratch.clear();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof AreaEffectCloud cloud && !cloud.isRemoved()) cloudScratch.add(cloud);
        }
        return cloudScratch;
    }

    private Entity findEntity(int id) {
        for (Entity e : mc.level.entitiesForRendering()) if (e.getId() == id) return e;
        return null;
    }

    private static List<MobEffectInstance> effectsOf(ItemStack stack, List<MobEffectInstance> out) {
        out.clear();
        if (stack == null || stack.isEmpty()) return out;
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null) return out;
        for (MobEffectInstance e : contents.getAllEffects()) out.add(e);
        return out;
    }

    private static String labelOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        return stack.getHoverName().getString();
    }

    private static int colorOf(List<MobEffectInstance> effects) {
        return PotionContents.getColorOptional(effects).orElse(-1);
    }

    public void render(Render3DEvent event, Vec3 cam, double maxDistSq) {
        if (!showAoe.get()) return;
        if (bursts.isEmpty() && clouds.isEmpty()) return;

        boolean wantShape = showAoeShape.get();
        boolean wantHits = showHitEntities.get();
        if (!wantShape && !wantHits) return;

        for (Burst b : bursts) {
            if (cam.distanceToSqr(b.center) > maxDistSq) continue;
            if (wantShape) renderShape(event, b.area, b.potionColor);
            if (wantHits) renderHits(event, b.hits, b.directEntityId);
        }

        for (CloudView c : clouds) {
            Vec3 centre = c.cloud().position();
            if (cam.distanceToSqr(centre) > maxDistSq) continue;

            // Live: recomputed here rather than cached, because a cloud keeps re-applying for up
            // to 600 ticks while mobs move through it.
            if (wantHits) {
                List<MobEffectInstance> effects = c.effects();
                if (!effects.isEmpty()) {
                    // Vanilla runs TWO tests, and dropping either one changes the result:
                    //
                    // 1. Broad phase - `getEntitiesOfClass(LivingEntity.class, this.getBoundingBox())`.
                    //    The cloud overrides `getDimensions` to return
                    //    `EntityDimensions.scalable(radius * 2.0f, 0.5f)`, so that box is 2r wide
                    //    but only HALF A BLOCK tall, and `makeBoundingBox` anchors it at the feet
                    //    (y .. y+0.5). This is where the vertical constraint lives: a mob on a
                    //    floor above the cloud, or in a cave below it, is never a candidate.
                    // 2. Narrow phase - flat horizontal only:
                    //        double dx = e.getX() - cloud.getX();  double dz = e.getZ() - cloud.getZ();
                    //        if (dx*dx + dz*dz > radius*radius) continue;
                    //    Feet positions, no vertical term.
                    //
                    // Neither test subsumes the other. The box alone is too permissive
                    // horizontally (a mob whose corner overlaps the square can have its centre up
                    // to half a body-width beyond r); the flat test alone is far too permissive
                    // vertically, since it would happily include mobs on a different floor.
                    float r = c.cloud().getRadius();
                    if (r <= 0) continue;
                    double rSq = r * r;
                    AABB cloudBox = c.cloud().getBoundingBox();
                    hitScratch.clear();
                    for (Entity e : mc.level.entitiesForRendering()) {
                        if (!(e instanceof LivingEntity le)) continue;
                        if (le.isRemoved() || !le.isAlive()) continue;
                        if (!le.isAffectedByPotions()) continue;
                        if (!le.getBoundingBox().intersects(cloudBox)) continue;
                        if (DistanceUtil.distanceSq(c.cloud(), le, false) > rSq) continue;

                        // No distance falloff exists for a cloud in 26.2 - every entity inside the
                        // radius gets an identical `new MobEffectInstance(existing)` copy, same
                        // amplifier and same duration. So a cloud has no "discarded" outcome and
                        // the rim is not a distinct case the way it is for splash.
                        //
                        // The duration the cloud will actually apply is the effect SCALED by the
                        // cloud's `potionDurationScale` (`PotionContents.forEachEffect(_, scale)`
                        // -> `MobEffectInstance.withScaledDuration(scale)` -> `max(1, floor(dur *
                        // scale))`), and lingering potions carry a scale below 1. Comparing the
                        // raw duration would report a win where vanilla keeps the existing effect.
                        // The cloud's own scale is unreadable client-side (the field has a setter
                        // but no getter, and it is not a synced accessor), so the bound ItemStack's
                        // value is used instead - identical, since it was copied from that stack.
                        float scale = c.durationScale();
                        boolean wins = false;
                        for (MobEffectInstance potion : effects) {
                            MobEffectInstance cur = le.getEffect(potion.getEffect());
                            int dur = (potion.isInfiniteDuration() || potion.getDuration() == 0)
                                ? potion.getDuration()
                                : Math.max(1, (int) Math.floor(potion.getDuration() * scale));
                            if (cur == null || effectWins(cur.getAmplifier(), cur.getDuration(),
                                potion.getAmplifier(), dur)) {
                                wins = true;
                                break;
                            }
                        }
                        hitScratch.add(new Hit(le.getId(), wins));
                    }
                    renderHits(event, hitScratch, -1);
                }
            }
            if (wantShape) {
                float r = c.cloud().getRadius();
                if (r > 0) {
                    // The real affected region is the 2r-wide box ANDed with the radius disc,
                    // and the box is only 0.5 tall - so the honest shape is a flat slab, not the
                    // 2r cube this used to draw. Built from the live radius rather than from
                    // `getBoundingBox()`: the radius animates and `refreshDimensions()` lags it,
                    // so the entity box would be a tick stale while the shape must not be.
                    // Anchored at the feet, matching `EntityDimensions.makeBoundingBox`.
                    renderShape(event, new AABB(centre.x - r, centre.y, centre.z - r,
                        centre.x + r, centre.y + CLOUD_HEIGHT, centre.z + r), c.potionColor());
                }
            }
        }
    }

    private void renderShape(Render3DEvent event, AABB area, int potionColor) {
        SettingColor color = shapeColor(potionColor);
        // Meteor's own AABB overload, the same one TrajectoryPlus uses, instead of unpacking
        // minX/maxX by hand. shape-mode is the module-wide shared setting, so the shared
        // ShapeMode still decides whether lines and/or sides are drawn.
        shared.renderBox(event.renderer, area, translucent(color, AOE_FILL_ALPHA), color);
    }

    private void renderHits(Render3DEvent event, List<Hit> hits, int directId) {
        boolean directEnabled = highlightDirectHit.get();
        SettingColor full = hitColorFull.get();
        SettingColor weak = hitColorWeak.get();
        SettingColor direct = directColor.get();

        // Translucent fill variants are resolved once per frame, not per entity. The previous
        // version allocated a fresh SettingColor inside the loop, so a lingering cloud over a
        // crowd produced one throwaway object per highlighted mob per frame - the exact cost
        // `renderer.box(AABB, ...)` plus hoisted colours avoids.
        SettingColor fullFill = translucent(full, HIT_FILL_ALPHA);
        SettingColor weakFill = translucent(weak, HIT_FILL_ALPHA);
        SettingColor directFill = translucent(direct, HIT_FILL_ALPHA);

        for (Hit hit : hits) {
            Entity e = findEntity(hit.entityId());
            if (e == null) continue;

            // Two outcomes: the potion overwrote the entity's effect (full) or it did nothing to
            // it (weak) - the latter covering both "the existing effect is stronger" and "the
            // falloff discarded the effect outright". Direct-hit wins over both: it identifies
            // which entity the projectile struck, independent of what the potion then did to it.
            boolean isDirect = directEnabled && hit.entityId() == directId;
            SettingColor color, fill;
            if (isDirect) {
                color = direct;
                fill = directFill;
            } else if (hit.wins()) {
                color = full;
                fill = fullFill;
            } else {
                color = weak;
                fill = weakFill;
            }

            // Boxed exactly the way TrajectoryPlus draws its collision highlights: the raw
            // getBoundingBox() is last tick's, so offset it by the tick's movement scaled by
            // tickDelta. Going through the 8-double renderBox keeps BlockRadius's own
            // shape-mode setting in charge of lines vs sides.
            Vec3 old = e.oldPosition();
            double x = (e.getX() - old.x) * event.tickDelta;
            double y = (e.getY() - old.y) * event.tickDelta;
            double z = (e.getZ() - old.z) * event.tickDelta;

            AABB box = e.getBoundingBox();
            shared.renderBox(event.renderer,
                x + box.minX, y + box.minY, z + box.minZ,
                x + box.maxX, y + box.maxY, z + box.maxZ,
                fill, color);
        }
    }

    /**
     * Caches the translucent variants against the base colour they were built from, turning a
     * per-entity allocation into a map lookup. Both the base (a user setting) and the potion tint
     * (per potion) change rarely.
     *
     * <p>SettingColor is <em>not</em> immutable it has public r/g/b/a and the settings GUI
     * mutates the very same instance when the user edits a colour. That is safe here only
     * because {@code Color.equals}/{@code hashCode} are value-based on the channels: editing a
     * colour changes the key's hash, the lookup misses, and the value is rebuilt from the current
     * channels. It costs one unreachable map entry per edit, which is nothing.
     *
     * <p>Do not "optimize" this into an identity-keyed cache. That would make the key stop
     * changing when the colour does, and the entry built from the old RGB would then be returned
     * forever, a stale-fill bug with no error anywhere.
     */
    private SettingColor translucent(SettingColor base, int alpha) {
        SettingColor cached = fillCache.get(base);
        if (cached != null && cached.a == alpha) return cached;

        SettingColor made = new SettingColor(base.r, base.g, base.b, alpha);
        fillCache.put(base, made);
        return made;
    }

    private SettingColor shapeColor(int potionColor) {
        if (tintByPotionColor.get() && potionColor != -1) {
            SettingColor cached = potionTintCache;
            if (cached != null && cached.getPacked() == ARGB.opaque(potionColor)) return cached;
            cached = new SettingColor(ARGB.opaque(potionColor));
            potionTintCache = cached;
            return cached;
        }
        return aoeColor.get();
    }

    // labels
    public void render2D(Render2DEvent event, Vec3 cam, double maxDistSq) {
        if (!showAoe.get() || !showEffectName.get()) return;

        double scale = effectLabelScale.get();

        for (Burst b : bursts) {
            if (b.label.isEmpty()) continue;
            if (cam.distanceToSqr(b.center) > maxDistSq) continue;
            // Above the AoE box.
            drawLabel(event, b.label, b.center.x, b.area.maxY + 0.4, b.center.z,
                shapeColor(b.potionColor), scale);
        }

        for (CloudView c : clouds) {
            if (c.label.isEmpty()) continue;
            Vec3 p = c.cloud().position();
            if (cam.distanceToSqr(p) > maxDistSq) continue;
            drawLabel(event, c.label, p.x, p.y + c.cloud().getRadius() + 0.4, p.z,
                shapeColor(c.potionColor()), scale);
        }
    }

    private void drawLabel(Render2DEvent event, String text, double x, double y, double z,
                           SettingColor color, double scale) {
        try {
            Vector3d joml = new Vector3d(x, y, z);
            if (!NametagUtils.to2D(joml, scale)) return;

            NametagUtils.begin(joml, event.graphics);
            TextRenderer.get().begin(1, false, true);

            double w = TextRenderer.get().getWidth(text) / 2;
            double h = TextRenderer.get().getHeight() / 2;
            TextRenderer.get().render(text, -w, -h, color, true);

            TextRenderer.get().end();
            NametagUtils.end(event.graphics);
        } catch (Throwable ignored) {
            // A label failure must never take the rest of the frame's rendering down with it.
        }
    }
}
