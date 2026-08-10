package dev.unrau.samsara.command;

import dev.unrau.samsara.config.DimensionalTravelConfig;
import dev.unrau.samsara.config.PluginConfig;
import dev.unrau.samsara.config.SocialConfig;
import dev.unrau.samsara.data.EndExpedition;
import dev.unrau.samsara.path.PathIndex;
import dev.unrau.samsara.path.PathService;
import dev.unrau.samsara.path.PlayerPath;
import dev.unrau.samsara.service.Coord;
import dev.unrau.samsara.service.DimensionalMapping;
import dev.unrau.samsara.service.DimensionalTravelService;
import dev.unrau.samsara.service.EndGatewayNetwork;
import dev.unrau.samsara.service.ExileSpawnService;
import dev.unrau.samsara.service.GatewayGrid;
import dev.unrau.samsara.service.WormholePairing;
import dev.unrau.samsara.social.SocialData;
import dev.unrau.samsara.social.SocialService;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SamsaraCommand implements CommandExecutor {

    private static final String USAGE =
        "Usage: /samsara <reload|version|debugspawn|expedition|map|wormholes|endrecover|social|paths>";

    private final PluginConfig config;
    private final ExileSpawnService spawnService;
    private final DimensionalTravelService travelService;
    private final EndGatewayNetwork gatewayNetwork;
    private final SocialService social;
    private final PathService paths;
    private final String version;

    public SamsaraCommand(PluginConfig config, ExileSpawnService spawnService,
                      DimensionalTravelService travelService, EndGatewayNetwork gatewayNetwork,
                      SocialService social, PathService paths, String version) {
        this.config = config;
        this.spawnService = spawnService;
        this.travelService = travelService;
        this.gatewayNetwork = gatewayNetwork;
        this.social = social;
        this.paths = paths;
        this.version = version;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("samsara.admin")) {
            sender.sendMessage("You don't have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(USAGE);
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "version" -> {
                sender.sendMessage("Samsara v" + version);
                yield true;
            }
            case "reload" -> {
                config.reload();
                // The social sampler runs on an interval read from the file; everything else in the
                // social layer asks the configuration again per event and needs no telling.
                social.reload();
                sender.sendMessage("Samsara config reloaded.");
                yield true;
            }
            case "social" -> {
                handleSocial(sender, args);
                yield true;
            }
            case "paths" -> {
                handlePaths(sender, args);
                yield true;
            }
            case "debugspawn" -> {
                handleDebugSpawn(sender);
                yield true;
            }
            case "expedition" -> {
                handleExpedition(sender, args);
                yield true;
            }
            // 'endregion' was this command's name when the End was the only destination.
            case "map", "endregion" -> {
                handleMap(sender, args);
                yield true;
            }
            // 'gateways' was this command's name when the grid led home rather than sideways.
            case "wormholes", "gateways" -> {
                handleGateways(sender, args);
                yield true;
            }
            case "endrecover" -> {
                handleEndRecover(sender, args);
                yield true;
            }
            default -> {
                sender.sendMessage(USAGE);
                yield true;
            }
        };
    }

    /**
     * Reports what the social layer believes about a player: who they are connected to, who they
     * have switched off, and how close anybody is to becoming a contact by proximity.
     *
     * <p>The last of those is the only part of the system whose state is otherwise invisible — a
     * player asking "why did that happen" or "why has it not" is asking about a number nothing else
     * prints.
     */
    private void handleSocial(CommandSender sender, String[] args) {
        SocialConfig settings = config.getSocial();
        if (!settings.isEnabled()) {
            sender.sendMessage("The social layer is disabled; chat and announcements are vanilla.");
            return;
        }

        Player target = resolveTarget(sender, args, 1);
        if (target == null) return;

        SocialData data = social.store().load(target.getUniqueId());
        boolean auto = data.autoContactsEnabled(settings.isAutoContactsDefaultOn());

        sender.sendMessage(target.getName() + "'s social record:");
        sender.sendMessage("  radius:   " + settings.getChatRadius() + " blocks (chat)");
        sender.sendMessage("  contacts: " + data.getContacts().size() + " of "
            + settings.getMaxContacts() + " — " + String.join(", ", data.getContacts().values()));
        sender.sendMessage("  ignoring: " + data.getIgnored().size() + " — "
            + String.join(", ", data.getIgnored().values()));
        sender.sendMessage("  auto:     " + (auto ? "on" : "off")
            + (settings.isAutoContactsEnabled() ? "" : " (disabled server-wide)")
            + ", " + data.getAutoSuppressed().size() + " pairing(s) deliberately severed");

        // The stored figures are as of the last time each pair were together; what an operator is
        // being asked about is the score now, so the time apart comes off before any of this is
        // printed — otherwise a pair who drifted off months ago would read as nearly there.
        long now = System.currentTimeMillis();
        SocialData.Fade fade = social.fade();

        List<Map.Entry<UUID, SocialData.Proximity>> progress = data.proximitySnapshot();
        if (progress.isEmpty()) {
            sender.sendMessage("  nearness: nothing accumulating");
            return;
        }
        progress.sort((a, b) -> Double.compare(scoreNow(b.getValue(), now, fade),
            scoreNow(a.getValue(), now, fade)));

        for (var entry : progress.subList(0, Math.min(5, progress.size()))) {
            sender.sendMessage(String.format("  nearness: %s  %.0f of %d minutes  (last together %s ago)",
                social.nameOf(target, entry.getKey()),
                scoreNow(entry.getValue(), now, fade) / 60.0,
                settings.getAutoRequiredSeconds() / 60,
                describeGap(now - entry.getValue().lastSampleMillis())));
        }
    }

    /** A pair's score with the time since they were last together already taken off. */
    private static double scoreNow(SocialData.Proximity entry, long now, SocialData.Fade fade) {
        return fade.after(entry.seconds(), Math.max(0, now - entry.lastSampleMillis()));
    }

    private static String describeGap(long millis) {
        long minutes = Math.max(0, millis) / 60_000L;
        if (minutes < 60) return minutes + "m";
        return (minutes / 60) + "h" + (minutes % 60) + "m";
    }

    /**
     * Reports which existences a player holds and which of them is in front of the server.
     *
     * <p>Read-only, and deliberately. Nothing here can switch, rename or destroy a path on somebody
     * else's behalf: a path is an existence with belongings in it, moving one means moving the
     * player who is living it, and there is no operator question that is answered by doing that
     * remotely. What an operator does need is to be able to see the list — which is what a player
     * describing a problem is describing.
     */
    private void handlePaths(CommandSender sender, String[] args) {
        if (!paths.settings().isEnabled()) {
            sender.sendMessage("Paths are disabled; every player has the one existence they are in.");
            return;
        }

        Player target = resolveTarget(sender, args, 1);
        if (target == null) return;

        PathIndex index = paths.indexOf(target.getUniqueId());
        PlayerPath active = index.active();

        sender.sendMessage(target.getName() + "'s paths — " + index.size() + " of "
            + paths.settings().getMaxPaths() + ":");
        for (PlayerPath path : index.paths()) {
            sender.sendMessage("  " + (path == active ? "> " : "  ") + path.name()
                + (path == active ? "  (active)" : "  (dormant)")
                + "  " + path.id());
        }
        if (paths.isMoving(target.getUniqueId())) {
            sender.sendMessage("  note: they are midway through a move right now.");
        }
    }

    /** Shows a player's open journey record — where they set out from and where they landed. */
    private void handleExpedition(CommandSender sender, String[] args) {
        Player target = resolveTarget(sender, args, 1);
        if (target == null) return;

        EndExpedition expedition = travelService.expeditionOf(target.getUniqueId());
        if (expedition == null) {
            sender.sendMessage(target.getName() + " has no open End journey.");
            return;
        }

        sender.sendMessage(target.getName() + "'s End journey:");
        sender.sendMessage("  site:    " + expedition.getRegionKey());
        sender.sendMessage(String.format("  origin:  %s %d/%d/%d", expedition.getOriginWorld(),
            (long) expedition.getOriginX(), (long) expedition.getOriginY(), (long) expedition.getOriginZ()));
        sender.sendMessage(String.format("  arrival: %s %d/%d/%d", expedition.getEndWorld(),
            (long) expedition.getEndX(), (long) expedition.getEndY(), (long) expedition.getEndZ()));
        sender.sendMessage("  note:    routing is by coordinate, so this record is history, not a binding.");
    }

    /**
     * Reports every route in and out of a coordinate without travelling. The single most useful
     * thing to be able to answer on a server where destinations are millions of blocks apart.
     */
    private void handleMap(CommandSender sender, String[] args) {
        Coord at = resolveCoord(sender, args);
        if (at == null) return;

        DimensionalMapping mapping = travelService.mapping();
        DimensionalTravelConfig settings = travelService.settings();

        Coord end = mapping.overworldToEnd(at.x(), at.z());

        sender.sendMessage("Routes for " + at.key() + ":");
        sender.sendMessage("  read as Overworld -> End:       " + end.key());
        sender.sendMessage("  read as End       -> Overworld: " + mapping.endToOverworld(at.x(), at.z()).key());
        sender.sendMessage("  the way back from " + end.key() + ": "
            + mapping.endToOverworld(end.x(), end.z()).key());
        sender.sendMessage("  arrival grid: " + settings.getArrivalSiteSpacing() + " blocks");
        sender.sendMessage("  note: Overworld <-> Nether is vanilla and is not routed by this plugin.");
    }

    /**
     * Reports the wormhole grid around a coordinate and where each node comes out — the answer to
     * "where does this gateway go", without anybody having to step into it to find out.
     */
    private void handleGateways(CommandSender sender, String[] args) {
        DimensionalTravelConfig settings = travelService.settings();
        if (!settings.isWormholesEnabled()) {
            sender.sendMessage("Wormholes are disabled; End gateways behave as in vanilla.");
            return;
        }

        Coord at = resolveCoord(sender, args);
        if (at == null) return;

        WormholePairing pairing = travelService.wormholes();
        sender.sendMessage("Wormholes: cells of " + pairing.cellSize() + " blocks, reaching "
            + pairing.reach() + " blocks from the origin (seed " + settings.getWormholeSeed() + ").");
        sender.sendMessage("  a wormhole at End " + at.key() + " comes out at End "
            + pairing.partnerOf(at.x(), at.z()).key());

        if (!settings.isGatewaysEnabled()) {
            sender.sendMessage("  The distributed grid is disabled; only the End's own gateways exist.");
            return;
        }

        GatewayGrid grid = travelService.gatewayGrid();
        sender.sendMessage("Grid: one node per " + grid.spacing() + "-block cell, scattered inside it"
            + " and at least " + grid.minimumSeparation() + " blocks from its neighbour, "
            + gatewayNetwork.materialisedCount() + " built this session.");

        List<Coord> nearby = grid.nodesWithin(at.x(), at.z(), grid.spacing() * 2);
        if (nearby.isEmpty()) {
            sender.sendMessage("  No eligible nodes near End " + at.key() + ".");
            return;
        }
        for (Coord node : nearby.subList(0, Math.min(5, nearby.size()))) {
            // Report where the gateway actually stands, not the raw grid node: every wormhole is
            // built at the centre of its pairing cell, a few blocks off the node that named it.
            Coord site = pairing.cellCentreOf(node.x(), node.z());
            sender.sendMessage(String.format("  End %s  (%d blocks away)  ->  End %s",
                site.key(),
                (long) Math.sqrt(site.distanceSquaredTo(at)),
                pairing.partnerOf(site.x(), site.z()).key()));
        }
    }

    /** Emergency recovery for a stuck traveller: send them out of the End now, or drop a record. */
    private void handleEndRecover(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("clear")) {
            Player target = resolveTarget(sender, args, 2);
            if (target == null) return;
            boolean cleared = travelService.clearExpedition(target.getUniqueId());
            sender.sendMessage(cleared
                ? "Cleared " + target.getName() + "'s End journey record."
                : target.getName() + " had no journey record to clear.");
            return;
        }

        Player target = resolveTarget(sender, args, 1);
        if (target == null) return;

        if (travelService.forceReturn(target)) {
            sender.sendMessage("Returning " + target.getName() + " from the End...");
        } else {
            sender.sendMessage("Could not return " + target.getName()
                + "; they must be in the End and End travel must be enabled. See the server log.");
        }
    }

    /** Reads an optional {@code x z} pair, defaulting to where the sender is standing. */
    private Coord resolveCoord(CommandSender sender, String[] args) {
        if (args.length >= 3) {
            try {
                return new Coord(Integer.parseInt(args[1]), Integer.parseInt(args[2]));
            } catch (NumberFormatException e) {
                sender.sendMessage("Usage: /samsara " + args[0] + " [x z]");
                return null;
            }
        }
        if (sender instanceof Player player) {
            return new Coord(player.getLocation().getBlockX(), player.getLocation().getBlockZ());
        }
        sender.sendMessage("Usage: /samsara " + args[0] + " <x> <z>");
        return null;
    }

    /** Resolves an optional player argument, defaulting to the sender. */
    private Player resolveTarget(CommandSender sender, String[] args, int index) {
        if (args.length > index) {
            Player target = Bukkit.getPlayerExact(args[index]);
            if (target == null) {
                sender.sendMessage("Player '" + args[index] + "' is not online.");
            }
            return target;
        }
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage("Specify a player name when running this from the console.");
        return null;
    }

    private void handleDebugSpawn(CommandSender sender) {
        World world = spawnService.resolveOverworld();
        if (world == null) {
            sender.sendMessage("No world is loaded; cannot search for a spawn.");
            return;
        }

        sender.sendMessage("Searching '" + world.getName() + "' for a debug exile spawn location...");
        sender.sendMessage("Spawn area: " + spawnService.areaFor(world));

        // Use a dummy UUID that won't match any real player
        UUID dummyUuid = UUID.fromString("00000000-0000-0000-0000-000000000000");

        spawnService.findFreshSpawn(world, dummyUuid, sender.getName() + "[debug]", location -> {
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
