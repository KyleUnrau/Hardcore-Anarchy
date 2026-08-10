package dev.unrau.samsara.data;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * One thing that happened to one player: a first join, a death, an exile, or a crossing between
 * dimensions.
 *
 * <p>Entries live on the player's own record in {@code playerdata/&lt;uuid&gt;.json}, so a player's
 * history is read by opening that player's file. There is no server-wide log to grep through and
 * no way for one player's history to be mixed up with another's.
 *
 * <p>Coordinates are block positions. A journal says where somebody was, not where they were to
 * within a fraction of a block — the precise position is already kept in the fields the plugin
 * actually acts on ({@code lastExile}, {@code endExpedition} and the rest).
 *
 * @param at         when it happened
 * @param reason     what happened
 * @param playerName the name the player was using at the time, which a renamed player's old
 *                   entries keep
 * @param world      the world the location belongs to
 */
public record JournalEntry(Instant at, Reason reason, String playerName,
                           String world, long x, long y, long z) {

    public enum Reason {
        FIRST_JOIN,
        DEATH,
        EXILE_RESPAWN,
        /** Crossed into the End; the location is the arrival site. */
        END_DEPART,
        /** Left the End and was set down at the portal, or at the reflected coordinate itself. */
        END_RETURN,
        /** Left the End, but the portal could not be reached — see the server log for why. */
        END_RETURN_NEARBY,
        /** Jumped between two End cells through a wormhole; the location is where they came out. */
        END_WORMHOLE;

        /** The reason of this name, or null if the name is not one this version knows. */
        public static Reason parse(String raw) {
            if (raw == null) return null;
            for (Reason reason : values()) {
                if (reason.name().equalsIgnoreCase(raw.trim())) return reason;
            }
            return null;
        }
    }

    /**
     * Records an event happening now, truncating a live location to the block it stands on and the
     * clock to the millisecond — a history is read by people, and nanoseconds are noise in one.
     */
    public static JournalEntry now(Reason reason, String playerName,
                                   String world, double x, double y, double z) {
        return new JournalEntry(Instant.now().truncatedTo(ChronoUnit.MILLIS), reason, playerName, world,
            (long) Math.floor(x), (long) Math.floor(y), (long) Math.floor(z));
    }

    @Override
    public String toString() {
        return at + " " + reason + " " + playerName + " " + world + " " + x + "," + y + "," + z;
    }
}
