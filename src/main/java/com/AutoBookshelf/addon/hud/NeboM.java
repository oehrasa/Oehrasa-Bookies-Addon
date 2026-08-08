package com.AutoBookshelf.addon.hud;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.hud.Alignment;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.*;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.item.*;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.s2c.play.BlockBreakingProgressS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockEventS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;  // added missing import

import java.util.*;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class NeboM extends HudElement {
    public static final HudElementInfo<NeboM> INFO = new HudElementInfo<>(
        Addon.HUD_GROUP,
        "Nebo-M",
        "The radar system claims to be able to detect 5th generation aircraft (Loud Incorrect Buzzer noise).",
        NeboM::new
    );

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> maxDistance = sgGeneral.add(new IntSetting.Builder()
        .name("max-distance")
        .description("Maximum distance to show players (meters).")
        .defaultValue(100)
        .min(10)
        .sliderRange(10, 200)
        .build()
    );

    private final Setting<Integer> limit = sgGeneral.add(new IntSetting.Builder()
        .name("limit")
        .description("Maximum number of players to show.")
        .defaultValue(10)
        .min(1)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Boolean> showDistance = sgGeneral.add(new BoolSetting.Builder()
        .name("show-distance")
        .description("Show distance next to the name.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showState = sgGeneral.add(new BoolSetting.Builder()
        .name("show-state")
        .description("Show player state like Flying, Sprinting, etc.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showAllStates = sgGeneral.add(new BoolSetting.Builder()
        .name("show-all-states")
        .description("Show all active states.")
        .defaultValue(true)
        .visible(showState::get)
        .build()
    );

    private final Setting<String> stateSeparator = sgGeneral.add(new StringSetting.Builder()
        .name("state-separator")
        .description("Separator between multiple states.")
        .defaultValue(" ")
        .visible(showState::get)
        .build()
    );

    private final Setting<Integer> actionDisplayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("action-display-delay")
        .description("How many ticks quick actions (crystal place, mining, etc.) remain visible.")
        .defaultValue(10)
        .min(1)
        .sliderRange(1, 60)
        .build()
    );

    private final Setting<Boolean> displayFriends = sgGeneral.add(new BoolSetting.Builder()
        .name("include-friends")
        .description("Show friends.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> includeSelf = sgGeneral.add(new BoolSetting.Builder()
        .name("include-self")
        .description("Show yourself in the radar.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> showHead = sgGeneral.add(new BoolSetting.Builder()
        .name("show-head")
        .description("Show the player's face icon.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> headSize = sgGeneral.add(new IntSetting.Builder()
        .name("head-size")
        .description("Size of the player head icon (pixels).")
        .defaultValue(8)
        .min(6)
        .sliderRange(6, 16)
        .visible(showHead::get)
        .build()
    );

    private final Setting<Boolean> showDirection = sgGeneral.add(new BoolSetting.Builder()
        .name("show-direction")
        .description("Show the cardinal direction the player is facing.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> primaryColor = sgGeneral.add(new ColorSetting.Builder()
        .name("primary-color")
        .description("Default name color.")
        .defaultValue(new SettingColor())
        .build()
    );
    private final Setting<SettingColor> flyingColor = sgGeneral.add(new ColorSetting.Builder()
        .name("flying-color")
        .description("Color for flying/gliding players.")
        .defaultValue(new SettingColor(0, 255, 0))
        .visible(showState::get)
        .build()
    );
    private final Setting<SettingColor> sprintingColor = sgGeneral.add(new ColorSetting.Builder()
        .name("sprinting-color")
        .description("Color for sprinting players.")
        .defaultValue(new SettingColor(255, 255, 0))
        .visible(showState::get)
        .build()
    );
    private final Setting<SettingColor> sneakingColor = sgGeneral.add(new ColorSetting.Builder()
        .name("sneaking-color")
        .description("Color for sneaking players.")
        .defaultValue(new SettingColor(128, 128, 128))
        .visible(showState::get)
        .build()
    );
    private final Setting<SettingColor> usingItemColor = sgGeneral.add(new ColorSetting.Builder()
        .name("using-item-color")
        .description("Color for action states (Bow, Eat, Mining, etc.).")
        .defaultValue(new SettingColor(255, 165, 0))
        .visible(showState::get)
        .build()
    );
    private final Setting<SettingColor> swimmingColor = sgGeneral.add(new ColorSetting.Builder()
        .name("swimming-color")
        .description("Color for swimming players.")
        .defaultValue(new SettingColor(0, 191, 255))
        .visible(showState::get)
        .build()
    );

    private final Setting<Alignment> alignment = sgGeneral.add(new EnumSetting.Builder<Alignment>()
        .name("alignment")
        .description("Horizontal alignment.")
        .defaultValue(Alignment.Auto)
        .build()
    );
    private final Setting<Integer> border = sgGeneral.add(new IntSetting.Builder()
        .name("border")
        .description("Padding around the element.")
        .defaultValue(0)
        .build()
    );
    private final Setting<Boolean> shadow = sgGeneral.add(new BoolSetting.Builder()
        .name("shadow")
        .description("Draw text shadow.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> customScale = sgGeneral.add(new BoolSetting.Builder()
        .name("custom-scale")
        .description("Applies custom text scale rather than the global one.")
        .defaultValue(false)
        .build()
    );
    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Custom scale.")
        .visible(customScale::get)
        .defaultValue(1)
        .min(0.5)
        .sliderRange(0.5, 3)
        .build()
    );

    private final List<PlayerEntity> players = new ArrayList<>();
    private static final int ICON_SIZE = 16;  // normal size
    private static final int ICON_TEXT_GAP = 2;
    private final Map<UUID, Integer> crystalPlaceTicks = new HashMap<>(); // player UUID -> ticks left
    private final Map<Integer, Integer> miningTicks = new HashMap<>(); // entity ID -> ticks left
    private final Map<UUID, Integer> totemPopTicks = new HashMap<>(); // player UUID -> ticks left
    private final Map<UUID, Integer> throwTicks = new HashMap<>(); // player UUID -> ticks left
    private final Map<UUID, ItemStack> throwItemStack = new HashMap<>(); // player UUID -> what was thrown
    private final Map<UUID, Integer> containerOpenTicks = new HashMap<>(); // player UUID -> ticks left
    private final Map<UUID, ItemStack> containerOpenIcon = new HashMap<>(); // player UUID -> container icon

    // Nearest-player search radii (squared, in blocks) for events that don't carry the actor's identity directly
    private static final double THROW_MATCH_RADIUS_SQ = 64.0;   // 8 blocks, matches existing crystal-guess radius
    private static final double CONTAINER_MATCH_RADIUS_SQ = 16.0; // 4 blocks, containers are stationary so this can be tighter

    // Cache for head textures (per player UUID)
    private final Map<UUID, SkinTextures> headTextureCache = new HashMap<>();

    public NeboM() { super(INFO); MeteorClient.EVENT_BUS.subscribe(this); }

    @Override
    public void remove() {
        super.remove();
        MeteorClient.EVENT_BUS.unsubscribe(this);
    }

    private double getScale() {
        return customScale.get() ? scale.get() : -1;   // -1 = global HUD scale
    }

    @Override public void setSize(double width, double height) {
        super.setSize(width + border.get() * 2, height + border.get() * 2);
    }
    @Override protected double alignX(double width, Alignment alignment) {
        return box.alignX(getWidth() - border.get() * 2, width, alignment);
    }

    @Override public void tick(HudRenderer renderer) {
        double scl = getScale();
        double width = renderer.textWidth("Players:", shadow.get(), scl);
        double height = renderer.textHeight(shadow.get(), scl);

        if (mc.world == null) { setSize(width, height); return; }

        for (PlayerEntity player : getNearbyPlayers()) {
            if (!shouldShow(player)) continue;

            double lineWidth = 0;

            // Head icon width
            if (showHead.get()) {
                lineWidth += headSize.get() + 2; // icon + gap
            }

            // name
            String name = player.getName().getString();
            lineWidth += renderer.textWidth(name, shadow.get(), scl);
            // distance
            if (showDistance.get()) {
                String dist = String.format(" (%.0fm)", player.distanceTo(mc.player));
                lineWidth += renderer.textWidth(dist, shadow.get(), scl);
            }
            // direction
            if (showDirection.get()) {
                String dir = getDirectionString(player.getYaw());
                lineWidth += renderer.textWidth(" " + dir, shadow.get(), scl);
            }
            // states + icons
            if (showState.get()) {
                List<String> states = getActiveStates(player);
                for (int i = 0; i < states.size(); i++) {
                    String state = states.get(i);
                    if (i > 0) lineWidth += renderer.textWidth(stateSeparator.get(), shadow.get(), scl);
                    if (getActionItem(player, state) != null && isActionState(state)) {
                        lineWidth += ICON_SIZE + ICON_TEXT_GAP;
                    }
                    lineWidth += renderer.textWidth(state, shadow.get(), scl);
                }
            }
            width = Math.max(width, lineWidth);
            height += renderer.textHeight(shadow.get(), scl) + 2;
        }
        setSize(width, height);
    }

    @Override public void render(HudRenderer renderer) {
        double y = this.y + border.get();
        double scl = getScale();

        renderer.text("Players:",
            x + border.get() + alignX(renderer.textWidth("Players:", shadow.get(), scl), alignment.get()),
            y, Color.GRAY, shadow.get(), scl);

        if (mc.world == null) return;

        for (PlayerEntity player : getNearbyPlayers()) {
            if (!shouldShow(player)) continue;

            String name = player.getName().getString();
            Color nameColor = PlayerUtils.getPlayerColor(player, primaryColor.get());

            String distanceStr = showDistance.get()
                ? String.format(" (%.0fm)", player.distanceTo(mc.player))
                : "";

            String dirStr = "";
            if (showDirection.get()) {
                dirStr = " " + getDirectionString(player.getYaw());
            }

            String statePart = "";
            if (showState.get()) {
                List<String> states = getActiveStates(player);
                if (!states.isEmpty()) {
                    statePart = String.join(stateSeparator.get(), states);
                    statePart = " " + statePart;
                }
            }

            // Build the line (excluding the head) to measure total text width for alignment
            String lineWithoutHead = name + distanceStr + dirStr + statePart;
            double fullLineWidth = renderer.textWidth(lineWithoutHead, shadow.get(), scl);
            if (showHead.get()) {
                fullLineWidth += headSize.get() + 2; // head icon + gap
            }

            y += renderer.textHeight(shadow.get(), scl) + 2;
            double x = this.x + border.get() + alignX(fullLineWidth, alignment.get());

            // Draw head icon
            if (showHead.get()) {
                SkinTextures skinTextures = getSkinTextures(player);
                double textHeight = renderer.textHeight(shadow.get(), scl);
                int headY = (int) (y - textHeight + (textHeight - headSize.get()) / 2);
                if (skinTextures != null) {
                    PlayerSkinDrawer.draw(
                        renderer.drawContext,
                        skinTextures,
                        (int) x,
                        headY,
                        headSize.get()
                    );
                }
                x += headSize.get() + 2;
            }

            // name + distance + direction
            x = renderer.text(name + distanceStr + dirStr, x, y, nameColor, shadow.get(), scl);

            // states with optional icons
            if (!statePart.isEmpty()) {
                List<String> stateWords = showAllStates.get()
                    ? getActiveStates(player)
                    : (getActiveStates(player).isEmpty() ? List.of() : List.of(getActiveStates(player).get(0)));

                for (int i = 0; i < stateWords.size(); i++) {
                    String state = stateWords.get(i);
                    if (state.isEmpty()) continue;
                    Color stateColor = getStateColorByName(state);

                    if (i > 0) {
                        x = renderer.text(stateSeparator.get(), x, y, Color.GRAY, shadow.get(), scl);
                    }

                    // Draw the state text first
                    x = renderer.text(state, x, y, stateColor, shadow.get(), scl);

                    // Draw icon after the text if the state has an action item
                    ItemStack actionItem = getActionItem(player, state);
                    if (actionItem != null && isActionState(state)) {
                        x += ICON_TEXT_GAP;
                        double iconY = y - 9;   // centre 16px icon vertically
                        renderer.item(actionItem, (int) x, (int) iconY, 1.0f, false);
                        x += ICON_SIZE;
                    }
                }
            }
        }
    }

    private SkinTextures getSkinTextures(PlayerEntity player) {
        return headTextureCache.computeIfAbsent(player.getUuid(), uuid -> {
            if (mc.getNetworkHandler() == null) return null;
            PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(uuid);
            return entry != null ? entry.getSkinTextures() : null;
        });
    }

    private String getDirectionString(float yaw) {
        float y = (yaw % 360 + 360) % 360;
        String[] dirs = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
        int index = Math.round(y / 45) % 8;
        return dirs[index];
    }

    private boolean shouldShow(PlayerEntity player) {
        if (player == mc.player && !includeSelf.get()) return false;
        if (!displayFriends.get() && Friends.get().isFriend(player)) return false;
        return true;
    }

    private List<PlayerEntity> getNearbyPlayers() {
        players.clear();
        double maxDistSq = maxDistance.get() * maxDistance.get();
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player.squaredDistanceTo(mc.player) <= maxDistSq) players.add(player);
        }
        players.sort(Comparator.comparingDouble(p -> p.squaredDistanceTo(mc.player)));
        if (players.size() > limit.get()) players.subList(limit.get(), players.size()).clear();
        return players;
    }

    // Finds the closest player to a world position, used for packets (entity spawn, block event)
    // that don't carry the acting player's identity directly. Returns null if nobody is within radius.
    @Nullable
    private PlayerEntity findNearestPlayer(Vec3d pos, double maxDistSq) {
        PlayerEntity nearest = null;
        double nearestDist = maxDistSq;
        for (PlayerEntity p : mc.world.getPlayers()) {
            double dist = p.getEntityPos().squaredDistanceTo(pos);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = p;
            }
        }
        return nearest;
    }

    private List<String> getActiveStates(PlayerEntity player) {
        List<String> states = new ArrayList<>();
        if (player.isGliding()) states.add("Fly");
        if (player.isSprinting()) states.add("Sprint");
        if (player.isSneaking()) states.add("Sneak");
        if (player.isSwimming()) states.add("Swim");

        // Crystal placement (recent packet)
        Integer crystalTicks = crystalPlaceTicks.get(player.getUuid());
        if (crystalTicks != null && crystalTicks > 0) {
            states.add("Crystal");
        }

        // Totem of undying popped (recent packet)
        Integer totemTicks = totemPopTicks.get(player.getUuid());
        if (totemTicks != null && totemTicks > 0) {
            states.add("Totem");
        }

        // Thrown projectile (ender pearl / snowball / egg / potion / etc, recent packet)
        Integer throwT = throwTicks.get(player.getUuid());
        if (throwT != null && throwT > 0) {
            states.add("Throw");
        }

        // Container opened nearby (recent packet)
        Integer containerT = containerOpenTicks.get(player.getUuid());
        if (containerT != null && containerT > 0) {
            states.add("Container");
        }

        // Continuous item use
        if (player.isUsingItem()) {
            ItemStack active = player.getActiveItem();
            if (!active.isEmpty()) {
                Item item = active.getItem();
                if (item instanceof BowItem || item instanceof CrossbowItem) states.add("Bow");
                else if (item == Items.END_CRYSTAL || item == Items.RESPAWN_ANCHOR) states.add("Crystal");
                else if (item instanceof BlockItem) states.add("Place");
                else if (active.getComponents().get(DataComponentTypes.FOOD) != null) states.add("Eat");
                else if (item instanceof PotionItem || item == Items.POTION) states.add("Drink");
            }
        }

        // Mining
        Integer mineTicks = miningTicks.get(player.getId());
        if (mineTicks != null && mineTicks > 0) {
            states.add("Mining");
        } else if (player == mc.player && mc.interactionManager != null && mc.interactionManager.isBreakingBlock()) {
            // fallback for local player without packet
            states.add("Mining");
        }

        return states;
    }

    @Nullable
    private ItemStack getActionItem(PlayerEntity player, String state) {
        return switch (state) {
            case "Bow", "Eat", "Drink" -> player.getActiveItem().copy();
            case "Crystal" -> {
                // If using item, return the active item; else for quick crystal it's already in the state list, show crystal item.
                if (player.isUsingItem()) yield player.getActiveItem().copy();
                else yield new ItemStack(Items.END_CRYSTAL);
            }
            case "Place" -> player.getActiveItem().copy();
            case "Mining" -> player.getMainHandStack().copy();
            case "Totem" -> new ItemStack(Items.TOTEM_OF_UNDYING);
            case "Throw" -> {
                ItemStack thrown = throwItemStack.get(player.getUuid());
                yield thrown != null ? thrown.copy() : null;
            }
            case "Container" -> {
                ItemStack icon = containerOpenIcon.get(player.getUuid());
                yield icon != null ? icon.copy() : new ItemStack(Items.CHEST);
            }
            default -> null;
        };
    }

    private boolean isActionState(String state) {
        return switch (state) {
            case "Bow", "Crystal", "Place", "Eat", "Drink", "Mining", "Totem", "Throw", "Container" -> true;
            default -> false;
        };
    }

    private Color getStateColorByName(String state) {
        return switch (state) {
            case "Fly" -> flyingColor.get();
            case "Sprint" -> sprintingColor.get();
            case "Sneak" -> sneakingColor.get();
            case "Swim" -> swimmingColor.get();
            default -> usingItemColor.get();
        };
    }

    // Any block that shows an "open" animation to nearby players via a block event packet.
    // Order matters here: TrappedChestBlock extends ChestBlock, so it must be checked first.
    private boolean isContainerBlock(Block block) {
        return block instanceof TrappedChestBlock
            || block instanceof ChestBlock
            || block instanceof EnderChestBlock
            || block instanceof ShulkerBoxBlock;
    }

    // TrappedChestBlock extends ChestBlock, so it's checked first here too.
    private ItemStack getContainerIcon(Block block) {
        if (block instanceof TrappedChestBlock) return new ItemStack(Items.TRAPPED_CHEST);
        if (block instanceof ChestBlock) return new ItemStack(Items.CHEST);
        if (block instanceof EnderChestBlock) return new ItemStack(Items.ENDER_CHEST);
        if (block instanceof ShulkerBoxBlock) return new ItemStack(Items.SHULKER_BOX);
        return new ItemStack(Items.CHEST);
    }

    // Projectile types thrown via a single instant right-click (not charged like a bow).
    private boolean isThrowable(EntityType<?> type) {
        return type == EntityType.ENDER_PEARL
            || type == EntityType.SNOWBALL
            || type == EntityType.EGG
            || type == EntityType.SPLASH_POTION
            || type == EntityType.LINGERING_POTION
            || type == EntityType.EXPERIENCE_BOTTLE;
    }

    private ItemStack getThrowableIcon(EntityType<?> type) {
        if (type == EntityType.ENDER_PEARL) return new ItemStack(Items.ENDER_PEARL);
        if (type == EntityType.SNOWBALL) return new ItemStack(Items.SNOWBALL);
        if (type == EntityType.EGG) return new ItemStack(Items.EGG);
        if (type == EntityType.SPLASH_POTION) return new ItemStack(Items.SPLASH_POTION);
        if (type == EntityType.LINGERING_POTION) return new ItemStack(Items.LINGERING_POTION);
        if (type == EntityType.EXPERIENCE_BOTTLE) return new ItemStack(Items.EXPERIENCE_BOTTLE);
        return ItemStack.EMPTY;
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (mc.player == null || mc.world == null) return;
        if (event.packet instanceof PlayerInteractBlockC2SPacket) {
            ItemStack stack = mc.player.getMainHandStack();
            if (stack.getItem() == Items.END_CRYSTAL) {
                crystalPlaceTicks.put(mc.player.getUuid(), actionDisplayTicks.get());
            }
        }
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.player == null || mc.world == null) return;

        // Mining progress for all players
        if (event.packet instanceof BlockBreakingProgressS2CPacket packet) {
            int entityId = packet.getEntityId();
            miningTicks.put(entityId, actionDisplayTicks.get());
        }

        // Totem of undying popped - entity status 35 is the vanilla "totem used" event
        if (event.packet instanceof EntityStatusS2CPacket packet) {
            if (packet.getStatus() == 35) {
                Entity entity = packet.getEntity(mc.world);
                if (entity instanceof PlayerEntity player) {
                    totemPopTicks.put(player.getUuid(), actionDisplayTicks.get());
                }
            }
        }

        // Crystal placed / projectile thrown by anyone - guess who did it by nearest player to the spawn position
        if (event.packet instanceof EntitySpawnS2CPacket packet) {
            EntityType<?> type = packet.getEntityType();
            Vec3d spawnPos = new Vec3d(packet.getX(), packet.getY(), packet.getZ());

            if (type == EntityType.END_CRYSTAL) {
                PlayerEntity nearest = findNearestPlayer(spawnPos, 64.0);
                if (nearest != null) {
                    crystalPlaceTicks.put(nearest.getUuid(), actionDisplayTicks.get());
                }
            } else if (isThrowable(type)) {
                PlayerEntity nearest = findNearestPlayer(spawnPos, THROW_MATCH_RADIUS_SQ);
                if (nearest != null) {
                    throwTicks.put(nearest.getUuid(), actionDisplayTicks.get());
                    throwItemStack.put(nearest.getUuid(), getThrowableIcon(type));
                }
            }
        }

        // Container opened (chest/trapped chest/ender chest/shulker box) - block event packet is broadcast
        // to nearby clients when a container's viewer count changes. Barrels don't use this (their open
        // state is a plain blockstate property), so they aren't detectable this way.
        if (event.packet instanceof BlockEventS2CPacket packet) {
            Block block = packet.getBlock();
            if (isContainerBlock(block)) {
                BlockPos pos = packet.getPos();
                Vec3d center = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                PlayerEntity nearest = findNearestPlayer(center, CONTAINER_MATCH_RADIUS_SQ);
                if (nearest != null) {
                    containerOpenTicks.put(nearest.getUuid(), actionDisplayTicks.get());
                    containerOpenIcon.put(nearest.getUuid(), getContainerIcon(block));
                }
            }
        }
    }

    private static <K> void decrementTicks(Map<K, Integer> ticks) {
        ticks.entrySet().removeIf(entry -> {
            entry.setValue(entry.getValue() - 1);
            return entry.getValue() <= 0;
        });
    }

    @EventHandler
    private void onTickPost(TickEvent.Post event) {
        decrementTicks(crystalPlaceTicks);
        decrementTicks(miningTicks);
        decrementTicks(totemPopTicks);
        decrementTicks(throwTicks);
        decrementTicks(containerOpenTicks);

        // Drop cached icons once their matching tick entry expires, so they don't linger forever
        throwItemStack.keySet().retainAll(throwTicks.keySet());
        containerOpenIcon.keySet().retainAll(containerOpenTicks.keySet());
    }
}
