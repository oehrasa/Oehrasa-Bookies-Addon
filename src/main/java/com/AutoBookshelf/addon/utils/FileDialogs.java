package com.AutoBookshelf.addon.utils;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * File pickers for the two features that used LWJGL's tinyfd before 26.3 dropped it from the
 * classpath. Swing ships with the JDK, so nothing has to be bundled and no natives are needed.
 * <p>
 * The dialogs are modal and only opened from a user click, so blocking the calling thread matches
 * the old tinyfd behaviour. A missing display (headless or AWT unavailable) returns null, and every
 * caller already treats null as "user cancelled" and keeps its typed path setting.
 */
public final class FileDialogs {
    private FileDialogs() {
    }

    public static String openFile(String title, File directory, String description, String... extensions) {
        return show(directory, chooser -> {
            if (extensions.length > 0) chooser.setFileFilter(new FileNameExtensionFilter(description, extensions));
            return chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION
                ? chooser.getSelectedFile().getAbsolutePath()
                : null;
        }, title);
    }

    public static String saveFile(String title, File suggested, String description, String... extensions) {
        return show(suggested.getParentFile(), chooser -> {
            chooser.setSelectedFile(suggested);
            if (extensions.length > 0) chooser.setFileFilter(new FileNameExtensionFilter(description, extensions));
            return chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION
                ? chooser.getSelectedFile().getAbsolutePath()
                : null;
        }, title);
    }

    public static String chooseDirectory(String title, File directory) {
        return show(directory, chooser -> {
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            return chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION
                ? chooser.getSelectedFile().getAbsolutePath()
                : null;
        }, title);
    }

    private static String show(File directory, Function<JFileChooser, String> action, String title) {
        AtomicReference<String> result = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle(title);
                if (directory != null) chooser.setCurrentDirectory(directory);
                result.set(action.apply(chooser));
            });
        } catch (Throwable t) {
            return null;
        }
        return result.get();
    }
}
