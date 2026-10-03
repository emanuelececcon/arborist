package io.github.emanuelececcon.arborist;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Logs placed by players since Arborist was installed, kept in the chunk's persistent data. They are never part of a
 * tree: they don't own leaves and are never felled. Logs placed before Arborist look natural.
 */
final class PlacedLogs {

    private final NamespacedKey key;
    private final Map<UUID, Map<Long, Set<Integer>>> cache = new HashMap<>();

    PlacedLogs(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "placed_logs");
    }

    /** Only call this for a loaded chunk. */
    boolean isPlaced(World world, int x, int y, int z) {
        return chunk(world, x >> 4, z >> 4).contains(local(x, y, z));
    }

    void mark(Block block) {
        Set<Integer> placed = chunk(block.getWorld(), block.getX() >> 4, block.getZ() >> 4);
        if (placed.add(local(block.getX(), block.getY(), block.getZ()))) {
            write(block.getChunk(), placed);
        }
    }

    void unmark(Block block) {
        Set<Integer> placed = chunk(block.getWorld(), block.getX() >> 4, block.getZ() >> 4);
        if (placed.remove(local(block.getX(), block.getY(), block.getZ()))) {
            write(block.getChunk(), placed);
        }
    }

    void forget(Chunk chunk) {
        Map<Long, Set<Integer>> chunks = cache.get(chunk.getWorld().getUID());
        if (chunks != null) {
            chunks.remove(chunkKey(chunk.getX(), chunk.getZ()));
        }
    }

    void forget(World world) {
        cache.remove(world.getUID());
    }

    private Set<Integer> chunk(World world, int chunkX, int chunkZ) {
        Map<Long, Set<Integer>> chunks = cache.computeIfAbsent(world.getUID(), k -> new HashMap<>());
        long chunkKey = chunkKey(chunkX, chunkZ);
        Set<Integer> placed = chunks.get(chunkKey);
        if (placed == null) {
            placed = new HashSet<>();
            int[] stored = world.getChunkAt(chunkX, chunkZ).getPersistentDataContainer().get(key, PersistentDataType.INTEGER_ARRAY);
            if (stored != null) {
                for (int value : stored) {
                    placed.add(value);
                }
            }
            chunks.put(chunkKey, placed);
        }
        return placed;
    }

    private void write(Chunk chunk, Set<Integer> placed) {
        PersistentDataContainer data = chunk.getPersistentDataContainer();
        if (placed.isEmpty()) {
            data.remove(key);
            return;
        }
        int[] values = new int[placed.size()];
        int i = 0;
        for (int value : placed) {
            values[i++] = value;
        }
        data.set(key, PersistentDataType.INTEGER_ARRAY, values);
    }

    private static int local(int x, int y, int z) {
        return (x & 15) | (z & 15) << 4 | (y + 2048) << 8;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (long) chunkX << 32 | (chunkZ & 0xFFFFFFFFL);
    }
}
