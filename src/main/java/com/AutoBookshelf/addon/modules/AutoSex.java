package com.AutoBookshelf.addon.modules;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.meteor.MouseButtonEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;

import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_MIDDLE;

public class AutoSex extends Module {
    public enum Mode {
        MiddleClick,
        BindClick,
        Automatic
    }

    public enum ApproachMode {
        Direct("Direct - Go straight to target"),
        Behind("Behind - Stand behind target"),
        Side("Side - Stand to the side of target");

        private final String title;
        ApproachMode(String title) { this.title = title; }
        @Override public String toString() { return title; }
    }

    public enum FriendFilter {
        ALL("All Players"),
        ONLY_FRIENDS("Only Friends"),
        ONLY_NON_FRIENDS("Only Non-Friends");

        private final String title;
        FriendFilter(String title) { this.title = title; }
        @Override public String toString() { return title; }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSex = settings.createGroup("Auto Sex");
    private final SettingGroup sgMovement = settings.createGroup("Movement");

    // Target selection
    private final Setting<Mode> targetMode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("target-mode")
        .description("The mode at which to follow the player.")
        .defaultValue(Mode.BindClick)
        .build()
    );

    private final Setting<Keybind> keybind = sgGeneral.add(new KeybindSetting.Builder()
        .name("keybind")
        .description("What key to press to start following someone.")
        .defaultValue(Keybind.fromKey(-1))
        .visible(() -> targetMode.get() == Mode.BindClick)
        .build()
    );

    private final Setting<SortPriority> priority = sgGeneral.add(new EnumSetting.Builder<SortPriority>()
        .name("target-priority")
        .description("How to select the player to target.")
        .defaultValue(SortPriority.LowestDistance)
        .visible(() -> targetMode.get() == Mode.Automatic)
        .build()
    );

    private final Setting<Boolean> ignoreRange = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-range")
        .description("Follow the player even if they are out of range.")
        .defaultValue(false)
        .visible(() -> targetMode.get() == Mode.Automatic)
        .build()
    );

    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("The range in which it follows a random player.")
        .defaultValue(50)
        .min(1)
        .max(200)
        .sliderRange(1, 200)
        .visible(() -> targetMode.get() == Mode.Automatic && !ignoreRange.get())
        .build()
    );

    private final Setting<FriendFilter> friendFilter = sgGeneral.add(new EnumSetting.Builder<FriendFilter>()
        .name("friend-filter")
        .description("Which players to target based on friend status")
        .defaultValue(FriendFilter.ALL)
        .build()
    );

    private final Setting<Boolean> message = sgGeneral.add(new BoolSetting.Builder()
        .name("message")
        .description("Sends a message to the player when you start/stop following them")
        .defaultValue(false)
        .build()
    );

    // Movement settings
    private final Setting<ApproachMode> approachMode = sgMovement.add(new EnumSetting.Builder<ApproachMode>()
        .name("approach-mode")
        .description("How to approach the target")
        .defaultValue(ApproachMode.Behind)
        .build()
    );

    private final Setting<Double> behindOffset = sgMovement.add(new DoubleSetting.Builder()
        .name("behind-offset")
        .description("How many blocks behind the target to stand")
        .defaultValue(1.5)
        .min(1.0)
        .max(3.0)
        .sliderRange(1.0, 3.0)
        .visible(() -> approachMode.get() == ApproachMode.Behind)
        .build()
    );

    private final Setting<Double> sideOffset = sgMovement.add(new DoubleSetting.Builder()
        .name("side-offset")
        .description("How many blocks to the side of the target to stand")
        .defaultValue(2.0)
        .min(1.0)
        .max(4.0)
        .sliderRange(1.0, 4.0)
        .visible(() -> approachMode.get() == ApproachMode.Side)
        .build()
    );

    private final Setting<Integer> maxFollowRange = sgMovement.add(new IntSetting.Builder()
        .name("max-follow-range")
        .description("Maximum range to follow the target (blocks)")
        .defaultValue(100)
        .min(10)
        .max(500)
        .sliderRange(10, 500)
        .build()
    );

    private final Setting<Boolean> autoLook = sgMovement.add(new BoolSetting.Builder()
        .name("auto-look")
        .description("Continuously look at the back of the target's head when in position")
        .defaultValue(true)
        .build()
    );

    // Sex settings
    private final Setting<Boolean> twerkWhenClose = sgSex.add(new BoolSetting.Builder()
        .name("auto-hump")
        .description("Crouch against the target to give the appearance of sex OwO")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> twerkSpeed = sgSex.add(new IntSetting.Builder()
        .name("twerk-speed")
        .description("How many times per second to spam crouch (1-20)")
        .defaultValue(8)
        .min(1)
        .max(20)
        .visible(twerkWhenClose::get)
        .build()
    );

    private final Setting<Integer> twerkDelay = sgSex.add(new IntSetting.Builder()
        .name("twerk-delay")
        .description("Delay in ticks before starting to twerk after reaching position")
        .defaultValue(10)
        .min(0)
        .max(40)
        .visible(twerkWhenClose::get)
        .build()
    );

    private final Setting<Boolean> dirtyTalk = sgSex.add(new BoolSetting.Builder()
        .name("dirty-talk")
        .description("Whisper naughty things in your enemy's ear")
        .defaultValue(true)
        .visible(message::get)
        .build()
    );

    private final Setting<Boolean> dm = sgSex.add(new BoolSetting.Builder()
        .name("private-msg")
        .description("Sends a private chat msg to the person")
        .defaultValue(false)
        .visible(message::get)
        .build()
    );

    private final Setting<Boolean> pm = sgSex.add(new BoolSetting.Builder()
        .name("public-msg")
        .description("Sends a public chat msg")
        .defaultValue(false)
        .visible(message::get)
        .build()
    );

    private final Setting<Integer> delay = sgSex.add(new IntSetting.Builder()
        .name("delay")
        .description("The delay between specified messages in ticks")
        .defaultValue(100)
        .min(0)
        .sliderMax(200)
        .visible(message::get)
        .build()
    );

    private final Setting<Boolean> random = sgSex.add(new BoolSetting.Builder()
        .name("randomise")
        .description("Selects a random message from your spam message list.")
        .defaultValue(false)
        .visible(message::get)
        .build()
    );

    private final Setting<List<String>> messages = sgSex.add(new StringListSetting.Builder()
        .name("messages")
        .description("Messages to use for dirty talk.")
        .defaultValue(List.of(
            "God, I love you so much (enemy)~",
            "Ahhhh! Fuck me harder (enemy)!",
            "Please put your cock inside me (enemy)!",
            "I want to choke on your cock (enemy)!",
            "Oh god, you're so big (enemy)!",
            "Treat me like a whore!",
            "Ahhhhn! Fuck me deeper (enemy)!",
            "Fill me with your spunk (enemy)~!",
            "Demolish my bussy (enemy)!~"
        ))
        .visible(message::get)
        .build()
    );

    private final Setting<Boolean> debugMode = sgMovement.add(new BoolSetting.Builder()
        .name("debug-mode")
        .description("Show debug information.")
        .defaultValue(false)
        .build()
    );

    private int messageI, timer;
    private boolean isFollowing = false;
    private String playerName;
    private PlayerEntity playerEntity;
    private int iPublic;
    private boolean pressed = false;
    private boolean alternate = true;
    private boolean wasCrouching = false;
    private int twerkTimer = 0;
    private boolean crouchState = false;
    private IBaritone baritone = null;
    private BlockPos lastTargetPos = null;
    private boolean isInPosition = false;
    private int positionStableTimer = 0;

    public AutoSex() {
        super(Addon.CATEGORY, "auto-Sex", "Tries to have sex with the player in different ways.");
    }

    @Override
    public void onActivate() {
        timer = delay.get();
        messageI = 0;
        wasCrouching = false;
        twerkTimer = 0;
        crouchState = false;
        isInPosition = false;
        positionStableTimer = 0;
        
        try {
            baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        } catch (Exception e) {
            error("Baritone not found! Please install Baritone mod.");
            toggle();
        }
    }

    @Override
    public void onDeactivate() {
        stopFollowing();
        playerEntity = null;
        playerName = null;
        isFollowing = false;
        
        // Reset crouch state
        if (wasCrouching) {
            mc.options.sneakKey.setPressed(false);
            wasCrouching = false;
            crouchState = false;
        }
        
        if (baritone != null) {
            baritone.getPathingBehavior().cancelEverything();
        }
    }

    private boolean isPlayerAllowed(PlayerEntity player) {
        if (player == null) return false;
        if (player == mc.player) return false;
        
        boolean isFriend = Friends.get().isFriend(player);
        
        return switch (friendFilter.get()) {
            case ALL -> true;
            case ONLY_FRIENDS -> isFriend;
            case ONLY_NON_FRIENDS -> !isFriend;
        };
    }

    @EventHandler
    private void onMouseButton(MouseButtonEvent event) {
        if (targetMode.get() == Mode.MiddleClick) {
            if (event.action == KeyAction.Press && event.button == GLFW_MOUSE_BUTTON_MIDDLE && mc.currentScreen == null && mc.targetedEntity != null && mc.targetedEntity instanceof PlayerEntity) {
                PlayerEntity target = (PlayerEntity) mc.targetedEntity;
                
                if (!isPlayerAllowed(target)) {
                    if (friendFilter.get() == FriendFilter.ONLY_FRIENDS) {
                        error("§cThat player is not your friend!");
                    } else if (friendFilter.get() == FriendFilter.ONLY_NON_FRIENDS) {
                        error("§cThat player is your friend!");
                    }
                    return;
                }
                
                if (!isFollowing) {
                    playerName = target.getName().getString();
                    playerEntity = target;

                    if (message.get()) {
                        startMsg();
                    }

                    startFollowing();
                    isFollowing = true;
                } else {
                    if (message.get()) {
                        endMsg();
                    }

                    stopFollowing();
                    playerName = null;
                    isFollowing = false;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;

        // Handle bind click mode
        if (targetMode.get() == Mode.BindClick && keybind != null) {
            if (keybind.get().isPressed() && !pressed && !alternate) {
                if (isFollowing) {
                    if (message.get()) {
                        endMsg();
                    }
                    pressed = true;
                    alternate = true;
                    playerName = null;
                    stopFollowing();
                    isFollowing = false;
                }
            }

            if (!keybind.get().isPressed()) {
                pressed = false;
            }

            if (keybind.get().isPressed() && !pressed && alternate && mc.currentScreen == null && mc.targetedEntity != null && mc.targetedEntity instanceof PlayerEntity) {
                PlayerEntity target = (PlayerEntity) mc.targetedEntity;
                
                if (!isPlayerAllowed(target)) {
                    if (friendFilter.get() == FriendFilter.ONLY_FRIENDS) {
                        error("§cThat player is not your friend!");
                    } else if (friendFilter.get() == FriendFilter.ONLY_NON_FRIENDS) {
                        error("§cThat player is your friend!");
                    }
                    return;
                }
                
                if (!isFollowing) {
                    playerName = target.getName().getString();
                    playerEntity = target;

                    if (message.get()) {
                        startMsg();
                    }

                    startFollowing();
                    pressed = true;
                    alternate = false;
                    isFollowing = true;
                }
            }
        }

        // Handle automatic mode
        if (targetMode.get() == Mode.Automatic) {
            if (!isFollowing) {
                PlayerEntity potentialTarget = null;
                double closestDistance = targetRange.get();
                
                for (PlayerEntity player : mc.world.getPlayers()) {
                    if (isPlayerAllowed(player)) {
                        double dist = mc.player.distanceTo(player);
                        if (dist <= closestDistance) {
                            closestDistance = dist;
                            potentialTarget = player;
                        }
                    }
                }
                
                if (potentialTarget == null) {
                    if (friendFilter.get() == FriendFilter.ONLY_FRIENDS && debugMode.get()) {
                        error("§cNo friends found in range!");
                    }
                    return;
                }
                
                playerEntity = potentialTarget;
                playerName = playerEntity.getName().getString();

                if (message.get()) {
                    startMsg();
                }

                startFollowing();
                isFollowing = true;
            }

            if (!playerEntity.isAlive() || (playerEntity.distanceTo(mc.player) > maxFollowRange.get() && !ignoreRange.get())) {
                if (message.get()) {
                    endMsg();
                }
                playerEntity = null;
                playerName = null;
                stopFollowing();
                isFollowing = false;
            }
        }

        // Handle following
        if (isFollowing && playerEntity != null && baritone != null) {
            BlockPos targetPos = getApproachPosition();
            
            // Check if we've reached the goal block
            boolean reachedGoal = mc.player.getBlockPos().equals(targetPos);
            
            // Handle position state changes
            if (reachedGoal && !isInPosition) {
                isInPosition = true;
                positionStableTimer = 0;
                if (autoLook.get() && debugMode.get()) {
                    info("§aReached target position!");
                }
            } else if (!reachedGoal && isInPosition) {
                isInPosition = false;
                positionStableTimer = 0;
                // Stop twerking when leaving position
                if (wasCrouching) {
                    mc.options.sneakKey.setPressed(false);
                    wasCrouching = false;
                    crouchState = false;
                    twerkTimer = 0;
                }
            }
            
            // Continuous auto-look when in position
            if (isInPosition && autoLook.get()) {
                updateLookAtBackOfHead();
            }
            
            // Handle twerking with delay
            if (isInPosition && twerkWhenClose.get()) {
                if (positionStableTimer < twerkDelay.get()) {
                    positionStableTimer++;
                } else {
                    // Twerking logic
                    if (twerkTimer <= 0) {
                        int ticksBetween = Math.max(1, 20 / twerkSpeed.get());
                        twerkTimer = ticksBetween;
                        crouchState = !crouchState;
                        mc.options.sneakKey.setPressed(crouchState);
                        wasCrouching = true;
                    } else {
                        twerkTimer--;
                    }
                }
            } else if (!isInPosition && wasCrouching) {
                // Ensure crouch is off if not in position
                mc.options.sneakKey.setPressed(false);
                wasCrouching = false;
                crouchState = false;
                twerkTimer = 0;
            }
            
            // Pathfinding - only when not at goal
            if (!reachedGoal) {
                if (lastTargetPos == null || !lastTargetPos.equals(targetPos)) {
                    lastTargetPos = targetPos;
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(targetPos));
                    if (debugMode.get()) {
                        info("§7Pathfinding to: §f" + targetPos.getX() + ", " + targetPos.getY() + ", " + targetPos.getZ());
                    }
                }
            } else if (baritone.getPathingBehavior().isPathing()) {
                // Cancel pathfinding if we've reached the goal
                baritone.getPathingBehavior().cancelEverything();
            }

            // Dirty talk messages (only when in position)
            if (isInPosition && dirtyTalk.get() && message.get() && !messages.get().isEmpty()) {
                if (timer <= 0) {
                    int i;
                    if (random.get()) {
                        i = Utils.random(0, messages.get().size());
                    } else {
                        if (messageI >= messages.get().size()) messageI = 0;
                        i = messageI++;
                    }

                    iPublic = i;
                    followMsg();
                    timer = delay.get();
                } else {
                    timer--;
                }
            }
        }
    }

    private BlockPos getApproachPosition() {
        if (playerEntity == null) return mc.player.getBlockPos();
        
        Vec3d playerPos = playerEntity.getPos();
        
        return switch (approachMode.get()) {
            case Direct -> {
                yield new BlockPos(
                    (int) Math.floor(playerPos.x),
                    (int) Math.floor(playerPos.y),
                    (int) Math.floor(playerPos.z)
                );
            }
            case Behind -> {
                // Get the direction the player is FACING
                float yaw = playerEntity.getBodyYaw();
                double rad = Math.toRadians(yaw);
                
                // Calculate facing direction (0° = South, 90° = West, etc.)
                double facingX = -Math.sin(rad);
                double facingZ = Math.cos(rad);
                
                // Behind is OPPOSITE direction
                double behindX = playerPos.x - (facingX * behindOffset.get());
                double behindZ = playerPos.z - (facingZ * behindOffset.get());
                
                yield new BlockPos(
                    (int) Math.floor(behindX),
                    (int) Math.floor(playerPos.y),
                    (int) Math.floor(behindZ)
                );
            }
            case Side -> {
                // Side = perpendicular to facing direction (+90° for right side)
                float yaw = playerEntity.getBodyYaw();
                double rad = Math.toRadians(yaw + 90);
                
                double offsetX = -Math.sin(rad) * sideOffset.get();
                double offsetZ = Math.cos(rad) * sideOffset.get();
                
                yield new BlockPos(
                    (int) Math.floor(playerPos.x + offsetX),
                    (int) Math.floor(playerPos.y),
                    (int) Math.floor(playerPos.z + offsetZ)
                );
            }
        };
    }

    private void updateLookAtBackOfHead() {
        if (playerEntity == null) return;
        
        // Get the direction the player is facing
        float yaw = playerEntity.getBodyYaw();
        double rad = Math.toRadians(yaw);
        
        // Calculate facing direction
        double facingX = -Math.sin(rad);
        double facingZ = Math.cos(rad);
        
        // Position behind the head (0.5 blocks back, at eye height)
        Vec3d headPos = playerEntity.getPos().add(
            facingX * 0.5,
            playerEntity.getEyeHeight(playerEntity.getPose()),
            facingZ * 0.5
        );
        
        // Calculate rotation to look at that position
        double dx = headPos.x - mc.player.getX();
        double dy = headPos.y - (mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()));
        double dz = headPos.z - mc.player.getZ();
        
        double dh = Math.sqrt(dx * dx + dz * dz);
        double targetYaw = Math.toDegrees(Math.atan2(dz, dx)) - 90;
        double targetPitch = -Math.toDegrees(Math.atan2(dy, dh));
        
        // Apply rotation
        Rotations.rotate(targetYaw, targetPitch, 50);
    }

    private void startFollowing() {
        if (baritone != null && playerEntity != null) {
            info("§aNow following §f" + playerName);
            info("§7Friend filter: §f" + friendFilter.get());
            info("§7Mode: §f" + approachMode.get());
            if (autoLook.get()) {
                info("§7Auto-look: §aON §7- Continuously looking at back of head");
            }
            if (twerkWhenClose.get()) {
                info("§7Twerk delay: §f" + twerkDelay.get() / 20.0 + " §7seconds");
            }
            lastTargetPos = null;
            isInPosition = false;
            positionStableTimer = 0;
        } else {
            error("§cBaritone is not available!");
        }
    }

    private void stopFollowing() {
        if (baritone != null) {
            baritone.getPathingBehavior().cancelEverything();
        }
        if (wasCrouching) {
            mc.options.sneakKey.setPressed(false);
            wasCrouching = false;
            crouchState = false;
            twerkTimer = 0;
        }
        lastTargetPos = null;
        isInPosition = false;
        positionStableTimer = 0;
        info("§aStopped following");
    }

    public void startMsg() {
        if (dirtyTalk.get()) {
            if (dm.get()) {
                ChatUtils.sendPlayerMsg("/msg " + playerName + " Come here bby lets have sex uwu");
            }
            if (pm.get()) {
                ChatUtils.sendPlayerMsg("Come here " + playerName + " lets have sex uwu");
            }
        } else {
            if (dm.get()) {
                ChatUtils.sendPlayerMsg("/msg " + playerName + " I am now following you");
            }
            if (pm.get()) {
                ChatUtils.sendPlayerMsg("I am now following " + playerName);
            }
        }
    }

    public void followMsg() {
        if (dm.get()) {
            ChatUtils.sendPlayerMsg("/msg " + playerName + " " + messages.get().get(iPublic).replace("(enemy)", playerName));
        }
        if (pm.get()) {
            ChatUtils.sendPlayerMsg(messages.get().get(iPublic).replace("(enemy)", playerName));
        }
    }

    public void endMsg() {
        if (dirtyTalk.get()) {
            if (dm.get()) {
                ChatUtils.sendPlayerMsg("/msg " + playerName + " See u later bby girl ;*");
            }
            if (pm.get()) {
                ChatUtils.sendPlayerMsg("See u later " + playerName + " xxx ;*");
            }
        } else {
            if (dm.get()) {
                ChatUtils.sendPlayerMsg("/msg " + playerName + " I am no longer following you");
            }
            if (pm.get()) {
                ChatUtils.sendPlayerMsg("I am no longer following " + playerName);
            }
        }
    }
}