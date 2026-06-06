package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public class BundlePreview extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<PreviewMode> previewMode = sgGeneral.add(new EnumSetting.Builder<PreviewMode>()
        .name("preview-mode")
        .description("Which item to display as the overlay on bundles.")
        .defaultValue(PreviewMode.MostCommon)
        .build()
    );

    public final Setting<Integer> iconSize = sgGeneral.add(new IntSetting.Builder()
        .name("icon-size")
        .description("Size of the overlay icon (pixels).")
        .defaultValue(12)
        .min(4)
        .max(16)
        .sliderMin(4)
        .sliderMax(16)
        .build()
    );

    public final Setting<IconPosition> iconPosition = sgGeneral.add(new EnumSetting.Builder<IconPosition>()
        .name("icon-position")
        .description("Position of the overlay icon on the bundle slot.")
        .defaultValue(IconPosition.TopRight)
        .build()
    );

    public final Setting<String> multipleText = sgGeneral.add(new StringSetting.Builder()
        .name("multiple-indicator")
        .description("Text to show when the bundle contains multiple item types.")
        .defaultValue(" ")
        .build()
    );

    public final Setting<Integer> multipleSize = sgGeneral.add(new IntSetting.Builder()
        .name("multiple-size")
        .description("Font size of the multiple indicator text.")
        .defaultValue(8)
        .min(4)
        .max(16)
        .sliderMin(4)
        .sliderMax(16)
        .build()
    );

    private final WeakHashMap<ItemStack, CachedBundleData> bundleCache = new WeakHashMap<>();

    private static class CachedBundleData {
        final ItemStack previewStack;
        final boolean hasMultiple;

        CachedBundleData(ItemStack previewStack, boolean hasMultiple) {
            this.previewStack = previewStack;
            this.hasMultiple = hasMultiple;
        }
    }

    public enum PreviewMode {
        FirstItem,
        MostCommon
    }

    public enum IconPosition {
        BottomRight,
        BottomLeft,
        TopRight,
        TopLeft,
        Center
    }

    public BundlePreview() {
        super(Addon.CATEGORY, "bundle-preview", "Shows an item preview overlay on bundles.");
    }

    public void renderBundleOverlay(DrawContext context, int x, int y, ItemStack stack) {
        if (stack.isEmpty()) return;
        if (!stack.contains(DataComponentTypes.BUNDLE_CONTENTS)) return;

        CachedBundleData data = bundleCache.get(stack);
        if (data == null) {
            BundleContentsComponent contents = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
            if (contents == null || contents.isEmpty()) return;

            List<ItemStack> items = contents.stream().toList();
            if (items.isEmpty()) return;

            boolean hasMultiple = items.size() > 1;
            ItemStack previewStack = null;

            switch (previewMode.get()) {
                case FirstItem -> previewStack = items.get(0).copy();
                case MostCommon -> {
                    Map<Item, Integer> counts = new HashMap<>();
                    for (ItemStack s : items) counts.merge(s.getItem(), s.getCount(), Integer::sum);
                    Item dominant = counts.entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey)
                        .orElse(null);
                    if (dominant != null) {
                        previewStack = items.stream()
                            .filter(s -> s.getItem() == dominant)
                            .findFirst()
                            .map(ItemStack::copy)
                            .orElse(null);
                    }
                }
            }

            if (previewStack == null) return;
            data = new CachedBundleData(previewStack, hasMultiple);
            bundleCache.put(stack, data);
        }

        // Prepare display stack (count = 1 so no big number)
        ItemStack displayStack = data.previewStack.copy();
        displayStack.setCount(1);

        // Calculate position
        int border = 1;
        int effectiveSize = iconSize.get() - 2 * border;
        float scale = effectiveSize / 16.0f;
        int iconX, iconY;
        switch (iconPosition.get()) {
            case BottomLeft -> {
                iconX = x + border;
                iconY = y + 16 - effectiveSize - border;
            }
            case TopRight -> {
                iconX = x + 16 - effectiveSize - border;
                iconY = y + border;
            }
            case TopLeft -> {
                iconX = x + border;
                iconY = y + border;
            }
            default -> { // Center
                iconX = x + (16 - effectiveSize) / 2;
                iconY = y + (16 - effectiveSize) / 2;
            }
        }

        var matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.translate(iconX, iconY);
        matrices.scale(scale, scale);
        context.drawItem(displayStack, 0, 0);
        context.drawStackOverlay(mc.textRenderer, displayStack, 0, 0, "");
        matrices.popMatrix();

        // Multiple indicator
        if (data.hasMultiple && !multipleText.get().isEmpty()) {
            String text = multipleText.get();
            int textWidth = mc.textRenderer.getWidth(text);
            int textX = x + 16 - textWidth - 1;
            int textY = y + 1;
            context.drawText(mc.textRenderer, text, textX, textY, 0xFFFFFF00, true);
        }
    }
}
