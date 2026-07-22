package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.entity.EntityMoveEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class BetterBoatFly extends Module {
    private final Setting<Double> speed;
    private final Setting<Double> verticalSpeed;
    private final Setting<Double> fallSpeed;
    private final Setting<Boolean> cancelServerPackets;
    private final Setting<Boolean> autoMount;
    private final Setting<Boolean> rotate;
    private final Setting<Double> mountRange;
    private final Setting<Boolean> antiKick;
    private final Setting<Integer> delay;

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

        mountRange = sgGeneral.add(new DoubleSetting.Builder()
            .name("mount-range")
            .description("Max distance to actually attempt mounting a boat.")
            .defaultValue(3.0)
            .min(1.0)
            .sliderMax(6.0)
            .visible(autoMount::get)
            .build());

        antiKick = sgGeneral.add(new BoolSetting.Builder()
            .name("anti-fly-kick")
            .description("Periodically dips down to prevent the server flagging you for flying.")
            .defaultValue(true)
            .build());

        delay = sgGeneral.add(new IntSetting.Builder()
            .name("delay")
            .description("Ticks between each anti-kick dip.")
            .defaultValue(40)
            .min(1)
            .sliderMax(80)
            .visible(antiKick::get)
            .build());
    }

    private int delayLeft;
    private double lastPacketY = Double.MAX_VALUE;
    private boolean sentPacket = false;
    private int mountCooldown;

    @Override
    public void onActivate() {
        delayLeft = delay.get();
        sentPacket = false;
        lastPacketY = Double.MAX_VALUE;
        mountCooldown = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!autoMount.get()) return;

        LocalPlayer player = mc.player;
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
            if (distSq < nearestDistSq && PlayerUtils.isWithin(boat, mountRange.get())) {
                nearest = boat;
                nearestDistSq = distSq;
            }
        }

        if (nearest != null) {
            if (mountCooldown <= 0) {
                interact(nearest);
                mountCooldown = 10; // half a second between attempts
            } else {
                mountCooldown--;
            }
        }
    }

    private void interact(Boat boat) {
        LocalPlayer player = mc.player;
        assert player != null;

        if (rotate.get()) {
            double deltaX = boat.getX() - player.getX();
            double deltaZ = boat.getZ() - player.getZ();
            double deltaY = boat.getY() + boat.getBbHeight() / 2.0 - (player.getY() + player.getEyeHeight());
            float yaw = (float) (Math.toDegrees(Math.atan2(deltaZ, deltaX)) - 90.0);
            float pitch = (float) Math.toDegrees(-Math.atan2(deltaY, Math.sqrt(deltaX * deltaX + deltaZ * deltaZ)));

            Rotations.rotate(yaw, pitch, -100, () -> doInteract(boat));
        } else {
            doInteract(boat);
        }
    }

    // interactAt + interact were merged into a single interact(player, entity, location, hand)
    // call in this Minecraft version; swing is still sent separately.
    private void doInteract(Boat boat) {
        assert mc.gameMode != null;
        EntityHitResult location = new EntityHitResult(boat, boat.getBoundingBox().getCenter());
        mc.player.swing(InteractionHand.MAIN_HAND);
        mc.gameMode.interact(mc.player, boat, location, InteractionHand.MAIN_HAND);
    }

    @EventHandler
    private void onEntityMove(EntityMoveEvent event) {
        if (!(event.entity instanceof Boat boat) || boat.getFirstPassenger() != mc.player) return;

        boat.setYRot(mc.player.getYRot());

        Vec3 vel = PlayerUtils.getHorizontalVelocity(speed.get());
        double velX = vel.x;
        double velZ = vel.z;
        double velY = 0.0;

        if (mc.options.keyJump.isDown()) velY += verticalSpeed.get() / 20.0;
        if (mc.options.keySprint.isDown()) velY -= verticalSpeed.get() / 20.0;
        else velY -= fallSpeed.get() / 20.0;

        ((IVec3) event.movement).meteor$set(velX, velY, velZ);
    }

    // Anti-fly-kick: periodically resends a slightly lower Y to the server so it
    // doesn't flag continuous upward/level flight, then corrects back to the real
    // position on the following tick. Ported from EntityControl; the isOnAir /
    // !isFlyingVehicle guard was dropped since BetterBoatFly is boat-only and
    // always airborne while flying, so that check doesn't apply here.
    @EventHandler
    private void onPreTick(TickEvent.Pre event) {
        if (!antiKick.get()) return;

        if (sentPacket && mc.player.getVehicle() != null) {
            ServerboundMoveVehiclePacket packet = ServerboundMoveVehiclePacket.fromEntity(mc.player.getVehicle());
            ((IVec3) packet.position()).meteor$setY(lastPacketY);
            mc.getConnection().send(packet);
            sentPacket = false;
        }

        delayLeft -= 1;
    }

    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        if (!antiKick.get() || !(event.packet instanceof ServerboundMoveVehiclePacket packet)) return;

        double currentY = packet.position().y;
        if (delayLeft <= 0 && !sentPacket && shouldFlyDown(currentY)) {
            ((IVec3) packet.position()).meteor$setY(lastPacketY - 0.03130D);
            sentPacket = true;
            delayLeft = delay.get();
        }

        lastPacketY = currentY;
    }

    private boolean shouldFlyDown(double currentY) {
        if (currentY >= lastPacketY) return true;
        return lastPacketY - currentY < 0.03130D;
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (event.packet instanceof ClientboundMoveVehiclePacket && cancelServerPackets.get()) {
            event.cancel();
        }
    }
}
