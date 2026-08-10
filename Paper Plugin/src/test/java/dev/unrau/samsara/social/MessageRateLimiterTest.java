package dev.unrau.samsara.social;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The line between a conversation and a broadcast.
 *
 * <p>The failure mode worth testing for is not the spammer getting through — it is the ordinary
 * player being stopped. A limiter that blocks somebody mid-sentence has taken chat away from the
 * people it was meant to protect, so the first tests here are the ones that must always pass.
 */
class MessageRateLimiterTest {

    private static final MessageRateLimiter.Limits LIMITS =
        new MessageRateLimiter.Limits(200, 60, 3, 2, 20);

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final MessageRateLimiter limiter = new MessageRateLimiter(() -> LIMITS, clock::get);
    private final UUID sender = UUID.randomUUID();

    private MessageRateLimiter.Verdict send(UUID to, String message, boolean contact) {
        clock.addAndGet(1_000);
        return limiter.attempt(sender, to, message, contact);
    }

    @Test
    void aBackAndForthWithOnePersonIsNeverInterrupted() {
        UUID friend = UUID.randomUUID();
        for (int i = 0; i < 15; i++) {
            assertEquals(MessageRateLimiter.Verdict.ALLOWED, send(friend, "line " + i, false),
                "two people talking must not run into a limit");
        }
    }

    @Test
    void talkingToManyContactsIsAConversationAndNotABroadcast() {
        for (int i = 0; i < 10; i++) {
            assertEquals(MessageRateLimiter.Verdict.ALLOWED,
                send(UUID.randomUUID(), "we're at the base", true),
                "a contact is a relationship both players agreed to");
        }
    }

    @Test
    void reachingForOneStrangerAfterAnotherIsStopped() {
        assertEquals(MessageRateLimiter.Verdict.ALLOWED, send(UUID.randomUUID(), "hello", false));
        assertEquals(MessageRateLimiter.Verdict.ALLOWED, send(UUID.randomUUID(), "hi there", false));
        assertEquals(MessageRateLimiter.Verdict.ALLOWED, send(UUID.randomUUID(), "good day", false));

        assertEquals(MessageRateLimiter.Verdict.TOO_MANY_RECIPIENTS,
            send(UUID.randomUUID(), "greetings", false));
    }

    @Test
    void theSameLineHandedRoundIsStoppedSooner() {
        assertEquals(MessageRateLimiter.Verdict.ALLOWED, send(UUID.randomUUID(), "join my base", false));
        assertEquals(MessageRateLimiter.Verdict.ALLOWED, send(UUID.randomUUID(), "join my base", false));

        assertEquals(MessageRateLimiter.Verdict.DUPLICATE_BROADCAST,
            send(UUID.randomUUID(), "join my base", false));
    }

    @Test
    void punctuationAndCaseAreNotADisguise() {
        assertEquals(MessageRateLimiter.Verdict.ALLOWED, send(UUID.randomUUID(), "Join my base!", false));
        assertEquals(MessageRateLimiter.Verdict.ALLOWED, send(UUID.randomUUID(), "join   my base", false));

        assertEquals(MessageRateLimiter.Verdict.DUPLICATE_BROADCAST,
            send(UUID.randomUUID(), "JOIN MY BASE!!!", false));
    }

    @Test
    void repeatingYourselfToOnePersonIsNotABroadcast() {
        UUID friend = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            assertEquals(MessageRateLimiter.Verdict.ALLOWED, send(friend, "are you there", false),
                "the rule counts recipients, not repetitions");
        }
    }

    @Test
    void aMacroFasterThanTypingIsRefused() {
        UUID friend = UUID.randomUUID();
        assertEquals(MessageRateLimiter.Verdict.ALLOWED, limiter.attempt(sender, friend, "one", false));

        clock.addAndGet(50);
        assertEquals(MessageRateLimiter.Verdict.TOO_FAST, limiter.attempt(sender, friend, "two", false));

        clock.addAndGet(200);
        assertEquals(MessageRateLimiter.Verdict.ALLOWED, limiter.attempt(sender, friend, "two", false));
    }

    @Test
    void theWindowEmptiesAsTimePasses() {
        for (int i = 0; i < 3; i++) {
            assertEquals(MessageRateLimiter.Verdict.ALLOWED, send(UUID.randomUUID(), "hello " + i, false));
        }
        assertEquals(MessageRateLimiter.Verdict.TOO_MANY_RECIPIENTS,
            send(UUID.randomUUID(), "hello again", false));

        clock.addAndGet(61_000);

        assertEquals(MessageRateLimiter.Verdict.ALLOWED, limiter.attempt(sender, UUID.randomUUID(),
            "hello again", false), "a limit is a rate, not a ban");
    }

    @Test
    void oneSendersHistoryIsNotAnother() {
        UUID other = UUID.randomUUID();
        for (int i = 0; i < 4; i++) {
            send(UUID.randomUUID(), "hello " + i, false);
        }

        clock.addAndGet(1_000);
        assertEquals(MessageRateLimiter.Verdict.ALLOWED,
            limiter.attempt(other, UUID.randomUUID(), "hello", false));
    }
}
