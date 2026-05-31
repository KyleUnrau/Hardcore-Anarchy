package dev.unrau.hardcoreanarchy.listener;

import dev.unrau.hardcoreanarchy.config.PluginConfig;
import dev.unrau.hardcoreanarchy.data.PlayerData;
import dev.unrau.hardcoreanarchy.data.PlayerDataStore;
import dev.unrau.hardcoreanarchy.log.SpawnLogger;
import dev.unrau.hardcoreanarchy.service.ExileSpawnService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class FirstJoinListener implements Listener {

    private final JavaPlugin plugin;
    private final PluginConfig config;
    private final PlayerDataStore dataStore;
    private final ExileSpawnService spawnService;
    private final SpawnLogger spawnLogger;

    public FirstJoinListener(JavaPlugin plugin, PluginConfig config,
                             PlayerDataStore dataStore, ExileSpawnService spawnService,
                             SpawnLogger spawnLogger) {
        this.plugin = plugin;
        this.config = config;
        this.dataStore = dataStore;
        this.spawnService = spawnService;
        this.spawnLogger = spawnLogger;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerData data = dataStore.load(player.getUniqueId());

        if (data.isHasJoinedBefore()) {
            // Check if they need a delayed teleport from a previous session's death
            // (e.g. server restarted mid-calculation — treat as needing fresh exile)
            if (data.isNeedsDelayedTeleport() && data.isHasPendingRespawn()) {
                World world = Bukkit.getWorld(data.getPendingRespawnWorld());
                if (world != null) {
                    Location dest = new Location(world,
                        data.getPendingRespawnX(), data.getPendingRespawnY(), data.getPendingRespawnZ());
                    player.teleport(dest);
                    player.sendMessage(config.getMsgDeathScattered());
                    data.clearPendingRespawn();
                    data.setNeedsDelayedTeleport(false);
                    dataStore.save(player.getUniqueId(), data);
                }
            }
            return;
        }

        // First time joining — teleport to exile spawn
        World world = Bukkit.getWorld(config.getWorldName());
        if (world == null) {
            plugin.getLogger().severe("World '" + config.getWorldName() + "' not found; cannot exile first-join player.");
            return;
        }

        String playerName = player.getName();
        spawnService.findFirstJoinSpawn(world, player.getUniqueId(), playerName, location -> {
            // Callback fires on main thread
            data.setHasJoinedBefore(true);
            data.setFirstSpawn(location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
            dataStore.save(player.getUniqueId(), data);

            spawnLogger.log(SpawnLogger.Reason.FIRST_JOIN, player.getUniqueId(), playerName,
                location.getWorld().getName(), location.getX(), location.getY(), location.getZ());

            // Teleport only if still online
            Player live = Bukkit.getPlayer(player.getUniqueId());
            if (live != null && live.isOnline()) {
                live.teleport(location);
            }
        });
    }
}
