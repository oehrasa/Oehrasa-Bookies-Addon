package com.AutoBookshelf.addon.mixin;

import com.AutoBookshelf.addon.utils.EnemyColorManager;
import com.AutoBookshelf.addon.utils.EnemyManager;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = PlayerUtils.class, remap = false)
public class PlayerUtilsMixin {
    // Used only if no module has wired a colour via EnemyColorManager yet.
    private static final Color DEFAULT_ENEMY_COLOR = new Color(255, 85, 85);

    @Shadow
    @Final
    private static Color color;

    @Inject(method = "getPlayerColor", at = @At("HEAD"), cancellable = true)
    private static void addEnemyColor(PlayerEntity entity, Color defaultColor, CallbackInfoReturnable<Color> cir) {
        if (entity.getGameProfile() == null) return;
        if (!EnemyManager.get().isEnemy(entity.getGameProfile().name())) return;

        Setting<SettingColor> colorSetting = EnemyColorManager.getEnemyColorSetting();
        Color target = colorSetting != null ? colorSetting.get() : DEFAULT_ENEMY_COLOR;
        cir.setReturnValue(color.set(target).a(defaultColor.a));
    }
}
