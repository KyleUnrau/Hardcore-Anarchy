package dev.unrau.samsara.path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Beginning together is the one place on this server where two players are deliberately put down in
 * the same square metre. That is only defensible if every one of them asked for it, so what these
 * tests are really about is that nothing happens until the last person has answered — and that one
 * refusal is enough to make sure nothing does.
 */
class SharedBeginningsTest {

    private AtomicLong clock;
    private SharedBeginnings beginnings;

    private final UUID steve = UUID.randomUUID();
    private final UUID alex = UUID.randomUUID();
    private final UUID herobrine = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        clock = new AtomicLong(1_000_000L);
        beginnings = new SharedBeginnings(clock::get);
    }

    private SharedBeginnings.Party openWithBoth() {
        Map<UUID, String> invited = new LinkedHashMap<>();
        invited.put(alex, "Alex");
        invited.put(herobrine, "Herobrine");
        return beginnings.open(steve, "Steve", "Together", invited, 120);
    }

    @Test
    void askingCountsAsAgreeingButNobodyElseHas() {
        SharedBeginnings.Party party = openWithBoth();

        assertTrue(party.hasConsented(steve));
        assertFalse(party.hasConsented(alex));
        assertFalse(party.isComplete());
        assertEquals(3, party.members().size());
        assertEquals(2, party.outstanding().size());
    }

    @Test
    void oneLookupAnswersForEveryMember() {
        SharedBeginnings.Party party = openWithBoth();

        assertSame(party, beginnings.partyOf(steve));
        assertSame(party, beginnings.partyOf(alex));
        assertSame(party, beginnings.partyOf(herobrine));
        assertNull(beginnings.partyOf(UUID.randomUUID()));
    }

    @Test
    void itCompletesOnlyOnTheLastAnswer() {
        openWithBoth();

        assertNull(beginnings.consent(alex, null), "two of three is not everybody");

        SharedBeginnings.Party complete = beginnings.consent(herobrine, null);
        assertNotNull(complete);
        assertTrue(complete.isComplete());
    }

    /** The proposed name is a suggestion: a member may already hold a path called that. */
    @Test
    void eachMemberMayNameTheirOwnPath() {
        SharedBeginnings.Party party = openWithBoth();

        beginnings.consent(alex, null);
        beginnings.consent(herobrine, "Second");

        assertEquals("Together", party.chosenNameOf(alex));
        assertEquals("Together", party.chosenNameOf(steve));
        assertEquals("Second", party.chosenNameOf(herobrine));
    }

    /**
     * A beginning three people agreed to is not the same beginning with two, so one refusal ends the
     * offer for everybody rather than quietly shrinking it.
     */
    @Test
    void oneRefusalEndsItForEveryone() {
        openWithBoth();

        SharedBeginnings.Party cancelled = beginnings.cancel(herobrine);

        assertNotNull(cancelled);
        assertNull(beginnings.partyOf(steve));
        assertNull(beginnings.partyOf(alex));
        assertNull(beginnings.partyOf(herobrine));
        assertEquals(0, beginnings.size());
    }

    @Test
    void anOfferLapsesOnItsOwn() {
        openWithBoth();

        clock.addAndGet(119_000L);
        assertNotNull(beginnings.partyOf(alex));

        clock.addAndGet(2_000L);
        assertNull(beginnings.partyOf(alex));
        assertNull(beginnings.consent(alex, null));
        assertEquals(0, beginnings.size());
    }

    @Test
    void everyoneExceptOneMemberIsWhoToTell() {
        SharedBeginnings.Party party = openWithBoth();

        assertEquals(2, party.othersThan(steve).size());
        assertFalse(party.othersThan(steve).contains(steve));
        assertTrue(party.othersThan(steve).contains(alex));
    }

    @Test
    void cancellingSomethingThatWasNeverOfferedSaysSo() {
        assertNull(beginnings.cancel(steve));
        assertNull(beginnings.consent(steve, "Whatever"));
    }
}
