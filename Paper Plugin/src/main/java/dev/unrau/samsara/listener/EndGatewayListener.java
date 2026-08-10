package dev.unrau.samsara.listener;

import com.destroystokyo.paper.event.entity.EntityTeleportEndGatewayEvent;
import dev.unrau.samsara.service.Coord;
import dev.unrau.samsara.service.DimensionalTravelService;
import dev.unrau.samsara.service.SiteKind;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

/**
 * Decides what an End gateway does. There are only two answers.
 *
 * <ul>
 *   <li>A gateway tagged {@link SiteKind#HOME} is a way out of the End, back to the Overworld portal
 *       whose reflection built it. These exist only where a player opened an End portal, and they
 *       are the only doorway in the End a traveller can simply walk through.</li>
 *   <li><b>Everything else is a wormhole</b> — the plugin's grid nodes, the gateways the dragon
 *       leaves behind, the ones waiting on the outer islands, all of them. Step in and you come out
 *       somewhere else in the End; step into the gateway there and you come back.</li>
 * </ul>
 *
 * <p>The consequence worth stating plainly: a gateway found in the wild can never put anybody into
 * the Overworld. Leaving the End means finding a door somebody built, and doors are only built by
 * walking into an End portal from the other side.
 *
 * <h2>Two ways in, one route</h2>
 *
 * <p>A wormhole is a vanilla End gateway: sealed above and below, so a player gets into it the way
 * they always have — a pearl through the side, or a trapdoor to stand in. Those arrive here as two
 * different events, and both end at the same routing call.
 *
 * <ul>
 *   <li>The player themselves entering the block is a {@link PlayerTeleportEvent} with cause
 *       {@link TeleportCause#END_GATEWAY}.</li>
 *   <li>A thrown pearl is a {@link EntityTeleportEndGatewayEvent}. In vanilla the pearl goes through
 *       and drags its owner along when it lands; here the pearl is spent where it is and the plugin
 *       moves the thrower, which is the same journey with a step taken out of it.</li>
 * </ul>
 */
public class EndGatewayListener implements Listener {

    /** How far from the player's position to look for the gateway block they actually entered. */
    private static final int SEARCH_RADIUS = 1;

    private final DimensionalTravelService travelService;

    public EndGatewayListener(DimensionalTravelService travelService) {
        this.travelService = travelService;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != TeleportCause.END_GATEWAY) return;
        if (!travelService.isEnabled()) return;

        Block gateway = findGateway(event.getFrom());

        // The destination is computed from the site centre rather than the gateway block, which for
        // a way home stands on the platform rim and for a wormhole is somewhere in a pairing cell:
        // routing from the block itself would land a few blocks off. A gateway the plugin has never
        // seen records no centre and answers with its own position, which is the best guess there is.
        Coord origin = gateway != null
            ? travelService.siteBuilder().siteOriginOf(gateway)
            : new Coord(event.getFrom().getBlockX(), event.getFrom().getBlockZ());

        if (route(event.getPlayer(), gateway, origin)) {
            // The plugin performs the authoritative teleport itself, so vanilla must not also move
            // the player — nor go looking for an outer island to drop them on.
            event.setCancelled(true);
        }
    }

    /**
     * Sends a player through the gateway their pearl found.
     *
     * <p>Only ender pearls are taken over. Anything else that drifts into a gateway is left to
     * vanilla, which — every gateway in the End having been given an exact exit by now — can do no
     * more than set it down where it already was.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityTeleportEndGateway(EntityTeleportEndGatewayEvent event) {
        if (!travelService.isEnabled()) return;
        if (!(event.getEntity() instanceof EnderPearl pearl)) return;
        if (!(pearl.getShooter() instanceof Player thrower)) return;

        Block gateway = event.getGateway().getBlock();
        Coord origin = travelService.siteBuilder().siteOriginOf(gateway);

        if (route(thrower, gateway, origin)) {
            event.setCancelled(true);
            // The pearl has done its job. Left alive it would sail out the far side and teleport its
            // owner back to wherever it happened to land, undoing the journey a tick later.
            pearl.remove();
        }
    }

    /**
     * Sends a traveller wherever this gateway leads.
     *
     * @return true if the plugin has taken responsibility and vanilla must be cancelled
     */
    private boolean route(Player player, Block gateway, Coord origin) {
        SiteKind kind = gateway != null ? travelService.siteBuilder().kindOf(gateway) : null;

        return kind == SiteKind.HOME
            ? travelService.handleEndExit(player, DimensionalTravelService.ExitSource.GATEWAY, origin)
            : travelService.handleWormhole(player, origin);
    }

    /**
     * Locates the gateway block the player entered. Their position is inside it, but a small
     * neighbourhood is checked so a player clipping the edge is still recognised, and gateways the
     * plugin knows about are preferred so an unadopted one never shadows a way home.
     */
    private Block findGateway(Location from) {
        if (from.getWorld() == null) return null;

        Block at = from.getBlock();
        if (travelService.siteBuilder().isOurs(at)) return at;

        Block untagged = at.getType() == Material.END_GATEWAY ? at : null;

        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dy = -SEARCH_RADIUS; dy <= SEARCH_RADIUS; dy++) {
                for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                    Block candidate = at.getRelative(dx, dy, dz);
                    if (travelService.siteBuilder().isOurs(candidate)) return candidate;
                    if (untagged == null && candidate.getType() == Material.END_GATEWAY) {
                        untagged = candidate;
                    }
                }
            }
        }
        return untagged;
    }
}
