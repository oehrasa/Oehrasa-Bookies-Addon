package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class AutoTakeOff extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRotation = settings.createGroup("Rotation");
    private final SettingGroup sgFirework = settings.createGroup("Firework");
    private final SettingGroup sgDisable = settings.createGroup("Auto Disable");

    // Mode selection
    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Normal = pitch adjustment + rotation packets. Simple = only double‑jump (no rotation).")
        .defaultValue(Mode.Normal)
        .build()
    );

    // Rotation settings (for Normal mode)
    private final Setting<RotationMode> rotationMode = sgRotation.add(new EnumSetting.Builder<RotationMode>()
        .name("rotation-mode")
        .description("Normal: sends rotation packets (server sees you turn). Silent: client‑side only (no packets)")
        .defaultValue(RotationMode.Silent)
        .visible(() -> mode.get() == Mode.Normal)
        .build()
    );

    private final Setting<Boolean> setPitch = sgGeneral.add(new BoolSetting.Builder()
        .name("set-pitch")
        .description("Temporarily set pitch to a specific value during takeoff")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Normal)
        .build()
    );

    private final Setting<Double> takeoffPitch = sgGeneral.add(new DoubleSetting.Builder()
        .name("takeoff-pitch")
        .description("Pitch angle to use during takeoff (negative = looking down)")
        .defaultValue(-18)
        .min(-90)
        .max(90)
        .sliderRange(-90, 90)
        .visible(() -> mode.get() == Mode.Normal && setPitch.get())
        .build()
    );

    private final Setting<Boolean> restorePitch = sgRotation.add(new BoolSetting.Builder()
        .name("restore-pitch")
        .description("Restore original pitch after takeoff (Normal mode only)")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Normal)
        .build()
    );

    // Normal mode conditions
    private final Setting<Boolean> takeOffOnGround = sgGeneral.add(new BoolSetting.Builder()
        .name("take-off-on-ground")
        .description("Start elytra flight when standing on ground.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Normal)
        .build()
    );

    private final Setting<Boolean> takeOffInLava = sgGeneral.add(new BoolSetting.Builder()
        .name("take-off-in-lava")
        .description("Start elytra flight when swimming in lava")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Normal)
        .build()
    );

    private final Setting<Boolean> takeOffWhenFalling = sgGeneral.add(new BoolSetting.Builder()
        .name("take-off-when-falling")
        .description("Start elytra flight when falling")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Normal)
        .build()
    );

    private final Setting<Double> fallingVelocityThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("falling-velocity-threshold")
        .description("Vertical velocity (negative) required to trigger takeoff when falling")
        .defaultValue(-0.1)
        .min(-5)
        .max(0)
        .sliderRange(-5, 0)
        .visible(() -> mode.get() == Mode.Normal && takeOffWhenFalling.get())
        .build()
    );

    private final Setting<Integer> cooldown = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown")
        .description("Ticks to wait before attempting another takeoff")
        .defaultValue(20)
        .min(5)
        .max(100)
        .build()
    );

    // Firework settings (both modes)
    private final Setting<Boolean> useFirework = sgFirework.add(new BoolSetting.Builder()
        .name("use-firework")
        .description("Automatically use a firework rocket from your hotbar after takeoff")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> fireworkDelay = sgFirework.add(new IntSetting.Builder()
        .name("firework-delay")
        .description("Ticks after gliding starts to use the firework")
        .defaultValue(5)
        .min(0)
        .max(40)
        .visible(useFirework::get)
        .build()
    );

    // Auto disable after takeoff
    private final Setting<Boolean> disableAfterTakeoff = sgDisable.add(new BoolSetting.Builder()
        .name("disable-after-takeoff")
        .description("Automatically disable the module after a successful takeoff")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> disableDelay = sgDisable.add(new IntSetting.Builder()
        .name("disable-delay")
        .description("Ticks after gliding starts before disabling the module")
        .defaultValue(10)
        .min(0)
        .max(100)
        .visible(disableAfterTakeoff::get)
        .build()
    );

    // State variables
    private int cooldownTimer = 0;
    private int takeOffDelay = 0;
    private int waitingForGlide = 0;
    private int fireworkTimer = 0;
    private int originalHotbarSlot = -1;
    private int fireworkSlot = -1;
    private float originalPitch;
    private float originalYaw;
    private int disableTimer = 0;

    // Simple mode state
    private boolean simpleJumped = false;
    private int simpleWaitingForGlide = 0;

    // Broken elytra message cooldown
    private int brokenMessageCooldown = 0;
    private static final int BROKEN_MESSAGE_INTERVAL = 100;

    public AutoTakeOff() {
        super(Addon.CATEGORY, "Auto-Take-Off", "Automatically starts elytra flight when on ground, in lava, or falling.");
    }

    @Override
    public void onActivate() {
        cooldownTimer = 0;
        takeOffDelay = 0;
        waitingForGlide = 0;
        fireworkTimer = 0;
        originalHotbarSlot = -1;
        fireworkSlot = -1;
        simpleJumped = false;
        simpleWaitingForGlide = 0;
        disableTimer = 0;
        brokenMessageCooldown = 0;
    }

    private boolean isElytraUsable(ItemStack chest) {
        if (chest.getItem() != Items.ELYTRA) return false;
        if (!chest.has(DataComponents.GLIDER)) return false;
        int damage = chest.getDamageValue();
        int maxDamage = chest.getMaxDamage();
        return damage < maxDamage;
    }

    private void sendStartFlyingPacket() {
        mc.player.connection.send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
    }

    // Rotation helper using Rotations.rotate (like reference)
    private void setRotation(float yaw, float pitch, boolean silent) {
        Rotations.rotate(yaw, pitch, 50, silent, null);
    }

    private int findFireworkInHotbar() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.getItem() instanceof FireworkRocketItem) {
                return i;
            }
        }
        return -1;
    }

    private void useFirework() {
        if (fireworkSlot == -1) return;
        originalHotbarSlot = mc.player.getInventory().getSelectedSlot();
        InvUtils.swap(fireworkSlot, false);
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        mc.execute(() -> {
            if (originalHotbarSlot != -1 && originalHotbarSlot != mc.player.getInventory().getSelectedSlot()) {
                InvUtils.swap(originalHotbarSlot, false);
            }
            originalHotbarSlot = -1;
        });
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;

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

        if (mc.player.isFallFlying()) {
            takeOffDelay = 0;
            waitingForGlide = 0;
            simpleJumped = false;
            simpleWaitingForGlide = 0;
            return;
        }

        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (chest.getItem() != Items.ELYTRA) return;

        if (!isElytraUsable(chest)) {
            if (brokenMessageCooldown <= 0) {
                error("Elytra is broken! Cannot take off.");
                brokenMessageCooldown = BROKEN_MESSAGE_INTERVAL;
            } else {
                brokenMessageCooldown--;
            }
            return;
        } else {
            brokenMessageCooldown = 0;
        }

        // Firework timer
        if (fireworkTimer > 0) {
            fireworkTimer--;
            if (fireworkTimer == 0 && useFirework.get()) {
                fireworkSlot = findFireworkInHotbar();
                if (fireworkSlot != -1) useFirework();
                fireworkTimer = -1;
            }
            return;
        }

        if (mode.get() == Mode.Simple) {
            if (simpleWaitingForGlide > 0) {
                simpleWaitingForGlide--;
                if (mc.player.isFallFlying()) {
                    simpleWaitingForGlide = 0;
                    if (useFirework.get()) fireworkTimer = fireworkDelay.get() + 1;
                    if (disableAfterTakeoff.get()) disableTimer = disableDelay.get();
                }
                return;
            }

            // Ground takeoff: double jump
            if (mc.player.onGround()) {
                if (!simpleJumped) {
                    mc.player.jumpFromGround();
                    simpleJumped = true;
                    takeOffDelay = 2;
                } else if (takeOffDelay > 0) {
                    takeOffDelay--;
                    if (takeOffDelay == 0) {
                        mc.player.jumpFromGround();
                        sendStartFlyingPacket();
                        cooldownTimer = cooldown.get();
                        simpleJumped = false;
                        simpleWaitingForGlide = 10;
                    }
                }
                return;
            }

            // Falling takeoff
            if (takeOffWhenFalling.get() && !mc.player.onGround() && mc.player.getDeltaMovement().y < fallingVelocityThreshold.get()) {
                mc.player.jumpFromGround();
                sendStartFlyingPacket();
                cooldownTimer = cooldown.get();
                simpleWaitingForGlide = 10;
                return;
            }

            // Lava takeoff
            if (takeOffInLava.get() && mc.player.isInLava()) {
                sendStartFlyingPacket();
                cooldownTimer = cooldown.get();
                simpleWaitingForGlide = 10;
                return;
            }
            return;
        }

        if (takeOffDelay > 0) {
            takeOffDelay--;
            if (takeOffDelay == 0) {
                if (setPitch.get()) {
                    boolean silent = (rotationMode.get() == RotationMode.Silent);
                    setRotation(originalYaw, (float) takeoffPitch.get().doubleValue(), silent);
                }
                sendStartFlyingPacket();
                cooldownTimer = cooldown.get();
                waitingForGlide = 10;
            }
            return;
        }

        if (waitingForGlide > 0) {
            waitingForGlide--;
            if (mc.player.isFallFlying()) {
                if (restorePitch.get() && setPitch.get()) {
                    // Restore original pitch, client‑side only (no packet)
                    setRotation(originalYaw, originalPitch, true);
                }
                waitingForGlide = 0;
                if (useFirework.get()) fireworkTimer = fireworkDelay.get() + 1;
                if (disableAfterTakeoff.get()) disableTimer = disableDelay.get();
            } else if (waitingForGlide == 0) {
                // Timeout – restore anyway
                if (restorePitch.get() && setPitch.get()) {
                    setRotation(originalYaw, originalPitch, true);
                }
            }
            return;
        }

        // Ground takeoff
        if (takeOffOnGround.get() && mc.player.onGround()) {
            if (setPitch.get()) {
                originalYaw = mc.player.getYRot();
                originalPitch = mc.player.getXRot();
            }
            mc.player.jumpFromGround();
            takeOffDelay = 2;
            return;
        }

        // Lava takeoff
        if (takeOffInLava.get() && mc.player.isInLava()) {
            if (setPitch.get()) {
                originalYaw = mc.player.getYRot();
                originalPitch = mc.player.getXRot();
                boolean silent = (rotationMode.get() == RotationMode.Silent);
                setRotation(originalYaw, (float) takeoffPitch.get().doubleValue(), silent);
            }
            sendStartFlyingPacket();
            cooldownTimer = cooldown.get();
            waitingForGlide = 10;
            return;
        }

        // Falling takeoff
        if (takeOffWhenFalling.get() && !mc.player.onGround() && mc.player.getDeltaMovement().y < fallingVelocityThreshold.get()) {
            if (setPitch.get()) {
                originalYaw = mc.player.getYRot();
                originalPitch = mc.player.getXRot();
            }
            mc.player.jumpFromGround();
            takeOffDelay = 2;
            return;
        }
    }

    public enum Mode {
        Normal,
        Simple
    }

    public enum RotationMode {
        Normal,
        Silent
    }
}
