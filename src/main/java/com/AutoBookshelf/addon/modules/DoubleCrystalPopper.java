package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.DamageUtils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/// Locks onto a nearby player and runs Attack -> wait N1 -> pop crystal -> wait N2 -> pop crystal -> repeat.
/// "Pop" = place an end crystal on the closest obsidian/bedrock and break it again as soon as it spawns.
public class DoubleCrystalPopper extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgCombo = settings.createGroup("Combo");
    private final SettingGroup sgPlace = settings.createGroup("Placement");

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

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Prints the current combo stage to chat.")
        .defaultValue(false)
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

    private PlayerEntity target;
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
    }

    @Override
    public void onDeactivate() {
        target = null;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // (Re)acquire a target, resetting the combo whenever we get a new one
        if (target == null || TargetUtils.isBadTarget(target, targetRange.get())) {
            PlayerEntity found = TargetUtils.getPlayerTarget(targetRange.get(), SortPriority.LowestDistance);

            if (found != target) {
                target = found;
                stage = Stage.ATTACKING;
                timer = 0;
            }
        }

        if (target == null) return;

        switch (stage) {
            case ATTACKING -> {
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
        if (!(event.entity instanceof EndCrystalEntity crystal)) return;
        if (placingPos == null || !crystal.getBlockPos().equals(placingPos)) return;

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
        mc.player.networkHandler.sendPacket(PlayerInteractEntityC2SPacket.attack(entity, mc.player.isSneaking()));
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    // Returns false if it couldn't place this tick (no item, no valid spot) so the caller can retry next tick.
    private boolean popCrystal() {
        BlockPos basePos = findPlacementBlock();
        if (basePos == null) {
            log("no valid obsidian/bedrock in range");
            return false;
        }

        Vec3d crystalPos = new Vec3d(basePos.getX() + 0.5, basePos.getY() + 1, basePos.getZ() + 0.5);
        if (DamageUtils.crystalDamage(mc.player, crystalPos) > maxSelfDamage.get()) {
            log("skipped placement, would deal too much self damage");
            return false;
        }

        FindItemResult item = InvUtils.findInHotbar(Items.END_CRYSTAL);
        if (!item.found()) {
            log("no end crystals in hotbar");
            return false;
        }

        Hand hand = item.getHand();
        if (hand == null) return false;
        if (!item.isOffhand()) InvUtils.swap(item.slot(), false);

        BlockHitResult result = new BlockHitResult(crystalPos, Direction.UP, basePos, false);
        placingPos = basePos.up();

        Runnable place = () -> {
            mc.interactionManager.interactBlock(mc.player, hand, result);
            mc.player.swingHand(hand);
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

    private BlockPos findPlacementBlock() {
        BlockPos playerPos = mc.player.getBlockPos();
        int r = (int) Math.ceil(placeRange.get());
        double rangeSq = placeRange.get() * placeRange.get();

        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos pos = playerPos.add(x, y, z);

                    BlockState state = mc.world.getBlockState(pos);
                    if (!state.isOf(Blocks.OBSIDIAN) && !state.isOf(Blocks.BEDROCK)) continue;
                    if (!mc.world.getBlockState(pos.up()).isAir()) continue;
                    if (!isPlaceable(pos)) continue;

                    double distSq = mc.player.getEntityPos().squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
                    if (distSq > rangeSq || distSq >= bestDistSq) continue;

                    bestDistSq = distSq;
                    best = pos;
                }
            }
        }

        return best;
    }

    // Checks that the space the crystal would spawn in isn't overlapping any entity (yourself included) —
    // this is what silently rejects placements right at your own feet or right next to your target.
    private boolean isPlaceable(BlockPos basePos) {
        double x = basePos.getX();
        double y = basePos.getY() + 1;
        double z = basePos.getZ();

        Box box = new Box(x, y, z, x + 1, y + 2, z + 1);
        return mc.world.getOtherEntities(null, box).isEmpty();
    }

    private void log(String msg) {
        if (debug.get()) info(msg);
    }
}
