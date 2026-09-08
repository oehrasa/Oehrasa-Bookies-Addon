package com.AutoBookshelf.addon.modules.remote;

import com.google.gson.Gson;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Fetches the GitHub Pages hosted index.json manifest
// and individual .txt book files over plain HTTP.
public class RemoteLibrary {
    private static final Gson GSON = new Gson();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private static class Manifest {
        long generated;
        List<BookEntry> books;
    }

    public static List<BookEntry> fetchManifest(String manifestUrl) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(manifestUrl))
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/json")
            .GET()
            .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != 200) {
            throw new IOException("Manifest fetch failed: HTTP " + response.statusCode() + " (" + manifestUrl + ")");
        }

        Manifest manifest = GSON.fromJson(response.body(), Manifest.class);
        if (manifest == null || manifest.books == null) return new ArrayList<>();
        return manifest.books;
    }

    // Downloads a book's .txt content and splits it into lines, same shape convertLinesToPages() expects.
    public static List<String> fetchLines(String fileUrl) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(fileUrl))
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != 200) {
            throw new IOException("File fetch failed: HTTP " + response.statusCode() + " (" + fileUrl + ")");
        }

        String body = response.body().replace("\r\n", "\n").replace("\r", "\n");
        return Arrays.asList(body.split("\n", -1));
    }

    private RemoteLibrary() {
    }
}
