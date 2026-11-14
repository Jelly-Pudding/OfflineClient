# Testing OfflineClient in the real game

The rig starts the real game with the client in it and plays it the way a player does. It presses keys and clicks through the game's own input handlers and reads what the world and the screen show afterwards. Nothing of it ships. The jar is built from `src/main` alone.

## What a run does

- The game runs in `build/run/clientGameTest`. That folder is emptied before every run. Your own `.minecraft` is never read or written.
- The window stays small and never takes the keyboard or the mouse. Sound is off. No name outside this machine resolves.
- One game runs at a time. A run that passes its time limit ends itself.

## Running it

```
./gradlew rig                               every suite
./gradlew rig -Prig.filter=scenarios        one suite
./gradlew rig -Prig.filter="clicktp,farm"   every case whose name holds one of the words
./gradlew rig -Prig.name=mine               name the result folder
```

Results land in `build/rig/<name>`. `summary.txt` says what passed. `results.jsonl` holds one line a case with what it measured. `shots` holds a picture of every screen a case looked at and beside each a list of every text that was drawn. `sheets` holds the pictures side by side. `traces` holds every walk of the Goto suite tick by tick.

A case that fails says what it saw. A fault the client is known to have is marked in the case with `known` and reported apart from the failures.

## The suites

| Suite | What it proves |
| --- | --- |
| Boot | The game reaches the title screen. Every mixin applied. The log holds no fault of the client. |
| Smoke | Every module switches on and off in creative and in survival and every command runs. Nothing throws and nothing is logged as a fault. |
| Screens | The HUD and the click GUI and each tab and the HUD editor are drawn at GUI scales 2 and 4. No text leaves its box or lies over another. The overlay draws over the debug screen. |
| Scenarios | One case for each thing a player reported. The case plays what the player did and checks what the world shows. |
| Goto | The goto command walks built courses and the game's own land with and without Speed and Step. Every walk arrives and none is slower than the plain walk. |

## Adding a scenario

A scenario is one method in `ScenariosSuite`. It gets a `Rig` and reads like the report it came from.

```java
private static void farmGivesBack(Rig rig) {
    World world = rig.world();
    BlockPos home = home(rig);
    world.gameMode(SURVIVAL);
    world.hotbar(HOE_SLOT, "diamond_hoe");
    world.hotbar(SEED_SLOT, "wheat_seeds 16");
    hold(rig, HOE_SLOT);
    rig.client().set(FARM, SOW, "on");
    rig.client().enable(FARM);
    BlockPos soil = home.offset(0, -1, -2);
    rig.controls().aimAtBlock(soil, Direction.UP);
    rig.controls().click(USE);
    rig.until("AutoFarm to sow the new farmland", SOW_TICKS, mc -> is(mc, soil.above(), Blocks.WHEAT));
    rig.until("the hotbar to go back to the hoe", HAND_BACK_TICKS, mc -> InventoryUtil.selectedSlot() == HOE_SLOT);
}
```

- `rig.world()` runs server commands and asks the server what it holds.
- `rig.client()` switches modules and types the client's own commands.
- `rig.controls()` is the keyboard and the mouse. Mouse buttons use the game's numbers. Left is 1 and right is 3.
- `rig.until` waits for something and fails with what it waited for. `rig.expect` fails with what was seen. `rig.note` adds a measured value to the result.
- `rig.frames().capture(name)` takes a picture and returns every text and box drawn in it.

Prove a new case both ways. Take the fix out and the case must fail. Put it back and the case must pass.

## What the rig cannot see

- How you look to another player. A second client would be needed.
- A real server. Paper's own rules and an anti cheat are not there. The single player server checks moves the way vanilla does.
- Anything that needs eyes such as whether a colour is pleasant. The pictures are kept for that.
