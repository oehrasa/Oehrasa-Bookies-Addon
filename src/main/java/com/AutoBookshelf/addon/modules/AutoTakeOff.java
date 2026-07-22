package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.FireworkRocketItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.util.Hand;

public class AutoTakeOff extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRotation = settings.createGroup("Rotation");
    private final SettingGroup sgFirework = settings.createGroup("Firework");
    private final SettingGroup sgDisable = settings.createGroup("Auto Disable");

    // Mode selection
    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Normal = pitch adjustment + rotation packets. Simple = direct camera pitch set + jump.")
        .defaultValue(Mode.Simple)
        .build()
    );

    private final Setting<Boolean> setPitch = sgGeneral.add(new BoolSetting.Builder()
        .name("set-pitch")
        .description("Override the pitch sent to the server during takeoff, without moving your camera")
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

    private final Setting<Integer> rotationPriority = sgRotation.add(new IntSetting.Builder()
        .name("rotation-priority")
        .description("Priority passed to Rotations.rotate; raise this if another module keeps overriding the takeoff pitch.")
        .defaultValue(90)
        .min(0)
        .max(1000)
        .sliderRange(0, 200)
        .visible(() -> mode.get() == Mode.Normal && setPitch.get())
        .build()
    );

    // Simple mode pitch (direct camera pitch, mc.player.setPitch())
    private final Setting<Boolean> simpleSetPitch = sgGeneral.add(new BoolSetting.Builder()
        .name("simple-set-pitch")
        .description("Directly set your camera pitch during Simple mode takeoff.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Simple)
        .build()
    );

    private final Setting<Double> simpleTakeoffPitch = sgGeneral.add(new DoubleSetting.Builder()
        .name("simple-takeoff-pitch")
        .description("Pitch angle to set during Simple mode takeoff (negative = looking down)")
        .defaultValue(-28)
        .min(-90)
        .max(90)
        .sliderRange(-90, 90)
        .visible(() -> mode.get() == Mode.Simple && simpleSetPitch.get())
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

    private final Setting<Boolean> silentRockets = sgFirework.add(new BoolSetting.Builder()
        .name("silent-rockets")
        .description("Suppresses the hand swing animation when firing the firework.")
        .defaultValue(true)
        .visible(useFirework::get)
        .build()
    );

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
        simpleJumped = false;
        simpleWaitingForGlide = 0;
        disableTimer = 0;
        brokenMessageCooldown = 0;
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

    private void rotateThenTakeOff() {
        Rotations.rotate(originalYaw, (float) takeoffPitch.get().doubleValue(), rotationPriority.get(), this::sendStartFlyingPacket);
    }

    // Re-issued every tick while waiting for the glide to register so the
    // overridden pitch keeps holding until gliding starts or the window times out.
    private void holdTakeoffRotation() {
        Rotations.rotate(originalYaw, (float) takeoffPitch.get().doubleValue(), rotationPriority.get(), null);
    }

    private void applySimplePitch() {
        if (simpleSetPitch.get()) {
            mc.player.setPitch((float) simpleTakeoffPitch.get().doubleValue());
        }
    }

    private int countFireworks() {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() instanceof FireworkRocketItem) count += stack.getCount();
        }
        ItemStack offhand = mc.player.getOffHandStack();
        if (offhand.getItem() instanceof FireworkRocketItem) count += offhand.getCount();
        return count;
    }

    private void fireRocket() {
        if (mc.player == null || mc.interactionManager == null) return;

        int rocketSlot = -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() instanceof FireworkRocketItem) {
                rocketSlot = i;
                break;
            }
        }

        if (rocketSlot == -1) {
            if (mc.player.getOffHandStack().getItem() instanceof FireworkRocketItem) {
                mc.interactionManager.interactItem(mc.player, Hand.OFF_HAND);
                if (!silentRockets.get()) mc.player.swingHand(Hand.OFF_HAND);
            }
            return;
        }

        InvUtils.swap(rocketSlot, true);
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        if (!silentRockets.get()) mc.player.swingHand(Hand.MAIN_HAND);
        InvUtils.swapBack();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;

        // Auto-disable countdown always runs, independent of cooldown/gliding state.
        if (disableTimer > 0) {
            disableTimer--;
            if (disableTimer == 0 && disableAfterTakeoff.get()) {
                toggle();
                return;
            }
        }

        // Firework countdown always runs. This must be checked before the cooldown
        // and isGliding early-returns below, otherwise it can never reach 0 while
        // cooldownTimer is still counting down or once gliding has started.
        if (fireworkTimer > 0) {
            fireworkTimer--;
            if (fireworkTimer == 0 && useFirework.get()) {
                if (countFireworks() > 0) fireRocket();
                fireworkTimer = -1;
            }
            return;
        }

        if (waitingForGlide > 0) {
            waitingForGlide--;
            if (setPitch.get() && mode.get() == Mode.Normal && !mc.player.isGliding()) {
                holdTakeoffRotation();
            }
            if (mc.player.isGliding()) {
                waitingForGlide = 0;
                if (useFirework.get()) fireworkTimer = fireworkDelay.get() + 1;
                if (disableAfterTakeoff.get()) disableTimer = disableDelay.get();
            }
            return;
        }

        if (simpleWaitingForGlide > 0) {
            simpleWaitingForGlide--;
            if (mc.player.isGliding()) {
                simpleWaitingForGlide = 0;
                if (useFirework.get()) fireworkTimer = fireworkDelay.get() + 1;
                if (disableAfterTakeoff.get()) disableTimer = disableDelay.get();
            }
            return;
        }

        if (mc.player.isGliding()) {
            takeOffDelay = 0;
            simpleJumped = false;
            return;
        }

        if (cooldownTimer > 0) {
            cooldownTimer--;
            return;
        }

        ItemStack chest = mc.player.getEquippedStack(EquipmentSlot.CHEST);
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

        if (mode.get() == Mode.Simple) {
            // Ground takeoff: pitch + double jump
            if (mc.player.isOnGround()) {
                if (!simpleJumped) {
                    applySimplePitch();
                    mc.player.jump();
                    simpleJumped = true;
                    takeOffDelay = 2;
                } else if (takeOffDelay > 0) {
                    takeOffDelay--;
                    if (takeOffDelay == 0) {
                        mc.player.jump();
                        sendStartFlyingPacket();
                        cooldownTimer = cooldown.get();
                        simpleJumped = false;
                        simpleWaitingForGlide = 10;
                    }
                }
                return;
            }

            // Falling takeoff
            if (takeOffWhenFalling.get() && !mc.player.isOnGround() && mc.player.getVelocity().y < fallingVelocityThreshold.get()) {
                applySimplePitch();
                mc.player.jump();
                sendStartFlyingPacket();
                cooldownTimer = cooldown.get();
                simpleWaitingForGlide = 10;
                return;
            }

            // Lava takeoff
            if (takeOffInLava.get() && mc.player.isInLava()) {
                applySimplePitch();
                mc.player.jump();
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
                if (mc.player.isOnGround()) {
                    takeOffDelay = 1;
                    return;
                }
                if (setPitch.get()) {
                    rotateThenTakeOff();
                } else {
                    sendStartFlyingPacket();
                }
                cooldownTimer = cooldown.get();
                waitingForGlide = 10;
            }
            return;
        }

        // Ground takeoff
        if (takeOffOnGround.get() && mc.player.isOnGround()) {
            if (setPitch.get()) {
                originalYaw = mc.player.getYaw();
            }
            mc.player.jump();
            takeOffDelay = 2;
            return;
        }

        // Lava takeoff
        if (takeOffInLava.get() && mc.player.isInLava()) {
            mc.player.jump();
            if (setPitch.get()) {
                originalYaw = mc.player.getYaw();
                rotateThenTakeOff();
            } else {
                sendStartFlyingPacket();
            }
            cooldownTimer = cooldown.get();
            waitingForGlide = 10;
            return;
        }

        // Falling takeoff
        if (takeOffWhenFalling.get() && !mc.player.isOnGround() && mc.player.getVelocity().y < fallingVelocityThreshold.get()) {
            if (setPitch.get()) {
                originalYaw = mc.player.getYaw();
            }
            mc.player.jump();
            takeOffDelay = 2;
            return;
        }
    }

    public enum Mode {
        Normal,
        Simple
    }
}
