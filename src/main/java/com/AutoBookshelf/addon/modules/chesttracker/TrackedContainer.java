package com.AutoBookshelf.addon.modules.chesttracker;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class TrackedContainer {
    private final BlockPos position;
    private final String dimension;
    private String customName;
    private final Map<String, Integer> items = new LinkedHashMap<>();
    private final List<ItemStack> itemStacks;
    private long lastUpdated;
    private String containerType;
    private final Map<Integer, String> dominantItems = new HashMap<>();   // slot index -> item ID string

    public TrackedContainer(BlockPos position, String dimension, String containerType) {
        this.position = position;
        this.dimension = dimension;
        this.containerType = containerType;
        this.itemStacks = new ArrayList<>();
        this.customName = null;
        this.lastUpdated = System.currentTimeMillis();
    }

    public void updateContents(List<ItemStack> stacks) {
        items.clear();
        itemStacks.clear();
        dominantItems.clear();

        int index = 0;
        for (ItemStack stack : stacks) {
            if (stack != null && !stack.isEmpty()) {
                String itemId = Registries.ITEM.getId(stack.getItem()).toString();
                items.put(itemId, items.getOrDefault(itemId, 0) + stack.getCount());
                itemStacks.add(stack.copy());

                // Compute dominant item for shulker boxes
                String dominantId = getDominantShulkerItemId(stack);

                if (dominantId != null) {
                    dominantItems.put(index, dominantId);
                }
            } else {
                itemStacks.add(ItemStack.EMPTY);
            }
            index++;
        }
        this.lastUpdated = System.currentTimeMillis();
    }

    public boolean containsItem(String itemId) { return items.containsKey(itemId); }
    public boolean containsItem(Item item) {
        return items.containsKey(Registries.ITEM.getId(item).toString());
    }
    public int getItemCount(String itemId) { return items.getOrDefault(itemId, 0); }
    public Map<String, Integer> getItems() { return new HashMap<>(items); }
    public List<ItemStack> getItemStacks() {
        // If we already have stacks (e.g. freshly tracked), return them directly
        if (!itemStacks.isEmpty()) {
            return new ArrayList<>(itemStacks);
        }

        // Reconstruct from items map (after loading from JSON)
        List<ItemStack> stacks = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : items.entrySet()) {
            Identifier id = Identifier.tryParse(entry.getKey());
            if (id == null) continue;
            Item item = Registries.ITEM.get(id);
            if (item == null) continue;               // mod removed?
            int count = entry.getValue();
            int maxStack = item.getMaxCount();
            while (count > 0) {
                int stackSize = Math.min(count, maxStack);
                stacks.add(new ItemStack(item, stackSize));
                count -= stackSize;
            }
        }
        // Cache the result so we don't rebuild every frame
        itemStacks.clear();
        itemStacks.addAll(stacks);
        return stacks;
    }
    public BlockPos getPosition() { return position; }
    public String getDimension() { return dimension; }
    public String getCustomName() { return customName; }
    public void setCustomName(String name) { this.customName = name; }
    public long getLastUpdated() { return lastUpdated; }
    public String getContainerType() { return containerType; }
    public boolean isEmpty() { return items.isEmpty(); }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("x", position.getX());
        json.addProperty("y", position.getY());
        json.addProperty("z", position.getZ());
        json.addProperty("dimension", dimension);
        json.addProperty("type", containerType);
        json.addProperty("lastUpdated", lastUpdated);
        if (customName != null) {
            json.addProperty("customName", customName);
        }
        JsonObject itemsJson = new JsonObject();
        for (Map.Entry<String, Integer> entry : items.entrySet()) {
            itemsJson.addProperty(entry.getKey(), entry.getValue());
        }
        json.add("items", itemsJson);
        if (!dominantItems.isEmpty()) {
            JsonObject domJson = new JsonObject();
            for (Map.Entry<Integer, String> entry : dominantItems.entrySet()) {
                domJson.addProperty(entry.getKey().toString(), entry.getValue());
            }
            json.add("dominantItems", domJson);
        }
        JsonArray slotArray = new JsonArray();
        for (ItemStack stack : itemStacks) {
            if (stack == null || stack.isEmpty()) {
                slotArray.add("minecraft:air");
            } else {
                slotArray.add(Registries.ITEM.getId(stack.getItem()).toString());
            }
        }
        json.add("slotContents", slotArray);
        return json;
    }

    public static TrackedContainer fromJson(JsonObject json) {
        BlockPos pos = new BlockPos(
            json.get("x").getAsInt(),
            json.get("y").getAsInt(),
            json.get("z").getAsInt()
        );
        String dimension = json.get("dimension").getAsString();
        String type = json.has("type") ? json.get("type").getAsString() : "chest";
        TrackedContainer container = new TrackedContainer(pos, dimension, type);
        if (json.has("customName")) {
            container.customName = json.get("customName").getAsString();
        }
        if (json.has("lastUpdated")) {
            container.lastUpdated = json.get("lastUpdated").getAsLong();
        }
        if (json.has("items")) {
            JsonObject itemsJson = json.getAsJsonObject("items");
            for (String key : itemsJson.keySet()) {
                container.items.put(key, itemsJson.get(key).getAsInt());
            }
        }
        if (json.has("slotContents")) {
            JsonArray slotArray = json.getAsJsonArray("slotContents");
            for (JsonElement element : slotArray) {
                String idStr = element.getAsString();
                if ("minecraft:air".equals(idStr)) {
                    container.itemStacks.add(ItemStack.EMPTY);
                } else {
                    Identifier id = Identifier.tryParse(idStr);
                    if (id != null) {
                        Item item = Registries.ITEM.get(id);
                        if (item != null) {
                            container.itemStacks.add(new ItemStack(item));
                        } else {
                            container.itemStacks.add(ItemStack.EMPTY);
                        }
                    } else {
                        container.itemStacks.add(ItemStack.EMPTY);
                    }
                }
            }
        }
        if (json.has("dominantItems")) {
            JsonObject domJson = json.getAsJsonObject("dominantItems");
            for (Map.Entry<String, JsonElement> entry : domJson.entrySet()) {
                try {
                    int slot = Integer.parseInt(entry.getKey());
                    String id = entry.getValue().getAsString();
                    container.dominantItems.put(slot, id);
                } catch (NumberFormatException ignored) {
                }
            }
        }

        return container;
    }

    @Nullable
    private static String getDominantShulkerItemId(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock)) return null;
        ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
        if (container == null) return null;
        Map<Item, Integer> counts = new HashMap<>();
        container.streamNonEmpty().forEach(s -> counts.merge(s.getItem(), s.getCount(), Integer::sum));
        if (counts.isEmpty()) return null;
        return counts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(e -> Registries.ITEM.getId(e.getKey()).toString())
            .orElse(null);
    }

    public Map<Integer, String> getDominantItems() {
        return new HashMap<>(dominantItems);
    }

    public String getDisplayName() {
        if (customName != null && !customName.isEmpty()) return customName;
        return String.format("%s [%d, %d, %d]",
            containerType.substring(0, 1).toUpperCase() + containerType.substring(1),
            position.getX(), position.getY(), position.getZ());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof TrackedContainer other)) return false;
        return position.equals(other.position) && dimension.equals(other.dimension);
    }

    @Override
    public int hashCode() {
        return position.hashCode() * 31 + dimension.hashCode();
    }
}
