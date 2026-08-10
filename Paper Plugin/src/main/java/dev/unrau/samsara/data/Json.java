package dev.unrau.samsara.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The JSON the plugin keeps on disk: how it is written, and how it is read back from a file that
 * may have been edited by hand, truncated by a crash, or written by a version that did not have the
 * field being asked for.
 *
 * <p>Every reader here answers rather than throws. A record that is missing a key, or holds the
 * wrong kind of value under it, is a record with a gap in it — and a gap must cost the caller a
 * default, never an exception on the main thread in the middle of somebody's login.
 */
public final class Json {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private Json() {
    }

    // -------------------------------------------------------------------------
    // Files
    // -------------------------------------------------------------------------

    /** Parses a file, leaving it to the caller to decide what an unexpected shape means. */
    public static JsonElement parse(File file) throws IOException, JsonParseException {
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader);
        }
    }

    /**
     * Writes to a temporary file and moves it into place, so a server that dies mid-write leaves the
     * previous record intact rather than half a record.
     *
     * <p>Half a record is worse than none of one everywhere this is used. A truncated player record
     * reads as a player who has never joined, and exiles somebody who was standing still; a
     * truncated path index reads as somebody with no paths at all.
     *
     * @param subject what this file is, for the log line if it cannot be written
     * @return true if the file is now on disk
     */
    public static boolean writeAtomically(File file, JsonObject root, Logger logger, String subject) {
        Path target = file.toPath();
        Path temp = target.resolveSibling(file.getName() + ".tmp");
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
                writer.write(System.lineSeparator());
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to save " + subject, e);
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // Nothing more can be done here; the next save overwrites it.
            }
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Values
    // -------------------------------------------------------------------------

    public static JsonObject objectOrNull(JsonObject root, String key) {
        JsonElement element = root.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    public static String stringOrNull(JsonObject root, String key) {
        JsonElement element = root.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    public static String stringOr(JsonObject root, String key, String fallback) {
        String value = stringOrNull(root, key);
        return value != null ? value : fallback;
    }

    /** A uuid written as a string, or null if it is absent or is not one. */
    public static UUID uuidOrNull(JsonObject root, String key) {
        String raw = stringOrNull(root, key);
        if (raw == null) return null;
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static boolean boolOr(JsonObject root, String key, boolean fallback) {
        JsonElement element = root.get(key);
        if (element == null || !element.isJsonPrimitive()) return fallback;
        try {
            return element.getAsBoolean();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public static int intOr(JsonObject root, String key, int fallback) {
        return (int) longOr(root, key, fallback);
    }

    public static long longOr(JsonObject root, String key, long fallback) {
        JsonElement element = root.get(key);
        if (element == null || !element.isJsonPrimitive()) return fallback;
        try {
            return element.getAsLong();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public static double doubleOr(JsonObject root, String key, double fallback) {
        JsonElement element = root.get(key);
        if (element == null || !element.isJsonPrimitive()) return fallback;
        try {
            return element.getAsDouble();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public static float floatOr(JsonObject root, String key, float fallback) {
        return (float) doubleOr(root, key, fallback);
    }
}
