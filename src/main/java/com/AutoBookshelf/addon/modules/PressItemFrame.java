package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class PressItemFrame extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> commandTemplate = sgGeneral.add(new StringSetting.Builder()
        .name("command")
        .description("What command to use, {x} {y} {z} {uuid} {i} as placeholders.")
        .defaultValue("invisframe")
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks between processing each item frame.")
        .defaultValue(20)
        .min(1)
        .max(100)
        .sliderRange(1, 100)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotate player's head to look at the item frame briefly.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyWithItem = sgGeneral.add(new BoolSetting.Builder()
        .name("only-with-item")
        .description("Only process item frames that contain an item.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showInfo = sgGeneral.add(new BoolSetting.Builder()
        .name("show-info")
        .description("Display info when a frame is processed.")
        .defaultValue(true)
        .build()
    );

    private int timer;

    public PressItemFrame() {
        super(Addon.CATEGORY2, "Press-Frame", "Flatten any nearby item frame because You're an Elite Rank.");
    }

    @Override
    public void onActivate() {
        timer = 0;
    }

    private boolean canSee(ItemFrame frame) {
        Vec3 eyes = mc.player.getEyePosition();
        // Accurate centre of the frame's bounding box
        Vec3 center = frame.getBoundingBox().getCenter();
        // Direction the frame's front is facing (opposite of the attached wall/ceiling/floor)
        Vec3 facing = Vec3.atLowerCornerOf(frame.getDirection().getUnitVec3i());
        // Move the target 0.1 blocks outward so the ray doesn't hit the solid block behind
        Vec3 target = center.add(facing.scale(0.1));

        ClipContext context = new ClipContext(
            eyes, target,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            mc.player
        );

        BlockHitResult hit = mc.level.clip(context);
        if (hit.getType() == HitResult.Type.MISS) return true;

        double distToHit = eyes.distanceToSqr(hit.getLocation());
        double distToTarget = eyes.distanceToSqr(target);
        // Allow a tiny tolerance to handle floating‑point inaccuracies
        return distToHit >= distToTarget - 0.01;
    }

    @EventHandler
    public void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null) return;

        if (timer > 0) {
            timer--;
            return;
        }

        double reach = mc.player.entityInteractionRange();
        ItemFrame target = null;
        for (ItemFrame frame : mc.level.getEntitiesOfClass(
            ItemFrame.class,
            mc.player.getBoundingBox().inflate(reach),
            e -> true
        )) {
            if (frame.isInvisible()) continue;
            if (!PlayerUtils.isWithinReach(frame)) continue;
            if (onlyWithItem.get() && frame.getItem().isEmpty()) continue;
            if (!canSee(frame)) continue;

            target = frame;
            break;
        }

        if (target == null) return;

        // Safety: if not rotating, ensure crosshair is on the specific frame
        if (!rotate.get()) {
            HitResult hit = mc.hitResult;
            if (hit == null || hit.getType() != HitResult.Type.ENTITY) return;
            EntityHitResult entityHit = (EntityHitResult) hit;
            if (entityHit.getEntity() != target) return;
        }

        ItemStack held = target.getItem();
        String itemName = "";
        if (!held.isEmpty()) {
            itemName = BuiltInRegistries.ITEM.getKey(held.getItem()).getPath();
        }

        String rawCmd = commandTemplate.get()
            .replace("{x}", String.valueOf(target.blockPosition().getX()))
            .replace("{y}", String.valueOf(target.blockPosition().getY()))
            .replace("{z}", String.valueOf(target.blockPosition().getZ()))
            .replace("{uuid}", target.getUUID().toString())
            .replace("{i}", itemName);

        if (rawCmd.startsWith("/")) {
            rawCmd = rawCmd.substring(1);
        }

        if (showInfo.get()) {
            info("Sending command for frame at " + target.blockPosition().toShortString());
        }

        final String finalCmd = rawCmd;
        if (rotate.get()) {
            Vec3 center = target.position().add(0, target.getBbHeight() / 2.0, 0);
            Rotations.rotate(Rotations.getYaw(center), Rotations.getPitch(center), () -> {
                mc.player.connection.sendCommand(finalCmd);
            });
        } else {
            mc.player.connection.sendCommand(finalCmd);
        }

        timer = delay.get();
    }
}
