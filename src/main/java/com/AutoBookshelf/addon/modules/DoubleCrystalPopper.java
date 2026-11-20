/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.utils.EntityUtil;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.DamageUtils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

//? if >=26.1 {
//?} else {
/*import net.minecraft.network.protocol.game.ServerboundInteractPacket;
 */
//?}

/// Locks onto a nearby player and runs Attack -> wait N1 -> pop crystal -> wait N2 -> pop crystal -> repeat.
/// "Pop" = place an end crystal on the closest obsidian/bedrock and break it again as soon as it spawns.
public class DoubleCrystalPopper extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgCombo = settings.createGroup("Combo");
    private final SettingGroup sgPlace = settings.createGroup("Placement");

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Prints the current combo stage to chat.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("Range to look for a player to lock onto.")
        .defaultValue(10)
        .min(0)
        .sliderMax(16)
        .build()
    );

    private final Setting<Double> attackRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("attack-range")
        .description("Range needed to melee-attack the locked target.")
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotates towards the target/crystal placement before acting.")
        .defaultValue(true)
        .build()
    );


    private final Setting<Integer> n1 = sgCombo.add(new IntSetting.Builder()
        .name("n1-delay")
        .description("Ticks to wait after attacking before placing the first crystal.")
        .defaultValue(7)
        .min(0)
        .sliderMax(40)
        .build()
    );

    private final Setting<Integer> n2 = sgCombo.add(new IntSetting.Builder()
        .name("n2-delay")
        .description("Ticks to wait between the first and second crystal.")
        .defaultValue(0)
        .min(0)
        .sliderMax(40)
        .build()
    );

    private final Setting<Integer> breakTimeout = sgCombo.add(new IntSetting.Builder()
        .name("break-timeout")
        .description("If a placed crystal doesn't confirm within this many ticks, move on anyway instead of getting stuck.")
        .defaultValue(10)
        .min(1)
        .sliderMax(40)
        .build()
    );

    private final Setting<Double> placeRange = sgPlace.add(new DoubleSetting.Builder()
        .name("place-range")
        .description("Range to search for obsidian/bedrock and place crystals on.")
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Double> maxSelfDamage = sgPlace.add(new DoubleSetting.Builder()
        .name("max-self-damage")
        .description("Won't pop a crystal if it would deal more damage than this to you. Set to 36 to disable.")
        .defaultValue(8)
        .min(0)
        .sliderMax(36)
        .build()
    );

    private enum Stage {
        ATTACKING,
        WAIT_BEFORE_POP_1,
        WAIT_BREAK_1,
        WAIT_BEFORE_POP_2,
        WAIT_BREAK_2
    }

    private Player target;
    private Stage stage = Stage.ATTACKING;
    private int timer;
    private int breakTimeoutTimer;
    private BlockPos placingPos;

    public DoubleCrystalPopper() {
        super(Addon.CATEGORY, "Double-Crystal", "Attacks a locked target then pops two crystals with configurable delays.");
    }

    @Override
    public void onActivate() {
        target = null;
        stage = Stage.ATTACKING;
        timer = 0;
        breakTimeoutTimer = 0;
        placingPos = null;
        findPlacementBlockLastTick = -1000L;
        findPlacementBlockCache = null;
    }

    @Override
    public void onDeactivate() {
        target = null;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // (Re)acquire a target, resetting the combo whenever we get a new one
        if (target == null || TargetUtils.isBadTarget(target, targetRange.get())) {
            Player found = TargetUtils.getPlayerTarget(targetRange.get(), SortPriority.LowestDistance);

            if (found != target) {
                target = found;
                stage = Stage.ATTACKING;
                timer = 0;
            }
        }

        if (target == null) return;

        switch (stage) {
            case ATTACKING -> {
                // A melee beyond attack-range is rejected server-side, so hold the combo
                // (retrying each tick) until the target is actually in reach instead of
                // swinging at a packet that never lands.
                if (mc.player == null || !PlayerUtils.isWithin(target, attackRange.get())) return;
                attackTarget();
                timer = n1.get();
                stage = Stage.WAIT_BEFORE_POP_1;
                log("attacked, waiting " + n1.get() + " ticks");
            }

            case WAIT_BEFORE_POP_1 -> {
                if (timer > 0) {
                    timer--;
                    return;
                }

                if (!popCrystal()) return; // couldn't find a spot/item this tick, try again next tick

                breakTimeoutTimer = breakTimeout.get();
                stage = Stage.WAIT_BREAK_1;
                log("placed crystal 1, waiting for break confirmation");
            }

            case WAIT_BREAK_1 -> {
                if (--breakTimeoutTimer <= 0) {
                    timer = n2.get();
                    stage = Stage.WAIT_BEFORE_POP_2;
                    log("crystal 1 timed out, waiting " + n2.get() + " ticks");
                }
            }

            case WAIT_BEFORE_POP_2 -> {
                if (timer > 0) {
                    timer--;
                    return;
                }

                if (!popCrystal()) return;

                breakTimeoutTimer = breakTimeout.get();
                stage = Stage.WAIT_BREAK_2;
                log("placed crystal 2, waiting for break confirmation");
            }

            case WAIT_BREAK_2 -> {
                if (--breakTimeoutTimer <= 0) {
                    stage = Stage.ATTACKING;
                    log("crystal 2 timed out, looping back to attack");
                }
            }
        }
    }

    // Breaks the crystal the instant it's confirmed by the server, instead of waiting a tick.
    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        if (stage != Stage.WAIT_BREAK_1 && stage != Stage.WAIT_BREAK_2) return;
        if (!(event.entity instanceof EndCrystal crystal)) return;
        if (placingPos == null || !crystal.blockPosition().equals(placingPos)) return;

        attackEntity(crystal);

        if (stage == Stage.WAIT_BREAK_1) {
            timer = n2.get();
            stage = Stage.WAIT_BEFORE_POP_2;
            log("crystal 1 broken, waiting " + n2.get() + " ticks");
        } else {
            stage = Stage.ATTACKING;
            log("crystal 2 broken, looping back to attack");
        }
    }

    private void attackTarget() {
        if (rotate.get()) {
            double yaw = Rotations.getYaw(target);
            double pitch = Rotations.getPitch(target);
            Rotations.rotate(yaw, pitch, 50, () -> attackEntity(target));
        } else {
            attackEntity(target);
        }
    }

    private void attackEntity(Entity entity) {
        //? if >=26.1 {
        mc.player.connection.send(new ServerboundAttackPacket(entity.getId()));
        //?} else {
        /*mc.player.connection.send(ServerboundInteractPacket.createAttackPacket(entity, mc.player.isShiftKeyDown()));
         *///?}
        //? if >=26.3 {
        /*mc.player.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
        *///?} else {
        EntityUtil.swing(mc.player, InteractionHand.MAIN_HAND);
        //?}
    }

    // Returns false if it couldn't place this tick (no item, no valid spot) so the caller can retry next tick.
    private boolean popCrystal() {
        BlockPos basePos = findPlacementBlock();
        if (basePos == null) {
            log("no valid obsidian/bedrock in range");
            return false;
        }

        Vec3 crystalPos = new Vec3(basePos.getX() + 0.5, basePos.getY() + 1, basePos.getZ() + 0.5);
        if (DamageUtils.crystalDamage(mc.player, crystalPos) > maxSelfDamage.get()) {
            log("skipped placement, would deal too much self damage");
            return false;
        }

        FindItemResult item = InvUtils.findInHotbar(Items.END_CRYSTAL);
        if (!item.found()) {
            log("no end crystals in hotbar");
            return false;
        }

        InteractionHand hand = item.getHand();
        if (hand == null) return false;
        if (!item.isOffhand()) InvUtils.swap(item.slot(), false);

        BlockHitResult result = new BlockHitResult(crystalPos, Direction.UP, basePos, false);
        placingPos = basePos.above();

        Runnable place = () -> {
            mc.gameMode.useItemOn(mc.player, hand, result);
            EntityUtil.swing(mc.player, hand);
        };

        if (rotate.get()) {
            double yaw = Rotations.getYaw(crystalPos);
            double pitch = Rotations.getPitch(crystalPos);
            Rotations.rotate(yaw, pitch, 50, place);
        } else {
            place.run();
        }

        return true;
    }

    // The candidate search walks every cell in the (2r+1)³ cube with two block-state reads
    // each time; while the pop waits it runs every tick, so worst-case 1250 lookups per tick.
    // The answer cannot change faster than a block placement (or a block update), so a 4-tick
    // window captures every relevant change without starving the scan.
    private static final int FIND_SCAN_COOLDOWN_TICKS = 4;
    private long findPlacementBlockLastTick = -1000L;
    private BlockPos findPlacementBlockCache;

    private BlockPos findPlacementBlock() {
        BlockPos playerPos = mc.player.blockPosition();
        int r = (int) Math.ceil(placeRange.get());
        double rangeSq = placeRange.get() * placeRange.get();

        // One entity query for the whole search volume: candidates near us, crystal
        // boxes around each candidate. Computed here so both the cache revalidation
        // and the scan share it.
        List<AABB> nearby = entityBoxesNear(playerPos, r);

        long tick = mc.level.getGameTime();
        BlockPos cached = this.findPlacementBlockCache;
        if (this.findPlacementBlockLastTick >= 0 && tick - this.findPlacementBlockLastTick < FIND_SCAN_COOLDOWN_TICKS
            && cached != null
            && isPlacementCandidateValid(cached, playerPos, rangeSq, nearby)) {
            return cached;
        }

        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos pos = playerPos.offset(x, y, z);

                    if (!isPlacementCandidateValid(pos, playerPos, rangeSq, nearby)) continue;

                    double distSq = mc.player.position().distanceToSqr(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
                    if (distSq >= bestDistSq) continue;

                    bestDistSq = distSq;
                    best = pos;
                }
            }
        }

        this.findPlacementBlockCache = best;
        this.findPlacementBlockLastTick = tick;
        return best;
    }

    private boolean isPlacementCandidateValid(BlockPos pos, BlockPos playerPos, double rangeSq, List<AABB> nearby) {
        BlockState state = mc.level.getBlockState(pos);
        if (!state.is(Blocks.OBSIDIAN) && !state.is(Blocks.BEDROCK)) return false;
        if (!mc.level.getBlockState(pos.above()).isAir()) return false;
        if (!isPlaceable(pos, nearby)) return false;

        double distSq = mc.player.position().distanceToSqr(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
        return distSq <= rangeSq;
    }

    /**
     * The entity boxes around the whole search volume. Every candidate's crystal box sits inside
     * it, so one query covers them all.
     */
    private List<AABB> entityBoxesNear(BlockPos center, int radius) {
        // The volume has to contain every candidate's crystal box, or an entity standing in
        // a box the query never covered is invisible to the check and the crystal is placed
        // inside it. The candidates sit at center.y - radius .. center.y + radius and each
        // crystal box spans [y+1, y+2], so the union spans [center.y - radius + 1,
        // center.y + radius + 2]. The horizontal span is that union too.
        AABB volume = new AABB(center.getX() - radius, center.getY() - radius + 1.0, center.getZ() - radius,
            center.getX() + radius + 1.0, center.getY() + radius + 2.0, center.getZ() + radius + 1.0);
        List<AABB> boxes = new ArrayList<>();
        // The two-argument getEntities(null, box) this replaced runs NO_SPECTATORS (that is
        // EntityGetter's default for the two-argument form), so the predicate has to exclude
        // them too: a spectator's hitbox must not veto a crystal placement.
        for (Entity e : mc.level.getEntities((Entity) null, volume, EntitySelector.NO_SPECTATORS)) {
            boxes.add(e.getBoundingBox());
        }
        return boxes;
    }

    // Checks that the space the crystal would spawn in isn't overlapping any entity (yourself included) -
    // this is what silently rejects placements right at your own feet or right next to your target.
    private boolean isPlaceable(BlockPos basePos, List<AABB> nearby) {
        double x = basePos.getX();
        double y = basePos.getY() + 1;
        double z = basePos.getZ();

        AABB box = new AABB(x, y, z, x + 1, y + 2, z + 1);
        for (AABB other : nearby) {
            if (box.intersects(other)) return false;
        }
        return true;
    }

    private void log(String msg) {
        if (debug.get()) info(msg);
    }
}
