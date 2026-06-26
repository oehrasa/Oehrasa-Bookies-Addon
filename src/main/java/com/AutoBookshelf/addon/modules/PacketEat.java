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

import java.util.List;
import java.util.function.BiPredicate;

public class PacketEat extends Module {
    // Normal eating duration in ticks (1.6 s). The server consumes the food after this time.
    private static final int EAT_DURATION_TICKS = 32;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

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

    private final Setting<Boolean> autoEat = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-eat")
        .description("Automatically eats from your offhand using packets when below thresholds.")
        .defaultValue(false)
        .build()
    );

    private final Setting<ThresholdMode> thresholdMode = sgGeneral.add(new EnumSetting.Builder<ThresholdMode>()
        .name("threshold-mode")
        .description("The threshold mode to trigger auto eat.")
        .defaultValue(ThresholdMode.Any)
        .visible(autoEat::get)
        .build()
    );

    private final Setting<Double> healthThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("health-threshold")
        .description("The level of health you eat at.")
        .defaultValue(10)
        .range(1, 19)
        .sliderRange(1, 19)
        .visible(() -> autoEat.get() && thresholdMode.get() != ThresholdMode.Hunger)
        .build()
    );

    private final Setting<Integer> hungerThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("hunger-threshold")
        .description("The level of hunger you eat at.")
        .defaultValue(16)
        .range(1, 19)
        .sliderRange(1, 19)
        .visible(() -> autoEat.get() && thresholdMode.get() != ThresholdMode.Health)
        .build()
    );

    private final Setting<Integer> cooldownTicks = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown")
        .description("Extra ticks to wait after an eat finishes before checking again.")
        .defaultValue(0)
        .range(0, 200)
        .sliderRange(0, 200)
        .visible(autoEat::get)
        .build()
    );

    private final Setting<List<Item>> blacklist = sgGeneral.add(new ItemListSetting.Builder()
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
    private int eatTicks = 0;
    private int postEatCooldown = 0;

    public PacketEat() {
        super(Addon.CATEGORY, "PacketEat", "Allows you to eat without interrupting other actions.");
    }

    @Override
    public void onDeactivate() {
        autoEating = false;
        eatTicks = 0;
        postEatCooldown = 0;
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
        // 1. While an eat is already in progress
        if (autoEating) {
            eatTicks++;
            if (eatTicks >= EAT_DURATION_TICKS) {
                // The server will have consumed the food by now.
                // Finish this cycle and start the extra cooldown.
                autoEating = false;
                eatTicks = 0;
                postEatCooldown = cooldownTicks.get();
            }
            return;
        }

        // 2. Wait for extra cooldown after a completed eat
        if (postEatCooldown > 0) {
            postEatCooldown--;
            return;
        }

        // 3. Check if we need to eat
        if (!shouldEat(player)) return;

        // 4. Validate offhand
        var offhandStack = player.getOffHandStack();
        if (offhandStack.get(DataComponentTypes.FOOD) == null) return;
        if (blacklist.get().contains(offhandStack.getItem())) return;

        // 5. Send the single "start use" packet
        player.networkHandler.sendPacket(
            new PlayerInteractItemC2SPacket(Hand.OFF_HAND, 0, player.getYaw(), player.getPitch())
        );

        // 6. Mark as eating and wait the full natural duration
        autoEating = true;
        eatTicks = 0;
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
