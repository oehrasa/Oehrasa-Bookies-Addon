package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.entity.player.ItemUseCrosshairTargetEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.*;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

public class PacketEat extends Module {
    @SuppressWarnings("unchecked")
    private static final Class<? extends Module>[] AURAS = new Class[]{
        KillAura.class, CrystalAura.class, AnchorAura.class, BedAura.class,
        // Not technically an aura, but AutoWeapon swaps the hotbar on attack,
        // which cancels eating, so it must be paused alongside the attack modules.
        AutoWeapon.class
    };

    @SuppressWarnings("unchecked")
    private static final Class<? extends Module>[] INTERACTIONS = new Class[]{
        AutoBeacon.class, AutoFarm.class, AutoMoss.class, BookshelfFiller.class,
        DoubleCrystalPopper.class, DriedGhastPlacer.class, MinecartPlacer.class,
        PlatformBuilder.class, PressItemFrame.class, UnwaxAura.class
    };

    private static final int OFFHAND_EAT_TICKS = 10;

    private static final int HOTBAR_EAT_TICKS = 32;

    private static final int CONFIRM_TIMEOUT_TICKS = 20;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAutoEat = settings.createGroup("Auto Eat");
    private final SettingGroup sgEmergency = settings.createGroup("Emergency");

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
        .filter(item -> item.components().get(DataComponents.FOOD) != null)
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

    private final Setting<Boolean> swapBack = sgAutoEat.add(new BoolSetting.Builder()
        .name("swap-back")
        .description("Swap back to the previously held hotbar slot after finishing a hotbar eat cycle.")
        .defaultValue(true)
        .visible(autoEat::get)
        .build()
    );

    private final Setting<Boolean> confirmFinish = sgAutoEat.add(new BoolSetting.Builder()
        .name("confirm-finish")
        .description("Only end a cycle on an actual server-confirmed stack-count drop (or the timeout).")
        .defaultValue(true)
        .visible(autoEat::get)
        .build()
    );

    private final Setting<Boolean> suppressActions = sgAutoEat.add(new BoolSetting.Builder()
        .name("suppress-packet-actions")
        .description("While an eat cycle is active, cancel block-placement packets from third-party mods. Mining and attacking are never suppressed.")
        .defaultValue(false)
        .visible(autoEat::get)
        .build()
    );

    private final Setting<Integer> resumeDelay = sgAutoEat.add(new IntSetting.Builder()
        .name("resume-delay")
        .description("Extra ticks to keep paused modules paused after an eat cycle ends.")
        .defaultValue(4)
        .range(0, 40)
        .sliderRange(0, 20)
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

    private final Setting<Boolean> preventAlwaysEatSpam = sgAutoEat.add(new BoolSetting.Builder()
        .name("prevent-always-eat-spam")
        .description("Adds extra cooldown after eating a food that can always be eaten regardless of hunger.")
        .defaultValue(true)
        .visible(autoEat::get)
        .build()
    );

    private final Setting<Integer> alwaysEatCooldown = sgAutoEat.add(new IntSetting.Builder()
        .name("always-eat-cooldown")
        .description("Extra ticks to wait after eating a hunger-independent food before eating can trigger again.")
        .defaultValue(40)
        .range(0, 600)
        .sliderRange(0, 200)
        .visible(() -> autoEat.get() && preventAlwaysEatSpam.get())
        .build()
    );

    private final Setting<Boolean> emergencyMode = sgEmergency.add(new BoolSetting.Builder()
        .name("emergency-mode")
        .description("When health drops to emergency-health, drop everything (mining, placing blocks, attacking, pathing) and force-eat immediately.")
        .defaultValue(false)
        .visible(autoEat::get)
        .build()
    );

    private final Setting<Double> emergencyHealth = sgEmergency.add(new DoubleSetting.Builder()
        .name("emergency-health")
        .description("Health in hearts that triggers the emergency eat.")
        .defaultValue(6)
        .range(1, 10)
        .sliderRange(1, 10)
        .visible(() -> autoEat.get() && emergencyMode.get())
        .build()
    );

    private final Setting<Boolean> emergencyPauseInteractions = sgEmergency.add(new BoolSetting.Builder()
        .name("emergency-pause-interactions")
        .description("Pauses modules that place blocks or use items while the emergency eat is active.")
        .defaultValue(true)
        .visible(() -> autoEat.get() && emergencyMode.get())
        .build()
    );

    // Active auto-eat cycle tracking
    private boolean autoEating = false;
    private int eatTicks = 0;
    private int eatDuration = 0;
    private int postEatCooldown = 0;

    private int eatSlot = -1;
    private int prevSlot = -1;
    private int eatStackCountAtStart = -1;
    private boolean eatFoodAlwaysEat = false;

    // tracks which method this cycle used, so the crosshair override
    // only applies when we actually went through the screen-open fallback.
    private boolean eatingViaScreenClick = false;

    private final List<Class<? extends Module>> wasAura = new ArrayList<>();
    private boolean wasBaritone = false;

    // Deferred resume buffer: auras stay paused for resume-delay ticks after the
    // cycle ends so the final bite isn't interrupted by a resumed combat module.
    private int resumeDelayTicks = 0;

    // Emergency-mode state
    private boolean emergencyActive = false;
    private boolean emergencyBlocking = false;
    private final List<Class<? extends Module>> emergencyPausedAura = new ArrayList<>();
    private boolean emergencyPausedBaritone = false;
    private final List<Class<? extends Module>> emergencyPausedInteractions = new ArrayList<>();

    // Anti-double-eat latch: the client hunger/health stats lag the server, so
    // right after a meal the trigger flag is still set. Remember the levels at
    // the start of the last cycle and refuse to eat again on the same crossing.
    private float lastCycleHealth = -1;
    private int lastCycleHunger = -1;

    public PacketEat() {
        super(Addon.CATEGORY2, "PacketEat", "Eat without interrupting movement or combat.");
    }

    @Override
    public void onDeactivate() {
        if (autoEating) stopAutoEating();
        if (emergencyBlocking) stopEmergency();
        emergencyActive = false;
        emergencyBlocking = false;
        // Force-resolve any pending deferred resume so disabling the module
        // never leaves the paused auras off.
        resumeAuras();
        postEatCooldown = 0;
        lastCycleHealth = -1;
        lastCycleHunger = -1;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        var player = mc.player;
        if (player == null) return;

        if (deSync.get() && !autoEating && player.isUsingItem()) {
            var activeStack = player.getActiveItem();
            if (activeStack.get(DataComponents.FOOD) != null) {
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

    // Scoped: only intercepts crosshair targeting when this cycle actually
    // needed the screen-open workaround. The direct-packet path (no screen)
    // never touches this event.
    @EventHandler
    private void onItemUseCrosshairTarget(ItemUseCrosshairTargetEvent event) {
        if (autoEating && eatingViaScreenClick) event.target = null;
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        var player = mc.player;
        if (player == null) return;

        if (noRelease.get() && event.packet instanceof ServerboundPlayerActionPacket packet) {
            if (packet.getAction() == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM) {
                var activeStack = player.getActiveItem();
                if (activeStack.get(DataComponents.FOOD) != null) {
                    event.cancel();
                }
            }
        }

        // Third-party mods (Litematica printers) cannot be paused like modules,
        // so cancel their block placement while a cycle is actually chewing on
        // food. Mining is never suppressed, and nothing is cancelled at all when
        // there's no food left in the eat slot to recover from, so the cycle
        // can't lock the player out of digging or attacking.
        if (suppressActions.get() && autoEating
            && eatSlot != -1 && getFoodComponent(player, eatSlot) != null
            && event.packet instanceof ServerboundUseItemOnPacket) {
            event.cancel();
        }
    }

    private void handleAutoEat(LocalPlayer player) {
        boolean wasBlocking = emergencyBlocking;
        emergencyActive = emergencyMode.get() && player.getHealth() <= emergencyHealth.get() * 2;

        // Engage the "drop placing modules" state only while there is actually
        // food to force-eat. Critically low with no food is just critically low —
        // nothing to recover from
        emergencyBlocking = emergencyActive && findSlot(player, true) != -1;

        if (emergencyBlocking && !wasBlocking) startEmergency();
        else if (!emergencyBlocking && wasBlocking) stopEmergency();

        if (autoEating) {
            eatTicks++;

            // De-sync spam is only meaningful on the direct-packet path. If we're
            // going through the screen-open fallback, a raw resend here doesn't
            // reflect real input state the way Utils.rightClick() does, so skip it.
            if (deSync.get() && eatSlot != -1 && !eatingViaScreenClick) {
                InteractionHand hand = eatSlot == SlotUtils.OFFHAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
                player.connection.send(
                    new ServerboundUseItemPacket(hand, 0, player.getYRot(), player.getXRot())
                );
            }

            int count = getStackCount(player, eatSlot);
            boolean stackConsumed = eatStackCountAtStart != -1 && count < eatStackCountAtStart;
            boolean noFood = count <= 0 || getFoodComponent(player, eatSlot) == null;
            boolean duringBite = !stackConsumed;

            if (stackConsumed) eatStackCountAtStart = count;

            // AutoEat-style stat guard: end a cycle only once hunger/health actually
            // recovered (and, with confirm-finish, only after the bite that restored
            // them completed server-side), when the food ran out, or when the attempt
            // timed out without making progress. Never swap the hotbar back earlier.
            boolean recovered = !belowThreshold(player);
            // Use at least HOTBAR_EAT_TICKS as the base so offhand cycles also get
            // enough time to finish and be confirmed before the timeout fires.
            int minEatDuration = Math.max(eatDuration, HOTBAR_EAT_TICKS);
            boolean timedOut = duringBite && eatTicks >= minEatDuration + CONFIRM_TIMEOUT_TICKS;

            boolean readyToStop = noFood || timedOut;
            if (!emergencyActive && recovered && (stackConsumed || !confirmFinish.get())) {
                readyToStop = true;
            }

            if (readyToStop) {
                stopAutoEating();
                postEatCooldown = computeCooldown();
                return;
            }

            // A bite finished and more food is still needed -> chain-eat the next
            // item in the stack from the same slot, without leaving it.
            if (stackConsumed) {
                eatTicks = 0;
                useSelectedFood(player);
            }
            return;
        }

        // Deferred resume buffer: keep the paused modules off for resume-delay
        // ticks after the cycle ends. Skipped while an emergency is actively
        // blocking, which chain-eats and holds its own pause union. Must be
        // emergencyBlocking, not emergencyActive: otherwise low health with no
        // food freezes the countdown forever and the auras never resume.
        if (resumeDelayTicks > 0) {
            if (!emergencyBlocking) {
                resumeDelayTicks--;
                if (resumeDelayTicks == 0) resumeAuras();
            }
            return;
        }

        // Phase 2: post-eat cooldown. Skiped entirely while emergency is active
        // so we chain-eat until health is back above the emergency threshold.
        if (postEatCooldown > 0) {
            if (emergencyActive) postEatCooldown = 0;
            else {
                postEatCooldown--;
                return;
            }
        }

        // Phase 3: check if eating is needed. Emergency bypasses the slow
        // shouldEat threshold entirely.
        if (!emergencyActive && !shouldEat(player)) return;

        int slot = findSlot(player, emergencyActive);
        if (slot == -1) return;

        // Only interrupt the current action when we're actually about to bite
        // into something
        if (emergencyActive) interruptCurrentAction(player);

        eatSlot = slot;
        startAutoEating(player);
    }

    private void startAutoEating(LocalPlayer player) {
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

        FoodProperties food = getFoodComponent(player, eatSlot);
        eatFoodAlwaysEat = food != null && food.canAlwaysEat();

        if (eatSlot == SlotUtils.OFFHAND) {
            // Offhand: item stays equipped; noRelease + packet intercept carry the rest.
            eatDuration = OFFHAND_EAT_TICKS;
        } else {
            // Hotbar: we're temporarily swapping the hotbar selection, so we must stay
            // on this slot until the eat is confirmed finished before swapping back.
            eatDuration = HOTBAR_EAT_TICKS;
            prevSlot = player.getInventory().getSelectedSlot();
            InvUtils.swap(eatSlot, false);
        }

        // Record the stack count baseline right before we start consuming, so the
        // per-tick guard in handleAutoEat can detect the moment it drops.
        eatStackCountAtStart = getStackCount(player, eatSlot);

        // Remember where the stats were when the cycle started, for the
        // anti-double-eat latch in shouldEat.
        lastCycleHealth = player.getHealth();
        lastCycleHunger = player.getFoodData().getFoodLevel();

        // Decide method once per cycle, based on actual screen state right now.
        eatingViaScreenClick = mc.screen != null;

        autoEating = true;
        eatTicks = 0;

        useSelectedFood(player);
    }

    // Kicks off (or re-kicks) the eat using the same mechanism this cycle chose:
    // the screen-open fallback goes through the real input/raycast pipeline, which
    // is why the crosshair override above is needed for that branch; anything else
    // sends the raw interact packet on the eat slot.
    private void useSelectedFood(LocalPlayer player) {
        if (eatingViaScreenClick) {
            Utils.rightClick();
        } else {
            InteractionHand hand = eatSlot == SlotUtils.OFFHAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            player.connection.send(
                new ServerboundUseItemPacket(hand, 0, player.getYRot(), player.getXRot())
            );
        }
    }

    private void stopAutoEating() {
        // Revert hotbar slot if we swapped and swap-back is enabled
        if (eatSlot != SlotUtils.OFFHAND && prevSlot != -1) {
            if (swapBack.get()) {
                InvUtils.swap(prevSlot, false);
            }
            prevSlot = -1;
        }

        eatSlot = -1;
        eatDuration = 0;
        autoEating = false;
        eatStackCountAtStart = -1;
        eatingViaScreenClick = false;

        // Defer the resume. While an emergency is actively blocking, the resume is
        // handled by stopEmergency() (which resumes the union, ours + the
        // routine's), so we leave wasAura populated for it. Otherwise hold the
        // paused modules off for resume-delay ticks so the final bite isn't
        // interrupted. Gate on emergencyBlocking, not emergencyActive: with low
        // health but no food to eat, no emergency ever started, so nothing will
        // ever call stopEmergency() to resume for us.
        resumeDelayTicks = emergencyBlocking ? 0 : resumeDelay.get();
    }

    private void resumeAuras() {
        resumeDelayTicks = 0;

        // Resume auras. Skipped while an emergency is actively blocking, where
        // stopEmergency() owns the union resume. emergencyActive alone is not
        // enough: that flag is also true when there is no food to eat, and in
        // that case no emergency started, so this resume must still run or the
        // auras/Baritone stay paused for good.
        if (pauseAuras.get() && !emergencyBlocking) {
            for (Class<? extends Module> klass : AURAS) {
                Module module = Modules.get().get(klass);
                if (wasAura.contains(klass) && !module.isActive()) {
                    module.toggle();
                }
            }
        }
        wasAura.clear();

        // Resume Baritone
        if (pauseBaritone.get() && wasBaritone && !emergencyBlocking) {
            wasBaritone = false;
            PathManagers.get().resume();
        }
    }

    private void interruptCurrentAction(LocalPlayer player) {
        // Target only item use / placement: drop the use key and cancel any
        // in-progress item interaction so nothing keeps building while we bite.
        // The attack key and block-breaking progress are deliberately left
        // untouched — mining must never be suppressed by this module.
        mc.options.keyUse.setDown(false);
        if (player.isUsingItem()) player.releaseUsingItem();
    }

    private void startEmergency() {
        if (emergencyPauseInteractions.get()) {
            for (Class<? extends Module> klass : INTERACTIONS) {
                Module module = Modules.get().get(klass);
                if (module.isActive()) {
                    emergencyPausedInteractions.add(klass);
                    module.toggle();
                }
            }
        }

        emergencyPausedAura.clear();
        for (Class<? extends Module> klass : AURAS) {
            Module module = Modules.get().get(klass);
            if (module.isActive() || wasAura.contains(klass)) {
                emergencyPausedAura.add(klass);
                if (module.isActive()) module.toggle();
            }
        }

        // The routine may already own the (global) baritone pause; only claim it
        // if it is actually pathing right now.
        emergencyPausedBaritone = PathManagers.get().isPathing();
        if (emergencyPausedBaritone) {
            PathManagers.get().pause();
        }
    }

    private void stopEmergency() {
        for (Class<? extends Module> klass : emergencyPausedInteractions) {
            Module module = Modules.get().get(klass);
            if (!module.isActive()) {
                module.toggle();
            }
        }
        emergencyPausedInteractions.clear();

        // Resume the auras we paused, plus any the routine eat paused but could
        // not resume because its cycle ended inside the emergency window.
        for (Class<? extends Module> klass : AURAS) {
            Module module = Modules.get().get(klass);
            boolean ours = emergencyPausedAura.contains(klass);
            boolean deferredRoutine = !autoEating && wasAura.contains(klass);
            if (!module.isActive() && (ours || deferredRoutine)) {
                module.toggle();
            }
        }
        emergencyPausedAura.clear();

        // The routine resume is fully handled above. Drop any still-pending
        // deferred resume so it can't double-toggle later. If a routine cycle is
        // still running, it owns wasAura and logs the resume at its own end.
        if (!autoEating) {
            wasAura.clear();
            resumeDelayTicks = 0;
        }

        if (emergencyPausedBaritone) {
            PathManagers.get().resume();
        } else if (wasBaritone && !autoEating) {
            wasBaritone = false;
            PathManagers.get().resume();
        }
        emergencyPausedBaritone = false;
    }

    private int computeCooldown() {
        int cooldown = cooldownTicks.get();
        if (eatFoodAlwaysEat && preventAlwaysEatSpam.get()) {
            cooldown = Math.max(cooldown, alwaysEatCooldown.get());
        }
        return cooldown;
    }

    private int getStackCount(LocalPlayer player, int slot) {
        return slot == SlotUtils.OFFHAND
            ? player.getOffhandItem().getCount()
            : player.getInventory().getItem(slot).getCount();
    }

    private FoodProperties getFoodComponent(LocalPlayer player, int slot) {
        ItemStack stack = slot == SlotUtils.OFFHAND
            ? player.getOffhandItem()
            : player.getInventory().getItem(slot);
        return stack.get(DataComponents.FOOD);
    }

    private int findSlot(LocalPlayer player, boolean emergency) {
        boolean hungerNotFull = player.getFoodData().needsFood();

        int bestSlot = -1;
        int bestNutrition = -1;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            Item item = stack.getItem();
            FoodProperties food = item.components().get(DataComponents.FOOD);
            if (food == null) continue;
            if (blacklist.get().contains(item)) continue;
            if (!hungerNotFull && !food.canAlwaysEat() && !emergency) continue;

            if (food.nutrition() > bestNutrition) {
                bestSlot = i;
                bestNutrition = food.nutrition();
            }
        }

        Item offItem = player.getOffhandItem().getItem();
        FoodProperties offFood = offItem.components().get(DataComponents.FOOD);

        // Emergency: offhand is preferred outright whenever it holds food, it is
        // the fastest possible start (no hotbar swap, no animation cancel)
        if (emergency && offFood != null && !blacklist.get().contains(offItem)) {
            return SlotUtils.OFFHAND;
        }

        if (offFood != null && !blacklist.get().contains(offItem)
            && (hungerNotFull || offFood.canAlwaysEat())
            && offFood.nutrition() > bestNutrition) {
            bestSlot = SlotUtils.OFFHAND;
        }

        return bestSlot;
    }

    private boolean belowThreshold(LocalPlayer player) {
        boolean health = player.getHealth() <= healthThreshold.get();
        boolean hunger = player.getFoodData().getFoodLevel() <= hungerThreshold.get();
        return thresholdMode.get().test(health, hunger);
    }

    private boolean shouldEat(LocalPlayer player) {
        if (!belowThreshold(player)) {
            // Both stats have recovered above their thresholds, so the client is
            // no longer lagging behind a meal. Clear the anti-double-eat latch:
            // leaving it set would make the next cycle compare against this healed
            // baseline and stall in Both mode, where health rarely drops below it
            // at the same time hunger does.
            if (lastCycleHealth != -1) {
                lastCycleHealth = -1;
                lastCycleHunger = -1;
            }
            return false;
        }

        // Anti-double-eat: the client hunger/health values lag the server.
        if (lastCycleHealth != -1) {
            boolean healthWorse = player.getHealth() < lastCycleHealth;
            boolean hungerWorse = player.getFoodData().getFoodLevel() < lastCycleHunger;
            switch (thresholdMode.get()) {
                case Health -> {
                    if (!healthWorse) return false;
                }
                case Hunger -> {
                    if (!hungerWorse) return false;
                }
                case Any -> {
                    if (!healthWorse && !hungerWorse) return false;
                }
                case Both -> {
                    if (!healthWorse || !hungerWorse) return false;
                }
            }
        }

        return true;
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
