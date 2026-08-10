package dev.unrau.samsara.log;

import dev.unrau.samsara.data.JournalEntry;
import dev.unrau.samsara.data.PlayerData;
import dev.unrau.samsara.data.PlayerDataStore;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Moves the history in an old {@code exile-log.csv} onto the players it describes, once.
 *
 * <p>Every row of that file already names the player it belongs to, so nothing is guessed: each
 * row becomes a journal entry on one player's record. When the file has been read it is renamed
 * rather than deleted — the plugin is finished with it, but the administrator gets to decide
 * whether the original is worth keeping.
 */
public class ExileLogImport {

    /** The file written by versions before the journal existed. */
    public static final String LEGACY_LOG_NAME = "exile-log.csv";

    private static final String IMPORTED_SUFFIX = ".imported";

    private final PlayerDataStore store;
    private final Logger logger;

    public ExileLogImport(PlayerDataStore store, Logger logger) {
        this.store = store;
        this.logger = logger;
    }

    /**
     * Imports the log if it is there, and does nothing at all if it is not.
     *
     * @param maxEntries how many entries each player keeps, oldest dropped first; zero keeps all
     * @return the number of entries imported
     */
    public int run(File dataFolder, int maxEntries) {
        File log = new File(dataFolder, LEGACY_LOG_NAME);
        if (!log.isFile()) return 0;

        List<String> lines;
        try {
            lines = Files.readAllLines(log.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not read " + LEGACY_LOG_NAME
                + "; it has been left alone and no history was imported.", e);
            return 0;
        }

        // Grouped so each player's file is opened once, however many rows they have.
        Map<UUID, List<JournalEntry>> byPlayer = new LinkedHashMap<>();
        int unreadable = 0;
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("timestamp,")) continue;

            Row row = parse(line);
            if (row == null) {
                unreadable++;
                continue;
            }
            byPlayer.computeIfAbsent(row.uuid(), id -> new ArrayList<>()).add(row.entry());
        }

        int imported = 0;
        for (Map.Entry<UUID, List<JournalEntry>> player : byPlayer.entrySet()) {
            imported += merge(player.getKey(), player.getValue(), maxEntries);
        }

        logger.info("Imported " + imported + " journal "
            + (imported == 1 ? "entry" : "entries") + " from " + LEGACY_LOG_NAME + " for "
            + byPlayer.size() + " " + (byPlayer.size() == 1 ? "player" : "players")
            + ". History now lives on each player's own record in playerdata/.");
        if (unreadable > 0) {
            logger.warning("Skipped " + unreadable + " unreadable "
                + (unreadable == 1 ? "row" : "rows") + " in " + LEGACY_LOG_NAME + ".");
        }
        retire(log);
        return imported;
    }

    /**
     * Folds imported entries into whatever the player's record already holds, in time order and
     * without duplicating anything an earlier run put there.
     *
     * @return how many entries were actually added
     */
    private int merge(UUID uuid, List<JournalEntry> imported, int maxEntries) {
        PlayerData data = store.load(uuid);
        List<JournalEntry> combined = new ArrayList<>(data.getJournal());

        int added = 0;
        for (JournalEntry entry : imported) {
            if (combined.contains(entry)) continue;
            combined.add(entry);
            added++;
        }
        if (added == 0) return 0;

        combined.sort(Comparator.comparing(JournalEntry::at));
        data.setJournal(combined, maxEntries);
        store.save(uuid, data);
        return added;
    }

    /** Renames the log out of the way, so a restart cannot import it twice. */
    private void retire(File log) {
        File retired = new File(log.getParentFile(), LEGACY_LOG_NAME + IMPORTED_SUFFIX);
        if (retired.exists()) {
            retired = new File(log.getParentFile(),
                LEGACY_LOG_NAME + IMPORTED_SUFFIX + "-" + Instant.now().toEpochMilli());
        }
        if (log.renameTo(retired)) {
            logger.info("Renamed " + LEGACY_LOG_NAME + " to " + retired.getName()
                + ". Nothing writes to it any more; delete it whenever you like.");
        } else {
            logger.warning("Could not rename " + LEGACY_LOG_NAME + " after importing it."
                + " Move or delete it by hand, or its rows will be read again on the next start"
                + " (already-imported entries are recognised and skipped).");
        }
    }

    /** One CSV row: {@code timestamp,reason,uuid,player,world,x,y,z}. */
    private record Row(UUID uuid, JournalEntry entry) {}

    private Row parse(String line) {
        String[] fields = line.split(",", -1);
        if (fields.length < 8) return null;

        Instant at = parseInstant(fields[0]);
        JournalEntry.Reason reason = JournalEntry.Reason.parse(fields[1]);
        if (at == null || reason == null) return null;

        UUID uuid;
        try {
            uuid = UUID.fromString(fields[2].trim());
        } catch (IllegalArgumentException e) {
            return null;
        }

        long x, y, z;
        try {
            x = Long.parseLong(fields[5].trim());
            y = Long.parseLong(fields[6].trim());
            z = Long.parseLong(fields[7].trim());
        } catch (NumberFormatException e) {
            return null;
        }

        return new Row(uuid, new JournalEntry(at, reason, fields[3].trim(), fields[4].trim(), x, y, z));
    }

    private static Instant parseInstant(String raw) {
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeException e) {
            return null;
        }
    }
}
