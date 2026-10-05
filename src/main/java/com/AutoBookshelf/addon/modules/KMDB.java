package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class KMDB extends Module {

    public enum BuildMode {Wither, IronGolem, SnowGolem, CopperGolem, Creaking}

    /**
     * Which way a wither's skulls point relative to the base soul block.
     */
    private enum WitherOrientation {
        Upright(Direction.UP),
        UpsideDown(Direction.DOWN),
        North(Direction.NORTH),
        South(Direction.SOUTH),
        East(Direction.EAST),
        West(Direction.WEST);

        private final Direction direction;

        WitherOrientation(Direction direction) {
            this.direction = direction;
        }

        public Direction direction() {
            return direction;
        }
    }

    /**
     * An iron golem's T is planar: a body block with two arms along {@code cross},
     * the base iron on the {@code -up} side of the body and the pumpkin on the
     * {@code +up} side. Describing it as one (up, cross) pair rather than as fixed
     * block lists is what makes upright, upside-down and lying-down the same code
     * path, a lying golem is just the T rotated until its plane is vertical.
     */
    private static class IronGolem {
        public BlockPos base;
        public Direction up;
        public Direction cross;

        public IronGolem set(BlockPos base, Direction up, Direction cross) {
            this.base = base;
            this.up = up;
            this.cross = cross;
            return this;
        }

        public BlockPos body() {
            return base.relative(up);
        }

        public BlockPos armA() {
            return body().relative(cross);
        }

        public BlockPos armB() {
            return body().relative(cross.getOpposite());
        }

        public BlockPos pumpkin() {
            return body().relative(up);
        }

        /**
         * Base, body, then both arms which is the four iron blocks, in the order they are
         * placed. The pumpkin is deliberately excluded so callers cannot place it
         * before the iron it sits on.
         */
        public List<BlockPos> ironPositions() {
            return List.of(base, body(), armA(), armB());
        }

        /**
         * The four cells above and below each arm. Vanilla refuses to spawn the golem
         * unless every one of them is true air
         */
        public List<BlockPos> clearancePositions() {
            BlockPos arm = armA();
            BlockPos other = armB();
            return List.of(
                arm.relative(up), arm.relative(up.getOpposite()),
                other.relative(up), other.relative(up.getOpposite()));
        }
    }

    /**
     * Which way the golem's T points. Lying follows the player's horizontal facing,
     * which decides both the plane and which way the pumpkin ends up pointing.
     */
    private enum IronGolemOrientation {
        /**
         * Base on the floor, pumpkin on top. Arms follow your facing.
         */
        Upright,
        /**
         * The T flipped vertically: pumpkin below the body, base on top.
         */
        UpsideDown,
        /**
         * Standing on edge, pumpkin pointing where you look.
         */
        Lying
    }

    private static final List<int[]> SNOW_GOLEM_FOOTPRINT = List.of(
        new int[]{0, 0, 0},
        new int[]{0, 1, 0},
        new int[]{0, 2, 0}
    );

    private static final List<int[]> COPPER_GOLEM_FOOTPRINT = List.of(
        new int[]{0, 0, 0},
        new int[]{0, 1, 0}
    );

    /**
     * Creaking core: two pale oak logs flanking one creaking heart, all three
     * sharing a single axis. Y is a vertical pillar; X and Z are horizontal.
     *
     * <p>This 1x3 line is what actually activates a Creaking:
     * CreakingHeartBlock#hasRequiredLogs requires the two neighbours along the
     * heart's axis to be BlockTags.PALE_OAK_LOGS with the same axis value.
     * The all-six-faces check (isSurroundedByLogs) only drives the idle
     * sound, so it must NOT be applied here.
     */
    private static final List<int[]> CREAKING_FOOTPRINT_Y = List.of(
        new int[]{0, 0, 0},   // log
        new int[]{0, 1, 0},   // creaking heart
        new int[]{0, 2, 0}    // log
    );

    private static final List<int[]> CREAKING_FOOTPRINT_X = List.of(
        new int[]{-1, 1, 0},  // log
        new int[]{0, 1, 0},   // creaking heart
        new int[]{1, 1, 0}    // log
    );

    private static final List<int[]> CREAKING_FOOTPRINT_Z = List.of(
        new int[]{0, 1, -1},  // log
        new int[]{0, 1, 0},   // creaking heart
        new int[]{0, 1, 1}    // log
    );

    /**
     * Deferred creaking placements still waiting on their rotation callback.
     */
    private int creakingPending = 0;
    /**
     * A creaking placement was rejected, so the build must not report success.
     */
    private boolean creakingFailed = false;

    // Memo for selectedFoot, valid only while every input the search reads is
    // unchanged. footCacheFoot is null when no clear spot was found, which is why
    // validity is tracked separately rather than tested against the result.
    private boolean footCacheValid = false;
    private BlockPos footCacheFoot = null;
    private int footCacheHash = 0;
    private int footCacheRadius = 0;
    private int footCacheDistance = 0;
    private boolean footCacheAirPlace = false;
    private BlockPos footCachePlayerPos = null;
    private Direction footCacheFacing = null;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgWither = settings.createGroup("Wither Settings");
    private final SettingGroup sgPlacement = settings.createGroup("Placement");
    private final SettingGroup sgIronGolem = settings.createGroup("Iron Golem");
    private final SettingGroup sgCopper = settings.createGroup("Copper Golem");
    private final SettingGroup sgCreaking = settings.createGroup("Creaking");

    private final Setting<BuildMode> buildMode = sgGeneral.add(new EnumSetting.Builder<BuildMode>()
        .name("build-mode")
        .description("Which structure to build.")
        .defaultValue(BuildMode.Wither)
        .build()
    );

    private final Setting<Direction.Axis> creakingAxis = sgCreaking.add(new EnumSetting.Builder<Direction.Axis>()
        .name("creaking-axis")
        .description("Axis the two pale oak logs and the creaking heart line up on. Y is a vertical pillar, X and Z are horizontal.")
        .defaultValue(Direction.Axis.Y)
        .visible(() -> buildMode.get() == BuildMode.Creaking)
        .build()
    );

    private final Setting<Boolean> creakingRotate = sgCreaking.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotate to face the blocks when placing them.")
        .defaultValue(true)
        .visible(() -> buildMode.get() == BuildMode.Creaking)
        .build()
    );

    private final Setting<Integer> placementDistance = sgPlacement.add(new IntSetting.Builder()
        .name("placement-distance")
        .description("Distance in front of the player to build at. Manual placement walks closer when the structure will not past your range.")
        .defaultValue(3)
        .min(2)
        .max(6)
        .build()
    );

    private final Setting<Integer> placementSearchRadius = sgPlacement.add(new IntSetting.Builder()
        .name("search-radius")
        .description("How far to search around the preferred spot for a clear location if it's obstructed.")
        .defaultValue(2)
        .min(0)
        .max(5)
        .visible(() -> buildMode.get() != BuildMode.Wither)
        .build()
    );

    private final Setting<Boolean> manualPlacement = sgPlacement.add(new BoolSetting.Builder()
        .name("manual-placement")
        .description("Pick the spot yourself. The preview follows the block you are looking at, the build key places it.")
        .defaultValue(false)
        .build()
    );

    private final KeybindSetting buildKey = sgPlacement.add(new KeybindSetting.Builder()
        .name("build-key")
        .description("Build the previewed structure. Only used while manual-placement is on.")
        .action(this::executeBuild)
        .visible(() -> manualPlacement.get())
        .build()
    );

    private final Setting<Boolean> airPlace = sgPlacement.add(new BoolSetting.Builder()
        .name("air-place")
        .description("Use packet-based airplace to place structure blocks with nothing to click against.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> airPlaceRange = sgPlacement.add(new DoubleSetting.Builder()
        .name("air-place-range")
        .description("How far to reach for the spot while air placing. Applied in every mode.")
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .visible(() -> airPlace.get())
        .build()
    );

    private final Setting<IronGolemOrientation> ironGolemOrientation = sgIronGolem.add(new EnumSetting.Builder<IronGolemOrientation>()
        .name("orientation")
        .description("Upright, upside-down, or lying on its side. Lying takes its direction from where you face.")
        .defaultValue(IronGolemOrientation.Upright)
        .visible(() -> buildMode.get() == BuildMode.IronGolem)
        .build()
    );

    private final Setting<Integer> witherHorizontalRadius = sgWither.add(new IntSetting.Builder()
        .name("horizontal-radius")
        .description("Horizontal search radius for valid Wither spawn positions.")
        .defaultValue(4)
        .min(0)
        .sliderMax(6)
        .visible(() -> buildMode.get() == BuildMode.Wither)
        .build()
    );

    private final Setting<Integer> witherVerticalRadius = sgWither.add(new IntSetting.Builder()
        .name("vertical-radius")
        .description("Vertical search radius for valid Wither spawn positions.")
        .defaultValue(3)
        .min(0)
        .sliderMax(6)
        .visible(() -> buildMode.get() == BuildMode.Wither)
        .build()
    );

    private final Setting<Item> witherSoulBlock = sgWither.add(new ItemSetting.Builder()
        .name("soul-block")
        .description("Soul sand or soul soil variant to use for the base. The other one is used if this is not in your hotbar.")
        .defaultValue(Items.SOUL_SAND)
        .filter(item -> item == Items.SOUL_SAND || item == Items.SOUL_SOIL)
        .visible(() -> buildMode.get() == BuildMode.Wither)
        .build()
    );

    private final Setting<WitherOrientation> witherOrientation = sgWither.add(new EnumSetting.Builder<WitherOrientation>()
        .name("orientation")
        .description("Which way the wither skulls point. Upright and upside down stack the base against a floor or ceiling.")
        .defaultValue(WitherOrientation.Upright)
        .visible(() -> buildMode.get() == BuildMode.Wither)
        .build()
    );

    private final Setting<Boolean> witherRotate = sgWither.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotate to face the blocks when placing.")
        .defaultValue(true)
        .visible(() -> buildMode.get() == BuildMode.Wither)
        .build()
    );

    private final Setting<Integer> witherPlaceDelay = sgWither.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Delay between placing Wither blocks (ticks).")
        .defaultValue(1)
        .min(0)
        .sliderRange(0, 10)
        .visible(() -> buildMode.get() == BuildMode.Wither)
        .build()
    );

    private final Setting<Boolean> autoToggle = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-toggle")
        .description("Automatically disable after building one structure. Turn this off to build several in a row.")
        .defaultValue(true)
        .visible(() -> !manualPlacement.get())
        .build()
    );

    private final Setting<Item> copperBlock = sgCopper.add(new ItemSetting.Builder()
        .name("copper-block")
        .description("The copper block variant to use for the base.")
        .defaultValue(Items.COPPER_BLOCK.weathering().unaffected())
        .visible(() -> buildMode.get() == BuildMode.CopperGolem)
        .build()
    );

    private final Setting<Boolean> skipIfOccupied = sgCopper.add(new BoolSetting.Builder()
        .name("skip-if-occupied")
        .description("Skip building if the foot position already contains a block (copper chest).")
        .defaultValue(true)
        .visible(() -> buildMode.get() == BuildMode.CopperGolem)
        .build()
    );

    private FindItemResult findPumpkin() {
        FindItemResult pumpkin = InvUtils.findInHotbar(Items.CARVED_PUMPKIN);
        if (!pumpkin.found()) {
            pumpkin = InvUtils.findInHotbar(Items.JACK_O_LANTERN);
        }
        return pumpkin;
    }

    /**
     * Soul sand and soul soil are interchangeable for a wither, so honour the chosen
     * variant but fall back to the other one rather than refusing to build.
     */
    private FindItemResult findSoulBlock() {
        Item preferred = witherSoulBlock.get();
        Item other = preferred == Items.SOUL_SOIL ? Items.SOUL_SAND : Items.SOUL_SOIL;

        FindItemResult result = InvUtils.findInHotbar(preferred);
        if (!result.found()) {
            result = InvUtils.findInHotbar(other);
        }
        return result;
    }

    private final Setting<Boolean> renderPreview = sgGeneral.add(new BoolSetting.Builder()
        .name("render-preview")
        .description("Show a preview of the structure.")
        .defaultValue(true)
        .build()
    );
    private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .defaultValue(ShapeMode.Both)
        .build()
    );
    private final Setting<SettingColor> soulSandColor = sgGeneral.add(new ColorSetting.Builder()
        .name("soul-sand-color")
        .defaultValue(new SettingColor(139, 69, 19, 85))
        .visible(() -> renderPreview.get() && buildMode.get() == BuildMode.Wither)
        .build()
    );
    private final Setting<SettingColor> skullColor = sgGeneral.add(new ColorSetting.Builder()
        .name("skull-color")
        .defaultValue(new SettingColor(200, 200, 200, 85))
        .visible(() -> renderPreview.get() && buildMode.get() == BuildMode.Wither)
        .build()
    );
    private final Setting<SettingColor> ironColor = sgGeneral.add(new ColorSetting.Builder()
        .name("iron-color")
        .description("The four iron blocks of an iron golem.")
        .defaultValue(new SettingColor(255, 255, 255, 90))
        .visible(() -> renderPreview.get() && buildMode.get() == BuildMode.IronGolem)
        .build()
    );
    private final Setting<SettingColor> snowColor = sgGeneral.add(new ColorSetting.Builder()
        .name("snow-color")
        .description("The two snow layers of a snow golem.")
        .defaultValue(new SettingColor(235, 245, 255, 90))
        .visible(() -> renderPreview.get() && buildMode.get() == BuildMode.SnowGolem)
        .build()
    );
    private final Setting<SettingColor> copperColor = sgGeneral.add(new ColorSetting.Builder()
        .name("copper-color")
        .description("The copper base block of a copper golem.")
        .defaultValue(new SettingColor(200, 130, 80, 90))
        .visible(() -> renderPreview.get() && buildMode.get() == BuildMode.CopperGolem)
        .build()
    );
    private final Setting<SettingColor> pumpkinColor = sgGeneral.add(new ColorSetting.Builder()
        .name("pumpkin-color")
        .description("The carved pumpkin that triggers the spawn.")
        .defaultValue(new SettingColor(255, 140, 0, 90))
        .visible(() -> renderPreview.get() && (buildMode.get() == BuildMode.IronGolem
            || buildMode.get() == BuildMode.SnowGolem
            || buildMode.get() == BuildMode.CopperGolem))
        .build()
    );
    private final Setting<SettingColor> logColor = sgGeneral.add(new ColorSetting.Builder()
        .name("log-color")
        .description("The pale oak logs flanking a creaking heart.")
        .defaultValue(new SettingColor(110, 90, 80, 120))
        .visible(() -> renderPreview.get() && buildMode.get() == BuildMode.Creaking)
        .build()
    );
    private final Setting<SettingColor> heartColor = sgGeneral.add(new ColorSetting.Builder()
        .name("heart-color")
        .description("The creaking heart between the two logs.")
        .defaultValue(new SettingColor(210, 110, 0, 120))
        .visible(() -> renderPreview.get() && buildMode.get() == BuildMode.Creaking)
        .build()
    );
    private final Setting<SettingColor> airPlaceColor = sgGeneral.add(new ColorSetting.Builder()
        .name("air-place-color")
        .description("Marks where air placing is aiming, since there is no crosshair target to aim with.")
        .defaultValue(new SettingColor(255, 0, 0, 200))
        .visible(() -> renderPreview.get() && manualPlacement.get() && airPlace.get())
        .build()
    );

    /**
     * A wither is a flat T: 4 soul sand/soil with 3 skulls on the far side, so the
     * whole thing lives in the plane spanned by up (base -> skulls) and cross (the
     * two arms of the T). up is user-facing via the orientation setting; cross is
     * whichever perpendicular axis the search found room for.
     */
    private static class Wither {
        public int stage;
        public BlockPos.MutableBlockPos foot = new BlockPos.MutableBlockPos();
        public Direction up;
        public Direction cross;

        public Wither set(BlockPos pos, Direction up, Direction cross) {
            this.stage = 0;
            this.foot.set(pos);
            this.up = up;
            this.cross = cross;
            return this;
        }

        /**
         * Stages 0-3 are soul sand and 4-6 are the skulls, so the last block placed
         * is always a skull which is what actually triggers the spawn.
         */
        public BlockPos pos(int stage) {
            BlockPos center = foot.relative(up);
            BlockPos armA = center.relative(cross);
            BlockPos armB = center.relative(cross.getOpposite());
            return switch (stage) {
                case 0 -> foot;
                case 1 -> center;
                case 2 -> armA;
                case 3 -> armB;
                case 4 -> center.relative(up);
                case 5 -> armA.relative(up);
                case 6 -> armB.relative(up);
                default -> null;
            };
        }
    }

    private Wither currentWither;
    private int witherTicksWaited;
    private int blockTicksWaited;

    /**
     * Last mode seen on a tick. A mode switch has to clear what the previous mode was
     * holding: a half-placed or finished Wither would otherwise be resumed mid-staging
     * the moment Wither is selected again, and a stale spawn would keep previewing at a
     * position the player has long since walked away from.
     */
    private BuildMode lastBuildMode;

    public KMDB() {
        super(Addon.CATEGORY, "KMDB", "Builds Wither, Iron Golem, Snow Golem, Copper Golem, or Creaking automatically.");
    }

    @Override
    public void onActivate() {
        // A previous run may have left deferred placements queued, the module can be
        // switched off before they land, and nothing cancels those callbacks. Clear
        // them here, ahead of the switch, so a stale count cannot suppress the
        // toggle-off at the end of this method.
        creakingPending = 0;
        creakingFailed = false;
        footCacheValid = false;
        // Record the mode now so the first tick sees no change and does not mistake
        // activation for a mode switch.
        lastBuildMode = buildMode.get();

        // Manual placement only previews; the build key does the placing, so that
        // the spot can be moved between builds without deactivating.
        if (manualMode()) return;

        executeBuild();

        // Toggle off instantly for non Wither modes, except a rotating creaking build
        // whose placements are still queued, settleCreaking() toggles once they land.
        if (autoToggle.get() && buildMode.get() != BuildMode.Wither && creakingPending == 0) toggle();
    }

    /**
     * Builds whatever the current mode is. Shared by activation and the manual
     * build key so the two paths cannot drift apart.
     */
    private void executeBuild() {
        switch (buildMode.get()) {
            case Wither -> {
                if (manualMode()) manualStartWither();
                else startWither();
            }
            case IronGolem -> buildIronGolem();
            case SnowGolem -> buildSnowGolem();
            case CopperGolem -> buildCopperGolem();
            case Creaking -> buildCreaking();
        }
    }

    /**
     * Manual placement: the player picks the spot and only the build key places it,
     * so the module stays active across several builds. Available for every mode,
     * Wither included.
     */
    private boolean manualMode() {
        return manualPlacement.get();
    }

    @Override
    public void onDeactivate() {
        currentWither = null;
    }

    private void startWither() {
        currentWither = null;
        witherTicksWaited = 0;
        blockTicksWaited = 0;
    }

    private Wither findValidWitherSpawn() {
        int hRadius = witherHorizontalRadius.get();
        int vRadius = witherVerticalRadius.get();
        BlockPos playerPos = mc.player.blockPosition();
        Direction up = witherOrientation.get().direction();

        Wither best = null;
        double bestDist = Double.MAX_VALUE;
        for (int y = -vRadius; y <= vRadius; y++) {
            for (int x = -hRadius; x <= hRadius; x++) {
                for (int z = -hRadius; z <= hRadius; z++) {
                    BlockPos pos = playerPos.offset(x, y, z);
                    for (Direction cross : crossCandidates(up)) {
                        Wither candidate = new Wither().set(pos, up, cross);
                        if (isValidWitherSpawn(candidate)) {
                            double dist = PlayerUtils.distanceTo(pos);
                            if (dist < bestDist) {
                                bestDist = dist;
                                best = candidate;
                            }
                            break; // this spot works on one cross axis, no need to test the other
                        }
                    }
                }
            }
        }
        return best;
    }

    /**
     * The T's arms have to sit perpendicular to the skull direction. An upright or
     * upside-down build keeps them horizontal, so any compass direction will do; a
     * sideways build stands them up or down instead.
     */
    private static Direction[] crossCandidates(Direction up) {
        if (up.getAxis() == Direction.Axis.Y) {
            // Upright or upside down: the arms stay horizontal, either compass axis works.
            return new Direction[]{Direction.EAST, Direction.NORTH};
        }

        // Sideways: the arms can either stand vertical or lie flat across the other
        // horizontal axis (a wither resting on the ground), so check one of each
        // rather than only the vertical pair.
        Direction flat = up.getAxis() == Direction.Axis.X ? Direction.NORTH : Direction.EAST;
        return new Direction[]{Direction.UP, flat};
    }

    private boolean isValidWitherSpawn(Wither wither) {
        for (int stage = 0; stage <= 6; stage++) {
            BlockPos pos = wither.pos(stage);
            if (!inBuildBounds(pos)) return false;

            BlockState state = mc.level.getBlockState(pos);
            if (!state.canBeReplaced()) return false;
            if (!mc.level.noCollision(new AABB(pos))) return false;
        }

        // Air on both sides of the base, otherwise the shape is not a clean T and
        // the wither refuses to spawn no matter how the skulls are placed.
        for (Direction side : new Direction[]{wither.cross, wither.cross.getOpposite()}) {
            BlockPos pos = wither.foot.relative(side);
            if (!inBuildBounds(pos)) return false;
            if (!mc.level.getBlockState(pos).isAir()) return false;
        }

        return true;
    }

    private boolean inBuildBounds(BlockPos pos) {
        // Ask the world rather than hardcoding 0-255: the Nether and the End have
        // their own build range, and a spot outside it can never be built on.
        return pos.getY() >= mc.level.getMinY() && pos.getY() <= mc.level.getMaxY();
    }

    /**
     * The unvalidated block a manual placement would build on: the first cell with room
     * in it along the camera ray when air placing, otherwise the placement-distance
     * straight ahead. Callers run the fit checks, which for the non-air case also walk
     * the distance in until the structure fits.
     *
     * <p>Air place cannot use the crosshair at all so there is nothing to click against,
     * so it walks the camera ray instead. See {@link #airPlaceTarget}.
     */
    private BlockPos selectedBlock() {
        if (airPlace.get()) return airPlaceTarget(airPlaceRange.get());

        // Not air placing: the spot is the placement-distance straight ahead. Callers
        // validate it
        return mc.player.blockPosition().relative(mc.player.getDirection(), placementDistance.get());
    }

    /**
     * The Wither's distance-walked foot, matching manualFoot for the golem modes. The
     * cross axis is re-picked per candidate because a rotated T does not fit where an
     * unrotated one did.
     */
    private BlockPos manualWitherFoot(Direction up) {
        if (airPlace.get()) return selectedBlock();

        Direction facing = mc.player.getDirection();
        BlockPos playerPos = mc.player.blockPosition();

        for (int d = placementDistance.get(); d >= 0; d--) {
            BlockPos pos = playerPos.relative(facing, d);
            for (Direction cross : crossCandidates(up)) {
                Wither candidate = new Wither().set(pos, up, cross);
                if (isValidWitherSpawn(candidate) && withinReach(candidate)) return pos;
            }
        }
        return null;
    }

    /**
     * Every cell of the staged Wither close enough for the server to place it.
     */
    private boolean withinReach(Wither wither) {
        double limit = reachLimit() * reachLimit();
        Vec3 from = mc.player.position();
        for (int stage = 0; stage <= 6; stage++) {
            BlockPos pos = wither.pos(stage);
            if (pos.distToCenterSqr(from.x, from.y, from.z) > limit) return false;
        }
        return true;
    }

    /**
     * The cell the camera ray reaches within the range, matching how Meteor's AirPlace
     * picks its spot.
     *
     * <p>The ray is bounded by the range rather than walked, which is what makes the
     * setting mean something. Walking it and stopping at the first replaceable cell
     * always landed on the air right in front of the player's face, so every range gave
     * the same answer and the setting did nothing. A bounded ray instead reports the
     * cell at the far end when it meets nothing, so raising the range moves the spot out
     * to where the player is aiming and lowering it pulls it back in.
     *
     */
    private BlockPos airPlaceTarget(double range) {
        Entity camera = mc.getCameraEntity();
        if (camera == null || range <= 0) return null;

        Vec3 eye = camera.getEyePosition();
        Vec3 step = camera.getLookAngle().normalize().scale(0.05);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        BlockPos lastFree = null;
        BlockPos target = null;
        boolean metSolid = false;
        BlockPos last = null;

        for (int i = 0; i <= Math.max(1, (int) Math.ceil(range / 0.05)); i++) {
            Vec3 point = eye.add(step.scale(i));
            cursor.set(point.x, point.y, point.z);
            BlockPos pos = cursor.immutable();

            if (last != null && pos.equals(last)) continue;
            last = pos;

            // The eye sits in the player's own cell, which is always air, so without
            // this the walk hands back the block the player is standing in. Measured
            // from the cell's centre: the eye's own cell is always under 1 block away.
            // The box test is what actually catches the rest of the body, looking
            // straight down passes that distance check at the cell under the feet while
            // still overlapping the hitbox.
            if (pos.distToCenterSqr(eye.x, eye.y, eye.z) < 1) continue;
            if (new AABB(pos).intersects(camera.getBoundingBox())) continue;

            if (!inBuildBounds(pos)) break;
            if (metSolid) break;

            BlockState state = mc.level.getBlockState(pos);
            // A replaceable cell still holds something a golem cannot spawn into a
            // grass block, a snow layer, water etc so that counts as blocked even though
            // air place would overwrite it. Only true air is somewhere to build.
            if (!state.isAir()) metSolid = true;
            if (state.isAir()) lastFree = pos;
            target = pos;
        }

        if (metSolid) return lastFree;
        return target != null && mc.level.getBlockState(target).canBeReplaced() ? target : null;
    }

    /**
     * <p>Manual placement takes the block the player picked, no search, but the same
     * fit checks, so a spot that cannot hold the shape reports none. Otherwise it is
     * the nearest clear spot in front of them.
     *
     * <p>Builds deliberately re-resolve rather than trusting the cache, so a stale
     * preview can only ever cost a fresh search, never a placement into a spot that
     * was not clear when the key was pressed.
     */
    private BlockPos selectedFoot(List<int[]> relativeOffsets, boolean useCache) {
        return selectedFoot(relativeOffsets, relativeOffsets, useCache);
    }

    /**
     * @param relativeOffsets every cell that has to be free, which for the iron golem
     *                        also covers the four arm clearance cells, nothing is placed in those, they just
     *                        have to be air
     * @param placedOffsets   only the cells a block is actually placed into, which is what
     *                        reach has to be judged against
     */
    private BlockPos selectedFoot(List<int[]> relativeOffsets, List<int[]> placedOffsets, boolean useCache) {
        if (manualMode()) {
            if (airPlace.get()) {
                // Aimed spot: the ray picks it, so the distance setting does not apply.
                BlockPos picked = selectedBlock();
                return picked != null && structureFits(picked, relativeOffsets) ? picked : null;
            }
            return manualFoot(placedOffsets, relativeOffsets);
        }

        int radius = placementSearchRadius.get();
        if (!useCache) return findClearFootPosition(relativeOffsets, radius);

        int hash = footprintHash(relativeOffsets);
        BlockPos playerPos = mc.player.blockPosition();
        Direction facing = mc.player.getDirection();
        boolean air = airPlace.get();
        // placement-distance picks the preferred spot the search sorts towards, so it
        // has to be part of the key or the preview would ignore a change to it until
        // the player happened to move.
        int distance = placementDistance.get();

        if (footCacheValid && footCacheHash == hash && footCacheRadius == radius
            && footCacheDistance == distance && footCacheAirPlace == air
            && footCachePlayerPos.equals(playerPos) && footCacheFacing == facing) {
            return footCacheFoot;
        }

        BlockPos found = findClearFootPosition(relativeOffsets, radius);
        footCacheFoot = found;
        footCacheHash = hash;
        footCacheRadius = radius;
        footCacheDistance = distance;
        footCacheAirPlace = air;
        footCachePlayerPos = playerPos;
        footCacheFacing = facing;
        footCacheValid = true;
        return found;
    }

    private static int footprintHash(List<int[]> relativeOffsets) {
        int hash = 1;
        for (int[] offset : relativeOffsets) {
            for (int value : offset) hash = 31 * hash + value;
        }
        return hash;
    }

    private boolean structureFits(BlockPos pos, List<int[]> relativeOffsets) {
        for (int[] rel : relativeOffsets) {
            BlockPos check = pos.offset(rel[0], rel[1], rel[2]);
            // Outside the world's build range it can never be placed, and the Nether
            // and the End each have their own.
            if (!inBuildBounds(check)) return false;
            // noCollision() checks entity collisions, which is what canPlace()
            // covered on 1.21.11; the replaceability test is the isAir() above.
            if (!mc.level.getBlockState(check).isAir()
                || !mc.level.noCollision(new AABB(check))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether every cell of the structure is close enough for the server to accept a
     * placement there. A golem is two blocks deep, so the base can sit inside the
     * interaction range while the far arm does not, and that far block would silently
     * fail to place.
     */
    private boolean structureWithinReach(BlockPos pos, List<int[]> relativeOffsets) {
        double limit = reachLimit() * reachLimit();
        Vec3 from = mc.player.position();
        for (int[] rel : relativeOffsets) {
            BlockPos check = pos.offset(rel[0], rel[1], rel[2]);
            if (check.distToCenterSqr(from.x, from.y, from.z) > limit) return false;
        }
        return true;
    }

    private double reachLimit() {
        return airPlace.get() ? airPlaceRange.get() : mc.player.blockInteractionRange();
    }

    /**
     * Straight ahead of the player at placement-distance, walked closer until the whole
     * structure both fits and stays within reach.
     *
     * <p>Manual placement selects a distance rather than a block, so the same spot comes
     * out of the setting every time and the auto search's {@code preferred} offset means
     * the same thing in both modes. The walk is what keeps that distance honest: at six
     * blocks the far end of a golem is past the interaction range, so it steps toward
     * the player until every cell is placeable rather than building something the server
     * will only half place.
     */
    /**
     * Whether there is something to build on, on the side this build actually hangs off.
     *
     * <p>Only the upside-down iron golem differs: its own "up" points down, so its base is the
     * topmost cell and the support has to be above it. Checking below unconditionally demanded
     * floor under a golem meant to be built from a ceiling. Every other mode and orientation
     * sits on top of its support, which is what below() gives.
     */
    private boolean hasSupportAt(BlockPos pos) {
        boolean upsideDownIron = buildMode.get() == BuildMode.IronGolem
            && ironGolemUp() == Direction.DOWN;
        BlockPos support = upsideDownIron ? pos.above() : pos.below();
        BlockState state = mc.level.getBlockState(support);
        return !state.isAir() && !state.canBeReplaced();
    }

    private BlockPos manualFoot(List<int[]> placedOffsets, List<int[]> relativeOffsets) {
        Direction facing = mc.player.getDirection();
        BlockPos playerPos = mc.player.blockPosition();

        for (int d = placementDistance.get(); d >= 0; d--) {
            BlockPos pos = playerPos.relative(facing, d);
            if (!airPlace.get() && !hasSupportAt(pos)) continue;
            if (structureFits(pos, relativeOffsets) && structureWithinReach(pos, placedOffsets)) return pos;
        }
        return null;
    }

    private BlockPos findClearFootPosition(List<int[]> relativeOffsets, int searchRadius) {
        Direction facing = mc.player.getDirection();
        BlockPos preferred = mc.player.blockPosition().relative(facing, placementDistance.get());

        List<BlockPos> candidates = new ArrayList<>();
        for (int x = -searchRadius; x <= searchRadius; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -searchRadius; z <= searchRadius; z++) {
                    BlockPos pos = preferred.offset(x, y, z);

                    if (!airPlace.get() && !hasSupportAt(pos)) continue;

                    if (structureFits(pos, relativeOffsets)) candidates.add(pos);
                }
            }
        }

        if (candidates.isEmpty()) return null;
        candidates.sort(Comparator.comparingDouble(PlayerUtils::distanceTo));
        return candidates.get(0);
    }

    private List<int[]> snowGolemFootprint() {
        return SNOW_GOLEM_FOOTPRINT;
    }

    private List<int[]> copperGolemFootprint() {
        return COPPER_GOLEM_FOOTPRINT;
    }

    private List<int[]> creakingFootprint() {
        return switch (creakingAxis.get()) {
            case X -> CREAKING_FOOTPRINT_X;
            case Z -> CREAKING_FOOTPRINT_Z;
            default -> CREAKING_FOOTPRINT_Y;
        };
    }

    /**
     * The Wither to preview, or null when there is nothing to show.
     *
     * <p>Manual placement has to preview before anything is armed, otherwise there is
     * nothing on screen to aim with: the build key is what arms a spot, so the shape
     * has to be visible at the crosshair long before that. Armed takes precedence over
     * the selected spot so the preview follows the build that is actually running
     * rather than jumping back to wherever the player is now looking.
     */
    private Wither previewWither() {
        if (currentWither != null) return currentWither;
        if (!manualMode()) return null;

        Direction up = witherOrientation.get().direction();
        BlockPos foot = manualWitherFoot(up);
        if (foot == null) return null;

        for (Direction cross : crossCandidates(up)) {
            Wither candidate = new Wither().set(foot, up, cross);
            if (isValidWitherSpawn(candidate)) return candidate;
        }
        return null;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!renderPreview.get()) return;

        // Air placing has no crosshair to aim with, so mark the ray cast's hit. It is
        // drawn even when the structure does not fit there
        if (manualMode() && airPlace.get()) {
            BlockPos airTarget = selectedBlock();
            if (airTarget != null) {
                event.renderer.box(airTarget, airPlaceColor.get(), airPlaceColor.get(), ShapeMode.Lines, 0);
            }
        }

        switch (buildMode.get()) {
            case Wither -> {
                Wither preview = previewWither();
                if (preview != null) {
                    for (int stage = 0; stage <= 3; stage++) {
                        event.renderer.box(preview.pos(stage), soulSandColor.get(), soulSandColor.get(), shapeMode.get(), 0);
                    }
                    for (int stage = 4; stage <= 6; stage++) {
                        renderSkullBox(event, preview.pos(stage));
                    }
                }
            }
            case IronGolem -> {
                Direction up = ironGolemUp();
                Direction cross = ironGolemCross(up);
                BlockPos base = selectedFoot(ironGolemFootprint(up, cross), ironGolemPlacedOffsets(up, cross), true);
                if (base != null) {
                    IronGolem golem = new IronGolem();
                    golem.base = base;
                    golem.up = up;
                    golem.cross = cross;

                    for (BlockPos pos : golem.ironPositions()) {
                        event.renderer.box(pos, ironColor.get(), ironColor.get(), shapeMode.get(), 0);
                    }
                    event.renderer.box(golem.pumpkin(), pumpkinColor.get(), pumpkinColor.get(), shapeMode.get(), 0);

                    // The four arm-adjacent cells decide whether the spawn works, and
                    // manual placement is exactly when the player is choosing a spot by eye
                    if (manualMode()) {
                        for (BlockPos pos : golem.clearancePositions()) {
                            event.renderer.box(pos, ironColor.get(), ironColor.get(), ShapeMode.Lines, 0);
                        }
                    }
                }
            }
            case SnowGolem -> {
                BlockPos base = selectedFoot(snowGolemFootprint(), true);
                if (base != null) {
                    event.renderer.box(base, snowColor.get(), snowColor.get(), shapeMode.get(), 0);
                    event.renderer.box(base.above(), snowColor.get(), snowColor.get(), shapeMode.get(), 0);
                    event.renderer.box(base.above(2), pumpkinColor.get(), pumpkinColor.get(), shapeMode.get(), 0);
                }
            }
            case CopperGolem -> {
                BlockPos foot = selectedFoot(copperGolemFootprint(), true);
                if (foot != null) {
                    event.renderer.box(foot, copperColor.get(), copperColor.get(), shapeMode.get(), 0);          // copper base
                    event.renderer.box(foot.above(), pumpkinColor.get(), pumpkinColor.get(), shapeMode.get(), 0);  // pumpkin
                }
            }
            case Creaking -> {
                List<int[]> footprint = creakingFootprint();
                BlockPos foot = selectedFoot(footprint, true);
                if (foot != null) {
                    event.renderer.box(foot.offset(footprint.get(0)[0], footprint.get(0)[1], footprint.get(0)[2]),
                        logColor.get(), logColor.get(), shapeMode.get(), 0);           // log
                    event.renderer.box(foot.offset(footprint.get(1)[0], footprint.get(1)[1], footprint.get(1)[2]),
                        heartColor.get(), heartColor.get(), shapeMode.get(), 0);       // creaking heart
                    event.renderer.box(foot.offset(footprint.get(2)[0], footprint.get(2)[1], footprint.get(2)[2]),
                        logColor.get(), logColor.get(), shapeMode.get(), 0);           // log
                }
            }
        }
    }

    private void place(BlockPos pos, FindItemResult item) {
        if (airPlace.get()) {
            airPlaceBlock(pos, item);
        } else {
            BlockUtils.place(pos, item, 0, false);
        }
    }

    private void airPlaceBlock(BlockPos pos, FindItemResult item) {
        airPlaceBlock(pos, item, Direction.UP);
    }

    private void airPlaceBlock(BlockPos pos, FindItemResult item, Direction face) {
        if (!item.found()) return;

        if (item.isOffhand()) {
            // Already in the off-hand, so place straight from it. Selecting it as the carried
            // slot throws: SlotUtils.OFFHAND is 40 and Inventory.setSelectedSlot rejects
            // anything outside the hotbar. Skipping the swap dance is also what Meteor's own
            // BlockUtils.place does for this case.
            mc.player.connection.send(new ServerboundUseItemOnPacket(InteractionHand.OFF_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false), 0));
            mc.player.swing(InteractionHand.OFF_HAND);
            return;
        }

        int previousSlot = mc.player.getInventory().getSelectedSlot();
        mc.player.getInventory().setSelectedSlot(item.slot());
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(item.slot()));

        BlockHitResult bhr = new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false);

        mc.player.connection.send(new ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));

        mc.player.connection.send(new ServerboundUseItemOnPacket(
            InteractionHand.OFF_HAND, bhr, 0));

        // Swap back
        mc.player.connection.send(new ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));

        mc.player.swing(InteractionHand.MAIN_HAND);

        mc.player.getInventory().setSelectedSlot(previousSlot);
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(previousSlot));
    }

    private void renderSkullBox(Render3DEvent event, BlockPos pos) {
        event.renderer.box(pos.getX() + 0.2, pos.getY() + 0.2, pos.getZ() + 0.2,
            pos.getX() + 0.8, pos.getY() + 0.7, pos.getZ() + 0.8,
            skullColor.get(), skullColor.get(), shapeMode.get(), 0);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (buildMode.get() != lastBuildMode) {
            lastBuildMode = buildMode.get();
            // Selecting Wither mid-session used to kick off a build immediately: the
            // stage below ran with whatever currentWither was left over, or searched
            // for one on its own.
            startWither();
        }

        if (buildMode.get() != BuildMode.Wither) return;

        if (currentWither == null) {
            // Manual placement arms the spot itself, so an unarmed Wither is waiting
            // for the build key rather than for the automatic search.
            if (manualMode()) return;

            if (witherTicksWaited < witherPlaceDelay.get()) {
                witherTicksWaited++;
                return;
            }
            currentWither = findValidWitherSpawn();
            if (currentWither == null) {
                error("No valid Wither spawn location found within range.");
                toggle();
                return;
            }
            witherTicksWaited = 0;
        }

        FindItemResult soulSand = findSoulBlock();
        FindItemResult witherSkull = InvUtils.findInHotbar(Items.WITHER_SKELETON_SKULL);
        if (!soulSand.found() || !witherSkull.found()) {
            error("Not enough resources in hotbar.");
            // Disarm rather than retrying every tick, which would repeat the error
            // until the player pressed the key again. Manual placement stays active.
            startWither();
            if (!manualMode()) toggle();
            return;
        }

        int delay = witherPlaceDelay.get();
        if (delay == 0) {
            for (int i = 0; i <= 6; i++) placeWitherBlock(soulSand, witherSkull, i);
            // Disarm before the toggle check, same as the staged branch below. Leaving
            // it armed keeps stage at 0, so the next tick runs this loop again and
            // keeps rebuilding in manual mode it would repeat every tick forever.
            startWither();
            if (autoToggle.get() && !manualMode()) toggle();
        } else {
            if (blockTicksWaited < delay) {
                blockTicksWaited++;
                return;
            }
            blockTicksWaited = 0;
            if (currentWither.stage <= 6) {
                placeWitherBlock(soulSand, witherSkull, currentWither.stage);
                currentWither.stage++;
            }
            if (currentWither.stage > 6) {
                // Disarm so the next manual build starts from a clean stage instead of
                // re-reporting a Wither that was already finished.
                startWither();
                if (autoToggle.get() && !manualMode()) toggle();
            }
        }
    }

    /**
     * Arms a Wither at the spot the player picked instead of searching for one. The
     * staged placement that follows is the same code the automatic path uses, so the
     * place delay and rotation behave identically.
     */
    private void manualStartWither() {
        Direction up = witherOrientation.get().direction();
        BlockPos foot = manualWitherFoot(up);
        if (foot == null) {
            error(airPlace.get()
                ? "No clear space in reach to build the Wither on."
                : "No clear space to build the Wither on. Try a shorter placement distance.");
            return;
        }

        for (Direction cross : crossCandidates(up)) {
            Wither candidate = new Wither().set(foot, up, cross);
            if (!isValidWitherSpawn(candidate)) continue;

            FindItemResult soulSand = findSoulBlock();
            FindItemResult witherSkull = InvUtils.findInHotbar(Items.WITHER_SKELETON_SKULL);
            if (!soulSand.found() || !witherSkull.found()) {
                error("Not enough resources in hotbar.");
                return;
            }

            currentWither = candidate;
            witherTicksWaited = 0;
            blockTicksWaited = 0;
            // Not "placed" -> the T is still staged over the coming ticks, and the last
            // skull is what actually spawns it.
            info("Building Wither...");
            return;
        }

        error("That spot cannot hold a Wither. it needs clear space for the whole shape.");
    }

    private void placeWitherBlock(FindItemResult soulSand, FindItemResult skull, int stage) {
        BlockPos pos = currentWither.pos(stage);
        if (pos == null) return;
        FindItemResult item = (stage < 4) ? soulSand : skull;
        if (witherRotate.get()) {
            Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), () -> place(pos, item));
        } else {
            place(pos, item);
        }
    }

    /**
     * The T's own "up" the side the pumpkin sits on. Lying takes it from the
     * player's horizontal facing so the golem lies the way you are looking.
     */
    private Direction ironGolemUp() {
        return switch (ironGolemOrientation.get()) {
            case Upright -> Direction.UP;
            case UpsideDown -> Direction.DOWN;
            case Lying -> mc.player.getDirection();
        };
    }

    /**
     * The axis the two arms run along. It has to be perpendicular to {@code up} and
     * horizontal either way: when the T is upright the arms follow your facing, and
     * when it is lying they run along the other horizontal axis so the plane stays
     * vertical.
     *
     * <p>Derived from {@code up}'s own axis rather than from the facing -> the facing
     * is only the right answer while the T is upright, and picking it otherwise would
     * return a direction parallel to {@code up} and fold the T flat.
     */
    private Direction ironGolemCross(Direction up) {
        // The arms run across the player so the golem faces them. Lying has already
        // committed the T's own up to the player's facing, so its arms are simply the
        // other horizontal axis; upright and upside-down have no such constraint, so
        // they take the axis across the facing.
        //
        // Derived from the reference vector's axis rather than from the facing directly:
        // for an upright golem the facing is the reference, and picking the axis from
        // up instead would return a vertical direction and fold the T flat.
        Direction reference = up.getAxis() == Direction.Axis.Y ? mc.player.getDirection() : up;
        return reference.getAxis() == Direction.Axis.X ? Direction.SOUTH : Direction.WEST;
    }

    /**
     * The golem's footprint relative to its base block, derived from the orientation
     * so the search and the build can never disagree about the shape.
     */
    private List<int[]> ironGolemFootprint(Direction up, Direction cross) {
        return ironGolemOffsets(new IronGolem().set(BlockPos.ZERO, up, cross), true);
    }

    /**
     * The blocks actually placed: four iron then the pumpkin, no clearance cells.
     */
    private List<int[]> ironGolemPlacedOffsets(Direction up, Direction cross) {
        return ironGolemOffsets(new IronGolem().set(BlockPos.ZERO, up, cross), false);
    }

    private List<int[]> ironGolemOffsets(IronGolem golem, boolean includeClearance) {
        List<int[]> footprint = new ArrayList<>();
        for (BlockPos pos : golem.ironPositions()) {
            footprint.add(new int[]{(int) pos.getX(), (int) pos.getY(), (int) pos.getZ()});
        }
        // ironPositions() leaves the pumpkin out so no caller can place it before the
        // iron it sits on, but it is still a cell that gets built into
        BlockPos pumpkin = golem.pumpkin();
        footprint.add(new int[]{(int) pumpkin.getX(), (int) pumpkin.getY(), (int) pumpkin.getZ()});
        if (!includeClearance) return footprint;

        // The four cells around the arms need air too, vanilla refuses to spawn the
        // golem otherwise, and buildIronGolem rejects a spot that fails it. Searching
        // on them here means the auto search skips those spots instead of picking one
        // and then erroring out.
        for (BlockPos pos : golem.clearancePositions()) {
            footprint.add(new int[]{(int) pos.getX(), (int) pos.getY(), (int) pos.getZ()});
        }
        return footprint;
    }

    private void buildIronGolem() {
        Direction up = ironGolemUp();
        Direction cross = ironGolemCross(up);

        BlockPos base = selectedFoot(ironGolemFootprint(up, cross), ironGolemPlacedOffsets(up, cross), false);
        if (base == null) {
            error(manualMode() ? "Look at the block you want to build on." : "Not enough clear space for an iron golem nearby.");
            return;
        }

        IronGolem golem = new IronGolem();
        golem.base = base;
        golem.up = up;
        golem.cross = cross;

        // Checked up front rather than after placing: the arms have to be placed into
        // air, and vanilla will not spawn the golem if a snow layer, plant or water
        // took one of these cells in the meantime.
        for (BlockPos clearance : golem.clearancePositions()) {
            if (!mc.level.getBlockState(clearance).isAir()) {
                error("The four spaces around the golem arms must be clear air.");
                return;
            }
        }

        FindItemResult iron = InvUtils.findInHotbar(Items.IRON_BLOCK);
        if (!iron.found()) {
            error("No iron blocks in hotbar.");
            return;
        }
        FindItemResult pumpkin = findPumpkin();
        if (!pumpkin.found()) {
            error("No carved pumpkin or jack o'lantern in hotbar.");
            return;
        }

        // Upside down the base is the topmost cell, so the body hanging beneath it has no face
        // to be placed against until the base exists. Build base-first in that case only; every
        // other orientation already starts with the base.
        List<BlockPos> ironOrder = new ArrayList<>(golem.ironPositions());
        if (up == Direction.DOWN) {
            BlockPos body = golem.body();
            ironOrder.remove(body);
            ironOrder.add(0, body);
        }

        for (BlockPos pos : ironOrder) place(pos, iron);
        // Lastly a pumpkin, that is what triggers the spawn, and placing it over
        // an incomplete T just does nothing.
        place(golem.pumpkin(), pumpkin);
        info("Iron golem built.");
    }

    private void buildSnowGolem() {
        BlockPos base = selectedFoot(snowGolemFootprint(), false);
        if (base == null) {
            error(manualMode() ? "Look at the block you want to build on." : "Not enough clear space for a snow golem nearby.");
            return;
        }

        FindItemResult snow = InvUtils.findInHotbar(Items.SNOW_BLOCK);
        if (!snow.found()) {
            error("No snow blocks in hotbar.");
            return;
        }
        FindItemResult pumpkin = findPumpkin();
        if (!pumpkin.found()) {
            error("No carved pumpkin or jack o'lantern in hotbar.");
            return;
        }

        place(base, snow);
        place(base.above(), snow);
        place(base.above(2), pumpkin);
        info("Snow golem built.");
    }

    private void buildCopperGolem() {
        Direction facing = mc.player.getDirection();
        BlockPos preferredFoot = mc.player.blockPosition().relative(facing, placementDistance.get());

        BlockPos foot = selectedFoot(copperGolemFootprint(), false);
        if (foot == null) {
            error(manualMode() ? "Look at the block you want to build on." : "Not enough clear space for copper golem nearby.");
            return;
        }

        if (skipIfOccupied.get() && foot.equals(preferredFoot)
            && !mc.level.getBlockState(preferredFoot).isAir()) {
            warning("Foot position is already occupied, golem may already exist.");
            return;
        }

        FindItemResult copper = InvUtils.findInHotbar(copperBlock.get());
        if (!copper.found()) {
            error("No copper block in hotbar.");
            return;
        }
        FindItemResult pumpkin = findPumpkin();
        if (!pumpkin.found()) {
            error("No carved pumpkin or jack o'lantern in hotbar.");
            return;
        }

        place(foot, copper);
        place(foot.above(), pumpkin);
        info("Copper golem built.");
    }

    private void buildCreaking() {
        // Manual mode keeps the module active across builds, so onActivate's reset never
        // runs between them and a failure would otherwise silence every later report.
        // Only clear it once nothing is still queued, so a build that is still settling
        // keeps the failure state of the placements it is still waiting on.
        if (creakingPending == 0) creakingFailed = false;

        BlockPos foot = selectedFoot(creakingFootprint(), false);
        if (foot == null) {
            error(manualMode() ? "Look at the block you want to build on." : "Not enough clear space for the creaking heart nearby.");
            return;
        }

        FindItemResult heart = InvUtils.findInHotbar(Items.CREAKING_HEART);
        if (!heart.found()) {
            error("No creaking heart in hotbar.");
            return;
        }

        FindItemResult log = InvUtils.findInHotbar(Items.PALE_OAK_LOG);
        if (!log.found()) {
            error("No pale oak logs in hotbar.");
            return;
        }

        Direction.Axis axis = creakingAxis.get();
        Direction face = axis == Direction.Axis.X ? Direction.EAST
            : axis == Direction.Axis.Z ? Direction.SOUTH
              : Direction.UP;

        // Both the log and the heart derive their AXIS from the face that was clicked,
        // and the normal place path gives us no control over that face: Meteor picks its
        // own side via getPlaceSide and falls back to DOWN, which resolves to axis Y for
        // every cell here. So logs and heart would all come out axis-Y and the heart
        // would never enable, because its neighbours along Y are ground and air. Only
        // air-place passes an explicit face and can therefore orient them. A Y pillar
        // happens to work either way, since DOWN resolves to the axis it already needs.
        if (axis != Direction.Axis.Y && !airPlace.get()) {
            error("A horizontal creaking axis needs air-place enabled, otherwise the logs and heart cannot be turned to point along it.");
            creakingFailed = true;
            return;
        }

        List<int[]> footprint = creakingFootprint();

        // Footprint is always log, heart, log, so the heart sits at index 1.
        BlockPos logPosA = foot.offset(footprint.get(0)[0], footprint.get(0)[1], footprint.get(0)[2]);
        BlockPos heartPos = foot.offset(footprint.get(1)[0], footprint.get(1)[1], footprint.get(1)[2]);
        BlockPos logPosB = foot.offset(footprint.get(2)[0], footprint.get(2)[1], footprint.get(2)[2]);

        if (!creakingPlace(logPosA, log, face)) {
            creakingFailed = true;
            return;
        }
        if (!creakingPlace(heartPos, heart, face)) {
            creakingFailed = true;
            return;
        }
        if (!creakingPlace(logPosB, log, face)) {
            creakingFailed = true;
            return;
        }

        // A synchronous build is already finished here, so only report it and let
        // onActivate do the single toggle-off. Deferred builds settle in their last
        // placement callback instead. Reporting twice would be harmless, toggling
        // twice would switch the module straight back on.
        if (creakingPending == 0) reportCreaking();
    }

    /**
     * Logs the outcome of a creaking build. A failed build already reported the
     * failing position, so it stays quiet here.
     */
    private void reportCreaking() {
        if (creakingFailed) return;

        info("Creaking heart built on the " + creakingAxis.get().name().toLowerCase() + " axis.");
    }

    /**
     * Called once every deferred creaking placement has run, where onActivate could
     * not toggle because placements were still outstanding. Reports the build and
     * switches off.
     */
    private void settleCreaking() {
        // The player may have switched the module off while placements were still
        // queued. The callbacks still run (nothing cancels them), but activating a
        // deactivated module here would silently switch it back on.
        if (!isActive()) return;

        reportCreaking();
        if (!manualMode() && autoToggle.get()) toggle();
    }

    private boolean creakingPlace(BlockPos pos, FindItemResult item, Direction face) {
        if (!item.found()) {
            error("Failed to place at " + pos.toShortString() + ".");
            return false;
        }

        BlockState state = mc.level.getBlockState(pos);
        if (!state.isAir() && !state.canBeReplaced()) {
            error("Failed to place at " + pos.toShortString() + ".");
            return false;
        }

        if (creakingRotate.get()) {
            // Rotations.queue here and each callback runs once its own movement packet
            // has been sent, so the placement is genuinely deferred: count it and let
            // the last one report the build instead of claiming success immediately.
            creakingPending++;
            Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), () -> {
                // Always decrement, so a build abandoned by deactivating mid-flight
                // still drains to zero rather than stranding the count.
                boolean active = isActive();
                if (active && !placeCreakingBlock(pos, item, face)) creakingFailed = true;
                if (--creakingPending == 0 && active) settleCreaking();
            });
        } else {
            if (!placeCreakingBlock(pos, item, face)) creakingFailed = true;
        }
        return true;
    }

    /**
     * Honours air-place, so disabling it actually switches to the normal place path.
     * Returns whether the placement was accepted, so a rejected build is reported as
     * failed rather than announced as a finished creaking heart.
     */
    private boolean placeCreakingBlock(BlockPos pos, FindItemResult item, Direction face) {
        if (airPlace.get()) {
            airPlaceBlock(pos, item, face);
            return true;
        }

        // The face offset is Meteor's click target; the third argument is the slot
        // switch count and 0 means do not switch away from the found item.
        boolean placed = BlockUtils.place(pos, item, 0, false);
        if (!placed) error("Failed to place at " + pos.toShortString() + ".");
        return placed;
    }
}
