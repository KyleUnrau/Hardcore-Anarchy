package dev.unrau.samsara;

import dev.unrau.samsara.command.ContactCommand;
import dev.unrau.samsara.command.IgnoreCommand;
import dev.unrau.samsara.command.MessageCommand;
import dev.unrau.samsara.command.PathCommand;
import dev.unrau.samsara.command.SamsaraCommand;
import dev.unrau.samsara.config.PluginConfig;
import dev.unrau.samsara.config.PresentationConfig;
import dev.unrau.samsara.data.PlayerDataStore;
import dev.unrau.samsara.handler.EnderChestHandler;
import dev.unrau.samsara.help.ServerHelp;
import dev.unrau.samsara.listener.*;
import dev.unrau.samsara.log.ExileLogImport;
import dev.unrau.samsara.log.PlayerJournal;
import dev.unrau.samsara.path.PathMessages;
import dev.unrau.samsara.path.PathService;
import dev.unrau.samsara.path.PathStore;
import dev.unrau.samsara.service.ArrivalPreparation;
import dev.unrau.samsara.service.DimensionalTravelService;
import dev.unrau.samsara.service.EndGatewayNetwork;
import dev.unrau.samsara.service.EndSiteBuilder;
import dev.unrau.samsara.service.ExileSpawnService;
import dev.unrau.samsara.service.SafeLocationFinder;
import dev.unrau.samsara.service.TransitHold;
import dev.unrau.samsara.social.SocialService;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class SamsaraPlugin extends JavaPlugin {

    private EndGatewayNetwork gatewayNetwork;
    private SocialService social;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        PluginConfig config = new PluginConfig(this);
        PlayerDataStore dataStore = new PlayerDataStore(this);
        adoptLegacyExileLog(config, dataStore);
        PlayerJournal journal = new PlayerJournal(dataStore, config);
        SafeLocationFinder locationFinder = new SafeLocationFinder(config);
        ExileSpawnService spawnService = new ExileSpawnService(this, config, locationFinder, dataStore, journal);
        EnderChestHandler enderChestHandler = new EnderChestHandler(config);
        EndSiteBuilder siteBuilder = new EndSiteBuilder(this);
        ArrivalPreparation arrival = new ArrivalPreparation(this, config, dataStore, spawnService);
        TransitHold transitHold = new TransitHold(this, locationFinder);
        DimensionalTravelService travelService = new DimensionalTravelService(this, config, dataStore,
            locationFinder, journal, siteBuilder, transitHold);
        gatewayNetwork = new EndGatewayNetwork(this, travelService);
        social = new SocialService(this, config);

        // Paths sit on top of everything above rather than beside it. Only one of a player's paths
        // is ever being walked, and the one being walked is the ordinary player record the exile,
        // travel and journal systems already act on — so none of them has to know this exists.
        PathMessages pathMessages = new PathMessages(config);
        PathService paths = new PathService(this, config, new PathStore(getDataFolder(), getLogger()),
            dataStore, spawnService, travelService, journal, pathMessages, social);

        getServer().getPluginManager().registerEvents(new PathListener(paths), this);
        getServer().getPluginManager().registerEvents(new ArrivalListener(arrival), this);
        getServer().getPluginManager().registerEvents(new TransitRecoveryListener(travelService), this);
        getServer().getPluginManager().registerEvents(new FirstJoinListener(this, dataStore, spawnService, journal, arrival), this);
        getServer().getPluginManager().registerEvents(new DeathListener(dataStore, enderChestHandler, spawnService, travelService, journal), this);
        getServer().getPluginManager().registerEvents(new RespawnListener(this, dataStore, spawnService, travelService), this);
        getServer().getPluginManager().registerEvents(new EndPortalListener(travelService), this);
        getServer().getPluginManager().registerEvents(new EndPortalIgnitionListener(config), this);
        getServer().getPluginManager().registerEvents(new EndGatewayListener(travelService), this);
        getServer().getPluginManager().registerEvents(new NaturalGatewayListener(this, travelService), this);

        registerSocial(paths, pathMessages);

        PathCommand pathCommand = new PathCommand(paths);
        bind("path", pathCommand, pathCommand);

        ServerHelp serverHelp = new ServerHelp(config);
        registerPresentation(config, serverHelp);

        String version = getDescription().getVersion();
        getCommand("samsara").setExecutor(
            new SamsaraCommand(config, spawnService, travelService, gatewayNetwork, social, paths, version));

        // One line at startup, and nothing else. What the plugin is configured to do is answered on
        // demand by /samsara version and the config file itself; the console does not need it read
        // aloud every boot. Anything genuinely wrong still speaks up as a warning.
        getLogger().info("Samsara v" + version + " enabled. The world remembers. You do not.");
        gatewayNetwork.start();
        social.start();
    }

    @Override
    public void onDisable() {
        if (gatewayNetwork != null) {
            gatewayNetwork.stop();
        }
        if (social != null) {
            // Contacts are written when they are made, but the progress towards one is only in
            // memory between flushes. Stopping cleanly is what stops a restart costing somebody
            // half an evening of standing next to their neighbour.
            social.stop();
        }
        getLogger().info("Samsara disabled.");
    }

    /**
     * Registers the social layer — proximity chat, contacts, private messages, ignore.
     *
     * <p>The listeners go on whatever the configuration currently says, because each of them asks
     * again at the moment an event arrives. That is what lets {@code /samsara reload} turn the whole
     * system on or off on a running server: a listener registered only when the feature was enabled
     * would still be absent an hour later when somebody turned it back on.
     */
    private void registerSocial(PathService paths, PathMessages pathMessages) {
        getServer().getPluginManager().registerEvents(new SocialChatListener(social), this);
        getServer().getPluginManager().registerEvents(
            new SocialPresenceListener(this, social, paths, pathMessages), this);
        getServer().getPluginManager().registerEvents(new SocialDeathListener(social), this);
        getServer().getPluginManager().registerEvents(new SocialAdvancementListener(social), this);
        getServer().getPluginManager().registerEvents(new PetDeathListener(social), this);
        getServer().getPluginManager().registerEvents(new SocialCommandListener(social), this);

        ContactCommand contacts = new ContactCommand(social);
        bind("contact", contacts, contacts);
        IgnoreCommand ignore = new IgnoreCommand(social, false);
        bind("ignore", ignore, ignore);
        IgnoreCommand unignore = new IgnoreCommand(social, true);
        bind("unignore", unignore, unignore);

        // /msg and its aliases are registered here as well as intercepted in SocialCommandListener.
        // The interception is what actually wins the plain labels, since vanilla owns those; this is
        // what gives the command a usage line, a tab completer and a namespaced form of its own.
        MessageCommand message = new MessageCommand(social, false);
        bind("msg", message, message);
        MessageCommand reply = new MessageCommand(social, true);
        bind("reply", reply, reply);
    }

    /** Attaches an executor to a command declared in plugin.yml, tolerating one that is not. */
    private void bind(String name, CommandExecutor executor, TabCompleter completer) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().warning("[Samsara] Command '" + name + "' is missing from plugin.yml;"
                + " that part of the social system is unreachable.");
            return;
        }
        command.setExecutor(executor);
        command.setTabCompleter(completer);
    }

    /**
     * Hands the old server-wide {@code exile-log.csv} back to the players it was about, if one is
     * still there, and then retires the file. Nothing writes to it any more: a player's history is
     * part of that player's record now.
     *
     * <p>A server whose journal is switched off is left entirely alone — the file stays where it
     * is, unread, because turning the journal off is a statement that this history is not wanted.
     */
    private void adoptLegacyExileLog(PluginConfig config, PlayerDataStore dataStore) {
        File log = new File(getDataFolder(), ExileLogImport.LEGACY_LOG_NAME);
        if (!log.isFile()) return;

        if (!config.isJournalEnabled()) {
            getLogger().info("Found " + ExileLogImport.LEGACY_LOG_NAME
                + ", but the journal is switched off; leaving it untouched and unread.");
            return;
        }
        new ExileLogImport(dataStore, getLogger()).run(getDataFolder(), config.getJournalMaxEntries());
    }

    /**
     * Registers what the server is willing to tell a player who asks.
     *
     * <p>Only {@code /help} is involved. The server list entry belongs to server.properties and is
     * not touched: nothing here listens for a ping.
     *
     * <p>Help topics have to be filed here, while the plugin is enabling: the help map is emptied
     * before plugins load and its index is built once they have all finished, so a topic added at
     * any later moment would exist but never be listed.
     */
    private void registerPresentation(PluginConfig config, ServerHelp serverHelp) {
        PresentationConfig presentation = config.getPresentation();

        if (presentation.isHelpTopics()) {
            serverHelp.register(getServer().getHelpMap());
        }
        // The landing page is registered even with topics off: it stands on its own, and a server
        // that wanted neither has turned both keys off.
        if (presentation.isHelpLandingPage()) {
            getServer().getPluginManager().registerEvents(
                new HelpLandingListener(config, serverHelp), this);
        }
    }

}
