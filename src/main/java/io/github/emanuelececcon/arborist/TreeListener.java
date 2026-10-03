package io.github.emanuelececcon.arborist;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.event.world.WorldUnloadEvent;

/** Watches every way a log or a leaf can disappear, and logs placed by players. */
final class TreeListener implements Listener {

    private final ArboristPlugin plugin;

    TreeListener(ArboristPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        TreeRecord record = removed(block);
        if (record != null && plugin.feller().wants(event.getPlayer())) {
            plugin.feller().plan(event.getPlayer(), block, record.species);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        removed(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().forEach(this::removed);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().forEach(this::removed);
    }

    /** Withers, ravagers and other mobs that break blocks. Stripping a log with an axe keeps it a log of the tree. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        Block block = event.getBlock();
        Material from = block.getType();
        Material to = event.getTo();
        TreeTypes types = plugin.types();
        if (from == to) {
            return;
        }
        String species = types.species(from);
        if (species != null && species.equals(types.species(to))) {
            return;
        }
        if (types.keepsLeaves(from) || types.isLeaves(from)) {
            removed(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDecay(LeavesDecayEvent event) {
        if (!plugin.decayer().firingOwnEvent() && plugin.settings().enabledIn(event.getBlock().getWorld())) {
            Block block = event.getBlock();
            plugin.decayer().seedOrphans(block.getWorld(), Pos.pack(block.getX(), block.getY(), block.getZ()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (plugin.types().species(block.getType()) != null) {
            plugin.placedLogs().mark(block);
        }
    }

    /** A sapling that grows where a partly cut tree stood makes that tree's record stale. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(StructureGrowEvent event) {
        List<Long> positions = new ArrayList<>();
        for (BlockState state : event.getBlocks()) {
            positions.add(Pos.pack(state.getX(), state.getY(), state.getZ()));
        }
        plugin.tracker().forgetTouching(event.getWorld().getUID(), positions);
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        plugin.placedLogs().forget(event.getChunk());
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        plugin.placedLogs().forget(event.getWorld());
    }

    /**
     * A log or leaf at this block is about to be removed. Records the log's tree while it is still whole, re-checks
     * it on the next tick, and has the leaves around checked. Returns the tree the log belongs to, if any.
     */
    private TreeRecord removed(Block block) {
        Material type = block.getType();
        TreeTypes types = plugin.types();
        boolean leaves = types.isLeaves(type);
        if ((!leaves && !types.keepsLeaves(type)) || !plugin.settings().enabledIn(block.getWorld())) {
            return null;
        }
        long pos = Pos.pack(block.getX(), block.getY(), block.getZ());
        plugin.decayer().seedOrphans(block.getWorld(), pos);
        if (leaves || types.species(type) == null) {
            return null;
        }
        if (plugin.placedLogs().isPlaced(block.getWorld(), block.getX(), block.getY(), block.getZ())) {
            plugin.placedLogs().unmark(block);
            return null;
        }
        TreeRecord record = plugin.track(block);
        if (record != null) {
            plugin.decayer().settleLater(record);
        }
        return record;
    }
}
