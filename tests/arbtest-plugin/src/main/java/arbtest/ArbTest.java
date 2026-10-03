package arbtest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

/**
 * Test-only helper for Arborist, driven over RCON by tests/bot/tests.py. Never install it on a real server.
 * Every command answers "ARBTEST OK ..." or "ARBTEST FAIL ...".
 */
public final class ArbTest extends JavaPlugin {

    private static final Set<Material> SOIL = Set.of(Material.DIRT, Material.GRASS_BLOCK, Material.PODZOL,
            Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.MUD, Material.MOSS_BLOCK, Material.MYCELIUM,
            Material.MANGROVE_ROOTS, Material.MUDDY_MANGROVE_ROOTS, Material.PALE_MOSS_BLOCK);

    private World world;

    @Override
    public void onEnable() {
        world = Bukkit.getWorlds().get(0);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            sender.sendMessage("ARBTEST OK " + run(args));
        } catch (Exception e) {
            sender.sendMessage("ARBTEST FAIL " + e);
            getLogger().log(java.util.logging.Level.WARNING, "arbtest " + String.join(" ", args), e);
        }
        return true;
    }

    private String run(String[] a) throws IOException {
        switch (a[0]) {
            case "world" -> {
                world = Bukkit.getWorld(a[1]);
                if (world == null) {
                    throw new IllegalArgumentException("no world " + a[1]);
                }
                return world.getName();
            }
            case "makeworld" -> {
                World created = new WorldCreator(a[1]).seed(Long.parseLong(a[2])).type(WorldType.NORMAL).createWorld();
                return created.getName() + " " + created.getKey();
            }
            case "load", "unload" -> {
                boolean load = a[0].equals("load");
                int count = 0;
                for (int cx = i(a[1]) >> 4; cx <= i(a[3]) >> 4; cx++) {
                    for (int cz = i(a[2]) >> 4; cz <= i(a[4]) >> 4; cz++) {
                        world.setChunkForceLoaded(cx, cz, load);
                        if (load) {
                            world.getChunkAt(cx, cz).load(true);
                        }
                        count++;
                    }
                }
                return "chunks=" + count;
            }
            case "clear" -> {
                int ground = i(a[7]);
                int changed = 0;
                for (int x = i(a[1]); x <= i(a[4]); x++) {
                    for (int z = i(a[3]); z <= i(a[6]); z++) {
                        for (int y = i(a[2]); y <= i(a[5]); y++) {
                            Material want = y > ground ? Material.AIR : y == ground ? Material.GRASS_BLOCK : Material.DIRT;
                            Block block = world.getBlockAt(x, y, z);
                            if (block.getType() != want) {
                                block.setType(want, false);
                                changed++;
                            }
                        }
                    }
                }
                removeItems(box(a, 1));
                return "changed=" + changed;
            }
            case "clone" -> {
                // Copies non-air blocks like a tree being generated: only logs replace logs.
                int dx = i(a[7]) - i(a[1]);
                int dy = i(a[8]) - i(a[2]);
                int dz = i(a[9]) - i(a[3]);
                int copied = 0;
                for (int x = i(a[1]); x <= i(a[4]); x++) {
                    for (int y = i(a[2]); y <= i(a[5]); y++) {
                        for (int z = i(a[3]); z <= i(a[6]); z++) {
                            Block from = world.getBlockAt(x, y, z);
                            if (from.getType().isAir()) {
                                continue;
                            }
                            Block to = world.getBlockAt(x + dx, y + dy, z + dz);
                            if (!Tag.LOGS.isTagged(from.getType()) && Tag.LOGS.isTagged(to.getType())) {
                                continue;
                            }
                            to.setBlockData(from.getBlockData(), false);
                            copied++;
                        }
                    }
                }
                return "copied=" + copied;
            }
            case "snapshot" -> {
                BoundingBox box = box(a, 2);
                StringBuilder leaves = new StringBuilder();
                StringBuilder logs = new StringBuilder();
                for (int x = i(a[2]); x <= i(a[5]); x++) {
                    for (int y = i(a[3]); y <= i(a[6]); y++) {
                        for (int z = i(a[4]); z <= i(a[7]); z++) {
                            Block block = world.getBlockAt(x, y, z);
                            BlockData data = block.getBlockData();
                            String type = block.getType().getKey().getKey();
                            if (data instanceof Leaves l) {
                                sep(leaves).append('[').append(x).append(',').append(y).append(',').append(z)
                                        .append(",\"").append(type).append("\",").append(l.isPersistent() ? 1 : 0)
                                        .append(',').append(l.getDistance()).append(']');
                            } else if (Tag.LOGS.isTagged(block.getType())) {
                                sep(logs).append('[').append(x).append(',').append(y).append(',').append(z)
                                        .append(",\"").append(type).append("\"]");
                            }
                        }
                    }
                }
                Map<String, Integer> items = new TreeMap<>();
                for (Entity entity : world.getNearbyEntities(box)) {
                    if (entity instanceof Item item) {
                        items.merge(item.getItemStack().getType().getKey().getKey(), item.getItemStack().getAmount(), Integer::sum);
                    }
                }
                StringBuilder json = new StringBuilder("{\"leaves\":[").append(leaves).append("],\"logs\":[")
                        .append(logs).append("],\"items\":{");
                boolean first = true;
                for (Map.Entry<String, Integer> e : items.entrySet()) {
                    json.append(first ? "" : ",").append('"').append(e.getKey()).append("\":").append(e.getValue());
                    first = false;
                }
                json.append("}}");
                write(a[1], json.toString());
                return a[1];
            }
            case "gear" -> {
                Player p = player(a[1]);
                p.setGameMode(GameMode.SURVIVAL);
                p.getInventory().clear();
                p.setFoodLevel(20);
                p.setSaturation(20);
                p.setInvulnerable(true);
                ItemStack tool = new ItemStack(Material.valueOf(a[2].toUpperCase(Locale.ROOT)));
                if (tool.getItemMeta() instanceof Damageable meta && i(a[3]) > 0) {
                    meta.setDamage(i(a[3]));
                    tool.setItemMeta(meta);
                }
                p.getInventory().setHeldItemSlot(0);
                p.getInventory().setItemInMainHand(tool);
                return "gave " + tool.getType();
            }
            case "hand" -> {
                ItemStack hand = player(a[1]).getInventory().getItemInMainHand();
                int damage = hand.getItemMeta() instanceof Damageable meta ? meta.getDamage() : -1;
                return "type=" + hand.getType().getKey().getKey() + " damage=" + damage;
            }
            case "break" -> {
                Player p = player(a[1]);
                Block block = world.getBlockAt(i(a[2]), i(a[3]), i(a[4]));
                p.setSneaking(a[5].equals("1"));
                try {
                    return "broken=" + p.breakBlock(block);
                } finally {
                    p.setSneaking(false);
                }
            }
            case "strip" -> {
                int removed = 0;
                for (int x = i(a[1]); x <= i(a[4]); x++) {
                    for (int y = i(a[2]); y <= i(a[5]); y++) {
                        for (int z = i(a[3]); z <= i(a[6]); z++) {
                            Block block = world.getBlockAt(x, y, z);
                            if (Tag.LOGS.isTagged(block.getType())) {
                                block.setType(Material.AIR, true);
                                removed++;
                            }
                        }
                    }
                }
                return "removed=" + removed;
            }
            case "setblock" -> {
                world.getBlockAt(i(a[1]), i(a[2]), i(a[3])).setType(Material.valueOf(a[4].toUpperCase(Locale.ROOT)), false);
                return "set";
            }
            case "placelog" -> {
                Player p = player(a[1]);
                Block block = world.getBlockAt(i(a[2]), i(a[3]), i(a[4]));
                Material type = Material.valueOf(a[5].toUpperCase(Locale.ROOT));
                BlockState replaced = block.getState();
                block.setType(type, false);
                BlockPlaceEvent event = new BlockPlaceEvent(block, replaced, block.getRelative(BlockFace.DOWN),
                        new ItemStack(type), p, true, EquipmentSlot.HAND);
                Bukkit.getPluginManager().callEvent(event);
                if (event.isCancelled()) {
                    replaced.update(true, false);
                    return "cancelled";
                }
                return "placed";
            }
            case "explode" -> {
                return "exploded=" + world.createExplosion(d(a[1]), d(a[2]), d(a[3]), Float.parseFloat(a[4]), false, true);
            }
            case "tp" -> {
                World target = a.length > 5 ? Bukkit.getWorld(a[5]) : world;
                double y = a[3].equals("surface") ? target.getHighestBlockYAt(i(a[2]), i(a[4])) + 1 : d(a[3]);
                player(a[1]).teleport(new Location(target, d(a[2]) + 0.5, y, d(a[4]) + 0.5));
                return "tp";
            }
            case "trees" -> {
                // Trunk bases: natural, unstripped logs standing on soil, from the top of each column down.
                StringBuilder out = new StringBuilder();
                for (int x = i(a[2]); x <= i(a[4]); x++) {
                    for (int z = i(a[3]); z <= i(a[5]); z++) {
                        int top = world.getHighestBlockYAt(x, z);
                        for (int y = top; y > top - 40 && y > world.getMinHeight(); y--) {
                            Material type = world.getType(x, y, z);
                            String name = type.name();
                            if (name.endsWith("_LOG") && !name.startsWith("STRIPPED_") && SOIL.contains(world.getType(x, y - 1, z))) {
                                sep(out).append('[').append(x).append(',').append(y).append(',').append(z).append(",\"")
                                        .append(type.getKey().getKey()).append("\"]");
                                break;
                            }
                        }
                    }
                }
                write(a[1], "{\"trees\":[" + out + "]}");
                return a[1];
            }
            case "killitems" -> {
                return "removed=" + removeItems(box(a, 1));
            }
            default -> throw new IllegalArgumentException("unknown " + a[0]);
        }
    }

    private int removeItems(BoundingBox box) {
        int count = 0;
        for (Entity entity : world.getNearbyEntities(box)) {
            if (entity instanceof Item) {
                entity.remove();
                count++;
            }
        }
        return count;
    }

    private static StringBuilder sep(StringBuilder sb) {
        if (!sb.isEmpty()) {
            sb.append(',');
        }
        return sb;
    }

    private void write(String name, String json) throws IOException {
        Path folder = getDataFolder().toPath();
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(name + ".json"), json, StandardCharsets.UTF_8);
    }

    private static BoundingBox box(String[] a, int from) {
        return new BoundingBox(i(a[from]), i(a[from + 1]), i(a[from + 2]), i(a[from + 3]) + 1, i(a[from + 4]) + 1,
                i(a[from + 5]) + 1);
    }

    private static Player player(String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) {
            throw new IllegalArgumentException("no player " + name);
        }
        return p;
    }

    private static int i(String s) {
        return Integer.parseInt(s);
    }

    private static double d(String s) {
        return Double.parseDouble(s);
    }
}
