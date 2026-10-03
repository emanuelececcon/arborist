package io.github.emanuelececcon.arborist;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

/** A snapshot of config.yml. */
record Settings(
        int startDelayTicks,
        int ticksPerStep,
        int randomTicks,
        boolean fastDecay,
        int fastMinDelayTicks,
        int fastMaxDelayTicks,
        boolean felling,
        boolean fellingRequiresSneak,
        boolean fellingInCreative,
        boolean protectAxe,
        boolean particles,
        boolean sound,
        int maxTreeLogs,
        int maxLeavesPerTick,
        long forgetAfterMillis,
        Set<String> disabledWorlds) {

    static Settings load(FileConfiguration config) {
        int fastMin = Math.max(1, config.getInt("fast-decay.min-delay-ticks", 4));
        int fastMax = Math.max(fastMin, config.getInt("fast-decay.max-delay-ticks", 16));
        Set<String> disabled = new HashSet<>();
        for (String world : config.getStringList("disabled-worlds")) {
            disabled.add(world.toLowerCase(Locale.ROOT));
        }
        return new Settings(
                Math.max(1, config.getInt("falling-leaves.start-delay-ticks", 10)),
                Math.max(0, config.getInt("falling-leaves.ticks-per-step", 10)),
                Math.max(0, config.getInt("falling-leaves.random-ticks", 10)),
                config.getBoolean("fast-decay.enabled", true),
                fastMin,
                fastMax,
                config.getBoolean("felling.enabled", true),
                config.getBoolean("felling.require-sneak", true),
                config.getBoolean("felling.creative", false),
                config.getBoolean("felling.protect-axe", true),
                config.getBoolean("effects.particles", true),
                config.getBoolean("effects.sound", false),
                Math.max(1, config.getInt("max-tree-logs", 400)),
                Math.max(1, config.getInt("max-leaves-per-tick", 200)),
                Math.max(1L, config.getLong("forget-after-hours", 72)) * 3_600_000L,
                Set.copyOf(disabled));
    }

    boolean enabledIn(World world) {
        return !disabledWorlds.contains(world.getName().toLowerCase(Locale.ROOT));
    }
}
