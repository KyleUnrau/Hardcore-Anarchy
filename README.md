# Samsara

## Overview

Samsara is a Minecraft server project that seeks to reimagine Hardcore mode by preserving the permanence of the world while eliminating the permanence of player death.

In traditional Hardcore Minecraft, death removes a player from the game. On Samsara, death removes a player from their current life. The world, its history, its structures, its civilizations, and its conflicts remain. The player must begin again elsewhere and decide whether to rebuild, survive locally, or undertake the long journey back to what was lost.

The project consists of two primary components:

* **Server Plugin** — Implements the gameplay mechanics and server rules.
* **Resource Pack** — Provides the visual presentation and atmosphere that communicates the server's identity to players.

Together, these components create a multiplayer experience focused on permanence, distance, exploration, survival, and the consequences of death.

---

# Design Philosophy

The central principle of Samsara is:

> The world remembers. The player does not.

Death should be significant.

However, unlike traditional Hardcore mode, death does not remove a player from the server. Instead, it destroys the continuity of that player's current life while preserving the continuity of the world itself.

A player's bases, structures, roads, portals, farms, hidden caches, and history remain exactly where they were. The challenge becomes regaining access to them.

The goal is to create a world where:

* Locations matter.
* Infrastructure matters.
* Exploration matters.
* Distance matters.
* Civilization matters.
* Death matters.

---

# Core Experience

Samsara is intended to create a world that feels less like a game session and more like a persistent civilization.

Players may:

* Establish settlements.
* Build roads and transportation networks.
* Create hidden caches and recovery locations.
* Form alliances and factions.
* Leave behind ruins, monuments, and history.

When death occurs, those creations remain.

The player does not lose because the world was reset.

The player loses because they have been separated from the world they built.

---

# Relationship to Hardcore Minecraft

Samsara is inspired by Hardcore Minecraft but is not intended to replicate vanilla Hardcore mechanics.

The objective is to preserve the emotional weight of Hardcore mode while replacing permanent removal from the server with a system of exile and renewal.

A death should feel like the end of a life rather than the end of a character.

---

# Relationship to Anarchy Servers

Samsara embraces many principles commonly associated with anarchy servers:

* Minimal administrative intervention.
* Player-driven history.
* Persistent consequences.
* Emergent social structures.
* Freedom of action.

However, the project also seeks to preserve the integrity of the world itself.

The server should encourage experimentation, automation, exploration, and player freedom while protecting the core systems that make distance, discovery, and survival meaningful.

The line this draws is the one the server states to players in `/help rules`:

> Administration governs the integrity of the server. Players govern the world.

Conflict inside the world — killing, stealing, raiding, trapping, betrayal, territorial disputes — is
gameplay, and no administrator will undo it. What is enforced sits outside the world: attacks on the
host or the software, conduct that Minecraft's own rules and Microsoft's Community Standards already
prohibit, and abuse that makes chat unusable. Technical Minecraft carries a strong presumption of
being allowed; something is only an exploit here if it attacks the server rather than the game.

---

# Plugin Goals

The Samsara plugin exists to enforce the gameplay rules and systems that define the server.

Its purpose is to:

* Manage death consequences.
* Preserve world persistence.
* Enforce exile mechanics.
* Maintain long-term world integrity.
* Create meaningful consequences without removing players from the game.

The plugin should be considered the authoritative source of gameplay behavior.

It is also the source of the server's presentation: the help topics that let a player look the
server up rather than learn it by dying. The server list entry is left to `server.properties`. See
[Paper Plugin/README.md](Paper%20Plugin/README.md).

---

# Resource Pack Goals

The Samsara Resource Pack exists to communicate the identity of the server through visual presentation.

Its purpose is to:

* Reinforce the atmosphere of Samsara.
* Present players with a unique visual identity.
* Communicate that this is not a standard survival server.
* Support immersion without altering gameplay mechanics.

The resource pack should make the world feel consistent with the themes of consequence, persistence, exile, and renewal.

---

# Long-Term Vision

The long-term goal of Samsara is to create a persistent world where stories emerge naturally from player actions.

The project aims to encourage:

* Forgotten civilizations.
* Long-distance journeys.
* Hidden settlements.
* Lost empires.
* Recoveries and returns.
* Exploration of a vast and persistent world.

Players should feel that every structure, road, tunnel, cache, and coordinate has value because the world continues to exist long after the life that created it has ended.

Samsara is not a game about preventing death.

It is a game about living with its consequences.
