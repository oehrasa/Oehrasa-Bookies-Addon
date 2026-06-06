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
import net.minecraft.block.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        .defaultValue(new SettingColor(0, 0, 0, 180))
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
    private World lastWorld;

    private Entity lastEntity;
    private List<ItemStack> lastEntityItems;
    private String lastEntityType;

    private PreviewData currentPreview = null;

    private record PreviewData(
        BlockPos pos,
        Text titleText,
        Text posText,
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
        lastWorld = mc.world;
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
        if (mc.world != lastWorld) {
            lastWorld = mc.world;
            if (mc.world != null) data.loadData();
            lastTargetedPos = null;
            lastContainer = null;
            lastEntity = null;
            lastEntityItems = null;
            lastEntityType = null;
            currentPreview = null;
        }

        if (mc.player == null || mc.world == null) return;

        if (lastEntity != null && (lastEntity.isRemoved() || !lastEntity.isAlive())) {
            lastEntity = null;
            lastEntityItems = null;
            lastEntityType = null;
            currentPreview = null;
        }

        // Block containers
        if (mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.BLOCK) {
            lastEntity = null;
            lastEntityItems = null;
            lastEntityType = null;

            BlockHitResult hit = (BlockHitResult) mc.crosshairTarget;
            BlockPos pos = hit.getBlockPos();
            if (pos.equals(lastTargetedPos)) return;
            lastTargetedPos = pos;

            double dist = Math.sqrt(mc.player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
            if (dist > maxDistance.get()) {
                lastContainer = null;
                currentPreview = null;
                return;
            }

            Block block = mc.world.getBlockState(pos).getBlock();
            if (!(block instanceof ChestBlock || block instanceof BarrelBlock
                || block instanceof ShulkerBoxBlock || block instanceof EnderChestBlock)) {
                lastContainer = null;
                currentPreview = null;
                return;
            }

            String dimension = mc.world.getRegistryKey().getValue().toString();
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
        if (mc.crosshairTarget != null && mc.crosshairTarget instanceof EntityHitResult entityHit) {
            Entity entity = entityHit.getEntity();
            if (entity == lastEntity) return;

            lastEntity = entity;
            lastEntityItems = null;
            lastEntityType = null;

            if (entity instanceof ItemFrameEntity frame) {
                ItemStack stack = frame.getHeldItemStack();
                if (stack.isEmpty()) {
                    currentPreview = null;
                    return;
                }
                ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
                if (container != null) {
                    List<ItemStack> nonEmpty = container.streamNonEmpty().map(ItemStack::copy).toList();
                    if (!nonEmpty.isEmpty()) {
                        lastEntityItems = nonEmpty;
                        lastEntityType = "shulker_box";
                        buildEntityPreview(entity, nonEmpty, "shulker_box");
                        return;
                    }
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

        for (Map.Entry<Integer, String> entry : container.getDominantItems().entrySet()) {
            Identifier id = Identifier.tryParse(entry.getValue());

            if (id == null) continue;

            Item item = Registries.ITEM.get(id);

            if (item != null) {
                dominantMap.put(entry.getKey(), item);
            }
        }

        dominantMap.putAll(computeDominantMap(stacks));
        buildPreview(pos, title, stacks, false, dominantMap);
    }

    private void buildEntityPreview(Entity entity, List<ItemStack> stacks, String type) {
        String title = type != null ? type : "container";
        BlockPos pos = entity.getBlockPos();
        Map<Integer, Item> dominantMap = computeDominantMap(stacks);
        buildPreview(pos, title, stacks, false, dominantMap);
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
        ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
        if (container == null) return null;
        Map<Item, Integer> counts = new HashMap<>();
        container.streamNonEmpty().forEach(s -> counts.merge(s.getItem(), s.getCount(), Integer::sum));
        if (counts.isEmpty()) return null;
        return counts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);
    }

    private void buildPreview(BlockPos pos, String title, List<ItemStack> stacks, boolean isShulker,
                              Map<Integer, Item> dominantMap) {
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

        Text titleText = Text.literal(title);
        Text posText = Text.literal(String.format("%d, %d, %d", pos.getX(), pos.getY(), pos.getZ()));

        currentPreview = new PreviewData(pos, titleText, posText, w, h, stacks,
            itemsPerRow, scale, dominantMap);
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        PreviewData preview = this.currentPreview;
        if (preview == null) return;

        DrawContext context = event.drawContext;
        if (context == null) return;

        Vector3d vec = new Vector3d(preview.pos.getX() + 0.5, preview.pos.getY() + 0.5, preview.pos.getZ() + 0.5);
        if (!NametagUtils.to2D(vec, 1.0)) return;

        int screenX = (int) vec.x;
        int screenY = (int) vec.y;

        int panelX = screenX - preview.panelWidth / 2;
        int panelY = screenY - preview.panelHeight - 20;

        if (panelX < 0) panelX = 0;
        if (panelY < 0) panelY = 0;
        if (panelX + preview.panelWidth > mc.getWindow().getScaledWidth())
            panelX = mc.getWindow().getScaledWidth() - preview.panelWidth;
        if (panelY + preview.panelHeight > mc.getWindow().getScaledHeight())
            panelY = mc.getWindow().getScaledHeight() - preview.panelHeight;

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
            context.drawTextWithShadow(mc.textRenderer, preview.titleText, panelX + pad, textY, 0xFFFFFF);
            textY += 10;
        }
        // Position
        if (showPosition.get()) {
            context.drawTextWithShadow(mc.textRenderer, preview.posText, panelX + pad, textY, 0xCCCCCC);
            textY += 10;
        }

        // Full item grid
        int itemStartX = panelX + pad;
        int itemStartY = textY + pad;
        float itemScale = preview.itemScale;
        for (int i = 0; i < preview.stacks.size(); i++) {
            int col = i % preview.itemsPerRow;
            int row = i / preview.itemsPerRow;
            int x = itemStartX + col * (iconSize.get() + pad);
            int y = itemStartY + row * (iconSize.get() + pad);

            // Draw the original shulker item
            RenderUtils.drawItem(event.drawContext, preview.stacks.get(i), x, y, itemScale, true);

            Item dominant = preview.dominantItems.get(i);
            if (dominant != null) {
                int border = 1;
                int overlaySize = iconSize.get() - 2 * border;
                float scale = overlaySize / 16.0f;          // 12/16 = 0.75
                int overlayX = x + border;
                int overlayY = y + border;

                RenderUtils.drawItem(event.drawContext, new ItemStack(dominant), overlayX, overlayY, scale, false);
            }
        }
    }
}
