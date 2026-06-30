package com.AutoBookshelf.addon;

import com.AutoBookshelf.addon.commands.*;
import com.AutoBookshelf.addon.hud.*;
import com.AutoBookshelf.addon.modules.*;
import com.AutoBookshelf.addon.utils.JoinPayload;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.item.Items;
import org.slf4j.Logger;

public class Addon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final HudGroup HUD_GROUP = new HudGroup("MayaChan");

    public static final Category CATEGORY = new Category("Bookshelf", Items.WRITTEN_BOOK.getDefaultStack());
    public static final Category CATEGORY2 = new Category("Aurelius", Items.WRITABLE_BOOK.getDefaultStack());
    // Cocceius Fulvius Ulpius

    @Override
    public void onInitialize() {
        LOG.info("Honey, dinner's ready, Identified AutoBookshelf Addon.");

        PayloadTypeRegistry.playC2S().register(JoinPayload.TYPE, JoinPayload.CODEC);

        // Modules
        Modules.get().add(new AutoLogin(CATEGORY));
        Modules.get().add(new AutoPot());
        Modules.get().add(new AutoSex());
        Modules.get().add(new AutoMoss());
        Modules.get().add(new AutoLoader());
        Modules.get().add(new AutoBeacon());
        Modules.get().add(new AutoTakeOff());
        Modules.get().add(new AudiobookReader());
        // B
        Modules.get().add(new B36());
        Modules.get().add(new BlockRadius());
        Modules.get().add(new BLU27BNapalm());
        Modules.get().add(new BookImporter());
        Modules.get().add(new BetterBoatFly());
        Modules.get().add(new BookshelfFiller());
        // C
        Modules.get().add(new ContainerPeek());
        Modules.get().add(new com.AutoBookshelf.addon.modules.chesttracker.ChestTrackerModule());
        // E G
        Modules.get().add(new ElytraPath());
        Modules.get().add(new GetPreview());
        // H
        Modules.get().add(new HomesList());
        // I
        Modules.get().add(new ItemDespawn());
        Modules.get().add(new InventoryInfo());
        // K
        Modules.get().add(new KMDB());
        // M
        Modules.get().add(new MobOwner());
        Modules.get().add(new MapartNamer());
        Modules.get().add(new MapGridZone());
        Modules.get().add(new MinecartPlacer());
        Modules.get().add(new MaterialsRefill());
        // P
        Modules.get().add(new PacketEat());
        Modules.get().add(new PortalCave());
        Modules.get().add(new PressItemFrame());
        Modules.get().add(new PlatformBuilder());
        // S
        Modules.get().add(new SculkRange());
        Modules.get().add(new ShulkBookRestock());
        // T
        Modules.get().add(new TntFuseEsp());
        Modules.get().add(new TsundereFurry());
        Modules.get().add(new TrajectoryPlus());
        Modules.get().add(new ThrowEmptyShulkers(CATEGORY2));
        // U
        Modules.get().add(new UnwaxAura());

        // HUD
        Hud.get().register(AnimePics.INFO);
        Hud.get().register(ElytraTime.INFO);
        Hud.get().register(MayaChan.INFO);
        Hud.get().register(MapViewer.INFO);
        Hud.get().register(NeboM.INFO);
        Hud.get().register(OnlineFriendsHUD.INFO);
        Hud.get().register(TeleportTimer.INFO);

        // COMMANDS
        Commands.add(new BookCommand());
        Commands.add(new BookTranslateCommand());
        Commands.add(new IfpeekCommand());
        Commands.add(new ShelfCommand());
        Commands.add(new AssignOwnerCommand());

    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
        Modules.registerCategory(CATEGORY2);
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
