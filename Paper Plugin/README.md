# Samsara

The Paper plugin behind Samsara, a Minecraft 26.2 server where death is exile rather than a
respawn. The world persists — your position does not.

The plugin and the server share one name. The jar is `samsara`, it installs to `plugins/Samsara/`,
its admin command is `/samsara`, and *Samsara* is what a player sees wherever the server names
itself — `/help` and the resource pack.

Servers upgrading from 2.3.x, when this plugin was called HardcoreAnarchy, must rename
`plugins/HardcoreAnarchy/` to `plugins/Samsara/` before starting the server; otherwise the plugin
generates a fresh data folder and every player's exile record is left behind in the old one. The
admin command is now `/samsara` rather than `/hea`, and the permission node is `samsara.admin`
rather than `hardcoreanarchy.admin` — regrant it to anyone who is not an operator.

## Core Rules

- **First join**: you wake at a random safe location, which by default may be anywhere in the world. It is chosen and its terrain generated during the login itself, so it is the first ground you are ever sent — you are never placed at the world spawn and moved afterwards.
- **On death**: your Ender chest contents drop at your death location. You respawn at a new random safe location, far from where you died. First join and death draw from the same band of coordinates — a new life is a new life however it began.
- **Paths**: one account may keep up to five separate existences and live one at a time, each with its own position, inventory, ender chest, experience and history. Dying costs you none of them — the life ends, the path keeps its name and receives the next one. `/path new <name> <player...>` begins one alongside other players, in the same place, if all of them agree. Abandoning a path drops everything it was carrying into the world where it stood; nothing ever passes between paths. See [docs/paths.md](docs/paths.md).
- **Beds**: can still be slept in to skip the night, but they do not set your respawn point.
- **Respawn anchors**: the block exists, but it does not bind your respawn.
- **End travel**: preserves the current life. Only death creates exile. The End is a reflection of the Overworld — a portal at `(x, z)` arrives at `(-z, -x)`, and the gateway on the platform there reflects back the same way, to the portal you came from. Every other End gateway is a two-way wormhole to a distant part of the End, so nothing found in the wild leads to the Overworld. Overworld ↔ Nether is vanilla. See [docs/dimensional-travel.md](docs/dimensional-travel.md).
- **Chat**: local. What you say — and your deaths, advancements, joins and leaves — reaches players within `social.radius` blocks of you in the same world, and nobody else. A **contact** is somebody who sees all of it at any distance; contacts are mutual, are asked for and accepted, can be ended by either player, and survive logout, death and exile. `/msg` crosses any distance, `/ignore` beats everything. See [docs/social.md](docs/social.md).
- **Inventory and XP**: lost on death as in vanilla.
- **Bases, chests, farms, portals**: untouched. World-bound things persist.
- **Strongholds**: spread across the whole world by the companion data pack, rather than ringed around origin.

Players can read all of this in game with `/help` — see [What players are told](#what-players-are-told).

## Requirements

- Paper 26.2 or newer
- Java 25

## Installation

1. Build the jar: `mvn package`
2. Copy `target/samsara-2.7.3.jar` into your server's `plugins/` folder.
3. Start the server. A default `config.yml` is generated automatically.

## Configuration

`plugins/Samsara/config.yml`:

```yaml
# What the server calls itself, and what it tells a player who asks
presentation:
  name: Samsara
  tagline: The world remembers. You do not.
  help:
    topics: true
    landingPage: true

# The name of the overworld used for exile spawning
worldName: world

# How the distances below are measured from 0,0: square (per axis) or circle (a radius)
spawnAreaShape: square

# The band new lives are placed in, in blocks from 0,0 — first join and death respawn alike
spawnMinDistanceFromZero: 0
spawnMaxDistanceFromZero: 29000000

# Death respawn: also at least this far from the death location
deathRespawnMinDistanceFromDeath: 500000

# Never place anyone outside the world border
respectWorldBorder: true

# Terrain safety filters
avoidOcean: true
avoidLava: true

# How many candidate locations to try before giving up (uses best fallback)
maxSafeSpawnAttempts: 64

# Settle where a player wakes during the login itself, before any chunk is sent to them, so nobody
# is ever placed at the world spawn and moved afterwards
arrival:
  prepareBeforeJoin: true
  timeoutSeconds: 20
  preloadRadius: 48

# Each player's history, kept on that player's own record
journal:
  enabled: true
  maxEntries: 200

# Ender chest behaviour on death: drop the contents at the death location and empty the vault
dropEnderChestOnDeath: true

# The separate existences one account may keep — see docs/paths.md
paths:
  enabled: true
  max: 5
  defaultName: Original
  invitationTimeoutSeconds: 120
  messages:
    join: ""    # empty leaves connecting and disconnecting to vanilla, translations included
    quit: ""
    departure: "&e%player% left their path"
    arrival: "&e%player% entered their path"

# How far the End portal activation roar carries, in blocks (0 silences it)
endPortal:
  activationSoundRadius: 64

# Who can see whom — see docs/social.md
social:
  enabled: true
  radius: 256
  contacts:
    enabled: true
    max: 100
    requestExpirySeconds: 300
    requestCooldownSeconds: 300
    auto:
      enabled: true
      defaultOn: true
      radius: 48
      closeRadius: 16
      requiredMinutes: 20
      decayRate: 0.25
      forgetAfterMinutes: 180
  messages:
    enabled: true
    maxUniqueRecipientsPerWindow: 6
    maxDuplicateRecipients: 3
  pets:
    enabled: true
    radius: 64

# End travel — see docs/dimensional-travel.md for the full table
dimensionalTravel:
  enabled: true
  endWorldName: world_the_end
  arrivalSiteSpacing: 16
  arrivalPlatformRadius: 3
  arrivalPlatformY: 64
  buildArrivalSites: true
  centralIslandProtectRadius: 1024
  gateways:
    enabled: true
    spacing: 512
    separation: 128
    materialiseRadius: 192
    scanIntervalTicks: 100
  wormholes:
    enabled: true
    cellSize: 16
    seed: 0
  returnSearchRadius: 16
  portalCooldownTicks: 100
  immediateTransition: true
  debug: false
```

A config that still uses the old `endTravel:` section name, or the old
`deathRespawnMinDistanceFromZero` / `deathRespawnMaxDistanceFromZero` key names, is read as-is, so
an existing server keeps working without edits. Keys that no longer do anything — `overworldToEndScale`,
`netherFromEnd`, `netherPortals`, `netherWorldName`, `wormholes.reach` — are ignored with a warning
naming each one. `wormholes.reach` is retired because it could only make the network *smaller than
the End*, and gateways beyond its edge all shared one destination and none of them led home again;
the network now always spans the End's world border.
Overworld ↔ Nether travel is vanilla in both directions and is not touched by this plugin.

### Lighting an End portal

Vanilla plays the activation roar as a *global* event — the same machinery behind a wither spawning
and the dragon dying. The packet goes to every player on the server and the client places the sound
two blocks from your ear whatever the real distance is, so a portal lit in the middle of nowhere is
heard at full volume by everyone, in every dimension. On a map where nobody shares a front door,
that announces to the whole server that somebody found a stronghold.

`endPortal.activationSoundRadius` replaces it with an ordinary positional sound at the portal.
Anyone within that many blocks hears exactly what they always heard; nobody further away is sent
anything at all. `0` silences it, `16` makes it as loud as any other block sound, and larger values
reach further without being louder up close. Values above 512 are clamped.

Nothing else about lighting a portal changes. The eyes still go in one at a time with their usual
click, the frame still fills, the portal still opens on the twelfth eye, and it is still the player
who spends the eye — the plugin only takes over that last placement so the roar can be local. A
protection or claim plugin that would have blocked the interaction still blocks it.

### Spawn area shape

`spawnAreaShape` decides how `spawnMinDistanceFromZero` and `spawnMaxDistanceFromZero` are read.
It only matters near the edge of the map:

| Shape | Meaning | With a max of 29000000 |
|-------|---------|------------------------|
| `square` | Distance is per axis, `max(\|x\|, \|z\|)` | The whole square, corners included. This is the default. |
| `circle` | Distance is a radius, `sqrt(x² + z²)` | A circle of radius 29m. The corners are unreachable — nobody lands at 29m,29m, which is a radius of ~41m. |

Both shapes spread players evenly by area rather than by distance, so the inner edge of the band
is no more crowded than the outer edge. At the defaults — `square`, `0` to `29000000` — that means
a new life can begin almost anywhere the world will generate, with every part of the band equally
likely.

The maximum stops a million blocks short of the world's own `29999984` limit. That last million is
where the terrain generator's arithmetic starts to fray, and a life that begins out there begins
somewhere subtly wrong; the band given up is one nobody will ever notice is missing. Set `30000000`
for literally the whole map — it is clamped to `29999984` silently, because it is not a mistake.

With `respectWorldBorder: true` the maximum is narrowed to whatever actually fits inside the
current world border, so a border set smaller than the configured maximum never leaves anyone
outside the wall. Run `/samsara debugspawn` to see the band the plugin resolved.

## What players are told

Everything a player learns about this server, they learn by asking for it: `/help`, and nothing
else. There is no welcome message, no tab list header or footer, no scoreboard and no chat prefixes.
Nothing this plugin *does* is announced while it happens — no exile, no arrival, no journey is ever
mentioned to anybody.

Vanilla's own announcements — joins, leaves, deaths, advancements — are kept exactly as vanilla wrote
them, and only their audience is narrowed. See [docs/social.md](docs/social.md).

The server list entry is not this plugin's business either. The MOTD comes from
`server.properties`, exactly as it would if the plugin were not installed — no ping listener is
registered, so anything else that wants to own the server list can.

### /help

Paper's help is left completely intact. The command index, `/help <page>`, `/help <command>` and
every command's own usage page all behave exactly as they would without this plugin. Two things are
added on top:

**Topics.** Eight subjects are filed with the server's help map, so they answer to `/help <topic>` and
are listed in the ordinary `/help` index above the commands:

| Topic | Covers |
|-------|--------|
| `/help samsara` | The premise: the world is permanent, you are not. |
| `/help rules` | The three things administration enforces, and the fact that nothing inside the world is one of them. |
| `/help death` | Inventory, experience, and the ender chest dropping at the death site. |
| `/help respawn` | No world spawn, no bed respawn, and the real distances a new life is drawn from. |
| `/help paths` | Keeping several existences, that dying costs none of them, and that abandoning one drops everything. |
| `/help chat` | Chat is local, what a contact is and is not, and that `/ignore` beats both. |
| `/help end` | What each kind of End door does: portals, the platform that leads home, and wormholes. |
| `/help strongholds` | Strongholds are scattered rather than ringed around origin. |

`/help samsara` is named after `presentation.name`, so renaming the server renames the topic.

Each topic reads its facts from the live configuration rather than restating them in prose, so a
server that turns a mechanic off stops telling players it is on: `/help death` describes an ender
chest that survives when `dropEnderChestOnDeath` is false, `/help respawn` quotes whatever
`deathRespawnMinDistanceFrom*` actually say, and `/help end` describes a vanilla End when
`dimensionalTravel.enabled` is false.

`/help end` draws the line between the two kinds of door, because the End punishes a wrong guess
about either. It says that an End portal lands you in a region of its own rather than at End 0,0,
that the platform you arrive on carries the only way back to the Overworld, and that every other
gateway — the dragon's included — is a wormhole that throws you far across the End and back again
but never home. What it does not say is the machinery: the reflection the End is built on, how
wormholes are paired, or where to find them. Knowing the rules is not the same as being handed the
map.

**Landing page.** A bare `/help` opens a short page about the server instead of page one of the
command index, ending with a line pointing at `/help 1`. Only the bare form is intercepted; `/help 1`
and `/help <anything>` reach Paper's help command untouched. Set `landingPage: false` for the
vanilla behaviour.

Topics are registered while the server starts, because the help map is emptied before plugins load
and its index is built once they have all enabled. `/samsara reload` therefore updates what a topic
*says* but not what it is *called* — renaming the server needs a restart before `/help` answers to
the new name.

## Commands

### Players

No permission is needed for any of these.

| Command | Description |
|---------|-------------|
| `/path` | Which paths you hold, and which one you are walking (also `/paths`) |
| `/path switch <name>` | Leave this existence and take up another |
| `/path new <name>` | Begin a path, alone |
| `/path new <name> <player...>` | Ask those players to begin one alongside you, in the same place |
| `/path accept <player> [name]` | Agree to a shared beginning |
| `/path decline [player]` | Refuse one, or withdraw your own |
| `/path rename <old> <new>` | Rename a path |
| `/path abandon <name> confirm` | Destroy a path and drop everything it was carrying |
| `/contacts` | Who your contacts are |
| `/contact add <player>` | Ask somebody to be a contact |
| `/contact accept <player>` | Accept a request |
| `/contact decline <player>` | Decline a request |
| `/contact remove <player>` | End a contact, from either side |
| `/contact requests` | What is outstanding, in both directions |
| `/contacts auto on\|off` | Let contacts form out of time spent together |
| `/contacts auto allow <player>` | Lift your own side of a severance |
| `/msg <player> <message>` | Private message, at any distance (also `/tell`, `/w`, `/whisper`) |
| `/reply <message>` | Answer whoever last messaged you (also `/r`) |
| `/ignore <player>` | Stop seeing somebody entirely |
| `/ignore list` | Who that is |
| `/unignore <player>` | Undo it |

`samsara.social.observe` (op by default) receives proximity-scoped events from any distance, for
moderation; `/ignore` still overrides it. `samsara.social.unlimited` (op by default) is exempt from
the private-message anti-spam rules.

### Administration

All of these require the `samsara.admin` permission (granted to ops by default).

| Command | Description |
|---------|-------------|
| `/samsara version` | Show plugin version |
| `/samsara reload` | Reload config.yml from disk |
| `/samsara debugspawn` | Find and report a spawn location without teleporting |
| `/samsara map [x z]` | Show every route in and out of a coordinate |
| `/samsara wormholes [x z]` | Show where a wormhole here comes out, and the grid nodes nearby |
| `/samsara expedition [player]` | Show a player's open End journey record |
| `/samsara endrecover <player>` | Force a stuck traveller out of the End |
| `/samsara endrecover clear <player>` | Drop a corrupt journey record without moving anyone |
| `/samsara social [player]` | Contacts, ignores, and progress towards an automatic contact |
| `/samsara paths [player]` | Which paths a player holds and which is active (read-only) |

## Player records

Everything the plugin knows about the path a player is currently walking lives in one file,
`plugins/Samsara/playerdata/<uuid>.json` — the state it acts on, and the journal of what has happened
to them. There is no server-wide log: one player's history is read by opening one player's file, and
deleting a player takes their history with them.

A player's *other* paths are dormant and live under `plugins/Samsara/paths/<uuid>/`, one file each,
plus an `index.json` naming them. The active path never has a file there — it is the player — which
is the invariant an interrupted switch is recovered from. See [docs/paths.md](docs/paths.md).

```json
{
  "dataVersion": 4,
  "hasJoinedBefore": true,
  "deathCount": 2,
  "lifeId": "5f0d1c8e-...",
  "worldUid": "8a6f2b31-...",
  "firstSpawn":  { "world": "world", "x": 123456.5, "y": 64.0, "z": -78901.5 },
  "lastDeath":   { "world": "world", "x": 12345.5,  "y": 64.0, "z": -6789.5 },
  "lastExile":   { "world": "world", "x": 456789.5, "y": 72.0, "z": 123456.5 },
  "hasPendingRespawn": false,
  "journal": [
    { "at": "2026-05-31T14:23:45Z", "reason": "FIRST_JOIN",    "player": "Steve", "world": "world",         "x": 123456, "y": 64, "z": -78901 },
    { "at": "2026-05-31T14:25:12Z", "reason": "END_DEPART",    "player": "Steve", "world": "world_the_end", "x": 7552,   "y": 65, "z": -4096 },
    { "at": "2026-05-31T14:31:08Z", "reason": "END_RETURN",    "player": "Steve", "world": "world",         "x": 120002, "y": 29, "z": -64000 },
    { "at": "2026-05-31T14:42:19Z", "reason": "DEATH",         "player": "Steve", "world": "world",         "x": 12345,  "y": 64, "z": -6789 },
    { "at": "2026-05-31T14:42:19Z", "reason": "EXILE_RESPAWN", "player": "Steve", "world": "world",         "x": 456789, "y": 72, "z": 123456 }
  ]
}
```

While an End journey is open the record also carries an `endExpedition` object, which is what
`/samsara expedition` reads and what brings the traveller home after a restart.

Reasons are `FIRST_JOIN`, `DEATH`, `EXILE_RESPAWN`, `END_DEPART`, `END_RETURN`,
`END_RETURN_NEARBY` and `END_WORMHOLE`. Entries are oldest first, and the oldest are dropped once
there are more than `journal.maxEntries` of them, so a file settles at a size rather than growing
forever. `maxEntries: 0` keeps everything; `journal.enabled: false` records nothing.

The server log is unchanged, and still carries the detail behind every entry — every route taken,
every fallback, every failure to build or teleport.

### Upgrading from 2.4.x

Player records were YAML before this version, and history went to a single `exile-log.csv`. Both
convert themselves and neither needs anything done to it:

- `playerdata/<uuid>.yml` is read once, rewritten as `<uuid>.json` and deleted, the first time that
  player's record is needed. A player who never logs in again keeps their old file, untouched.
- `exile-log.csv` is read once at startup, its rows handed back to the players they name, and the
  file renamed to `exile-log.csv.imported`. Nothing writes to it afterwards; delete it whenever you
  like. A server with `journal.enabled: false` is left alone entirely — the file is not even read.

The old `logSpawnLocations` key is still honoured as `journal.enabled`, so a server that had
logging switched off does not start journalling because the jar was replaced.

### Social records

Who a player knows lives in a separate file, `plugins/Samsara/social/<uuid>.json`:

```json
{
  "dataVersion": 1,
  "name": "Steve",
  "autoContacts": true,
  "contacts": { "5f0d1c8e-...": "Alex" },
  "ignored":  { "8a6f2b31-...": "Herobrine" },
  "autoSuppressed": [ "c31f0a72-..." ],
  "proximity": { "5f0d1c8e-...": { "seconds": 640.5, "at": 1785000000000 } }
}
```

Kept apart from `playerdata/` deliberately. That record describes a **life** and is rewritten on
every death and every dimension crossing; this one describes a **person** and survives all of it —
including switching paths, which is the same judgement that leaves advancements, statistics and
recipes on the account rather than on the existence.
`autoSuppressed` is the list of pairings that were deliberately broken, which is what stops standing
next to somebody rebuilding a contact they ended. `proximity` is the score towards an automatic one —
earned faster the closer two players are, lost while they are apart, and pruned once the fade has
taken it to nothing, whenever the file is written.

Contacts, ignores and severances are written the moment they change; only the proximity progress
waits for `saveIntervalSeconds`. Deleting a player's file leaves them knowing nobody, and leaves the
other half of every contact holding a name that no longer answers — remove both sides, or let
`/contact remove` do it.

## Design Philosophy

**World-bound things persist. Player-bound things die.**

Bases, ordinary chests, farms, roads, portals, tunnels, ruins, and hidden stashes remain in the world forever. But when a player dies, their personal continuity is destroyed and they are exiled far away — including the contents of their Ender chest, which drops into the world at the death location.

Paths do not soften that. A path is a branch through which successive lives pass, not a life that
can be saved: dying on one costs exactly what dying always cost, and the only way to be rid of a path
is to abandon it, which drops everything it was carrying into the world for somebody else to find.
Nothing has ever crossed from one life into another here, and nothing crosses between paths either.
