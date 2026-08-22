# OfflineClient
A utility client custom designed for the anarchy lifesteal server [minecraftoffline.net](https://www.minecraftoffline.net/). This is a fabric mod for Minecraft 26.2.

## Installing
1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 26.2 and run it.
2. Check the path to your `.minecraft` directory is correctly set and click `install`.
3. Drop the OfflineClient jar into your `mods` folder. If necessary create the `mods` folder in `.minecraft` if it doesn't exist.
4. Launch the game with the Fabric profile.

## Usage
- **RIGHT SHIFT** opens the ClickGUI.
- Chat commands use the `.` prefix. `.help` lists them.
- Everything is saved to `.minecraft/offlineclient/config.json` automatically.

## Modules
| Category | Modules |
| --- | --- |
| Combat | KillAura · TriggerBot · AutoClicker · Criticals · AutoTotem · AutoArmor · AutoWeapon · BowAimbot · Surround · CrystalAura · AutoCity · SelfTrap · AutoTrap · Offhand · Hitboxes |
| Movement | Sprint · Speed · Flight · ElytraFly · NoFall · NoKnockback · Step · HighJump · NoSlowdown · EdgeGuard · Spider · QuickClimb · AutoWalk · Blink · Parkour · FastFall · Jesus · AutoJump · AntiPush · LongJump · NoWeb · Sneak |
| Render | Fullbright · AntiBlind · ClearView · ClearSkies · ESP · ChestESP · ItemESP · LogoutSpots · Portals · Search · HoleESP · XRay · Nametags · Trajectories · Tracers · Breadcrumbs · Freecam · Zoom · NoHurtCam |
| Player | FastPlace · FastBreak · AutoRespawn · AutoEat · ChestStealer · AntiHunger · AutoTool · Reach · AutoFish · AutoDrop · NoRotate · FastUse · AutoReplenish · InvWalk · GhostHand · MiddleClickExtra · AirPlace |
| World | Scaffold · Nuker · VeinMiner |
| Misc | ClickGUI · HUD · AntiAFK · AutoReconnect · AutoLog · Timer · FakePlayer · Notifier · NameProtect · Spam · AntiSpam |

## Building
```
./gradlew build
```

The jar lands in `build/libs`. Requires JDK 25.

## Developing
Launch the modded client:

```
./gradlew runClient
```

The first launch downloads assets and takes a while. After that it starts in a few seconds. N.B. it uses its own folder at `run/`.

When you want to test in the real launcher instead:

```
./gradlew install
```

That builds the jar and copies it into `.minecraft/mods` in one step.

## Support Me
[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/K3K715TC1R)