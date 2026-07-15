package com.AutoBookshelf.addon.mixin;

import com.AutoBookshelf.addon.utils.Checks;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ServerList.class)
public class ServerListMixin {

    @Shadow
    @Final
    private List<ServerInfo> servers;

    @Inject(method = "loadFile", at = @At("RETURN"))
    public void afterLoad(CallbackInfo ci) {
        if (servers.stream().noneMatch(data -> Checks.contains(data.address))) {
            //? if <1.20.2 {
            /*servers.add(0, new ServerInfo("6b6t", "6b6t.org", false));
             *///? } elif <1.20.5 {
            /*servers.add(0, new ServerInfo("6b6t", "6b6t.org", ServerInfo.ServerType.OTHER));
             *///?} else {
            servers.addFirst(new ServerInfo("6b6t", "6b6t.org", ServerInfo.ServerType.OTHER));
            //?}
        }
    }
}
