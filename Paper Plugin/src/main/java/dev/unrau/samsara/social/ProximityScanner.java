package dev.unrau.samsara.social;

import dev.unrau.samsara.config.PluginConfig;
import dev.unrau.samsara.config.SocialConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns time spent together into a contact.
 *
 * <p>The rule this exists to enforce is the one about what "meaningful physical interaction" means.
 * Walking past somebody is not it. Standing in the same base for an evening is. So nothing is
 * decided by a single sample, and nothing is decided by an unbroken stretch either: each pass adds
 * to a running score for the pair, and the contact is offered when that score crosses a threshold
 * measured in tens of minutes.
 *
 * <p>What one pass is worth depends on how close they were. Inside the close radius it is worth its
 * full length in seconds — they are in the same room. From there it tapers to nothing at the outer
 * radius, so two players at opposite ends of a field are building something, slowly, and two players
 * who happen to be inside the same wide circle are building almost nothing. That is what makes the
 * threshold mean "together" rather than "in the same area code", without needing the two of them to
 * stay in the same place for twenty unbroken minutes.
 *
 * <p>Time apart runs the other way: a pair who stop seeing each other lose what they had banked, at
 * a fraction of the rate they earned it. So the score describes how close two players are lately,
 * not how close they have ever been, and a hundred passing encounters spread over a year never
 * accumulate into one relationship. The fade is charged at the moment a pair are next looked at
 * rather than by visiting every pair on the map — see {@link SocialData.Fade}.
 *
 * <p>The two are the same arithmetic, which is what keeps the score from having a cliff in it.
 * Distance is charged as partial absence: at the far edge of the radius a pair are nine-tenths apart
 * and are billed for nine-tenths of the interval, and a pair on opposite sides of the world are
 * simply the case where that fraction is all of it. Nothing happens at the radius boundary except
 * that the last of the credit runs out.
 *
 * <p>Both players must have automatic contacts switched on before the pair is even sampled. There is
 * no half-consent state where one of them is accumulating progress towards a relationship the other
 * has not agreed to the possibility of.
 *
 * <p>Every other refusal is checked at the same moment: a pair either of them severed, a pair where
 * one ignores the other, a player whose contact list is full. A severed pair is not sampled at all,
 * so standing next to somebody you removed builds nothing that could later be released.
 */
public class ProximityScanner {

    private final JavaPlugin plugin;
    private final PluginConfig config;
    private final SocialStore store;
    private final SocialGraph graph;

    private BukkitTask sampleTask;
    private BukkitTask flushTask;

    /** When the last sample ran, so a lagging or restarted task credits real time and not ticks. */
    private long lastSampleMillis;

    public ProximityScanner(JavaPlugin plugin, PluginConfig config, SocialStore store, SocialGraph graph) {
        this.plugin = plugin;
        this.config = config;
        this.store = store;
        this.graph = graph;
    }

    /** Starts sampling, if automatic contacts are enabled. Safe to call twice. */
    public void start() {
        stop();

        SocialConfig social = config.getSocial();
        if (!social.isEnabled() || !social.isContactsEnabled() || !social.isAutoContactsEnabled()) {
            return;
        }

        int interval = social.getAutoSampleIntervalTicks();
        lastSampleMillis = System.currentTimeMillis();
        sampleTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sample, interval, interval);

        // Contacts are written the moment they are made; this is only for the progress towards one,
        // which is worth keeping across a restart but not worth a file write every few seconds.
        long flushTicks = social.getFlushIntervalSeconds() * 20L;
        flushTask = Bukkit.getScheduler().runTaskTimer(plugin, this::flush, flushTicks, flushTicks);
    }

    /** Tidies and writes the progress tables. The fade is what makes forgetting actually happen. */
    private void flush() {
        store.flushAll(System.currentTimeMillis(), fade(config.getSocial()));
    }

    /** How time apart is charged, as the current tuning describes it. */
    static SocialData.Fade fade(SocialConfig social) {
        return new SocialData.Fade(social.getAutoDecayRate(),
            social.getAutoForgetAfterSeconds() * 1000L);
    }

    public void stop() {
        if (sampleTask != null) {
            sampleTask.cancel();
            sampleTask = null;
        }
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
    }

    /**
     * One pass: score every eligible pair for how close they were this interval, and link the ones
     * whose score has now crossed the threshold.
     */
    private void sample() {
        SocialConfig social = config.getSocial();
        if (!social.isEnabled() || !social.isContactsEnabled() || !social.isAutoContactsEnabled()) {
            return;
        }

        long now = System.currentTimeMillis();
        double elapsedSeconds = Math.max(0, now - lastSampleMillis) / 1000.0;
        lastSampleMillis = now;

        // A pass that has been delayed — a lagging server, a task that resumed after a freeze —
        // should not hand out a quarter of an hour in one go. Credit at most two ordinary intervals.
        double interval = social.getAutoSampleIntervalTicks() / 20.0;
        double sampleSeconds = Math.min(elapsedSeconds, interval * 2);
        if (sampleSeconds <= 0) return;

        Map<UUID, Player> byId = new HashMap<>();
        Map<World, List<ProximityGrid.Position>> byWorld = new HashMap<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            // Filtering here rather than at the pair is what keeps a player who wants nothing to do
            // with automatic contacts out of the calculation entirely, rather than merely out of its
            // conclusions.
            if (!graph.autoContactsEnabled(player.getUniqueId())) continue;

            byId.put(player.getUniqueId(), player);
            var at = player.getLocation();
            byWorld.computeIfAbsent(player.getWorld(), key -> new ArrayList<>())
                .add(new ProximityGrid.Position(player.getUniqueId(), at.getX(), at.getY(), at.getZ()));
        }
        if (byId.size() < 2) return;

        SocialData.Fade fade = fade(social);
        double required = social.getAutoRequiredSeconds();

        for (List<ProximityGrid.Position> positions : byWorld.values()) {
            for (ProximityGrid.Near near : ProximityGrid.pairsWithin(positions, social.getAutoRadius())) {
                PairKey pair = near.pair();
                Player first = byId.get(pair.first());
                Player second = byId.get(pair.second());
                if (first == null || second == null) continue;
                if (!eligible(first.getUniqueId(), second.getUniqueId(), social)) continue;

                // Nearness is a fraction, not a yes: a pair at the far edge of the radius spend most
                // of the interval effectively apart, so most of it is charged as such. That is the
                // same arithmetic a pair who are nowhere near each other get — they are simply at
                // closeness zero — which is what makes the score move smoothly as they drift rather
                // than stalling the moment they are technically still inside the circle.
                double closeness = closeness(near.distance(),
                    social.getAutoCloseRadius(), social.getAutoRadius());

                SocialData firstData = store.load(first.getUniqueId());
                SocialData secondData = store.load(second.getUniqueId());

                // Two players sitting exactly on the radius earn nothing and have nothing to lose.
                // Writing them an empty entry would put a pair on both records, and back on disk at
                // every flush, to say that they are not getting to know each other.
                long lastAt = firstData.lastProximityMillis(second.getUniqueId());
                if (closeness <= 0 && lastAt == 0) continue;

                double credit = sampleSeconds * closeness;
                // The pair were together for the near fraction of this interval; the rest of it, and
                // the whole of the gap before it, is time apart.
                double apart = lastAt == 0 ? 0
                    : Math.max(0, (now - lastAt) / 1000.0 - sampleSeconds * closeness);

                double score = firstData.recordProximity(second.getUniqueId(), credit, apart, now, fade);
                secondData.recordProximity(first.getUniqueId(), credit, apart, now, fade);

                if (score >= required) {
                    link(first, second);
                }
            }
        }
    }

    /**
     * What a second at this distance is worth, between one and nothing.
     *
     * <p>Full credit inside {@code close}, tapering to nothing at {@code far}. Linear, because the
     * shape of the curve is not the point — the point is that being nearer is worth more, so a pair
     * working at the same table reach the threshold in the advertised twenty minutes and a pair who
     * merely share a wide circle take most of a day to get anywhere.
     *
     * <p>The same number is read the other way round as how <em>absent</em> the two of them are, and
     * used to charge the fade. So there is exactly one description of what a distance means, and no
     * way for the earning and the losing to disagree about it.
     */
    static double closeness(double distance, double close, double far) {
        if (distance <= close) return 1;
        // Crossed or equal radii leave no room for a taper; the config layer warns and squares them
        // up, and this is what the shape degrades to either way.
        if (distance >= far || far <= close) return 0;
        return (far - distance) / (far - close);
    }

    /** Every reason a pair standing together might still not be allowed to become contacts. */
    private boolean eligible(UUID a, UUID b, SocialConfig social) {
        SocialData first = store.load(a);
        SocialData second = store.load(b);

        if (first.isContact(b)) return false;
        // A severance either of them chose. Not sampled, so proximity cannot rebuild what somebody
        // deliberately took apart.
        if (first.isAutoSuppressed(b) || second.isAutoSuppressed(a)) return false;
        if (first.ignores(b) || second.ignores(a)) return false;

        int max = social.getMaxContacts();
        return first.contactCount() < max && second.contactCount() < max;
    }

    private void link(Player first, Player second) {
        SocialGraph.LinkOutcome outcome = graph.link(
            first.getUniqueId(), first.getName(), second.getUniqueId(), second.getName());
        if (outcome != SocialGraph.LinkOutcome.LINKED) return;

        tell(first, second.getName());
        tell(second, first.getName());
    }

    private void tell(Player player, String otherName) {
        player.sendMessage(Component.text(
            otherName + " is now a contact — you have spent enough time near each other."
                + " Distance no longer hides either of you from the other.",
            NamedTextColor.GRAY));
        player.sendMessage(Component.text(
            "  /contact remove " + otherName + " ends it, and /contacts auto off stops this"
                + " happening again.", NamedTextColor.DARK_GRAY));
    }
}
