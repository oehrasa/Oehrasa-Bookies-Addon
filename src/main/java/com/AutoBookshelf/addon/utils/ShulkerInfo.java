package com.AutoBookshelf.addon.utils;

import com.AutoBookshelf.addon.modules.InventoryInfo;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ShulkerInfo(String name, Type type, int color, int slot, List<ItemStack> stacks) {

    public static ShulkerInfo create(ItemStack stack, int slot) {
        if (!(stack.getItem() instanceof BlockItem bi) || !(bi.getBlock() instanceof ShulkerBoxBlock block))
            return null;

        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        if (container == null) return null;

        // Collect all items from the container – nonEmptyItems() returns List<ItemStackTemplate>
        List<ItemStack> items = new ArrayList<>();
        for (ItemStackTemplate template : container.nonEmptyItems()) {
            items.add(template.create());
        }

        Type type = Modules.get().get(InventoryInfo.class).compact.get() ? Type.COMPACT : Type.FULL;

        if (type == Type.COMPACT) {
            Map<Item, Integer> merged = new LinkedHashMap<>();
            for (ItemStack item : items) {
                merged.merge(item.getItem(), item.getCount(), Integer::sum);
            }
            items.clear();
            for (Map.Entry<Item, Integer> entry : merged.entrySet()) {
                items.add(new ItemStack(entry.getKey(), entry.getValue()));
            }
        } else {
            while (items.size() < 27) items.add(ItemStack.EMPTY);
        }

        int color = -1;
        if (block.getColor() != null) {
            color = block.getColor().getMapColor().col;
        }

        return new ShulkerInfo(stack.getHoverName().getString(), type, color, slot, items);
    }
}
