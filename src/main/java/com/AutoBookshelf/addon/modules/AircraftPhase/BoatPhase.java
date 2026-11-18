/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.AircraftPhase;

import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.events.PlayerPositionLookEvent;
import com.AutoBookshelf.addon.modules.AttackAircraft.BoatShot;
import com.AutoBookshelf.addon.modules.AttackAircraft.BoatShotCycle;
import com.AutoBookshelf.addon.modules.AttackAircraft.BoatShotEvents;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public final class BoatPhase extends Module {
    /**
     * Ticks we keep hitting a boat after leaving it before we give up on it.
     */
    private static final int BREAK_TIMEOUT_TICKS = 100;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDive = settings.createGroup("Quick Dive");
    private final SettingGroup sgRecovery = settings.createGroup("Recovery");
    private final SettingGroup sgNavigation = settings.createGroup("Navigation");
    private final SettingGroup sgMeter = settings.createGroup("Speedometer");

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Print debug messages in chat.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> speedometer = sgMeter.add(new BoolSetting.Builder()
        .name("speedometer")
        .description("Toggle the on-screen speedometer that shows the boat's speed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> meterScale = sgMeter.add(new DoubleSetting.Builder()
        .name("meter-scale")
        .description("Scale of the speedometer text.")
        .defaultValue(1.4)
        .range(0.6, 3)
        .sliderRange(0.6, 3)
        .build()
    );

    private final Setting<Integer> meterX = sgMeter.add(new IntSetting.Builder()
        .name("meter-x")
        .description("Horizontal position of the speedometer as a percentage of the free width.")
        .defaultValue(2)
        .range(0, 100)
        .sliderRange(0, 100)
        .build()
    );

    private final Setting<Integer> meterY = sgMeter.add(new IntSetting.Builder()
        .name("meter-y")
        .description("Vertical position of the speedometer as a percentage of the free height.")
        .defaultValue(30)
        .range(0, 100)
        .sliderRange(0, 100)
        .build()
    );

    private final Setting<Keybind> meterKey = sgMeter.add(new KeybindSetting.Builder()
        .name("speedometer-key")
        .description("Keybind that toggles the speedometer.")
        .defaultValue(Keybind.none())
        .action(this::toggleSpeedometer)
        .build()
    );

    private final Setting<Boolean> phase = sgGeneral.add(new BoolSetting.Builder()
        .name("phase")
        .description("Let the boat travel through solid blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> horizontalSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("horizontal-speed")
        .description("Horizontal speed in blocks per tick.")
        .defaultValue(2.8)
        .min(0)
        .max(9)
        .sliderRange(0, 9)
        .build()
    );

    private final Setting<Double> horizontalLimit = sgGeneral.add(new DoubleSetting.Builder()
        .name("horizontal-limit")
        .description("Horizontal speed cap for the limited travel mode.")
        .defaultValue(0.99)
        .min(0.01)
        .max(9)
        .sliderRange(0.01, 3)
        .build()
    );

    private final Setting<Boolean> fastAir = sgGeneral.add(new BoolSetting.Builder()
        .name("fast-air-travel")
        .description("Send each game tick as a single movement packet, without substeps.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> hashTravel = sgGeneral.add(new BoolSetting.Builder()
        .name("correction-hash-travel")
        .description("Failed correction-hash travel experiment. Leave off.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> hashSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("hash-travel-speed")
        .description("Horizontal speed in blocks per tick for the hash travel experiment.")
        .defaultValue(3.2)
        .range(0.1, 3.8)
        .sliderRange(0.1, 3.8)
        .visible(hashTravel::get)
        .build()
    );

    private final Setting<Boolean> airSubsteps = sgGeneral.add(new BoolSetting.Builder()
        .name("air-substeps")
        .description("Split air travel into multiple small position updates per tick.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> substepSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("substep-speed")
        .description("Horizontal speed in blocks per tick when using substeps.")
        .defaultValue(3.2)
        .min(0.1)
        .max(BoatPhaseAirSteps.MAX_SPEED)
        .sliderRange(0.1, 3.8)
        .visible(airSubsteps::get)
        .build()
    );

    private final Setting<Boolean> extendedSubsteps = sgGeneral.add(new BoolSetting.Builder()
        .name("extended-substeps")
        .description("Use the experimental extended speed in clear air.")
        .defaultValue(true)
        .visible(airSubsteps::get)
        .build()
    );

    private final Setting<Double> experimentalSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("experimental-speed")
        .description("Horizontal speed in blocks per tick in clear air for the extended mode.")
        .defaultValue(6.5)
        .range(3.8, BoatPhaseAirSteps.TRIAL_SPEED)
        .sliderRange(3.8, BoatPhaseAirSteps.TRIAL_SPEED)
        .onChanged(value -> retryExtension())
        .visible(() -> airSubsteps.get() && extendedSubsteps.get())
        .build()
    );

    private final Setting<Double> verticalSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("vertical-speed")
        .description("Vertical speed in blocks per tick.")
        .defaultValue(3)
        .min(0.1)
        .max(BoatPhaseVertical.MAX_SPEED)
        .sliderRange(0.1, BoatPhaseVertical.MAX_SPEED)
        .build()
    );

    private final Setting<Double> phaseHorizontalSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("phase-horizontal-speed")
        .description("Horizontal speed in blocks per tick while traveling inside blocks.")
        .defaultValue(0.24)
        .min(0.01)
        .max(BoatPhaseMotion.MAX_PHASE_HORIZONTAL)
        .sliderRange(0.01, 0.249)
        .visible(phase::get)
        .build()
    );

    private final Setting<Boolean> adaptivePhase = sgGeneral.add(new BoolSetting.Builder()
        .name("adaptive-phase")
        .description("Travel at the requested speed with collision resolution instead of the fixed phase speed.")
        .defaultValue(true)
        .visible(phase::get)
        .build()
    );

    private final Setting<Double> phaseSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("phase-speed")
        .description("Requested horizontal speed in blocks per tick while adaptive phasing through blocks.")
        .defaultValue(3)
        .min(0.24)
        .max(9)
        .sliderRange(0.24, 3)
        .visible(() -> phase.get() && adaptivePhase.get())
        .build()
    );

    private final Setting<Double> airAcceleration = sgGeneral.add(new DoubleSetting.Builder()
        .name("air-acceleration")
        .description("Maximum horizontal velocity change per tick in clear air.")
        .defaultValue(0.1)
        .min(0.01)
        .max(1)
        .sliderRange(0.01, 0.3)
        .build()
    );

    private final Setting<Boolean> lockYaw = sgGeneral.add(new BoolSetting.Builder()
        .name("lock-boat-yaw")
        .description("Face the boat in the direction you are looking.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> stopLava = sgGeneral.add(new BoolSetting.Builder()
        .name("stop-before-lava")
        .description("Stop movement when lava is on the path ahead.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> antiKick = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-kick")
        .description("Add tiny downward motion periodically to avoid the fly-kick.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> netherRoofBreathing = sgGeneral.add(new BoolSetting.Builder()
        .name("nether-roof-breathing")
        .description("Experimental: carry the boat slightly above the nether roof so the player can breathe.")
        .defaultValue(false)
        .build()
    );

    // Automount: extracted from the in-tree BetterBoatFly module.
    private final Setting<Boolean> autoMount = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-mount")
        .description("Automatically mount the nearest boat you are not already riding.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> mountRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("mount-range")
        .description("Maximum distance at which the module will attempt to mount a boat.")
        .defaultValue(3.0)
        .min(1.0)
        .sliderMax(6.0)
        .visible(autoMount::get)
        .build()
    );

    private final Setting<Boolean> autoMountRotate = sgGeneral.add(new BoolSetting.Builder()
        .name("mount-rotate")
        .description("Turn to face the boat before mounting it.")
        .defaultValue(true)
        .visible(autoMount::get)
        .build()
    );

    private final Setting<Boolean> autoBreakBoatOnExit = sgGeneral.add(new BoolSetting.Builder()
        .name("break-on-exit")
        .description("Experimental: break the boat when you dismount.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Keybind> diveKey = sgDive.add(new KeybindSetting.Builder()
        .name("dive-key")
        .description("Keybind that starts or cancels a quick dive.")
        .defaultValue(Keybind.none())
        .action(this::toggleDive)
        .build()
    );

    private final Setting<Double> diveDistance = sgDive.add(new DoubleSetting.Builder()
        .name("dive-distance")
        .description("Distance the boat travels during a quick dive.")
        .defaultValue(30)
        .min(1)
        .max(BoatPhaseMotion.MAX_TRAVEL)
        .sliderRange(1, BoatPhaseMotion.MAX_TRAVEL)
        .build()
    );

    private final Setting<Boolean> diveWithPassenger = sgDive.add(new BoolSetting.Builder()
        .name("dive-with-passenger")
        .description("Start a quick dive once automatically when a passenger boards.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> correctionHold = sgRecovery.add(new IntSetting.Builder()
        .name("correction-hold")
        .description("Ticks to hold still after the server corrects the boat.")
        .defaultValue(8)
        .min(1)
        .max(100)
        .sliderRange(1, 40)
        .build()
    );

    private final Setting<Boolean> landedRetry = sgRecovery.add(new BoolSetting.Builder()
        .name("retry-after-landing")
        .description("Retry fast travel after landing and remounting a boat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> remountRetry = sgRecovery.add(new BoolSetting.Builder()
        .name("retry-after-remount")
        .description("Retry fast travel after any remount.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> landedRetrySpeed = sgRecovery.add(new DoubleSetting.Builder()
        .name("landed-retry-speed")
        .description("Substep speed used for fast travel after a landed retry.")
        .defaultValue(3.8)
        .range(0.1, BoatPhaseAirSteps.MAX_SPEED)
        .sliderRange(0.1, 3.8)
        .visible(landedRetry::get)
        .build()
    );

    private final Setting<Boolean> pauseOthers = sgRecovery.add(new BoolSetting.Builder()
        .name("pause-conflicting-modules")
        .description("Pause conflicting movement modules while driving the boat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> debugFile = sgRecovery.add(new BoolSetting.Builder()
        .name("debug-file")
        .description("Write diagnostics to the boat-phase folder in the game directory.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Keybind> cruiseKey = sgNavigation.add(new KeybindSetting.Builder()
        .name("cruise-key")
        .description("Keybind that toggles cruising along your current heading.")
        .defaultValue(Keybind.none())
        .action(this::toggleCruise)
        .build()
    );

    private final Setting<Keybind> surfaceKey = sgNavigation.add(new KeybindSetting.Builder()
        .name("surface-key")
        .description("Keybind that rises to the next clear pocket of air.")
        .defaultValue(Keybind.none())
        .action(this::surface)
        .build()
    );

    private final Setting<Keybind> returnHeightKey = sgNavigation.add(new KeybindSetting.Builder()
        .name("return-height-key")
        .description("Keybind that returns to the height you boarded at.")
        .defaultValue(Keybind.none())
        .action(this::returnHeight)
        .build()
    );


    private static final String[] CONFLICTS = {"entity-control", "boat-noclip", "BoatNoclip", "lance", "lance-original",
        "mace-smash", "float", "glider-walk", "elytra-fly", "elytra-fly+", "flight", "poltergeist", "belfry", "geyser", "treadmill"};
    private final BoatPhaseMotion motion = new BoatPhaseMotion();
    private final BoatShotEvents eventGate = new BoatShotEvents();
    private final BoatPhaseTravel travel = new BoatPhaseTravel();
    private final BoatPhaseMeter meter = new BoatPhaseMeter();
    private final BoatPhaseRemount remount = new BoatPhaseRemount();
    private BoatPhaseTravel.Mode plannedMode = BoatPhaseTravel.Mode.LIMITED;
    private Connection travelConnection;
    private ClientLevel travelWorld;
    private ClientLevel hashWorld;
    private Connection hashConnection;
    private Integer correctionHash;
    private int plannedHashTick = -1;
    private Object lastPlayerCorrection, lastVehicleCorrection, lastInputPacket, lastObservationPacket;
    private Object lastBoardInteraction;
    private int lastGroundedAge = -1000;
    private String boardingRetryReason = "no grounded boarding interaction";
    private String lastModeDescription = "";
    private final List<Module> paused = new ArrayList<>();
    private final ConcurrentLinkedQueue<Observation> observations = new ConcurrentLinkedQueue<>();
    private final AtomicInteger observationCount = new AtomicInteger();
    private volatile long epoch;
    private volatile Connection connection;
    private LocalPlayer player;
    private ClientLevel world;
    private ClientPacketListener network;
    private int mountCooldown = 0;
    private boolean wasInBoat = false;
    private BlockPos lastBoatPos = null;
    private AbstractBoat boat;
    /**
     * Boat we are currently destroying after leaving it, see {@link #startBreak(AbstractBoat)}.
     */
    private AbstractBoat breakingBoat = null;
    /**
     * Ticks since the break started, used for the give-up timeout.
     */
    private int breakingTicks = 0;
    /**
     * Hotbar slot we switched to for the break, -1 when we kept the main hand.
     */
    private int breakingToolSlot = -1;
    /**
     * Hotbar slot to hand back when the break ends.
     */
    private int breakingPrevSlot = -1;
    /**
     * The swing is only cosmetic, and it resets the server attack charge, so it happens once.
     */
    private boolean breakingSwung = false;
    private boolean driver;
    private boolean correcting;
    private boolean hadGuest;
    private Vec3 lastWire;
    private Vec3 plannedFrom;
    private int plannedTick = -1;
    private int sentCount;
    private int corrections;
    private double requestedDiveDistance;
    private String travelName = "Quick dive";
    private boolean plannedThroughBlocks;
    private boolean plannedClear;
    private boolean plannedShotMovement;
    private boolean verticalRejected;
    private int lastFastVerticalTick = -100;
    private boolean adaptiveBlocked;
    private int lastAdaptiveTick = -100;
    private int lastSneakTick = -100;
    private boolean sentSneak;
    private int airborneTicks;
    private boolean cruising;
    private float cruiseYaw;
    private double entryHeight;
    private Vec3 lastStep = Vec3.ZERO;
    private ServerboundMoveVehiclePacket substepPacket;
    private boolean splitting;
    private Vec3 substepOrigin;
    private String status = "board a boat";
    private PrintWriter log;

    private record Observation(long epoch, Connection connection, Packet<?> packet) {
    }

    public BoatPhase() {
        super(Addon.CATEGORY, "Boat-Phase", "Fly a boat through blocks. WASD for direction, Left Control for Downward");
    }

    public static BoatPhase active() {
        Modules modules = Modules.get();
        if (modules == null) return null;
        BoatPhase module = modules.get(BoatPhase.class);
        return module != null && module.isActive() ? module : null;
    }

    @Override
    public void onActivate() {
        detach();
        endBreak();
        correctionHash = null;
        hashWorld = null;
        hashConnection = null;
        travel.retry();
        verticalRejected = false;
        remount.reset();
        eventGate.reset();
        adaptiveBlocked = false;
        status = "board a boat";
    }

    @Override
    public void onDeactivate() {
        detach();
        endBreak();
        wasInBoat = false;
        lastBoatPos = null;
        mountCooldown = 0;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        detach();
        endBreak();
        travel.retry();
        travelConnection = null;
        verticalRejected = false;
        remount.reset();
        correctionHash = null;
        hashWorld = null;
        hashConnection = null;
    }

    private boolean sameSession() {
        return player == mc.player && world == mc.level && network == mc.getConnection();
    }

    private boolean owns(AbstractBoat candidate) {
        return boat == candidate && driver && sameSession() && mc.player != null && mc.player.isAlive()
            && !candidate.isRemoved() && mc.player.getRootVehicle() == candidate && candidate.getControllingPassenger() == mc.player;
    }

    public boolean phases(AbstractBoat candidate) {
        return owns(candidate) && phase.get() && plannedThroughBlocks && !correcting && motion.holdTicks() == 0 && mc.gui.screen() == null;
    }

    public Vec3 movement(AbstractBoat candidate, Vec3 vanilla) {
        if (!owns(candidate)) return vanilla;
        BoatShot shot = BoatShot.active();
        boolean inventoryFollow = shot != null && shot.inventoryFollowAllowed();
        plannedShotMovement = false;
        plannedMode = travelMode();
        if (lockYaw.get()) candidate.setYRot(mc.player.getYRot());
        if (correcting || motion.holdTicks() > 0 || mc.gui.screen() != null && !inventoryFollow) {
            candidate.setDeltaMovement(Vec3.ZERO);
            return Vec3.ZERO;
        }
        Vec3 from = candidate.position();
        Vec3 shotStep = shot == null ? null : shot.movement(candidate);
        if (shotStep != null) {
            plannedShotMovement = shot.singleUpdateMovement();
            cruising = false;
            motion.cancelTravel();
            plannedThroughBlocks = false;
            plannedFrom = from;
            plannedTick = motion.tick();
            status = "boat shot";
            plannedClear = clearTravel(candidate, shotStep);
            shotStep = hashStep(candidate, shotStep);
            candidate.setDeltaMovement(shotStep);
            return shotStep;
        }
        double forward = (Input.isPressed(mc.options.keyUp) ? 1 : 0) - (Input.isPressed(mc.options.keyDown) ? 1 : 0);
        double sideways = (Input.isPressed(mc.options.keyLeft) ? 1 : 0) - (Input.isPressed(mc.options.keyRight) ? 1 : 0);
        if (forward != 0 || sideways != 0) cruising = false;
        float yaw = mc.player.getYRot();
        if (cruising) {
            forward = 1;
            yaw = cruiseYaw;
        }
        boolean up = Input.isPressed(mc.options.keyJump), down = Input.isPressed(mc.options.keySprint);
        double floor = world.getMinY() + 2;
        double topOffset = candidate.getSelfAndPassengers().mapToDouble(e -> e.getBoundingBox().maxY - candidate.getY()).max().orElse(candidate.getBbHeight());
        double ceiling = world.getMaxY() - topOffset;
        boolean isNether = (mc.level != null && mc.level.dimension().identifier().toString().contains("nether"))
            || (world != null && world.getMaxY() <= 128);
        if (netherRoofBreathing.get() && isNether) {
            // The boat+passenger topOffset (~2.3 for a boat with a player) eats most of a small
            // margin, so use a larger offset and cap at the bedrock roof (Y=127) rather than the
            // void boundary, keeping the player's head just above the roof without fully exiting.
            ceiling = Math.min(127.0, ceiling + 4.0);
        }
        double airLimit = travelSpeed();
        double airSpeed = airLimit;
        double solidSpeed = Math.min(phaseSpeed.get(), horizontalLimit.get());
        boolean adaptive = phase.get() && adaptivePhase.get() && !adaptiveBlocked;
        BoatPhaseMotion.Point step = motion.plan(point(from), forward, sideways, up, down, yaw,
            adaptive ? Math.max(airSpeed, solidSpeed) : airSpeed, travelVerticalSpeed(), floor, ceiling, false);
        Vec3 delta = new Vec3(step.x(), step.y(), step.z());
        Vec3 followStep = shot == null ? null : shot.followMovement(candidate, airLimit, Math.min(9, travelVerticalSpeed()));
        if (inventoryFollow && followStep == null) {
            candidate.setDeltaMovement(Vec3.ZERO);
            return Vec3.ZERO;
        }
        if (followStep != null) {
            cruising = false;
            motion.cancelTravel();

            delta = followStep;
            if (delta.lengthSqr() < 1e-10 && antiKick.get() && !supported(candidate)
                && motion.tick() % 10 < 2 && clearTravel(candidate, new Vec3(0, -.08, 0))) delta = new Vec3(0, -.08, 0);
            plannedThroughBlocks = false;
            plannedFrom = from;
            plannedTick = motion.tick();
            if (!shot.safeTravel(candidate, delta)) delta = Vec3.ZERO;
            plannedClear = clearTravel(candidate, delta);
            delta = hashStep(candidate, delta);
            candidate.setDeltaMovement(delta);
            status = speedFallback() ? "following (speed fallback)" : "following";
            return delta;
        }
        boolean throughBlocks = phase.get() && !world.noCollision(candidate, candidate.getBoundingBox().expandTowards(delta).deflate(1e-7));
        boolean embedded = !world.noCollision(candidate, candidate.getBoundingBox().deflate(1e-7));
        if (!embedded && !supported(candidate) && !candidate.isInWater()) airborneTicks++;
        else airborneTicks = 0;
        if (throughBlocks) {
            if (adaptive) {
                step = motion.plan(point(from), forward, sideways, up, down, yaw, solidSpeed, travelVerticalSpeed(), floor, ceiling, false);
                step = BoatPhaseMotion.collisionStep(step, phaseHorizontalSpeed.get(), p -> {
                    Vec3 desired = new Vec3(p.x(), p.y(), p.z());
                    return point(Entity.collideBoundingBox(candidate, desired, candidate.getBoundingBox(), world,
                        world.getEntityCollisions(candidate, candidate.getBoundingBox().expandTowards(desired))));
                });
                if (Math.hypot(step.x(), step.z()) > phaseHorizontalSpeed.get() + 1e-6) lastAdaptiveTick = motion.tick();
            } else step = BoatPhaseMotion.phaseStep(step, Math.min(phaseHorizontalSpeed.get(), BoatPhaseMotion.PROVEN_PHASE_HORIZONTAL));
            delta = new Vec3(step.x(), step.y(), step.z());
        } else {
            step = motion.plan(point(from), forward, sideways, up, down, yaw, airSpeed, travelVerticalSpeed(), floor, ceiling, false);
            step = BoatPhaseMotion.airStep(step, BoatPhaseMotion.horizontalStep(point(lastStep), airLimit), airAcceleration.get(), airborneTicks,
                antiKick.get(), up || down || motion.diveRemaining() > 0 || motion.riseRemaining() > 0, from.y - floor);
            delta = new Vec3(step.x(), step.y(), step.z());
            if (phase.get() && !world.noCollision(candidate, candidate.getBoundingBox().expandTowards(delta).deflate(1e-7))) {
                throughBlocks = true;
                step = BoatPhaseMotion.horizontalStep(step, horizontalLimit.get());
                step = BoatPhaseMotion.phaseStep(step, Math.min(phaseHorizontalSpeed.get(), BoatPhaseMotion.PROVEN_PHASE_HORIZONTAL));
                delta = new Vec3(step.x(), step.y(), step.z());
            }
        }
        Vec3 waterStep = waterDeparture(candidate, delta.y);
        if (waterStep != null) {
            delta = waterStep;
            throughBlocks = false;
        }
        plannedThroughBlocks = throughBlocks;
        AABB path = candidate.getBoundingBox().expandTowards(delta);
        if (!loaded(path)) {
            status = "waiting for chunks";
            delta = Vec3.ZERO;
            motion.cancelTravel();
        } else if (stopLava.get() && containsLava(path)) {
            status = "lava ahead";
            delta = Vec3.ZERO;
            motion.cancelTravel();
        } else if (motion.diveRemaining() > 0 && from.y + delta.y <= floor + 1e-6) {
            status = "world floor";
            motion.cancelTravel();
        } else if (motion.riseRemaining() > 0 && from.y + delta.y >= ceiling - 1e-6) {
            status = "world ceiling";
            motion.cancelTravel();
        } else {
            double remaining = Math.max(motion.diveRemaining(), motion.riseRemaining());
            status = remaining > 0 ? String.format(Locale.ROOT, "%s %.1f", travelName, remaining)
                : throughBlocks ? adaptive ? "adaptive phase" : adaptiveBlocked && adaptivePhase.get() ? "phasing (fallback)" : "phasing"
                  : (cruising ? "cruising" : "piloting") + (speedFallback() ? " (speed fallback)" : "");
        }
        if (waterStep != null) status = delta.y > 0 ? "exiting water" : "water exit blocked";
        plannedFrom = from;
        plannedTick = motion.tick();
        if (shot != null && !shot.safeTravel(candidate, delta)) {
            cruising = false;
            motion.cancelTravel();
            plannedThroughBlocks = false;
            status = shot.travelBlockReason();
            delta = Vec3.ZERO;
        }
        plannedClear = !throughBlocks && clearTravel(candidate, delta);
        delta = hashStep(candidate, delta);
        candidate.setDeltaMovement(delta);
        return delta;
    }

    private boolean supported(AbstractBoat candidate) {
        return supported(candidate, .05);
    }

    private boolean supported(AbstractBoat candidate, double depth) {
        AABB b = candidate.getBoundingBox();
        return mc.level != null && !mc.level.noCollision(candidate, new AABB(b.minX + 1e-4, b.minY - depth, b.minZ + 1e-4,
            b.maxX - 1e-4, b.minY + 1e-7, b.maxZ - 1e-4));
    }

    public boolean shotReady(AbstractBoat candidate) {
        return isActive() && owns(candidate) && !correcting && motion.holdTicks() == 0 && mc.gui.screen() == null;
    }

    private boolean travelReady(AbstractBoat candidate) {
        BoatShot shot = BoatShot.active();
        return isActive() && owns(candidate) && !correcting && motion.holdTicks() == 0
            && (mc.gui.screen() == null || shot != null && shot.inventoryFollowAllowed());
    }

    public double shotVerticalLimit() {
        return Math.min(9, travelVerticalSpeed());
    }

    public double captureVerticalLimit() {
        return Math.min(45, travelVerticalSpeed());
    }

    public double captureCeiling(AbstractBoat candidate) {
        if (candidate == null || mc.level == null) return Double.NEGATIVE_INFINITY;
        double top = candidate.getSelfAndPassengers().mapToDouble(e -> e.getBoundingBox().maxY - candidate.getY()).max().orElse(candidate.getBbHeight());
        return mc.level.getMaxY() - top;
    }

    private double travelVerticalSpeed() {
        return verticalRejected ? Math.min(9, verticalSpeed.get()) : verticalSpeed.get();
    }

    public double travelSpeed() {
        return switch (travelMode()) {
            case HASH -> hashSpeed.get();
            case EXTENDED -> experimentalSpeed.get();
            case SUBSTEPS -> extendedSubsteps.get() && travel.rejected(BoatPhaseTravel.Mode.EXTENDED)
                ? BoatPhaseAirSteps.MAX_SPEED : substepSpeed.get();
            case DIRECT -> horizontalSpeed.get();
            case LIMITED -> Math.min(horizontalSpeed.get(), horizontalLimit.get());
        };
    }

    private BoatPhaseTravel.Mode travelMode() {
        return travel.select(hashTravel.get(), hashReady(), airSubsteps.get(), fastAir.get(), extendedSubsteps.get());
    }

    private boolean speedFallback() {
        return travelMode() == BoatPhaseTravel.Mode.LIMITED && (airSubsteps.get() || fastAir.get() || hashTravel.get())
            || travelMode() == BoatPhaseTravel.Mode.SUBSTEPS && extendedSubsteps.get() && travel.rejected(BoatPhaseTravel.Mode.EXTENDED);
    }

    private boolean hashReady() {
        return hashTravel.get() && !travel.rejected(BoatPhaseTravel.Mode.HASH) && correctionHash != null
            && world == hashWorld && connection == hashConnection;
    }

    private Vec3 hashStep(AbstractBoat candidate, Vec3 delta) {
        plannedHashTick = -1;
        if (Math.abs(delta.y) > BoatPhaseVertical.STEP) return delta;
        if (!hashReady() || delta.horizontalDistance() <= horizontalLimit.get()) return delta;

        candidate.setYRot(Mth.wrapDegrees(candidate.getYRot()));
        candidate.setXRot(Mth.wrapDegrees(candidate.getXRot()));
        Vec3 to = candidate.position().add(delta);
        double y = BoatPhaseHash.altitude(to.x, to.y, to.z, candidate.getYRot(), candidate.getXRot(), correctionHash);
        Vec3 adjusted = new Vec3(delta.x, y - candidate.getY(), delta.z);
        BoatShot shot = BoatShot.active();
        if (!Double.isFinite(y) || !clearTravel(candidate, adjusted) || shot != null && !shot.safeTravel(candidate, adjusted)) return Vec3.ZERO;
        plannedHashTick = motion.tick();
        return adjusted;
    }

    public boolean clearTravel(AbstractBoat candidate, Vec3 delta) {
        if (!travelReady(candidate) || !Double.isFinite(delta.lengthSqr()) || delta.length() > Math.hypot(BoatPhaseVertical.MAX_SPEED, BoatPhaseAirSteps.TRIAL_SPEED))
            return false;
        return clearRoute(candidate, delta);
    }

    public boolean clearRoute(AbstractBoat candidate, Vec3 delta) {
        if (!travelReady(candidate) || !Double.isFinite(delta.lengthSqr()) || delta.length() > 192) return false;
        for (Entity entity : candidate.getSelfAndPassengers().toList()) {
            AABB path = entity.getBoundingBox().expandTowards(delta).deflate(1e-7);
            if (path.minY < world.getMinY() + 2 || path.maxY > world.getMaxY() + 1 || !loaded(path)
                || !world.getWorldBorder().isWithinBounds(path) || !world.noCollision(entity, path) || containsFluid(path)) return false;
        }
        return true;
    }

    public boolean shotPathClear(AbstractBoat candidate, Vec3 delta) {
        if (!shotReady(candidate) || !Double.isFinite(delta.y) || delta.x != 0 || delta.z != 0
            || Math.abs(delta.y) > BoatShotCycle.MAX_BURST + 1e-7) return false;
        for (Entity entity : candidate.getSelfAndPassengers().toList()) {
            AABB path = entity.getBoundingBox().expandTowards(delta).deflate(1e-7);
            if (path.minY < world.getMinY() + 2 || path.maxY > world.getMaxY() + 1
                || !loaded(path) || !world.getWorldBorder().isWithinBounds(path)
                || !world.noCollision(entity, path) || containsFluid(path)) return false;
        }
        return true;
    }

    public void afterMove(AbstractBoat candidate) {
        if (!owns(candidate)) return;
        boolean ground = candidate.getDeltaMovement().y <= 0 && supported(candidate);
        candidate.setOnGround(ground);
        candidate.verticalCollisionBelow = ground;
        if (!plannedThroughBlocks && !ground) candidate.verticalCollision = false;
    }

    private boolean loaded(AABB box) {
        for (int x = ((int) Math.floor(box.minX)) >> 4; x <= ((int) Math.floor(box.maxX)) >> 4; x++) {
            for (int z = ((int) Math.floor(box.minZ)) >> 4; z <= ((int) Math.floor(box.maxZ)) >> 4; z++) {
                if (!world.getChunkSource().hasChunk(x, z)) return false;
            }
        }
        return true;
    }

    private boolean containsLava(AABB box) {
        for (BlockPos pos : BlockPos.betweenClosed((int) Math.floor(box.minX), (int) Math.floor(box.minY), (int) Math.floor(box.minZ),
            (int) Math.floor(box.maxX), (int) Math.floor(box.maxY), (int) Math.floor(box.maxZ))) {
            if (world.getFluidState(pos).is(FluidTags.LAVA)) return true;
        }
        return false;
    }

    private boolean containsFluid(AABB box) {
        for (BlockPos pos : BlockPos.betweenClosed((int) Math.floor(box.minX), (int) Math.floor(box.minY), (int) Math.floor(box.minZ),
            (int) Math.floor(box.maxX), (int) Math.floor(box.maxY), (int) Math.floor(box.maxZ))) {
            if (!world.getFluidState(pos).isEmpty()) return true;
        }
        return false;
    }

    private boolean containsWater(AABB box) {
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ)))
            if (world.getFluidState(pos).is(FluidTags.WATER)) return true;
        return false;
    }

    public Vec3 waterDeparture(AbstractBoat candidate, double ascent) {
        if (candidate == null || world == null || !Double.isFinite(ascent) || ascent <= 0 || !containsWater(candidate.getBoundingBox())) return null;
        Vec3 step = new Vec3(0, Math.min(.5, ascent), 0);
        return clearWaterExit(candidate, step) ? step : Vec3.ZERO;
    }

    private boolean clearWaterExit(AbstractBoat candidate, Vec3 step) {
        if (!travelReady(candidate) || !Double.isFinite(step.lengthSqr()) || step.y <= 0 || step.y > .5 || step.horizontalDistanceSqr() != 0)
            return false;
        for (Entity entity : candidate.getSelfAndPassengers().toList()) {
            AABB path = entity.getBoundingBox().expandTowards(step).deflate(1e-7);
            if (path.minY < world.getMinY() + 2 || path.maxY > world.getMaxY() + 1 || !loaded(path)
                || !world.getWorldBorder().isWithinBounds(path) || !world.noCollision(entity, path)) return false;
            for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(path.minX, path.minY, path.minZ), BlockPos.containing(path.maxX, path.maxY, path.maxZ))) {
                var fluid = world.getFluidState(pos);
                if (!fluid.isEmpty() && !fluid.is(FluidTags.WATER)) return false;
            }
        }
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onTick(TickEvent.Pre event) {
        if (mc.player != null && !eventGate.tick(mc.player, mc.player.tickCount)) return;
        Connection currentConnection = mc.getConnection() == null ? null : mc.getConnection().getConnection();
        if (travelConnection != currentConnection) {
            travel.retry();
            verticalRejected = false;
            remount.reset();
            travelConnection = currentConnection;
        }
        if (travelWorld != mc.level) {
            remount.reset();
            travelWorld = mc.level;
        }
        if (hashWorld != mc.level || mc.getConnection() == null || hashConnection != mc.getConnection().getConnection()) {
            correctionHash = null;
            hashWorld = null;
            hashConnection = null;
        }
        if (boat != null && sameSession()) {
            if (Input.isPressed(mc.options.keyShift)) lastSneakTick = motion.tick();
            if (supported(boat)) lastGroundedAge = mc.player.tickCount;
            drainObservations();
        }
        AbstractBoat current = mc.player != null && mc.player.getRootVehicle() instanceof AbstractBoat b ? b : null;
        boolean controlling = current != null && current.getControllingPassenger() == mc.player;
        if (!sameSession() || boat != current || controlling != driver || (mc.player != null && !mc.player.isAlive())) {
            // Auto-break boat on exit: remember the boat we were riding, it is the only reliable handle on it.
            if (wasInBoat && autoBreakBoatOnExit.get() && boat != null && sameSession()
                && mc.player != null && mc.player.getVehicle() != boat) {
                startBreak(boat);
            }
            wasInBoat = false;
            lastBoatPos = null;
            detach();
            if (current != null && mc.player.isAlive()) attach(current, controlling);
        } else {
            if (boat != null) {
                wasInBoat = true;
                lastBoatPos = boat.blockPosition().immutable();
            }
        }

        // Auto-break: keep attacking the boat until it is destroyed.
        runBreak();
        if (boat == null) {
            // Automount: if not riding and autoMount is on, find nearest boat and mount.
            // Skipped while we are destroying a boat, otherwise we would fight over the same entity.
            if (autoMount.get() && mc.player != null && !mc.player.isPassenger() && breakingBoat == null) {
                if (mountCooldown > 0) {
                    mountCooldown--;
                } else {
                    double mountRangeVal = mountRange.get();
                    double radius = 5.0;
                    net.minecraft.world.phys.AABB searchBox = mc.player.getBoundingBox().inflate(radius);
                    java.util.List<AbstractBoat> boats = mc.level.getEntitiesOfClass(
                        AbstractBoat.class, searchBox,
                        b -> !b.hasPassenger(mc.player) && b != breakingBoat);
                    AbstractBoat nearest = null;
                    double nearestDistSq = Double.MAX_VALUE;
                    net.minecraft.world.phys.Vec3 playerPos = mc.player.position();
                    for (AbstractBoat b : boats) {
                        double distSq = b.distanceToSqr(playerPos);
                        if (distSq < nearestDistSq && distSq <= mountRangeVal * mountRangeVal) {
                            nearest = b;
                            nearestDistSq = distSq;
                        }
                    }
                    if (nearest != null) {
                        // Sneaking blocks the mount on the server: the interact packet carries
                        // player.isShiftKeyDown() and AbstractBoat#interact refuses a sneaking player.
                        // Do not spend the cooldown on that, so the mount lands on the first tick
                        // after the sneak key comes up.
                        if (mc.player.isShiftKeyDown()) {
                            status = "release sneak to mount";
                            return;
                        }
                        if (autoMountRotate.get()) {
                            double deltaX = nearest.getX() - mc.player.getX();
                            double deltaZ = nearest.getZ() - mc.player.getZ();
                            double deltaY = nearest.getY() + nearest.getBbHeight() / 2.0 - (mc.player.getY() + mc.player.getEyePosition().y - mc.player.getY());
                            float yaw = (float) (Math.toDegrees(Math.atan2(deltaZ, deltaX)) - 90.0);
                            float pitch = (float) Math.toDegrees(-Math.atan2(deltaY, Math.sqrt(deltaX * deltaX + deltaZ * deltaZ)));
                            final AbstractBoat nearestFinal = nearest;
                            meteordevelopment.meteorclient.utils.player.Rotations.rotate(yaw, pitch, -100, () -> doMount(nearestFinal));
                        } else {
                            doMount(nearest);
                        }
                        mountCooldown = 10;
                    }
                }
            }
            status = "board a boat";
            return;
        }
        motion.nextTick();
        meter.tick(motion.tick(), System.nanoTime());
        drainObservations();
        String modeDescription = modeDescription();
        if (!modeDescription.equals(lastModeDescription)) {
            record("SPEED-MODE %s hashRequested=%b substeps=%b direct=%b", modeDescription, hashTravel.get(), airSubsteps.get(), fastAir.get());
            lastModeDescription = modeDescription;
        }
        if (driver) {
            if (pauseOthers.get()) pauseConflicts();
            boolean guest = boat.getPassengers().size() > 1;
            if (guest && !hadGuest && diveWithPassenger.get()) toggleDive();
            hadGuest = guest;
            if (motion.holdTicks() > 0) status = "correction hold " + motion.holdTicks();
        } else status = "passenger - observing";
        if (motion.tick() % 20 == 0) {
            record("STATE role=%s boat=%d local=%s wire=%s passengers=%s inside=%b health=%.1f tx=%d corrections=%d status=%s",
                driver ? "driver" : "passenger", boat.getId(), boat.position(), lastWire, boat.getPassengers().stream().map(Entity::getId).toList(),
                !world.noCollision(boat, boat.getBoundingBox()), mc.player.getHealth() + mc.player.getAbsorptionAmount(), sentCount, corrections, status);
            long now = System.nanoTime();
            record("SPEED sentBpt=%.3f sentBps=%.2f echoBpt=%.3f echoBps=%.2f packetsLastTick=%d mode=%s elapsedMs=%d",
                meter.sentBpt(), meter.sentBps(), meter.echoBpt(now), meter.echoBps(now), meter.packetsLastTick(), modeDescription,
                (now - rideStartedNanos) / 1_000_000L);
            if (log != null) log.flush();
        }
    }

    /**
     * Starts destroying the boat we just left. The boat is kept by reference: it is the only handle we
     * have on it, a fresh entity search misses rafts and chest boats and can miss a moving boat.
     */
    private void startBreak(AbstractBoat target) {
        if (mc.player == null || mc.gameMode == null) return;
        endBreak();

        breakingBoat = target;
        breakingTicks = 0;
        breakingSwung = false;
        breakingPrevSlot = mc.player.getInventory().getSelectedSlot();

        // A swap changes the main hand item, which resets the server side attack charge, so only swap
        // when the tool is actually better than what is already in hand.
        int tool = bestDamageSlot();
        if (tool >= 0 && tool != breakingPrevSlot) {
            selectSlot(tool);
            breakingToolSlot = tool;
        }
    }

    /**
     * Gives the hotbar slot back and forgets the boat we were destroying.
     */
    private void endBreak() {
        if (breakingToolSlot >= 0 && breakingPrevSlot >= 0 && breakingPrevSlot != breakingToolSlot) {
            selectSlot(breakingPrevSlot);
        }
        breakingBoat = null;
        breakingToolSlot = -1;
        breakingPrevSlot = -1;
        breakingTicks = 0;
        breakingSwung = false;
    }

    /**
     * Attacks the boat until it is destroyed. The swing happens once: the server resets its attack
     * charge on every swing, so swinging per hit would pin every hit to the 0.2 damage floor.
     */
    private void runBreak() {
        if (breakingBoat == null) return;
        if (mc.player == null || mc.gameMode == null || !mc.player.isAlive()
            || breakingBoat.isRemoved() || !breakingBoat.isAlive()) {
            endBreak();
            return;
        }

        breakingTicks++;
        // The server drops attacks whose eye to hitbox distance is above entityInteractionRange + 3.
        if (breakingBoat.getBoundingBox().distanceToSqr(mc.player.getEyePosition()) <= 36.0) {
            if (!breakingSwung) {
                mc.player.swing(InteractionHand.MAIN_HAND);
                breakingSwung = true;
            }
            mc.gameMode.attack(mc.player, breakingBoat);
        }
        if (breakingTicks > BREAK_TIMEOUT_TICKS) endBreak();
    }

    /**
     * The hotbar slot with the highest attack damage, or -1 when nothing beats the current main hand.
     * Tridents and spears are skipped: the server rejects the attack outright for both.
     */
    private int bestDamageSlot() {
        if (mc.player == null) return -1;

        double mainHand = effectiveAttackDamage(mc.player.getMainHandItem());
        int best = -1;
        double bestDamage = mainHand;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty() || !isDamageTool(stack)) continue;
            double damage = effectiveAttackDamage(stack);
            if (damage > bestDamage) {
                bestDamage = damage;
                best = i;
            }
        }
        return best;
    }

    private boolean isDamageTool(ItemStack stack) {
        if (stack.has(DataComponents.PIERCING_WEAPON)) return false;
        if (stack.getOrDefault(DataComponents.MINIMUM_ATTACK_CHARGE, 0.0f) > 0.0f) return false;
        return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || stack.is(ItemTags.PICKAXES)
            || stack.is(Items.MACE);
    }

    /**
     * The attack damage the player would land with this stack in the main hand.
     */
    private double effectiveAttackDamage(ItemStack stack) {
        if (mc.player == null) return 0.0;
        double playerBase = mc.player.getAttributeValue(Attributes.ATTACK_DAMAGE)
            - heldAttackDamage(mc.player.getMainHandItem());
        return playerBase + heldAttackDamage(stack);
    }

    /**
     * The attack damage a stack contributes through its attribute modifiers.
     */
    private double heldAttackDamage(ItemStack stack) {
        ItemAttributeModifiers modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (modifiers == null) return 0.0;
        return modifiers.compute(Attributes.ATTACK_DAMAGE, 0.0, EquipmentSlot.MAINHAND);
    }

    /**
     * Selects a hotbar slot and tells the server about it. The setter alone does not sync the server
     * carried slot, so the packet has to go out as well.
     */
    private void selectSlot(int slot) {
        if (slot < 0 || slot > 8) return;
        if (mc.player == null || mc.getConnection() == null) return;
        mc.player.getInventory().setSelectedSlot(slot);
        mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
    }

    private void doMount(AbstractBoat boat) {
        if (mc.player == null || mc.gameMode == null) return;
        // A deferred mount can land after the break started: the mount is queued into the
        // Meteor rotation queue and runs during the movement packet phase, not in our tick.
        // Never mount the boat we are destroying, and never swing while a break is running,
        // because the swing resets the server side attack charge the break depends on.
        if (breakingBoat != null || breakingBoat == boat) return;
        mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        net.minecraft.world.phys.EntityHitResult location = new net.minecraft.world.phys.EntityHitResult(boat, boat.position());
        mc.gameMode.interact(mc.player, boat, location, net.minecraft.world.InteractionHand.MAIN_HAND);
    }

    private void attach(AbstractBoat current, boolean controlling) {
        boat = current;
        player = mc.player;
        world = mc.level;
        network = mc.getConnection();
        connection = network.getConnection();
        driver = controlling;
        lastWire = boat.position();
        motion.reset();
        adaptiveBlocked = false;
        lastAdaptiveTick = lastSneakTick = -100;
        travel.newRide();
        lastFastVerticalTick = -100;
        meter.reset();
        lastModeDescription = "";
        rideStartedNanos = System.nanoTime();
        sentSneak = false;
        plannedHashTick = -1;
        plannedClear = false;
        sentCount = corrections = 0;
        entryHeight = boat.getY();
        hadGuest = false;
        openLog();
        boolean retryToken = remount.board(boat.getId(), player.tickCount, driver, remountRetry.get() || landedRetry.get());
        record("RETRY-CHECK token=%b driver=%b substepRejected=%b reason=%s", retryToken, driver,
            travel.rejected(BoatPhaseTravel.Mode.SUBSTEPS), boardingRetryReason);
        if (retryToken && remountRetry.get() && (travel.flightRejected() || verticalRejected)) {
            retryConfiguredFlight();
            record("REMOUNT-RETRY horizontal=%.2f vertical=%.2f", travelSpeed(), travelVerticalSpeed());
            if (debug.get()) info("Reboarded: retrying %.2f horizontal / %.2f vertical b/t.", travelSpeed(), travelVerticalSpeed());
        } else if (retryToken && !remountRetry.get() && airSubsteps.get() && travel.rejected(BoatPhaseTravel.Mode.SUBSTEPS)) {
            travel.retry(BoatPhaseTravel.Mode.SUBSTEPS);

            travel.reject(BoatPhaseTravel.Mode.EXTENDED);
            substepSpeed.set(landedRetrySpeed.get());
            record("LANDED-RETRY speed=%.2f", substepSpeed.get());
            if (debug.get()) info("Landed remount: retrying air substeps at %.2f b/t.", substepSpeed.get());
        }
        record("MOUNT boat=%d type=%s role=%s position=%s passengers=%s", boat.getId(), boat.getType(), driver ? "driver" : "passenger",
            boat.position(), boat.getPassengers().stream().map(Entity::getId).toList());
        if (driver && pauseOthers.get()) pauseConflicts();
        if (debug.get())
            info(driver ? "Boat ready. WASD steers, jump rises, sprint descends; sneak dismounts." : "Passenger seat: observing the driver's boat; no movement controls are sent.");
    }

    private void detach() {
        if (boat != null && driver) {
            boolean deliberate = sameSession() && player.isAlive() && player.getRootVehicle() != boat && recentSneak();
            if (sameSession()) remount.intent(boat.getId(), player.tickCount,
                remountRetry.get() || supported(boat) || player.tickCount - lastGroundedAge <= 5, deliberate,
                retryableFallback(), remountRetry.get() || landedRetry.get());
        }
        epoch++;
        connection = null;
        record("END tx=%d corrections=%d recentSneak=%b inputSneak=%b stillMounted=%b adaptiveFallback=%b", sentCount, corrections,
            recentSneak(), sentSneak, player != null && boat != null && player.getRootVehicle() == boat, adaptiveBlocked);
        if (log != null) {
            log.close();
            log = null;
        }
        observations.clear();
        observationCount.set(0);
        lastPlayerCorrection = lastVehicleCorrection = lastInputPacket = lastObservationPacket = null;
        plannedMode = BoatPhaseTravel.Mode.LIMITED;
        plannedShotMovement = false;
        lastFastVerticalTick = -100;
        plannedHashTick = -1;
        meter.reset();
        if (boat != null && driver && !boat.isRemoved()) boat.setDeltaMovement(Vec3.ZERO);
        for (Module module : paused) if (!module.isActive()) module.toggle();
        paused.clear();
        boat = null;
        player = null;
        world = null;
        network = null;
        driver = correcting = hadGuest = false;
        cruising = plannedThroughBlocks = false;
        airborneTicks = 0;
        lastAdaptiveTick = -100;
        lastGroundedAge = -1000;
        lastStep = Vec3.ZERO;
        lastWire = plannedFrom = null;
        plannedTick = -1;
        motion.reset();
    }

    private void pauseConflicts() {
        for (String name : CONFLICTS) {
            Module module = Modules.get().get(name);
            if (module != null && module != this && module.isActive()) {
                module.toggle();
                if (!paused.contains(module)) paused.add(module);
                record("PAUSE module=%s", module.name);
            }
        }
    }

    private void toggleDive() {
        if (debug.get() && (!isActive() || boat == null || !owns(boat))) {
            info("Board the driver's seat and enable Boat Phase first.");
            return;
        }
        if (motion.diveRemaining() > 0 || motion.riseRemaining() > 0) {
            motion.cancelTravel();
            if (debug.get()) info("Vertical travel canceled.");
        } else if (motion.holdTicks() == 0) {
            cruising = false;
            travelName = "Quick dive";
            requestedDiveDistance = diveDistance.get();
            motion.dive(requestedDiveDistance);
            record("DIVE distance=%.2f start=%s", requestedDiveDistance, boat.position());
            if (debug.get()) info("Diving %.0f blocks. Jump cancels.", requestedDiveDistance);
        }
    }

    private void toggleCruise() {
        if (debug.get() && (!isActive() || boat == null || !owns(boat))) {
            info("Board the driver's seat first.");
            return;
        }
        cruising = !cruising;
        cruiseYaw = mc.player.getYRot();
        if (debug.get()) info(cruising ? "Cruise started. WASD cancels; jump and sprint control height." : "Cruise canceled.");
        record("CRUISE active=%b yaw=%.2f", cruising, cruiseYaw);
    }

    private void travelToHeight(double height, String name) {
        if (motion.holdTicks() > 0) return;
        double delta = height - boat.getY();
        if (debug.get() && (Math.abs(delta) < 0.01)) {
            info("Already at that height.");
            return;
        }
        if (Math.abs(delta) > BoatPhaseMotion.MAX_TRAVEL) {
            warning("That height is more than %.0f blocks away.", BoatPhaseMotion.MAX_TRAVEL);
            return;
        }
        cruising = false;
        requestedDiveDistance = Math.abs(delta);
        travelName = name;
        if (delta > 0) motion.rise(delta);
        else motion.dive(-delta);
        record("TRAVEL name=%s fromY=%.3f targetY=%.3f", name, boat.getY(), height);
        if (debug.get()) info("%s: %.1f blocks %s. Jump or sprint cancels a rise.", name, Math.abs(delta), delta > 0 ? "up" : "down");
    }

    private void returnHeight() {
        if (debug.get() && (!isActive() || boat == null || !owns(boat))) {
            info("Board the driver's seat first.");
            return;
        }
        travelToHeight(entryHeight, "Entry height");
    }

    private void surface() {
        if (debug.get() && (!isActive() || boat == null || !owns(boat))) {
            info("Board the driver's seat first.");
            return;
        }
        double max = Math.min(128, world.getMaxY() - boat.getBoundingBox().maxY);
        for (int dy = 1; dy <= max; dy++) {
            AABB target = boat.getBoundingBox().move(0, dy, 0);
            if (!loaded(target)) break;
            if (!world.noCollision(boat, target) || containsFluid(target)) continue;
            boolean clear = true;
            for (Entity passenger : boat.getPassengers()) {
                AABB passengerBox = passenger.getBoundingBox().move(0, dy, 0);
                if (passengerBox.maxY > world.getMaxY() + 1 || !world.noCollision(passenger, passengerBox) || containsFluid(passengerBox)) {
                    clear = false;
                    break;
                }
            }
            if (clear) {
                travelToHeight(boat.getY() + dy, "Clear pocket");
                return;
            }
        }
        if (debug.get()) info("No clear pocket with passenger headroom found within 128 blocks above.");
    }

    private void recover() {
        cruising = false;
        meter.reset();
        lastStep = Vec3.ZERO;
        airborneTicks = 0;
        if (!adaptiveBlocked && !recentSneak() && motion.tick() - lastAdaptiveTick <= 10) {
            adaptiveBlocked = true;
            record("ADAPTIVE-FALLBACK lastAdaptiveTick=%d", lastAdaptiveTick);
            warning("Adaptive phase corrected; slow step for this ride. Board again or use Resume adaptive phase to retry.");
        }
        motion.corrected(correctionHold.get());
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WTable table = theme.table();
        WButton dive = table.add(theme.button("Quick dive / cancel")).expandX().widget();
        dive.action = this::toggleDive;
        table.row();
        table.add(theme.button("Cruise / cancel")).expandX().widget().action = this::toggleCruise;
        table.row();
        table.add(theme.button("Rise to clear pocket")).expandX().widget().action = this::surface;
        table.row();
        table.add(theme.button("Return to entry height")).expandX().widget().action = this::returnHeight;
        table.row();
        table.add(theme.button("Resume adaptive phase")).expandX().widget().action = () -> {
            adaptiveBlocked = false;
            lastAdaptiveTick = -100;
            record("ADAPTIVE-RESUME horizontalLimit=%.3f", horizontalLimit.get());
            if (debug.get()) info("Adaptive fallback cleared. The adaptive-phase setting still controls whether it is enabled.");
        };
        table.row();
        table.add(theme.button("Retry fast air")).expandX().widget().action = () -> {
            travel.retry();
            verticalRejected = false;
            lastStep = Vec3.ZERO;
            record("SPEED-RETRY mode=%s", modeDescription());
            if (debug.get()) info("Speed fallback cleared. %s", modeDescription());
        };
        return table;
    }

    @EventHandler(priority = EventPriority.LOWEST - 100)
    private void onSend(PacketEvent.Send event) {
        if (!event.isCancelled() && mc.isSameThread() && mc.player != null && mc.level != null
            && mc.getConnection() != null && event.connection == mc.getConnection().getConnection()
            && !mc.player.isPassenger() && event.packet instanceof ServerboundInteractPacket interaction
            && lastBoardInteraction != interaction) {
            lastBoardInteraction = interaction;
            if (mc.level.getEntity(interaction.entityId()) instanceof AbstractBoat candidate) {

                boolean grounded = supported(candidate, .35);
                boolean armed = remount.intent(candidate.getId(), mc.player.tickCount, grounded || remountRetry.get(), true,
                    retryableFallback(), remountRetry.get() || landedRetry.get());
                boardingRetryReason = armed ? "deliberate boarding" : !landedRetry.get() && !remountRetry.get() ? "retry disabled"
                                                                      : !retryableFallback() ? "no flight rejection; terrain limits are separate"
                                                                        : "boat not landed";
            }
        }
        if (event.isCancelled() || !(event.packet instanceof ServerboundMoveVehiclePacket packet) || boat == null || !owns(boat) || event.connection != connection || correcting)
            return;
        if (packet == substepPacket) return;
        if (splitting) {
            event.cancel();
            return;
        }
        if (motion.alreadySent()) {
            record("SUPPRESS extra vehicle movement");
            event.cancel();
            return;
        }
        if (plannedHashTick == motion.tick() && hashReady()) {
            Vec3 p = packet.position();
            if (BoatPhaseHash.hash(p.x, p.y, p.z, Mth.wrapDegrees(packet.yRot()), Mth.wrapDegrees(packet.xRot())) != correctionHash) {
                event.cancel();
                if (lastWire != null) boat.setPos(lastWire);
                boat.setDeltaMovement(Vec3.ZERO);
                travel.reject(BoatPhaseTravel.Mode.HASH);
                record("HASH-STOP outgoing coordinates or rotation changed");
            }
            return;
        }
        if (lastWire != null) {
            Vec3 delta = packet.position().subtract(lastWire);
            boolean horizontalBatch = (plannedMode == BoatPhaseTravel.Mode.SUBSTEPS || plannedMode == BoatPhaseTravel.Mode.EXTENDED)
                && !plannedThroughBlocks;
            int horizontalCount = horizontalBatch ? BoatPhaseAirSteps.count(delta.horizontalDistance(), plannedMode == BoatPhaseTravel.Mode.EXTENDED
                                                                                                        ? BoatPhaseAirSteps.TRIAL_PACKETS : BoatPhaseAirSteps.MAX_PACKETS) : 1;
            int verticalCount = plannedShotMovement ? 1 : BoatPhaseVertical.count(delta.y);
            int count = Math.max(horizontalCount, verticalCount);

            if (horizontalCount == 0 || verticalCount == 0 || count > 1 && (plannedTick != motion.tick()
                || plannedFrom == null || plannedFrom.distanceTo(lastWire) > 1e-4 || !plannedClear && !plannedThroughBlocks)) {
                event.cancel();
                boat.setPos(lastWire);
                boat.setDeltaMovement(Vec3.ZERO);
                record("STEPS-STOP unverified sweep or packet budget; oversized update suppressed");
                return;
            }
            if (count > 1) {
                event.cancel();
                splitting = true;
                substepOrigin = lastWire;
                try {
                    for (int i = 1; i <= count; i++) {
                        Vec3 end = substepOrigin.add(delta.scale((double) i / count));
                        substepPacket = new ServerboundMoveVehiclePacket(end, packet.yRot(), packet.xRot(), packet.onGround());
                        network.send(substepPacket);
                        if (lastWire.distanceTo(end) > 1e-5) break;
                    }
                } finally {
                    substepPacket = null;
                    splitting = false;
                }
                boolean finished = motion.sent(point(substepOrigin), point(lastWire));
                lastStep = lastWire.subtract(substepOrigin);
                if (Math.abs(lastStep.y) > BoatPhaseVertical.STEP + 1e-6) {
                    lastFastVerticalTick = motion.tick();
                    travel.newRide();
                } else if (horizontalBatch) {
                    if (lastStep.horizontalDistance() > horizontalLimit.get() + 1e-6) lastFastVerticalTick = -100;
                    travel.sent(lastStep.horizontalDistance() > BoatPhaseAirSteps.MAX_SPEED + 1e-6
                            ? BoatPhaseTravel.Mode.EXTENDED : BoatPhaseTravel.Mode.SUBSTEPS,
                        motion.tick(), lastStep.horizontalDistance(), horizontalLimit.get());
                }
                if (lastWire.distanceTo(packet.position()) > 1e-5) {
                    boat.setPos(lastWire);
                    boat.setDeltaMovement(Vec3.ZERO);
                    record("SUBSTEP-INTERRUPTED position=%s", lastWire);
                }
                record("AIR-STEPS count=%d total=%s horizontal=%.3f", count, lastStep, lastStep.horizontalDistance());
                if (debug.get() && (finished)) info("%s movement sent: %.1f blocks.", travelName, requestedDiveDistance);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST - 100)
    private void onSent(PacketEvent.Sent event) {
        if (event.packet instanceof ServerboundPlayerInputPacket input && boat != null && sameSession() && event.connection == connection) {
            if (lastInputPacket == input) return;
            lastInputPacket = input;
            sentSneak = input.input().shift();
            if (sentSneak) lastSneakTick = motion.tick();
            record("INPUT %s", input.input());
        }
        if (!(event.packet instanceof ServerboundMoveVehiclePacket packet) || boat == null || !owns(boat) || event.connection != connection) return;

        if (!eventGate.vehicle(packet)) return;
        if (packet == substepPacket && splitting) {
            meter.sent(motion.tick(), System.nanoTime(), lastWire.x, lastWire.z, packet.position().x, packet.position().y, packet.position().z);
            record("SUBSTEP-WIRE from=%s to=%s delta=%s", lastWire, packet.position(), packet.position().subtract(lastWire));
            lastWire = packet.position();
            sentCount++;
            return;
        }
        if (motion.alreadySent()) return;
        if (correcting) {
            motion.acknowledged();
            record("CORRECTION-ACK position=%s", packet.position());
            return;
        }
        Vec3 from = lastWire == null ? packet.position() : lastWire;
        boolean finished = motion.sent(point(from), point(packet.position()));
        lastWire = packet.position();
        lastStep = lastWire.subtract(from);
        meter.sent(motion.tick(), System.nanoTime(), from.x, from.z, lastWire.x, lastWire.y, lastWire.z);
        BoatPhaseTravel.Mode sentMode = plannedHashTick == motion.tick() ? BoatPhaseTravel.Mode.HASH
            : plannedTick == motion.tick() && !plannedThroughBlocks ? plannedMode : BoatPhaseTravel.Mode.LIMITED;
        if (plannedShotMovement) {
            travel.newRide();
            lastFastVerticalTick = -100;
        } else {
            travel.sent(sentMode, motion.tick(), lastStep.horizontalDistance(), horizontalLimit.get());
            if (sentMode != BoatPhaseTravel.Mode.LIMITED && lastStep.horizontalDistance() > horizontalLimit.get() + 1e-6) lastFastVerticalTick = -100;
        }
        if (plannedHashTick == motion.tick() && lastStep.horizontalDistance() > horizontalLimit.get()) {
            record("HASH-WIRE horizontal=%.3f hash=%d", lastStep.horizontalDistance(), correctionHash);
        }
        sentCount++;
        record("WIRE from=%s to=%s delta=%s local=%s plannedFrom=%s plannedTick=%d inside=%b passengers=%d ground=%b airTicks=%d horizontalLimit=%.3f fallback=%b status=%s",
            from, lastWire, lastWire.subtract(from), boat.position(), plannedFrom, plannedTick,
            !world.noCollision(boat, boat.getBoundingBox()), boat.getPassengers().size(), packet.onGround(), airborneTicks, horizontalLimit.get(), adaptiveBlocked, status);
        if (debug.get() && (finished)) info("%s movement sent: %.1f blocks.", travelName, requestedDiveDistance);
    }

    public void beforeVehicleCorrection(ClientPacketListener handler, ClientboundMoveVehiclePacket packet) {
        if (handler != network || boat == null || !owns(boat)) return;
        if (lastVehicleCorrection == packet) return;
        lastVehicleCorrection = packet;
        rejectFastAir();
        correcting = true;
        corrections++;
        record("VEHICLE-CORRECTION server=%s local=%s lastWire=%s error=%.3f", packet.position(), boat.position(), lastWire,
            packet.position().distanceTo(boat.position()));
        recover();
    }

    public void afterVehicleCorrection(ClientPacketListener handler, ClientboundMoveVehiclePacket packet) {
        if (handler != network || !correcting) return;
        lastWire = packet.position();
        if (boat != null) boat.setDeltaMovement(Vec3.ZERO);
        correcting = false;
        status = "server correction";
        if (corrections <= 3 || corrections % 10 == 0)
            warning("Server corrected the boat (#%d). Dive canceled; pausing %d ticks.", corrections, correctionHold.get());
    }

    @EventHandler
    private void onPlayerCorrectionBefore(PlayerPositionLookEvent.Before event) {
        if (lastPlayerCorrection == event.packet) return;
        lastPlayerCorrection = event.packet;
        if (mc.player != null && mc.level != null && mc.getConnection() != null) {
            PositionMoveRotation p = PositionMoveRotation.calculateAbsolute(PositionMoveRotation.of(mc.player), event.packet.change(), event.packet.relatives());
            correctionHash = BoatPhaseHash.hash(p.position().x, p.position().y, p.position().z, p.yRot(), p.xRot());
            hashWorld = mc.level;
            hashConnection = mc.getConnection().getConnection();
            record("HASH-REFERENCE hash=%d recentSneak=%b", correctionHash, recentSneak());
        }
        if (boat == null || !driver || !sameSession()) return;
        if (!recentSneak()) rejectFastAir();
        correcting = true;
        corrections++;
        recover();
        record("PLAYER-CORRECTION packet=%s recentSneak=%b inputSneak=%b", event.packet, recentSneak(), sentSneak);
    }

    @EventHandler
    private void onPlayerCorrectionAfter(PlayerPositionLookEvent.After event) {
        if (!correcting) return;
        correcting = false;
        if (boat != null) {
            lastWire = boat.position();
            boat.setDeltaMovement(Vec3.ZERO);
        }
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        long observedEpoch = epoch;
        Connection owner = connection;
        if (owner == null || event.connection != owner) return;
        if (!(event.packet instanceof ClientboundSetPassengersPacket || event.packet instanceof ClientboundEntityPositionSyncPacket
            || event.packet instanceof ClientboundTeleportEntityPacket || event.packet instanceof ClientboundMoveEntityPacket)) return;
        if (observationCount.incrementAndGet() > 256) {
            observationCount.decrementAndGet();
            return;
        }
        observations.add(new Observation(observedEpoch, owner, event.packet));
    }

    private void drainObservations() {
        Observation entry;
        while ((entry = observations.poll()) != null) {
            observationCount.updateAndGet(n -> Math.max(0, n - 1));
            if (entry.epoch != epoch || entry.connection != connection || boat == null || !sameSession()) continue;
            Packet<?> packet = entry.packet;
            if (lastObservationPacket == packet) continue;
            lastObservationPacket = packet;
            if (packet instanceof ClientboundSetPassengersPacket p && p.getVehicle() == boat.getId()) {
                record("SERVER-PASSENGERS ids=%s", java.util.Arrays.toString(p.getPassengers()));
            } else if (packet instanceof ClientboundEntityPositionSyncPacket p && p.id() == boat.getId()) {
                record("SERVER-BOAT-SYNC values=%s", p.values());
                Vec3 pos = p.values().position();
                meter.echo(motion.tick(), System.nanoTime(), pos.x, pos.y, pos.z);
            } else if (packet instanceof ClientboundTeleportEntityPacket p && p.id() == boat.getId()) {
                record("SERVER-BOAT-POSITION values=%s relatives=%s", p.change(), p.relatives());
            } else if (packet instanceof ClientboundMoveEntityPacket p && p.getEntity(world) == boat && p.hasPosition()) {
                record("SERVER-BOAT-DELTA x=%d y=%d z=%d local=%s", p.getXa(), p.getYa(), p.getZa(), boat.position());
            }
        }
    }

    private void openLog() {
        if (!debugFile.get()) return;
        try {
            Path folder = mc.gameDirectory.toPath().resolve("boat-phase");
            Files.createDirectories(folder);
            String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
            Path path = folder.resolve("boat-" + time + ".log");
            log = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8));
            record("BOAT-PHASE v18 server=%s role=%s", mc.getCurrentServer() == null ? "local" : mc.getCurrentServer().ip, driver ? "driver" : "passenger");
            for (SettingGroup group : settings) for (Setting<?> setting : group) record("cfg %s = %s", setting.name, setting.get());
            if (debug.get()) info("Boat log: %s", path);
        } catch (IOException e) {
            Addon.LOG.warn("Cannot open Boat Phase log", e);
        }
    }

    private void record(String format, Object... args) {
        if (log != null) log.printf(Locale.ROOT, "[%d] %s%n", motion.tick(), String.format(Locale.ROOT, format, args));
    }

    private static BoatPhaseMotion.Point point(Vec3 v) {
        return new BoatPhaseMotion.Point(v.x, v.y, v.z);
    }

    private boolean recentSneak() {
        return motion.tick() - lastSneakTick <= 10 || (sameSession() && mc.player != null && Input.isPressed(mc.options.keyShift));
    }

    private void rejectFastAir() {
        if (motion.tick() - lastFastVerticalTick >= 0 && motion.tick() - lastFastVerticalTick <= 20) {
            verticalRejected = true;
            lastFastVerticalTick = -100;
            travel.newRide();
            record("VERTICAL-REJECTED next=%.2f", travelVerticalSpeed());
            warning("Fast vertical movement corrected; using at most 9 vertical b/t until reboarding or Retry fast air.");
            return;
        }
        BoatPhaseTravel.Mode rejected = travel.corrected(motion.tick());
        if (rejected == BoatPhaseTravel.Mode.LIMITED) return;
        record("SPEED-REJECTED mode=%s next=%s", rejected, modeDescription());
        if (rejected == BoatPhaseTravel.Mode.HASH)
            warning("Hash experiment rejected. Air substeps remain available; movement resumes after recovery / boarding.");
        else if (rejected == BoatPhaseTravel.Mode.EXTENDED)
            warning("Experimental speed corrected; returning to 3.8 b/t. Reboard, change experimental-speed or use Retry fast air to retry.");
        else warning("%s corrected: using the limited speed. Retry fast air or re-enable Boat Phase to retry.", rejected);
    }

    private long rideStartedNanos;

    private boolean retryableFallback() {
        return remountRetry.get() ? travel.flightRejected() || verticalRejected : travel.rejected(BoatPhaseTravel.Mode.SUBSTEPS);
    }

    private void retryConfiguredFlight() {
        travel.retryFlight();
        verticalRejected = false;
        lastFastVerticalTick = -100;
        lastStep = Vec3.ZERO;
        motion.corrected(4);
    }

    private void retryExtension() {
        if (travel != null) travel.retry(BoatPhaseTravel.Mode.EXTENDED);
    }

    public void toggleSpeedometer() {
        speedometer.set(!speedometer.get());
        if (debug.get()) info("Boat speedometer %s.", speedometer.get() ? "on" : "off");
    }

    private String modeDescription() {
        String mode = switch (travelMode()) {
            case EXTENDED -> "Adjustable substeps";
            case SUBSTEPS -> "Air substeps";
            case DIRECT -> "Single update";
            case HASH -> "Hash experiment";
            case LIMITED -> hashTravel.get() && !travel.rejected(BoatPhaseTravel.Mode.HASH) && !hashReady()
                ? "Waiting for hash reference" : speedFallback() ? "Speed fallback - Retry fast air" : "Limited";
        };
        return String.format(Locale.ROOT, "%s | cap %.2f b/t", mode, travelSpeed());
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!speedometer.get() || boat == null || !owns(boat)) return;
        long now = System.nanoTime();
        double echo = meter.echoBpt(now);
        String[] lines = {
            String.format(Locale.ROOT, "BOAT  Sent %.2f b/t | %.1f b/s", meter.sentBpt(), meter.sentBps()),
            Double.isFinite(echo) ? String.format(Locale.ROOT, "Server echo %.2f b/t | %.1f b/s", echo, meter.echoBps(now)) : "Server echo -- waiting for matching positions",
            modeDescription(),
            status + " | " + meter.packetsLastTick() + " updates/tick"
        };
        TextRenderer text = TextRenderer.get();
        if (text.isBuilding()) return;
        text.begin(event.graphics, meterScale.get());
        double width = 0, lineHeight = text.getHeight() + 4;
        for (String line : lines) width = Math.max(width, text.getWidth(line, true));
        double x = Math.max(0, event.screenWidth - width - 16) * meterX.get() / 100.0;
        double y = Math.max(0, event.screenHeight - lines.length * lineHeight - 16) * meterY.get() / 100.0;
        Renderer2D.COLOR.begin();
        Renderer2D.COLOR.quad(x, y, width + 16, lines.length * lineHeight + 16, new Color(10, 14, 20, 210));
        Renderer2D.COLOR.render();
        for (int i = 0; i < lines.length; i++)
            text.render(lines[i], x + 8, y + 8 + i * lineHeight,
                i == 1 ? new Color(110, 235, 220) : i == 2 && speedFallback() ? new Color(255, 195, 85) : new Color(255, 255, 255), true);
        text.end();
    }

    @Override
    public String getInfoString() {
        return boat == null ? status : String.format(Locale.ROOT, "%s | %.2f b/t sent", status, meter.sentBpt());
    }
}
