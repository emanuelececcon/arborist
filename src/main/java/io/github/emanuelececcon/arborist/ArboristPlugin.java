package io.github.emanuelececcon.arborist;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** Arborist: leaves belong to their own tree and fall when its last log is gone; sneak with an axe to fell a tree. */
public final class ArboristPlugin extends JavaPlugin {

    private static final String TREES_FILE = "trees.dat";

    private Settings settings;
    private TreeTypes types;
    private PlacedLogs placedLogs;
    private TreeTracker tracker;
    private LeafDecayer decayer;
    private Feller feller;

    long analyses;
    long analysisNanos;
    long slowestAnalysisNanos;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = Settings.load(getConfig());
        types = TreeTypes.fromServer();
        placedLogs = new PlacedLogs(this);
        tracker = new TreeTracker(types);
        loadTrees();
        decayer = new LeafDecayer(this);
        feller = new Feller(this);

        getServer().getPluginManager().registerEvents(new TreeListener(this), this);
        PluginCommand command = getCommand("arborist");
        if (command != null) {
            ArboristCommand executor = new ArboristCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        getServer().getScheduler().runTaskTimer(this, decayer, 1L, 1L);
        // Every 5 minutes: forget trees nobody touched for a long time, and save the rest in case of a crash.
        getServer().getScheduler().runTaskTimer(this, () -> {
            int expired = tracker.expire(System.currentTimeMillis(), settings.forgetAfterMillis());
            if (expired > 0) {
                getLogger().info("Forgot " + expired + " partly cut trees nobody touched for a long time");
            }
            saveTrees();
        }, 6000L, 6000L);
        getLogger().info("Tree species: " + types.speciesCount() + ", leaf types: " + types.leafTypeCount()
                + ", partly cut trees: " + tracker.size()
                + ", felling: " + (settings.felling() ? (settings.fellingRequiresSneak() ? "sneak + axe" : "axe") : "off"));
    }

    @Override
    public void onDisable() {
        if (decayer != null) {
            decayer.flush();
        }
        if (tracker != null) {
            saveTrees();
        }
    }

    void reloadSettings() {
        reloadConfig();
        settings = Settings.load(getConfig());
    }

    /** Records the tree of a natural log that is about to be removed. */
    TreeRecord track(Block block) {
        long start = System.nanoTime();
        TreeRecord record = tracker.track(block.getWorld().getUID(), view(block.getWorld()),
                block.getX(), block.getY(), block.getZ(), settings.maxTreeLogs(), System.currentTimeMillis());
        long took = System.nanoTime() - start;
        analyses++;
        analysisNanos += took;
        slowestAnalysisNanos = Math.max(slowestAnalysisNanos, took);
        return record;
    }

    BukkitWorldView view(World world) {
        return new BukkitWorldView(world, placedLogs);
    }

    private void loadTrees() {
        File file = new File(getDataFolder(), TREES_FILE);
        if (!file.isFile()) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(file))))) {
            tracker.load(in);
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Could not read " + TREES_FILE + "; partly cut trees are forgotten", e);
        }
    }

    private void saveTrees() {
        File folder = getDataFolder();
        File file = new File(folder, TREES_FILE);
        File temp = new File(folder, TREES_FILE + ".tmp");
        try {
            Files.createDirectories(folder.toPath());
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(temp))))) {
                tracker.save(out);
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Could not save " + TREES_FILE, e);
        }
    }

    Settings settings() {
        return settings;
    }

    TreeTypes types() {
        return types;
    }

    PlacedLogs placedLogs() {
        return placedLogs;
    }

    TreeTracker tracker() {
        return tracker;
    }

    LeafDecayer decayer() {
        return decayer;
    }

    Feller feller() {
        return feller;
    }
}
