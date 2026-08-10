package dev.unrau.samsara.service;

import java.util.List;

/**
 * Which height a site is rebuilt at, and which of the things standing there are no longer it.
 *
 * <p>A site's height used to be recomputed from the terrain on every visit, and that is a bug rather
 * than a saving: the inputs change. A trapdoor placed to crawl into a wormhole, a pillar built to
 * reach it, a chorus plant that grew a block — anything in the footprint the builder refuses to
 * overwrite raises the height the next build picks, and because the bedrock frame is force-placed
 * and never removed, the site that was already there stays exactly where it was. The result is two
 * gateways stacked a few blocks apart, sealing each other in bedrock; travel through the pair often
 * enough and the End grows a column of them.
 *
 * <p>So a height is chosen <em>once</em>, and after that the world is the record: the gateway blocks
 * of a site carry the plugin's tag and their site centre in their persistent data, which rides along
 * in the chunk across restarts, and rebuilds read the height back off them rather than deriving it
 * again. Nothing new is written to disk — this is the same "the world remembers, the plugin does
 * not" principle the rest of End travel is built on.
 *
 * <p>Where several gateways are found, the lowest is the site and the rest are demolished. Which one
 * survives matters less than that the rule is deterministic and total: every visit agrees on the
 * same answer, so a site that has already grown a stack converges back to a single gateway instead
 * of the stack settling in. Lowest is the useful choice as well as a stable one, because drift is
 * upwards — the height only ever rose to clear an obstruction — so the lowest gateway is the
 * original, standing where it was before anything went wrong.
 *
 * @param y      the height to build at
 * @param strays heights of gateways belonging to this site that are not it, to be taken down
 */
public record SiteAnchor(int y, List<Integer> strays) {

    public SiteAnchor {
        strays = List.copyOf(strays);
    }

    /**
     * Resolves a site's height from the gateways already standing in its column.
     *
     * @param found     heights of the site's own gateways found in the world, in any order
     * @param fallbackY the height to build at when the site does not exist yet — the only time the
     *                  terrain gets a say in where a site stands
     */
    public static SiteAnchor of(List<Integer> found, int fallbackY) {
        return found.isEmpty() ? new SiteAnchor(fallbackY, List.of()) : of(found);
    }

    /**
     * Resolves the height of a site already standing.
     *
     * @param found heights of the site's own gateways, in any order; must not be empty
     */
    public static SiteAnchor of(List<Integer> found) {
        int anchor = found.stream().min(Integer::compare)
            .orElseThrow(() -> new IllegalArgumentException("a standing site has at least one gateway"));
        return new SiteAnchor(anchor,
            found.stream().filter(y -> y != anchor).distinct().sorted().toList());
    }
}
