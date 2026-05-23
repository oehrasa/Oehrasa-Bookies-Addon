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
        .defaultValue(new SettingColor(0, 0, 0, 180))
        .build()
    );

    private final Setting<SettingColor> borderColor = sgGeneral.add(new ColorSetting.Builder()
        .name("border-color")
        .description("Border color of the preview panel.")
        .defaultValue(new SettingColor(255, 255, 255, 100))
        .build()
    );

    private final ChestTrackerDataV2 data = new ChestTrackerDataV2();   // fallback saved data
    private BlockPos lastTargetedPos;
    private TrackedContainer lastContainer;
    private World lastWorld;

    private Entity lastEntity;
    private List<ItemStack> lastEntityItems;
    private String lastEntityType;

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
        }

        if (mc.player == null || mc.world == null) return;

        // Clear entity data if the entity is no longer valid
        if (lastEntity != null && (lastEntity.isRemoved() || !lastEntity.isAlive())) {
            lastEntity = null;
            lastEntityItems = null;
            lastEntityType = null;
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
                return;
            }

            Block block = mc.world.getBlockState(pos).getBlock();

            // Accept all trackable container types
            if (!(block instanceof ChestBlock || block instanceof BarrelBlock
                || block instanceof ShulkerBoxBlock || block instanceof EnderChestBlock)) {
                lastContainer = null;
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
                if (stack.isEmpty()) return;

                // 1) Standard container component
                ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
                if (container != null) {
                    List<ItemStack> nonEmpty = container.streamNonEmpty()
                        .map(ItemStack::copy)
                        .toList();
                    if (!nonEmpty.isEmpty()) {
                        lastEntityItems = nonEmpty;
                        lastEntityType = "shulker_box";
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
                    return;
                }
            }

            lastEntity = null;
            lastEntityItems = null;
            lastEntityType = null;
            return;
        }

        // No target
        lastTargetedPos = null;
        lastContainer = null;
        lastEntity = null;
        lastEntityItems = null;
        lastEntityType = null;
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        List<ItemStack> stacks = null;
        BlockPos pos = null;
        String typeStr = null;
        String posStr = null;

        if (lastEntityItems != null && lastEntity != null) {
            stacks = lastEntityItems;
            pos = lastEntity.getBlockPos();
            typeStr = lastEntityType != null ? lastEntityType : "container";
            posStr = String.format("%d, %d, %d", pos.getX(), pos.getY(), pos.getZ());
        } else if (lastContainer != null) {
            stacks = lastContainer.getItemStacks();
            pos = lastContainer.getPosition();
            typeStr = lastContainer.getCustomName() != null
                ? lastContainer.getCustomName()
                : lastContainer.getContainerType();
            posStr = String.format("%d, %d, %d", pos.getX(), pos.getY(), pos.getZ());
        } else {
            return;
        }

        DrawContext context = event.drawContext;
        if (context == null) return;

        Vector3d vec = new Vector3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (!NametagUtils.to2D(vec, 1.0)) return;

        int screenX = (int) vec.x;
        int screenY = (int) vec.y;

        int totalItems = stacks.size();
        int itemsPerRow = maxItemsPerRow.get();
        int rows = (int) Math.ceil((double) totalItems / itemsPerRow);
        int iconSize = this.iconSize.get();
        int padding = 2;
        int panelWidth = itemsPerRow * iconSize + (itemsPerRow + 1) * padding;
        int panelHeight = rows * iconSize + (rows + 1) * padding;

        int textLines = 0;
        if (showType.get()) textLines++;
        if (showPosition.get()) textLines++;
        if (textLines > 0) panelHeight += 10 * textLines + padding;

        int panelX = screenX - panelWidth / 2;
        int panelY = screenY - panelHeight;   // directly above the crosshair

        // clamp to screen
        if (panelX < 0) panelX = 0;
        if (panelY < 0) panelY = 0;
        if (panelX + panelWidth > mc.getWindow().getScaledWidth()) panelX = mc.getWindow().getScaledWidth() - panelWidth;
        if (panelY + panelHeight > mc.getWindow().getScaledHeight()) panelY = mc.getWindow().getScaledHeight() - panelHeight;

        var matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.identity();   // reset inherited translation

        // Background
        context.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, backgroundColor.get().getPacked());

        // Border
        context.fill(panelX, panelY, panelX + panelWidth, panelY + 1, borderColor.get().getPacked());
        context.fill(panelX, panelY + panelHeight - 1, panelX + panelWidth, panelY + panelHeight, borderColor.get().getPacked());
        context.fill(panelX, panelY, panelX + 1, panelY + panelHeight, borderColor.get().getPacked());
        context.fill(panelX + panelWidth - 1, panelY, panelX + panelWidth, panelY + panelHeight, borderColor.get().getPacked());

        // Text
        int textY = panelY + padding;
        if (showType.get()) {
            context.drawTextWithShadow(mc.textRenderer, Text.literal(typeStr), panelX + padding, textY, 0xFFFFFF);
            textY += 10;
        }
        if (showPosition.get()) {
            context.drawTextWithShadow(mc.textRenderer, Text.literal(posStr), panelX + padding, textY, 0xCCCCCC);
            textY += 10;
        }

        // Item grid
        int itemStartX = panelX + padding;
        int itemStartY = textY + padding;
        float itemScale = iconSize / 16.0f;
        for (int i = 0; i < stacks.size(); i++) {
            int col = i % itemsPerRow;
            int row = i / itemsPerRow;
            int x = itemStartX + col * (iconSize + padding);
            int y = itemStartY + row * (iconSize + padding);
            RenderUtils.drawItem(event.drawContext, stacks.get(i), x, y, itemScale, true, null, false);
        }

        matrices.popMatrix();
    }
}
