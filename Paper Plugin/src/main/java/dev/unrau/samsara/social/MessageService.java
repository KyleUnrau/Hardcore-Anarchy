package dev.unrau.samsara.social;

import dev.unrau.samsara.config.PluginConfig;
import dev.unrau.samsara.config.SocialConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Private messages: the one thing besides a contact that crosses the map.
 *
 * <p>An addressed message is not the problem proximity chat was solving. Nobody is made to read it,
 * it names exactly one person, and on a server where the people you know are a thousand kilometres
 * away it is the only way to say something to them before either of you has agreed to anything
 * permanent. So distance does not apply to it at all.
 *
 * <p>What does apply is {@code /ignore}, absolutely, and {@link MessageRateLimiter}, which is aimed
 * at the shape of advertising rather than at the pace of conversation.
 *
 * <p>The wording and colours are vanilla's own: the same two translation keys the {@code /msg}
 * command uses, so every client renders them in its own language exactly as it would have.
 */
public class MessageService {

    /** Exempt from the anti-spam rules. For staff announcements, not for playing. */
    public static final String UNLIMITED_PERMISSION = "samsara.social.unlimited";

    private final PluginConfig config;
    private final SocialStore store;
    private final SocialGraph graph;
    private final MessageRateLimiter limiter;

    /** Who each player would be answering if they typed {@code /r}. Session-scoped, deliberately. */
    private final Map<UUID, UUID> replyTargets = new ConcurrentHashMap<>();

    public MessageService(PluginConfig config, SocialStore store, SocialGraph graph) {
        this.config = config;
        this.store = store;
        this.graph = graph;
        this.limiter = new MessageRateLimiter(() -> {
            SocialConfig social = config.getSocial();
            return new MessageRateLimiter.Limits(
                social.getMessageMinIntervalMillis(),
                social.getMessageWindowSeconds(),
                social.getMaxUniqueRecipientsPerWindow(),
                social.getMaxDuplicateRecipients(),
                social.getMaxMessagesPerWindow());
        });
    }

    /**
     * Sends a private message, telling the sender whatever went wrong.
     *
     * <p>Everything the {@code /msg} command does, so that the command, its aliases and the
     * interception of vanilla's own {@code /msg} all behave identically rather than nearly so.
     *
     * @return true if the message was delivered
     */
    public boolean send(Player from, String targetName, String message) {
        SocialConfig social = config.getSocial();
        if (!social.isEnabled() || !social.isMessagesEnabled()) {
            from.sendMessage(error("Private messages are disabled on this server."));
            return false;
        }
        if (message == null || message.isBlank()) {
            from.sendMessage(error("Say something."));
            return false;
        }

        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !target.isOnline()) {
            from.sendMessage(error(targetName + " is not online."));
            return false;
        }
        if (target.equals(from)) {
            from.sendMessage(error("You are already listening."));
            return false;
        }

        return deliver(from, target, message);
    }

    /** Answers whoever last spoke to this player privately, if they are still online. */
    public boolean reply(Player from, String message) {
        UUID targetId = replyTargets.get(from.getUniqueId());
        if (targetId == null) {
            from.sendMessage(error("Nobody has messaged you, and you have messaged nobody."));
            return false;
        }

        Player target = Bukkit.getPlayer(targetId);
        if (target == null || !target.isOnline()) {
            from.sendMessage(error("They are not online any more."));
            return false;
        }
        return send(from, target.getName(), message);
    }

    private boolean deliver(Player from, Player target, String message) {
        // The recipient's switch, and it is absolute. The sender is told the message did not land,
        // because silence would only have them say it again — but they are told it as a fact about
        // delivery rather than as a decision somebody made, and nothing here is reported back to
        // the player who made it.
        if (store.load(target.getUniqueId()).ignores(from.getUniqueId())) {
            from.sendMessage(error(target.getName() + " is not receiving your messages."));
            return false;
        }

        if (!from.hasPermission(UNLIMITED_PERMISSION)) {
            boolean toContact = graph.areContacts(from.getUniqueId(), target.getUniqueId());
            MessageRateLimiter.Verdict verdict =
                limiter.attempt(from.getUniqueId(), target.getUniqueId(), message, toContact);
            if (verdict != MessageRateLimiter.Verdict.ALLOWED) {
                from.sendMessage(error(explain(verdict)));
                return false;
            }
        }

        target.sendMessage(Component.translatable("commands.message.display.incoming",
            from.displayName(), Component.text(message))
            .color(NamedTextColor.GRAY).decorate(TextDecoration.ITALIC));

        from.sendMessage(Component.translatable("commands.message.display.outgoing",
            target.displayName(), Component.text(message))
            .color(NamedTextColor.GRAY).decorate(TextDecoration.ITALIC));

        // Logged in full, as vanilla logs its own whispers: the server's record does not have holes
        // in it just because the conversation was private between the two of them.
        Bukkit.getConsoleSender().sendMessage(Component.text(
            "[msg] " + from.getName() + " -> " + target.getName() + ": " + message));

        replyTargets.put(target.getUniqueId(), from.getUniqueId());
        replyTargets.put(from.getUniqueId(), target.getUniqueId());

        // A conversation with somebody you have switched off is one you will hear no answer to.
        // Saying so once is kinder than letting them wonder.
        if (store.load(from.getUniqueId()).ignores(target.getUniqueId())) {
            from.sendMessage(error("You are ignoring " + target.getName()
                + "; nothing they send back will reach you."));
        }
        return true;
    }

    /**
     * Forgets a player's reply target and rate-limit history. Called when they leave.
     *
     * <p>Both directions: everybody who was about to answer them is pointed at somebody who is no
     * longer there, and a {@code /r} into that would be a message sent to a name that has gone.
     */
    public void forget(UUID who) {
        replyTargets.remove(who);
        replyTargets.values().removeIf(who::equals);
        limiter.forget(who);
    }

    private String explain(MessageRateLimiter.Verdict verdict) {
        SocialConfig social = config.getSocial();
        return switch (verdict) {
            case TOO_FAST -> "Slow down.";
            case TOO_MANY_MESSAGES -> "You have sent too many messages in the last "
                + social.getMessageWindowSeconds() + " seconds.";
            case TOO_MANY_RECIPIENTS -> "You are messaging too many people you have no contact with."
                + " Wait a while, or add the ones you actually talk to as contacts.";
            case DUPLICATE_BROADCAST -> "You have already sent that to several people.";
            case ALLOWED -> "";
        };
    }

    private static Component error(String text) {
        return Component.text(text, NamedTextColor.RED);
    }
}
