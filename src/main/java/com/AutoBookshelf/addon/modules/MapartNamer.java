package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.interfaces.IClientPlayerInteractionManager;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.AnvilBlock;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.RenameItemC2SPacket;
import net.minecraft.network.packet.s2c.play.CloseScreenS2CPacket;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class MapartNamer extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> mapWidth = sgGeneral.add(new IntSetting.Builder()
        .name("map-width")
        .description("Number of columns (X) in the map art.")
        .defaultValue(4)
        .min(1)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Integer> mapHeight = sgGeneral.add(new IntSetting.Builder()
        .name("map-height")
        .description("Total number of rows (Y) in the map art.")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 30)
        .build()
    );

    public final Setting<String> mapName = sgGeneral.add(new StringSetting.Builder()
        .name("map-name")
        .description("Base name for the mapart.")
        .defaultValue("mapart ")
        .build()
    );

    public final Setting<String> coordinateFormat = sgGeneral.add(new StringSetting.Builder()
        .name("coordinate-format")
        .description("Format for the X/Y part. Use {x} and {y} as placeholders.")
        .defaultValue("[{x}, {y}]")
        .build()
    );

    private final Setting<CoordPosition> coordPosition = sgGeneral.add(new EnumSetting.Builder<CoordPosition>()
        .name("coord-position")
        .description("Whether the coordinates are placed before or after the map name.")
        .defaultValue(CoordPosition.Suffix)
        .build()
    );

    private final Setting<StartIndex> startIndex = sgGeneral.add(new EnumSetting.Builder<StartIndex>()
        .name("start-index")
        .description("Whether the first map is named with index 0 or 1.")
        .defaultValue(StartIndex.ZERO)
        .build()
    );

    private final Setting<Boolean> renameAlreadyNamed = sgGeneral.add(new BoolSetting.Builder()
        .name("rename-already-named")
        .description("If enabled, rename all filled maps, even those with a custom name.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> resumeSequence = sgGeneral.add(new BoolSetting.Builder()
        .name("resume-sequence")
        .description("Skip maps that already have the correct name for their inventory position.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> renameDelay = sgGeneral.add(new IntSetting.Builder()
        .name("rename-delay")
        .description("Ticks between finishing one map and starting the next.")
        .defaultValue(10)
        .min(4)
        .sliderRange(4, 30)
        .build()
    );

    private final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder()
        .name("action-delay")
        .description("Ticks between individual clicks, pickup/place/rename.")
        .defaultValue(1)
        .min(1)
        .max(6)
        .sliderRange(1, 6)
        .build()
    );

    private final Setting<Integer> renamePause = sgGeneral.add(new IntSetting.Builder()
        .name("rename-pause")
        .description("Extra ticks to wait after sending the rename packet before taking the result.")
        .defaultValue(1)
        .min(0)
        .max(6)
        .sliderRange(0, 6)
        .build()
    );

    private final Setting<Integer> baseY = sgGeneral.add(new IntSetting.Builder()
        .name("base-y")
        .description("Current Y offset saved automatically. Do not edit manually.")
        .defaultValue(0)
        .visible(() -> false)
        .build()
    );

    public MapartNamer() {
        super(Addon.CATEGORY, "Mapart-Namer", "Auto‑names maps based on inventory slot layout.");
    }

    private enum State { AwaitInteract, AwaitScreen, HandleMaps }
    public enum CoordPosition { Prefix, Suffix }
    private State state;
    private List<MapSlotInfo> mapSlots;
    private int ticks;

    private MapSlotInfo currentMap;
    private int mapStep;
    private int mapStepTimer;

    private int verifyTimeout = 0;
    private static final int STEP_PICKUP_SOURCE  = 0;
    private static final int STEP_PLACE_ANVIL    = 1;
    private static final int STEP_SEND_RENAME    = 2;
    private static final int STEP_WAIT_RENAME    = 3;
    private static final int STEP_PICKUP_OUTPUT  = 4;
    private static final int STEP_PLACE_SOURCE   = 5;
    private static final int STEP_VERIFY_CURSOR = 11;

    @Override
    public void onActivate() {
        state = State.AwaitInteract;
        ticks = 0;
        mapSlots = new ArrayList<>();
        currentMap = null;
        mapStep = -1;

        if (mc.currentScreen instanceof AnvilScreen) {
            state = State.AwaitScreen;
            info("Anvil screen already open, waiting for inventory packet…");
        } else {
            info("§aEnabled. Right-click an anvil with maps in your inventory.");
        }
    }

    // Reset button
    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WButton resetBtn = list.add(theme.button("Reset Progress")).widget();
        resetBtn.action = () -> {
            baseY.set(0);
            info("Progress reset (base-Y set to 0).");
        };
        return list;
    }

    // Return everything else except anvil screen
    private boolean isNotAnvilScreen() {
        return !(mc.currentScreen instanceof AnvilScreen);
    }

    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        if (state == State.AwaitInteract && event.packet instanceof PlayerInteractBlockC2SPacket packet) {
            BlockPos blockPos = packet.getBlockHitResult().getBlockPos();
            if (mc.world.getBlockState(blockPos).getBlock() instanceof AnvilBlock) {
                state = State.AwaitScreen;
            }
        }
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (state == State.HandleMaps && event.packet instanceof CloseScreenS2CPacket) {
            info("Anvil closed. Next batch starts at Y = " + getNextBatchStartY());
            state = State.AwaitInteract;
            return;
        }
        if (state != State.AwaitScreen) return;
        if (!(event.packet instanceof InventoryS2CPacket)) return;

        // Collect all filled map
        List<MapSlotInfo> allMaps = new ArrayList<>();
        for (int invSlot = 0; invSlot < 36; invSlot++) {
            ItemStack stack = mc.player.getInventory().getStack(invSlot);
            if (stack.getItem() != Items.FILLED_MAP) continue;

            String currentName = stack.getName().getString();
            if (!renameAlreadyNamed.get() && !currentName.equals("Map")) continue;

            int visualRow = invSlot < 9 ? 3 : (invSlot - 9) / 9;
            int column = invSlot % 9;
            int containerSlot = invSlot < 9 ? invSlot + 30 : invSlot - 6;

            allMaps.add(new MapSlotInfo(containerSlot, visualRow, column, invSlot, currentName));
        }

        if (allMaps.isEmpty()) {
            warning("No maps matching criteria found in inventory.");
            state = State.AwaitInteract;
            return;
        }

        // Anchor column
        int minCol = allMaps.stream().mapToInt(s -> s.col).min().orElse(0);
        int offset = startIndex.get() == StartIndex.ONE ? 1 : 0;

        mapSlots.clear();
        for (MapSlotInfo info : allMaps) {
            int rawX = info.col - minCol;
            info.x = rawX + offset;
            if (info.x > mapWidth.get()) { info.skip = true; continue; }

            int currentY = baseY.get() + info.row + offset;
            String expectedCoord = coordinateFormat.get()
                .replace("{x}", String.valueOf(info.x))
                .replace("{y}", String.valueOf(currentY));
            String expectedName = mapName.get() + expectedCoord;

            if (resumeSequence.get() && info.currentName.equals(expectedName)) {
                info("Skipping inv slot " + info.invSlot + " – already named " + expectedName);
                continue;
            }
            mapSlots.add(info);
        }

        if (mapSlots.isEmpty()) {
            info("All maps in this inventory already correctly named.");
            state = State.AwaitInteract;
            return;
        }

        // Sort top to bottom, left to right
        mapSlots.sort(Comparator.comparingInt((MapSlotInfo s) -> s.row).thenComparingInt(s -> s.col));

        ticks = renameDelay.get();
        state = State.HandleMaps;
        currentMap = null;
        mapStep = -1;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (state != State.HandleMaps) return;

        // If the anvil screen is gone, abort current step and wait for next anvil
        if (isNotAnvilScreen()) {
            state = State.AwaitInteract;
            currentMap = null;
            mapStep = -1;
            return;
        }

        if (currentMap == null) {
            if (ticks > 0) { ticks--; return; }
            if (mapSlots.isEmpty()) return;

            currentMap = mapSlots.remove(0);
            mapStep = STEP_PICKUP_SOURCE;
            mapStepTimer = actionDelay.get();
        }

        if (mapStepTimer > 0) { mapStepTimer--; return; }

        switch (mapStep) {
            case STEP_PICKUP_SOURCE -> {
                clickSlot(currentMap.containerSlot, 0, SlotActionType.PICKUP);
                mapStep = STEP_PLACE_ANVIL;
                mapStepTimer = actionDelay.get();
            }
            case STEP_PLACE_ANVIL -> {
                clickSlot(0, 0, SlotActionType.PICKUP);
                mapStep = STEP_SEND_RENAME;
                mapStepTimer = actionDelay.get();
            }
            case STEP_SEND_RENAME -> {
                if (mc.player.experienceLevel < 1) {
                    info("Not enough XP.");
                    currentMap = null;
                    mapStep = -1;
                    state = State.AwaitInteract;
                    if (mc.currentScreen != null) mc.player.closeHandledScreen();
                    return;
                }

                int off = startIndex.get() == StartIndex.ONE ? 1 : 0;
                int currentY = baseY.get() + currentMap.row + off;
                String coordPart = coordinateFormat.get()
                    .replace("{x}", String.valueOf(currentMap.x))
                    .replace("{y}", String.valueOf(currentY));
                String newName;
                if (coordPosition.get() == CoordPosition.Prefix) {
                    newName = coordPart + mapName.get();
                } else {
                    newName = mapName.get() + coordPart;
                }
                info("Renaming inv slot " + currentMap.invSlot + " (row " + currentMap.row + ", col " + currentMap.col + ") to " + newName);
                mc.getNetworkHandler().sendPacket(new RenameItemC2SPacket(newName));
                mapStep = STEP_WAIT_RENAME;
                mapStepTimer = renamePause.get();
            }
            case STEP_WAIT_RENAME -> {
                mapStep = STEP_PICKUP_OUTPUT;
                mapStepTimer = actionDelay.get();
            }
            case STEP_PICKUP_OUTPUT -> {
                clickSlot(2, 0, SlotActionType.PICKUP);
                mapStep = STEP_VERIFY_CURSOR;
                mapStepTimer = 2;
                verifyTimeout = 20;
            }
            case STEP_VERIFY_CURSOR -> {
                ItemStack cursorStack = mc.player.currentScreenHandler.getCursorStack();
                if (cursorStack.getItem() == Items.FILLED_MAP) {
                    mapStep = STEP_PLACE_SOURCE;
                    mapStepTimer = actionDelay.get();
                } else {
                    if (--verifyTimeout <= 0) {
                        error("Timed out waiting for renamed map on cursor. Aborting this map.");
                        currentMap = null;
                        mapStep = -1;
                        ticks = 0;
                        return;
                    }
                    mapStepTimer = 1;
                }
            }
            case STEP_PLACE_SOURCE -> {
                clickSlot(currentMap.containerSlot, 0, SlotActionType.PICKUP);
                currentMap = null;
                mapStep = -1;

                if (mapSlots.isEmpty()) {
                    int nextBaseY = baseY.get() + 4;
                    int off = startIndex.get() == StartIndex.ONE ? 1 : 0;
                    if (nextBaseY + off > mapHeight.get()) {
                        info("All maps named, mapart are now complete.");
                        baseY.set(0);
                        toggle();
                    } else {
                        info("Batch finished. Next Y-offset = " + (nextBaseY + off));
                        baseY.set(nextBaseY);
                    }
                    if (mc.currentScreen != null) mc.player.closeHandledScreen();
                    state = State.AwaitInteract;
                } else {
                    ticks = renameDelay.get();
                }
            }
        }
    }

    private void clickSlot(int slot, int button, SlotActionType action) {
        IClientPlayerInteractionManager cim = (IClientPlayerInteractionManager) mc.interactionManager;
        cim.clickSlot(mc.player.currentScreenHandler.syncId, slot, button, action, mc.player);
    }

    private int getNextBatchStartY() {
        int offset = startIndex.get() == StartIndex.ONE ? 1 : 0;
        return baseY.get() + offset;
    }

    private static class MapSlotInfo {
        final int containerSlot, row, col, invSlot;
        final String currentName;
        int x;
        boolean skip;

        MapSlotInfo(int containerSlot, int row, int col, int invSlot, String currentName) {
            this.containerSlot = containerSlot; this.row = row; this.col = col;
            this.invSlot = invSlot; this.currentName = currentName;
        }
    }

    public enum StartIndex { ZERO, ONE }
}
