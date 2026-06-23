package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.function.BiPredicate;

public class PacketEat extends Module {
    private static final int NATURAL_EAT_DURATION = 32;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAutoEat = settings.createGroup("Auto Eat");

    private final Setting<Boolean> deSync = sgGeneral.add(new BoolSetting.Builder()
        .name("de-sync")
        .description("Continuously send interaction packets to desync the eating animation.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> noRelease = sgGeneral.add(new BoolSetting.Builder()
        .name("no-release")
        .description("Cancels the release item packet so the server thinks you are still eating.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoEat = sgAutoEat.add(new BoolSetting.Builder()
        .name("auto-eat")
        .description("Automatically eats from your offhand using packets when below thresholds.")
        .defaultValue(false)
        .build()
    );

    private final Setting<ThresholdMode> thresholdMode = sgAutoEat.add(new EnumSetting.Builder<ThresholdMode>()
        .name("threshold-mode")
        .description("The threshold mode to trigger auto eat.")
        .defaultValue(ThresholdMode.Any)
        .visible(autoEat::get)
        .build()
    );

    private final Setting<Double> healthThreshold = sgAutoEat.add(new DoubleSetting.Builder()
        .name("health-threshold")
        .description("The level of health you eat at.")
        .defaultValue(10)
        .range(1, 19)
        .sliderRange(1, 19)
        .visible(() -> autoEat.get() && thresholdMode.get() != ThresholdMode.Hunger)
        .build()
    );

    private final Setting<Integer> hungerThreshold = sgAutoEat.add(new IntSetting.Builder()
        .name("hunger-threshold")
        .description("The level of hunger you eat at.")
        .defaultValue(16)
        .range(1, 19)
        .sliderRange(1, 19)
        .visible(() -> autoEat.get() && thresholdMode.get() != ThresholdMode.Health)
        .build()
    );

    private final Setting<Integer> holdTicks = sgAutoEat.add(new IntSetting.Builder()
        .name("hold-ticks")
        .description("How many ticks to hold the fake eat packet before releasing.")
        .defaultValue(3)
        .range(1, NATURAL_EAT_DURATION)
        .sliderRange(1, NATURAL_EAT_DURATION)
        .visible(autoEat::get)
        .build()
    );

    private final Setting<Integer> cooldownTicks = sgAutoEat.add(new IntSetting.Builder()
        .name("cooldown")
        .description("Minimum ticks to wait after finishing an eat cycle before checking again.")
        .defaultValue(0)
        .range(0, 200)
        .sliderRange(0, 200)
        .visible(autoEat::get)
        .build()
    );

    private final Setting<List<Item>> blacklist = sgAutoEat.add(new ItemListSetting.Builder()
        .name("blacklist")
        .description("Which offhand items to not auto eat.")
        .defaultValue(
            Items.ENCHANTED_GOLDEN_APPLE,
            Items.GOLDEN_APPLE
        )
        .filter(item -> item.components().get(DataComponents.FOOD) != null)
        .visible(autoEat::get)
        .build()
    );

    private boolean autoEating = false;
    private int autoEatTicks = 0;
    private int cooldownRemaining = 0;

    public PacketEat() {
        super(Addon.CATEGORY2, "PacketEat", "Allows you to eat without interrupting other actions.");
    }

    @Override
    public void onDeactivate() {
        autoEating = false;
        autoEatTicks = 0;
        cooldownRemaining = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        LocalPlayer player = mc.player;
        if (player == null) return;

        if (deSync.get() && player.isUsingItem()) {
            var useItem = player.getUseItem();
            if (useItem.has(DataComponents.FOOD)) {
                InteractionHand hand = player.getUsedItemHand();
                player.connection.send(
                    new ServerboundUseItemPacket(hand, 0, player.getYRot(), player.getXRot())
                );
            }
        }

        if (autoEat.get()) handleAutoEat(player);
    }

    private void handleAutoEat(LocalPlayer player) {
        if (autoEating) {
            autoEatTicks++;

            if (deSync.get()) {
                player.connection.send(
                    new ServerboundUseItemPacket(InteractionHand.OFF_HAND, 0, player.getYRot(), player.getXRot())
                );
            }

            if (autoEatTicks >= holdTicks.get()) finishAutoEat(player);
            return;
        }

        if (cooldownRemaining > 0) {
            cooldownRemaining--;
            return;
        }

        if (!shouldEat(player)) return;

        var offhandStack = player.getOffhandItem();
        if (!offhandStack.has(DataComponents.FOOD)) return;
        if (blacklist.get().contains(offhandStack.getItem())) return;

        startAutoEat(player);
    }

    private void startAutoEat(LocalPlayer player) {
        player.connection.send(
            new ServerboundUseItemPacket(InteractionHand.OFF_HAND, 0, player.getYRot(), player.getXRot())
        );

        autoEating = true;
        autoEatTicks = 0;
    }

    private void finishAutoEat(LocalPlayer player) {
        player.connection.send(
            new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM,
                BlockPos.ZERO,
                Direction.DOWN,
                0
            )
        );

        autoEating = false;

        int elapsed = autoEatTicks;
        int remaining = NATURAL_EAT_DURATION - elapsed;
        cooldownRemaining = Math.max(cooldownTicks.get(), remaining);

        autoEatTicks = 0;
    }

    private boolean shouldEat(LocalPlayer player) {
        boolean health = player.getHealth() <= healthThreshold.get();
        boolean hunger = player.getFoodData().getFoodLevel() <= hungerThreshold.get();
        return thresholdMode.get().test(health, hunger);
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        LocalPlayer player = mc.player;
        if (player == null) return;

        if (noRelease.get() && event.packet instanceof ServerboundPlayerActionPacket packet) {
            if (packet.getAction() == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM) {
                var activeStack = player.getUseItem();
                if (activeStack.has(DataComponents.FOOD)) {
                    event.cancel();
                }
            }
        }
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
