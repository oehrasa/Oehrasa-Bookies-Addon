package com.AutoBookshelf.addon.utils;

import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

public class EnemyColorManager {
    private static Setting<SettingColor> enemyColorSetting;

    public static Setting<SettingColor> getEnemyColorSetting() {
        return enemyColorSetting;
    }
}
