package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
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

    public final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Print debug info in chat.")
        .defaultValue(false)
        .build()
    );

    private final Map<String, CachedContainerData> bundleCache = new HashMap<>();
    private final Map<String, CachedContainerData> shulkerCache = new HashMap<>();

    // Secondary lookup: identity -> nbt-key, to avoid serializing every frame.
    private final Map<ItemStack, String> identityToKey = new HashMap<>();

    private boolean isRenderingPreview = false;

    private static class CachedContainerData {
        // store with count=1 already applied so we never copy() at render time.
        final ItemStack previewStack;
        final boolean hasMultiple;

        CachedContainerData(ItemStack previewStack, boolean hasMultiple) {
            // Ensure count is already 1 at construction time.
            ItemStack capped = previewStack.copy();
            capped.setCount(1);
            this.previewStack = capped;
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

    private net.minecraft.client.world.ClientWorld lastWorld = null;

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        lastWorld = null;
        clearCaches();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // One reference comparison per tick so no allocation, no NBT, no registry lookup.
        if (mc.world != lastWorld) {
            lastWorld = mc.world;
            clearCaches();
        }
    }

    private void clearCaches() {
        bundleCache.clear();
        shulkerCache.clear();
        identityToKey.clear();
        isRenderingPreview = false;
    }

    public void renderBundleOverlay(DrawContext context, int x, int y, ItemStack stack) {
        if (isRenderingPreview) return;

        if (stack.isEmpty()) return;

        if (previewShulkers.get() && isInsideContainerPeekRender()) {
            if (!stack.contains(DataComponentTypes.BUNDLE_CONTENTS) &&
                !stack.contains(DataComponentTypes.WRITTEN_BOOK_CONTENT)) {
                return;
            }
        }

        // 1. Written book to author initials overlay
        if (previewBooks.get() && stack.contains(DataComponentTypes.WRITTEN_BOOK_CONTENT)) {
            if (debug.get()) info("Book overlay called for: " + stack.getName().getString());
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

    private String getCacheKey(ItemStack stack) {
        // we've already computed a key for this object reference.
        String cached = identityToKey.get(stack);
        if (cached != null) return cached;

        String key = computeCacheKey(stack);
        if (key != null) identityToKey.put(stack, key);
        return key;
    }

    private String computeCacheKey(ItemStack stack) {
        try {
            NbtElement element = stack.toNbt(mc.world.getRegistryManager());
            if (!(element instanceof NbtCompound full)) return null;
            // Use the full component map as key for maximum correctness.
            NbtCompound components = full.getCompound("components");
            return components.isEmpty() ? null : components.asString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean hasShulkerContents(ItemStack stack) {
        ContainerComponent component = stack.get(DataComponentTypes.CONTAINER);
        if (component != null) return true;

        // Check if we already have a cache entry for this stack (component data may be absent client-side for stacks that have been seen before).
        String key = getCacheKey(stack);
        if (key != null && shulkerCache.containsKey(key)) {
            if (debug.get()) info("Cache hit (key)");
            return true;
        }

        if (debug.get()) info("No container data for: " + stack.getItem().getName().getString());
        return false;
    }

    private void renderBookOverlay(DrawContext context, int x, int y, ItemStack stack) {
        WrittenBookContentComponent book = stack.get(DataComponentTypes.WRITTEN_BOOK_CONTENT);
        if (book == null) return;

        int border = 1;
        int effectiveSize = iconSize.get() - 2 * border;
        int[] pos = computeIconPosition(x, y, effectiveSize, border);

        var matrices = context.getMatrices();
        matrices.push();
        matrices.translate(pos[0], pos[1], 300);
        // Canvas = effectiveSize around iconSize-2 pixels; inner textScale 0.6.
        drawBookInitials(context, book, iconSize.get() - 4, 0.6f);
        matrices.pop();
    }

    private void drawBookInitials(DrawContext context, WrittenBookContentComponent book,
                                  int canvasUnits, float textScale) {
        String author = book.author();
        if (author == null || author.isEmpty()) author = "???";
        String initials = (author.length() > 3 ? author.substring(0, 3) : author).toUpperCase();

        var matrices = context.getMatrices();
        matrices.push();
        matrices.scale(textScale, textScale, 1.0f);

        int textWidth = mc.textRenderer.getWidth(initials);
        int textHeight = mc.textRenderer.fontHeight;
        // In the textScale-subspace the canvas spans canvasUnits/textScale units.
        int textX = (int) ((canvasUnits / textScale - textWidth) / 2f);
        int textY = (int) ((canvasUnits / textScale - textHeight) / 2f);

        context.drawText(mc.textRenderer, initials, textX, textY, 0xFFFFFFFF, true);
        matrices.pop();
    }

    private void renderContainerOverlay(DrawContext context, int x, int y, ItemStack stack,
                                        Map<String, CachedContainerData> cache, boolean isShulker) {
        String cacheKey = getCacheKey(stack);
        CachedContainerData data = (cacheKey != null) ? cache.get(cacheKey) : null;

        if (data == null) {
            List<ItemStack> items;

            if (isShulker) {
                ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
                if (container == null) return;

                items = new ArrayList<>();
                for (ItemStack s : container.iterateNonEmpty()) items.add(s.copy());
            } else {
                BundleContentsComponent contents = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
                if (contents == null || contents.isEmpty()) return;
                items = contents.stream().toList();
            }

            if (items.isEmpty()) return;

            // Use plain loops instead of stream ops to avoid iterator/collector
            // allocations in the render path on a cache miss.
            boolean hasMultiple = false;
            {
                Item first = items.get(0).getItem();
                for (int i = 1; i < items.size(); i++) {
                    if (items.get(i).getItem() != first) {
                        hasMultiple = true;
                        break;
                    }
                }
            }

            ItemStack previewStack = null;
            switch (previewMode.get()) {
                case FirstItem -> previewStack = items.get(0);

                case MostCommon -> {
                    // Plain loop no stream, no collectors, no boxing beyond Integer.
                    Map<Item, Integer> counts = new HashMap<>();
                    for (ItemStack s : items)
                        counts.merge(s.getItem(), s.getCount(), Integer::sum);

                    Item dominant = null;
                    int max = -1;
                    for (Map.Entry<Item, Integer> entry : counts.entrySet()) {
                        if (entry.getValue() > max) {
                            max = entry.getValue();
                            dominant = entry.getKey();
                        }
                    }

                    if (dominant != null) {
                        for (ItemStack s : items) {
                            if (s.getItem() == dominant) {
                                previewStack = s;
                                break;
                            }
                        }
                    }
                }
            }

            if (previewStack == null) return;

            data = new CachedContainerData(previewStack, hasMultiple);
            if (cacheKey != null) cache.put(cacheKey, data);
        }

        ItemStack displayStack = data.previewStack;

        int border = 1;
        int effectiveSize = iconSize.get() - 2 * border;
        float scale = effectiveSize / 16.0f;
        int[] pos = computeIconPosition(x, y, effectiveSize, border);
        int iconX = pos[0];
        int iconY = pos[1];

        var matrices = context.getMatrices();
        matrices.push();
        matrices.translate(iconX, iconY, 200);
        matrices.scale(scale, scale, 1);

        isRenderingPreview = true;
        try {
            if (displayStack.isOf(Items.FILLED_MAP)) {
                if (!drawMapIfPossible(context, displayStack)) {
                    context.drawItem(displayStack, 0, 0);
                }
            } else {
                // Draw the item icon for all types, including written books.
                // Book author initials are drawn on top after this block.
                context.drawItem(displayStack, 0, 0);
            }
        } finally {
            isRenderingPreview = false;
        }

        if (previewBooks.get() && displayStack.isOf(Items.WRITTEN_BOOK)) {
            WrittenBookContentComponent book = displayStack.get(DataComponentTypes.WRITTEN_BOOK_CONTENT);
            if (book != null) drawBookInitials(context, book, 16, 0.6f);
        }

        matrices.pop();

        // Stack overlay (damage bar, count) is drawn in screen space, no inner scale.
        matrices.push();
        matrices.translate(iconX, iconY, 200);
        context.drawStackOverlay(mc.textRenderer, displayStack, 0, 0, null);
        matrices.pop();

        // Multiple-type indicator text.
        if (data.hasMultiple && !multipleText.get().isEmpty()) {
            String text = multipleText.get();
            int textWidth = mc.textRenderer.getWidth(text);
            int textX = x + 16 - textWidth - 1;
            int textY = y + 1;
            context.drawText(mc.textRenderer, text, textX, textY, 0xFFFFFF00, true);
        }
    }

    private boolean drawMapIfPossible(DrawContext context, ItemStack mapStack) {
        if (!mapStack.isOf(Items.FILLED_MAP)) return false;
        MapIdComponent mapId = mapStack.get(DataComponentTypes.MAP_ID);
        if (mapId == null) return false;
        MapState mapState = FilledMapItem.getMapState(mapId, mc.world);
        if (mapState == null) return false;

        var matrices = context.getMatrices();
        matrices.push();
        matrices.scale(0.125F, 0.125F, 1.0F);

        var renderState = new MapRenderState();
        var vertexConsumers = mc.getBufferBuilders().getEntityVertexConsumers();
        mc.getMapRenderer().update(mapId, mapState, renderState);
        mc.getMapRenderer().draw(renderState, matrices, vertexConsumers, false, 0xF000F0);

        vertexConsumers.draw();

        matrices.pop();
        return true;
    }

    private int[] computeIconPosition(int x, int y, int effectiveSize, int border) {
        return switch (iconPosition.get()) {
            case BottomRight -> new int[]{
                x + 16 - effectiveSize - border,
                y + 16 - effectiveSize - border
            };
            case BottomLeft -> new int[]{
                x + border,
                y + 16 - effectiveSize - border
            };
            case TopRight -> new int[]{
                x + 16 - effectiveSize - border,
                y + border
            };
            case TopLeft -> new int[]{
                x + border,
                y + border
            };
            case Center -> new int[]{
                x + (16 - effectiveSize) / 2,
                y + (16 - effectiveSize) / 2
            };
        };
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
