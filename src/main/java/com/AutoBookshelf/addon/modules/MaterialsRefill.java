package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.utils.PlacementEngine;
import com.AutoBookshelf.addon.utils.ShulkerRestockEngine;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;

public class MaterialsRefill extends Module {

    private final PlacementEngine placementEngine = new PlacementEngine(mc);

    private final ShulkerRestockEngine restockEngine = new ShulkerRestockEngine(mc, placementEngine,
        new ShulkerRestockEngine.RestockCallback() {
            @Override
            public void onInfo(String message) {
                info(message);
            }

            @Override
            public void onFinished(boolean success) {
                if (success) {
                    if (autoToggle.get()) toggle();
                } else {
                    // Same 20-tick backoff the old inline logic used before retrying checkStock().
                    retryCooldown = 20;
                }
            }
        });

    private int retryCooldown = 0;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgControls = settings.createGroup("Controls");

    private final Setting<List<Item>> targetItems = sgGeneral.add(new ItemListSetting.Builder()
        .name("target-items")
        .description("Items to keep stocked.")
        .defaultValue(new ArrayList<>())
        .build()
    );

    private final Setting<Integer> restockThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("restock-threshold")
        .description("Restock when total count falls below this number.")
        .defaultValue(16)
        .min(1)
        .sliderRange(1, 64)
        .build()
    );

    private final Setting<Integer> placeRange = sgGeneral.add(new IntSetting.Builder()
        .name("place-range")
        .description("How far the placement range is.")
        .defaultValue(2)
        .min(1)
        .sliderMax(5)
        .build()
    );

    private final Setting<Boolean> airPlace = sgGeneral.add(new BoolSetting.Builder()
        .name("air-place")
        .description("Place the shulker in mid-air.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> preferSolidBlock = sgGeneral.add(new BoolSetting.Builder()
        .name("prefer-solid-block")
        .description("When air place is on, try solid block positions first.")
        .defaultValue(true)
        .visible(airPlace::get)
        .build()
    );

    private final Setting<Boolean> breakAfterFill = sgGeneral.add(new BoolSetting.Builder()
        .name("break-after-fill")
        .description("Break the shulker after restocking. Disable to leave it placed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoTake = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-take")
        .description("Automatically take items from the shulker. If disabled, only open/close.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoToggle = sgGeneral.add(new BoolSetting.Builder()
        .name("toggle-off")
        .description("Automatically toggle the module off after breaking the shulker.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotate when placing / interacting.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> shulkerHotbarSlot = sgGeneral.add(new IntSetting.Builder()
        .name("shulker-hotbar-slot")
        .description("Hotbar slot (1‑9) used when moving the shulker from inventory.")
        .defaultValue(1)
        .min(1)
        .max(9)
        .sliderRange(1, 9)
        .build()
    );

    private final Setting<List<Item>> protectedItems = sgGeneral.add(new ItemListSetting.Builder()
        .name("protected-items")
        .description("Items in the hotbar that must never be swapped out.")
        .defaultValue(new ArrayList<>())
        .build()
    );

    @SuppressWarnings("unused")
    private final Setting<Keybind> setTargetFromHeld = sgControls.add(new KeybindSetting.Builder()
        .name("set-target-from-held")
        .description("Set the held item as the target item for restocking.")
        .defaultValue(Keybind.none())
        .action(() -> {
            if (!isActive()) return;
            ItemStack held = mc.player.getMainHandItem();
            if (!held.isEmpty()) {
                List<Item> current = new ArrayList<>(targetItems.get());
                if (!current.contains(held.getItem())) {
                    current.add(held.getItem());
                    targetItems.set(current);
                    info("Added target item: " + held.getItem().getName(held).getString());
                } else {
                    info("Item already in target list.");
                }
            }
        })
        .build()
    );

    public MaterialsRefill() {
        super(Addon.CATEGORY, "Mats-Refill",
            "Automatically restocks materials from shulker boxes.");
    }

    @Override
    public void onActivate() {
        retryCooldown = 0;
        restockEngine.reset();
    }

    @Override
    public void onDeactivate() {
        retryCooldown = 0;
        restockEngine.reset();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        if (restockEngine.isActive()) {
            restockEngine.tick();
            return;
        }

        if (retryCooldown > 0) {
            retryCooldown--;
            return;
        }

        checkStock();
    }

    private void checkStock() {
        List<Item> items = targetItems.get();
        if (items.isEmpty()) return;

        for (Item item : items) {
            if (InvUtils.find(item).count() < restockThreshold.get()) {
                restockEngine.start(item, buildConfig());
                return;
            }
        }
    }

    private ShulkerRestockEngine.RestockConfig buildConfig() {
        return new ShulkerRestockEngine.RestockConfig(
            placeRange.get(),
            airPlace.get(),
            preferSolidBlock.get(),
            breakAfterFill.get(),
            autoTake.get(),
            rotate.get(),
            shulkerHotbarSlot.get(),
            protectedItems.get(),
            List.<BlockPos>of()
        );
    }
}
