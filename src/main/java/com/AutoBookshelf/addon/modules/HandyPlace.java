package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.entity.player.InteractBlockEvent;
import meteordevelopment.meteorclient.events.entity.player.ItemUseCrosshairTargetEvent;
import meteordevelopment.meteorclient.events.entity.player.SendMovementPacketsEvent;
import meteordevelopment.meteorclient.events.meteor.MouseScrollEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Handy-Place.
 * <p>
 * Overrides the placement of directional blocks to a chosen direction, forces the half of stairs,
 * slabs and trapdoors, and treats thin blocks (fences, panes, bars, chains, gates, walls, end rods,
 * lightning rods) as full cubes when picking the placement cell.
 * <p>
 * Two interception paths, cleanly split so each click is handled exactly once:
 * -> face-derived orientation (AXIS, face-FACING, stair/slab/trapdoor half, skinny cell) replaces
 * the crosshair target in ItemUseCrosshairTargetEvent, keeping vanilla prediction and packet order;
 * -> look-derived FACING (piston family, stair FACING, chest/furnace FACING) queues a delayed
 * placement: the use-item-on packet is cancelled, rotation packets with the spoofed rotation are
 * sent for delayTicks ticks, then the placement packet goes out. This guarantees the server has
 * the spoofed rotation before getStateForPlacement runs (RedstonePlacement+ technique).
 */
public class HandyPlace extends Module {
    private final SettingGroup sgRotation = settings.createGroup("Rotation");
    private final SettingGroup sgHalf = settings.createGroup("Stair/Slab Half");
    private final SettingGroup sgSkinny = settings.createGroup("Skinny Blocks");
    private final SettingGroup sgDisplay = settings.createGroup("Display");

    // Rotation part
    private final Setting<Direction> direction = sgRotation.add(new EnumSetting.Builder<Direction>()
        .name("direction")
        .description("The direction to rotate directional blocks to.")
        .defaultValue(Direction.NORTH)
        .build()
    );

    private final Setting<Keybind> cycleKey = sgRotation.add(new KeybindSetting.Builder()
        .name("cycle-key")
        .description("Cycles through the six directions when pressed.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Boolean> scrollCycle = sgRotation.add(new BoolSetting.Builder()
        .name("scroll-cycle")
        .description("Cycle direction with mouse scroll while holding a block HandyPlace can redirect.")
        .defaultValue(false)
        .build()
    );

    private final Setting<RotationMode> rotationMode = sgRotation.add(new EnumSetting.Builder<RotationMode>()
        .name("rotation-mode")
        .description("How to send the spoofed rotation.")
        .defaultValue(RotationMode.Silent)
        .build()
    );

    public enum RotationMode {
        Off,
        Rotate,
        Silent
    }

    private final Setting<Integer> delayTicks = sgRotation.add(new IntSetting.Builder()
        .name("delay-ticks")
        .description("Ticks to delay placement for rotation synchronization (1-10).")
        .defaultValue(4)
        .min(1)
        .max(10)
        .build()
    );

    /**
     * Silent mode normally snaps the client view to the spoofed rotation so vanilla's placement
     * prediction reads it, then snaps back next tick. That snap is what makes placing while
     * walking look janky, and it fights the movement packet the player is already sending.
     * With this on, Silent mode leaves the camera alone entirely and relies purely on the
     * spoofed rotation packet injected immediately before the released placement so the server
     * still sees the right rotation, so the block still faces the right way.
     * <p>
     * NOTE: the spoof has to cover the WHOLE delay window, not just the release tick. It is handed
     * to Meteor's Rotations at the click (which re-applies it on sendPosition() for
     * rotationHoldTicks ticks) and re-registered on the release tick, so the player's own movement
     * packets carry it the whole time. A rotation arriving in the same batch as the placement is
     * not enough: Family.Look6 reads the SERVER's rotation, and the server only acts on a rotation
     * it has held for at least a tick. See onTickPre / onSendMovementPacketsPre.
     */
    private final Setting<Boolean> preserveClientView = sgRotation.add(new BoolSetting.Builder()
        .name("preserve-client-view")
        .description("In Silent mode, don't snap your camera to the spoofed rotation (smoother while walking, but no client-side preview of the placed orientation).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> holdRotationPacket = sgRotation.add(new BoolSetting.Builder()
        .name("hold-rotation-packet")
        .description("Send the spoofed look as a single rotation packet at the click and cancel your own movement packets until the placement is released.")
        .defaultValue(false)
        .visible(() -> rotationMode.get() != RotationMode.Rotate)
        .build()
    );

    // Stair/Slab Half
    public enum HalfMode {
        Click,
        Top,
        Bottom
    }

    private final Setting<HalfMode> halfMode = sgHalf.add(new EnumSetting.Builder<HalfMode>()
        .name("half-mode")
        .description("Which half of the stair/slab/trapdoor to place.")
        .defaultValue(HalfMode.Click)
        .build()
    );

    private final Setting<Boolean> skinnyFull = sgSkinny.add(new BoolSetting.Builder()
        .name("skinny-full")
        .description("Treats skinny blocks (fences, panes, bars, chains, trapdoors, gates, walls, end/lightning rods) as full cubes when picking the placement cell.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SkinnyActivation> skinnyActivation = sgSkinny.add(new EnumSetting.Builder<SkinnyActivation>()
        .name("skinny-activation")
        .description("When to apply the full-cube hitbox for skinny blocks.")
        .defaultValue(SkinnyActivation.HeldBlock)
        .build()
    );

    public enum SkinnyActivation {
        HeldBlock,
        Always
    }

    private final Setting<SettingColor> skinnyWireframeColor = sgSkinny.add(new ColorSetting.Builder()
        .name("wireframe-color")
        .description("Color of the wireframe overlay for skinny blocks.")
        .defaultValue(new SettingColor(0, 255, 0, 100))
        .visible(skinnyFull::get)
        .build()
    );

    /**
     * Where the current direction is reported. This is the ONLY switch for that - an earlier
     * version added a separate `show-on-screen` boolean next to it, so `Overlay` and
     * `show-on-screen` both claimed the same job, and `Overlay` claimed a world-space label that
     * never actually rendered. One control, four honest outcomes.
     *
     * <p>The constant is still named {@code Overlay} rather than {@code OnScreen}: Meteor keys
     * enum values by name, so renaming it would silently reset every existing config.
     */
    public enum ShowMode {
        /**
         * Nothing.
         */
        Off,
        /**
         * A chat line whenever the direction changes.
         */
        Chat,
        /**
         * The on-screen readout at the bottom of the screen.
         */
        Overlay,
        /**
         * Both.
         */
        Both;

        public boolean showsChat() {
            return this == Chat || this == Both;
        }

        public boolean showsScreen() {
            return this == Overlay || this == Both;
        }
    }

    private final Setting<ShowMode> showMode = sgDisplay.add(new EnumSetting.Builder<ShowMode>()
        .name("show-mode")
        .description("Where the current direction is reported: a chat line, the on-screen readout, both, or neither.")
        .defaultValue(ShowMode.Both)
        .build()
    );

    private final Setting<SettingColor> onScreenColor = sgDisplay.add(new ColorSetting.Builder()
        .name("on-screen-color")
        .description("Colour of the on-screen direction text.")
        .defaultValue(new SettingColor(255, 255, 255))
        .visible(() -> showMode.get().showsScreen())
        .build()
    );

    private final Setting<Double> onScreenScale = sgDisplay.add(new DoubleSetting.Builder()
        .name("on-screen-scale")
        .description("Scale of the on-screen direction text.")
        .defaultValue(1.2)
        .range(0.5, 3.0)
        .visible(() -> showMode.get().showsScreen())
        .build()
    );

    private final Setting<Integer> onScreenOffset = sgDisplay.add(new IntSetting.Builder()
        .name("on-screen-offset")
        .description("How far up from the bottom of the screen the text sits.")
        .defaultValue(50)
        .range(0, 300)
        .visible(() -> showMode.get().showsScreen())
        .build()
    );

    // Delayed placement system for proper rotation synchronization (RedstonePlacement+ technique:
    // cancel the use-item-on packet, send rotation packets for delayTicks ticks, then send placement).
    private final Queue<DelayedPlacement> delayedPlacements = new ArrayDeque<>();

    // Placements whose delay has elapsed and are waiting for the player's OWN movement packet to
    // carry the spoofed rotation before they are released. A bare ServerboundMovePlayerPacket.Rot
    // is applied by a vanilla server, but some servers/anti-cheats only trust the rotation that
    // arrives on a movement packet, so the reference mod never sends a bare Rot at all - it rotates
    // the player's real look and lets the movement packet do the work.
    //
    // The rotation is handed to Meteor's Rotations in onTickPre (before sendPosition() runs) rather
    // than written onto the client fields here: Rotations applies it at sendPosition() HEAD and
    // restores it at TAIL, so the movement packet carries it while neither the camera nor the
    // player's movement ever sees it.
    private final List<DelayedPlacement> readyToSend = new ArrayList<>();
    /**
     * True while {@link #flushReadyPlacements()} is re-sending captured packets.
     */
    private boolean releasingPlacements = false;
    /**
     * Real rotation captured at {@code TickEvent.Pre}, before any module (including Meteor's
     * {@code Rotations}) has written a spoof. Restoring to this rather than to the value seen at
     * {@code SendMovementPacketsEvent.Pre} is what makes the release-tick spoof safe: {@code
     * Rotations} subscribes before this module (PreInit vs module construction), so at our Pre the
     * fields may already be spoofed, and restoring to that would leave the camera snapped and the
     * trailing Rot wrong.
     */
    private float tickStartYaw = 0F;
    private float tickStartPitch = 0F;
    private boolean movementSpoofActive = false;
    /**
     * hold-rotation-packet: true from the click until the placement is released, during which our
     * own movement packets are cancelled outright. See {@link #holdRotationPacket}.
     */
    private boolean suppressMovement = false;

    // Pending intercept data for packet interception.
    private Intercept pendingIntercept = null;
    private BlockHitResult pendingHit = null;
    /**
     * Tick the pending intercept was created on; see onSendPacket for why it matters.
     */
    private int pendingTick = Integer.MIN_VALUE;

    private String displayText = "";
    private int rotateTick = -1;
    private float savedYaw = 0F;
    private float savedPitch = 0F;
    private boolean restorePending = false;
    private boolean cycleKeyWasPressed = false;
    private long lastVerticalWarnMs = 0L;

    public HandyPlace() {
        super(Addon.CATEGORY, "Handy-Place", "Controls how directional block were to be placed and skinny-block full-cube placement.");
    }

    @Override
    public void onActivate() {
        refreshDisplay();
    }

    @Override
    public void onDeactivate() {
        delayedPlacements.clear();
        restoreMovementSpoof();
        suppressMovement = false;
        readyToSend.clear();
        pendingIntercept = null;
        pendingHit = null;
        pendingTick = Integer.MIN_VALUE;
        restorePending = false;
        cycleKeyWasPressed = false;
    }

    // ---------- Events ----------

    @EventHandler
    private void onCrosshairTarget(ItemUseCrosshairTargetEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (!(event.target instanceof BlockHitResult hit)) return;

        ItemStack stack = getPlacingStack();
        if (stack.isEmpty()) return;

        Intercept intercept = action(hit, stack);
        if (intercept == null) {
            // No intercept for this click: drop any stale pending so a previous
            // rotate click can never cancel this unrelated placement.
            pendingIntercept = null;
            pendingHit = null;
            pendingTick = Integer.MIN_VALUE;
            return;
        }

        // Both families can carry a fabricated hit: Rotate intercepts from stair/look6/horizontal pass
        // the forced-half hit or the changed skinny cell so it is NOT always the original raycast.
        // Applying it before the type check is what makes half-mode and skinny-cell correction work
        // on the rotate path, otherwise vanilla places with the original hit and both are lost.
        if (intercept.hit != null) event.target = intercept.hit;

        if (intercept.type == InterceptType.Replace) {
            // Replace path places immediately through vanilla; nothing pending.
            pendingIntercept = null;
            pendingHit = null;
            pendingTick = Integer.MIN_VALUE;
            return;
        }
        if (rotationMode.get() == RotationMode.Off) {
            pendingIntercept = null;
            pendingHit = null;
            pendingTick = Integer.MIN_VALUE;
            return;
        }

        // Store intercept data for packet interception in onSendPacket, stamped with the current
        // tick so a pending that never produces a packet cannot be matched by a later click.
        pendingIntercept = intercept;
        pendingHit = intercept.hit;
        pendingTick = mc.player.tickCount;
    }

    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        if (mc.player == null || mc.level == null) return;
        // Re-sending a captured placement must not be re-intercepted by our own filter: a new click
        // can leave pendingIntercept live across the release, and pos+tick could match, which would
        // cancel the packet we just released and re-queue it forever.
        if (releasingPlacements) return;

        // hold-rotation-packet: RedstonePlacement+ cancels every movement packet the client sends
        // while the spoof is held, so the click-time look packet is still the server's only source
        // of rotation when the placement finally arrives. Our own sends are exempt because they run
        // with releasingPlacements set, which returned above.
        if (suppressMovement && event.packet instanceof ServerboundMovePlayerPacket) {
            event.cancel();
            return;
        }

        if (pendingIntercept == null || pendingHit == null) return;
        if (!(event.packet instanceof ServerboundUseItemOnPacket useItemOn)) return;

        // Only intercept the placement this pending click produced. A stale pending from an
        // earlier click must never cancel an unrelated placement (that was the "can't place
        // anything" bug: one rotate click poisoned every later click).
        if (!useItemOn.getHitResult().getBlockPos().equals(pendingHit.getBlockPos())) return;

        // ...and a stale pending must not survive long enough to be *matched* either. pos equality
        // alone is not enough: aiming at the same block twice (the normal repetitive case) will
        // happily match a pending left over from a click that never produced a packet, and the
        // placement then goes out with that dead click's rotation. Binding the pending to the
        // tick it was created in closes the window structurally rather than hoping the pos
        // differs. One tick of slack covers the crosshair event and the packet it triggers being
        // delivered either side of a tick boundary.
        if (Math.abs(mc.player.tickCount - pendingTick) > 1) {
            pendingIntercept = null;
            pendingHit = null;
            pendingTick = Integer.MIN_VALUE;
            return;
        }

        // Cancel the vanilla packet and queue the ORIGINAL packet for delayed sending.
        // Re-sending vanilla's own packet preserves its sequence number, hand and hit;
        // rebuilding one with sequence 0 risks the server rejecting it.
        event.cancel();

        // Key on the cell the block actually lands in, NOT on the block that was clicked. A piston or
        // crafter is placed against a *face*, so two clicks on the same block's different faces
        // share a clicked-pos but fill completely different cells - keying on the clicked block
        // silently swallowed the second one. intercept.cell is what `action()` computed the
        // placement cell from, so it is the only correct identity here.
        BlockPos target = pendingIntercept.cell;

        // Coalesce instead of queueing a second packet for the same cell. startUseItem fires
        // twice per key state on this version (a consumeClick while-loop per physical press,
        // PLUS the isDown() hold-fire on later ticks), so a fast hold emits two useItemOn packets
        // from one aim. Both were queued and both released on the same tick, and the second was
        // aimed at a cell the first had just filled.
        //
        // The NEWEST packet must replace the queued one, not just its rotation. Keeping the old
        // packet means the queue eventually replays a packet whose hit result and prediction
        // sequence are delayTicks stale by then; every subsequent click keeps merging into that
        // same doomed entry, so the placement never recovers and the block appears to have
        // stopped working entirely. The newest packet is the one aimed at the current world.
        for (DelayedPlacement dp : delayedPlacements) {
            if (dp.target.equals(target)) {
                // Same validation as the release path: if the player has moved off the slot or
                // item since this entry was queued, the entry is stale and must not be updated
                // into a placement that would fire with the wrong item.
                if (!dp.stillValid(mc.player)) {
                    delayedPlacements.remove(dp);
                    break;
                }
                dp.packet = useItemOn;
                dp.yaw = pendingIntercept.yaw;
                dp.pitch = pendingIntercept.pitch;
                dp.ticksLeft = Math.min(dp.ticksLeft, delayTicks.get());
                applyRotation(pendingIntercept);
                pendingIntercept = null;
                pendingHit = null;
                pendingTick = Integer.MIN_VALUE;
                return;
            }
        }

        delayedPlacements.add(new DelayedPlacement(useItemOn, target,
            pendingIntercept.yaw, pendingIntercept.pitch, delayTicks.get(),
            mc.player.getInventory().getSelectedSlot(), mc.player.getMainHandItem().copy()));

        applyRotation(pendingIntercept);

        pendingIntercept = null;
        pendingHit = null;
        pendingTick = Integer.MIN_VALUE;
    }

    /**
     * Applies the spoofed rotation and puts it on the wire before vanilla places.
     */
    private void applyRotation(Intercept intercept) {
        if (rotateTick == mc.player.tickCount) return; // second hand in the same click
        rotateTick = mc.player.tickCount;

        boolean silent = rotationMode.get() != RotationMode.Rotate;

        // hold-rotation-packet: the literal RedstonePlacement+ shape. Put the spoofed look on the
        // wire right now as a standalone packet and then drop every movement packet of our own
        // until the placement is released, so nothing can overwrite the server's view of it. This
        // is the only difference from the default path, which instead keeps the spoof on those
        // movement packets. The camera is never touched here, so preserve-client-view plays no part.
        if (silent && holdRotationPacket.get()) {
            restorePending = false;
            suppressMovement = true;
            if (mc.getConnection() != null) {
                sendOwnPacket(() -> mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
                    (float) intercept.yaw, (float) intercept.pitch,
                    mc.player.onGround(), mc.player.horizontalCollision)));
            }
            return;
        }

        // preserve-client-view: leave the camera alone, but still ride the player's own movement
        // packet. Meteor's Rotations re-applies the rotation on sendPosition() for
        // rotationHoldTicks ticks and nowhere else, so the camera and the player's movement never
        // see it, while the movement packets the server does receive keep reporting the spoofed
        // angle for the whole delay window.
        //
        // That window is the load-bearing part, not the release itself. A rotation arriving in the
        // same batch as the use-item-on is not enough for the server to have applied it by the time
        // it runs getStateForPlacement: Family.Look6 (piston/sticky piston/dispenser/dropper/
        // barrel/observer/crafter) reads the SERVER's rotation, so it came out unrotated, while a
        // stair/trapdoor still looked right because its fabricated half hit never consults the
        // server rotation at all. Spoofing only the release tick reproduces exactly that split.
        if (silent && preserveClientView.get()) {
            // Nothing to restore: the fields are never written for this path (Rotations owns the
            // spoof and restores it itself), so cancel any restore left over from a non-preserve
            // placement rather than snapping to a stale savedYaw later.
            restorePending = false;
            Rotations.rotate((float) intercept.yaw, (float) intercept.pitch, -100);
            return;
        }

        savedYaw = mc.player.getYRot();
        savedPitch = mc.player.getXRot();

        // Meteor's Rotations pipeline plus immediate player fields so vanilla reads them.
        Rotations.rotate((float) intercept.yaw, (float) intercept.pitch, -100, () -> {
            if (silent) restorePending = true;
        });

        mc.player.setYRot((float) intercept.yaw);
        mc.player.setXRot((float) intercept.pitch);
        restorePending = silent;
    }

    /**
     * Snaps the view back after a silent placement, on the following tick so the rotated values
     * survive long enough for vanilla to read them and for the packet to reach the server.
     */
    @EventHandler
    private void onTickPost(TickEvent.Post event) {
        // Fallback: if sendPosition() never ran this tick (so no movement packet carried the spoof),
        // release the queued placements here rather than lose them. In normal play
        // onSendMovementPacketsPost has already drained readyToSend before this runs, so this is a
        // no-op.
        restoreMovementSpoof();
        flushReadyPlacements();

        if (!restorePending || mc.player == null) return;
        restorePending = false;
        mc.player.setYRot(savedYaw);
        mc.player.setXRot(savedPitch);
    }

    @EventHandler
    private void onTickPre(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        // Capture the true rotation before anything (Meteor's Rotations included) spoofs it; the
        // release-tick spoof restores to this. See the tickStartYaw field.
        tickStartYaw = mc.player.getYRot();
        tickStartPitch = mc.player.getXRot();

        // Poll the cycle key as well as relying on KeybindSetting.action(). The action callback
        // alone was unreliable in practice (it fires on the GLFW key path, which can be missed
        // when the key is held across a tick boundary or when another module consumes it first),
        // which is why the key "did nothing". Edge-detect on the down state so one press is
        // still one step.
        if (mc.screen == null) {
            boolean pressed = cycleKey.get().isPressed();
            if (pressed && !cycleKeyWasPressed) cycleDirection(1);
            cycleKeyWasPressed = pressed;
        } else {
            cycleKeyWasPressed = false;
        }

        // Process delayed placements: release them once the window has elapsed.
        boolean rotationRegistered = false;
        Iterator<DelayedPlacement> it = delayedPlacements.iterator();
        while (it.hasNext()) {
            DelayedPlacement dp = it.next();
            dp.ticksLeft--;

            if (dp.ticksLeft <= 0) {
                // A queued packet is a snapshot. If the player swapped slots or picked up a
                // different block during the delay window, replaying it would place the wrong
                // item
                if (!dp.stillValid(mc.player)) {
                    it.remove();
                    continue;
                }
                // Put the spoofed rotation on THIS tick's own movement packet. Registering it
                // with Meteor's Rotations here, before sendPosition() runs later in the tick,
                // is what makes that packet carry it: Rotations applies the angle at
                // sendPosition() HEAD and restores it at TAIL, so we never touch the client
                // fields ourselves and neither the camera nor the player's movement ever sees it.
                // Registration is per tick, so this does not depend on rotationHoldTicks. In
                // hold-rotation-packet mode there is no movement packet to carry it (see
                // onSendPacket), so registration is skipped rather than snapping the camera for a
                // frame only to have the packet cancelled.
                if (!rotationRegistered && !holdRotationPacket.get()) {
                    Rotations.rotate(dp.yaw, dp.pitch, -100);
                    rotationRegistered = true;
                }
                readyToSend.add(dp);
                it.remove();
            }
        }

        // hold-rotation-packet: if every queued placement was dropped as stale (slot/item changed)
        // and nothing is left to release, close the window here. Otherwise suppression would
        // outlive the placement that opened it and the player would stop moving server-side.
        if (suppressMovement && delayedPlacements.isEmpty() && readyToSend.isEmpty()) suppressMovement = false;
    }

    /**
     * Releases the placements whose delay elapsed this tick, immediately after the player's own
     * movement packet has gone out (that packet is what carried the spoofed rotation, applied by
     * {@code Rotations} around this very {@code sendPosition()} call).
     *
     * <p>The leading {@code Rot} is belt-and-suspenders for a server that evaluates the placement
     * without having read the movement packet first, and the trailing {@code Rot} restores the real
     * rotation so preserve-client-view does not leave the server believing we still look at the
     * placement (it sees that in server-side facing/aim decisions and in other players' nametags).
     * The trailing Rot lands after the placement, so it cannot affect it.
     */
    @EventHandler
    private void onSendMovementPacketsPost(SendMovementPacketsEvent.Post event) {
        restoreMovementSpoof();
        flushReadyPlacements();
    }

    /**
     * Spoofs the client rotation for exactly the duration of one {@code sendPosition()} call, so the
     * player's own movement packet carries it. This is the reference mod's mechanism: a movement
     * packet reporting a new rotation is honoured by every server, whereas a standalone
     * {@code ServerboundMovePlayerPacket.Rot} is ignored by some servers/anti-cheats - which is how
     * the pitch-dependent piston/crafter family (Family.Look6) ends up unrotated while a
     * stair/trapdoor still looks right through its fabricated half.
     *
     * <p>Kept alongside the {@code Rotations} registration in {@code onTickPre} on purpose: the
     * registration is priority-ordered, so another module rotating with a higher priority would push
     * our angle onto a bare {@code Rot} instead of the movement packet. Spoofing here guarantees the
     * movement packet carries it regardless.
     *
     * <p>Setting the fields here, and not at {@code TickEvent.Pre}, is deliberate: {@code
     * sendPosition()} runs after {@code handleKeybinds()}, so the crosshair/facing computation still
     * reads the player's true look and a second placement in the same tick is not classified against
     * a stale spoofed angle.
     */
    @EventHandler
    private void onSendMovementPacketsPre(SendMovementPacketsEvent.Pre event) {
        if (readyToSend.isEmpty() || mc.player == null) return;
        // hold-rotation-packet: this tick's movement packet is cancelled anyway, so writing the
        // spoof onto the fields would only snap the camera for a frame and send nothing.
        if (suppressMovement) return;
        DelayedPlacement dp = readyToSend.get(0);
        mc.player.setYRot((float) dp.yaw);
        mc.player.setXRot((float) dp.pitch);
        movementSpoofActive = true;
    }

    private void restoreMovementSpoof() {
        if (!movementSpoofActive || mc.player == null) return;
        movementSpoofActive = false;
        mc.player.setYRot(tickStartYaw);
        mc.player.setXRot(tickStartPitch);
    }

    private void flushReadyPlacements() {
        if (readyToSend.isEmpty()) return;
        if (mc.getConnection() != null && mc.player != null) {
            sendOwnPacket(() -> {
                for (DelayedPlacement dp : readyToSend) {
                    mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
                        (float) dp.yaw, (float) dp.pitch,
                        mc.player.onGround(), mc.player.horizontalCollision));
                    mc.getConnection().send(dp.packet);
                    mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
                        mc.player.getYRot(), mc.player.getXRot(),
                        mc.player.onGround(), mc.player.horizontalCollision));
                    mc.player.swing(InteractionHand.MAIN_HAND);
                }
            });
        }
        readyToSend.clear();
        // The placement is on the wire, so the hold-rotation-packet window is over: our movement
        // packets must start flowing again or the server keeps the player frozen at the pre-hold
        // position.
        suppressMovement = false;
    }

    /**
     * Runs a send that is HandyPlace's own, so {@link #onSendPacket} neither re-intercepts it nor
     * cancels it under hold-rotation-packet.
     */
    private void sendOwnPacket(Runnable send) {
        releasingPlacements = true;
        try {
            send.run();
        } finally {
            releasingPlacements = false;
        }
    }

    @EventHandler
    private void onInteract(InteractBlockEvent event) {
        // Rotate families are handled via onCrosshairTarget + onSendPacket delayed placement.
        // Replace families need nothing here either: the crosshair event already rewrote the target.
    }

    @EventHandler
    private void onMouseScroll(MouseScrollEvent event) {
        if (!scrollCycle.get()) return;
        if (mc.player == null || mc.screen != null) return;

        // Gate on actually holding something this module would act on. Cancelling unconditionally
        // meant that turning scroll-cycle on killed hotbar scrolling for every item, including
        // the ones with no directional override at all. familyOf() non-null-but-Plain is NOT enough:
        // a plain full cube (stone, dirt) is handled only for the skinny-cell relocation, and
        // cycling `direction` for it does nothing while still swallowing the scroll, which is
        // exactly the case this gate exists to keep alive.
        ItemStack stack = getPlacingStack();
        if (stack.isEmpty()) return;
        if (!(stack.getItem() instanceof BlockItem blockItem)) return;
        Family family = familyOf(blockItem.getBlock());
        if (family == null || family == Family.Plain) return;

        event.cancel();
        cycleDirection(event.value > 0 ? 1 : -1);
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.level == null) return;

        // The wireframe annotates the full-cube skinny treatment, so it only appears when this
        // placement actually goes through that path: treatment active and the placed block itself
        // skinny (the same thing BlockStateBaseMixin overrides).
        if (!skinnyFull.get() || !skinnyTreatmentActive()) return;
        if (!(mc.hitResult instanceof BlockHitResult hit)) return;

        ItemStack stack = getPlacingStack();
        if (!(stack.getItem() instanceof BlockItem blockItem) || !isSkinny(blockItem.getBlock())) return;

        // Recomputed live from the current crosshair target every frame. The old anchor came from
        // ItemUseCrosshairTargetEvent, which is only posted from startUseItem, so the box stayed
        // where the crosshair was at the last use attempt until the next one.
        CellPick pick = pickCell(hit, stack);
        if (pick == null) return;

        BlockPos cell = pick.cell();
        event.renderer.boxLines(cell.getX(), cell.getY(), cell.getZ(),
            cell.getX() + 1.0, cell.getY() + 1.0, cell.getZ() + 1.0,
            skinnyWireframeColor.get(), 0);
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (!showMode.get().showsScreen()) return;

        // Computed live, not read from the cached displayText: that field is only rebuilt on
        // activate and on cycle, so changing rotation-mode or direction in the settings GUI
        // left both the on-screen text and getInfoString() showing a stale value.
        String text = directionSummary();

        float scale = onScreenScale.get().floatValue();
        int screenWidth = event.screenWidth;
        int screenHeight = event.screenHeight;

        int x = (int) (screenWidth / 2 - (mc.font.width(text) * scale) / 2);
        int y = screenHeight - (int) onScreenOffset.get();

        event.graphics.pose().pushMatrix();
        event.graphics.pose().translate(x, y);
        event.graphics.pose().scale(scale, scale);

        event.graphics.text(mc.font, text, 0, 0, onScreenColor.get().getPacked(), true);

        event.graphics.pose().popMatrix();
    }

    private Intercept action(BlockHitResult hit, ItemStack stack) {
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) return null;
        if (!(stack.getItem() instanceof BlockItem blockItem)) return null;

        Family family = familyOf(blockItem.getBlock());
        if (family == null) return null;

        CellPick pick = pickCell(hit, stack);
        if (pick == null) return null;

        BlockPos cell = pick.cell();
        Direction dominant = pick.dominant();
        boolean cellChanged = pick.cellChanged();
        Direction chosen = direction.get();

        return switch (family) {
            case Look6 -> look6(blockItem.getBlock(), hit, cell, cellChanged, dominant, chosen);
            case Stair -> stair(hit, cell, cellChanged, chosen);
            case Trapdoor -> trapdoor(hit, cell, cellChanged, chosen);
            case Horizontal -> horizontal(hit, cell, cellChanged, dominant, chosen);
            case HorizontalDirect -> horizontalDirect(hit, cell, cellChanged, dominant, chosen);
            case Face -> face(hit, cell, cellChanged, chosen);
            case Axis -> axis(hit, cell, cellChanged, chosen);
            case Slab -> slab(hit, cell, cellChanged, chosen);
            case Plain -> plain(cell, cellChanged, dominant);
        };
    }

    /**
     * The cell a placement lands in for a hit, and the dominant side / cell-changed flag behind it.
     */
    private record CellPick(BlockPos cell, Direction dominant, boolean cellChanged) {
    }

    /**
     * The cell {@code stack} would land in for {@code hit}, or null when the click cannot place
     * there.
     *
     * <p>Pure -> no packet, no chat, no module state because the render path calls it every frame
     * to keep the skinny overlay on the *current* crosshair. {@code ItemUseCrosshairTargetEvent},
     * the only event that carries the crosshair into this module, is posted from {@code
     * startUseItem}, so an overlay anchored there froze wherever the crosshair happened to be at the
     * last use attempt instead of following it.
     */
    private CellPick pickCell(BlockHitResult hit, ItemStack stack) {
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) return null;
        if (!(stack.getItem() instanceof BlockItem blockItem)) return null;

        boolean skinny = skinnyFull.get() && isSkinny(blockItem.getBlock());

        BlockPos pos = hit.getBlockPos();
        Direction face = hit.getDirection();
        BlockState clickedState = mc.level.getBlockState(pos);
        boolean replaceClicked = clickedState.canBeReplaced();

        // dominantSide() only has meaning on the skinny path, where it picks the cell beside a
        // partial clicked block (a slab, etc.) instead of the plain hit face. Every consumer of
        // `dominant` reads it only when cellChanged, and cellChanged requires `skinny`, so a plain
        // full-cube click -- and a partial block clicked with a non-skinny item held -- must fall
        // through to the real hit face rather than a dominant side (which would fabricate a hit
        // against a face the player never clicked).
        Direction dominant = skinny && !clickedState.isCollisionShapeFullBlock(mc.level, pos)
            ? dominantSide(hit.getLocation(), pos)
            : face;

        BlockPos vanillaCell = replaceClicked ? pos : pos.relative(face);
        BlockPos cell = replaceClicked ? pos : (skinny ? pos.relative(dominant) : pos.relative(face));
        boolean cellChanged = !cell.equals(vanillaCell);

        if (!Level.isInSpawnableBounds(cell)) return null;
        if (!mc.level.getBlockState(cell).canBeReplaced()) return null;

        return new CellPick(cell, dominant, cellChanged);
    }

    private Family familyOf(Block block) {
        if (block instanceof PistonBaseBlock || block instanceof ObserverBlock || block instanceof DispenserBlock
            || block instanceof CrafterBlock || block instanceof BarrelBlock) return Family.Look6;
        if (block instanceof StairBlock) return Family.Stair;
        if (block instanceof AbstractChestBlock || block instanceof AbstractFurnaceBlock) return Family.Horizontal;
        if (block instanceof DiodeBlock || block instanceof GlazedTerracottaBlock) return Family.Horizontal;
        // verified on 26.2 getStateForPlacement: LecternBlock does
        // getHorizontalDirection().getOpposite() -> same inversion as chest/furnace.
        if (block instanceof LecternBlock) return Family.Horizontal;
        // verified: FenceGateBlock (extends HorizontalDirectionalBlock) calls
        // getHorizontalDirection() with NO getOpposite, so it is the other rotation rule.
        if (block instanceof FenceGateBlock) return Family.HorizontalDirect;
        if (block instanceof ShulkerBoxBlock || block instanceof EndRodBlock || block instanceof LightningRodBlock) return Family.Face;
        if (block instanceof TrapDoorBlock) return Family.Trapdoor;

        BlockState defaultState = block.defaultBlockState();
        if (defaultState.hasProperty(BlockStateProperties.AXIS)) return Family.Axis;
        if (block instanceof SlabBlock) return Family.Slab;

        if (defaultState.hasProperty(BlockStateProperties.FACING)
            || defaultState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
            || defaultState.hasProperty(BlockStateProperties.HALF)) return null;

        return Family.Plain;
    }

    private Intercept look6(Block block, BlockHitResult hit, BlockPos cell, boolean cellChanged, Direction dominant, Direction chosen) {
        boolean observer = block instanceof ObserverBlock;

        Direction lookNow = Direction.orderedByNearest(mc.player)[0];
        Direction natural = observer ? lookNow : lookNow.getOpposite();

        if (chosen == natural) {
            if (!cellChanged) return null;
            return Intercept.replace(fabricated(cell, dominant), cell);
        }

        Direction lookTarget = observer ? chosen : chosen.getOpposite();
        BlockHitResult resend = cellChanged ? fabricated(cell, dominant) : hit;
        return Intercept.rotate(resend, yawOf(lookTarget), pitchOf(lookTarget), cell);
    }

    private Intercept trapdoor(BlockHitResult hit, BlockPos cell, boolean cellChanged, Direction chosen) {
        // A trapdoor has two independent properties: HALF (top/bottom) and HORIZONTAL_FACING.
        // UP/DOWN are not valid facing values, so rather than rejecting them we let them drive
        // the half
        Direction facing = chosen;
        Boolean forcedTop = null;
        if (chosen == Direction.UP || chosen == Direction.DOWN) {
            forcedTop = chosen == Direction.UP;
            facing = mc.player.getDirection().getOpposite();
        }

        boolean top = forcedTop != null ? forcedTop : decidedTop(hit);

        // Vanilla TrapDoorBlock.getStateForPlacement has TWO branches:
        // 1) replacingClickedOnBlock() is true (vertical face click) -> ROTATION branch:
        //      FACING = getHorizontalDirection().getOpposite()
        //      HALF   = (clickedFace == UP) ? BOTTOM : TOP
        // 2) replacingClickedOnBlock() is false AND face is horizontal -> FACE branch:
        //      FACING = clickedFace              (rotation IGNORED)
        //      HALF   = clickLocation.y - posY > 0.5 ? TOP : BOTTOM
        //
        // On a horizontal-face click (the normal wall placement), FACING comes from the
        // face and the spoofed rotation is DISCARDED. To force vanilla into the rotation
        // branch we must fabricate a vertical-face hit so replacingClickedOnBlock() is true.
        boolean horizontalFace = hit.getDirection().getAxis().isHorizontal();
        boolean needFabrication = forcedTop != null || halfMode.get() != HalfMode.Click
            || cellChanged || horizontalFace;
        BlockHitResult fab = needFabrication ? fabricateHalfHit(cell, top) : null;

        boolean rotateNeeded = facing != mc.player.getDirection().getOpposite();

        if (rotateNeeded) {
            return Intercept.rotate(fab != null ? fab : hit, yawOf(facing.getOpposite()), mc.player.getXRot(), cell);
        }
        if (fab != null) return Intercept.replace(fab, cell);
        return null;
    }

    private Intercept stair(BlockHitResult hit, BlockPos cell, boolean cellChanged, Direction chosen) {
        // A stair carries a horizontal FACING *and* a HALF, so `direction` has two jobs here. The
        // four horizontal values drive the FACING (vanilla StairBlock reads getHorizontalDirection()
        // = player.getDirection(), like the yaw sector, verified on 26.2). UP/DOWN cannot express a
        // horizontal facing, so exactly as for a trapdoor, they drive the HALF and leave the
        // facing on the player's own look.
        //
        // Before this, a vertical `direction` was a silent dead end for stairs: rotateNeeded was
        // false (the old test required getAxis().isHorizontal()) AND the default half-mode Click
        // never fabricates, so stair() returned null and cycling to DOWN/UP changed nothing at all
        // (not even the half) while the redstone family handles UP/DOWN through pitchOf().
        Direction facing = chosen;
        Boolean forcedTop = null;
        if (chosen == Direction.UP || chosen == Direction.DOWN) {
            forcedTop = chosen == Direction.UP;
            facing = mc.player.getDirection();
        }

        boolean top = forcedTop != null ? forcedTop : decidedTop(hit);

        Direction naturalFacing = mc.player.getDirection();
        boolean rotateNeeded = facing != naturalFacing;

        boolean needFabrication = forcedTop != null || halfMode.get() != HalfMode.Click || cellChanged;
        BlockHitResult fab = needFabrication ? fabricateHalfHit(cell, top) : null;

        if (rotateNeeded) {
            return Intercept.rotate(fab != null ? fab : hit, yawOf(facing), mc.player.getXRot(), cell);
        }
        if (fab != null) return Intercept.replace(fab, cell);
        return null;
    }

    private Intercept horizontal(BlockHitResult hit, BlockPos cell, boolean cellChanged, Direction dominant, Direction chosen) {
        if (!chosen.getAxis().isHorizontal()) {
            warnVerticalDirection("this block only faces horizontally");
            if (cellChanged) return Intercept.replace(fabricated(cell, dominant), cell);
            return null;
        }

        Direction natural = mc.player.getDirection().getOpposite();
        boolean rotateNeeded = chosen != natural;

        if (rotateNeeded) {
            BlockHitResult resend = cellChanged ? fabricated(cell, dominant) : hit;
            return Intercept.rotate(resend, yawOf(chosen.getOpposite()), mc.player.getXRot(), cell);
        }
        if (cellChanged) return Intercept.replace(fabricated(cell, dominant), cell);
        return null;
    }

    /**
     * Blocks whose FACING comes straight from getHorizontalDirection() with no inversion
     * (verified for FenceGateBlock on 26.2). This is the mirror image of horizontal():
     * to end up with FACING == chosen you look AT chosen, not at its opposite, and the
     * "natural" facing is the player's direction rather than its reverse. Treating these as
     * Family.Horizontal places every gate 180 degrees wrong.
     */
    private Intercept horizontalDirect(BlockHitResult hit, BlockPos cell, boolean cellChanged,
                                       Direction dominant, Direction chosen) {
        if (!chosen.getAxis().isHorizontal()) {
            warnVerticalDirection("this block only faces horizontally");
            if (cellChanged) return Intercept.replace(fabricated(cell, dominant), cell);
            return null;
        }

        Direction natural = mc.player.getDirection();
        boolean rotateNeeded = chosen != natural;

        if (rotateNeeded) {
            BlockHitResult resend = cellChanged ? fabricated(cell, dominant) : hit;
            return Intercept.rotate(resend, yawOf(chosen), mc.player.getXRot(), cell);
        }
        if (cellChanged) return Intercept.replace(fabricated(cell, dominant), cell);
        return null;
    }

    private Intercept face(BlockHitResult hit, BlockPos cell, boolean cellChanged, Direction chosen) {
        if (chosen == hit.getDirection() && !cellChanged) return null;
        return Intercept.replace(fabricated(cell, chosen), cell);
    }

    private Intercept axis(BlockHitResult hit, BlockPos cell, boolean cellChanged, Direction chosen) {
        Direction axisFace = faceForAxis(chosen.getAxis());
        if (axisFace == hit.getDirection() && !cellChanged) return null;
        return Intercept.replace(fabricated(cell, axisFace), cell);
    }

    private Intercept slab(BlockHitResult hit, BlockPos cell, boolean cellChanged, Direction chosen) {
        // vanilla SlabBlock.getStateForPlacement: face == DOWN -> TOP, face == UP -> BOTTOM,
        // horizontal face -> clickY - posY > 0.5 ? TOP : BOTTOM.
        boolean haveTop = vanillaTop(hit);
        // half-mode is what selects the slab half (see its description and the "Stair/Slab Half"
        // group). A vertical `direction` overrides only while half-mode is Click, so `direction =
        // UP` still means "top half" for someone driving the module from the direction setting
        // alone. A HORIZONTAL direction must NOT touch the half: it used to force every slab to
        // the bottom half, so with the default direction (north) placing a slab on a block's
        // underside flipped a vanilla top slab to bottom.
        HalfMode hm = halfMode.get();
        boolean wantTop;
        if (hm == HalfMode.Top) wantTop = true;
        else if (hm == HalfMode.Bottom) wantTop = false;
        else if (chosen.getAxis() == Direction.Axis.Y) wantTop = chosen == Direction.UP;
        else wantTop = haveTop;

        // Nothing to intercept if the half the module wants is already what vanilla would place.
        if (haveTop == wantTop && !cellChanged) return null;

        // fabricateHalfHit, NOT fabricated(cell, chosen). Encoding the half as the hit *face* is
        // inverted: a fabricated face of UP makes vanilla pick BOTTOM, and a face of DOWN makes it
        // pick TOP. So passing the chosen direction straight through placed top slabs as bottom
        // and bottom slabs as top, and a horizontal direction forced the bottom half every time
        // (its hit lands at cell centre, like exactly 0.5, which fails the > 0.5 test).
        // fabricateHalfHit(cell, top) flips the face correctly and puts the click point in the
        // matching half, which is what the y-offset rule then reads back.
        return Intercept.replace(fabricateHalfHit(cell, wantTop), cell);
    }

    private Intercept plain(BlockPos cell, boolean cellChanged, Direction dominant) {
        if (!cellChanged) return null;
        return Intercept.replace(fabricated(cell, dominant), cell);
    }

    // Helpers
    private static BlockHitResult fabricated(BlockPos cell, Direction face) {
        return new BlockHitResult(Vec3.atCenterOf(cell), face, cell, false);
    }

    private static BlockHitResult fabricateHalfHit(BlockPos cell, boolean top) {
        Direction face = top ? Direction.DOWN : Direction.UP;
        double y = cell.getY() + (top ? 0.75 : 0.25);
        return new BlockHitResult(new Vec3(cell.getX() + 0.5, y, cell.getZ() + 0.5), face, cell, false);
    }

    private boolean decidedTop(BlockHitResult hit) {
        if (halfMode.get() == HalfMode.Top) return true;
        if (halfMode.get() == HalfMode.Bottom) return false;
        return vanillaTop(hit);
    }

    /**
     * The half vanilla {@code SlabBlock.getStateForPlacement} would pick from the hit alone,
     * independent of half-mode: face DOWN -> TOP, face UP -> BOTTOM, horizontal -> click-Y split.
     */
    private static boolean vanillaTop(BlockHitResult hit) {
        Direction face = hit.getDirection();
        if (face == Direction.DOWN) return true;
        if (face == Direction.UP) return false;
        return hit.getLocation().y - hit.getBlockPos().getY() > 0.5;
    }

    private static Direction faceForAxis(Direction.Axis axis) {
        if (axis == Direction.Axis.X) return Direction.EAST;
        if (axis == Direction.Axis.Y) return Direction.UP;
        return Direction.SOUTH;
    }

    private static Direction dominantSide(Vec3 loc, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        double dx = loc.x - center.x;
        double dy = loc.y - center.y;
        double dz = loc.z - center.z;
        double adx = Math.abs(dx), ady = Math.abs(dy), adz = Math.abs(dz);
        if (adx >= ady && adx >= adz) return dx > 0 ? Direction.EAST : Direction.WEST;
        if (ady >= adz) return dy > 0 ? Direction.UP : Direction.DOWN;
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static double yawOf(Direction dir) {
        return Math.toDegrees(Math.atan2(-dir.getStepX(), dir.getStepZ()));
    }

    private static double pitchOf(Direction dir) {
        return -Math.toDegrees(Math.asin(dir.getStepY()));
    }

    private void warnVerticalDirection(String why) {
        long now = System.currentTimeMillis();
        if (now - lastVerticalWarnMs < 2000) return;
        lastVerticalWarnMs = now;
        info("§eDirection " + direction.get().name() + " is not usable here: " + why + ".");
    }

    // Display / cycling
    private void cycleDirection(int delta) {
        Direction[] values = Direction.values();
        direction.set(values[Math.floorMod(direction.get().ordinal() + delta, values.length)]);
        refreshDisplay();
    }

    private String directionSummary() {
        return direction.get().name() + "  |  Rot: " + rotationMode.get().name();
    }

    private void refreshDisplay() {
        // Chat line carries its own section-sign colours; the on-screen copy is deliberately
        // plain so the on-screen-colour setting is the one that decides the tint (embedded
        // section codes would win over the colour argument and silently ignore the setting).
        displayText = "§r[§bHandyPlace§r] §fDir: §e" + direction.get().name()
            + " §7| §fRot: §e" + rotationMode.get().name();
        if (showMode.get().showsChat()) info(displayText);
    }

    private enum Family {
        Look6,
        Stair,
        Trapdoor,
        Horizontal,
        HorizontalDirect,
        Face,
        Axis,
        Slab,
        Plain
    }

    private enum InterceptType {
        Replace, Rotate
    }

    private static final class Intercept {
        final InterceptType type;
        final BlockHitResult hit;
        final double yaw, pitch;
        /**
         * Cell the block actually lands in, for the overlay. Never null once built by action().
         */
        final BlockPos cell;

        private Intercept(InterceptType type, BlockHitResult hit, double yaw, double pitch, BlockPos cell) {
            this.type = type;
            this.hit = hit;
            this.yaw = yaw;
            this.pitch = pitch;
            this.cell = cell;
        }

        static Intercept replace(BlockHitResult hit, BlockPos cell) {
            return new Intercept(InterceptType.Replace, hit, 0, 0, cell);
        }

        static Intercept rotate(BlockHitResult hit, double yaw, double pitch, BlockPos cell) {
            return new Intercept(InterceptType.Rotate, hit, yaw, pitch, cell);
        }
    }

    private static final class DelayedPlacement {
        /**
         * Mutable: a newer packet for the same cell replaces a queued one during coalescing.
         */
        ServerboundUseItemOnPacket packet;
        /**
         * Cell this placement fills; used to coalesce duplicates from the same aim.
         */
        final BlockPos target;
        double yaw;
        double pitch;
        int ticksLeft;
        /**
         * Selected hotbar slot and held item captured at queue time.
         * <p>
         * A queued packet is a snapshot of "place THIS item from THIS slot". The delay window
         * (delay-ticks, and the full eat/rotation cycle on top of it) is long enough for the
         * player to swap to a different slot or pick up a different block. Replaying the stale
         * packet then places the WRONG item, which reads as "the block didn't rotate" the
         * placement went out, just not the one the click asked for. Both are validated before
         * the packet is released.
         */
        final int slotAtQueue;
        final ItemStack itemAtQueue;

        DelayedPlacement(ServerboundUseItemOnPacket packet, BlockPos target, double yaw,
                         double pitch, int ticksLeft, int slotAtQueue, ItemStack itemAtQueue) {
            this.packet = packet;
            this.target = target;
            this.yaw = yaw;
            this.pitch = pitch;
            this.ticksLeft = ticksLeft;
            this.slotAtQueue = slotAtQueue;
            this.itemAtQueue = itemAtQueue;
        }

        /**
         * True when the player is still on the same slot holding the same item.
         */
        boolean stillValid(net.minecraft.world.entity.player.Player player) {
            return player.getInventory().getSelectedSlot() == slotAtQueue
                && ItemStack.isSameItemSameComponents(player.getMainHandItem(), itemAtQueue);
        }
    }

    public static boolean isSkinny(Block block) {
        return block instanceof IronBarsBlock
            || block instanceof FenceBlock
            || block instanceof ChainBlock
            || block instanceof TrapDoorBlock
            || block instanceof FenceGateBlock
            || block instanceof WallBlock
            || block instanceof EndRodBlock
            || block instanceof LightningRodBlock;
    }

    public static boolean skinnyTreatmentActive() {
        Modules modules = Modules.get();
        if (modules == null) return false;

        HandyPlace module = modules.get(HandyPlace.class);
        if (module == null || !module.isActive() || !module.skinnyFull.get()) return false;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null) return false;

        if (module.skinnyActivation.get() == SkinnyActivation.Always) return true;

        return isSkinnyBlockItem(mc.player.getMainHandItem())
            || isSkinnyBlockItem(mc.player.getOffhandItem());
    }

    private static boolean isSkinnyBlockItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Item item = stack.getItem();
        return item instanceof BlockItem bi && isSkinny(bi.getBlock());
    }

    private ItemStack getPlacingStack() {
        ItemStack main = mc.player.getMainHandItem();
        if (main.getItem() instanceof BlockItem) return main;

        ItemStack off = mc.player.getOffhandItem();
        if (off.getItem() instanceof BlockItem) return off;

        return ItemStack.EMPTY;
    }

    @Override
    public String getInfoString() {
        // Live for the same reason as the on-screen text: the cached displayText goes
        // stale when a setting changes in the GUI rather than via the cycle key.
        return isActive() ? "§r[§bHandyPlace§r] §fDir: §e" + direction.get().name()
                            + " §7| §fRot: §e" + rotationMode.get().name() : null;
    }
}
