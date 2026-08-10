package dev.unrau.samsara.data;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Expedition state has to survive a restart: a player who disconnects in the End — or whose server
 * goes down while they are there — must still be able to come home. A player's history has to
 * survive with it, because it is kept in the same file.
 */
class PlayerDataStoreTest {

    @TempDir
    Path tempDir;

    private PlayerDataStore store;
    private UUID playerId;

    @BeforeEach
    void setUp() {
        store = new PlayerDataStore(tempDir.toFile(), Logger.getLogger(PlayerDataStoreTest.class.getName()));
        playerId = UUID.randomUUID();
    }

    private Path recordFor(UUID uuid) {
        return tempDir.resolve(uuid + ".json");
    }

    private static EndExpedition expeditionFor(UUID lifeId) {
        return new EndExpedition(
            lifeId,
            "world", 120_000.5, 28.0, -64_000.5,
            "world", 120_002.5, 29.0, -64_000.5,
            "world_the_end", 7552.5, 65.0, -4096.5,
            "7552,-4096",
            1_700_000_000_000L
        );
    }

    @Test
    void anExpeditionSurvivesAReload() {
        PlayerData saved = new PlayerData();
        saved.setHasJoinedBefore(true);
        UUID lifeId = saved.ensureLifeId();
        saved.openEndExpedition(expeditionFor(lifeId));
        store.save(playerId, saved);

        // A fresh store instance is what a server restart amounts to here.
        PlayerDataStore reopened = new PlayerDataStore(tempDir.toFile(), Logger.getLogger("reopened"));
        PlayerData loaded = reopened.load(playerId);

        EndExpedition expedition = loaded.getActiveExpedition();
        assertNotNull(expedition, "the traveller must still have a way home after a restart");
        assertEquals(lifeId, loaded.getLifeId());
        assertEquals("world", expedition.getReturnWorld());
        assertEquals(120_002.5, expedition.getReturnX());
        assertEquals(29.0, expedition.getReturnY());
        assertEquals(-64_000.5, expedition.getReturnZ());
        assertEquals("world_the_end", expedition.getEndWorld());
        assertEquals("7552,-4096", expedition.getRegionKey());
        assertEquals(1_700_000_000_000L, expedition.getOpenedAt());
    }

    @Test
    void closingAnExpeditionRemovesItFromDisk() throws IOException {
        PlayerData data = new PlayerData();
        UUID lifeId = data.ensureLifeId();
        data.openEndExpedition(expeditionFor(lifeId));
        store.save(playerId, data);

        data.closeEndExpedition();
        store.save(playerId, data);

        assertNull(store.load(playerId).getActiveExpedition());
        String contents = Files.readString(recordFor(playerId));
        assertFalse(contents.contains("endExpedition"), "a closed expedition should leave no record behind");
    }

    @Test
    void aDeadLifesExpeditionDoesNotSurviveIntoTheNextLife() {
        PlayerData data = new PlayerData();
        UUID lifeId = data.ensureLifeId();
        data.openEndExpedition(expeditionFor(lifeId));
        store.save(playerId, data);

        // Death: rotate the life and close the journey, exactly as DeathListener does.
        PlayerData afterDeath = store.load(playerId);
        afterDeath.beginNewLife();
        afterDeath.incrementDeathCount();
        store.save(playerId, afterDeath);

        PlayerData reloaded = store.load(playerId);
        assertNull(reloaded.getActiveExpedition());
        assertNull(reloaded.getEndExpedition());
        assertEquals(1, reloaded.getDeathCount());
    }

    @Test
    void aStaleExpeditionOnDiskIsIgnoredRatherThanObeyed() throws IOException {
        PlayerData data = new PlayerData();
        data.setLifeId(UUID.randomUUID());
        // A record left by a life that has since died — the safety net behind beginNewLife().
        data.setEndExpedition(expeditionFor(UUID.randomUUID()));
        store.save(playerId, data);

        PlayerData loaded = store.load(playerId);

        assertNull(loaded.getActiveExpedition());
        assertNotNull(loaded.getEndExpedition());
        assertTrue(Files.readString(recordFor(playerId)).contains("endExpedition"));
    }

    @Test
    void aCorruptExpeditionRecordIsDiscardedInsteadOfLoaded() throws IOException {
        Files.writeString(recordFor(playerId), """
            {
              "dataVersion": 4,
              "hasJoinedBefore": true,
              "lifeId": "not-a-uuid",
              "endExpedition": {
                "lifeId": "also-not-a-uuid",
                "origin": { "world": "world", "x": 1.0, "y": 2.0, "z": 3.0 },
                "return": { "world": "world", "x": 1.0, "y": 2.0, "z": 3.0 },
                "end": { "world": "world_the_end", "x": 100.0, "y": 65.0, "z": 100.0 }
              }
            }
            """);

        PlayerData loaded = store.load(playerId);

        assertNull(loaded.getLifeId());
        assertNull(loaded.getEndExpedition(), "an unreadable record must be dropped, not half-trusted");
        assertTrue(loaded.isHasJoinedBefore(), "the rest of the file is still usable");
    }

    @Test
    void anIncompleteExpeditionRecordIsDiscarded() throws IOException {
        Files.writeString(recordFor(playerId), """
            {
              "dataVersion": 4,
              "hasJoinedBefore": true,
              "lifeId": "00000000-0000-0000-0000-000000000001",
              "endExpedition": {
                "lifeId": "00000000-0000-0000-0000-000000000001",
                "origin": { "x": 1.0, "y": 2.0, "z": 3.0 }
              }
            }
            """);

        PlayerData loaded = store.load(playerId);

        assertNull(loaded.getEndExpedition());
    }

    @Test
    void aFileThatIsNotJsonAtAllIsSetAsideRatherThanOverwritten() throws IOException {
        Files.writeString(recordFor(playerId), "{ this is not json");

        PlayerData loaded = store.load(playerId);

        assertFalse(loaded.isHasJoinedBefore(), "an unreadable record is treated as a new player");
        assertTrue(Files.exists(tempDir.resolve(playerId + ".json.corrupt")),
            "whatever the file held must still be on disk to look at");
    }

    // -------------------------------------------------------------------------
    // Conversion from the YAML files earlier versions wrote
    // -------------------------------------------------------------------------

    @Test
    void versionOneYamlFilesAreConvertedToJsonWithoutLosingAnything() throws IOException {
        // Exactly what an existing server's playerdata looks like before this feature.
        Files.writeString(tempDir.resolve(playerId + ".yml"), """
            hasJoinedBefore: true
            calculatingRespawn: false
            needsDelayedTeleport: false
            deathCount: 3
            firstSpawn:
              world: world
              x: 123456.0
              y: 64.0
              z: -78901.0
            lastExile:
              world: world
              x: 456789.0
              y: 72.0
              z: 123456.0
            hasPendingRespawn: false
            """);

        PlayerData loaded = store.load(playerId);

        assertTrue(loaded.isHasJoinedBefore());
        assertEquals(3, loaded.getDeathCount());
        assertEquals(123456.0, loaded.getFirstSpawnX());
        assertEquals(456789.0, loaded.getLastExileX());
        assertNull(loaded.getLifeId(), "legacy players have no life identity until one is needed");
        assertNull(loaded.getActiveExpedition());

        // The YAML is gone and a current JSON record stands in its place.
        assertFalse(Files.exists(tempDir.resolve(playerId + ".yml")));
        String contents = Files.readString(recordFor(playerId));
        assertTrue(contents.contains("\"dataVersion\": " + PlayerData.CURRENT_DATA_VERSION));
        assertFalse(contents.contains("lifeId"));
        assertEquals(PlayerData.CURRENT_DATA_VERSION, loaded.getDataVersion());
        assertEquals(3, store.load(playerId).getDeathCount());
    }

    @Test
    void aYamlTravellerKeepsTheirWayHomeThroughTheConversion() throws IOException {
        Files.writeString(tempDir.resolve(playerId + ".yml"), """
            dataVersion: 3
            hasJoinedBefore: true
            lifeId: 00000000-0000-0000-0000-000000000001
            endExpedition:
              lifeId: 00000000-0000-0000-0000-000000000001
              origin:
                world: world
                x: 120000.5
                y: 28.0
                z: -64000.5
              return:
                world: world
                x: 120002.5
                y: 29.0
                z: -64000.5
              end:
                world: world_the_end
                x: 7552.5
                y: 65.0
                z: -4096.5
              region: 7552,-4096
              openedAt: 1700000000000
            """);

        EndExpedition expedition = store.load(playerId).getActiveExpedition();

        assertNotNull(expedition, "converting the file must not strand a traveller in the End");
        assertEquals(120_002.5, expedition.getReturnX());
        assertEquals("7552,-4096", expedition.getRegionKey());
        assertEquals(1_700_000_000_000L, expedition.getOpenedAt());
        assertTrue(Files.readString(recordFor(playerId)).contains("endExpedition"));
    }

    // -------------------------------------------------------------------------
    // The journal
    // -------------------------------------------------------------------------

    @Test
    void aJournalSurvivesAReload() {
        PlayerData data = new PlayerData();
        data.addJournalEntry(new JournalEntry(Instant.parse("2026-05-31T14:23:45Z"),
            JournalEntry.Reason.FIRST_JOIN, "Steve", "world", 123456, 64, -78901), 200);
        data.addJournalEntry(new JournalEntry(Instant.parse("2026-05-31T14:42:19Z"),
            JournalEntry.Reason.DEATH, "Steve", "world", 12345, 64, -6789), 200);
        store.save(playerId, data);

        List<JournalEntry> journal = store.load(playerId).getJournal();

        assertEquals(2, journal.size());
        assertEquals(JournalEntry.Reason.FIRST_JOIN, journal.get(0).reason());
        assertEquals("Steve", journal.get(0).playerName());
        assertEquals(123456, journal.get(0).x());
        assertEquals(-78901, journal.get(0).z());
        assertEquals(Instant.parse("2026-05-31T14:42:19Z"), journal.get(1).at());
        assertEquals(JournalEntry.Reason.DEATH, journal.get(1).reason());
    }

    @Test
    void oneUnreadableEntryDoesNotCostAPlayerTheirWholeHistory() throws IOException {
        Files.writeString(recordFor(playerId), """
            {
              "dataVersion": 4,
              "hasJoinedBefore": true,
              "journal": [
                { "at": "2026-05-31T14:23:45Z", "reason": "FIRST_JOIN", "player": "Steve",
                  "world": "world", "x": 1, "y": 2, "z": 3 },
                { "at": "not-a-time", "reason": "DEATH", "player": "Steve",
                  "world": "world", "x": 1, "y": 2, "z": 3 },
                { "at": "2026-05-31T14:44:00Z", "reason": "NOT_A_REASON", "player": "Steve",
                  "world": "world", "x": 1, "y": 2, "z": 3 },
                { "at": "2026-05-31T14:45:00Z", "reason": "EXILE_RESPAWN", "player": "Steve",
                  "world": "world", "x": 9, "y": 8, "z": 7 }
              ]
            }
            """);

        List<JournalEntry> journal = store.load(playerId).getJournal();

        assertEquals(2, journal.size());
        assertEquals(JournalEntry.Reason.FIRST_JOIN, journal.get(0).reason());
        assertEquals(JournalEntry.Reason.EXILE_RESPAWN, journal.get(1).reason());
    }

    @Test
    void aPlayersHistoryIsInTheirOwnFileAndNobodyElses() {
        UUID otherPlayer = UUID.randomUUID();

        PlayerData mine = new PlayerData();
        mine.addJournalEntry(JournalEntry.now(JournalEntry.Reason.DEATH, "Steve", "world", 1, 2, 3), 200);
        store.save(playerId, mine);

        PlayerData theirs = new PlayerData();
        theirs.addJournalEntry(JournalEntry.now(JournalEntry.Reason.FIRST_JOIN, "Alex", "world", 4, 5, 6), 200);
        store.save(otherPlayer, theirs);

        assertEquals(1, store.load(playerId).getJournal().size());
        assertEquals("Steve", store.load(playerId).getJournal().get(0).playerName());
        assertEquals("Alex", store.load(otherPlayer).getJournal().get(0).playerName());
    }

    @Test
    void theOldestEntriesAreDroppedOnceTheLimitIsReached() {
        PlayerData data = new PlayerData();
        for (int i = 0; i < 10; i++) {
            data.addJournalEntry(new JournalEntry(Instant.ofEpochSecond(1_700_000_000L + i),
                JournalEntry.Reason.DEATH, "Steve", "world", i, 64, i), 3);
        }
        store.save(playerId, data);

        List<JournalEntry> journal = store.load(playerId).getJournal();

        assertEquals(3, journal.size());
        assertEquals(7, journal.get(0).x(), "the three most recent entries are the ones kept");
        assertEquals(9, journal.get(2).x());
    }

    @Test
    void aLimitOfZeroKeepsEverything() {
        PlayerData data = new PlayerData();
        for (int i = 0; i < 50; i++) {
            data.addJournalEntry(new JournalEntry(Instant.ofEpochSecond(1_700_000_000L + i),
                JournalEntry.Reason.END_WORMHOLE, "Steve", "world_the_end", i, 65, i), 0);
        }
        store.save(playerId, data);

        assertEquals(50, store.load(playerId).getJournal().size());
    }

    @Test
    void aJournalIsKeptWhenTheWorldUnderneathItIsNot() {
        PlayerData data = new PlayerData();
        data.setHasJoinedBefore(true);
        data.setFirstSpawn("world", 1, 2, 3);
        data.addJournalEntry(JournalEntry.now(JournalEntry.Reason.FIRST_JOIN, "Steve", "world", 1, 2, 3), 200);
        data.incrementDeathCount();

        data.resetForNewWorld(UUID.randomUUID());

        assertEquals(1, data.getJournal().size(), "what happened to a player happened, whatever the map is now");
        assertEquals(1, data.getDeathCount());
        assertNull(data.getFirstSpawnWorld());
    }

    // -------------------------------------------------------------------------

    @Test
    void separateTravellersKeepSeparateExpeditions() {
        UUID otherPlayer = UUID.randomUUID();

        PlayerData first = new PlayerData();
        UUID firstLife = first.ensureLifeId();
        first.openEndExpedition(expeditionFor(firstLife));
        store.save(playerId, first);

        PlayerData second = new PlayerData();
        UUID secondLife = second.ensureLifeId();
        second.openEndExpedition(new EndExpedition(secondLife,
            "world", -900_000, 31, 42_000,
            "world", -899_998, 32, 42_000,
            "world_the_end", -56_320, 65, 2560,
            "-56320,2560", 1_700_000_001_000L));
        store.save(otherPlayer, second);

        // Two travellers standing on the same platform still go home to their own strongholds.
        assertEquals(120_002.5, store.load(playerId).getActiveExpedition().getReturnX());
        assertEquals(-899_998.0, store.load(otherPlayer).getActiveExpedition().getReturnX());
        assertEquals("7552,-4096", store.load(playerId).getActiveExpedition().getRegionKey());
        assertEquals("-56320,2560", store.load(otherPlayer).getActiveExpedition().getRegionKey());
    }

    @Test
    void missingFilesReadAsAFreshPlayer() {
        PlayerData data = store.load(UUID.randomUUID());

        assertFalse(data.isHasJoinedBefore());
        assertNull(data.getLifeId());
        assertNull(data.getActiveExpedition());
        assertTrue(data.getJournal().isEmpty());
    }

    @Test
    void aFileIsWrittenPerPlayer() {
        PlayerData data = new PlayerData();
        data.setHasJoinedBefore(true);
        store.save(playerId, data);

        assertTrue(new File(tempDir.toFile(), playerId + ".json").isFile());
        assertFalse(new File(tempDir.toFile(), playerId + ".json.tmp").exists(),
            "the write-and-move must not leave its temporary file behind");
    }
}
