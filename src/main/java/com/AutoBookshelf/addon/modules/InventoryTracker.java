package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.*;

public class InventoryTracker extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Map<UUID, TrackedInventory> inventoryMap = new HashMap<>();
    public final Map<UUID, PlayerEntity> playerMap = new HashMap<>();
    private final Map<UUID, ItemStack[]> lastEquipment = new HashMap<>();
    private final Map<UUID, PreviewData> renderPreviews = new HashMap<>();

    private final Setting<Boolean> savePlayers = sgGeneral.add(new BoolSetting.Builder()
        .name("save-players")
        .description("Keep a reference to players that have left render distance.")
        .defaultValue(false)
        .onChanged(v -> playerMap.clear())
        .build()
    );

    private final Setting<Boolean> render = sgGeneral.add(new BoolSetting.Builder()
        .name("render-tracked-inventory")
        .description("Draws a grid of each tracked player's held-item history above their head.")
        .defaultValue(true)
        .onChanged(v -> {
            if (!v) renderPreviews.clear();
        })
        .build()
    );

    private final Setting<Integer> maxRenderDistance = sgGeneral.add(new IntSetting.Builder()
        .name("max-render-distance")
        .description("Maximum distance to render the tracked inventory grid.")
        .defaultValue(24)
        .min(1)
        .max(64)
        .sliderRange(1, 64)
        .build()
    );

    private final Setting<Integer> iconSize = sgGeneral.add(new IntSetting.Builder()
        .name("icon-size")
        .description("Size of each item icon in the tracked inventory grid.")
        .defaultValue(12)
        .min(8)
        .max(20)
        .sliderRange(8, 20)
        .build()
    );

    private final Setting<Integer> itemsPerRow = sgGeneral.add(new IntSetting.Builder()
        .name("items-per-row")
        .description("Number of items per row in the tracked inventory grid.")
        .defaultValue(9)
        .min(1)
        .max(18)
        .sliderRange(1, 18)
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgGeneral.add(new ColorSetting.Builder()
        .name("background-color")
        .description("Background color of the tracked inventory panel.")
        .defaultValue(new SettingColor(0, 0, 0, 70))
        .build()
    );

    private final Setting<SettingColor> borderColor = sgGeneral.add(new ColorSetting.Builder()
        .name("border-color")
        .description("Border color of the tracked inventory panel.")
        .defaultValue(new SettingColor(255, 255, 255, 70))
        .build()
    );

    public InventoryTracker() {
        super(Addon.CATEGORY, "Inventory-Tracker",
            "Tracks and shows the equipment/slot history of other players in render distance (use .invsee to open screen).");
    }

    @Override
    public void onDeactivate() {
        reset();
    }

    @Nullable
    public TrackedInventory getTracked(UUID uuid) {
        return inventoryMap.get(uuid);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        Set<UUID> seenUUIDs = new HashSet<>();

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (!(player instanceof OtherClientPlayerEntity)) continue;

            UUID uuid = player.getUuid();
            seenUUIDs.add(uuid);

            ItemStack[] current = snapshotEquipment(player);
            ItemStack[] last = lastEquipment.get(uuid);

            if (last == null) {
                lastEquipment.put(uuid, copySnapshot(current));
                continue;
            }

            for (int i = 0; i < current.length; i++) {
                if (ItemStack.areItemsAndComponentsEqual(current[i], last[i])) continue;

                EquipmentSlot slot = indexToSlot(i);

                if (slot == EquipmentSlot.BODY) continue;

                ItemStack newStack = current[i];
                ItemStack mainHandStack = (slot == EquipmentSlot.MAINHAND) ? newStack : player.getMainHandStack();

                TrackedInventory tracked = inventoryMap.computeIfAbsent(uuid, k -> new TrackedInventory());

                if (savePlayers.get()) {
                    playerMap.put(uuid, player);
                }

                // Only the slot that actually changed gets pushed into history.
                tracked.update(newStack, slot);

                injectTrackedItems(player.getInventory(), tracked, mainHandStack);
                buildRenderPreview(uuid, player, tracked);

                last[i] = current[i].copy();
            }
        }

        lastEquipment.keySet().retainAll(seenUUIDs);
        renderPreviews.keySet().retainAll(seenUUIDs);
    }

    private void injectTrackedItems(PlayerInventory inventory, TrackedInventory tracked, ItemStack mainHand) {
        List<ItemStack> items = tracked.items;
        boolean mainHandEmpty = mainHand.isEmpty();
        int start = mainHandEmpty ? 0 : 1;
        int end = mainHandEmpty ? 34 : 35;
        int offset = mainHandEmpty ? 1 : 0;

        for (int i = start; i <= end; i++) {
            int itemIndex = i - start;
            ItemStack fill = itemIndex < items.size() ? items.get(itemIndex).copy() : ItemStack.EMPTY;
            inventory.getMainStacks().set(i + offset, fill);
        }
    }

    private ItemStack[] snapshotEquipment(PlayerEntity player) {
        return new ItemStack[]{
            player.getMainHandStack(),
            player.getOffHandStack(),
            player.getEquippedStack(EquipmentSlot.HEAD),
            player.getEquippedStack(EquipmentSlot.CHEST),
            player.getEquippedStack(EquipmentSlot.LEGS),
            player.getEquippedStack(EquipmentSlot.FEET)
        };
    }

    private ItemStack[] copySnapshot(ItemStack[] src) {
        ItemStack[] copy = new ItemStack[src.length];
        for (int i = 0; i < src.length; i++) copy[i] = src[i].copy();
        return copy;
    }

    private EquipmentSlot indexToSlot(int i) {
        return switch (i) {
            case 0 -> EquipmentSlot.MAINHAND;
            case 1 -> EquipmentSlot.OFFHAND;
            case 2 -> EquipmentSlot.HEAD;
            case 3 -> EquipmentSlot.CHEST;
            case 4 -> EquipmentSlot.LEGS;
            case 5 -> EquipmentSlot.FEET;
            default -> EquipmentSlot.MAINHAND;
        };
    }

    private void reset() {
        if (mc.world != null) {
            Map<UUID, PlayerEntity> live = new HashMap<>();
            for (PlayerEntity p : mc.world.getPlayers()) live.put(p.getUuid(), p);

            for (UUID uuid : inventoryMap.keySet()) {
                PlayerEntity player = live.get(uuid);
                if (player == null) continue;
                PlayerInventory inv = player.getInventory();
                for (int i = 1; i < inv.getMainStacks().size(); i++) {
                    inv.getMainStacks().set(i, ItemStack.EMPTY);
                }
            }
        }
        inventoryMap.clear();
        playerMap.clear();
        lastEquipment.clear();
        renderPreviews.clear();
    }

    private record PreviewData(
        PlayerEntity player,
        Text nameText,
        int panelWidth,
        int panelHeight,
        List<ItemStack> stacks,
        int itemsPerRowUsed,
        float itemScale
    ) {
    }

    private void buildRenderPreview(UUID uuid, PlayerEntity player, TrackedInventory tracked) {
        if (!render.get()) return;

        List<ItemStack> stacks = tracked.items;
        if (stacks.isEmpty()) {
            renderPreviews.remove(uuid);
            return;
        }

        int perRow = itemsPerRow.get();
        int total = stacks.size();
        int size = iconSize.get();
        int pad = 2;

        int rows = Math.max(1, (int) Math.ceil((double) total / perRow));
        int cols = Math.min(Math.max(total, 1), perRow);

        int gridWidth = cols * size + (cols + 1) * pad;
        int gridHeight = rows * size + (rows + 1) * pad;

        Text nameText = Text.literal(player.getName().getString());
        int textWidth = mc.textRenderer.getWidth(nameText);

        int w = Math.max(gridWidth, textWidth + pad * 2);
        int h = gridHeight + 10; // one text line for the player's name

        float itemScale = size / 16.0f;

        renderPreviews.put(uuid, new PreviewData(player, nameText, w, h, stacks, perRow, itemScale));
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!render.get() || mc.world == null || mc.player == null || renderPreviews.isEmpty()) return;

        DrawContext context = event.drawContext;
        if (context == null) return;

        for (PreviewData preview : renderPreviews.values()) {
            PlayerEntity player = preview.player();
            if (player == null || player.isRemoved() || !player.isAlive()) continue;

            double dist = mc.player.distanceTo(player);
            if (dist > maxRenderDistance.get()) continue;

            Vector3d vec = new Vector3d(player.getX(), player.getEyeY() + 0.6, player.getZ());
            if (!NametagUtils.to2D(vec, 1.0)) continue;

            // Recomputed live every frame
            double guiScaleX = (double) mc.getWindow().getScaledWidth() / mc.getWindow().getWidth();
            double guiScaleY = (double) mc.getWindow().getScaledHeight() / mc.getWindow().getHeight();

            int screenX = (int) (vec.x * guiScaleX);
            int screenY = (int) (vec.y * guiScaleY);

            int panelX = screenX - preview.panelWidth() / 2;
            int panelY = screenY - preview.panelHeight() - 20;

            if (panelX < 0) panelX = 0;
            if (panelY < 0) panelY = 0;
            if (panelX + preview.panelWidth() > mc.getWindow().getScaledWidth())
                panelX = mc.getWindow().getScaledWidth() - preview.panelWidth();
            if (panelY + preview.panelHeight() > mc.getWindow().getScaledHeight())
                panelY = mc.getWindow().getScaledHeight() - preview.panelHeight();

            drawTrackedPanel(context, preview, panelX, panelY);
        }
    }

    private void drawTrackedPanel(DrawContext context, PreviewData preview, int panelX, int panelY) {
        int pad = 2;

        context.fill(panelX, panelY, panelX + preview.panelWidth(), panelY + preview.panelHeight(), backgroundColor.get().getPacked());
        context.fill(panelX, panelY, panelX + preview.panelWidth(), panelY + 1, borderColor.get().getPacked());
        context.fill(panelX, panelY + preview.panelHeight() - 1, panelX + preview.panelWidth(), panelY + preview.panelHeight(), borderColor.get().getPacked());
        context.fill(panelX, panelY, panelX + 1, panelY + preview.panelHeight(), borderColor.get().getPacked());
        context.fill(panelX + preview.panelWidth() - 1, panelY, panelX + preview.panelWidth(), panelY + preview.panelHeight(), borderColor.get().getPacked());

        int textY = panelY + pad;
        context.drawTextWithShadow(mc.textRenderer, preview.nameText(), panelX + pad, textY, 0xFFFFFFFF);
        textY += 10;

        int itemStartX = panelX + pad;
        int itemStartY = textY + pad;
        int size = iconSize.get();

        for (int i = 0; i < preview.stacks().size(); i++) {
            int col = i % preview.itemsPerRowUsed();
            int row = i / preview.itemsPerRowUsed();
            int x = itemStartX + col * (size + pad);
            int y = itemStartY + row * (size + pad);

            RenderUtils.drawItem(context, preview.stacks().get(i), x, y, preview.itemScale(), true, null, false);
        }
    }

    public static class TrackedInventory {
        public final List<ItemStack> items = new ArrayList<>();
        public final Object2LongOpenHashMap<ItemStack> timeMap = new Object2LongOpenHashMap<>();

        public void update(ItemStack newStack, EquipmentSlot slot) {
            prune();
            if (newStack.isEmpty()) return;

            Iterator<ItemStack> it = items.iterator();
            while (it.hasNext()) {
                ItemStack existing = it.next();
                if (ItemStack.areItemsAndComponentsEqual(existing, newStack)) {
                    it.remove();
                    timeMap.removeLong(existing);
                }
            }

            if (slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND) {
                ItemStack copy = newStack.copy();
                items.add(0, copy);
                timeMap.put(copy, System.currentTimeMillis());
                if (items.size() > 36) {
                    ItemStack removed = items.remove(items.size() - 1);
                    timeMap.removeLong(removed);
                }
            }
        }

        private void prune() {
            Iterator<ItemStack> it = items.iterator();
            while (it.hasNext()) {
                ItemStack s = it.next();
                if (s.getCount() == 0) {
                    it.remove();
                    timeMap.removeLong(s);
                }
            }
        }
    }
}
