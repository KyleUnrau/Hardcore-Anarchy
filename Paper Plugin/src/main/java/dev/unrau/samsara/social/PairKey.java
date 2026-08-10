package dev.unrau.samsara.social;

import java.util.UUID;

/**
 * Two players, in no particular order.
 *
 * <p>Everything the contact system decides is decided about a pair rather than about a direction:
 * whether they are contacts, how long they have spent near each other, whether one of them has cut
 * the other off. Ordering the two ids at construction means one pair has one key however it is
 * written, so none of that can quietly become one-sided.
 */
public record PairKey(UUID first, UUID second) {

    public static PairKey of(UUID a, UUID b) {
        return a.compareTo(b) <= 0 ? new PairKey(a, b) : new PairKey(b, a);
    }
}
