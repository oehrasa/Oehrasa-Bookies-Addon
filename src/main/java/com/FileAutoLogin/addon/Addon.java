package com.FileAutoLogin.addon;

import com.FileAutoLogin.addon.modules.BookshelfFiller;
import com.FileAutoLogin.addon.modules.FileAutoLogin;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import com.FileAutoLogin.addon.modules.*;
import net.minecraft.item.Items;
import org.slf4j.Logger;

public class Addon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static Category CATEGORY = new Category("DortyAddons", Items.DRIED_KELP.getDefaultStack());
	
	
	
    @Override
    public void onInitialize() {
        LOG.info("Initializing FileAutoLogin");
		
		for (Category category : Modules.loopCategories()) {
			if (category.name == "DortyAddons"){
				CATEGORY = category;
				break;
			}
		}
		
        // Modules
        Modules.get().add(new FileAutoLogin(CATEGORY));
        Modules.get().add(new BookshelfFiller(CATEGORY));
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.FileAutoLogin.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("MeteorDevelopment", "meteor-addon-template");
    }
}
