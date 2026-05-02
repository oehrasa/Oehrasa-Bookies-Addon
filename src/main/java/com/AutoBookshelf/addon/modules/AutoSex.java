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
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Set;

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
    private final SettingGroup sgMobs = settings.createGroup("Mob Targeting");
    private final SettingGroup sgSex = settings.createGroup("Auto Sex");
    private final SettingGroup sgMovement = settings.createGroup("Movement");

    private final Setting<Mode> targetMode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("target-mode")
        .description("The mode at which to follow the target.")
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
        .description("How to select the target.")
        .defaultValue(SortPriority.LowestDistance)
        .visible(() -> targetMode.get() == Mode.Automatic)
        .build()
    );

    private final Setting<Boolean> ignoreRange = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-range")
        .description("Follow the target even if they are out of range.")
        .defaultValue(false)
        .visible(() -> targetMode.get() == Mode.Automatic)
        .build()
    );

    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("The range in which it follows a target.")
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
        .description("Sends a message to the player when you start/stop following them.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Set<EntityType<?>>> mobTypes = sgMobs.add(new EntityTypeListSetting.Builder()
        .name("mob-types")
        .description("Which mob types to target.")
        .defaultValue()
        .build()
    );

    private final Setting<Boolean> onlyHostileMobs = sgMobs.add(new BoolSetting.Builder()
        .name("only-hostile")
        .description("Only follow mobs that are currently hostile (angry/attacking).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> ignoreNamedMobs = sgMobs.add(new BoolSetting.Builder()
        .name("ignore-named")
        .description("Ignore mobs that have a custom name.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> ignoreTamed = sgMobs.add(new BoolSetting.Builder()
        .name("ignore-tamed")
        .description("Ignore tamed mobs.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ApproachMode> approachMode = sgMovement.add(new EnumSetting.Builder<ApproachMode>()
        .name("approach-mode")
        .description("How to approach the target.")
        .defaultValue(ApproachMode.Behind)
        .build()
    );

    private final Setting<Double> behindOffset = sgMovement.add(new DoubleSetting.Builder()
        .name("behind-offset")
        .description("How many blocks behind the target to stand.")
        .defaultValue(1.5)
        .min(1.0)
        .max(3.0)
        .sliderRange(1.0, 3.0)
        .visible(() -> approachMode.get() == ApproachMode.Behind)
        .build()
    );

    private final Setting<Double> sideOffset = sgMovement.add(new DoubleSetting.Builder()
        .name("side-offset")
        .description("How many blocks to the side of the target to stand.")
        .defaultValue(2.0)
        .min(1.0)
        .max(4.0)
        .sliderRange(1.0, 4.0)
        .visible(() -> approachMode.get() == ApproachMode.Side)
        .build()
    );

    private final Setting<Integer> maxFollowRange = sgMovement.add(new IntSetting.Builder()
        .name("max-follow-range")
        .description("Maximum range to follow the target.")
        .defaultValue(100)
        .min(10)
        .max(500)
        .sliderRange(10, 500)
        .build()
    );

    private final Setting<Boolean> autoLook = sgMovement.add(new BoolSetting.Builder()
        .name("auto-look")
        .description("Continuously look at the back of the target's head when in position.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> pathUpdateThreshold = sgMovement.add(new DoubleSetting.Builder()
        .name("path-update-threshold")
        .description("How far the target must move horizontally (blocks) before Baritone recalculates the path.")
        .defaultValue(0.5)
        .min(0.1)
        .max(2.0)
        .sliderRange(0.1, 2.0)
        .build()
    );

    private final Setting<Integer> pathUpdateCooldown = sgMovement.add(new IntSetting.Builder()
        .name("path-update-cooldown")
        .description("Minimum ticks between path recalculations.")
        .defaultValue(10)
        .min(0)
        .max(60)
        .sliderRange(0, 60)
        .build()
    );

    private final Setting<Boolean> debugMode = sgMovement.add(new BoolSetting.Builder()
        .name("debug-mode")
        .description("Show debug information.")
        .defaultValue(false)
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
        .description("How many times per second to spam crouch.")
        .defaultValue(8)
        .min(1)
        .max(20)
        .visible(twerkWhenClose::get)
        .build()
    );

    private final Setting<Integer> twerkDelay = sgSex.add(new IntSetting.Builder()
        .name("twerk-delay")
        .description("Delay in ticks before starting to twerk after reaching position.")
        .defaultValue(10)
        .min(0)
        .max(40)
        .visible(twerkWhenClose::get)
        .build()
    );

    private final Setting<Boolean> dirtyTalk = sgSex.add(new BoolSetting.Builder()
        .name("dirty-talk")
        .description("Whisper naughty things in your enemy's ear.")
        .defaultValue(true)
        .visible(message::get)
        .build()
    );

    private final Setting<Boolean> dm = sgSex.add(new BoolSetting.Builder()
        .name("private-msg")
        .description("Sends a private chat msg to the person.")
        .defaultValue(false)
        .visible(message::get)
        .build()
    );

    private final Setting<Boolean> pm = sgSex.add(new BoolSetting.Builder()
        .name("public-msg")
        .description("Sends a public chat msg.")
        .defaultValue(false)
        .visible(message::get)
        .build()
    );

    private final Setting<Integer> delay = sgSex.add(new IntSetting.Builder()
        .name("delay")
        .description("The delay between specified messages in ticks.")
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

    private int messageI, timer;
    private boolean isFollowing = false;
    private LivingEntity targetEntity;   // can be PlayerEntity or Mob
    private String targetName;           // for display
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
    private Vec3d lastTargetPosVec = null;
    private int pathCooldown = 0;

    public AutoSex() {
        super(Addon.CATEGORY, "auto-Sex", "Tries to have sex with the player or mob in freaky ways.");
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
        lastTargetPosVec = null;
        pathCooldown = 0;

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
        targetEntity = null;
        targetName = null;
        isFollowing = false;

        if (wasCrouching) {
            mc.options.sneakKey.setPressed(false);
            wasCrouching = false;
            crouchState = false;
        }

        if (baritone != null) {
            baritone.getPathingBehavior().cancelEverything();
        }
    }

    // Check if an entity is allowed as target (respects friend filter only for players)
    private boolean isEntityAllowed(LivingEntity entity) {
        if (entity == null) return false;
        if (entity == mc.player) return false;

        // Player specific filtering
        if (entity instanceof PlayerEntity player) {
            boolean isFriend = Friends.get().isFriend(player);
            return switch (friendFilter.get()) {
                case ALL -> true;
                case ONLY_FRIENDS -> isFriend;
                case ONLY_NON_FRIENDS -> !isFriend;
            };
        }

        // Mob filtering
        if (!mobTypes.get().contains(entity.getType())) return false;
        if (ignoreNamedMobs.get() && entity.hasCustomName()) return false;
        if (ignoreTamed.get() && entity instanceof TameableEntity tameable && tameable.isTamed()) return false;
        if (onlyHostileMobs.get()) {
            // Simple hostile check: if the mob is currently attacking or angry
            if (entity instanceof net.minecraft.entity.mob.Angerable angerable && angerable.getAngryAt() != null) return true;
            if (entity instanceof net.minecraft.entity.mob.HostileEntity) return true;
            // If not obviously hostile, treat as not allowed
            return false;
        }
        return true;
    }

    @EventHandler
    private void onMouseButton(MouseButtonEvent event) {
        if (targetMode.get() == Mode.MiddleClick) {
            if (event.action == KeyAction.Press && event.button == GLFW_MOUSE_BUTTON_MIDDLE && mc.currentScreen == null && mc.targetedEntity != null && mc.targetedEntity instanceof LivingEntity living) {
                if (!isEntityAllowed(living)) {
                    if (living instanceof PlayerEntity) {
                        if (friendFilter.get() == FriendFilter.ONLY_FRIENDS) error("§cThat player is not your friend!");
                        else if (friendFilter.get() == FriendFilter.ONLY_NON_FRIENDS) error("§cThat player is your friend!");
                        else error("§cThat entity is not allowed for targeting");
                    } else {
                        error("§cThat mob is not allowed (check mob types / filters)");
                    }
                    return;
                }

                if (!isFollowing) {
                    targetEntity = living;
                    targetName = living.getName().getString();

                    if (message.get() && living instanceof PlayerEntity) {
                        startMsg();
                    }

                    startFollowing();
                    isFollowing = true;
                } else {
                    if (message.get() && targetEntity instanceof PlayerEntity) {
                        endMsg();
                    }

                    stopFollowing();
                    targetEntity = null;
                    targetName = null;
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
                    if (message.get() && targetEntity instanceof PlayerEntity) {
                        endMsg();
                    }
                    pressed = true;
                    alternate = true;
                    targetEntity = null;
                    targetName = null;
                    stopFollowing();
                    isFollowing = false;
                }
            }

            if (!keybind.get().isPressed()) {
                pressed = false;
            }

            if (keybind.get().isPressed() && !pressed && alternate && mc.currentScreen == null && mc.targetedEntity != null && mc.targetedEntity instanceof LivingEntity living) {
                if (!isEntityAllowed(living)) {
                    if (living instanceof PlayerEntity) {
                        if (friendFilter.get() == FriendFilter.ONLY_FRIENDS) error("§cThat player is not your friend!");
                        else if (friendFilter.get() == FriendFilter.ONLY_NON_FRIENDS) error("§cThat player is your friend!");
                        else error("§cThat entity is not allowed.");
                    } else {
                        error("§cThat mob is not allowed (check mob types / filters)");
                    }
                    return;
                }

                if (!isFollowing) {
                    targetEntity = living;
                    targetName = living.getName().getString();

                    if (message.get() && living instanceof PlayerEntity) {
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
                LivingEntity potentialTarget = null;
                double closestDistance = targetRange.get();

                for (Entity entity : mc.world.getEntities()) {
                    if (entity instanceof LivingEntity living && isEntityAllowed(living)) {
                        double dist = mc.player.distanceTo(entity);
                        if (dist <= closestDistance) {
                            closestDistance = dist;
                            potentialTarget = living;
                        }
                    }
                }

                if (potentialTarget == null) {
                    if (debugMode.get() && friendFilter.get() == FriendFilter.ONLY_FRIENDS) error("§cNo valid targets in range!");
                    return;
                }

                targetEntity = potentialTarget;
                targetName = targetEntity.getName().getString();

                if (message.get() && targetEntity instanceof PlayerEntity) {
                    startMsg();
                }

                startFollowing();
                isFollowing = true;
            }

            if (!targetEntity.isAlive() || (targetEntity.distanceTo(mc.player) > maxFollowRange.get() && !ignoreRange.get())) {
                if (message.get() && targetEntity instanceof PlayerEntity) {
                    endMsg();
                }
                targetEntity = null;
                targetName = null;
                stopFollowing();
                isFollowing = false;
            }
        }

        // Handle following
        if (isFollowing && targetEntity != null && baritone != null) {
            BlockPos targetPos = getApproachPosition();

            boolean reachedGoal = mc.player.getBlockPos().equals(targetPos);

            if (reachedGoal && !isInPosition) {
                isInPosition = true;
                positionStableTimer = 0;
                if (autoLook.get() && debugMode.get()) {
                    info("§aReached target position!");
                }
            } else if (!reachedGoal && isInPosition) {
                isInPosition = false;
                positionStableTimer = 0;
                if (wasCrouching) {
                    mc.options.sneakKey.setPressed(false);
                    wasCrouching = false;
                    crouchState = false;
                    twerkTimer = 0;
                }
            }

            if (isInPosition && autoLook.get()) {
                updateLookAtBackOfHead();
            }

            if (isInPosition && twerkWhenClose.get()) {
                if (positionStableTimer < twerkDelay.get()) {
                    positionStableTimer++;
                } else {
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
                mc.options.sneakKey.setPressed(false);
                wasCrouching = false;
                crouchState = false;
                twerkTimer = 0;
            }

            if (!reachedGoal) {
                if (pathCooldown > 0) pathCooldown--;

                Vec3d targetPosVec = targetEntity.getPos();
                boolean shouldUpdate = false;

                if (lastTargetPosVec == null) {
                    shouldUpdate = true;
                } else {
                    // Horizontal distance only (ignore jumping)
                    double dx = targetPosVec.x - lastTargetPosVec.x;
                    double dz = targetPosVec.z - lastTargetPosVec.z;
                    double horizontalDist = Math.sqrt(dx * dx + dz * dz);
                    if (horizontalDist >= pathUpdateThreshold.get()) {
                        shouldUpdate = true;
                    }
                }

                boolean goalChanged = (lastTargetPos == null || !lastTargetPos.equals(targetPos));

                if ((shouldUpdate || goalChanged) && (pathCooldown == 0 || goalChanged)) {
                    lastTargetPosVec = targetPosVec;
                    lastTargetPos = targetPos;
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(targetPos));
                    if (debugMode.get()) {
                        info("§7Pathfinding to: §f" + targetPos.getX() + ", " + targetPos.getY() + ", " + targetPos.getZ());
                    }
                    pathCooldown = pathUpdateCooldown.get();
                }
            } else if (baritone.getPathingBehavior().isPathing()) {
                baritone.getPathingBehavior().cancelEverything();
            }

            // Dirty talk only for players
            if (isInPosition && dirtyTalk.get() && message.get() && targetEntity instanceof PlayerEntity && !messages.get().isEmpty()) {
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
        if (targetEntity == null) return mc.player.getBlockPos();

        Vec3d targetPos = targetEntity.getPos();

        return switch (approachMode.get()) {
            case Direct -> new BlockPos((int) Math.floor(targetPos.x), (int) Math.floor(targetPos.y), (int) Math.floor(targetPos.z));
            case Behind -> {
                float yaw = targetEntity.getBodyYaw();
                double rad = Math.toRadians(yaw);
                double facingX = -Math.sin(rad);
                double facingZ = Math.cos(rad);
                double behindX = targetPos.x - (facingX * behindOffset.get());
                double behindZ = targetPos.z - (facingZ * behindOffset.get());
                yield new BlockPos((int) Math.floor(behindX), (int) Math.floor(targetPos.y), (int) Math.floor(behindZ));
            }
            case Side -> {
                float yaw = targetEntity.getBodyYaw();
                double rad = Math.toRadians(yaw + 90);
                double offsetX = -Math.sin(rad) * sideOffset.get();
                double offsetZ = Math.cos(rad) * sideOffset.get();
                yield new BlockPos((int) Math.floor(targetPos.x + offsetX), (int) Math.floor(targetPos.y), (int) Math.floor(targetPos.z + offsetZ));
            }
        };
    }

    private void updateLookAtBackOfHead() {
        if (targetEntity == null) return;

        float yaw = targetEntity.getBodyYaw();
        double rad = Math.toRadians(yaw);
        double facingX = -Math.sin(rad);
        double facingZ = Math.cos(rad);

        Vec3d headPos = targetEntity.getPos().add(
            facingX * 0.5,
            targetEntity.getEyeHeight(targetEntity.getPose()),
            facingZ * 0.5
        );

        double dx = headPos.x - mc.player.getX();
        double dy = headPos.y - (mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()));
        double dz = headPos.z - mc.player.getZ();

        double dh = Math.sqrt(dx * dx + dz * dz);
        double targetYaw = Math.toDegrees(Math.atan2(dz, dx)) - 90;
        double targetPitch = -Math.toDegrees(Math.atan2(dy, dh));

        Rotations.rotate(targetYaw, targetPitch, 50);
    }

    private void startFollowing() {
        if (baritone != null && targetEntity != null) {
            info("§aNow following §f" + targetName);
            info("§7Friend filter: §f" + friendFilter.get());
            info("§7Mode: §f" + approachMode.get());
            if (autoLook.get()) {
                info("§7Auto-look: §aON §7- Continuously looking at back of head");
            }
            if (twerkWhenClose.get()) {
                info("§7Twerk delay: §f" + twerkDelay.get() / 20.0 + " §7seconds");
            }
            lastTargetPos = null;
            lastTargetPosVec = null;
            pathCooldown = 0;
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
        lastTargetPosVec = null;
        pathCooldown = 0;
        isInPosition = false;
        positionStableTimer = 0;
        info("§aStopped following");
    }

    public void startMsg() {
        if (dirtyTalk.get()) {
            if (dm.get()) ChatUtils.sendPlayerMsg("/msg " + targetName + " Come here bby lets have sex uwu");
            if (pm.get()) ChatUtils.sendPlayerMsg("Heya, Come here " + targetName + " lets have sex uwu");
        } else {
            if (dm.get()) ChatUtils.sendPlayerMsg("/msg " + targetName + " I am now following you");
            if (pm.get()) ChatUtils.sendPlayerMsg("I am now following " + targetName);
        }
    }

    public void followMsg() {
        if (dm.get()) ChatUtils.sendPlayerMsg("/msg " + targetName + " " + messages.get().get(iPublic).replace("(enemy)", targetName));
        if (pm.get()) ChatUtils.sendPlayerMsg(messages.get().get(iPublic).replace("(enemy)", targetName));
    }

    public void endMsg() {
        if (dirtyTalk.get()) {
            if (dm.get()) ChatUtils.sendPlayerMsg("/msg " + targetName + " See u later bby girl ;*");
            if (pm.get()) ChatUtils.sendPlayerMsg("See u later " + targetName + " xxx ;*");
        } else {
            if (dm.get()) ChatUtils.sendPlayerMsg("/msg " + targetName + " I am no longer following you");
            if (pm.get()) ChatUtils.sendPlayerMsg("I am no longer following " + targetName);
        }
    }
}
