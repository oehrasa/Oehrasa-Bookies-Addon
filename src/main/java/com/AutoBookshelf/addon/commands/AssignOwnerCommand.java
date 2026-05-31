package com.AutoBookshelf.addon.commands;

import com.AutoBookshelf.addon.modules.MobOwner;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class AssignOwnerCommand extends Command {

    public AssignOwnerCommand() {
        super("assowner", "Assign a cracked account name as the owner of the entity you're looking at.");
    }

    @Override
    public void build(LiteralArgumentBuilder<SharedSuggestionProvider> builder) {
        builder.then(argument("playerName", StringArgumentType.word()).executes(context -> {
            String playerName = StringArgumentType.getString(context, "playerName");

            // 1. Find the target entity
            if (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.ENTITY) {
                error("You must be looking at an entity.");
                return -1;
            }

            Entity target = ((EntityHitResult) mc.hitResult).getEntity();

            // 2. Only allow entities that the MobOwner module cares about
            boolean isTameable = target instanceof TamableAnimal;
            boolean isPearl = target instanceof ThrownEnderpearl;
            if (!isTameable && !isPearl) {
                error("You can only assign owners to tamed animals or ender pearls.");
                return -1;
            }

            // 3. Get the MobOwner module
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
        }));
    }
}
