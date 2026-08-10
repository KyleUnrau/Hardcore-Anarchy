package dev.unrau.samsara.social;

import dev.unrau.samsara.config.SocialConfig;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * The relationships themselves, and the rules that keep them honest.
 *
 * <p>One rule governs everything here: <b>a contact is a pair, not a subscription.</b> Every path
 * that creates one writes both records, every path that ends one clears both records, and there is
 * no method on this class that can leave A holding B without B holding A. That is what stops
 * "adding" somebody from being a way to watch them.
 *
 * <p>Ignoring is the exact opposite and is deliberately one-sided: it is the recipient's own switch,
 * it is never told to the other player, and it overrides everything above it.
 *
 * <p>Both halves of a change are written straight to disk. A contact list is small, is changed a
 * handful of times in a player's life, and is the one thing here that would be genuinely painful to
 * lose to a crash — so it is not left waiting for a flush.
 */
public class SocialGraph {

    /** What happened when somebody tried to make a contact. */
    public enum LinkOutcome {
        /** The pair are contacts now. */
        LINKED,
        /** They already were. */
        ALREADY_CONTACTS,
        /** One of them has as many contacts as the server allows. */
        FULL
    }

    private final SocialStore store;
    private final Supplier<SocialConfig> config;

    public SocialGraph(SocialStore store, Supplier<SocialConfig> config) {
        this.store = store;
        this.config = config;
    }

    // -------------------------------------------------------------------------
    // Questions
    // -------------------------------------------------------------------------

    /**
     * Whether these two are contacts.
     *
     * <p>Asked from the async chat thread for every viewer of every message, so it is answered from
     * one side only: the invariant above means the two sides cannot disagree, and reading both would
     * cost a file read for the half of the pair who is offline.
     */
    public boolean areContacts(UUID a, UUID b) {
        return store.load(a).isContact(b);
    }

    /** Whether {@code viewer} has switched {@code source} off. Never told to the source. */
    public boolean ignores(UUID viewer, UUID source) {
        return store.load(viewer).ignores(source);
    }

    /** Whether this pair was deliberately severed, from either side. */
    public boolean isAutoSuppressed(UUID a, UUID b) {
        return store.load(a).isAutoSuppressed(b) || store.load(b).isAutoSuppressed(a);
    }

    /** Whether automatic contacts are switched on for this player, server default included. */
    public boolean autoContactsEnabled(UUID who) {
        SocialConfig social = config.get();
        return social.isAutoContactsEnabled()
            && store.load(who).autoContactsEnabled(social.isAutoContactsDefaultOn());
    }

    // -------------------------------------------------------------------------
    // Changes
    // -------------------------------------------------------------------------

    /**
     * Makes two players contacts, in both directions.
     *
     * <p>The only way a contact is ever created. Both the accepted-request path and the sustained-
     * proximity path come through here, so neither can invent a variation of the rule.
     */
    public LinkOutcome link(UUID a, String aName, UUID b, String bName) {
        if (a.equals(b)) return LinkOutcome.ALREADY_CONTACTS;

        SocialData first = store.load(a);
        SocialData second = store.load(b);
        if (first.isContact(b) && second.isContact(a)) return LinkOutcome.ALREADY_CONTACTS;

        int max = config.get().getMaxContacts();
        if (first.contactCount() >= max || second.contactCount() >= max) {
            return LinkOutcome.FULL;
        }

        first.addContact(b, bName);
        second.addContact(a, aName);
        store.save(a, first);
        store.save(b, second);
        return LinkOutcome.LINKED;
    }

    /**
     * Ends a contact from either side, and remembers that it was ended.
     *
     * <p>Both records lose the relationship, so nothing becomes one-sided, and both records gain a
     * note that this pairing was broken on purpose — which is what stops the two of them standing
     * near each other for an afternoon and finding it quietly restored. Only a manual request,
     * accepted, clears that note.
     *
     * @return true if there was a contact to remove
     */
    public boolean unlink(UUID actor, UUID other) {
        SocialData mine = store.load(actor);
        SocialData theirs = store.load(other);

        boolean removed = mine.removeContact(other) | theirs.removeContact(actor);

        mine.suppressAuto(other);
        theirs.suppressAuto(actor);

        store.save(actor, mine);
        store.save(other, theirs);
        return removed;
    }

    /**
     * Lets automatic contacts form with somebody again, from this player's side.
     *
     * <p>The deliberate mechanism that undoes a deliberate severance without either of them having
     * to send a request. It only lifts the caller's own note — the other player's still stands until
     * they lift theirs, which is the point: one person cannot decide for both that the falling-out
     * is over.
     */
    public boolean allowAuto(UUID actor, UUID other) {
        SocialData mine = store.load(actor);
        if (!mine.allowAuto(other)) return false;
        store.save(actor, mine);
        return true;
    }

    public boolean ignore(UUID actor, UUID other, String otherName) {
        SocialData mine = store.load(actor);
        if (!mine.ignore(other, otherName)) return false;
        store.save(actor, mine);
        return true;
    }

    public boolean unignore(UUID actor, UUID other) {
        SocialData mine = store.load(actor);
        if (!mine.unignore(other)) return false;
        store.save(actor, mine);
        return true;
    }

    /** Sets — or unsets, with null — whether this player lets contacts form on their own. */
    public void setAutoContacts(UUID actor, Boolean value) {
        SocialData mine = store.load(actor);
        mine.setAutoContacts(value);
        store.save(actor, mine);
    }
}
