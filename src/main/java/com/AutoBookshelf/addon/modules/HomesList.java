package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.utils.JoinPayload;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WFavorite;
import meteordevelopment.meteorclient.gui.widgets.pressable.WMinus;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.*;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static com.AutoBookshelf.addon.utils.Checks.is6B6T;

public class HomesList extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgQuickSelect = settings.createGroup("Quick Select");
    private final SettingGroup sgDebug = settings.createGroup("Debug");

    public final Setting<Keybind> openGui = sgGeneral.add(new KeybindSetting.Builder()
        .name("open-gui")
        .description("Opens the homes list GUI. Press again to close.")
        .defaultValue(Keybind.fromKey(GLFW.GLFW_KEY_H))
        .action(() -> {
            if (MeteorClient.mc.currentScreen instanceof HomesScreen ||
                MeteorClient.mc.currentScreen instanceof EditHomeScreen) {
                MeteorClient.mc.setScreen(null);
            } else if (MeteorClient.mc.currentScreen == null) {
                MeteorClient.mc.setScreen(new HomesScreen(GuiThemes.get(), this));
            }
        })
        .build()
    );

    private final Setting<Boolean> refreshOnActivate = sgGeneral.add(new BoolSetting.Builder()
        .name("refresh-on-activate")
        .description("Automatically fetch the table using /homes when the module is enabled.")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> chatPrefix = sgGeneral.add(new StringSetting.Builder()
        .name("chat-prefix")
        .description("The chat message prefix that contains the home list to fetch (its fine dont touch).")
        .defaultValue("Your homes (")
        .build()
    );

    public final Setting<Keybind> quickSelectKey = sgQuickSelect.add(new KeybindSetting.Builder()
        .name("quick-select-key")
        .description("Hold to open the screen. Scroll to select then release to TP to highlighted home.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Integer> maxQuickSelectItems = sgQuickSelect.add(new IntSetting.Builder()
        .name("max-quick-select-items")
        .description("Maximum number of homes shown in the quick select overlay.")
        .defaultValue(20)
        .min(1)
        .sliderRange(1, 50)
        .build()
    );

    private final Setting<Integer> quickSelectColumns = sgQuickSelect.add(new IntSetting.Builder()
        .name("columns")
        .description("Number of columns in the quick select overlay. Scrolling moves by one row.")
        .defaultValue(1)
        .min(1)
        .sliderRange(1, 6)
        .build()
    );

    private final Setting<Boolean> Home = sgGeneral.add(new BoolSetting.Builder()
        .name("free-home")
        .description("qbasty.")
        .defaultValue(true)
        .build()
    );


    private final Setting<Boolean> debugMode = sgDebug.add(new BoolSetting.Builder()
        .name("debug-mode")
        .description("Show detailed debug information.")
        .defaultValue(true)
        .build()
    );

    private static final Gson GSON = new GsonBuilder()
        .registerTypeAdapter(HomeEntry.class, new HomeEntryAdapter())
        .create();
    private File saveFile;
    private List<HomeEntry> homes = new ArrayList<>();
    private boolean waitingForServerHomes = false;

    private static final Item[] RANDOM_ICONS = {
        Items.GRASS_BLOCK, Items.OAK_LOG, Items.STONE, Items.END_STONE, Items.BRICKS, Items.RESIN_CLUMP,
        Items.BOOKSHELF, Items.CRAFTING_TABLE, Items.FURNACE, Items.DIAMOND_BLOCK, Items.SNOWBALL,
        Items.EMERALD, Items.GOLD_INGOT, Items.IRON_INGOT, Items.REDSTONE, Items.SNIFFER_EGG, Items.COBBLESTONE,
        Items.LAPIS_LAZULI, Items.OBSIDIAN, Items.NETHERRACK, Items.ENDER_PEARL, Items.CHERRY_SAPLING,
        Items.POPPY, Items.DANDELION, Items.TORCHFLOWER, Items.CHEST, Items.ANVIL, Items.MELON
    };

    private QuickSelectScreen quickScreen = null;
    private List<HomeEntry> quickHomes = new ArrayList<>();
    private int quickSelectedIndex = -1;
    private boolean quickCancelled = false;
    private boolean quickForceClosed = false;

    public HomesList() {
        super(Addon.CATEGORY, "Homes-List", "Manage and teleport to your server homes with a GUI.");
        saveFile = new File(new File(MeteorClient.mc.runDirectory, "meteor-client"), "homes.json");
    }

    @Override
    public void onActivate() {
        load();
        if (refreshOnActivate.get()) refreshFromServer();

        if (Home.get() && is6B6T()) {
            mc.getNetworkHandler().sendPacket(new CustomPayloadC2SPacket(new JoinPayload()));
        }
    }

    @Override
    public void onDeactivate() {
        save();
        homes.clear();
        waitingForServerHomes = false;
        quickForceClosed = false;
        closeQuickScreen(false);
    }

    private void load() {
        if (!saveFile.exists()) return;
        try (Reader reader = new FileReader(saveFile)) {
            Type listType = new TypeToken<List<HomeEntry>>() {
            }.getType();
            homes = GSON.fromJson(reader, listType);
            if (homes == null) homes = new ArrayList<>();
        } catch (IOException e) {
            homes = new ArrayList<>();
        }
    }

    public void save() {
        try (Writer writer = new FileWriter(saveFile)) {
            GSON.toJson(homes, writer);
        } catch (IOException ignored) {
        }
    }

    public void refreshFromServer() {
        if (MeteorClient.mc.player == null) return;
        MeteorClient.mc.player.networkHandler.sendChatCommand("homes");
        waitingForServerHomes = true;
    }

    @EventHandler
    private void onMessageReceived(ReceiveMessageEvent event) {
        if (!waitingForServerHomes) return;
        String msg = event.getMessage().getString();
        String prefix = chatPrefix.get();

        int idx = msg.indexOf(prefix);
        if (idx == -1) return;

        String rest = msg.substring(idx + prefix.length());
        int colonIdx = rest.indexOf(':');
        if (colonIdx == -1) return;

        String list = rest.substring(colonIdx + 1).trim();
        if (list.endsWith(")")) list = list.substring(0, list.length() - 1).trim();

        String[] parts = list.split(",");

        for (String part : parts) {
            String homeName = part.trim();
            if (homeName.isEmpty()) continue;
            if (homes.stream().noneMatch(h -> h.originalName.equals(homeName))) {
                HomeEntry entry = new HomeEntry();
                entry.originalName = homeName;
                entry.displayName = homeName;
                entry.setIcon(RANDOM_ICONS[ThreadLocalRandom.current().nextInt(RANDOM_ICONS.length)]);
                entry.favorite = false;
                homes.add(entry);
            }
        }

        sortHomes();
        waitingForServerHomes = false;
        save();
        if (debugMode.get()) {
            info("Loaded " + parts.length + " homes.");
        }
    }

    public List<HomeEntry> getHomes() {
        homes.sort(Comparator.comparing(HomeEntry::isFavorite).reversed()
            .thenComparing(HomeEntry::getDisplayName));
        return homes;
    }

    private void sortHomes() {
        homes.sort(Comparator.comparing(HomeEntry::isFavorite).reversed()
            .thenComparing(HomeEntry::getDisplayName));
    }

    public void addHome(HomeEntry entry) {
        if (entry.getIcon() == Items.GRASS_BLOCK && entry.iconId == null) {
            entry.setIcon(RANDOM_ICONS[ThreadLocalRandom.current().nextInt(RANDOM_ICONS.length)]);
        }
        homes.add(entry);
        sortHomes();
        save();
    }

    public void updateHome(int index, HomeEntry entry) {
        homes.set(index, entry);
        sortHomes();
        save();
    }

    public void removeHome(int index) {
        homes.remove(index);
        save();
    }

    public void teleportTo(String homeName) {
        if (MeteorClient.mc.player == null) return;
        MeteorClient.mc.player.networkHandler.sendChatCommand("home " + homeName);
        if (debugMode.get()) {
            info("Teleport to " + homeName);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (MeteorClient.mc.player == null || MeteorClient.mc.world == null) return;

        boolean keyPressed = quickSelectKey.get().isPressed();

        if (keyPressed && quickScreen == null && !quickForceClosed && MeteorClient.mc.currentScreen == null) {
            List<HomeEntry> fullList = getHomes();
            if (!fullList.isEmpty()) {
                int limit = Math.min(maxQuickSelectItems.get(), fullList.size());
                quickHomes = new ArrayList<>(fullList.subList(0, limit));
                quickSelectedIndex = -1;
                quickCancelled = false;
                quickScreen = new QuickSelectScreen(this, quickHomes, -1,
                    (int) MeteorClient.mc.mouse.getX(), (int) MeteorClient.mc.mouse.getY(),
                    quickSelectColumns.get());
                MeteorClient.mc.setScreen(quickScreen);
            }
        } else if (!keyPressed && quickScreen != null) {
            closeQuickScreen(true);
        }

        if (!keyPressed) {
            quickForceClosed = false;
        }

        if (quickScreen != null) {
            quickSelectedIndex = quickScreen.getSelectedIndex();
        }
    }

    private void closeQuickScreen(boolean doTeleport) {
        if (quickScreen == null) return;

        quickSelectedIndex = quickScreen.getSelectedIndex();
        MeteorClient.mc.setScreen(null);
        quickScreen = null;

        if (doTeleport && !quickCancelled && quickSelectedIndex >= 0 && quickSelectedIndex < quickHomes.size()) {
            teleportTo(quickHomes.get(quickSelectedIndex).originalName);
        }

        quickHomes.clear();
    }

    /**
     * Called by QuickSelectScreen when Escape is pressed.
     */
    public void cancelQuickSelect() {
        quickCancelled = true;
        quickForceClosed = true;
        closeQuickScreen(false);
    }

    public static class HomeEntry {
        public String originalName;
        public String displayName;
        public boolean autoAdded = false;
        public boolean favorite = false;
        private String iconId = null;

        public Item getIcon() {
            if (iconId == null) return Items.GRASS_BLOCK;
            Identifier id = Identifier.tryParse(iconId);
            return id == null ? Items.GRASS_BLOCK : Registries.ITEM.get(id);
        }

        public void setIcon(Item item) {
            Identifier id = Registries.ITEM.getId(item);
            this.iconId = id != null ? id.toString() : null;
        }

        public ItemStack getIconStack() {
            return new ItemStack(getIcon());
        }

        public boolean isFavorite() {
            return favorite;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    private static class HomeEntryAdapter implements JsonSerializer<HomeEntry>, JsonDeserializer<HomeEntry> {
        @Override
        public JsonElement serialize(HomeEntry src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject obj = new JsonObject();
            obj.addProperty("originalName", src.originalName);
            obj.addProperty("displayName", src.displayName);
            obj.addProperty("autoAdded", src.autoAdded);
            obj.addProperty("favorite", src.favorite);
            obj.addProperty("iconId", src.iconId);
            return obj;
        }

        @Override
        public HomeEntry deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
            throws JsonParseException {
            JsonObject obj = json.getAsJsonObject();
            HomeEntry entry = new HomeEntry();
            entry.originalName = obj.get("originalName").getAsString();
            entry.displayName = obj.get("displayName").getAsString();
            entry.autoAdded = obj.has("autoAdded") && obj.get("autoAdded").getAsBoolean();
            entry.favorite = obj.has("favorite") && obj.get("favorite").getAsBoolean();
            if (obj.has("iconId") && !obj.get("iconId").isJsonNull()) {
                entry.iconId = obj.get("iconId").getAsString();
            }
            return entry;
        }
    }

    private static class QuickSelectScreen extends Screen {
        private final HomesList module;
        private final List<HomeEntry> homes;
        private final int columns;
        // selectedIndex is a flat index into homes list; scroll moves by one full row (columns items)
        private int selectedIndex;

        protected QuickSelectScreen(HomesList module, List<HomeEntry> homes, int startIndex, int startMouseX, int startMouseY, int columns) {
            super(Text.empty());
            this.module = module;
            this.homes = homes;
            this.columns = Math.max(1, columns);
            this.selectedIndex = startIndex;
        }

        public int getSelectedIndex() {
            return selectedIndex;
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            int iconSize = 16;
            int padding = 4;
            int rowHeight = iconSize + padding;

            int[] colWidths = new int[columns];
            for (int i = 0; i < homes.size(); i++) {
                int col = i % columns;
                int labelW = MeteorClient.mc.textRenderer.getWidth(homes.get(i).displayName);
                int cellW = iconSize + padding + labelW + padding * 2;
                if (cellW > colWidths[col]) colWidths[col] = cellW;
            }

            int rows = (int) Math.ceil((double) homes.size() / columns);
            int totalWidth = 0;
            for (int w : colWidths) totalWidth += w;
            totalWidth += (columns - 1) * 2;
            int totalHeight = rowHeight * rows + padding;

            int x = mouseX + 10;
            int y = mouseY - totalHeight / 2;
            if (x + totalWidth > this.width) x = this.width - totalWidth - 5;
            if (y < 0) y = 5;
            if (y + totalHeight > this.height) y = this.height - totalHeight - 5;

            // Background
            context.fill(x, y, x + totalWidth, y + totalHeight, 0xCC000000);

            // Column dividers
            int divX = x;
            for (int c = 0; c < columns - 1; c++) {
                divX += colWidths[c] + 1;
                context.fill(divX, y, divX + 1, y + totalHeight, 0x44FFFFFF);
                divX += 1;
            }

            for (int i = 0; i < homes.size(); i++) {
                int row = i / columns;
                int col = i % columns;

                int cellX = x;
                for (int c = 0; c < col; c++) cellX += colWidths[c] + 2;

                int rowY = y + padding + row * rowHeight;
                boolean selected = (i == selectedIndex && selectedIndex >= 0);

                if (selected) {
                    context.fill(cellX + 1, rowY - 1, cellX + colWidths[col] - 1, rowY + rowHeight - 1, 0x44FFFFFF);
                }

                context.drawItem(homes.get(i).getIconStack(), cellX + padding, rowY);

                int textX = cellX + padding + iconSize + padding;
                int textY = rowY + (rowHeight - MeteorClient.mc.textRenderer.fontHeight) / 2;
                int color = selected ? 0xFFFFFF55 : 0xFFFFFFFF; // alpha byte

                context.drawTextWithShadow(MeteorClient.mc.textRenderer, homes.get(i).displayName, textX, textY, color);
            }

            super.render(context, mouseX, mouseY, delta);
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
            if (verticalAmount != 0) {
                if (selectedIndex < 0) selectedIndex = 0;
                // scroll moves by a full row (columns items) so visually the highlight jumps row by row
                int dir = verticalAmount > 0 ? -columns : columns;
                selectedIndex = Math.floorMod(selectedIndex + dir, homes.size());
                return true;
            }
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        @Override
        public boolean keyPressed(KeyInput input) {
            int keyCode = input.key();
            if (keyCode == GLFW.GLFW_KEY_UP) {
                if (selectedIndex < 0) selectedIndex = 0;
                selectedIndex = Math.floorMod(selectedIndex - columns, homes.size());
                return true;
            } else if (keyCode == GLFW.GLFW_KEY_DOWN) {
                if (selectedIndex < 0) selectedIndex = 0;
                selectedIndex = Math.floorMod(selectedIndex + columns, homes.size());
                return true;
            } else if (keyCode == GLFW.GLFW_KEY_LEFT) {
                if (selectedIndex < 0) selectedIndex = 0;
                selectedIndex = Math.floorMod(selectedIndex - 1, homes.size());
                return true;
            } else if (keyCode == GLFW.GLFW_KEY_RIGHT) {
                if (selectedIndex < 0) selectedIndex = 0;
                selectedIndex = Math.floorMod(selectedIndex + 1, homes.size());
                return true;
            } else if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                module.cancelQuickSelect();
                return true;
            }
            return super.keyPressed(input);
        }

        @Override
        public boolean shouldCloseOnEsc() {
            return false;
        }
    }

    private static class HomesScreen extends WindowScreen {
        private final HomesList module;
        private WTable table;
        private String searchText = "";

        public HomesScreen(GuiTheme theme, HomesList module) {
            super(theme, "Homes");
            this.module = module;
        }

        @Override
        public void initWidgets() {
            WTextBox searchBox = add(theme.textBox("", "Search homes...")).expandX().widget();
            searchBox.action = () -> {
                searchText = searchBox.get().toLowerCase().trim();
                rebuildTable();
            };

            table = add(theme.table()).expandX().minWidth(400).widget();
            rebuildTable();

            add(theme.horizontalSeparator()).expandX();
            WHorizontalList row = add(theme.horizontalList()).expandX().widget();

            WButton refresh = row.add(theme.button("Refresh from server")).expandX().widget();
            refresh.action = module::refreshFromServer;

            WButton addNew = row.add(theme.button("Add home")).expandX().widget();
            addNew.action = () -> MeteorClient.mc.setScreen(new EditHomeScreen(theme, module, null, -1, this));
        }

        private void rebuildTable() {
            table.clear();
            List<HomeEntry> filtered = module.getHomes().stream()
                .filter(h -> searchText.isEmpty() || h.displayName.toLowerCase().contains(searchText))
                .toList();

            if (filtered.isEmpty()) {
                table.add(theme.label("No homes found.")).expandX().pad(10);
                return;
            }

            for (HomeEntry home : filtered) {
                WFavorite fav = table.add(theme.favorite(home.favorite)).widget();
                fav.action = () -> {
                    home.favorite = !home.favorite;
                    module.save();
                    rebuildTable();
                };

                table.add(theme.item(home.getIconStack()));
                table.add(theme.label(home.displayName));

                WButton teleport = table.add(theme.button("Teleport")).widget();
                teleport.action = () -> module.teleportTo(home.originalName);

                WButton edit = table.add(theme.button(GuiRenderer.EDIT)).widget();
                edit.action = () -> MeteorClient.mc.setScreen(
                    new EditHomeScreen(theme, module, home,
                        module.homes.indexOf(home), this)
                );

                WMinus delete = table.add(theme.minus()).widget();
                delete.action = () -> {
                    module.homes.remove(home);
                    module.sortHomes();
                    module.save();
                    rebuildTable();
                };

                table.row();
            }
        }

        @Override
        public void tick() {
        }
    }

    private static class EditHomeScreen extends WindowScreen {
        private final HomesList module;
        private final HomeEntry home;
        private final int index;
        private final HomesScreen parent;

        private final Setting<String> displayName;
        private final Setting<Item> icon;
        private final Setting<String> originalName;

        public EditHomeScreen(GuiTheme theme, HomesList module, HomeEntry home, int index, HomesScreen parent) {
            super(theme, home == null ? "New Home" : "Edit Home");
            this.module = module;
            this.home = home;
            this.index = index;
            this.parent = parent;

            Settings settings = new Settings();
            SettingGroup sg = settings.getDefaultGroup();
            originalName = sg.add(new StringSetting.Builder().name("original-name").defaultValue(home != null ? home.originalName : "").build());
            displayName = sg.add(new StringSetting.Builder().name("display-name").defaultValue(home != null ? home.displayName : "").build());
            icon = sg.add(new ItemSetting.Builder().name("icon").defaultValue(home != null ? home.getIcon() : Items.GRASS_BLOCK).build());
            add(theme.settings(settings)).expandX();
        }

        @Override
        public void initWidgets() {
            add(theme.horizontalSeparator()).expandX();
            WHorizontalList actions = add(theme.horizontalList()).expandX().widget();

            WButton save = actions.add(theme.button(home == null ? "Create" : "Update")).expandX().widget();
            save.action = () -> {
                HomeEntry newEntry = new HomeEntry();
                newEntry.originalName = originalName.get();
                newEntry.displayName = displayName.get();
                newEntry.setIcon(icon.get());
                newEntry.autoAdded = home != null && home.autoAdded;
                newEntry.favorite = home != null && home.favorite;
                if (home == null) module.addHome(newEntry);
                else module.updateHome(index, newEntry);
                if (parent != null) {
                    parent.rebuildTable();
                    MeteorClient.mc.setScreen(parent);
                } else MeteorClient.mc.setScreen(null);
            };

            WButton cancel = actions.add(theme.button("Cancel")).expandX().widget();
            cancel.action = () -> MeteorClient.mc.setScreen(parent != null ? parent : null);
        }
    }
}
