package dev.unrau.samsara.path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Beginnings that more than one player has to agree to.
 *
 * <p>Two people who want to start again together are asking for something the rest of the plugin
 * spends its effort preventing: to be put down in the same place. That is only defensible if every
 * one of them chose it, so the offer is a question and not an invitation — nobody is moved, nothing
 * is created, and no existing path is touched until the last person has said yes.
 *
 * <p>Held in memory and never on disk, for the same reason contact requests are. A party is a
 * conversation between people who are all online at once; it expires on its own, a restart is
 * allowed to forget it, and the thing that has to survive — the paths themselves — is written the
 * moment it completes.
 *
 * <p>One party per player, in either role. A player who is already deciding about one beginning is
 * not offered a second: the answer to "which of these was I saying yes to" must never be a
 * question.
 */
public final class SharedBeginnings {

    /** An offer that has been made and not yet finished being answered. */
    public static final class Party {

        private final UUID initiator;
        private final String initiatorName;
        private final String proposedName;
        private final Map<UUID, String> members = new LinkedHashMap<>();
        private final Set<UUID> consented = new LinkedHashSet<>();
        private final Map<UUID, String> chosenNames = new LinkedHashMap<>();
        private final long expiresAtMillis;

        private Party(UUID initiator, String initiatorName, String proposedName, long expiresAtMillis) {
            this.initiator = initiator;
            this.initiatorName = initiatorName;
            this.proposedName = proposedName;
            this.expiresAtMillis = expiresAtMillis;
        }

        public UUID initiator()        { return initiator; }
        public String initiatorName()  { return initiatorName; }

        /** The name the initiator proposed, which each member may take or replace with their own. */
        public String proposedName()   { return proposedName; }

        public long expiresAtMillis()  { return expiresAtMillis; }

        /** Everyone the new beginning would move, the initiator included, by name. */
        public Map<UUID, String> members() {
            return Collections.unmodifiableMap(members);
        }

        public String nameOf(UUID member) {
            return members.get(member);
        }

        /** What this member's new path will be called. */
        public String chosenNameOf(UUID member) {
            return chosenNames.getOrDefault(member, proposedName);
        }

        public boolean hasConsented(UUID member) {
            return consented.contains(member);
        }

        /** Who has not answered yet. */
        public List<UUID> outstanding() {
            List<UUID> waiting = new ArrayList<>();
            for (UUID member : members.keySet()) {
                if (!consented.contains(member)) waiting.add(member);
            }
            return waiting;
        }

        public boolean isComplete() {
            return consented.size() == members.size();
        }

        /** Everyone except one member — who to tell when that member answers or leaves. */
        public List<UUID> othersThan(UUID member) {
            List<UUID> others = new ArrayList<>(members.keySet());
            others.remove(member);
            return others;
        }
    }

    /** Every party, keyed by each of its members, so one lookup answers for any of them. */
    private final Map<UUID, Party> byMember = new ConcurrentHashMap<>();

    private final LongSupplier clock;

    public SharedBeginnings() {
        this(System::currentTimeMillis);
    }

    /** Direct constructor, used by tests that need to move time by hand. */
    public SharedBeginnings(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Opens a party. The initiator has consented by asking; everybody else has not.
     *
     * @param invited who is being asked, by id and by the name they are using
     * @return the party, ready to be announced to the people it names
     */
    public Party open(UUID initiator, String initiatorName, String proposedName,
                      Map<UUID, String> invited, int expirySeconds) {
        sweep();

        Party party = new Party(initiator, initiatorName, proposedName,
            clock.getAsLong() + expirySeconds * 1000L);
        party.members.put(initiator, initiatorName);
        party.members.putAll(invited);
        party.consented.add(initiator);

        for (UUID member : party.members.keySet()) {
            byMember.put(member, party);
        }
        return party;
    }

    /** The party this player is deciding about, in either role, or null. */
    public Party partyOf(UUID member) {
        sweep();
        return byMember.get(member);
    }

    /**
     * Records one member's agreement.
     *
     * @param chosenName what they want their new path called, or null to take the proposed one
     * @return the party if that was the last answer needed, otherwise null
     */
    public Party consent(UUID member, String chosenName) {
        Party party = partyOf(member);
        if (party == null) return null;

        party.consented.add(member);
        if (chosenName != null) {
            party.chosenNames.put(member, chosenName);
        }
        return party.isComplete() ? party : null;
    }

    /**
     * Ends a party outright, whoever caused it.
     *
     * <p>There is no partial version of this. A beginning that three people agreed to is not the
     * same beginning with two, so one refusal — or one disconnection — ends the offer for everyone
     * and it has to be made again.
     *
     * @return the party that was cancelled, so its members can be told, or null if there was none
     */
    public Party cancel(UUID member) {
        Party party = byMember.get(member);
        if (party == null) return null;
        for (UUID one : party.members.keySet()) {
            byMember.remove(one, party);
        }
        return party;
    }

    /** How many parties are open. Used by tests and by nothing else. */
    public int size() {
        sweep();
        return new LinkedHashSet<>(byMember.values()).size();
    }

    /** Drops what has run out. Cheap, and run on every question rather than on a timer. */
    private void sweep() {
        long now = clock.getAsLong();
        byMember.values().removeIf(party -> party.expiresAtMillis <= now);
    }
}
