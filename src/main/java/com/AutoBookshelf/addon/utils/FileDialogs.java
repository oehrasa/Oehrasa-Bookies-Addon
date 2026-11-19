/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.utils;

import javax.swing.JFileChooser;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Modal JDK file pickers for the 26.3+ nodes, where the tinyfd LWJGL module is gone.
 * Backed by {@link JFileChooser}, whose dialogs run on the AWT event dispatch thread.
 * Every caller treats {@code null} as "cancelled" and keeps its typed-path setting, so a
 * headless or AWT-less client degrades to the setting instead of crashing.
 */
public final class FileDialogs {
    private FileDialogs() {
    }

    public static String chooseFile(String title, String defaultPath) {
        return runDialog(title, defaultPath, false, false);
    }

    public static String saveFile(String title, String defaultPath) {
        return runDialog(title, defaultPath, true, false);
    }

    public static String chooseDirectory(String title, String defaultPath) {
        return runDialog(title, defaultPath, false, true);
    }

    private static String runDialog(String title, String defaultPath, boolean save, boolean directory) {
        if (GraphicsEnvironment.isHeadless()) return null;
        AtomicReference<String> result = new AtomicReference<>(null);
        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle(title);
                chooser.setFileSelectionMode(directory ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
                if (defaultPath != null && !defaultPath.isEmpty()) {
                    File f = new File(defaultPath);
                    if (directory && f.isDirectory()) {
                        chooser.setCurrentDirectory(f);
                    } else if (f.getParentFile() != null && f.getParentFile().isDirectory()) {
                        chooser.setCurrentDirectory(f.getParentFile());
                        if (f.isFile()) chooser.setSelectedFile(f);
                    } else if (directory || f.isDirectory()) {
                        chooser.setCurrentDirectory(f.isDirectory() ? f : new File(defaultPath));
                    }
                }
                int clicked = save ? chooser.showSaveDialog((Component) null) : chooser.showOpenDialog((Component) null);
                if (clicked == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
                    result.set(chooser.getSelectedFile().getAbsolutePath());
                }
            });
        } catch (InterruptedException | java.lang.reflect.InvocationTargetException | RuntimeException e) {
            return null;
        }
        return result.get();
    }
}
