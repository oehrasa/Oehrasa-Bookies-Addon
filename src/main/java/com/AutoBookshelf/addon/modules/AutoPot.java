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
import meteordevelopment.meteorclient.utils.player.SlotUtils;
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

    /**
     * How long we wait for a started drink to actually show up before giving up on it.
     */
    private static final int DRINK_START_TIMEOUT_TICKS = 10;

    /**
     * Ticks a thrown potion gets to land and apply its effect before we may throw another.
     */
    private static final int SPLASH_SETTLE_TICKS = 15;

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

    private final Setting<Boolean> useLingeringPots = sgGeneral.add(new BoolSetting.Builder()
        .name("lingering-potions")
        .description("Allow the use of lingering potions.")
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
        .description("Forces you to rotate downwards when throwing splash or lingering potions.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoToggle = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-toggle")
        .description("Automatically disable the module after using a potion.")
        .defaultValue(false)
        .build()
    );

    private int slot;          // inventory slot (0-35) of the found potion
    private int prevSlot = -1;  // hotbar slot before we swapped
    /**
     * The hotbar slot a potion was pulled INTO, and the inventory slot it came from, or -1 when
     * no swap happened (the potion was already in the hotbar).
     *
     * <p>{@link #prevSlot} alone was not enough. It records the *selected* slot, not the items,
     * and {@code findPotionSlot} scans all 36 slots - so whenever the potion lived in the main
     * inventory the module physically SWAPped it into a hotbar slot and then only ever restored
     * the selection. The potion (or the bottle it became) stayed in the hotbar and whatever had
     * been in that slot was pushed into the inventory, permanently and silently.
     */
    private int swapSlot = -1;
    private int swapSourceSlot = -1;
    private boolean drinking, splashing;
    /**
     * True once the drink has been observed in progress. A tick where the use has not begun yet
     * must not be read as "drinking finished", or the module swaps the potion back and starts
     * over: the reported "keeps swapping back before it finishes drinking" loop.
     */
    private boolean drinkStarted;
    private int drinkWaitTicks;
    /**
     * Ticks left in the throw's settle window.
     */
    private int splashTicks;
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
        // Deferred restoration: if an inventory-sourced swap was skipped because a container was
        // open, retry once the inventory menu is active again. Skipped while a drink/throw is in
        // flight: the potion is still in the swap slot then, and pulling it out mid-use would
        // interrupt the potion. stopPotionUsage() restores the swap once the use ends.
        if (swapSlot >= 0 && !drinking && !splashing
            && mc.player.currentScreenHandler == mc.player.playerScreenHandler) {
            restoreSwappedPotion();
        }

        // A thrown potion lands and applies its effect a few ticks later, and an instant effect
        // (Instant Health) never shows up as a mob effect at all, so the phase is ended by a tick
        // window rather than by the potion leaving the hand. Ending it as soon as the hand was
        // clear made the next tick read "effect still missing" and throw a second potion at the
        // same moment - one wasted per use.
        if (splashing) {
            if (++splashTicks >= SPLASH_SETTLE_TICKS) stopPotionUsage();
            return;
        }
        // Drinking: hold the potion in place until the use has genuinely run its course. Only a
        // tick after the use was seen in progress counts as "finished".
        if (drinking) {
            if (mc.player.isUsingItem()) {
                drinkStarted = true;
            } else if (drinkStarted || ++drinkWaitTicks > DRINK_START_TIMEOUT_TICKS) {
                stopPotionUsage();
            }
            return;
        }
        if (mc.player.isUsingItem()) return;

        // Look for missing effects
        for (StatusEffect statusEffect : usablePotions.get()) {
            RegistryEntry<StatusEffect> registryEntry = Registries.STATUS_EFFECT.getEntry(statusEffect);
            if (!mc.player.hasStatusEffect(registryEntry)) {
                slot = findPotionSlot(statusEffect);
                if (slot != -1) {
                    // Health is fine: skip this entry rather than aborting the scan, so the
                    // Strength/Slowness/Bad Omen entries after it still get a chance. An instant
                    // effect never shows up in hasStatusEffect(), so this branch is reached every tick.
                    if (registryEntry == StatusEffects.INSTANT_HEALTH && !shouldDrinkHealth()) continue;
                    startPotionUse();
                    return;
                }
            }
        }
    }

    @EventHandler
    private void onItemUseCrosshairTarget(ItemUseCrosshairTargetEvent event) {
        // Only the drink needs this: it holds the use key down, so vanilla's own hold-fire can
        // fire a use on the tick the drink ends, and the nulled target keeps that from placing a
        // block. A throw goes through gameMode.useItem, which never reads the crosshair, so
        // nulling it while splashing only swallowed the player's own clicks for the whole window.
        if (drinking) {
            event.target = null;
        }
    }

    /**
     * @return false when the swap was deferred or the hand is not a potion, so nothing started.
     */
    private boolean drink() {
        if (!switchToPotionSlot()) return false;

        // With a container open the swap is deferred, so the hand can still hold the previous
        // item. Starting a use on that would drink nothing and drop us into the retry loop.
        if (!isDrinkable(mc.player.getMainHandStack())) return false;

        // Start the drink here rather than through Utils.rightClick(): that flag is only consumed
        // after TickEvent.Pre, and vanilla's startUseItem() refuses to begin a use while another
        // item is mid-use (a drawn bow, another module). The potion then sat in the hotbar never
        // drunk, the next tick read as "finished", and the hotbar swapped back before the drink
        // had started.
        if (mc.player.isUsingItem()) mc.interactionManager.stopUsingItem(mc.player);
        mc.options.useKey.setPressed(true);
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);

        drinking = true;
        drinkStarted = false;
        drinkWaitTicks = 0;
        return true;
    }

    /**
     * @return false when the swap was deferred or the hand is not a throwable potion.
     */
    private boolean splash() {
        if (!switchToPotionSlot()) return false;
        if (!isThrownPotion(mc.player.getMainHandStack())) return false;

        // Set client pitch to 90 (straight down) instantly, throw, then restore
        float previousPitch = mc.player.getPitch();
        if (lookDown.get()) {
            mc.player.setPitch(90f);
        }
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        mc.player.setPitch(previousPitch);   // restore original pitch immediately
        splashing = true;
        splashTicks = 0;
        return true;
    }

    private void stopPotionUsage() {
        if (isStopping) {
            restoreHotbar();
            mc.options.useKey.setPressed(false);
            drinking = false;
            splashing = false;
            drinkStarted = false;
            drinkWaitTicks = 0;
            splashTicks = 0;
            return;
        }

        isStopping = true;

        restoreHotbar();
        mc.options.useKey.setPressed(false);
        drinking = false;
        splashing = false;
        drinkStarted = false;
        drinkWaitTicks = 0;
        splashTicks = 0;

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

    // Full inventory search (0-35)
    private int findPotionSlot(StatusEffect statusEffect) {
        // When a container (chest, furnace, ...) is open, the main inventory slots (9-35) are
        // addressed through that container's menu, not the player's inventory menu, so a SWAP
        // against them would target the wrong slots. Only hotbar slots (0-8) are safe to use
        // then; main-inventory potions are deferred until the inventory menu is active again.
        boolean inventoryOpen = mc.player.currentScreenHandler == mc.player.playerScreenHandler;
        int limit = inventoryOpen ? 36 : 9;
        for (int i = 0; i < limit; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (isOminousBottle(stack)) {
                if (statusEffect == StatusEffects.BAD_OMEN.value()) return i;
                continue;
            }
            boolean isPotion = stack.getItem() == Items.POTION;
            if (!isPotion && !isEnabledThrownPotion(stack)) continue;

            PotionContentsComponent effects = stack.getComponents().getOrDefault(DataComponentTypes.POTION_CONTENTS, PotionContentsComponent.DEFAULT);
            for (StatusEffectInstance effectInstance : effects.getEffects()) {
                if (effectInstance.getTranslationKey().equals(statusEffect.getTranslationKey())) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Puts the inventory back the way it was found: undoes the item swap first (so the player is
     * never left holding a different item than the one they were holding when the selection is
     * restored), then restores the previously selected hotbar slot.
     */
    private void restoreHotbar() {
        restoreSwappedPotion();
        if (prevSlot >= 0 && prevSlot <= 8) {
            mc.player.getInventory().setSelectedSlot(prevSlot);
        }
        // Clear it: a stale prevSlot would fire on the NEXT restore (including the one in
        // onDeactivate), yanking the selection back to a slot the module returned to long ago.
        prevSlot = -1;
    }

    /**
     * Swaps the potion (or the bottle it became) back out of the hotbar, returning whatever was
     * displaced when it was pulled in.
     *
     * <p>Guarded on the hotbar slot still holding a potion remnant. The player has ~1.6s of
     * drinking time to rearrange their inventory, and blindly swapping would relocate whatever
     * they put there. If the guard trips, the displaced item simply stays in the source slot,
     * misplaced, but not destroyed.
     */
    private void restoreSwappedPotion() {
        if (swapSlot < 0 || swapSourceSlot < 0) return;

        // Defer while a container is open: clickSlot needs the inventory menu's slot ids, which
        // only match when currentScreenHandler == playerScreenHandler.
        if (mc.player.currentScreenHandler != mc.player.playerScreenHandler) return;

        if (!isPotionRemnant(mc.player.getInventory().getStack(swapSlot))) {
            swapSlot = -1;
            swapSourceSlot = -1;
            return;
        }

        mc.interactionManager.clickSlot(
            mc.player.currentScreenHandler.syncId,
            SlotUtils.indexToId(swapSourceSlot),
            swapSlot,
            SlotActionType.SWAP,
            mc.player
        );
        swapSlot = -1;
        swapSourceSlot = -1;
    }

    /**
     * Anything this module may have left in the swapped-in slot: a potion, an ominous bottle (drunk
     * for Bad Omen, and still itself until the drink converts it), or the bottle either turned
     * into. Empty counts too.
     *
     * <p>The ominous bottle matters because the module swaps it in exactly like a potion: without
     * it here, an aborted or not-yet-converted drink leaves the bottle in the hotbar and the
     * displaced item stranded in its source slot.
     */
    private static boolean isPotionRemnant(ItemStack stack) {
        if (stack.isEmpty()) return true;
        return stack.isOf(Items.POTION)
            || stack.isOf(Items.SPLASH_POTION)
            || stack.isOf(Items.LINGERING_POTION)
            || stack.isOf(Items.OMINOUS_BOTTLE)
            || stack.isOf(Items.GLASS_BOTTLE);
    }

    /**
     * Selects (or swaps in) the potion slot.
     *
     * @return false when the swap had to be deferred because a container is open and the potion
     * lives in the main inventory, in which case the hand still holds the previous item and
     * the caller must not start a use.
     */
    private boolean switchToPotionSlot() {
        prevSlot = mc.player.getInventory().getSelectedSlot();

        if (slot < 9) {
            mc.player.getInventory().setSelectedSlot(slot);
            return true;
        }

        // Inventory-sourced swap requires the inventory menu to be active; if a container is
        // open, the slot ids would target the wrong container. Defer by skipping.
        if (mc.player.currentScreenHandler != mc.player.playerScreenHandler) return false;

        int tempSlot = -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) {
                tempSlot = i;
                break;
            }
        }
        // Hotbar full: displace slot 0. Safe only because restoreSwappedPotion() gives it back
        if (tempSlot == -1) tempSlot = 0;

        swapSlot = tempSlot;
        swapSourceSlot = slot;
        mc.interactionManager.clickSlot(
            mc.player.currentScreenHandler.syncId,
            SlotUtils.indexToId(slot),
            tempSlot,
            SlotActionType.SWAP,
            mc.player
        );
        mc.player.getInventory().setSelectedSlot(tempSlot);
        return true;
    }

    private void startPotionUse() {
        prevSlot = mc.player.getInventory().getSelectedSlot();

        ItemStack stack = mc.player.getInventory().getStack(slot);

        // Splash and lingering potions are both thrown (interactItem); only a regular potion is
        // drunk.
        boolean started = isEnabledThrownPotion(stack) ? splash() : drink();

        // Nothing actually started (the swap was deferred): leaving the auras paused here would
        // strand them, since only stopPotionUsage() resumes them and it is only reached while
        // drinking/splashing.
        if (!started) return;

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

    /**
     * Splash and lingering potions are both thrown; only a regular potion is drunk.
     */
    private static boolean isThrownPotion(ItemStack stack) {
        return stack.isOf(Items.SPLASH_POTION) || stack.isOf(Items.LINGERING_POTION);
    }

    /**
     * The two things this module drinks: a regular potion, or an ominous bottle.
     */
    private boolean isDrinkable(ItemStack stack) {
        return stack.isOf(Items.POTION) || isOminousBottle(stack);
    }

    /**
     * A thrown potion the player has allowed via the splash/lingering settings.
     */
    private boolean isEnabledThrownPotion(ItemStack stack) {
        if (stack.isOf(Items.SPLASH_POTION)) return useSplashPots.get();
        if (stack.isOf(Items.LINGERING_POTION)) return useLingeringPots.get();
        return false;
    }

    private boolean isOminousBottle(ItemStack stack) {
        return stack.getItem() == Items.OMINOUS_BOTTLE;
    }
}
