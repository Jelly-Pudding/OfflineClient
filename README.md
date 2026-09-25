# OfflineClient
A utility client custom designed for the anarchy lifesteal server [minecraftoffline.net](https://www.minecraftoffline.net/). This is a fabric mod for Minecraft 26.3.

## Download
The latest jar is on the [releases page](https://github.com/Jelly-Pudding/OfflineClient/releases/latest). It is a single file that needs nothing besides Fabric Loader for Minecraft 26.3.

### Vanilla Launcher
1. Download the latest release [here](https://github.com/Jelly-Pudding/OfflineClient/releases/latest).
2. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 26.3 and run it.
3. Check the path to your `.minecraft` directory is correctly set and click `install`.
4. Drop the OfflineClient jar into your `mods` folder. Create the `mods` folder inside `.minecraft` if it does not exist.
5. Launch the game with the Fabric profile.

### MultiMC
1. Download the latest release [here](https://github.com/Jelly-Pudding/OfflineClient/releases/latest).
2. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 26.3 and run it.
3. Check the path to your `.minecraft` directory is correctly set and click `install`.
4. Download [MultiMC](https://multimc.org/#Download).
5. Open MultiMC and click `Add Instance`. Choose a name and pick Minecraft 26.3. Click `OK` at the bottom.
6. Right click the instance and choose `Edit Instance`. On the right hand side click `Install Fabric` and pick a Fabric version.
7. On the left hand side click `Loader mods` then `Add` and pick the OfflineClient jar you downloaded.

## Usage
- **RIGHT SHIFT** opens the ClickGUI. Its Style setting switches between draggable panels and a single window with a sidebar.
- Chat commands use the `.` prefix. `.help` lists them. Typing just a module name toggles it and adding a setting name and value edits that setting. For example you can just do `.step height 2`.
- Press **TAB** whilst typing a command to autocomplete it.
- Middle click a setting in the ClickGUI to put it back to its default. `.reset <module>` does the whole module.
- `.macro add <name> <lines>` saves a list of chat lines or commands and `.macro key <name> <key>` puts it on a key.
- `.hud` toggles the overlay and `.hud edit` opens the editor. `.xray <block>` adds a block to XRay and `.xray this` adds the one you point at.
- Everything is saved to `.minecraft/offlineclient/config.json` automatically.
- The title screen has a Recovery button that puts settings back to default.

## Modules

<details>
<summary><b>Combat</b> (36)</summary>

| Module | What it does |
| --- | --- |
| KillAura | Automatically swings at nearby mobs and players. |
| TriggerBot | Swings at whatever your crosshair is on. |
| AimAssist | Nudges your view towards whatever you are fighting. |
| Criticals | Makes every melee hit a critical hit. |
| AutoWeapon | Switches to your strongest sword or axe when you attack. |
| AttributeSwap | Swaps to your best item for the hit the moment you attack. |
| Hitboxes | Makes entities easier to hit by growing their hitboxes. |
| AutoClicker | Clicks the mouse buttons for you. |
| TpAura | Teleports around a target whilst hitting it. |
| AutoTotem | Keeps a totem of undying in your offhand. |
| Offhand | Keeps a chosen item in your offhand. |
| AutoArmor | Automatically wears the best armour you have. |
| CrystalAura | Places end crystals near enemies and blows them up. |
| AnchorAura | Places respawn anchors near enemies and sets them off. |
| BedAura | Places beds near enemies and sets them off. |
| AutoCity | Mines the block guarding an enemy in a hole. |
| Surround | Places blast proof blocks around your feet to stop crystals. |
| HoleFiller | Seals the holes around an enemy before they can hide in one. |
| AutoTrap | Places blocks around an enemy to trap them. |
| SelfTrap | Places blocks above your head to stop crystals. |
| Burrow | Places a blast proof block inside your own hitbox. |
| AutoWeb | Throws cobwebs at an enemy to lock them in place. |
| SelfWeb | Webs your own block to stop knockback. |
| AutoAnvil | Drops anvils on an enemy to break their helmet. |
| SelfAnvil | Places an anvil above you to keep others out of your hole. |
| AntiAnvil | Puts a block over your head when an anvil is dropped on you. |
| AntiBed | Breaks an enemy bed placed next to you. |
| AntiAnchor | Breaks an enemy respawn anchor placed next to you. |
| ArrowDodge | Sidesteps arrows and other projectiles aimed at you. |
| BowAimbot | Aims your bow or crossbow at the nearest target whilst you draw it. |
| BowSpam | Fires your bow or crossbow as fast as it will go. |
| Quiver | Picks which arrow your bow fires next. |
| PotionArrows | Shoots tipped arrows into yourself to take their effects. |
| ArrowDamage | Makes your arrows fly faster and hit harder. |
| FightBot | Runs after a target and fights it for you. |
| Protect | Follows a friend about and fights off whatever comes near. |

</details>

<details>
<summary><b>Movement</b> (32)</summary>

| Module | What it does |
| --- | --- |
| Sprint | Sprints for you whenever you move. |
| Speed | Move faster on the ground. |
| Flight | Lets you fly like in creative mode. |
| NoFall | Stops fall damage. |
| Step | Step up full blocks without jumping. |
| NoSlowdown | Keeps your full speed through everything that would normally slow you down. |
| NoKnockback | Reduces or removes the knockback you take. |
| AntiPush | Stops water and entities from pushing you around. |
| ElytraFly | Full elytra control without firework rockets. |
| ElytraBoost | Press the bind whilst gliding to fire a firework rocket. |
| AutoWasp | Flies you straight at a player on your elytra. |
| Glide | Slows your fall into a controlled drift. |
| AutoJump | Jumps for you whenever you are on the ground. |
| HighJump | Jump higher than normal. |
| LongJump | Jump much further than normal. |
| AirJump | Lets you jump again whilst in the air. |
| Parkour | Jumps for you at the edge of blocks. |
| FastFall | Pulls you to the ground faster when you are falling. |
| Jesus | Walk on water and lava. |
| Spider | Climb up any wall like a spider. |
| QuickClimb | Climb ladders and vines and powder snow much faster. |
| Slippy | Makes the ground as slippery as ice or worse. |
| Sneak | Keeps you sneaking. |
| EdgeGuard | Stops you from going over edges without the sneak slowdown. |
| HoleSnap | Stops your movement over a hole and drops you straight in. |
| AntiVoid | Stops you falling into the void. |
| AutoWalk | Walks for you. |
| VehicleFly | Flies or speeds the boat or mount you are riding. |
| TridentBoost | Makes a riptide trident throw you further. |
| Blink | Pauses your position updates until you turn it off. |
| ClickTp | Teleports you to the block you right click. |
| NoClip | Turns your collision off. Vanilla servers and single player pull you straight back. Only works where the server skips that check. |

</details>

<details>
<summary><b>Render</b> (51)</summary>

| Module | What it does |
| --- | --- |
| ESP | See entities through walls. |
| Chams | Draws player and mob models through walls. |
| Nametags | Shows health and ping and gear above players and names above other things. |
| Tracers | Draws lines from you to entities around you. |
| ItemESP | See dropped items through walls. |
| TrueSight | Renders invisible entities. |
| PopChams | Flashes a copy of a player where their totem popped. |
| EntityOwner | Shows who owns the projectiles and pets around you. |
| LogoutSpots | Marks where players logged out. |
| ChestESP | See containers through walls. |
| BlockESP | Highlights chosen blocks through walls. |
| XRay | See ores through the ground. |
| WallHack | Makes the blocks you choose see through. |
| HoleESP | Highlights safe holes to stand in. |
| CityESP | Highlights the block that would open up a surrounded enemy. |
| SpawnESP | Marks the dark floor where mobs can appear. |
| BaseFinder | Lights up every block a player put down near you. |
| Fullbright | See in the dark without torches. |
| NoRender | Leaves out things in the world you do not need drawn. |
| AntiBlind | Ignores effects that ruin your view. |
| ClearView | Removes screen overlays that hide what you need to see. |
| Weather | Sets the rain and thunder you see. Clears them by default. |
| TimeChanger | Shows the world at a time of day you pick. |
| Ambience | Recolours the sky and the clouds and the ground around you. |
| Freecam | Fly the camera around whilst your character stays still. |
| FreeLook | Look around without turning your body. |
| Zoom | Zooms your view. |
| CameraTweaks | Pulls the third person camera further out and through walls. |
| NoHurtCam | Removes the camera tilt when you take damage. |
| RemoteView | See the world through the eyes of another player or mob. |
| Waypoints | Marks the coordinates you saved in the world. |
| Marker | Draws shapes you place yourself to build against. |
| Breadcrumbs | Draws a trail behind you to lead you back. |
| Trail | Leaves a trail of particles behind you. |
| Radar | Draws a small map of nearby entities on your screen. |
| NewChunks | Marks fresh and old chunks that load whilst this is on. |
| TunnelESP | Highlights hand dug tunnels underground. |
| VoidESP | Shows holes in the bedrock that lead to the void. |
| Portals | Highlights portals through walls. |
| Trajectories | Shows the path a thrown or shot item will take. |
| BreakIndicators | Shows blocks other players are mining. |
| OpenWaterESP | Shows whether your bobber sits in open water. |
| BetterTooltips | Previews and extra facts in item tooltips. |
| ItemHighlight | Paints a colour behind the items you pick in any inventory and the hotbar. |
| ItemPhysics | Dropped items lie on the ground instead of hovering and spinning. |
| HandView | Reshapes and repositions your first person hands. |
| NoShieldOverlay | Moves a held shield down out of your view. |
| BlockSelection | Recolours the outline on the block you are looking at. |
| NoBackground | Removes the dark tint behind inventory screens. |
| Blur | Blurs the world behind menus and fades the blur in and out. |
| BossStack | Stacks boss bars that share a name and tightens the gap between them. |

</details>

<details>
<summary><b>Player</b> (34)</summary>

| Module | What it does |
| --- | --- |
| AutoEat | Eats for you when you get hungry or hurt. |
| AutoGap | Eats golden apples to hold your buffs and your health up. |
| AutoPotion | Drinks a potion when you are hurt or burning or running low. |
| AutoMend | Repairs your mending gear with experience. |
| AutoRespawn | Instantly respawns you after dying. |
| AntiHunger | Slows down how fast you get hungry. |
| PotionSaver | Stops your effect timers whilst you stand still. |
| NoStatusEffects | Hides your chosen effects on your client whilst the server keeps them. |
| AntiCactus | Stops cacti from hurting you. |
| AutoTool | Switches to your best tool when you mine something. |
| FastBreak | Breaks blocks faster and without the vanilla wait. |
| FastPlace | Removes the delay between placing blocks. |
| FastUse | Removes the delay between right clicks for items. |
| Reach | Reaches blocks and entities from further away. |
| Multitask | Lets you mine and place blocks whilst you eat or draw a bow. |
| NoMiningTrace | Mines blocks through entities standing in the way. |
| LiquidInteract | Lets you aim at water and lava instead of looking through it. |
| NoInteract | Stops clicks on the things you choose to protect. |
| GhostHand | Opens containers through walls. |
| MiddleClickExtra | Does something useful on a middle click. |
| Throw | Fires a whole stack of throwables in one click. |
| InventoryTweaks | Sorts and merges your inventory whilst it is open. |
| AutoReplenish | Refills your hotbar stacks from your inventory. |
| ChestStealer | Takes everything out of containers for you. |
| ChestSwap | Swaps an elytra and a chestplate on one key. |
| AutoDrop | Throws away junk items as they enter your inventory. |
| AutoSwitch | Cycles through your hotbar slots on their own. |
| InvWalk | Lets you walk about whilst a screen is open. |
| GUIMove | Lets you look around whilst a chest or inventory is open. |
| PortalMenus | Keeps your inventory and chat open whilst you stand in a portal. |
| Rotation | Locks your view to a chosen angle. |
| NoRotate | Stops the server from turning your head on teleports. |
| Follow | Walks after another player. |
| AutoFish | Casts and reels the fishing rod for you. |

</details>

<details>
<summary><b>World</b> (33)</summary>

| Module | What it does |
| --- | --- |
| Scaffold | Places blocks under you as you walk. |
| AirPlace | Places blocks in mid air where your crosshair points. |
| NoGhostBlocks | Waits for the server instead of guessing what a click did. |
| AutoBuild | Builds a saved shape where you right click. |
| TemplateTool | Saves a build as a shape AutoBuild can put up again. |
| BuildRandom | Places your held block at random spots around you. |
| InstantBunker | Builds a small bunker round you in one jump. |
| BuildHeight | Lets you place blocks against the world height limit. |
| Nuker | Breaks all blocks around you. |
| VeinMiner | Mines a whole vein of ore when you break one block of it. |
| PacketMine | Keeps breaking the blocks you clicked whilst you do other things. |
| Excavator | Digs out a box. Press the bind at each corner whilst it is on. |
| Tunneller | Digs a straight tunnel the way you face. |
| HighwayBuilder | Digs and builds a highway along one line at a fixed height. |
| InfinityMiner | Mines ore for ever and lets Mending heal the pickaxe on the way. |
| LiquidFiller | Fills the water and lava around you with solid blocks. |
| SpawnProofer | Lights up or fills the spots mobs would spawn in. |
| Kaboom | Blows a hole around you in one go. |
| Collisions | Turns fire and webs and other soft blocks into walls. |
| AutoFarm | Harvests the ripe crops in reach and replants them. |
| TillAura | Tills the dirt around you with the hoe in your hand. |
| BonemealAura | Feeds bone meal to the plants around you. |
| AutoBreed | Breeds the animals around you with the food you hold. |
| AutoShearer | Shears every sheep that comes near you. |
| Flamethrower | Sets fire to nearby animals for cooked meat. |
| AutoMount | Climbs onto the nearest rideable mob or vehicle. |
| AutoNametag | Names every chosen mob near you with the tag you carry. |
| EndermanLook | Keeps your gaze off endermen or puts it right on them. |
| EChestFarmer | Places and breaks ender chests to farm obsidian. |
| AutoSmelter | Feeds an open furnace from your inventory and empties it. |
| AutoBrewer | Brews the potion you pick whilst a brewing stand is open. |
| AutoLibrarian | Rerolls a librarian until it sells a book you want. |
| AutoSign | Writes your set text onto every sign you place. |

</details>

<details>
<summary><b>Misc</b> (28)</summary>

| Module | What it does |
| --- | --- |
| ClickGUI | Opens the GUI and sets its accent colour. |
| HUD | The overlay you see whilst playing. Drag the pieces about with .hud edit. |
| TabGUI | A module list on screen you steer with the arrow keys. No menu to open. |
| Panic | Turns every enabled module off at once. |
| AutoReconnect | Rejoins the server after you get disconnected. |
| AutoLog | Logs you out when your health gets low. |
| AntiAFK | Keeps you from being kicked for idling. |
| Timer | Speeds up or slows down the whole game client side. |
| FakePlayer | Spawns a copy of you that only you can see. Practise your combat on it. |
| BetterChat | Small improvements to the chat box. |
| BetterTab | Ping numbers and friend colours in the player list. |
| AntiSpam | Stacks repeated chat lines into one. |
| NameProtect | Hides your own name and renames other players on your screen. |
| Notifier | Chat messages when players come and go and when totems pop. |
| Spam | Sends chat messages on a timer. |
| MessageAura | Sends a private message to every player who comes into view. |
| MassTPA | Sends a teleport request to every player on the server. |
| StashFinder | Points out chunks packed with containers as you travel. |
| BetterBeacons | Offers every beacon effect whatever the pyramid is worth. |
| SoundBlocker | Mutes the sounds you pick. |
| BookBot | Writes book after book for you. |
| Notebot | Plays songs on the note blocks around you. |
| SkinDerp | Makes your skin layers blink on and off for everyone to see. |
| Derp | Makes you look ridiculous to everyone else. |
| ServerSpoof | Reports a plain client to the server. |
| AntiPacketKick | Spreads packet bursts out and keeps the server from dropping you. |
| PacketCanceller | Drops the kinds of packet you pick. Hides an action from the server or ignores what it sends. |
| PacketLogger | Writes the packets you pick to chat or to a file. |

</details>

## Build with Gradle
Git clone the repository and then run this:
```
./gradlew build
```
The jar lands in `build/libs`.

## Developing
Launch the modded client:
```
./gradlew runClient
```
The first launch takes a while. After that it starts in a few seconds. N.B. it uses its own folder at `run/`.

To test in the real launcher instead run this and it builds the jar and copies it into `.minecraft/mods`.

```
./gradlew install
```

## Support Me
[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/K3K715TC1R)
