package com.AutoBookshelf.addon.utils;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.network.ServerInfo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class Checks {

    private static final Logger LOGGER = Logger.getLogger("AnarchyMod-Domains");
    private static final String DOMAINS_URL = "https://www.6b6t.org/api/anarchy-mod.json";

    private static final Set<String> DEFAULT = Set.of(
        "*.6b6t.org", "*.10b10t.org", "*.6b6t.cc", "*.6b6t.me",
        "*.7b7t.me", "*.8b8t.org", "*.alacity.net",
        "*.anarchypvp.pw", "*.l2x9.org", "*.simpleanarchy.org"
    );

    private static final Set<String> domains = new HashSet<>();
    private static final Gson gson = new Gson();
    private static final HttpClient httpClient = HttpClient.newHttpClient();

    static {
        domains.addAll(DEFAULT);
    }

    public static void init() {
        loadRemoteAsync();
    }

    private static void loadRemoteAsync() {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(DOMAINS_URL))
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenAccept(Checks::onRemoteResponse)
            .exceptionally(t -> {
                LOGGER.warning("Failed to load domains from remote, using defaults: " + t.getMessage());
                return null;
            });
    }

    private static void onRemoteResponse(HttpResponse<String> response) {
        try {
            JsonObject json = gson.fromJson(response.body(), JsonObject.class);
            if (json == null) return;

            JsonArray array = json.getAsJsonArray("domains");
            if (array == null) return;

            array.forEach(element ->
                domains.add(element.getAsString().toLowerCase(Locale.ROOT))
            );
        } catch (Throwable t) {
            LOGGER.warning("Failed to parse remote domains response, using defaults: " + t.getMessage());
        }
    }

    public static boolean contains(String input) {
        if (input == null) return false;

        String domain = input.toLowerCase(Locale.ROOT);

        if (domain.startsWith("*.")) {
            domain = domain.substring(2);
        }

        int colon = domain.indexOf(':');
        if (colon != -1) {
            domain = domain.substring(0, colon);
        }

        for (String entry : domains) {
            if (entry.startsWith("*.")) {
                String base = entry.substring(2);
                if (domain.equals(base) || domain.endsWith("." + base)) {
                    return true;
                }
            } else {
                if (domain.equals(entry)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * <a href="https://github.com/6b6t/AnarchyMod/blob/main/src/main/java/net/blockhost/anarchymod/Domains.java">Source</a>
     */
    public static boolean is6B6T() {
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) return true; // Bypass check in dev environment
        ServerInfo server = mc.getCurrentServerEntry();
        if (server == null || server.isLocal()) return false;
        return contains(server.address);
    }
}
