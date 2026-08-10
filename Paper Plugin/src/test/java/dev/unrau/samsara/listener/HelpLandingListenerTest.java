package dev.unrau.samsara.listener;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The landing page is the one thing this plugin puts in front of a command a player already knows,
 * so the test that matters is not that it appears — it is that it gets out of the way. Every form
 * of /help that carries an argument has to reach Paper's help command untouched, or the page a
 * player is shown becomes a page they cannot get past.
 */
class HelpLandingListenerTest {

    @Test
    void claimsBareHelp() {
        assertTrue(HelpLandingListener.isBareHelp("/help"));
        assertTrue(HelpLandingListener.isBareHelp("/?"));
        assertTrue(HelpLandingListener.isBareHelp("/HELP"));
        assertTrue(HelpLandingListener.isBareHelp("/help "),
            "a trailing space is still a player typing /help and pressing enter");
    }

    @Test
    void claimsNamespacedHelp() {
        // The client offers these in tab completion, so players do send them.
        assertTrue(HelpLandingListener.isBareHelp("/bukkit:help"));
        assertTrue(HelpLandingListener.isBareHelp("/minecraft:help"));
    }

    @Test
    void leavesEveryArgumentedFormAlone() {
        assertFalse(HelpLandingListener.isBareHelp("/help 1"),
            "/help 1 is the command index and must stay the command index");
        assertFalse(HelpLandingListener.isBareHelp("/help rules"));
        assertFalse(HelpLandingListener.isBareHelp("/help hea"));
        assertFalse(HelpLandingListener.isBareHelp("/bukkit:help 2"));
    }

    @Test
    void leavesOtherCommandsAlone() {
        assertFalse(HelpLandingListener.isBareHelp("/samsara"));
        assertFalse(HelpLandingListener.isBareHelp("/helpme"),
            "a command that merely starts with 'help' is somebody else's command");
        assertFalse(HelpLandingListener.isBareHelp("/"));
        assertFalse(HelpLandingListener.isBareHelp(""));
    }
}
