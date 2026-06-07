package com.AutoBookshelf.addon.commands;

import com.AutoBookshelf.addon.modules.MobOwner;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class AssignOwnerCommand extends Command {

    public AssignOwnerCommand() {
        super("assowner", "Assign a cracked account name as the owner of the entity you're looking at (or the nearest valid entity within range).");
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
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
        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY) {
            Entity hitEntity = ((EntityHitResult) mc.hitResult).getEntity();
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

        info("Assigned " + playerName + " (offline UUID " + ownerUuid + ") as owner of " + target.getType().getDescription().getString());
        return SINGLE_SUCCESS;
    }

    private boolean isValidEntity(Entity entity) {
        return entity instanceof TamableAnimal || entity instanceof ThrownEnderpearl;
    }

    private Entity findNearestValidEntity(double radius) {
        Entity nearest = null;
        double nearestDistSq = Double.MAX_VALUE;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!isValidEntity(entity)) continue;

            double distSq = mc.player.distanceToSqr(entity);
            if (distSq <= radius * radius && distSq < nearestDistSq) {
                nearest = entity;
                nearestDistSq = distSq;
            }
        }
        return nearest;
    }
}
