package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.function.BiPredicate;

public class PacketEat extends Module {
    private static final int EAT_DURATION_TICKS = 32; // vanilla eat time

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
        .filter(item -> item.components().get(DataComponents.FOOD) != null)
        .visible(autoEat::get)
        .build()
    );

    private boolean autoEating = false;
    private int eatTicks = 0;
    private int postEatCooldown = 0;
    private int interactionSequence = 0;
    private Item eatingItemSnapshot = null;
    private int eatingCountSnapshot = 0;

    public PacketEat() {
        super(Addon.CATEGORY2, "PacketEat", "Allows you to eat without interrupting other actions.");
    }

    @Override
    public void onDeactivate() {
        autoEating = false;
        eatTicks = 0;
        postEatCooldown = 0;
        eatingItemSnapshot = null;
        eatingCountSnapshot = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        LocalPlayer player = mc.player;
        if (player == null) return;

        // Manual desync: resend the use packet while the player is already eating
        if (deSync.get() && player.isUsingItem()) {
            var useItem = player.getUseItem();
            if (useItem.has(DataComponents.FOOD)) {
                InteractionHand hand = player.getUsedItemHand();
                player.connection.send(
                    new ServerboundUseItemPacket(hand, 0, player.getYRot(), player.getXRot())
                );
            }
        }

        if (autoEat.get()) {
            handleAutoEat(player);
        }
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

    private void handleAutoEat(LocalPlayer player) {
        // 1. While an eat is already in progress
        if (autoEating) {
            eatTicks++;

            var offhandStack = player.getOffhandItem();
            boolean stackChanged = offhandStack.getItem() != eatingItemSnapshot
                || offhandStack.getCount() != eatingCountSnapshot;

            if (stackChanged) {
                // Real confirmation the server actually consumed it.
                autoEating = false;
                eatTicks = 0;
                postEatCooldown = cooldownTicks.get();
                return;
            }

            if (eatTicks >= EAT_DURATION_TICKS) {
                // Timed out with no visible change; the interaction never landed.
                // Reset so the next tick can retry.
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
        var offhandStack = player.getOffhandItem();
        if (!offhandStack.has(DataComponents.FOOD)) return;
        if (blacklist.get().contains(offhandStack.getItem())) return;

        // 5. Send the "start use" packet with a real sequence number
        player.connection.send(
            new ServerboundUseItemPacket(InteractionHand.OFF_HAND, interactionSequence++, player.getYRot(), player.getXRot())
        );

        // 6. Snapshot what we're eating so step 1 can detect a real server‑side change
        eatingItemSnapshot = offhandStack.getItem();
        eatingCountSnapshot = offhandStack.getCount();
        autoEating = true;
        eatTicks = 0;
    }

    private boolean shouldEat(LocalPlayer player) {
        boolean health = player.getHealth() <= healthThreshold.get();
        boolean hunger = player.getFoodData().getFoodLevel() <= hungerThreshold.get();
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
