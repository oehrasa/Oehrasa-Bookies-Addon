package com.AutoBookshelf.addon.hud;

import java.util.List;

/**
 * Plain metadata holder for the image/GIF currently loaded into an AnimePics HUD element.
 * Populated by whichever fetch*() method resolved the image URL. Fields are left null/0
 * when a source doesn't provide that data (example: NekosLife has no author or tags).
 */
public class ImageMetadata {
    public String url;
    public String sourceSite;
    public String postUrl;
    public String author;
    public String sourceOrigin;
    public List<String> tags;
    public String rating;
    public int width;
    public int height;

    public ImageMetadata(String url, String sourceSite) {
        this.url = url;
        this.sourceSite = sourceSite;
    }
}
