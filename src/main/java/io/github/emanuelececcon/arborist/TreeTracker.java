package io.github.emanuelececcon.arborist;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;

/**
 * The trees that lost some logs but still own leaves. A tree is recorded when its first log goes, while it is still
 * whole; its leaves fall once their own logs no longer keep them alive, and all of them when the last log is gone.
 */
final class TreeTracker {

    private static final int FILE_MAGIC = 0x41524254; // "ARBT"
    private static final int FILE_VERSION = 1;
    /** A record left alone this long is checked against the world before it is used again. */
    static final long RECHECK_AFTER_MILLIS = 60_000L;

    private final TreeTypes types;
    private final Set<TreeRecord> records = new LinkedHashSet<>();
    private final Map<UUID, Map<Long, TreeRecord>> byLog = new HashMap<>();
    private final Map<UUID, Map<Long, TreeRecord>> byLeaf = new HashMap<>();

    TreeTracker(TreeTypes types) {
        this.types = types;
    }

    /**
     * The tree the log at (x, y, z) belongs to, recording it first if needed. Call it before the log is removed.
     * Returns null when the log is not part of a natural tree with leaves of its own.
     */
    TreeRecord track(UUID world, WorldView view, int x, int y, int z, int maxLogs, long now) {
        Map<Long, TreeRecord> logs = byLog.computeIfAbsent(world, k -> new HashMap<>());
        TreeRecord known = logs.get(Pos.pack(x, y, z));
        TreeAnalyzer analyzer = new TreeAnalyzer(view, types);
        Set<Long> trunk = null;
        if (known != null && now - known.touched > RECHECK_AFTER_MILLIS) {
            // A tree only loses logs while it is cut. Logs the record doesn't know mean another tree grew here
            // since (a sapling planted where a partly cut tree stood): the record is stale.
            trunk = analyzer.trunk(x, y, z, maxLogs, Integer.MIN_VALUE);
            if (trunk == null || !known.logs.containsAll(trunk)) {
                remove(known);
                known = null;
            }
        }
        if (known != null) {
            known.touched = now;
            return known;
        }
        if (trunk == null) {
            trunk = analyzer.trunk(x, y, z, maxLogs, Integer.MIN_VALUE);
        }
        if (trunk == null) {
            return null;
        }
        for (long log : trunk) {
            TreeRecord other = logs.get(log);
            if (other != null) {
                // Part of a tree that is already being cut (the log was out of reach of the last look): adopt it.
                for (long l : trunk) {
                    if (other.logs.add(l)) {
                        logs.put(l, other);
                    }
                }
                other.touched = now;
                return other;
            }
        }
        String species = types.species(view.type(x, y, z));
        Map<Long, TreeRecord> leaves = byLeaf.computeIfAbsent(world, k -> new HashMap<>());
        Map<Long, Integer> owned = analyzer.ownedLeaves(species, trunk, leaves.keySet());
        if (owned.isEmpty()) {
            return null;
        }
        TreeRecord record = new TreeRecord(world, species, trunk, owned, now);
        add(record);
        return record;
    }

    /**
     * Brings a record up to date after some of its logs went. Returns the leaves that should fall now, with their
     * distance from the trunk; the record is dropped once it has no logs or no leaves left.
     */
    Map<Long, Integer> settle(TreeRecord record, WorldView view, long now) {
        Map<Long, Integer> falling = new LinkedHashMap<>();
        if (!records.contains(record)) {
            return falling;
        }
        record.touched = now;
        Map<Long, TreeRecord> logIndex = byLog.get(record.world);
        Map<Long, TreeRecord> leafIndex = byLeaf.get(record.world);
        for (Iterator<Long> it = record.logs.iterator(); it.hasNext(); ) {
            long log = it.next();
            Material type = view.type(log);
            if (type != null && !record.species.equals(types.species(type))) {
                it.remove();
                logIndex.remove(log);
            }
        }
        for (Iterator<Map.Entry<Long, Integer>> it = record.leaves.entrySet().iterator(); it.hasNext(); ) {
            long leaf = it.next().getKey();
            Material type = view.type(leaf);
            if (type != null && (!types.isLeaves(type) || view.persistent(leaf))) {
                it.remove();
                leafIndex.remove(leaf);
            }
        }
        if (record.logs.isEmpty()) {
            falling.putAll(record.leaves);
        } else {
            Set<Long> held = new TreeAnalyzer(view, types).stillHeld(record.species, record.logs);
            for (Map.Entry<Long, Integer> leaf : record.leaves.entrySet()) {
                if (!held.contains(leaf.getKey()) && view.type(leaf.getKey()) != null) {
                    falling.put(leaf.getKey(), leaf.getValue());
                }
            }
        }
        for (long leaf : falling.keySet()) {
            record.leaves.remove(leaf);
            leafIndex.remove(leaf);
        }
        if (record.logs.isEmpty() || record.leaves.isEmpty()) {
            remove(record);
        }
        return falling;
    }

    /**
     * A tree grew over these positions: every record that has a log or a leaf there is forgotten, since its leaf list
     * no longer matches the world. Their leaves are left as they are. Returns how many records were forgotten.
     */
    int forgetTouching(UUID world, Collection<Long> positions) {
        Map<Long, TreeRecord> logIndex = byLog.get(world);
        Map<Long, TreeRecord> leafIndex = byLeaf.get(world);
        if (logIndex == null || leafIndex == null) {
            return 0;
        }
        Set<TreeRecord> stale = new LinkedHashSet<>();
        for (long pos : positions) {
            TreeRecord record = logIndex.get(pos);
            if (record == null) {
                record = leafIndex.get(pos);
            }
            if (record != null) {
                stale.add(record);
            }
        }
        stale.forEach(this::remove);
        return stale.size();
    }

    /** Forgets trees nobody touched for maxAgeMillis; their leaves are left as they are. Returns how many. */
    int expire(long now, long maxAgeMillis) {
        List<TreeRecord> old = new ArrayList<>();
        for (TreeRecord record : records) {
            if (now - record.touched > maxAgeMillis) {
                old.add(record);
            }
        }
        old.forEach(this::remove);
        return old.size();
    }

    int size() {
        return records.size();
    }

    int leafCount() {
        int count = 0;
        for (TreeRecord record : records) {
            count += record.leaves.size();
        }
        return count;
    }

    TreeRecord byLog(UUID world, long pos) {
        Map<Long, TreeRecord> index = byLog.get(world);
        return index == null ? null : index.get(pos);
    }

    TreeRecord byLeaf(UUID world, long pos) {
        Map<Long, TreeRecord> index = byLeaf.get(world);
        return index == null ? null : index.get(pos);
    }

    private void add(TreeRecord record) {
        records.add(record);
        Map<Long, TreeRecord> logs = byLog.computeIfAbsent(record.world, k -> new HashMap<>());
        Map<Long, TreeRecord> leaves = byLeaf.computeIfAbsent(record.world, k -> new HashMap<>());
        for (long log : record.logs) {
            logs.put(log, record);
        }
        for (long leaf : record.leaves.keySet()) {
            leaves.put(leaf, record);
        }
    }

    private void remove(TreeRecord record) {
        if (!records.remove(record)) {
            return;
        }
        Map<Long, TreeRecord> logs = byLog.get(record.world);
        Map<Long, TreeRecord> leaves = byLeaf.get(record.world);
        for (long log : record.logs) {
            logs.remove(log, record);
        }
        for (long leaf : record.leaves.keySet()) {
            leaves.remove(leaf, record);
        }
    }

    void save(DataOutputStream out) throws IOException {
        out.writeInt(FILE_MAGIC);
        out.writeInt(FILE_VERSION);
        out.writeInt(records.size());
        for (TreeRecord record : records) {
            out.writeLong(record.world.getMostSignificantBits());
            out.writeLong(record.world.getLeastSignificantBits());
            out.writeUTF(record.species);
            out.writeLong(record.touched);
            out.writeInt(record.logs.size());
            for (long log : record.logs) {
                out.writeLong(log);
            }
            out.writeInt(record.leaves.size());
            for (Map.Entry<Long, Integer> leaf : record.leaves.entrySet()) {
                out.writeLong(leaf.getKey());
                out.writeByte(leaf.getValue());
            }
        }
    }

    /** Adds the records from a file written by {@link #save}. Returns how many were read. */
    int load(DataInputStream in) throws IOException {
        if (in.readInt() != FILE_MAGIC) {
            throw new IOException("not an Arborist tree file");
        }
        int version = in.readInt();
        if (version != FILE_VERSION) {
            throw new IOException("unknown tree file version " + version);
        }
        int count = in.readInt();
        for (int i = 0; i < count; i++) {
            UUID world = new UUID(in.readLong(), in.readLong());
            String species = in.readUTF();
            long touched = in.readLong();
            int logCount = in.readInt();
            Set<Long> logs = new LinkedHashSet<>();
            for (int j = 0; j < logCount; j++) {
                logs.add(in.readLong());
            }
            int leafCount = in.readInt();
            Map<Long, Integer> leaves = new LinkedHashMap<>();
            for (int j = 0; j < leafCount; j++) {
                leaves.put(in.readLong(), (int) in.readByte());
            }
            if (!logs.isEmpty() && !leaves.isEmpty()) {
                add(new TreeRecord(world, species, logs, leaves, touched));
            }
        }
        return count;
    }
}
