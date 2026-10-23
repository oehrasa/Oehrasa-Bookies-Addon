package com.AutoBookshelf.addon.utils;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A stable "what is this stack, exactly" key: item id plus its sorted
 * enchantment list, e.g. {@code minecraft:diamond_pickaxe|minecraft:silk_touch=1}.
 * <p>
 * ChestTracker indexes these so a search can match a <i>specific</i> enchanted
 * stack instead of every stack of that item. Two stacks with the same signature
 * are interchangeable; two with the same item id but different signatures are not.
 * <p>
 * Ported from BepHax's {@code bep.hax.util.ItemSignature}. Differences from that
 * original, both forced by 26.2 mappings and both deliberate:
 * <ul>
 *   <li>Enchantment ids come from {@code Holder#unwrapKey()} and are rendered via
 *       {@code ResourceKey#identifier()} (which returns a {@code net.minecraft.resources.Identifier},
 *       not a {@code ResourceLocation}). Holders with no registry key are skipped
 *       rather than emitted as a null id.</li>
 *   <li>Enchanted books read {@code DataComponentTypes.STORED_ENCHANTMENTS} instead of
 *       the book's own (always empty) enchantment component.</li>
 * </ul>
 */
public final class ItemSignature {
    private ItemSignature() {
    }

    public static String of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";

        String itemId = Registries.ITEM.getKey(stack.getItem()).toString();
        ItemEnchantmentsComponent ench = stack.getItem() == Items.ENCHANTED_BOOK
            ? stack.getOrDefault(DataComponentTypes.STORED_ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT)
            : stack.getEnchantments();

        List<String> parts = new ArrayList<>();
        for (var entry : ench.getEnchantmentEntries()) {
            entry.getKey().getKey().ifPresent(key -> parts.add(key.getValue() + "=" + entry.getValue()));
        }

        return format(itemId, parts);
    }

    /**
     * Builds a signature from an already-assembled list of {@code id=level} parts.
     */
    public static String format(String itemId, List<String> enchParts) {
        List<String> sorted = new ArrayList<>(enchParts);
        // Enchantment application order is not stable between stacks, so sort to
        // make the key order-independent.
        Collections.sort(sorted);
        return itemId + "|" + String.join(",", sorted);
    }
}
