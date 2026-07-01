package com.AutoBookshelf.addon.modules;

import baritone.api.BaritoneAPI;
import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.entity.player.ItemUseCrosshairTargetEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.BaritoneUtils;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.AnchorAura;
import meteordevelopment.meteorclient.systems.modules.combat.BedAura;
import meteordevelopment.meteorclient.systems.modules.combat.CrystalAura;
import meteordevelopment.meteorclient.systems.modules.combat.KillAura;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;

import java.util.ArrayList;
import java.util.List;

public class AutoPot extends Module {
    @SuppressWarnings("unchecked")
    private static final Class<? extends Module>[] AURAS = new Class[]{KillAura.class, CrystalAura.class, AnchorAura.class, BedAura.class};

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<List<StatusEffect>> usablePotions = sgGeneral.add(new StatusEffectListSetting.Builder()
        .name("potions-to-use")
        .description("The potions effect to use.")
        .defaultValue(
            StatusEffects.INSTANT_HEALTH.value(),
            StatusEffects.STRENGTH.value(),
            StatusEffects.SLOWNESS.value(),
            StatusEffects.BAD_OMEN.value()
        )
        .build()
    );

    private final Setting<Boolean> useSplashPots = sgGeneral.add(new BoolSetting.Builder()
        .name("splash-potions")
        .description("Allow the use of splash potions")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> health = sgGeneral.add(new IntSetting.Builder()
        .name("health")
        .description("If health goes below this point, Healing potions will trigger.")
        .defaultValue(15)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<Boolean> pauseAuras = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-auras")
        .description("Pauses all auras when eating.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseBaritone = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-baritone")
        .description("Pause baritone when eating.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> lookDown = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Forces you to rotate downwards when throwing splash potions.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoToggle = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-toggle")
        .description("Automatically disable the module after using a potion.")
        .defaultValue(false)
        .build()
    );

    private int slot;          // inventory slot (0–35) of the found potion
    private int prevSlot;      // hotbar slot before we swapped
    private boolean drinking, splashing;
    private final List<Class<? extends Module>> wasAura = new ArrayList<>();
    private boolean wasBaritone;
    private boolean isStopping = false;

    public AutoPot() {
        super(Addon.CATEGORY, "Auto-Pot", "Body of Effulgent Beryl!");
    }

    @Override
    public void onDeactivate() {
        stopPotionUsage();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        // If we're splashing and the potion is gone, stop
        if (splashing) {
            ItemStack held = mc.player.getMainHandStack();
            if (held.isEmpty() || held.getItem() != Items.SPLASH_POTION) {
                stopPotionUsage();
            }
            return;
        }
        // Drinking finished
        if (drinking && !mc.player.isUsingItem()) {
            stopPotionUsage();
            return;
        }
        if (mc.player.isUsingItem()) return;

        // Look for missing effects
        for (StatusEffect statusEffect : usablePotions.get()) {
            RegistryEntry<StatusEffect> registryEntry = Registries.STATUS_EFFECT.getEntry(statusEffect);
            if (!mc.player.hasStatusEffect(registryEntry)) {
                slot = findPotionSlot(statusEffect);
                if (slot != -1) {
                    if (registryEntry == StatusEffects.INSTANT_HEALTH && !shouldDrinkHealth()) return;
                    startPotionUse();
                    return;
                }
            }
        }
    }

    @EventHandler
    private void onItemUseCrosshairTarget(ItemUseCrosshairTargetEvent event) {
        if (drinking || splashing) {
            event.target = null;
        }
    }

    private void drink() {
        switchToPotionSlot();
        mc.options.useKey.setPressed(true);
        if (!mc.player.isUsingItem()) Utils.rightClick();
        drinking = true;
    }

    private void splash() {
        switchToPotionSlot();
        // Set client pitch to 90 (straight down) instantly, throw, then restore
        float previousPitch = mc.player.getPitch();
        if (lookDown.get()) {
            mc.player.setPitch(90f);
        }
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        mc.player.setPitch(previousPitch);   // restore original pitch immediately
        splashing = true;
    }

    private void stopPotionUsage() {
        if (isStopping) {
            if (prevSlot >= 0 && prevSlot <= 8) {
                mc.player.getInventory().setSelectedSlot(prevSlot);
            }
            mc.options.useKey.setPressed(false);
            drinking = false;
            splashing = false;
            return;
        }

        isStopping = true;

        // Restore original hotbar slot (yk these were less smoother because we are holding a button..)
        if (prevSlot >= 0 && prevSlot <= 8) {
            mc.player.getInventory().setSelectedSlot(prevSlot);
        }
        mc.options.useKey.setPressed(false);
        drinking = false;
        splashing = false;

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
            BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("resume");
        }

        if (autoToggle.get()) {
            toggle();
        }

        isStopping = false;
    }

    private double trueHealth() {
        assert mc.player != null;
        return mc.player.getHealth();
    }

    private boolean shouldDrinkHealth() {
        return trueHealth() < health.get();
    }

    // Full inventory search (0–35)
    private int findPotionSlot(StatusEffect statusEffect) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (isOminousBottle(stack)) {
                if (statusEffect == StatusEffects.BAD_OMEN.value()) return i;
                continue;
            }
            boolean isPotion = stack.getItem() == Items.POTION;
            boolean isSplashPotion = stack.getItem() == Items.SPLASH_POTION;
            if (!isPotion && !(isSplashPotion && useSplashPots.get())) continue;

            PotionContentsComponent effects = stack.getComponents().getOrDefault(DataComponentTypes.POTION_CONTENTS, PotionContentsComponent.DEFAULT);
            for (StatusEffectInstance effectInstance : effects.getEffects()) {
                if (effectInstance.getTranslationKey().equals(statusEffect.getTranslationKey())) {
                    return i;
                }
            }
        }
        return -1;
    }

    private void switchToPotionSlot() {
        prevSlot = mc.player.getInventory().getSelectedSlot();

        if (slot < 9) {
            mc.player.getInventory().setSelectedSlot(slot);
        } else {
            int tempSlot = -1;
            for (int i = 0; i < 9; i++) {
                if (mc.player.getInventory().getStack(i).isEmpty()) {
                    tempSlot = i;
                    break;
                }
            }
            if (tempSlot == -1) tempSlot = 0;

            mc.interactionManager.clickSlot(
                mc.player.playerScreenHandler.syncId,
                slot,
                tempSlot,
                SlotActionType.SWAP,
                mc.player
            );
            mc.player.getInventory().setSelectedSlot(tempSlot);
        }
    }

    private void startPotionUse() {
        prevSlot = mc.player.getInventory().getSelectedSlot();

        ItemStack stack = mc.player.getInventory().getStack(slot);
        boolean isSplashPotion = stack.getItem() == Items.SPLASH_POTION;

        if (isSplashPotion && useSplashPots.get()) {
            splash();
        } else {
            drink();
        }

        // Pause auras & Baritone
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
        wasBaritone = false;
        if (BaritoneUtils.IS_AVAILABLE) {
            if (pauseBaritone.get() && BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing()) {
                wasBaritone = true;
                BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("pause");
            }
        }
    }

    private boolean isOminousBottle(ItemStack stack) {
        return stack.getItem() == Items.OMINOUS_BOTTLE;
    }
}
