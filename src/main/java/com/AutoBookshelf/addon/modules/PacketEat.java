package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;

public class PacketEat extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> deSync = sgGeneral.add(new BoolSetting.Builder()
        .name("de-sync")
        .description("Continuously send interaction packets to desync the eating animation.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> noRelease = sgGeneral.add(new BoolSetting.Builder()
        .name("no-release")
        .description("Cancels the release item packet so the server thinks you are still eating.")
        .defaultValue(true)
        .build()
    );

    public PacketEat() {
        super(Addon.CATEGORY, "PacketEat", "Allows you to eat without interrupting other actions.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        var player = mc.player;
        if (player == null) return;

        if (deSync.get() && player.isUsingItem()) {
            var activeStack = player.getUseItem();
            if (activeStack.get(DataComponents.FOOD) != null) {
                InteractionHand hand = player.getUsedItemHand();
                player.connection.send(
                    new ServerboundUseItemPacket(hand, 0, player.getYRot(), player.getXRot())
                );
            }
        }
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        var player = mc.player;
        if (player == null) return;

        if (noRelease.get() && event.packet instanceof ServerboundPlayerActionPacket packet) {
            if (packet.getAction() == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM) {
                var activeStack = player.getUseItem();
                if (activeStack.get(DataComponents.FOOD) != null) {
                    event.cancel();
                }
            }
        }
    }
}
