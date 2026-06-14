package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.game.ItemStackTooltipEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.MapRenderState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.map.MapState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GetPreview extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<PreviewMode> previewMode = sgGeneral.add(new EnumSetting.Builder<PreviewMode>()
        .name("preview-mode")
        .description("Which item to display as the overlay on bundles/shulkers.")
        .defaultValue(PreviewMode.MostCommon)
        .build()
    );

    public final Setting<Integer> iconSize = sgGeneral.add(new IntSetting.Builder()
        .name("icon-size")
        .description("Size of the overlay icon in pixels.")
        .defaultValue(14)
        .min(4).max(16).sliderMin(4).sliderMax(16)
        .build()
    );

    public final Setting<IconPosition> iconPosition = sgGeneral.add(new EnumSetting.Builder<IconPosition>()
        .name("icon-position")
        .description("Position of the overlay icon on the slot.")
        .defaultValue(IconPosition.TopRight)
        .build()
    );

    public final Setting<String> multipleText = sgGeneral.add(new StringSetting.Builder()
        .name("multiple-indicator")
        .description("Text to show when the container contains multiple item types.")
        .defaultValue("+")
        .build()
    );

    public final Setting<Integer> multipleSize = sgGeneral.add(new IntSetting.Builder()
        .name("multiple-size")
        .description("Font size of the multiple indicator text.")
        .defaultValue(8).min(4).max(16).sliderMin(4).sliderMax(16)
        .build()
    );

    public final Setting<Boolean> previewShulkers = sgGeneral.add(new BoolSetting.Builder()
        .name("preview-shulkers")
        .description("Also show a preview icon on shulker boxes.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> previewBooks = sgGeneral.add(new BoolSetting.Builder()
        .name("preview-books")
        .description("Show author initials as overlay on written books (and on bundles/shulkers containing them).")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> showMapIdInTooltip = sgGeneral.add(new BoolSetting.Builder()
        .name("show-map-id-tooltip")
        .description("Append map ID to the tooltip of filled maps.")
        .defaultValue(false)
        .build()
    );

    // Caching
    private final Map<ItemStack, CachedContainerData> bundleCache = new HashMap<>();
    private final Map<ItemStack, CachedContainerData> shulkerCache = new HashMap<>();
    private final Map<ItemStack, ContainerComponent> shulkerComponentCache = new HashMap<>();
    private final Map<String, ContainerComponent> nbtShulkerCache = new HashMap<>();

    private static class CachedContainerData {
        final ItemStack previewStack;
        final boolean hasMultiple;

        CachedContainerData(ItemStack previewStack, boolean hasMultiple) {
            this.previewStack = previewStack;
            this.hasMultiple = hasMultiple;
        }
    }

    public enum PreviewMode {FirstItem, MostCommon}

    public enum IconPosition {BottomRight, BottomLeft, TopRight, TopLeft, Center}

    public GetPreview() {
        super(Addon.CATEGORY, "Get-Preview", "Shows an item preview overlay on bundles, shulkers, and books.");
    }

    @Override
    public void onActivate() {
        clearCaches();
    }

    @Override
    public void onDeactivate() {
        clearCaches();
    }

    @EventHandler
    private void onWorldChange(TickEvent.Post event) {
        if (mc.world != null && mc.world.getRegistryKey() != lastWorldKey) {
            clearCaches();
            lastWorldKey = mc.world.getRegistryKey();
        }
    }

    private Object lastWorldKey;

    private void clearCaches() {
        bundleCache.clear();
        shulkerCache.clear();
        shulkerComponentCache.clear();
        nbtShulkerCache.clear();
    }

    public void renderBundleOverlay(DrawContext context, int x, int y, ItemStack stack) {
        if (stack.isEmpty()) return;

        if (previewShulkers.get() && isInsideContainerPeekRender()) {
            if (!stack.contains(DataComponentTypes.BUNDLE_CONTENTS) &&
                !stack.contains(DataComponentTypes.WRITTEN_BOOK_CONTENT)) {
                return;
            }
        }

        // 1. Written book
        if (previewBooks.get() && stack.contains(DataComponentTypes.WRITTEN_BOOK_CONTENT)) {
            renderBookOverlay(context, x, y, stack);
            return;
        }

        // 2. Bundles
        if (stack.contains(DataComponentTypes.BUNDLE_CONTENTS)) {
            renderContainerOverlay(context, x, y, stack, bundleCache, false);
        }
        // 3. Shulker boxes
        else if (previewShulkers.get() && hasShulkerContents(stack)) {
            renderContainerOverlay(context, x, y, stack, shulkerCache, true);
        }
    }

    private boolean isInsideContainerPeekRender() {
        var peek = Modules.get().get(ContainerPeek.class);
        return peek != null && peek.isActive() && peek.isShulkerIconPreviewEnabled()
            && ContainerPeek.IS_RENDERING.get();
    }

    private boolean hasShulkerContents(ItemStack stack) {
        ContainerComponent component = stack.get(DataComponentTypes.CONTAINER);
        if (component != null) {
            shulkerComponentCache.put(stack, component);
            String key = getShulkerNbtKey(stack);
            if (key != null) nbtShulkerCache.put(key, component);
            return true;
        }

        component = shulkerComponentCache.get(stack);
        if (component != null) return true;

        String key = getShulkerNbtKey(stack);
        if (key != null) {
            component = nbtShulkerCache.get(key);
            if (component != null) {
                shulkerComponentCache.put(stack, component);
                return true;
            }
        }
        return false;
    }

    private String getShulkerNbtKey(ItemStack stack) {
        try {
            var ops = mc.world.getRegistryManager().getOps(NbtOps.INSTANCE);
            NbtElement element = ItemStack.CODEC.encodeStart(ops, stack).getOrThrow();
            if (!(element instanceof NbtCompound full)) return null;
            NbtCompound components = full.getCompound("components").orElse(null);
            if (components == null) return null;
            NbtCompound container = components.getCompound("minecraft:container").orElse(null);
            if (container != null && !container.isEmpty()) {
                return container.asString().orElse(null);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void renderBookOverlay(DrawContext context, int x, int y, ItemStack stack) {
        WrittenBookContentComponent book = stack.get(DataComponentTypes.WRITTEN_BOOK_CONTENT);
        if (book == null) return;

        int border = 1;
        int effectiveSize = iconSize.get() - 2 * border;
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
            default -> {
                iconX = x + (16 - effectiveSize) / 2;
                iconY = y + (16 - effectiveSize) / 2;
            }
        }

        var matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.translate(iconX, iconY);
        drawBookInitials(context, book);
        matrices.popMatrix();
    }

    private void drawBookInitials(DrawContext context, WrittenBookContentComponent book) {
        String author = book.author();
        if (author == null || author.isEmpty()) author = "???";
        String initials = author.length() > 3 ? author.substring(0, 3).toUpperCase() : author.toUpperCase();

        float textScale = 0.6f;
        int fullSize = iconSize.get() - 4;
        int textColor = 0xFFFFFFFF;

        var matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.scale(textScale, textScale);

        int textWidth = mc.textRenderer.getWidth(initials);
        int textHeight = mc.textRenderer.fontHeight;
        int textX = (int) ((fullSize / textScale - textWidth) / 2);
        int textY = (int) ((fullSize / textScale - textHeight) / 2);

        context.drawText(mc.textRenderer, initials, textX, textY, textColor, true);
        matrices.popMatrix();
    }

    private void renderContainerOverlay(DrawContext context, int x, int y, ItemStack stack,
                                        Map<ItemStack, CachedContainerData> cache, boolean isShulker) {
        CachedContainerData data = cache.get(stack);
        if (data == null) {
            List<ItemStack> items;
            if (isShulker) {
                ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
                if (container == null) container = shulkerComponentCache.get(stack);
                if (container == null) return;
                items = new ArrayList<>();
                for (ItemStack s : container.iterateNonEmpty()) items.add(s.copy());
            } else {
                BundleContentsComponent contents = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
                if (contents == null || contents.isEmpty()) return;
                items = contents.stream().toList();
            }
            if (items.isEmpty()) return;

            boolean hasMultiple = items.stream().map(ItemStack::getItem).distinct().count() > 1;
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
            data = new CachedContainerData(previewStack, hasMultiple);
            cache.put(stack, data);
        }

        ItemStack displayStack = data.previewStack.copy();
        displayStack.setCount(1);

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
            default -> {
                iconX = x + (16 - effectiveSize) / 2;
                iconY = y + (16 - effectiveSize) / 2;
            }
        }

        var matrices = context.getMatrices();

        // Draw the scaled overlay icon
        matrices.pushMatrix();
        matrices.translate(iconX, iconY);
        matrices.scale(scale, scale);

        if (displayStack.isOf(Items.FILLED_MAP)) {
            if (!drawMapIfPossible(context, displayStack, 0, 0, 1.0f)) {
                context.drawItem(displayStack, 0, 0);
            }
        } else if (!(previewBooks.get() && displayStack.isOf(Items.WRITTEN_BOOK))) {
            // Normal item
            context.drawItem(displayStack, 0, 0);
        }

        // Stack overlay for non-book items
        if (!(previewBooks.get() && displayStack.isOf(Items.WRITTEN_BOOK))) {
            context.drawStackOverlay(mc.textRenderer, displayStack, 0, 0, null);
        }
        matrices.popMatrix();

        // For written books, draw author initials at full size
        if (previewBooks.get() && displayStack.isOf(Items.WRITTEN_BOOK)) {
            WrittenBookContentComponent book = displayStack.get(DataComponentTypes.WRITTEN_BOOK_CONTENT);
            if (book != null) {
                matrices.pushMatrix();
                matrices.translate(iconX, iconY);
                drawBookInitials(context, book);
                matrices.popMatrix();
            }
        }

        if (data.hasMultiple && !multipleText.get().isEmpty()) {
            String text = multipleText.get();
            int textWidth = mc.textRenderer.getWidth(text);
            int textX = x + 16 - textWidth - 1;
            int textY = y + 1;
            context.drawText(mc.textRenderer, text, textX, textY, 0xFFFFFF00, true);
        }
    }

    private boolean drawMapIfPossible(DrawContext context, ItemStack mapStack, int x, int y, float scale) {
        if (!mapStack.isOf(Items.FILLED_MAP)) return false;
        MapIdComponent mapId = mapStack.get(DataComponentTypes.MAP_ID);
        if (mapId == null) return false;
        MapState mapState = FilledMapItem.getMapState(mapId, mc.world);
        if (mapState == null) return false;

        var matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.translate(x, y);
        // Map texture is 128x128, scale to fit the overlay area: 16 * scale gives the desired pixel size
        matrices.scale(0.125F * scale, 0.125F * scale);

        MapRenderState renderState = new MapRenderState();
        mc.getMapRenderer().update(mapId, mapState, renderState);
        context.drawMap(renderState);

        matrices.popMatrix();
        return true;
    }

    @EventHandler
    private void getTooltipData(ItemStackTooltipEvent event) {
        if (!showMapIdInTooltip.get()) return;
        if (event.itemStack().getItem() != Items.FILLED_MAP) return;
        MapIdComponent mapIdComponent = event.itemStack().get(DataComponentTypes.MAP_ID);
        if (mapIdComponent == null) return;

        String idText = "#" + mapIdComponent.id();
        boolean alreadyPresent = event.list().stream()
            .anyMatch(line -> line.getString().contains(idText));
        if (!alreadyPresent) {
            event.appendStart(Text.literal(idText));
        }
    }
}
