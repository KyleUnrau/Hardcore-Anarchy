package dev.unrau.samsara.path;

import java.util.Locale;
import java.util.Set;

/**
 * What a path may be called.
 *
 * <p>A path name is not decoration. It is the only handle every command has on an existence a
 * player may not have visited for months, typed under pressure, sometimes into a command that
 * destroys the thing it names. So the rules are tight on purpose: short enough to read in a list,
 * plain enough to type without looking, and impossible to confuse with the word next to it.
 *
 * <p>Two exclusions are worth stating out loud. Names are compared without regard to case, so
 * {@code Home} and {@code home} are one path and not two — nobody should be able to lose an
 * existence to the shift key. And the subcommand words are refused, because
 * {@code /path abandon switch} reads as a mistake even when it is not one.
 */
public final class PathNames {

    public static final int MIN_LENGTH = 1;
    public static final int MAX_LENGTH = 16;

    /**
     * Words a path may not be called, because a command line holding one is ambiguous to a person
     * even when it is not ambiguous to the parser.
     */
    private static final Set<String> RESERVED = Set.of(
        "switch", "new", "accept", "decline", "cancel", "rename", "abandon", "list", "help",
        "confirm"
    );

    private PathNames() {
    }

    /**
     * Why this name cannot be used, phrased to be read straight back to the player after "because",
     * or null if it can.
     */
    public static String rejectionFor(String raw) {
        if (raw == null) return "it is missing";

        String name = raw.trim();
        if (name.length() < MIN_LENGTH) return "it is empty";
        if (name.length() > MAX_LENGTH) {
            return "it is longer than " + MAX_LENGTH + " characters";
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!allowed) {
                return "'" + c + "' is not a letter, a digit, an underscore or a hyphen";
            }
        }
        if (RESERVED.contains(name.toLowerCase(Locale.ROOT))) {
            return "'" + name + "' is one of the words /path itself uses";
        }
        return null;
    }

    /** The name as it will be stored: trimmed, and otherwise exactly as the player typed it. */
    public static String normalise(String raw) {
        return raw == null ? "" : raw.trim();
    }
}
