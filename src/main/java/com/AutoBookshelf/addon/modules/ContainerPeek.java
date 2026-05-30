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
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

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
        .build()    // F ts
    );

    private final Setting<SettingColor> borderColor = sgGeneral.add(new ColorSetting.Builder()
        .name("border-color")
        .description("Border color of the preview panel.")
        .defaultValue(new SettingColor(255, 255, 255, 0))
        .build()
    );

    private final ChestTrackerDataV2 data = new ChestTrackerDataV2();   // fallback saved data
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
        float itemScale
    ) {}

    public ContainerPeek() {
        super(Addon.CATEGORY, "Container-Peek",
            "Displays the tracked contents from Chest-Tracker when you look at the block.");
    }

    @Override
    public void onActivate() {
        data.loadData();    // load saved cache on enable
        lastWorld = mc.world;
    }

    @Override
    public void onDeactivate() {
        lastTargetedPos = null;
        lastContainer = null;
        lastEntity = null;
        lastEntityItems = null;
        lastEntityType = null;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // Reload data when world changes (relog)
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

        // Clear entity data if the entity is no longer valid
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

            // Accept all trackable container types
            if (!(block instanceof ChestBlock || block instanceof BarrelBlock
                || block instanceof ShulkerBoxBlock || block instanceof EnderChestBlock)) {
                lastContainer = null;
                currentPreview = null;
                return;
            }

            // Use active ChestTracker data if available, else fallback saved data
            String dimension = mc.world.getRegistryKey().getValue().toString();
            ChestTrackerModule tracker = Modules.get().get(ChestTrackerModule.class);
            if (tracker != null && tracker.isActive()) {
                lastContainer = tracker.getData().getContainer(pos, dimension);
            } else {
                lastContainer = data.getContainer(pos, dimension);
            }

            // Build lightweight preview snapshot
            if (lastContainer != null) {
                List<ItemStack> stacks = lastContainer.getItemStacks();
                String title = lastContainer.getCustomName() != null
                    ? lastContainer.getCustomName()
                    : lastContainer.getContainerType();

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

                currentPreview = new PreviewData(pos, titleText, posText, w, h, stacks, itemsPerRow, scale);
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

                // 1) Standard container component
                ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
                if (container != null) {
                    List<ItemStack> nonEmpty = container.streamNonEmpty()
                        .map(ItemStack::copy)
                        .toList();
                    if (!nonEmpty.isEmpty()) {
                        lastEntityItems = nonEmpty;
                        lastEntityType = "shulker_box";

                        // Build snapshot for entity frame
                        buildEntityPreview(entity, nonEmpty, "shulker_box");
                        return;
                    }
                }

                // 2) Bundle contents
                BundleContentsComponent bundle = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
                if (bundle != null && !bundle.isEmpty()) {
                    List<ItemStack> bundleStacks = new ArrayList<>();
                    bundle.stream().forEach(s -> bundleStacks.add(s.copy()));
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

    private void buildEntityPreview(Entity entity, List<ItemStack> stacks, String type) {
        String title = type != null ? type : "container";
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
        BlockPos pos = entity.getBlockPos();
        Text titleText = Text.literal(title);
        Text posText = Text.literal(String.format("%d, %d, %d", pos.getX(), pos.getY(), pos.getZ()));
        currentPreview = new PreviewData(pos, titleText, posText, w, h, stacks, itemsPerRow, scale);
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

        // Clamp to screen
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

        // Item grid
        int itemStartX = panelX + pad;
        int itemStartY = textY + pad;
        float itemScale = preview.itemScale;
        for (int i = 0; i < preview.stacks.size(); i++) {
            int col = i % preview.itemsPerRow;
            int row = i / preview.itemsPerRow;
            int x = itemStartX + col * (iconSize.get() + pad);
            int y = itemStartY + row * (iconSize.get() + pad);
            RenderUtils.drawItem(event.drawContext, preview.stacks.get(i), x, y, itemScale, true);
        }
    }
}
