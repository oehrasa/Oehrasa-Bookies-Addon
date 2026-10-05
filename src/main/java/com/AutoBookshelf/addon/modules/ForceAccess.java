package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import com.AutoBookshelf.addon.mixin.accessor.ClientPlayerInteractionManagerAccessor;
import meteordevelopment.meteorclient.events.entity.player.InteractBlockEvent;
import meteordevelopment.meteorclient.events.entity.player.InteractEntityEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.*;
import net.minecraft.block.enums.ChestType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.ShulkerEntity;
import net.minecraft.entity.passive.AbstractDonkeyEntity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.entity.vehicle.VehicleInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Hand;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.RaycastContext;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Force-Access rewrite.
 * <p>
 * Click a chest or shulker box like you normally would. If a block keeps the
 * lid from opening, the module takes over the click, removes the obstruction
 * with the same packet breaking as AutoLoader (including the grim-v3 sequence),
 * then opens the container for you. Gravity-block stacks above the lid are
 * mined top-down as a full column, since a removed block merely lets the next
 * one fall back onto the lid.
 * <p>
 * With {@code auto-hidden} on it also opens hidden containers through walls:
 * you right-click the wall in front of them and the module finds the
 * container in your aim direction and opens it: chests, shulkers, ender
 * chests, chest minecarts/boats, villagers, and tamed mounts.
 */
public class ForceAccess extends Module {
    private record HitTarget(BlockPos pos, Vec3d point, Entity entity) {
    }

    private static final int MINE_WAIT_TICKS = 4;
    private static final int POST_MINE_WAIT_TICKS = 2;
    private static final int GRAVITY_SETTLE_WAIT_TICKS = 20; // let a re-settling column land before the re-check
    private static final int MAX_NORMAL_BREAK_TICKS = 400;   // ~20s safety net for unbreakable blocks
    private static final int OPEN_CALLBACK_TIMEOUT = 60;     // give a superseded rotation a chance to recover
    private static final int OPEN_TRIES = 4;                 // re-mine cycles for fixed obstructions

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgMining = settings.createGroup("Mining");
    private final SettingGroup sgESP = settings.createGroup("ESP");

    private final Setting<Double> maxRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-range")
        .description("Maximum distance to look for an obstructed container.")
        .defaultValue(4.5)
        .min(1)
        .max(6)
        .sliderRange(1, 6)
        .build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotate towards the container before opening it.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> autoHidden = sgGeneral.add(new BoolSetting.Builder()
        .name("open-hidden")
        .description("Auto-detect and open hidden containers through walls when you right-click the wall in front of them.")
        .defaultValue(true)
        .build());

    public boolean isAutoHidden() {
        return autoHidden.get();
    }

    private final Setting<Boolean> mineObstruction = sgGeneral.add(new BoolSetting.Builder()
        .name("mine-obstruction")
        .description("Mine blocks that would keep a hidden container's lid from opening before opening it.")
        .defaultValue(true)
        .visible(autoHidden::get)
        .build());

    private final Setting<Boolean> bypassInteractiveNearContainer = sgGeneral.add(new BoolSetting.Builder()
        .name("bypass-interactive-near-container")
        .description("Clicking an interactive block (button, lever, door, ...) next to a container opens the container instead.")
        .defaultValue(false)
        .visible(autoHidden::get)
        .build());

    public boolean isBypassInteractiveNearContainer() {
        return bypassInteractiveNearContainer.get();
    }

    private final Setting<Boolean> requireEmptyHand = sgGeneral.add(new BoolSetting.Builder()
        .name("require-empty-hand")
        .description("Only trigger auto-hidden when the main hand is empty.")
        .defaultValue(false)
        .visible(autoHidden::get)
        .build());

    private final Setting<Boolean> disabledWhileSneaking = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-while-sneaking")
        .description("Do not open containers or hidden containers while sneaking.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> autoTool = sgMining.add(new BoolSetting.Builder()
        .name("auto-tool")
        .description("Swap to the best tool for the obstruction before breaking it and swap back afterwards.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> packetBreak = sgMining.add(new BoolSetting.Builder()
        .name("packet-break")
        .description("Use instant packet based breaking.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> packetBreakGrim = sgMining.add(new BoolSetting.Builder()
        .name("grim-v3-packet")
        .description("Use the Grim-v3 packet breaking sequence.")
        .defaultValue(true)
        .visible(packetBreak::get)
        .build());

    private final Setting<Boolean> instantRemine = sgMining.add(new BoolSetting.Builder()
        .name("instant-remine")
        .description("Send the break packets through sendSequencedPacket so the server processes them order-synced.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> maxAttempts = sgMining.add(new IntSetting.Builder()
        .name("max-attempts")
        .description("How many packet bursts to send per obstruction block before skipping it.")
        .defaultValue(8)
        .min(1)
        .max(40)
        .sliderRange(1, 40)
        .build());

    private final Setting<Boolean> doubleBreak = sgMining.add(new BoolSetting.Builder()
        .name("double-break")
        .description("Break two queued obstructions at once: the gravity layer above the lid together with the lid, or both halves of a double chest.")
        .defaultValue(true)
        .visible(packetBreak::get)
        .build());

    private final Setting<Boolean> espEnabled = sgESP.add(new BoolSetting.Builder()
        .name("ESP-enabled")
        .description("Highlight the container and its obstruction blocks.")
        .defaultValue(true)
        .build());

    private final Setting<ShapeMode> shapeMode = sgESP.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("ESP shape mode.")
        .defaultValue(ShapeMode.Lines)
        .visible(espEnabled::get)
        .build());

    private final Setting<SettingColor> sideColor = sgESP.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Side color of ESP boxes.")
        .defaultValue(new SettingColor(255, 200, 0, 40))
        .visible(espEnabled::get)
        .build());

    private final Setting<SettingColor> lineColor = sgESP.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Line color of ESP boxes.")
        .defaultValue(new SettingColor(255, 200, 0, 200))
        .visible(espEnabled::get)
        .build());

    private enum Phase {IDLE, MINE, OPEN}

    private final Deque<BlockPos> scratch = new ArrayDeque<>();
    private Phase phase = Phase.IDLE;
    private BlockPos container;
    private Vec3d openPoint;
    private Entity openEntity;
    private final Deque<BlockPos> queue = new ArrayDeque<>();
    private final Deque<BlockPos> espBoxes = new ArrayDeque<>(); // survives settle pauses so ESP never blinks out
    private Target mining;
    private Target mining2;
    private boolean waiting;
    private int waitTicks;
    private boolean burstSent;
    private int postMineWait;
    private int openTries;
    private boolean pendingOpen;          // rotate deferred sendOpen; don't re-issue doOpen
    private int openTimeoutTicks;
    private int session;                 // bumped on reset; stale rotate callbacks bail
    private boolean toolSwapped;
    private int preToolSlot;
    private int toolSlot = -1;

    private boolean clearingGravity;     // pass draining a gravity column -> longer settle wait
    private int minedThisPhase;          // blocks actually broken in the current mine phase (progress check)
    private int gravityPasses;           // settle-loop count; safety cap for an unbreakable column
    private boolean allowMine;           // whether this session may mine obstructions

    // Auto-hidden polling state
    private boolean hiddenLock;   // debounces one scan per use-key press
    private boolean hiddenBlock;  // true while we're driving a hidden open/mine flow — suppress vanilla's own packet
    private boolean hiddenOwn;    // true only while WE send the real open packet, so it isn't self-cancelled

    /**
     * An actively mined obstruction (primary/secondary, see double-break).
     */
    private static final class Target {
        private BlockPos pos;
        private int attempts;
    }

    public ForceAccess() {
        super(Addon.CATEGORY, "Force-Access", "Mines the block that keeps a container from opening, then opens it, also works through walls");
    }

    @Override
    public void onActivate() {
        reset();
    }

    @Override
    public void onDeactivate() {
        reset();
    }

    // Leaving the world must not let onTick keep working positions/queues from the previous
    // world (or the tool slot we put back there); drop the whole session.
    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        reset();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        if (mc.currentScreen != null) return;

        if (phase == Phase.MINE) {
            tickMine();
            return;
        }
        if (phase == Phase.OPEN) {
            open();
            return;
        }

        tickAutoHidden();
    }

    @EventHandler
    private void onInteract(InteractBlockEvent event) {
        if (mc.player == null || mc.world == null) return;
        if (event.result == null) return;

        if (phase != Phase.IDLE) {
            event.cancel();
            return;
        }

        // Sneaking is the player's explicit escape hatch: never hijack the click
        // into opening a (hidden) container while crouching. Vanilla skips the
        // block's onUse and uses the held item when sneaking with an item in
        // hand, mirror that so we don't cancel a placement.
        if (mc.player.isSneaking() && disabledWhileSneaking.get()) return;
        if (mc.player.shouldCancelInteraction()
            && (!mc.player.getMainHandStack().isEmpty() || !mc.player.getOffHandStack().isEmpty())) return;

        BlockPos pos = event.result.getBlockPos();

        if (container(pos) && obstructed(pos)) {
            event.cancel();
            // mine-obstruction gates mining, and a cancelled event does not stop
            // this handler from running, so the gate has to live here too -
            // otherwise an actual click starts mining a stuck lid even with the
            // setting off (the key-poll path above checks it, this path did not).
            if (mineObstruction.get()) start(pos);
        }
        // Hidden-container detection no longer lives here
    }

    @EventHandler
    private void onInteractEntity(InteractEntityEvent event) {
        if (mc.player == null || mc.world == null) return;
        if (event.entity == null) return;

        if (phase != Phase.IDLE) {
            event.cancel();
        }
    }

    private void tickAutoHidden() {
        if (!autoHidden.get() || !mc.options.useKey.isPressed()) {
            hiddenLock = false;
            return;
        }
        if (hiddenLock) return;
        hiddenLock = true;

        if (mc.player.isSneaking() && disabledWhileSneaking.get()) return;
        // Vanilla skips the block's onUse and uses the held item here.
        if (mc.player.shouldCancelInteraction()
            && (!mc.player.getMainHandStack().isEmpty() || !mc.player.getOffHandStack().isEmpty())) return;

        // Placing a block is never an attempt to reach a hidden container, so let it
        // through whatever require-empty-hand is set to
        if (mc.player.getMainHandStack().getItem() instanceof BlockItem) return;

        // Only take over idle main-hand right-clicks when the setting asks for
        // it
        if (requireEmptyHand.get() && !mc.player.getMainHandStack().isEmpty()) return;

        if (mc.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = hit.getBlockPos();
            // A directly-targeted container is onInteract's job, not ours.
            if (container(pos)) return;
            // Let blocks that answer right-clicks keep their own interaction
            // (buttons, doors, signs, crafting stations, ...) instead of the
            // hidden container behind swallowing the click. Optionally bypass
            // that when the clicked block sits right next to a container.
            if (hasOwnInteraction(pos) && !(bypassInteractiveNearContainer.get() && nearContainer(pos))) return;
        } else if (mc.crosshairTarget != null && mc.crosshairTarget.getType() != HitResult.Type.MISS) {
            // Targeting something else (an entity) leave it to vanilla.
            return;
        }

        HitTarget hidden = hiddenTarget();
        if (hidden == null) return;

        hiddenBlock = true;
        openHidden(hidden);
    }

    @EventHandler
    private void onPacket(PacketEvent.Send event) {
        if (hiddenBlock && !hiddenOwn && event.packet instanceof PlayerInteractBlockC2SPacket) {
            event.cancel();
        }
    }

    private void openHidden(HitTarget hidden) {
        if (hidden.entity != null) {
            beginOpenEntity(hidden.entity);
        } else if (mineObstruction.get() && obstructed(hidden.pos)) {
            start(hidden.pos);
        } else {
            beginOpen(hidden.pos, hidden.point);
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!espEnabled.get() || mc.player == null || mc.world == null) return;
        Vec3d eye = mc.player.getEyePos();
        double maxSq = 16 * 16;

        if (container != null && eye.squaredDistanceTo(container.toCenterPos()) < maxSq) {
            event.renderer.box(container, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }

        for (BlockPos pos : espBoxes) {
            if (eye.squaredDistanceTo(pos.toCenterPos()) < maxSq) {
                event.renderer.box(pos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            }
        }

        if (openEntity != null && openEntity.isAlive()
            && eye.squaredDistanceTo(openEntity.getBoundingBox().getCenter()) < maxSq) {
            event.renderer.box(openEntity.getBoundingBox(), sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }

    // targeting
    private void start(BlockPos pos) {
        container = pos.toImmutable();
        openPoint = null;
        openEntity = null;
        queue(pos);
        minedThisPhase = 0;
        allowMine = true;
        phase = Phase.MINE;
    }

    private void beginOpen(BlockPos pos, Vec3d point) {
        container = pos.toImmutable();
        openPoint = point;
        openEntity = null;
        allowMine = mineObstruction.get();
        phase = Phase.OPEN;
    }

    private void beginOpenEntity(Entity entity) {
        container = null;
        openPoint = null;
        openEntity = entity;
        allowMine = false;
        phase = Phase.OPEN;
    }

    private HitTarget hiddenTarget() {
        Vec3d eye = mc.player.getEyePos();
        Vec3d end = eye.add(mc.player.getRotationVector().normalize().multiply(maxRange.get()));

        HitTarget best = null;
        double bestSq = Double.MAX_VALUE;

        double blockRange = Math.min(maxRange.get(), mc.player.getBlockInteractionRange());
        if (blockRange > 0) {
            int r = (int) Math.ceil(blockRange);
            double maxSq = blockRange * blockRange;

            for (BlockPos p : BlockPos.iterateOutwards(BlockPos.ofFloored(eye), r, r, r)) {
                if (!container(p)) continue;
                double distSq = eye.squaredDistanceTo(p.toCenterPos());
                if (distSq > maxSq || distSq >= bestSq) continue;

                Vec3d point = new Box(p).expand(1.0E-3).raycast(eye, end).orElse(null);
                if (point == null) continue;
                if (visible(p, eye, point)) continue;

                bestSq = distSq;
                best = new HitTarget(p.toImmutable(), point, null);
            }
        }

        double entityRange = Math.min(maxRange.get(), mc.player.getEntityInteractionRange());
        if (entityRange > 0) {
            double maxSq = entityRange * entityRange;

            for (Entity e : mc.world.getEntities()) {
                if (!container(e)) continue;
                double distSq = eye.squaredDistanceTo(e.getBoundingBox().getCenter());
                if (distSq > maxSq || distSq >= bestSq) continue;

                Optional<Vec3d> result = e.getBoundingBox().expand(1.0E-3).raycast(eye, end);
                if (result.isEmpty()) continue;

                Vec3d point = result.get();
                if (visibleEntity(eye, point)) continue;

                bestSq = distSq;
                best = new HitTarget(null, point, e);
            }
        }
        return best;
    }

    private boolean visible(BlockPos pos, Vec3d eye, Vec3d point) {
        Vec3d dir = point.subtract(eye);
        if (dir.lengthSquared() > 0) {
            point = point.add(dir.normalize().multiply(1.0E-3));
        }
        BlockHitResult hit = mc.world.raycast(new RaycastContext(eye, point, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
    }

    /**
     * An entity container is "visible" when no block stands between the eye and
     * the hit point (a clear raycast). Visible ones are left to vanilla
     * interaction; only walled-off ones are auto-opened.
     */
    private boolean visibleEntity(Vec3d eye, Vec3d point) {
        Vec3d dir = point.subtract(eye);
        if (dir.lengthSquared() > 0) {
            point = point.subtract(dir.normalize().multiply(1.0E-3));
        }
        BlockHitResult hit = mc.world.raycast(new RaycastContext(eye, point, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
        return hit.getType() == HitResult.Type.MISS;
    }

    /**
     * Container entities: chest minecarts/boats, merchants (villagers and
     * wandering traders), and tamed chested donkeys/mules/llamas.
     */
    private boolean container(Entity entity) {
        if (!entity.isAlive() || entity.isSpectator()) return false;

        if (entity instanceof VehicleInventory) return true;
        if (entity instanceof MerchantEntity) return true;

        return entity instanceof AbstractDonkeyEntity donkey
            && donkey.isTame() && donkey.hasChest();
    }

    private boolean nearContainer(BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (container(pos.offset(d))) return true;
        }
        return false;
    }

    private boolean container(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        return state.getBlock() instanceof ChestBlock
            || state.getBlock() instanceof ShulkerBoxBlock
            || state.getBlock() == Blocks.ENDER_CHEST
            || state.createScreenHandlerFactory(mc.world, pos) != null;
    }

    /**
     * Blocks that answer a right-click of their own (a screen factory or a
     * direct on-use behaviour) must keep the click; auto-hidden only takes over
     * clicks on otherwise dead wall blocks.
     */
    private boolean hasOwnInteraction(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        if (state.createScreenHandlerFactory(mc.world, pos) != null) return true;
        Block block = state.getBlock();
        return block instanceof ButtonBlock
            || block instanceof LeverBlock
            || block instanceof DoorBlock
            || block instanceof TrapdoorBlock
            || block instanceof FenceGateBlock
            || block instanceof AbstractSignBlock
            || block instanceof HangingSignBlock
            || block instanceof WallHangingSignBlock
            || block instanceof NoteBlock
            || block instanceof JukeboxBlock
            || block instanceof BellBlock
            || block instanceof CauldronBlock
            || block instanceof DecoratedPotBlock
            || block instanceof BeehiveBlock
            || block instanceof DragonEggBlock
            || block instanceof RespawnAnchorBlock
            || block instanceof BedBlock
            || block instanceof RepeaterBlock
            || block instanceof ComparatorBlock
            || block instanceof CakeBlock
            || block instanceof CandleCakeBlock
            || block instanceof CandleBlock
            || block instanceof FlowerPotBlock
            || block instanceof LecternBlock
            || block instanceof ChiseledBookshelfBlock
            || block instanceof ComposterBlock
            || block instanceof DaylightDetectorBlock
            || block instanceof RedstoneWireBlock;
    }

    private boolean obstructed(BlockPos pos) {
        scratch.clear();
        collectObstructions(pos, scratch);
        return !scratch.isEmpty();
    }

    /**
     * The lowest gravity block among the current obstructions (collectObstructions
     * leaves them in scratch), or null when none of the obstructions is falling.
     */
    private BlockPos gravityObstruction() {
        BlockPos lowest = null;
        for (BlockPos p : scratch) {
            if (mc.world.getBlockState(p).getBlock() instanceof FallingBlock
                && (lowest == null || p.getY() < lowest.getY())) {
                lowest = p;
            }
        }
        return lowest;
    }

    private void queue(BlockPos pos) {
        collectObstructions(pos, queue);
        espBoxes.clear();
        espBoxes.addAll(queue);
    }

    private void collectObstructions(BlockPos pos, Deque<BlockPos> out) {
        BlockState state = mc.world.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock) {
            collectChestObstructions(pos, state, out);
        } else if (state.getBlock() instanceof ShulkerBoxBlock) {
            collectShulkerObstructions(pos, state, out);
        } else if (state.getBlock() == Blocks.ENDER_CHEST) {
            if (ChestBlock.isChestBlocked(mc.world, pos)) addSolidAbove(pos, out);
        }
    }

    /**
     * A chest lid needs the block above to be open space; a double chest needs
     * both halves clear.
     */
    private void collectChestObstructions(BlockPos pos, BlockState state, Deque<BlockPos> out) {
        if (ChestBlock.isChestBlocked(mc.world, pos)) {
            addSolidAbove(pos, out);
        }
        if (state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
            BlockPos other = pos.offset(ChestBlock.getFacing(state));
            if (mc.world.getBlockState(other).isOf(state.getBlock()) && ChestBlock.isChestBlocked(mc.world, other)) {
                addSolidAbove(other, out);
            }
        }
    }

    /**
     * Vanilla ShulkerBoxBlock.canOpen uses the half-open lid box, which sits
     * exactly in the half-block the facing direction points to (fully outside
     * the shulker's own block). Mirror it: empty lid space opens, otherwise the
     * colliding block in the facing direction is the obstruction.
     */
    private void collectShulkerObstructions(BlockPos pos, BlockState state, Deque<BlockPos> out) {
        Box lid = ShulkerEntity.calculateBoundingBox(1.0f, state.get(ShulkerBoxBlock.FACING), 0.0f, 0.5f, pos.toBottomCenterPos()).contract(1.0E-6);
        VoxelShape lidShape = VoxelShapes.cuboid(lid);

        if (mc.world.isSpaceEmpty(lid)) return;

        BlockPos.stream(lid).forEach(p -> {
            if (p.equals(pos)) return;
            BlockState bs = mc.world.getBlockState(p);
            if (bs.isAir()) return;
            VoxelShape shape = bs.getCollisionShape(mc.world, p);
            if (shape.isEmpty()) return;
            // Collision shapes are local to the block; lidShape is in world coords.
            if (VoxelShapes.matchesAnywhere(shape.offset(p.getX(), p.getY(), p.getZ()), lidShape, BooleanBiFunction.AND)) {
                // A gravity block in the lid's path keeps refilling from above: clear a
                // couple of layers off this cell and let the settle loop consume the
                // rest of the column once it drops back onto the lid.
                if (bs.getBlock() instanceof FallingBlock) {
                    addFallingLid(p.toImmutable(), out);
                } else if (!out.contains(p)) {
                    out.addLast(p.toImmutable());
                }
            }
        });
    }

    /**
     * A gravity block (sand/gravel/concrete powder) sitting on a container does
     * not clear when removed, as the column above simply falls back onto the lid
     * and re-obstructs it. Clear a couple of layers per pass instead of the whole
     * column: the layer above the lid is mined before the lid itself (so the
     * rest of the column keeps resting on it and the queued positions stay
     * valid), then open() waits for the column to settle and re-queues it,
     * dropping the column ~2 layers each pass until the lid is genuinely clear.
     * The loop only ever acts on server-confirmed resting blocks.
     */
    private void addSolidAbove(BlockPos pos, Deque<BlockPos> out) {
        BlockPos above = pos.up();
        BlockState state = mc.world.getBlockState(above);
        if (!state.isFullCube(mc.world, above)) return;
        if (state.getBlock() instanceof FallingBlock) {
            addFallingLid(above, out);
        } else if (!out.contains(above)) {
            out.addLast(above.toImmutable());
        }
    }

    private void addFallingLid(BlockPos base, Deque<BlockPos> out) {
        clearingGravity = true;
        BlockPos oneUp = base.up();
        if (mc.world.getBlockState(oneUp).getBlock() instanceof FallingBlock && !out.contains(oneUp)) {
            out.addLast(oneUp.toImmutable());
        }
        if (!out.contains(base)) out.addLast(base.toImmutable());
    }

    private void tickMine() {
        if (mining == null && !queue.isEmpty()) {
            mining = pop();
            waiting = false;
            waitTicks = 0;
            if (autoTool.get()) swapTool();
        }
        if (mining != null && mining2 == null && doubleBreak.get() && packetBreak.get() && !queue.isEmpty()
            && (!autoTool.get() || findToolSlot(queue.peekFirst()) == findToolSlot(mining.pos))) {
            mining2 = pop();
        }
        if (mining == null && mining2 == null) {
            phase = Phase.OPEN;
            postMineWait = clearingGravity ? GRAVITY_SETTLE_WAIT_TICKS : POST_MINE_WAIT_TICKS;
            clearingGravity = false;
            return;
        }

        if (waiting) {
            if (++waitTicks >= MINE_WAIT_TICKS) {
                waiting = false;
                waitTicks = 0;
            }
        }

        mineTarget(mining);
        mineTarget(mining2);

        // A finished target leaves its pos null; drop it and promote the
        // secondary so the slot stays usable and onRender never dereferences a
        // null pos.
        if (mining != null && mining.pos == null) mining = null;
        if (mining2 != null && mining2.pos == null) mining2 = null;
        if (mining == null && mining2 != null) {
            mining = mining2;
            mining2 = null;
            if (autoTool.get()) swapTool();
        }

        if (burstSent) {
            waiting = true;
            waitTicks = 0;
            burstSent = false;
        }
    }

    private Target pop() {
        Target target = new Target();
        target.pos = queue.pollFirst().toImmutable();
        target.attempts = 0;
        return target;
    }

    /**
     * Advances one mining target: a block that has already broken (or walked off
     * to gravity) counts as done, otherwise the active break tactic is applied.
     * In packet mode the burst for both active targets runs on the shared
     * waiting cadence.
     */
    private void mineTarget(Target target) {
        if (target == null) return;

        if (mc.world.getBlockState(target.pos).isAir()) {
            minedThisPhase++;
            target.pos = null;
            return;
        }

        if (packetBreak.get()) {
            if (waiting) return;

            if (++target.attempts > maxAttempts.get()) {
                target.pos = null;
                return;
            }

            Vec3d center = target.pos.toCenterPos();
            if (rotate.get() && target == mining) {
                Rotations.rotate(Rotations.getYaw(center), Rotations.getPitch(center), -100, null);
            }
            refreshToolSlot();
            sendBreakPackets(target.pos, face(target.pos));
            mc.player.swingHand(Hand.MAIN_HAND);
            burstSent = true;
        } else {
            // Normal per-tick breaking: progress every tick until the server reports
            // the block gone. No attempt cap (obstructions can be slow to break);
            // the only net is a long-timeout safety net for unbreakable blocks.
            if (++target.attempts > MAX_NORMAL_BREAK_TICKS) {
                target.pos = null;
                return;
            }
            refreshToolSlot();
            mc.interactionManager.updateBlockBreakingProgress(target.pos, face(target.pos));
            mc.player.swingHand(Hand.MAIN_HAND);
        }
    }

    /**
     * The exact break sequence AutoLoader uses: grim-v3 sends an extra
     * STOP/START/ABORT burst, both end with a STOP.
     */
    private void sendBreakPackets(BlockPos pos, Direction dir) {
        if (packetBreakGrim.get()) {
            breakAction(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, pos, dir);
            breakAction(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, dir);
            breakAction(PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, pos, dir);
        } else {
            breakAction(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, dir);
            breakAction(PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, pos, dir);
        }
        breakAction(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, pos, dir);
    }

    /**
     * Sends a break action either as a plain packet or — for instant remining —
     * through the sequenced-packet accessor, which stamps it with the client's
     * current interaction sequence so the server applies the break in order.
     */
    private void breakAction(PlayerActionC2SPacket.Action action, BlockPos pos, Direction dir) {
        if (instantRemine.get()) {
            ((ClientPlayerInteractionManagerAccessor) mc.interactionManager)
                .invokeSendSequencedPacket(mc.world, sequence ->
                    new PlayerActionC2SPacket(action, pos, dir, sequence));
        } else {
            mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(action, pos, dir));
        }
    }

    private void swapTool() {
        int slot = findToolSlot(mining.pos);
        if (slot == -1) return;
        // Remember the original hotbar slot only on the first swap; each new
        // obstruction reselects its own matching tool.
        if (!toolSwapped) preToolSlot = mc.player.getInventory().getSelectedSlot();
        toolSlot = slot;
        mc.player.getInventory().setSelectedSlot(slot);
        refreshToolSlot();
        toolSwapped = true;
    }

    /**
     * Pick the hotbar tool that best matches the obstruction: the tool for the
     * block's MINEABLE tag first (speed matters even when no tool is "required"
     * for drops, e.g. shovel for dirt), then any suitable tool, then a pickaxe.
     */
    private int findToolSlot(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        TagKey<net.minecraft.item.Item> preferred = pickToolTag(state);
        if (preferred != null) {
            TagKey<net.minecraft.item.Item> tag = preferred;
            int slot = hotbarSlot(stack -> stack.isIn(tag));
            if (slot != -1) return slot;
        }
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isSuitableFor(state)) return i;
        }
        return hotbarSlot(stack -> stack.isIn(ItemTags.PICKAXES));
    }

    private TagKey<net.minecraft.item.Item> pickToolTag(BlockState state) {
        if (state.isIn(BlockTags.PICKAXE_MINEABLE)) return ItemTags.PICKAXES;
        if (state.isIn(BlockTags.AXE_MINEABLE)) return ItemTags.AXES;
        if (state.isIn(BlockTags.SHOVEL_MINEABLE)) return ItemTags.SHOVELS;
        if (state.isIn(BlockTags.HOE_MINEABLE)) return ItemTags.HOES;
        return null;
    }

    private int hotbarSlot(Predicate<net.minecraft.item.ItemStack> match) {
        for (int i = 0; i < 9; i++) {
            if (match.test(mc.player.getInventory().getStack(i))) return i;
        }
        return -1;
    }

    /**
     * Re-send the selected slot so the server tracks the swap even if a packet lagged.
     */
    private void refreshToolSlot() {
        if (toolSwapped) {
            mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(toolSlot));
        }
    }

    private void restoreTool() {
        if (toolSwapped && preToolSlot >= 0 && preToolSlot <= 8 && mc.player != null) {
            mc.player.getInventory().setSelectedSlot(preToolSlot);
            mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(preToolSlot));
        }
        toolSwapped = false;
        preToolSlot = -1;
    }

    private Direction face(BlockPos pos) {
        Vec3d d = pos.toCenterPos().subtract(mc.player.getEyePos());
        return Direction.getFacing(d.x, d.y, d.z);
    }

    // opening
    private void open() {
        if (openEntity != null) {
            if (!container(openEntity)) {
                reset();
                return;
            }
            if (postMineWait > 0) {
                postMineWait--;
                return;
            }
            if (pendingOpen) {
                // Rotations.rotate() defers the send to the callback; wait for it. A
                // superseded rotation never fires its callback, so time out instead of
                // blocking clicks forever (phase would otherwise stay OPEN).
                if (++openTimeoutTicks > OPEN_CALLBACK_TIMEOUT) reset();
                return;
            }
            doOpenEntity();
            return;
        }

        if (container == null || !container(container)) {
            reset();
            return;
        }
        if (postMineWait > 0) {
            postMineWait--;
            return;
        }
        if (pendingOpen) {
            // Rotations.rotate() defers sendOpen to the callback; wait for it. A
            // superseded rotation never fires its callback, so time out instead of
            // blocking clicks forever (phase would otherwise stay OPEN).
            if (++openTimeoutTicks > OPEN_CALLBACK_TIMEOUT) reset();
            return;
        }
        if (obstructed(container)) {
            // A hidden container whose lid is stuck may only be mined when this
            // session allowed it (start() allows, a beginOpen() honors the
            // mine-obstruction setting). Without that the module would queue a
            // mine here anyway, silently ignoring the user's switch.
            if (!allowMine) {
                info("Can't open: the obstruction stays and mining is disabled.");
                reset();
                return;
            }
            // A re-settled gravity column refills the lid one pass at a time, so
            // it must not consume openTries. Progress is validated on actually
            // broken blocks: a pass that broke nothing means the lid can't be
            // cleared, and the pass cap is only a safety net.
            BlockPos gravity = gravityObstruction();
            if (gravity != null) {
                if (minedThisPhase == 0) {
                    info("Gravity column isn't clearing, giving up.");
                    reset();
                    return;
                }
                if (++gravityPasses > 96) {
                    info("Gravity column isn't clearing, giving up.");
                    reset();
                    return;
                }
                minedThisPhase = 0;
                queue(container);
                phase = Phase.MINE;
                return;
            }
            if (openTries >= OPEN_TRIES) {
                info("Couldn't clear the obstruction, giving up.");
                reset();
                return;
            }
            openTries++;
            queue(container);
            phase = Phase.MINE;
            minedThisPhase = 0;
            return;
        }
        openTries = 0;
        doOpen();
    }

    private void doOpen() {
        if (container == null) {
            reset();
            return;
        }
        BlockHitResult hit = hit(container);
        if (rotate.get()) {
            Vec3d vec = container.toCenterPos();
            final int gen = session;
            // Reset only once the open is actually sent (rotation may defer it).
            pendingOpen = true;
            openTimeoutTicks = 0;
            Rotations.rotate(Rotations.getYaw(vec), Rotations.getPitch(vec), -100, () -> {
                if (gen != session || !isActive()) return;
                sendOpen(hit);
                reset();
            });
        } else {
            sendOpen(hit);
            reset();
        }
    }

    private void doOpenEntity() {
        Entity e = openEntity;
        if (e == null) {
            reset();
            return;
        }
        if (rotate.get()) {
            Vec3d vec = e.getBoundingBox().getCenter();
            final int gen = session;
            pendingOpen = true;
            openTimeoutTicks = 0;
            Rotations.rotate(Rotations.getYaw(vec), Rotations.getPitch(vec), -100, () -> {
                if (gen != session || !isActive()) return;
                sendOpenEntity(e);
                reset();
            });
        } else {
            sendOpenEntity(e);
            reset();
        }
    }

    private void sendOpenEntity(Entity e) {
        // Mounts and chest boats open on a sneaking interaction, merchants on a
        // plain one. Send the packet directly instead of via
        // interactionManager.interactEntity, which posts InteractEntityEvent and
        // gets cancelled here by the phase != IDLE guard on our own handler.
        boolean sneak = e instanceof AbstractDonkeyEntity || e instanceof VehicleInventory;
        mc.player.networkHandler.sendPacket(PlayerInteractEntityC2SPacket.interact(e, sneak, Hand.MAIN_HAND));
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private void sendOpen(BlockHitResult hit) {
        // hiddenOwn lets this exact packet through onPacket's cancel filter,
        // which otherwise suppresses vanilla's own interact packet for any
        // click currently being driven through the hidden-container flow.
        hiddenOwn = true;
        ((ClientPlayerInteractionManagerAccessor) mc.interactionManager)
            .invokeSendSequencedPacket(mc.world, sequence -> new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hit, sequence));
        mc.player.swingHand(Hand.MAIN_HAND);
        hiddenOwn = false;
    }

    private BlockHitResult hit(BlockPos pos) {
        Vec3d eye = mc.player.getEyePos();
        if (openPoint != null) {
            return new BlockHitResult(openPoint, side(pos, eye), pos, false);
        }
        Vec3d end = eye.add(mc.player.getRotationVector().normalize().multiply(maxRange.get()));
        Vec3d point = new Box(pos).expand(1.0E-3).raycast(eye, end).orElse(pos.toCenterPos());
        return new BlockHitResult(point, side(pos, eye), pos, false);
    }

    private Direction side(BlockPos pos, Vec3d eye) {
        Vec3d center = pos.toCenterPos();
        Vec3d dir = eye.subtract(center);
        Direction best = Direction.UP;
        double value = -Double.MAX_VALUE;
        for (Direction s : Direction.values()) {
            double current = dir.x * s.getOffsetX() + dir.y * s.getOffsetY() + dir.z * s.getOffsetZ();
            if (current > value) {
                best = s;
                value = current;
            }
        }
        return best;
    }

    private void reset() {
        restoreTool();
        toolSlot = -1;
        mining = null;
        mining2 = null;
        queue.clear();
        espBoxes.clear();
        container = null;
        openPoint = null;
        openEntity = null;
        waiting = false;
        waitTicks = 0;
        burstSent = false;
        postMineWait = 0;
        openTries = 0;
        pendingOpen = false;
        openTimeoutTicks = 0;
        minedThisPhase = 0;
        gravityPasses = 0;
        clearingGravity = false;
        allowMine = false;
        // hiddenLock is intentionally left alone here, it should only clear on
        // use-key release (see tickAutoHidden).
        hiddenBlock = false;
        hiddenOwn = false;
        session++;
        phase = Phase.IDLE;
    }
}
