package dev.unrau.samsara.service;

import dev.unrau.samsara.config.DimensionalTravelConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Materialises the distributed wormhole grid in the End.
 *
 * <p>{@link GatewayGrid} says where a gateway belongs; this builds it. Every node is a
 * {@link SiteKind#WORMHOLE} — a road to somewhere else in the End, never a way out of it — and the
 * grid exists for one reason: so that a traveller who lands anywhere in the End has a wormhole
 * within a few hundred blocks rather than a flight of millions.
 *
 * <p><b>Nodes are snapped to the centre of their pairing cell before anything is built.</b> That is
 * not a detail — it is what stops the End filling up with pairs of gateways standing eight blocks
 * apart. {@link WormholePairing} pairs <em>cells</em> and always returns a traveller to a cell
 * centre, so a gateway anywhere else in its cell can be left from but never arrived at: jump away
 * from a raw grid node, come back, and the arrival builds a second gateway at the cell centre beside
 * the first. Snapping makes every wormhole in the world a fixed point of the network, and a round
 * trip lands on the gateway it set out from rather than next to it.
 *
 * <p>Nothing is generated ahead of time — the End is unbounded and almost none of it will ever be
 * visited — so a node is created the first time a player comes within {@code materialiseRadius} of
 * it, and it is there for good.
 *
 * <p>Because the grid is a function and the build is idempotent, this needs no persistence at all.
 * After a restart the in-memory record of what has been built is empty, and the first player back in
 * the area simply re-runs a build that finds everything already in place.
 *
 * <p>The scan is deliberately unhurried: one new site per player per pass, every
 * {@code scanIntervalTicks}, with chunk loading done asynchronously. Building a gateway is not
 * urgent, and a player exploring the End at speed should not be able to queue up hundreds of them.
 */
public class EndGatewayNetwork {

    private final JavaPlugin plugin;
    private final DimensionalTravelService travelService;

    /**
     * Nodes already built, or being built, this session. Only ever grows with the part of the End
     * players actually visit, and losing it on restart costs nothing but a repeat no-op build.
     */
    private final Set<Coord> materialised = ConcurrentHashMap.newKeySet();

    private BukkitTask task;

    public EndGatewayNetwork(JavaPlugin plugin, DimensionalTravelService travelService) {
        this.plugin = plugin;
        this.travelService = travelService;
    }

    /** Starts the scan, if distributed gateways are enabled. Safe to call twice. */
    public void start() {
        stop();

        DimensionalTravelConfig settings = travelService.settings();
        if (!settings.isEnabled() || !settings.isGatewaysEnabled()) {
            travelService.debug("The distributed wormhole grid is disabled; only the End's own"
                + " gateways carry travellers around it.");
            return;
        }

        int interval = settings.getGatewayScanIntervalTicks();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::scan, interval, interval);
        travelService.debug("Distributed wormhole grid enabled: one node per "
            + settings.getGatewaySpacing() + "-block cell, scattered inside it and never closer than "
            + settings.getGatewaySeparation() + " blocks to its neighbour, built within "
            + settings.getGatewayMaterialiseRadius() + " blocks of a traveller.");
    }

    /** Stops the scan. Gateways already in the world are unaffected. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Number of grid nodes built or claimed this session, for {@code /samsara gateways}. */
    public int materialisedCount() {
        return materialised.size();
    }

    private void scan() {
        DimensionalTravelConfig settings = travelService.settings();
        if (!settings.isEnabled() || !settings.isGatewaysEnabled()) return;

        World endWorld = Bukkit.getWorld(settings.getEndWorldName());
        if (endWorld == null) return;

        GatewayGrid grid = travelService.gatewayGrid();
        int radius = settings.getGatewayMaterialiseRadius();

        for (Player player : endWorld.getPlayers()) {
            Location at = player.getLocation();
            List<Coord> nearby = grid.nodesWithin(at.getBlockX(), at.getBlockZ(), radius);

            WormholePairing pairing = travelService.wormholes();

            for (Coord node : nearby) {
                // One per player per pass: claiming here is what stops two players in the same
                // area, or two passes overlapping on a slow chunk load, from building it twice.
                if (materialised.add(node)) {
                    materialise(endWorld, pairing.cellCentreOf(node.x(), node.z()), node, settings);
                    break;
                }
            }
        }
    }

    private void materialise(World endWorld, Coord site, Coord node, DimensionalTravelConfig settings) {
        travelService.debug("Materialising wormhole node " + node.key() + " at cell centre "
            + site.key() + ".");

        ChunkArea.load(endWorld, site.x(), site.z(), EndSiteBuilder.footprintRadius(settings))
            .thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> build(endWorld, site, node, settings)))
            .exceptionally(error -> {
                plugin.getLogger().log(Level.WARNING, "[Travel] Could not load End chunks for wormhole node "
                    + node.key() + "; it will be tried again after a restart.", error);
                materialised.remove(node);
                return null;
            });
    }

    private void build(World endWorld, Coord site, Coord node, DimensionalTravelConfig settings) {
        try {
            Location standing = travelService.siteBuilder()
                .ensureSite(endWorld, site, settings, true, SiteKind.WORMHOLE);
            if (standing == null) {
                // Only possible with building disabled, which the caller has already ruled out.
                plugin.getLogger().warning("[Travel] Wormhole node " + node.key() + " could not be built.");
                materialised.remove(node);
                return;
            }
            travelService.debug("Wormhole ready at End " + site.key()
                + " (ground at y " + (long) standing.getY() + "); it leads to End "
                + travelService.wormholes().partnerOf(site.x(), site.z()).key() + ".");
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "[Travel] Failed to build wormhole node " + node.key(), e);
            materialised.remove(node);
        }
    }
}
