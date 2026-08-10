package dev.unrau.samsara.service;

import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A patch of Overworld with End portals standing in it — as much of one as {@link EndPortalAnchor}
 * ever reads, which is one horizontal plane and nothing else.
 *
 * <p>Built out of {@link EndPortalShape} rather than out of hand-written coordinates, so a portal
 * here is the same shape the plugin lights and the same shape it looks for.
 */
final class PortalSurvey implements EndPortalAnchor.Survey {

    private final List<Coord> centres;
    private final boolean framed;
    private final Set<Coord> missing = new HashSet<>();

    private PortalSurvey(boolean framed, Coord... centres) {
        this.framed = framed;
        this.centres = List.of(centres);
    }

    /** Portals as the world builds them: twelve inward-facing frames around a filled opening. */
    static PortalSurvey completePortalAt(Coord... centres) {
        return new PortalSurvey(true, centres);
    }

    /** The opening alone, with no frames — a portal placed by an editor rather than lit. */
    static PortalSurvey unframedPortalAt(Coord... centres) {
        return new PortalSurvey(false, centres);
    }

    /** Knocks blocks out of the opening, for shapes the anchor was never promised. */
    PortalSurvey without(Coord... blocks) {
        this.missing.addAll(List.of(blocks));
        return this;
    }

    @Override
    public boolean isPortalBlock(int x, int z) {
        if (missing.contains(new Coord(x, z))) return false;
        return offsetsFrom(x, z).stream()
            .anyMatch(offset -> Math.abs(offset.x()) <= 1 && Math.abs(offset.z()) <= 1);
    }

    @Override
    public boolean isFrameFacing(int x, int z, BlockFace facing) {
        if (!framed) return false;
        return offsetsFrom(x, z).stream()
            .anyMatch(offset -> facing == EndPortalShape.facingAt(offset.x(), offset.z()));
    }

    /** Where this position stands relative to each portal in the survey. */
    private List<Coord> offsetsFrom(int x, int z) {
        List<Coord> offsets = new ArrayList<>(centres.size());
        for (Coord centre : centres) {
            offsets.add(new Coord(x - centre.x(), z - centre.z()));
        }
        return offsets;
    }
}
