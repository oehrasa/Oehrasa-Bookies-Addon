// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker.search;

import com.AutoBookshelf.addon.modules.chesttracker.TrackerLog;
import com.google.gson.*;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Player-chosen labels for head textures ("Pumpkin pie head"), so every head with that texture is
 * searchable by the label. One file for all servers, since a texture is the same everywhere.
 */
public final class HeadAliases {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file;
    private final Map<String, String> aliases = new LinkedHashMap<>();

    public HeadAliases(Path root) {
        this.file = root.resolve("head-aliases.json");
        load();
    }

    private void load() {
        if (!Files.exists(file)) return;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, JsonElement> e : JsonParser.parseReader(reader).getAsJsonObject().entrySet()) {
                aliases.put(e.getKey(), e.getValue().getAsString());
            }
        } catch (Exception e) {
            TrackerLog.LOGGER.error("Could not read {}; head aliases unavailable this session", file, e);
        }
    }

    public @Nullable String get(@Nullable String hash) {
        return hash == null ? null : aliases.get(hash);
    }

    public Map<String, String> all() {
        return aliases;
    }

    public void set(String hash, String alias) {
        aliases.put(hash, alias);
        save();
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            JsonObject o = new JsonObject();
            aliases.forEach(o::addProperty);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(o), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            TrackerLog.LOGGER.error("Could not save {}", file, e);
        }
    }
}
