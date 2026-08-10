package dev.unrau.samsara.social;

import dev.unrau.samsara.config.SocialConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The promise this whole system rests on is that a contact cannot be one-sided.
 *
 * <p>If it could, "adding" somebody would be a way to watch a player who never agreed to be
 * watched — their chat, their deaths, their comings and goings — from anywhere on the map. So these
 * tests pin both halves of every change: that making a contact writes both records, that removing
 * one clears both, and that a removal cannot be undone by the two of them happening to stand in the
 * same room afterwards.
 */
class SocialGraphTest {

    @TempDir
    Path tempDir;

    private SocialStore store;
    private SocialGraph graph;
    private UUID alice;
    private UUID bob;

    @BeforeEach
    void setUp() {
        Logger logger = Logger.getLogger(SocialGraphTest.class.getName());
        store = new SocialStore(tempDir.toFile(), logger);
        SocialConfig settings = SocialConfig.from(null, logger);
        graph = new SocialGraph(store, () -> settings);
        alice = UUID.randomUUID();
        bob = UUID.randomUUID();
    }

    @Test
    void linkingWritesBothSides() {
        assertEquals(SocialGraph.LinkOutcome.LINKED, graph.link(alice, "Alice", bob, "Bob"));

        assertTrue(store.load(alice).isContact(bob));
        assertTrue(store.load(bob).isContact(alice), "a contact the other player does not hold is a"
            + " subscription, and this system does not have those");
    }

    @Test
    void linkingIsIdempotent() {
        graph.link(alice, "Alice", bob, "Bob");
        assertEquals(SocialGraph.LinkOutcome.ALREADY_CONTACTS, graph.link(alice, "Alice", bob, "Bob"));
        assertEquals(1, store.load(alice).contactCount());
    }

    @Test
    void eitherSideCanEndIt() {
        graph.link(alice, "Alice", bob, "Bob");

        // Bob removes Alice; Alice never agreed to that and does not have to.
        assertTrue(graph.unlink(bob, alice));

        assertFalse(store.load(alice).isContact(bob), "removal must not leave the other half"
            + " still seeing them");
        assertFalse(store.load(bob).isContact(alice));
    }

    @Test
    void removalSuppressesAutomaticRecreationOnBothSides() {
        graph.link(alice, "Alice", bob, "Bob");
        graph.unlink(alice, bob);

        assertTrue(store.load(alice).isAutoSuppressed(bob));
        assertTrue(store.load(bob).isAutoSuppressed(alice),
            "the player who was removed must not be able to rebuild it by standing there");
        assertTrue(graph.isAutoSuppressed(alice, bob));
    }

    @Test
    void aManualContactClearsTheSuppression() {
        graph.link(alice, "Alice", bob, "Bob");
        graph.unlink(alice, bob);

        // Asking again, and being accepted, is exactly the deliberate consent the severance waits for.
        graph.link(alice, "Alice", bob, "Bob");

        assertFalse(store.load(alice).isAutoSuppressed(bob));
        assertFalse(store.load(bob).isAutoSuppressed(alice));
        assertFalse(graph.isAutoSuppressed(alice, bob));
    }

    @Test
    void oneSideMayLiftItsOwnSuppressionAndOnlyItsOwn() {
        graph.link(alice, "Alice", bob, "Bob");
        graph.unlink(alice, bob);

        assertTrue(graph.allowAuto(alice, bob));

        assertFalse(store.load(alice).isAutoSuppressed(bob));
        assertTrue(store.load(bob).isAutoSuppressed(alice),
            "one player cannot decide for both that the falling-out is over");
        assertTrue(graph.isAutoSuppressed(alice, bob));
    }

    @Test
    void unlinkingSomebodyWhoWasNeverAContactStillSeversThePairing() {
        assertFalse(graph.unlink(alice, bob), "there was no contact to remove");
        assertTrue(graph.isAutoSuppressed(alice, bob),
            "saying no to somebody in advance is a decision, not a mistake");
    }

    @Test
    void ignoringIsOneSidedAndDoesNotTouchTheContact() {
        graph.link(alice, "Alice", bob, "Bob");
        assertTrue(graph.ignore(alice, bob, "Bob"));

        assertTrue(graph.ignores(alice, bob));
        assertFalse(graph.ignores(bob, alice), "an ignore is the recipient's own switch");
        assertTrue(graph.areContacts(alice, bob), "quietening somebody is not the same statement as"
            + " ending the relationship");
    }

    @Test
    void relationshipsSurviveTheRecordBeingWrittenAndReadAgain() {
        graph.link(alice, "Alice", bob, "Bob");
        graph.ignore(alice, UUID.randomUUID(), "Someone");
        graph.unlink(alice, bob);
        graph.link(alice, "Alice", bob, "Bob");

        // A restart: nothing held in memory, everything read back off disk.
        SocialStore reopened = new SocialStore(tempDir.toFile(),
            Logger.getLogger(SocialGraphTest.class.getName()));

        assertTrue(reopened.load(alice).isContact(bob));
        assertTrue(reopened.load(bob).isContact(alice));
        assertEquals("Bob", reopened.load(alice).contactName(bob));
        assertEquals(1, reopened.load(alice).getIgnored().size());
        assertFalse(reopened.load(alice).isAutoSuppressed(bob));
    }
}
