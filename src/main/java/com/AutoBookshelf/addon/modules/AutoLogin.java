package com.AutoBookshelf.addon.modules;

import meteordevelopment.meteorclient.systems.modules.Category;

import java.io.File;
import java.io.IOException;
import java.io.FileReader;
import java.io.BufferedReader;
import net.minecraft.text.Text;

import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;

import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChiseledBookshelfBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.screen.slot.SlotActionType;

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

    // Book extraction fields
    private boolean isProcessingBook = false;
    private BlockPos targetBookPos = null;
    private Direction targetFacing = null;
    private Vec3d targetHitVec = null;
    private double targetYaw = 0;
    private double targetPitch = 0;
    private int bookDelayTicks = 0;
    private int bookStage = 0;
    private Runnable onBookInHand = null;
    private Runnable onBookReturned = null;

    // Simplified inventory tracking
    private int originalSelectedSlot = -1;      // slot that was selected before extraction
    private int bookSlot = -1;                  // where the book was found (after extraction)
    private boolean didSwap = false;            // whether we swapped the book into the main hand
    private int timeout = 0;                    // timeout counter to prevent getting stuck

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

    public void extractAndReturn(BlockPos pos, int slot, Runnable onBookInHandCallback, Runnable onBookReturnedCallback) {
        if (isProcessingBook) {
            sendMessage("§cAlready processing a book!");
            return;
        }

        BlockState state = mc.world.getBlockState(pos);
        if (state.getBlock() != Blocks.CHISELED_BOOKSHELF) {
            sendMessage("§cNot a chiseled bookshelf!");
            return;
        }

        boolean occupied = state.get(ChiseledBookshelfBlock.SLOT_OCCUPIED_PROPERTIES.get(slot));
        if (!occupied) {
            sendMessage("§cSlot " + (slot + 1) + " is empty!");
            return;
        }

        // Check for at least one empty inventory slot
        if (isInventoryFull()) {
            sendMessage("§cInventory is full! Cannot extract book (would drop on ground).");
            return;
        }

        isProcessingBook = true;
        targetBookPos = pos;
        targetFacing = state.get(Properties.HORIZONTAL_FACING);
        targetHitVec = getHitVec(pos, targetFacing, slot);

        targetYaw = Rotations.getYaw(targetHitVec);
        targetPitch = Rotations.getPitch(targetHitVec);

        originalSelectedSlot = mc.player.getInventory().selectedSlot;
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
            if (mc.player.getInventory().getStack(i).isEmpty()) return false;
        }
        return true;
    }

    private void updateBookProcessing() {
        if (!isProcessingBook) return;

        // Timeout after 100 ticks (~5 seconds) to prevent permanent stuck
        if (timeout++ > 100) {
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
                // First right-click to take the book
                rightClickBookshelf();
                bookDelayTicks = 10; // wait for server to update inventory
                bookStage = 1;
            }
            case 1 -> {
                // Locate the extracted book anywhere in inventory (0-35)
                bookSlot = findBookInInventory();
                if (bookSlot == -1) {
                    sendMessage("§cFailed to locate extracted book in inventory!");
                    cleanupBook();
                    return;
                }

                // If the book is not already in the selected hotbar slot, bring it to hand
                if (bookSlot != originalSelectedSlot) {
                    if (bookSlot < 9) {
                        // Book is already in a hotbar slot – simply select it
                        mc.player.getInventory().selectedSlot = bookSlot;
                        didSwap = false;           // no swap performed
                    } else {
                        // Book is in main inventory – swap with selected slot
                        mc.interactionManager.clickSlot(
                            mc.player.currentScreenHandler.syncId,
                            bookSlot,
                            originalSelectedSlot,
                            SlotActionType.SWAP,
                            mc.player
                        );
                        didSwap = true;
                        // After swap, the book is now in originalSelectedSlot
                    }
                } else {
                    didSwap = false;
                }

                // Book is now in hand
                if (onBookInHand != null) {
                    onBookInHand.run();
                }
                bookDelayTicks = 40;
                bookStage = 2;
            }
            case 2 -> {
                // Put the book back into the bookshelf (it's still in the selected slot)
                rightClickBookshelf();
                bookDelayTicks = 10;
                bookStage = 3;
            }
            case 3 -> {
                // Restore original inventory state
                if (didSwap) {
                    // After placing the book, the selected slot (originalSelectedSlot) is empty
                    // The original item that was in that slot is now at bookSlot
                    // Swap them back.
                    mc.interactionManager.clickSlot(
                        mc.player.currentScreenHandler.syncId,
                        bookSlot,
                        originalSelectedSlot,
                        SlotActionType.SWAP,
                        mc.player
                    );
                }
                // Ensure the selected slot is restored (in case something changed)
                if (mc.player.getInventory().selectedSlot != originalSelectedSlot) {
                    mc.player.getInventory().selectedSlot = originalSelectedSlot;
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
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
            mc.player.swingHand(Hand.MAIN_HAND);
        });
    }

    private int findBookInInventory() {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isOf(Items.WRITTEN_BOOK) && !stack.isEmpty()) {
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

    private Vec3d getHitVec(BlockPos pos, Direction facing, int slot) {
        double x = 0, y = 0;

        switch (slot) {
            case 0 -> { x = -0.25; y = 0.25; }
            case 1 -> { x = 0.0;  y = 0.25; }
            case 2 -> { x = 0.25; y = 0.25; }
            case 3 -> { x = -0.25; y = -0.25; }
            case 4 -> { x = 0.0;  y = -0.25; }
            case 5 -> { x = 0.25; y = -0.25; }
        }

        Vec3d center = Vec3d.ofCenter(pos);

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
            mc.player.sendMessage(Text.literal(msg), true);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        updateBookProcessing();

        if (serverOnly.get() && mc.getServer() != null && mc.getServer().isSingleplayer()) return;

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

        info("Oops, password seems missing");
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        work = true;
    }
}
