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
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.entity.Entity;
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
        Behind("Behind - Stand behind target before twerking"),
        Side("Side - Stand to the side of target");

        private final String title;
        ApproachMode(String title) { this.title = title; }
        @Override public String toString() { return title; }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSex = settings.createGroup("Auto Sex");
    private final SettingGroup sgMovement = settings.createGroup("Movement");

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

    private final Setting<Boolean> onlyFriend = sgGeneral.add(new BoolSetting.Builder()
        .name("only-friends")
        .description("Whether or not to only follow friends")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> onlyOther = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-friends")
        .description("Whether or not to follow friends")
        .defaultValue(false)
        .visible(() -> targetMode.get() != Mode.Automatic)
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
        .description("How to approach the target before twerking")
        .defaultValue(ApproachMode.Behind)
        .build()
    );

    private final Setting<Double> followDistance = sgMovement.add(new DoubleSetting.Builder()
        .name("follow-distance")
        .description("How close to get to the target before stopping (in blocks)")
        .defaultValue(0.5)
        .min(0.5)
        .max(5.0)
        .sliderRange(0.5, 5.0)
        .build()
    );

    private final Setting<Double> behindOffset = sgMovement.add(new DoubleSetting.Builder()
        .name("behind-offset")
        .description("How awkward behind the target to stand")
        .defaultValue(1.0)
        .min(1.0)
        .max(1.5)
        .sliderRange(1.0, 1.5)
        .visible(() -> approachMode.get() == ApproachMode.Behind)
        .build()
    );

    private final Setting<Double> sideOffset = sgMovement.add(new DoubleSetting.Builder()
        .name("side-offset")
        .description("How many blocks to the side of the target to stand")
        .defaultValue(1.0)
        .min(1.0)
        .max(1.5)
        .sliderRange(1.0, 1.5)
        .visible(() -> approachMode.get() == ApproachMode.Side)
        .build()
    );

    private final Setting<Integer> maxFollowRange = sgMovement.add(new IntSetting.Builder()
        .name("max-follow-range")
        .description("Maximum range to follow the target (blocks)")
        .defaultValue(30)
        .min(10)
        .max(500)
        .sliderRange(10, 500)
        .build()
    );

    private final Setting<Boolean> twerkWhenClose = sgSex.add(new BoolSetting.Builder()
        .name("auto-hump")
        .description("Crouch against the target to give the appearance of sex OwO")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> twerkSpeed = sgSex.add(new IntSetting.Builder()
        .name("twerk-speed")
        .description("How many times per second to spam crouch (1-20)")
        .defaultValue(10)
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

    private final Setting<Boolean> dm = sgGeneral.add(new BoolSetting.Builder()
        .name("private-msg")
        .description("Sends a private chat msg to the person")
        .defaultValue(false)
        .visible(message::get)
        .build()
    );

    private final Setting<Boolean> pm = sgGeneral.add(new BoolSetting.Builder()
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

    public AutoSex() {
        super(Addon.CATEGORY, "auto-Sex", "Tries to have sex with the player in different ways.");
    }

    private int messageI, timer;
    private boolean isFollowing = false;
    private String playerName;
    private Entity playerEntity;
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
    private boolean isTwerking = false;

    @Override
    public void onActivate() {
        timer = delay.get();
        messageI = 0;
        wasCrouching = false;
        twerkTimer = 0;
        crouchState = false;
        isInPosition = false;
        positionStableTimer = 0;
        isTwerking = false;
        
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
        
        if (baritone != null) {
            baritone.getPathingBehavior().cancelEverything();
        }
    }

    @EventHandler
    private void onMouseButton(MouseButtonEvent event) {
        if (targetMode.get() == Mode.MiddleClick) {
            if (event.action == KeyAction.Press && event.button == GLFW_MOUSE_BUTTON_MIDDLE && mc.currentScreen == null && mc.targetedEntity != null && mc.targetedEntity instanceof PlayerEntity) {
                if (!isFollowing) {
                    if (!Friends.get().isFriend((PlayerEntity) mc.targetedEntity) && onlyFriend.get()) return;
                    if (Friends.get().isFriend((PlayerEntity) mc.targetedEntity) && onlyOther.get()) return;

                    playerName = mc.targetedEntity.getName().getString();
                    playerEntity = mc.targetedEntity;

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
                if (!isFollowing) {
                    if (!Friends.get().isFriend((PlayerEntity) mc.targetedEntity) && onlyFriend.get()) return;
                    if (Friends.get().isFriend((PlayerEntity) mc.targetedEntity) && onlyOther.get()) return;

                    playerName = mc.targetedEntity.getName().getString();
                    playerEntity = mc.targetedEntity;

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
                playerEntity = TargetUtils.getPlayerTarget(targetRange.get(), priority.get());
                if (playerEntity == null) return;
                playerName = playerEntity.getName().getString();

                if (!Friends.get().isFriend((PlayerEntity) playerEntity) && onlyFriend.get()) return;

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

        // Handle following with Baritone
        if (isFollowing && playerEntity != null && baritone != null) {
            double distance = mc.player.distanceTo(playerEntity);
            BlockPos targetPos = getApproachPosition();
            
            // Calculate distance to goal position
            double distanceToGoal = Math.sqrt(mc.player.getBlockPos().getSquaredDistance(targetPos));
            boolean wasInPosition = isInPosition;
            isInPosition = (distanceToGoal <= followDistance.get()) || (distance <= followDistance.get());
            
            // Track how long we've been in position (stability timer)
            if (isInPosition) {
                if (!wasInPosition) {
                    positionStableTimer = 0;
                } else if (positionStableTimer < twerkDelay.get()) {
                    positionStableTimer++;
                }
            } else {
                positionStableTimer = 0;
                isTwerking = false;
            }
            
            // Only pathfind when NOT in position
            if (!isInPosition) {
                // Cancel twerking if we were
                if (isTwerking) {
                    isTwerking = false;
                    if (wasCrouching) {
                        mc.options.sneakKey.setPressed(false);
                        wasCrouching = false;
                        crouchState = false;
                        twerkTimer = 0;
                    }
                }
                
                // Update pathfinding if target moved
                if (lastTargetPos == null || !lastTargetPos.equals(targetPos)) {
                    lastTargetPos = targetPos;
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(targetPos));
                }
            } else {
                // In position - stop pathfinding
                if (baritone.getPathingBehavior().isPathing()) {
                    baritone.getPathingBehavior().cancelEverything();
                }
                
                // Start twerking only after stable in position
                if (twerkWhenClose.get() && positionStableTimer >= twerkDelay.get()) {
                    isTwerking = true;
                    if (twerkTimer <= 0) {
                        int ticksBetween = Math.max(1, 20 / twerkSpeed.get());
                        twerkTimer = ticksBetween;
                        crouchState = !crouchState;
                        mc.options.sneakKey.setPressed(crouchState);
                        wasCrouching = true;
                    } else {
                        twerkTimer--;
                    }
                } else if (!isTwerking && wasCrouching) {
                    // Ensure crouch is off if not twerking
                    mc.options.sneakKey.setPressed(false);
                    wasCrouching = false;
                    crouchState = false;
                    twerkTimer = 0;
                }
            }

            // Dirty talk messages (only when in position)
            if (isInPosition && dirtyTalk.get() && message.get()) {
                if (messages.get().isEmpty()) return;

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
        float playerYaw = playerEntity.getYaw();
        
        return switch (approachMode.get()) {
            case Direct -> {
                yield new BlockPos(
                    (int) Math.floor(playerPos.x),
                    (int) Math.floor(playerPos.y),
                    (int) Math.floor(playerPos.z)
                );
            }
            case Behind -> {
                double rad = Math.toRadians(playerYaw);
                double offsetX = -Math.sin(rad) * behindOffset.get();
                double offsetZ = -Math.cos(rad) * behindOffset.get();
                yield new BlockPos(
                    (int) Math.floor(playerPos.x + offsetX),
                    (int) Math.floor(playerPos.y),
                    (int) Math.floor(playerPos.z + offsetZ)
                );
            }
            case Side -> {
                double rad = Math.toRadians(playerYaw + 90);
                double offsetX = Math.sin(rad) * sideOffset.get();
                double offsetZ = Math.cos(rad) * sideOffset.get();
                yield new BlockPos(
                    (int) Math.floor(playerPos.x + offsetX),
                    (int) Math.floor(playerPos.y),
                    (int) Math.floor(playerPos.z + offsetZ)
                );
            }
        };
    }

    private void startFollowing() {
        if (baritone != null && playerEntity != null) {
            info("§aNow following §f" + playerName);
            info("§7Mode: §f" + approachMode.get());
            info("§7Distance: §f" + followDistance.get() + " §7blocks");
            if (twerkWhenClose.get()) {
                info("§7Twerk delay: §f" + twerkDelay.get() / 20.0 + " §7seconds");
            }
            lastTargetPos = null;
            isInPosition = false;
            positionStableTimer = 0;
            isTwerking = false;
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
        isTwerking = false;
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
    
    private final Setting<Boolean> debugMode = sgMovement.add(new BoolSetting.Builder()
        .name("debug-mode")
        .description("Show debug information.")
        .defaultValue(false)
        .build()
    );
}