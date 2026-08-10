package dev.unrau.samsara.social;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Consent, and what it costs to refuse.
 *
 * <p>A request that could be re-sent immediately after being declined would make "no" the cheapest
 * thing in the game to ignore, so the refusal has to close the door — in both directions, or it is
 * worked around by asking the other way about.
 */
class ContactRequestsTest {

    private static final int EXPIRY = 300;
    private static final int COOLDOWN = 300;

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final ContactRequests requests = new ContactRequests(clock::get);

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    private ContactRequests.SendOutcome ask(UUID from, UUID to) {
        return requests.send(from, "name", to, EXPIRY, COOLDOWN);
    }

    @Test
    void askingLeavesAQuestionForTheOtherPlayer() {
        assertEquals(ContactRequests.SendOutcome.SENT, ask(alice, bob));

        assertTrue(requests.hasPending(bob, alice));
        assertFalse(requests.hasPending(alice, bob), "asking is not answering");
        assertEquals(1, requests.pendingFor(bob).size());
        assertEquals(1, requests.sentBy(alice).size());
    }

    @Test
    void askingTwiceIsStillOneQuestion() {
        ask(alice, bob);
        assertEquals(ContactRequests.SendOutcome.ALREADY_PENDING, ask(alice, bob));
        assertEquals(1, requests.pendingFor(bob).size());
    }

    @Test
    void askingSomebodyWhoAskedYouIsAnAnswer() {
        ask(alice, bob);
        assertEquals(ContactRequests.SendOutcome.RECIPROCATED, ask(bob, alice),
            "both of them have now said yes; making them ask again would be pedantry");
    }

    @Test
    void aQuestionNobodyAnsweredStopsBeingOne() {
        ask(alice, bob);

        clock.addAndGet(EXPIRY * 1000L + 1);

        assertFalse(requests.hasPending(bob, alice));
        assertTrue(requests.pendingFor(bob).isEmpty());
        assertEquals(ContactRequests.SendOutcome.SENT, ask(alice, bob), "and may be asked again");
    }

    @Test
    void acceptingConsumesTheRequest() {
        ask(alice, bob);

        assertTrue(requests.take(bob, alice));
        assertFalse(requests.take(bob, alice), "an answer is given once");
    }

    @Test
    void aRefusalClosesTheDoorBothWays() {
        ask(alice, bob);
        requests.decline(bob, alice, COOLDOWN);

        assertEquals(ContactRequests.SendOutcome.RECENTLY_DECLINED, ask(alice, bob));
        assertEquals(ContactRequests.SendOutcome.RECENTLY_DECLINED, ask(bob, alice),
            "a cooldown that only bound the original asker would be walked around in one keystroke");
    }

    @Test
    void aRefusalWearsOff() {
        ask(alice, bob);
        requests.decline(bob, alice, COOLDOWN);

        clock.addAndGet(COOLDOWN * 1000L + 1);

        assertEquals(ContactRequests.SendOutcome.SENT, ask(alice, bob),
            "declining is not a permanent block; that is what /ignore is for");
    }

    @Test
    void agreeingAnywayForgetsTheRefusal() {
        requests.decline(bob, alice, COOLDOWN);
        requests.clearDecline(alice, bob);

        assertEquals(ContactRequests.SendOutcome.SENT, ask(alice, bob));
    }

    @Test
    void oneLeavingDropsWhateverWasInFlight() {
        ask(alice, bob);
        requests.forget(alice, bob);

        assertFalse(requests.hasPending(bob, alice));
        assertTrue(requests.sentBy(alice).isEmpty());
    }

    @Test
    void aCooldownOfZeroTurnsRefusalCooldownsOff() {
        ask(alice, bob);
        requests.decline(bob, alice, 0);

        assertEquals(ContactRequests.SendOutcome.SENT, requests.send(alice, "name", bob, EXPIRY, 0));
    }
}
