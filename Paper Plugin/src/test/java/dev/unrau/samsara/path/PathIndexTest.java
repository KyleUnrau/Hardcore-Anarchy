package dev.unrau.samsara.path;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The list of a player's existences, and the one rule that holds the whole feature up: there is
 * always at least one path and exactly one of them is being walked.
 */
class PathIndexTest {

    @Test
    void anAccountBeginsWithOnePathAndIsWalkingIt() {
        PlayerPath original = PlayerPath.beginning("Original");
        PathIndex index = PathIndex.beginningWith(original);

        assertEquals(1, index.size());
        assertSame(original, index.active());
        assertEquals(original.id(), index.activePathId());
        assertTrue(index.dormant().isEmpty());
    }

    /** Nobody should be able to lose an existence to the shift key. */
    @Test
    void namesAreMatchedWithoutRegardToCase() {
        PathIndex index = PathIndex.beginningWith(PlayerPath.beginning("Original"));

        assertEquals("Original", index.byName("original").name());
        assertEquals("Original", index.byName("ORIGINAL").name());
        assertNull(index.byName("Origina"));
        assertNull(index.byName(null));
    }

    @Test
    void aNameIsTakenUnlessItIsHeldByThePathBeingRenamed() {
        PlayerPath original = PlayerPath.beginning("Original");
        PlayerPath second = PlayerPath.beginning("North");
        PathIndex index = PathIndex.beginningWith(original);
        index.add(second);

        assertTrue(index.nameTaken("north", null));
        assertTrue(index.nameTaken("North", original));
        // Renaming North to North is a no-op, not a collision.
        assertFalse(index.nameTaken("North", second));
        assertFalse(index.nameTaken("South", null));
    }

    @Test
    void dormantIsEveryPathExceptTheOneBeingWalked() {
        PlayerPath original = PlayerPath.beginning("Original");
        PlayerPath north = PlayerPath.beginning("North");
        PlayerPath south = PlayerPath.beginning("South");

        PathIndex index = PathIndex.beginningWith(original);
        index.add(north);
        index.add(south);

        assertEquals(List.of(north, south), index.dormant());

        index.setActivePathId(north.id());
        assertEquals(List.of(original, south), index.dormant());
    }

    /**
     * An index naming a path it does not hold is the one broken state worth recognising: the player
     * is standing in the world as nobody. {@code PathService} repairs it; this is what it looks for.
     */
    @Test
    void anActivePathThatIsNotHeldReadsAsNoActivePath() {
        PlayerPath original = PlayerPath.beginning("Original");
        PathIndex index = PathIndex.beginningWith(original);

        index.remove(original);
        assertNull(index.active());
        assertTrue(index.isEmpty());
    }

    @Test
    void renamingKeepsTheIdentityTheFilesAreKeyedBy() {
        PlayerPath path = PlayerPath.beginning("Original");
        var id = path.id();

        path.rename("Elsewhere");

        assertEquals(id, path.id());
        assertEquals("Elsewhere", path.name());
        assertTrue(path.isNamed("elsewhere"));
        assertFalse(path.isNamed("Original"));
    }
}
