package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;
import java.util.function.BiPredicate;

public class PacketEat extends Module {
    // Vanilla's real food use_action duration (in ticks). Used to throttle auto-eat
    // so it can never consume items faster than a real eat would, regardless of holdTicks.
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
        .description("Minimum ticks to wait after finishing an eat cycle before checking again. Never goes below what's needed to match a real eat's pace.")
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
        .filter(item -> item.getComponents().get(DataComponentTypes.FOOD) != null)
        .visible(autoEat::get)
        .build()
    );

    private boolean autoEating = false;
    private int autoEatTicks = 0;
    private int cooldownRemaining = 0;

    public PacketEat() {
        super(Addon.CATEGORY, "PacketEat", "Allows you to eat without interrupting other actions.");
    }

    @Override
    public void onDeactivate() {
        autoEating = false;
        autoEatTicks = 0;
        cooldownRemaining = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        var player = mc.player;
        if (player == null) return;

        // Manual de-sync (mainhand/offhand, whichever the player is actively using)
        if (deSync.get() && player.isUsingItem()) {
            var activeStack = player.getActiveItem();
            if (activeStack.get(DataComponentTypes.FOOD) != null) {
                Hand hand = player.getActiveHand();
                player.networkHandler.sendPacket(
                    new PlayerInteractItemC2SPacket(hand, 0, player.getYaw(), player.getPitch())
                );
            }
        }

        // Auto eat (offhand packet only)
        if (autoEat.get()) handleAutoEat(player);
    }

    private void handleAutoEat(ClientPlayerEntity player) {
        if (autoEating) {
            autoEatTicks++;

            // Resend periodically to keep the server convinced we're still "using" the item
            if (deSync.get()) {
                player.networkHandler.sendPacket(
                    new PlayerInteractItemC2SPacket(Hand.OFF_HAND, 0, player.getYaw(), player.getPitch())
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

        var offhandStack = player.getOffHandStack();
        if (offhandStack.get(DataComponentTypes.FOOD) == null) return;
        if (blacklist.get().contains(offhandStack.getItem())) return;

        startAutoEat(player);
    }

    private void startAutoEat(ClientPlayerEntity player) {
        player.networkHandler.sendPacket(
            new PlayerInteractItemC2SPacket(Hand.OFF_HAND, 0, player.getYaw(), player.getPitch())
        );

        autoEating = true;
        autoEatTicks = 0;
    }

    private void finishAutoEat(ClientPlayerEntity player) {
        player.networkHandler.sendPacket(
            new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, Direction.DOWN)
        );

        autoEating = false;

        int elapsed = autoEatTicks;
        int remaining = NATURAL_EAT_DURATION - elapsed;
        cooldownRemaining = Math.max(cooldownTicks.get(), remaining);

        autoEatTicks = 0;
    }

    private boolean shouldEat(ClientPlayerEntity player) {
        boolean health = player.getHealth() <= healthThreshold.get();
        boolean hunger = player.getHungerManager().getFoodLevel() <= hungerThreshold.get();
        return thresholdMode.get().test(health, hunger);
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
