# OfflineClient
A utility client custom designed for the anarchy lifesteal server [minecraftoffline.net](https://www.minecraftoffline.net/). This is a fabric mod for Minecraft 26.2.

## Installation

### Vanilla Launcher
1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 26.2 and run it.
2. Check the path to your `.minecraft` directory is correctly set and click `install`.
3. Drop the OfflineClient jar into your `mods` folder. If necessary create the `mods` folder in `.minecraft` if it doesn't exist.
4. Launch the game with the Fabric profile.

### MultiMC
1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 26.2 and run it.
2. Check the path to your `.minecraft` directory is correctly set and click `install`.
3. Downlaod [MultiMC](https://multimc.org/#Download).
4. Open MultiMC and click `Add Instance`. Choose the version.
5. Right click on the instance you created and choose `Edit Instance`. On the right-hand side click `Install Fabric`.
5. Choose your version of Fabric.
6. Go to `Loader Mods` and click `Add` and find the `OfflineClient` jar you downloaded.

## Usage
- **RIGHT SHIFT** opens the ClickGUI. Its Style setting switches between draggable panels and a single window with a sidebar.
- Chat commands use the `.` prefix. `.help` lists them. Typing just a module name toggles it.
- Press **TAB** whilst typing a command to autocomplete it.
- Middle click a setting in the ClickGUI to put it back to its default. `.reset <module>` does the whole module.
- `.macro add <name> <lines>` saves a list of chat lines or commands and `.macro key <name> <key>` puts it on a key.
- `.hud` opens the overlay editor or bind a key to the HUD module. Drag each piece where you want it and hold shift to ignore snapping.
- Everything is saved to `.minecraft/offlineclient/config.json` automatically.
- The title screen has a Recovery button that puts settings back to default.

## Modules
| Category | Modules |
| --- | --- |
| Combat | KillAura · TriggerBot · AutoClicker · Criticals · AutoTotem · AutoArmor · AutoWeapon · BowAimbot · BowSpam · ArrowDodge · Quiver · PotionArrows · Surround · CrystalAura · AnchorAura · BedAura · AutoCity · SelfTrap · AutoTrap · HoleFiller · Burrow · AutoWeb · SelfWeb · AntiBed · AntiAnchor · AttributeSwap · Offhand · Hitboxes · AutoAnvil · AimAssist · AntiAnvil · SelfAnvil |
| Movement | Sprint · Speed · Flight · ElytraFly · NoFall · NoKnockback · Step · HighJump · NoSlowdown · EdgeGuard · Spider · QuickClimb · AutoWalk · AutoWasp · Blink · Parkour · FastFall · Jesus · AutoJump · AntiPush · AntiVoid · LongJump · NoWeb · Slippy · Sneak · ElytraBoost · VehicleFly · Glide · AirJump · ClickTp · HoleSnap · NoClip · TridentBoost |
| Render | Fullbright · AntiBlind · ClearView · ESP · Chams · PopChams · ChestESP · ItemESP · ItemPhysics · LogoutSpots · Portals · Search · HoleESP · CityESP · XRay · WallHack · NewChunks · BreakIndicators · Nametags · Trajectories · Tracers · Breadcrumbs · Trail · BetterTooltips · TrueSight · EntityOwner · Radar · Waypoints · Marker · VoidESP · TunnelESP · SpawnESP · Freecam · FreeLook · Zoom · NoHurtCam · CameraTweaks · BlockSelection · Blur · BossStack · Ambience · HandView · ItemHighlight · NoBackground · NoRender · NoShieldOverlay · OpenWaterESP · TimeChanger · Weather |
| Player | FastPlace · FastBreak · AutoRespawn · AutoEat · AutoGap · AutoPotion · AutoMend · ChestStealer · InventoryTweaks · AntiHunger · AutoTool · Reach · AutoFish · AutoDrop · NoRotate · NoInteract · NoMiningTrace · LiquidInteract · FastUse · AutoReplenish · InvWalk · Follow · GUIMove · Multitask · ChestSwap · PotionSaver · PortalMenus · GhostHand · MiddleClickExtra · AirPlace · NoStatusEffects · AntiCactus · Rotation |
| World | Scaffold · Nuker · VeinMiner · PacketMine · Excavator · Tunneller · HighwayBuilder · LiquidFiller · SpawnProofer · AutoSign · NoGhostBlocks · AutoFarm · BuildHeight · AutoMount · EndermanLook · Flamethrower · Collisions · AutoBreed · AutoBrewer · AutoLibrarian · AutoNametag · AutoShearer · AutoSmelter · BonemealAura · EChestFarmer · Kaboom · TillAura |
| Misc | ClickGUI · HUD · AntiAFK · AutoReconnect · AutoLog · Timer · FakePlayer · Notifier · NameProtect · Spam · AntiSpam · MessageAura · Derp · Panic · ServerSpoof · StashFinder · BetterTab · BetterBeacons · AntiPacketKick · PacketCanceller · PacketLogger · SoundBlocker · BookBot · Notebot · BetterChat · TabGUI |

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
