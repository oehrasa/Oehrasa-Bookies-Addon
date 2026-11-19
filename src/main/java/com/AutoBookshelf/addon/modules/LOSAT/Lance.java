/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.LOSAT;

import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.events.PlayerPositionLookEvent;
import com.AutoBookshelf.addon.mixin.accessor.LivingEntityAccessor;
import com.AutoBookshelf.addon.mixin.accessor.PlayerMoveC2SPacketAccessor;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.entity.player.SendMovementPacketsEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.LocalPlayerAccessor;
import meteordevelopment.meteorclient.mixininterface.IServerboundMovePlayerPacket;
import meteordevelopment.meteorclient.mixininterface.IVec3;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.meteorclient.utils.entity.DamageUtils;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.item.component.KineticWeapon;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Lance extends Module {
    private static final int TAG = 1337;
    private static final double MAX_H = 2.8;
    private static final double MAX_V = 1.4;
    private static final double EYE = 0.4;
    private static final double RAY_MARGIN = 0.125;

    private record Tier(int delay, int window, double mult) {
    }

    private record CrystalPlacer(LanceCrystalSafety.Point eye, double reach) {
    }

    private static Tier tier(Item i) {
        if (i == Items.NETHERITE_SPEAR) return new Tier(8, 175, 1.2);
        if (i == Items.DIAMOND_SPEAR) return new Tier(10, 200, 1.075);
        if (i == Items.IRON_SPEAR) return new Tier(12, 225, 0.95);
        if (i == Items.COPPER_SPEAR) return new Tier(13, 250, 0.82);
        if (i == Items.STONE_SPEAR) return new Tier(14, 275, 0.82);
        if (i == Items.GOLDEN_SPEAR) return new Tier(14, 275, 0.7);
        if (i == Items.WOODEN_SPEAR) return new Tier(15, 300, 0.7);
        return null;
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPerch = settings.createGroup("Perch");
    private final SettingGroup sgLance = settings.createGroup("Lance");
    private final SettingGroup sgWeb = settings.createGroup("Webbing");

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Print debug messages in chat.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Set<EntityType<?>>> entities = sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("entities")
        .description("Which attackable entities to hunt.")
        .onlyAttackable()
        .build()
    );

    private final Setting<Double> fov = sgGeneral.add(new DoubleSetting.Builder()
        .name("fov")
        .description("View cone in degrees for acquiring new targets.")
        .defaultValue(90)
        .min(1)
        .max(360)
        .build()
    );

    private final Setting<SortPriority> priority = sgGeneral.add(new EnumSetting.Builder<SortPriority>()
        .name("priority")
        .description("Which entity to pick first.")
        .defaultValue(SortPriority.ClosestAngle)
        .build()
    );

    private final Setting<Double> lockRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("lock-range")
        .description("Unlock the target when it is beyond this distance in blocks.")
        .defaultValue(64)
        .min(16)
        .max(192)
        .sliderRange(24, 128)
        .build()
    );

    private final Setting<Boolean> ignoreWalls = sgGeneral.add(new BoolSetting.Builder()
        .name("through-walls")
        .description("Keep the locked target without line of sight.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> follow = sgPerch.add(new BoolSetting.Builder()
        .name("follow")
        .description("Fly your body after the target.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> hoverHeight = sgPerch.add(new DoubleSetting.Builder()
        .name("hover-height")
        .description("Height of your body above the target while following.")
        .defaultValue(20)
        .min(5)
        .max(30)
        .sliderRange(8, 28)
        .visible(follow::get)
        .build()
    );

    private final Setting<Double> followSpeed = sgPerch.add(new DoubleSetting.Builder()
        .name("follow-speed")
        .description("Chase speed in blocks per tick.")
        .defaultValue(3.0)
        .min(0.5)
        .max(3.0)
        .sliderRange(0.5, 3.0)
        .visible(follow::get)
        .build()
    );

    private final Setting<Double> standoff = sgLance.add(new DoubleSetting.Builder()
        .name("standoff")
        .description("Ghost rest height above the target's head.")
        .defaultValue(4.8)
        .min(3.5)
        .max(9)
        .sliderRange(4, 8)
        .build()
    );

    private final Setting<Double> strikeHeight = sgLance.add(new DoubleSetting.Builder()
        .name("strike-height")
        .description("Ghost eye height above the target at the strike bottom.")
        .defaultValue(3.6)
        .min(2.5)
        .max(4.4)
        .sliderRange(2.5, 4.4)
        .build()
    );

    private final Setting<Double> dip = sgLance.add(new DoubleSetting.Builder()
        .name("dip")
        .description("Vertical strike dip in one packet. Bigger dips hit harder.")
        .defaultValue(1.6)
        .min(1.0)
        .max(2.0)
        .sliderRange(1.0, 2.0)
        .build()
    );

    private final Setting<Integer> period = sgLance.add(new IntSetting.Builder()
        .name("period")
        .description("Minimum ticks between strikes.")
        .defaultValue(10)
        .min(10)
        .max(40)
        .sliderRange(10, 30)
        .build()
    );

    private final Setting<Double> crystalClearance = sgLance.add(new DoubleSetting.Builder()
        .name("crystal-clearance")
        .description("Hold strikes near end crystals within this distance. Set to 0 to ignore crystals.")
        .defaultValue(4)
        .min(0)
        .max(12)
        .sliderRange(0, 10)
        .build()
    );

    public enum CrystalPolicy {EstimatedDamage, Distance}

    private final Setting<CrystalPolicy> crystalPolicy = sgLance.add(new EnumSetting.Builder<CrystalPolicy>()
        .name("crystal-policy")
        .description("Judge crystal danger by estimated damage or by distance.")
        .defaultValue(CrystalPolicy.EstimatedDamage)
        .build()
    );

    private final Setting<Double> maxCrystalDamage = sgLance.add(new DoubleSetting.Builder()
        .name("max-crystal-damage")
        .description("Preferred crystal damage limit in HP.")
        .defaultValue(0.9)
        .min(0)
        .max(20)
        .sliderRange(0, 4)
        .visible(() -> crystalPolicy.get() == CrystalPolicy.EstimatedDamage)
        .build()
    );

    private final Setting<Boolean> strictCrystalLimit = sgLance.add(new BoolSetting.Builder()
        .name("strict-crystal-limit")
        .description("Make the crystal damage limit a hard limit that blocks the strike.")
        .defaultValue(false)
        .visible(() -> crystalPolicy.get() == CrystalPolicy.EstimatedDamage)
        .build()
    );

    private final Setting<Boolean> horizontalFallback = sgLance.add(new BoolSetting.Builder()
        .name("horizontal-fallback")
        .description("Use a sideways lane when the vertical lane is blocked by a ceiling.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> maxOffset = sgLance.add(new DoubleSetting.Builder()
        .name("max-offset")
        .description("Maximum distance between the ghost and your body.")
        .defaultValue(30)
        .min(8)
        .max(45)
        .sliderRange(10, 40)
        .build()
    );

    private final Setting<Boolean> autoSwap = sgLance.add(new BoolSetting.Builder()
        .name("auto-swap")
        .description("Swap to the best spear in your hotbar.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chatDebug = sgLance.add(new BoolSetting.Builder()
        .name("chat-debug")
        .description("Print telemetry in chat once a second.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> fileDebug = sgLance.add(new BoolSetting.Builder()
        .name("debug-file")
        .description("Write diagnostics to the lance-debug folder in the game directory.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> webEnabled = sgWeb.add(new BoolSetting.Builder()
        .name("web")
        .description("Web the target when it tries to flee.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> webPlayersOnly = sgWeb.add(new BoolSetting.Builder()
        .name("players-only")
        .description("Only web players, not mobs.")
        .defaultValue(true)
        .visible(webEnabled::get)
        .build()
    );

    private final Setting<Integer> websPerTick = sgWeb.add(new IntSetting.Builder()
        .name("webs-per-tick")
        .description("Maximum web placements per tick.")
        .defaultValue(6)
        .min(1)
        .max(18)
        .sliderRange(1, 18)
        .visible(webEnabled::get)
        .build()
    );

    private final Setting<Double> webRange = sgWeb.add(new DoubleSetting.Builder()
        .name("range")
        .description("Maximum reach for placing webs.")
        .defaultValue(5.4)
        .min(1)
        .max(5.5)
        .sliderRange(3, 5.5)
        .visible(webEnabled::get)
        .build()
    );

    private final Setting<Boolean> webAirPlace = sgWeb.add(new BoolSetting.Builder()
        .name("air-place")
        .description("Allow placing webs in mid air.")
        .defaultValue(true)
        .visible(webEnabled::get)
        .build()
    );

    private Entity target;
    private int blindTicks;
    private final Setting<Boolean> clearSelfWebs = sgWeb.add(new BoolSetting.Builder()
        .name("clear-self-webs")
        .description("Break your own webs to get out of them.")
        .defaultValue(true)
        .build()
    );


    private double ox, oy, oz;
    private Vec3 lastClaim;
    private Vec3 returnClaim;
    private boolean protectMovementThisTick;
    private int protectedWireTick = -1000;
    private Vec3 lastWire;
    private int lastWireTick = -1000;
    private boolean wireSyncWanted;
    private int claimsSinceSetback;
    private int lastClaimWireTick = -1000;
    private boolean posSentThisTick;
    private int cycleTick;
    private final LanceBeatTiming beat = new LanceBeatTiming();
    private final LanceApproachPriority approachPriority = new LanceApproachPriority();
    private LanceAttackMath.Lane attackLane;
    private Vec3 strikeAxis = new Vec3(0, -1, 0);
    private final List<LanceCrystalSafety.Point> crystalPositions = new ArrayList<>();
    private final List<LanceCrystalSafety.Point> potentialCrystalPositions = new ArrayList<>();
    private final Map<LanceAttackMath.Lane, Double> laneDamageCache = new HashMap<>();
    private final Map<LanceAttackMath.Lane, Double> potentialLaneDamageCache = new HashMap<>();
    private int crystalPlacers;
    private final Map<Vec3, Double> crystalDamageCache = new HashMap<>();
    private ExplosionReductions crystalReductions;
    private double plannedCrystalDamage;
    private int crystalRefreshTick = Integer.MIN_VALUE;
    private int plannedAimTick = Integer.MIN_VALUE;
    private float plannedYaw, plannedPitch;
    private float lastSelfHp;
    private int cooldown;
    private int setbacks, passiveSetbacks;
    private int lastSetbackAt = -1000;
    private int blockedTicks, homeBlocked;
    private boolean terrainBlocked;
    private int tickCounter;
    private boolean correcting;
    private boolean leavingWorld;
    private LocalPlayer ownerPlayer;
    private ClientLevel ownerWorld;
    private ClientPacketListener ownerNetwork;

    private record EchoOwner(Connection connection, int playerId) {
    }

    private volatile EchoOwner echoOwner;

    private record GlideEcho(EchoOwner owner, boolean gliding) {
    }

    private final ConcurrentLinkedQueue<GlideEcho> glideEchoes = new ConcurrentLinkedQueue<>();

    private record UseEcho(EchoOwner owner, long generation, boolean using) {
    }

    private final ConcurrentLinkedQueue<UseEcho> useEchoes = new ConcurrentLinkedQueue<>();
    private final LanceCouchTiming couchTiming = new LanceCouchTiming();
    private volatile long couchGeneration;

    private Boolean srvGliding;
    private boolean glideRecovery, recoveryJumped;
    private double recoveryVy;
    private int recoveryJumpTick = -1000;
    private static final boolean FORK_SYNC = detectForkSync();
    private int relightPending, relights;
    private boolean relightArmed, relightAirborneMove;
    private int srvCouch = -1;
    private boolean srvUse;
    private int srvUseOffTick = -1000;
    private int couchPressTick = -1000;
    private int recouchIn;
    private int postWebSpearSlot = -1;
    private Entity postWebRetryTarget;
    private boolean pressedUse;
    private int userItemTicks;

    private boolean strikeLast;
    private boolean volleyWanted;
    private boolean webbedNow;
    private int lastWebVolleyTick = -1000;
    private String volleyReason = "continuation";
    private Tier weavingTier;
    private AttackRange weavingRange;
    private int weavingSlot = -1, weavingOriginalSlot = -1, weavingStartedAt, weavingAttempts;
    private Vec3 shellCenter;
    private boolean shellAttempted;

    private record WebPlacement(BlockPos pos, BlockHitResult hit) {
    }

    private WebPlacement sendingWeb;
    private int webSentSequence = -1;

    private record PendingWeb(int tick, boolean expired) {
    }

    private record WebEcho(EchoOwner owner, BlockPos pos, boolean web) {
    }

    private final Map<BlockPos, PendingWeb> pendingWebs = new HashMap<>();
    private volatile Set<BlockPos> pendingWebSnapshot = Set.of();
    private final ConcurrentLinkedQueue<WebEcho> webEchoes = new ConcurrentLinkedQueue<>();

    private record WireFootprint(int tick, AABB swept) {
    }

    private final ArrayDeque<WireFootprint> recentWire = new ArrayDeque<>();
    private Vec3 avoidanceWaypoint;
    private Vec3 avoidanceRise;
    private int avoidanceUntil;
    private volatile BlockPos clearingWeb;
    private boolean clearingConfirmed, selfWebHold, manualWebControl;
    private int clearingSlot = -1, clearingOriginalSlot = -1, selfWebPauseUntil, clearingStartedAt;
    private Vec3 correctionFrom;

    private String status = "idle";
    private double parkX, parkY, parkZ;
    private double fVx, fVy, fVz;
    private boolean fActive, fVerticalActive;
    private final ArrayDeque<String> trace = new ArrayDeque<>();
    private final ArrayDeque<String> wireTrace = new ArrayDeque<>();

    private final Vec3[] hist = new Vec3[8];
    private int histLen, histIdx;
    private Entity histTarget;
    private Vec3 vPos = Vec3.ZERO, vVel = Vec3.ZERO;

    private int telTicks, telStrikes, telHurts, telPlausibleHurts, telWebs, telWebConfirmed;
    private double telDmg, telPredicted;
    private int lastHurt;
    private float lastHp;

    private record StrikeObservation(int tick, int targetId) {
    }

    private final ArrayDeque<StrikeObservation> pendingStrikes = new ArrayDeque<>();
    private boolean recordStrikeOnSend;
    private int webNoItemThrottle;
    private PrintWriter dbgOut;

    public Lance() {
        super(Addon.CATEGORY2, "Lance", "Spear strikes from above on a ten tick beat.");
    }

    @Override
    public String getInfoString() {
        return target != null ? EntityUtils.getName(target) : null;
    }

    @Override
    public void onActivate() {
        boolean sameBody = ownsCurrentBody();
        finishWeave(sameBody);
        finishSelfWeb(sameBody);
        if (!sameBody) recentWire.clear();
        avoidanceWaypoint = null;
        avoidanceRise = null;
        selfWebHold = false;
        manualWebControl = false;
        selfWebPauseUntil = 0;
        if (sameBody) drainWebEchoes();
        if (pressedUse && !ownsCurrentBody()) setUseKey(false);

        if (homing && ownsCurrentBody() && lastClaim != null) adoptGhost("reactivated during return");
        stopHoming();
        ox = oy = oz = 0;
        lastClaim = null;
        protectMovementThisTick = false;
        protectedWireTick = -1000;
        lastWire = null;
        lastWireTick = -1000;
        wireSyncWanted = false;
        claimsSinceSetback = 0;
        lastClaimWireTick = -1000;
        target = null;
        blindTicks = 0;
        cycleTick = 0;
        beat.reset();
        crystalRefreshTick = plannedAimTick = Integer.MIN_VALUE;
        terrainBlocked = false;
        attackLane = null;
        shellCenter = null;
        shellAttempted = false;
        lastSelfHp = mc.player == null ? 0 : hp(mc.player);
        cooldown = 0;
        setbacks = passiveSetbacks = 0;
        lastSetbackAt = -1000;
        blockedTicks = homeBlocked = 0;
        correcting = leavingWorld = false;
        bindCurrentBody();
        glideEchoes.clear();
        useEchoes.clear();
        if (!sameBody) {
            pendingWebs.clear();
            pendingWebSnapshot = Set.of();
        }
        webEchoes.clear();
        couchTiming.reset();
        clearPostWebRetry();
        couchGeneration = couchTiming.stop();
        srvGliding = mc.player == null ? null : mc.player.isFallFlying();
        glideRecovery = recoveryJumped = false;
        recoveryVy = 0;
        recoveryJumpTick = -1000;
        relightPending = relights = 0;
        relightArmed = relightAirborneMove = false;
        srvCouch = -1;
        srvUse = false;
        srvUseOffTick = couchPressTick = -1000;
        recouchIn = 0;
        pressedUse = false;
        userItemTicks = 0;
        strikeLast = volleyWanted = webbedNow = false;
        lastWebVolleyTick = -1000;
        posSentThisTick = false;
        fActive = fVerticalActive = false;
        histLen = histIdx = 0;
        histTarget = null;
        telTicks = telStrikes = telHurts = telPlausibleHurts = telWebs = telWebConfirmed = 0;
        telDmg = telPredicted = 0;
        lastHurt = 0;
        lastHp = 0;
        pendingStrikes.clear();
        recordStrikeOnSend = false;
        webNoItemThrottle = 0;
        trace.clear();
        wireTrace.clear();
        status = "idle";
        openDebug();
    }

    @Override
    public void onDeactivate() {
        finishWeave(true);
        finishSelfWeb(true);
        if (ownsCurrentBody()) releaseCouch();
        else if (pressedUse) setUseKey(false);
        target = null;
        fActive = fVerticalActive = false;
        pendingStrikes.clear();
        if (!leavingWorld && ownsCurrentBody() && lastClaim != null) startHoming();
        else clearPositionState();
        closeDebug();
    }

    private void bindCurrentBody() {
        ownerPlayer = mc.player;
        ownerWorld = mc.level;
        ownerNetwork = mc.getConnection();
        echoOwner = ownerPlayer == null || ownerNetwork == null ? null : new EchoOwner(ownerNetwork.getConnection(), ownerPlayer.getId());
    }

    private boolean ownsCurrentBody() {
        return mc.player != null && mc.level != null && mc.getConnection() != null
            && mc.player == ownerPlayer && mc.level == ownerWorld && mc.getConnection() == ownerNetwork;
    }

    private void clearPositionState() {
        ox = oy = oz = 0;
        lastClaim = lastWire = null;
        protectMovementThisTick = false;
        protectedWireTick = -1000;
        lastWireTick = -1000;
        wireSyncWanted = false;
        strikeLast = volleyWanted = recordStrikeOnSend = false;
        fActive = fVerticalActive = false;
        blockedTicks = homeBlocked = 0;
        avoidanceWaypoint = null;
        avoidanceRise = null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onGameLeft(GameLeftEvent event) {
        leavingWorld = true;
        finishWeave(false);
        finishSelfWeb(false);
        recentWire.clear();
        clearPositionState();
        glideEchoes.clear();
        useEchoes.clear();
        pendingWebs.clear();
        pendingWebSnapshot = Set.of();
        webEchoes.clear();
        couchGeneration = couchTiming.stop();
        echoOwner = null;
        stopHoming();
    }

    @EventHandler
    private void onTickPre(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;
        if (!ownsCurrentBody()) onActivate();
        posSentThisTick = false;
        protectMovementThisTick = false;
        tickCounter++;
        if (cooldown > 0) cooldown--;
        if (recouchIn > 0) recouchIn--;
        drainGlideEchoes();
        drainUseEchoes();
        drainWebEchoes();
        if (weavingTier != null && mc.player.getInventory().getSelectedSlot() == weavingSlot
            && mc.player.getMainHandItem().is(Items.COBWEB) && mc.player.isUsingItem()
            && mc.player.getUseItem().is(Items.COBWEB) && !mc.options.keyUse.isDown() && !mc.options.keyAttack.isDown()) {

            mc.player.stopUsingItem();
            dbg("WEBS cleared stale local cobweb-use mirror");
        }
        if (weavingTier != null && (!webEnabled.get() || target == null || cooldown > 0
            || mc.player.getInventory().getSelectedSlot() != weavingSlot || !mc.player.getMainHandItem().is(Items.COBWEB)
            || mc.options.keyAttack.isDown() || mc.player.isUsingItem()
            || hasWeb(bodyAt(lastWire != null ? lastWire : mc.player.position())))) finishWeave(true);
        recentWire.removeIf(entry -> tickCounter - entry.tick > wireProtectionTicks());
        selfWebHold = selfWebTick();
        if (selfWebHold || manualWebControl) {
            finishWeave(true);
            clearPostWebRetry();
        }
        prepareGlideRecovery();
        relightTick();

        if (!selfWebHold && !manualWebControl && follow.get() && target != null && usableElytra() && !mc.player.onGround() && !mc.player.isInWater() && !mc.player.isFallFlying()) {
            mc.player.startFallFlying();
            if (srvGliding == Boolean.FALSE) beginGlideRecovery();
        }

        if (target != null) {
            boolean keep = target.isAlive() && !target.isRemoved() && baseValid(target) && PlayerUtils.distanceTo(target) <= lockRange.get();
            if (keep && !ignoreWalls.get()) {
                if (!PlayerUtils.canSeeEntity(target)) keep = ++blindTicks <= 60;
                else blindTicks = 0;
            }
            if (!keep) setTarget(null);
        }
        if (target == null) {
            Entity t = TargetUtils.get(this::acquire, priority.get());
            if (t != null) setTarget(t);
        }

        if (target != null) {
//? if >=26.3 {
            /*vPos = target.getPosition(1.0F);
*///?} else {
            InterpolationHandler pi = target.getInterpolation();
            vPos = pi != null ? pi.position() : target.position();
//?}
            if (target != histTarget) {
                histLen = 0;
                histIdx = 0;
                histTarget = target;
            }
            hist[histIdx] = vPos;
            histIdx = (histIdx + 1) & 7;
            if (histLen < 8) histLen++;
            if (histLen >= 5) vVel = hist[(histIdx - 1) & 7].subtract(hist[(histIdx - 5) & 7]).scale(0.25);
            else vVel = Vec3.ZERO;
        }

        if (target instanceof LivingEntity lv) {
            float hpNow = hp(lv);
            if (lv.hurtTime > lastHurt) {
                telHurts++;
                boolean plausible = consumePlausibleStrike(lv.getId());
                if (plausible) telPlausibleHurts++;
                dbg("OBSERVED-HURT hurt %d->%d hp %.1f->%.1f plausibleStrikeWindow=%b cyc=%d trace=%s", lastHurt, lv.hurtTime, lastHp, hpNow, plausible, cycleTick, String.join(" | ", trace));
            }
            if (!(lv instanceof Player) && hpNow < lastHp) telDmg += lastHp - hpNow;
            lastHurt = lv.hurtTime;
            lastHp = hpNow;
        }

        float selfHp = hp(mc.player);
        if (selfHp < lastSelfHp) dbg("SELF-DAMAGE hp=%.2f->%.2f estimatedCrystal=%.3f wire=%s", lastSelfHp, selfHp, plannedCrystalDamage, lastWire);
        lastSelfHp = selfHp;
        if (!selfWebHold && !manualWebControl && weavingTier == null) couchTick();
        refreshCrystalSafety();
        followTick();
        telemetryTick();
    }

    private void couchTick() {
        boolean usingOther = mc.player.isUsingItem() && tier(mc.player.getUseItem().getItem()) == null;
        Tier tier = heldTier();
        if (postWebSpearSlot >= 0 && (!ownsCurrentBody() || correcting || cooldown > 0 || target != postWebRetryTarget
            || mc.player.getInventory().getSelectedSlot() != postWebSpearSlot || tier == null || usingOther
            || mc.options.keyAttack.isDown())) clearPostWebRetry();
        if (srvCouch >= 0) srvCouch++;
        if (usingOther) {
            pressedUse = false;
            srvCouch = -1;
            couchGeneration = couchTiming.stop();
            userItemTicks = 0;
            status = "paused - your item is in use";
        } else if (tier == null) {
            if (pressedUse) setUseKey(false);
            srvCouch = -1;
            couchGeneration = couchTiming.stop();
            if (autoSwap.get() && target != null && (mc.player.getMainHandItem().isEmpty() || ++userItemTicks >= 15)) {
                int slot = bestSpearSlot();
                if (slot != -1) {
                    InvUtils.swap(slot, false);
                    tier = heldTier();
                    userItemTicks = 0;
                }
            }
            if (tier == null) status = "no spear in hand";
        } else if (target == null) {
            userItemTicks = 0;
            releaseCouch();
        } else {
            userItemTicks = 0;
            if (srvCouch < 0) {
                if (recouchIn == 0) pressCouch();
            } else if (couchTiming.earliestUseAge(tickCounter) >= tier.delay + tier.window - 12) {
                releaseCouch();
                recouchIn = 2;
                dbg("couch EXPIRY release, re-press in 2t");
            } else {
                setUseKey(true);
                if (couchTiming.retryDue(tickCounter, PlayerUtils.getPing())) {
                    dbg("couch RETRY freshOn=%b srvUse=%b age=%dt budget=%dt offAge=%dt", couchTiming.confirmed(), srvUse,
                        tickCounter - couchPressTick, couchTiming.responseBudget(PlayerUtils.getPing()), tickCounter - srvUseOffTick);
                    couchTiming.failed();
                    releaseCouch();
                    recouchIn = 2;
                } else if (postWebSpearSlot >= 0 && pressedUse && couchTiming.reassertDue(tickCounter, PlayerUtils.getPing())) {
                    reassertCouch();
                } else if (!mc.player.isUsingItem()) {

                    mc.player.startUsingItem(InteractionHand.MAIN_HAND);
                }
            }
        }
    }

    private void followTick() {
        fActive = fVerticalActive = false;
        if (selfWebHold || manualWebControl) return;
        if (!follow.get() || target == null || !mc.player.isFallFlying()) return;
        double lead = Math.min(5.0, PlayerUtils.getPing() / 50.0);
        parkX = vPos.x + vVel.x * lead;
        parkZ = vPos.z + vVel.z * lead;
        parkY = vPos.y + hoverHeight.get();
        boolean horizontalPerch = attackLane != null && attackLane.horizontal();
        if (horizontalPerch) {
            Vec3 perch = safeHorizontalPerch(new Vec3(parkX, parkY, parkZ));
            parkX = perch.x;
            parkY = perch.y;
            parkZ = perch.z;
        }
        double dx = parkX - mc.player.getX(), dz = parkZ - mc.player.getZ();
        double h = Math.sqrt(dx * dx + dz * dz);
        fVx = fVz = 0;
        if (h > 1.0) {
            double mv = Math.min(h - 1.0, followSpeed.get());
            fVx = dx / h * mv;
            fVz = dz / h * mv;
        }
        fActive = true;
        fVy = 0;
        double dy = parkY - mc.player.getY();
        if (horizontalPerch || Math.abs(dy) > 1.0) {

            fVy = Mth.clamp(dy, -MAX_V, MAX_V);
            fVerticalActive = true;
        }

        if (cooldown > 0) {
            fVx *= 0.5;
            fVz *= 0.5;
            fVy = Mth.clamp(fVy, -0.8, 0.8);
        }

        if (Math.abs(fVy) > 0.4) {
            double budgetH = Math.sqrt(Math.max(0, MAX_H * MAX_H - fVy * fVy));
            double fh = Math.sqrt(fVx * fVx + fVz * fVz);
            if (fh > budgetH && fh > 1e-6) {
                double s = budgetH / fh;
                fVx *= s;
                fVz *= s;
            }
        }
        if (horizontalPerch) {

            Vec3 real = mc.player.position();
            Vec3 safeStep = sweepMove(real, real, new Vec3(fVx, fVy, fVz));
            fVx = safeStep.x;
            fVy = safeStep.y;
            fVz = safeStep.z;
        }

    }

    private Vec3 safeHorizontalPerch(Vec3 overhead) {
        Vec3 real = mc.player.position();
        Vec3 ghost = lastWire != null ? lastWire : real;
        double leash = maxOffset.get() * 0.95;
        if (clearRealPerch(real, ghost, overhead, leash)) return overhead;

        Vec3 rest = vec(attackLane.rest()), axis = vec(attackLane.axis());
        Vec3 preferred = rest.subtract(axis.scale(8)).add(0, 0.5, 0);
        Vec3 best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (double retreat : new double[]{8, 4, 2, 0}) {
            for (double lift : new double[]{0.15, 0.5, 1, 2}) {
                Vec3 candidate = rest.subtract(axis.scale(retreat)).add(0, lift, 0);
                if (!clearRealPerch(real, ghost, candidate, leash)) continue;
                AABB clearance = bodyAt(candidate).inflate(0.08);
                boolean room = mc.level.noCollision(mc.player, clearance) && !hasWeb(clearance);
                double score = candidate.distanceTo(preferred) + candidate.distanceTo(real) * 0.25 + (room ? 0 : 2);
                if (score < bestScore) {
                    best = candidate;
                    bestScore = score;
                }
            }
        }
        if (best != null) return best;

        Vec3 up = real.add(0, 0.1, 0);
        if (mc.player.onGround() && clearRealPerch(real, ghost, up, leash)) return up;
        return real;
    }

    private boolean clearRealPerch(Vec3 real, Vec3 ghost, Vec3 perch, double leash) {
        if (perch.distanceToSqr(ghost) > leash * leash
            || !mc.level.getChunkSource().hasChunk(Mth.floor(perch.x) >> 4, Mth.floor(perch.z) >> 4)) return false;
        AABB body = bodyAt(perch);
        if (!mc.level.noCollision(mc.player, body) || hasWeb(body)) return false;
        Vec3 delta = perch.subtract(real);
        return sweepMove(real, real, delta).distanceToSqr(delta) < 1e-8;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onPlayerMove(PlayerMoveEvent event) {
        if (!ownsCurrentBody() || correcting || mc.player.getAbilities().flying) return;
        if (manualWebControl) return;
        if (selfWebHold) {

            mc.player.setDeltaMovement(Vec3.ZERO);
            ((IVec3) event.movement).meteor$set(0, 0, 0);
            return;
        }
        if (srvGliding == Boolean.FALSE && mc.player.isFallFlying() && !glideRecovery) beginGlideRecovery();
        boolean recovering = glideRecovery && srvGliding == Boolean.FALSE;
        if (!recovering && (!fActive || !mc.player.isFallFlying())) return;
        double vx = fActive ? fVx : event.movement.x, vz = fActive ? fVz : event.movement.z;
        double vy = fActive && fVerticalActive ? fVy : event.movement.y;
        if (recovering && !mc.player.isPassenger() && !mc.player.isInWater() && !mc.player.isInLava()
            && !mc.player.hasEffect(MobEffects.LEVITATION)) {

            if (recoveryJumpTick != tickCounter) {
                double gravity = mc.player.getAttributeValue(Attributes.GRAVITY);
                if (recoveryVy <= 0 && mc.player.hasEffect(MobEffects.SLOW_FALLING)) gravity = Math.min(gravity, 0.01);
                recoveryVy = mc.player.onGround() ? 0 : LanceMovementMath.nextRecoveryVelocity(recoveryVy, gravity);
            }
            vy = recoveryVy;
            double scale = LanceMovementMath.capScale(vx, 0, vz, 0.25, 1);
            vx *= scale;
            vz *= scale;
            mc.player.setDeltaMovement(vx, vy, vz);
        }
        if (lastClaim != null) {
            Vec3 rel = mc.player.position().subtract(lastClaim);
            double leash = maxOffset.get() * 0.95;

            if (blockedTicks > 0 || homeBlocked > 0) leash = Math.min(leash, rel.length());
            double fraction = LanceMovementMath.followFraction(rel.x, rel.y, rel.z, vx, vy, vz, leash);
            vx *= fraction;
            vy *= fraction;
            vz *= fraction;
        }
        int cx = (int) Math.floor((mc.player.getX() + vx) / 16), cz = (int) Math.floor((mc.player.getZ() + vz) / 16);
        if (!mc.level.getChunkSource().hasChunk(cx, cz)) {
            vx = 0;
            vz = 0;
        }
        Vec3 movement = new Vec3(vx, vy, vz);
        if (fActive && lastClaim == null) {
            refreshCrystalSafety();
            Vec3 real = mc.player.position();
            if (!recovering) movement = protectCrystalMove(real, real, movement, maxOffset.get() * 0.95);
            else if (!damageAllowed(segmentDamage(real, real.add(movement)))) {

                Vec3 falling = new Vec3(0, movement.y, 0);
                if (positionDamage(real.add(falling)) < positionDamage(real.add(movement))) movement = falling;
            }
            wireSyncWanted = true;
        }

        if (fActive && !hasWeb(mc.player.getBoundingBox())) {
            double fraction = webSafeFraction(mc.player.getBoundingBox(), movement);
            movement = movement.scale(fraction);
        }
        ((IVec3) event.movement).meteor$set(movement.x, movement.y, movement.z);
    }

    private boolean usableElytra() {
        if (mc.player == null || mc.player.isPassenger() || mc.player.getAbilities().flying || mc.player.hasEffect(MobEffects.LEVITATION))
            return false;
        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        return chest.has(DataComponents.GLIDER) && (!chest.isDamageableItem() || chest.getDamageValue() < chest.getMaxDamage() - 1);
    }

    private void beginGlideRecovery() {
        if (glideRecovery) return;
        glideRecovery = true;
        recoveryJumped = false;
        recoveryJumpTick = -1000;
        recoveryVy = Math.min(0, mc.player.getDeltaMovement().y);
    }

    private void prepareGlideRecovery() {
        if (!ownsCurrentBody() || correcting || selfWebHold || manualWebControl) return;
        if (srvGliding != Boolean.FALSE) return;
        if (mc.player.isFallFlying() && !glideRecovery) beginGlideRecovery();
        if (lastClaim != null) {
            beginGlideRecovery();
            adoptGhost("server closed the wings");
            recoveryVy = 0;
        }
        if (!glideRecovery || !follow.get() || target == null || recoveryJumped || !usableElytra()
            || !mc.player.onGround() || mc.player.isInWater() || mc.player.isInLava()) return;
        mc.player.jumpFromGround();
        recoveryVy = mc.player.getDeltaMovement().y;
        recoveryJumpTick = tickCounter;
        recoveryJumped = true;
        dbg("recovery ground jump vy=%.3f; waiting for ElytraFly glide echo", recoveryVy);
    }

    private static boolean detectForkSync() {
        try {
            ElytraFly.class.getDeclaredField("serverSync");
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    private int relightWait() {
        return Math.min(60, Math.max(4, (int) Math.ceil(PlayerUtils.getPing() / 50.0) + 3));
    }

    private void relightTick() {
        relightArmed = false;
        relightAirborneMove = false;
        if (relightPending > 0) {
            relightPending--;
            return;
        }
        if (FORK_SYNC || !ownsCurrentBody() || correcting || srvGliding != Boolean.FALSE || !mc.player.isFallFlying()) return;
        if (mc.player.onGround() || mc.player.isInWater() || !usableElytra()) return;
        relightArmed = true;
        ((LocalPlayerAccessor) mc.player).meteor$setPositionReminder(20);
    }

    private void relightPost() {
        if (!relightArmed) return;
        relightArmed = false;
        if (!relightAirborneMove || mc.getConnection() == null) return;
        mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        relightPending = relightWait();
        relights++;
        dbg("RELIGHT #%d glide command sent behind the airborne packet", relights);
    }

    private void adoptGhost(String reason) {
        if (!ownsCurrentBody() || lastClaim == null || correcting) return;
        Vec3 adopted = lastWire != null ? lastWire : lastClaim;
        dbg("ADOPT ghost (%s) delta=%.2f", reason, adopted.distanceTo(mc.player.position()));
        mc.player.setPos(adopted);
        mc.player.setDeltaMovement(Vec3.ZERO);
        ox = oy = oz = 0;
        lastClaim = null;
        lastWire = adopted;
        lastWireTick = tickCounter;
        wireSyncWanted = true;
        strikeLast = volleyWanted = recordStrikeOnSend = false;
        blockedTicks = homeBlocked = 0;
        fActive = fVerticalActive = false;
        cooldown = Math.max(cooldown, 4);
        status = "rejoined the server-side body - " + reason;
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onSendPre(SendMovementPacketsEvent.Pre event) {
        if (!ownsCurrentBody() || correcting) return;
        boolean wasStrike = strikeLast;
        strikeLast = false;
        volleyWanted = false;
        recordStrikeOnSend = false;

        if (manualWebControl) {
            if (lastClaim != null) adoptGhost("manual web recovery");
            status = "web recovery - manual control";
            return;
        }
        if (selfWebHold) {
            status = clearingWeb != null ? "clearing own web - waiting for server removal" : "inside web - need a hotbar sword or shears";
            return;
        }

        if (srvGliding == Boolean.FALSE) {
            finishWeave(true);
            prepareGlideRecovery();
            status = !glideRecovery && !mc.player.isFallFlying() ? "not gliding - hover on the elytra"
                : FORK_SYNC ? "server dropped the wings - waiting for ElytraFly to re-open them" : "server dropped the wings - re-opening them";
            return;
        }

        Tier tier = weavingTier != null ? weavingTier : heldTier();
        if (target == null || cooldown > 0 || tier == null) {
            finishWeave(true);
            if (target != null || cooldown > 0) dbg("skip tgt=%b cd=%d tier=%b", target != null, cooldown, tier != null);
            walkHome();
            return;
        }
        if (!mc.player.isFallFlying()) {
            finishWeave(true);
            status = "not gliding - hover on the elytra";
            walkHome();
            return;
        }
        Vec3 real = mc.player.position();

        Vec3 cur;
        if (lastClaim != null) cur = lastWire != null ? lastWire : lastClaim;
        else if (lastWire != null && (tickCounter - lastWireTick <= 2 || lastWire.distanceToSqr(real) < 0.01)) cur = lastWire;
        else {
            ox = oy = oz = 0;
            wireSyncWanted = true;
            dbg("skip handoff-sync");
            return;
        }

        double lead = couchTiming.predictionTicks(PlayerUtils.getPing());
        AABB b = target.getBoundingBox().move(vPos.subtract(target.position())).move(vVel.scale(lead));
        double cx = (b.minX + b.maxX) * 0.5, cz = (b.minZ + b.maxZ) * 0.5, head = b.maxY;
        AttackRange ar = weavingTier != null ? weavingRange : mc.player.getAttackRangeWith(mc.player.isUsingItem() ? mc.player.getUseItem() : mc.player.getMainHandItem());
        double effMax = ar != null ? ar.effectiveMaxRange(mc.player) : 4.5;
        double effMin = ar != null ? ar.effectiveMinRange(mc.player) : 2.0;

        double strikeEye = Mth.clamp(strikeHeight.get(), effMin + 0.3, effMax - 0.3);
        double launchEye = strikeEye + dip.get();
        double restEye = Math.max(Math.max(standoff.get() + EYE, launchEye), effMax + RAY_MARGIN + 0.3);
        double launchY = head + launchEye - EYE, restY = head + restEye - EYE;
        int approachTicks = (int) Math.ceil(Math.max(0, restEye - launchEye) / 1.2);
        refreshCrystalSafety();
        LanceAttackMath.Lane lane = wasStrike && attackLane != null ? attackLane : chooseLane(b, cur, strikeEye, restEye);
        attackLane = lane;
        Vec3 axis = wasStrike ? strikeAxis : vec(lane.axis());
        Vec3 impact = vec(lane.impact()), launch = vec(lane.launch()), rest = vec(lane.rest());
        launchY = launch.y;
        restY = rest.y;
        approachTicks = (int) Math.ceil(rest.distanceTo(launch) / 1.2);
        int remaining = beat.remaining(tickCounter, period.get());
        cycleTick = period.get() - remaining;
        int armTicks = tier.delay + couchTiming.latencyTicks(PlayerUtils.getPing()) + 1;
        boolean couched = weavingTier == null && couchTiming.confirmed() && srvCouch >= armTicks;
        approachPriority.observe(tickCounter, couchGeneration, target.getId(), couched, wasStrike);
        double dxz = Math.sqrt((cur.x - cx) * (cur.x - cx) + (cur.z - cz) * (cur.z - cz));

        boolean atLaunch = LanceAttackMath.readyToStrike(lane, attackPoint(cur), attackPoint(vVel), dip.get(), MAX_H,
            lane.horizontal() ? MAX_V : dip.get() + 0.05);
        webbedNow = victimWebbed(target);
        boolean covered = currentWebCoverage();
        boolean needsWeb = needsWebMaintenance(covered);
        boolean canWeb = webEnabled.get() && (!webPlayersOnly.get() || target instanceof Player)
            && InvUtils.findInHotbar(Items.COBWEB).isHotbar();
        plannedCrystalDamage = laneDamage(lane);
        Vec3 threat = damageAllowed(plannedCrystalDamage) ? null : impact;
        boolean capture = canWeb && !covered && vVel.lengthSqr() > 0.04
            && tickCounter - lastWebVolleyTick >= armTicks + period.get();

        if (capture && couched && !hasUsefulWeb(covered)) capture = false;
        if (couched && approachPriority.active(tickCounter)) capture = false;

        Vec3 want;
        double capH = MAX_H, capV = MAX_V;
        boolean strike = false, aimIn = false;
        String kind;
        if (wasStrike) {

            want = cur.add(axis.scale(0.3));
            capV = lane.horizontal() ? MAX_V : 0.35;
            aimIn = true;
            kind = "cont";
            volleyWanted = canWeb && needsWeb;
            volleyReason = covered ? "containment / escape edge" : "continuation";
        } else if (weavingTier != null) {
            want = impact.add(axis.scale(0.3));
            kind = "weave";
        } else if (couched && remaining == 0 && atLaunch && threat == null && attackRayClear(lane)) {
            want = impact;
            capV = lane.horizontal() ? MAX_V : dip.get() + 0.05;
            aimIn = true;
            strike = true;
            kind = "STRIKE";
        } else if (capture && threat == null) {
            want = impact;
            kind = "capture";
        } else if (cycleTick >= period.get() - approachTicks && !(couched && cycleTick >= period.get() && threat != null)) {
            want = launch;
            capV = 1.2;
            kind = cur.y > launchY + 0.2 ? "approach" : "launch";
        } else {
            want = rest;
            kind = cur.y < restY - 0.2 ? "rise" : "rest";
        }

        boolean attackMove = strike || wasStrike;
        Vec3 d = want.subtract(cur);
        d = !attackMove && ("capture".equals(kind) || "weave".equals(kind) || "launch".equals(kind) || "approach".equals(kind))
            ? approachMove(d, capH, capV) : capMove(d, capH, capV);
        double leash = maxOffset.get() * 0.95;
        Vec3 nextWant = cur.add(d);
        if (nextWant.subtract(real).length() > leash) {
            Vec3 rel = nextWant.subtract(real);
            d = real.add(rel.scale(leash / rel.length())).subtract(cur);
            if (strike) {
                strike = false;
                aimIn = false;
                kind = "leash";
            }
        }
        d = capMove(d, capH, capV);
        Vec3 requestedStep = d;
        double planned = d.length();
        d = sweepMove(real, cur, d);
        if (strike && (cur.add(d).distanceTo(impact) > 0.06 || d.dot(axis) < 0.3 || !kineticDamageReady(d, axis))) {
            strike = aimIn = false;
            kind = "blocked";
            d = Vec3.ZERO;
        }
        if (!LancePathSafety.allowDetour(attackMove, want.y - cur.y)) avoidanceWaypoint = avoidanceRise = null;
        else if (avoidanceWaypoint != null || d.length() < planned * 0.9) {
            double routeCapV = Math.min(capV, MAX_V);
            Vec3 routed = routeAroundWeb(real, cur, want, capH, routeCapV);
            if (routed != null) {
                Vec3 rel = cur.subtract(real);
                double fraction = LanceMovementMath.followFraction(rel.x, rel.y, rel.z, routed.x, routed.y, routed.z, leash);
                d = sweepMove(real, cur, capMove(routed.scale(fraction), capH, routeCapV));
                kind = "web-detour";
            }
        }
        terrainBlocked = planned > 0.3 && d.length() < planned * 0.3;
        double approachDamage = segmentDamage(cur, cur.add(d));
        boolean crystalBlocked = !damageAllowed(approachDamage);
        if (crystalBlocked) {
            d = protectCrystalMove(cur, real, d, leash);
            strike = aimIn = volleyWanted = false;
            kind = "crystal-retreat";
        }
        if (planned > 0.3 && d.length() < planned * 0.3) {

            blockedTicks = Math.min(40, blockedTicks + 1);
            if (!"cont".equals(kind) && !crystalBlocked) kind = "blocked";
        } else blockedTicks = 0;
        if (blockedTicks > 0 && tickCounter % 20 == 0)
            dbg("PATH-HOLD web=%b terrain=%b crystal=%b routeDamage=%.3f limit=%.3f observed=%d possible=%d requested=%s accepted=%s body=%.2fx%.2f",
                webSafeFraction(bodyAt(cur), requestedStep) < 0.999, terrainBlocked, crystalBlocked, approachDamage, damageLimit(),
                crystalPositions.size(), potentialCrystalPositions.size(), requestedStep, d,
                mc.player.getBoundingBox().getXsize(), mc.player.getBoundingBox().getYsize());
        Vec3 next = cur.add(d);

        trace.addLast(String.format("%s h%.1f v%+.1f @%.1f", kind, Math.sqrt(d.x * d.x + d.z * d.z), d.y, next.y));
        while (trace.size() > 8) trace.removeFirst();
        ox = next.x - real.x;
        oy = next.y - real.y;
        oz = next.z - real.z;
        lastClaim = next;
        claimsSinceSetback++;

        if (strike) {
            strikeAxis = axis;
            telStrikes++;
            recordStrikeOnSend = true;
            telPredicted = 1 + Math.floor(Math.max(0, d.subtract(vVel).dot(axis)) * 20 * tier.mult());
            status = String.format("LANCE %s · %s STRIKE · ~%.0f raw · off %.1f", EntityUtils.getName(target), lane.horizontal() ? "horizontal" : "overhead", telPredicted, next.distanceTo(real));
            dbg("STRIKE step=%.2f/%.2f/%.2f pred=%.0f eye-head=%.2f dxz=%.2f srvUse=%b srvC=%d webbed=%b", d.x, d.y, d.z, telPredicted, next.y + EYE - head, dxz, srvUse, srvCouch, webbedNow);
        } else if (!couched) {
            status = srvCouch < 0 ? String.format("raising the spear in %dt", recouchIn)
                : !couchTiming.confirmed() ? String.format("waiting for use confirmation %d/%dt", srvCouch, couchTiming.responseBudget(PlayerUtils.getPing()))
                  : String.format("arming %d/%dt", srvCouch, armTicks);
        } else if (threat != null && cycleTick >= period.get()) {
            status = String.format("LANCE %s · crystal estimate %.2f HP (limit %.2f)", EntityUtils.getName(target), plannedCrystalDamage, damageLimit());
        } else if (!"cont".equals(kind)) {
            String hold = cycleTick < period.get() ? String.format("cooldown %dt", period.get() - cycleTick)
                : "blocked".equals(kind) || "leash".equals(kind) ? kind
                  : String.format("aligning: vertical %.2f, sideways %.2f", Math.abs(cur.y - launchY), dxz);
            status = String.format("LANCE %s · %s · off %.1f%s", EntityUtils.getName(target), hold, next.distanceTo(real), webbedNow ? " · webbed" : "");
        }

        boolean nextAtLaunch = LanceAttackMath.readyToStrike(lane, attackPoint(next), attackPoint(vVel), dip.get(), MAX_H,
            lane.horizontal() ? MAX_V : dip.get() + 0.05);
        boolean runnerVolley = !strike && !wasStrike && weavingTier == null && canWeb && needsWeb && threat == null
            && LanceWebTiming.canStart(tickCounter, lastWebVolleyTick, period.get(), couched)
            && (!couched || capture || !atLaunch && !nextAtLaunch) && tickCounter - lastWebVolleyTick >= period.get();
        if (runnerVolley && couched && remaining == 0) {
            boolean imminent = LanceApproachPriority.imminent(lane, attackPoint(next), attackPoint(vVel), dip.get(), MAX_H,
                lane.horizontal() ? MAX_V : dip.get() + 0.05,
                delta -> attackPoint(approachMove(vec(delta), MAX_H, 1.2)),
                (fromPoint, toPoint) -> {
                    Vec3 from = vec(fromPoint), to = vec(toPoint), movement = to.subtract(from);
                    return to.distanceTo(real) <= leash && sweepMove(real, from, movement).distanceToSqr(movement) <= 1e-6
                        && damageAllowed(segmentDamage(from, to));
                }, this::attackRayClear);
            if (approachPriority.preserve(tickCounter, imminent)) {
                runnerVolley = false;
                dbg("WEBS defer runner intercept: clear strike reachable within two ticks");
            }
        }
        if (runnerVolley) {
            volleyWanted = true;
            volleyReason = "runner intercept";
        }

        if (dbgOut != null && target instanceof LivingEntity vle) {
            dbg("st k=%s cyc=%d srvC=%d sU=%b cur=%.2f/%.2f/%.2f step=%.2f/%.2f/%.2f off=%.1f eye-head=%.2f dxz=%.2f atL=%b vpos=%.2f/%.2f/%.2f vvel=%.2f/%.2f/%.2f vhp=%.1f vhurt=%d webbed=%b thr=%b look=%s",
                kind, cycleTick, srvCouch, srvUse, next.x, next.y, next.z, d.x, d.y, d.z, next.distanceTo(real), next.y + EYE - head, dxz, atLaunch,
                vPos.x, vPos.y, vPos.z, vVel.x, vVel.y, vVel.z, hp(vle), vle.hurtTime, webbedNow, threat != null, aimIn ? "IN" : "away");
            if (tickCounter % 10 == 0 || strike)
                dbg("LANE horizontal=%b axis=%s launch=%s crystal=%.3f possibleDamage=%.3f observed=%d possible=%d placers=%d preferred=%.3f limit=%.3f strict=%b covered=%b needsWeb=%b weaving=%b",
                    lane.horizontal(), axis, launch, plannedCrystalDamage, potentialLaneDamage(lane), crystalPositions.size(),
                    potentialCrystalPositions.size(), crystalPlacers, maxCrystalDamage.get(), damageLimit(), strictCrystalLimit.get(), covered, needsWeb, weavingTier != null);
            if (!strike && (cycleTick >= period.get() || !couched)) dbg("HOLD %s freshOn=%b", status, couchTiming.confirmed());
        }

        Vec3 look = aimIn ? axis : next.add(0, EYE, 0).subtract(b.getCenter()).normalize();
        if (look.lengthSqr() < 0.5) look = axis.reverse();
        plannedYaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
        plannedPitch = (float) -Math.toDegrees(Math.asin(Mth.clamp(look.y, -1, 1)));
        plannedAimTick = tickCounter;
        protectMovementThisTick = true;
        Rotations.rotate(plannedYaw, plannedPitch, 100);
    }

    private static Vec3 vec(LanceAttackMath.Point p) {
        return new Vec3(p.x(), p.y(), p.z());
    }

    private static LanceAttackMath.Point attackPoint(Vec3 p) {
        return new LanceAttackMath.Point(p.x, p.y, p.z);
    }

    private boolean kineticDamageReady(Vec3 movement, Vec3 axis) {
        KineticWeapon kinetic = mc.player.getMainHandItem().get(DataComponents.KINETIC_WEAPON);
        if (kinetic == null || kinetic.damageConditions().isEmpty()) return false;
        double forward = movement.dot(axis) * 20;
        double relative = Math.max(0, movement.subtract(vVel).dot(axis) * 20);
        return kinetic.damageConditions().get().test(Math.max(0, srvCouch - kinetic.delayTicks()), forward, relative, 1.0);
    }

    private LanceAttackMath.Lane chooseLane(AABB b, Vec3 cur, double strikeGap, double restGap) {
        LanceAttackMath.Bounds targetBox = new LanceAttackMath.Bounds(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
        LanceAttackMath.Lane vertical = LanceAttackMath.vertical(targetBox, strikeGap, dip.get(), restGap);
        double preferred = crystalPolicy.get() == CrystalPolicy.Distance ? 0 : maxCrystalDamage.get();
        List<LanceAttackMath.Lane> candidates = new ArrayList<>();
        candidates.add(vertical);

        if (attackLane != null && (!attackLane.horizontal() || horizontalFallback.get())) {
            double oldGap = vec(attackLane.impact()).add(0, EYE, 0).distanceTo(vec(attackLane.contact()));
            LanceAttackMath.Lane current = attackLane.horizontal()
                ? LanceAttackMath.horizontal(targetBox, attackLane.axis().x(), attackLane.axis().z(), oldGap, dip.get(), restGap)
                : LanceAttackMath.vertical(targetBox, oldGap, dip.get(), Math.max(restGap, oldGap + dip.get()));
            double currentDamage = laneDamage(current);
            if (currentWebCoverage() && currentDamage <= preferred && damageAllowed(currentDamage) && strikeCorridorClear(current)
                && clearApproach(cur, current) && damageAllowed(segmentDamage(cur, vec(current.launch())))) return current;
            candidates.add(current);
        }
        boolean clear = strikeCorridorClear(vertical);
        double verticalDamage = laneDamage(vertical);
        double possibleDamage = potentialLaneDamage(vertical);
        if (crystalPolicy.get() == CrystalPolicy.EstimatedDamage && (verticalDamage > preferred || possibleDamage > preferred)) {
            AttackRange range = weavingTier != null ? weavingRange : mc.player.getAttackRangeWith(mc.player.isUsingItem() ? mc.player.getUseItem() : mc.player.getMainHandItem());
            double outerGap = range != null ? range.effectiveMaxRange(mc.player) - 0.3 : strikeGap;
            if (outerGap > strikeGap + 0.05) {
                candidates.add(LanceAttackMath.vertical(targetBox, outerGap, dip.get(), Math.max(restGap, outerGap + dip.get())));
            }
        }
        if (horizontalFallback.get() && (!clear || verticalDamage > preferred || possibleDamage > preferred
            || terrainBlocked && blockedTicks >= 3 || attackLane != null && attackLane.horizontal())) {
            for (int i = 0; i < 8; i++) {
                double angle = i * Math.PI / 4;
                candidates.add(LanceAttackMath.horizontal(targetBox, Math.cos(angle), Math.sin(angle), strikeGap, dip.get(), restGap));
            }
        }
        LanceAttackMath.Lane best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (LanceAttackMath.Lane candidate : candidates) {
            if (!strikeCorridorClear(candidate)) continue;
            Vec3 destination = vec(candidate.launch()), delta = destination.subtract(cur);
            boolean approachClear = clearApproach(cur, candidate);
            Vec3 firstStep = sweepMove(mc.player.position(), cur, approachMove(delta, MAX_H, 1.2));

            if (delta.length() > 0.3 && firstStep.length() < 0.05) continue;
            if (!damageAllowed(segmentDamage(cur, cur.add(firstStep)))) continue;
            boolean sameLane = attackLane != null && candidate.horizontal() == attackLane.horizontal()
                && vec(candidate.axis()).dot(vec(attackLane.axis())) > 0.99
                && Math.abs(vec(candidate.impact()).add(0, EYE, 0).distanceTo(vec(candidate.contact()))
                - vec(attackLane.impact()).add(0, EYE, 0).distanceTo(vec(attackLane.contact()))) < 0.05;
            double score = LanceLanePolicy.score(laneDamage(candidate), potentialLaneDamage(candidate), delta.length(),
                preferred, damageLimit(), sameLane, approachClear);

            if (!laneClear(candidate)) score += 3;
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        if (best != null) {
            if (attackLane == null || best.horizontal() != attackLane.horizontal()
                || vec(best.axis()).dot(vec(attackLane.axis())) < 0.99) {
                dbg("LANE chosen horizontal=%b overheadClear=%b blocked=%d launch=%s axis=%s observed=%d possible=%d score=%.3f",
                    best.horizontal(), clear, blockedTicks, best.launch(), best.axis(), crystalPositions.size(), potentialCrystalPositions.size(), bestScore);
            }
            return best;
        }

        return vertical;
    }

    private boolean clearApproach(Vec3 cur, LanceAttackMath.Lane lane) {
        Vec3 delta = vec(lane.launch()).subtract(cur);
        return sweepMove(mc.player.position(), cur, delta).distanceToSqr(delta) <= 1e-6;
    }

    private boolean strikeCorridorClear(LanceAttackMath.Lane lane) {
        Vec3 launch = vec(lane.launch()), delta = vec(lane.impact()).subtract(launch);
        return sweepMove(mc.player.position(), launch, delta).distanceToSqr(delta) <= 1e-6 && attackRayClear(lane);
    }

    private boolean laneClear(LanceAttackMath.Lane lane) {
        Vec3 launch = vec(lane.launch()), impact = vec(lane.impact()), rest = vec(lane.rest()), axis = vec(lane.axis());
        Vec3 continuation = impact.add(axis.scale(0.3));
        for (Vec3[] segment : List.of(new Vec3[]{rest, launch}, new Vec3[]{launch, continuation})) {
            Vec3 delta = segment[1].subtract(segment[0]);
            if (sweepMove(mc.player.position(), segment[0], delta).distanceToSqr(delta) > 1e-6) return false;
        }
        return attackRayClear(lane);
    }

    private boolean attackRayClear(LanceAttackMath.Lane lane) {
        Vec3 eye = vec(lane.impact()).add(0, EYE, 0);
        return mc.level.clip(new ClipContext(eye, vec(lane.contact()), ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE, mc.player)).getType() == HitResult.Type.MISS;
    }

    private Vec3 sweepMove(Vec3 real, Vec3 cur, Vec3 d) {
        AABB body = bodyAt(cur);
        AABB broad = box(LancePathSafety.swept(bounds(body), d.x, d.y, d.z));
        double fraction = LancePathSafety.safeFraction(bounds(body), d.x, d.y, d.z, pathObstacles(broad, true));
        return d.scale(fraction);
    }

    private static LancePathSafety.Bounds bounds(AABB b) {
        return new LancePathSafety.Bounds(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
    }

    private static AABB box(LancePathSafety.Bounds b) {
        return new AABB(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
    }

    private AABB bodyAt(Vec3 feet) {
        return mc.player.getBoundingBox().move(feet.subtract(mc.player.position()));
    }

    private boolean hasWeb(AABB b) {
        for (BlockPos pos : BlockPos.betweenClosed(Mth.floor(b.minX), Mth.floor(b.minY), Mth.floor(b.minZ),
            Mth.floor(b.maxX), Mth.floor(b.maxY), Mth.floor(b.maxZ))) {
            PendingWeb pending = pendingWebs.get(pos);
            if ((mc.level.getBlockState(pos).is(Blocks.COBWEB) || pending != null && !pending.expired)
                && b.intersects(new AABB(pos))) return true;
        }
        return false;
    }

    private double webSafeFraction(AABB body, Vec3 d) {
        AABB broad = box(LancePathSafety.swept(bounds(body), d.x, d.y, d.z));
        return LancePathSafety.safeFraction(bounds(body), d.x, d.y, d.z, pathObstacles(broad, false));
    }

    private List<LancePathSafety.Bounds> pathObstacles(AABB broad, boolean solids) {
        List<LancePathSafety.Bounds> obstacles = new ArrayList<>();
        if (solids) {
            for (VoxelShape shape : mc.level.getBlockCollisions(mc.player, broad)) {
                for (AABB collision : shape.toAabbs()) obstacles.add(bounds(collision));
            }
            for (VoxelShape shape : mc.level.getEntityCollisions(mc.player, broad)) {
                for (AABB collision : shape.toAabbs()) obstacles.add(bounds(collision));
            }
            if (mc.level.getWorldBorder().isInsideCloseToBorder(mc.player, broad)) {
                for (AABB collision : mc.level.getWorldBorder().getCollisionShape().toAabbs()) {

                    if (collision.intersects(broad)) obstacles.add(bounds(collision.intersect(broad.inflate(1))));
                }
            }
        }
        for (BlockPos pos : BlockPos.betweenClosed(Mth.floor(broad.minX), Mth.floor(broad.minY), Mth.floor(broad.minZ),
            Mth.floor(broad.maxX), Mth.floor(broad.maxY), Mth.floor(broad.maxZ))) {
            PendingWeb pending = pendingWebs.get(pos);
            if (mc.level.getBlockState(pos).is(Blocks.COBWEB) || pending != null && !pending.expired) obstacles.add(bounds(new AABB(pos)));
        }
        return obstacles;
    }

    private Vec3 routeAroundWeb(Vec3 real, Vec3 cur, Vec3 want, double capH, double capV) {
        Vec3 direct = capMove(want.subtract(cur), capH, capV);
        if (sweepMove(real, cur, direct).distanceToSqr(direct) < 1e-10) {
            avoidanceWaypoint = avoidanceRise = null;
            return null;
        }
        if (avoidanceWaypoint != null && tickCounter > avoidanceUntil) {
            avoidanceWaypoint = avoidanceRise = null;
        }
        if (avoidanceWaypoint != null && cur.distanceTo(avoidanceWaypoint) < 0.15) {
            avoidanceWaypoint = avoidanceRise;
            avoidanceRise = null;
        }
        if (avoidanceWaypoint == null) {
            if (webSafeFraction(bodyAt(cur), direct) >= 0.999) return null;
            Vec3 relativeGoal = want.subtract(cur);
            AABB body = bodyAt(cur);
            AABB routeArea = body.inflate(4, 0, 4).expandTowards(0, 3, 0);
            var detour = LancePathSafety.detour(bounds(bodyAt(cur)), relativeGoal.x, relativeGoal.y, relativeGoal.z,
                pathObstacles(routeArea, true));
            if (detour == null) return null;
            Vec3 side = cur.add(detour.sideX(), 0, detour.sideZ());
            Vec3 top = side.add(0, detour.rise(), 0);
            boolean straightUp = detour.sideX() == 0 && detour.sideZ() == 0;
            avoidanceWaypoint = straightUp ? top : side;
            avoidanceRise = straightUp ? null : top;
            avoidanceUntil = tickCounter + 20;
            dbg("WEB-DETOUR waypoint=%.2f/%.2f/%.2f", avoidanceWaypoint.x, avoidanceWaypoint.y, avoidanceWaypoint.z);
        }
        Vec3 step = capMove(avoidanceWaypoint.subtract(cur), capH, capV);
        Vec3 safe = sweepMove(real, cur, step);
        if (safe.lengthSqr() < 1e-6) avoidanceWaypoint = avoidanceRise = null;
        return safe;
    }

    private int wireProtectionTicks() {

        return Math.min(24, Math.max(6, couchTiming.latencyTicks(PlayerUtils.getPing()) + 4));
    }

    private void rememberWire(Vec3 position) {
        AABB footprint = bodyAt(position).inflate(0.3);

        footprint = new AABB(footprint.minX, footprint.minY, footprint.minZ, footprint.maxX,
            Math.max(footprint.maxY, position.y + 1.8), footprint.maxZ);
        if (lastWire != null && position.distanceToSqr(lastWire) < 36) footprint = footprint.minmax(bodyAt(lastWire).inflate(0.3));
        recentWire.addLast(new WireFootprint(tickCounter, footprint));
        while (recentWire.size() > 128) recentWire.removeFirst();
    }

    private static Vec3 capMove(Vec3 d, double capH, double capV) {
        return d.scale(LanceMovementMath.capScale(d.x, d.y, d.z, capH, capV));
    }

    private static Vec3 approachMove(Vec3 d, double capH, double capV) {
        LanceMovementMath.Step step = LanceMovementMath.approachStep(d.x, d.y, d.z, capH, capV);
        return new Vec3(step.x(), step.y(), step.z());
    }

    private void walkHome() {
        if (lastClaim == null) {
            returnClaim = null;
            ox = oy = oz = 0;
            homeBlocked = 0;
            return;
        }
        if (!mc.player.isFallFlying() || srvGliding == Boolean.FALSE) {
            adoptGhost("flight ended during return");
            return;
        }
        Vec3 real = mc.player.position();
        if (lastClaim.distanceTo(real) > maxOffset.get() + 8) {
            adoptGhost("return leash exceeded");
            return;
        }

        Vec3 cur = lastWire != null ? lastWire : lastClaim;
        Vec3 d = real.subtract(cur);
        d = sweepMove(real, cur, capMove(d, MAX_H, 1.2));
        refreshCrystalSafety();
        d = protectCrystalMove(cur, real, d, maxOffset.get() * 0.95);
        if (d.length() < 0.05 && cur.distanceTo(real) > 0.05) {

            lastClaim = cur;
            returnClaim = null;
            ox = cur.x - real.x;
            oy = cur.y - real.y;
            oz = cur.z - real.z;
            if (++homeBlocked >= 40) adoptGhost("return obstructed");
            return;
        }
        homeBlocked = 0;
        Vec3 next = cur.add(d);
        ox = next.x - real.x;
        oy = next.y - real.y;
        oz = next.z - real.z;
        lastClaim = next;
        claimsSinceSetback++;
        returnClaim = null;
        if (Math.abs(ox) < 0.05 && Math.abs(oy) < 0.05 && Math.abs(oz) < 0.05) {

            returnClaim = lastClaim;
            wireSyncWanted = true;
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onSend(PacketEvent.Send event) {
        if (!ownsCurrentBody() || correcting || event.connection != ownerNetwork.getConnection()) return;
        if (event.packet instanceof ServerboundPlayerCommandPacket c && c.getAction() == ServerboundPlayerCommandPacket.Action.START_FALL_FLYING)
            relightPending = relightWait();
        if (!(event.packet instanceof ServerboundMovePlayerPacket p)) return;

        if (protectedWireTick == tickCounter) {
            event.cancel();
            return;
        }
        if (lastClaim == null) return;
        if (plannedAimTick == tickCounter) {

            if (!p.hasRotation() || !p.hasPosition()) {
                event.cancel();
                sendPos();
                return;
            }
            ((PlayerMoveC2SPacketAccessor) p).oehrasa$setYaw(plannedYaw);
            ((PlayerMoveC2SPacketAccessor) p).oehrasa$setPitch(plannedPitch);
        }

        ((PlayerMoveC2SPacketAccessor) p).oehrasa$setOnGround(false);
        if (!p.hasPosition()) return;
        ((PlayerMoveC2SPacketAccessor) p).oehrasa$setX(lastClaim.x);
        ((PlayerMoveC2SPacketAccessor) p).oehrasa$setY(lastClaim.y);
        ((PlayerMoveC2SPacketAccessor) p).oehrasa$setZ(lastClaim.z);
    }

    @EventHandler
    private void onSent(PacketEvent.Sent event) {
        if (!ownsCurrentBody() || correcting || event.connection != ownerNetwork.getConnection()) return;
        if (relightArmed && event.packet instanceof ServerboundMovePlayerPacket rp && rp.hasPosition() && !rp.isOnGround())
            relightAirborneMove = true;
        if (event.packet instanceof ServerboundUseItemOnPacket block && sendingWeb != null) {
//? if >=26.3 {
            /*webSentSequence = block.sequence();
            BlockHitResult hit = block.hitResult();
*///?} else {
            webSentSequence = block.getSequence();
            BlockHitResult hit = block.getHitResult();
//?}
            dbg("BLOCK-SENT intended=%s clicked=%s side=%s hit=%s ghostDistance=%.3f sequence=%d selected=%d active=%s using=%b",
                sendingWeb.pos.toShortString(), hit.getBlockPos().toShortString(), hit.getDirection(), hit.getLocation(),
                lastWire == null ? -1 : lastWire.add(0, EYE, 0).distanceTo(hit.getLocation()), //? if >=26.3 {
                /*block.sequence(),
*///?} else {
                block.getSequence(),
//?}
                mc.player.getInventory().getSelectedSlot(), mc.player.getUseItem().getItem(), mc.player.isUsingItem());
        } else if (event.packet instanceof ServerboundSetCarriedItemPacket slot) {
            dbg("ITEM-SENT SLOT slot=%d selected=%d active=%s using=%b key=%b weave=%b", slot.getSlot(),
                mc.player.getInventory().getSelectedSlot(), mc.player.getUseItem().getItem(), mc.player.isUsingItem(), mc.options.keyUse.isDown(), weavingTier != null);
        } else if (event.packet instanceof ServerboundUseItemPacket use) {
//? if >=26.3 {
            /*dbg("ITEM-SENT USE hand=%s sequence=%d selected=%d active=%s using=%b key=%b gen=%d", use.hand(), use.sequence(),
*///?} else {
            dbg("ITEM-SENT USE hand=%s sequence=%d selected=%d active=%s using=%b key=%b gen=%d", use.getHand(), use.getSequence(),
//?}
                mc.player.getInventory().getSelectedSlot(), mc.player.getUseItem().getItem(), mc.player.isUsingItem(), mc.options.keyUse.isDown(), couchGeneration);
        } else if (event.packet instanceof ServerboundPlayerActionPacket action && action.getAction() == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM) {
            dbg("ITEM-SENT RELEASE selected=%d active=%s using=%b key=%b gen=%d", mc.player.getInventory().getSelectedSlot(),
                mc.player.getUseItem().getItem(), mc.player.isUsingItem(), mc.options.keyUse.isDown(), couchGeneration);
        }
        if (!(event.packet instanceof ServerboundMovePlayerPacket p) || !p.hasPosition()) return;
        recordWire(p);
        posSentThisTick = true;
        if (recordStrikeOnSend && target != null && lastClaim != null && lastWire.distanceToSqr(lastClaim) < 1e-6) {
            dbg("STRIKE-SENT target=%d wire=%s", target.getId(), lastWire);
            beat.strike(tickCounter);
            strikeLast = true;
            pendingStrikes.addLast(new StrikeObservation(tickCounter, target.getId()));
            while (pendingStrikes.size() > 16) pendingStrikes.removeFirst();
            recordStrikeOnSend = false;
        }
        if (lastClaim != null && lastWire.distanceToSqr(lastClaim) < 1e-6) {
            if (protectMovementThisTick) protectedWireTick = tickCounter;
            wireSyncWanted = false;
            if (lastClaim == returnClaim) {
                lastClaim = returnClaim = null;
                ox = oy = oz = 0;
                dbg("RETURN synced on wire");
            }
        } else if (lastClaim == null && lastWire.distanceToSqr(mc.player.position()) < 1e-6) {
            wireSyncWanted = false;
        }
    }

    private void recordWire(ServerboundMovePlayerPacket p) {
        Vec3 position = new Vec3(p.getX(mc.player.getX()), p.getY(mc.player.getY()), p.getZ(mc.player.getZ()));
        if (lastClaim != null && position.distanceToSqr(lastClaim) < 1e-6) lastClaimWireTick = tickCounter;
        else if (lastClaim != null) dbg("WIRE claim mismatch error=%.3f tag=%d intended=%s sent=%s", position.distanceTo(lastClaim),
            ((IServerboundMovePlayerPacket) p).meteor$getTag(), lastClaim, position);
        if (lastWire != null) {
            Vec3 delta = position.subtract(lastWire);
            wireTrace.addLast(String.format("t%d h%.2f v%+.2f g%b%s", tickCounter, Math.hypot(delta.x, delta.z), delta.y, p.isOnGround(), posSentThisTick ? " extra" : ""));
            while (wireTrace.size() > 8) wireTrace.removeFirst();
            if (posSentThisTick && delta.lengthSqr() > 1e-6) dbg("WIRE extra position in tick: delta=%s claimError=%.3f", delta,
                lastClaim == null ? 0 : position.distanceTo(lastClaim));
        }
        rememberWire(position);
        lastWire = position;
        lastWireTick = tickCounter;
    }

    @EventHandler
    private void onSendPost(SendMovementPacketsEvent.Post event) {
        if (!ownsCurrentBody() || correcting) return;
        relightPost();
        boolean claimSent = posSentThisTick && (lastClaim == null
            || lastWire != null && lastWire.distanceToSqr(lastClaim) < 1e-6);
        if (!claimSent && (lastClaim != null || wireSyncWanted || volleyWanted || weavingTier != null)) sendPos();
        if (volleyWanted || weavingTier != null) {
            volleyWanted = false;
            if (posSentThisTick) webTick();
        }
    }

    private void sendPos() {
        ServerboundMovePlayerPacket p = new ServerboundMovePlayerPacket.PosRot(mc.player.getX() + ox, mc.player.getY() + oy, mc.player.getZ() + oz,
            plannedAimTick == tickCounter ? plannedYaw : Rotations.serverYaw,
            plannedAimTick == tickCounter ? plannedPitch : Rotations.serverPitch, false, mc.player.horizontalCollision);
        ((IServerboundMovePlayerPacket) p).meteor$setTag(TAG);
        mc.getConnection().send(p);
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {

        EchoOwner observedOwner = echoOwner;
        long observedGeneration = couchGeneration;
        if (observedOwner != null && event.connection == observedOwner.connection
            && event.packet instanceof ClientboundSetEntityDataPacket p && p.id() == observedOwner.playerId) {
            for (SynchedEntityData.DataValue<?> entry : p.packedItems()) {
                if (entry.id() == 0 && entry.value() instanceof Byte b) {
                    glideEchoes.add(new GlideEcho(observedOwner, (b & 0x80) != 0));
                } else if (entry.id() == LivingEntityAccessor.quiettee$livingFlags().id() && entry.value() instanceof Byte b) {
                    useEchoes.add(new UseEcho(observedOwner, observedGeneration, (b & 1) != 0));
                }
            }
        }
        BlockPos selfWeb = clearingWeb;
        if (observedOwner != null && event.connection == observedOwner.connection && (!pendingWebSnapshot.isEmpty() || selfWeb != null)) {
            Set<BlockPos> watched = pendingWebSnapshot;
            if (event.packet instanceof ClientboundBlockUpdatePacket p && (watched.contains(p.getPos()) || p.getPos().equals(selfWeb))) {
                webEchoes.add(new WebEcho(observedOwner, p.getPos().immutable(), p.getBlockState().is(Blocks.COBWEB)));
            } else if (event.packet instanceof ClientboundSectionBlocksUpdatePacket p) {
                p.runUpdates((pos, state) -> {
                    if (watched.contains(pos) || pos.equals(selfWeb))
                        webEchoes.add(new WebEcho(observedOwner, pos.immutable(), state.is(Blocks.COBWEB)));
                });
            }
        }
    }

    private void drainGlideEchoes() {
        GlideEcho echo;
        while ((echo = glideEchoes.poll()) != null) {
            if (!ownsCurrentBody() || echo.owner != echoOwner) continue;
            if (srvGliding == null || echo.gliding != srvGliding) dbg("GLIDE echo %s", echo.gliding ? "ON" : "OFF");
            if (!echo.gliding && (srvGliding == Boolean.TRUE || lastClaim != null)) beginGlideRecovery();
            if (echo.gliding || srvGliding == null || echo.gliding != srvGliding) relightPending = 0;
            srvGliding = echo.gliding;
            if (echo.gliding) {
                glideRecovery = recoveryJumped = false;
                recoveryJumpTick = -1000;
                recoveryVy = 0;
            }
        }
    }

    private void drainUseEchoes() {
        UseEcho echo;
        while ((echo = useEchoes.poll()) != null) {
            if (!ownsCurrentBody() || echo.owner != echoOwner) continue;
            boolean fresh = couchTiming.observe(echo.generation, echo.using, tickCounter);
            if (!echo.using) srvUseOffTick = tickCounter;
            srvUse = echo.using;

            dbg("SRV-USE %s fresh=%b (press %dt ago, srvC=%d, gen=%d/%d)", srvUse ? "on" : "off", fresh,
                tickCounter - couchPressTick, srvCouch, echo.generation, couchGeneration);
        }
    }

    private void drainWebEchoes() {
        boolean changed = false;
        WebEcho echo;
        while ((echo = webEchoes.poll()) != null) {
            if (!ownsCurrentBody() || echo.owner != echoOwner) continue;
            if (echo.pos.equals(clearingWeb)) {
                clearingConfirmed = !echo.web;
                dbg("SELF-WEB server=%s at=%s", echo.web ? "web" : "clear", echo.pos.toShortString());
            }
            PendingWeb pending = pendingWebs.get(echo.pos);
            if (pending == null) continue;
            if (echo.web) {
                pendingWebs.remove(echo.pos);
                telWebConfirmed++;
            } else pendingWebs.put(echo.pos, new PendingWeb(pending.tick, true));
            changed = true;
            dbg("WEB-STATE %s %s age=%dt", echo.pos.toShortString(), echo.web ? "server-web" : "server-not-web", tickCounter - pending.tick);
        }
        int timeout = Math.max(40, couchTiming.latencyTicks(PlayerUtils.getPing()) * 3 + 10);
        for (var iterator = pendingWebs.entrySet().iterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            PendingWeb pending = entry.getValue();
            if (pending.expired && !mc.level.getBlockState(entry.getKey()).is(Blocks.COBWEB)) {
                iterator.remove();
                changed = true;
            } else if (!pending.expired && tickCounter - pending.tick >= timeout) {
                entry.setValue(new PendingWeb(pending.tick, true));
                dbg("WEB-STATE %s unconfirmed after %dt", entry.getKey().toShortString(), tickCounter - pending.tick);
            }
        }
        if (changed) pendingWebSnapshot = Set.copyOf(pendingWebs.keySet());
    }

    @EventHandler
    private void onCorrectionBefore(PlayerPositionLookEvent.Before event) {

        correcting = true;
        plannedAimTick = crystalRefreshTick = Integer.MIN_VALUE;
        protectMovementThisTick = false;
        protectedWireTick = -1000;
        finishWeave(true);
        recordStrikeOnSend = false;
        clearPostWebRetry();
        correctionFrom = lastWire;
    }

    @EventHandler
    private void onCorrectionAfter(PlayerPositionLookEvent.After event) {
        if (!correcting) return;
        boolean sameBody = ownsCurrentBody();
        clearPositionState();
        correcting = false;
        pendingStrikes.clear();
        if (sameBody) {
            dbg("CORRECTION from=%s to=%s localWeb=%b pending=%d velocity=%s claimAge=%dt volleyAge=%dt wire=%s",
                correctionFrom, mc.player.position(), hasWeb(mc.player.getBoundingBox()), pendingWebs.size(), mc.player.getDeltaMovement(),
                tickCounter - lastClaimWireTick, tickCounter - lastWebVolleyTick, String.join(" | ", wireTrace));
            lastWire = mc.player.position();
            rememberWire(lastWire);
            lastWireTick = tickCounter;
            posSentThisTick = true;
            if (isActive()) onSetback();
            if (srvGliding == Boolean.FALSE) {
                beginGlideRecovery();
                recoveryVy = Math.min(0, mc.player.getDeltaMovement().y);
            }
        }
        if (homing) stopHoming();
    }

    private boolean selfWebTick() {
        manualWebControl = false;
        if (tickCounter < selfWebPauseUntil) {
            manualWebControl = true;
            return false;
        }
        if (!clearSelfWebs.get() || mc.gameMode == null) {
            finishSelfWeb(true);
            manualWebControl = hasWeb(bodyAt(lastWire != null ? lastWire : mc.player.position()));
            if (manualWebControl) {
                if (lastClaim != null) adoptGhost("manual web recovery");
                releaseCouch();
            }
            return false;
        }
        if (clearingWeb != null && mc.player.getInventory().getSelectedSlot() == clearingSlot
            && !usableWebTool(mc.player.getMainHandItem())) finishSelfWeb(true);
        if (clearingWeb != null) {
            if (mc.player.getInventory().getSelectedSlot() != clearingSlot || mc.options.keyAttack.isDown()
                || mc.player.isUsingItem() && tier(mc.player.getUseItem().getItem()) == null) {
                finishSelfWeb(false);
                selfWebPauseUntil = tickCounter + 20;
                manualWebControl = true;
                return false;
            }
            if (tickCounter - clearingStartedAt >= 200) {
                dbg("SELF-WEB timeout at=%s; yielding control without claiming server removal", clearingWeb.toShortString());
                finishSelfWeb(true);
                selfWebPauseUntil = tickCounter + 40;
                manualWebControl = true;
                return false;
            }
            if (clearingConfirmed && !mc.level.getBlockState(clearingWeb).is(Blocks.COBWEB)) {
                dbg("SELF-WEB removed at=%s", clearingWeb.toShortString());
                finishSelfWeb(true);
                cooldown = Math.max(cooldown, 4);
            } else {

                if (!bodyAt(lastWire != null ? lastWire : mc.player.position()).inflate(0.05).intersects(new AABB(clearingWeb))) {
                    finishSelfWeb(true);
                } else {
                    if (mc.level.getBlockState(clearingWeb).is(Blocks.COBWEB)) {
                        BlockBreaker.breakBlock(clearingWeb, true);
                    }
                    return true;
                }
            }
        }

        Vec3 server = lastWire != null ? lastWire : mc.player.position();
        AABB body = bodyAt(server);
        BlockPos obstruction = null;
        for (BlockPos pos : BlockPos.betweenClosed(Mth.floor(body.minX), Mth.floor(body.minY), Mth.floor(body.minZ),
            Mth.floor(body.maxX), Mth.floor(body.maxY), Mth.floor(body.maxZ))) {
            if (body.intersects(new AABB(pos)) && mc.level.getBlockState(pos).is(Blocks.COBWEB)) {
                obstruction = pos.immutable();
                break;
            }
        }
        if (obstruction == null) return false;
        if (mc.options.keyAttack.isDown() || mc.player.isUsingItem() && tier(mc.player.getUseItem().getItem()) == null) {
            manualWebControl = true;
            if (lastClaim != null) adoptGhost("manual web recovery");
            releaseCouch();
            return false;
        }
        if (lastClaim != null) adoptGhost("web contact on the wire path");
        releaseCouch();
        fActive = fVerticalActive = false;
        strikeLast = volleyWanted = false;
        mc.player.setDeltaMovement(Vec3.ZERO);

        BlockState state = mc.level.getBlockState(obstruction);
        int toolSlot = -1;
        double bestSpeed = 0;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (!usableWebTool(stack)) continue;
            double speed = stack.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                toolSlot = slot;
            }
        }
        if (toolSlot < 0) {
            if (tickCounter % 20 == 0) dbg("SELF-WEB holding at=%s; need hotbar sword/shears", obstruction.toShortString());
            return true;
        }
        clearingOriginalSlot = mc.player.getInventory().getSelectedSlot();
        clearingSlot = toolSlot;
        clearingConfirmed = false;
        clearingStartedAt = tickCounter;
        clearingWeb = obstruction;
        InvUtils.swap(toolSlot, false);
        BlockBreaker.breakBlock(obstruction, true);
        dbg("SELF-WEB start at=%s toolSlot=%d", obstruction.toShortString(), toolSlot);
        return true;
    }

    private static boolean usableWebTool(ItemStack stack) {
        return (stack.is(Items.SHEARS) || stack.is(ItemTags.SWORDS))
            && (!stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage() - 2);
    }

    private void finishSelfWeb(boolean restore) {
        if (clearingWeb != null && ownsCurrentBody()) {
            if (mc.gameMode != null) {
                BlockUtils.breaking = false;
                mc.gameMode.stopDestroyBlock();
            }
            if (restore && clearingOriginalSlot >= 0 && mc.player.getInventory().getSelectedSlot() == clearingSlot)
                InvUtils.swap(clearingOriginalSlot, false);
            recouchIn = Math.max(recouchIn, 2);
        }
        clearingWeb = null;
        clearingSlot = clearingOriginalSlot = -1;
        clearingConfirmed = false;
        selfWebHold = false;
    }

    private void onSetback() {
        setbacks++;
        strikeLast = false;
        boolean passive = claimsSinceSetback == 0 || tickCounter - lastClaimWireTick > Math.max(3, couchTiming.latencyTicks(PlayerUtils.getPing()) + 2);
        claimsSinceSetback = 0;
        if (passive) {
            passiveSetbacks++;
            cooldown = Math.max(cooldown, 4);
        } else {
            cooldown = Math.max(cooldown, 10);
            lastSetbackAt = tickCounter;
        }
        dbg("SETBACK #%d%s srvGliding=%s trace=%s", setbacks, passive ? " PASSIVE" : "", srvGliding, String.join(" | ", trace));
        if (chatDebug.get() && !trace.isEmpty()) {
            info("setback #%d · last moves: %s", setbacks, String.join(" → ", trace));
            trace.clear();
        }
    }

    private Tier heldTier() {
        return tier(mc.player.getMainHandItem().getItem());
    }

    private int bestSpearSlot() {
        double bestMult = -1;
        int bestSlot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            Tier t = tier(s.getItem());
            if (t != null && t.mult() > bestMult) {
                bestMult = t.mult();
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    private void pressCouch() {

        if (mc.player.isUsingItem() && tier(mc.player.getUseItem().getItem()) != null) mc.player.stopUsingItem();
        setUseKey(true);
        srvCouch = 0;
        couchPressTick = tickCounter;
        couchGeneration = couchTiming.start(tickCounter);
        InteractionResult r = mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        dbg("couch PRESS (%s, srvUse=%b, gen=%d, replyBudget=%dt)", r, srvUse, couchGeneration, couchTiming.responseBudget(PlayerUtils.getPing()));
    }

    private void releaseCouch() {
        clearPostWebRetry();
        if (pressedUse) setUseKey(false);

        if (mc.player != null && mc.gameMode != null && srvCouch >= 0 && heldTier() != null) mc.gameMode.releaseUsingItem(mc.player);
        srvCouch = -1;
        couchGeneration = couchTiming.stop();
    }

    private void clearPostWebRetry() {
        couchTiming.cancelPostWebRetry();
        postWebSpearSlot = -1;
        postWebRetryTarget = null;
    }

    private void reassertCouch() {
        if (!ownsCurrentBody() || correcting || cooldown > 0 || selfWebHold || manualWebControl || !pressedUse
            || target == null || target != postWebRetryTarget || mc.player.getInventory().getSelectedSlot() != postWebSpearSlot
            || heldTier() == null || mc.options.keyAttack.isDown()
            || mc.player.isUsingItem() && tier(mc.player.getUseItem().getItem()) == null) {
            clearPostWebRetry();
            return;
        }
        if (!couchTiming.reassert(tickCounter, PlayerUtils.getPing())) return;

        if (mc.player.isUsingItem()) mc.player.stopUsingItem();
        srvCouch = 0;
        InteractionResult result = mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        dbg("couch USE-REASSERT (%s, firstAge=%dt, deadline=%dt, gen=%d; no release or slot change)",
            result, couchTiming.earliestUseAge(tickCounter), couchTiming.responseBudget(PlayerUtils.getPing()), couchGeneration);
    }

    private void setUseKey(boolean pressed) {
        mc.options.keyUse.setDown(pressed);
        pressedUse = pressed;
    }

    private void setTarget(Entity next) {
        finishWeave(true);
        clearPostWebRetry();
        target = next;
        crystalRefreshTick = Integer.MIN_VALUE;
        blindTicks = 0;
        cycleTick = period.get();
        beat.reset();
        attackLane = null;
        shellCenter = null;
        shellAttempted = false;
        strikeLast = false;
        lastHurt = next instanceof LivingEntity nle ? nle.hurtTime : 0;
        lastHp = next instanceof LivingEntity nle ? hp(nle) : 0;
        pendingStrikes.clear();
        dbg("target -> %s", next != null ? EntityUtils.getName(next) : "none");
    }

    private boolean acquire(Entity e) {
        return baseValid(e)
            && PlayerUtils.distanceTo(e) <= maxOffset.get() + 10
            && (ignoreWalls.get() || PlayerUtils.canSeeEntity(e))
            && inFov(e);
    }

    private boolean baseValid(Entity e) {
        if (e == mc.player || e == mc.getCameraEntity()) return false;
        if (!(e instanceof LivingEntity le) || le.isDeadOrDying() || !e.isAlive()) return false;
        if (!entities.get().contains(e.getType())) return false;
        return !(e instanceof Player p) || (!p.isCreative() && !p.isSpectator() && Friends.get().shouldAttack(p));
    }

    private boolean inFov(Entity e) {
        if (fov.get() >= 360) return true;
        AABB b = e.getBoundingBox();
        Vec3 eye = mc.player.getEyePosition();
        Vec3 dir = new Vec3(e.getX() - eye.x, (b.minY + b.maxY) * 0.5 - eye.y, e.getZ() - eye.z);
        if (dir.lengthSqr() <= 1e-6) return true;
        double dot = mc.player.getViewVector(1f).dot(dir.normalize());
        return Math.toDegrees(Math.acos(Mth.clamp(dot, -1, 1))) <= fov.get() / 2;
    }

    private static float hp(LivingEntity le) {
        return le.getHealth() + le.getAbsorptionAmount();
    }

    private boolean consumePlausibleStrike(int targetId) {
        int pingTicks = (int) Math.ceil(PlayerUtils.getPing() / 50.0);
        while (!pendingStrikes.isEmpty()) {
            StrikeObservation first = pendingStrikes.peekFirst();
            int age = tickCounter - first.tick;
            if (first.targetId != targetId || age > Math.max(1, pingTicks) + 6) {
                pendingStrikes.removeFirst();
                continue;
            }
            if (!LanceMovementMath.plausibleStrikeAge(age, pingTicks)) return false;
            pendingStrikes.removeFirst();
            return true;
        }
        return false;
    }

    private void webTick() {
        boolean continuing = weavingTier != null;
        if (target == null || lastWire == null || selfWebHold || manualWebControl || srvGliding == Boolean.FALSE
            || !webEnabled.get() || !mc.player.isFallFlying()) {
            finishWeave(true);
            return;
        }
        Tier currentTier = heldTier();
        boolean ready = currentTier != null && couchTiming.confirmed()
            && srvCouch >= currentTier.delay + couchTiming.latencyTicks(PlayerUtils.getPing()) + 1;
        if (!continuing && (currentTier == null || !LanceWebTiming.canStart(tickCounter, lastWebVolleyTick, period.get(), ready))) return;
        FindItemResult webs = InvUtils.findInHotbar(Items.COBWEB);
        if (!webs.found() || !webs.isHotbar()) {
            if (debug.get() && (chatDebug.get() && webNoItemThrottle++ % 200 == 0)) info("webbing: no cobwebs in hotbar");
            finishWeave(true);
            return;
        }
        int webSlot = continuing ? weavingSlot : webs.slot();
        if (continuing && (mc.player.getInventory().getSelectedSlot() != webSlot || !mc.player.getMainHandItem().is(Items.COBWEB))) {
            finishWeave(false);
            return;
        }

        Vec3 eye = lastWire.add(0, EYE, 0);
        int budget = Math.min(Math.max(websPerTick.get(), 1), mc.player.getInventory().getItem(webSlot).getCount());
        budget = Math.min(budget, Math.max(0, 256 - pendingWebs.size()));
        budget = Math.min(budget, 27 - (continuing ? weavingAttempts : 0));
        List<WebPlacement> plan = new ArrayList<>(budget);
        boolean covered = currentWebCoverage();
        for (LanceWebMath.Cell cell : LanceWebMath.captureCandidates(serverBounds(target), vVel.x, vVel.y, vVel.z,
            couchTiming.predictionTicks(PlayerUtils.getPing()), eye.x, eye.y, eye.z, webRange.get(), covered)) {
            if (plan.size() >= budget) break;
            WebPlacement placement = prepareWeb(cell);
            if (placement != null) plan.add(placement);
        }
        if (plan.isEmpty()) {
            if (tickCounter % 20 == 0) dbg("WEBS skip %s: no useful reachable unoccupied cells", volleyReason);
            finishWeave(true);
            return;
        }
        int attempted = 0, accepted = 0;
        boolean completed = false;
        boolean sneaking = mc.player.isShiftKeyDown();

        if (!continuing) {
            weavingTier = heldTier();
            weavingRange = mc.player.getAttackRangeWith(mc.player.isUsingItem() ? mc.player.getUseItem() : mc.player.getMainHandItem());
            weavingOriginalSlot = mc.player.getInventory().getSelectedSlot();
            weavingSlot = webSlot;
            weavingStartedAt = lastWebVolleyTick = tickCounter;
            weavingAttempts = 0;
            if (covered || vVel.lengthSqr() <= 0.0625) {
                shellAttempted = true;
                shellCenter = vPos;
            }
            releaseCouch();
        }
        try {
            if (!continuing) InvUtils.swap(webSlot, false);
            mc.player.setShiftKeyDown(false);
            for (WebPlacement planned : plan) {

                WebPlacement placement = prepareWeb(new LanceWebMath.Cell(planned.pos.getX(), planned.pos.getY(), planned.pos.getZ()));
                if (placement == null) continue;
                pendingWebs.put(placement.pos, new PendingWeb(tickCounter, false));
                pendingWebSnapshot = Set.copyOf(pendingWebs.keySet());
                sendingWeb = placement;
                webSentSequence = -1;
                try {
                    InteractionResult result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, placement.hit);
                    attempted++;
                    dbg("WEB-ATTEMPT intended=%s sequence=%d clientAccepted=%b", placement.pos.toShortString(), webSentSequence, result.consumesAction());
                    if (result.consumesAction()) {
                        accepted++;
    //? if >=26.3 {
                    /*mc.player.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
*///?} else {
                    mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
//?}
                    }
                } finally {
                    sendingWeb = null;
                }
            }
            completed = true;
        } finally {
            mc.player.setShiftKeyDown(sneaking);
            weavingAttempts += attempted;
            if (!completed || tickCounter - weavingStartedAt >= 2 || weavingAttempts >= 27
                || !mc.player.getMainHandItem().is(Items.COBWEB)) finishWeave(true);
        }
        telWebs += attempted;
        dbg("WEBS reason=%s attempted=%d clientAccepted=%d pending=%d burstContinues=%b (server confirmation pending)",
            volleyReason, attempted, accepted, pendingWebs.size(), weavingTier != null);
    }

    private void finishWeave(boolean restore) {
        if (weavingTier == null) return;
        boolean restoredSpear = false;
        if (restore && ownsCurrentBody() && weavingOriginalSlot >= 0
            && mc.player.getInventory().getSelectedSlot() == weavingSlot) {
            restoredSpear = weavingOriginalSlot != weavingSlot
                && (mc.player.getMainHandItem().is(Items.COBWEB) || mc.player.getMainHandItem().isEmpty())
                && tier(mc.player.getInventory().getItem(weavingOriginalSlot).getItem()) != null;
            restoredSpear &= InvUtils.swap(weavingOriginalSlot, false);
        }
        if (restoredSpear && target != null && !correcting && cooldown == 0 && !selfWebHold && !manualWebControl
            && !mc.options.keyAttack.isDown()
            && (!mc.player.isUsingItem() || tier(mc.player.getUseItem().getItem()) != null)) {
            postWebSpearSlot = weavingOriginalSlot;
            postWebRetryTarget = target;
            couchTiming.allowNextPostWebRetry();
        } else clearPostWebRetry();
        weavingTier = null;
        weavingRange = null;
        weavingSlot = weavingOriginalSlot = -1;
        weavingAttempts = 0;
        recouchIn = Math.max(recouchIn, 2);
    }

    private boolean observedWeb(LanceWebMath.Cell cell) {
        BlockPos pos = new BlockPos(cell.x(), cell.y(), cell.z());
        return !pendingWebs.containsKey(pos) && mc.level.getBlockState(pos).is(Blocks.COBWEB);
    }

    private boolean hasUsefulWeb(boolean covered) {
        if (lastWire == null || target == null) return false;
        Vec3 eye = lastWire.add(0, EYE, 0);
        for (LanceWebMath.Cell cell : LanceWebMath.captureCandidates(serverBounds(target), vVel.x, vVel.y, vVel.z,
            couchTiming.predictionTicks(PlayerUtils.getPing()), eye.x, eye.y, eye.z, webRange.get(), covered)) {
            if (prepareWeb(cell) != null) return true;
        }
        return false;
    }

    private boolean currentWebCoverage() {
        return target != null && LanceWebMath.hasCurrentWebCoverage(serverBounds(target), this::observedWeb);
    }

    private boolean needsWebMaintenance(boolean covered) {
        if (!covered) return true;
        if (shellCenter == null || shellCenter.distanceToSqr(vPos) > 2.25) {
            shellCenter = vPos;
            shellAttempted = false;
        }
        return !shellAttempted || LanceWebMath.isEscaping(serverBounds(target), vVel.x, vVel.y, vVel.z, this::observedWeb);
    }

    private WebPlacement prepareWeb(LanceWebMath.Cell candidate) {
        BlockPos pos = new BlockPos(candidate.x(), candidate.y(), candidate.z());
        if (pendingWebs.containsKey(pos) || !BlockUtils.canPlaceBlock(pos, false, Blocks.COBWEB)) return null;
        AABB cell = new AABB(pos);
        if (cell.intersects(mc.player.getBoundingBox().inflate(2.0))) return null;
        Vec3 ghostOffset = lastWire.subtract(mc.player.position());
        if (cell.intersects(mc.player.getBoundingBox().move(ghostOffset).inflate(1.0))) return null;
        for (WireFootprint entry : recentWire) {
            if (tickCounter - entry.tick <= wireProtectionTicks() && cell.intersects(entry.swept)) return null;
        }
        Vec3 eye = lastWire.add(0, EYE, 0);
        LanceWebMath.Click click = LanceWebMath.placementClick(candidate, eye.x, eye.y, eye.z, webRange.get(), webAirPlace.get(), support -> {
            BlockPos neighbour = new BlockPos(support.x(), support.y(), support.z());
            if (pendingWebs.containsKey(neighbour)) return false;
            BlockState state = mc.level.getBlockState(neighbour);
            return !state.isAir() && !state.canBeReplaced() && !BlockUtils.isClickable(state.getBlock()) && state.getFluidState().isEmpty();
        });
        if (click == null) return null;
        BlockPos clicked = new BlockPos(click.clicked().x(), click.clicked().y(), click.clicked().z());
        Direction side = click.sideX() < 0 ? Direction.WEST : click.sideX() > 0 ? Direction.EAST
                                                              : click.sideY() < 0 ? Direction.DOWN : click.sideY() > 0 ? Direction.UP
                                                                                                     : click.sideZ() < 0 ? Direction.NORTH : Direction.SOUTH;
        return new WebPlacement(pos, new BlockHitResult(new Vec3(click.hitX(), click.hitY(), click.hitZ()), side, clicked, false));
    }

    private LanceWebMath.Bounds serverBounds(Entity entity) {
        AABB box = entity.getBoundingBox().move(vPos.subtract(entity.position()));
        return new LanceWebMath.Bounds(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    private boolean victimWebbed(Entity entity) {
        return LanceWebMath.hasSustainedWebCoverage(serverBounds(entity), vVel.x, vVel.y, vVel.z, cell -> {
            BlockPos pos = new BlockPos(cell.x(), cell.y(), cell.z());
            return !pendingWebs.containsKey(pos) && mc.level.getBlockState(pos).is(Blocks.COBWEB);
        });
    }

    private void refreshCrystalSafety() {
        if (crystalRefreshTick == tickCounter) return;
        crystalRefreshTick = tickCounter;
        crystalPositions.clear();
        potentialCrystalPositions.clear();
        laneDamageCache.clear();
        potentialLaneDamageCache.clear();
        crystalPlacers = 0;
        crystalDamageCache.clear();
        crystalReductions = null;
        plannedCrystalDamage = 0;
        if (mc.player == null || mc.level == null) return;
        Vec3 wire = lastWire != null ? lastWire : mc.player.position();
        AABB scan = bodyAt(wire).inflate(14);
        if (target != null) scan = scan.minmax(new AABB(vPos.subtract(20, 20, 20), vPos.add(20, 20, 20)));
        Set<LanceCrystalSafety.Point> seen = new java.util.HashSet<>();
        for (Entity entity : mc.level.getEntities(mc.player, scan,
            entity -> entity instanceof EndCrystal && !entity.isRemoved())) {
            LanceCrystalSafety.Point point = crystalPoint(entity.position());
            if (seen.add(point)) crystalPositions.add(point);
        }
        if (crystalPolicy.get() == CrystalPolicy.Distance) return;
        crystalReductions = ExplosionReductions.of(mc.player);
        if (target == null) return;

        List<CrystalPlacer> placers = new ArrayList<>();
        for (Player player : mc.level.players()) {
            if (player == mc.player || !player.isAlive() || player.isRemoved() || player.isSpectator()
                || !Friends.get().shouldAttack(player)) continue;
//? if >=26.3 {
            /*Vec3 observed = player.getPosition(1.0F);
*///?} else {
            InterpolationHandler interpolator = player.getInterpolation();
            Vec3 observed = interpolator != null ? interpolator.position() : player.position();
//?}
            double reach = player.blockInteractionRange();
            if (observed.distanceToSqr(vPos) > (reach + 12) * (reach + 12)) continue;
            placers.add(new CrystalPlacer(crystalPoint(observed.add(0, player.getEyeHeight(player.getPose()), 0)), reach));
        }
        crystalPlacers = placers.size();
        if (placers.isEmpty()) return;

        int centerX = Mth.floor(vPos.x), centerY = Mth.floor(vPos.y), centerZ = Mth.floor(vPos.z);
        for (BlockPos base : BlockPos.betweenClosed(centerX - 5, centerY - 2, centerZ - 5, centerX + 5, centerY + 3, centerZ + 5)) {
            if (!mc.level.getChunkSource().hasChunk(base.getX() >> 4, base.getZ() >> 4)) continue;
            BlockState state = mc.level.getBlockState(base);
            if (!state.is(Blocks.OBSIDIAN) && !state.is(Blocks.BEDROCK)) continue;
            LanceCrystalSafety.Point baseMin = new LanceCrystalSafety.Point(base.getX(), base.getY(), base.getZ());
            boolean reachable = false;
            for (CrystalPlacer placer : placers) {
                if (LanceCrystalSafety.canPlaceAt(placer.eye(), baseMin, placer.reach())) {
                    reachable = true;
                    break;
                }
            }
            if (!reachable) continue;
            BlockPos above = base.above();
            if (!mc.level.getBlockState(above).isAir()) continue;
            Vec3 source = new Vec3(base.getX() + 0.5, base.getY() + 1, base.getZ() + 0.5);
            if (source.distanceToSqr(vPos) > 36) continue;
            AABB placementBox = new AABB(base.getX(), base.getY() + 1, base.getZ(), base.getX() + 1, base.getY() + 3, base.getZ() + 1);
            if (!mc.level.getEntities(mc.player, placementBox,
                entity -> !entity.isSpectator() && !entity.isRemoved()).isEmpty()) continue;
            LanceCrystalSafety.Point point = crystalPoint(source);
            if (seen.add(point)) potentialCrystalPositions.add(point);
        }
    }

    private static LanceCrystalSafety.Point crystalPoint(Vec3 point) {
        return new LanceCrystalSafety.Point(point.x, point.y, point.z);
    }

    private double segmentDamage(Vec3 from, Vec3 to) {
        return evaluateCrystalSegment(from, to, damageLimit());
    }

    private Vec3 protectCrystalMove(Vec3 cur, Vec3 real, Vec3 delta, double leash) {
        if (damageAllowed(segmentDamage(cur, cur.add(delta)))) return delta;
        double before = positionDamage(cur), after = positionDamage(cur.add(delta));
        if (!damageAllowed(before) && after < before - 0.01
            && evaluateCrystalSegment(cur, cur.add(delta), before + 1e-5) <= before + 1e-5) return delta;
        return crystalRetreat(cur, real, leash);
    }

    private double evaluateCrystalSegment(Vec3 from, Vec3 to, double limit) {
        if (mc.player == null || mc.level == null || from == null || to == null
            || !Double.isFinite(from.x) || !Double.isFinite(from.y) || !Double.isFinite(from.z)
            || !Double.isFinite(to.x) || !Double.isFinite(to.y) || !Double.isFinite(to.z)) return Double.POSITIVE_INFINITY;
        if (crystalPolicy.get() == CrystalPolicy.Distance) {
            double clearance = crystalClearance.get();
            if (clearance <= 0) return 0;
            Vec3 delta = to.subtract(from);
            double lengthSq = delta.lengthSqr(), worst = 0;
            for (LanceCrystalSafety.Point point : crystalPositions) {
                Vec3 source = new Vec3(point.x(), point.y(), point.z());
                double t = lengthSq == 0 ? 0 : Math.clamp(source.subtract(from).dot(delta) / lengthSq, 0, 1);
                worst = Math.max(worst, clearance - from.add(delta.scale(t)).distanceTo(source));
            }
            return worst;
        }
        return estimateCrystalSources(from, to, crystalPositions, limit, true);
    }

    private double estimateCrystalSources(Vec3 from, Vec3 to, List<LanceCrystalSafety.Point> sources, double limit, boolean observed) {
        if (crystalReductions == null) return Double.POSITIVE_INFINITY;
        return LanceCrystalSafety.estimateSegment(crystalPoint(from), crystalPoint(to), sources, limit,
            new LanceCrystalSafety.DamageModel() {
                @Override
                public double reduce(double raw) {
                    return crystalReductions.apply((float) raw);
                }

                @Override
                public double exact(LanceCrystalSafety.Point ghost, LanceCrystalSafety.Point source) {
                    Vec3 feet = new Vec3(ghost.x(), ghost.y(), ghost.z());
                    Double cached = observed ? crystalDamageCache.get(feet) : null;

                    if (cached != null) return cached;
                    return DamageUtils.crystalDamage(mc.player, feet, bodyAt(feet),
                        new Vec3(source.x(), source.y(), source.z()), DamageUtils.HIT_FACTORY);
                }
            }).maximumDamage();
    }

    private double positionDamage(Vec3 feet) {
        Double cached = crystalDamageCache.get(feet);
        if (cached != null) return cached;
        if (crystalPolicy.get() == CrystalPolicy.Distance) {
            double damage = evaluateCrystalSegment(feet, feet, 0);
            crystalDamageCache.put(feet, damage);
            return damage;
        }
        if (mc.player == null || mc.level == null || crystalReductions == null || feet == null
            || !Double.isFinite(feet.x) || !Double.isFinite(feet.y) || !Double.isFinite(feet.z)) return Double.POSITIVE_INFINITY;
        double maximum = 0, limit = damageLimit();
        AABB body = bodyAt(feet);
        for (LanceCrystalSafety.Point point : crystalPositions) {
            Vec3 source = new Vec3(point.x(), point.y(), point.z());
            double raw = LanceCrystalSafety.rawDamage(feet.distanceTo(source), 1);
            double bound = crystalReductions.apply((float) raw);
            double damage = bound <= limit ? bound
                : DamageUtils.crystalDamage(mc.player, feet, body, source, DamageUtils.HIT_FACTORY);
            if (!Double.isFinite(damage) || damage < 0) return Double.POSITIVE_INFINITY;
            maximum = Math.max(maximum, damage);
        }
        crystalDamageCache.put(feet, maximum);
        return maximum;
    }

    private double laneDamage(LanceAttackMath.Lane lane) {
        if (lane == null) return Double.POSITIVE_INFINITY;
        Double cached = laneDamageCache.get(lane);
        if (cached != null) return cached;
        LanceAttackMath.Point rest = lane.rest(), impact = lane.impact(), axis = lane.axis();

        double damage = segmentDamage(new Vec3(rest.x(), rest.y(), rest.z()),
            new Vec3(impact.x() + axis.x() * 0.3, impact.y() + axis.y() * 0.3, impact.z() + axis.z() * 0.3));
        laneDamageCache.put(lane, damage);
        return damage;
    }

    private double potentialLaneDamage(LanceAttackMath.Lane lane) {
        if (potentialCrystalPositions.isEmpty() || crystalPolicy.get() == CrystalPolicy.Distance) return 0;
        Double cached = potentialLaneDamageCache.get(lane);
        if (cached != null) return cached;

        double damage = estimateCrystalSources(vec(lane.rest()), vec(lane.impact()).add(vec(lane.axis()).scale(0.3)),
            potentialCrystalPositions, maxCrystalDamage.get(), false);
        potentialLaneDamageCache.put(lane, damage);
        return damage;
    }

    private boolean damageAllowed(double damage) {
        return Double.isFinite(damage) && damage >= 0 && damage <= damageLimit();
    }

    private double damageLimit() {
        if (crystalPolicy.get() == CrystalPolicy.Distance) return 0;
        if (mc.player == null) return 0;
        return LanceLanePolicy.hardLimit(hp(mc.player), maxCrystalDamage.get(), strictCrystalLimit.get());
    }

    private Vec3 crystalRetreat(Vec3 cur, Vec3 real, double leash) {
        double current = positionDamage(cur);
        if (!Double.isFinite(current) || damageAllowed(current)) return Vec3.ZERO;
        List<Vec3> directions = new ArrayList<>(12);
        Vec3 away = Vec3.ZERO;
        double nearest = Double.POSITIVE_INFINITY;
        for (LanceCrystalSafety.Point point : crystalPositions) {
            Vec3 delta = cur.subtract(point.x(), point.y(), point.z());
            double distance = delta.lengthSqr();
            if (distance < nearest && distance > 1e-8) {
                nearest = distance;
                away = delta.normalize();
            }
        }
        if (away.lengthSqr() > 0) directions.add(away);
        directions.add(new Vec3(0, 1, 0));
        directions.add(new Vec3(0, -1, 0));
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x != 0 || z != 0) directions.add(new Vec3(x, 0, z).normalize());
            }
        }
        Vec3 best = Vec3.ZERO;
        double bestDamage = current;
        Vec3 relative = cur.subtract(real);
        for (Vec3 direction : directions) {
            Vec3 step = capMove(direction.scale(MAX_H), MAX_H, MAX_V);
            double fraction = LanceMovementMath.followFraction(relative.x, relative.y, relative.z, step.x, step.y, step.z, leash);
            step = sweepMove(real, cur, step.scale(fraction));
            if (step.lengthSqr() < 0.01) continue;
            Vec3 next = cur.add(step);
            if (!mc.level.getChunkSource().hasChunk(Mth.floor(next.x) >> 4, Mth.floor(next.z) >> 4)) continue;
            double nextDamage = positionDamage(next);
            if (nextDamage >= bestDamage - 1e-4) continue;

            if (evaluateCrystalSegment(cur, next, current + 1e-5) > current + 1e-5) continue;
            best = step;
            bestDamage = nextDamage;
        }
        return best;
    }

    private void telemetryTick() {
        if (++telTicks < 20) return;
        if ((chatDebug.get() || dbgOut != null) && (target != null || telStrikes > 0)) {
            Tier t = heldTier();
            String line = String.format("LANCE %s · strikes %d · observed-hurts %d (%d in strike window) · observed-mob-loss %.1f · ~%.0f raw · couch %s%s · off %.1f · ping %dms · setbacks %d%s%s%s",
                target != null ? EntityUtils.getName(target) : "-", telStrikes, telHurts, telPlausibleHurts, telDmg, telPredicted,
                t == null ? "NO SPEAR" : (srvCouch >= 0 ? Math.max(0, srvCouch - t.delay) + "/" + t.window + "t" : "down"),
                srvCouch >= 0 && !srvUse ? " (SRV-USE OFF)" : "",
                Math.sqrt(ox * ox + oy * oy + oz * oz), PlayerUtils.getPing(), setbacks,
                passiveSetbacks > 0 ? String.format(" (%d passive)", passiveSetbacks) : "",
                webEnabled.get() ? " · web-attempts " + telWebs + " / server-web " + telWebConfirmed + (webbedNow ? " (covered)" : "") : "",
                telStrikes == 0 ? " · " + status : "");
            if (debug.get() && (chatDebug.get())) info("%s", line);
            dbg("TEL %s", line);
        }
        if (dbgOut != null) dbgOut.flush();
        telTicks = 0;
        telStrikes = telHurts = telPlausibleHurts = telWebs = telWebConfirmed = 0;
        telDmg = 0;
    }

    private void openDebug() {
        closeDebug();
        if (!fileDebug.get()) return;
        try {
            File dir = new File(mc.gameDirectory, "lance-debug");
            dir.mkdirs();
            File f = new File(dir, "lance-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".log");
            dbgOut = new PrintWriter(new BufferedWriter(new FileWriter(f, true)));
            dbg("=== LANCE r127 black box · %s ===", new Date());
            for (SettingGroup g : settings) for (Setting<?> s : g) dbg("cfg %s = %s", s.name, s.get());
            if (debug.get()) info("black box recording to %s", f.getAbsolutePath());
        } catch (Exception e) {
            dbgOut = null;
            if (debug.get()) info("black box failed to open: %s", e.getMessage());
        }
    }

    private void closeDebug() {
        if (dbgOut != null) {
            dbgOut.flush();
            dbgOut.close();
            dbgOut = null;
        }
    }

    private void dbg(String fmt, Object... args) {
        if (dbgOut == null) return;
        Object[] all = new Object[args.length + 1];
        all[0] = tickCounter;
        System.arraycopy(args, 0, all, 1, args.length);
        dbgOut.printf("[%d] " + fmt + "%n", all);
    }

    private boolean homing;
    private final Homer homer = new Homer();

    private class Homer {
        private boolean validateBody() {
            if (ownsCurrentBody() && !leavingWorld) return true;
            clearPositionState();
            stopHoming();
            return false;
        }

        @EventHandler
        private void onTickPre(TickEvent.Pre event) {
            if (!validateBody()) return;
            protectMovementThisTick = false;
            tickCounter++;
            drainGlideEchoes();
            prepareGlideRecovery();
        }

        @EventHandler(priority = EventPriority.LOWEST)
        private void onPlayerMove(PlayerMoveEvent event) {
            if (validateBody()) Lance.this.onPlayerMove(event);
        }

        @EventHandler
        private void onSendPre(SendMovementPacketsEvent.Pre event) {
            if (!validateBody() || correcting) return;
            posSentThisTick = false;
            walkHome();
        }

        @EventHandler(priority = EventPriority.LOWEST)
        private void onSend(PacketEvent.Send event) {
            if (validateBody()) Lance.this.onSend(event);
        }

        @EventHandler
        private void onSent(PacketEvent.Sent event) {
            if (validateBody()) Lance.this.onSent(event);
        }

        @EventHandler
        private void onSendPost(SendMovementPacketsEvent.Post event) {
            if (!validateBody() || correcting) return;
            boolean claimSent = posSentThisTick && (lastClaim == null
                || lastWire != null && lastWire.distanceToSqr(lastClaim) < 1e-6);
            if (!claimSent && (lastClaim != null || wireSyncWanted)) sendPos();
            if (lastClaim == null && !wireSyncWanted && ox == 0 && oy == 0 && oz == 0) stopHoming();
        }

        @EventHandler
        private void onReceive(PacketEvent.Receive event) {
            Lance.this.onReceive(event);
        }

        @EventHandler
        private void onCorrectionBefore(PlayerPositionLookEvent.Before event) {
            if (validateBody()) Lance.this.onCorrectionBefore(event);
        }

        @EventHandler
        private void onCorrectionAfter(PlayerPositionLookEvent.After event) {
            if (validateBody()) Lance.this.onCorrectionAfter(event);
        }

        @EventHandler(priority = EventPriority.HIGH)
        private void onGameLeft(GameLeftEvent event) {
            Lance.this.onGameLeft(event);
        }
    }

    private void startHoming() {
        if (homing) return;
        homing = true;
        MeteorClient.EVENT_BUS.subscribe(homer);
    }

    private void stopHoming() {
        if (!homing) return;
        homing = false;
        MeteorClient.EVENT_BUS.unsubscribe(homer);
    }
}
