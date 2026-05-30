package com.AutoBookshelf.addon.commands;

import com.AutoBookshelf.addon.modules.MobOwner;
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
        super("assowner", "Assign a cracked account name as the owner of the entity you're looking at.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(argument("playerName", StringArgumentType.word()).executes(context -> {
            String playerName = StringArgumentType.getString(context, "playerName");

            // 1. Find the target entity
            if (mc.crosshairTarget == null || mc.crosshairTarget.getType() != HitResult.Type.ENTITY) {
                error("You must be looking at an entity.");
                return -1;
            }

            Entity target = ((EntityHitResult) mc.crosshairTarget).getEntity();

            // 2. Only allow entities that the MobOwner module cares about
            boolean isTameable = target instanceof TameableEntity;
            boolean isPearl = target instanceof EnderPearlEntity;
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

            info("Assigned " + playerName + " (offline UUID " + ownerUuid + ") as owner of " + target.getType().getName().getString());
            return SINGLE_SUCCESS;
        }));
    }
}
