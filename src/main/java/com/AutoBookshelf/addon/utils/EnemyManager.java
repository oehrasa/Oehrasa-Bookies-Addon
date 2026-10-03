package com.AutoBookshelf.addon.utils;

import com.mojang.util.UndashedUuid;
import meteordevelopment.meteorclient.systems.System;
import meteordevelopment.meteorclient.systems.Systems;
import meteordevelopment.meteorclient.utils.misc.NbtUtils;
import meteordevelopment.meteorclient.utils.network.MeteorExecutor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class EnemyManager extends System<EnemyManager> implements Iterable<Enemy> {
    private final List<Enemy> enemies = new ArrayList<>();
    // ConcurrentHashMap for thread-safe access from main thread (get) and
    // MeteorExecutor (updateInfo which can rename enemies asynchronously).
    private final Map<String, Enemy> byName = new ConcurrentHashMap<>();

    public EnemyManager() {
        super("enemies");
    }

    public static EnemyManager get() {
        return Systems.get(EnemyManager.class);
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public boolean add(Enemy enemy) {
        if (enemy.name.isEmpty() || enemy.name.contains(" ")) return false;
        if (enemies.contains(enemy)) return false;

        enemies.add(enemy);
        byName.putIfAbsent(key(enemy.name), enemy);
        save();

        MeteorExecutor.execute(enemy::updateInfo);

        return true;
    }

    public boolean add(String name) {
        return name != null && !name.isEmpty() && add(new Enemy(name));
    }

    public boolean add(Player player) {
        return player != null && add(new Enemy(player));
    }

    public boolean remove(Enemy enemy) {
        if (enemies.remove(enemy)) {
            byName.entrySet().removeIf(e -> e.getValue() == enemy);
            save();
            return true;
        }
        return false;
    }

    public boolean remove(String name) {
        if (name == null || name.isEmpty()) return false;
        Enemy enemy = get(name);
        return enemy != null && remove(enemy);
    }

    public boolean remove(Player player) {
        return player != null && remove(player.getName().getString());
    }

    public Enemy get(String name) {
        if (name == null) return null;
        String k = key(name);
        Enemy enemy = byName.get(k);
        if (enemy == null) {
            for (Enemy e : enemies) {
                if (e.name.equalsIgnoreCase(name)) {
                    // Re-index the found enemy under the requested key atomically
                    byName.compute(k, (key, existing) -> existing != null ? existing : e);
                    return e;
                }
            }
            return null;
        }
        // updateInfo() can rename an enemy asynchronously on the executor thread;
        // re-key the map entry if it drifted so the name-index stays consistent.
        if (!key(enemy.name).equals(k)) {
            byName.computeIfAbsent(key(enemy.name), ignored -> enemy);
            // Remove stale key if it still points to this enemy
            byName.remove(k, enemy);
            // After rename, the old name no longer maps to this enemy.
            // Search for an enemy whose current name matches the requested name.
            for (Enemy e : enemies) {
                if (e.name.equalsIgnoreCase(name)) {
                    byName.compute(k, (key, existing) -> existing != null ? existing : e);
                    return e;
                }
            }
            return null;
        }
        return enemy;
    }

    public Enemy get(Player player) {
        return player == null ? null : get(player.getName().getString());
    }

    public Enemy get(PlayerInfo player) {
        return get(player.getProfile().name());
    }

    public boolean isEnemy(Player player) {
        return player != null && get(player) != null;
    }

    public boolean isEnemy(PlayerInfo player) {
        return get(player) != null;
    }

    public boolean isEnemy(String name) {
        return get(name) != null;
    }

    public List<String> getEnemyNames() {
        List<String> names = new ArrayList<>();
        for (Enemy enemy : enemies) names.add(enemy.name);
        return names;
    }

    public int count() {
        return enemies.size();
    }

    public boolean isEmpty() {
        return enemies.isEmpty();
    }

    public void clear() {
        enemies.clear();
        byName.clear();
        save();
    }

    @Override
    public @NotNull Iterator<Enemy> iterator() {
        return enemies.iterator();
    }

    @Override
    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.put("enemies", NbtUtils.listToTag(enemies));
        return tag;
    }

    @Override
    public EnemyManager fromTag(CompoundTag tag) {
        enemies.clear();
        byName.clear();

        for (Tag itemTag : tag.getListOrEmpty("enemies")) {
            CompoundTag enemyTag = (CompoundTag) itemTag;
            if (!enemyTag.contains("name")) continue;

            String name = enemyTag.getStringOr("name", "");
            if (get(name) != null) continue;

            String uuid = enemyTag.getStringOr("id", "");
            Enemy enemy = !uuid.isBlank()
                ? new Enemy(name, UndashedUuid.fromStringLenient(uuid))
                : new Enemy(name);

            enemies.add(enemy);
            byName.putIfAbsent(key(enemy.name), enemy);
        }

        Collections.sort(enemies);

        List<Enemy> snapshot = new ArrayList<>(enemies);
        MeteorExecutor.execute(() -> snapshot.forEach(Enemy::updateInfo));

        return this;
    }
}
