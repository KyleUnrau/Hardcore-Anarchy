package dev.unrau.samsara.service;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds a traveller still while a journey resolves, and crosses them into the destination dimension
 * as early as it can.
 *
 * <p>Every destination in this plugin may be terrain that has never been generated, so a journey
 * always contains a wait — chunks load, a site is built, a landing is chosen. Two separate things
 * have to be true for that wait to be survivable and to look deliberate, and they happen at different
 * moments.
 *
 * <h2>Suspending, in the same tick the portal is cancelled</h2>
 *
 * <p>The moment this plugin takes over a portal it cancels vanilla's transition, and a cancelled
 * transition stops the trip but not the physics. An End portal block is not solid and a stronghold
 * portal room has lava under it, so a traveller left to their own devices for the length of a slow
 * journey falls through the door they were standing in and swims. That is not a slow teleport; it is
 * an unprotected player standing in a hole.
 *
 * <p>So {@link #suspend} runs synchronously, inside the event, before anything asynchronous begins.
 * From that tick on the traveller cannot fall, cannot be hurt and cannot wander off.
 *
 * <h2>What actually holds a player</h2>
 *
 * <p>Not gravity. {@code setGravity(false)} is an entity flag the server keeps for entities the
 * server itself moves — and a player is not one of those. The client simulates its own movement and
 * tells the server where it went, so a player with the flag set falls exactly as before. It is kept
 * here anyway, because it costs nothing, it is saved to the player file, and it is therefore a
 * durable marker that {@link #recoverOnJoin} can recognise after a logout or a crash.
 *
 * <p>What holds a player is <b>flight</b>. A flying client applies no gravity of its own, and a fly
 * speed of zero leaves it nowhere to go. Together with invulnerability that is a real suspension:
 * the traveller hangs where they were, unhurt, until the journey has somewhere to put them.
 *
 * <h2>Crossing, as soon as one chunk allows it</h2>
 *
 * <p>{@link #begin} then moves them to the far side, above the column they are bound for, which only
 * needs the one chunk under it rather than the whole area the arrival will be resolved in. That
 * dimension change is what puts the client's own loading screen up — the real one, the End's
 * starfield or the Overworld's — and it holds while the server finishes.
 *
 * <h2>Nothing is left hanging</h2>
 *
 * <p>Three things end a hold, and between them they cover every way a journey can stop: it finishes
 * and the traveller is {@link #deliver}ed; it never reports back and a watchdog puts them down; or
 * the server loses them mid-journey and {@link #recoverOnJoin} finishes it on their next login. That
 * last one is why the durable marker matters, and why nothing here restores anything on quit or on
 * shutdown — the state left on the player is the note saying what was happening to them.
 */
public final class TransitHold {

    /**
     * How far above a world's build ceiling a traveller waits once they have crossed.
     *
     * <p>Above the ceiling because there is nothing up there: no block to suffocate in, no lava, no
     * mob, and no ground for a slow journey to leave someone standing in. Above rather than below
     * because the void is the one damage in Minecraft that invulnerability does not stop.
     */
    private static final int HOLD_CLEARANCE = 8;

    /**
     * How long a hold may last before it is treated as abandoned, in ticks.
     *
     * <p>Twice the transit claim's own timeout. A journey this old has already lost its claim, so the
     * only thing left to do about it is stop the traveller hanging there waiting for a completion
     * that is not coming.
     */
    private static final long ABANDONED_AFTER_TICKS = 600L;

    /** How far around a stranded traveller to load and search when setting them down. */
    private static final int RESCUE_RADIUS = 16;

    /** Minecraft's default fly speed, restored to anyone who had not changed it. */
    private static final float DEFAULT_FLY_SPEED = 0.1f;

    /**
     * A traveller's own state before the hold, and where to put them if the journey never finishes.
     *
     * <p>The fallback is taken before they move for a reason: it is somewhere they were demonstrably
     * able to stand, which is more than can be said for the portal block they are suspended in.
     */
    private record Held(boolean gravity, boolean invulnerable, boolean allowFlight, boolean flying,
                        float flySpeed, Location fallback) {}

    private final JavaPlugin plugin;
    private final SafeLocationFinder finder;
    private final Map<UUID, Held> holding = new ConcurrentHashMap<>();

    public TransitHold(JavaPlugin plugin, SafeLocationFinder finder) {
        this.plugin = plugin;
        this.finder = finder;
    }

    public boolean isHolding(UUID uuid) {
        return holding.containsKey(uuid);
    }

    /** Number of travellers currently suspended. */
    public int size() {
        return holding.size();
    }

    /**
     * Takes a traveller out of their own hands, where they stand, in the tick the journey begins.
     *
     * <p>Synchronous and cheap on purpose: it is called from inside the portal event that is being
     * cancelled, and every tick between the cancellation and this is a tick the traveller spends
     * falling through the door.
     *
     * <p>Idempotent. A portal fires every tick somebody stands in it, and the second call must not
     * record a suspended player's suspended state as the state to give back to them.
     *
     * @param fallback somewhere the traveller could stand when the journey began, used if it never
     *                 finishes
     */
    public void suspend(Player player, Location fallback) {
        UUID uuid = player.getUniqueId();

        if (holding.containsKey(uuid)) {
            settle(player);
            return;
        }

        holding.put(uuid, new Held(player.hasGravity(), player.isInvulnerable(),
            player.getAllowFlight(), player.isFlying(), player.getFlySpeed(), fallback.clone()));

        settle(player);
        // Kept for the player file to remember, not for the physics — see the class comment.
        player.setGravity(false);
        player.setInvulnerable(true);
        // The order matters: a player who is not allowed to fly cannot be made to fly.
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setFlySpeed(0.0f);

        watchOver(uuid);
    }

    /**
     * Crosses a suspended traveller into {@code world}, above the column they are bound for.
     *
     * <p>Only the one chunk under the hold has to load for this, which is what makes it quick enough
     * to read as immediate. Everything else loads while the traveller is already on the far side,
     * behind the client's own loading screen.
     *
     * @return true once they are across; false if the teleport was refused, in which case they are
     *         still suspended where they started and the journey can finish the old way
     */
    public CompletableFuture<Boolean> begin(Player player, World world, int x, int z, Location fallback) {
        suspend(player, fallback);

        Location facing = player.getLocation();
        Location hold = new Location(world, x + 0.5, holdY(world), z + 0.5,
            facing.getYaw(), facing.getPitch());

        return player.teleportAsync(hold, TeleportCause.PLUGIN)
            .thenApply(Boolean.TRUE::equals);
    }

    /**
     * Sets a traveller down and gives them their own state back.
     *
     * <p>Safe to call for a player who was never suspended: it is then an ordinary teleport, which is
     * what makes the two paths through {@link DimensionalTravelService} one path at every call site.
     */
    public CompletableFuture<Boolean> deliver(Player player, Location destination, TeleportCause cause) {
        settle(player);
        UUID uuid = player.getUniqueId();

        return player.teleportAsync(destination, cause).handle((success, error) -> {
            // Scheduled rather than done here: the teleport completes off the main thread, and a
            // player's own abilities are not ours to hand back from there. Restoring happens whether
            // the teleport worked or not — a refused teleport must never leave somebody suspended.
            Bukkit.getScheduler().runTask(plugin, () -> restore(uuid));
            return error == null && Boolean.TRUE.equals(success);
        });
    }

    /** Gives a suspended traveller their own state back without moving them. */
    public void restore(UUID uuid) {
        Held held = holding.remove(uuid);
        if (held == null) return;

        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) return;

        player.setFlySpeed(held.flySpeed());
        player.setAllowFlight(held.allowFlight());
        player.setFlying(held.allowFlight() && held.flying());
        player.setGravity(held.gravity());
        player.setInvulnerable(held.invulnerable());
        player.setFallDistance(0.0f);
    }

    /**
     * Finishes a journey the server lost, recognising the traveller by the marker the hold left on
     * them: a player whose gravity flag is off.
     *
     * <p>Nothing is stored anywhere for this. The flag lives in the player file like any other, so it
     * survives exactly the events — a logout, a crash, a restart — that would lose an in-memory note
     * of who was mid-journey.
     *
     * <p>A traveller who had already crossed is somewhere no player belongs, above the ceiling of a
     * world, and is set down on the ground beneath them. One who never crossed is standing where they
     * were when they logged out, which is where they chose to be, so they simply get themselves back.
     *
     * @return true if this player was mid-journey
     */
    public boolean recoverOnJoin(Player player) {
        if (player.hasGravity()) return false;

        Location at = player.getLocation();
        boolean crossed = at.getY() > player.getWorld().getMaxHeight();

        plugin.getLogger().warning("[Travel] " + player.getName() + " rejoined mid-journey at "
            + describe(at) + "; " + (crossed ? "setting them down there." : "returning them to themselves.")
            + " Their crossing did not finish.");

        // Claim them so the restore below hands back ordinary flight rather than whatever a
        // half-finished journey left set.
        // Nothing survived to say what their own state was, so it is reconstructed from what the
        // game mode entitles them to: ordinary gravity, no invulnerability, and flight only where
        // the mode grants it anyway.
        boolean fliesByRight = player.getGameMode() == GameMode.CREATIVE
            || player.getGameMode() == GameMode.SPECTATOR;
        holding.putIfAbsent(player.getUniqueId(),
            new Held(true, false, fliesByRight, fliesByRight, DEFAULT_FLY_SPEED, at.clone()));

        if (crossed) {
            player.setInvulnerable(true);
            player.setAllowFlight(true);
            player.setFlying(true);
            setDown(player);
        } else {
            restore(player.getUniqueId());
        }
        return true;
    }

    /**
     * Watches a hold that should have ended by now.
     *
     * <p>No task is cancelled when a journey completes normally: the check below is the whole of it,
     * and a tick spent finding out that a traveller has already landed is cheaper than the
     * bookkeeping to avoid it.
     */
    private void watchOver(UUID uuid) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Held held = holding.get(uuid);
            if (held == null) return;

            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) return;

            plugin.getLogger().warning("[Travel] " + player.getName() + "'s journey never reported"
                + " back after " + (ABANDONED_AFTER_TICKS / 20) + " seconds; releasing them rather"
                + " than leaving them suspended.");

            if (player.getLocation().getY() > player.getWorld().getMaxHeight()) {
                // They crossed. There is no going back from up here, so land them where they are.
                setDown(player);
            } else {
                // They never left. Put them back on the spot the journey was started from, which is
                // somewhere they could stand — unlike the portal block they are suspended in.
                deliver(player, held.fallback(), TeleportCause.PLUGIN);
            }
        }, ABANDONED_AFTER_TICKS);
    }

    /**
     * Puts a suspended traveller on whatever ground is under them, loading it first.
     *
     * <p>They stay suspended until it is there, so the rescue can never itself be the thing that drops
     * somebody out of the sky. The ladder ends at {@link SafeLocationFinder#forceLanding}, which
     * cannot fail: standing somewhere awkward beats hanging over it forever.
     */
    private void setDown(Player player) {
        World world = player.getWorld();
        Location at = player.getLocation();
        int x = at.getBlockX();
        int z = at.getBlockZ();

        ChunkArea.load(world, x, z, RESCUE_RADIUS).thenRun(() ->
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;

                Location landing = finder.findAnyLanding(world, x, z);
                if (landing == null) {
                    landing = finder.findSafeStandingNear(world, x, world.getSeaLevel(), z, RESCUE_RADIUS);
                }
                if (landing == null) {
                    landing = finder.forceLanding(world, x, z);
                }
                landing.setYaw(at.getYaw());
                landing.setPitch(at.getPitch());
                deliver(player, landing, TeleportCause.PLUGIN);
            })
        ).exceptionally(error -> {
            plugin.getLogger().warning("[Travel] Could not load the ground under " + player.getName()
                + "; they stay suspended until they travel again. " + error.getMessage());
            return null;
        });
    }

    private static String describe(Location at) {
        return at.getWorld().getName() + " " + at.getBlockX() + "," + at.getBlockY() + "," + at.getBlockZ();
    }

    /** Where a traveller waits above a world: clear of everything the world itself can contain. */
    private static int holdY(World world) {
        return world.getMaxHeight() + HOLD_CLEARANCE;
    }

    /** Cancels whatever motion a traveller arrived with, so no journey is ever paid for in fall damage. */
    private static void settle(Player player) {
        player.setFallDistance(0.0f);
        player.setVelocity(new Vector(0, 0, 0));
    }
}
