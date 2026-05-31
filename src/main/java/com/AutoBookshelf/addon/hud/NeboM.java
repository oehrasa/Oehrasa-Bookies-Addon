package com.AutoBookshelf.addon.hud;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.hud.*;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

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

    private final List<Player> players = new ArrayList<>();
    private static final int ICON_SIZE = 16;  // normal size
    private static final int ICON_TEXT_GAP = 2;
    private final Map<UUID, Integer> crystalPlaceTicks = new HashMap<>(); // player UUID -> ticks left
    private final Map<Integer, Integer> crystalPlaceTicksByEntity = new HashMap<>();
    private final Map<Integer, Integer> miningTicks = new HashMap<>(); // entity ID -> ticks left
    private final Map<UUID, Integer> containerOpenTicks = new HashMap<>();

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

        if (mc.level == null) { setSize(width, height); return; }

        for (Player player : getNearbyPlayers()) {
            if (!shouldShow(player)) continue;

            double lineWidth = 0;
            // name
            String name = player.getName().getString();
            lineWidth += renderer.textWidth(name, shadow.get(), scl);
            // distance
            if (showDistance.get()) {
                String dist = String.format(" (%.0fm)", player.distanceTo(mc.player));
                lineWidth += renderer.textWidth(dist, shadow.get(), scl);
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

        if (mc.level == null) return;

        for (Player player : getNearbyPlayers()) {
            if (!shouldShow(player)) continue;

            String name = player.getName().getString();
            Color nameColor = PlayerUtils.getPlayerColor(player, primaryColor.get());

            String distanceStr = showDistance.get()
                ? String.format(" (%.0fm)", player.distanceTo(mc.player))
                : "";

            String statePart = "";
            if (showState.get()) {
                List<String> states = getActiveStates(player);
                if (!states.isEmpty()) {
                    statePart = String.join(stateSeparator.get(), states);
                    statePart = " " + statePart;
                }
            }

            String fullLine = name + distanceStr + statePart;
            double fullWidth = renderer.textWidth(fullLine, shadow.get(), scl);
            double x = this.x + border.get() + alignX(fullWidth, alignment.get());
            y += renderer.textHeight(shadow.get(), scl) + 2;

            // name + distance
            x = renderer.text(name + distanceStr, x, y, nameColor, shadow.get(), scl);

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

    private boolean shouldShow(Player player) {
        if (player == mc.player && !includeSelf.get()) return false;
        if (!displayFriends.get() && Friends.get().isFriend(player)) return false;
        return true;
    }

    private List<Player> getNearbyPlayers() {
        players.clear();
        double maxDistSq = maxDistance.get() * maxDistance.get();
        for (Player player : mc.level.players()) {
            if (player.distanceToSqr(mc.player) <= maxDistSq) players.add(player);
        }
        players.sort(Comparator.comparingDouble(p -> p.distanceToSqr(mc.player)));
        if (players.size() > limit.get()) players.subList(limit.get(), players.size()).clear();
        return players;
    }

    private List<String> getActiveStates(Player player) {
        List<String> states = new ArrayList<>();
        if (player.isFallFlying()) states.add("Fly");
        if (player.isSprinting()) states.add("Sprint");
        if (player.isShiftKeyDown()) states.add("Sneak");
        if (player.isSwimming()) states.add("Swim");

        // Crystal placement (recent packet)
        Integer crystalTicks = crystalPlaceTicks.get(player.getUUID());
        if (crystalTicks != null && crystalTicks > 0) {
            states.add("Crystal");
        }

        // Continuous item use
        if (player.isUsingItem()) {
            ItemStack active = player.getUseItem();
            if (!active.isEmpty()) {
                Item item = active.getItem();
                if (item instanceof BowItem || item instanceof CrossbowItem) states.add("Bow");
                else if (item == Items.END_CRYSTAL || item == Items.RESPAWN_ANCHOR) states.add("Crystal");
                else if (item instanceof BlockItem) states.add("Place");
                else if (active.getComponents().get(DataComponents.FOOD) != null) states.add("Eat");
                else if (item instanceof PotionItem || item == Items.POTION) states.add("Drink");
            }
        }

        // Mining
        Integer mineTicks = miningTicks.get(player.getId());
        if (mineTicks != null && mineTicks > 0) {
            states.add("Mining");
        } else if (player == mc.player && mc.gameMode != null && mc.gameMode.isDestroying()) {
            // fallback for local player without packet
            states.add("Mining");
        }

        // Container
        Integer containerTicks = containerOpenTicks.get(player.getUUID());
        if (containerTicks != null && containerTicks > 0) {
            states.add("Container");
        }
        return states;
    }

    @Nullable
    private ItemStack getActionItem(Player player, String state) {
        return switch (state) {
            case "Bow", "Eat", "Drink" -> player.getUseItem().copy();
            case "Crystal" -> {
                // If using item, return the active item; else for quick crystal it's already in the state list, show crystal item.
                if (player.isUsingItem()) yield player.getUseItem().copy();
                else yield new ItemStack(Items.END_CRYSTAL);
            }
            case "Place" -> player.getUseItem().copy();
            case "Mining" -> player.getMainHandItem().copy();
            default -> null;
        };
    }

    private boolean isActionState(String state) {
        return switch (state) {
            case "Bow", "Crystal", "Place", "Eat", "Drink", "Mining" -> true;
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

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (mc.player == null || mc.level == null) return;
        if (event.packet instanceof ServerboundUseItemOnPacket) {
            ItemStack stack = mc.player.getMainHandItem();
            if (stack.getItem() == Items.END_CRYSTAL) {
                crystalPlaceTicks.put(mc.player.getUUID(), actionDisplayTicks.get());
            }
        }
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.player == null || mc.level == null) return;

        // Mining progress for all players
        if (event.packet instanceof ClientboundBlockDestructionPacket packet) {
            int entityId = packet.getId();
            miningTicks.put(entityId, actionDisplayTicks.get());
        }

        // Crystal placed by anyone, guess who placed it by distance
        if (event.packet instanceof ClientboundAddEntityPacket packet
            && packet.getType() == EntityType.END_CRYSTAL) {

            Vec3 crystalPos = new Vec3(packet.getX(), packet.getY(), packet.getZ());
            Player nearest = null;
            double nearestDist = Double.MAX_VALUE;

            for (Player p : mc.level.players()) {
                double dist = p.position().distanceToSqr(crystalPos);
                if (dist < 64.0 && dist < nearestDist) {
                    nearestDist = dist;
                    nearest = p;
                }
            }

            if (nearest != null) {
                crystalPlaceTicks.put(nearest.getUUID(), actionDisplayTicks.get());
            }
        }

        // Container open detection
        if (event.packet instanceof ClientboundLevelEventPacket worldEvent) {
            int eventId = worldEvent.getType();
            if (eventId == 1008 || eventId == 1010 || eventId == 1012 || eventId == 1013) {
                BlockPos pos = worldEvent.getPos();
                Vec3 containerPos = pos.getCenter();

                Player nearest = null;
                double nearestDist = Double.MAX_VALUE;
                for (Player p : mc.level.players()) {
                    double dist = p.position().distanceToSqr(containerPos);
                    if (dist < 256.0 && dist < nearestDist) {   // 16 blocks reach
                        nearestDist = dist;
                        nearest = p;
                    }
                }
                if (nearest != null) {
                    containerOpenTicks.put(nearest.getUUID(), actionDisplayTicks.get());
                }
            }
        }
    }

    @EventHandler
    private void onTickPost(TickEvent.Post event) {
        // Decrement crystal timers
        crystalPlaceTicks.entrySet().removeIf(entry -> {
            entry.setValue(entry.getValue() - 1);
            return entry.getValue() <= 0;
        });
        // Decrement mining timers
        miningTicks.entrySet().removeIf(entry -> {
            entry.setValue(entry.getValue() - 1);
            return entry.getValue() <= 0;
        });
        containerOpenTicks.entrySet().removeIf(entry -> {
            entry.setValue(entry.getValue() - 1);
            return entry.getValue() <= 0;
        });
    }
}
