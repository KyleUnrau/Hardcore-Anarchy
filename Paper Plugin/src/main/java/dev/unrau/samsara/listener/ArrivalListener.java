package dev.unrau.samsara.listener;

import dev.unrau.samsara.service.ArrivalPreparation;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * Puts a player in the right place from the first frame they are shown.
 *
 * <p>The two halves of this listener are two halves of one question, asked at the only two moments
 * where the answer still costs nothing:
 *
 * <ol>
 *   <li>The login handshake, off the main thread, before the player exists on the server at all.
 *       This is where {@link ArrivalPreparation} does the searching and the chunk loading, and it can
 *       take as long as it needs to — the client is sitting on its own "Logging in" screen, which is
 *       exactly what that screen is for.</li>
 *   <li>The spawn position, asked of the connection while it is still being configured — before the
 *       player object is built and before a single chunk has been sent. Whatever is set here
 *       <em>is</em> where they appear: there is no teleport, no correction, and no moment at which
 *       they were somewhere else.</li>
 * </ol>
 *
 * <p>Neither half decides anything. If nothing was prepared — the feature is off, the search timed
 * out, the player is an ordinary returning one who is already somewhere — both fall through and the
 * server does what it always did.
 */
public class ArrivalListener implements Listener {

    private final ArrivalPreparation arrival;

    public ArrivalListener(ArrivalPreparation arrival) {
        this.arrival = arrival;
    }

    /**
     * Runs last, so a login another plugin has already refused is not searched for. Blocking here is
     * deliberate and safe: this is the login thread for one connection, not the server thread.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        arrival.prepare(event.getUniqueId(), event.getName());
    }

    /**
     * Runs last, so this is the answer the server acts on. What it replaces is the world spawn — the
     * one place this plugin exists to keep people away from, and the place vanilla puts everybody
     * whose player file does not already name somewhere else.
     *
     * <p>There is no player here to ask, only the connection being configured, which is the whole
     * reason to prefer this event: the player is built <em>after</em> this answer rather than being
     * brought into existence early to receive it. The profile on the connection carries the same id
     * the handshake prepared against, so a placement is looked up exactly as before.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        UUID uuid = event.getConnection().getProfile().getId();
        if (uuid == null) return;

        Location prepared = arrival.claim(uuid);
        if (prepared != null) {
            event.setSpawnLocation(prepared);
        }
    }

    /**
     * A player who logs out before their join is recorded leaves a placement behind. Dropping it here
     * keeps the common case tidy; the age limit in {@link ArrivalPreparation} covers the rest, since
     * a login that fails between the handshake and the join never reaches this event either.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        arrival.discard(event.getPlayer().getUniqueId());
    }
}
