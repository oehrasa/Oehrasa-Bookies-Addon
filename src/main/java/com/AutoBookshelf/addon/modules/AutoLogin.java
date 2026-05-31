package com.AutoBookshelf.addon.modules;

import meteordevelopment.meteorclient.systems.modules.Category;

import java.io.File;
import java.io.IOException;
import java.io.FileReader;
import java.io.BufferedReader;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;

import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class AutoLogin extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> fromFile = sgGeneral.add(new BoolSetting.Builder()
        .name("from-file")
        .description("Use File as password storage. locate at .minecraft/passwords.txt. type in SERVER NICK PASSWORD ENDL ... ")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> file_name = sgGeneral.add(new StringSetting.Builder()
        .name("file-name")
        .description("name for a file that would contain the passwords (stores in .minecraft folder).")
        .visible(() -> fromFile.get())
        .defaultValue("passwords.txt")
        .build()
    );

    private final Setting<String> loginCommand = sgGeneral.add(new StringSetting.Builder()
        .name("login-command")
        .description("Command to login.")
        .visible(() -> !fromFile.get())
        .defaultValue("login 1234")
        .build()
    );

    private final Setting<Boolean> serverOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("server-only")
        .description("Use Auto Login only on server.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay before send command in ticks.")
        .defaultValue(20)
        .range(1, 120)
        .sliderRange(1, 40)
        .build()
    );

    private final Setting<Boolean> debugPrint = sgGeneral.add(new BoolSetting.Builder()
        .name("debug-print")
        .description("prints messages for debugging.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> debugServer = sgGeneral.add(new BoolSetting.Builder()
        .name("debug-print-server-ip")
        .description("prints message of current server ip.")
        .visible(() -> debugPrint.get())
        .defaultValue(true)
        .build()
    );

    boolean work;
    private int timer;

    public boolean isProcessingBook = false;
    private BlockPos targetBookPos = null;
    private Direction targetFacing = null;
    private Vec3 targetHitVec = null;
    private double targetYaw = 0;
    private double targetPitch = 0;
    private int bookDelayTicks = 0;
    private int bookStage = 0;
    private Runnable onBookInHand = null;
    private Runnable onBookReturned = null;

    private int originalSelectedSlot = -1;
    private int bookSlot = -1;
    private boolean didSwap = false;
    private int timeout = 0;

    public AutoLogin(Category cat) {
        super(cat, "Auto-Login", "Automatically logs in your account via file Data.");
    }

    @Override
    public void onActivate() {
        try {
            File myObj = new File(file_name.get());
            if (myObj.createNewFile()) {
                System.out.println("File created: " + myObj.getName());
            } else {
                System.out.println("File already exists");
            }
        } catch (IOException e) {
            System.out.println("An error occurred");
            e.printStackTrace();
        }

        timer = 0;
        work = true;
    }

    @Override
    public void onDeactivate() {
        cleanupBook();
    }

    public void extractAndReturn(BlockPos pos, int slot, Runnable onBookInHandCallback, Runnable onBookReturnedCallback) {
        if (isProcessingBook) {
            sendMessage("§cAlready processing a book!");
            return;
        }

        BlockState state = mc.level.getBlockState(pos);
        if (state.getBlock() != Blocks.CHISELED_BOOKSHELF) {
            sendMessage("§cNot a chiseled bookshelf!");
            return;
        }

        boolean occupied = state.getValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(slot));
        if (!occupied) {
            sendMessage("§cSlot " + (slot + 1) + " is empty!");
            return;
        }

        if (isInventoryFull()) {
            sendMessage("§cInventory is full! Cannot extract book (would drop on ground).");
            return;
        }

        isProcessingBook = true;
        targetBookPos = pos;
        targetFacing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        targetHitVec = getHitVec(pos, targetFacing, slot);

        targetYaw = Rotations.getYaw(targetHitVec);
        targetPitch = Rotations.getPitch(targetHitVec);

        originalSelectedSlot = mc.player.getInventory().getSelectedSlot();
        bookSlot = -1;
        didSwap = false;
        bookStage = 0;
        bookDelayTicks = 0;
        timeout = 0;
        onBookInHand = onBookInHandCallback;
        onBookReturned = onBookReturnedCallback;

        sendMessage("§aExtracting book from slot §f" + (slot + 1) + "§a...");
    }

    private boolean isInventoryFull() {
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) return false;
        }
        return true;
    }

    private void updateBookProcessing() {
        if (!isProcessingBook) return;

        if (timeout++ > 160) {
            sendMessage("§cBook extraction timed out! Resetting.");
            cleanupBook();
            return;
        }

        if (bookDelayTicks > 0) {
            bookDelayTicks--;
            return;
        }

        switch (bookStage) {
            case 0 -> {
                rightClickBookshelf();
                bookDelayTicks = 10;
                bookStage = 1;
            }
            case 1 -> {
                bookSlot = findBookInInventory();
                if (bookSlot == -1) {
                    sendMessage("§cFailed to locate extracted book in inventory!");
                    cleanupBook();
                    return;
                }

                if (bookSlot != originalSelectedSlot) {
                    if (bookSlot < 9) {
                        mc.player.getInventory().setSelectedSlot(bookSlot);
                        didSwap = false;
                    } else {
                        mc.gameMode.handleInventoryMouseClick(
                            mc.player.inventoryMenu.containerId,
                            bookSlot,
                            originalSelectedSlot,
                            ClickType.SWAP,
                            mc.player
                        );
                        didSwap = true;
                    }
                } else {
                    didSwap = false;
                }

                if (onBookInHand != null) {
                    onBookInHand.run();
                }
                bookDelayTicks = 40;
                bookStage = 2;
            }
            case 2 -> {
                rightClickBookshelf();
                bookDelayTicks = 10;
                bookStage = 3;
            }
            case 3 -> {
                if (didSwap) {
                    mc.gameMode.handleInventoryMouseClick(
                        mc.player.inventoryMenu.containerId,
                        bookSlot,
                        originalSelectedSlot,
                        ClickType.SWAP,
                        mc.player
                    );
                }
                if (mc.player.getInventory().getSelectedSlot() != originalSelectedSlot) {
                    mc.player.getInventory().setSelectedSlot(originalSelectedSlot);
                }
                if (onBookReturned != null) {
                    onBookReturned.run();
                }
                cleanupBook();
                sendMessage("§aBook returned");
            }
        }
    }

    private void rightClickBookshelf() {
        BlockHitResult hitResult = new BlockHitResult(targetHitVec, targetFacing, targetBookPos, false);

        Rotations.rotate(targetYaw, targetPitch, () -> {
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hitResult);
            mc.player.swing(InteractionHand.MAIN_HAND);
        });
    }

    private int findBookInInventory() {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.is(Items.WRITTEN_BOOK) && !stack.isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private void cleanupBook() {
        bookDelayTicks = 0;
        isProcessingBook = false;
        targetBookPos = null;
        onBookInHand = null;
        onBookReturned = null;
        originalSelectedSlot = -1;
        bookSlot = -1;
        didSwap = false;
        timeout = 0;
    }

    public void cancelBookExtraction() {
        if (isProcessingBook) {
            cleanupBook();
            info("Book extraction cancelled.");
        } else {
            info("No active book extraction.");
        }
    }

    private Vec3 getHitVec(BlockPos pos, Direction facing, int slot) {
        double x = 0, y = 0;

        switch (slot) {
            case 0 -> { x = -0.25; y = 0.25; }
            case 1 -> { x = 0.0;  y = 0.25; }
            case 2 -> { x = 0.25; y = 0.25; }
            case 3 -> { x = -0.25; y = -0.25; }
            case 4 -> { x = 0.0;  y = -0.25; }
            case 5 -> { x = 0.25; y = -0.25; }
        }

        Vec3 center = Vec3.atCenterOf(pos);

        return switch (facing) {
            case NORTH -> center.add(-x, y, -0.5);
            case SOUTH -> center.add(x, y, 0.5);
            case WEST  -> center.add(-0.5, y, x);
            case EAST  -> center.add(0.5, y, -x);
            default -> center;
        };
    }

    private void sendMessage(String msg) {
        info(msg);
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(msg), true);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (isActive()) updateBookProcessing();

        if (serverOnly.get() && mc.getSingleplayerServer() != null && mc.getSingleplayerServer().isSingleplayer()) return;

        if ( !(timer >= delay.get() && !loginCommand.get().isEmpty() && work) ) {
            timer ++;
            return;
        }

        timer = 0;
        work = false;
        if (!fromFile.get()){
            ChatUtils.sendPlayerMsg("/" + loginCommand.get());
            return;
        }
        if (debugPrint.get()){
            info("reading file...");
            if ( debugServer.get()){
                info("Server is " + Utils.getWorldName());
            }
        }

        try {
            FileReader reader = new FileReader(file_name.get());
            BufferedReader bufferedReader = new BufferedReader(reader);

            String line;

            while ((line = bufferedReader.readLine()) != null) {
                String[] Dataa = line.split(" ");

                if (Dataa[0].equals(Utils.getWorldName()) && Dataa[1].equals((mc.player.getName().getString()))){
                    ChatUtils.sendPlayerMsg("/login " + Dataa[2]);
                    reader.close();
                    return;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        info("Oops, password seems missing.");
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        work = true;
    }
}
