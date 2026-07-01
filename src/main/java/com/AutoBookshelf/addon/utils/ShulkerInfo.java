package com.AutoBookshelf.addon.utils;

import com.AutoBookshelf.addon.modules.InventoryInfo;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ShulkerInfo(String name, Type type, int color, int slot, List<ItemStack> stacks) {

    public static ShulkerInfo create(ItemStack stack, int slot) {
        if (!(stack.getItem() instanceof BlockItem bi) || !(bi.getBlock() instanceof ShulkerBoxBlock block))
            return null;

        ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
        if (container == null) return null;

        // Collect all items from the container
        List<ItemStack> items = new ArrayList<>();
        container.iterateNonEmpty().forEach(items::add);

        // Determine compact/full type
        Type type = Modules.get().get(InventoryInfo.class).compact.get() ? Type.COMPACT : Type.FULL;

        if (type == Type.COMPACT) {
            Map<Item, Integer> merged = new LinkedHashMap<>();
            for (ItemStack item : items) {
                merged.merge(item.getItem(), item.getCount(), Integer::sum);
            }
            items.clear();
            merged.forEach((item, count) -> items.add(new ItemStack(item, count)));
        } else {
            // Fill to 27 slots for full view
            while (items.size() < 27) items.add(ItemStack.EMPTY);
        }

        int color = -1;
        if (block.getColor() != null) {
            color = block.getColor().getMapColor().color;
        }

        return new ShulkerInfo(stack.getName().getString(), type, color, slot, items);
    }
}
