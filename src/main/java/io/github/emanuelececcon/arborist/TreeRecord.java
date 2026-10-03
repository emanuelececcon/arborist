package io.github.emanuelececcon.arborist;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A tree that is being cut: its logs and the leaves it owned when its first log went. The leaf list is taken while
 * the tree is still whole, so leaves don't change owner while the tree loses its logs.
 */
final class TreeRecord {

    final UUID world;
    final String species;
    final Set<Long> logs;
    /** Owned leaf to its distance in steps from the tree's logs, used to make the crown fall from the inside out. */
    final Map<Long, Integer> leaves;
    long touched;

    TreeRecord(UUID world, String species, Set<Long> logs, Map<Long, Integer> leaves, long touched) {
        this.world = world;
        this.species = species;
        this.logs = new LinkedHashSet<>(logs);
        this.leaves = new LinkedHashMap<>(leaves);
        this.touched = touched;
    }
}
