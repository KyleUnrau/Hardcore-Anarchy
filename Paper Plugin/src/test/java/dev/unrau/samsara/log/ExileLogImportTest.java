package dev.unrau.samsara.log;

import dev.unrau.samsara.data.JournalEntry;
import dev.unrau.samsara.data.PlayerDataStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The history in an old exile-log.csv belongs to the players it names. Importing it is what makes
 * removing the file honest: nothing that was written down is thrown away.
 */
class ExileLogImportTest {

    private static final UUID STEVE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ALEX = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @TempDir
    Path dataFolder;

    private PlayerDataStore store;
    private ExileLogImport importer;

    @BeforeEach
    void setUp() {
        store = new PlayerDataStore(dataFolder.resolve("playerdata").toFile(),
            Logger.getLogger(ExileLogImportTest.class.getName()));
        importer = new ExileLogImport(store, Logger.getLogger(ExileLogImportTest.class.getName()));
    }

    private void writeLog(String body) throws IOException {
        Files.writeString(dataFolder.resolve(ExileLogImport.LEGACY_LOG_NAME),
            "timestamp,reason,uuid,player,world,x,y,z\n" + body);
    }

    private int run(int maxEntries) {
        return importer.run(dataFolder.toFile(), maxEntries);
    }

    @Test
    void everyRowGoesToThePlayerItNames() throws IOException {
        writeLog("""
            2026-05-31T14:23:45Z,FIRST_JOIN,11111111-1111-1111-1111-111111111111,Steve,world,123456,64,-78901
            2026-05-31T14:25:12Z,END_DEPART,11111111-1111-1111-1111-111111111111,Steve,world_the_end,7552,65,-4096
            2026-05-31T14:31:08Z,FIRST_JOIN,22222222-2222-2222-2222-222222222222,Alex,world,-4000,71,900
            2026-05-31T14:42:19Z,DEATH,11111111-1111-1111-1111-111111111111,Steve,world,12345,64,-6789
            """);

        assertEquals(4, run(200));

        List<JournalEntry> steve = store.load(STEVE).getJournal();
        assertEquals(3, steve.size());
        assertEquals(JournalEntry.Reason.FIRST_JOIN, steve.get(0).reason());
        assertEquals("world", steve.get(0).world());
        assertEquals(123456, steve.get(0).x());
        assertEquals(JournalEntry.Reason.END_DEPART, steve.get(1).reason());
        assertEquals("world_the_end", steve.get(1).world());
        assertEquals(JournalEntry.Reason.DEATH, steve.get(2).reason());

        List<JournalEntry> alex = store.load(ALEX).getJournal();
        assertEquals(1, alex.size());
        assertEquals("Alex", alex.get(0).playerName());
        assertEquals(-4000, alex.get(0).x());
    }

    @Test
    void theLogIsRetiredSoNothingReadsItAgain() throws IOException {
        writeLog("2026-05-31T14:23:45Z,FIRST_JOIN,11111111-1111-1111-1111-111111111111,Steve,world,1,2,3\n");

        run(200);

        assertFalse(Files.exists(dataFolder.resolve(ExileLogImport.LEGACY_LOG_NAME)));
        assertTrue(Files.exists(dataFolder.resolve(ExileLogImport.LEGACY_LOG_NAME + ".imported")));
    }

    @Test
    void aSecondImportOfTheSameRowsAddsNothing() throws IOException {
        String rows = """
            2026-05-31T14:23:45Z,FIRST_JOIN,11111111-1111-1111-1111-111111111111,Steve,world,1,2,3
            2026-05-31T14:42:19Z,DEATH,11111111-1111-1111-1111-111111111111,Steve,world,4,5,6
            """;
        writeLog(rows);
        assertEquals(2, run(200));

        // A restored backup, or a rename that failed the first time round.
        writeLog(rows);
        assertEquals(0, run(200));

        assertEquals(2, store.load(STEVE).getJournal().size());
    }

    @Test
    void importedEntriesJoinWhateverThePlayerAlreadyHasInTimeOrder() throws IOException {
        var data = store.load(STEVE);
        data.addJournalEntry(new JournalEntry(java.time.Instant.parse("2026-06-01T09:00:00Z"),
            JournalEntry.Reason.EXILE_RESPAWN, "Steve", "world", 7, 7, 7), 200);
        store.save(STEVE, data);

        writeLog("2026-05-31T14:23:45Z,FIRST_JOIN,11111111-1111-1111-1111-111111111111,Steve,world,1,2,3\n");
        assertEquals(1, run(200));

        List<JournalEntry> journal = store.load(STEVE).getJournal();
        assertEquals(2, journal.size());
        assertEquals(JournalEntry.Reason.FIRST_JOIN, journal.get(0).reason(), "older entries come first");
        assertEquals(JournalEntry.Reason.EXILE_RESPAWN, journal.get(1).reason());
    }

    @Test
    void aDamagedRowIsSkippedAndTheRestStillArrives() throws IOException {
        writeLog("""
            2026-05-31T14:23:45Z,FIRST_JOIN,11111111-1111-1111-1111-111111111111,Steve,world,1,2,3
            not-a-timestamp,DEATH,11111111-1111-1111-1111-111111111111,Steve,world,1,2,3
            2026-05-31T14:30:00Z,DEATH,not-a-uuid,Steve,world,1,2,3
            2026-05-31T14:31:00Z,SOMETHING_ELSE,11111111-1111-1111-1111-111111111111,Steve,world,1,2,3
            2026-05-31T14:32:00Z,DEATH,11111111-1111-1111-1111-111111111111,Steve,world,x,y,z
            2026-05-31T14:33:00Z,DEATH,11111111-1111-1111-1111-111111111111
            2026-05-31T14:42:19Z,DEATH,11111111-1111-1111-1111-111111111111,Steve,world,4,5,6
            """);

        assertEquals(2, run(200));
        assertEquals(2, store.load(STEVE).getJournal().size());
    }

    @Test
    void onlyTheMostRecentEntriesAreKeptWhenThereIsALimit() throws IOException {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            rows.append("2026-05-31T14:").append(String.format("%02d", i))
                .append(":00Z,DEATH,11111111-1111-1111-1111-111111111111,Steve,world,")
                .append(i).append(",64,0\n");
        }
        writeLog(rows.toString());

        run(3);

        List<JournalEntry> journal = store.load(STEVE).getJournal();
        assertEquals(3, journal.size());
        assertEquals(7, journal.get(0).x());
        assertEquals(9, journal.get(2).x());
    }

    @Test
    void noLogMeansNothingHappens() {
        assertEquals(0, run(200));
        assertFalse(Files.exists(dataFolder.resolve(ExileLogImport.LEGACY_LOG_NAME + ".imported")));
    }
}
