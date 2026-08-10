package dev.unrau.samsara.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A site whose height is recomputed on every visit is a site that can be built twice: the new one
 * goes up, the old one's bedrock stays, and the two seal each other. These tests pin the rule that
 * stops it — a standing site keeps its height, and only one of anything survives a rebuild.
 */
class SiteAnchorTest {

    private static final int FROM_TERRAIN = 96;

    @Test
    void anEmptyColumnTakesTheHeightTheTerrainOffers() {
        SiteAnchor anchor = SiteAnchor.of(List.of(), FROM_TERRAIN);

        assertEquals(FROM_TERRAIN, anchor.y());
        assertTrue(anchor.strays().isEmpty(), "there is nothing standing to take down");
    }

    @Test
    void aStandingSiteIgnoresTheTerrainEntirely() {
        // The whole bug: a trapdoor, a pillar or a chorus plant in the footprint moves the height
        // the terrain suggests, and the gateway already standing must not move with it.
        SiteAnchor anchor = SiteAnchor.of(List.of(74), FROM_TERRAIN);

        assertEquals(74, anchor.y());
        assertTrue(anchor.strays().isEmpty());
    }

    @Test
    void aStackCollapsesToItsLowestGateway() {
        // Drift is upwards — the height only ever rose to clear an obstruction — so the lowest
        // gateway is the original, standing where it was before anything went wrong.
        SiteAnchor anchor = SiteAnchor.of(List.of(77, 74, 80), FROM_TERRAIN);

        assertEquals(74, anchor.y());
        assertEquals(List.of(77, 80), anchor.strays());
    }

    @Test
    void aGatewayBelowTheOriginalIsStillJustOneOfThem() {
        SiteAnchor anchor = SiteAnchor.of(List.of(74, 70), FROM_TERRAIN);

        assertEquals(70, anchor.y());
        assertEquals(List.of(74), anchor.strays());
    }

    @Test
    void everyRebuildAgreesOnTheSameAnswer() {
        // Stability is the point. If two visits could disagree, the site would move between them and
        // the stack would build itself straight back up.
        SiteAnchor first = SiteAnchor.of(List.of(80, 74, 77), FROM_TERRAIN);
        SiteAnchor second = SiteAnchor.of(List.of(77, 80, 74), FROM_TERRAIN + 12);

        assertEquals(first, second);
    }

    @Test
    void theSurvivorIsNeverAlsoAStray() {
        // The anchor is skipped while the strays are demolished, so listing it as both would leave
        // the wormhole with no gateway at all.
        for (List<Integer> found : List.of(List.of(64), List.of(64, 64), List.of(64, 67, 64))) {
            SiteAnchor anchor = SiteAnchor.of(found, FROM_TERRAIN);
            assertEquals(64, anchor.y());
            assertTrue(anchor.strays().stream().noneMatch(y -> y == anchor.y()),
                "the gateway being kept must not be demolished as a duplicate");
        }
    }

    @Test
    void aSettledSiteStaysSettled() {
        // What the second visit sees after the first has repaired the stack: one gateway, no work.
        SiteAnchor repaired = SiteAnchor.of(List.of(74, 77), FROM_TERRAIN);
        SiteAnchor next = SiteAnchor.of(List.of(repaired.y()), FROM_TERRAIN);

        assertEquals(repaired.y(), next.y());
        assertTrue(next.strays().isEmpty());
    }

    @Test
    void theStandingFormRefusesAnEmptyColumn() {
        assertThrows(IllegalArgumentException.class, () -> SiteAnchor.of(List.of()));
    }
}
