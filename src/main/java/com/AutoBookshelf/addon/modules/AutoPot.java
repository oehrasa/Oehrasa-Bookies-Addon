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
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;

import java.util.ArrayList;
import java.util.List;

public class AutoPot extends Module {
    @SuppressWarnings("unchecked")
    private static final Class<? extends Module>[] AURAS = new Class[]{KillAura.class, CrystalAura.class, AnchorAura.class, BedAura.class};

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<List<MobEffect>> usablePotions = sgGeneral.add(new StatusEffectListSetting.Builder()
        .name("potions-to-use")
        .description("The potions effect to use.")
        .defaultValue(
            MobEffects.INSTANT_HEALTH.value(),
            MobEffects.STRENGTH.value(),
            MobEffects.SLOWNESS.value(),
            MobEffects.BAD_OMEN.value()
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
            ItemStack held = mc.player.getMainHandItem();
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
        for (MobEffect statusEffect : usablePotions.get()) {
            Holder<MobEffect> registryEntry = BuiltInRegistries.MOB_EFFECT.wrapAsHolder(statusEffect);
            if (!mc.player.hasEffect(registryEntry)) {
                slot = findPotionSlot(statusEffect);
                if (slot != -1) {
                    if (registryEntry == MobEffects.INSTANT_HEALTH && !shouldDrinkHealth()) return;
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
        mc.options.keyUse.setDown(true);
        if (!mc.player.isUsingItem()) Utils.rightClick();
        drinking = true;
    }

    private void splash() {
        switchToPotionSlot();
        float previousPitch = mc.player.getXRot();
        if (lookDown.get()) {
            mc.player.setXRot(90f);
        }
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        mc.player.setXRot(previousPitch);
        splashing = true;
    }

    private void stopPotionUsage() {
        if (isStopping) {
            if (prevSlot >= 0 && prevSlot <= 8) {
                mc.player.getInventory().setSelectedSlot(prevSlot);
            }
            mc.options.keyUse.setDown(false);
            drinking = false;
            splashing = false;
            return;
        }

        isStopping = true;

        if (prevSlot >= 0 && prevSlot <= 8) {
            mc.player.getInventory().setSelectedSlot(prevSlot);
        }
        mc.options.keyUse.setDown(false);
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

    private int findPotionSlot(MobEffect statusEffect) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            if (isOminousBottle(stack)) {
                if (statusEffect == MobEffects.BAD_OMEN.value()) return i;
                continue;
            }
            boolean isPotion = stack.getItem() == Items.POTION;
            boolean isSplashPotion = stack.getItem() == Items.SPLASH_POTION;
            if (!isPotion && !(isSplashPotion && useSplashPots.get())) continue;

            PotionContents effects = stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
            for (MobEffectInstance effectInstance : effects.getAllEffects()) {
                if (effectInstance.getDescriptionId().equals(statusEffect.getDescriptionId())) {
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
                if (mc.player.getInventory().getItem(i).isEmpty()) {
                    tempSlot = i;
                    break;
                }
            }
            if (tempSlot == -1) tempSlot = 0;

            int slotId = SlotUtils.indexToId(slot);
            mc.gameMode.handleContainerInput(
                mc.player.inventoryMenu.containerId,
                slotId,
                tempSlot,
                ContainerInput.SWAP,
                mc.player
            );
            mc.player.getInventory().setSelectedSlot(tempSlot);
        }
    }

    private void startPotionUse() {
        prevSlot = mc.player.getInventory().getSelectedSlot();

        ItemStack stack = mc.player.getInventory().getItem(slot);
        boolean isSplashPotion = stack.getItem() == Items.SPLASH_POTION;

        if (isSplashPotion && useSplashPots.get()) {
            splash();
        } else {
            drink();
        }

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
