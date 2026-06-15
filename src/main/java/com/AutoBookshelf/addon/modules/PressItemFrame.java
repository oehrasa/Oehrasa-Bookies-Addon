package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

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

    private boolean canSee(ItemFrameEntity frame) {
        Vec3d eyes = mc.player.getEyePos();
        // Accurate centre of the frame's bounding box
        Vec3d center = frame.getBoundingBox().getCenter();
        // Direction the frame's front is facing (opposite of the attached wall/ceiling/floor)
        Vec3d facing = Vec3d.of(frame.getHorizontalFacing().getVector());
        // Move the target 0.1 blocks outward so the ray doesn't hit the solid block behind
        Vec3d target = center.add(facing.multiply(0.1));

        RaycastContext context = new RaycastContext(
            eyes, target,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            mc.player
        );

        BlockHitResult hit = mc.world.raycast(context);
        if (hit.getType() == HitResult.Type.MISS) return true;

        double distToHit = eyes.squaredDistanceTo(hit.getPos());
        double distToTarget = eyes.squaredDistanceTo(target);
        // Allow a tiny tolerance to handle floating‑point inaccuracies
        return distToHit >= distToTarget - 0.01;
    }

    @EventHandler
    public void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        if (timer > 0) {
            timer--;
            return;
        }

        double reach = mc.player.getEntityInteractionRange();
        ItemFrameEntity target = null;
        for (ItemFrameEntity frame : mc.world.getEntitiesByClass(
            ItemFrameEntity.class,
            mc.player.getBoundingBox().expand(reach),
            e -> true
        )) {
            if (frame.isInvisible()) continue;
            if (!PlayerUtils.isWithinReach(frame)) continue;
            if (onlyWithItem.get() && frame.getHeldItemStack().isEmpty()) continue;
            if (!canSee(frame)) continue;

            target = frame;
            break;
        }

        if (target == null) return;

        // Safety: if not rotating, ensure crosshair is on the specific frame
        if (!rotate.get()) {
            HitResult hit = mc.crosshairTarget;
            if (hit == null || hit.getType() != HitResult.Type.ENTITY) return;
            EntityHitResult entityHit = (EntityHitResult) hit;
            if (entityHit.getEntity() != target) return;
        }

        ItemStack held = target.getHeldItemStack();
        String itemName = "";
        if (!held.isEmpty()) {
            itemName = Registries.ITEM.getId(held.getItem()).getPath();
        }

        String rawCmd = commandTemplate.get()
            .replace("{x}", String.valueOf(target.getBlockPos().getX()))
            .replace("{y}", String.valueOf(target.getBlockPos().getY()))
            .replace("{z}", String.valueOf(target.getBlockPos().getZ()))
            .replace("{uuid}", target.getUuid().toString())
            .replace("{i}", itemName);

        if (rawCmd.startsWith("/")) {
            rawCmd = rawCmd.substring(1);
        }

        if (showInfo.get()) {
            info("Sending command for frame at " + target.getBlockPos().toShortString());
        }

        final String finalCmd = rawCmd;
        if (rotate.get()) {
            Vec3d center = target.getPos().add(0, target.getHeight() / 2.0, 0);
            Rotations.rotate(Rotations.getYaw(center), Rotations.getPitch(center), () -> {
                mc.player.networkHandler.sendChatCommand(finalCmd);
            });
        } else {
            mc.player.networkHandler.sendChatCommand(finalCmd);
        }

        timer = delay.get();
    }
}
