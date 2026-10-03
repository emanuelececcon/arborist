package io.github.emanuelececcon.arborist;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.event.block.LeavesDecayEvent;

/**
 * Makes leaves fall, one tick at a time: the crowns of cut trees as a cascade from the trunk outwards, and leaves that
 * no log keeps alive at all (vanilla decay) within a second. Runs every tick on the main thread.
 */
final class LeafDecayer implements Runnable {

    private record Entry(long due, long seq, UUID world, long pos, Material type, boolean owned) {
    }

    /** Leaves around removed blocks are checked in batches: one look at a crown instead of one per fallen leaf. */
    private static final int ORPHAN_CHECK_TICKS = 5;

    private final ArboristPlugin plugin;
    private final PriorityQueue<Entry> queue =
            new PriorityQueue<>(Comparator.comparingLong(Entry::due).thenComparingLong(Entry::seq));
    private final Map<UUID, Set<Long>> pending = new HashMap<>();
    private final Map<UUID, Set<Long>> orphanSeeds = new HashMap<>();
    private final Set<TreeRecord> toSettle = new LinkedHashSet<>();
    private long tick;
    private long seq;
    private boolean firingOwnEvent;

    long ownedDropped;
    long orphansDropped;
    long orphanChecks;
    long orphanCheckNanos;
    long slowestOrphanCheckNanos;

    LeafDecayer(ArboristPlugin plugin) {
        this.plugin = plugin;
    }

    /** Re-checks the tree on the next tick, after the log is really gone. */
    void settleLater(TreeRecord record) {
        toSettle.add(record);
    }

    /** Something next to these positions was removed: check the leaves around them on the next tick. */
    void seedOrphans(World world, long pos) {
        if (plugin.settings().fastDecay()) {
            orphanSeeds.computeIfAbsent(world.getUID(), k -> new HashSet<>()).add(pos);
        }
    }

    /** True while Arborist itself fires a LeavesDecayEvent. */
    boolean firingOwnEvent() {
        return firingOwnEvent;
    }

    int pendingCount() {
        return queue.size();
    }

    @Override
    public void run() {
        tick++;
        settle();
        if (tick % ORPHAN_CHECK_TICKS == 0) {
            findOrphans();
        }
        Settings settings = plugin.settings();
        int budget = settings.maxLeavesPerTick();
        while (budget > 0 && !queue.isEmpty() && queue.peek().due() <= tick) {
            Entry entry = queue.poll();
            unpend(entry);
            if (drop(entry, settings)) {
                budget--;
            }
        }
    }

    /** Drops every waiting leaf now; used when the plugin is disabled so no crown is left hanging. */
    void flush() {
        settle();
        Settings settings = plugin.settings();
        while (!queue.isEmpty()) {
            Entry entry = queue.poll();
            unpend(entry);
            drop(entry, settings);
        }
    }

    private void settle() {
        if (toSettle.isEmpty()) {
            return;
        }
        List<TreeRecord> records = new ArrayList<>(toSettle);
        toSettle.clear();
        Settings settings = plugin.settings();
        long now = System.currentTimeMillis();
        for (TreeRecord record : records) {
            World world = Bukkit.getWorld(record.world);
            if (world == null) {
                continue;
            }
            BukkitWorldView view = plugin.view(world);
            Map<Long, Integer> falling = plugin.tracker().settle(record, view, now);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (Map.Entry<Long, Integer> leaf : falling.entrySet()) {
                int delay = settings.startDelayTicks() + settings.ticksPerStep() * Math.max(0, leaf.getValue() - 1)
                        + random.nextInt(settings.randomTicks() + 1);
                schedule(world, leaf.getKey(), view.type(leaf.getKey()), delay, true);
            }
        }
    }

    private void findOrphans() {
        if (orphanSeeds.isEmpty()) {
            return;
        }
        long start = System.nanoTime();
        Map<UUID, Set<Long>> seeds = new HashMap<>(orphanSeeds);
        orphanSeeds.clear();
        Settings settings = plugin.settings();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (Map.Entry<UUID, Set<Long>> e : seeds.entrySet()) {
            World world = Bukkit.getWorld(e.getKey());
            if (world == null) {
                continue;
            }
            BukkitWorldView view = plugin.view(world);
            Set<Long> skip = pending.getOrDefault(e.getKey(), Set.of());
            for (long leaf : new TreeAnalyzer(view, plugin.types()).orphansNear(e.getValue(), skip)) {
                int delay = settings.fastMinDelayTicks()
                        + random.nextInt(settings.fastMaxDelayTicks() - settings.fastMinDelayTicks() + 1);
                schedule(world, leaf, view.type(leaf), delay, false);
            }
        }
        long took = System.nanoTime() - start;
        orphanChecks++;
        orphanCheckNanos += took;
        slowestOrphanCheckNanos = Math.max(slowestOrphanCheckNanos, took);
    }

    private void schedule(World world, long pos, Material type, int delay, boolean owned) {
        if (type == null || !plugin.types().isLeaves(type)) {
            return;
        }
        if (pending.computeIfAbsent(world.getUID(), k -> new HashSet<>()).add(pos)) {
            queue.add(new Entry(tick + Math.max(1, delay), seq++, world.getUID(), pos, type, owned));
        }
    }

    private void unpend(Entry entry) {
        Set<Long> set = pending.get(entry.world());
        if (set != null) {
            set.remove(entry.pos());
            if (set.isEmpty()) {
                pending.remove(entry.world());
            }
        }
    }

    private boolean drop(Entry entry, Settings settings) {
        World world = Bukkit.getWorld(entry.world());
        int x = Pos.x(entry.pos());
        int y = Pos.y(entry.pos());
        int z = Pos.z(entry.pos());
        if (world == null || !world.isChunkLoaded(x >> 4, z >> 4)) {
            return false;
        }
        Block block = world.getBlockAt(x, y, z);
        if (block.getType() != entry.type() || !(block.getBlockData() instanceof Leaves leaves) || leaves.isPersistent()) {
            return false;
        }
        LeavesDecayEvent event = new LeavesDecayEvent(block);
        firingOwnEvent = true;
        try {
            Bukkit.getPluginManager().callEvent(event);
        } finally {
            firingOwnEvent = false;
        }
        if (event.isCancelled() || block.getType() != entry.type()) {
            return false;
        }
        BlockData data = block.getBlockData();
        block.breakNaturally(settings.particles(), false);
        if (settings.sound()) {
            Location center = block.getLocation().add(0.5, 0.5, 0.5);
            world.playSound(center, data.getSoundGroup().getBreakSound(), SoundCategory.BLOCKS, 0.5f, 1.0f);
        }
        if (entry.owned()) {
            ownedDropped++;
        } else {
            orphansDropped++;
        }
        seedOrphans(world, entry.pos());
        return true;
    }
}
