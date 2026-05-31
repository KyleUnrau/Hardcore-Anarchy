package dev.unrau.hardcoreanarchy.command;

import dev.unrau.hardcoreanarchy.config.PluginConfig;
import dev.unrau.hardcoreanarchy.service.ExileSpawnService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class HeaCommand implements CommandExecutor {

    private final PluginConfig config;
    private final ExileSpawnService spawnService;
    private final String version;

    public HeaCommand(PluginConfig config, ExileSpawnService spawnService, String version) {
        this.config = config;
        this.spawnService = spawnService;
        this.version = version;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("hardcoreanarchy.admin")) {
            sender.sendMessage("You don't have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("Usage: /hea <reload|version|debugspawn>");
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "version" -> {
                sender.sendMessage("HardcoreAnarchy v" + version);
                yield true;
            }
            case "reload" -> {
                config.reload();
                sender.sendMessage("HardcoreAnarchy config reloaded.");
                yield true;
            }
            case "debugspawn" -> {
                handleDebugSpawn(sender);
                yield true;
            }
            default -> {
                sender.sendMessage("Usage: /hea <reload|version|debugspawn>");
                yield true;
            }
        };
    }

    private void handleDebugSpawn(CommandSender sender) {
        World world = Bukkit.getWorld(config.getWorldName());
        if (world == null) {
            sender.sendMessage("World '" + config.getWorldName() + "' not found.");
            return;
        }

        sender.sendMessage("Searching for a debug exile spawn location...");

        // Use a dummy UUID that won't match any real player
        UUID dummyUuid = UUID.fromString("00000000-0000-0000-0000-000000000000");
        Location senderLoc = (sender instanceof Player p) ? p.getLocation() : world.getSpawnLocation();

        spawnService.findFirstJoinSpawn(world, dummyUuid, sender.getName() + "[debug]", location -> {
            sender.sendMessage(String.format(
                "Debug spawn found: world=%s x=%d y=%d z=%d",
                location.getWorld().getName(),
                (long) location.getX(),
                (long) location.getY(),
                (long) location.getZ()
            ));
        });
    }
}
