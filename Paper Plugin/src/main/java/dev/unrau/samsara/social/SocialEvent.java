package dev.unrau.samsara.social;

import dev.unrau.samsara.config.SocialConfig;

import java.util.function.ToIntFunction;

/**
 * The kinds of thing one player does that another player might be told about.
 *
 * <p>This enum is the reason chat, joins, deaths, advancements and pet deaths are not five
 * unrelated pieces of special-case code. Each of them answers the same question —
 * <em>who should be told?</em> — and each of them differs only in the three answers held here.
 * {@link SocialAudience} asks the question; this says what makes each event's answer its own.
 */
public enum SocialEvent {

    /** Ordinary chat. Heard nearby, and by contacts wherever they are. */
    CHAT(SocialConfig::getChatRadius, true, true),

    /** "X joined the game" — including for the player who joined, as in vanilla. */
    JOIN(SocialConfig::getJoinRadius, true, true),

    /** "X left the game". The player it is about is already on their way out. */
    QUIT(SocialConfig::getQuitRadius, false, true),

    /** A player's death message, which they are shown as well. */
    DEATH(SocialConfig::getDeathRadius, true, true),

    /** An advancement worth announcing. */
    ADVANCEMENT(SocialConfig::getAdvancementRadius, true, true),

    /**
     * A tamed animal's death.
     *
     * <p>The only event that does not reach contacts. Somebody's wolf dying is a thing that happens
     * in a place — it is news to whoever is standing there, and to its owner, and to nobody else. A
     * contact half a world away learning that your dog died is the kind of ambient noise this whole
     * system exists to remove.
     *
     * <p>The owner is not resolved here either; they are told separately, because vanilla already
     * tells them and duplicating that would say it twice.
     */
    PET_DEATH(SocialConfig::getPetRadius, false, false);

    private final ToIntFunction<SocialConfig> radius;
    private final boolean reachesSource;
    private final boolean reachesContacts;

    SocialEvent(ToIntFunction<SocialConfig> radius, boolean reachesSource, boolean reachesContacts) {
        this.radius = radius;
        this.reachesSource = reachesSource;
        this.reachesContacts = reachesContacts;
    }

    /** How far this event carries to players who are merely nearby, in blocks. */
    public int radius(SocialConfig config) {
        return radius.applyAsInt(config);
    }

    /** Whether the player the event is about is one of its own recipients. */
    public boolean reachesSource() {
        return reachesSource;
    }

    /** Whether a contact receives this at any distance. */
    public boolean reachesContacts() {
        return reachesContacts;
    }
}
