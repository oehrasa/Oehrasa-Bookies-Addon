/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.AttackAircraft;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

public final class BoatShotFeedback {
    private final ConcurrentLinkedQueue<Packet<?>> incoming = new ConcurrentLinkedQueue<>();
    private final Map<Integer, Float> health = new HashMap<>();
    private final Map<Integer, Float> absorption = new HashMap<>();
    private final Map<Integer, Integer> recentTotem = new HashMap<>();
    private final Map<Long, Integer> seen = new HashMap<>();
    private final Map<Integer, Integer> otherDamage = new HashMap<>();
    private final List<Hit> pending = new ArrayList<>();

    private static final class Hit {
        int victim, arrow, due;
        float before, absorptionBefore;
        String name;
        boolean overlapping, totem;
    }

    public void offer(Packet<?> packet) {
        if (!(packet instanceof ClientboundDamageEventPacket || packet instanceof ClientboundEntityEventPacket)) return;
        if (incoming.size() >= 256) incoming.poll();
        incoming.offer(packet);
    }

    public void reset() {
        incoming.clear();
        health.clear();
        absorption.clear();
        recentTotem.clear();
        seen.clear();
        otherDamage.clear();
        pending.clear();
    }

    public boolean hitConfirmed(int arrow) {
        if (arrow < 0) return false;
        for (long key : seen.keySet()) if ((int) (key >> 32) == arrow) return true;
        return false;
    }

    public void tick(ClientLevel world, int player, int tick, Consumer<String> report, Consumer<String> log) {
        Packet<?> packet;
        while ((packet = incoming.poll()) != null) {
            if (packet instanceof ClientboundDamageEventPacket damage) {
                int victim = damage.entityId();
                boolean ours = damage.sourceCauseId() == player && damage.sourceType().is(DamageTypes.ARROW);
                if (ours) {
                    long key = ((long) damage.sourceDirectId() << 32) ^ (victim & 0xffffffffL);
                    if (seen.putIfAbsent(key, tick + 200) != null) continue;
                    Entity entity = world.getEntity(victim);
                    Hit hit = new Hit();
                    hit.victim = victim;
                    hit.arrow = damage.sourceDirectId();
                    hit.due = tick + 6;
                    hit.name = entity == null ? "entity #" + victim : entity.getName().getString();
                    hit.before = health.getOrDefault(victim, Float.NaN);
                    hit.absorptionBefore = absorption.getOrDefault(victim, Float.NaN);
                    hit.totem = recentTotem.getOrDefault(victim, -100) >= tick - 1;
                    hit.overlapping = otherDamage.getOrDefault(victim, -100) >= tick - 2;
                    for (Hit old : pending)
                        if (old.victim == victim) {
                            old.overlapping = true;
                            hit.overlapping = true;
                        }
                    if (pending.size() >= 32) pending.removeFirst();
                    pending.add(hit);
                    log.accept("HIT tick=" + tick + " arrow=" + hit.arrow + " victim=" + victim + " name=" + hit.name);
                } else {
                    otherDamage.put(victim, tick);
                    for (Hit hit : pending) if (hit.victim == victim) hit.overlapping = true;
                }
            } else if (packet instanceof ClientboundEntityEventPacket status && status.getEventId() == 35) {
                Entity entity = status.getEntity(world);
                if (entity != null) {
                    recentTotem.put(entity.getId(), tick);
                    for (Hit hit : pending) if (hit.victim == entity.getId()) hit.totem = true;
                }
            }
        }
        for (Iterator<Hit> iterator = pending.iterator(); iterator.hasNext(); ) {
            Hit hit = iterator.next();
            if (tick < hit.due) continue;
            Entity entity = world.getEntity(hit.victim);
            float after = entity instanceof LivingEntity living ? living.getHealth() : Float.NaN;
            float absorptionAfter = entity instanceof LivingEntity living ? living.getAbsorptionAmount() : Float.NaN;
            double drop = hit.before - after;
            String result;
            if (hit.totem) result = "totem popped; damage amount unavailable";
            else if (!hit.overlapping && Double.isFinite(drop) && drop > 0.01) {
                result = String.format(Locale.ROOT, "observed health loss %.2f HP (%.2f hearts); remaining %.3f HP%s", drop, drop / 2, after, after > 0 ? " (alive)" : "");
            } else if (!hit.overlapping && Float.isFinite(hit.absorptionBefore) && hit.absorptionBefore > absorptionAfter + .01)
                result = String.format(Locale.ROOT, "observed absorption loss %.2f HP; damage amount unavailable", hit.absorptionBefore - absorptionAfter);
            else result = "damage amount unavailable" + (hit.overlapping ? " (overlapping hits)" : "");
            report.accept("Arrow hit " + hit.name + ": " + result + ".");
            log.accept("HIT-RESULT tick=" + tick + " arrow=" + hit.arrow + " victim=" + hit.victim + " before=" + hit.before + " after=" + after
                + " absorptionBefore=" + hit.absorptionBefore + " absorptionAfter=" + absorptionAfter + " result=" + result);
            iterator.remove();
        }
        seen.entrySet().removeIf(e -> tick > e.getValue());
        otherDamage.entrySet().removeIf(e -> tick - e.getValue() > 10);
        recentTotem.entrySet().removeIf(e -> tick - e.getValue() > 10);
        health.clear();
        absorption.clear();
        for (Entity entity : world.entitiesForRendering())
            if (entity instanceof LivingEntity living) {
                health.put(entity.getId(), living.getHealth());
                absorption.put(entity.getId(), living.getAbsorptionAmount());
            }
    }
}
