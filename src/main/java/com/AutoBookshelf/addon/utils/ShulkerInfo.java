/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only AND MIT AND Apache-2.0
 * Portions adapted from PathSeeker by FaxHack (https://github.com/FaxHack/PathSeeker),
 * a Meteor port of shulker-view by cattyn (https://github.com/cattyngmd/shulker-view).
 * See third-party/licenses and THIRD_PARTY_NOTICES.md.
 */
package com.AutoBookshelf.addon.utils;

import com.AutoBookshelf.addon.modules.InventoryInfo;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.ARGB;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
//? if >=26.1 {
import net.minecraft.world.item.ItemStackTemplate;
//?}
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.ArrayList;
import java.util.List;

public record ShulkerInfo(String name, Type type, int color, int slot, List<ItemStack> stacks) {

    /**
     * Vanilla's uncoloured shulker box, used for the base shulker and any dyed colour
     * with no mapping.
     */
    private static final int SHULKER_BOX_DEFAULT = 0xff9953b0;

    public static ShulkerInfo create(ItemStack stack, int slot) {
        if (!(stack.getItem() instanceof BlockItem bi) || !(bi.getBlock() instanceof ShulkerBoxBlock block))
            return null;

        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        if (container == null) return null;

        // Collect all items from the container – nonEmptyItems() returns List<ItemStackTemplate>
        List<ItemStack> items = new ArrayList<>();
        //? if >=26.1 {
        for (ItemStackTemplate template : container.nonEmptyItems()) {
            items.add(template.create());
        }
        //?} else {
        /*for (ItemStack stored : container.nonEmptyItemsCopy()) {
            items.add(stored);
        }
        *///?}

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

        int color = shulkerColor(block);

        return new ShulkerInfo(stack.getHoverName().getString(), type, color, slot, items);
    }

    /**
     * The bar colour for the grid header.
     *
     * <p>{@code MapColor#col} is 24 bit RGB with no alpha, so handing it to a fill
     * straight out drew a fully transparent bar - which is why every dyed shulker came
     * out with no header at all while the undyed one, falling back to {@code -1}, drew
     * opaque white. Alpha has to be forced on, or the colour is lost. Vanilla does the
     * same in {@code MapColor#calculateARGBColor}.
     */
    private static int shulkerColor(ShulkerBoxBlock block) {
        if (block.getColor() == null) return SHULKER_BOX_DEFAULT;
        return ARGB.opaque(block.getColor().getMapColor().col);
    }
}
