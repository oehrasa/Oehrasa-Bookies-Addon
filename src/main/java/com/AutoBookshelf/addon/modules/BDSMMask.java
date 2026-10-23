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
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FacingBlock;
import net.minecraft.block.ObserverBlock;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Angerable;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.Generic3x3ContainerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
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
        .description("Ensure target Box intersects dispense cell.")
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
     * {@code PlayerMoveC2SPacket.Rot} is ignored by some servers/anti-cheats - the same trap
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
     * {@code PlayerMoveC2SPacket.Rot} still have the spoofed view when {@code UseItemOn} lands.
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
        if (mc.player == null || mc.world == null) return;
        // Real rotation for this tick, captured before any spoof is written, and a one-tick lifetime
        // for the movement-packet spoof (set by a click later in this same tick, consumed at
        // sendPosition()): a stale rotation must never ride a later tick's movement packet.
        tickStartYaw = mc.player.getYaw();
        tickStartPitch = mc.player.getPitch();
        spoofPending = false;

        if (targetMode.get() == TargetMode.Automatic) {
            if (targetEntity == null && scanTickCounter++ % SEARCH_INTERVAL == 0) findTarget();
        } else if (targetMode.get() == TargetMode.BindClick) {
            boolean now = keybind.get().isPressed();
            if (now && !prevKeybindPressed && state == State.IDLE
                && mc.targetedEntity instanceof LivingEntity living && isEntityAllowed(living)) {
                targetEntity = living;
                attemptCycle();
            }
            prevKeybindPressed = now;
        } else if (targetMode.get() == TargetMode.MiddleClick) {
            boolean pressed = GLFW.glfwGetMouseButton(GLFW.glfwGetCurrentContext(), GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS;
            if (mc.targetedEntity instanceof LivingEntity living && isEntityAllowed(living)) {
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
        if (stopOnWearer.get() && entity.getEquippedStack(EquipmentSlot.HEAD).isOf(Items.CARVED_PUMPKIN)) return false;

        // Player specific
        if (entity instanceof PlayerEntity p) {
            // Automatic targeting skips a player whose head is not empty, same as mobs: the
            // pumpkin cannot land on an occupied head slot. A manually picked target bypasses
            // this, since the player chose it deliberately.
            if (targetMode.get() == TargetMode.Automatic
                && onlyTargetEmptyHead.get() && !p.getEquippedStack(EquipmentSlot.HEAD).isEmpty()) {
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
        if (ignoreTamed.get() && entity instanceof TameableEntity t && t.isTamed()) return false;
        if (onlyHostileMobs.get() && !(entity instanceof Monster || (entity instanceof Angerable n && n.getAngryAt() != null))) {
            return false;
        }
        if (onlyTargetEmptyHead.get() && !entity.getEquippedStack(EquipmentSlot.HEAD).isEmpty()) return false;
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
            && mc.player.squaredDistanceTo(e) <= rangeSq
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
            retryLater(targetEntity.getBlockPos());
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
        int dispSlot = ensureHotbarItem(stack -> stack.isOf(Items.DISPENSER));
        if (dispSlot == -1) {
            fail("No dispenser in inventory.");
            return;
        }

        prePlaceSlot = previousSlot;
        mc.player.getInventory().setSelectedSlot(dispSlot);
        mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(dispSlot));
        ActionResult pr = placeDispenserFacingDown();
        if (!pr.isAccepted()) {
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
        Box bb = targetEntity.getBoundingBox();
        int targetX = (int) Math.floor((bb.minX + bb.maxX) / 2.0);
        int targetZ = (int) Math.floor((bb.minZ + bb.maxZ) / 2.0);
        int topY = (int) Math.floor(bb.maxY);
        for (int yOff = 1; yOff <= (int) Math.ceil(maxHeightGap.get()); yOff++) {
            BlockPos candidate = new BlockPos(targetX, topY + yOff, targetZ);
            if (!mc.world.getBlockState(candidate).isReplaceable()) continue;
            if (requireHitboxIntersection.get()) {
                Box dispenseCell = new Box(candidate.down());
                if (!bb.intersects(dispenseCell) && !bb.intersects(new Box(candidate))) continue;
            }
            if (requireInReach.get() && !PlayerUtils.isWithinReach(candidate)) continue;

            // DOWN is skipped: that neighbour is the target's head cell (air).
            Direction sd = findSupportDir(candidate, Direction.DOWN);
            if (sd != null) {
                supportPos = candidate.offset(sd);
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
            BlockPos s = cell.offset(d);
            BlockState st = mc.world.getBlockState(s);
            if (st.isAir() || st.isReplaceable()) continue;
            if (!st.isSideSolidFullSquare(mc.world, s, d.getOpposite())) continue;
            return d;
        }
        return null;
    }

    private ActionResult clickBlock(BlockPos clicked, Direction face) {
        Vec3d hitVec = Vec3d.ofCenter(clicked)
            .add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
        return mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
            new BlockHitResult(hitVec, face, clicked, false));
    }

    /**
     * Clicks a block with the client *and* the server looking at {@code yaw}/{@code pitch}, then
     * restores both. Everything placed here keys off the player's look
     * ({@code getNearestLookingDirection()} / {@code getHorizontalDirection()}), which the server
     * only sees if the rotation is sent before the interaction hence the leading
     * {@code PlayerMoveC2SPacket.Rot}.
     */
    private ActionResult clickBlockLooking(BlockPos clicked, Direction face, double yaw, double pitch) {
        float oldYaw = mc.player.getYaw();
        float oldPitch = mc.player.getPitch();
        mc.player.setYaw((float) yaw);
        mc.player.setPitch((float) pitch);
        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
            (float) yaw, (float) pitch, mc.player.isOnGround(), mc.player.horizontalCollision));
        ActionResult result = clickBlock(clicked, face);
        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
            oldYaw, oldPitch, mc.player.isOnGround(), mc.player.horizontalCollision));
        mc.player.setYaw(oldYaw);
        mc.player.setPitch(oldPitch);
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
        mc.player.setYaw(spoofYaw);
        mc.player.setPitch(spoofPitch);
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
        mc.player.setYaw(tickStartYaw);
        mc.player.setPitch(tickStartPitch);
    }

    /**
     * Yaw that makes {@code Direction.fromYRot} resolve back to {@code dir}.
     */
    private static double yawOf(Direction dir) {
        return Math.toDegrees(Math.atan2(-dir.getOffsetX(), dir.getOffsetZ()));
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
        return -Math.toDegrees(Math.asin(dir.getOffsetY()));
    }

    private ActionResult placeDispenserFacingDown() {
        // The dispenser takes FACING = getNearestLookingDirection().getOpposite(), so looking
        // straight up (pitch -90) yields a dispenser facing down.
        return clickBlockLooking(supportPos, supportFace, 0, -90);
    }

    private void advanceState() {
        switch (state) {
            case PLACE -> {
                stateTicks++;
                if (stateTicks <= 5) return;
                if (mc.world.getBlockState(dispenserPos).getBlock() != Blocks.DISPENSER) {
                    fail("Dispenser didn't place.");
                    return;
                }
                // Right-clicking the dispenser opens its GUI for stuffDispenser().
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                    new BlockHitResult(Vec3d.ofCenter(dispenserPos), Direction.UP, dispenserPos, false));
                if (prePlaceSlot >= 0) {
                    mc.player.getInventory().setSelectedSlot(prePlaceSlot);
                    mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(prePlaceSlot));
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
                    int pickSlot = ensureHotbarItem(stack -> stack.isIn(ItemTags.PICKAXES));
                    if (pickSlot != -1) {
                        mc.player.getInventory().setSelectedSlot(pickSlot);
                        mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(pickSlot));
                    }
                }
                stateTicks++;
                boolean activatorGone = activatorPos == null || mc.world.getBlockState(activatorPos).isAir();
                boolean clockEndGone = clockEndPos == null || mc.world.getBlockState(clockEndPos).isAir();
                boolean dispenserGone = dispenserPos == null || mc.world.getBlockState(dispenserPos).isAir();
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
            mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, Direction.UP));
            mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, pos, Direction.UP));
        } else {
            // Vanilla: continueDestroyBlock starts the break if one isn't in progress.
            mc.interactionManager.updateBlockBreakingProgress(pos, Direction.UP);
        }
    }

    private void finishCleanup() {
        boolean toggleOff = toggleAfterCleanup;
        boolean failed = failedCleanup;
        if (cleanupRestoreSlot >= 0 && mc.player != null) {
            mc.player.getInventory().setSelectedSlot(cleanupRestoreSlot);
            mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(cleanupRestoreSlot));
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
        return stack.isOf(Items.CARVED_PUMPKIN)
            && (!bindingCurseEnchant.get() || Utils.hasEnchantment(stack, Enchantments.BINDING_CURSE));
    }

    private boolean stuffDispenser() {
        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) return false;

        // Must be our dispenser's menu, not merely *any* container screen: the player can open
        // something else in the tick between the GUI opening and STUFF running, and the slot
        // indices below would then be applied to that container - pulling the pumpkin stack onto
        // the cursor and right-clicking a slot of an unrelated inventory.
        //
        // This is also the synchronisation gate. The client only builds the menu (with a real
        // containerId and slot list) when the server's open-screen packet arrives, which is sent
        // together with the menu's contents, so a menu of the right type has synced slots and the
        // "occupied" test below reads real data rather than a not-yet-ready placeholder. Both
        // Dispensers (and droppers) use Generic3x3ContainerScreenHandler (DispenserBlockEntity.createScreenHandler).
        var handler = screen.getScreenHandler();
        if (!(handler instanceof Generic3x3ContainerScreenHandler)) return false;

        int dstIndex = pumpkinSlot.get();
        if (dstIndex < 0 || dstIndex >= handler.slots.size()) return false;
        Slot dest = handler.getSlot(dstIndex);
        if (!dest.getStack().isEmpty()) {
            mc.player.closeHandledScreen();
            fail("Dispenser slot " + dstIndex + " already occupied.");
            return false;
        }

        Slot pumpkinPlayerSlot = null;
        for (Slot sl : handler.slots) {
            if (!sl.inventory.equals(mc.player.getInventory())) continue;
            ItemStack st = sl.getStack();
            if (!isValidPumpkin(st)) continue;
            pumpkinPlayerSlot = sl;
            break;
        }
        if (pumpkinPlayerSlot == null) {
            mc.player.closeHandledScreen();
            fail("Need a carved pumpkin" + (bindingCurseEnchant.get() ? " with Curse of Binding" : "") + " in inventory.");
            return false;
        }

        int toMove = Math.min(pumpkinsPerDisp.get(), pumpkinPlayerSlot.getStack().getCount());
        // Pick the whole stack up once (left-click PICKUP), then drop exactly one item per
        // iteration (right-click PICKUP) so pumpkins-per-dispenser is honoured, and finally
        // return any remainder to the source slot so nothing is left on the cursor.
        mc.interactionManager.clickSlot(handler.syncId, pumpkinPlayerSlot.id, 0, SlotActionType.PICKUP, mc.player);
        for (int moved = 0; moved < toMove && !handler.getCursorStack().isEmpty(); moved++) {
            mc.interactionManager.clickSlot(handler.syncId, dest.id, 1, SlotActionType.PICKUP, mc.player);
        }
        if (!handler.getCursorStack().isEmpty()) {
            mc.interactionManager.clickSlot(handler.syncId, pumpkinPlayerSlot.id, 0, SlotActionType.PICKUP, mc.player);
        }

        boolean ok = handler.getSlot(pumpkinSlot.get()).getStack().isOf(Items.CARVED_PUMPKIN);
        mc.player.closeHandledScreen();
        return ok;
    }

    private boolean targetEquipped() {
        if (targetEntity == null || !targetEntity.isAlive()) return false;
        // For players the target head slot reflects equipped; for mobs too.
        return targetEntity.getEquippedStack(EquipmentSlot.HEAD).isOf(Items.CARVED_PUMPKIN);
    }

    private boolean placeRedstoneBlockAdjacent() {
        int rbSlot = ensureHotbarItem(stack -> stack.isOf(Items.REDSTONE_BLOCK));
        if (rbSlot == -1) {
            warning("No redstone block in inventory.");
            return false;
        }

        for (Direction d : Direction.values()) {
            if (d == Direction.DOWN) continue; // dispense cell
            BlockPos cell = dispenserPos.offset(d);
            if (!mc.world.getBlockState(cell).isReplaceable()) continue;

            // Click the replaceable target cell itself never the dispenser, and never another
            // solid neighbour. Clicking a *replaceable* cell makes vanilla resolve getClickedPos()
            // to the clicked cell, so the block lands where we want with nothing else involved.

            int prev = mc.player.getInventory().getSelectedSlot();
            mc.player.getInventory().setSelectedSlot(rbSlot);
            mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(rbSlot));
            ActionResult pr = clickBlock(cell, Direction.UP);
            mc.player.getInventory().setSelectedSlot(prev);
            mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(prev));
            if (!pr.isAccepted()) continue;   // try another side rather than claim success

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
                    BlockPos aCell = dispenserPos.offset(out);
                    BlockPos bCell = aCell.offset(out);
                    if (!mc.world.getBlockState(aCell).isReplaceable()) continue;
                    if (!mc.world.getBlockState(bCell).isReplaceable()) continue;
                    pendingACell = aCell;
                    pendingAOut = out;
                    break;
                }
                if (pendingACell == null) return ActivationStep.FAILED;
            }
            Direction out = pendingAOut;
            double yaw = yawOf(out);
            double pitch = pitchOf(out);
            float oldYaw = mc.player.getYaw();
            float oldPitch = mc.player.getPitch();
            mc.player.setYaw((float) yaw);
            mc.player.setPitch((float) pitch);
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                (float) yaw, (float) pitch, mc.player.isOnGround(), mc.player.horizontalCollision));
            mc.player.setYaw(oldYaw);
            mc.player.setPitch(oldPitch);
            spoofYaw = (float) yaw;
            spoofPitch = (float) pitch;
            spoofPending = true;
            spoofWrote = false;
            spoofPhase = 0;
            spoofACounter++;
            return ActivationStep.PENDING;
        }

        // Click tick.
        int obsSlot = ensureHotbarItem(stack -> stack.isOf(Items.OBSERVER));
        if (obsSlot == -1) {
            warning("No observer in inventory.");
            spoofACounter = 0;
            pendingACell = null;
            pendingAOut = null;
            return ActivationStep.FAILED;
        }

        BlockPos aCell = pendingACell;
        Direction out = pendingAOut;
        if (aCell == null || !mc.world.getBlockState(aCell).isReplaceable()) {
            spoofACounter = 0;
            pendingACell = null;
            pendingAOut = null;
            return ActivationStep.FAILED;
        }
        BlockPos bCell = aCell.offset(out);
        if (!mc.world.getBlockState(bCell).isReplaceable()) {
            spoofACounter = 0;
            pendingACell = null;
            pendingAOut = null;
            return ActivationStep.FAILED;
        }

        int prev = mc.player.getInventory().getSelectedSlot();
        mc.player.getInventory().setSelectedSlot(obsSlot);
        mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(obsSlot));

        boolean placedA = clickBlockLooking(aCell, Direction.UP, yawOf(out), pitchOf(out)).isAccepted();

        mc.player.getInventory().setSelectedSlot(prev);
        mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(prev));
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

            if (activatorPos == null || mc.world.getBlockState(activatorPos).getBlock() != Blocks.OBSERVER) {
                warning("Observer A isn't in the world (placement rejected?).");
                activationWarned = true;
                return ActivationStep.FAILED;
            }
            Direction aFacing = mc.world.getBlockState(activatorPos).get(FacingBlock.FACING);
            if (aFacing != out) {
                warning("Observer A is mis-rotated (faces " + aFacing.asString() + ", should face "
                    + out.asString() + "). Skipping the second observer.");
                activationWarned = true;
                return ActivationStep.FAILED;
            }
            aVerified = true;
        }

        BlockPos bCell = activatorPos.offset(out);

        if (needsMoreSpoof(spoofBCounter, spoofBDelivered)) {
            if (!mc.world.getBlockState(bCell).isReplaceable()) return ActivationStep.FAILED;
            double yaw = yawOf(out.getOpposite());
            double pitch = pitchOf(out.getOpposite());
            float oldYaw = mc.player.getYaw();
            float oldPitch = mc.player.getPitch();
            mc.player.setYaw((float) yaw);
            mc.player.setPitch((float) pitch);
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                (float) yaw, (float) pitch, mc.player.isOnGround(), mc.player.horizontalCollision));
            mc.player.setYaw(oldYaw);
            mc.player.setPitch(oldPitch);
            spoofYaw = (float) yaw;
            spoofPitch = (float) pitch;
            spoofPending = true;
            spoofWrote = false;
            spoofPhase = 1;
            spoofBCounter++;
            return ActivationStep.PENDING;
        }

        int obsSlot = ensureHotbarItem(stack -> stack.isOf(Items.OBSERVER));
        if (obsSlot == -1) {
            warning("No observer in inventory.");
            spoofBCounter = 0;
            return ActivationStep.FAILED;
        }

        if (!mc.world.getBlockState(bCell).isReplaceable()) {
            spoofBCounter = 0;
            return ActivationStep.FAILED;
        }

        int prev = mc.player.getInventory().getSelectedSlot();
        mc.player.getInventory().setSelectedSlot(obsSlot);
        mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(obsSlot));

        boolean placedB = clickBlockLooking(bCell, Direction.UP, yawOf(out.getOpposite()),
            pitchOf(out.getOpposite())).isAccepted();

        mc.player.getInventory().setSelectedSlot(prev);
        mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(prev));
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
                || mc.world.getBlockState(activatorPos).getBlock() != (clock ? Blocks.OBSERVER : Blocks.REDSTONE_BLOCK)) {
                warning(clock
                    ? "Observer activator isn't in the world (placement rejected?)."
                    : "Redstone activator isn't in the world (placement rejected?).");
                activationWarned = true;
            } else if (clock && (clockEndPos == null
                || mc.world.getBlockState(clockEndPos).getBlock() != Blocks.OBSERVER)) {
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
                        + (expected == null ? "" : ", should face " + expected.asString())
                        + "; B faces " + facingName(clockEndPos)
                        + (expected == null ? "" : ", should face " + expected.getOpposite().asString())
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
        BlockState a = mc.world.getBlockState(activatorPos);
        if (a.getBlock() != Blocks.OBSERVER) return;
        int powered = a.get(ObserverBlock.POWERED) ? 1 : 0;
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
        BlockState st = mc.world.getBlockState(pos);
        return st.contains(FacingBlock.FACING) ? st.get(FacingBlock.FACING) : null;
    }

    /**
     * An observer's FACING at {@code pos}, like "north", for diagnostics; "?" when absent.
     */
    private String facingName(BlockPos pos) {
        Direction d = facingAt(pos);
        return d == null ? "?" : d.asString();
    }

    private void fail(String msg) {
        if (msg != null) warning(msg);
        if (prePlaceSlot >= 0 && mc.player != null) {
            mc.player.getInventory().setSelectedSlot(prePlaceSlot);
            mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(prePlaceSlot));
            prePlaceSlot = -1;
        }
        // Close the dispenser GUI if that is what is open. A failed attempt (no pumpkin, wrong
        // slot, menu never synced) otherwise leaves the player staring at it, and a screen left
        // open keeps the module's clicks being predicted locally while the server ignores them.
        // Only our own dispenser menu is closed, never a container the player opened.
        if (mc.player != null
            && mc.currentScreen instanceof HandledScreen<?> screen
            && screen.getScreenHandler() instanceof Generic3x3ContainerScreenHandler) {
            mc.player.closeHandledScreen();
        }
        // A dispenser that actually landed is not abandoned in the world: send the cycle through the
        // normal teardown, which breaks the build (or leaves it standing when `dismantle` is off), and
        // let finishCleanup() set the cooldown. reset() here would just forget the block.
        if (mc.world != null && dispenserPos != null
            && mc.world.getBlockState(dispenserPos).getBlock() == Blocks.DISPENSER) {
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
