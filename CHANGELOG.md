# Changelog

## 1.0.0 - 2026-10-03
- First release.
- Every natural leaf belongs to one tree, worked out when the tree's first log is broken: the tree whose logs are fewest steps away through leaves of its own kind (azalea, jungle bush and poplar mixes included). When the last log is gone the whole crown falls in about 2-4 seconds, from the trunk outwards, even where it touches another tree. Partly cut trees drop only the leaves their remaining logs no longer reach and are remembered across restarts (`trees.dat`).
- Fast decay of leaves that no log keeps alive at all, like a fast leaf decay plugin.
- Sneak and break a log with an axe to fell the tree: every log at and above the broken one, as if broken by the player (drops, axe damage, statistics), farthest first; stops before the axe breaks.
- Logs placed by players are remembered in the chunk data and never felled or used as a tree. Felling refuses trees whose logs touch building blocks, so old log builds next to a tree stay safe.
- `/arborist status | reload`, permissions `arborist.admin` (op) and `arborist.fell` (everyone).
- In-game tests in `tests/`: a bot cuts real 26.3 trees of every kind on a local Paper server.
