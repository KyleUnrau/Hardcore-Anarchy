package dev.unrau.samsara.listener;

import dev.unrau.samsara.listener.EndPortalListener.PearlRoute;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A pearl thrown into an End portal is the one way into the End that never asks the plugin anything:
 * the pearl is the thing in the portal, and vanilla crosses it — and its owner — to the coordinates
 * vanilla likes, which are the ones this server exists to not gather everybody at.
 *
 * <p>What is worth testing without a server attached is the decision itself: which portals are ours,
 * which direction they run in, and whose pearl is allowed to move whom.
 */
class EndPortalListenerTest {

    private static final String OVERWORLD = "world";
    private static final String END = "world_the_end";

    @Test
    void routesAPearlThrownIntoAnOverworldPortal() {
        assertEquals(PearlRoute.ENTER,
            EndPortalListener.routeFor(OVERWORLD, OVERWORLD, OVERWORLD, END));
    }

    @Test
    void refusesAPearlThrownIntoTheExitPortal() {
        // Vanilla would cross the pearl to the Overworld's shared spawn and pull the thrower with it,
        // skipping the bed, the anchor and the record of where this life began.
        assertEquals(PearlRoute.REFUSE,
            EndPortalListener.routeFor(END, END, OVERWORLD, END),
            "the way out of the End is walked into, not thrown at");
    }

    @Test
    void refusesTheExitPortalEvenWhenTheThrowerIsElsewhere() {
        // Refusing only ever cancels a crossing, so it costs nothing to apply it unconditionally.
        assertEquals(PearlRoute.REFUSE,
            EndPortalListener.routeFor(END, OVERWORLD, OVERWORLD, END));
    }

    @Test
    void leavesAThrowerInAnotherWorldAlone() {
        // A journey is only theirs to make from a door they are standing at. Moving an owner who is
        // somewhere else entirely would teleport somebody out of a world they had no reason to leave.
        assertEquals(PearlRoute.IGNORE,
            EndPortalListener.routeFor(OVERWORLD, "creative", OVERWORLD, END));
    }

    @Test
    void leavesUnmanagedWorldsAlone() {
        assertEquals(PearlRoute.IGNORE,
            EndPortalListener.routeFor("resource", "resource", OVERWORLD, END),
            "only the configured Overworld is intercepted");
        assertEquals(PearlRoute.IGNORE,
            EndPortalListener.routeFor("resource_the_end", "resource_the_end", OVERWORLD, END));
    }

    @Test
    void honoursAConfiguredOverworldName() {
        assertEquals(PearlRoute.ENTER,
            EndPortalListener.routeFor("survival", "survival", "survival", "survival_the_end"));
        assertEquals(PearlRoute.IGNORE,
            EndPortalListener.routeFor("world", "world", "survival", "survival_the_end"));
    }
}
