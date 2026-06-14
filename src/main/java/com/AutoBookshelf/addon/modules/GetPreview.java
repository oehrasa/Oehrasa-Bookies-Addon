package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.game.ItemStackTooltipEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

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
    private final Map<ItemStack, ItemContainerContents> shulkerComponentCache = new HashMap<>();
    private final Map<String, ItemContainerContents> nbtShulkerCache = new HashMap<>();

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
        if (mc.level != null && mc.level.dimension() != lastWorldKey) {
            clearCaches();
            lastWorldKey = mc.level.dimension();
        }
    }

    private Object lastWorldKey;

    private void clearCaches() {
        bundleCache.clear();
        shulkerCache.clear();
        shulkerComponentCache.clear();
        nbtShulkerCache.clear();
    }

    public void renderBundleOverlay(GuiGraphicsExtractor context, int x, int y, ItemStack stack) {
        if (stack.isEmpty()) return;

        if (previewShulkers.get() && isInsideContainerPeekRender()) {
            if (!stack.has(DataComponents.BUNDLE_CONTENTS) &&
                !stack.has(DataComponents.WRITTEN_BOOK_CONTENT)) {
                return;
            }
        }

        // 1. Written book
        if (previewBooks.get() && stack.has(DataComponents.WRITTEN_BOOK_CONTENT)) {
            renderBookOverlay(context, x, y, stack);
            return;
        }

        // 2. Bundles
        if (stack.has(DataComponents.BUNDLE_CONTENTS)) {
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
        if (shulkerComponentCache.containsKey(stack)) return true;
        ItemContainerContents component = stack.get(DataComponents.CONTAINER);
        if (component != null) {
            shulkerComponentCache.put(stack, component);
            return true;
        }
        return false;
    }

    private String getShulkerNbtKey(ItemStack stack) {
        try {
            var ops = mc.level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
            Tag element = ItemStack.CODEC.encodeStart(ops, stack).getOrThrow();
            if (!(element instanceof CompoundTag full)) return null;
            CompoundTag components = full.getCompound("components").orElse(null);
            if (components == null) return null;
            CompoundTag container = components.getCompound("minecraft:container").orElse(null);
            if (container != null && !container.isEmpty()) {
                return container.asString().orElse(null);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void renderBookOverlay(GuiGraphicsExtractor context, int x, int y, ItemStack stack) {
        WrittenBookContent book = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
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

        var matrices = context.pose();
        matrices.pushMatrix();
        matrices.translate(iconX, iconY);
        drawBookInitials(context, book);
        matrices.popMatrix();
    }

    private void drawBookInitials(GuiGraphicsExtractor context, WrittenBookContent book) {
        String author = book.author();
        if (author == null || author.isEmpty()) author = "???";
        String initials = author.length() > 3 ? author.substring(0, 3).toUpperCase() : author.toUpperCase();

        float textScale = 0.6f;
        int fullSize = iconSize.get() - 4;
        int textColor = 0xFFFFFFFF;

        var matrices = context.pose();
        matrices.pushMatrix();
        matrices.scale(textScale, textScale);

        int textWidth = mc.font.width(initials);
        int textHeight = mc.font.lineHeight;
        int textX = (int) ((fullSize / textScale - textWidth) / 2);
        int textY = (int) ((fullSize / textScale - textHeight) / 2);

        context.text(mc.font, initials, textX, textY, textColor, true);
        matrices.popMatrix();
    }

    private void renderContainerOverlay(GuiGraphicsExtractor context, int x, int y, ItemStack stack,
                                        Map<ItemStack, CachedContainerData> cache, boolean isShulker) {
        CachedContainerData data = cache.get(stack);
        if (data == null) {
            List<ItemStack> items;
            if (isShulker) {
                ItemContainerContents container = stack.get(DataComponents.CONTAINER);
                if (container == null) container = shulkerComponentCache.get(stack);
                if (container == null) return;
                items = new ArrayList<>();
                for (ItemStackTemplate s : container.nonEmptyItems()) items.add(s.create());
            } else {
                BundleContents contents = stack.get(DataComponents.BUNDLE_CONTENTS);
                if (contents == null || contents.isEmpty()) return;
                items = new ArrayList<>();
                for (ItemStackTemplate template : contents.items()) {
                    items.add(template.create());
                }
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

        var matrices = context.pose();

        // Draw the scaled overlay icon
        matrices.pushMatrix();
        matrices.translate(iconX, iconY);
        matrices.scale(scale, scale);

        if (displayStack.is(Items.FILLED_MAP)) {
            if (!drawMapIfPossible(context, displayStack, 0, 0, 1.0f)) {
                context.item(displayStack, 0, 0);
            }
        } else if (!(previewBooks.get() && displayStack.is(Items.WRITTEN_BOOK))) {
            context.item(displayStack, 0, 0);
        }

        // Stack overlay
        if (!(previewBooks.get() && displayStack.is(Items.WRITTEN_BOOK))) {
            context.itemDecorations(mc.font, displayStack, 0, 0, "");
        }
        matrices.popMatrix();

        // For written books, draw author initials at full size
        if (previewBooks.get() && displayStack.is(Items.WRITTEN_BOOK)) {
            WrittenBookContent book = displayStack.get(DataComponents.WRITTEN_BOOK_CONTENT);
            if (book != null) {
                matrices.pushMatrix();
                matrices.translate(iconX, iconY);
                drawBookInitials(context, book);
                matrices.popMatrix();
            }
        }

        if (data.hasMultiple && !multipleText.get().isEmpty()) {
            String text = multipleText.get();
            int textWidth = mc.font.width(text);
            int textX = x + 16 - textWidth - 1;
            int textY = y + 1;
            context.text(mc.font, text, textX, textY, 0xFFFFFF00, true);
        }
    }

    private boolean drawMapIfPossible(GuiGraphicsExtractor context, ItemStack mapStack, int x, int y, float scale) {
        if (!mapStack.is(Items.FILLED_MAP)) return false;
        MapId mapId = mapStack.get(DataComponents.MAP_ID);
        if (mapId == null) return false;
        MapItemSavedData mapState = MapItem.getSavedData(mapId, mc.level);
        if (mapState == null) return false;

        var matrices = context.pose();
        matrices.pushMatrix();
        matrices.translate(x, y);
        matrices.scale(0.125F * scale, 0.125F * scale);

        MapRenderState renderState = new MapRenderState();
        mc.getMapRenderer().extractRenderState(mapId, mapState, renderState);
        context.map(renderState);

        matrices.popMatrix();
        return true;
    }

    @EventHandler
    private void getTooltipData(ItemStackTooltipEvent event) {
        if (!showMapIdInTooltip.get()) return;
        if (event.itemStack().getItem() != Items.FILLED_MAP) return;
        MapId mapIdComponent = event.itemStack().get(DataComponents.MAP_ID);
        if (mapIdComponent == null) return;

        String idText = "#" + mapIdComponent.id();
        boolean alreadyPresent = event.list().stream()
            .anyMatch(line -> line.getString().contains(idText));
        if (!alreadyPresent) {
            event.appendStart(Component.literal(idText));
        }
    }
}
