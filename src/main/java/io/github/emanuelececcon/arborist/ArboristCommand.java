package io.github.emanuelececcon.arborist;

import java.util.List;
import java.util.Locale;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

/** /arborist status | reload */
final class ArboristCommand implements TabExecutor {

    private final ArboristPlugin plugin;

    ArboristCommand(ArboristPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> status(sender);
            case "reload" -> {
                plugin.reloadSettings();
                sender.sendMessage("Arborist config reloaded.");
                status(sender);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void status(CommandSender sender) {
        Settings settings = plugin.settings();
        TreeTracker tracker = plugin.tracker();
        LeafDecayer decayer = plugin.decayer();
        Feller feller = plugin.feller();
        sender.sendMessage("Arborist " + plugin.getPluginMeta().getVersion()
                + " - felling: " + (settings.felling() ? (settings.fellingRequiresSneak() ? "sneak + axe" : "axe") : "off")
                + ", fast decay: " + (settings.fastDecay() ? "on" : "off"));
        sender.sendMessage("Partly cut trees: " + tracker.size() + " (" + tracker.leafCount() + " leaves waiting for"
                + " their last log), leaves about to fall: " + decayer.pendingCount());
        sender.sendMessage("Since start: " + decayer.ownedDropped + " leaves of cut trees fell, "
                + decayer.orphansDropped + " other leaves decayed, " + feller.treesFelled + " trees felled ("
                + feller.logsFelled + " logs), " + feller.refusedBuilds + " fellings refused next to builds");
        if (plugin.analyses > 0) {
            sender.sendMessage(String.format(Locale.ROOT, "Tree analyses: %d, average %.2f ms, slowest %.2f ms",
                    plugin.analyses, plugin.analysisNanos / 1e6 / plugin.analyses, plugin.slowestAnalysisNanos / 1e6));
        }
        if (decayer.orphanChecks > 0) {
            sender.sendMessage(String.format(Locale.ROOT, "Leaf checks: %d, average %.2f ms, slowest %.2f ms",
                    decayer.orphanChecks, decayer.orphanCheckNanos / 1e6 / decayer.orphanChecks,
                    decayer.slowestOrphanCheckNanos / 1e6));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return List.of("status", "reload").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
}
