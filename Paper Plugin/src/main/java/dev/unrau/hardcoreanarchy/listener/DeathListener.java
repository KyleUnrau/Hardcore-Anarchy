package dev.unrau.hardcoreanarchy.listener;

import dev.unrau.hardcoreanarchy.data.PlayerData;
import dev.unrau.hardcoreanarchy.data.PlayerDataStore;
import dev.unrau.hardcoreanarchy.handler.EnderChestHandler;
import dev.unrau.hardcoreanarchy.log.SpawnLogger;
import dev.unrau.hardcoreanarchy.service.ExileSpawnService;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

public class DeathListener implements Listener {

    private final PlayerDataStore dataStore;
    private final EnderChestHandler enderChestHandler;
    private final ExileSpawnService spawnService;
    private final SpawnLogger spawnLogger;

    public DeathListener(PlayerDataStore dataStore, EnderChestHandler enderChestHandler,
                         ExileSpawnService spawnService, SpawnLogger spawnLogger) {
        this.dataStore = dataStore;
        this.enderChestHandler = enderChestHandler;
        this.spawnService = spawnService;
        this.spawnLogger = spawnLogger;
    }

    // HIGH priority so Ender chest drops appear alongside vanilla death drops
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Location deathLoc = player.getLocation();

        // Drop and clear Ender chest before vanilla drops are finalised
        enderChestHandler.dropAndClear(player, deathLoc);

        // Update player state
        PlayerData data = dataStore.load(player.getUniqueId());
        data.setLastDeath(deathLoc.getWorld().getName(), deathLoc.getX(), deathLoc.getY(), deathLoc.getZ());
        data.incrementDeathCount();
        data.setCalculatingRespawn(true);
        data.clearPendingRespawn();
        data.setNeedsDelayedTeleport(false);
        dataStore.save(player.getUniqueId(), data);

        spawnLogger.log(SpawnLogger.Reason.DEATH, player.getUniqueId(), player.getName(),
            deathLoc.getWorld().getName(), deathLoc.getX(), deathLoc.getY(), deathLoc.getZ());

        // Start async exile location search; result stored in PlayerData when ready
        spawnService.beginExileCalculation(player, deathLoc);
    }
}
