# Arborist

A Paper 26.3 plugin for cutting trees. Every natural leaf belongs to one tree, and when that tree's last log is gone its whole crown comes down in a couple of seconds, even where it touches the crown of another tree. Sneak while breaking a log with an axe and the whole tree falls.

It replaces fast leaf decay plugins. Those only speed up vanilla decay, and vanilla keeps a leaf alive while *any* log is at most 6 steps away through leaves. Cut a tree whose crown touches another one and half of it stays in the air forever, held up by the neighbour.

## What it does

- **Leaves belong to their tree.** When the last log of a tree goes, every leaf it owns falls as a cascade from the trunk outwards, in about 2 to 4 seconds. Leaves drop saplings, sticks and apples as in vanilla decay, with leaf particles and no sound.
- **Partly cut trees.** While a tree still has logs, only the leaves that its remaining logs no longer reach fall. A tree that was half cut is remembered across restarts for 72 hours.
- **Fast decay.** Leaves that no log keeps alive at all (the vanilla rule) fall within a second instead of slowly, as with a fast leaf decay plugin.
- **Felling.** Sneak and break a log with an axe: every log of the tree at and above the broken one falls.

## Which leaves belong to which tree

When the first log of a tree is broken, while the tree is still whole, Arborist looks at its logs and its crown and writes down which leaves are its own. A leaf belongs to the tree whose logs are the fewest steps away through leaves (the same steps vanilla counts). On a tie, the log that is nearer in a straight line wins. A tree only owns leaves of its own kind, plus the mixes vanilla generates: azalea trees have oak logs with azalea leaves, jungle bushes have a jungle log with oak leaves, and the red, orange and yellow poplar leaves grow on poplar logs. An oak never holds up the leaves of a birch.

Where two crowns overlap, nobody can tell which tree grew a leaf, so a few leaves end up with the other tree. On 3931 pairs of real 26.3 trees of every kind, placed so that their crowns touch:

| after cutting one tree of the pair | vanilla, fast leaf decay | Arborist |
|---|---|---|
| leaves of the cut tree that stay up | 19% | 2% (they stay attached to the other crown, on its side) |
| leaves of the standing tree that fall | 0.2% | 1.3% (on the cut tree's side) |

Logs that touch each other are one tree, even if two saplings grew them: they are felled together and own their leaves together.

## Felling

- Sneak and break a log of a natural tree with an axe. Without sneaking you break one log, as usual.
- Every log of the tree at and above the broken one is broken as if you broke it: normal drops where each log was, one point of axe damage per log (Unbreaking applies), statistics and hunger. Logs below the broken one stay as a stump.
- The farthest logs go first. If the axe is down to its last point of durability the felling stops, so the axe never breaks; what is left is the lower part of the tree.
- Only natural trees with leaves of their own are felled, with at most 400 logs (`max-tree-logs`). Bigger groups of connected logs are left alone.
- Works in survival and adventure mode (creative too if `felling.creative` is true), with the `arborist.fell` permission, which everyone has.

### Builds are never felled

Logs placed by players since Arborist was installed are remembered (in the chunk's data). They are never part of a tree, never felled and never hold up leaves.

Logs placed *before* Arborist was installed look exactly like natural ones, so a log house built next to a tree could pass for part of it. That's why a tree is not felled when any of the logs to fell touches a building block: planks, stairs, slabs, doors, trapdoors, fences, gates, walls, glass, signs, beds, wool, carpets, banners, bars, chains, chests, shelves, wood blocks, bricks, concrete, crafting blocks, or a log placed by a player. The broken log goes as usual and the player sees *This tree touches a build: only one log was cut*. Torches, lanterns and ladders on a tree don't count.

## Commands and permissions

| Command | Permission | Default | |
|---|---|---|---|
| `/arborist status` | `arborist.admin` | op | Trees being cut, leaves about to fall, totals since start, timings |
| `/arborist reload` | `arborist.admin` | op | Reloads config.yml |
| (sneak + axe on a log) | `arborist.fell` | everyone | Fells the tree |

## Configuration

`plugins/Arborist/config.yml`, applied with `/arborist reload`:

```yaml
falling-leaves:          # how a crown comes down: start-delay + ticks-per-step * (distance from the trunk - 1) + 0..random
  start-delay-ticks: 10
  ticks-per-step: 10
  random-ticks: 10
fast-decay:              # leaves no log keeps alive at all
  enabled: true
  min-delay-ticks: 4
  max-delay-ticks: 16
felling:
  enabled: true
  require-sneak: true    # false: every log broken with an axe fells the tree
  protect-axe: true      # stop before the axe breaks
  creative: false
effects:
  particles: true
  sound: false
max-tree-logs: 400       # bigger groups of logs are not trees
max-leaves-per-tick: 200
forget-after-hours: 72   # how long a partly cut tree is remembered
disabled-worlds: []
```

## Installing

1. Put `arborist-1.0.0.jar` in `plugins/` (Paper 26.3).
2. Remove any fast leaf decay plugin. Two plugins making the same leaves decay get in each other's way.
3. Restart the server.

## How it works

- Every way a log can disappear is watched: players (`BlockBreakEvent`), fire, explosions, withers and other mobs. The tree is recorded before the log is removed, while it is still whole, and re-checked on the next tick, once the log is really gone.
- A tree is the group of natural logs of one species connected through faces, edges or corners (acacia trunks and big oak branches step diagonally). Stripped logs still count, so stripping a trunk is not cutting it. Wood blocks and logs placed by players never count.
- Each leaf that falls fires a `LeavesDecayEvent`, like vanilla decay, so other plugins can cancel it, and then breaks with the vanilla leaf drops and the particles-only break effect.
- Felled logs are broken with `Player#breakBlock`, so every log fires its own break event: protection plugins and tool statistics see each one.
- Partly cut trees are saved in `plugins/Arborist/trees.dat` every 5 minutes and on shutdown. A sapling growing where one stood makes its record stale, and a record that waited more than a minute is checked against the world before it is used again.
- Leaves in unloaded chunks are never touched, and a tree that reaches into an unloaded chunk is not recorded or felled.
- All work runs on the main thread in small steps: recording a tree takes about 0.03 ms on average (a few ms for the biggest), and at most `max-leaves-per-tick` leaves fall per tick.

## Building and testing

```bash
mvn package
```

The jar is in `target/`. The unit tests run with the build. The in-game tests in [`tests/`](tests/README.md) start a local Paper 26.3 server, grow real trees of every kind with `/place feature`, and make a bot player cut them.

## License

MIT
