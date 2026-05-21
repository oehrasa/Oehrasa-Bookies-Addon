package com.AutoBookshelf.addon.hud;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.render.MapRenderState;
import net.minecraft.client.render.MapRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.map.MapState;
import net.minecraft.util.hit.EntityHitResult;
import org.jetbrains.annotations.Nullable;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class MapViewer extends HudElement {
    public static final HudElementInfo<MapViewer> INFO = new HudElementInfo<>(
        Addon.HUD_GROUP,
        "Map-Viewer",
        "Displays the contents of held/item maps frame onto your HUD.",
        MapViewer::new
    );

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public enum Mode { Held, Mainhand, Offhand, ItemFrame, SlotIndex, MapId }
    public enum DualHandling { ShowBoth, PrioritizeMainhand, PrioritizeOffhand }
    public enum Orientation { Horizontal, Vertical }

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("How to determine which map is to render.")
        .defaultValue(Mode.Held)
        .build()
    );
    private final Setting<DualHandling> dualHandling = sgGeneral.add(new EnumSetting.Builder<DualHandling>()
        .name("dual-handling")
        .description("What to do when both hands hold different maps.")
        .visible(() -> mode.get() == Mode.Held)
        .defaultValue(DualHandling.ShowBoth)
        .build()
    );
    private final Setting<Boolean> showGap = sgGeneral.add(new BoolSetting.Builder()
        .name("show-gap")
        .description("Add a gap between two maps when both are shown.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Orientation> orientation = sgGeneral.add(new EnumSetting.Builder<Orientation>()
        .name("orientation")
        .description("Arrangement of two maps when both are shown.")
        .defaultValue(Orientation.Horizontal)
        .build()
    );
    private final Setting<Integer> slotIndex = sgGeneral.add(new IntSetting.Builder()
        .name("slot-index")
        .description("Which inventory slot to grab the map from.")
        .visible(() -> mode.get() == Mode.SlotIndex)
        .defaultValue(0).sliderRange(0, 40)
        .build()
    );
    private final Setting<Integer> mapId = sgGeneral.add(new IntSetting.Builder()
        .name("map-id")
        .description("Which map ID to render. Must be in your inventory!")
        .visible(() -> mode.get() == Mode.MapId)
        .defaultValue(0).noSlider()
        .build()
    );
    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("How big to render the map.")
        .defaultValue(1.0).min(0.5)
        .sliderRange(0.5, 5).build()
    );
    private final Setting<Boolean> background = sgGeneral.add(new BoolSetting.Builder()
        .name("background")
        .description("Draw a background behind the map.")
        .defaultValue(false)
        .build()
    );
    private final Setting<SettingColor> backgroundColor = sgGeneral.add(new ColorSetting.Builder()
        .name("background-color")
        .description("Background color.")
        .visible(background::get)
        .defaultValue(new SettingColor(25, 25, 25, 50))
        .build()
    );

    // State
    @Nullable private MapIdComponent mapId1, mapId2;
    @Nullable private MapState mapData1, mapData2;
    private boolean showSecondMap = false;
    private final MapRenderState renderState1 = new MapRenderState();
    private final MapRenderState renderState2 = new MapRenderState();

    public MapViewer() {
        super(INFO);
    }

    @Override
    public void tick(HudRenderer renderer) {
        double s = scale.get();
        double mapSize = 128 * s;
        double gap = showGap.get() ? 4 * s : 0;

        // Update size based on orientation and number of maps
        if (showSecondMap) {
            if (orientation.get() == Orientation.Horizontal) {
                setSize(2 * mapSize + gap, mapSize);
            } else {
                setSize(mapSize, 2 * mapSize + gap);
            }
        } else {
            setSize(mapSize, mapSize);
        }

        if (!Utils.canUpdate()) return;

        mapId1 = mapId2 = null;
        mapData1 = mapData2 = null;
        showSecondMap = false;

        ItemStack stack1 = ItemStack.EMPTY;
        ItemStack stack2 = ItemStack.EMPTY;

        switch (mode.get()) {
            case Held -> {
                stack1 = mc.player.getMainHandStack();
                stack2 = mc.player.getOffHandStack();
                boolean hasMain = isMap(stack1);
                boolean hasOff  = isMap(stack2);

                if (hasMain && hasOff) {
                    if (areMapsDifferent(stack1, stack2)) {
                        if (dualHandling.get() == DualHandling.ShowBoth) {
                            mapId1 = getMapId(stack1); mapData1 = getMapData(mapId1);
                            mapId2 = getMapId(stack2); mapData2 = getMapData(mapId2);
                            showSecondMap = true;
                            return;
                        } else if (dualHandling.get() == DualHandling.PrioritizeMainhand) {
                            stack2 = ItemStack.EMPTY;
                        } else {
                            stack1 = stack2;
                        }
                    } else {
                        stack2 = ItemStack.EMPTY;
                    }
                } else if (hasMain) {
                    stack2 = ItemStack.EMPTY;
                } else if (hasOff) {
                    stack1 = stack2;
                }
            }
            case Mainhand -> stack1 = mc.player.getMainHandStack();
            case Offhand  -> stack1 = mc.player.getOffHandStack();
            case ItemFrame -> {
                if (mc.crosshairTarget instanceof EntityHitResult entityHit
                    && entityHit.getEntity() instanceof ItemFrameEntity frame) {
                    stack1 = frame.getHeldItemStack();
                }
            }
            case SlotIndex -> stack1 = mc.player.getInventory().getStack(slotIndex.get());
            case MapId -> {
                for (int i = 0; i < mc.player.getInventory().size(); i++) {
                    ItemStack stack = mc.player.getInventory().getStack(i);
                    if (isMap(stack) && getMapId(stack).id() == mapId.get()) {
                        stack1 = stack; break;
                    }
                }
            }
        }

        if (isMap(stack1)) {
            mapId1 = getMapId(stack1);
            mapData1 = getMapData(mapId1);
        }
    }

    @Override
    public void render(HudRenderer renderer) {
        if (mapId1 == null || mapData1 == null) return;

        double s = scale.get();
        double mapSize = 128 * s;
        double gap = showGap.get() ? 4 * s : 0;
        double x1 = this.x;
        double y1 = this.y;

        // Main map
        drawMap(renderer, mapId1, mapData1, renderState1, x1, y1, s);

        // Second map (dual hand) positioned according to orientation
        if (showSecondMap && mapId2 != null && mapData2 != null) {
            double x2, y2;
            if (orientation.get() == Orientation.Horizontal) {
                x2 = x1 + mapSize + gap;
                y2 = y1;
            } else {
                x2 = x1;
                y2 = y1 + mapSize + gap;
            }
            drawMap(renderer, mapId2, mapData2, renderState2, x2, y2, s);
        }
    }

    /**
     * Renders a single map using MapRenderState pipeline.
     * Background is drawn at absolute screen coordinates (unaffected by matrix).
     */
    private void drawMap(HudRenderer renderer, MapIdComponent id, MapState data,
                         MapRenderState renderState, double x, double y, double scale) {
        MapRenderer mapRenderer = mc.getMapRenderer();

        // Background placed at absolute position before matrix transformations
        if (background.get()) {
            double mapSize = 128 * scale;
            renderer.quad(x, y, mapSize, mapSize, backgroundColor.get());
        }

        MatrixStack matrices = renderer.drawContext.getMatrices();
        VertexConsumerProvider.Immediate vertexConsumers = mc.getBufferBuilders().getEntityVertexConsumers();

        // Populate render state with live map data
        mapRenderer.update(id, data, renderState);

        matrices.push();
        matrices.translate(x, y, 0.0);
        matrices.scale((float) scale, (float) scale, 1.0F);

        // Draw the map
        mapRenderer.draw(renderState, matrices, vertexConsumers, false, 0xF000F0);

        matrices.pop();

        // Flush geometry so it appears on screen
        vertexConsumers.draw();
    }

    private boolean isMap(ItemStack stack) {
        return stack.isOf(Items.FILLED_MAP) && stack.contains(DataComponentTypes.MAP_ID);
    }

    private MapIdComponent getMapId(ItemStack stack) {
        return stack.get(DataComponentTypes.MAP_ID);
    }

    @Nullable
    private MapState getMapData(@Nullable MapIdComponent component) {
        if (component == null) return null;
        return FilledMapItem.getMapState(component, mc.world);
    }

    private boolean areMapsDifferent(ItemStack a, ItemStack b) {
        MapIdComponent idA = getMapId(a);
        MapIdComponent idB = getMapId(b);
        if (idA == null || idB == null) return false;
        return idA.id() != idB.id();
    }
}
