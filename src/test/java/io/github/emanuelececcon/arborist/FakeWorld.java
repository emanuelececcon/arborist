package io.github.emanuelececcon.arborist;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;

/** An in-memory world for the analysis tests: air everywhere unless set. */
final class FakeWorld implements WorldView {

    static final TreeTypes TYPES = TreeTypes.of(
            List.of(Material.OAK_LOG, Material.STRIPPED_OAK_LOG, Material.OAK_WOOD, Material.BIRCH_LOG,
                    Material.JUNGLE_LOG, Material.SPRUCE_LOG, Material.POPLAR_LOG, Material.ACACIA_LOG,
                    Material.CRIMSON_STEM),
            List.of(Material.OAK_LEAVES, Material.BIRCH_LEAVES, Material.JUNGLE_LEAVES, Material.SPRUCE_LEAVES,
                    Material.AZALEA_LEAVES, Material.FLOWERING_AZALEA_LEAVES, Material.RED_POPLAR_LEAVES,
                    Material.ACACIA_LEAVES));

    final Map<Long, Material> blocks = new HashMap<>();
    final Set<Long> persistent = new HashSet<>();
    final Set<Long> placed = new HashSet<>();
    final Set<Long> unloadedChunks = new HashSet<>();

    @Override
    public Material type(int x, int y, int z) {
        if (unloadedChunks.contains(chunk(x >> 4, z >> 4))) {
            return null;
        }
        return blocks.getOrDefault(Pos.pack(x, y, z), Material.AIR);
    }

    @Override
    public boolean persistent(int x, int y, int z) {
        return persistent.contains(Pos.pack(x, y, z));
    }

    @Override
    public boolean placed(int x, int y, int z) {
        return placed.contains(Pos.pack(x, y, z));
    }

    void set(int x, int y, int z, Material type) {
        blocks.put(Pos.pack(x, y, z), type);
    }

    void remove(long pos) {
        blocks.remove(pos);
        persistent.remove(pos);
    }

    Material at(int x, int y, int z) {
        return type(x, y, z);
    }

    static long chunk(int chunkX, int chunkZ) {
        return (long) chunkX << 32 | (chunkZ & 0xFFFFFFFFL);
    }

    /**
     * A simple tree like vanilla's oak: a trunk from y=1 to y=height, two wide layers (radius 2) and two narrow layers
     * (radius 1) of leaves around the top, without the corners of the wide layers. Leaves never replace logs.
     */
    void tree(int x, int z, int height, Material log, Material leaf) {
        for (int y = 1; y <= height; y++) {
            set(x, y, z, log);
        }
        for (int y = height - 2; y <= height + 1; y++) {
            int r = y < height ? 2 : 1;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (r == 2 && Math.abs(dx) == 2 && Math.abs(dz) == 2) {
                        continue;
                    }
                    if (r == 1 && y == height + 1 && Math.abs(dx) + Math.abs(dz) == 2) {
                        continue;
                    }
                    long pos = Pos.pack(x + dx, y, z + dz);
                    if (!blocks.containsKey(pos)) {
                        blocks.put(pos, leaf);
                    }
                }
            }
        }
    }

    Set<Long> leavesOf(Material leaf) {
        Set<Long> result = new HashSet<>();
        blocks.forEach((pos, type) -> {
            if (type == leaf) {
                result.add(pos);
            }
        });
        return result;
    }
}
