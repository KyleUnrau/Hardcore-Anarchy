package dev.unrau.samsara.listener;

import dev.unrau.samsara.config.DimensionalTravelConfig;
import dev.unrau.samsara.service.DimensionalTravelService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

/**
 * Routes End portals lit in the Overworld, replacing the vanilla trip to the central island with a
 * trip to the reflection of the portal — and building, on arrival, the platform that carries the
 * traveller's way home.
 *
 * <p>Nether portals are vanilla in every direction, in every dimension, and are not touched by this
 * plugin at all.
 *
 * <h2>Two ways in, one route</h2>
 *
 * <p>A traveller reaches an End portal in one of two ways, and they arrive here as different events.
 *
 * <ul>
 *   <li><b>Walking in</b> is a {@link PlayerPortalEvent} with cause {@link TeleportCause#END_PORTAL}.
 *   </li>
 *   <li><b>A thrown pearl</b> is not a player event at all — the pearl is the thing in the portal, and
 *       vanilla crosses the <em>pearl</em> into the End and drags its owner along behind it. Vanilla
 *       decides where a pearl lands the vanilla way, which is the obsidian platform at End 0,0: the
 *       one hub this server is built to not have, reached by throwing something at the door instead
 *       of stepping through it. So the pearl is spent where it is and the plugin moves the thrower,
 *       which is the same journey with a step taken out of it — exactly as
 *       {@link EndGatewayListener} already does for a pearl thrown into a gateway.</li>
 * </ul>
 *
 * <p>The pearl is caught twice on purpose. {@link EntityPortalEnterEvent} is the earlier of the two
 * and the one that does the work: it fires the moment the pearl touches the portal block, before
 * vanilla has resolved a destination or built anything at End 0,0, and it names the portal block
 * itself rather than wherever the pearl had drifted to. {@link EntityPortalEvent} is the backstop for
 * a crossing that reaches the transition anyway. Both end at the same routing call, and a second
 * event for a journey already under way is swallowed by the transit claim.
 *
 * <h2>The exit portal at End 0,0</h2>
 *
 * <p>A pearl thrown into the exit portal is <b>refused</b> rather than routed. Walking into it leaves
 * the End through respawn machinery — see {@link RespawnListener} — and a pearl reaches none of that:
 * vanilla would simply cross the pearl to the Overworld's shared spawn and pull the thrower to it,
 * which is the same 0,0 hub arriving from the other direction, and it would skip the bed, the anchor
 * and the record of where this life began. Refusing costs the pearl its crossing and nothing else:
 * it is left alive to fly on and land like any other pearl. The exit portal is walked into.
 */
public class EndPortalListener implements Listener {

    /** What a pearl found inside an End portal means. */
    enum PearlRoute {
        /** Route the thrower into the End, as though they had walked in themselves. */
        ENTER,
        /** Cancel the crossing and leave the pearl to behave as an ordinary pearl. */
        REFUSE,
        /** Not a portal this plugin manages; vanilla decides. */
        IGNORE
    }

    private final DimensionalTravelService travelService;

    public EndPortalListener(DimensionalTravelService travelService) {
        this.travelService = travelService;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (event.getCause() != TeleportCause.END_PORTAL) return;
        if (!travelService.isEnabled()) return;

        Location from = event.getFrom();
        World fromWorld = from.getWorld();
        if (fromWorld == null || fromWorld.getEnvironment() != World.Environment.NORMAL) return;

        if (travelService.handleEndEntry(event.getPlayer(), from)) {
            // The plugin performs the authoritative teleport itself, so vanilla must not also move
            // the player — nor build the obsidian platform at the centre of the End.
            event.setCanCreatePortal(false);
            event.setCancelled(true);
        }
    }

    /**
     * A pearl touching an End portal block, before vanilla has decided anything at all. Cancelling
     * here stops the crossing being scheduled, so nothing is built at End 0,0 on the way.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortalEnter(EntityPortalEnterEvent event) {
        if (interceptPearl(event.getEntity(), event.getLocation())) {
            event.setCancelled(true);
        }
    }

    /**
     * The same pearl, one step later, in case the entry above was never seen. Reached only when
     * something has already claimed the crossing, so it does no work of its own beyond stopping it.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        if (interceptPearl(event.getEntity(), event.getFrom())) {
            event.setCancelled(true);
        }
    }

    /**
     * Sends the thrower through the End portal their pearl found.
     *
     * <p>Only ender pearls thrown by a player are taken over. A pearl from a dispenser, or from
     * somebody who has since left, carries nobody, and anything else that drifts into a portal is
     * vanilla's business — as are Nether portals, which is why the portal block itself is checked
     * rather than the event's portal type: what matters is the block the entity is standing in.
     *
     * @return true if the plugin has taken responsibility and vanilla must be cancelled
     */
    private boolean interceptPearl(Entity entity, Location portal) {
        if (!travelService.isEnabled()) return false;
        if (!(entity instanceof EnderPearl pearl)) return false;
        if (!(pearl.getShooter() instanceof Player thrower)) return false;

        World world = portal.getWorld();
        if (world == null || portal.getBlock().getType() != Material.END_PORTAL) return false;

        DimensionalTravelConfig settings = travelService.settings();
        PearlRoute route = routeFor(world.getName(), thrower.getWorld().getName(),
            settings.getOverworldName(), settings.getEndWorldName());

        return switch (route) {
            case ENTER -> enter(pearl, thrower, portal);
            case REFUSE -> refuse(thrower, portal);
            case IGNORE -> false;
        };
    }

    /**
     * What to do with a pearl in an End portal, from world names alone.
     *
     * <p>The thrower's own world is part of the question because a journey is only theirs to make
     * from a portal they are standing at: an owner who is somewhere else entirely is not walking into
     * this door, and moving them as though they were would teleport somebody out of a world they had
     * no reason to leave.
     */
    static PearlRoute routeFor(String portalWorld, String throwerWorld,
                               String overworldName, String endWorldName) {
        if (portalWorld.equals(endWorldName)) return PearlRoute.REFUSE;
        if (portalWorld.equals(overworldName) && portalWorld.equals(throwerWorld)) {
            return PearlRoute.ENTER;
        }
        return PearlRoute.IGNORE;
    }

    /** Takes the thrower into the End and spends the pearl. */
    private boolean enter(EnderPearl pearl, Player thrower, Location portal) {
        if (!travelService.handleEndEntry(thrower, portal)) return false;

        // The pearl has done its job. Left alive it would sail out the far side and teleport its
        // owner back to wherever it happened to land, undoing the journey a tick later.
        pearl.remove();
        return true;
    }

    /**
     * Stops a pearl carrying its owner out of the End. The pearl is left alive deliberately: denied
     * the crossing it is an ordinary pearl, and it lands like one.
     */
    private boolean refuse(Player thrower, Location portal) {
        travelService.debug(thrower.getName() + "'s pearl entered the End exit portal at "
            + portal.getBlockX() + "," + portal.getBlockY() + "," + portal.getBlockZ()
            + "; refusing the crossing. The way out of the End is walked into, not thrown at.");
        return true;
    }
}
