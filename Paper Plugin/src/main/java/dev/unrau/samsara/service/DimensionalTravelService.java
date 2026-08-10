package dev.unrau.samsara.service;

import dev.unrau.samsara.config.DimensionalTravelConfig;
import dev.unrau.samsara.config.PluginConfig;
import dev.unrau.samsara.data.EndExpedition;
import dev.unrau.samsara.data.PlayerData;
import dev.unrau.samsara.data.PlayerDataStore;
import dev.unrau.samsara.data.JournalEntry;
import dev.unrau.samsara.log.PlayerJournal;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.block.EndGateway;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * End travel: into the End, around the End, and back out of it.
 *
 * <p>Travel preserves the current life. Nothing in this class consults a bed, a respawn anchor or
 * the world spawn, and nothing here ever begins an exile — the two systems are deliberately kept
 * apart, and the only thing that crosses between them is a death.
 *
 * <p>Three routes, and the difference between them is the design:
 *
 * <ul>
 *   <li><b>Overworld to the End</b> reflects the portal through {@code (x, z) -> (-z, -x)} and
 *       builds a platform with a way home. Because reflection is its own inverse, that way home
 *       leads back to the portal without anything being written down.</li>
 *   <li><b>Wormholes</b> throw a traveller between two End cells paired by {@link WormholePairing}.
 *       Both ends are in the End. A wormhole never reaches the Overworld, so no gateway found in the
 *       wild can drop somebody into an untouched Overworld region.</li>
 *   <li><b>Home gateways</b> are the only way out, and they exist only where a player opened an End
 *       portal. Reaching one may mean several wormhole jumps and a flight; that is the journey.</li>
 * </ul>
 *
 * <p>Nether travel is not here at all. Overworld to Nether and back is vanilla in both directions.
 *
 * <p>Routing is a pure function of coordinates, so there is no destination to persist and nothing to
 * rebuild after a restart. What state does exist is transient: a claim held for the few ticks a
 * journey takes, so a gateway firing every tick cannot stack up parallel teleports.
 *
 * <p>Every route follows the same shape: validate synchronously inside the event and take
 * responsibility (so the listener can cancel vanilla), then load chunks asynchronously, do the world
 * work on the main thread, and teleport. Nothing blocks the server on chunk generation.
 *
 * <p>The two routes that change dimension cross first and resolve afterwards — see
 * {@link TransitHold}. The traveller is on the far side within a tick, behind the client's own
 * loading screen, and the chunk loading and site building happen while they are already there rather
 * than while they stand in the world they are leaving. Wormholes do not do this: both ends are in
 * the End, there is no dimension to change, and the traveller is standing on a platform in the
 * meantime rather than falling through a portal block into a stronghold's lava.
 */
public class DimensionalTravelService {

    /** Where a trip out of the End was initiated from, for logging and messaging. */
    public enum ExitSource {
        /** One of the plugin's home gateways, on a platform built by an Overworld End portal. */
        GATEWAY,
        /** {@code /samsara endrecover}, on behalf of a stuck traveller. */
        ADMIN
    }

    /** How faithfully a destination could be honoured, in descending order of fidelity. */
    public enum Outcome {
        /** Beside the Overworld portal itself — the answer a traveller is actually promised. */
        PORTAL,
        /** The mapped coordinate itself was safe to stand on. */
        EXACT,
        /** A safe spot was found near it. */
        NEARBY,
        /** Nothing was safe nearby, so the mapped column was used as it is. Never world spawn. */
        FORCED;

        /** True if the traveller was set down where they were promised, at the portal or on it. */
        boolean isFaithful() {
            return this == PORTAL || this == EXACT;
        }
    }

    /** How long an in-flight journey may claim a player before the claim is treated as abandoned. */
    private static final long TRANSIT_TIMEOUT_MILLIS = 15_000L;

    private final JavaPlugin plugin;
    private final PluginConfig config;
    private final PlayerDataStore dataStore;
    private final SafeLocationFinder finder;
    private final PlayerJournal journal;
    private final EndSiteBuilder siteBuilder;
    private final TransitHold hold;

    /** Players with travel in flight, so duplicate gateway ticks and re-entry cannot stack up. */
    private final TransitRegistry transit = new TransitRegistry(TRANSIT_TIMEOUT_MILLIS);

    public DimensionalTravelService(JavaPlugin plugin, PluginConfig config, PlayerDataStore dataStore,
                                    SafeLocationFinder finder, PlayerJournal journal,
                                    EndSiteBuilder siteBuilder, TransitHold hold) {
        this.plugin = plugin;
        this.config = config;
        this.dataStore = dataStore;
        this.finder = finder;
        this.journal = journal;
        this.siteBuilder = siteBuilder;
        this.hold = hold;
    }

    public boolean isEnabled() {
        return config.getDimensionalTravel().isEnabled();
    }

    public EndSiteBuilder siteBuilder() {
        return siteBuilder;
    }

    public DimensionalTravelConfig settings() {
        return config.getDimensionalTravel();
    }

    /**
     * Builds a mapping from the current config and the live world borders, so {@code /samsara reload}
     * and border changes take effect without restarting.
     */
    public DimensionalMapping mapping() {
        DimensionalTravelConfig settings = config.getDimensionalTravel();
        return new DimensionalMapping(
            borderLimit(Bukkit.getWorld(settings.getOverworldName())),
            borderLimit(Bukkit.getWorld(settings.getEndWorldName())),
            settings.getArrivalSiteSpacing());
    }

    /**
     * Builds the wormhole pairing from the current config. Cheap; rebuilt rather than cached.
     *
     * <p>The network is always sized to the End's own border, never smaller. A pairing narrower than
     * the world folds every coordinate past its edge onto one shared cell, and a folded coordinate
     * has no way back: the return gateway leads to that cell, not to the traveller. Since the
     * Overworld reflection and {@link #gatewayGrid()} both reach the border, so must this.
     */
    public WormholePairing wormholes() {
        DimensionalTravelConfig settings = config.getDimensionalTravel();
        int reach = borderLimit(Bukkit.getWorld(settings.getEndWorldName()));
        return WormholePairing.covering(settings.getWormholeCellSize(), reach,
            settings.getWormholeSeed());
    }

    /**
     * Builds the distributed gateway scatter from the current config, the End's world border and the
     * End's seed. Cheap; rebuilt rather than cached.
     *
     * <p>Seeding from the world means each world scatters its gateways differently while any one
     * world scatters them the same way forever — the layout is a function of the world, so it cannot
     * drift across restarts and nothing about it needs saving.
     *
     * <p>The one case that would disagree with itself is an unloaded End, which has no seed to read.
     * Nothing builds gateways then: {@code EndGatewayNetwork} skips a pass without the world, and the
     * only other caller is {@code /samsara wormholes}, which reports rather than builds.
     */
    public GatewayGrid gatewayGrid() {
        DimensionalTravelConfig settings = config.getDimensionalTravel();
        World endWorld = Bukkit.getWorld(settings.getEndWorldName());
        int endLimit = borderLimit(endWorld);
        int spacing = settings.getGatewaySpacing();
        return new GatewayGrid(spacing, settings.getGatewaySeparation(),
            settings.getCentralIslandProtectRadius(), Math.max(spacing * 2, endLimit),
            scatterSeed(endWorld, settings));
    }

    /**
     * The scatter's seed: the End's own, stirred with the wormhole seed so that repointing the
     * network and relaying it out remain separate decisions a server owner can make one at a time.
     */
    private static long scatterSeed(World endWorld, DimensionalTravelConfig settings) {
        long worldSeed = endWorld == null ? 0L : endWorld.getSeed();
        return worldSeed * 0x9E3779B97F4A7C15L + settings.getWormholeSeed();
    }

    // -------------------------------------------------------------------------
    // Overworld -> End
    // -------------------------------------------------------------------------

    /**
     * Takes over an Overworld End-portal entry, sending the traveller to the reflection of the portal
     * rather than to the central island, and building the platform that carries their way home.
     *
     * @return true if the plugin has accepted responsibility and the vanilla transition should be
     *         cancelled; false to let vanilla do whatever it would normally do
     */
    public boolean handleEndEntry(Player player, Location portal) {
        DimensionalTravelConfig settings = config.getDimensionalTravel();
        if (!settings.isEnabled()) return false;

        World fromWorld = portal.getWorld();
        if (fromWorld == null || !fromWorld.getName().equals(settings.getOverworldName())) {
            debug("Ignoring End portal in unmanaged world "
                + (fromWorld == null ? "null" : fromWorld.getName()) + ".");
            return false;
        }

        World endWorld = requireEndWorld(settings, player);
        if (endWorld == null) return false;

        UUID uuid = player.getUniqueId();
        if (!claimJourney(player, settings)) {
            // A second portal tick for a journey already under way — swallow it.
            return true;
        }

        // Suppress further portal ticks while the async work runs; reset once we land.
        player.setPortalCooldown(Math.max(player.getPortalCooldown(), 200));

        // Route from the portal, not from the block of it this traveller happened to stand on. The
        // opening is three blocks across, and one straddling a snap-grid boundary gives its blocks
        // two sites — four if it straddles one on each axis — so the End grew a second arrival
        // platform in the chunk next door, both of them leading back to the same stronghold. See
        // EndPortalAnchor: naming the portal is what makes the nine positions one answer.
        Coord anchor = EndPortalAnchor.centreOf(fromWorld, portal);
        Coord site = mapping().overworldToEnd(anchor.x(), anchor.z());

        // The player is standing here, so these chunks are loaded and this is safe to do inline.
        Location besidePortal = finder.findSafeStandingNear(fromWorld, portal.getBlockX(),
            portal.getBlockY(), portal.getBlockZ(), portalSearchRadius(settings));
        Location returnPoint = besidePortal != null ? besidePortal : portal.clone();

        if (besidePortal == null) {
            plugin.getLogger().warning("[Travel] Could not find a safe standing spot beside the portal at "
                + portal.getBlockX() + "," + portal.getBlockY() + "," + portal.getBlockZ()
                + " for " + player.getName() + "; recording the portal position as the return point.");
        }

        if (settings.isImmediateTransition()) {
            // Now, in this tick, before a single asynchronous thing has been started. The portal event
            // is about to be cancelled, and a cancelled portal stops the transition but not the
            // physics: an End portal block is not solid and a stronghold portal room has lava under
            // it, so every tick a traveller is left to themselves is a tick they spend falling through
            // the door they were standing in.
            hold.suspend(player, returnPoint);
        } else if (besidePortal != null) {
            // The older order leaves them in the Overworld for the whole journey. Standing them on the
            // ledge is the best that can be done about the same fall.
            parkBesidePortal(player, besidePortal);
        }

        long startedAt = System.nanoTime();
        boolean offCentre = anchor.x() != portal.getBlockX() || anchor.z() != portal.getBlockZ();
        debug(player.getName() + " entering the End from "
            + fromWorld.getName() + " " + portal.getBlockX() + "," + portal.getBlockY() + ","
            + portal.getBlockZ() + (offCentre ? " (portal centre " + anchor.key() + ")" : "")
            + " -> End " + site.key());

        // Both at once, and deliberately: the crossing needs one chunk and the arrival needs the
        // whole footprint, so waiting for the second before starting the first would spend the
        // journey in the Overworld — which is the wait this is here to get rid of.
        CompletableFuture<Void> loading =
            ChunkArea.load(endWorld, site.x(), site.z(), EndSiteBuilder.footprintRadius(settings));

        crossInto(player, endWorld, site, returnPoint, settings)
            .thenCombine(loading, (crossed, ignored) -> crossed)
            .thenAccept(crossed -> Bukkit.getScheduler().runTask(plugin, () ->
                completeEndEntry(player, portal, returnPoint, endWorld, site, settings, startedAt)))
            .exceptionally(error -> {
                plugin.getLogger().log(Level.WARNING, "[Travel] Failed to load End chunks for site "
                    + site.key() + "; " + player.getName() + " goes no further.", error);
                Bukkit.getScheduler().runTask(plugin, () -> abortEntry(player, returnPoint));
                return null;
            });

        return true;
    }

    /**
     * Puts the traveller on the far side before the destination is resolved, so the dimension change
     * — and with it the client's own loading screen — happens in the first tick of the journey rather
     * than the last.
     *
     * <p>Scheduled for the next tick rather than done inline, for the same reason
     * {@link #parkBesidePortal} is: this runs inside a portal event, and teleporting a player out
     * from under the event that is still being cancelled around them is asking for trouble.
     *
     * @return true once the traveller is across. False — never an exception — if they could not be,
     *         in which case they are still where they started and the journey simply finishes the
     *         old way, with one teleport at the end.
     */
    private CompletableFuture<Boolean> crossInto(Player player, World world, Coord site,
                                                 Location fallback, DimensionalTravelConfig settings) {
        if (!settings.isImmediateTransition()) {
            return CompletableFuture.completedFuture(false);
        }

        CompletableFuture<Boolean> crossed = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                crossed.complete(false);
                return;
            }
            detachPassengers(player);
            hold.begin(player, world, site.x(), site.z(), fallback).handle((success, error) -> {
                if (error != null) {
                    plugin.getLogger().log(Level.WARNING, "[Travel] Could not cross " + player.getName()
                        + " into " + world.getName() + " ahead of their arrival; falling back to a"
                        + " single teleport at the end of the journey.", error);
                }
                crossed.complete(error == null && Boolean.TRUE.equals(success));
                return null;
            });
        });
        return crossed;
    }

    private void completeEndEntry(Player player, Location portal, Location returnPoint, World endWorld,
                                  Coord site, DimensionalTravelConfig settings, long startedAt) {
        UUID uuid = player.getUniqueId();
        try {
            if (!player.isOnline()) {
                // They left while the chunks loaded. No expedition was opened, so nothing is stale;
                // stepping into the portal again starts cleanly. A traveller who had already crossed
                // is left held, which is what TransitHold.recoverOnJoin reads on their next login.
                debug(player.getName() + " went offline before reaching the End; entry abandoned.");
                endTransit(uuid);
                return;
            }

            Location arrival = resolveEndArrival(endWorld, site, settings, SiteKind.HOME);
            if (arrival == null) {
                plugin.getLogger().warning("[Travel] No landable arrival site at End " + site.key()
                    + "; " + player.getName() + " is returned to their portal.");
                abortEntry(player, returnPoint);
                return;
            }

            PlayerData data = dataStore.load(uuid);
            UUID lifeId = data.ensureLifeId();
            data.openEndExpedition(EndExpedition.open(lifeId, portal, returnPoint, arrival, site.key()));
            dataStore.save(uuid, data);

            arrival.setYaw(player.getLocation().getYaw());
            arrival.setPitch(player.getLocation().getPitch());
            detachPassengers(player);

            hold.deliver(player, arrival, TeleportCause.PLUGIN).whenComplete((success, error) -> {
                if (error == null && Boolean.TRUE.equals(success)) {
                    onEntered(uuid, player.getName(), arrival, site, settings, startedAt);
                } else {
                    plugin.getLogger().warning("[Travel] Teleport into the End was refused for "
                        + player.getName() + "; closing the journey record again.");
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        closeExpeditionQuietly(uuid, lifeId);
                        abortEntry(player, returnPoint);
                    });
                }
            });
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "[Travel] End entry failed for " + player.getName(), e);
            abortEntry(player, returnPoint);
        }
    }

    /**
     * Gives up on an entry, wherever it got to.
     *
     * <p>A traveller who never crossed is simply released and left in the Overworld. One who crossed
     * already is brought back to the ledge beside their own portal — the same place the return point
     * would have taken them, and the reason it is worked out before the journey rather than after it.
     */
    private void abortEntry(Player player, Location returnPoint) {
        if (player.isOnline() && hold.isHolding(player.getUniqueId())) {
            // deliver() gives them themselves back when they land, so the claim is all that is
            // released here — restoring twice would hand back their state while they were still in
            // the air above a world, which is a fall this method exists to avoid.
            hold.deliver(player, returnPoint, TeleportCause.PLUGIN);
            releaseClaim(player);
            return;
        }
        abortTravel(player);
    }

    /**
     * Finds somewhere to stand at an End site, building the platform unless that would mean building
     * on the dragon island. The island already has ground, so it is landed on rather than paved over
     * — the dragon fight keeps its vanilla shape.
     */
    private Location resolveEndArrival(World endWorld, Coord site, DimensionalTravelConfig settings,
                                       SiteKind kind) {
        boolean nearCentre = site.distanceFromOrigin() < settings.getCentralIslandProtectRadius();
        boolean build = settings.isBuildArrivalSites() && !nearCentre;

        Location arrival = siteBuilder.ensureSite(endWorld, site, settings, build, kind);
        if (arrival != null) return arrival;

        // Construction was declined or refused. Fall back to the natural surface here.
        return highestLanding(endWorld, site.x(), site.z());
    }

    private void onEntered(UUID uuid, String playerName, Location arrival, Coord site,
                           DimensionalTravelConfig settings, long startedAt) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            endTransit(uuid);
            Player live = Bukkit.getPlayer(uuid);
            if (live != null && live.isOnline()) {
                live.setPortalCooldown(settings.getPortalCooldownTicks());
            }
            journal.record(JournalEntry.Reason.END_DEPART, uuid, playerName,
                arrival.getWorld().getName(), arrival.getX(), arrival.getY(), arrival.getZ());
            debug(playerName + " arrived in the End at " + site.key()
                + " (y " + (long) arrival.getY() + ") in " + elapsedMillis(startedAt) + "ms.");
        });
    }

    /**
     * Stands the traveller safely beside the portal for the moment the journey takes, cancelling
     * their fall so the wait cannot hurt them. Scheduled for the next tick rather than done inline,
     * because teleporting from inside a teleport event is asking for trouble.
     */
    private void parkBesidePortal(Player player, Location besidePortal) {
        Location parked = besidePortal.clone();
        parked.setYaw(player.getLocation().getYaw());
        parked.setPitch(player.getLocation().getPitch());

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            detachPassengers(player);
            player.setFallDistance(0.0f);
            player.setVelocity(new Vector(0, 0, 0));
            player.teleport(parked, TeleportCause.PLUGIN);
        });
    }

    // -------------------------------------------------------------------------
    // End -> End, through a wormhole
    // -------------------------------------------------------------------------

    /**
     * Takes over an End gateway that is not a way home, throwing the traveller to its partner cell.
     *
     * <p>Both ends of the jump are in the End, always. This is what the End's own gateways do here
     * too: a gateway found in the wild is a road to somewhere else in the End, never a shortcut into
     * the Overworld.
     *
     * <p>A gateway is built at the far end, which is what makes the return trip possible — and
     * because {@link WormholePairing} is its own inverse, that gateway leads straight back to the
     * cell this journey started in, with nothing recorded anywhere.
     *
     * <p>Every destination is a cell <em>centre</em>, and every wormhole is built at one, so the
     * return trip lands on the gateway it set out from instead of a few blocks beside it. A wormhole
     * standing anywhere else in its cell can be left from but never arrived at, and going back and
     * forth through one would leave a second gateway next to the first.
     *
     * @param origin the End position the pairing is computed from — the centre of the site for one of
     *               ours, or the gateway block's own position for one of the End's
     * @return true if the plugin has accepted responsibility and the vanilla teleport should be
     *         cancelled; false to let vanilla handle it
     */
    public boolean handleWormhole(Player player, Coord origin) {
        DimensionalTravelConfig settings = config.getDimensionalTravel();
        if (!settings.isEnabled() || !settings.isWormholesEnabled()) return false;

        World endWorld = player.getWorld();
        if (endWorld.getEnvironment() != World.Environment.THE_END) {
            debug("Ignoring a gateway outside the End for " + player.getName() + ".");
            return false;
        }

        UUID uuid = player.getUniqueId();
        if (!claimJourney(player, settings)) {
            return true;
        }
        player.setPortalCooldown(Math.max(player.getPortalCooldown(), 200));

        // Both ends are in the End, so there is no dimension to change and no loading screen to earn
        // — but the far end may still be an unbuilt cell in the void, and a traveller left to their
        // own devices for that long walks out of the gateway they were standing in and wonders why
        // nothing happened. Hold them still; the gateway will not fire twice while they wait.
        Location leftFrom = player.getLocation().clone();
        if (settings.isImmediateTransition()) {
            hold.suspend(player, leftFrom);
        }

        WormholePairing pairing = wormholes();
        Coord target = pairing.partnerOf(origin.x(), origin.z());
        long startedAt = System.nanoTime();

        debug(player.getName() + " entering a wormhole at End "
            + origin.key() + " -> End " + target.key() + " ("
            + (long) Math.sqrt(origin.distanceSquaredTo(target)) + " blocks).");

        ChunkArea.load(endWorld, target.x(), target.z(), EndSiteBuilder.footprintRadius(settings))
            .thenRun(() -> Bukkit.getScheduler().runTask(plugin,
                () -> completeWormhole(player, endWorld, origin, target, settings, startedAt, leftFrom)))
            .exceptionally(error -> {
                plugin.getLogger().log(Level.WARNING, "[Travel] Failed to load End chunks for wormhole "
                    + "exit " + target.key() + "; " + player.getName() + " stays where they are.", error);
                Bukkit.getScheduler().runTask(plugin, () -> abortExit(player, leftFrom));
                return null;
            });

        return true;
    }

    private void completeWormhole(Player player, World endWorld, Coord origin, Coord target,
                                  DimensionalTravelConfig settings, long startedAt, Location leftFrom) {
        UUID uuid = player.getUniqueId();
        try {
            if (!player.isOnline()) {
                debug(player.getName() + " went offline mid-jump; the wormhole is still there for next time.");
                endTransit(uuid);
                return;
            }

            Location arrival = resolveEndArrival(endWorld, target, settings, SiteKind.WORMHOLE);
            if (arrival == null) {
                plugin.getLogger().warning("[Travel] Nowhere to land at wormhole exit " + target.key()
                    + "; " + player.getName() + " stays at " + origin.key() + ".");
                abortExit(player, leftFrom);
                return;
            }

            arrival.setYaw(player.getLocation().getYaw());
            arrival.setPitch(player.getLocation().getPitch());
            detachPassengers(player);

            hold.deliver(player, arrival, TeleportCause.PLUGIN).whenComplete((success, error) -> {
                if (error == null && Boolean.TRUE.equals(success)) {
                    onJumped(uuid, player.getName(), arrival, target, settings, startedAt);
                } else {
                    plugin.getLogger().warning("[Travel] Wormhole teleport was refused for "
                        + player.getName() + "; the gateway can simply be used again.");
                    Bukkit.getScheduler().runTask(plugin, () -> abortExit(player, leftFrom));
                }
            });
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "[Travel] Wormhole failed for " + player.getName(), e);
            abortExit(player, leftFrom);
        }
    }

    private void onJumped(UUID uuid, String playerName, Location arrival, Coord target,
                          DimensionalTravelConfig settings, long startedAt) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            endTransit(uuid);
            Player live = Bukkit.getPlayer(uuid);
            if (live != null && live.isOnline()) {
                live.setPortalCooldown(settings.getPortalCooldownTicks());
            }
            journal.record(JournalEntry.Reason.END_WORMHOLE, uuid, playerName,
                arrival.getWorld().getName(), arrival.getX(), arrival.getY(), arrival.getZ());
            debug(playerName + " came out of a wormhole at End "
                + target.key() + " (y " + (long) arrival.getY() + ") in "
                + elapsedMillis(startedAt) + "ms.");
        });
    }

    /**
     * Takes an End gateway the world generated into the network, so that it behaves like every other
     * wormhole rather than like a vanilla one.
     *
     * <p>Two things would otherwise go wrong with a gateway nobody has adopted. It would keep
     * vanilla's destination — the outer islands, and from there the central island, which is exactly
     * the hub this server does not have. And the first entity to touch it would make the server hunt
     * for that destination and <em>generate</em> it: an outer island and a return gateway, appearing
     * before any plugin is asked what it thinks.
     *
     * <p>Adoption is idempotent and cheap, so this is safe to call for every gateway in every End
     * chunk that loads. The pairing is recorded as the gateway's cell centre, the one position in
     * the cell a wormhole can return a traveller to.
     *
     * @return true if this call adopted the gateway, false if it was already in the network
     */
    public boolean adoptNaturalGateway(EndGateway gateway) {
        DimensionalTravelConfig settings = config.getDimensionalTravel();
        if (!settings.isEnabled() || !settings.isWormholesEnabled()) return false;

        Block block = gateway.getBlock();
        Coord cell = wormholes().cellCentreOf(block.getX(), block.getZ());
        if (!siteBuilder.adopt(gateway, cell)) return false;

        debug("Adopted the End's own gateway at " + block.getX() + ","
            + block.getY() + "," + block.getZ() + " as a wormhole from cell " + cell.key()
            + "; it leads to End " + wormholes().partnerOf(cell.x(), cell.z()).key() + ".");
        return true;
    }

    // -------------------------------------------------------------------------
    // End -> Overworld
    // -------------------------------------------------------------------------

    /**
     * Takes over a trip out of the End through one of the plugin's home gateways, reflecting the
     * gateway's site back into the Overworld. No bed, no anchor, no world spawn, no exile.
     *
     * @param origin the End position the destination is computed from — the centre of the gateway's
     *               site, or the player's own position for an administrative recovery
     * @return true if the plugin has accepted responsibility and the vanilla transition should be
     *         cancelled; false to let vanilla handle it
     */
    public boolean handleEndExit(Player player, ExitSource source, Coord origin) {
        DimensionalTravelConfig settings = config.getDimensionalTravel();
        if (!settings.isEnabled()) return false;

        World overworld = Bukkit.getWorld(settings.getOverworldName());
        if (overworld == null) {
            plugin.getLogger().severe("[Travel] Overworld '" + settings.getOverworldName()
                + "' is not loaded; " + player.getName() + " cannot leave the End.");
            return false;
        }

        UUID uuid = player.getUniqueId();
        if (!claimJourney(player, settings)) {
            return true;
        }
        player.setPortalCooldown(Math.max(player.getPortalCooldown(), 200));

        Coord target = mapping().endToOverworld(origin.x(), origin.z());
        EndExpedition homeward = homewardRecordFor(uuid, source, origin);
        long startedAt = System.nanoTime();

        // Where to put them back if the return cannot be completed. Taken before they move, because
        // once they have crossed there is nothing left in the End to read it from.
        Location leftFrom = player.getLocation().clone();

        // In this tick, before anything asynchronous. A gateway fires once and then will not fire
        // again for five seconds, so a traveller who is left standing in one while the Overworld
        // loads has every reason to think the door is broken and walk out of it. Held still, the
        // wait reads as what it is.
        if (settings.isImmediateTransition()) {
            hold.suspend(player, leftFrom);
        }

        debug(player.getName() + " leaving the End via " + source
            + " at " + origin.key() + " -> " + overworld.getName() + " " + target.key()
            + (homeward != null ? " (journey record open)" : ""));

        CompletableFuture<Void> loading = loadReturnArea(overworld, target, homeward, settings);

        crossInto(player, overworld, target, leftFrom, settings)
            .thenCombine(loading, (crossed, ignored) -> crossed)
            .thenAccept(crossed -> Bukkit.getScheduler().runTask(plugin,
                () -> completeEndExit(player, overworld, target, homeward, settings, startedAt, leftFrom)))
            .exceptionally(error -> {
                plugin.getLogger().log(Level.SEVERE, "[Travel] Failed to load Overworld chunks for "
                    + player.getName() + "'s return.", error);
                Bukkit.getScheduler().runTask(plugin, () -> abortExit(player, leftFrom));
                return null;
            });

        return true;
    }

    /**
     * Gives up on a return. A traveller who never crossed stays in the End where they were; one who
     * crossed already is set down on whatever the mapped column offers, because the alternative is
     * leaving them in the air over it.
     */
    private void abortExit(Player player, Location leftFrom) {
        if (player.isOnline() && hold.isHolding(player.getUniqueId())) {
            hold.deliver(player, leftFrom, TeleportCause.PLUGIN);
            releaseClaim(player);
            return;
        }
        abortTravel(player);
    }

    private void completeEndExit(Player player, World overworld, Coord target, EndExpedition homeward,
                                 DimensionalTravelConfig settings, long startedAt, Location leftFrom) {
        UUID uuid = player.getUniqueId();
        try {
            if (!player.isOnline()) {
                debug(player.getName() + " went offline mid-return; the gateway is still there for next time.");
                endTransit(uuid);
                return;
            }

            Resolution resolution = resolveOverworldArrival(overworld, target, homeward, settings);
            if (!resolution.outcome().isFaithful()) {
                plugin.getLogger().warning("[Travel] No standing room at the portal for "
                    + player.getName() + ", nor at " + overworld.getName() + " " + target.key()
                    + "; setting them down " + resolution.outcome() + " at "
                    + describe(resolution.location()) + " instead.");
            }

            Location destination = resolution.location();
            destination.setYaw(player.getLocation().getYaw());
            destination.setPitch(player.getLocation().getPitch());
            detachPassengers(player);

            UUID lifeId = dataStore.load(uuid).getLifeId();
            boolean displaced = !resolution.outcome().isFaithful();

            hold.deliver(player, destination, TeleportCause.PLUGIN).whenComplete((success, error) -> {
                if (error == null && Boolean.TRUE.equals(success)) {
                    onExited(uuid, player.getName(), lifeId, destination, displaced,
                        resolution.outcome(), settings, startedAt);
                } else {
                    plugin.getLogger().severe("[Travel] Teleport out of the End was refused for "
                        + player.getName() + "; the gateway can simply be used again.");
                    Bukkit.getScheduler().runTask(plugin, () -> abortExit(player, leftFrom));
                }
            });
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "[Travel] End exit failed for " + player.getName(), e);
            abortExit(player, leftFrom);
        }
    }

    private void onExited(UUID uuid, String playerName, UUID lifeId, Location destination,
                          boolean displaced, Outcome outcome, DimensionalTravelConfig settings,
                          long startedAt) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            endTransit(uuid);

            // Re-read: the player may have died in flight, in which case the exile system has
            // already rotated their life and closed this journey record. Never resurrect it.
            closeExpeditionQuietly(uuid, lifeId);

            // No cooldown on this side, deliberately, and this is the one arrival where that matters.
            //
            // A traveller comes out of the End standing *beside their own End portal*, because that
            // is what they were promised. A portal cooldown does not stop them walking back into it
            // — nothing does — it only stops the portal answering when they do. And an End portal
            // block is not solid. So the cooldown's entire effect here is that a player who turns
            // round and steps back through their own door falls through the floor of the stronghold
            // and into the lava under it, and is teleported out of it seconds later, on fire.
            //
            // Re-entry loops are already prevented by the transit claim, which is about a journey
            // rather than about a doorway, and which cannot drop anybody through anything.
            Player live = Bukkit.getPlayer(uuid);
            if (live != null && live.isOnline()) {
                live.setPortalCooldown(0);
            }

            journal.record(displaced ? JournalEntry.Reason.END_RETURN_NEARBY : JournalEntry.Reason.END_RETURN,
                uuid, playerName, destination.getWorld().getName(),
                destination.getX(), destination.getY(), destination.getZ());
            debug(playerName + " returned to " + describe(destination)
                + " (" + outcome + ") in " + elapsedMillis(startedAt) + "ms.");
        });
    }

    private record Resolution(Location location, Outcome outcome) {}

    /**
     * Finds somewhere to stand at the far end of a trip out of the End. Chunks around the mapped
     * coordinate are already loaded by the caller.
     *
     * <p>The thing a returning traveller is promised is <em>their portal</em>, and the first two
     * rungs of this ladder are both about finding it. That matters because an Overworld End portal
     * is almost always underground: the reflection names the portal's column, and asking the height
     * map about that column answers with the roof of the stronghold — the right coordinate, and
     * hundreds of blocks above the door.
     *
     * <ol>
     *   <li>The journey's own record, re-validated. Exact, and immune to whatever the terrain
     *       above the portal happens to be.</li>
     *   <li>The portal block itself, found by searching the mapped column. This is what serves a
     *       traveller using somebody else's gateway, or their own after a restart lost nothing but
     *       still has no record to consult.</li>
     *   <li>The surface at the mapped coordinate, and only then a spot near it.</li>
     * </ol>
     *
     * <p>The last two rungs are a recovery path for a portal that is genuinely no longer there, not
     * the ordinary route home. The ladder never leaves the mapped column's neighbourhood, and never
     * falls back to world spawn: a gateway that dropped travellers at spawn would be exactly the hub
     * this server does not have.
     */
    private Resolution resolveOverworldArrival(World world, Coord target, EndExpedition homeward,
                                               DimensionalTravelConfig settings) {
        Location recorded = recordedReturnPoint(world, homeward, settings);
        if (recorded != null) {
            debug("Returning to the recorded point beside the portal at " + describe(recorded) + ".");
            return new Resolution(recorded, Outcome.PORTAL);
        }

        Location besidePortal = besidePortalAt(world, target, settings);
        if (besidePortal != null) {
            debug("Returning beside the portal found at " + describe(besidePortal) + ".");
            return new Resolution(besidePortal, Outcome.PORTAL);
        }

        // No portal within reach of the mapped column. Everything below is recovery.
        Location landing = finder.findAnyLanding(world, target.x(), target.z());
        if (landing != null) {
            return new Resolution(landing, Outcome.EXACT);
        }

        for (int ring = 1; ring <= settings.getReturnSearchRadius(); ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    // Only the perimeter of this ring; inner positions were checked already.
                    if (Math.abs(dx) != ring && Math.abs(dz) != ring) continue;
                    Location nearby = finder.findAnyLanding(world, target.x() + dx, target.z() + dz);
                    if (nearby != null) {
                        return new Resolution(nearby, Outcome.NEARBY);
                    }
                }
            }
        }

        plugin.getLogger().warning("[Travel] Nothing landable within " + settings.getReturnSearchRadius()
            + " blocks of " + world.getName() + " " + target.key()
            + "; placing the traveller at the mapped coordinate as it is. This is not an exile.");
        return new Resolution(finder.forceLanding(world, target.x(), target.z()), Outcome.FORCED);
    }

    /**
     * Re-validates the standing spot recorded when this journey began, and returns it if a player
     * can still stand there.
     *
     * <p>Re-validation rather than blind trust: the record may be hours old and the stronghold may
     * have been dug out, flooded or built over since. When the exact spot no longer works, the
     * search around it is small and centred on the portal's own height, so the answer is still the
     * portal room rather than the surface far above it.
     */
    private Location recordedReturnPoint(World world, EndExpedition homeward,
                                         DimensionalTravelConfig settings) {
        if (homeward == null) return null;
        if (!world.getName().equals(homeward.getReturnWorld())) return null;

        Location recorded = homeward.returnLocation(world);
        int x = recorded.getBlockX();
        int y = recorded.getBlockY();
        int z = recorded.getBlockZ();

        // The caller loads chunks around the mapped coordinate, which the record sits within — but
        // a record written before the snapping grid changed could fall outside it, and reading an
        // unloaded chunk on the main thread is the one thing this class never does.
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            debug("The recorded return point at " + describe(recorded) + " is outside the loaded area.");
            return null;
        }

        if (finder.isSafeStanding(world, x, y, z)) {
            return new Location(world, x + 0.5, y, z + 0.5);
        }
        return finder.findSafeStandingNear(world, x, y, z, portalSearchRadius(settings));
    }

    /**
     * Finds the portal in the mapped column and returns a standing spot beside it.
     *
     * <p>{@link SafeLocationFinder#isSafeStanding} rejects portal blocks, so this can never set
     * somebody down inside the portal they just came out of and send them straight back.
     */
    private Location besidePortalAt(World world, Coord target, DimensionalTravelConfig settings) {
        Location portal = finder.findPortalBlock(world, target.x(), target.z(), portalReach(settings));
        if (portal == null) return null;

        return finder.findSafeStandingNear(world, portal.getBlockX(), portal.getBlockY(),
            portal.getBlockZ(), portalSearchRadius(settings));
    }

    /**
     * The journey record to route this exit by, or null to route by coordinates alone.
     *
     * <p>A gateway is a public door: anybody who finds one can walk through it, and it must take
     * them to the portal <em>it</em> was built by, not to whatever portal they personally have open
     * somewhere else in the world. So the record is only consulted when its End site is the one
     * being left from — which for an administrative recovery cannot be checked, and does not need
     * to be: sending a stuck traveller to their own portal is the entire point of the command.
     */
    private EndExpedition homewardRecordFor(UUID uuid, ExitSource source, Coord origin) {
        EndExpedition expedition = dataStore.load(uuid).getActiveExpedition();
        if (expedition == null) return null;
        if (source == ExitSource.ADMIN) return expedition;

        if (!origin.key().equals(expedition.getRegionKey())) {
            debug("Gateway at End " + origin.key() + " is not the one " + uuid + " arrived through ("
                + expedition.getRegionKey() + "); routing by coordinates.");
            return null;
        }
        return expedition;
    }

    /**
     * How far around the mapped coordinate to look for the portal. The reflection snaps to the
     * arrival grid on the way out and not on the way back, so the portal sits within half a cell of
     * the mapped column — plus a little slack for a grid that was changed after a site was built.
     */
    private int portalReach(DimensionalTravelConfig settings) {
        return Math.max(settings.getArrivalSiteSpacing() / 2 + 2, 4);
    }

    /** How far from a portal to look for standing room. A portal room is small; this is generous. */
    private int portalSearchRadius(DimensionalTravelConfig settings) {
        return Math.min(settings.getReturnSearchRadius(), 8);
    }

    /**
     * The area to load before resolving a return: wide enough for the portal search, the standing
     * room beside whatever it finds, and the terrain fallback beyond that.
     */
    private int returnLoadRadius(DimensionalTravelConfig settings) {
        return settings.getReturnSearchRadius() + portalReach(settings) + portalSearchRadius(settings) + 2;
    }

    /**
     * Loads everywhere the return might land.
     *
     * <p>Normally that is one area, because the recorded point sits within half a snap cell of the
     * mapped coordinate. An administrative recovery is the exception: it maps from wherever the
     * player happens to be standing in the End, which may be nowhere near their portal, so the
     * recorded point has to be loaded in its own right or it would be skipped as unloaded and the
     * traveller sent to the wrong place entirely.
     */
    private CompletableFuture<Void> loadReturnArea(World overworld, Coord target,
                                                   EndExpedition homeward,
                                                   DimensionalTravelConfig settings) {
        CompletableFuture<Void> loading =
            ChunkArea.load(overworld, target.x(), target.z(), returnLoadRadius(settings));

        if (homeward == null || !overworld.getName().equals(homeward.getReturnWorld())) {
            return loading;
        }

        int recordX = (int) Math.floor(homeward.getReturnX());
        int recordZ = (int) Math.floor(homeward.getReturnZ());
        return loading.thenCompose(ignored ->
            ChunkArea.load(overworld, recordX, recordZ, portalSearchRadius(settings) + 2));
    }

    // -------------------------------------------------------------------------
    // Admin recovery and death
    // -------------------------------------------------------------------------

    /** Forces a trip out of the End for a player, used by {@code /samsara endrecover}. */
    public boolean forceReturn(Player player) {
        World world = player.getWorld();
        if (world.getEnvironment() != World.Environment.THE_END) {
            return false;
        }
        Location at = player.getLocation();
        return handleEndExit(player, ExitSource.ADMIN, new Coord(at.getBlockX(), at.getBlockZ()));
    }

    /** Drops a player's journey record without moving them, used by {@code /samsara endrecover clear}. */
    public boolean clearExpedition(UUID uuid) {
        PlayerData data = dataStore.load(uuid);
        if (data.getEndExpedition() == null) return false;
        data.closeEndExpedition();
        dataStore.save(uuid, data);
        plugin.getLogger().info("[Travel] Journey record for " + uuid + " cleared by an administrator.");
        return true;
    }

    public EndExpedition expeditionOf(UUID uuid) {
        return dataStore.load(uuid).getActiveExpedition();
    }

    /**
     * Releases every trace of an in-flight journey.
     *
     * <p>Called on death, so that a teleport still resolving when a player died cannot report back
     * onto the new life, and so the exile system starts from a clean slate. The persisted journey
     * record is closed separately, by the life rotation in {@code PlayerData.beginNewLife()}.
     *
     * <p>A traveller who died mid-crossing gets their own gravity back here too. They are on the
     * ground now — the exile system has them — so the hold has nothing left to protect them from,
     * and leaving it set would follow them into their next life.
     */
    public void clearTravelState(UUID uuid) {
        endTransit(uuid);
        hold.restore(uuid);
    }

    /** The crossing mechanism, for the listener that recovers an interrupted one. */
    public TransitHold hold() {
        return hold;
    }

    /**
     * Whether a journey between dimensions is still resolving for this player.
     *
     * <p>Asked by anything that would move them somewhere else entirely — switching paths, for one.
     * A traveller who is halfway between two worlds is not somewhere a second teleport can be
     * measured from, and letting one start would race the first to decide where they end up.
     */
    public boolean isTravelling(UUID uuid) {
        return transit.isInFlight(uuid);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private World requireEndWorld(DimensionalTravelConfig settings, Player player) {
        World endWorld = Bukkit.getWorld(settings.getEndWorldName());
        if (endWorld == null) {
            plugin.getLogger().severe("[Travel] End world '" + settings.getEndWorldName()
                + "' is not loaded; falling back to vanilla End travel for " + player.getName() + ".");
            return null;
        }
        if (endWorld.getEnvironment() != World.Environment.THE_END) {
            plugin.getLogger().severe("[Travel] World '" + settings.getEndWorldName()
                + "' is not an End world; falling back to vanilla End travel for " + player.getName() + ".");
            return null;
        }
        return endWorld;
    }

    /**
     * Claims a player for an in-flight journey. Returns false if one is already under way, unless
     * that claim is old enough to be considered abandoned.
     */
    /**
     * Claims a player for a journey, taking over a claim that has no traveller attached to it.
     *
     * <p>A claim exists so that a door firing every tick cannot stack up parallel journeys, and while
     * one is held every further attempt is swallowed — and swallowed means <em>cancelled</em>, because
     * the alternative is letting vanilla send somebody to the middle of the End. That is the right
     * behaviour for a journey genuinely in flight, whose traveller is suspended and safe.
     *
     * <p>It is the wrong behaviour for a claim that leaked. Nobody is suspended, nobody is going
     * anywhere, and the door in front of a perfectly ordinary player is cancelled every tick until
     * the claim ages out — which, for an End portal in a stronghold, means falling through the floor
     * and into the lava under it and waiting there. So a claim whose holder is not actually in
     * transit is treated as what it is: an accident, taken over rather than obeyed.
     *
     * <p>Only meaningful while crossings suspend their travellers. With that off there is nothing to
     * check against, and a claim is simply believed.
     */
    private boolean claimJourney(Player player, DimensionalTravelConfig settings) {
        UUID uuid = player.getUniqueId();
        if (beginTransit(uuid)) return true;
        if (!settings.isImmediateTransition() || hold.isHolding(uuid)) return false;

        plugin.getLogger().warning("[Travel] " + player.getName() + " is held by a travel claim but is"
            + " not in transit; the journey that took it never reported back. Taking it over rather"
            + " than leaving them standing in a door that will not open.");
        endTransit(uuid);
        return beginTransit(uuid);
    }

    private boolean beginTransit(UUID uuid) {
        TransitRegistry.Claim claim = transit.begin(uuid);
        if (claim == TransitRegistry.Claim.BUSY) {
            return false;
        }
        if (claim == TransitRegistry.Claim.RECLAIMED_STALE) {
            plugin.getLogger().warning("[Travel] Previous journey for " + uuid
                + " never reported back; releasing it and starting again.");
        }
        return true;
    }

    private void endTransit(UUID uuid) {
        transit.end(uuid);
    }

    /** Closes the journey record only if the player is still living the life that opened it. */
    private void closeExpeditionQuietly(UUID uuid, UUID lifeId) {
        PlayerData data = dataStore.load(uuid);
        EndExpedition expedition = data.getEndExpedition();
        if (expedition == null) return;
        if (lifeId != null && !expedition.isValidFor(lifeId)) {
            debug("Leaving the journey record for " + uuid + " untouched; it belongs to a different life.");
            return;
        }
        data.closeEndExpedition();
        dataStore.save(uuid, data);
    }

    /**
     * Releases a player from in-flight state and lets vanilla portal behaviour resume.
     *
     * <p>The portal cooldown is <em>cleared</em> rather than applied. A cooldown is for somebody who
     * has just travelled and might loop; this is somebody who has not travelled at all and is very
     * likely still standing in the door. Leaving it set would make the door ignore them for five
     * seconds — and an End portal block is not solid, so being ignored by it means falling through
     * it. A journey that failed should be retryable by stepping forward again.
     */
    private void abortTravel(Player player) {
        releaseClaim(player);
        hold.restore(player.getUniqueId());
    }

    /** Ends the journey's claim on a player and lets the door in front of them answer again. */
    private void releaseClaim(Player player) {
        endTransit(player.getUniqueId());
        if (player.isOnline()) {
            player.setPortalCooldown(0);
        }
    }

    /**
     * Vanilla End portals leave vehicles behind, and an async teleport refuses to move a player who
     * is riding or being ridden, so both links are broken before travelling.
     */
    private void detachPassengers(Player player) {
        if (player.isInsideVehicle()) {
            player.leaveVehicle();
        }
        if (!player.getPassengers().isEmpty()) {
            player.eject();
        }
    }

    /** A standing position on whatever the highest solid block here is, or null in the void. */
    private Location highestLanding(World world, int x, int z) {
        int highestY = world.getHighestBlockYAt(x, z);
        if (highestY <= world.getMinHeight()) return null;
        if (!finder.isSafeStanding(world, x, highestY + 1, z)) return null;
        return new Location(world, x + 0.5, highestY + 1, z + 0.5);
    }

    /** The furthest coordinate usable in a world, keeping inside its border and vanilla's limit. */
    private int borderLimit(World world) {
        if (world == null) return DimensionalMapping.VANILLA_LIMIT;

        WorldBorder border = world.getWorldBorder();
        double half = border.getSize() / 2.0;
        double centreOffset = Math.max(Math.abs(border.getCenter().getX()), Math.abs(border.getCenter().getZ()));
        double limit = Math.max(0.0, half - centreOffset);

        int capped = (int) Math.min(limit, DimensionalMapping.VANILLA_LIMIT);
        // Guard against an absurdly small or offset border making the mapping unconstructable.
        return Math.max(1, capped);
    }

    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    private static String describe(Location location) {
        return location.getWorld().getName() + " "
            + (long) location.getX() + "," + (long) location.getY() + "," + (long) location.getZ();
    }

    /**
     * The console channel for everything routine: who went where, and how long it took.
     *
     * <p>Silent unless {@code dimensionalTravel.debug} is on. A busy End produces two of these lines
     * per gateway per player, which buries the lines that actually need reading, and none of it is
     * lost — every journey is written to the player's own journal either way. Anything that went
     * wrong is a warning instead and is never gated.
     */
    public void debug(String message) {
        if (config.getDimensionalTravel().isDebug()) {
            plugin.getLogger().info("[Travel/debug] " + message);
        }
    }
}
