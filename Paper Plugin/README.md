# Hardcore Exile Anarchy

A Paper plugin for Minecraft 1.21.4 that turns death into exile rather than a respawn. The world persists — your position does not.

## Core Rules

- **First join**: you are teleported to a random safe location far from 0,0.
- **On death**: your Ender chest contents drop at your death location. You respawn at a new random safe location far from your death location and far from 0,0.
- **Beds**: can still be slept in to skip the night, but they do not set your respawn point.
- **Respawn anchors**: the block exists, but it does not bind your respawn.
- **Inventory and XP**: lost on death as in vanilla.
- **Bases, chests, farms, portals**: untouched. World-bound things persist.

## Requirements

- Paper 1.21.4 or newer
- Java 21

## Installation

1. Build the jar: `mvn package`
2. Copy `target/hardcore-anarchy-1.0.0-SNAPSHOT.jar` into your server's `plugins/` folder.
3. Start the server. A default `config.yml` is generated automatically.

## Configuration

`plugins/HardcoreAnarchy/config.yml`:

```yaml
# The name of the overworld used for exile spawning
worldName: world

# First join: random location between these distances from 0,0 (blocks)
firstJoinMinDistanceFromZero: 50000
firstJoinMaxDistanceFromZero: 500000

# Death respawn: random location within this ring from 0,0
deathRespawnMinDistanceFromZero: 250000
deathRespawnMaxDistanceFromZero: 5000000

# Death respawn: also at least this far from the death location
deathRespawnMinDistanceFromDeath: 500000

# Terrain safety filters
avoidOcean: true
avoidLava: true

# How many candidate locations to try before giving up (uses best fallback)
maxSafeSpawnAttempts: 64

# Log all spawn/death/exile events to exile-log.txt
logSpawnLocations: true

# Ender chest behaviour on death
clearEnderChestOnDeath: true
dropEnderChestContentsOnDeath: true

# Prevent beds and respawn anchors from binding respawn
disableBedRespawn: true
disableRespawnAnchorRespawn: true
```

## Commands

All commands require the `hardcoreanarchy.admin` permission (granted to ops by default).

| Command | Description |
|---------|-------------|
| `/hea version` | Show plugin version |
| `/hea reload` | Reload config.yml from disk |
| `/hea debugspawn` | Find and report a spawn location without teleporting |

## Logs

Exile events are appended to `plugins/HardcoreAnarchy/exile-log.txt`:

```
2026-05-31T14:23:45Z | FIRST_JOIN    | uuid=... | player=Steve | world=world | x=123456 | y=64 | z=-78901
2026-05-31T14:25:12Z | DEATH         | uuid=... | player=Steve | world=world | x=12345  | y=64 | z=-6789
2026-05-31T14:25:12Z | EXILE_RESPAWN | uuid=... | player=Steve | world=world | x=456789 | y=72 | z=123456
```

## Design Philosophy

**World-bound things persist. Player-bound things die.**

Bases, ordinary chests, farms, roads, portals, tunnels, ruins, and hidden stashes remain in the world forever. But when a player dies, their personal continuity is destroyed and they are exiled far away — including the contents of their Ender chest, which drops into the world at the death location.
