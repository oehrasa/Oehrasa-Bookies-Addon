package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.BoneMealItem;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import baritone.api.BaritoneAPI;
import java.util.HashSet;
import java.util.Set;
import java.util.*;

public class AutoMoss extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgMoss    = settings.createGroup("Moss");
    private final SettingGroup sgTrees   = settings.createGroup("Trees");
    private final SettingGroup sgRoaming = settings.createGroup("Roaming");

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("The range to search for blocks to bonemeal.")
        .defaultValue(4.5)
        .min(1)
        .sliderMax(6)
        .build()
    );

    private final Setting<Integer> mossSpreadCooldown = sgMoss.add(new IntSetting.Builder()
        .name("moss-cooldown")
        .description("Cooldown in ticks before bone mealing the same moss block again.")
        .defaultValue(100)
        .min(20)
        .sliderMax(200)
        .build()
    );

    private final Setting<Boolean> makeTrees = sgTrees.add(new BoolSetting.Builder()
        .name("make-trees")
        .description("Use bone meal on azalea bushes and saplings to grow them into trees.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> treeChance = sgTrees.add(new IntSetting.Builder()
        .name("tree-chance")
        .description("Probability from 0 to 100% to grow a trees (for saplings).")
        .defaultValue(2)
        .range(0, 100)
        .sliderRange(0, 100)
        .build()
    );

    private final Setting<Integer> azaleaTreeFraction = sgTrees.add(new IntSetting.Builder()
        .name("azalea-tree-fraction")
        .description("X/10 chance to bonemeal an azalea bush (0-10).")
        .defaultValue(4)
        .min(0)
        .max(10)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Boolean> inventoryAllow = sgGeneral.add(new BoolSetting.Builder()
        .name("inventory-allow")
        .description("Take bone meal from inventory when hotbar is empty.")
        .defaultValue(true)
        .build()
    );

    // Moss seeding
    private final Setting<Boolean> placeMoss = sgMoss.add(new BoolSetting.Builder()
        .name("place-moss")
        .description("When no moss is in range, place ONE moss block at your feet to start spreading.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> placeMossDelay = sgMoss.add(new IntSetting.Builder()
        .name("place-moss-delay")
        .description("Ticks to wait after placing a seed moss block before placing another.")
        .defaultValue(40)
        .min(5)
        .sliderMax(200)
        .visible(placeMoss::get)
        .build()
    );

    private final Setting<Boolean> requireSkyAccess = sgMoss.add(new BoolSetting.Builder()
        .name("require-sky-access")
        .description("Only bonemeal moss blocks that have open sky above them (skip buried ones).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> skyAccessDepth = sgMoss.add(new IntSetting.Builder()
        .name("sky-access-depth")
        .description("Max thickness of solid blocks allowed above a target before it's considered buried.")
        .defaultValue(5)
        .min(1)
        .sliderMax(20)
        .visible(requireSkyAccess::get)
        .build()
    );

    private final Setting<Boolean> avoidObstruction = sgMoss.add(new BoolSetting.Builder()
        .name("avoid-obstruction")
        .description("Skip moss blocks that have a torch, sign, or fluid directly above them.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay between bone meal uses in ticks.")
        .defaultValue(2)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<Integer> maxUsesPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("max-uses-per-tick")
        .description("Maximum number of bone meal uses per tick.")
        .defaultValue(1)
        .min(1)
        .sliderMax(5)
        .build()
    );

    private final Setting<Boolean> roamEnabled = sgRoaming.add(new BoolSetting.Builder()
        .name("roam-enabled")
        .description("Automatically walk to new areas using Baritone when no moss is in range.")
        .defaultValue(false)
        .build()
    );

    private final Setting<List<Block>> pathfindBlocks = sgRoaming.add(new BlockListSetting.Builder()
        .name("pathfind-blocks")
        .description("Surface block types to walk on (dirt, stone, grass, etc).")
        .defaultValue(new ArrayList<>(List.of(Blocks.STONE, Blocks.GRASS_BLOCK, Blocks.DIRT)))
        .visible(roamEnabled::get)
        .build()
    );

    private final Setting<Integer> roamScanRadius = sgRoaming.add(new IntSetting.Builder()
        .name("roam-scan-radius")
        .description("Horizontal radius (blocks) to search for surface targets.")
        .defaultValue(16)
        .min(2)
        .sliderMax(48)
        .visible(roamEnabled::get)
        .build()
    );

    private final Setting<Integer> roamVerticalScan = sgRoaming.add(new IntSetting.Builder()
        .name("roam-vertical-scan")
        .description("Vertical range (blocks up/down) to search for the surface block in each column.")
        .defaultValue(6)
        .min(1)
        .sliderMax(20)
        .visible(roamEnabled::get)
        .build()
    );

    private final Setting<Boolean> surfaceOnly = sgRoaming.add(new BoolSetting.Builder()
        .name("surface-only")
        .description("Only walk to blocks open to the sky. Prevents going into caves.")
        .defaultValue(true)
        .visible(roamEnabled::get)
        .build()
    );

    private final Setting<Integer> maxDescend = sgRoaming.add(new IntSetting.Builder()
        .name("max-descend")
        .description("Ignore surface targets more than this many blocks BELOW you.")
        .defaultValue(4)
        .min(1)
        .sliderMax(32)
        .visible(roamEnabled::get)
        .build()
    );

    private final Setting<Integer> visitedRadius = sgRoaming.add(new IntSetting.Builder()
        .name("visited-radius")
        .description("Columns within this radius of a reached target count as visited (won't immediately re-target).")
        .defaultValue(3)
        .min(0)
        .sliderMax(10)
        .visible(roamEnabled::get)
        .build()
    );

    private final Setting<Integer> roamRestartCooldown = sgRoaming.add(new IntSetting.Builder()
        .name("roam-restart-cd")
        .description("Ticks to wait after arriving before heading to the next target.")
        .defaultValue(5)
        .min(0)
        .sliderMax(100)
        .visible(roamEnabled::get)
        .build()
    );

    private int delayTimer = 0;
    private final Map<BlockPos, Integer> recentlyUsedMoss = new HashMap<>();
    private int placeMossTimer = 0;   // cooldown between placing seed moss blocks

    // Baritone roaming state
    private boolean baritoneRunning = false;
    private BlockPos currentGotoTarget = null;
    private int gotoRestartCooldown = 0;
    private int baritoneStallTicks = 0;
    private static final int GOTO_GRACE_TICKS = 10; // ticks before considering arrival
    private final Set<Long> visitedColumns = new HashSet<>();

    public AutoMoss() {
        super(Addon.CATEGORY, "Auto-Moss", "Automatically uses bone meal <the moss must spreads!>.");
    }

    @Override
    public void onActivate() {
        baritoneRunning = false;
        currentGotoTarget = null;
        gotoRestartCooldown = 0;
        baritoneStallTicks = 0;
        visitedColumns.clear();
    }

    @Override
    public void onDeactivate() {
        if (baritoneRunning) {
            if (mc.player != null) mc.player.networkHandler.sendChatCommand("stop");
            baritoneRunning = false;
        }
        visitedColumns.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (delayTimer > 0) {
            delayTimer--;
            return;
        }
        if (mc.player == null || mc.world == null) return;

        if (roamEnabled.get()) {
            boolean pathing = BaritoneAPI.getProvider().getPrimaryBaritone()
                .getPathingBehavior().isPathing();

            if (baritoneRunning) {
                if (pathing) {
                    baritoneStallTicks = 0;
                } else if (++baritoneStallTicks >= GOTO_GRACE_TICKS) {
                    // Arrived at target
                    if (currentGotoTarget != null) markVisited(currentGotoTarget.down());
                    baritoneRunning = false;
                    baritoneStallTicks = 0;
                    gotoRestartCooldown = roamRestartCooldown.get();
                }
            } else {
                // Not currently pathing
                if (gotoRestartCooldown > 0) {
                    gotoRestartCooldown--;
                } else {
                    // Only start roaming if there is no moss within range (and seeding is either off or on cooldown)
                    if (!isMossInRange() && (placeMoss.get() ? placeMossTimer <= 0 : true)) {
                        startBaritoneGoto();
                    }
                }
            }
        }

        // Moss seeding (before regular bonemealing)
        if (placeMossTimer > 0) placeMossTimer--;
        if (placeMoss.get() && !isMossInRange() && placeMossTimer <= 0) {
            trySeedMoss();
        }

        // Update moss cooldowns
        updateMossCooldowns();

        // Check if player has bone meal
        int boneMealSlot = findBoneMealSlot();
        if (boneMealSlot == -1) return;

        int uses = 0;
        List<BlockPos> targets = findTargets();

        for (BlockPos blockPos : targets) {
            if (uses >= maxUsesPerTick.get()) break;

            BlockState state = mc.world.getBlockState(blockPos);
            Block block = state.getBlock();
            boolean isMoss = block.getTranslationKey().contains("moss_block");

            // Skip moss blocks on cooldown
            if (isMoss && recentlyUsedMoss.containsKey(blockPos)) {
                continue;
            }

            if (BoneMealItem.useOnFertilizable(mc.player.getInventory().getStack(boneMealSlot), mc.world, blockPos)) {
                Vec3d hitPos = new Vec3d(blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5);
                BlockHitResult hit = new BlockHitResult(hitPos, Direction.UP, blockPos, false);

                int prevSelectedSlot = mc.player.getInventory().getSelectedSlot();
                mc.player.getInventory().setSelectedSlot(boneMealSlot);

                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);

                mc.player.getInventory().setSelectedSlot(prevSelectedSlot);

                // Add moss blocks to cooldown map
                if (isMoss) {
                    recentlyUsedMoss.put(new BlockPos(blockPos), mossSpreadCooldown.get());
                }

                uses++;
                delayTimer = delay.get();
            }
        }
    }

    private void startBaritoneGoto() {
        if (mc.player == null) return;

        BlockPos surface = pickRoamTarget();
        if (surface == null) {
            gotoRestartCooldown = 40; // nothing nearby, wait a bit
            return;
        }

        // Target the air block on top of the surface so Baritone walks on it, not digs
        BlockPos stand = surface.up();
        currentGotoTarget = stand;

        mc.player.networkHandler.sendChatMessage("#goto " + stand.getX() + " " + stand.getY() + " " + stand.getZ());
        baritoneRunning = true;
        baritoneStallTicks = 0;
    }

    private void stopBaritone() {
        if (baritoneRunning && mc.player != null) {
            mc.player.networkHandler.sendChatCommand("stop");
            baritoneRunning = false;
        }
    }

    /**
     * Chooses the farthest unvisited surface column within scanning range.
     */
    private BlockPos pickRoamTarget() {
        List<BlockPos> surfaces = findSurfaceTargets();
        if (surfaces.isEmpty()) return null;

        BlockPos origin = mc.player.getBlockPos();
        BlockPos farthest = null;
        double maxDistSq = -1;

        for (BlockPos p : surfaces) {
            if (visitedColumns.contains(columnKey(p))) continue;
            double d = p.getSquaredDistance(origin);
            if (d > maxDistSq) {
                maxDistSq = d;
                farthest = p;
            }
        }

        if (farthest == null) {
            // All columns visited, clear memory and pick the farthest again
            visitedColumns.clear();
            for (BlockPos p : surfaces) {
                double d = p.getSquaredDistance(origin);
                if (d > maxDistSq) {
                    maxDistSq = d;
                    farthest = p;
                }
            }
        }

        return farthest;
    }

    /**
     * Scans the area around the player and returns every surface column's top
     * matching block that is outdoor and walkable.
     */
    private List<BlockPos> findSurfaceTargets() {
        List<BlockPos> out = new ArrayList<>();
        List<Block> wanted = pathfindBlocks.get();
        if (wanted == null || wanted.isEmpty()) return out;

        BlockPos origin = mc.player.getBlockPos();
        int horiz = roamScanRadius.get();
        int vert  = roamVerticalScan.get();
        int floorY = origin.getY() - 1;
        int minTargetY = floorY - maxDescend.get();

        for (int dx = -horiz; dx <= horiz; dx++) {
            for (int dz = -horiz; dz <= horiz; dz++) {
                for (int dy = vert; dy >= -vert; dy--) {
                    BlockPos p = origin.add(dx, dy, dz);
                    Block b = mc.world.getBlockState(p).getBlock();

                    if (!wanted.contains(b)) continue;
                    if (p.getY() < minTargetY) continue;
                    if (!mc.world.getBlockState(p.up()).isAir()) continue;
                    if (surfaceOnly.get() && !isOutdoorSurface(p)) continue;

                    out.add(p);
                    break; // surface block for this column found, next column
                }
            }
        }
        return out;
    }

    private boolean isOutdoorSurface(BlockPos pos) {
        if (mc.world == null) return false;
        BlockPos above = pos.up();
        // Main: sky visibility check
        if (mc.world.isSkyVisible(above)) return true;

        // Fallback: scan upward; any solid block overhead means it's covered
        for (int dy = 1; dy <= 64; dy++) {
            BlockState st = mc.world.getBlockState(pos.up(dy));
            if (st.isAir()) continue;
            if (!st.getFluidState().isEmpty()) continue;
            String n = st.getBlock().getTranslationKey().toLowerCase();
            boolean passable = n.contains("grass") || n.contains("fern") || n.contains("flower")
                || n.contains("vine") || n.contains("sapling") || n.contains("moss_carpet")
                || n.contains("snow") || n.contains("leaves");
            if (passable) continue;
            return false;
        }
        return true;
    }

    private void markVisited(BlockPos surface) {
        if (surface == null) return;
        int r = visitedRadius.get();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                visitedColumns.add(columnKey(surface.add(dx, 0, dz)));
            }
        }
    }

    private static long columnKey(BlockPos p) {
        return (((long) p.getX()) << 32) ^ (p.getZ() & 0xffffffffL);
    }

    private void updateMossCooldowns() {
        Iterator<Map.Entry<BlockPos, Integer>> it = recentlyUsedMoss.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = it.next();
            int cooldown = entry.getValue() - 1;
            if (cooldown <= 0) {
                it.remove();
            } else {
                entry.setValue(cooldown);
            }
        }
    }

    private List<BlockPos> findTargets() {
        List<BlockPos> targets = new ArrayList<>();
        if (mc.player == null || mc.world == null) return targets;

        double rangeSq = range.get() * range.get();
        BlockPos playerPos = mc.player.getBlockPos();

        for (int x = (int) -range.get(); x <= range.get(); x++) {
            for (int y = (int) -range.get(); y <= range.get(); y++) {
                for (int z = (int) -range.get(); z <= range.get(); z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    if (pos.getSquaredDistance(playerPos) > rangeSq) continue;
                    if (!hasLineOfSight(pos)) continue;

                    BlockState state = mc.world.getBlockState(pos);
                    Block block = state.getBlock();
                    String blockName = block.getTranslationKey().toLowerCase();

                    // Check for tree growables if make-trees is enabled
                    if (makeTrees.get()) {
                        boolean isAzalea = blockName.contains("azalea") && !blockName.contains("tree");
                        boolean isSapling = blockName.contains("sapling");

                        if (isAzalea || isSapling) {
                            if (isAzalea) {
                                // Use the azalea tree fraction
                                if (mc.world.random.nextInt(10) < azaleaTreeFraction.get())
                                    targets.add(pos);
                            } else {
                                if (mc.world.random.nextInt(100) < treeChance.get())
                                    targets.add(pos);
                            }
                            continue;
                        }
                    }

                    // Check for moss blocks with valid neighbours and sky access
                    boolean isMoss = blockName.contains("moss_block");
                    if (isMoss && hasValidNeighbor(pos)) {
                        // sky access and obstruction checks
                        if (requireSkyAccess.get() && !hasSkyAccess(pos)) continue;
                        if (avoidObstruction.get() && isObstructedAbove(pos)) continue;
                        targets.add(pos);
                    }
                }
            }
        }
        return targets;
    }

    private boolean hasValidNeighbor(BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = pos.offset(dir);
            BlockState neighborState = mc.world.getBlockState(neighborPos);
            Block neighborBlock = neighborState.getBlock();
            String blockName = neighborBlock.getTranslationKey().toLowerCase();

            // Skip if the neighbour is in our exclusion list
            if (blockName.contains("azalea") ||
                blockName.contains("tall_grass") ||
                blockName.contains("grass") && !blockName.contains("block") ||
                blockName.contains("moss_block") ||
                blockName.contains("moss_carpet")) {
                continue;
            }
            return true;
        }
        return false;
    }

    private boolean isObstructedAbove(BlockPos pos) {
        BlockState above = mc.world.getBlockState(pos.up());
        if (!above.getFluidState().isEmpty()) return true;
        String n = above.getBlock().getTranslationKey().toLowerCase();
        return n.contains("torch") || n.contains("lantern") || n.contains("sign")
            || n.contains("lava") || n.contains("water");
    }

    /**
     * Scan upward from the moss block; if a solid block is found within
     * skyAccessDepth, the moss is considered buried and should be skipped.
     */
    private boolean hasSkyAccess(BlockPos pos) {
        if (!requireSkyAccess.get()) return true;
        int depth = skyAccessDepth.get();
        for (int dy = 1; dy <= depth; dy++) {
            BlockState state = mc.world.getBlockState(pos.up(dy));
            if (state.isAir()) continue;
            if (!state.getFluidState().isEmpty()) return false;
            // Vegetation that doesn't block light/sky is okay
            String n = state.getBlock().getTranslationKey().toLowerCase();
            if (n.contains("grass") || n.contains("fern") || n.contains("flower")
                || n.contains("vine") || n.contains("sapling") || n.contains("moss_carpet")
                || n.contains("snow") || n.contains("leaves")) continue;
            return false;
        }
        return true;
    }

    private boolean hasLineOfSight(BlockPos pos) {
        if (mc.player == null || mc.world == null) return false;
        Vec3d eyePos = mc.player.getEyePos();
        Vec3d blockPos = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        RaycastContext context = new RaycastContext(
            eyePos, blockPos,
            RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player
        );
        BlockHitResult result = mc.world.raycast(context);
        return result.getBlockPos().equals(pos);
    }

    /** True if any moss block exists within the configured range of the player. */
    private boolean isMossInRange() {
        double rangeSq = range.get() * range.get();
        BlockPos origin = mc.player.getBlockPos();
        int r = (int) Math.ceil(range.get());
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos pos = origin.add(x, y, z);
                    if (pos.getSquaredDistance(origin) > rangeSq) continue;
                    if (mc.world.getBlockState(pos).getBlock().getTranslationKey()
                        .toLowerCase().contains("moss_block")) return true;
                }
            }
        }
        return false;
    }

    /** Is this block one that moss naturally spreads onto / can be placed against? */
    private boolean isMossableSurface(BlockState state) {
        Block b = state.getBlock();
        return b == Blocks.DIRT || b == Blocks.GRASS_BLOCK || b == Blocks.STONE
            || b == Blocks.COARSE_DIRT || b == Blocks.ROOTED_DIRT
            || b == Blocks.PODZOL || b == Blocks.MYCELIUM
            || b == Blocks.GRANITE || b == Blocks.DIORITE || b == Blocks.ANDESITE
            || b == Blocks.TUFF || b == Blocks.DEEPSLATE || b == Blocks.MOSS_BLOCK;
    }

    private void trySeedMoss() {
        if (mc.player == null || mc.world == null) return;
        int mossSlot = findMossBlockSlot();
        if (mossSlot == -1) return; // no moss block available

        BlockPos feet = mc.player.getBlockPos();
        // Floor ring around the player
        BlockPos[] floors = {
            feet.down().north(), feet.down().south(),
            feet.down().east(),  feet.down().west(),
            feet.down().north().east(), feet.down().north().west(),
            feet.down().south().east(), feet.down().south().west()
        };

        Vec3d eye = mc.player.getEyePos();
        double maxReachSq = mc.player.getEntityInteractionRange(); // effective reach
        maxReachSq *= maxReachSq;

        BlockPos bestFloor = null;
        Vec3d    bestHit   = null;
        double   bestDistSq = Double.MAX_VALUE;

        for (BlockPos floor : floors) {
            if (!isMossableSurface(mc.world.getBlockState(floor))) continue;
            BlockPos placeAt = floor.up();
            BlockState atState = mc.world.getBlockState(placeAt);
            if (!atState.isAir() && !atState.isReplaceable()) continue;
            if (placeAt.equals(feet) || placeAt.equals(feet.up())) continue;

            Vec3d hitVec = new Vec3d(floor.getX() + 0.5, floor.getY() + 1.0, floor.getZ() + 0.5);
            double distSq = eye.squaredDistanceTo(hitVec);
            if (distSq > maxReachSq) continue;

            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestFloor  = floor;
                bestHit    = hitVec;
            }
        }

        if (bestFloor == null) return;

        // Place the moss block using a reliable placement method
        int prevSlot = mc.player.getInventory().getSelectedSlot();
        mc.player.getInventory().setSelectedSlot(mossSlot);

        // Rotate toward the hit vector and place
        Vec3d finalHit = bestHit;
        BlockPos finalFloor = bestFloor;
        Rotations.rotate(Rotations.getYaw(finalHit), Rotations.getPitch(finalHit), -100, () -> {
            BlockHitResult hit = new BlockHitResult(finalHit, Direction.UP, finalFloor, false);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            mc.player.swingHand(Hand.MAIN_HAND);
        });

        mc.player.getInventory().setSelectedSlot(prevSlot);
        placeMossTimer = placeMossDelay.get();
    }

    private int findMossBlockSlot() {
        if (mc.player == null) return -1;
        // Check hotbar first
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.MOSS_BLOCK) return i;
        }
        // If inventory allow, move one to hotbar
        if (inventoryAllow.get()) {
            int mossInInv = -1;
            for (int i = 9; i < 36; i++) {
                if (mc.player.getInventory().getStack(i).getItem() == Items.MOSS_BLOCK) {
                    mossInInv = i;
                    break;
                }
            }
            if (mossInInv != -1) {
                int emptySlot = -1;
                for (int j = 0; j < 9; j++) {
                    if (mc.player.getInventory().getStack(j).isEmpty()) {
                        emptySlot = j;
                        break;
                    }
                }
                if (emptySlot != -1) {
                    mc.interactionManager.clickSlot(0, mossInInv, emptySlot, SlotActionType.SWAP, mc.player);
                    return emptySlot;
                }
            }
        }
        return -1;
    }

    private int findBoneMealSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.BONE_MEAL) return i;
        }
        if (inventoryAllow.get()) {
            for (int i = 9; i < 36; i++) {
                if (mc.player.getInventory().getStack(i).getItem() != Items.BONE_MEAL) continue;
                for (int j = 0; j < 9; j++) {
                    if (mc.player.getInventory().getStack(j).isEmpty()) {
                        mc.interactionManager.clickSlot(0, i, j, SlotActionType.SWAP, mc.player);
                        return j;
                    }
                }
                break;
            }
        }
        return -1;
    }
}
