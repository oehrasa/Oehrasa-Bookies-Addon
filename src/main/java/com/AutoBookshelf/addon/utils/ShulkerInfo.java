package com.AutoBookshelf.addon.utils;

import com.AutoBookshelf.addon.modules.InventoryInfo;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.ArrayList;
import java.util.List;

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
            // Merge stacks that are the same item AND have identical components
            // so merged stacks keep their component data (custom names, book
            // author/pages, enchantments, damage, etc.) for the tooltip.
            List<ItemStack> merged = new ArrayList<>();
            for (ItemStack item : items) {
                ItemStack rep = null;
                for (ItemStack candidate : merged) {
                    if (ItemStack.isSameItemSameComponents(candidate, item)) {
                        rep = candidate;
                        break;
                    }
                }
                if (rep == null) {
                    merged.add(item.copy());
                } else {
                    rep.setCount(rep.getCount() + item.getCount());
                }
            }
            items = merged;
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
