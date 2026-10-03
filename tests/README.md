# In-game tests

Tests for Arborist against a real Paper 26.3 server with real trees. `bot/mcbot.py` is a minimal Minecraft 26.3 client (protocol 777, copied from ShulkerAnywhere's tests) that only joins and stays online. `arbtest-plugin` is a test-only helper plugin, driven over RCON: it grows and copies trees, makes the bot player break logs through the server's own code (`Player#breakBlock`, so every event fires as for a real player) and writes the blocks of an area to JSON.

The tests cover:

- **pairs**: for 19 kinds of trees (oak, big oak, birch, spruce, pine, giant spruce, jungle, giant jungle, jungle bush, dark oak, acacia, cherry, mangrove, pale oak, azalea, poplars, mixed pairs), tree A and tree B are grown apart and copied next to each other so their crowns touch, then the bot fells A while sneaking. Every log of A must be gone and dropped, with one axe damage per log; few leaves only A grew may stay up and few leaves only B grew may fall. The output also shows how many of A's leaves vanilla decay (and fast leaf decay plugins) would leave in the air.
- breaking a tree one log at a time without sneaking: the crown stays until the last log, then falls
- felling refused next to planks, and next to a log placed by a player (which also holds up no leaves)
- the axe stops at 1 durability
- an explosion that takes the trunk
- a partly cut tree survives a restart
- a dark forest in a newly generated world: 40 trees felled, no leaf left that nothing keeps alive, and the timings

## Setup

You need Python 3.10+, a JDK 21+ and Maven.

1. Build the plugin (`mvn package` in the project root) and the helper (`mvn package` in `arbtest-plugin`).
2. Make a server folder, by default `tests/server/`, with:
   - the Paper 26.3 jar, named `paper.jar` (or set `AR_SERVER_JAR`)
   - `eula.txt` with `eula=true`: you must accept the [Minecraft EULA](https://aka.ms/MinecraftEULA) yourself
   - `server.properties`:
     ```properties
     online-mode=false
     server-ip=127.0.0.1
     server-port=25599
     enable-rcon=true
     rcon.port=25598
     rcon.password=<a random password>
     white-list=false
     level-type=minecraft\:flat
     generate-structures=false
     spawn-protection=0
     difficulty=peaceful
     view-distance=6
     simulation-distance=6
     allow-flight=true
     network-compression-threshold=-1
     max-tick-time=-1
     ```
   - `rcon.pw` holding the same RCON password
   - `plugins/` with `arborist-<version>.jar` and `arbtest.jar`
3. Run `python bot/tests.py`, or `python bot/tests.py pairs dark` for some tests. The script starts and stops the server itself. Set `AR_JAVA` if `java` is not on the path, and `AR_SERVER_DIR` for another server folder. With `AR_ATTACH=1` it uses a server that is already running, with the bot already online (the restart test is skipped then).

The server only listens on 127.0.0.1 and runs in offline mode; never point these scripts at a real server, and never install `arbtest.jar` on one.

## Updating for a new Minecraft version

The packet ids in `mcbot.py` follow the registration order in the server's `GameProtocols`, `ConfigurationProtocols` and `LoginProtocols` classes, and `PROTOCOL` comes from `version.json` in the server jar. Check the tree features used in `tests.py` against `data/minecraft/worldgen/feature` too: new trees should be added to the pairs.
