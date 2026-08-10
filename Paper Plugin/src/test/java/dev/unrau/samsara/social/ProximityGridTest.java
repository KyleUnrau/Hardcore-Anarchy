package dev.unrau.samsara.social;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bucketing is an optimisation, and an optimisation that quietly loses pairs would mean two
 * players standing together for an evening and nothing ever happening. So the property under test is
 * that it finds exactly what the double loop would have found — including across cell boundaries,
 * which is the only place a grid can get this wrong.
 */
class ProximityGridTest {

    private static final double RADIUS = 48;

    private final List<ProximityGrid.Position> positions = new ArrayList<>();

    private UUID at(double x, double y, double z) {
        UUID id = UUID.randomUUID();
        positions.add(new ProximityGrid.Position(id, x, y, z));
        return id;
    }

    private List<PairKey> pairs(double radius) {
        return ProximityGrid.pairsWithin(positions, radius).stream().map(ProximityGrid.Near::pair).toList();
    }

    @Test
    void twoPlayersInTheSameRoomArePaired() {
        UUID a = at(100, 64, 100);
        UUID b = at(110, 64, 105);

        assertEquals(List.of(PairKey.of(a, b)), pairs(RADIUS));
    }

    @Test
    void aPairStraddlingACellBoundaryIsStillFound() {
        // Either side of a multiple of the radius: the case a naive grid drops entirely.
        UUID a = at(47, 64, 0);
        UUID b = at(49, 64, 0);

        assertEquals(List.of(PairKey.of(a, b)), pairs(RADIUS),
            "two players two blocks apart are together, whichever cells they fall in");
    }

    @Test
    void negativeCoordinatesAreNotASpecialCase() {
        UUID a = at(-1, 64, -1);
        UUID b = at(1, 64, 1);

        assertEquals(List.of(PairKey.of(a, b)), pairs(RADIUS));
    }

    @Test
    void distantPlayersAreNotPaired() {
        at(0, 64, 0);
        at(500, 64, 500);

        assertTrue(pairs(RADIUS).isEmpty());
    }

    @Test
    void heightCounts() {
        at(0, 64, 0);
        at(0, 200, 0);

        assertTrue(pairs(RADIUS).isEmpty(), "somebody a hundred blocks overhead is not in the room");
    }

    @Test
    void everyPairIsReportedExactlyOnce() {
        at(0, 64, 0);
        at(5, 64, 5);
        at(10, 64, 10);

        List<PairKey> found = pairs(RADIUS);
        assertEquals(3, found.size());
        assertEquals(3, new HashSet<>(found).size(), "a pair counted twice is time credited twice");
    }

    @Test
    void theGridAgreesWithTheDoubleLoopItReplaces() {
        Random random = new Random(20260809L);
        for (int i = 0; i < 200; i++) {
            at(random.nextInt(-400, 400), random.nextInt(0, 128), random.nextInt(-400, 400));
        }

        assertTrue(bruteForce().size() > 10, "the sample must actually contain pairs to compare");
        assertEquals(bruteForce(), new HashSet<>(pairs(RADIUS)));
    }

    @Test
    void theDistanceComesBackWithThePair() {
        // The caller scores nearness by how near it actually was, so a grid that reported only
        // "within 48 blocks" would flatten the same table and the far side of the field into one.
        at(0, 64, 0);
        at(0, 64, 30);

        List<ProximityGrid.Near> found = ProximityGrid.pairsWithin(positions, RADIUS);
        assertEquals(1, found.size());
        assertEquals(30, found.get(0).distance(), 0.001);
    }

    @Test
    void nobodyIsPairedWithNobody() {
        at(0, 64, 0);
        assertTrue(pairs(RADIUS).isEmpty());
        assertTrue(ProximityGrid.pairsWithin(List.of(), RADIUS).isEmpty());
    }

    @Test
    void aRadiusOfZeroPairsNobody() {
        at(0, 64, 0);
        at(0, 64, 0);

        assertTrue(pairs(0).isEmpty(), "a server that switched this off switched it off");
    }

    /** The answer the grid has to reproduce, worked out the slow and obviously-correct way. */
    private Set<PairKey> bruteForce() {
        Set<PairKey> expected = new HashSet<>();
        for (int i = 0; i < positions.size(); i++) {
            for (int j = i + 1; j < positions.size(); j++) {
                ProximityGrid.Position first = positions.get(i);
                ProximityGrid.Position second = positions.get(j);
                double dx = first.x() - second.x();
                double dy = first.y() - second.y();
                double dz = first.z() - second.z();
                if (dx * dx + dy * dy + dz * dz <= RADIUS * RADIUS) {
                    expected.add(PairKey.of(first.id(), second.id()));
                }
            }
        }
        return expected;
    }
}
