package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
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

    private int cooldownTimer = 0;
    private int takeOffDelay = 0;
    private boolean restoring = false;
    private float originalPitch;
    private float originalYaw;

    public AutoTakeOff() {
        super(Addon.CATEGORY, "auto-take-off", "Automatically starts elytra flight when on ground, in lava, or falling.");
    }

    @Override
    public void onActivate() {
        cooldownTimer = 0;
        takeOffDelay = 0;
        restoring = false;
    }

    private boolean isElytraUsable(ItemStack chest) {
        if (chest.getItem() != Items.ELYTRA) return false;
        if (!chest.contains(DataComponentTypes.GLIDER)) return false;
        // Check durability: if damage >= max damage, it's broken
        int damage = chest.getDamage();
        int maxDamage = chest.getMaxDamage();
        return damage < maxDamage;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;

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
                mc.player.setPitch(originalPitch);
                mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(originalYaw, originalPitch, mc.player.isOnGround(), mc.player.horizontalCollision));
            }
            restoring = false;
            return;
        }

        if (takeOffDelay > 0) {
            takeOffDelay--;
            if (takeOffDelay == 0) {
                if (setPitch.get()) {
                    float targetPitch = takeoffPitch.get().floatValue();
                    mc.player.setPitch(targetPitch);
                    mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(originalYaw, targetPitch, mc.player.isOnGround(), mc.player.horizontalCollision));
                }
                mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
                cooldownTimer = cooldown.get();
                if (setPitch.get()) {
                    restoring = true;
                }
            }
            return;
        }

        // Ground takeoff: need to jump and then wait 2 ticks
        if (takeOffOnGround.get() && mc.player.isOnGround()) {
            if (setPitch.get()) {
                originalYaw = mc.player.getYaw();
                originalPitch = mc.player.getPitch();
            }
            mc.player.jump();
            takeOffDelay = 2;
            return;
        }

        // Lava takeoff: immediate
        if (takeOffInLava.get() && mc.player.isInLava()) {
            if (setPitch.get()) {
                originalYaw = mc.player.getYaw();
                originalPitch = mc.player.getPitch();
                float targetPitch = takeoffPitch.get().floatValue();
                mc.player.setPitch(targetPitch);
                mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(originalYaw, targetPitch, mc.player.isOnGround(), mc.player.horizontalCollision));
            }
            mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
            cooldownTimer = cooldown.get();
            if (setPitch.get()) {
                restoring = true;
            }
            return;
        }

        // Falling takeoff: immediate (no jump needed)
        if (takeOffWhenFalling.get() && !mc.player.isOnGround() && mc.player.getVelocity().y < fallingVelocityThreshold.get()) {
            if (setPitch.get()) {
                originalYaw = mc.player.getYaw();
                originalPitch = mc.player.getPitch();
                float targetPitch = takeoffPitch.get().floatValue();
                mc.player.setPitch(targetPitch);
                mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(originalYaw, targetPitch, mc.player.isOnGround(), mc.player.horizontalCollision));
            }
            mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
            cooldownTimer = cooldown.get();
            if (setPitch.get()) {
                restoring = true;
            }
            return;
        }
    }
}
