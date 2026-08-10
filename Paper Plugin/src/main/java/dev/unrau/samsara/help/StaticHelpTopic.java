package dev.unrau.samsara.help;

import org.bukkit.command.CommandSender;
import org.bukkit.help.HelpTopic;

import java.util.function.Supplier;

/**
 * A help topic with no command behind it — a subject rather than a syntax.
 *
 * <p>Bukkit builds these itself from {@code help.yml}'s general topics, but only for text that sits
 * in a file the plugin does not own. This is the same thing, sourced from the plugin, so the server
 * can describe itself without an administrator having to paste prose into the server's own config.
 *
 * <p>The body is a supplier rather than a string because the help map refuses a second topic under
 * a name it already holds: the object registered while the server starts is the object players read
 * for the rest of that server's life. Building the text on demand is what lets {@code /samsara reload}
 * change what a topic says. The one thing a reload cannot change is the topic's <em>name</em>,
 * since that is the key it was filed under.
 */
public class StaticHelpTopic extends HelpTopic {

    private final Supplier<String> body;

    public StaticHelpTopic(String name, String shortText, Supplier<String> body) {
        this.name = name;
        this.shortText = shortText;
        this.body = body;
    }

    /**
     * Always visible. These topics describe rules that already apply to everyone reading them, so
     * there is nothing here to withhold from anybody.
     */
    @Override
    public boolean canSee(CommandSender sender) {
        return true;
    }

    @Override
    public String getFullText(CommandSender sender) {
        return body.get();
    }
}
