// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
package com.AutoBookshelf.addon.modules.chesttracker.search;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Profile name and skin texture hash of a player head (CLAUDE.md 4.5), read from the item's
 * encoded data rather than authlib classes so it doesn't depend on the authlib version and
 * survives ViaVersion translation. Decorative heads often have only a texture.
 */
public record HeadInfo(@Nullable String profileName, @Nullable String textureHash) {
    public static @Nullable HeadInfo of(ItemStack stack) {
        if (!stack.has(DataComponents.PROFILE)) return null;
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return null;
        JsonElement encoded = ItemStack.CODEC.encodeStart(client.level.registryAccess().createSerializationContext(JsonOps.INSTANCE), stack)
            .result().orElse(null);
        if (encoded == null || !encoded.isJsonObject()) return null;
        JsonObject components = encoded.getAsJsonObject().getAsJsonObject("components");
        if (components == null || !components.has("minecraft:profile")) return null;
        JsonElement profile = components.get("minecraft:profile");

        // Either just a name ("Notch") or {name?, id?, properties?: [{name: "textures", value: base64}]}.
        if (profile.isJsonPrimitive()) return new HeadInfo(profile.getAsString(), null);
        if (!profile.isJsonObject()) return null;
        JsonObject p = profile.getAsJsonObject();
        String name = p.has("name") ? p.get("name").getAsString() : null;
        String hash = null;
        if (p.has("properties") && p.get("properties").isJsonArray()) {
            for (JsonElement prop : p.getAsJsonArray("properties")) {
                JsonObject o = prop.getAsJsonObject();
                if (o.has("name") && "textures".equals(o.get("name").getAsString()) && o.has("value")) {
                    hash = textureHash(o.get("value").getAsString());
                }
            }
        }
        return name == null && hash == null ? null : new HeadInfo(name, hash);
    }

    /**
     * The last path segment of the skin URL inside the base64 "textures" value.
     */
    private static @Nullable String textureHash(String base64) {
        try {
            String json = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
            String url = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("textures")
                .getAsJsonObject("SKIN").get("url").getAsString();
            return url.substring(url.lastIndexOf('/') + 1);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
