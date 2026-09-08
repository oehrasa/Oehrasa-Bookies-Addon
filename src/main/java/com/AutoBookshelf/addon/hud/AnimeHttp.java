package com.AutoBookshelf.addon.hud;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public final class AnimeHttp {

    private static final String USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 15000;

    private AnimeHttp() {
    }

    public record Response(int statusCode, String statusMessage, String body) {
    }

    private static HttpURLConnection open(String urlString) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(urlString).toURL().openConnection();
        SSLHelper.configure(conn);

        conn.setRequestProperty("accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
        conn.setRequestProperty("accept-language", "en-US,en;q=0.8");
        conn.setRequestProperty("priority", "u=0, i");
        conn.setRequestProperty("sec-ch-ua", "\"Not=A?Brand\";v=\"99\", \"Chromium\";v=\"151\"");
        conn.setRequestProperty("sec-ch-ua-mobile", "?0");
        conn.setRequestProperty("sec-ch-ua-platform", "\"Windows\"");
        conn.setRequestProperty("sec-fetch-dest", "document");
        conn.setRequestProperty("sec-fetch-mode", "navigate");
        conn.setRequestProperty("sec-fetch-site", "none");
        conn.setRequestProperty("sec-fetch-user", "?1");
        conn.setRequestProperty("sec-gpc", "1");
        conn.setRequestProperty("upgrade-insecure-requests", "1");
        conn.setRequestProperty("user-agent", USER_AGENT);

        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        return conn;
    }

    /**
     * GETs a URL and returns the status code, message, and body as text.
     */
    public static Response get(String urlString) throws IOException {
        HttpURLConnection conn = open(urlString);
        try {
            int statusCode = conn.getResponseCode();
            String statusMessage = conn.getResponseMessage();

            StringBuilder body = new StringBuilder();
            InputStream stream = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (stream != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line);
                }
            }

            return new Response(statusCode, statusMessage, body.toString());
        } finally {
            conn.disconnect();
        }
    }

    public static JsonElement getJson(String urlString) throws IOException {
        Response res = get(urlString);
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new IOException("HTTP " + res.statusCode() + " " + res.statusMessage() + " from " + urlString);
        }
        return JsonParser.parseString(res.body());
    }

    public static byte[] getBytes(String urlString) throws IOException {
        HttpURLConnection conn = open(urlString);
        try (InputStream in = conn.getInputStream(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] data = new byte[16384];
            int nRead;
            while ((nRead = in.read(data, 0, data.length)) != -1) buffer.write(data, 0, nRead);
            return buffer.toByteArray();
        } finally {
            conn.disconnect();
        }
    }
}
