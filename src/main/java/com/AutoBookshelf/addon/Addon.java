package com.AutoBookshelf.addon;

import com.AutoBookshelf.addon.hud.MayaChan;
import com.AutoBookshelf.addon.hud.ElytraTime;
import com.AutoBookshelf.addon.commands.IfpeekCommand;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import com.AutoBookshelf.addon.modules.*;
import net.minecraft.item.Items;
import org.slf4j.Logger;

public class Addon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final HudGroup HUD_GROUP = new HudGroup("MayaChan");
    public static Category CATEGORY = new Category("DortyAddons", Items.DRIED_KELP.getDefaultStack());
	
	
	
    @Override
    public void onInitialize() {
        LOG.info("Honey, dinner's ready, Identified AutoBookshelf");
		
		for (Category category : Modules.loopCategories()) {
			if (category.name == "DortyAddons"){
				CATEGORY = category;
				break;
			}
		}
		
        // Modules
        Modules.get().add(new AutoLogin(CATEGORY));
        Modules.get().add(new BookshelfFiller());
        Modules.get().add(new B36());
        Modules.get().add(new MinecartPlacer());
        Modules.get().add(new CalibratedRange());
        Modules.get().add(new CrackMobOwner());
        Modules.get().add(new AutoChestAura());
        Modules.get().add(new ProjectilePredict());
        Modules.get().add(new BetterBoatFly());
        Modules.get().add(new BeaconRangeModule());

        // HUD
        Hud.get().register(MayaChan.INFO);
        Hud.get().register(ElytraTime.INFO);

        // COMMANDS
        Commands.add(new IfpeekCommand());

    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.AutoBookshelf.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("MeteorDevelopment", "meteor-addon-template");
    }
}
