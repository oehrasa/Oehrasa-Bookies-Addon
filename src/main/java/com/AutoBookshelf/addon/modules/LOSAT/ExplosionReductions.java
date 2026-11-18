/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.LOSAT;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.entity.fakeplayer.FakePlayerEntity;
import net.minecraft.core.Holder;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class ExplosionReductions {
    private static final Object2IntMap<Holder<Enchantment>> enchantmentsScratch = new Object2IntOpenHashMap<>();

    private final LivingEntity target;
    private final boolean immune;
    private final float armor, toughness;
    private final int protection;
    private final float resistanceFactor;

    private ExplosionReductions(LivingEntity target) {
        this.target = target;

        immune = target instanceof Player player && EntityUtils.getGameMode(player) == GameType.CREATIVE && !(player instanceof FakePlayerEntity);
        armor = (float) Math.floor(target.getAttributeValue(Attributes.ARMOR));
        toughness = (float) target.getAttributeValue(Attributes.ARMOR_TOUGHNESS);

        MobEffectInstance resistance = target.getEffect(MobEffects.RESISTANCE);
        resistanceFactor = resistance == null ? 1 : (1 - (resistance.getAmplifier() + 1) * 0.2f);

        int damageProtection = 0;
        if (!explosionSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            for (EquipmentSlot slot : EquipmentSlotGroup.ARMOR) {
                ItemStack stack = target.getItemBySlot(slot);
                Utils.getEnchantments(stack, enchantmentsScratch);

                int prot = Utils.getEnchantmentLevel(enchantmentsScratch, Enchantments.PROTECTION);
                if (prot > 0) damageProtection += prot;

                int blast = Utils.getEnchantmentLevel(enchantmentsScratch, Enchantments.BLAST_PROTECTION);
                if (blast > 0) damageProtection += 2 * blast;
            }
        }
        protection = damageProtection;
    }

    public static ExplosionReductions of(LivingEntity target) {
        return new ExplosionReductions(target);
    }

    private static DamageSource explosionSource() {
        return mc.level.damageSources().explosion(null);
    }

    public float apply(float damage) {
        if (immune) return 0;

        DamageSource source = explosionSource();

        if (source.scalesWithDifficulty()) {
            switch (mc.level.getDifficulty()) {
                case EASY -> damage = Math.min(damage / 2 + 1, damage);
                case HARD -> damage *= 1.5f;
                default -> {
                }
            }
        }

        damage = CombatRules.getDamageAfterAbsorb(target, damage, source, armor, toughness);
        damage = Math.max(damage * resistanceFactor, 0);
        damage = CombatRules.getDamageAfterMagicAbsorb(damage, protection);

        return Math.max(damage, 0);
    }
}
