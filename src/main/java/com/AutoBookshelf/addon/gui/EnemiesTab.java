package com.AutoBookshelf.addon.gui;

import com.AutoBookshelf.addon.utils.Enemy;
import com.AutoBookshelf.addon.utils.EnemyManager;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.tabs.Tab;
import meteordevelopment.meteorclient.gui.tabs.TabScreen;
import meteordevelopment.meteorclient.gui.tabs.WindowTabScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WMinus;
import meteordevelopment.meteorclient.gui.widgets.pressable.WPlus;
import meteordevelopment.meteorclient.utils.misc.NbtUtils;
import meteordevelopment.meteorclient.utils.network.MeteorExecutor;
import net.minecraft.client.gui.screens.Screen;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class EnemiesTab extends Tab {
    public EnemiesTab() {
        super("Enemies");
    }

    @Override
    public TabScreen createScreen(GuiTheme theme) {
        return new EnemiesScreen(theme, this);
    }

    @Override
    public boolean isScreen(Screen screen) {
        return screen instanceof EnemiesScreen;
    }

    private static class EnemiesScreen extends WindowTabScreen {
        public EnemiesScreen(GuiTheme theme, Tab tab) {
            super(theme, tab);
        }

        @Override
        public void initWidgets() {
            WTable table = add(theme.table()).expandX().minWidth(400).widget();
            initTable(table);

            add(theme.horizontalSeparator()).expandX();

            WHorizontalList list = add(theme.horizontalList()).expandX().widget();

            WTextBox nameW = list.add(theme.textBox("", (text, c) -> c != ' ')).expandX().widget();
            nameW.setFocused(true);

            WPlus add = list.add(theme.plus()).widget();
            add.action = () -> {
                String name = nameW.get().trim();
                Enemy enemy = new Enemy(name);

                if (EnemyManager.get().add(enemy)) {
                    nameW.set("");
                    initTable(table);
                    nameW.setFocused(true);

                    // add() already dispatched updateInfo() on MeteorExecutor; just refresh once it lands
                    MeteorExecutor.execute(() -> mc.execute(() -> {
                        initTable(table);
                        nameW.setFocused(true);
                    }));
                }
            };

            enterAction = add.action;
        }

        private void initTable(WTable table) {
            table.clear();
            if (EnemyManager.get().isEmpty()) return;

            EnemyManager.get().forEach(enemy ->
                MeteorExecutor.execute(() -> {
                    if (enemy.headTextureNeedsUpdate()) {
                        enemy.updateInfo();
                    }
                })
            );

            for (Enemy enemy : EnemyManager.get()) {
                table.add(theme.texture(32, 32, enemy.getHead().needsRotate() ? 90 : 0, enemy.getHead()));
                table.add(theme.label(enemy.getName()));

                WMinus remove = table.add(theme.minus()).expandCellX().right().widget();
                remove.action = () -> {
                    EnemyManager.get().remove(enemy);
                    initTable(table);
                };

                table.row();
            }
        }

        @Override
        public boolean toClipboard() {
            return NbtUtils.toClipboard(EnemyManager.get());
        }

        @Override
        public boolean fromClipboard() {
            return NbtUtils.fromClipboard(EnemyManager.get());
        }
    }
}
