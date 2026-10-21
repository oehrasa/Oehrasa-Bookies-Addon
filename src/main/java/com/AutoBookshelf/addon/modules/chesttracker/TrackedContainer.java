package com.AutoBookshelf.addon.modules.chesttracker;

import com.AutoBookshelf.addon.utils.ItemSignature;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
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
    // Enchantment-aware index: ItemSignature -> count. Additive alongside
    // `items` (item id -> count); `items` stays the authority for search-by-id
    // and for hasSameContents(), so this never changes existing behaviour.
    // Not present in databases written before this was added -> those load with
    // an empty map and only gain signatures once the container is re-opened.
    private final Map<String, Integer> sigCounts = new HashMap<>();

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
        sigCounts.clear();

        int index = 0;
        for (ItemStack stack : stacks) {
            if (stack != null && !stack.isEmpty()) {
                itemStacks.add(stack.copy());

                // Compute dominant item for shulker boxes (used as the icon for
                // this slot in the browser UI)
                String dominantId = getDominantShulkerItemId(stack);
                if (dominantId != null) {
                    dominantItems.put(index, dominantId);
                }
            } else {
                itemStacks.add(ItemStack.EMPTY);
            }
            index++;
        }

        // Flatten top-level items AND anything nested inside tracked shulker
        // boxes into a single searchable id -> count map.
        items.putAll(computeItemCounts(stacks));

        // Same flattening, but keyed on the enchantment-aware signature so a
        // caller can search for one exact enchanted stack rather than every
        // stack of that item.
        sigCounts.putAll(computeSignatures(stacks));

        this.lastUpdated = System.currentTimeMillis();
    }

    /**
     * Builds an id -> count map from a slot list, unpacking any shulker box
     * stacks so their contents are counted alongside top-level items. Static
     * so it can be reused by hasSameContents() to compare a fresh snapshot
     * against what's already stored without mutating this container.
     */
    public static Map<String, Integer> computeItemCounts(List<ItemStack> stacks) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) continue;
            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            counts.merge(itemId, stack.getCount(), Integer::sum);
            addShulkerContents(stack, counts);
        }
        return counts;
    }

    /**
     * Enchantment-aware counterpart of {@link #computeItemCounts}: walks the same
     * slot list (including nested shulker contents) but keys on the stack's
     * {@link ItemSignature} instead of its item id, so an enchanted stack is
     * distinguishable from a plain one of the same item.
     */
    public static Map<String, Integer> computeSignatures(List<ItemStack> stacks) {
        Map<String, Integer> sigs = new LinkedHashMap<>();
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) continue;
            String sig = ItemSignature.of(stack);
            if (!sig.isEmpty()) sigs.merge(sig, stack.getCount(), Integer::sum);
            addShulkerSignatures(stack, sigs);
        }
        return sigs;
    }

    private static void addShulkerSignatures(ItemStack stack, Map<String, Integer> sigs) {
        if (!(stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock)) return;
        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        if (container == null) return;
        container.nonEmptyItemCopyStream().forEach(inner -> {
            String sig = ItemSignature.of(inner);
            if (!sig.isEmpty()) sigs.merge(sig, inner.count(), Integer::sum);
            addShulkerSignatures(inner, sigs);
        });
    }

    public boolean containsSignature(String sig) {
        return sigCounts.containsKey(sig);
    }

    public int getSignatureCount(String sig) {
        return sigCounts.getOrDefault(sig, 0);
    }

    private static void addShulkerContents(ItemStack stack, Map<String, Integer> counts) {
        if (!(stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock)) return;
        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        if (container == null) return;
        container.nonEmptyItemCopyStream().forEach(inner -> {
            Item innerItem = inner.typeHolder().value();
            String innerId = BuiltInRegistries.ITEM.getKey(innerItem).toString();
            counts.merge(innerId, inner.count(), Integer::sum);
            addShulkerContents(inner, counts);
        });
    }

    /**
     * True if a fresh slot snapshot would produce the exact same flattened
     * item counts already stored on this container. Used to skip redundant
     * re-tracking (and the on-screen "Tracked ..." message) when a player
     * just re-opens a container whose contents haven't actually changed.
     */
    public boolean hasSameContents(List<ItemStack> newStacks) {
        // Signatures must be compared too, otherwise the enchantment index goes stale: swapping a
        // plain diamond pickaxe for a Silk Touch one leaves the item-id counts identical, so the
        // container would be skipped and `sigs` would keep describing the old stack. The same check
        // also backfills signatures on containers loaded from a database that predates them,
        // because an empty sigCounts can never equal a computed one.
        return this.items.equals(computeItemCounts(newStacks))
            && this.sigCounts.equals(computeSignatures(newStacks));
    }

    public boolean containsItem(Item item) {
        return items.containsKey(BuiltInRegistries.ITEM.getKey(item).toString());
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
            Item item = BuiltInRegistries.ITEM.getValue(id);
            if (item == null) continue;               // mod removed?
            int count = entry.getValue();
            int maxStack = item.getDefaultMaxStackSize();
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
    public String getCustomName() { return customName; }
    public void setCustomName(String name) { this.customName = name; }
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
        // Additive key: older databases have no "sigs" object and still load fine.
        if (!sigCounts.isEmpty()) {
            JsonObject sigsJson = new JsonObject();
            for (Map.Entry<String, Integer> entry : sigCounts.entrySet()) {
                sigsJson.addProperty(entry.getKey(), entry.getValue());
            }
            json.add("sigs", sigsJson);
        }
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
                slotArray.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
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
        if (json.has("sigs")) {
            JsonObject sigsJson = json.getAsJsonObject("sigs");
            for (Map.Entry<String, JsonElement> entry : sigsJson.entrySet()) {
                container.sigCounts.put(entry.getKey(), entry.getValue().getAsInt());
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
                        Item item = BuiltInRegistries.ITEM.getValue(id);
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
        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        if (container == null) return null;
        Map<Item, Integer> counts = new HashMap<>();
        container.nonEmptyItemCopyStream().forEach(s -> counts.merge(s.getItem(), s.getCount(), Integer::sum));
        if (counts.isEmpty()) return null;
        return counts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(e -> BuiltInRegistries.ITEM.getKey(e.getKey()).toString())
            .orElse(null);
    }

    public Map<Integer, String> getDominantItems() {
        return new HashMap<>(dominantItems);
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
