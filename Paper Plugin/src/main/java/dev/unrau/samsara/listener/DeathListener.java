package dev.unrau.samsara.listener;

import dev.unrau.samsara.data.EndExpedition;
import dev.unrau.samsara.data.JournalEntry;
import dev.unrau.samsara.data.PlayerData;
import dev.unrau.samsara.data.PlayerDataStore;
import dev.unrau.samsara.handler.EnderChestHandler;
import dev.unrau.samsara.log.PlayerJournal;
import dev.unrau.samsara.service.Coord;
import dev.unrau.samsara.service.DimensionalMapping;
import dev.unrau.samsara.service.DimensionalTravelService;
import dev.unrau.samsara.service.ExileSpawnService;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

public class DeathListener implements Listener {

    private final PlayerDataStore dataStore;
    private final EnderChestHandler enderChestHandler;
    private final ExileSpawnService spawnService;
    private final DimensionalTravelService travelService;
    private final PlayerJournal journal;

    public DeathListener(PlayerDataStore dataStore, EnderChestHandler enderChestHandler,
                         ExileSpawnService spawnService, DimensionalTravelService travelService,
                         PlayerJournal journal) {
        this.dataStore = dataStore;
        this.enderChestHandler = enderChestHandler;
        this.spawnService = spawnService;
        this.travelService = travelService;
        this.journal = journal;
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
        EndExpedition expedition = data.getActiveExpedition();

        // A death in any dimension is a real death. The exile is measured from where this life was
        // living, projected into Overworld terms so the distance means something.
        Location distanceAnchor = deathAnchor(deathLoc);

        if (expedition != null) {
            travelService.debug(player.getName() + " died during a journey that began at "
                + expedition.getOriginWorld() + " " + (long) expedition.getOriginX() + ","
                + (long) expedition.getOriginZ() + " (End site " + expedition.getRegionKey()
                + "). The journey dies with the life; exile rules apply.");
        }

        // Any teleport still resolving belonged to the life that just ended; release it so it cannot
        // report back onto the new one.
        travelService.clearTravelState(player.getUniqueId());

        data.setLastDeath(deathLoc.getWorld().getName(), deathLoc.getX(), deathLoc.getY(), deathLoc.getZ());
        data.incrementDeathCount();
        // Rotates the life id and closes any open journey record — the new life never inherits the
        // old life's way home.
        data.beginNewLife();
        data.setCalculatingRespawn(true);
        data.clearPendingRespawn();
        data.setNeedsDelayedTeleport(false);
        dataStore.save(player.getUniqueId(), data);

        journal.record(JournalEntry.Reason.DEATH, player.getUniqueId(), player.getName(),
            deathLoc.getWorld().getName(), deathLoc.getX(), deathLoc.getY(), deathLoc.getZ());

        // Start async exile location search; result stored in PlayerData when ready
        spawnService.beginExileCalculation(player, deathLoc, distanceAnchor);
    }

    /**
     * The point the new life must be far away from, in Overworld coordinates.
     *
     * <p>A death in the End or the Nether happens at coordinates that are not on the Overworld's
     * scale — the End is an inverted reflection of it and the Nether is eight times smaller — so
     * measuring the exile distance from the raw numbers would be meaningless. The same transforms
     * that route a living player home are used to say where in the Overworld the death happened.
     *
     * <p>This needs no record of how the player got there, which is the point: an exile is decided
     * by where a life ended, not by which portal it walked through.
     */
    private Location deathAnchor(Location deathLoc) {
        World deathWorld = deathLoc.getWorld();
        World overworld = spawnService.resolveOverworld();
        if (overworld == null || deathWorld.getEnvironment() == World.Environment.NORMAL) {
            return deathLoc;
        }

        DimensionalMapping.Realm realm = switch (deathWorld.getEnvironment()) {
            case THE_END -> DimensionalMapping.Realm.END;
            case NETHER -> DimensionalMapping.Realm.NETHER;
            default -> DimensionalMapping.Realm.OVERWORLD;
        };

        Coord projected = travelService.mapping()
            .toOverworld(realm, deathLoc.getBlockX(), deathLoc.getBlockZ());

        travelService.debug(deathWorld.getName() + " death at "
            + deathLoc.getBlockX() + "," + deathLoc.getBlockZ() + " measured as Overworld "
            + projected.key() + " for the exile search.");

        return new Location(overworld, projected.x(), overworld.getSeaLevel(), projected.z());
    }
}
