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
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
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
        if (player == null || player.isRemoved() || player.isPassenger()) return;

        double radius = 5.0;
        AABB searchBox = player.getBoundingBox().inflate(radius);
        assert mc.level != null;
        List<Boat> boats = mc.level.getEntitiesOfClass(Boat.class, searchBox, boat -> !boat.hasPassenger(player));

        Boat nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        Vec3 playerPos = player.position();

        for (Boat boat : boats) {
            double distSq = boat.distanceToSqr(playerPos);
            if (distSq < nearestDistSq && PlayerUtils.isWithin(boat, 5.0)) {
                nearest = boat;
                nearestDistSq = distSq;
            }
        }

        if (nearest != null) interact(nearest);
    }

    private void interact(Boat boat) {
        if (rotate.get()) {
            var player = mc.player;
            assert player != null;
            double deltaX = boat.getX() - player.getX();
            double deltaZ = boat.getZ() - player.getZ();
            double deltaY = boat.getY() + boat.getBbHeight() / 2.0 - (player.getY() + player.getEyeHeight());
            double yaw = Math.toDegrees(Math.atan2(deltaZ, deltaX)) - 90.0;
            double pitch = Math.toDegrees(-Math.atan2(deltaY, Math.sqrt(deltaX * deltaX + deltaZ * deltaZ)));

            player.setYRot((float) yaw);
            player.setXRot((float) pitch);
        }

        assert mc.gameMode != null;
        mc.gameMode.interact(mc.player, boat, InteractionHand.MAIN_HAND);
    }

    @EventHandler
    private void onEntityMove(EntityMoveEvent event) {
        Entity entity = event.entity;
        if (!(entity instanceof Boat boat)) return;
        if (boat.getControllingPassenger() != mc.player) return;

        double velX = entity.getDeltaMovement().x;
        double velY = entity.getDeltaMovement().y;
        double velZ = entity.getDeltaMovement().z;

        // Horizontal
        Vec3 vel = PlayerUtils.getHorizontalVelocity(speed.get());
        velX = vel.x;
        velZ = vel.z;

        // Vertical
        velY = 0.0;
        if (mc.options.keyJump.isDown()) velY += verticalSpeed.get() / 20.0;
        if (mc.options.keySprint.isDown()) velY -= verticalSpeed.get() / 20.0;
        else velY -= fallSpeed.get() / 20.0;

        boat.setYRot(mc.player.getYRot());
        ((IVec3d) event.movement).meteor$set(velX, velY, velZ);
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (event.packet instanceof ClientboundMoveVehiclePacket && cancelServerPackets.get()) {
            event.cancel();
        }
    }
}
