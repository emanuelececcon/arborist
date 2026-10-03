package io.github.emanuelececcon.arborist;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;

/**
 * Finds trees and decides which leaves belong to them.
 *
 * <p>Vanilla keeps a leaf alive while any block of {@code #logs} is at most 6 steps away through leaves, whichever tree
 * it belongs to. That is why the crown of a cut tree stays up when it touches another tree. Here every natural leaf
 * belongs to one tree: the one whose logs are fewest steps away through leaves that tree can own, ties going to the
 * log that is closer in a straight line. A tree keeps its own leaves alive, and only those.
 */
final class TreeAnalyzer {

    /** Leaves more than this many steps from every log decay (vanilla distance 7). */
    static final int REACH = 6;
    /** Another tree can only compete for a leaf within REACH of this tree if its log is within this many steps. */
    private static final int SCAN = 2 * REACH - 1;

    private final WorldView view;
    private final TreeTypes types;

    TreeAnalyzer(WorldView view, TreeTypes types) {
        this.view = view;
        this.types = types;
    }

    /**
     * The logs of the tree that contains the log at (x, y, z), in breadth-first order from it: natural logs of the
     * same species that players didn't place, connected through faces, edges or corners (branches step diagonally).
     * Logs below minY are left out. Returns null when the block is not such a log, the tree has more than maxLogs
     * logs, or it reaches into an unloaded chunk.
     */
    LinkedHashSet<Long> trunk(int x, int y, int z, int maxLogs, int minY) {
        String species = types.species(view.type(x, y, z));
        if (species == null || y < minY || view.placed(x, y, z)) {
            return null;
        }
        long start = Pos.pack(x, y, z);
        LinkedHashSet<Long> logs = new LinkedHashSet<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        logs.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            long pos = queue.poll();
            int px = Pos.x(pos);
            int py = Pos.y(pos);
            int pz = Pos.z(pos);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int ny = py + dy;
                        if ((dx | dy | dz) == 0 || ny < minY) {
                            continue;
                        }
                        long next = Pos.pack(px + dx, ny, pz + dz);
                        if (logs.contains(next)) {
                            continue;
                        }
                        Material type = view.type(px + dx, ny, pz + dz);
                        if (type == null) {
                            return null;
                        }
                        if (!species.equals(types.species(type)) || view.placed(px + dx, ny, pz + dz)) {
                            continue;
                        }
                        logs.add(next);
                        if (logs.size() > maxLogs) {
                            return null;
                        }
                        queue.add(next);
                    }
                }
            }
        }
        return logs;
    }

    /** How a tree reaches a leaf: fewest steps through leaves, and the squared distance to the log it comes from. */
    private static final class Reach {
        final Material type;
        final int steps;
        long source;
        int euclid;

        Reach(Material type, int steps, long leaf, long source) {
            this.type = type;
            this.steps = steps;
            this.source = source;
            this.euclid = Pos.distanceSquared(leaf, source);
        }

        void offer(long leaf, long otherSource) {
            int d = Pos.distanceSquared(leaf, otherSource);
            if (d < euclid) {
                euclid = d;
                source = otherSource;
            }
        }

        boolean beats(Reach other) {
            return other == null || steps < other.steps || (steps == other.steps && euclid < other.euclid);
        }
    }

    /**
     * The natural leaves the tree owns, with their distance in steps from its logs. A leaf is owned when the tree
     * keeps it alive (at most REACH steps through natural leaves it can own) and no other natural tree that could own
     * it is nearer. Exact ties stay with the other tree. Leaves in {@code taken} already belong to another tracked
     * tree and are never owned again.
     */
    Map<Long, Integer> ownedLeaves(String species, Set<Long> logs, Set<Long> taken) {
        // 1) Everything this tree reaches within SCAN steps. A rival log that is within REACH of a leaf this tree
        //    keeps alive always touches one of these leaves, and the rival's path to that leaf stays inside them.
        Map<Long, Reach> mine = new LinkedHashMap<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        Map<String, Set<Long>> rivals = new HashMap<>();
        for (long log : logs) {
            for (int[] f : Pos.FACES) {
                long leaf = Pos.offset(log, f[0], f[1], f[2]);
                Reach known = mine.get(leaf);
                if (known != null) {
                    known.offer(leaf, log);
                    continue;
                }
                Material type = view.type(leaf);
                if (ownable(species, type, leaf)) {
                    mine.put(leaf, new Reach(type, 1, leaf, log));
                    queue.add(leaf);
                }
            }
        }
        while (!queue.isEmpty()) {
            long pos = queue.poll();
            Reach here = mine.get(pos);
            for (int[] f : Pos.FACES) {
                long next = Pos.offset(pos, f[0], f[1], f[2]);
                Reach known = mine.get(next);
                if (known != null) {
                    if (known.steps == here.steps + 1) {
                        known.offer(next, here.source);
                    }
                    continue;
                }
                Material type = view.type(next);
                if (type == null) {
                    continue;
                }
                if (types.isLeaves(type)) {
                    if (here.steps < SCAN && ownable(species, type, next)) {
                        mine.put(next, new Reach(type, here.steps + 1, next, here.source));
                        queue.add(next);
                    }
                } else if (!logs.contains(next)) {
                    String other = types.species(type);
                    if (other != null && !view.placed(next)) {
                        rivals.computeIfAbsent(other, k -> new HashSet<>()).add(next);
                    }
                }
            }
        }

        // 2) The nearest rival tree for each of those leaves, each species moving only through leaves it can own.
        Map<Long, Reach> nearestRival = new HashMap<>();
        for (Map.Entry<String, Set<Long>> rival : rivals.entrySet()) {
            String rivalSpecies = rival.getKey();
            Map<Long, Reach> theirs = new HashMap<>();
            for (long log : rival.getValue()) {
                for (int[] f : Pos.FACES) {
                    long leaf = Pos.offset(log, f[0], f[1], f[2]);
                    Reach shared = mine.get(leaf);
                    if (shared == null || !types.canOwn(rivalSpecies, shared.type)) {
                        continue;
                    }
                    Reach known = theirs.get(leaf);
                    if (known == null) {
                        theirs.put(leaf, new Reach(shared.type, 1, leaf, log));
                        queue.add(leaf);
                    } else {
                        known.offer(leaf, log);
                    }
                }
            }
            while (!queue.isEmpty()) {
                long pos = queue.poll();
                Reach here = theirs.get(pos);
                if (here.steps >= REACH) {
                    continue;
                }
                for (int[] f : Pos.FACES) {
                    long next = Pos.offset(pos, f[0], f[1], f[2]);
                    Reach shared = mine.get(next);
                    if (shared == null || !types.canOwn(rivalSpecies, shared.type)) {
                        continue;
                    }
                    Reach known = theirs.get(next);
                    if (known == null) {
                        theirs.put(next, new Reach(shared.type, here.steps + 1, next, here.source));
                        queue.add(next);
                    } else if (known.steps == here.steps + 1) {
                        known.offer(next, here.source);
                    }
                }
            }
            for (Map.Entry<Long, Reach> e : theirs.entrySet()) {
                if (e.getValue().beats(nearestRival.get(e.getKey()))) {
                    nearestRival.put(e.getKey(), e.getValue());
                }
            }
        }

        // 3) Owned: kept alive by this tree and strictly nearer to it than to any rival.
        Map<Long, Integer> owned = new LinkedHashMap<>();
        for (Map.Entry<Long, Reach> e : mine.entrySet()) {
            Reach reach = e.getValue();
            if (reach.steps <= REACH && !taken.contains(e.getKey()) && reach.beats(nearestRival.get(e.getKey()))) {
                owned.put(e.getKey(), reach.steps);
            }
        }
        return owned;
    }

    /** The natural leaves that the given logs still keep alive: at most REACH steps through leaves the species can own. */
    Set<Long> stillHeld(String species, Collection<Long> logs) {
        Map<Long, Integer> steps = new HashMap<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        for (long log : logs) {
            for (int[] f : Pos.FACES) {
                long leaf = Pos.offset(log, f[0], f[1], f[2]);
                if (!steps.containsKey(leaf) && ownable(species, view.type(leaf), leaf)) {
                    steps.put(leaf, 1);
                    queue.add(leaf);
                }
            }
        }
        while (!queue.isEmpty()) {
            long pos = queue.poll();
            int here = steps.get(pos);
            if (here >= REACH) {
                continue;
            }
            for (int[] f : Pos.FACES) {
                long next = Pos.offset(pos, f[0], f[1], f[2]);
                if (!steps.containsKey(next) && ownable(species, view.type(next), next)) {
                    steps.put(next, here + 1);
                    queue.add(next);
                }
            }
        }
        return steps.keySet();
    }

    /**
     * Natural leaves near the given positions that nothing keeps alive by vanilla's rule (no block of {@code #logs}
     * within REACH steps through any leaves): the leaves vanilla would let decay slowly. Only leaves within REACH steps
     * of a seed are checked, and leaves in {@code skip} are left out. Unloaded chunks count as keeping leaves alive.
     */
    Set<Long> orphansNear(Collection<Long> seeds, Set<Long> skip) {
        // Every leaf on a path that keeps a candidate alive is within REACH - 1 steps of it, so within SCAN of a seed.
        Map<Long, Integer> region = new HashMap<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        for (long seed : seeds) {
            for (int[] f : Pos.FACES) {
                long leaf = Pos.offset(seed, f[0], f[1], f[2]);
                if (!region.containsKey(leaf) && types.isLeaves(view.type(leaf))) {
                    region.put(leaf, 1);
                    queue.add(leaf);
                }
            }
        }
        Set<Long> candidates = new LinkedHashSet<>();
        ArrayDeque<Long> alive = new ArrayDeque<>();
        Map<Long, Integer> held = new HashMap<>();
        while (!queue.isEmpty()) {
            long pos = queue.poll();
            int here = region.get(pos);
            if (here <= REACH && !skip.contains(pos) && !view.persistent(pos)) {
                candidates.add(pos);
            }
            boolean nextToLog = false;
            for (int[] f : Pos.FACES) {
                long next = Pos.offset(pos, f[0], f[1], f[2]);
                Material type = view.type(next);
                if (type == null || types.keepsLeaves(type)) {
                    nextToLog = true;
                } else if (here < SCAN && !region.containsKey(next) && types.isLeaves(type)) {
                    region.put(next, here + 1);
                    queue.add(next);
                }
            }
            if (nextToLog) {
                held.put(pos, 1);
                alive.add(pos);
            }
        }
        if (candidates.isEmpty()) {
            return candidates;
        }
        while (!alive.isEmpty()) {
            long pos = alive.poll();
            int here = held.get(pos);
            if (here >= REACH) {
                continue;
            }
            for (int[] f : Pos.FACES) {
                long next = Pos.offset(pos, f[0], f[1], f[2]);
                if (region.containsKey(next) && !held.containsKey(next)) {
                    held.put(next, here + 1);
                    alive.add(next);
                }
            }
        }
        candidates.removeIf(held::containsKey);
        return candidates;
    }

    private boolean ownable(String species, Material type, long pos) {
        return types.isLeaves(type) && types.canOwn(species, type) && !view.persistent(pos);
    }
}
