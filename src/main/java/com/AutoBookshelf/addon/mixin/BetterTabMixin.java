package com.AutoBookshelf.addon.mixin;

import com.AutoBookshelf.addon.utils.EnemyColorManager;
import com.AutoBookshelf.addon.utils.EnemyManager;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.modules.render.BetterTab;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = BetterTab.class, remap = false)
public class BetterTabMixin {
    // Used only if no module has wired a colour via EnemyColorManager yet.
    private static final Color DEFAULT_ENEMY_COLOR = new Color(255, 85, 85);

    @Inject(
        method = "getPlayerName",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/PlayerListEntry;getDisplayName()Lnet/minecraft/text/Text;", remap = true, ordinal = 0)
    )
    private void addEnemyColor(PlayerListEntry entry, CallbackInfoReturnable<Text> cir, @Local LocalRef<Color> colorRef) {
        if (entry.getProfile() == null) return;
        if (!EnemyManager.get().isEnemy(entry.getProfile().name())) return;

        Setting<SettingColor> colorSetting = EnemyColorManager.getEnemyColorSetting();
        colorRef.set(colorSetting != null ? colorSetting.get() : DEFAULT_ENEMY_COLOR);
    }
}
