package com.AutoBookshelf.addon.utils;

public class MathUtils {
    public static double toMapQuad(double v) {
        int j = (int) Math.floor((v + 64.0) / 128.0);
        int l = j * 128 + 128 / 2 - 64;
        return (double) l / 128;
    }
}
