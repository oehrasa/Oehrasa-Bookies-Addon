package com.AutoBookshelf.addon.utils;

import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ShelfBlock;
import net.minecraft.world.level.block.entity.ShelfBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * Builds and draws a vanilla-style tooltip for the item sitting on a shelf.
 */
public class ShelfTooltip {

    /**
     * Where the tooltip is anchored. Names match the reference's enum, so configs line up.
     */
    public enum TooltipPosition {
        TOP_LEFT, TOP_MIDDLE, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_MIDDLE, BOTTOM_RIGHT
    }

    /**
     * Where the stack count lands inside the tooltip. Names likewise match the reference.
     */
    public enum AmountPosition {
        BEFORE_NAME, AFTER_NAME, ABOVE, BETWEEN, UNDER, BEFORE_LORE, AFTER_LORE
    }

    /**
     * The settings that can change a tooltip <em>line</em>, and therefore the ones a
     * {@link #stamp} has to cover.
     *
     * <p>Kept separate from {@link LayoutConfig} on purpose: the split is the invalidation
     * boundary. Layout is applied at draw time to already-built lines, so dragging the scale
     * slider must not cost a rebuild. Merging the two records would make that wrong by
     * construction rather than by accident.
     */
    public record ContentConfig(
        boolean tripleMode,
        boolean showAmount,
        String amountFormat,
        AmountPosition amountPosition,
        SettingColor amountColor,
        boolean showAttributes,
        SettingColor attributeColor,
        boolean showPotionEffects,
        boolean showTrimUpgrades,
        boolean showAdvanced,
        SettingColor advancedColor
    ) {
    }

    /**
     * The settings that only affect where and how big the finished tooltip is drawn.
     */
    public record LayoutConfig(
        TooltipPosition position,
        double scale,
        double spacing,
        boolean transparent
    ) {
    }

    /**
     * Substring markers for the two tooltip sections that can be cut off wholesale. These are
     * vanilla's own attribute-modifier and potion section headers, matched as substrings because
     * the rendered line carries styling and a trailing value after them.
     *
     * <p>Fragile by nature: these are literal English strings, so a Mojang reword silently
     * disables the cutoff and the tooltip simply shows more than was asked for. Nothing crashes,
     * which is also why it is worth knowing about rather than discovering.
     */
    private static final String[] ATTRIBUTE_HEADERS = {
        "When in Main Hand", "When in Off Hand", "When on Head", "When on Chest",
        "When on Legs", "When on Feet", "When Equipped", "When in Hand"
    };

    private static final String[] POTION_SECTION_HEADERS = {"When Applied:", "Effects:"};

    /**
     * A line like {@code (0:30)} starts a potion duration section. Anchored on the shape rather
     * than on any particular effect name, so it works for modded potions too.
     */
    private static final Pattern POTION_DURATION = Pattern.compile(".*\\(\\d+:\\d+\\)");

    /**
     * A resolved position and scale for one frame's draw.
     */
    public record Placement(int startX, int startY, int groupWidth, float scale, float shiftX, float shiftY) {
    }

    private ShelfTooltip() {
    }

    /**
     * Which shelf slots the tooltip should describe: all three in triple mode, otherwise only the
     * one under the crosshair.
     *
     * <p>{@code getHitSlot} is a default method on {@code SelectableSlotContainer} that maps a hit
     * onto a slot for a given facing, so it is exactly the "which slot am I pointing at" test, and
     * it returns empty when the hit is on the block rather than on a slot face.
     */
    public static List<ItemStack> targetStacks(Level level, BlockPos pos, BlockHitResult hit, boolean tripleMode) {
        if (level == null || !(level.getBlockEntity(pos) instanceof ShelfBlockEntity be)) return null;

        List<ItemStack> stacks = new ArrayList<>(ShelfBlockEntity.MAX_ITEMS);

        if (tripleMode) {
            for (int i = 0; i < ShelfBlockEntity.MAX_ITEMS; i++) stacks.add(be.getItem(i));
            return stacks;
        }

        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ShelfBlock shelf)) return null;
        OptionalInt slot = shelf.getHitSlot(hit, state.getValue(ShelfBlock.FACING));
        if (slot.isPresent()) stacks.add(be.getItem(slot.getAsInt()));
        return stacks;
    }

    /**
     * Fingerprint of everything in {@link ContentConfig} plus the contents of the target slots, so
     * the line construction is skipped unless one of them actually moved.
     *
     * <p>Stack identity is by reference, which is what makes this cheap and correct:
     * {@code ListBackedContainer.getItem} hands back the same object until the server's
     * block-entity sync replaces it, and that sync happens precisely when the contents changed.
     * So the reference is stable while nothing happens, and changes exactly when something does.
     * Count is folded in as well, being the one thing that can change on a stack we already hold.
     */
    public static long stamp(BlockPos pos, List<ItemStack> stacks, ContentConfig config) {
        long stamp = 1469598103934665603L;
        stamp = 31 * stamp + pos.asLong();
        stamp = 31 * stamp + (config.tripleMode() ? 1 : 0);
        stamp = 31 * stamp + (config.showAmount() ? 1 : 0);
        stamp = 31 * stamp + config.amountFormat().hashCode();
        stamp = 31 * stamp + config.amountPosition().hashCode();
        stamp = 31 * stamp + rgb(config.amountColor());
        stamp = 31 * stamp + (config.showAttributes() ? 1 : 0);
        stamp = 31 * stamp + rgb(config.attributeColor());
        stamp = 31 * stamp + (config.showPotionEffects() ? 1 : 0);
        stamp = 31 * stamp + (config.showTrimUpgrades() ? 1 : 0);
        stamp = 31 * stamp + (config.showAdvanced() ? 1 : 0);
        stamp = 31 * stamp + rgb(config.advancedColor());

        for (ItemStack stack : stacks) {
            stamp = 31 * stamp + System.identityHashCode(stack);
            stamp = 31 * stamp + stack.getCount();
        }
        return stamp;
    }

    /**
     * Builds the display lines for one stack: vanilla's tooltip, with the attribute and potion
     * sections optionally cut, debug-only lines removed (or appended, if asked for), and the stack
     * count folded in.
     */
    public static List<Component> buildLines(ItemStack stack, Item.TooltipContext tooltipContext,
                                             Player player, ContentConfig config) {
        List<Component> lines = new ArrayList<>(stack.getTooltipLines(tooltipContext, player, TooltipFlag.NORMAL));

        // The advanced pass is only ever needed for show-advanced, so it is not built otherwise.
        List<Component> advancedOnly = new ArrayList<>();
        if (config.showAdvanced()) {
            for (Component line : stack.getTooltipLines(tooltipContext, player, TooltipFlag.ADVANCED)) {
                String text = line.getString().strip();
                if (!text.isEmpty() && !lines.contains(line)) advancedOnly.add(line);
            }
        }

        // The scan starts at 1, not 0: an item literally *named* "When in Hand" must not be read
        // as the start of an attribute section, which would cut the whole tooltip away.
        int attrStart = -1;
        int potionStart = -1;
        for (int i = 1; i < lines.size(); i++) {
            String text = lines.get(i).getString();
            if (attrStart == -1 && containsAny(text, ATTRIBUTE_HEADERS)) attrStart = i;
            if (potionStart == -1
                && (containsAny(text, POTION_SECTION_HEADERS) || POTION_DURATION.matcher(text).matches())) {
                potionStart = i;
            }
            if (attrStart != -1 && potionStart != -1) break;
        }

        int cutoff = lines.size();
        if (!config.showAttributes() && attrStart != -1) cutoff = Math.min(cutoff, attrStart);
        if (!config.showPotionEffects() && potionStart != -1) cutoff = Math.min(cutoff, potionStart);

        List<Component> filtered = new ArrayList<>();
        // "Upgrade:" is a header for the two trim lines beneath it, so hiding it means hiding those
        // too - hence the countdown rather than a single-line skip.
        int trimLinesToSkip = 0;

        for (int i = 0; i < cutoff; i++) {
            Component line = lines.get(i);
            String text = line.getString();
            if (text.isBlank()) continue;
            // Advanced-only lines are identified exactly by the ADVANCED-vs-NORMAL pass above.
            // A content heuristic here cannot help: TooltipFlag.NORMAL never emits an advanced
            // line (getTooltipLines does not consult the client's F3+H option and NORMAL has
            // advanced=false), so it could only ever drop real content - a shulker renamed
            // "Redstone components", or lore containing "Durability 3/5".
            if (advancedOnly.contains(line)) continue;

            if (!config.showTrimUpgrades()) {
                if (text.startsWith("Upgrade:")) {
                    trimLinesToSkip = 2;
                    continue;
                }
                if (trimLinesToSkip > 0) {
                    trimLinesToSkip--;
                    continue;
                }
            }

            if (config.showAttributes() && attrStart != -1 && i >= attrStart) {
                line = tint(line, config.attributeColor());
            }
            filtered.add(line);
        }

        if (config.showAdvanced()) {
            for (Component line : advancedOnly) {
                if (!line.getString().isBlank()) filtered.add(tint(line, config.advancedColor()));
            }
        }

        if (config.showAmount()) applyAmount(stack, filtered, config);

        return filtered;
    }

    private static void applyAmount(ItemStack stack, List<Component> lines, ContentConfig config) {
        MutableComponent amount = tint(
            Component.literal(config.amountFormat().replace("%amount%", String.valueOf(stack.getCount()))),
            config.amountColor());

        if (lines.isEmpty()) {
            lines.add(amount);
            return;
        }

        // Lore-relative placement is meaningless without lore, so fall back rather than clamping
        // to an arbitrary index.
        ItemLore lore = stack.get(DataComponents.LORE);
        int loreCount = lore != null ? lore.lines().size() : 0;
        AmountPosition mode = config.amountPosition();
        if ((mode == AmountPosition.BEFORE_LORE || mode == AmountPosition.AFTER_LORE) && loreCount == 0) {
            mode = AmountPosition.BETWEEN;
        }

        switch (mode) {
            case BEFORE_NAME -> lines.set(0, amount.copy().append(lines.get(0)));
            // copy() rather than appending in place: the line came straight out of
            // getTooltipLines, and mutating a component we only borrowed is how two render paths
            // end up disagreeing about the same tooltip.
            case AFTER_NAME -> lines.set(0, ((MutableComponent) lines.get(0).copy()).append(" ").append(amount));
            case ABOVE -> lines.add(0, amount);
            case UNDER -> lines.add(amount);
            case BETWEEN -> {
                if (lines.size() > 1) lines.add(1, amount);
                else lines.add(amount);
            }
            case BEFORE_LORE -> {
                int idx = Math.min(1, lines.size() - 1);
                lines.set(idx, amount.copy().append(lines.get(idx)));
            }
            case AFTER_LORE -> {
                int idx = Math.min(loreCount, lines.size() - 1);
                lines.set(idx, ((MutableComponent) lines.get(idx).copy()).append(" ").append(amount));
            }
        }
    }

    /**
     * Draws the finished boxes. Corner anchors stack vertically and middle anchors horizontally, as
     * in the reference: a row of three full tooltips does not fit across a screen, a column does
     * fit down one.
     */
    public static void render(GuiGraphicsExtractor context, Font font,
                              List<List<ClientTooltipComponent>> boxes, LayoutConfig config,
                              int screenWidth, int screenHeight) {
        if (boxes.isEmpty()) return;

        TooltipPosition position = config.position();
        boolean vertical = position == TooltipPosition.TOP_LEFT
            || position == TooltipPosition.TOP_RIGHT
            || position == TooltipPosition.BOTTOM_LEFT
            || position == TooltipPosition.BOTTOM_RIGHT;

        int paddingW = Math.round(14.0f * (float) config.spacing());
        int paddingH = Math.round(13.0f * (float) config.spacing());

        // Measure first, so the group can be positioned and clamped as a whole.
        int[] widths = new int[boxes.size()];
        int[] heights = new int[boxes.size()];
        int totalWidth = 0;
        int totalHeight = 0;
        int maxWidth = 0;

        for (int i = 0; i < boxes.size(); i++) {
            List<ClientTooltipComponent> box = boxes.get(i);
            widths[i] = box.stream().mapToInt(c -> c.getWidth(font)).max().orElse(0);
            heights[i] = box.stream().mapToInt(c -> c.getHeight(font)).sum();
            if (vertical) {
                totalHeight += heights[i] > 0 ? heights[i] + paddingH : 0;
                maxWidth = Math.max(maxWidth, widths[i]);
            } else {
                totalWidth += widths[i] > 0 ? widths[i] + paddingW : 0;
                totalHeight = Math.max(totalHeight, heights[i]);
            }
        }

        Placement placement = place(config, maxWidth, totalWidth, totalHeight, screenWidth, screenHeight);

        context.pose().pushMatrix();
        // Shift outermost, so it lands in final screen space rather than being scaled by the
        // matrix below it.
        context.pose().translate(placement.shiftX(), placement.shiftY());
        context.pose().translate(placement.startX() + placement.groupWidth() / 2f,
            placement.startY() + totalHeight / 2f);
        context.pose().scale(placement.scale(), placement.scale());
        context.pose().translate(-(placement.startX() + placement.groupWidth() / 2f),
            -(placement.startY() + totalHeight / 2f));

        int cursorX = placement.startX();
        int cursorY = placement.startY();

        for (int i = 0; i < boxes.size(); i++) {
            int width = widths[i];
            int height = heights[i];
            // The right-anchored inset is measured from the *widest* box, not from each box's own
            // width. Per-box would left-align the row, so it would drift right as boxes got wider
            // and the edge the position pinned would stop being pinned.
            int boxX = position == TooltipPosition.TOP_RIGHT || position == TooltipPosition.BOTTOM_RIGHT
                ? screenWidth - maxWidth - 10
                : cursorX;

            if (!config.transparent()) {
                try {
                    TooltipRenderUtil.extractTooltipBackground(context, boxX, cursorY, width, height, null);
                } catch (RuntimeException ignored) {
                    // A zero-size box can be rejected by the sprite lookup; a missing background is
                    // not worth taking the whole tooltip down for.
                }
            }

            int lineY = cursorY;
            for (ClientTooltipComponent component : boxes.get(i)) {
                component.extractText(context, font, boxX, lineY);
                lineY += component.getHeight(font);
            }

            if (vertical) cursorY += height + paddingH;
            else cursorX += width + paddingW;
        }

        context.pose().popMatrix();
    }

    /**
     * Resolves where the group goes, and at what scale.
     *
     * <p>Layout is computed in unscaled GUI coordinates and then scaled and clamped, rather than
     * the reference's {@code 5 / guiScale * scale} global scale with a
     * {@code getWidth() / (scale * guiScale)} window size. That dance exists to cancel out
     * vanilla's GUI scale so the tooltip renders at a constant <em>physical</em> size; here scale
     * is an explicit user setting, so the honest reading is "make it bigger or smaller" in GUI
     * units.
     */
    public static Placement place(LayoutConfig config, int maxWidth, int totalWidth, int totalHeight,
                                  int screenWidth, int screenHeight) {
        TooltipPosition position = config.position();

        float scale = (float) config.scale();

        // A scale that pushes the group off the screen is not a bigger tooltip, it is a lost one,
        // so the setting is an upper bound and is reduced to what actually fits. Three stacked
        // tooltips are ~160px tall unscaled, so 3x genuinely cannot fit a 360px window and must
        // not be honoured literally.
        boolean vertical = position == TooltipPosition.TOP_LEFT
            || position == TooltipPosition.TOP_RIGHT
            || position == TooltipPosition.BOTTOM_LEFT
            || position == TooltipPosition.BOTTOM_RIGHT;
        int groupWidth = vertical ? maxWidth : totalWidth;
        if (groupWidth > 0) scale = Math.min(scale, (float) screenWidth / groupWidth);
        if (totalHeight > 0) scale = Math.min(scale, (float) screenHeight / totalHeight);

        int startX = switch (position) {
            case BOTTOM_LEFT, TOP_LEFT -> 10;
            case BOTTOM_MIDDLE, TOP_MIDDLE -> screenWidth / 2 - totalWidth / 2;
            case BOTTOM_RIGHT, TOP_RIGHT -> screenWidth - maxWidth - 10;
        };
        int startY = switch (position) {
            case BOTTOM_LEFT, BOTTOM_RIGHT -> screenHeight - totalHeight - 7;
            case BOTTOM_MIDDLE -> screenHeight - totalHeight - 35;
            default -> 10;
        };

        // Scale about the group's centre, not about a corner. Corner scaling reads better in
        // theory ["grows inward from where it is pinned"] but it only holds while the group
        // fits, and the one case where a large scale has been deliberately asked for is exactly
        // the case where it does not. Centring is symmetric, survives overflow, and needs no
        // per-position special case.
        float centreX = startX + groupWidth / 2f;
        float centreY = startY + totalHeight / 2f;

        // Clamp as well, so the corner insets and rounding cannot leave a sliver off screen.
        float halfW = groupWidth * scale / 2f;
        float halfH = totalHeight * scale / 2f;
        float shiftX = centreX - halfW < 0 ? -(centreX - halfW)
            : centreX + halfW > screenWidth ? screenWidth - (centreX + halfW) : 0f;
        float shiftY = centreY - halfH < 0 ? -(centreY - halfH)
            : centreY + halfH > screenHeight ? screenHeight - (centreY + halfH) : 0f;

        return new Placement(startX, startY, groupWidth, scale, shiftX, shiftY);
    }

    private static boolean containsAny(String line, String[] needles) {
        for (String n : needles) {
            if (line.contains(n)) return true;
        }
        return false;
    }

    private static MutableComponent tint(Component line, SettingColor color) {
        return line.copy().setStyle(line.getStyle().withColor(rgb(color)));
    }

    /**
     * 24-bit RGB for {@code Style#withColor(int)}, which routes to {@code TextColor.fromRgb} and
     * drops any alpha. {@code SettingColor.getPacked()} is 32-bit ARGB, so the alpha has to come
     * off or the colour comes out wrong.
     */
    private static int rgb(SettingColor color) {
        return (color.r << 16) | (color.g << 8) | color.b;
    }
}
