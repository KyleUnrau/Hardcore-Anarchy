package dev.unrau.samsara.social;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Stops private messages being used to shout at the whole server, without getting in the way of two
 * people talking.
 *
 * <p>A flat cooldown would be the easy thing to write and the wrong thing to have. It costs a
 * spammer a loop with a sleep in it and costs everybody else the ability to hold a conversation, so
 * the one behaviour it reliably prevents is the one nobody was complaining about. What actually
 * distinguishes abuse is its <em>shape</em>: many different recipients in a short time, or the same
 * line delivered to a series of people who did not ask for it. Those are what this counts.
 *
 * <p><b>Messages to contacts are exempt from both shape rules.</b> A contact is a relationship both
 * players consented to, so talking to eight of them in a minute is a conversation and not a
 * broadcast. What is left applying to everybody is the crude flood ceiling — an interval between
 * messages measured in fractions of a second, and a total per window that ordinary typing cannot
 * reach — because those describe a macro rather than a person.
 *
 * <p>Pure: it is given its limits and its clock, so the rules can be tested at whatever speed the
 * test likes.
 */
public class MessageRateLimiter {

    /** The tuning, read fresh on each call so {@code /samsara reload} takes effect immediately. */
    public record Limits(int minIntervalMillis, int windowSeconds, int maxUniqueRecipients,
                         int maxDuplicateRecipients, int maxMessages) {}

    /** Why a message was not sent, or {@link #ALLOWED} when it was. */
    public enum Verdict {
        ALLOWED,
        /** Two messages closer together than a person types. */
        TOO_FAST,
        /** More messages in the window than a conversation contains. */
        TOO_MANY_MESSAGES,
        /** Reaching for one stranger after another. */
        TOO_MANY_RECIPIENTS,
        /** The same line, handed round. */
        DUPLICATE_BROADCAST
    }

    /** One message that was allowed through, and what it was. */
    private record Sent(long atMillis, UUID recipient, String fingerprint, boolean toContact) {}

    private static final class Sender {
        final Deque<Sent> recent = new ArrayDeque<>();
        long lastSentMillis = Long.MIN_VALUE;
    }

    private final Supplier<Limits> limits;
    private final LongSupplier clock;
    private final Map<UUID, Sender> senders = new ConcurrentHashMap<>();

    public MessageRateLimiter(Supplier<Limits> limits) {
        this(limits, System::currentTimeMillis);
    }

    /** Direct constructor, used by tests that need to move time by hand. */
    public MessageRateLimiter(Supplier<Limits> limits, LongSupplier clock) {
        this.limits = limits;
        this.clock = clock;
    }

    /**
     * Judges a message and, if it passes, remembers it.
     *
     * @param toContact whether the recipient is one of the sender's contacts, which exempts the
     *                  message from the rules about reaching many people
     */
    public synchronized Verdict attempt(UUID sender, UUID recipient, String message, boolean toContact) {
        Limits current = limits.get();
        long now = clock.getAsLong();

        Sender state = senders.computeIfAbsent(sender, key -> new Sender());
        prune(state, now - current.windowSeconds() * 1000L);

        // Written as a comparison against a deadline rather than as a subtraction: a sender who has
        // never sent anything carries Long.MIN_VALUE, and now minus that overflows into a negative
        // number, which would refuse everybody's very first message.
        if (state.lastSentMillis > now - current.minIntervalMillis()) {
            return Verdict.TOO_FAST;
        }
        if (state.recent.size() >= current.maxMessages()) {
            return Verdict.TOO_MANY_MESSAGES;
        }

        String fingerprint = fingerprint(message);

        if (!toContact) {
            Set<UUID> strangers = new HashSet<>();
            Set<UUID> sameLine = new HashSet<>();
            for (Sent sent : state.recent) {
                if (sent.toContact()) continue;
                strangers.add(sent.recipient());
                if (sent.fingerprint().equals(fingerprint)) {
                    sameLine.add(sent.recipient());
                }
            }

            strangers.add(recipient);
            if (strangers.size() > current.maxUniqueRecipients()) {
                return Verdict.TOO_MANY_RECIPIENTS;
            }

            sameLine.add(recipient);
            if (sameLine.size() > current.maxDuplicateRecipients()) {
                return Verdict.DUPLICATE_BROADCAST;
            }
        }

        state.recent.addLast(new Sent(now, recipient, fingerprint, toContact));
        state.lastSentMillis = now;
        return Verdict.ALLOWED;
    }

    /** Drops a player's history. Called when they leave, so this never becomes a leak. */
    public void forget(UUID sender) {
        senders.remove(sender);
    }

    private void prune(Sender state, long before) {
        while (!state.recent.isEmpty() && state.recent.peekFirst().atMillis() < before) {
            state.recent.removeFirst();
        }
    }

    /**
     * What makes two messages "the same".
     *
     * <p>Case, punctuation and spacing are dropped, because the difference between
     * {@code join my base!!} and {@code Join   my base} is one line of code to a spammer and no
     * difference at all to the six people receiving it.
     */
    private static String fingerprint(String message) {
        StringBuilder builder = new StringBuilder(message.length());
        for (char character : message.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(character)) {
                builder.append(character);
            }
        }
        return builder.toString();
    }
}
