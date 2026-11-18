/*
 * This file is part of the AutoBookshelf addon distribution (https://github.com/oehrasa/Oehrasa-Bookies-Addon).
 * Copyright (c) oehrasa and AutoBookshelf contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.AutoBookshelf.addon.utils;

import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

public class EnemyColorManager {
    private static Setting<SettingColor> enemyColorSetting;

    public static Setting<SettingColor> getEnemyColorSetting() {
        return enemyColorSetting;
    }
}
