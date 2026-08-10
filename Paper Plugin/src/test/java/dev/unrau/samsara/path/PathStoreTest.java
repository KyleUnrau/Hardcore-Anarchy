package dev.unrau.samsara.path;

import dev.unrau.samsara.data.PlayerDataCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The index has to survive a restart, because it is the answer to "who is this player" and the join
 * that asks cannot wait for anybody to fix a file. A damaged one must be set aside rather than
 * overwritten: whatever it held is the only record that several existences ever belonged to this
 * account.
 *
 * <p>The dormant path files themselves are not exercised here. They hold {@code ItemStack}s
 * serialised by the server's own NBT writer, which needs a running server — so what is testable is
 * everything around them, and what is not is covered by the invariant those files exist to serve:
 * an active path never has one.
 */
class PathStoreTest {

    @TempDir
    Path tempDir;

    private PathStore store;
    private UUID account;

    @BeforeEach
    void setUp() {
        Logger logger = Logger.getLogger(PathStoreTest.class.getName());
        store = new PathStore(tempDir.toFile(), logger, new PlayerDataCodec(logger));
        account = UUID.randomUUID();
    }

    private Path indexFile() {
        return tempDir.resolve(account.toString()).resolve("index.json");
    }

    @Test
    void anAccountThatHasNeverHeldAPathHoldsNothing() {
        assertNull(store.loadIndex(account));
    }

    @Test
    void anIndexSurvivesBeingWrittenAndReadBack() {
        PlayerPath original = PlayerPath.beginning("Original");
        PlayerPath north = PlayerPath.beginning("North", List.of("Alex", "Herobrine"));
        PathIndex written = PathIndex.beginningWith(original);
        written.add(north);
        written.setActivePathId(north.id());

        assertTrue(store.saveIndex(account, written));

        PathIndex read = store.loadIndex(account);
        assertNotNull(read);
        assertEquals(2, read.size());
        assertEquals(north.id(), read.activePathId());
        assertEquals("North", read.active().name());
        assertEquals(original.id(), read.byName("original").id());
        assertEquals(List.of("Alex", "Herobrine"), read.byName("North").companions());
        assertEquals(north.createdAt(), read.byName("North").createdAt());
    }

    @Test
    void aPathWithNoUsableIdOrNameIsDroppedRatherThanGuessedAt() throws IOException {
        PlayerPath original = PlayerPath.beginning("Original");
        store.saveIndex(account, PathIndex.beginningWith(original));

        String damaged = """
            {
              "dataVersion": 1,
              "activePathId": "%s",
              "paths": [
                { "id": "%s", "name": "Original", "createdAt": 1 },
                { "name": "Nameless" },
                { "id": "not-a-uuid", "name": "Broken" }
              ]
            }
            """.formatted(original.id(), original.id());
        Files.writeString(indexFile(), damaged, StandardCharsets.UTF_8);

        PathIndex read = store.loadIndex(account);
        assertNotNull(read);
        assertEquals(1, read.size());
        assertEquals("Original", read.active().name());
    }

    /**
     * The alternative — overwriting it — would silently take away every existence but the one the
     * player happens to be standing in, and there would be nothing left to look at afterwards.
     */
    @Test
    void anUnreadableIndexIsSetAsideRatherThanOverwritten() throws IOException {
        store.saveIndex(account, PathIndex.beginningWith(PlayerPath.beginning("Original")));
        Files.writeString(indexFile(), "{ this is not json", StandardCharsets.UTF_8);

        assertNull(store.loadIndex(account));
        assertFalse(Files.exists(indexFile()));
        assertTrue(Files.exists(indexFile().resolveSibling("index.json.corrupt")));
    }

    @Test
    void anIndexHoldingNoPathsAtAllReadsAsNoIndex() throws IOException {
        store.saveIndex(account, PathIndex.beginningWith(PlayerPath.beginning("Original")));
        Files.writeString(indexFile(), "{ \"dataVersion\": 1, \"paths\": [] }", StandardCharsets.UTF_8);

        assertNull(store.loadIndex(account));
    }

    @Test
    void aDormantFileIsReportedOnlyWhenItIsThere() throws IOException {
        UUID pathId = UUID.randomUUID();
        assertFalse(store.hasSnapshot(account, pathId));
        assertNull(store.loadSnapshot(account, pathId));
        // Deleting one that was never there is not a failure; the caller wants it gone, and it is.
        assertTrue(store.deleteSnapshot(account, pathId));

        Path file = tempDir.resolve(account.toString()).resolve(pathId + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{}", StandardCharsets.UTF_8);

        assertTrue(store.hasSnapshot(account, pathId));
        // Present but carrying no Minecraft state: not something anybody can be stood up as.
        assertNull(store.loadSnapshot(account, pathId));

        assertTrue(store.deleteSnapshot(account, pathId));
        assertFalse(store.hasSnapshot(account, pathId));
    }
}
