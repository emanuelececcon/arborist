package io.github.emanuelececcon.arborist;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Fells a whole tree when a player breaks one of its logs with an axe (while sneaking, by default). Every other log
 * at or above the cut is broken as if the player had broken it: one break event each, normal drops, one point of axe
 * damage each (Unbreaking applies), statistics and hunger. The farthest logs go first, so if the axe is about to break
 * the felling stops and what is left is a stump.
 *
 * <p>Logs placed before Arborist was installed look natural, so a log build next to a tree could pass for one. A tree
 * whose logs touch building blocks (planks, stairs, slabs, doors, glass, fences, wood blocks, logs placed by players
 * and so on) is never felled: only the broken log goes, and the player is told why.
 */
final class Feller {

    static final String PERMISSION = "arborist.fell";

    private final ArboristPlugin plugin;
    private final Set<UUID> felling = new HashSet<>();
    private final Set<Material> buildBlocks = EnumSet.noneOf(Material.class);

    long treesFelled;
    long logsFelled;
    long refusedBuilds;

    Feller(ArboristPlugin plugin) {
        this.plugin = plugin;
        for (Tag<Material> tag : List.of(Tag.PLANKS, Tag.STAIRS, Tag.SLABS, Tag.DOORS, Tag.TRAPDOORS, Tag.FENCES,
                Tag.FENCE_GATES, Tag.WALLS, Tag.ALL_SIGNS, Tag.BEDS, Tag.WOOL, Tag.WOOL_CARPETS, Tag.BANNERS, Tag.BARS,
                Tag.CHAINS, Tag.COPPER_CHESTS, Tag.WOODEN_SHELVES)) {
            buildBlocks.addAll(tag.getValues());
        }
        Set<String> named = Set.of("CHEST", "TRAPPED_CHEST", "BARREL", "CRAFTING_TABLE", "FURNACE", "BLAST_FURNACE",
                "SMOKER", "BOOKSHELF", "CHISELED_BOOKSHELF", "LECTERN", "CARTOGRAPHY_TABLE", "FLETCHING_TABLE",
                "SMITHING_TABLE", "LOOM", "COMPOSTER", "BRICKS");
        for (Material type : Material.values()) {
            String name = type.name();
            if (name.startsWith("LEGACY_") || !type.isBlock()) {
                continue;
            }
            if (named.contains(name) || name.contains("GLASS") || name.endsWith("_WOOD") || name.endsWith("_HYPHAE")
                    || name.endsWith("_BRICKS") || name.endsWith("CONCRETE")) {
                buildBlocks.add(type);
            }
        }
    }

    /** True while this player's breaks are made by Arborist. */
    boolean isFelling(Player player) {
        return felling.contains(player.getUniqueId());
    }

    /** Whether breaking a log right now should fell its tree. */
    boolean wants(Player player) {
        Settings settings = plugin.settings();
        if (!settings.felling() || isFelling(player) || !player.hasPermission(PERMISSION)) {
            return false;
        }
        if (settings.fellingRequiresSneak() && !player.isSneaking()) {
            return false;
        }
        GameMode mode = player.getGameMode();
        boolean modeAllowed = mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE
                || (mode == GameMode.CREATIVE && settings.fellingInCreative());
        return modeAllowed && Tag.ITEMS_AXES.isTagged(player.getInventory().getItemInMainHand().getType());
    }

    /** Called while the cut log is still in place; the rest of the tree is broken on the next tick. */
    void plan(Player player, Block cut, String species) {
        BukkitWorldView view = plugin.view(cut.getWorld());
        TreeAnalyzer analyzer = new TreeAnalyzer(view, plugin.types());
        LinkedHashSet<Long> logs = analyzer.trunk(cut.getX(), cut.getY(), cut.getZ(), plugin.settings().maxTreeLogs(), cut.getY());
        if (logs == null || logs.size() < 2) {
            return;
        }
        List<Long> order = new ArrayList<>(logs);
        order.remove(0); // the cut log, which the player is breaking right now
        if (touchesBuild(view, order)) {
            refusedBuilds++;
            player.sendActionBar(Component.text("This tree touches a build: only one log was cut", NamedTextColor.YELLOW));
            return;
        }
        Collections.reverse(order); // farthest first
        World world = cut.getWorld();
        plugin.getServer().getScheduler().runTask(plugin, () -> fell(player, world, species, order));
    }

    private void fell(Player player, World world, String species, List<Long> order) {
        if (!player.isOnline() || !player.getWorld().equals(world)) {
            return;
        }
        int broken = 0;
        felling.add(player.getUniqueId());
        try {
            for (long pos : order) {
                if (!axeUsable(player)) {
                    break;
                }
                int x = Pos.x(pos);
                int y = Pos.y(pos);
                int z = Pos.z(pos);
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                Block block = world.getBlockAt(x, y, z);
                if (!species.equals(plugin.types().species(block.getType()))) {
                    continue;
                }
                BlockData data = block.getBlockData();
                if (player.breakBlock(block)) {
                    broken++;
                    // The break effect goes to everyone else; the player who fells the tree gets the particles here.
                    player.spawnParticle(Particle.BLOCK, x + 0.5, y + 0.5, z + 0.5, 16, 0.3, 0.3, 0.3, 0, data);
                }
            }
        } finally {
            felling.remove(player.getUniqueId());
        }
        if (broken > 0) {
            treesFelled++;
            logsFelled += broken;
        }
    }

    private boolean touchesBuild(WorldView view, List<Long> logs) {
        TreeTypes types = plugin.types();
        for (long log : logs) {
            for (int[] f : Pos.FACES) {
                long next = Pos.offset(log, f[0], f[1], f[2]);
                Material type = view.type(next);
                if (type == null) {
                    continue;
                }
                if (buildBlocks.contains(type) || (types.species(type) != null && view.placed(next))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean axeUsable(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!Tag.ITEMS_AXES.isTagged(hand.getType())) {
            return false;
        }
        if (!plugin.settings().protectAxe()) {
            return true;
        }
        ItemMeta meta = hand.getItemMeta();
        if (!(meta instanceof Damageable damageable) || meta.isUnbreakable()) {
            return true;
        }
        int max = damageable.hasMaxDamage() ? damageable.getMaxDamage() : hand.getType().getMaxDurability();
        return max <= 0 || max - damageable.getDamage() > 1;
    }
}
