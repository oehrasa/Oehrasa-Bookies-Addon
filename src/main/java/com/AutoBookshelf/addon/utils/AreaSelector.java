package com.AutoBookshelf.addon.utils;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Shared two-click cuboid area selector (pos1/pos2) with rendering.
 */
public class AreaSelector {

    public enum Result {POS1_SET, COMPLETE, RESET}

    private final Setting<Item> selectionTool;
    private final Setting<Boolean> requireToolInHand;
    private final Setting<Boolean> render;
    private final Setting<SettingColor> sideColor;
    private final Setting<SettingColor> lineColor;
    private final Setting<SettingColor> pos1Color;
    private final Setting<SettingColor> pos2Color;

    private BlockPos pos1;
    private BlockPos pos2;
    private boolean selecting = true;
    private boolean wandModeActive = false;

    public AreaSelector(SettingGroup sgSelection, SettingGroup sgRender, Item defaultTool) {
        selectionTool = sgSelection.add(new ItemSetting.Builder()
            .name("selection-tool")
            .description("Which item to use for making selections.")
            .defaultValue(defaultTool)
            .build()
        );

        requireToolInHand = sgSelection.add(new BoolSetting.Builder()
            .name("require-tool-in-hand")
            .description("Require the selection tool to be held in hand to make selections.")
            .defaultValue(true)
            .build()
        );

        render = sgRender.add(new BoolSetting.Builder()
            .name("render")
            .description("Renders the selection area.")
            .defaultValue(true)
            .build()
        );

        sideColor = sgRender.add(new ColorSetting.Builder()
            .name("side-color")
            .description("The side color of the selection box.")
            .defaultValue(new SettingColor(0, 255, 255, 30))
            .build()
        );

        lineColor = sgRender.add(new ColorSetting.Builder()
            .name("line-color")
            .description("The line color of the selection box.")
            .defaultValue(new SettingColor(0, 255, 255, 255))
            .build()
        );

        pos1Color = sgRender.add(new ColorSetting.Builder()
            .name("pos1-color")
            .description("The color of the first position marker.")
            .defaultValue(new SettingColor(0, 255, 0, 255))
            .build()
        );

        pos2Color = sgRender.add(new ColorSetting.Builder()
            .name("pos2-color")
            .description("The color of the second position marker.")
            .defaultValue(new SettingColor(255, 0, 0, 255))
            .build()
        );
    }

    public boolean isToolStack(ItemStack stack) {
        Item tool = selectionTool.get();
        return tool != null && !stack.isEmpty() && stack.getItem() == tool;
    }

    public boolean requiresToolInHand() {
        return requireToolInHand.get();
    }

    public Item getSelectionToolItem() {
        return selectionTool.get();
    }

    /**
     * Call every tick while selecting() if the module wants a "wand active" indicator.
     */
    public void updateWandState(ItemStack mainHand) {
        if (requireToolInHand.get() && selectionTool.get() != null) {
            wandModeActive = !mainHand.isEmpty() && mainHand.getItem() == selectionTool.get();
        }
    }

    public boolean isWandModeActive() {
        return wandModeActive;
    }

    /**
     * Call from onInteract once you've confirmed the held item is this selector's tool.
     */
    public Result handleClick(BlockPos pos) {
        if (!selecting) {
            reset();
            return Result.RESET;
        }

        if (pos1 == null) {
            pos1 = pos;
            return Result.POS1_SET;
        }

        pos2 = pos;
        selecting = false;
        return Result.COMPLETE;
    }

    public void reset() {
        pos1 = null;
        pos2 = null;
        selecting = true;
        wandModeActive = false;
    }

    public boolean isSelecting() {
        return selecting;
    }

    public boolean hasCompleteSelection() {
        return pos1 != null && pos2 != null;
    }

    public BlockPos getPos1() {
        return pos1;
    }

    public BlockPos getPos2() {
        return pos2;
    }

    public void setPos1(BlockPos pos) {
        this.pos1 = pos;
    }

    public void setPos2(BlockPos pos) {
        this.pos2 = pos;
        this.selecting = false;
    }

    /**
     * pos1 flattened to the given Y, so the corner marker moves together with
     * the highlighted preview instead of staying frozen at click height.
     */
    public BlockPos getFlatPos1(int y) {
        return pos1 == null ? null : new BlockPos(pos1.getX(), y, pos1.getZ());
    }

    /**
     * pos2 flattened to the given Y, same reasoning as getFlatPos1.
     */
    public BlockPos getFlatPos2(int y) {
        return pos2 == null ? null : new BlockPos(pos2.getX(), y, pos2.getZ());
    }

    // geometry
    public int minX() {
        return Math.min(pos1.getX(), pos2.getX());
    }

    public int maxX() {
        return Math.max(pos1.getX(), pos2.getX());
    }

    public int minY() {
        return Math.min(pos1.getY(), pos2.getY());
    }

    public int maxY() {
        return Math.max(pos1.getY(), pos2.getY());
    }

    public int minZ() {
        return Math.min(pos1.getZ(), pos2.getZ());
    }

    public int maxZ() {
        return Math.max(pos1.getZ(), pos2.getZ());
    }

    public boolean isXIncreasing() {
        return pos2.getX() >= pos1.getX();
    }

    public boolean isYIncreasing() {
        return pos2.getY() >= pos1.getY();
    }

    public boolean isZIncreasing() {
        return pos2.getZ() >= pos1.getZ();
    }

    public AABB getBoundingBox() {
        if (!hasCompleteSelection()) return null;
        return new AABB(minX(), minY(), minZ(), maxX() + 1, maxY() + 1, maxZ() + 1);
    }

    /**
     * Bounding box flattened to a single Y-slice (for platform-style previews).
     */
    public AABB getFlatBoundingBox(int y) {
        if (!hasCompleteSelection()) return null;
        return new AABB(minX(), y, minZ(), maxX() + 1, y + 1, maxZ() + 1);
    }

    /**
     * All positions in the cuboid where the given predicate matches (block type check).
     */
    public List<BlockPos> getMatchingBlocks(Predicate<BlockPos> matches) {
        List<BlockPos> list = new ArrayList<>();
        if (!hasCompleteSelection()) return list;

        for (int y = minY(); y <= maxY(); y++) {
            for (int x = minX(); x <= maxX(); x++) {
                for (int z = minZ(); z <= maxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (matches.test(pos)) list.add(pos);
                }
            }
        }
        return list;
    }

    /**
     * Flat XZ rectangle at a fixed Y (for platform-style fills, ignores selection's Y range).
     */
    public List<BlockPos> getFlatArea(int y) {
        List<BlockPos> list = new ArrayList<>();
        if (!hasCompleteSelection()) return list;

        for (int x = minX(); x <= maxX(); x++) {
            for (int z = minZ(); z <= maxZ(); z++) {
                list.add(new BlockPos(x, y, z));
            }
        }
        return list;
    }

    /**
     * Default render: pos1/pos2 markers plus the raw cuboid spanning pos1.Y..pos2.Y.
     */
    public void render(Render3DEvent event) {
        if (!render.get()) return;

        if (pos1 != null) {
            event.renderer.box(pos1, pos1Color.get(), pos1Color.get(), ShapeMode.Both, 0);
        }
        if (pos2 != null) {
            event.renderer.box(pos2, pos2Color.get(), pos2Color.get(), ShapeMode.Both, 0);
        }

        AABB box = getBoundingBox();
        if (box != null) {
            event.renderer.box(box, sideColor.get(), lineColor.get(), ShapeMode.Both, 0);
        }
    }

    /**
     * Flattened render: pos1/pos2 markers are shown at flattenedY too, so the
     * corners move together with the highlighted plane instead of staying pinned
     * at the original click height.
     */
    public void render(Render3DEvent event, int flattenedY) {
        if (!render.get()) return;

        BlockPos flatPos1 = getFlatPos1(flattenedY);
        BlockPos flatPos2 = getFlatPos2(flattenedY);

        if (flatPos1 != null) {
            event.renderer.box(flatPos1, pos1Color.get(), pos1Color.get(), ShapeMode.Both, 0);
        }
        if (flatPos2 != null) {
            event.renderer.box(flatPos2, pos2Color.get(), pos2Color.get(), ShapeMode.Both, 0);
        }

        AABB box = getFlatBoundingBox(flattenedY);
        if (box != null) {
            event.renderer.box(box, sideColor.get(), lineColor.get(), ShapeMode.Both, 0);
        }
    }

    public SettingColor getSideColor() {
        return sideColor.get();
    }

    public SettingColor getLineColor() {
        return lineColor.get();
    }
}
