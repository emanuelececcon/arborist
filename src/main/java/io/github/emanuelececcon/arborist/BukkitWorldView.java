package io.github.emanuelececcon.arborist;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;

/** A {@link WorldView} over a live world. Short-lived: it caches which chunks are loaded. Main thread only. */
final class BukkitWorldView implements WorldView {

    private final World world;
    private final PlacedLogs placedLogs;
    private final int minY;
    private final int maxY;
    private final Map<Long, Boolean> loaded = new HashMap<>();

    BukkitWorldView(World world, PlacedLogs placedLogs) {
        this.world = world;
        this.placedLogs = placedLogs;
        this.minY = world.getMinHeight();
        this.maxY = world.getMaxHeight();
    }

    @Override
    public Material type(int x, int y, int z) {
        if (y < minY || y >= maxY) {
            return Material.AIR;
        }
        if (!isLoaded(x >> 4, z >> 4)) {
            return null;
        }
        return world.getType(x, y, z);
    }

    @Override
    public boolean persistent(int x, int y, int z) {
        BlockData data = world.getBlockData(x, y, z);
        return data instanceof Leaves leaves && leaves.isPersistent();
    }

    @Override
    public boolean placed(int x, int y, int z) {
        return placedLogs.isPlaced(world, x, y, z);
    }

    private boolean isLoaded(int chunkX, int chunkZ) {
        long key = (long) chunkX << 32 | (chunkZ & 0xFFFFFFFFL);
        Boolean known = loaded.get(key);
        if (known == null) {
            known = world.isChunkLoaded(chunkX, chunkZ);
            loaded.put(key, known);
        }
        return known;
    }
}
