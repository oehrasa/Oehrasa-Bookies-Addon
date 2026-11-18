/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.modules.AttackAircraft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

public final class BoatShotCover {
    private BoatShotCover() {
    }

    public record Point(double x, double y, double z) {
        public double distance(Point p) {
            return Math.sqrt((x - p.x) * (x - p.x) + (y - p.y) * (y - p.y) + (z - p.z) * (z - p.z));
        }
    }

    public record Route(List<Point> points) {
        public Route {
            points = List.copyOf(points);
        }

        public double length(Point from) {
            double n = 0;
            for (Point p : points) {
                n += from.distance(p);
                from = p;
            }
            return n;
        }
    }

    public static Point step(Point delta, double horizontal, double vertical) {
        double distance = delta.distance(new Point(0, 0, 0)), h = Math.hypot(delta.x, delta.z);
        if (!Double.isFinite(distance + horizontal + vertical) || horizontal <= 0 || vertical <= 0) return new Point(0, 0, 0);
        double scale = Math.min(1, 3 / Math.max(3, distance));
        if (h > 0) scale = Math.min(scale, horizontal / h);
        if (delta.y != 0) scale = Math.min(scale, vertical / Math.abs(delta.y));
        return new Point(delta.x * scale, delta.y * scale, delta.z * scale);
    }

    public static Route find(Point from, Point targetTop, double ceiling,
                             Predicate<Point> firing, BiPredicate<Point, Point> travel) {
        if (!Double.isFinite(from.x + from.y + from.z + targetTop.x + targetTop.y + targetTop.z + ceiling)) return null;
        if (firing.test(from)) return new Route(List.of(from));
        List<Point> candidates = new ArrayList<>();
        for (double height : new double[]{4, 8, 16, 28}) {
            double y = targetTop.y + height;
            if (y > ceiling) continue;
            candidates.add(new Point(targetTop.x, y, targetTop.z));
            for (double radius : new double[]{4, 8, 14})
                for (int i = 0; i < 8; i++) {
                    double angle = i * Math.PI / 4;
                    candidates.add(new Point(targetTop.x + Math.cos(angle) * radius, y, targetTop.z + Math.sin(angle) * radius));
                }
        }
        candidates.sort(Comparator.comparingDouble(from::distance));
        Route best = null;
        double shortest = Double.POSITIVE_INFINITY;
        for (Point p : candidates) {
            if (from.distance(p) > 96 || from.distance(p) >= shortest || !firing.test(p)) continue;
            for (Route route : List.of(new Route(List.of(p)),
                new Route(List.of(new Point(p.x, from.y, p.z), p)),
                new Route(List.of(new Point(from.x, p.y, from.z), p)))) {
                double length = route.length(from);
                if (length >= shortest) continue;
                Point previous = from;
                boolean clear = true;
                for (Point next : route.points) {
                    if (!travel.test(previous, next)) {
                        clear = false;
                        break;
                    }
                    previous = next;
                }
                if (clear) {
                    best = route;
                    shortest = length;
                }
            }
        }
        return best;
    }
}
