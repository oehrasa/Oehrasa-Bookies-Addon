package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.ItemStackTooltipEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
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

    public final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Print debug info in chat.")
        .defaultValue(false)
        .build()
    );

    // NBT-key–based caches
    private final Map<String, CachedContainerData> bundleCache = new HashMap<>();
    private final Map<String, CachedContainerData> shulkerCache = new HashMap<>();

    // Secondary lookup: object identity to nbt-key, to avoid serializing every frame
    private final Map<ItemStack, String> identityToKey = new HashMap<>();

    private static class CachedContainerData {
        // count=1 already applied at construction so the render path never needs .copy()
        final ItemStack previewStack;
        final boolean hasMultiple;

        CachedContainerData(ItemStack previewStack, boolean hasMultiple) {
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

    // typed as ClientLevel so comparison is a single reference check, no allocation
    private ClientLevel lastWorld = null;

    // clear on disconnect so stale keys is gone
    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        lastWorld = null;
        clearCaches();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // One reference comparison per tick
        if (mc.level != lastWorld) {
            lastWorld = mc.level;
            clearCaches();
        }
    }

    private void clearCaches() {
        bundleCache.clear();
        shulkerCache.clear();
        identityToKey.clear();
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
            if (debug.get()) info("Book overlay called for: " + stack.getHoverName().getString());
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

    // identity first (fast path), NBT serialize only on first encounter
    private String getCacheKey(ItemStack stack) {
        String cached = identityToKey.get(stack);
        if (cached != null) return cached;

        String key = computeCacheKey(stack);
        if (key != null) identityToKey.put(stack, key);
        return key;
    }

    private String computeCacheKey(ItemStack stack) {
        try {
            var ops = mc.level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
            Tag element = ItemStack.CODEC.encodeStart(ops, stack).getOrThrow();
            if (!(element instanceof CompoundTag full)) return null;
            if (full.isEmpty()) return null;
            return full.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean hasShulkerContents(ItemStack stack) {
        ItemContainerContents component = stack.get(DataComponents.CONTAINER);
        if (component != null) return true;

        // Cache hit means we already parsed this stack successfully before
        String key = getCacheKey(stack);
        if (key != null && shulkerCache.containsKey(key)) {
            if (debug.get()) info("Cache hit (key)");
            return true;
        }

        if (debug.get()) info("No container data for: " + stack.getHoverName().getString());
        return false;
    }

    private void renderBookOverlay(GuiGraphicsExtractor context, int x, int y, ItemStack stack) {
        WrittenBookContent book = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (book == null) return;

        int border = 1;
        int effectiveSize = iconSize.get() - 2 * border;
        int[] pos = computeIconPosition(x, y, effectiveSize, border);

        var matrices = context.pose();
        matrices.pushMatrix();
        matrices.translate(pos[0], pos[1]);
        drawBookInitials(context, book);
        matrices.popMatrix();
    }

    private void drawBookInitials(GuiGraphicsExtractor context, WrittenBookContent book) {
        String author = book.author();
        if (author == null || author.isEmpty()) author = "???";
        String initials = author.length() > 3
            ? author.substring(0, 3).toUpperCase()
            : author.toUpperCase();

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
                                        Map<String, CachedContainerData> cache, boolean isShulker) {
        // look up by stable NBT key, not object identity
        String cacheKey = getCacheKey(stack);
        CachedContainerData data = (cacheKey != null) ? cache.get(cacheKey) : null;

        if (data == null) {
            List<ItemStack> items;

            if (isShulker) {
                ItemContainerContents container = stack.get(DataComponents.CONTAINER);
                if (container == null) return;
                items = new ArrayList<>();
                // use stream API that returns ItemStack copies directly
                container.nonEmptyItemCopyStream().forEach(items::add);
            } else {
                BundleContents contents = stack.get(DataComponents.BUNDLE_CONTENTS);
                if (contents == null || contents.isEmpty()) return;
                items = new ArrayList<>();
                contents.itemCopyStream().forEach(items::add);
            }

            if (items.isEmpty()) return;

            // plain loop instead of stream this avoids allocating a distinct-key set
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
                    // plain loop instead of stream for dominant item
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

            // CachedContainerData constructor copies and sets count=1 internally
            data = new CachedContainerData(previewStack, hasMultiple);
            if (cacheKey != null) cache.put(cacheKey, data);
        }

        // no .copy() needed. constructor already produced a count=1 copy
        ItemStack displayStack = data.previewStack;

        int border = 1;
        int effectiveSize = iconSize.get() - 2 * border;
        float scale = effectiveSize / 16.0f;
        int[] pos = computeIconPosition(x, y, effectiveSize, border);
        int iconX = pos[0];
        int iconY = pos[1];

        var matrices = context.pose();

        // Draw the scaled overlay icon
        matrices.pushMatrix();
        matrices.translate(iconX, iconY);
        matrices.scale(scale, scale);

        if (displayStack.is(Items.FILLED_MAP)) {
            if (!drawMapIfPossible(context, displayStack)) {
                context.item(displayStack, 0, 0);
            }
        } else if (previewBooks.get() && displayStack.is(Items.WRITTEN_BOOK)) {
            // book initials draw inlined into the if-else chain
            WrittenBookContent book = displayStack.get(DataComponents.WRITTEN_BOOK_CONTENT);
            if (book != null) drawBookInitials(context, book);
        } else {
            context.item(displayStack, 0, 0);
        }

        matrices.popMatrix();

        // stack overlay rendered in its own unscaled matrix block so the count
        // badge is not stretched by the icon scale factor
        matrices.pushMatrix();
        matrices.translate(iconX, iconY);
        context.itemDecorations(mc.font, displayStack, 0, 0, null);
        matrices.popMatrix();

        // Multiple-type indicator text
        if (data.hasMultiple && !multipleText.get().isEmpty()) {
            String text = multipleText.get();
            int textWidth = mc.font.width(text);
            int textX = x + 16 - textWidth - 1;
            int textY = y + 1;
            context.text(mc.font, text, textX, textY, 0xFFFFFF00, true);
        }
    }

    // x/y are always 0 and scale is always 0.125, so params were redundant
    private boolean drawMapIfPossible(GuiGraphicsExtractor context, ItemStack mapStack) {
        if (!mapStack.is(Items.FILLED_MAP)) return false;
        MapId mapId = mapStack.get(DataComponents.MAP_ID);
        if (mapId == null) return false;
        MapItemSavedData mapState = MapItem.getSavedData(mapId, mc.level);
        if (mapState == null) return false;

        var matrices = context.pose();
        matrices.pushMatrix();
        matrices.scale(0.125F, 0.125F);

        MapRenderState renderState = new MapRenderState();
        mc.getMapRenderer().extractRenderState(mapId, mapState, renderState);
        context.map(renderState);

        matrices.popMatrix();
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
