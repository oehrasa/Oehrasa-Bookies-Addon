package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.modules.chesttracker.ChestTrackerDataV2;
import com.AutoBookshelf.addon.modules.chesttracker.ChestTrackerModule;
import com.AutoBookshelf.addon.modules.chesttracker.TrackedContainer;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.*;
import java.util.stream.Collectors;

public class ContainerPeek extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> maxDistance = sgGeneral.add(new IntSetting.Builder()
        .name("max-distance")
        .description("Maximum distance to show the container preview.")
        .defaultValue(8)
        .min(1)
        .max(16)
        .sliderRange(1, 16)
        .build()
    );

    private final Setting<Integer> iconSize = sgGeneral.add(new IntSetting.Builder()
        .name("icon-size")
        .description("Size of each item icon in the preview grid.")
        .defaultValue(14)
        .min(8)
        .max(20)
        .sliderRange(8, 20)
        .build()
    );

    private final Setting<Integer> maxItemsPerRow = sgGeneral.add(new IntSetting.Builder()
        .name("items-per-row")
        .description("Number of items per row in the preview.")
        .defaultValue(9)
        .min(1)
        .max(27)
        .sliderRange(1, 27)
        .build()
    );

    private final Setting<Boolean> showPosition = sgGeneral.add(new BoolSetting.Builder()
        .name("show-position")
        .description("Show the container's coordinates.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showType = sgGeneral.add(new BoolSetting.Builder()
        .name("show-type")
        .description("Show the container type (chest, barrel, etc.).")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgGeneral.add(new ColorSetting.Builder()
        .name("background-color")
        .description("Background color of the preview panel.")
        .defaultValue(new SettingColor(0, 0, 0, 0))
        .build()
    );

    private final Setting<SettingColor> borderColor = sgGeneral.add(new ColorSetting.Builder()
        .name("border-color")
        .description("Border color of the preview panel.")
        .defaultValue(new SettingColor(255, 255, 255, 0))
        .build()
    );

    private final Setting<Boolean> shulkerIconPreview = sgGeneral.add(new BoolSetting.Builder()
        .name("shulker-preview")
        .description("Display a compact icon for shulker boxes instead of the full grid.")
        .defaultValue(false)
        .build()
    );

    private final ChestTrackerDataV2 data = new ChestTrackerDataV2();
    private BlockPos lastTargetedPos;
    private TrackedContainer lastContainer;
    private Level lastWorld;

    private Entity lastEntity;
    private List<ItemStack> lastEntityItems;
    private String lastEntityType;

    private PreviewData currentPreview = null;

    public static final ThreadLocal<Boolean> IS_RENDERING = ThreadLocal.withInitial(() -> false);

    public boolean isShulkerIconPreviewEnabled() {
        return shulkerIconPreview.get();
    }

    private record PreviewData(
        BlockPos pos,
        Component titleText,
        Component posText,
        int panelWidth,
        int panelHeight,
        List<ItemStack> stacks,
        int itemsPerRow,
        float itemScale,
        Map<Integer, Item> dominantItems
    ) {}

    public ContainerPeek() {
        super(Addon.CATEGORY, "Container-Peek",
            "Displays the tracked contents from Chest-Tracker when you look at the block.");
    }

    @Override
    public void onActivate() {
        data.loadData();
        lastWorld = mc.level;
    }

    @Override
    public void onDeactivate() {
        lastTargetedPos = null;
        lastContainer = null;
        lastEntity = null;
        lastEntityItems = null;
        lastEntityType = null;
        currentPreview = null;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.level != lastWorld) {
            lastWorld = mc.level;
            if (mc.level != null) data.loadData();
            lastTargetedPos = null;
            lastContainer = null;
            lastEntity = null;
            lastEntityItems = null;
            lastEntityType = null;
            currentPreview = null;
        }

        if (mc.player == null || mc.level == null) return;

        if (lastEntity != null && (lastEntity.isRemoved() || !lastEntity.isAlive())) {
            lastEntity = null;
            lastEntityItems = null;
            lastEntityType = null;
            currentPreview = null;
        }

        // Block containers
        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK) {
            lastEntity = null;
            lastEntityItems = null;
            lastEntityType = null;

            BlockHitResult hit = (BlockHitResult) mc.hitResult;
            BlockPos pos = hit.getBlockPos();
            if (pos.equals(lastTargetedPos)) return;
            lastTargetedPos = pos;

            double dist = Math.sqrt(mc.player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
            if (dist > maxDistance.get()) {
                lastContainer = null;
                currentPreview = null;
                return;
            }

            Block block = mc.level.getBlockState(pos).getBlock();
            if (!(block instanceof ChestBlock || block instanceof BarrelBlock
                || block instanceof ShulkerBoxBlock || block instanceof EnderChestBlock)) {
                lastContainer = null;
                currentPreview = null;
                return;
            }

            String dimension = mc.level.dimension().identifier().toString();
            ChestTrackerModule tracker = Modules.get().get(ChestTrackerModule.class);
            lastContainer = (tracker != null && tracker.isActive())
                ? tracker.getData().getContainer(pos, dimension)
                : data.getContainer(pos, dimension);

            if (lastContainer != null) {
                buildContainerPreview(pos, lastContainer);
            } else {
                currentPreview = null;
            }
            return;
        }

        // Entity item frame
        if (mc.hitResult != null && mc.hitResult instanceof EntityHitResult entityHit) {
            Entity entity = entityHit.getEntity();
            if (entity == lastEntity) return;

            lastEntity = entity;
            lastEntityItems = null;
            lastEntityType = null;

            if (entity instanceof ItemFrame frame) {
                ItemStack stack = frame.getItem();
                if (stack.isEmpty()) {
                    currentPreview = null;
                    return;
                }

                // 1) Standard container component (shulker box)
                ItemContainerContents container = stack.get(DataComponents.CONTAINER);
                if (container != null) {
                    List<ItemStack> nonEmpty = container.nonEmptyItemCopyStream()
                        .map(ItemStack::copy)
                        .collect(Collectors.toList());
                    if (!nonEmpty.isEmpty()) {
                        lastEntityItems = nonEmpty;
                        lastEntityType = "shulker_box";
                        buildEntityPreview(entity, nonEmpty, "shulker_box");
                        return;
                    }
                }

                // 2) Bundle contents
                BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
                if (bundle != null && !bundle.isEmpty()) {
                    List<ItemStack> bundleStacks = new ArrayList<>();
                    bundle.itemCopyStream().forEach(s -> bundleStacks.add(s.copy()));
                    lastEntityItems = bundleStacks;
                    lastEntityType = "bundle";
                    buildEntityPreview(entity, bundleStacks, "bundle");
                    return;
                }
            }

            lastEntity = null;
            lastEntityItems = null;
            lastEntityType = null;
            currentPreview = null;
            return;
        }

        // No target
        lastTargetedPos = null;
        lastContainer = null;
        lastEntity = null;
        lastEntityItems = null;
        lastEntityType = null;
        currentPreview = null;
    }

    private void buildContainerPreview(BlockPos pos, TrackedContainer container) {
        List<ItemStack> stacks = container.getItemStacks();
        String title = container.getCustomName() != null ? container.getCustomName() : container.getContainerType();

        Map<Integer, Item> dominantMap = new HashMap<>();

        // From stored dominant item data
        for (Map.Entry<Integer, String> entry : container.getDominantItems().entrySet()) {
            Identifier id = Identifier.tryParse(entry.getValue());
            if (id == null) continue;
            Item item = BuiltInRegistries.ITEM.getValue(id);
            if (item != null) {
                dominantMap.put(entry.getKey(), item);
            }
        }

        // Recompute from stacks if needed
        dominantMap.putAll(computeDominantMap(stacks));

        buildPreview(pos, title, stacks, dominantMap);
    }

    private void buildEntityPreview(Entity entity, List<ItemStack> stacks, String type) {
        String title = type != null ? type : "container";
        BlockPos pos = entity.blockPosition();
        Map<Integer, Item> dominantMap = computeDominantMap(stacks);
        buildPreview(pos, title, stacks, dominantMap);
    }

    private Map<Integer, Item> computeDominantMap(List<ItemStack> stacks) {
        if (!shulkerIconPreview.get()) return Collections.emptyMap();
        Map<Integer, Item> map = new HashMap<>();
        for (int i = 0; i < stacks.size(); i++) {
            Item dominant = getDominantShulkerItem(stacks.get(i));
            if (dominant != null) {
                map.put(i, dominant);
            }
        }
        return map;
    }

    @Nullable
    private Item getDominantShulkerItem(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock)) return null;
        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        if (container == null) return null;
        Map<Item, Integer> counts = new HashMap<>();
        container.nonEmptyItemCopyStream().forEach(s -> counts.merge(s.getItem(), s.getCount(), Integer::sum));
        if (counts.isEmpty()) return null;
        return counts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);
    }

    private void buildPreview(BlockPos pos, String title, List<ItemStack> stacks, Map<Integer, Item> dominantMap) {
        int itemsPerRow = maxItemsPerRow.get();
        int total = stacks.size();
        int rows = (int) Math.ceil((double) total / itemsPerRow);
        int iconSizeVal = iconSize.get();
        int pad = 2;
        int w = itemsPerRow * iconSizeVal + (itemsPerRow + 1) * pad;
        int h = rows * iconSizeVal + (rows + 1) * pad;

        int textLines = 0;
        if (showType.get()) textLines++;
        if (showPosition.get()) textLines++;
        if (textLines > 0) h += 10 * textLines + pad;

        float scale = iconSizeVal / 16.0f;

        Component titleText = Component.literal(title);
        Component posText = Component.literal(String.format("%d, %d, %d", pos.getX(), pos.getY(), pos.getZ()));

        currentPreview = new PreviewData(pos, titleText, posText, w, h, stacks, itemsPerRow, scale, dominantMap);
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        PreviewData preview = this.currentPreview;
        if (preview == null) return;

        GuiGraphicsExtractor context = event.graphics;
        if (context == null) return;

        Vector3d vec = new Vector3d(preview.pos.getX() + 0.5, preview.pos.getY() + 0.5, preview.pos.getZ() + 0.5);
        if (!NametagUtils.to2D(vec, 1.0)) return;

        int screenX = (int) vec.x;
        int screenY = (int) vec.y;

        int panelX = screenX - preview.panelWidth / 2;
        int panelY = screenY - preview.panelHeight - 20;

        if (panelX < 0) panelX = 0;
        if (panelY < 0) panelY = 0;
        if (panelX + preview.panelWidth > mc.getWindow().getGuiScaledWidth())
            panelX = mc.getWindow().getGuiScaledWidth() - preview.panelWidth;
        if (panelY + preview.panelHeight > mc.getWindow().getGuiScaledHeight())
            panelY = mc.getWindow().getGuiScaledHeight() - preview.panelHeight;

        int pad = 2;

        // Background & border
        context.fill(panelX, panelY, panelX + preview.panelWidth, panelY + preview.panelHeight, backgroundColor.get().getPacked());
        context.fill(panelX, panelY, panelX + preview.panelWidth, panelY + 1, borderColor.get().getPacked());
        context.fill(panelX, panelY + preview.panelHeight - 1, panelX + preview.panelWidth, panelY + preview.panelHeight, borderColor.get().getPacked());
        context.fill(panelX, panelY, panelX + 1, panelY + preview.panelHeight, borderColor.get().getPacked());
        context.fill(panelX + preview.panelWidth - 1, panelY, panelX + preview.panelWidth, panelY + preview.panelHeight, borderColor.get().getPacked());

        int textY = panelY + pad;

        // Title
        if (showType.get()) {
            context.text(mc.font, preview.titleText, panelX + pad, textY, 0xFFFFFF);
            textY += 10;
        }
        // Position
        if (showPosition.get()) {
            context.text(mc.font, preview.posText, panelX + pad, textY, 0xCCCCCC);
            textY += 10;
        }

        // Item grid
        int itemStartX = panelX + pad;
        int itemStartY = textY + pad;
        float itemScale = preview.itemScale;
        for (int i = 0; i < preview.stacks.size(); i++) {
            int col = i % preview.itemsPerRow;
            int row = i / preview.itemsPerRow;
            int x = itemStartX + col * (iconSize.get() + pad);
            int y = itemStartY + row * (iconSize.get() + pad);

            // Draw original item
            RenderUtils.drawItem(event.graphics, preview.stacks.get(i), x, y, itemScale, true);

            // Overlay dominant shulker item
            Item dominant = preview.dominantItems.get(i);
            if (dominant != null) {
                int border = 1;
                int overlaySize = iconSize.get() - 2 * border;
                float overlayScale = overlaySize / 16.0f;
                int overlayX = x + border;
                int overlayY = y + border;
                RenderUtils.drawItem(event.graphics, new ItemStack(dominant), overlayX, overlayY, overlayScale, false);
            }
        }
    }
}
