# Testing

The rig starts the real game with the client in it and plays it the way a player does. It presses keys and clicks through the game's own input handlers and then asks the world and the screen what happened. Nothing here ships. The jar is built from `src/main` alone.

| Path | What it holds |
| --- | --- |
| `rig.gradle` | The gradle tasks. `build.gradle` applies it. |
| `src/java` | The rig and its suites. |
| `probe` | A small server plugin that tells the rig what the server saw. |
| `local.properties` | Settings of your own machine. Git ignores it. |

## Running it

```
./gradlew rig                               every suite
./gradlew rig -Prig.filter=movement         one suite
./gradlew rig -Prig.filter="clicktp,farm"   every case whose name holds one of the words
./gradlew rig -Prig.name=mine               name the result folder
```

A full run takes about fifty minutes and one suite a few. The game runs in `build/run/clientGameTest` and that folder is emptied before every run. Your own `.minecraft` is never touched. The window stays small and never takes the keyboard or the mouse. Sound is off and no name outside this machine resolves.

Results land in `build/rig/<name>`.

| File | What it holds |
| --- | --- |
| `summary.txt` | What passed and what failed with what the case saw. |
| `results.jsonl` | One line a case with what it measured. |
| `coverage.txt` | Which modules a case put to work. |
| `guard.txt` | What the anti cheat made of each move. |
| `shots` and `sheets` | A picture of every screen a case looked at. |
| `server.log` | The log of the test server. |

## A real server and a second player

Some things only show on a real server or to another player. For those the rig runs a Paper server and a second game on this machine. Tell it where a server is in `testing/local.properties`.

```
server=C:/path/to/a/paper/server
guard=NameOfAnAntiCheatPlugin
```

`server` is the folder of a Paper server for the game version the client is built for. The rig never writes there. It copies the jar and its libraries to `build/rig-server` and runs the copy in offline mode on `127.0.0.1` with a fresh flat world. No account logs in. `guard` may be left out. It names anti cheat plugins of that server for the Guard suite and their jars and settings are copied too.

Without the file the cases that need a server are left out and say why.

The single player server never checks the moves of its own host. A trick done with packets that passes there proves nothing until it has passed on the Paper server.

## The suites

| Suite | What it proves |
| --- | --- |
| Boot | The game reaches the title screen. Every mixin applied. The log holds no fault of the client. |
| Smoke | Every module switches on and off in creative and in survival and again on the server beside a second player. Every command runs. Nothing throws or logs a fault and the server kicks nobody. |
| Screens | The HUD and the click GUI and each tab and the HUD editor are drawn at GUI scales 2 and 4. No text leaves its box or lies over another. |
| Scenarios | One case for each thing a player reported. It plays what the player did and checks what the world shows. |
| Movement | One case for each movement module in single player and again on the server. The server says where the player ended up. |
| Combat | KillAura and TriggerBot against a mob and the modules that manage your gear and wall you in. Both grounds. |
| World | The modules that break and place and farm. The server says which blocks stand afterwards. Both grounds. |
| Player | The modules that look after you once you are hungry or hurt or burning or dead. Both grounds. |
| Render | A still scene is pictured before a module goes on and whilst it is on and after it went off. The module must change the picture and leave nothing behind. |
| Goto | The goto command walks built courses and the game's own land with and without Speed and Step. Every walk arrives and none is slower than the plain walk. |
| Server | What the server learns about the game with ServerSpoof on and off. What a second player sees of you and what happens when it is hit or followed or talked to. |
| Guard | The Movement cases again under an anti cheat. It writes down what was stopped. A plain walk must not be set back and the game must end up where the server has it. |

## Writing a case

A case is one method that gets a `Rig` and reads like the thing it proves.

```java
private void speed(Rig rig) {
    Paces plain = paces(rig);
    rig.client().enable("Speed");
    Moved moved = Motion.hold(rig, RUN_TICKS, FORWARD);
    rig.note(told(moved.across()) + " blocks against " + told(plain.sprinted()) + " at a sprint");
    rig.expect(moved.across() >= plain.sprinted() * 1.3, "Speed did not outrun a sprint");
    expectNoSetback(rig, moved);
}
```

- `rig.world()` runs server commands and asks the server what it holds.
- `rig.client()` switches modules and changes settings and types the client's own commands.
- `rig.controls()` is the keyboard and the mouse. Mouse buttons use the game's numbers. Left is 1 and right is 3.
- `rig.until` waits for something and fails with what it waited for. `rig.expect` fails with what was seen. `rig.note` adds a measured value to the result.
- `rig.frames().capture(name)` takes a picture and returns every text and box drawn in it.
- A case of the Server suite also gets a `Duo`. `duo.server()` asks the probe what the server saw and `duo.partner()` gives the second player orders.

Prove a new case both ways. Take the fix out and the case must fail. Put it back and it must pass.

## What the rig cannot see

- Signed chat. The test server runs offline and no account signs anything.
- Anything that needs eyes such as whether a colour is pleasant. The pictures are kept for that.
