package com.AutoBookshelf.addon.modules;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalBlock;
import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.utils.HotbarSwap;
import meteordevelopment.meteorclient.events.entity.player.SendMovementPacketsEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.Set;
import java.util.function.Predicate;

public class BDSMMask extends Module {

    public enum TargetMode {MiddleClick, BindClick, Automatic}

    public enum FriendFilter {
        ALL("All Players"),
        ONLY_FRIENDS("Only Friends"),
        ONLY_NON_FRIENDS("Only Non-Friends");
        private final String title;

        FriendFilter(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    public enum ActivationMode {RedstoneBlock, ObserverClock}

    public enum DisassembleMode {Vanilla, PacketBreak}

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTargets = settings.createGroup("Targets");
    private final SettingGroup sgGeometry = settings.createGroup("Geometry");
    private final SettingGroup sgMaterials = settings.createGroup("Materials");
    private final SettingGroup sgInventory = settings.createGroup("Inventory");
    private final SettingGroup sgActivation = settings.createGroup("Activation");

    // General
    private final Setting<TargetMode> targetMode = sgGeneral.add(new EnumSetting.Builder<TargetMode>()
        .name("target-mode")
        .description("How targets are selected.")
        .defaultValue(TargetMode.Automatic)
        .build()
    );
    private final Setting<Keybind> keybind = sgGeneral.add(new KeybindSetting.Builder()
        .name("keybind")
        .description("When target-mode=BindClick triggers attempt.")
        .defaultValue(Keybind.none())
        .visible(() -> targetMode.get() == TargetMode.BindClick)
        .build()
    );
    private final Setting<ActivationMode> activationMode = sgGeneral.add(new EnumSetting.Builder<ActivationMode>()
        .name("activation-mode")
        .description("One redstone block or two observer as a clock beside the dispenser.")
        .defaultValue(ActivationMode.RedstoneBlock)
        .build()
    );
    private final Setting<Boolean> autoToggle = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-toggle")
        .description("Automatically disable after a successful dispense.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> stopOnWearer = sgGeneral.add(new BoolSetting.Builder()
        .name("stop-on-wearer")
        .description("Skip targets that already wear a pumpkin.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Integer> delayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("delay-ticks")
        .description("Delay before retrying when no placement was possible.")
        .defaultValue(6)
        .min(1)
        .sliderMax(20)
        .build()
    );
    private final Setting<Integer> cooldownTicks = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown-ticks")
        .description("Ticks between successive attempts.")
        .defaultValue(20)
        .min(1)
        .sliderMax(100)
        .build()
    );
    private final Setting<Boolean> onlyTargetEmptyHead = sgGeneral.add(new BoolSetting.Builder()
        .name("only-target-empty-head")
        .description("For targeting, only consider entities with empty head slot.")
        .defaultValue(true)
        .visible(() -> targetMode.get() == TargetMode.Automatic)
        .build()
    );
    private final Setting<SortPriority> priority = sgGeneral.add(new EnumSetting.Builder<SortPriority>()
        .name("target-priority")
        .description("Target sort when Automatic.")
        .defaultValue(SortPriority.LowestDistance)
        .visible(() -> targetMode.get() == TargetMode.Automatic)
        .build()
    );
    private final Setting<Boolean> useBaritone = sgGeneral.add(new BoolSetting.Builder()
        .name("use-baritone")
        .description("Optional Baritone move to target if unreachable.")
        .defaultValue(false)
        .build()
    );

    // Targets
    private final Setting<Double> targetRange = sgTargets.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("Scan range only; attempt when dispensing placement is within reach.")
        .defaultValue(32)
        .min(1)
        .sliderRange(1, 100)
        .build()
    );
    private final Setting<FriendFilter> friendFilter = sgTargets.add(new EnumSetting.Builder<FriendFilter>()
        .name("friend-filter")
        .description("For players: which to target.")
        .defaultValue(FriendFilter.ALL)
        .build()
    );
    private final Setting<Set<EntityType<?>>> mobTypes = sgTargets.add(new EntityTypeListSetting.Builder()
        .name("mob-types")
        .description("Mob types to target.")
        .defaultValue(Set.of())
        .build()
    );
    private final Setting<Boolean> ignoreNamedMobs = sgTargets.add(new BoolSetting.Builder()
        .name("ignore-named-mobs")
        .description("Skip named mobs.")
        .defaultValue(false)
        .build()
    );
    private final Setting<Boolean> ignoreTamed = sgTargets.add(new BoolSetting.Builder()
        .name("ignore-tamed")
        .description("Skip tamed animals.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> onlyHostileMobs = sgTargets.add(new BoolSetting.Builder()
        .name("only-hostile-mobs")
        .description("Only target hostile mobs.")
        .defaultValue(false)
        .build()
    );

    // Geometry
    private final Setting<Double> maxHeightGap = sgGeometry.add(new DoubleSetting.Builder()
        .name("max-height-gap")
        .description("Scan upward until free placement cell.")
        .defaultValue(3.0)
        .min(1)
        .sliderMax(8)
        .build()
    );
    private final Setting<Boolean> airPlace = sgGeometry.add(new BoolSetting.Builder()
        .name("air-place")
        .description("Allow placing the dispenser with no solid neighbour to click.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> requireHitboxIntersection = sgGeometry.add(new BoolSetting.Builder()
        .name("require-hitbox-intersection")
        .description("Ensure target AABB intersects dispense cell.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> requireInReach = sgGeometry.add(new BoolSetting.Builder()
        .name("require-in-reach")
        .description("Only place when within vanilla reach.")
        .defaultValue(true)
        .build()
    );

    // Materials
    private final Setting<DisassembleMode> disassembleMode = sgMaterials.add(new EnumSetting.Builder<DisassembleMode>()
        .name("disassemble-mode")
        .description("How the dispenser and activation blocks are broken after use.")
        .defaultValue(DisassembleMode.Vanilla)
        .build()
    );

    // Inventory
    private final Setting<Integer> hotbarSlot = sgInventory.add(new IntSetting.Builder()
        .name("hotbar-slot")
        .description("Preferred hotbar slot (1-9) for the module's items (dispenser, activator, pickaxe).")
        .defaultValue(1)
        .min(1)
        .max(9)
        .sliderRange(1, 9)
        .build()
    );
    private final Setting<Boolean> bindingCurseEnchant = sgInventory.add(new BoolSetting.Builder()
        .name("binding-curse")
        .description("Need carved pumpkin enchanted with Curse of Binding.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Integer> pumpkinsPerDisp = sgInventory.add(new IntSetting.Builder()
        .name("pumpkins-per-dispenser")
        .description("How many carved pumpkins into each dispenser.")
        .defaultValue(1)
        .min(1)
        .max(64)
        .build()
    );
    private final Setting<Integer> pumpkinSlot = sgInventory.add(new IntSetting.Builder()
        .name("pumpkin-slot")
        .description("Dispenser GUI slot index (0-8) for pumpkin.")
        .defaultValue(0)
        .min(0)
        .max(8)
        .build()
    );

    // Activation
    private final Setting<Integer> verifyDispenseTicks = sgActivation.add(new IntSetting.Builder()
        .name("verify-dispense-ticks")
        .description("Ticks to wait for pumpkin to equip.")
        .defaultValue(6)
        .min(1)
        .max(20)
        .build()
    );
    private final Setting<Integer> maxMoveTicks = sgActivation.add(new IntSetting.Builder()
        .name("max-move-ticks")
        .description("Baritone movement timeout ticks.")
        .defaultValue(100)
        .min(10)
        .sliderMax(300)
        .build()
    );

    private final Setting<Boolean> dismantle = sgActivation.add(new BoolSetting.Builder()
        .name("dismantle")
        .description("Tear down the dispenser and activator after a successful dispense. dont leave any trace.")
        .defaultValue(true)
        .build()
    );
    private static final int SEARCH_INTERVAL = 5; // ticks
    private static final int CLEANUP_TIMEOUT_TICKS = 60;
    /**
     * Observer-clock sides are built horizontally, so the dispense cell below stays clear.
     */
    private static final Direction[] HORIZONTALS =
        {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    private enum State {IDLE, PREP_PLACE, PLACE, STUFF, ACTIVATE, WAIT_DISPENSE, CLEANUP}

    /**
     * Outcome of one ACTIVATE tick. An observer clock is built over two of them.
     */
    private enum ActivationStep {DONE, PENDING, FAILED}

    private State state = State.IDLE;
    private LivingEntity targetEntity;
    /**
     * Automatic target the last attempt could not build over or reach. Skipped by exactly one
     * scan (see {@link #findTarget}) so an unreachable entity cannot stall the module on itself.
     */
    private LivingEntity failedTarget;
    private int scanTickCounter = 0;
    private int ticksUntilCooldown = 0;
    private int stateTicks = 0;
    private int prePlaceSlot = -1;
    private int cleanupRestoreSlot = -1;
    private BlockPos dispenserPos;
    /**
     * Redstone block (RedstoneBlock mode) or the observer touching the dispenser (ObserverClock).
     */
    private BlockPos activatorPos;
    /**
     * Far observer of an observer clock, in front of {@link #activatorPos}. Null for a redstone block.
     */
    private BlockPos clockEndPos;
    /**
     * The direction {@link #activatorPos} was built to face ({@code clockEndPos} is {@code out} of it).
     */
    private Direction clockOut;
    /**
     * True between the two ticks an observer clock is built over (see beginObserverClock).
     */
    private boolean clockInProgress = false;
    /**
     * Ticks to wait for the server's block update for observer A before placing B.
     */
    private static final int VERIFY_A_TICKS = 5;
    /**
     * True once observer A's server-side FACING has been confirmed against {@link #clockOut}.
     */
    private boolean aVerified = false;
    private int verifyACounter = 0;
    /**
     * Rotation to put on this tick's movement packet. The observer's FACING is derived purely from
     * the player's look ({@code getNearestLookingDirection()}), and a standalone
     * {@code ServerboundMovePlayerPacket.Rot} is ignored by some servers/anti-cheats - the same trap
     * HandyPlace documents so the angle also has to ride the movement packet, which every server
     * honours. See onSendMovementPacketsPre/Post.
     */
    private float spoofYaw = 0F;
    private float spoofPitch = 0F;
    private boolean spoofPending = false;
    /**
     * Real rotation captured at {@code TickEvent.Pre}, before anything has written a spoof.
     */
    private float tickStartYaw = 0F;
    private float tickStartPitch = 0F;
    /**
     * Spoof-first state for the two-observer clock: each observer is placed one tick after its
     * rotation has been on the wire, so servers/anti-cheats that ignore a standalone
     * {@code ServerboundMovePlayerPacket.Rot} still have the spoofed view when {@code UseItemOn} lands.
     * See beginObserverClock / placeClockEnd.
     */
    /**
     * How many uncontested ticks to spoof the rotation before clicking, so the server's view is
     * updated. A tick whose movement packet another module's rotation claimed does not count (see
     * onSendMovementPacketsPre); the cap stops a module that holds the rotation every tick from
     * stalling the clock forever.
     */
    private static final int SPOOF_TICKS = 3;
    private static final int SPOOF_YIELD_CAP = SPOOF_TICKS * 3;
    private int spoofACounter = 0;
    private int spoofADelivered = 0;
    private BlockPos pendingACell = null;
    private Direction pendingAOut = null;
    private int spoofBCounter = 0;
    private int spoofBDelivered = 0;
    /**
     * 0 while spoofing for observer A, 1 for the far observer B; -1 when nothing is pending.
     */
    private int spoofPhase = -1;
    /**
     * True when the pending spoof was actually written to the fields on this tick.
     */
    private boolean spoofWrote = false;
    // Block (and face) we actually click to place the dispenser.
    private BlockPos supportPos;
    private Direction supportFace;
    private boolean toggleAfterCleanup = false;
    /**
     * Set when the teardown was started by {@link #fail}, so the retry pace stays the failure one.
     */
    private boolean failedCleanup = false;
    private boolean prevKeybindPressed = false;
    private boolean prevMiddlePressed = false;
    // Activation verification (see verifyActivation): a client-side consumesAction() only proves
    // the client predicted the placement, and a clock that never oscillates silently dispenses
    // nothing, so both are re-read from the world once per attempt.
    private boolean activationVerified = false;
    private boolean clockSawToggle = false;
    private boolean activationWarned = false;
    private int lastClockPowered = -1;   // -1 = no sample yet

    public BDSMMask() {
        super(Addon.CATEGORY, "BDSM Mask",
            "Dispenses carved pumpkins with Curse of Binding onto players/mobs.");
    }

    @Override
    public void onActivate() {
        reset();
    }

    @Override
    public void onDeactivate() {
        reset();
    }

    private void reset() {
        state = State.IDLE;
        targetEntity = null;
        failedTarget = null;
        ticksUntilCooldown = 0;
        stateTicks = 0;
        prePlaceSlot = -1;
        dispenserPos = null;
        activatorPos = null;
        clockEndPos = null;
        clockOut = null;
        clockInProgress = false;
        aVerified = false;
        verifyACounter = 0;
        spoofPending = false;
        spoofWrote = false;
        spoofPhase = -1;
        spoofACounter = 0;
        spoofADelivered = 0;
        pendingACell = null;
        pendingAOut = null;
        spoofBCounter = 0;
        spoofBDelivered = 0;
        cleanupRestoreSlot = -1;
        supportPos = null;
        supportFace = null;
        toggleAfterCleanup = false;
        failedCleanup = false;
        activationVerified = false;
        clockSawToggle = false;
        activationWarned = false;
        lastClockPowered = -1;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;
        // Real rotation for this tick, captured before any spoof is written, and a one-tick lifetime
        // for the movement-packet spoof (set by a click later in this same tick, consumed at
        // sendPosition()): a stale rotation must never ride a later tick's movement packet.
        tickStartYaw = mc.player.getYRot();
        tickStartPitch = mc.player.getXRot();
        spoofPending = false;

        if (targetMode.get() == TargetMode.Automatic) {
            if (targetEntity == null && scanTickCounter++ % SEARCH_INTERVAL == 0) findTarget();
        } else if (targetMode.get() == TargetMode.BindClick) {
            boolean now = keybind.get().isPressed();
            if (now && !prevKeybindPressed && state == State.IDLE
                && mc.crosshairPickEntity instanceof LivingEntity living && isEntityAllowed(living)) {
                targetEntity = living;
                attemptCycle();
            }
            prevKeybindPressed = now;
        } else if (targetMode.get() == TargetMode.MiddleClick) {
            boolean pressed = GLFW.glfwGetMouseButton(GLFW.glfwGetCurrentContext(), GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS;
            if (mc.crosshairPickEntity instanceof LivingEntity living && isEntityAllowed(living)) {
                if (pressed && !prevMiddlePressed && state == State.IDLE) {
                    targetEntity = living;
                    attemptCycle();
                }
                prevMiddlePressed = pressed;
            } else {
                prevMiddlePressed = false;
            }
        }

        if (state == State.IDLE) {
            if (ticksUntilCooldown > 0) {
                ticksUntilCooldown--;
                return;
            }
            if (targetMode.get() == TargetMode.Automatic) attemptCycle();
        } else {
            advanceState();
        }
    }

    private boolean isEntityAllowed(LivingEntity entity) {
        if (entity == null || entity == mc.player) return false;
        if (stopOnWearer.get() && entity.getItemBySlot(EquipmentSlot.HEAD).is(Items.CARVED_PUMPKIN)) return false;

        // Player specific
        if (entity instanceof Player p) {
            // Automatic targeting skips a player whose head is not empty, same as mobs: the
            // pumpkin cannot land on an occupied head slot. A manually picked target bypasses
            // this, since the player chose it deliberately.
            if (targetMode.get() == TargetMode.Automatic
                && onlyTargetEmptyHead.get() && !p.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) {
                return false;
            }
            boolean friend = Friends.get().isFriend(p);
            return switch (friendFilter.get()) {
                case ALL -> true;
                case ONLY_FRIENDS -> friend;
                case ONLY_NON_FRIENDS -> !friend;
            };
        }

        // Mob types
        if (!mobTypes.get().contains(entity.getType())) return false;
        if (ignoreNamedMobs.get() && entity.hasCustomName()) return false;
        if (ignoreTamed.get() && entity instanceof TamableAnimal t && t.isTame()) return false;
        if (onlyHostileMobs.get() && !(entity instanceof Monster || (entity instanceof NeutralMob n && n.getPersistentAngerTarget() != null))) {
            return false;
        }
        if (onlyTargetEmptyHead.get() && !entity.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) return false;
        return true;
    }

    private void findTarget() {
        double rangeSq = targetRange.get() * targetRange.get();
        // One-shot skip: the target the last attempt gave up on is passed over by exactly one scan,
        // so the module can move on to another candidate. A scan that finds nobody clears the skip,
        // which leaves that entity eligible again rather than banned for good.
        LivingEntity skip = failedTarget;
        failedTarget = null;
        Entity found = TargetUtils.get(e -> e instanceof LivingEntity living
            && living != skip
            && mc.player.distanceToSqr(e) <= rangeSq
            && isEntityAllowed(living), priority.get());
        targetEntity = found instanceof LivingEntity living ? living : null;
    }

    /**
     * Returns a hotbar slot holding a stack matching {@code matches}, pulling one in from the main
     * inventory when it is only there. {@code hotbar-slot} is the slot it prefers (converted from
     * the 1-9 setting to the 0-8 index), and the swap itself is MinecartPlacer's: click the player's
     * own inventory menu with a container slot id and an empty hotbar slot preferred, so the module
     * no longer hard-fails just because the item sat one slot too far to the right. Returns -1 when
     * the item is nowhere, or when the swap could not be applied (a screen is open).
     */
    private int ensureHotbarItem(Predicate<ItemStack> matches) {
        return HotbarSwap.ensureInHotbar(matches, hotbarSlot.get() - 1);
    }

    private void attemptCycle() {
        if (targetEntity == null || !targetEntity.isAlive() || !isEntityAllowed(targetEntity)) {
            targetEntity = null;
            return;
        }
        state = State.PREP_PLACE;
        stateTicks = 0;
        attemptPlacement();
    }

    private void attemptPlacement() {
        if (targetEntity == null) {
            reset();
            return;
        }
        if (!findCandidateCell()) {
            retryLater(targetEntity.blockPosition());
            return;
        }

        // Ensure Player within reach
        if (requireInReach.get() && !PlayerUtils.isWithinReach(dispenserPos)) {
            retryLater(dispenserPos);
            return;
        }

        // Check the rest of the cycle's materials before a dispenser goes into the world: a pumpkin
        // or activator discovered missing in STUFF/ACTIVATE leaves the dispenser behind. fail() tears
        // such activation blocks down now, but not placing it in the first place is cheaper for the player.
        if (InvUtils.find(this::isValidPumpkin).count() <= 0) {
            fail("Need a carved pumpkin" + (bindingCurseEnchant.get() ? " with Curse of Binding" : "")
                + " in inventory.");
            return;
        }
        if (activationMode.get() == ActivationMode.RedstoneBlock) {
            if (InvUtils.find(Items.REDSTONE_BLOCK).count() <= 0) {
                fail("Need a redstone block in inventory.");
                return;
            }
        } else if (InvUtils.find(Items.OBSERVER).count() < 2) {
            fail("Need two observers in inventory for the clock.");
            return;
        }

        int previousSlot = mc.player.getInventory().getSelectedSlot();
        int dispSlot = ensureHotbarItem(stack -> stack.is(Items.DISPENSER));
        if (dispSlot == -1) {
            fail("No dispenser in inventory.");
            return;
        }

        prePlaceSlot = previousSlot;
        mc.player.getInventory().setSelectedSlot(dispSlot);
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(dispSlot));
        InteractionResult pr = placeDispenserFacingDown();
        if (!pr.consumesAction()) {
            fail("Dispenser placement rejected client-side: " + pr);
            return;
        }

        state = State.PLACE;
        stateTicks = 0;
    }

    /**
     * Ends an attempt that could not proceed. With Baritone the target is kept and pathing is
     * resumed, since walking there is what makes the same target work on a later attempt. Without
     * it nothing can change the outcome, so an automatic target is dropped and the next scan is
     * free to pick another entity.
     */
    private void retryLater(BlockPos baritoneGoal) {
        state = State.IDLE;
        ticksUntilCooldown = delayTicks.get();
        if (useBaritone.get()) {
            // GoalBlock(BlockPos) floors, so negative fractional coordinates resolve to the block
            // the entity is actually in.
            BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess()
                .setGoalAndPath(new GoalBlock(baritoneGoal));
            return;
        }
        if (targetMode.get() == TargetMode.Automatic) {
            failedTarget = targetEntity;
            targetEntity = null;
        }
    }

    private boolean findCandidateCell() {
        if (targetEntity == null) return false;
        AABB bb = targetEntity.getBoundingBox();
        int targetX = (int) Math.floor((bb.minX + bb.maxX) / 2.0);
        int targetZ = (int) Math.floor((bb.minZ + bb.maxZ) / 2.0);
        int topY = (int) Math.floor(bb.maxY);
        for (int yOff = 1; yOff <= (int) Math.ceil(maxHeightGap.get()); yOff++) {
            BlockPos candidate = new BlockPos(targetX, topY + yOff, targetZ);
            if (!mc.level.getBlockState(candidate).canBeReplaced()) continue;
            if (requireHitboxIntersection.get()) {
                AABB dispenseCell = new AABB(candidate.below());
                if (!bb.intersects(dispenseCell) && !bb.intersects(new AABB(candidate))) continue;
            }
            if (requireInReach.get() && !PlayerUtils.isWithinReach(candidate)) continue;

            // DOWN is skipped: that neighbour is the target's head cell (air).
            Direction sd = findSupportDir(candidate, Direction.DOWN);
            if (sd != null) {
                supportPos = candidate.relative(sd);
                supportFace = sd.getOpposite();
            } else if (airPlace.get()) {
                // Clicking a replaceable block makes vanilla place at that block.
                supportPos = candidate;
                supportFace = Direction.UP;
            } else continue;

            dispenserPos = candidate;
            return true;
        }
        return false;
    }

    /**
     * Direction from cell to a solid neighbour we can click to place into cell, or null.
     */
    private Direction findSupportDir(BlockPos cell, Direction skip) {
        for (Direction d : Direction.values()) {
            if (d == skip) continue;
            BlockPos s = cell.relative(d);
            BlockState st = mc.level.getBlockState(s);
            if (st.isAir() || st.canBeReplaced()) continue;
            if (!st.isFaceSturdy(mc.level, s, d.getOpposite())) continue;
            return d;
        }
        return null;
    }

    private InteractionResult clickBlock(BlockPos clicked, Direction face) {
        Vec3 hitVec = Vec3.atCenterOf(clicked)
            .add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        return mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
            new BlockHitResult(hitVec, face, clicked, false));
    }

    /**
     * Clicks a block with the client *and* the server looking at {@code yaw}/{@code pitch}, then
     * restores both. Everything placed here keys off the player's look
     * ({@code getNearestLookingDirection()} / {@code getHorizontalDirection()}), which the server
     * only sees if the rotation is sent before the interaction hence the leading
     * {@code ServerboundMovePlayerPacket.Rot}.
     */
    private InteractionResult clickBlockLooking(BlockPos clicked, Direction face, double yaw, double pitch) {
        float oldYaw = mc.player.getYRot();
        float oldPitch = mc.player.getXRot();
        mc.player.setYRot((float) yaw);
        mc.player.setXRot((float) pitch);
        mc.player.connection.send(new ServerboundMovePlayerPacket.Rot(
            (float) yaw, (float) pitch, mc.player.onGround(), mc.player.horizontalCollision));
        InteractionResult result = clickBlock(clicked, face);
        mc.player.connection.send(new ServerboundMovePlayerPacket.Rot(
            oldYaw, oldPitch, mc.player.onGround(), mc.player.horizontalCollision));
        mc.player.setYRot(oldYaw);
        mc.player.setXRot(oldPitch);
        // The bare Rot above is not honoured by every server/anti-cheat, and an observer's FACING is
        // derived *only* from the look, so the angle must also ride this tick's movement packet -
        // which is the form every server honours (HandyPlace's mechanism). The trailing Rot cannot
        // affect the placement; the movement spoof is what makes this reliable.
        spoofYaw = (float) yaw;
        spoofPitch = (float) pitch;
        spoofPending = true;
        spoofWrote = false;
        spoofPhase = -1;   // neither observer's counter: this is a click, not a warm-up tick
        return result;
    }

    /**
     * Writes the pending rotation onto the client fields for exactly the duration of one
     * {@code sendPosition()} call, so the player's own movement packet carries it. The camera is
     * untouched because {@link #onSendMovementPacketsPost} restores immediately, and the fields are
     * restored to the rotation captured at {@code TickEvent.Pre} rather than to whatever a spoof
     * left behind.
     *
     * <p>Yields the tick when Meteor's {@code Rotations} is active: AutoLoader, BookshelfFiller,
     * ArenaM, ThrowEmptyShulkers and HandyPlace all deliver their placement rotation through that
     * queue, and it writes the fields first (its handler is registered at PreInit), so overwriting
     * it would place *their* block with our angle. Activation order cannot be relied on instead,
     * because modules subscribe on activate, so which direct field-writer runs last changes with
     * the order the player toggles them.
     */
    @EventHandler
    private void onSendMovementPacketsPre(SendMovementPacketsEvent.Pre event) {
        if (!spoofPending || mc.player == null) return;
        if (Rotations.rotating) return;   // contested tick: do not steal the movement packet
        mc.player.setYRot(spoofYaw);
        mc.player.setXRot(spoofPitch);
        spoofWrote = true;
        if (spoofPhase == 0) spoofADelivered++;
        else if (spoofPhase == 1) spoofBDelivered++;
    }

    @EventHandler
    private void onSendMovementPacketsPost(SendMovementPacketsEvent.Post event) {
        if (!spoofPending || mc.player == null) return;
        spoofPending = false;
        // Only put the fields back when this module wrote them: a yielded tick belongs to whoever
        // wrote it, and restoring over that would fight the module that actually used the packet.
        if (!spoofWrote) return;
        spoofWrote = false;
        mc.player.setYRot(tickStartYaw);
        mc.player.setXRot(tickStartPitch);
    }

    /**
     * Yaw that makes {@code Direction.fromYRot} resolve back to {@code dir}.
     */
    private static double yawOf(Direction dir) {
        return Math.toDegrees(Math.atan2(-dir.getStepX(), dir.getStepZ()));
    }

    /**
     * True while the clock should keep spoofing instead of clicking: fewer than {@link #SPOOF_TICKS}
     * uncontested ticks have landed and the yield cap has not been reached. {@code attempts} counts
     * ticks spent, {@code delivered} counts the ones whose movement packet actually carried our
     * angle (see onSendMovementPacketsPre).
     */
    private static boolean needsMoreSpoof(int attempts, int delivered) {
        return delivered < SPOOF_TICKS && attempts < SPOOF_TICKS + SPOOF_YIELD_CAP;
    }

    /**
     * Minecraft pitch (positive looks down) for {@code dir}; 0 for the horizontals.
     */
    private static double pitchOf(Direction dir) {
        return -Math.toDegrees(Math.asin(dir.getStepY()));
    }

    private InteractionResult placeDispenserFacingDown() {
        // The dispenser takes FACING = getNearestLookingDirection().getOpposite(), so looking
        // straight up (pitch -90) yields a dispenser facing down.
        return clickBlockLooking(supportPos, supportFace, 0, -90);
    }

    private void advanceState() {
        switch (state) {
            case PLACE -> {
                stateTicks++;
                if (stateTicks <= 5) return;
                if (mc.level.getBlockState(dispenserPos).getBlock() != Blocks.DISPENSER) {
                    fail("Dispenser didn't place.");
                    return;
                }
                // Right-clicking the dispenser opens its GUI for stuffDispenser().
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(dispenserPos), Direction.UP, dispenserPos, false));
                if (prePlaceSlot >= 0) {
                    mc.player.getInventory().setSelectedSlot(prePlaceSlot);
                    mc.player.connection.send(new ServerboundSetCarriedItemPacket(prePlaceSlot));
                    prePlaceSlot = -1;
                }
                state = State.STUFF;
                stateTicks = 0;
            }
            case STUFF -> {
                if (stuffDispenser()) {
                    state = State.ACTIVATE;
                    stateTicks = 0;
                } else if (state == State.STUFF) {
                    // stuffDispenser() may already have failed and reset the state machine.
                    stateTicks++;
                    if (stateTicks > 20) fail("Couldn't insert pumpkin.");
                }
            }
            case ACTIVATE -> {
                ActivationStep step;
                if (activationMode.get() == ActivationMode.RedstoneBlock) {
                    step = placeRedstoneBlockAdjacent() ? ActivationStep.DONE : ActivationStep.FAILED;
                } else if (clockInProgress) {
                    step = placeClockEnd();
                } else {
                    step = beginObserverClock();
                }
                if (step == ActivationStep.PENDING) return;   // second observer goes in on the next tick
                if (step == ActivationStep.FAILED) {
                    if (activatorPos != null) {
                        // Something did land (only the first observer of a clock): tear the
                        // partial build down rather than abandon it in the world. A specific reason
                        // was already reported when activationWarned is set, so do not repeat it.
                        if (!activationWarned) warning("Activation incomplete; disassembling.");
                        toggleAfterCleanup = false;
                        state = State.CLEANUP;
                        stateTicks = 0;
                    } else {
                        fail(activationMode.get() == ActivationMode.RedstoneBlock
                            ? "Couldn't place redstone activator."
                            : "Couldn't place observer clock.");
                    }
                    return;
                }
                state = State.WAIT_DISPENSE;
                stateTicks = 0;
            }
            case WAIT_DISPENSE -> {
                stateTicks++;
                verifyActivation();
                if (targetEquipped()) {
                    toggleAfterCleanup = autoToggle.get();
                    state = State.CLEANUP;
                    stateTicks = 0;
                    return;
                }
                if (stateTicks >= verifyDispenseTicks.get() * 2 + 20) {
                    // Blocks are already placed, so still clean them up.
                    warning("Target not equipped.");
                    state = State.CLEANUP;
                    stateTicks = 0;
                }
            }
            case CLEANUP -> {
                if (stateTicks == 0 && dismantle.get()) {
                    // Swap to a pickaxe for the teardown so blocks break in a few ticks instead of
                    // hundreds bare-handed. Restored in finishCleanup(). Pulled in from the
                    // inventory if it is not already in the hotbar; nothing to do when the build is
                    // left standing.
                    cleanupRestoreSlot = mc.player.getInventory().getSelectedSlot();
                    int pickSlot = ensureHotbarItem(stack -> stack.is(ItemTags.PICKAXES));
                    if (pickSlot != -1) {
                        mc.player.getInventory().setSelectedSlot(pickSlot);
                        mc.player.connection.send(new ServerboundSetCarriedItemPacket(pickSlot));
                    }
                }
                stateTicks++;
                boolean activatorGone = activatorPos == null || mc.level.getBlockState(activatorPos).isAir();
                boolean clockEndGone = clockEndPos == null || mc.level.getBlockState(clockEndPos).isAir();
                boolean dispenserGone = dispenserPos == null || mc.level.getBlockState(dispenserPos).isAir();
                if ((activatorGone && clockEndGone && dispenserGone) || stateTicks > CLEANUP_TIMEOUT_TICKS) {
                    if (stateTicks > CLEANUP_TIMEOUT_TICKS && !(activatorGone && clockEndGone && dispenserGone)) {
                        // Vanilla/PacketBreak give the block its normal break time. Held bare-handed
                        // a dispenser needs ~350 ticks and a redstone block ~500 (observers ~300), so
                        // without a pickaxe this window cannot finish, say so rather than leave an
                        // observer clock ticking away in the world silently.
                        warning("Disassembly timed out; part of the activator is still standing.");
                    }
                    finishCleanup();
                    return;
                }
                // If dismantle is disabled, leave the blocks standing and finish immediately.
                if (!dismantle.get()) {
                    finishCleanup();
                    return;
                }
                // Signal source first so the dispenser cannot re-trigger: the clock's far observer
                // (breaking it stops the oscillation), then the block touching the dispenser, then
                // the dispenser itself.
                if (!clockEndGone) breakBlock(clockEndPos);
                else if (!activatorGone) breakBlock(activatorPos);
                else breakBlock(dispenserPos);
            }
            default -> {
            }
        }
    }

    private void breakBlock(BlockPos pos) {
        if (pos == null || !PlayerUtils.isWithinReach(pos)) return;
        if (disassembleMode.get() == DisassembleMode.PacketBreak) {
            mc.player.connection.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, Direction.UP));
            mc.player.connection.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, Direction.UP));
        } else {
            // Vanilla: continueDestroyBlock starts the break if one isn't in progress.
            mc.gameMode.continueDestroyBlock(pos, Direction.UP);
        }
    }

    private void finishCleanup() {
        boolean toggleOff = toggleAfterCleanup;
        boolean failed = failedCleanup;
        if (cleanupRestoreSlot >= 0 && mc.player != null) {
            mc.player.getInventory().setSelectedSlot(cleanupRestoreSlot);
            mc.player.connection.send(new ServerboundSetCarriedItemPacket(cleanupRestoreSlot));
        }
        reset();
        // A failed cycle keeps its old pacing: tearing the build down must not turn a repeatable
        // failure into a place/break loop every `cooldown-ticks`.
        ticksUntilCooldown = failed ? 100 : cooldownTicks.get();
        if (toggleOff) toggle();
    }

    /**
     * Carved pumpkin that satisfies {@code binding-curse-enchant}, i.e. carries Curse of Binding.
     */
    private boolean isValidPumpkin(ItemStack stack) {
        return stack.is(Items.CARVED_PUMPKIN)
            && (!bindingCurseEnchant.get() || Utils.hasEnchantment(stack, Enchantments.BINDING_CURSE));
    }

    private boolean stuffDispenser() {
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) return false;

        // Must be our dispenser's menu, not merely *any* container screen: the player can open
        // something else in the tick between the GUI opening and STUFF running, and the slot
        // indices below would then be applied to that container - pulling the pumpkin stack onto
        // the cursor and right-clicking a slot of an unrelated inventory.
        //
        // This is also the synchronisation gate. The client only builds the menu (with a real
        // containerId and slot list) when the server's open-screen packet arrives, which is sent
        // together with the menu's contents, so a menu of the right type has synced slots and the
        // "occupied" test below reads real data rather than a not-yet-ready placeholder. Both
        // dispensers and droppers use DispenserMenu (DispenserBlockEntity.createMenu).
        var handler = screen.getMenu();
        if (!(handler instanceof DispenserMenu)) return false;

        int dstIndex = pumpkinSlot.get();
        if (dstIndex < 0 || dstIndex >= handler.slots.size()) return false;
        Slot dest = handler.getSlot(dstIndex);
        if (!dest.getItem().isEmpty()) {
            mc.player.closeContainer();
            fail("Dispenser slot " + dstIndex + " already occupied.");
            return false;
        }

        Slot pumpkinPlayerSlot = null;
        for (Slot sl : handler.slots) {
            if (!sl.container.equals(mc.player.getInventory())) continue;
            ItemStack st = sl.getItem();
            if (!isValidPumpkin(st)) continue;
            pumpkinPlayerSlot = sl;
            break;
        }
        if (pumpkinPlayerSlot == null) {
            mc.player.closeContainer();
            fail("Need a carved pumpkin" + (bindingCurseEnchant.get() ? " with Curse of Binding" : "") + " in inventory.");
            return false;
        }

        int toMove = Math.min(pumpkinsPerDisp.get(), pumpkinPlayerSlot.getItem().getCount());
        // Pick the whole stack up once (left-click PICKUP), then drop exactly one item per
        // iteration (right-click PICKUP) so pumpkins-per-dispenser is honoured, and finally
        // return any remainder to the source slot so nothing is left on the cursor.
        mc.gameMode.handleContainerInput(handler.containerId, pumpkinPlayerSlot.index, 0, ContainerInput.PICKUP, mc.player);
        for (int moved = 0; moved < toMove && !handler.getCarried().isEmpty(); moved++) {
            mc.gameMode.handleContainerInput(handler.containerId, dest.index, 1, ContainerInput.PICKUP, mc.player);
        }
        if (!handler.getCarried().isEmpty()) {
            mc.gameMode.handleContainerInput(handler.containerId, pumpkinPlayerSlot.index, 0, ContainerInput.PICKUP, mc.player);
        }

        boolean ok = handler.getSlot(pumpkinSlot.get()).getItem().is(Items.CARVED_PUMPKIN);
        mc.player.closeContainer();
        return ok;
    }

    private boolean targetEquipped() {
        if (targetEntity == null || !targetEntity.isAlive()) return false;
        // For players the target head slot reflects equipped; for mobs too.
        return targetEntity.getItemBySlot(EquipmentSlot.HEAD).is(Items.CARVED_PUMPKIN);
    }

    private boolean placeRedstoneBlockAdjacent() {
        int rbSlot = ensureHotbarItem(stack -> stack.is(Items.REDSTONE_BLOCK));
        if (rbSlot == -1) {
            warning("No redstone block in inventory.");
            return false;
        }

        for (Direction d : Direction.values()) {
            if (d == Direction.DOWN) continue; // dispense cell
            BlockPos cell = dispenserPos.relative(d);
            if (!mc.level.getBlockState(cell).canBeReplaced()) continue;

            // Click the replaceable target cell itself never the dispenser, and never another
            // solid neighbour. Clicking a *replaceable* cell makes vanilla resolve getClickedPos()
            // to the clicked cell, so the block lands where we want with nothing else involved.

            int prev = mc.player.getInventory().getSelectedSlot();
            mc.player.getInventory().setSelectedSlot(rbSlot);
            mc.player.connection.send(new ServerboundSetCarriedItemPacket(rbSlot));
            InteractionResult pr = clickBlock(cell, Direction.UP);
            mc.player.getInventory().setSelectedSlot(prev);
            mc.player.connection.send(new ServerboundSetCarriedItemPacket(prev));
            if (!pr.consumesAction()) continue;   // try another side rather than claim success

            activatorPos = cell;
            return true;
        }
        return false;
    }

    /**
     * Builds a two-observer clock against the dispenser: three collinear cells,
     * {@code dispenser - A - B}. {@code A} faces away from the dispenser so its *back*, the face
     * an observer emits from.
     */
    /**
     * Places the observer that touches the dispenser, and remembers which way the clock runs. The
     * far observer follows on the <em>next</em> tick ({@link #placeClockEnd()}), which is a
     * requirement rather than tidiness: each observer needs a different server-visible rotation, and
     * that rotation is carried by the tick's movement packet (the form every server honours -> see
     * {@link #onSendMovementPacketsPre}), so a single tick cannot supply both.
     */
    private ActivationStep beginObserverClock() {
        if (needsMoreSpoof(spoofACounter, spoofADelivered)) {
            // Spoof ticks: pick a valid cell on the first one, then keep spoofing the rotation so the
            // server's view is updated before the click lands. No click yet.
            if (pendingACell == null) {
                spoofACounter = 0;
                spoofADelivered = 0;
                for (Direction out : HORIZONTALS) {
                    BlockPos aCell = dispenserPos.relative(out);
                    BlockPos bCell = aCell.relative(out);
                    if (!mc.level.getBlockState(aCell).canBeReplaced()) continue;
                    if (!mc.level.getBlockState(bCell).canBeReplaced()) continue;
                    pendingACell = aCell;
                    pendingAOut = out;
                    break;
                }
                if (pendingACell == null) return ActivationStep.FAILED;
            }
            Direction out = pendingAOut;
            double yaw = yawOf(out);
            double pitch = pitchOf(out);
            float oldYaw = mc.player.getYRot();
            float oldPitch = mc.player.getXRot();
            mc.player.setYRot((float) yaw);
            mc.player.setXRot((float) pitch);
            mc.player.connection.send(new ServerboundMovePlayerPacket.Rot(
                (float) yaw, (float) pitch, mc.player.onGround(), mc.player.horizontalCollision));
            mc.player.setYRot(oldYaw);
            mc.player.setXRot(oldPitch);
            spoofYaw = (float) yaw;
            spoofPitch = (float) pitch;
            spoofPending = true;
            spoofWrote = false;
            spoofPhase = 0;
            spoofACounter++;
            return ActivationStep.PENDING;
        }

        // Click tick.
        int obsSlot = ensureHotbarItem(stack -> stack.is(Items.OBSERVER));
        if (obsSlot == -1) {
            warning("No observer in inventory.");
            spoofACounter = 0;
            pendingACell = null;
            pendingAOut = null;
            return ActivationStep.FAILED;
        }

        BlockPos aCell = pendingACell;
        Direction out = pendingAOut;
        if (aCell == null || !mc.level.getBlockState(aCell).canBeReplaced()) {
            spoofACounter = 0;
            pendingACell = null;
            pendingAOut = null;
            return ActivationStep.FAILED;
        }
        BlockPos bCell = aCell.relative(out);
        if (!mc.level.getBlockState(bCell).canBeReplaced()) {
            spoofACounter = 0;
            pendingACell = null;
            pendingAOut = null;
            return ActivationStep.FAILED;
        }

        int prev = mc.player.getInventory().getSelectedSlot();
        mc.player.getInventory().setSelectedSlot(obsSlot);
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(obsSlot));

        boolean placedA = clickBlockLooking(aCell, Direction.UP, yawOf(out), pitchOf(out)).consumesAction();

        mc.player.getInventory().setSelectedSlot(prev);
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(prev));
        if (!placedA) {
            spoofACounter = 0;
            pendingACell = null;
            pendingAOut = null;
            return ActivationStep.FAILED;
        }

        activatorPos = aCell;
        clockOut = out;
        clockInProgress = true;
        aVerified = false;
        verifyACounter = 0;
        spoofACounter = 0;
        spoofBCounter = 0;
        spoofBDelivered = 0;
        pendingACell = null;
        pendingAOut = null;
        return ActivationStep.PENDING;
    }

    private ActivationStep placeClockEnd() {
        Direction out = clockOut;
        if (out == null) return ActivationStep.FAILED;

        // Do not spend the second observer until the first one is confirmed correctly rotated.
        // A's FACING comes only from the rotation the server saw, so a rejected rotation has to be
        // caught here instead of after both are built. Reading it on the placement tick would
        // always pass, because the client prediction already holds the spoofed facing.
        if (!aVerified) {
            if (++verifyACounter < VERIFY_A_TICKS) return ActivationStep.PENDING;

            if (activatorPos == null || mc.level.getBlockState(activatorPos).getBlock() != Blocks.OBSERVER) {
                warning("Observer A isn't in the world (placement rejected?).");
                activationWarned = true;
                return ActivationStep.FAILED;
            }
            Direction aFacing = mc.level.getBlockState(activatorPos).getValue(DirectionalBlock.FACING);
            if (aFacing != out) {
                warning("Observer A is mis-rotated (faces " + aFacing.getName() + ", should face "
                    + out.getName() + "). Skipping the second observer.");
                activationWarned = true;
                return ActivationStep.FAILED;
            }
            aVerified = true;
        }

        BlockPos bCell = activatorPos.relative(out);

        if (needsMoreSpoof(spoofBCounter, spoofBDelivered)) {
            if (!mc.level.getBlockState(bCell).canBeReplaced()) return ActivationStep.FAILED;
            double yaw = yawOf(out.getOpposite());
            double pitch = pitchOf(out.getOpposite());
            float oldYaw = mc.player.getYRot();
            float oldPitch = mc.player.getXRot();
            mc.player.setYRot((float) yaw);
            mc.player.setXRot((float) pitch);
            mc.player.connection.send(new ServerboundMovePlayerPacket.Rot(
                (float) yaw, (float) pitch, mc.player.onGround(), mc.player.horizontalCollision));
            mc.player.setYRot(oldYaw);
            mc.player.setXRot(oldPitch);
            spoofYaw = (float) yaw;
            spoofPitch = (float) pitch;
            spoofPending = true;
            spoofWrote = false;
            spoofPhase = 1;
            spoofBCounter++;
            return ActivationStep.PENDING;
        }

        int obsSlot = ensureHotbarItem(stack -> stack.is(Items.OBSERVER));
        if (obsSlot == -1) {
            warning("No observer in inventory.");
            spoofBCounter = 0;
            return ActivationStep.FAILED;
        }

        if (!mc.level.getBlockState(bCell).canBeReplaced()) {
            spoofBCounter = 0;
            return ActivationStep.FAILED;
        }

        int prev = mc.player.getInventory().getSelectedSlot();
        mc.player.getInventory().setSelectedSlot(obsSlot);
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(obsSlot));

        boolean placedB = clickBlockLooking(bCell, Direction.UP, yawOf(out.getOpposite()),
            pitchOf(out.getOpposite())).consumesAction();

        mc.player.getInventory().setSelectedSlot(prev);
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(prev));
        if (!placedB) {
            spoofBCounter = 0;
            return ActivationStep.FAILED;
        }

        clockEndPos = bCell;
        clockInProgress = false;
        spoofBCounter = 0;
        return ActivationStep.DONE;
    }

    /**
     * Confirms what the activator clicks actually produced, and for an observer clock that the
     * clock is oscillating. Reported once per attempt and never fatal: the dispense wait and the
     * cleanup still run.
     */
    private void verifyActivation() {
        if (stateTicks < 5 || activationWarned) return;
        boolean clock = activationMode.get() == ActivationMode.ObserverClock;

        if (!activationVerified) {
            activationVerified = true;
            if (activatorPos == null
                || mc.level.getBlockState(activatorPos).getBlock() != (clock ? Blocks.OBSERVER : Blocks.REDSTONE_BLOCK)) {
                warning(clock
                    ? "Observer activator isn't in the world (placement rejected?)."
                    : "Redstone activator isn't in the world (placement rejected?).");
                activationWarned = true;
            } else if (clock && (clockEndPos == null
                || mc.level.getBlockState(clockEndPos).getBlock() != Blocks.OBSERVER)) {
                warning("Observer clock is half-built (far observer rejected?).");
                activationWarned = true;
            } else if (clock) {
                // The observer's FACING comes only from the look the server saw, so this is where a
                // rotation that never reached the server shows up: the blocks are there, but facing
                // the wrong way, which is unfixable at runtime (nothing can rotate a placed
                // observer). Report it precisely rather than waiting out the dispense.
                Direction aFacing = facingAt(activatorPos);
                Direction expected = clockOut;
                if (expected == null || aFacing != expected
                    || facingAt(clockEndPos) != expected.getOpposite()) {
                    warning("Observer clock is mis-rotated (A faces " + facingName(activatorPos)
                        + (expected == null ? "" : ", should face " + expected.getName())
                        + "; B faces " + facingName(clockEndPos)
                        + (expected == null ? "" : ", should face " + expected.getOpposite().getName())
                        + "). The placement rotation did not reach the server.");
                    activationWarned = true;
                }
            }
            return;
        }

        if (!clock) return;

        // A healthy clock flips the near observer within ~4 ticks, so 12 ticks of sampling with no
        // flip means it is not oscillating and the dispenser will never be re-fired. The FACINGs
        // are exactly what decides which side an observer emits from, so report them.
        BlockState a = mc.level.getBlockState(activatorPos);
        if (a.getBlock() != Blocks.OBSERVER) return;
        int powered = a.getValue(ObserverBlock.POWERED) ? 1 : 0;
        if (lastClockPowered < 0) lastClockPowered = powered;
        else if (powered != lastClockPowered) clockSawToggle = true;

        if (!clockSawToggle && stateTicks > 16) {
            warning("Observer clock isn't oscillating (A faces " + facingName(activatorPos)
                + ", B faces " + facingName(clockEndPos) + ").");
            activationWarned = true;
        }
    }

    /**
     * The FACING at {@code pos}, or null when there is no directional block there.
     */
    private Direction facingAt(BlockPos pos) {
        if (pos == null) return null;
        BlockState st = mc.level.getBlockState(pos);
        return st.hasProperty(DirectionalBlock.FACING) ? st.getValue(DirectionalBlock.FACING) : null;
    }

    /**
     * An observer's FACING at {@code pos}, like "north", for diagnostics; "?" when absent.
     */
    private String facingName(BlockPos pos) {
        Direction d = facingAt(pos);
        return d == null ? "?" : d.getName();
    }

    private void fail(String msg) {
        if (msg != null) warning(msg);
        if (prePlaceSlot >= 0 && mc.player != null) {
            mc.player.getInventory().setSelectedSlot(prePlaceSlot);
            mc.player.connection.send(new ServerboundSetCarriedItemPacket(prePlaceSlot));
            prePlaceSlot = -1;
        }
        // Close the dispenser GUI if that is what is open. A failed attempt (no pumpkin, wrong
        // slot, menu never synced) otherwise leaves the player staring at it, and a screen left
        // open keeps the module's clicks being predicted locally while the server ignores them.
        // Only our own dispenser menu is closed, never a container the player opened.
        if (mc.player != null
            && mc.gui.screen() instanceof AbstractContainerScreen<?> screen
            && screen.getMenu() instanceof DispenserMenu) {
            mc.player.closeContainer();
        }
        // A dispenser that actually landed is not abandoned in the world: send the cycle through the
        // normal teardown, which breaks the build (or leaves it standing when `dismantle` is off), and
        // let finishCleanup() set the cooldown. reset() here would just forget the block.
        if (mc.level != null && dispenserPos != null
            && mc.level.getBlockState(dispenserPos).getBlock() == Blocks.DISPENSER) {
            toggleAfterCleanup = false;
            failedCleanup = true;
            state = State.CLEANUP;
            stateTicks = 0;
            return;
        }
        reset();
        ticksUntilCooldown = 100;
    }
}
