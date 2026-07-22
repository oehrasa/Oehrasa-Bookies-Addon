package com.AutoBookshelf.addon.hud;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.Alignment;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.VehicleEntity;

import java.util.*;

public class EntityList extends HudElement {
    public static final HudElementInfo<EntityList> INFO = new HudElementInfo<>(
        Addon.HUD_GROUP, "EntityList", "Displays nearby entities in a list.", EntityList::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgColors = settings.createGroup("Colors");
    private final SettingGroup sgBackground = settings.createGroup("Background");

    private final Setting<Boolean> showTitle = sgGeneral.add(new BoolSetting.Builder()
        .name("show-title")
        .description("Display the HUD title.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> showItems = sgGeneral.add(new BoolSetting.Builder()
        .name("show-items")
        .description("Show dropped items.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> showMobs = sgGeneral.add(new BoolSetting.Builder()
        .name("show-mobs")
        .description("Show mobs.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> showPlayers = sgGeneral.add(new BoolSetting.Builder()
        .name("show-players")
        .description("Show players.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> showProjectiles = sgGeneral.add(new BoolSetting.Builder()
        .name("show-projectiles")
        .description("Show thrown/fired projectiles (arrows, tridents, ender pearls, fireballs, etc).")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> showRockets = sgGeneral.add(new BoolSetting.Builder()
        .name("show-rockets")
        .description("Show firework rockets.")
        .defaultValue(false)
        .build()
    );
    private final Setting<Boolean> showVehicles = sgGeneral.add(new BoolSetting.Builder()
        .name("show-vehicles")
        .description("Show minecarts and boats.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> showOther = sgGeneral.add(new BoolSetting.Builder()
        .name("show-other")
        .description("Show everything else not covered above (TNT, XP orbs, armor stands, item frames, paintings, end crystals, etc).")
        .defaultValue(true)
        .build()
    );
    private final Setting<Double> maxDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-distance")
        .description("Maximum distance to show entities.")
        .defaultValue(100.0)
        .min(0.0)
        .sliderRange(0.0, 500.0)
        .build()
    );
    private final Setting<SortMode> sortMode = sgGeneral.add(new EnumSetting.Builder<SortMode>()
        .name("sort-mode")
        .description("How to order entities in the list.")
        .defaultValue(SortMode.Distance)
        .build()
    );
    private final Setting<Boolean> showDistance = sgGeneral.add(new BoolSetting.Builder()
        .name("show-distance")
        .description("Show distance to entities.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> includeYLevel = sgGeneral.add(new BoolSetting.Builder()
        .name("include-y-level")
        .description("Include Y level in distance calculation (3D distance).")
        .defaultValue(false)
        .build()
    );
    private final Setting<Alignment> alignment = sgGeneral.add(new EnumSetting.Builder<Alignment>()
        .name("alignment")
        .description("Whether the list is anchored to the left or right edge of the element.")
        .defaultValue(Alignment.Left)
        .build()
    );
    private final Setting<Double> textScale = sgGeneral.add(new DoubleSetting.Builder()
        .name("text-scale")
        .description("Scale of the text.")
        .defaultValue(1.0)
        .min(0.1)
        .sliderRange(0.1, 3.0)
        .build()
    );
    private final Setting<Boolean> textShadow = sgGeneral.add(new BoolSetting.Builder()
        .name("text-shadow")
        .description("Render shadow behind the text.")
        .defaultValue(true)
        .build()
    );

    // Colors
    private final Setting<SettingColor> titleColor = sgColors.add(new ColorSetting.Builder()
        .name("title-color")
        .description("Color for the HUD title.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build()
    );
    private final Setting<SettingColor> playerColor = sgColors.add(new ColorSetting.Builder()
        .name("player-color")
        .description("Color for player entities.")
        .defaultValue(new SettingColor(0, 255, 0, 255))
        .build()
    );
    private final Setting<SettingColor> hostileColor = sgColors.add(new ColorSetting.Builder()
        .name("hostile-color")
        .description("Color for hostile mobs (zombies, skeletons, creepers, etc).")
        .defaultValue(new SettingColor(255, 0, 0, 255))
        .build()
    );
    private final Setting<SettingColor> animalColor = sgColors.add(new ColorSetting.Builder()
        .name("animal-color")
        .description("Color for passive land animals.")
        .defaultValue(new SettingColor(25, 255, 25, 255))
        .build()
    );
    private final Setting<SettingColor> aquaticColor = sgColors.add(new ColorSetting.Builder()
        .name("aquatic-color")
        .description("Color for aquatic mobs (fish, squid, axolotls, etc).")
        .defaultValue(new SettingColor(25, 25, 255, 255))
        .build()
    );
    private final Setting<SettingColor> ambientMobColor = sgColors.add(new ColorSetting.Builder()
        .name("ambient-mob-color")
        .description("Color for ambient mobs (bats, etc).")
        .defaultValue(new SettingColor(25, 25, 25, 255))
        .build()
    );
    private final Setting<SettingColor> miscMobColor = sgColors.add(new ColorSetting.Builder()
        .name("misc-mob-color")
        .description("Color for mobs that don't fall into the categories above.")
        .defaultValue(new SettingColor(175, 175, 175, 255))
        .build()
    );
    private final Setting<SettingColor> itemColor = sgColors.add(new ColorSetting.Builder()
        .name("item-color")
        .description("Color for item entities.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build()
    );
    private final Setting<SettingColor> projectileColor = sgColors.add(new ColorSetting.Builder()
        .name("projectile-color")
        .description("Color for projectile entities.")
        .defaultValue(new SettingColor(150, 100, 255, 255))
        .build()
    );
    private final Setting<SettingColor> rocketColor = sgColors.add(new ColorSetting.Builder()
        .name("rocket-color")
        .description("Color for firework rockets.")
        .defaultValue(new SettingColor(255, 165, 0, 255))
        .build()
    );
    private final Setting<SettingColor> vehicleColor = sgColors.add(new ColorSetting.Builder()
        .name("vehicle-color")
        .description("Color for minecarts and boats.")
        .defaultValue(new SettingColor(210, 180, 140, 255))
        .build()
    );
    private final Setting<SettingColor> otherColor = sgColors.add(new ColorSetting.Builder()
        .name("other-color")
        .description("Color for miscellaneous entities.")
        .defaultValue(new SettingColor(180, 180, 180, 255))
        .build()
    );

    // Background

    private final Setting<Boolean> background = sgBackground.add(new BoolSetting.Builder()
        .name("background")
        .description("Displays a background panel behind the list.")
        .defaultValue(false)
        .build()
    );
    private final Setting<SettingColor> backgroundColor = sgBackground.add(new ColorSetting.Builder()
        .name("background-color")
        .description("Color used for the background.")
        .visible(background::get)
        .defaultValue(new SettingColor(25, 25, 25, 50))
        .build()
    );

    public EntityList() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        if (MeteorClient.mc.level == null || MeteorClient.mc.player == null) {
            if (isInEditor()) {
                String title = "Entity List";
                double titleWidth = renderer.textWidth(title, textShadow.get(), textScale.get());
                double titleHeight = renderer.textHeight(textShadow.get(), textScale.get());
                setSize(titleWidth, titleHeight);

                if (background.get()) renderer.quad(x, y, getWidth(), getHeight(), backgroundColor.get());
                double drawX = x + box.alignX(getWidth(), titleWidth, alignment.get());
                renderer.text(title, drawX, y, titleColor.get(), textShadow.get(), textScale.get());
            }
            return;
        }

        Map<String, Aggregated> map = new HashMap<>();
        for (Entity entity : MeteorClient.mc.level.entitiesForRendering()) {
            if (entity == MeteorClient.mc.player) continue;
            double dx = entity.getX() - MeteorClient.mc.player.getX();
            double dz = entity.getZ() - MeteorClient.mc.player.getZ();
            double distance;
            if (includeYLevel.get()) {
                double dy = entity.getY() - MeteorClient.mc.player.getY();
                distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            } else {
                distance = Math.sqrt(dx * dx + dz * dz);
            }
            if (distance > maxDistance.get()) continue;

            // Classify into exactly one category, most specific first.
            boolean isRocket = entity instanceof FireworkRocketEntity;
            boolean isItem = !isRocket && entity instanceof ItemEntity;
            boolean isPlayer = !isRocket && !isItem && entity instanceof Player;
            boolean isMob = !isRocket && !isItem && !isPlayer && entity instanceof Mob;
            boolean isProjectile = !isRocket && !isItem && !isPlayer && !isMob && entity instanceof Projectile;
            boolean isVehicle = !isRocket && !isItem && !isPlayer && !isMob && !isProjectile && entity instanceof VehicleEntity;
            boolean isOther = !isRocket && !isItem && !isPlayer && !isMob && !isProjectile && !isVehicle;

            if (isRocket && !showRockets.get()) continue;
            if (isItem && !showItems.get()) continue;
            if (isPlayer && !showPlayers.get()) continue;
            if (isMob && !showMobs.get()) continue;
            if (isProjectile && !showProjectiles.get()) continue;
            if (isVehicle && !showVehicles.get()) continue;
            if (isOther && !showOther.get()) continue;

            String name = getEntityName(entity);
            SettingColor color = getEntityColor(entity, isRocket, isItem, isPlayer, isMob, isProjectile, isVehicle);
            Aggregated agg = map.get(name);
            if (agg == null) {
                agg = new Aggregated();
                agg.name = name;
                agg.color = color;
                agg.minDist = distance;
                if (isItem) {
                    agg.count = ((ItemEntity) entity).getItem().getCount();
                } else {
                    agg.count = 1;
                }
                map.put(name, agg);
            } else {
                agg.minDist = Math.min(agg.minDist, distance);
                if (isItem) {
                    agg.count += ((ItemEntity) entity).getItem().getCount();
                } else {
                    agg.count++;
                }
            }
        }

        double textHeight = renderer.textHeight(textShadow.get(), textScale.get());
        double spacing = 2;

        // Build the display text/width for every aggregated entry up front, since sorting by
        // Length needs the final rendered text (name + count + distance suffix), not just the
        // raw entity name.
        List<Line> entityLines = new ArrayList<>();
        for (Aggregated agg : map.values()) {
            String text = agg.name;
            if (agg.count > 1) {
                text += " x" + agg.count;
            }
            if (showDistance.get()) {
                text += " (" + (int) agg.minDist + "m)";
            }
            double textWidth = renderer.textWidth(text, textShadow.get(), textScale.get());
            entityLines.add(new Line(text, agg.color, textWidth, agg.minDist));
        }

        switch (sortMode.get()) {
            // Sort by rendered pixel width rather than String#length(): length() counts UTF-16
            // code units, which misrepresents entities with non-ASCII/wide-glyph names (and any
            // future locale-translated names) relative to the actual on-screen size of the
            // name + count + distance suffix.
            case Distance -> entityLines.sort(Comparator.comparingDouble(Line::minDist));
            case Length -> entityLines.sort(Comparator.comparingDouble(Line::width).reversed());
            case Name -> entityLines.sort(Comparator.comparing(Line::text, String.CASE_INSENSITIVE_ORDER));
        }

        List<Line> lines = new ArrayList<>();
        double maxWidth = 0;
        double totalHeight = 0;

        if (showTitle.get()) {
            String title = "Entity List";
            double titleWidth = renderer.textWidth(title, textShadow.get(), textScale.get());
            lines.add(new Line(title, titleColor.get(), titleWidth, 0));
            maxWidth = Math.max(maxWidth, titleWidth);
            totalHeight += textHeight + spacing;
        }

        for (Line line : entityLines) {
            maxWidth = Math.max(maxWidth, line.width());
            totalHeight += textHeight + spacing;
        }
        lines.addAll(entityLines);

        setSize(maxWidth, Math.max(0, totalHeight - spacing));

        if (background.get()) renderer.quad(x, y, getWidth(), getHeight(), backgroundColor.get());

        // Draw, aligning each line within the now-correct element width.
        double curY = y;
        for (Line line : lines) {
            double drawX = x + box.alignX(getWidth(), line.width(), alignment.get());
            renderer.text(line.text(), drawX, curY, line.color(), textShadow.get(), textScale.get());
            curY += textHeight + spacing;
        }
    }

    private String getEntityName(Entity entity) {
        if (entity instanceof ItemEntity item) {
            return item.getItem().getHoverName().getString();
        } else if (entity instanceof Player player) {
            return player.getName().getString();
        } else {
            return entity.getType().getDescription().getString();
        }
    }

    private SettingColor getEntityColor(Entity entity, boolean isRocket, boolean isItem, boolean isPlayer, boolean isMob, boolean isProjectile, boolean isVehicle) {
        if (isItem) return itemColor.get();
        if (isPlayer) return playerColor.get();
        if (isMob) return getMobColor(entity);
        if (isRocket) return rocketColor.get();
        if (isProjectile) return projectileColor.get();
        if (isVehicle) return vehicleColor.get();
        return otherColor.get();
    }

    private SettingColor getMobColor(Entity entity) {
        return switch (entity.getType().getCategory()) {
            case CREATURE -> animalColor.get();
            case WATER_AMBIENT, WATER_CREATURE, UNDERGROUND_WATER_CREATURE, AXOLOTLS -> aquaticColor.get();
            case MONSTER -> hostileColor.get();
            case AMBIENT -> ambientMobColor.get();
            default -> miscMobColor.get();
        };
    }

    public enum SortMode {
        Distance,
        Length,
        Name
    }

    private static class Aggregated {
        String name;
        int count;
        double minDist;
        SettingColor color;
    }

    private record Line(String text, SettingColor color, double width, double minDist) {
    }
}
