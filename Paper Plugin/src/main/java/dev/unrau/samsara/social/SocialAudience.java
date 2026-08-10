package dev.unrau.samsara.social;

import dev.unrau.samsara.config.PluginConfig;
import dev.unrau.samsara.config.SocialConfig;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Who gets told.
 *
 * <p>This is the one place that answers that question, and every kind of social event goes through
 * it: chat, joins, leaves, deaths, advancements, a wolf dying. None of them carries its own idea of
 * who is nearby or what a contact means, because five copies of that rule would be five chances for
 * one of them to disagree with the others — and the disagreement a player would notice is the one
 * where somebody they blocked can still be heard.
 *
 * <p>The order of the tests below is the design, not an implementation detail:
 *
 * <ol>
 *   <li><b>Ignore first.</b> It is the recipient's own switch and it beats everything, including
 *       being a contact and being in the same room.</li>
 *   <li><b>Contacts next.</b> A relationship both players agreed to, and distance stops applying.</li>
 *   <li><b>Then proximity.</b> Same world, within the radius for that kind of event.</li>
 * </ol>
 *
 * <p>Different worlds are never near each other. A Nether coordinate is eight times smaller than the
 * Overworld one it sits under and an End coordinate is a reflection of one; there is no distance
 * between them that means anything, and pretending otherwise would put a player's chat in the ears
 * of somebody standing over a completely different part of the map.
 */
public class SocialAudience {

    /** Receives proximity-scoped events from any distance. For moderation, not for playing. */
    public static final String OBSERVE_PERMISSION = "samsara.social.observe";

    private final PluginConfig config;
    private final SocialStore store;
    private final SocialGraph graph;

    public SocialAudience(PluginConfig config, SocialStore store, SocialGraph graph) {
        this.config = config;
        this.store = store;
        this.graph = graph;
    }

    /**
     * Whether one player should be shown an event about another.
     *
     * <p>Called from Paper's async chat thread as well as from the main one. Everything it reads is
     * either immutable, concurrent, or a live entity position — never a structure that a tick could
     * be halfway through rewriting.
     */
    public boolean canSee(Player viewer, Player source, SocialEvent kind) {
        SocialConfig social = config.getSocial();
        if (!social.isEnabled()) return true;

        UUID viewerId = viewer.getUniqueId();
        UUID sourceId = source.getUniqueId();

        if (viewerId.equals(sourceId)) return kind.reachesSource();

        // The recipient's own switch, and the only test that can refuse on its own.
        if (store.load(viewerId).ignores(sourceId)) return false;

        if (kind.reachesContacts() && social.isContactsEnabled()
            && graph.areContacts(viewerId, sourceId)) {
            return true;
        }

        if (isNear(viewer, source, kind.radius(social))) return true;

        return viewer.hasPermission(OBSERVE_PERMISSION);
    }

    /** Everybody online who should be told about this event, the source included where that fits. */
    public List<Player> resolve(Player source, SocialEvent kind) {
        List<Player> audience = new ArrayList<>();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (canSee(viewer, source, kind)) {
                audience.add(viewer);
            }
        }
        return audience;
    }

    /**
     * Sends a message to everybody an event should reach, and to the console.
     *
     * <p>The console is not an audience with a position, and the server log is not a social space:
     * it keeps everything, exactly as it would if nobody had ever heard of proximity. An operator
     * reading logs after the fact should not have to reconstruct who was standing where.
     */
    public void announce(Player source, SocialEvent kind, Component message) {
        Bukkit.getConsoleSender().sendMessage(message);
        for (Player viewer : resolve(source, kind)) {
            viewer.sendMessage(message);
        }
    }

    /**
     * Sends a message to everybody near a place, regardless of who — if anyone — it is about.
     *
     * <p>Used for the events that belong to a location rather than to a player: a tamed animal dying
     * is news where it happened, and the ignore test is still applied on the owner's behalf so that
     * blocking somebody blocks their dog too.
     */
    public void announceNear(Location where, UUID about, SocialEvent kind, Component message,
                             UUID... except) {
        SocialConfig social = config.getSocial();
        int radius = kind.radius(social);
        if (radius <= 0) return;

        long limit = (long) radius * radius;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (isExcepted(viewer.getUniqueId(), except)) continue;
            if (about != null && store.load(viewer.getUniqueId()).ignores(about)) continue;
            if (!viewer.getWorld().equals(where.getWorld())) continue;
            if (viewer.getLocation().distanceSquared(where) > limit) continue;
            viewer.sendMessage(message);
        }
    }

    private static boolean isExcepted(UUID who, UUID[] except) {
        for (UUID one : except) {
            if (who.equals(one)) return true;
        }
        return false;
    }

    /**
     * Whether two players are close enough to count as being in the same place.
     *
     * <p>A radius of zero switches proximity off entirely — a server that wants contacts and nothing
     * else says so with a zero — and a radius large enough to span the world gives vanilla's single
     * shared room back without anything else changing.
     */
    private boolean isNear(Player viewer, Player source, int radius) {
        if (radius <= 0) return false;
        if (!viewer.getWorld().equals(source.getWorld())) return false;

        Location here = viewer.getLocation();
        Location there = source.getLocation();
        double dx = here.getX() - there.getX();
        double dy = here.getY() - there.getY();
        double dz = here.getZ() - there.getZ();
        return dx * dx + dy * dy + dz * dz <= (double) radius * radius;
    }
}
