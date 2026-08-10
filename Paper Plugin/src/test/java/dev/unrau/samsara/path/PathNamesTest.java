package dev.unrau.samsara.path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A path name is the only handle any command has on an existence, including the command that
 * destroys one. What it may be is therefore a rule and not a preference.
 */
class PathNamesTest {

    @Test
    void ordinaryNamesAreAccepted() {
        assertNull(PathNames.rejectionFor("Original"));
        assertNull(PathNames.rejectionFor("north-base"));
        assertNull(PathNames.rejectionFor("Alt_2"));
        assertNull(PathNames.rejectionFor("a"));
    }

    @Test
    void surroundingWhitespaceIsNotPartOfTheName() {
        assertNull(PathNames.rejectionFor("  Original  "));
        assertEquals("Original", PathNames.normalise("  Original  "));
    }

    @Test
    void emptyAndMissingNamesAreRefused() {
        assertNotNull(PathNames.rejectionFor(null));
        assertNotNull(PathNames.rejectionFor(""));
        assertNotNull(PathNames.rejectionFor("   "));
    }

    @Test
    void namesTooLongToReadInAListAreRefused() {
        assertNull(PathNames.rejectionFor("x".repeat(PathNames.MAX_LENGTH)));
        assertNotNull(PathNames.rejectionFor("x".repeat(PathNames.MAX_LENGTH + 1)));
    }

    /**
     * Spaces are the important one. {@code /path abandon my base} would otherwise read as a path
     * called "my" followed by a word the parser is free to ignore.
     */
    @Test
    void anythingThatWouldConfuseACommandLineIsRefused() {
        assertNotNull(PathNames.rejectionFor("my base"));
        assertNotNull(PathNames.rejectionFor("home!"));
        assertNotNull(PathNames.rejectionFor("§cred"));
        assertNotNull(PathNames.rejectionFor("a/b"));
    }

    @Test
    void theCommandsOwnWordsAreRefusedInAnyCase() {
        assertNotNull(PathNames.rejectionFor("switch"));
        assertNotNull(PathNames.rejectionFor("Abandon"));
        assertNotNull(PathNames.rejectionFor("CONFIRM"));
    }
}
