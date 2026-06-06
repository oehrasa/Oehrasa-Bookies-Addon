package com.AutoBookshelf.addon.commands;

import com.AutoBookshelf.addon.modules.MobOwner;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class AssignOwnerCommand extends Command {

    public AssignOwnerCommand() {
        super("assowner", "Assign a cracked account name as the owner of the entity you're looking at (or the nearest valid entity within range).");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        // Optional radius argument
        builder.then(argument("playerName", StringArgumentType.word())
            .executes(context -> {
                String playerName = StringArgumentType.getString(context, "playerName");
                return assignOwner(playerName, 4);   // default radius 4
            })
            .then(argument("radius", IntegerArgumentType.integer(1)).executes(context -> {
                String playerName = StringArgumentType.getString(context, "playerName");
                int radius = IntegerArgumentType.getInteger(context, "radius");
                return assignOwner(playerName, radius);
            }))
        );
    }

    private int assignOwner(String playerName, int radius) {
        Entity target = null;

        // 1. Try crosshair target first
        if (mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.ENTITY) {
            Entity hitEntity = ((EntityHitResult) mc.crosshairTarget).getEntity();
            if (isValidEntity(hitEntity)) {
                target = hitEntity;
            }
        }

        // 2. If not found, search for the nearest valid entity within the given radius
        if (target == null) {
            target = findNearestValidEntity(radius);
        }

        if (target == null) {
            error("No valid entity (tamed animal or ender pearl) found within " + radius + " blocks.");
            return -1;
        }

        // 3. Get MobOwner module
        MobOwner mobOwner = Modules.get().get(MobOwner.class);
        if (mobOwner == null || !mobOwner.isActive()) {
            error("MobOwner module is not active.");
            return -1;
        }

        // 4. Generate offline UUID
        UUID ownerUuid = UUID.nameUUIDFromBytes(
            ("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8)
        );

        // 5. Store the mapping
        mobOwner.assignOwner(target, ownerUuid, playerName);

        info("Assigned " + playerName + " (offline UUID " + ownerUuid + ") as owner of " + target.getType().getName().getString());
        return SINGLE_SUCCESS;
    }

    private boolean isValidEntity(Entity entity) {
        return entity instanceof TameableEntity || entity instanceof EnderPearlEntity;
    }

    private Entity findNearestValidEntity(double radius) {
        Entity nearest = null;
        double nearestDistSq = Double.MAX_VALUE;

        for (Entity entity : mc.world.getEntities()) {
            if (!isValidEntity(entity)) continue;

            double distSq = mc.player.squaredDistanceTo(entity);
            if (distSq <= radius * radius && distSq < nearestDistSq) {
                nearest = entity;
                nearestDistSq = distSq;
            }
        }
        return nearest;
    }
}
