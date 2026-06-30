package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.AnchorAura;
import meteordevelopment.meteorclient.systems.modules.combat.BedAura;
import meteordevelopment.meteorclient.systems.modules.combat.CrystalAura;
import meteordevelopment.meteorclient.systems.modules.combat.KillAura;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.util.Hand;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

public class PacketEat extends Module {
    @SuppressWarnings("unchecked")
    private static final Class<? extends Module>[] AURAS = new Class[]{
        KillAura.class, CrystalAura.class, AnchorAura.class, BedAura.class
    };

    private static final int OFFHAND_EAT_TICKS = 10;

    private static final int HOTBAR_EAT_TICKS = 33;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAutoEat = settings.createGroup("Auto Eat");

    private final Setting<Boolean> deSync = sgGeneral.add(new BoolSetting.Builder()
        .name("de-sync")
        .description("Continuously resend the use-item packet each tick to de-sync the eating animation.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> noRelease = sgGeneral.add(new BoolSetting.Builder()
        .name("no-release")
        .description("Cancels the release-item packet so the server keeps you eating past the active window.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoEat = sgAutoEat.add(new BoolSetting.Builder()
        .name("auto-eat")
        .description("Automatically eat the best food in your hotbar or offhand when below a threshold.")
        .defaultValue(false)
        .build()
    );

    private final Setting<List<Item>> blacklist = sgAutoEat.add(new ItemListSetting.Builder()
        .name("blacklist")
        .description("Items that will never be auto-eaten.")
        .defaultValue(
            Items.POISONOUS_POTATO,
            Items.PUFFERFISH,
            Items.CHICKEN,
            Items.ROTTEN_FLESH,
            Items.SPIDER_EYE,
            Items.SUSPICIOUS_STEW
        )
        .filter(item -> item.getComponents().get(DataComponentTypes.FOOD) != null)
        .visible(autoEat::get)
        .build()
    );

    private final Setting<Boolean> pauseAuras = sgAutoEat.add(new BoolSetting.Builder()
        .name("pause-auras")
        .description("Pauses all combat auras while eating.")
        .defaultValue(true)
        .visible(autoEat::get)
        .build()
    );

    private final Setting<Boolean> pauseBaritone = sgAutoEat.add(new BoolSetting.Builder()
        .name("pause-baritone")
        .description("Pauses Baritone pathfinding while eating.")
        .defaultValue(true)
        .visible(autoEat::get)
        .build()
    );

    private final Setting<ThresholdMode> thresholdMode = sgAutoEat.add(new EnumSetting.Builder<ThresholdMode>()
        .name("threshold-mode")
        .description("Which stat(s) must be below their threshold to trigger eating.")
        .defaultValue(ThresholdMode.Any)
        .visible(autoEat::get)
        .build()
    );

    private final Setting<Double> healthThreshold = sgAutoEat.add(new DoubleSetting.Builder()
        .name("health-threshold")
        .description("Eat when health is at or below this value.")
        .defaultValue(10)
        .range(1, 19)
        .sliderRange(1, 19)
        .visible(() -> autoEat.get() && thresholdMode.get() != ThresholdMode.Hunger)
        .build()
    );

    private final Setting<Integer> hungerThreshold = sgAutoEat.add(new IntSetting.Builder()
        .name("hunger-threshold")
        .description("Eat when hunger is at or below this value.")
        .defaultValue(16)
        .range(1, 19)
        .sliderRange(1, 19)
        .visible(() -> autoEat.get() && thresholdMode.get() != ThresholdMode.Health)
        .build()
    );

    private final Setting<Integer> cooldownTicks = sgAutoEat.add(new IntSetting.Builder()
        .name("cooldown")
        .description("Extra ticks to wait after finishing an eat cycle before starting another.")
        .defaultValue(5)
        .range(0, 200)
        .sliderRange(0, 200)
        .visible(autoEat::get)
        .build()
    );

    // Active auto-eat cycle tracking
    private boolean autoEating = false;
    private int eatTicks = 0;
    private int eatDuration = 0; // set per-cycle: HOTBAR_EAT_TICKS or OFFHAND_EAT_TICKS
    private int postEatCooldown = 0;

    private int eatSlot = -1;
    private int prevSlot = -1;

    // Aura/baritone pause state
    private final List<Class<? extends Module>> wasAura = new ArrayList<>();
    private boolean wasBaritone = false;

    public PacketEat() {
        super(Addon.CATEGORY, "PacketEat", "Eat without interrupting movement or combat. Auto-eat mirrors Meteor's AutoEat with offhand-native support.");
    }

    @Override
    public void onDeactivate() {
        if (autoEating) stopAutoEating();
        postEatCooldown = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        var player = mc.player;
        if (player == null) return;

        // Manual de-sync: resend the use packet every tick

        if (deSync.get() && !autoEating && player.isUsingItem()) {
            var activeStack = player.getActiveItem();
            if (activeStack.get(DataComponentTypes.FOOD) != null) {
                Hand hand = player.getActiveHand();
                player.networkHandler.sendPacket(
                    new PlayerInteractItemC2SPacket(hand, 0, player.getYaw(), player.getPitch())
                );
            }
        }

        if (autoEat.get()) {
            handleAutoEat(player);
        }
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        var player = mc.player;
        if (player == null) return;

        if (noRelease.get() && event.packet instanceof PlayerActionC2SPacket packet) {
            if (packet.getAction() == PlayerActionC2SPacket.Action.RELEASE_USE_ITEM) {
                var activeStack = player.getActiveItem();
                if (activeStack.get(DataComponentTypes.FOOD) != null) {
                    event.cancel();
                }
            }
        }
    }

    private void handleAutoEat(ClientPlayerEntity player) {
        // Phase 1: actively in an eat cycle
        if (autoEating) {
            eatTicks++;

            // de-sync spam during the active window
            if (deSync.get() && eatSlot != -1) {
                Hand hand = eatSlot == SlotUtils.OFFHAND ? Hand.OFF_HAND : Hand.MAIN_HAND;
                player.networkHandler.sendPacket(
                    new PlayerInteractItemC2SPacket(hand, 0, player.getYaw(), player.getPitch())
                );
            }

            if (eatTicks >= eatDuration) {
                stopAutoEating();
                postEatCooldown = cooldownTicks.get();
            }
            return;
        }

        // Phase 2: post-eat cooldown
        if (postEatCooldown > 0) {
            postEatCooldown--;
            return;
        }

        // Phase 3: check if eating is needed
        if (!shouldEat(player)) return;

        int slot = findSlot(player);
        if (slot == -1) return;

        eatSlot = slot;
        startAutoEating(player);
    }

    private void startAutoEating(ClientPlayerEntity player) {
        // Pause combat auras
        wasAura.clear();
        if (pauseAuras.get()) {
            for (Class<? extends Module> klass : AURAS) {
                Module module = Modules.get().get(klass);
                if (module.isActive()) {
                    wasAura.add(klass);
                    module.toggle();
                }
            }
        }

        // Pause Baritone
        if (pauseBaritone.get() && PathManagers.get().isPathing() && !wasBaritone) {
            wasBaritone = true;
            PathManagers.get().pause();
        }

        if (eatSlot == SlotUtils.OFFHAND) {
            // Offhand: item stays equipped; noRelease + packet intercept carry the rest.
            eatDuration = OFFHAND_EAT_TICKS;
        } else {
            // Hotbar: we're temporarily swapping the hotbar selection, so we must stay
            // on this slot for the entire vanilla eat duration before swapping back.
            eatDuration = HOTBAR_EAT_TICKS;
            prevSlot = player.getInventory().selectedSlot;
            InvUtils.swap(eatSlot, false);
        }

        // Send the initial use-item packet to begin eating
        Hand hand = eatSlot == SlotUtils.OFFHAND ? Hand.OFF_HAND : Hand.MAIN_HAND;
        player.networkHandler.sendPacket(
            new PlayerInteractItemC2SPacket(hand, 0, player.getYaw(), player.getPitch())
        );

        autoEating = true;
        eatTicks = 0;
    }

    private void stopAutoEating() {
        // Revert hotbar slot if we swapped
        if (eatSlot != SlotUtils.OFFHAND && prevSlot != -1) {
            InvUtils.swap(prevSlot, false);
            prevSlot = -1;
        }

        eatSlot = -1;
        eatDuration = 0;
        autoEating = false;

        // Resume auras
        if (pauseAuras.get()) {
            for (Class<? extends Module> klass : AURAS) {
                Module module = Modules.get().get(klass);
                if (wasAura.contains(klass) && !module.isActive()) {
                    module.toggle();
                }
            }
        }

        // Resume Baritone
        if (pauseBaritone.get() && wasBaritone) {
            wasBaritone = false;
            PathManagers.get().resume();
        }
    }

    private int findSlot(ClientPlayerEntity player) {
        int bestSlot = -1;
        int bestNutrition = -1;

        // Hotbar (slots 0-8)
        for (int i = 0; i < 9; i++) {
            Item item = player.getInventory().getStack(i).getItem();
            FoodComponent food = item.getComponents().get(DataComponentTypes.FOOD);
            if (food == null) continue;
            if (blacklist.get().contains(item)) continue;
            if (food.nutrition() > bestNutrition) {
                bestSlot = i;
                bestNutrition = food.nutrition();
            }
        }

        // Offhand
        Item offItem = player.getOffHandStack().getItem();
        FoodComponent offFood = offItem.getComponents().get(DataComponentTypes.FOOD);
        if (offFood != null && !blacklist.get().contains(offItem) && offFood.nutrition() > bestNutrition) {
            bestSlot = SlotUtils.OFFHAND;
        }

        return bestSlot;
    }

    private boolean shouldEat(ClientPlayerEntity player) {
        boolean health = player.getHealth() <= healthThreshold.get();
        boolean hunger = player.getHungerManager().getFoodLevel() <= hungerThreshold.get();
        return thresholdMode.get().test(health, hunger);
    }

    public enum ThresholdMode {
        Health((health, hunger) -> health),
        Hunger((health, hunger) -> hunger),
        Any((health, hunger) -> health || hunger),
        Both((health, hunger) -> health && hunger);

        private final BiPredicate<Boolean, Boolean> predicate;

        ThresholdMode(BiPredicate<Boolean, Boolean> predicate) {
            this.predicate = predicate;
        }

        public boolean test(boolean health, boolean hunger) {
            return predicate.test(health, hunger);
        }
    }
}
