package io.github.emanuelececcon.arborist;

import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.Tag;

/**
 * Which blocks are tree logs, which are leaves, and which trees can own which leaves.
 *
 * <p>A tree is made of natural logs of one species: {@code <species>_log}, and {@code stripped_<species>_log} so that
 * stripping a trunk in place doesn't count as cutting it. Wood blocks and nether stems are never part of a tree.
 *
 * <p>A tree owns leaves of its own species, plus the mixes the 26.3 tree features generate: azalea trees have oak logs
 * with azalea leaves, jungle bushes have a jungle log with oak leaves, and the three poplar leaves grow on poplar logs.
 * Leaves of a type no rule matches can be owned by any tree.
 */
final class TreeTypes {

    private final Map<Material, String> logSpecies = new EnumMap<>(Material.class);
    private final Set<Material> keepsLeaves = EnumSet.noneOf(Material.class);
    private final Set<Material> leaves = EnumSet.noneOf(Material.class);
    private final Map<Material, Set<String>> leafOwners = new EnumMap<>(Material.class);

    private TreeTypes(Collection<Material> logs, Collection<Material> leafBlocks) {
        keepsLeaves.addAll(logs);
        for (Material log : logs) {
            String name = log.name().toLowerCase(Locale.ROOT);
            if (name.startsWith("stripped_")) {
                name = name.substring("stripped_".length());
            }
            if (name.endsWith("_log")) {
                logSpecies.put(log, name.substring(0, name.length() - "_log".length()));
            }
        }
        Set<String> species = new HashSet<>(logSpecies.values());
        leaves.addAll(leafBlocks);
        for (Material leaf : leafBlocks) {
            leafOwners.put(leaf, owners(leaf.name().toLowerCase(Locale.ROOT), species));
        }
    }

    /** The server's own tags: {@code #logs} keeps leaves alive in vanilla, {@code #leaves} are the decaying leaves. */
    static TreeTypes fromServer() {
        return new TreeTypes(Tag.LOGS.getValues(), Tag.LEAVES.getValues());
    }

    static TreeTypes of(Collection<Material> logs, Collection<Material> leafBlocks) {
        return new TreeTypes(logs, leafBlocks);
    }

    private static Set<String> owners(String leafName, Set<String> species) {
        Set<String> owners = new HashSet<>();
        String base = leafName.endsWith("_leaves") ? leafName.substring(0, leafName.length() - "_leaves".length()) : leafName;
        switch (base) {
            case "azalea", "flowering_azalea" -> owners.add("oak");
            case "oak" -> {
                owners.add("oak");
                owners.add("jungle");
            }
            default -> {
                if (species.contains(base)) {
                    owners.add(base);
                } else {
                    // red_poplar, orange_poplar, yellow_poplar -> poplar: the longest species the name ends with
                    String best = null;
                    for (String s : species) {
                        if (base.endsWith("_" + s) && (best == null || s.length() > best.length())) {
                            best = s;
                        }
                    }
                    if (best != null) {
                        owners.add(best);
                    }
                }
            }
        }
        owners.retainAll(species);
        return Set.copyOf(owners);
    }

    /** The species of a natural tree log, or null if the block is not one. */
    String species(Material type) {
        return type == null ? null : logSpecies.get(type);
    }

    /** Whether the block keeps nearby leaves alive in vanilla ({@code #prevents_nearby_leaf_decay}, which is {@code #logs}). */
    boolean keepsLeaves(Material type) {
        return keepsLeaves.contains(type);
    }

    boolean isLeaves(Material type) {
        return type != null && leaves.contains(type);
    }

    /** Whether a tree of this species can own leaves of this type. */
    boolean canOwn(String species, Material leaf) {
        Set<String> owners = leafOwners.get(leaf);
        if (owners == null) {
            return false;
        }
        return owners.isEmpty() || owners.contains(species);
    }

    int speciesCount() {
        return new HashSet<>(logSpecies.values()).size();
    }

    int leafTypeCount() {
        return leaves.size();
    }
}
