# AutoBookshelf
> *How tsundere are You?*

Meteor Addon for Timid bookkeeper and Yuri Enjoyers

XB-70 My beloved <3

## Installation

1. Download the latest JAR from [Releases](https://github.com/oehrasa/Oehrasa-Bookies-Addon/releases)
2. Place in `~/.minecraft/mods` folder
3. Launch Minecraft with Fabric and Meteor Client
4. Access modules via Meteor GUI (Right Shift)

## Dependencies

Tested successfully with these mods, but other might work as well (these are just optinal mod)
- baritone-api-fabric-1.13.1.jar
- BepHax-0.4.4.0.jar
- meteor-client-1.21.4-42.jar
- client_maps-1.3.2+1.21.4.jar (Recommended for Mapart-Namer module)
- map-in-slot-3.3.0.jar (Recommended for Mapart-Namer module)

## Features

**30 modules**, **4 commands**, **5 HUD elements**, and **1 mixin**

## Modules (30 total)
<details>
<summary><b>Modules</b> (30 modules)</summary>

- **AudiobookReader** - Reads books aloud using narrator feature
- **Auto-Beacon** - Builds a 4‑beacon pyramid at a selected location
- **Auto-Login** - Automatically logs in your account via file Data
- **Auto-Moss** - Automatically uses bone meal on specific blocks
- **Auto-Take-Off** - Automatically starts elytra flight when on ground, in lava, or falling
- **Auto-Sex** - Tries to have sex with the player or mob in freaky ways
- **B36 Peacemaker** - Created this to make peace. Named after Convair B-36 Peacemaker
- **Beacon-Range** - Renders the range of powered beacons
- **BLU-27/B-Napalm** - I love the smell of Napalm in the morning, Commit some trolling against the Vietnamese
- **Better-BoatFly** - Transforms your boat into a plane
- **BoatPhase6b6t** - For 6b6t BoatPhase
- **BoatGlitch6b6t** - Dependency for BoatPhase
- **Bookshelf-Filler** - oeh Yuri romcom bookshelves restocker
- **Book-Import** - Automatically imports text files into signed books
- **Cart-Placer** - Places any minecarts on any rails in range
- **Chest-Aura** - High-speed automatic container opener
- **Elytra-Path** - Shows your elytra flight path to destination with smooth movement. better luck next time, Pilots
- **Item-Despawn** - Highlights items that are about to despawn.
- **Map-Grid** - Highlights map grid boundaries around the player
- **Mapart-Namer** - Auto‑names maps based on inventory slot layout
- **Mob-Owner** - Shows entity owner by saving into cache
- **Platform** - Build a platform at a given y-level once in range
- **Press-Frame** - Flatten any nearby item frame because You're an Elite Rank
- **SBB-Restock** - Automatically restocks shulkers and books in your hotbar when used
- **Sculk-Range** - Shows the detection range of calibrated sculk sensors.
- **Throw-Shulkers** - Automatically throws shulker boxes based on their contents
- **Tnt-Fuse-Esp** - Shows the fuse time of lit tnt
- **Trajectory-Plus** - Smooth projectile prediction and tracking
- **Tsundere-Furry** - Transforms outgoing chat messages into animal sounds, tsundere, or both :>
- **Unwax-Aura** - Automatically removes wax from waxed copper blocks

</details>

## HUD Elements (5)

- **Anime-Pics** - Displays random Anime pictures ( Cheers >< )
- **Elytra-Time** - Gives you a rough estimate of the elytra flight time you have left
- **MayaChan** - Render oehrasa beloved OC's : Nishizumi Maya
- **Online-Friends** - Displays online friends from your friend list
- **Map-Viewer** - Displays the contents of held maps on your HUD

## Commands (4)
| Command          | Description                                                                |
|------------------|----------------------------------------------------------------------------|
| `.book`          | Shows book information from your held item                                 |
| `.booktranslate` | Translates the held written book into another language                     |
| `.ifpeek`        | Shows book information from an item frame                                  |
| `.shelf`         | Extracts a book from a chiseled bookshelf slot, reads it, and puts it back |

<details>
<summary>Auto-Login</summary>
- Turn on, login at any server, file 'passwords.txt' will be created
- Add lines in form of 'server_ip nickname password comment_optional'
- Alternative usage: login by command

https://github.com/user-attachments/assets/f9d2825f-a3c3-4b94-a5f9-1155b09b4c64

</details>

<details>
<summary><b>Bookshelf-Filler</b> - Fill Chiseled Bookshelf with books</summary>

1. Enable module
2. Select an area of position using Axe
3. Set position 1 corner
4. Set position 2 corner
5. To reset position just click again with Axe tools
6. Optionally : Hold and click with Gaples to take out book from chiseled bookshelf
7. Optionally : Hold and click with Pickaxes tool to count how many book in chiseled bookshelf area
</details>

<details>
<summary><b>Mapart-Namer</b> - Auto‑names maps based on inventory slot layout</summary>

1. Position mapart in inventory grid
2. Enable the module
3. Open up anvil
4. Note : If the mapart has a longer column (Y) take out the current map (that had been named) and position (stitch) new map to same grid then redo
</details>

<details>
<summary><b>Book-Import</b> - Automatically imports text files into signed books</summary>

1. Enable the module
2. Put the .txt file into folder : \minecraft\AutoBookshelf\books
3. _Recommend to use along side Bookshelf-Filler module_
4. Press set key in the module settings to continue to next file
</details>

## Credits
List of addons I used as reference(skid? mwhehe), You should check them out it's pretty awesome!
- **[FileAutoLogin](https://github.com/DortyTheGreat/FileAutoLogin)** - Base of this addon
- **[Clarity](https://github.com/ck-clarity/addon)** - Boat modules and image HUD
- **[meerhax](https://github.com/dekrom/meeerhax)** - ELytra time HUD
- **[BepHax](https://github.com/dekrom/BepHaxAddon)** - Basically a bunch including this README
- **[lambda meteor utilities](https://github.com/lambda505/lambda-meteor-utilities)** - Online Friends HUD
- **[Numby Hack](https://github.com/cqb13/Numby-hack)** - TntFuseEsp module
- **[InvincibleMachineGun](https://github.com/adaxiaohu/InvincibleMachineGun)** - AutoChestAura module
- **[meteor community addon](https://github.com/lapoliciarobomiquesofrances/meteor-community-addon)** - AutoSex base
- **[hybridious mod](https://github.com/Hybridious/hybridious_mod)** - B36, AutoMoss
- **[lu public](https://github.com/CunnyCorp/lu-public)** - Auto Animal for Tsundere Furry module message, Highlighter for Map Grid
- **[nerv printer addon](https://github.com/Julflips/nerv-printer-addon)** - Map Namer base reference
- **[JanitorAddon](https://github.com/Sleeepyv/JanitorAddon)** - ThrowEmptyShulker
- **[delirious](https://github.com/underscore-zi/delirious)** - Platform Builder

## Contributing

Open an [issue](https://github.com/oehrasa/Oehrasa-Bookies-Addon/issues) or submit a pull request.

## License

[GNU GPLv3](LICENSE) - Free to fork and modify.

## Disclaimer

Designed for anarchy servers like 6b6t.org. Use responsibly.
Ask a question or make a discussions with me on discord : oeh4233

<h1 align="center">
  <img src="https://github.com/user-attachments/assets/0e9c7164-663c-411a-a222-e4416a74131e" alt="Header Image" style="width:70%; max-width:600px;"/>
</h1>

