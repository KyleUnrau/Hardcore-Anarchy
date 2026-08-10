package dev.unrau.samsara.service;

/**
 * What a gateway in the End is for.
 *
 * <p>The distinction is the whole shape of End travel on this server. A way out of the End is not
 * something you can stumble across — it has to be built by somebody walking into an End portal in
 * the Overworld. Everything else in the End moves you around inside the End.
 */
public enum SiteKind {

    /**
     * The way back to the Overworld, at the reflection of the portal that opened it.
     *
     * <p>Only created by a player entering an Overworld End portal, so every door out of the End
     * leads to somewhere a player has actually been. Nobody arrives in an untouched Overworld region
     * by falling through a hole in the End.
     */
    HOME("home"),

    /**
     * A wormhole to a distant partner cell, in the End at both ends.
     *
     * <p>The grid nodes and the End's own natural gateways are all of this kind, which is what makes
     * the End crossable: pairings are two-way, so a wormhole is a road rather than a one-way drop.
     */
    WORMHOLE("wormhole");

    private final String tag;

    SiteKind(String tag) {
        this.tag = tag;
    }

    /** The value stored in a gateway's persistent data. Stable — it is written into the world. */
    public String tag() {
        return tag;
    }

    /**
     * Reads a stored tag.
     *
     * @return the kind, or {@link #HOME} for a missing or unrecognised tag — gateways built before
     *         kinds existed were all ways home, and a corrupted tag on a real gateway is better
     *         resolved towards the exit than towards a jump into the unknown
     */
    public static SiteKind fromTag(String tag) {
        if (WORMHOLE.tag.equals(tag)) return WORMHOLE;
        return HOME;
    }
}
