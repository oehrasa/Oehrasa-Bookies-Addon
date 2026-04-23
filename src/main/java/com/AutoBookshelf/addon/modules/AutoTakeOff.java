package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

public class AutoTakeOff extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> takeOffOnGround = sgGeneral.add(new BoolSetting.Builder()
        .name("take-off-on-ground")
        .description("Start elytra flight when standing on ground (auto jump).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> takeOffInLava = sgGeneral.add(new BoolSetting.Builder()
        .name("take-off-in-lava")
        .description("Start elytra flight when swimming in lava.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> takeOffWhenFalling = sgGeneral.add(new BoolSetting.Builder()
        .name("take-off-when-falling")
        .description("Start elytra flight when falling (e.g., walked off a cliff).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> fallingVelocityThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("falling-velocity-threshold")
        .description("Vertical velocity (negative) required to trigger takeoff when falling.")
        .defaultValue(-0.1)
        .min(-5)
        .max(0)
        .sliderRange(-5, 0)
        .visible(takeOffWhenFalling::get)
        .build()
    );

    private final Setting<Boolean> setPitch = sgGeneral.add(new BoolSetting.Builder()
        .name("set-pitch")
        .description("Temporarily set pitch to a specific value during takeoff.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> takeoffPitch = sgGeneral.add(new DoubleSetting.Builder()
        .name("takeoff-pitch")
        .description("Pitch angle to use during takeoff (negative = looking down).")
        .defaultValue(-18)
        .min(-90)
        .max(90)
        .sliderRange(-90, 90)
        .visible(setPitch::get)
        .build()
    );

    private final Setting<Integer> cooldown = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown")
        .description("Ticks to wait before attempting another takeoff.")
        .defaultValue(20)
        .min(5)
        .max(100)
        .build()
    );

    // NEW: Rotation mode
    private final Setting<RotationMode> rotationMode = sgGeneral.add(new EnumSetting.Builder<RotationMode>()
        .name("rotation-mode")
        .description("How to handle rotation during takeoff.")
        .defaultValue(RotationMode.Normal)
        .build()
    );

    // NEW: Auto disable after takeoff
    private final Setting<Boolean> disableAfterTakeoff = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-after-takeoff")
        .description("Automatically disable the module after a successful takeoff.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> disableDelay = sgGeneral.add(new IntSetting.Builder()
        .name("disable-delay")
        .description("Ticks to wait after takeoff before disabling the module.")
        .defaultValue(20)
        .min(0)
        .max(200)
        .visible(disableAfterTakeoff::get)
        .build()
    );

    private int cooldownTimer = 0;
    private int takeOffDelay = 0;
    private boolean restoring = false;
    private float originalPitch;
    private float originalYaw;
    private int disableTimer = 0;  // for delayed disable

    public AutoTakeOff() {
        super(Addon.CATEGORY, "auto-take-off", "Automatically starts elytra flight when on ground, in lava, or falling.");
    }

    @Override
    public void onActivate() {
        cooldownTimer = 0;
        takeOffDelay = 0;
        restoring = false;
        disableTimer = 0;
    }

    private boolean isElytraUsable(ItemStack chest) {
        if (chest.getItem() != Items.ELYTRA) return false;
        if (!chest.contains(DataComponentTypes.GLIDER)) return false;
        int damage = chest.getDamage();
        int maxDamage = chest.getMaxDamage();
        return damage < maxDamage;
    }

    private void sendStartFlyingPacket() {
        mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
    }

    private void setRotation(float yaw, float pitch, boolean silent) {
        if (rotationMode.get() == RotationMode.Silent) {
            // Silent rotation: client‑side only, no packets
            Rotations.rotate(yaw, pitch, 50, true, null);
        } else {
            // Normal rotation: send packets to server
            mc.player.setYaw(yaw);
            mc.player.setPitch(pitch);
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, mc.player.isOnGround(), mc.player.horizontalCollision));
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;

        // Handle delayed disable
        if (disableTimer > 0) {
            disableTimer--;
            if (disableTimer == 0 && disableAfterTakeoff.get()) {
                toggle();
                return;
            }
        }

        if (cooldownTimer > 0) {
            cooldownTimer--;
            return;
        }

        if (mc.player.isGliding()) {
            takeOffDelay = 0;
            return;
        }

        ItemStack chest = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (!isElytraUsable(chest)) return;

        if (restoring) {
            if (setPitch.get()) {
                setRotation(originalYaw, originalPitch, rotationMode.get() == RotationMode.Silent);
            }
            restoring = false;
            return;
        }

        if (takeOffDelay > 0) {
            takeOffDelay--;
            if (takeOffDelay == 0) {
                if (setPitch.get()) {
                    float targetPitch = takeoffPitch.get().floatValue();
                    setRotation(originalYaw, targetPitch, rotationMode.get() == RotationMode.Silent);
                }
                sendStartFlyingPacket();
                cooldownTimer = cooldown.get();
                if (setPitch.get()) {
                    restoring = true;
                }
                // Start disable timer if needed
                if (disableAfterTakeoff.get()) {
                    disableTimer = disableDelay.get();
                }
            }
            return;
        }

        // Ground takeoff
        if (takeOffOnGround.get() && mc.player.isOnGround()) {
            if (setPitch.get()) {
                originalYaw = mc.player.getYaw();
                originalPitch = mc.player.getPitch();
            }
            mc.player.jump();
            takeOffDelay = 2;
            return;
        }

        // Lava takeoff (immediate)
        if (takeOffInLava.get() && mc.player.isInLava()) {
            if (setPitch.get()) {
                originalYaw = mc.player.getYaw();
                originalPitch = mc.player.getPitch();
                float targetPitch = takeoffPitch.get().floatValue();
                setRotation(originalYaw, targetPitch, rotationMode.get() == RotationMode.Silent);
            }
            sendStartFlyingPacket();
            cooldownTimer = cooldown.get();
            if (setPitch.get()) {
                restoring = true;
            }
            if (disableAfterTakeoff.get()) {
                disableTimer = disableDelay.get();
            }
            return;
        }

        // Falling takeoff – FIXED: now also jumps and waits 2 ticks
        if (takeOffWhenFalling.get() && !mc.player.isOnGround() && mc.player.getVelocity().y < fallingVelocityThreshold.get()) {
            if (setPitch.get()) {
                originalYaw = mc.player.getYaw();
                originalPitch = mc.player.getPitch();
            }
            // Perform a jump (even though falling) to simulate double‑jump
            mc.player.jump();
            takeOffDelay = 2;
            return;
        }
    }

    public enum RotationMode {
        Normal,
        Silent
    }
}
