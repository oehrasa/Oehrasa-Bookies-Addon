package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.entity.EntityMoveEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.network.packet.s2c.play.VehicleMoveS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.List;

public class BetterBoatFly extends Module {
    private final Setting<Double> speed;
    private final Setting<Double> verticalSpeed;
    private final Setting<Double> fallSpeed;
    private final Setting<Boolean> cancelServerPackets;
    private final Setting<Boolean> autoMount;
    private final Setting<Boolean> rotate;

    public BetterBoatFly() {
        super(Addon.CATEGORY, "Better-BoatFly", "Transforms your boat into a plane.");
        SettingGroup sgGeneral = settings.getDefaultGroup();

        speed = sgGeneral.add(new DoubleSetting.Builder()
            .name("speed")
            .description("Horizontal speed in blocks per second.")
            .defaultValue(10.0)
            .min(0.0)
            .sliderMax(50.0)
            .build());

        verticalSpeed = sgGeneral.add(new DoubleSetting.Builder()
            .name("vertical-speed")
            .description("Vertical speed in blocks per second.")
            .defaultValue(6.0)
            .min(0.0)
            .sliderMax(20.0)
            .build());

        fallSpeed = sgGeneral.add(new DoubleSetting.Builder()
            .name("fall-speed")
            .description("How fast you fall in blocks per second.")
            .defaultValue(0.1)
            .min(0.0)
            .build());

        cancelServerPackets = sgGeneral.add(new BoolSetting.Builder()
            .name("cancel-server-packets")
            .description("Cancels incoming boat move packets.")
            .defaultValue(false)
            .build());

        autoMount = sgGeneral.add(new BoolSetting.Builder()
            .name("boat-auto-mount")
            .description("Automatically mounts the nearest boat if not already riding one.")
            .defaultValue(false)
            .build());

        rotate = sgGeneral.add(new BoolSetting.Builder()
            .name("rotate")
            .description("Faces the boat before mounting.")
            .defaultValue(true)
            .build());
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!autoMount.get()) return;

        var player = mc.player;
        if (player == null || player.isRemoved() || player.hasVehicle()) return;

        double radius = 5.0;
        Box searchBox = player.getBoundingBox().expand(radius);
        assert mc.world != null;
        List<BoatEntity> boats = mc.world.getEntitiesByClass(BoatEntity.class, searchBox, boat -> !boat.hasPassenger(player));

        BoatEntity nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        Vec3d playerPos = player.getEntityPos();

        for (BoatEntity boat : boats) {
            double distSq = boat.squaredDistanceTo(playerPos);
            if (distSq < nearestDistSq && PlayerUtils.isWithin(boat, 5.0)) {
                nearest = boat;
                nearestDistSq = distSq;
            }
        }

        if (nearest != null) interact(nearest);
    }

    private void interact(BoatEntity boat) {
        if (rotate.get()) {
            var player = mc.player;
            assert player != null;
            double deltaX = boat.getX() - player.getX();
            double deltaZ = boat.getZ() - player.getZ();
            double deltaY = boat.getY() + boat.getHeight() / 2.0 - (player.getY() + player.getStandingEyeHeight());
            double yaw = Math.toDegrees(Math.atan2(deltaZ, deltaX)) - 90.0;
            double pitch = Math.toDegrees(-Math.atan2(deltaY, Math.sqrt(deltaX * deltaX + deltaZ * deltaZ)));

            player.setYaw((float) yaw);
            player.setPitch((float) pitch);
        }

        assert mc.interactionManager != null;
        mc.interactionManager.interactEntity(mc.player, boat, Hand.MAIN_HAND);
    }

    @EventHandler
    private void onEntityMove(EntityMoveEvent event) {
        Entity entity = event.entity;
        if (!(entity instanceof BoatEntity boat)) return;
        if (boat.getControllingPassenger() != mc.player) return;

        double velX = entity.getVelocity().x;
        double velY = entity.getVelocity().y;
        double velZ = entity.getVelocity().z;

        // Horizontal
        Vec3d vel = PlayerUtils.getHorizontalVelocity(speed.get());
        velX = vel.x;
        velZ = vel.z;

        // Vertical
        velY = 0.0;
        if (mc.options.jumpKey.isPressed()) velY += verticalSpeed.get() / 20.0;
        if (mc.options.sprintKey.isPressed()) velY -= verticalSpeed.get() / 20.0;
        else velY -= fallSpeed.get() / 20.0;

        boat.setYaw(mc.player.getYaw());
        ((IVec3d) event.movement).meteor$set(velX, velY, velZ);
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (event.packet instanceof VehicleMoveS2CPacket && cancelServerPackets.get()) {
            event.cancel();
        }
    }
}
