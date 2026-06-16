<div align="center">
  <!-- Logo and Title -->
  <img src="https://github.com/user-attachments/assets/4171fbfb-461a-4512-a6e5-74458512f429" alt="logo" width="30%"/>
  <h1>AutoBookshelf</h1>
  <p>Meteor Addon for Timid bookkeeper and Yuri Enjoyers</p>
<img src="https://img.shields.io/badge/Meteor Addon-6f1ab1?logo=meteor&logoColor=white" alt="Meteor Addon"/>
<img src="https://img.shields.io/github/repo-size/oehrasa/Oehrasa-Bookies-Addon?color=magenta" alt="Repo Size">
<img src="https://img.shields.io/github/release/oehrasa/Oehrasa-Bookies-Addon?color=blue" alt="Release">
<a href="https://github.com/oehrasa/Oehrasa-Bookies-Addon/commits/main"><img src="https://img.shields.io/github/last-commit/oehrasa/Oehrasa-Bookies-Addon?logo=github&color=light_green" alt="Last commit"></a>
<img src="https://img.shields.io/github/stars/oehrasa/Oehrasa-Bookies-Addon?style=flat&color=yellow" alt="Stars">
<img src="https://img.shields.io/github/downloads/oehrasa/Oehrasa-Bookies-Addon/total?color=red" alt="Downloads">
</div>

## Installation

1. Download the latest JAR from [Releases](https://github.com/oehrasa/Oehrasa-Bookies-Addon/releases)
2. Place in `~/.minecraft/mods` folder
3. Launch Minecraft with Fabric and Meteor Client
4. Access modules via Meteor GUI (Right Shift)

## Dependencies

Tested successfully with these mods, but other might work as well
- baritone-meteor-1.21.11.jar
- BepHax-Final.jar
- meteor-client-1.21.11-65.jar
- client_maps-1.3.2.jar (Recommended for Mapart-Namer module)
- map-in-slot-3.4.1.jar (Recommended for Mapart-Namer module)

## Features

**36 modules**, **5 commands**, **6 HUD elements**, and **6 mixin**

## Modules (36 total)
<details>
<summary><b>Modules</b> (Bookies modules)</summary>

- **AudiobookReader** - Reads books aloud using narrator feature
- **Auto-Beacon** - Builds a 4‑beacon pyramid at a selected location
- **Auto-Login** - Automatically logs in your account via file Data
- **Auto-Moss** - Automatically uses bone meal on specific blocks
- **Auto-Pot** - Amrita
- **Auto-Take-Off** - Automatically starts elytra flight when on ground, in lava, or falling
- **Auto-Sex** - Tries to have sex with the player or mob in freaky ways
- **B36-Peacemaker** - Created this to make peace. Named after Convair B-36 Peacemaker
- **Beacon-Range** - Renders the range of powered beacons
- **BLU-27/B-Napalm** - I love the smell of Napalm in the morning, Commit some trolling against the Vietnamese
- **Better-BoatFly** - Transforms your boat into a plane
- **Bookshelf-Filler** - oeh Yuri romcom bookshelves restocker
- **Book-Import** - Automatically imports text files into signed books
- **Cart-Placer** - Places any minecarts on any rails in range
- **Chest-Aura** - High-speed automatic container opener
- **Chest-Tracker** - Track items in containers
- **Dried-Ghast** - Give these cute and tiny little creatures a second chances
- **Container-Peek** - Displays the tracked contents from Chest-Tracker when you look at the block
- **Elytra-Path** - Shows your elytra flight path to destination with smooth movement. better luck next time, Pilots
- **Get-Preview** - Shows an item preview overlay on bundles, shulkers, and books
- **Item-Despawn** - Highlights items that are about to despawn
- **KMDB** - Builds a Wither, Iron Golem, or Snow Golem in front of you
- **Map-Grid** - Highlights map grid boundaries around the player
- **Mapart-Namer** - Auto‑names maps based on inventory slot layout
- **Mob-Owner** - Shows entity owner by saving into cache
- **Mats-Refill** - Automatically restocks materials from shulker boxes
- **PacketEat** - Allows you to eat without interrupting other actions
- **Platform** - Build a platform at a given y-level once in range
- **Press-Frame** - Flatten any nearby item frame because You're an Elite Rank
- **Portal-Cave** - Scans for the shapes of broken/removed Nether Portals within the cave air blocks found in caves and underground structures in 1.13+ chunks
- **SBB-Restock** - Automatically restocks shulkers and books in your hotbar when used
- **Throw-Shulkers** - Automatically throws shulker boxes based on their contents
- **Tnt-Fuse-Esp** - Shows the fuse time of lit tnt
- **Trajectory-Plus** - Smooth projectile prediction and tracking
- **Tsundere-Furry** - Transforms outgoing chat messages into animal sounds, tsundere, or both :>
- **Unwax-Aura** - Automatically removes wax from waxed copper blocks

</details>

## HUD Elements (6)

- **Anime-Pics** - Displays random Anime pictures ( Cheers >< )
- **Elytra-Time** - Gives you a rough estimate of the elytra flight time you have left
- **MayaChan** - Render oehrasa beloved OC's : Nishizumi Maya
- **NeboM** - The radar system claims to be able to detect 5th generation aircraft (Loud Incorrect Buzzer noise)
- **Online-Friends** - Displays online friends from your friend list
- **Teleport-Timer** - Shows a countdown bar on pending teleportation

## Commands (5)
| Command          | Description                                                                |
|------------------|----------------------------------------------------------------------------|
| `.assowner`      | Assign a cracked account name as the owner of the entity you're looking at |
| `.book`          | Shows book information from your held item                                 |
| `.booktranslate` | Translates the held written book into another language                     |
| `.ifpeek`        | Shows book information from an item frame                                  |
| `.shelf`         | Extracts a book from a chiseled bookshelf slot, reads it, and puts it back |

## TUTORIALS
<details>
<summary><b>Auto-Login</b></summary>
- Turn on, login at any server, file 'passwords.txt' will be created
- Add lines in form of 'server_ip nickname password comment_optional'
- Alternative usage: login by command

https://github.com/user-attachments/assets/f9d2825f-a3c3-4b94-a5f9-1155b09b4c64

</details>

<details>
<summary><b>Bookshelf-Filler</b> - Fill Chiselled Bookshelf with books</summary>

1. Enable module
2. Select an area of position using Axe
3. Set position 1 corner
4. Set position 2 corner
5. To reset position just click again with Axe tools
6. Optionally : Hold and click with Gaples to take out book from chiselled bookshelf
7. Optionally : Hold and click with Pickaxes tool to count how many book in chiselled bookshelf area
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
2. Put the .txt file into folder : `\minecraft\AutoBookshelf\books`
3. _Recommend to use alongside Bookshelf-Filler module_
4. Press set key in the module settings to continue to next file
> I will post the Python code soon
</details>

<details>
<summary><b>Cart-Placer</b> - Do NOT the USS Ohio</summary>
- Think of your decision before arming the launchers with tomahawk missiles
    
https://github.com/user-attachments/assets/023f2df1-003e-4c12-bd4f-9119999ad11e
</details>

## Credits
List of addons I used as reference(skid? mwhehe), You should check them out it's pretty awesome!
- **[FileAutoLogin](https://github.com/DortyTheGreat/FileAutoLogin)** - Base of this addon
- **[Clarity](https://github.com/ck-clarity/addon)** - Boat module and image HUD
- **[meerhax](https://github.com/dekrom/meeerhax)** - ELytra time HUD
- **[BepHax](https://github.com/dekrom/BepHaxAddon)** - Basically a bunch including this README
- **[lambda meteor utilities](https://github.com/lambda505/lambda-meteor-utilities)** - Online Friends HUD
- **[Numby Hack](https://github.com/cqb13/Numby-hack)** - TntFuseEsp module
- **[InvincibleMachineGun](https://github.com/adaxiaohu/InvincibleMachineGun)** - AutoChestAura module
- **[meteor community addon](https://github.com/lapoliciarobomiquesofrances/meteor-community-addon)** - AutoSex base
- **[hybridious mod](https://github.com/Hybridious/hybridious_mod)** - B36, AutoMoss
- **[lu public](https://github.com/CunnyCorp/lu-public)** - Auto Animal, Highlighter
- **[nerv printer addon](https://github.com/Julflips/nerv-printer-addon)** - Map Namer base reference
- **[JanitorAddon](https://github.com/Sleeepyv/JanitorAddon)** - ThrowEmptyShulker module
- **[delirious](https://github.com/underscore-zi/delirious)** - Platform Builder module
- **[Trouser](https://github.com/etianl/Trouser-Streak)** - PortalPatternFinder module
- **[MeteorPlusPlus](https://github.com/zychen027/MeteorPlusPlusAddon)** - PacketEat

## Contributing

Open an [issue](https://github.com/oehrasa/Oehrasa-Bookies-Addon/issues) or submit a pull request.

## License

[GNU GPLv3](LICENSE) - Free to fork and modify.

> [!IMPORTANT]
>## Disclaimer
>Designed for anarchy servers like 6b6t.org. Use responsibly.
> 
>Ask a question or make a discussions with me on discord : oeh4233

<h1 align="center">
  <img src="https://github.com/user-attachments/assets/0e9c7164-663c-411a-a222-e4416a74131e" alt="Header Image" style="width:70%; max-width:600px;"/>
</h1>

