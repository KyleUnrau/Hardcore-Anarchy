package dev.unrau.samsara.social;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One player's social record: who they know, who they refuse to hear, and who they are on their way
 * to knowing.
 *
 * <p>Kept apart from {@link dev.unrau.samsara.data.PlayerData} on purpose. That record describes a
 * <em>life</em> — where it began, where it ended, what it has left behind — and is rewritten every
 * time a player dies. This one describes a <em>person</em>, and survives everything that record
 * does not: logging out, dying, being exiled to the other side of the world.
 *
 * <p>The contact and ignore sets are concurrent because chat arrives on Paper's async chat thread
 * and asks this record who may hear it. Proximity progress is touched only by the scanner on the
 * main thread, but its methods are synchronized anyway so that a save running beside it cannot
 * serialise a half-updated map.
 */
public class SocialData {

    /** Schema version written by {@link SocialStore}. Bumped when the on-disk layout changes. */
    public static final int CURRENT_DATA_VERSION = 1;

    private int dataVersion = CURRENT_DATA_VERSION;

    /** Last name this player was seen under, so an offline contact still has something to call. */
    private String name = "";

    /**
     * Whether contacts may form on their own. Null means the player has never said, and the server
     * default applies — so a server that changes its mind about the default changes it for everyone
     * who never had an opinion, and for nobody who did.
     */
    private Boolean autoContacts;

    /** Contacts, by id, with the name each was last seen under. Mutual by construction. */
    private final Map<UUID, String> contacts = new ConcurrentHashMap<>();

    /** Players whose everything this player has switched off. Purely one-sided, by design. */
    private final Map<UUID, String> ignored = new ConcurrentHashMap<>();

    /**
     * Players this pair-bond was deliberately broken with.
     *
     * <p>Written on both sides when either of them removes the other, and it is what stops standing
     * next to somebody undoing a decision to stop knowing them. Cleared only by a manual contact
     * being made again, which is consent stated out loud rather than inferred from a doorway.
     */
    private final Set<UUID> autoSuppressed = ConcurrentHashMap.newKeySet();

    /** Accumulated nearness to other players, and when each was last added to. */
    private final Map<UUID, Proximity> proximity = new HashMap<>();

    /** True when something has changed that the file on disk does not know about yet. */
    private transient boolean dirty;

    /**
     * How much nearness two players have banked, and when it was last added to.
     *
     * <p>The score is in seconds — seconds of standing right beside each other, which is what one
     * second at touching distance is worth. Everything further away is worth a fraction of that, so
     * the stored number is not a stopwatch reading and is not compared to one.
     *
     * <p>What is stored is the score at {@link #lastSampleMillis} and nothing else. The score
     * <em>now</em> is that figure minus whatever the time since has faded off it, worked out on
     * demand by {@link Fade} — which is why being apart costs a player something without the server
     * having to visit every pair on the map to charge them for it.
     */
    public static final class Proximity {
        private double seconds;
        private long lastSampleMillis;

        Proximity(double seconds, long lastSampleMillis) {
            this.seconds = seconds;
            this.lastSampleMillis = lastSampleMillis;
        }

        /** The banked score, as of {@link #lastSampleMillis}. Not the score now. */
        public double seconds()        { return seconds; }
        public long lastSampleMillis() { return lastSampleMillis; }
    }

    /**
     * What time apart does to a score.
     *
     * <p>Two rules, and they answer different questions. {@code perSecond} is the ordinary one: a
     * pair who have stopped seeing each other lose what they had built, gradually, so the score
     * always describes how close they are <em>lately</em> rather than how close they have ever been.
     * {@code forgetAfterMillis} is the backstop under it, for the server that turns the decay off:
     * past that gap the entry is not faded but dropped, and a pair who meet again after it start
     * from nothing.
     *
     * @param perSecond         score lost per second apart, in the same units the score is in
     * @param forgetAfterMillis gap after which the score is discarded outright rather than faded
     */
    public record Fade(double perSecond, long forgetAfterMillis) {

        /** The score left after {@code millisApart}, or zero once it has all gone. */
        public double after(double score, long millisApart) {
            if (millisApart >= forgetAfterMillis) return 0;
            return Math.max(0, score - perSecond * millisApart / 1000.0);
        }
    }

    // -------------------------------------------------------------------------
    // Identity
    // -------------------------------------------------------------------------

    public int getDataVersion()      { return dataVersion; }
    public void setDataVersion(int v) { dataVersion = v; }

    public String getName()          { return name; }

    public void setName(String value) {
        if (value == null || value.equals(name)) return;
        name = value;
        dirty = true;
    }

    public boolean isDirty()         { return dirty; }
    public void clearDirty()         { dirty = false; }

    // -------------------------------------------------------------------------
    // Contacts
    // -------------------------------------------------------------------------

    public boolean isContact(UUID other) {
        return contacts.containsKey(other);
    }

    public int contactCount() {
        return contacts.size();
    }

    /** The contacts, by id, with the last name each was seen under. */
    public Map<UUID, String> getContacts() {
        return Collections.unmodifiableMap(contacts);
    }

    /**
     * Adds a contact and forgets both the severance that may have stood between them and whatever
     * proximity had been accumulating towards this. The relationship exists now; the progress
     * towards it is no longer a thing anybody needs to know.
     */
    public void addContact(UUID other, String otherName) {
        contacts.put(other, otherName == null ? "" : otherName);
        autoSuppressed.remove(other);
        clearProximity(other);
        dirty = true;
    }

    public boolean removeContact(UUID other) {
        boolean removed = contacts.remove(other) != null;
        if (removed) dirty = true;
        return removed;
    }

    /** Keeps the cached name current while a contact is online, without touching the relationship. */
    public void refreshContactName(UUID other, String otherName) {
        if (otherName == null || otherName.isEmpty()) return;
        String previous = contacts.replace(other, otherName);
        if (previous != null && !previous.equals(otherName)) dirty = true;
    }

    public String contactName(UUID other) {
        return contacts.get(other);
    }

    // -------------------------------------------------------------------------
    // Ignore
    // -------------------------------------------------------------------------

    public boolean ignores(UUID other) {
        return ignored.containsKey(other);
    }

    public Map<UUID, String> getIgnored() {
        return Collections.unmodifiableMap(ignored);
    }

    public boolean ignore(UUID other, String otherName) {
        if (ignored.putIfAbsent(other, otherName == null ? "" : otherName) != null) return false;
        dirty = true;
        return true;
    }

    public boolean unignore(UUID other) {
        boolean removed = ignored.remove(other) != null;
        if (removed) dirty = true;
        return removed;
    }

    // -------------------------------------------------------------------------
    // Automatic contacts
    // -------------------------------------------------------------------------

    /** The player's own answer, or null if they have never given one. */
    public Boolean getAutoContacts() {
        return autoContacts;
    }

    public boolean autoContactsEnabled(boolean serverDefault) {
        return autoContacts == null ? serverDefault : autoContacts;
    }

    public void setAutoContacts(Boolean value) {
        autoContacts = value;
        dirty = true;
    }

    public boolean isAutoSuppressed(UUID other) {
        return autoSuppressed.contains(other);
    }

    /** Records that this pairing was broken on purpose, so proximity cannot quietly rebuild it. */
    public void suppressAuto(UUID other) {
        if (autoSuppressed.add(other)) dirty = true;
        clearProximity(other);
    }

    public boolean allowAuto(UUID other) {
        boolean removed = autoSuppressed.remove(other);
        if (removed) dirty = true;
        return removed;
    }

    public Set<UUID> getAutoSuppressed() {
        return Collections.unmodifiableSet(autoSuppressed);
    }

    // -------------------------------------------------------------------------
    // Proximity
    // -------------------------------------------------------------------------

    /**
     * Settles up with another player and returns the score that leaves, in seconds.
     *
     * <p>One call does both halves of the arithmetic, in the order they happened: the stretch since
     * this pair were last measured together fades off whatever they had banked, and then this
     * sample's worth of nearness is added. {@code apartSeconds} is the caller's own reckoning of how
     * much of that gap was time apart — an ordinary sample of a pair standing together is nearly all
     * time together, and passing the whole gap would charge them for the very seconds being credited.
     *
     * @param credit       seconds of nearness earned, already weighted by how close they were
     * @param apartSeconds seconds of the gap since the last sample that they spent apart
     */
    public synchronized double recordProximity(UUID other, double credit, double apartSeconds,
                                               long nowMillis, Fade fade) {
        Proximity entry = proximity.get(other);
        if (entry == null) {
            entry = new Proximity(0, nowMillis);
            proximity.put(other, entry);
        } else {
            entry.seconds = fade.after(entry.seconds, (long) (Math.max(0, apartSeconds) * 1000));
        }
        entry.seconds += credit;
        entry.lastSampleMillis = nowMillis;
        dirty = true;
        return entry.seconds;
    }

    /** What this pair are worth to each other right now, the time apart already taken off. */
    public synchronized double proximitySeconds(UUID other, long nowMillis, Fade fade) {
        Proximity entry = proximity.get(other);
        if (entry == null) return 0;
        return fade.after(entry.seconds, Math.max(0, nowMillis - entry.lastSampleMillis));
    }

    /** When this pair were last measured together, or zero if they never have been. */
    public synchronized long lastProximityMillis(UUID other) {
        Proximity entry = proximity.get(other);
        return entry == null ? 0 : entry.lastSampleMillis;
    }

    public synchronized void clearProximity(UUID other) {
        if (proximity.remove(other) != null) dirty = true;
    }

    /**
     * Drops every score that time apart has taken to nothing.
     *
     * <p>Called before every save, which is what keeps this file from becoming a list of everybody
     * the player has ever walked past. The entries that survive are left exactly as they are: the
     * fade is worked out from {@code lastSampleMillis} on demand, so writing a decayed figure back
     * here would only move the same number between two places it can be read from.
     */
    public synchronized int pruneProximity(long nowMillis, Fade fade) {
        int removed = 0;
        var iterator = proximity.entrySet().iterator();
        while (iterator.hasNext()) {
            Proximity entry = iterator.next().getValue();
            if (fade.after(entry.seconds, Math.max(0, nowMillis - entry.lastSampleMillis)) <= 0) {
                iterator.remove();
                removed++;
            }
        }
        if (removed > 0) dirty = true;
        return removed;
    }

    /** A snapshot of the progress table, for saving and for the admin readout. */
    public synchronized List<Map.Entry<UUID, Proximity>> proximitySnapshot() {
        return new ArrayList<>(proximity.entrySet());
    }

    /** Used by the loader only; every other path goes through {@link #recordProximity}. */
    synchronized void restoreProximity(UUID other, double seconds, long lastSampleMillis) {
        proximity.put(other, new Proximity(seconds, lastSampleMillis));
    }
}
