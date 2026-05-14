package com.AutoBookshelf.addon;

import com.AutoBookshelf.addon.commands.BookTranslateCommand;
import com.AutoBookshelf.addon.hud.MayaChan;
import com.AutoBookshelf.addon.hud.ElytraTime;
import com.AutoBookshelf.addon.hud.OnlineFriendsHUD;
import com.AutoBookshelf.addon.hud.MapViewer;
import com.AutoBookshelf.addon.hud.AnimePics;
import com.AutoBookshelf.addon.commands.IfpeekCommand;
import com.AutoBookshelf.addon.commands.ShelfCommand;
import com.AutoBookshelf.addon.commands.BookCommand;
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

    public static final Category CATEGORY = new Category("Bookshelf", Items.WRITTEN_BOOK.getDefaultStack());

    @Override
    public void onInitialize() {
        LOG.info("Honey, dinner's ready, Identified AutoBookshelf Addon.");

        // Modules
        Modules.get().add(new AutoLogin(CATEGORY));
        Modules.get().add(new BookshelfFiller());
        Modules.get().add(new B36());
        Modules.get().add(new MinecartPlacer());
        Modules.get().add(new SculkRange());
        Modules.get().add(new MobOwner());
        Modules.get().add(new AutoChestAura());
        Modules.get().add(new TrajectoryPlus());
        Modules.get().add(new BetterBoatFly());
        Modules.get().add(new BoatPhase());
        Modules.get().add(new BoatGlitch());
        Modules.get().add(new BeaconRange());
        Modules.get().add(new ShulkBookRestock());
        Modules.get().add(new AutoMoss());
        Modules.get().add(new AutoSex());
        Modules.get().add(new AudiobookReader());
        Modules.get().add(new PlatformBuilder());
        Modules.get().add(new UnwaxAura());
        Modules.get().add(new ItemDespawn());
        Modules.get().add(new TntFuseEsp());
        Modules.get().add(new BookImporter());
        Modules.get().add(new AutoTakeOff());
        Modules.get().add(new ThrowEmptyShulkers());
        Modules.get().add(new AutoBeacon());
        Modules.get().add(new PressItemFrame());
        Modules.get().add(new TsundereFurry());
        Modules.get().add(new MapartNamer());
        Modules.get().add(new MapGridZone());
        Modules.get().add(new BLU27BNapalm());
        Modules.get().add(new ElytraPath());

        // HUD
        Hud.get().register(MayaChan.INFO);
        Hud.get().register(ElytraTime.INFO);
        Hud.get().register(OnlineFriendsHUD.INFO);
        Hud.get().register(MapViewer.INFO);
        Hud.get().register(AnimePics.INFO);

        // COMMANDS
        Commands.add(new IfpeekCommand());
        Commands.add(new ShelfCommand());
        Commands.add(new BookCommand());
        Commands.add(new BookTranslateCommand());

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
