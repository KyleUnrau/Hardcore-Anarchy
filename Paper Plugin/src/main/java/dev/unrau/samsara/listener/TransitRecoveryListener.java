package dev.unrau.samsara.listener;

import dev.unrau.samsara.service.DimensionalTravelService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Sets down a traveller whose crossing was interrupted.
 *
 * <p>A journey between dimensions holds its traveller above the destination for the moment it takes
 * to work out where they land. Log out in that moment — or be dropped by a server that stops in it —
 * and they are left in the air with the hold still on them: unhurt and going nowhere, but above the
 * ceiling of a world with no journey left to finish.
 *
 * <p>The hold is made of ordinary entity flags precisely so that it survives that. This listener
 * reads them back on the next login and finishes the trip, which is why nothing anywhere clears them
 * on quit or on shutdown: the state left on the player is the record of what was happening to them.
 *
 * <p>Ordinary joins cost one comparison and touch nothing.
 */
public class TransitRecoveryListener implements Listener {

    private final DimensionalTravelService travelService;

    public TransitRecoveryListener(DimensionalTravelService travelService) {
        this.travelService = travelService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        travelService.hold().recoverOnJoin(event.getPlayer());
    }
}
