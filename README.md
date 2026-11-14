# OfflineClient
A utility client custom designed for the anarchy lifesteal server [minecraftoffline.net](https://www.minecraftoffline.net/). This is a fabric mod for Minecraft 26.3.

## Install
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
- **RIGHT SHIFT** opens the ClickGUI. Its Style setting switches between draggable panels and a single window with a sidebar and Hidden categories leaves out the ones you never use.
- Chat commands use the `.` prefix. `.help` lists them. Typing just a module name toggles it and adding a setting name and value edits that setting. For example you can just do `.step height 2`.
- Press **TAB** whilst typing a command to autocomplete it.
- `.bind <module> <key>` puts a module on a key or a mouse button such as `mouse4` and `ctrl+` `alt+` or `shift+` can go before the key. Add `hold` to keep the module on only whilst the key is down.
- With Dormant on the Panic key puts the whole client to sleep until you press it again. Whilst it sleeps a line that starts with the command prefix is thrown away unsent.
- A setting you have changed shows a small mark beside it in the ClickGUI. Click the mark or middle click the setting to put it back. A module with changes ends with a Reset settings row and Ctrl Z takes the last reset back. `.reset <module>` puts back every setting of a module from chat and `.reset undo` takes the last reset back. `.reset` on its own lists the modules you have changed.
- `.macro add <name> <lines>` saves a list of chat lines or commands and `.macro key <name> <key>` puts it on a key. A line can be `wait 2s` or `repeat 5 2s <line>` and `.macro stop` ends a running macro. `.macro join <name> here` runs it each time you join this server.
- `.autologin set <password>` answers the server's login prompt for you from then on.
- `.seed set <seed>` lets OreSight show the ores of a server whose seed you know and `.locate <structure>` finds the nearest one from it. `.seed bedrock` writes out the Nether bedrock around you to find the seed from.
- `.hud` toggles the overlay and `.hud edit` opens the editor. Drag a piece to move it and pull an edge or a corner to resize it. Right click a piece to put its size back and then its place. The arrow keys nudge a piece and a drag across empty space picks several to move together. Switch on a part of the game's own HUD in the list such as the hotbar or the hearts to move or resize or hide it. `.hud add <kind>` adds another custom text or item counter or map or picture and `.words` lists the live words a custom text can show.
- `.xray <block>` adds a block to XRay and `.xray this` adds the one you point at.
- `.finds` lists everything the Hunting modules have found on this server. Each row can walk you there or save a waypoint or be forgotten.
- `.waypoint add <name>` marks where you stand and `.waypoint` on its own opens a list of the waypoints of every dimension. Each one can have its own colour and icon and range. The Waypoints tab of the ClickGUI does the same.
- `.goto <x> <z>` walks you there. Places joined by `then` are walked in turn and `wait <seconds>` and `loop` go after them. `.path` draws the way for you to walk yourself.
- With WorldMap on its Map key (**M**) opens a map of everything you have explored on the server. Drag to move it and scroll to zoom. A click walks you there and a right click saves a waypoint.
- `.name <name>` sets the name you join offline servers with and `.name random` picks one. The Names tab of the ClickGUI keeps a list to switch between. A server that checks accounts only lets in your own account name.
- The Proxies tab of the ClickGUI keeps a list of proxies and `.proxy add <host:port>` adds one. `.proxy login <name> <password>` saves its login and `.proxy test` checks that it reaches the server.
- `.reconnect` joins the server again and `.disconnect <way> <reason>` leaves it with a reason of your own.
- `.dossier <name>` shows what Dossier has recorded about a player and `.dossier open` lists everyone it has met on this server.
- `.chests <item>` lists the containers ChestMemory remembers holding that item.
- `.autopilot <x> <z>` flies you there and `.autopilot area <x> <z> <x> <z>` sweeps an area. `.autopilot resume` carries on with the saved sweep and `.autopilot stop` ends the flight.
- `.build place <file>` puts a schematic from `.minecraft/offlineclient/schematics` down where you stand for AutoBuild to print.
- MapPrinter prints map art from the files in `.minecraft/offlineclient/mapart` on its own. Switch it on where the map goes and follow the prompts to mark its chests.
- `.mute <name>` hides the chat of a player and `.mute list` shows who is muted.
- `.chatbot key <key>` saves the key a chat model service asks for. A model running on your own computer needs none.
- The Smooth font switches of ClickGUI and HUD draw the client in a smooth font. A `font.ttf` in `.minecraft/offlineclient` takes its place. The smooth font that comes with the client is [Inter](https://github.com/rsms/inter) under the SIL Open Font Licence.
- Everything is saved to `.minecraft/offlineclient/config.json` automatically.
- The title screen has a Recovery button that puts settings back to default.

## Modules

<details>
<summary><b>Combat</b> (52)</summary>

| Module | What it does |
| --- | --- |
| KillAura | Automatically swings at nearby mobs and players. |
| TriggerBot | Swings at whatever sits near your crosshair. |
| AimAssist | Turns your view towards whatever you are fighting. |
| Criticals | Makes every melee hit a critical hit. |
| Knockback | Makes every hit knock the target as far as a sprinting hit and the way you choose. |
| AutoWeapon | Switches to your strongest sword or axe when you attack. |
| AttributeSwap | Swaps to your best item for the hit the moment you attack. |
| Hitboxes | Makes entities and small blocks easier to hit by growing their boxes. |
| AutoClicker | Clicks the mouse buttons for you. |
| MaceCombo | Launches you off a wind charge and lands a mace smash on the target as you fall. |
| Wrecker | Breaks a boat or minecart with one hit. |
| TpAura | Teleports around a target whilst hitting it. |
| AntiBot | Spots fake players the server spawns and keeps every combat module off them. |
| AutoTotem | Keeps a totem of undying in your offhand. |
| Offhand | Keeps a chosen item in your offhand. |
| AutoArmor | Automatically wears the best armour you have. |
| CrystalAura | Places end crystals near enemies and blows them up. |
| AnchorAura | Places respawn anchors near enemies and sets them off. |
| BedAura | Places beds near enemies and sets them off. |
| TntAura | Places lit TNT on enemies and lights TNT around you. |
| CubeRigger | Turns sulfur cubes into bombs and traps. |
| AutoCity | Mines the block guarding an enemy in a hole. |
| PistonAura | Fires pistons at an enemy in a hole to push a crystal into them or shove them out. |
| Surround | Places blast proof blocks around your feet to stop crystals. |
| Blocker | Fills the spots around your hole that crystals and pistons would use. |
| HoleFiller | Seals the holes around an enemy before they can hide in one. |
| AutoTrap | Places blocks around an enemy to trap them. |
| SelfTrap | Places blocks above your head to stop crystals. |
| Cocoon | Seals you inside a ball or a box of blocks. |
| Burrow | Places a blast proof block inside your own hitbox. |
| AutoPearl | Throws ender pearls to escape a fight or chase an enemy or phase into a wall. |
| ChorusHold | Keeps a chorus fruit teleport back until you put the fruit away or two seconds pass whilst the server already has you at the landing spot. |
| AutoWeb | Throws cobwebs at an enemy to lock them in place. |
| SelfWeb | Webs your own block to stop knockback. |
| AutoAnvil | Drops anvils on an enemy to break their helmet. |
| SelfAnvil | Places an anvil above you to keep others out of your hole. |
| LavaAura | Pours lava or lights fire on targets near you. |
| AntiAnvil | Puts a block over your head when an anvil is dropped on you. |
| AntiBed | Breaks an enemy bed placed next to you. |
| AntiAnchor | Breaks an enemy respawn anchor placed next to you. |
| ArrowDodge | Sidesteps arrows and other projectiles aimed at you or blocks them. |
| Evade | Hops you out of reach of a falling mace or a charging spear or a raised blade. |
| BowAimbot | Aims your bow or crossbow or trident at the nearest target whilst you draw it. |
| BowSpam | Fires your bow or crossbow as fast as it will go. |
| Quiver | Picks which arrow your bow fires next. |
| PotionArrows | Shoots tipped arrows into yourself to take their effects. |
| Slingshot | Makes your arrows and throws fly much faster. |
| SkyDrop | Carries your throws and arrows high over a target and fires them straight down. |
| FightBot | Runs after a target and fights it for you. |
| Protect | Follows a player or a mob about and fights off whatever comes near. |
| Confuse | Runs round the target whilst you fight to be hard to hit. |
| KillMessage | Says something in chat when you kill a player. |

</details>

<details>
<summary><b>Movement</b> (39)</summary>

| Module | What it does |
| --- | --- |
| Sprint | Sprints for you whenever you move. |
| Speed | Makes you move faster on the ground. |
| Flight | Lets you fly like in creative mode. |
| NoFall | Stops fall damage. |
| Step | Lets you step up full blocks without jumping. |
| NoSlowdown | Keeps your full speed through everything that would normally slow you down. |
| NoKnockback | Reduces or removes the knockback you take. |
| AntiPush | Stops water and entities from pushing you around. |
| ElytraFly | Full elytra control without firework rockets. |
| ElytraBoost | Its bind fires a firework rocket whilst you glide. |
| AutoWasp | Flies you at a player on your elytra. |
| Glide | Slows your fall into a controlled drift. |
| AutoJump | Jumps for you whenever you are on the ground. |
| NoJumpDelay | Jumps again the moment you land whilst you hold jump. |
| HighJump | Lets you jump higher than normal. |
| WindJump | Throws a wind charge at your feet and jumps with it to fly as high as a charge can send you. |
| LongJump | Lets you jump much further than normal. |
| Boost | Its bind dashes you the way you move or straight ahead. |
| AirJump | Lets you jump again whilst in the air. |
| Parkour | Jumps for you at the edge of blocks. |
| FastFall | Pulls you to the ground faster when you are falling. |
| Jesus | Lets you walk on water and lava. |
| Spider | Lets you climb up and down any wall like a spider. |
| QuickClimb | Lets you climb ladders and vines and powder snow much faster. |
| Slippy | Makes the ground as slippery as ice or worse. |
| Sneak | Keeps you sneaking. |
| EdgeGuard | Stops you from going over edges without the sneak slowdown. |
| HoleSnap | Stops your movement over a hole and drops you straight in. |
| AntiVoid | Stops you falling into the void. |
| NoReturn | Keeps you from going back within sight of the spot where you switched it on. |
| AutoWalk | Walks for you. |
| Pathfinder | Settings for every walk the client makes for you such as goto and Follow. |
| Autopilot | Flies you to a spot or along a route whilst you do something else. |
| VehicleFly | Flies or speeds the boat or mount you are riding. |
| TridentBoost | Makes a riptide trident throw you further. |
| Blink | Pauses your position updates until you turn it off. |
| ClickTp | Teleports you onto the block you click or beside it when there is no room on top. |
| Fetch | Picks up dropped items from far away. |
| NoClip | Takes you through blocks. |

</details>

<details>
<summary><b>Render</b> (64)</summary>

| Module | What it does |
| --- | --- |
| ESP | Shows entities through walls. |
| Chams | Draws player and mob models through walls. |
| Nametags | Shows health and ping and gear above players and names above other things. |
| Tracers | Draws lines from you to entities around you. |
| Skeletons | Draws players and mobs built like them as stick figures through walls. |
| ItemESP | Shows dropped items through walls. |
| TrueSight | Renders invisible entities. |
| PopChams | Flashes a copy of a player where their totem popped. |
| Confetti | Turns totem pops into colourful confetti and marks your kills on your screen only. |
| DamageNumbers | Shows the health each hit takes off as a number rising from the entity. |
| EntityOwner | Shows who owns the projectiles and pets around you. |
| LogoutSpots | Marks where players logged out. |
| ChestESP | Shows containers through walls. |
| BlockESP | Highlights chosen blocks through walls. |
| TrialESP | Marks the vaults that already rewarded you and times each trial spawner's next wave. |
| XRay | Shows ores through the ground. |
| OreSight | Shows the ores the world seed placed even where anti xray hides them. |
| OreProbe | Uncovers the ores anti xray hides within about seven blocks of you. |
| WallHack | Makes the blocks you choose see through. |
| HoleESP | Highlights safe holes to stand in. |
| FeetESP | Marks the block each nearby player stands in and whether it is a safe hole. |
| CityESP | Highlights the block that would open up a surrounded enemy. |
| SpawnESP | Marks the dark floor where mobs can appear. |
| FarmRanges | Draws the distances that rule mob spawning round you and round each spawner. |
| SculkRange | Shows how far sculk sensors and shriekers hear and warns before a step sets one off. |
| Fullbright | Lets you see in the dark without torches. |
| NoRender | Leaves out things in the world you do not need drawn. |
| AntiBlind | Ignores effects that ruin your view. |
| ClearView | Removes screen overlays that hide what you need to see. |
| Weather | Sets the rain and thunder you see and clears them by default. |
| TimeChanger | Shows the world at a time of day you pick. |
| Ambience | Recolours the sky and the clouds and the ground around you. |
| SteadyFrames | Brings the render distance down whilst frames are slow and back up once they recover. |
| Freecam | Lets you fly the camera around whilst your character stays still. |
| FreeLook | Lets you look around without turning your body. |
| Zoom | Zooms your view. |
| CameraTweaks | Pulls the third person camera further out and through walls. |
| NoHurtCam | Removes the camera tilt when you take damage. |
| RemoteView | Shows the world through the eyes of another player or mob. |
| Waypoints | Marks the coordinates you saved in the world. |
| Marker | Draws shapes you place yourself to build against. |
| Breadcrumbs | Draws a trail behind you to lead you back. |
| Trail | Leaves a trail of particles behind you. |
| Radar | Draws a small map of nearby entities where you place it with .hud edit. |
| Minimap | Draws a map of the land around you where you place it with .hud edit. |
| WorldMap | Records the land you explore and opens a map of all of it with its Map key. |
| Highways | Draws the highways and ring roads round 0 0 out to the horizon. |
| ChunkBorders | Outlines the chunk you stand in and the chunks and region and map around it and marks slime chunks. |
| DebugDrawings | Shows the game's own debug drawings in single player whilst cheats are on. |
| VoidESP | Shows holes in the bedrock that lead to the void. |
| Portals | Highlights portals through walls. |
| Trajectories | Shows the path a thrown or shot item will take. |
| PearlTracker | Shows where other players' ender pearls will land and who threw them. |
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
<summary><b>Player</b> (46)</summary>

| Module | What it does |
| --- | --- |
| AutoEat | Eats for you when you get hungry or hurt. |
| AutoGap | Eats golden apples to hold your buffs and your health up. |
| AutoPotion | Drinks or throws potions to heal you and keep your effects up. |
| AutoExtinguish | Puts you out when you catch fire. |
| AutoMend | Repairs your mending gear with experience. |
| AutoRespawn | Instantly respawns you after dying. |
| Ghost | Lets you walk and look around after you die whilst the server still sees you dead. |
| AutoSleep | Sleeps in a bed near you at night and gets up in the morning. |
| AntiHunger | Slows down how fast you get hungry. |
| NoStatusEffects | Hides your chosen effects on your client whilst the server keeps them. |
| AntiCactus | Stops cacti from hurting you. |
| AutoTool | Switches to your best tool when you mine something. |
| FastBreak | Breaks blocks faster and without the vanilla wait. |
| FastPlace | Removes the delay between placing blocks. |
| FastUse | Removes the delay between right clicks for items. |
| Reach | Reaches blocks and entities from further away. |
| Multitask | Lets you mine and place blocks whilst you eat or draw a bow. |
| FrameInput | Reads your keys and mining every drawn frame rather than every tick. |
| NoMiningTrace | Mines and clicks through the entities and portals in the way. |
| LiquidInteract | Lets you aim at water and lava instead of looking through it. |
| NoInteract | Stops clicks and throws on the things you choose to protect. |
| GhostHand | Reaches containers and other blocks you pick through walls. |
| MiddleClickExtra | Does something useful on a middle click and can place blocks on a right click. |
| Throw | Repeats each right click several times in the same tick. |
| InventoryTweaks | Sorts and merges your inventory whilst it is open. |
| MenuTools | Adds buttons to menus that desync them or fetch your items from the server again. |
| MenuClicker | Plays a list of clicks you write whenever a menu you picked opens. |
| AutoReplenish | Refills your hotbar stacks from your inventory. |
| Loadouts | Puts your inventory back into a layout saved with the loadout command. |
| ChestStealer | Takes everything or only what you set limits for out of containers. |
| ShulkerPacker | Packs your backpack or an open container into a shulker box and picks the box back up. |
| LootList | Lists the enchanted gear and books and potions in an open container beside it. |
| ChestAura | Opens the containers around you and empties or fills them. |
| ChestLink | Keeps your ender chest open after you close it and shows it again on a key. |
| ChestMemory | Remembers what each container you open holds and finds an item among them. |
| ChestSwap | Swaps an elytra and a chestplate on one key. |
| AutoDrop | Throws away junk or whatever you hold or chosen hotbar slots. |
| AutoSwitch | Switches your hotbar slot on its own on a timer or after each placement. |
| InvWalk | Lets you walk about whilst a screen is open. |
| GUIMove | Lets you look around whilst a chest or inventory is open. |
| PortalMenus | Keeps your inventory and chat open whilst you stand in a portal. |
| Rotation | Locks your view to a chosen angle or places blocks as if you looked a chosen way. |
| NoRotate | Stops the server from turning your head on teleports. |
| Follow | Walks after another player or an entity you pick. |
| AutoDoors | Opens doors and gates in your way and shuts them behind you. |
| AutoFish | Casts and reels the fishing rod for you. |

</details>

<details>
<summary><b>World</b> (56)</summary>

| Module | What it does |
| --- | --- |
| Scaffold | Places blocks under you or over your head as you walk. |
| Staircase | Builds a staircase up or down as you walk or at a rapid pace. |
| AirPlace | Places blocks in mid air where your crosshair points. |
| NoGhostBlocks | Waits for the server instead of guessing what a click did. |
| AutoBuild | Builds a pattern or a template or text where you aim and prints build files. |
| AutoWither | Builds and spawns a wither. |
| TemplateTool | Saves a build as a shape AutoBuild can put up again. |
| MapPrinter | Prints map art from the files in offlineclient/mapart and makes and stores a map of each one. |
| BuildRandom | Places your held block at random spots around you. |
| TrailMaker | Places a block behind you as you walk. |
| InstantBunker | Jumps and builds a small bunker round you. |
| Nuker | Breaks all blocks around you. |
| VeinMiner | Mines a whole vein of ore when you break one block of it. |
| PacketMine | Breaks each block you click and any area around it whilst you do other things. |
| Excavator | Digs out the box between two opposite corners you click with the use key. |
| Filler | Fills the box between two opposite corners you click with the use key. |
| Tunneller | Digs a straight tunnel the way you face. |
| HighwayBuilder | Digs and builds a highway along one line. |
| InfinityMiner | Mines ore for ever and lets Mending heal the pickaxe on the way. |
| Salvage | Walks to and mines every block players put down near you. |
| LiquidFiller | Fills the water and lava around you with solid blocks. |
| LavaCast | Pours lava then water from a tower to cast a cobblestone mountain. |
| SpawnProofer | Lights up or fills the spots mobs would spawn in. |
| Painter | Covers every open face of the chosen blocks in reach with a block from your hotbar. |
| BlockSwap | Digs out the listed blocks in reach and puts a block from your hotbar in each hole. |
| Kaboom | Blows a hole around you in one go and gets you kicked on Paper. |
| Arson | Sets fire to the blocks round you or pours lava on them. |
| Collisions | Turns fire and webs and other soft blocks into walls. |
| AutoFarm | Harvests the ripe crops in reach and replants them. |
| BrushReader | Names the find in suspicious sand or gravel as you brush it. |
| TillAura | Tills the dirt around you with the hoe in your hand. |
| ToolAura | Uses the shovel or axe or hoe or water bottle you hold on the listed blocks around you. |
| BonemealAura | Feeds bone meal to the plants around you. |
| TreeAura | Plants saplings around you and grows them with bone meal. |
| WaxAura | Waxes the copper and the signs around you with honeycomb from the hotbar. |
| AutoBreed | Breeds the animals around you with the food you hold. |
| AutoShearer | Shears the animals and mobs you pick as they come near. |
| AutoInteract | Uses the item you hold on the mobs you pick as they come near. |
| AxolotlHunter | Marks axolotls of the colours you pick and can catch them in a bucket. |
| Flamethrower | Sets fire to nearby animals for cooked meat. |
| AutoMount | Climbs onto the nearest rideable mob or vehicle. |
| AutoNametag | Names every chosen mob near you with the tag you carry. |
| EndermanLook | Keeps your gaze off endermen or puts it right on them. |
| EChestFarmer | Places and breaks ender chests to farm obsidian. |
| AutoCraft | Crafts the items you pick whilst a crafting table or your inventory is open. |
| AutoSmelter | Feeds an open furnace from your inventory and empties it. |
| AutoBrewer | Brews the potion you pick whilst a brewing stand is open. |
| AutoEnchant | Enchants the items you pick whilst an enchanting table is open. |
| Grinder | Grinds the enchantments off the items you pick whilst a grindstone is open. |
| AutoSmith | Upgrades diamond gear to netherite or trims armour whilst a smithing table is open. |
| AutoMason | Cuts stone into the shapes you pick whilst a stonecutter is open. |
| AutoRename | Gives the items you pick a name whilst an anvil is open. |
| AutoBooks | Puts the enchanted books you carry onto your gear whilst an anvil is open. |
| AutoTrade | Buys and sells the trades you pick whilst a trading screen is open. |
| AutoLibrarian | Rerolls a librarian until it sells a book you want. |
| AutoSign | Writes your text on signs without the edit screen showing. |

</details>

<details>
<summary><b>Hunting</b> (18)</summary>

| Module | What it does |
| --- | --- |
| NewChunks | Marks fresh and old chunks as they load and the ones that show someone is near. |
| StashFinder | Points out chunks packed with containers as you travel. |
| BaseFinder | Marks the chunks around you where players built or lived. |
| SpawnerFinder | Finds spawners players have been near and rooms whose spawner they mined. |
| PotFinder | Finds decorated pots that players placed and learns what they hold. |
| SignReader | Shows the text of nearby signs through walls and remembers what each one said. |
| TunnelESP | Highlights hand dug tunnels shafts and staircases underground. |
| CaveTracks | Finds blocks players mined out of caves and the outlines of broken portals. |
| Locator | Finds other players through the locator bar unless the server turns it off. |
| Dossier | Keeps a record of every player you meet on each server. |
| Eavesdrop | Points to wither spawns and end portals and dragon deaths anywhere on the server. |
| EyeTracker | Finds the stronghold where the flights of thrown eyes of ender cross. |
| Earshot | Marks where the sounds you pick come from through walls. |
| GearedMobs | Highlights mobs carrying gear that only players bring into the world. |
| Collectibles | Marks rare items and banners wherever they show in the world. |
| ItemCoords | Marks where compasses and maps lead and the flowers that bees in hive items fed from. |
| BookReader | Reads the books other players hold or show or drop and copies them for you. |
| MapArt | Marks item frames holding maps and saves every map you see as a picture. |

</details>

<details>
<summary><b>Misc</b> (46)</summary>

| Module | What it does |
| --- | --- |
| ClickGUI | Opens the GUI and sets its accent colour. |
| HUD | The overlay you see whilst playing which you lay out with .hud edit along with the game's own bars. |
| TabGUI | A module list on screen you steer with the arrow keys and place with .hud edit. |
| Panic | Turns every enabled module off at once. |
| Triggers | Switches modules or sends a line or runs a macro when the conditions you pick are met. |
| ModuleLock | Keeps the modules you pick switched off until you switch this off again. |
| AutoReconnect | Rejoins the server after you get disconnected. |
| Linger | Keeps you in the world as it was when the connection drops and says why in chat. |
| AutoLogin | Answers the login prompt of an offline server with its saved password and offers to save one you type. |
| AutoLog | Logs you out on low health or for any other reason you pick. |
| Stasis | Calls for a pull home through a stasis chamber or works the chambers for others. |
| AntiAFK | Keeps you from being kicked for idling. |
| Timer | Speeds up or slows down the whole game client side. |
| FakePlayer | Spawns a copy of you that only you can see to practise your combat on. |
| Inspector | Its key opens a sheet about the entity you look at with actions for it. |
| BetterChat | Small improvements to the chat box. |
| BetterTab | Ping numbers and friend colours in the player list. |
| AntiSpam | Stacks repeated chat lines into one and can mute players who flood the chat. |
| NameProtect | Hides your own name and renames other players on your screen. |
| Conceal | Hides what the client draws whilst its modules keep working. |
| SecretChat | Chats in public in a code that anyone with your key can read. |
| Notifier | Tells you in chat and with alarms what other players and the server do. |
| ChatAlerts | Rings and shows a toast when chat mentions you or a word you pick or a player whispers to you. |
| Webhook | Sends the alerts you pick to a webhook address. |
| Streamers | Checks whether the players online are streaming live under the same name. |
| Spam | Sends chat messages on a timer. |
| ChatBot | Answers players and the server in chat by rules you set or with a chat model. |
| MessageAura | Whispers a line to players who come into view or to everyone on the tab list. |
| MassTPA | Sends a teleport request to every player on the server. |
| BetterBeacons | Offers every beacon effect whatever the pyramid is worth. |
| SoundBlocker | Mutes the sounds you pick. |
| Playlist | Plays the songs and discs you pick in place of the game's own music. |
| BookBot | Writes book after book for you. |
| ColourCodes | Turns codes such as &c for red and &l for bold on the signs and books you write into real colours and styles. |
| Notebot | Plays songs on the note blocks around you. |
| Honker | Blows a goat horn from your offhand or hotbar when another player comes near. |
| SkinDerp | Makes your skin layers blink on and off for everyone to see. |
| Derp | Makes you look ridiculous to everyone else. |
| ServerSpoof | Reports a plain client to the server. |
| Proxy | Joins servers through the proxy picked in the Proxies tab and they see its address in place of yours. |
| Privacy | Stops usage data reaching Mojang and keeps your chat out of reports where a server takes unsigned chat. |
| AntiPacketKick | Spreads packet bursts out and keeps the server from dropping you. |
| AntiCrash | Stops packets that would crash or freeze the game. |
| PacketCanceller | Drops the kinds of packet you pick to hide an action from the server or ignore what it sends. |
| Latency | Holds the packets you pick for a while before they go on. |
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
