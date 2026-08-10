package dev.unrau.samsara.path;

import dev.unrau.samsara.config.PathConfig;
import dev.unrau.samsara.config.PluginConfig;
import dev.unrau.samsara.data.JournalEntry;
import dev.unrau.samsara.data.PlayerData;
import dev.unrau.samsara.data.PlayerDataStore;
import dev.unrau.samsara.log.PlayerJournal;
import dev.unrau.samsara.service.DimensionalTravelService;
import dev.unrau.samsara.service.ExileSpawnService;
import dev.unrau.samsara.social.SocialEvent;
import dev.unrau.samsara.social.SocialService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * A player's paths, and the act of stepping between them.
 *
 * <p>The idea this exists to serve: a person should not need a second Microsoft account to have a
 * second existence in the world. A path is that second existence — its own position, its own
 * belongings, its own history — and a player may keep a small, fixed number of them.
 *
 * <p>Three things it is careful about, because each of them would otherwise quietly break the
 * server's premise:
 *
 * <ol>
 *   <li><b>A path is not a life.</b> Dying ends the life; the path receives the next one, keeps its
 *       name, and is exiled to the far side of the world exactly as Samsara has always done. Nothing
 *       in this class runs on death, and that is deliberate — the death and respawn a player
 *       experiences is the vanilla one they already knew.</li>
 *   <li><b>Nothing crosses between paths.</b> Not an item, not an experience level, not an ender
 *       chest. Abandoning a path is the only way to destroy one and it is settled like a death: the
 *       belongings fall into the world at the place that existence was standing, where anybody can
 *       pick them up, and the slot is freed.</li>
 *   <li><b>A switch is not a teleport.</b> It saves one existence whole and restores another whole,
 *       and the order it does that in is chosen so that a server dying halfway through leaves a
 *       player with one intact path and never with two copies of anything. See
 *       {@link #recoverInterruptedSwitch}.</li>
 * </ol>
 *
 * <p>Everything here runs on the main thread except the two waits that cannot: finding ground for a
 * new beginning, and loading the chunks under an old one. Both come back to the main thread before
 * anything is written.
 */
public class PathService {

    /** What a command should say back, and whether anything happened. */
    public record Outcome(boolean ok, String message) {

        public static Outcome ok(String message) {
            return new Outcome(true, message);
        }

        public static Outcome refused(String message) {
            return new Outcome(false, message);
        }
    }

    private final JavaPlugin plugin;
    private final PluginConfig config;
    private final PathStore store;
    private final PlayerDataStore dataStore;
    private final ExileSpawnService spawnService;
    private final DimensionalTravelService travelService;
    private final PlayerJournal journal;
    private final PathMessages messages;
    private final SocialService social;

    private final SharedBeginnings beginnings = new SharedBeginnings();

    /**
     * Who is halfway through a move.
     *
     * <p>A switch spans a chunk load and a beginning spans a terrain search, and in both windows the
     * player is still standing there able to type. Without this, a second command would archive an
     * existence that the first command is midway through replacing — which is the one way this
     * design could ever duplicate an item.
     */
    private final Set<UUID> moving = ConcurrentHashMap.newKeySet();

    public PathService(JavaPlugin plugin, PluginConfig config, PathStore store,
                       PlayerDataStore dataStore, ExileSpawnService spawnService,
                       DimensionalTravelService travelService, PlayerJournal journal,
                       PathMessages messages, SocialService social) {
        this.plugin = plugin;
        this.config = config;
        this.store = store;
        this.dataStore = dataStore;
        this.spawnService = spawnService;
        this.travelService = travelService;
        this.journal = journal;
        this.messages = messages;
        this.social = social;
    }

    // -------------------------------------------------------------------------
    // What a player currently is
    // -------------------------------------------------------------------------

    public PathConfig settings() {
        return config.getPaths();
    }

    /**
     * The paths this account holds, creating the first one if it holds none.
     *
     * <p>This is the whole of the migration. Every player who existed before paths did is walking
     * one, it is called whatever {@code paths.defaultName} says, and it already contains their
     * position, their belongings and their history — because those never moved.
     */
    public PathIndex indexOf(UUID account) {
        PathIndex index = store.loadIndex(account);
        if (index == null) {
            index = PathIndex.beginningWith(PlayerPath.beginning(settings().getDefaultPathName()));
            store.saveIndex(account, index);
            return index;
        }

        // An index that names no path it holds is a broken one, and the player is standing in the
        // world as nobody. The first path is as good an answer as exists, and it is written down so
        // the same repair is not made again on every join.
        if (index.active() == null) {
            PlayerPath first = index.paths().get(0);
            plugin.getLogger().warning("[Samsara] " + account + " had no active path; they are"
                + " walking '" + first.name() + "'.");
            index.setActivePathId(first.id());
            store.saveIndex(account, index);
        }
        return index;
    }

    /** What the path this player is walking is called. Never null while they are online. */
    public String activeNameOf(UUID account) {
        PlayerPath active = indexOf(account).active();
        return active == null ? settings().getDefaultPathName() : active.name();
    }

    /** Whether this player is midway through a move and should not be asked to start another. */
    public boolean isMoving(UUID account) {
        return moving.contains(account);
    }

    public SharedBeginnings beginnings() {
        return beginnings;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Settles what this player is before anything else looks at them.
     *
     * <p>Runs first among the join handlers, because everything after it — the exile system, the
     * announcement, the help page — is about the path they turn out to be walking.
     */
    public void onJoin(Player player) {
        if (!settings().isEnabled()) return;

        PathIndex index = indexOf(player.getUniqueId());
        recoverInterruptedSwitch(player, index);
        repairMissingSnapshots(player.getUniqueId(), index);
    }

    /** Lets go of everything held in memory for a player who has gone. */
    public void onQuit(Player player) {
        UUID account = player.getUniqueId();
        moving.remove(account);

        SharedBeginnings.Party party = beginnings.cancel(account);
        if (party != null) {
            tell(party.othersThan(account),
                party.nameOf(account) + " disconnected; the shared beginning is off.");
        }
    }

    // -------------------------------------------------------------------------
    // Switching
    // -------------------------------------------------------------------------

    /**
     * Puts one existence away and takes another out.
     *
     * <p>This is the only part of the class a crash can be measured against, so it is worth reading
     * as a sequence rather than as code. Nothing at all is written until the player has arrived —
     * see {@link #completeMove} for why that ordering is what makes a switch incapable of
     * duplicating an item — and once writing starts, the outgoing existence is on disk before the
     * index moves. Between those two moments the player's <em>active</em> path has a dormant file of
     * its own, which is the signal {@link #recoverInterruptedSwitch} reads: it means the switch did
     * not finish, and the honest answer is to put the player back into the existence that file
     * describes.
     */
    public Outcome switchTo(Player player, String name) {
        Outcome refusal = refuseIfBusy(player);
        if (refusal != null) return refusal;

        UUID account = player.getUniqueId();
        PathIndex index = indexOf(account);
        PlayerPath incoming = index.byName(name);
        if (incoming == null) {
            return Outcome.refused("You have no path called '" + name + "'. /path lists them.");
        }

        PlayerPath outgoing = index.active();
        if (incoming == outgoing) {
            return Outcome.refused("You are already walking " + incoming.name() + ".");
        }

        PathSnapshot snapshot = store.loadSnapshot(account, incoming.id());
        if (snapshot == null) {
            return Outcome.refused(incoming.name() + " cannot be read from disk. Nothing has been"
                + " changed; tell an administrator before trying again.");
        }

        Location destination = snapshot.state().location();
        if (destination == null) {
            return Outcome.refused(incoming.name() + " is standing in the world '"
                + snapshot.state().worldName() + "', which this server no longer has loaded.");
        }

        move(player, index, outgoing, incoming, snapshot, destination,
            () -> player.sendMessage("You are walking " + incoming.name() + "."));
        return Outcome.ok("Leaving " + outgoing.name() + "...");
    }

    /**
     * The one path in and out of an existence, used by switching and by beginning alike.
     *
     * <p>Written once rather than twice because the dangerous part is identical: a player is moved
     * across a world that may not be generated, and on the far side of that wait one existence has
     * to be put away whole and another stood up whole, with no moment in between where either could
     * be counted twice.
     *
     * @param arriving what the player is becoming — a stored path, or a fresh one nothing has been
     *                 lived in yet
     */
    private void move(Player player, PathIndex index, PlayerPath outgoing, PlayerPath incoming,
                      PathSnapshot arriving, Location destination, Runnable onArrival) {
        UUID account = player.getUniqueId();
        moving.add(account);

        // Where the outgoing existence lives. Taken now, because in a moment the player will be
        // standing somewhere else entirely — and everything else about that existence is read after
        // the move, when nothing more can happen to it.
        Location from = player.getLocation().clone();

        announce(player, SocialEvent.QUIT, messages.departure(player, outgoing.name()));

        // The teleport completes off the main thread, and everything below it touches a player's
        // inventory, their record and the world. All of it is handed back to the main thread first.
        player.teleportAsync(destination.clone()).thenAccept(arrived ->
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    if (!Boolean.TRUE.equals(arrived)) {
                        abandonMove(player, outgoing, null, "Could not reach " + incoming.name() + ".");
                        return;
                    }
                    if (!player.isOnline() || player.isDead()) {
                        // Something happened to them in the second the ground took to load. Nothing
                        // has been written, so there is nothing to undo: they are still who they were.
                        abandonMove(player, outgoing, from,
                            "Something happened to you on the way; you are still walking "
                                + outgoing.name() + ".");
                        return;
                    }
                    if (completeMove(player, index, outgoing, incoming, arriving, from)) {
                        onArrival.run();
                        announce(player, SocialEvent.JOIN, messages.arrival(player, incoming.name()));
                    } else {
                        abandonMove(player, outgoing, from, "Your current path could not be written"
                            + " to disk, so nothing has been changed. Tell an administrator.");
                    }
                } finally {
                    moving.remove(account);
                }
            })
        ).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "[Samsara] Moving " + player.getName() + " from "
                + outgoing.name() + " to " + incoming.name() + " failed.", error);
            moving.remove(account);
            return null;
        });
    }

    /**
     * Writes both halves of a move, in the order that makes it survivable.
     *
     * <p>The outgoing existence is read <em>here</em>, after the move rather than before it, and
     * that is the whole defence against duplication. A teleport across ungenerated terrain takes
     * time, and in that time a player can drop something, be hit, eat, or die. Reading them
     * beforehand would store an existence holding an item that is by now lying on the ground — the
     * same item, in two places, one of them recoverable by walking back into that path.
     *
     * <p>Only the position comes from before, because that is the one thing the move itself changed.
     *
     * @return false if the outgoing existence could not be stored, in which case nothing was written
     */
    private boolean completeMove(Player player, PathIndex index, PlayerPath outgoing,
                                 PlayerPath incoming, PathSnapshot arriving, Location from) {
        UUID account = player.getUniqueId();

        // Any journey between dimensions belonged to the existence being put away; it must not be
        // left resolving onto the one about to arrive.
        travelService.clearTravelState(account);

        PathSnapshot departing = new PathSnapshot(dataStore.load(account),
            IncarnationState.captureAt(player, from));
        if (!store.saveSnapshot(account, outgoing.id(), departing)) {
            return false;
        }

        dataStore.save(account, arriving.record());
        arriving.state().applyTo(player);

        if (!index.paths().contains(incoming)) {
            // A path being begun joins the list at the moment it becomes real, in the same write
            // that makes it active. A crash before this leaves an account that never heard of it.
            index.add(incoming);
        }
        index.setActivePathId(incoming.id());
        store.saveIndex(account, index);

        store.deleteSnapshot(account, incoming.id());
        return true;
    }

    /**
     * Calls off a move that got as far as the teleport and no further.
     *
     * <p>Nothing has been written by this point, so there is nothing to roll back — only a player
     * standing in the wrong place, which is put right by sending them back where they came from.
     */
    private void abandonMove(Player player, PlayerPath outgoing, Location back, String why) {
        if (back != null && player.isOnline() && !player.isDead()) {
            player.teleportAsync(back);
        }
        if (player.isOnline()) {
            player.sendMessage(why);
        }
        plugin.getLogger().info("[Samsara] " + player.getName() + " stayed on " + outgoing.name()
            + ": " + why);
    }

    // -------------------------------------------------------------------------
    // Beginning
    // -------------------------------------------------------------------------

    /** Begins a new path alone, somewhere the world has never put this player before. */
    public Outcome begin(Player player, String name) {
        Outcome refusal = refuseIfCannotBegin(player, name);
        if (refusal != null) return refusal;

        beginTogether(List.of(player), Map.of(player.getUniqueId(), PathNames.normalise(name)),
            List.of(player.getName()));
        return Outcome.ok("Looking for somewhere in the world that is nobody's...");
    }

    /**
     * Offers a shared beginning to other players.
     *
     * <p>Nothing happens here except the asking. No path is created, nobody is moved, and the
     * initiator's own existing path is untouched until the last person has agreed — which is the
     * only thing that makes putting several players down in one place defensible.
     */
    public Outcome propose(Player initiator, String name, List<Player> invited) {
        Outcome refusal = refuseIfCannotBegin(initiator, name);
        if (refusal != null) return refusal;

        if (beginnings.partyOf(initiator.getUniqueId()) != null) {
            return Outcome.refused("You are already waiting on an answer. /path cancel withdraws it.");
        }

        Map<UUID, String> members = new LinkedHashMap<>();
        for (Player one : invited) {
            if (one.getUniqueId().equals(initiator.getUniqueId())) {
                return Outcome.refused("You are already part of it; name the others.");
            }
            if (beginnings.partyOf(one.getUniqueId()) != null) {
                return Outcome.refused(one.getName() + " is already deciding about another beginning.");
            }
            if (isMoving(one.getUniqueId())) {
                return Outcome.refused(one.getName() + " is in the middle of a move.");
            }
            Outcome theirs = refuseIfCannotBegin(one, null);
            if (theirs != null) {
                return Outcome.refused(one.getName() + " cannot: " + theirs.message());
            }
            members.put(one.getUniqueId(), one.getName());
        }
        if (members.isEmpty()) {
            return Outcome.refused("Name at least one other player, or use /path new <name> alone.");
        }

        SharedBeginnings.Party party = beginnings.open(initiator.getUniqueId(), initiator.getName(),
            PathNames.normalise(name), members, settings().getInvitationTimeoutSeconds());

        String who = String.join(", ", party.members().values());
        for (UUID member : party.othersThan(initiator.getUniqueId())) {
            Player one = Bukkit.getPlayer(member);
            if (one == null) continue;
            one.sendMessage(initiator.getName() + " is beginning a new path called '"
                + party.proposedName() + "' and wants you there: " + who + ".");
            one.sendMessage("Your current paths are kept. /path accept " + initiator.getName()
                + " [name] to agree, /path decline " + initiator.getName() + " to refuse.");
        }

        return Outcome.ok("Asked " + String.join(", ", members.values()) + ". Nothing happens until"
            + " everyone agrees; it lapses in " + settings().getInvitationTimeoutSeconds() + " seconds.");
    }

    /**
     * Agrees to a shared beginning.
     *
     * @param chosenName what this player wants their new path called, or null to take the proposed
     *                   name — which they may not be able to, since path names are their own and
     *                   they may already hold one by that name
     */
    public Outcome accept(Player player, String initiatorName, String chosenName) {
        SharedBeginnings.Party party = beginnings.partyOf(player.getUniqueId());
        if (party == null) {
            return Outcome.refused("Nobody is waiting on an answer from you.");
        }
        if (party.initiator().equals(player.getUniqueId())) {
            return Outcome.refused("You asked for this one. /path cancel withdraws it.");
        }
        if (initiatorName != null && !party.initiatorName().equalsIgnoreCase(initiatorName)) {
            return Outcome.refused("Your open offer is from " + party.initiatorName() + ".");
        }
        if (party.hasConsented(player.getUniqueId())) {
            return Outcome.refused("You have already agreed; waiting on "
                + String.join(", ", namesOf(party, party.outstanding())) + ".");
        }

        String name = chosenName == null ? party.proposedName() : PathNames.normalise(chosenName);
        Outcome refusal = refuseIfCannotBegin(player, name);
        if (refusal != null) return refusal;

        SharedBeginnings.Party complete = beginnings.consent(player.getUniqueId(), name);
        tell(party.othersThan(player.getUniqueId()),
            player.getName() + " has agreed.");

        if (complete == null) {
            return Outcome.ok("Agreed. Waiting on "
                + String.join(", ", namesOf(party, party.outstanding())) + ".");
        }

        beginnings.cancel(player.getUniqueId());
        List<Player> everyone = new ArrayList<>();
        Map<UUID, String> names = new LinkedHashMap<>();
        for (UUID member : party.members().keySet()) {
            Player one = Bukkit.getPlayer(member);
            if (one == null) {
                tell(party.othersThan(member), party.nameOf(member)
                    + " left before it could begin; nothing has changed.");
                return Outcome.refused(party.nameOf(member) + " is no longer online.");
            }
            everyone.add(one);
            names.put(member, party.chosenNameOf(member));
        }

        beginTogether(everyone, names, new ArrayList<>(party.members().values()));
        return Outcome.ok("Agreed. Looking for somewhere in the world that is nobody's...");
    }

    /**
     * Refuses a shared beginning, which ends the offer for everybody.
     *
     * @param initiatorName who the refusal is aimed at, checked so that a refusal typed at the
     *                      wrong offer is a message rather than a mistake, or null to refuse
     *                      whichever offer is open
     */
    public Outcome decline(Player player, String initiatorName) {
        SharedBeginnings.Party open = beginnings.partyOf(player.getUniqueId());
        if (open != null && initiatorName != null
            && !open.initiatorName().equalsIgnoreCase(initiatorName)
            && !open.initiator().equals(player.getUniqueId())) {
            return Outcome.refused("Your open offer is from " + open.initiatorName() + ".");
        }

        SharedBeginnings.Party party = beginnings.cancel(player.getUniqueId());
        if (party == null) {
            return Outcome.refused("Nobody is waiting on an answer from you.");
        }

        boolean withdrawn = party.initiator().equals(player.getUniqueId());
        tell(party.othersThan(player.getUniqueId()), player.getName()
            + (withdrawn ? " withdrew the shared beginning." : " declined; the shared beginning is off."));
        return Outcome.ok(withdrawn ? "Withdrawn." : "Declined. Nothing has changed.");
    }

    /**
     * Creates one new path for each player and puts all of them down at the same place.
     *
     * <p>The location is searched for once, not once per player. That is the entire meaning of the
     * feature: several existences that begin in the same square metre of a world that would
     * otherwise have scattered them half a million blocks apart.
     */
    private void beginTogether(List<Player> players, Map<UUID, String> names, List<String> companions) {
        World world = spawnService.resolveOverworld();
        if (world == null) {
            for (Player one : players) {
                one.sendMessage("No world is loaded; nothing has been changed.");
            }
            return;
        }

        for (Player one : players) {
            moving.add(one.getUniqueId());
        }

        String searchedFor = players.size() == 1
            ? players.get(0).getName()
            : String.join("+", players.stream().map(Player::getName).toList());

        spawnService.findFreshSpawn(world, players.get(0).getUniqueId(), searchedFor, location -> {
            for (Player one : players) {
                // Everybody is checked again here: the search takes as long as it takes, and a
                // player who logged out during it is no longer somebody this can move.
                Player live = Bukkit.getPlayer(one.getUniqueId());
                if (live == null || !live.isOnline()) {
                    moving.remove(one.getUniqueId());
                    continue;
                }
                openNewPath(live, names.get(one.getUniqueId()),
                    players.size() == 1 ? List.of() : companions, location);
            }
        });
    }

    /**
     * Puts a player's current existence away and stands them up in a brand new one.
     *
     * <p>The same move as a switch, with one difference: what arrives is not read off a disk, it is
     * a life that has not been lived — empty hands, empty vault, no experience, and a Samsara record
     * saying only that it began here. A first join, in other words, which is exactly what it is.
     */
    private void openNewPath(Player player, String name, List<String> companions, Location where) {
        UUID account = player.getUniqueId();

        // Asked again, at the last possible moment. Agreeing to a shared beginning does not stop a
        // player using the wait to fill their last slot or claim the name themselves, and the check
        // that ran when they agreed is by then out of date.
        Outcome refusal = refuseIfNoRoomFor(player, name);
        if (refusal != null) {
            moving.remove(account);
            player.sendMessage("Your new path was not begun: " + refusal.message());
            return;
        }

        PathIndex index = indexOf(account);
        PlayerPath outgoing = index.active();
        PlayerPath opened = PlayerPath.beginning(name, companions);

        PlayerData record = new PlayerData();
        record.setHasJoinedBefore(true);
        record.setWorldUid(where.getWorld().getUID());
        record.ensureLifeId();
        record.setFirstSpawn(where.getWorld().getName(), where.getX(), where.getY(), where.getZ());

        PathSnapshot fresh = new PathSnapshot(record, IncarnationState.freshAt(where));

        move(player, index, outgoing, opened, fresh, where, () -> {
            journal.record(JournalEntry.Reason.FIRST_JOIN, account, player.getName(),
                where.getWorld().getName(), where.getX(), where.getY(), where.getZ());

            player.sendMessage("You are walking " + opened.name() + ", and it has nothing in it.");
            if (!companions.isEmpty()) {
                player.sendMessage("It began alongside " + String.join(", ", companions) + ".");
            }
        });
    }

    // -------------------------------------------------------------------------
    // Renaming and abandoning
    // -------------------------------------------------------------------------

    public Outcome rename(Player player, String from, String to) {
        UUID account = player.getUniqueId();
        PathIndex index = indexOf(account);

        PlayerPath path = index.byName(from);
        if (path == null) {
            return Outcome.refused("You have no path called '" + from + "'. /path lists them.");
        }

        String rejection = PathNames.rejectionFor(to);
        if (rejection != null) {
            return Outcome.refused("That name will not do, because " + rejection + ".");
        }
        String name = PathNames.normalise(to);
        if (index.nameTaken(name, path)) {
            return Outcome.refused("You already have a path called '" + name + "'.");
        }

        String was = path.name();
        path.rename(name);
        if (!store.saveIndex(account, index)) {
            path.rename(was);
            return Outcome.refused("The rename could not be written to disk; it is still called "
                + was + ".");
        }
        return Outcome.ok(was + " is now called " + name + ".");
    }

    /**
     * Destroys a path for good.
     *
     * <p>Materially this is a death, and it is settled like one. The belongings of that existence —
     * its inventory and its ender chest alike — fall into the world at the place it was standing,
     * where anybody at all may find them, and its experience falls with them. Nothing follows the
     * player back into the path they are walking, because a way to abandon an existence and keep its
     * pockets would be a way to move a shulker box full of netherite across the map for free.
     *
     * <p>The order is chosen against that risk rather than against inconvenience: the path is
     * removed from the index and its file deleted <em>before</em> anything is dropped. A server that
     * dies in the middle loses the belongings, which is what dying is; it can never produce them
     * twice.
     */
    public Outcome abandon(Player player, String name, boolean confirmed) {
        Outcome refusal = refuseIfBusy(player);
        if (refusal != null) return refusal;

        UUID account = player.getUniqueId();
        PathIndex index = indexOf(account);

        PlayerPath path = index.byName(name);
        if (path == null) {
            return Outcome.refused("You have no path called '" + name + "'. /path lists them.");
        }
        if (path == index.active()) {
            return Outcome.refused("You are standing in " + path.name() + ". Switch to another path"
                + " first — there has to be somewhere left for you to be.");
        }
        if (index.size() <= 1) {
            return Outcome.refused("That is your only path.");
        }
        if (!confirmed) {
            return Outcome.refused("Abandoning " + path.name() + " destroys it. Everything it is"
                + " carrying falls where it stands, for anybody to find, and the name is free again."
                + " Type: /path abandon " + path.name() + " confirm");
        }

        PathSnapshot snapshot = store.loadSnapshot(account, path.id());

        index.remove(path);
        if (!store.saveIndex(account, index)) {
            index.add(path);
            return Outcome.refused("The change could not be written to disk; " + path.name()
                + " is untouched.");
        }
        store.deleteSnapshot(account, path.id());

        if (snapshot == null) {
            plugin.getLogger().warning("[Samsara] " + player.getName() + " abandoned '" + path.name()
                + "', which had no readable file. The slot is free; there was nothing to drop.");
            return Outcome.ok(path.name() + " is gone.");
        }

        scatter(player, path.name(), snapshot.state());
        return Outcome.ok(path.name() + " is gone. What it was carrying is on the ground where it"
            + " stood.");
    }

    /**
     * Drops an abandoned existence's belongings into the world where it stood.
     *
     * <p>The chunk is loaded first, because that ground has very likely not been touched since its
     * owner last stood on it, and dropping into an unloaded chunk is dropping into nothing. If the
     * world is gone or the chunk cannot be loaded, the belongings are lost — the same outcome as
     * dying somewhere nobody ever goes, and better than the alternative of holding them for a path
     * that no longer exists.
     */
    private void scatter(Player player, String pathName, IncarnationState state) {
        Location where = state.location();
        List<ItemStack> belongings = state.belongings();
        int experience = Math.min(state.level() * 7, 100);

        if (where == null) {
            plugin.getLogger().warning("[Samsara] " + player.getName() + " abandoned '" + pathName
                + "', which stood in the world '" + state.worldName() + "'. That world is not"
                + " loaded, so its " + belongings.size() + " item stack(s) are gone.");
            return;
        }
        if (belongings.isEmpty() && experience <= 0) return;

        World world = where.getWorld();
        world.getChunkAtAsync(where).thenAccept(chunk ->
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (ItemStack item : belongings) {
                    world.dropItemNaturally(where, item);
                }
                if (experience > 0) {
                    world.spawn(where, ExperienceOrb.class, orb -> orb.setExperience(experience));
                }
                plugin.getLogger().info("[Samsara] " + player.getName() + " abandoned '" + pathName
                    + "'; " + belongings.size() + " item stack(s) and " + experience
                    + " experience fell at " + world.getName() + " " + where.getBlockX() + ","
                    + where.getBlockY() + "," + where.getBlockZ() + ".");
            })
        ).exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING, "[Samsara] Could not load the ground under the"
                + " abandoned path '" + pathName + "'; its belongings are lost.", error);
            return null;
        });
    }

    // -------------------------------------------------------------------------
    // Putting an interrupted move right
    // -------------------------------------------------------------------------

    /**
     * Puts right a switch that a crash landed in the middle of.
     *
     * <p>One rule does all of it: <b>the path a player is walking never has a dormant file.</b> The
     * active path is the player, so a file for it can only mean a switch that wrote its outgoing
     * half and never finished the incoming one — or one that finished and died before tidying up.
     * In both cases the file is what that existence should be, and the live player is not to be
     * trusted, so the file wins and is then removed.
     *
     * <p>Nothing is lost either way. The path on the other side of the interrupted switch still has
     * its own file and is still dormant; the player simply has not moved.
     */
    private void recoverInterruptedSwitch(Player player, PathIndex index) {
        UUID account = player.getUniqueId();
        PlayerPath active = index.active();
        if (active == null || !store.hasSnapshot(account, active.id())) return;

        PathSnapshot snapshot = store.loadSnapshot(account, active.id());
        if (snapshot == null) {
            plugin.getLogger().severe("[Samsara] " + player.getName() + " has an unfinished switch"
                + " into '" + active.name() + "' whose file cannot be read. They are left as they"
                + " are; the file has been set aside for inspection.");
            return;
        }

        plugin.getLogger().warning("[Samsara] " + player.getName() + " has an unfinished switch"
            + " into '" + active.name() + "'; restoring that path from disk.");

        dataStore.save(account, snapshot.record());
        snapshot.state().applyTo(player);
        store.deleteSnapshot(account, active.id());

        Location where = snapshot.state().location();
        if (where != null) {
            player.teleportAsync(where);
        }
        player.sendMessage("The server stopped while you were changing paths. You are back in "
            + active.name() + ", exactly as it was.");
    }

    /**
     * Drops any dormant path that has no file behind it.
     *
     * <p>Only reachable if a file was deleted by hand or a write failed silently. The path cannot be
     * restored — there is nothing to restore from — so keeping it in the list would cost the player
     * a slot for an existence they can never walk back into, and would tell them it exists every
     * time they ran {@code /path}.
     */
    private void repairMissingSnapshots(UUID account, PathIndex index) {
        List<PlayerPath> orphans = new ArrayList<>();
        for (PlayerPath path : index.dormant()) {
            if (!store.hasSnapshot(account, path.id())) {
                orphans.add(path);
            }
        }
        if (orphans.isEmpty()) return;

        for (PlayerPath path : orphans) {
            plugin.getLogger().severe("[Samsara] The path '" + path.name() + "' of " + account
                + " has no file on disk and cannot be restored; removing it from their list.");
            index.remove(path);
        }
        store.saveIndex(account, index);
    }

    // -------------------------------------------------------------------------
    // Guards and plumbing
    // -------------------------------------------------------------------------

    /**
     * Whether this player can be moved at all right now, whatever they are asking for.
     *
     * <p>The dead case is the one that matters. A player on the respawn screen is between two lives
     * and Samsara is already searching the world for where the next one begins; archiving that would
     * store an existence whose position is about to be decided by something else entirely. They can
     * click Respawn and switch a second later.
     */
    private Outcome refuseIfBusy(Player player) {
        if (!settings().isEnabled()) {
            return Outcome.refused("Paths are switched off on this server.");
        }
        if (isMoving(player.getUniqueId())) {
            return Outcome.refused("You are already in the middle of a move.");
        }
        if (player.isDead()) {
            return Outcome.refused("You are dead. Respawn first — the path you are on receives that"
                + " new life, and you can leave it afterwards.");
        }
        if (dataStore.load(player.getUniqueId()).isCalculatingRespawn()) {
            return Outcome.refused("A new life is still being found for you. Try again in a moment.");
        }
        if (travelService.isTravelling(player.getUniqueId())) {
            return Outcome.refused("You are between dimensions. Try again once you have arrived.");
        }
        return null;
    }

    /** Whether this player could begin a path, and — when a name is offered — whether by that name. */
    private Outcome refuseIfCannotBegin(Player player, String name) {
        Outcome refusal = refuseIfBusy(player);
        if (refusal != null) return refusal;
        return refuseIfNoRoomFor(player, name);
    }

    /**
     * Whether there is a free slot, and a free name to put in it.
     *
     * <p>Kept apart from {@link #refuseIfBusy} so it can be asked a second time, at the moment a
     * path is actually created, when the player is already midway through the move that creates it
     * and the busy test would refuse them for being exactly where they are meant to be.
     */
    private Outcome refuseIfNoRoomFor(Player player, String name) {
        PathIndex index = indexOf(player.getUniqueId());
        if (index.size() >= settings().getMaxPaths()) {
            return Outcome.refused("You hold " + index.size() + " paths, which is all this server"
                + " allows. Abandon one first — /path abandon <name>.");
        }

        if (name == null) return null;

        String rejection = PathNames.rejectionFor(name);
        if (rejection != null) {
            return Outcome.refused("That name will not do, because " + rejection + ".");
        }
        if (index.byName(PathNames.normalise(name)) != null) {
            return Outcome.refused("You already have a path called '" + PathNames.normalise(name)
                + "'.");
        }
        return null;
    }

    /**
     * Says something where a player is standing, to whoever the social layer says should hear it.
     *
     * <p>Reusing that machinery rather than broadcasting is the whole point: somebody stepping out
     * of an existence is news exactly where it happened, in the same way and to the same people as
     * a join or a leave. With the social layer switched off it reaches everybody, which is what
     * vanilla would have done.
     */
    private void announce(Player player, SocialEvent kind, Component message) {
        if (message == null) return;
        social.audience().announce(player, kind, message);
    }

    /** Says one thing to whichever of these players is still here to hear it. */
    private void tell(List<UUID> members, String message) {
        for (UUID member : members) {
            Player one = Bukkit.getPlayer(member);
            if (one != null) one.sendMessage(message);
        }
    }

    private List<String> namesOf(SharedBeginnings.Party party, List<UUID> members) {
        List<String> names = new ArrayList<>();
        for (UUID member : members) names.add(party.nameOf(member));
        return names;
    }
}
