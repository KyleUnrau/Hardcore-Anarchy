package dev.unrau.samsara.social;

import dev.unrau.samsara.config.PluginConfig;
import dev.unrau.samsara.config.SocialConfig;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;

/**
 * Everything the social system is, in one object.
 *
 * <p>The listeners and the commands hold this rather than holding six collaborators each, and it is
 * what {@code /samsara reload} talks to when the tuning changes underneath a running server.
 *
 * <p>Nothing here decides anything. The rules live in {@link SocialAudience} (who is told),
 * {@link SocialGraph} (who knows whom), {@link ProximityScanner} (who is getting to know whom) and
 * {@link MessageService} (what crosses the map). This assembles them and owns their lifetime.
 */
public class SocialService {

    private final PluginConfig config;

    private final SocialStore store;
    private final SocialGraph graph;
    private final SocialAudience audience;
    private final ContactRequests requests;
    private final MessageService messages;
    private final ProximityScanner scanner;

    public SocialService(JavaPlugin plugin, PluginConfig config) {
        this.config = config;
        this.store = new SocialStore(plugin);
        this.graph = new SocialGraph(store, config::getSocial);
        this.audience = new SocialAudience(config, store, graph);
        this.requests = new ContactRequests();
        this.messages = new MessageService(config, store, graph);
        this.scanner = new ProximityScanner(plugin, config, store, graph);
    }

    /**
     * Begins sampling and adopts whoever is already online.
     *
     * <p>The second half matters when the plugin is enabled by hand into a running server: the join
     * listener will never fire for the players already on it, and every record read after that would
     * be a detached copy that the next write throws away.
     */
    public void start() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            store.hold(player.getUniqueId(), player.getName());
        }
        scanner.start();
    }

    /** Re-reads the tuning: restarts the sampler on its new interval. */
    public void reload() {
        scanner.start();
    }

    public void stop() {
        scanner.stop();
        store.shutdown(System.currentTimeMillis(), fade());
    }

    /** Brings a player's record in and keeps the names their contacts see current. */
    public void onJoin(Player player) {
        SocialData data = store.hold(player.getUniqueId(), player.getName());

        // Each of this player's contacts holds a name for them, cached so that an offline contact
        // still reads as somebody rather than as a uuid. A rename would otherwise leave every one of
        // those lists quietly wrong until the next time the pair met.
        for (UUID contactId : data.getContacts().keySet()) {
            SocialData theirs = store.load(contactId);
            theirs.refreshContactName(player.getUniqueId(), player.getName());
            store.saveIfDirty(contactId, theirs);
        }
    }

    /** Writes a player's record out and lets go of everything held for them in memory. */
    public void onQuit(Player player) {
        UUID uuid = player.getUniqueId();
        messages.forget(uuid);
        // Requests are a conversation between two people who are both here. One of them leaving ends
        // it; nothing is owed to a question nobody is left to answer.
        for (UUID from : requests.pendingFor(uuid)) requests.take(uuid, from);
        for (UUID to : requests.sentBy(uuid)) requests.take(to, uuid);

        SocialData data = store.load(uuid);
        data.pruneProximity(System.currentTimeMillis(), fade());
        store.release(uuid);
    }

    /** How time apart is charged against banked nearness, as the current tuning describes it. */
    public SocialData.Fade fade() {
        return ProximityScanner.fade(config.getSocial());
    }

    /**
     * Finds a player by name, whether or not they are online.
     *
     * <p>Online players first, then this player's own lists — the names cached against their
     * contacts and their ignores — and only then the server's own cache of everybody it has ever
     * seen. The order is what lets somebody remove a contact who has not logged in for a month
     * without having to know their id.
     */
    public UUID resolve(Player viewer, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();

        SocialData data = store.load(viewer.getUniqueId());
        UUID known = findByName(data.getContacts(), name);
        if (known != null) return known;
        known = findByName(data.getIgnored(), name);
        if (known != null) return known;

        // Never blocks: this reads the server's user cache and returns null rather than going to
        // Mojang, which is the difference between an unknown name and a hitch on the main thread.
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached == null ? null : cached.getUniqueId();
    }

    private static UUID findByName(Map<UUID, String> source, String name) {
        for (Map.Entry<UUID, String> entry : source.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(name)) return entry.getKey();
        }
        return null;
    }

    /**
     * The best name available for somebody, online or not.
     *
     * <p>The viewer's own lists are consulted before the other player's record, because reading that
     * record means reading a file — and this is called once per comparison while sorting a list.
     */
    public String nameOf(Player viewer, UUID who) {
        Player online = Bukkit.getPlayer(who);
        if (online != null) return online.getName();

        SocialData data = store.load(viewer.getUniqueId());
        String name = data.contactName(who);
        if (name == null || name.isEmpty()) {
            name = data.getIgnored().get(who);
        }
        if (name != null && !name.isEmpty()) return name;

        String stored = store.load(who).getName();
        return stored == null || stored.isEmpty() ? who.toString() : stored;
    }

    public SocialConfig settings()      { return config.getSocial(); }
    public SocialStore store()          { return store; }
    public SocialGraph graph()          { return graph; }
    public SocialAudience audience()    { return audience; }
    public ContactRequests requests()   { return requests; }
    public MessageService messages()    { return messages; }
}
