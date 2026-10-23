package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.modules.chesttracker.ChestTrackerDataV2;
import com.AutoBookshelf.addon.modules.chesttracker.ChestTrackerModule;
import com.AutoBookshelf.addon.modules.chesttracker.TrackedContainer;
import com.AutoBookshelf.addon.utils.ShelfTooltip;
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
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.TooltipBackgroundRenderer;
import net.minecraft.client.gui.tooltip.TooltipComponent;
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
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.*;
import java.util.stream.Collectors;

public class ContainerPeek extends Module {
    private static final int SLOT_SIZE = 18;
    private static final Identifier SLOT_TEXTURE = Identifier.ofVanilla("container/slot");

    public enum PeekStyle {
        Panel("Panel"),
        Container("Container");

        private final String title;

        PeekStyle(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<PeekStyle> style = sgGeneral.add(new EnumSetting.Builder<PeekStyle>()
        .name("style")
        .description("How the preview is rendered. Panel = custom panel, Container = minecraft container GUI look.")
        .defaultValue(PeekStyle.Panel)
        .build()
    );

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
        .defaultValue(12)
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
        .defaultValue(new SettingColor(0, 0, 0, 70))
        .build()
    );

    private final Setting<SettingColor> borderColor = sgGeneral.add(new ColorSetting.Builder()
        .name("border-color")
        .description("Border color of the preview panel.")
        .defaultValue(new SettingColor(255, 255, 255, 70))
        .build()
    );

    private final Setting<Boolean> shulkerIconPreview = sgGeneral.add(new BoolSetting.Builder()
        .name("shulker-preview")
        .description("Display a compact icon for shulker boxes instead of the full grid.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> showEmpty = sgGeneral.add(new BoolSetting.Builder()
        .name("show-empty")
        .description("Still show the preview when a container in an item frame (a shulker) is empty.")
        .defaultValue(true)
        .build()
    );

    private final SettingGroup sgShelfTooltip = settings.createGroup("Shelf Tooltip");

    private final Setting<Boolean> shelfTooltip = sgShelfTooltip.add(new BoolSetting.Builder()
        .name("shelf-tooltip")
        .description("When looking at a shelf, show a vanilla-style tooltip of the item on it.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> tripleMode = sgShelfTooltip.add(new BoolSetting.Builder()
        .name("triple-mode")
        .description("Show all three shelf slots at once instead of only the slot under the crosshair.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> showAmount = sgShelfTooltip.add(new BoolSetting.Builder()
        .name("show-amount")
        .description("Show the item's stack count in the tooltip.")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> amountFormat = sgShelfTooltip.add(new StringSetting.Builder()
        .name("amount-format")
        .description("Format for the item count. %amount% is replaced with the count.")
        .defaultValue("%amount%x ")
        .build()
    );

    private final Setting<ShelfTooltip.AmountPosition> amountPosition = sgShelfTooltip.add(new EnumSetting.Builder<ShelfTooltip.AmountPosition>()
        .name("amount-position")
        .description("Where the count goes. The lore positions fall back to 'between' on an item with no lore.")
        .defaultValue(ShelfTooltip.AmountPosition.BEFORE_NAME)
        .build()
    );

    private final Setting<SettingColor> amountColor = sgShelfTooltip.add(new ColorSetting.Builder()
        .name("amount-color")
        .description("Colour of the item count.")
        .defaultValue(new SettingColor(170, 170, 170))
        .build()
    );

    private final Setting<Boolean> showAttributes = sgShelfTooltip.add(new BoolSetting.Builder()
        .name("show-attributes")
        .description("Show the item's attribute modifier lines.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> attributeColor = sgShelfTooltip.add(new ColorSetting.Builder()
        .name("attribute-color")
        .description("Colour applied to the attribute modifier lines.")
        .defaultValue(new SettingColor(170, 170, 170))
        .build()
    );

    private final Setting<Boolean> showPotionEffects = sgShelfTooltip.add(new BoolSetting.Builder()
        .name("show-potion-effects")
        .description("Show the item's potion effect lines.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showTrimUpgrades = sgShelfTooltip.add(new BoolSetting.Builder()
        .name("show-trim-upgrades")
        .description("Show smithing trim material and pattern lines.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showAdvanced = sgShelfTooltip.add(new BoolSetting.Builder()
        .name("show-advanced")
        .description("Append the debug lines vanilla only shows in advanced mode (component ids and the like).")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> advancedColor = sgShelfTooltip.add(new ColorSetting.Builder()
        .name("advanced-color")
        .description("Colour of the advanced/debug lines.")
        .defaultValue(new SettingColor(170, 170, 170))
        .build()
    );

    private final Setting<ShelfTooltip.TooltipPosition> tooltipPosition = sgShelfTooltip.add(new EnumSetting.Builder<ShelfTooltip.TooltipPosition>()
        .name("tooltip-position")
        .description("Which corner or edge the tooltip is anchored to.")
        .defaultValue(ShelfTooltip.TooltipPosition.TOP_MIDDLE)
        .build()
    );

    private final Setting<Double> tooltipScale = sgShelfTooltip.add(new DoubleSetting.Builder()
        .name("tooltip-scale")
        .description("Scale of the whole tooltip.")
        .defaultValue(1.0)
        .min(0.25)
        .max(3.0)
        .sliderRange(0.25, 3.0)
        .build()
    );

    private final Setting<Double> tripleSpacing = sgShelfTooltip.add(new DoubleSetting.Builder()
        .name("spacing")
        .description("Gap between the per-slot tooltips in triple mode.")
        .defaultValue(1.0)
        .min(0.25)
        .max(4.0)
        .sliderRange(0.25, 4.0)
        .build()
    );

    private final Setting<Boolean> transparent = sgShelfTooltip.add(new BoolSetting.Builder()
        .name("transparent")
        .description("Draw the tooltip without its background.")
        .defaultValue(true)
        .build()
    );

    private final ChestTrackerDataV2 data = new ChestTrackerDataV2();
    private BlockPos lastTargetedPos;
    private TrackedContainer lastContainer;
    private World lastWorld;

    private Entity lastEntity;

    private boolean lastShulkerIconSetting = shulkerIconPreview.get();
    private boolean lastShowEmptySetting = showEmpty.get();
    private PeekStyle lastStyle = style.get();

    private PreviewData currentPreview = null;
    private final Vector3d previewVec = new Vector3d();   // reused per frame by onRender2D

    // Shelf tooltip state
    /**
     * Per-slot tooltip lines, parallel to the slots that were targeted.
     */
    private final List<List<TooltipComponent>> shelfBoxes = new ArrayList<>();
    private BlockPos shelfTarget = null;
    private long shelfStamp = Long.MIN_VALUE;

    public static final ThreadLocal<Boolean> IS_RENDERING = ThreadLocal.withInitial(() -> false);

    public boolean isShulkerIconPreviewEnabled() {
        return shulkerIconPreview.get();
    }

    private record PreviewData(
        BlockPos pos,
        Text titleText,
        Text posText,
        int panelWidth,
        int panelHeight,
        List<ItemStack> stacks,
        int itemsPerRow,
        float itemScale,
        Map<Integer, ItemStack> dominantItems,
        PeekStyle style
    ) {}

    public ContainerPeek() {
        super(Addon.CATEGORY, "Container-Peek",
            "Displays the tracked contents from Chest-Tracker when you look at the block, Shelf block too.");
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
        currentPreview = null;
        resetShelfTooltip();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world != lastWorld) {
            lastWorld = mc.world;
            if (mc.world != null) data.loadData();
            lastTargetedPos = null;
            lastContainer = null;
            lastEntity = null;
            currentPreview = null;
            resetShelfTooltip();
        }

        if (mc.player == null || mc.world == null) return;

        if (lastEntity != null && (lastEntity.isRemoved() || !lastEntity.isAlive())) {
            lastEntity = null;
            currentPreview = null;
        }

        // Setting changes
        boolean shulkerSettingChanged = shulkerIconPreview.get() != lastShulkerIconSetting;
        if (shulkerSettingChanged) lastShulkerIconSetting = shulkerIconPreview.get();

        boolean styleChanged = style.get() != lastStyle;
        if (styleChanged) lastStyle = style.get();

        // show-empty is a *decision*, not a render-time toggle: it decides whether the entity
        // branch produces a preview at all. Without folding it into the guard below, toggling it
        // while already aimed at the same item frame would hit
        // `entity == lastEntity && !changed && return` and the panel would not appear until you
        // looked away and back.
        boolean showEmptyChanged = showEmpty.get() != lastShowEmptySetting;
        if (showEmptyChanged) lastShowEmptySetting = showEmpty.get();
        boolean branchInputsChanged = shulkerSettingChanged || styleChanged || showEmptyChanged;

        // Block containers
        if (mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.BLOCK) {
            lastEntity = null;

            BlockHitResult hit = (BlockHitResult) mc.crosshairTarget;
            BlockPos pos = hit.getBlockPos();

            // A shelf is not a grid-preview target - it gets its own tooltip, handled before the
            // panel path below. Claimed first so the chest branch's `pos.equals(lastTargetedPos)`
            // early return can never swallow it: this branch never stores lastTargetedPos, it
            // clears it, so a stale value from an earlier chest can never match a later one.
            if (mc.world.getBlockState(pos).getBlock() instanceof ShelfBlock) {
                lastTargetedPos = null;
                lastContainer = null;
                currentPreview = null;
                // Same range gate as the grid preview below. Without it the two disagreed: lower
                // max-distance and the panel would vanish while a shelf tooltip for the very same
                // block stayed up.
                double shelfDist = Math.sqrt(mc.player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
                if (shelfDist > maxDistance.get()) {
                    resetShelfTooltip();
                    return;
                }
                updateShelfTooltip(pos, hit);
                return;
            }
            resetShelfTooltip();

            if (pos.equals(lastTargetedPos) && !shulkerSettingChanged && !styleChanged) return;
            lastTargetedPos = pos;

            BlockPos canonicalPos = getCanonicalChestPos(pos);

            double dist = Math.sqrt(mc.player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
            if (dist > maxDistance.get()) {
                lastContainer = null;
                currentPreview = null;
                return;
            }

            Block block = mc.world.getBlockState(pos).getBlock();
            if (!(block instanceof ChestBlock || block instanceof BarrelBlock
                || block instanceof ShulkerBoxBlock || block instanceof EnderChestBlock
                || block instanceof DispenserBlock || block instanceof DropperBlock)) {
                lastContainer = null;
                currentPreview = null;
                return;
            }

            String dimension = mc.world.getRegistryKey().getValue().toString();
            ChestTrackerModule tracker = Modules.get().get(ChestTrackerModule.class);
            lastContainer = (tracker != null && tracker.isActive())
                ? tracker.getData().getContainer(canonicalPos, dimension)
                : data.getContainer(canonicalPos, dimension);

            if (lastContainer != null) {
                buildContainerPreview(pos, lastContainer);
            } else {
                currentPreview = null;
            }
            return;
        }

        // Entity item frame
        if (mc.crosshairTarget != null && mc.crosshairTarget instanceof EntityHitResult entityHit) {
            resetShelfTooltip();
            Entity entity = entityHit.getEntity();
            if (entity == lastEntity && !branchInputsChanged) return;

            lastEntity = entity;

            if (entity instanceof ItemFrameEntity frame) {
                ItemStack stack = frame.getHeldItemStack();
                if (stack.isEmpty()) {
                    currentPreview = null;
                    return;
                }
                // Standard container component (shulker box and anything else carrying one).
                // Note this is broader than "shulker": any item with a CONTAINER component gets
                // a peek, so the type label has to be derived rather than hardcoded
                ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
                if (container != null) {
                    List<ItemStack> nonEmpty = container.streamNonEmpty().map(ItemStack::copy).toList();

                    // Empty is a real, visible state
                    if (!nonEmpty.isEmpty() || showEmpty.get()) {
                        String type = entityContainerType(stack);
                        // Title is the stack's real display name, so a renamed shulker shows its
                        // custom name instead of the raw id-ish "shulker_box" string this used to
                        // hardcode and render verbatim as the panel header.
                        String title = stack.getName().getString();
                        buildEntityPreview(entity, nonEmpty, type, title);
                        return;
                    }
                }
            }

            lastEntity = null;
            currentPreview = null;
            return;
        }

        // No target
        lastTargetedPos = null;
        lastContainer = null;
        lastEntity = null;
        currentPreview = null;
        resetShelfTooltip();
    }
    // Shelf tooltip

    /**
     * The settings that can change a tooltip line, and so the ones the line cache is keyed on.
     */
    private ShelfTooltip.ContentConfig shelfContentConfig() {
        return new ShelfTooltip.ContentConfig(
            tripleMode.get(),
            showAmount.get(),
            amountFormat.get(),
            amountPosition.get(),
            amountColor.get(),
            showAttributes.get(),
            attributeColor.get(),
            showPotionEffects.get(),
            showTrimUpgrades.get(),
            showAdvanced.get(),
            advancedColor.get()
        );
    }

    /**
     * The settings that only affect where and how big the finished tooltip is drawn.
     */
    private ShelfTooltip.LayoutConfig shelfLayoutConfig() {
        return new ShelfTooltip.LayoutConfig(
            tooltipPosition.get(),
            tooltipScale.get(),
            tripleSpacing.get(),
            transparent.get()
        );
    }

    private void resetShelfTooltip() {
        shelfBoxes.clear();
        shelfTarget = null;
        // Deliberately not reset to Long.MIN_VALUE: the stamp has to stay stale so the next
        // target is recognised as a change. Clearing it would make the first look at an
        // unchanged shelf skip its rebuild and render nothing.
    }

    /**
     * Builds the cached tooltip lines for the shelf under the crosshair, if anything changed.
     *
     * <p>Runs on the tick, not the frame, and only rebuilds when {@link ShelfTooltip#stamp} says
     * a line-affecting input moved. Layout settings are absent from that stamp on purpose - they
     * are read at draw time, so moving the scale slider must not cost a rebuild.
     */
    private void updateShelfTooltip(BlockPos pos, BlockHitResult hit) {
        if (!shelfTooltip.get()) {
            resetShelfTooltip();
            return;
        }

        ShelfTooltip.ContentConfig config = shelfContentConfig();
        List<ItemStack> stacks = ShelfTooltip.targetStacks(mc.world, pos, hit, config.tripleMode());
        if (stacks == null) {
            resetShelfTooltip();
            return;
        }

        long stamp = ShelfTooltip.stamp(pos, stacks, config);
        if (pos.equals(shelfTarget) && stamp == shelfStamp) return;

        shelfTarget = pos;
        shelfStamp = stamp;
        shelfBoxes.clear();

        if (mc.world == null || mc.player == null) return;

        Item.TooltipContext tooltipContext = Item.TooltipContext.create(mc.world);

        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            List<Text> lines = ShelfTooltip.buildLines(stack, tooltipContext, mc.player, config);
            if (lines.isEmpty()) continue;
            shelfBoxes.add(lines.stream()
                .map(Text::asOrderedText)
                .map(TooltipComponent::of)
                .collect(Collectors.toList()));
        }
    }

    private void renderShelfTooltip(DrawContext context) {
        if (shelfBoxes.isEmpty() || !shelfTooltip.get()) return;
        ShelfTooltip.render(context, mc.textRenderer, shelfBoxes, shelfLayoutConfig(),
            mc.getWindow().getScaledWidth(), mc.getWindow().getScaledHeight());
    }

    private BlockPos getCanonicalChestPos(BlockPos pos) {
        if (mc.world == null) return pos;
        BlockState state = mc.world.getBlockState(pos);
        Block block = state.getBlock();

        if (!(block instanceof ChestBlock || block instanceof TrappedChestBlock)) return pos;
        if (!state.contains(ChestBlock.CHEST_TYPE)) return pos;
        ChestType chestType = state.get(ChestBlock.CHEST_TYPE);
        if (chestType == ChestType.SINGLE) return pos;
        if (!state.contains(ChestBlock.FACING)) return pos;

        Direction facing = state.get(ChestBlock.FACING);
        BlockPos other = pos.offset(
            chestType == ChestType.LEFT ? facing.rotateYClockwise() : facing.rotateYCounterclockwise()
        );

        int cmp = Integer.compare(pos.getX(), other.getX());
        if (cmp == 0) cmp = Integer.compare(pos.getY(), other.getY());
        if (cmp == 0) cmp = Integer.compare(pos.getZ(), other.getZ());
        return cmp <= 0 ? pos : other;
    }

    private void buildContainerPreview(BlockPos pos, TrackedContainer container) {
        List<ItemStack> stacks = container.getItemStacks();
        String title = container.getCustomName() != null ? container.getCustomName() : container.getContainerType();

        Map<Integer, ItemStack> dominantMap = new HashMap<>();

        if (shulkerIconPreview.get()) {
            for (Map.Entry<Integer, String> entry : container.getDominantItems().entrySet()) {
                Identifier id = Identifier.tryParse(entry.getValue());
                if (id == null) continue;
                Item item = Registries.ITEM.get(id);
                if (item != null) {
                    dominantMap.put(entry.getKey(), new ItemStack(item));
                }
            }
        }

        dominantMap.putAll(computeDominantMap(stacks));

        buildPreview(pos, title, stacks, false, dominantMap);
    }

    /**
     * Container type label for an item-frame stack. Only a real ShulkerBoxBlock may be called
     * "shulker_box" the CONTAINER component is carried by other items too, and they were all
     * being filed under the shulker label.
     */
    private static String entityContainerType(ItemStack stack) {
        if (stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock) {
            return "shulker_box";
        }
        return "container";
    }

    private void buildEntityPreview(Entity entity, List<ItemStack> stacks, String type, String title) {
        BlockPos pos = entity.getBlockPos();
        Map<Integer, ItemStack> dominantMap = computeDominantMap(stacks);
        buildPreview(pos, title != null ? title : type, stacks, false, dominantMap);
    }

    private Map<Integer, ItemStack> computeDominantMap(List<ItemStack> stacks) {
        if (!shulkerIconPreview.get()) return Collections.emptyMap();
        Map<Integer, ItemStack> map = new HashMap<>();
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack dominant = getDominantShulkerItem(stacks.get(i));
            if (dominant != null) {
                map.put(i, dominant);
            }
        }
        return map;
    }

    @Nullable
    private ItemStack getDominantShulkerItem(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock)) return null;
        ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
        if (container == null) return null;
        Map<Item, Integer> counts = new HashMap<>();
        container.streamNonEmpty().forEach(s -> counts.merge(s.getItem(), s.getCount(), Integer::sum));
        if (counts.isEmpty()) return null;
        return counts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(e -> new ItemStack(e.getKey()))
            .orElse(null);
    }

    private void buildPreview(BlockPos pos, String title, List<ItemStack> stacks, boolean isShulker,
                              Map<Integer, ItemStack> dominantMap) {
        int itemsPerRow = maxItemsPerRow.get();
        int total = stacks.size();
        int pad = 2;

        // Wrap to the grid this specific container actually draws,
        int rows = Math.max(1, (int) Math.ceil((double) total / itemsPerRow));
        int cols = Math.min(Math.max(total, 1), itemsPerRow);

        int gridWidth;
        int gridHeight;
        if (style.get() == PeekStyle.Container) {
            gridWidth = cols * SLOT_SIZE + (cols + 1) * pad;
            gridHeight = rows * SLOT_SIZE + (rows + 1) * pad;
        } else {
            int iconSizeVal = iconSize.get();
            gridWidth = cols * iconSizeVal + (cols + 1) * pad;
            gridHeight = rows * iconSizeVal + (rows + 1) * pad;
        }

        Text titleText = Text.literal(title);
        Text posText = Text.literal(String.format("%d, %d, %d", pos.getX(), pos.getY(), pos.getZ()));

        // Also make sure the panel is wide enough for the title/position text.
        int textLines = 0;
        int textWidth = 0;
        if (showType.get()) {
            textLines++;
            textWidth = Math.max(textWidth, mc.textRenderer.getWidth(titleText));
        }
        if (showPosition.get()) {
            textLines++;
            textWidth = Math.max(textWidth, mc.textRenderer.getWidth(posText));
        }

        int w = Math.max(gridWidth, textWidth + pad * 2);
        int h = gridHeight + (10 * textLines); // exact: no leftover slack vs the draw loop below

        float itemScale = iconSize.get() / 16.0f;

        currentPreview = new PreviewData(pos, titleText, posText, w, h, stacks,
            itemsPerRow, itemScale, dominantMap, style.get());
    }

    private void renderContainerStyle(DrawContext context, PreviewData preview, Vector3d vec) {
        // Same positioning pipeline as the Panel style: to2D output lives in
        // physical-pixel (unscaled projection) space on the Render2DEvent context.
        double guiScaleX = (double) mc.getWindow().getScaledWidth() / mc.getWindow().getWidth();
        double guiScaleY = (double) mc.getWindow().getScaledHeight() / mc.getWindow().getHeight();

        int screenX = (int) (vec.x * guiScaleX);
        int screenY = (int) (vec.y * guiScaleY);

        int panelX = screenX - preview.panelWidth / 2;
        int panelY = screenY - preview.panelHeight - 20;

        if (panelX < 0) panelX = 0;
        if (panelY < 0) panelY = 0;
        if (panelX + preview.panelWidth > mc.getWindow().getScaledWidth())
            panelX = mc.getWindow().getScaledWidth() - preview.panelWidth;
        if (panelY + preview.panelHeight > mc.getWindow().getScaledHeight())
            panelY = mc.getWindow().getScaledHeight() - preview.panelHeight;

        int pad = 2;

        // Plain fill first so the panel is always visible even if the vanilla
        // tooltip background can't be drawn in this context.
        context.fill(panelX, panelY, panelX + preview.panelWidth, panelY + preview.panelHeight, backgroundColor.get().getPacked());

        try {
            TooltipBackgroundRenderer.render(context, panelX, panelY, preview.panelWidth, preview.panelHeight, null);
        } catch (RuntimeException ignored) {
        }

        int textY = panelY + pad;

        if (showType.get()) {
            context.drawTextWithShadow(mc.textRenderer, preview.titleText, panelX + pad, textY, 0xFFFFFFFF);
            textY += 10;
        }
        if (showPosition.get()) {
            context.drawTextWithShadow(mc.textRenderer, preview.posText, panelX + pad, textY, 0xFFCCCCCC);
            textY += 10;
        }

        ContainerPeek.IS_RENDERING.set(true);

        int itemStartX = panelX + pad;
        int itemStartY = textY + pad;

        for (int i = 0; i < preview.stacks.size(); i++) {
            int col = i % preview.itemsPerRow;
            int row = i / preview.itemsPerRow;
            int x = itemStartX + col * (SLOT_SIZE + pad);
            int y = itemStartY + row * (SLOT_SIZE + pad);

            drawSlotBackground(context, x, y);

            ItemStack stack = preview.stacks.get(i);
            if (!stack.isEmpty()) {
                RenderUtils.drawItem(context, stack, x + 1, y + 1, 1.0f, true, null, false);
            }

            ItemStack dominant = preview.dominantItems.get(i);
            if (dominant != null) {
                int border = 1;
                int overlaySize = SLOT_SIZE - 2 * border;
                float overlayScale = overlaySize / 16.0f;
                RenderUtils.drawItem(context, dominant, x + border, y + border, overlayScale, false, null, false);
            }
        }

        ContainerPeek.IS_RENDERING.set(false);
    }

    private void drawSlotBackground(DrawContext context, int x, int y) {
        try {
            context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, SLOT_TEXTURE, x, y, SLOT_SIZE, SLOT_SIZE);
        } catch (RuntimeException e) {
            // Fall back to the bevelled grey slot rendering used by the tracker screens.
            context.fill(x, y, x + SLOT_SIZE, y + SLOT_SIZE, 0xFF8B8B8B);
            context.fill(x, y, x + SLOT_SIZE - 1, y + 1, 0xFFFFFFFF);
            context.fill(x, y, x + 1, y + SLOT_SIZE - 1, 0xFFFFFFFF);
            context.fill(x + SLOT_SIZE - 1, y + 1, x + SLOT_SIZE, y + SLOT_SIZE, 0xFF565656);
            context.fill(x + 1, y + SLOT_SIZE - 1, x + SLOT_SIZE, y + SLOT_SIZE, 0xFF565656);
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        DrawContext context = event.drawContext;
        if (context == null) return;

        // The shelf tooltip and the grid preview are mutually exclusive by construction: a shelf
        // is not in the preview allowlist, and the tick branch claims it before the panel path
        // runs. Drawn first, and only when there is no panel to show.
        PreviewData preview = this.currentPreview;
        if (preview == null) {
            renderShelfTooltip(context);
            return;
        }

        Vector3d vec = previewVec;
        vec.set(preview.pos.getX() + 0.5, preview.pos.getY() + 0.5, preview.pos.getZ() + 0.5);
        if (!NametagUtils.to2D(vec, 1.0)) return;

        if (preview.style == PeekStyle.Container) {
            renderContainerStyle(context, preview, vec);
            return;
        }

        // Convert to2D's output into scaled-GUI space. Computed live every frame,
        // so itself corrects regardless of your GUI scale setting (Auto/1/2/3/etc).
        double guiScaleX = (double) mc.getWindow().getScaledWidth() / mc.getWindow().getWidth();
        double guiScaleY = (double) mc.getWindow().getScaledHeight() / mc.getWindow().getHeight();

        int screenX = (int) (vec.x * guiScaleX);
        int screenY = (int) (vec.y * guiScaleY);

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

        if (showType.get()) {
            context.drawTextWithShadow(mc.textRenderer, preview.titleText, panelX + pad, textY, 0xFFFFFFFF);
            textY += 10;
        }
        if (showPosition.get()) {
            context.drawTextWithShadow(mc.textRenderer, preview.posText, panelX + pad, textY, 0xFFCCCCCC);
            textY += 10;
        }

        ContainerPeek.IS_RENDERING.set(true);

        int itemStartX = panelX + pad;
        int itemStartY = textY + pad;
        float itemScale = preview.itemScale;
        int iconSizeVal = iconSize.get();

        for (int i = 0; i < preview.stacks.size(); i++) {
            int col = i % preview.itemsPerRow;
            int row = i / preview.itemsPerRow;
            int x = itemStartX + col * (iconSizeVal + pad);
            int y = itemStartY + row * (iconSizeVal + pad);

            RenderUtils.drawItem(event.drawContext, preview.stacks.get(i), x, y, itemScale, true, null, false);

            ItemStack dominant = preview.dominantItems.get(i);
            if (dominant != null) {
                int border = 1;
                int overlaySize = iconSizeVal - 2 * border;
                float overlayScale = overlaySize / 16.0f;
                int overlayX = x + border;
                int overlayY = y + border;

                RenderUtils.drawItem(event.drawContext, dominant, overlayX, overlayY, overlayScale, false, null, false);
            }
        }

        ContainerPeek.IS_RENDERING.set(false);
    }
}
